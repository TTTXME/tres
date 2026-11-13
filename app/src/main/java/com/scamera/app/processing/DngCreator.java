package com.scamera.app.processing;

import android.os.Build;

import com.scamera.app.processing.color.DngColour;
import com.scamera.app.processing.render.Parameters;
import com.scamera.app.util.DeviceName;
import com.scamera.app.util.Log;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * One DNG file of a shot, built by the native writer (library dngCreator, dngCreator.cpp): {@link #setParameters} hands it
 * the shot's metadata, {@link #writeBuffer} lays out the file around the 16-bit mosaic and streams it out, {@link #close}
 * releases the native side. A writer is meant for one file.
 *
 * <p>P214b / P214c: the Software tag is this APK's (DngMetadata.software), not a library's BuildConfig.
 */
public class DngCreator {
    private static final String LOG = "DngCreator";
    /** P27: the file goes out in pieces of this size instead of one heap copy (100 MB at 50 MP uncompressed). */
    private static final int STREAM_PIECE = 4 << 20;

    // Field ids of the native setters (dngCreator.cpp NumberField / VectorField / TextField / TagKind)
    private static final int NUMBER_ORIENTATION = 0, NUMBER_WHITE = 1, NUMBER_BASELINE = 2, NUMBER_ILLUMINANT1 = 3,
            NUMBER_ILLUMINANT2 = 4, NUMBER_ISO = 5, NUMBER_EXPOSURE = 6, NUMBER_APERTURE = 7, NUMBER_FOCAL = 8, NUMBER_CFA = 9,
            NUMBER_BLOCK = 10, NUMBER_LOSSLESS = 11;
    private static final int VECTOR_COLOUR1 = 0, VECTOR_COLOUR2 = 1, VECTOR_FORWARD1 = 2, VECTOR_FORWARD2 = 3,
            VECTOR_CALIBRATION1 = 4, VECTOR_CALIBRATION2 = 5, VECTOR_NEUTRAL = 6, VECTOR_NOISE = 7;
    private static final int TEXT_UNIQUE_MODEL = 0, TEXT_DESCRIPTION = 1, TEXT_SOFTWARE = 2, TEXT_MAKE = 3, TEXT_MODEL = 4,
            TEXT_DATE_TIME = 5;
    private static final int KIND_ASCII = 0, KIND_SHORTS = 1, KIND_LONGS = 2, KIND_RATIONALS = 3;
    /** Camera2's CFA arrangements the writer knows (SENSOR_INFO_COLOR_FILTER_ARRANGEMENT RGGB .. BGGR). */
    private static final int CFA_FIRST = 0, CFA_LAST = 3;
    /** EXIF Orientation of the sensor rotation in quarter turns (0, 90, 180, 270 degrees); 0 for any other step. */
    private static final int[] ORIENTATION_OF_QUARTER = {1, 6, 3, 8};

    private static native long nOpen();
    private static native void nClose(long file);
    private static native void nNumber(long file, int field, double value);
    private static native void nVector(long file, int field, double[] values);
    private static native void nText(long file, int field, String value);
    private static native void nBlack(long file, short[] levels);
    private static native void nGainMap(long file, float[] map, int xmin, int ymin, int xmax, int ymax, int width, int height);
    private static native void nTag(long file, int ifd, int tag, int kind, String text, long[] words);
    private static native void nCrop(long file, int x, int y, int width, int height);
    private static native ByteBuffer nBuild(long file, int width, int height, ByteBuffer mosaic);
    /** P187: frees a buffer of nBuild (native memory); it must not be touched afterwards. */
    private static native void nDrop(ByteBuffer built);

    static {
        System.loadLibrary("dngCreator");
    }

    private long file;

    public DngCreator() {
        file = nOpen();
        if (file == 0) throw new RuntimeException("DNG writer not created");
    }

    private static double[] checked(double[] values, int length, String what) {
        if (values.length != length) throw new IllegalArgumentException(what + " needs " + length + " values, not " + values.length);
        return values;
    }

    /** The EXIF orientation of the sensor's rotation step (0 .. 3 quarter turns; 4 .. 8 are accepted and written as 0). */
    public void setOrientation(int quarterTurns) {
        if (quarterTurns < 0 || quarterTurns > 8) throw new IllegalArgumentException("orientation step " + quarterTurns + " outside 0..8");
        nNumber(file, NUMBER_ORIENTATION, quarterTurns < ORIENTATION_OF_QUARTER.length ? ORIENTATION_OF_QUARTER[quarterTurns] : 0);
    }

    public void setWhiteLevel(double white) { nNumber(file, NUMBER_WHITE, white); }

    /** P165: BaselineExposure (EV) of a merged DNG stored over its headroom (+log2 of the headroom); 0 writes no tag. */
    public void setBaselineExposure(double ev) { nNumber(file, NUMBER_BASELINE, ev); }

    public void setBlackLevel(short[] levels) {
        if (levels.length != 4) throw new IllegalArgumentException("4 black levels (one per 2x2 site), not " + levels.length);
        nBlack(file, levels);
    }

    public void setColorMatrix1(double[] m) { nVector(file, VECTOR_COLOUR1, checked(m, 9, "ColorMatrix1")); }
    public void setColorMatrix2(double[] m) { nVector(file, VECTOR_COLOUR2, checked(m, 9, "ColorMatrix2")); }
    public void setForwardMatrix1(double[] m) { nVector(file, VECTOR_FORWARD1, checked(m, 9, "ForwardMatrix1")); }
    public void setForwardMatrix2(double[] m) { nVector(file, VECTOR_FORWARD2, checked(m, 9, "ForwardMatrix2")); }
    public void setCameraCalibration1(double[] m) { nVector(file, VECTOR_CALIBRATION1, checked(m, 9, "CameraCalibration1")); }
    public void setCameraCalibration2(double[] m) { nVector(file, VECTOR_CALIBRATION2, checked(m, 9, "CameraCalibration2")); }
    public void setAsShotNeutral(double[] rgb) { nVector(file, VECTOR_NEUTRAL, checked(rgb, 3, "AsShotNeutral")); }

    /** EXIF LightSource codes (17 standard A, 21 D65, ...). */
    public void setCalibrationIlluminant1(short code) { nNumber(file, NUMBER_ILLUMINANT1, code); }
    public void setCalibrationIlluminant2(short code) { nNumber(file, NUMBER_ILLUMINANT2, code); }

    public void setUniqueCameraModel(String name) { nText(file, TEXT_UNIQUE_MODEL, name); }
    public void setDescription(String text) { nText(file, TEXT_DESCRIPTION, text); }
    public void setSoftware(String text) { nText(file, TEXT_SOFTWARE, text); }
    public void setMake(String text) { nText(file, TEXT_MAKE, text); }
    public void setModel(String text) { nText(file, TEXT_MODEL, text); }

    // The TIFF/EP exposure tags of IFD 0 (P618: setParameters writes these in the Exif IFD through ShotExif instead)
    public void setIso(short iso) { nNumber(file, NUMBER_ISO, iso); }
    public void setExposureTime(double seconds) { nNumber(file, NUMBER_EXPOSURE, seconds); }
    public void setAperture(double fNumber) { nNumber(file, NUMBER_APERTURE, fNumber); }
    public void setFocalLength(double millimetres) { nNumber(file, NUMBER_FOCAL, millimetres); }

    /** DateTime as EXIF writes it, "YYYY:MM:DD HH:MM:SS". */
    public void setDateTime(String stamp) {
        final boolean shaped = stamp != null && stamp.length() == 19 && stamp.charAt(4) == ':' && stamp.charAt(7) == ':'
                && stamp.charAt(10) == ' ' && stamp.charAt(13) == ':' && stamp.charAt(16) == ':';
        if (!shaped) throw new IllegalArgumentException("DateTime not in the form YYYY:MM:DD HH:MM:SS: " + stamp);
        nText(file, TEXT_DATE_TIME, stamp);
    }

    /** NoiseProfile: (scale, offset) per colour plane, 1 to 4 planes. */
    public void setNoiseProfile(double[] pairs) {
        if (pairs == null || pairs.length == 0 || pairs.length % 2 != 0 || pairs.length > 8)
            throw new IllegalArgumentException("NoiseProfile needs 1..4 (scale, offset) pairs: " + Arrays.toString(pairs));
        nVector(file, VECTOR_NOISE, pairs);
    }

    /** True: lossless JPEG (LJ92) when the mosaic allows it; false: the samples uncompressed. */
    public void setCompression(boolean lossless) { nNumber(file, NUMBER_LOSSLESS, lossless ? 1 : 0); }

    /** P618: a ShotExif tag (IFD 0 or the Exif IFD); a value the writer refuses is logged there, not written. */
    public void setTag(ShotExif.Tag t) {
        if (t.ascii != null) nTag(file, t.ifd, t.tag, KIND_ASCII, t.ascii, null);
        else if (t.shorts != null) nTag(file, t.ifd, t.tag, KIND_SHORTS, null, widen(t.shorts));
        else if (t.longs != null) nTag(file, t.ifd, t.tag, KIND_LONGS, null, t.longs);
        else if (t.rationals != null) nTag(file, t.ifd, t.tag, KIND_RATIONALS, null, t.rationals);
    }

    private static long[] widen(int[] values) {
        final long[] out = new long[values.length];
        for (int i = 0; i < values.length; i++) out[i] = values[i];
        return out;
    }

    /** P618: DefaultCropOrigin / DefaultCropSize in the stored image's pixels (a raw converter's frame); a zero size: none. */
    public void setDefaultCrop(int x, int y, int width, int height) { nCrop(file, x, y, width, height); }

    /** Camera2's colour filter arrangement (0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR). */
    public void setCFAPattern(int arrangement) {
        if (arrangement < CFA_FIRST || arrangement > CFA_LAST) throw new IllegalArgumentException("CFA arrangement " + arrangement + " unknown");
        nNumber(file, NUMBER_CFA, arrangement);
    }

    /**
     * P318: the colour block of the stored mosaic - 2 Quad, 4 Tetra (any other value: Bayer). The file then declares the
     * CFA order over the blocks (CFARepeatPatternDim 2b x 2b), the black level and the lens-shading GainMap by block too.
     * Before {@link #setGainMap}, which places its maps by it.
     */
    public void setMosaicBlock(int block) { nNumber(file, NUMBER_BLOCK, block); }

    /** Camera2's lens shading map (width x height points of 4 channels) over the stored area (xmin, ymin) .. (xmax, ymax). */
    public void setGainMap(float[] map, int xmin, int ymin, int xmax, int ymax, int width, int height) {
        if (map.length < 4) throw new IllegalArgumentException("a GainMap needs at least one point of 4 channels");
        nGainMap(file, map, xmin, ymin, xmax, ymax, width, height);
    }

    /** Builds the file around {@code mosaic} (a direct buffer of width x height 16-bit samples) and streams it to {@code out}. */
    public void writeBuffer(OutputStream out, ByteBuffer mosaic, int width, int height) {
        final ByteBuffer built = nBuild(file, width, height, mosaic);
        if (built == null) throw new RuntimeException("DNG layout failed");
        try {
            final ByteBuffer view = built.duplicate();
            view.clear();
            final byte[] piece = new byte[Math.min(STREAM_PIECE, Math.max(1, view.remaining()))];
            for (int n; (n = Math.min(piece.length, view.remaining())) > 0; ) {
                view.get(piece, 0, n);
                out.write(piece, 0, n);
            }
        } catch (java.io.IOException | RuntimeException e) {
            throw new RuntimeException("DNG not written to the stream", e);
        } finally {
            // P187: the whole file in native memory (401 MB at 200 MP) goes now, not never
            nDrop(built);
        }
    }

    /** P370 (review): a finite, non-zero, invertible colour matrix (Parameters.dngColourMatrixUsable); logged when it is not. */
    static boolean colourMatrix(String tag, float[] values) {
        if (!finite(tag, values)) return false;
        if (Parameters.dngColourMatrixUsable(values)) return true;
        Log.e(LOG, "DNG: " + tag + " all zero or singular, not written: " + Arrays.toString(values));
        return false;
    }

    /** P215: every value finite (the native writer would drop a tag with a NaN / infinity silently); logged otherwise. */
    static boolean finite(String tag, float[] values) {
        if (values == null) {
            Log.e(LOG, "DNG: " + tag + " missing");
            return false;
        }
        for (float v : values) {
            if (Float.isFinite(v)) continue;
            Log.e(LOG, "DNG: " + tag + " not finite: " + Arrays.toString(values));
            return false;
        }
        return true;
    }

    /** P215: a neutral with a zero or negative channel is no white balance (a raw converter would divide by it). */
    static boolean positive(float[] values) {
        for (float v : values) if (!(v > 0f)) return false;
        return true;
    }

    double[] toDouble(float[] values) {
        final double[] wide = new double[values.length];
        for (int i = 0; i < values.length; i++) wide[i] = values[i];
        return wide;
    }

    /** The NoiseProfile of the shot: the DNG writer's own (P214c), else the noise model's R, G, B (scale, offset) or none. */
    private static double[] noiseProfile(Parameters p) {
        if (p.dngNoiseProfile != null && p.dngNoiseProfile.length == 6) return p.dngNoiseProfile;
        if (p.noiseModel == null || p.noiseModel.rawModel == null) return null;
        final double[] pairs = new double[6];
        for (int plane = 0; plane < 3; plane++) {
            pairs[2 * plane] = p.noiseModel.stackModel[plane].first;
            pairs[2 * plane + 1] = p.noiseModel.stackModel[plane].second;
        }
        return pairs;
    }

    /**
     * Everything the file says about the shot. The order is the file's: the native writer stores each value as it comes, and
     * the unit tests (DngCreatorExifTagsTest, DngCreatorColourTagsTest) read the names and time before the colour.
     */
    public void setParameters(Parameters p) {
        setMosaicBlock(p.dngMosaicBlock); // P318: before the GainMap below
        // P618: what the file holds (ShotExif.dngDescription), and this APK as the Software (P214c)
        setDescription(ShotExif.dngDescription(p));
        setSoftware(DngMetadata.software());

        // P618: the shot's time (when it was named, as the photo's EXIF) with its milliseconds and zone in the Exif IFD, the
        // lens and the exposure (ShotExif); the photo's 4:3 crop of an ISZ / native-mosaic frame as the raw converter's frame
        final ShotExif.Facts facts = ShotExif.of(p, p.dngFieldZoom, DeviceName.make(), DeviceName.model());
        setDateTime(facts.dateTime());
        for (ShotExif.Tag t : ShotExif.dngTags(facts)) setTag(t);
        if (p.photoAspect4x3 && p.rawSize != null) {
            final int[] crop = ShotExif.aspectCrop(p.rawSize.x, p.rawSize.y);
            if (crop != null) setDefaultCrop(crop[0], crop[1], crop[2], crop[3]);
        }
        final double[] noise = noiseProfile(p);
        if (noise != null) setNoiseProfile(noise);

        // P196 / P618: Make / Model as the shot's other files (DeviceName); UniqueCameraModel is the build model and the
        // physical camera (the colour profile's identity). ExposureTime, FNumber, ISO and FocalLength are in the Exif IFD only.
        setMake(facts.make);
        setModel(DeviceName.model());
        setUniqueCameraModel(ShotExif.uniqueCameraModel(facts.make, Build.MODEL, p.physicalID));

        final short[] black = new short[4];
        for (int i = 0; i < 4; i++) black[i] = (short) p.cfaBlack[i];
        setBlackLevel(black);
        setWhiteLevel(p.whiteLevel);
        if (p.dngBaselineExposure != 0f) setBaselineExposure(p.dngBaselineExposure);
        writeColour(p);
        setCFAPattern(p.baseCfaPattern);
        setOrientation(p.rotationDeg / 90);
        // the stored image as (xmin, ymin, xmax, ymax) (P118: not the active array's rectangle)
        setGainMap(p.shadingMap, 0, 0, p.rawSize.x, p.rawSize.y, p.shadingGrid.x, p.shadingGrid.y);
    }

    /**
     * The colour: P243, the JPEG's own profile (DngColour: one ColorMatrix / ForwardMatrix, the illuminant twice) when the
     * render made one; else the camera's dual profile, every matrix checked (P215: a non-finite one is logged, not dropped
     * silently; P370: no all-zero or singular ColorMatrix, no sRGB -> XYZ placeholder ForwardMatrix).
     */
    private void writeColour(Parameters p) {
        final DngColour.Profile jpeg = p.dngColour;
        setCalibrationIlluminant1((short) (jpeg != null ? jpeg.illuminant : p.refIlluminant1));
        setCalibrationIlluminant2((short) (jpeg != null ? jpeg.illuminant : p.refIlluminant2));
        final boolean forward = jpeg == null && p.dngForwardMatrices();
        if (jpeg != null) {
            setColorMatrix1(toDouble(jpeg.colorMatrix1));
            setForwardMatrix1(toDouble(jpeg.forwardMatrix1));
            Log.i(LOG, "DNG: the JPEG's colour: " + jpeg);
        } else {
            if (colourMatrix("ColorMatrix1", p.colorMatrix1)) setColorMatrix1(toDouble(p.colorMatrix1));
            if (colourMatrix("ColorMatrix2", p.colorMatrix2)) setColorMatrix2(toDouble(p.colorMatrix2));
            if (!forward) Log.i(LOG, "DNG: the forward matrices are the sRGB -> XYZ placeholder: not written (the ColorMatrix renders)");
            if (forward && finite("ForwardMatrix1", p.forwardMatrix1)) setForwardMatrix1(toDouble(p.forwardMatrix1));
            if (forward && finite("ForwardMatrix2", p.forwardMatrix2)) setForwardMatrix2(toDouble(p.forwardMatrix2));
            if (finite("CameraCalibration1", p.cameraCalibration1)) setCameraCalibration1(toDouble(p.cameraCalibration1));
            if (finite("CameraCalibration2", p.cameraCalibration2)) setCameraCalibration2(toDouble(p.cameraCalibration2));
        }
        final boolean neutral = finite("AsShotNeutral", p.asShotNeutral) && positive(p.asShotNeutral);
        if (neutral) setAsShotNeutral(toDouble(p.asShotNeutral));
        else Log.e(LOG, "DNG: AsShotNeutral not written: " + Arrays.toString(p.asShotNeutral));
        // P370 (B1): the ForwardMatrix1 the file holds
        Log.i(LOG, "DNG: AsShotNeutral " + Arrays.toString(p.asShotNeutral) + " ForwardMatrix1 "
                + (jpeg != null ? Arrays.toString(jpeg.forwardMatrix1) + " (the JPEG's colour)"
                : (forward ? Arrays.toString(p.forwardMatrix1) : "none") + " (the camera's profile)"));
    }

    /** Releases the native writer (and the metadata it holds); safe to call twice. */
    public void close() {
        if (file != 0) {
            nClose(file);
            file = 0;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }
}
