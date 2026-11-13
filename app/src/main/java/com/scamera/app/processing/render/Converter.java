package com.scamera.app.processing.render;

import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.util.SparseIntArray;

import com.scamera.app.gallery.dng.DngColor;
import com.scamera.app.util.Log;

import java.util.Arrays;

/**
 * 3x3 colour matrix arithmetic of the development (row-major float[9]) and the DNG colour model of Camera2's two calibrated
 * illuminants: the illuminant interpolation that fits a neutral (the DNG specification's iteration over the correlated
 * colour temperature) and the camera -> XYZ (D50) transform at that interpolation. The products are evaluated in a fixed
 * order (float, left to right): the development's numbers depend on it.
 */
public class Converter {
    private static final String TAG = "Converter";

    /** XYZ (D50) to linear sRGB (Bradford-adapted). */
    public static final float[] sXYZtoSRGB = {
            3.1338561f, -1.6168667f, -0.4906146f,
            -0.9787684f, 1.9161415f, 0.0334540f,
            0.0719453f, -0.2289914f, 1.4052427f};
    /** ProPhoto (ROMM) RGB to XYZ (D50). */
    public static final float[] sProPhotoToXYZ = {
            0.797779f, 0.135213f, 0.031303f,
            0.288000f, 0.711900f, 0.000100f,
            0.000000f, 0.000000f, 0.825105f};
    /** XYZ (D50) to ProPhoto (ROMM) RGB. */
    public static final float[] sXYZtoProPhoto = {
            1.345753f, -0.255603f, -0.051025f,
            -0.544426f, 1.508096f, 0.020472f,
            0.000000f, 0.000000f, 1.211968f};
    /** The D50 white in XYZ. */
    private static final float[] D50 = {0.9642f, 1, 0.8249f};
    private static final int UNKNOWN = -1;
    /** Correlated colour temperatures (K) of the illuminant codes Camera2 names (CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_*). */
    private static final SparseIntArray KELVIN = new SparseIntArray();
    /** The interpolation search: steps at most, and the change of the factor that ends it. */
    private static final int SEARCH_STEPS = 30;
    private static final double SEARCH_TOLERANCE = 0.0001;
    /**
     * P440: the search used to log ~35 lines per shot (with the transform's 5) and pushed OPPO's per-process log quota over;
     * one summary line is logged ({@link #findDngInterpolationFactor}). True: every step. Only log lines depend on it.
     */
    private static final boolean DEBUG = false;

    static {
        final int[][] table = {
                {CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT, 6504},
                {CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D65, 6504},
                {CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D50, 5003},
                {CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D55, 5503},
                {CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D75, 7504},
                {CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_A, 2856},
                {CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_B, 4874},
                {CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_C, 6774},
                {CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT_FLUORESCENT, 6430},
                {CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_COOL_WHITE_FLUORESCENT, 4230},
                {CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_WHITE_FLUORESCENT, 3450}};
        for (int[] entry : table) KELVIN.append(entry[0], entry[1]);
    }

    // ---- matrix arithmetic --------------------------------------------------------------------------------------------------

    /** {@code out} = a x b (3x3). {@code out} must be another array than a and b. */
    public static void multiply(float[] a, float[] b, /*out*/float[] out) {
        for (int column = 0; column < 3; column++) {
            for (int row = 0; row < 3; row++) {
                final int r = row * 3;
                out[r + column] = a[r] * b[column] + a[r + 1] * b[3 + column] + a[r + 2] * b[6 + column];
            }
        }
    }

    /** {@code out} = m x v for a column vector v. */
    private static void apply(float[] m, float[] v, /*out*/float[] out) {
        for (int row = 0; row < 3; row++) out[row] = v[0] * m[row * 3] + v[1] * m[row * 3 + 1] + v[2] * m[row * 3 + 2];
    }

    /**
     * The inverse of {@code m} into {@code out} through its cofactors (in double); false for a matrix whose determinant is
     * below 1e-9 in magnitude.
     */
    static boolean invert(float[] m, /*out*/float[] out) {
        final double[] adjugate = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                // cofactor of element (j, i): the 2x2 minor of the rows and columns after them, cyclically
                final int r1 = (j + 1) % 3, r2 = (j + 2) % 3, c1 = (i + 1) % 3, c2 = (i + 2) % 3;
                adjugate[i * 3 + j] = (double) m[r1 * 3 + c1] * m[r2 * 3 + c2] - (double) m[r1 * 3 + c2] * m[r2 * 3 + c1];
            }
        }
        final double det = m[0] * adjugate[0] + m[1] * adjugate[3] + m[2] * adjugate[6];
        if (Math.abs(det) < 1e-9) return false;
        for (int k = 0; k < 9; k++) out[k] = (float) (adjugate[k] / det);
        return true;
    }

    /** {@code out} = a + (b - a) f per element, as (a (1 - f)) + (b f) in double. */
    private static void blend(float[] a, float[] b, double f, /*out*/float[] out) {
        final double keep = 1.0f - f;
        for (int k = 0; k < 9; k++) out[k] = (float) ((a[k] * keep) + (b[k] * f));
    }

    /** A Camera2 transform (getElement(column, row)) as a row-major matrix. */
    public static void convertColorspaceTransform(ColorSpaceTransform transform, /*out*/float[] out) {
        for (int row = 0; row < 3; row++)
            for (int column = 0; column < 3; column++) out[row * 3 + column] = transform.getElement(column, row).floatValue();
    }

    /** Scales a ForwardMatrix in place by rows so that camera white (1, 1, 1) lands on the D50 white. */
    public static void normalizeFM(/*inout*/float[] forward) {
        final float[] white = new float[3];
        apply(forward, new float[]{1, 1, 1}, white);
        final float[] unit = new float[9];
        multiply(new float[]{1.0f / white[0], 0, 0, 0, 1.0f / white[1], 0, 0, 0, 1.0f / white[2]}, forward, unit);
        multiply(new float[]{D50[0], 0, 0, 0, D50[1], 0, 0, 0, D50[2]}, unit, forward);
    }

    // ---- the DNG colour model -----------------------------------------------------------------------------------------------

    /**
     * Camera -> XYZ (D50) at illuminant interpolation {@code f}: ForwardMatrix(f) x D x CameraCalibration(f)^-1, where D maps
     * the neutral (taken back through the calibration) to the white of the forward matrices.
     */
    public static void calculateCameraToXYZD50Transform(float[] forward1, float[] forward2, float[] calibration1,
                                                        float[] calibration2, float[/*3*/] neutral, double f,
                                                        /*out*/float[] out) {
        final float[] calibration = new float[9];
        blend(calibration1, calibration2, f, calibration);
        final float[] calibrationInverse = new float[9];
        if (!invert(calibration, calibrationInverse))
            throw new IllegalArgumentException("Cannot invert interpolated calibration transform, input matrices are invalid.");
        final float[] reference = new float[3];
        apply(calibrationInverse, new float[]{neutral[0], neutral[1], neutral[2]}, reference);
        final float peak = Math.max(Math.max(reference[0], reference[1]), reference[2]);
        final float[] balance = {peak / reference[0], 0, 0, 0, peak / reference[1], 0, 0, 0, peak / reference[2]};
        final float[] forward = new float[9];
        blend(forward1, forward2, f, forward);
        final float[] balanced = new float[9];
        multiply(balance, calibrationInverse, balanced);
        multiply(forward, balanced, out);
        if (DEBUG) Log.d(TAG, "neutral " + Arrays.toString(neutral) + " reference " + Arrays.toString(reference) + " -> " + Arrays.toString(out));
    }

    /**
     * The interpolation between the two illuminants (1: the first one's matrices, 0: the second's) whose white is
     * {@code neutral}: start at 0.5; take the neutral to XYZ through the interpolated XYZ -> camera matrix, its correlated
     * colour temperature (McCamy) to a factor linear in 1 / CCT between the two illuminants, halfway between that and the
     * last factor; until it moves less than 1e-4 or 30 steps.
     */
    public static double findDngInterpolationFactor(int illuminant1, int illuminant2, float[] calibration1, float[] calibration2,
                                                    float[] colourMatrix1, float[] colourMatrix2, float[/*3*/] neutral) {
        // P370 (A5): a code the table lacks (3 tungsten, 10 cloudy, 24 ISO studio tungsten, ...) takes the DNG SDK's value;
        // only a code nobody knows throws (resolveColour catches it)
        final int kelvin1 = illuminantTemperature(illuminant1);
        if (kelvin1 == UNKNOWN) throw new IllegalArgumentException("No such illuminant for reference illuminant 1: " + illuminant1);
        final int kelvin2 = illuminantTemperature(illuminant2);
        if (kelvin2 == UNKNOWN) throw new IllegalArgumentException("No such illuminant for reference illuminant 2: " + illuminant2);
        final float[] toCamera1 = new float[9], toCamera2 = new float[9];
        multiply(calibration1, colourMatrix1, toCamera1);
        multiply(calibration2, colourMatrix2, toCamera2);
        final float[] cameraNeutral = {neutral[0], neutral[1], neutral[2]};
        final double low = Math.min(kelvin1, kelvin2), high = Math.max(kelvin1, kelvin2);
        final float[] toCamera = new float[9], toXyz = new float[9], xyz = new float[3];
        double factor = 0.5, change = Double.MAX_VALUE;
        int steps = 0;
        while (change > SEARCH_TOLERANCE && steps < SEARCH_STEPS) {
            blend(toCamera1, toCamera2, factor, toCamera);
            if (!invert(toCamera, toXyz))
                throw new IllegalArgumentException("Cannot invert XYZ to Camera matrix, input matrices are invalid."
                        + Arrays.toString(toCamera) + " " + Arrays.toString(toXyz));
            apply(toXyz, cameraNeutral, xyz);
            final double sum = (double) xyz[0] + xyz[1] + xyz[2];
            final double kelvin = mcCamy(xyz[0] / sum, xyz[1] / sum);
            double next = kelvin <= low ? 1 : kelvin >= high ? 0 : (1.0 / kelvin - 1.0 / high) / (1.0 / low - 1.0 / high);
            if (low == kelvin1) next = 1.0 - next;
            next = (next + factor) / 2;
            change = Math.abs(factor - next);
            factor = next;
            steps++;
            if (DEBUG) Log.d(TAG, "step " + steps + ": XYZ " + Arrays.toString(xyz) + ", " + kelvin + " K, factor " + factor);
        }
        if (steps == SEARCH_STEPS) {
            Log.w(TAG, "Could not converge on interpolation factor, using factor " + factor + " with remaining error factor of " + change);
        }
        Log.d(TAG, "DNG interpolation: illuminants " + kelvin1 + " / " + kelvin2 + " K, neutral " + Arrays.toString(cameraNeutral)
                + ", " + steps + " loops, factor " + factor);
        return factor;
    }

    /**
     * The temperature of an illuminant code: this table's (scam_dev "dng_white_spec 0" keeps the legacy estimate on it), else
     * the DNG SDK's; UNKNOWN for a code neither knows.
     */
    static int illuminantTemperature(int code) {
        final int own = KELVIN.get(code, UNKNOWN);
        if (own != UNKNOWN) return own;
        final double sdk = DngColor.illuminantTemperature(code, -1);
        return sdk > 0 ? (int) Math.round(sdk) : UNKNOWN;
    }

    /** McCamy's (1992) cubic approximation of the correlated colour temperature of chromaticity (x, y). */
    private static double mcCamy(double x, double y) {
        final double n = (x - 0.332) / (y - 0.1858);
        return -449 * Math.pow(n, 3) + 3525 * Math.pow(n, 2) - 6823.3 * n + 5520.33;
    }
}
