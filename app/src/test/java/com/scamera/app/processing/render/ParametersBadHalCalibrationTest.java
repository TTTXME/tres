package com.scamera.app.processing.render;

import static com.scamera.app.processing.render.ParametersSceneCctTest.IDENTITY;
import static com.scamera.app.processing.render.ParametersSceneCctTest.cst;
import static com.scamera.app.processing.render.ParametersSceneCctTest.result;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.util.Rational;

import com.scamera.app.capture.CaptureController;
import com.scamera.app.processing.color.PhotoMatrix;
import com.scamera.app.settings.PreferenceKeys;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * P580 (CCM audit): a HAL that reports its missing second calibration as zeros (or as a rational over 0), or an unknown
 * illuminant (code 0), renders with the first calibration alone - exactly the photo of a HAL without the *2 keys, as the DNG SDK
 * renders such a profile. Before: a zero ColorMatrix2 failed the white estimate, the daylight weight then chose the zero
 * calibration on a camera whose second illuminant is D65 (the Pixel order: 17 / 21), its CameraCalibration2 of zeros could not be
 * inverted and the shot fell to IDENTITY_FALLBACK (camera RGB as sRGB); an illuminant 0 was interpolated as 6500 K.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ParametersBadHalCalibrationTest {
    private MockedStatic<com.scamera.app.app.ScameraApp> app;
    private MockedStatic<PreferenceKeys> prefsKeys;
    private CameraCharacteristics previous;
    private static final ColorSpaceTransform ZERO = cst(1, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    private static final ColorSpaceTransform FM1 = cst(128, 70, 40, 14, 30, 90, 8, 4, 20, 82);
    private static final ColorSpaceTransform FM2 = cst(128, 72, 38, 14, 31, 89, 8, 5, 22, 80);
    // the PHY110 camera 2 calibrations in the Pixel order: 1 = standard A, 2 = D65
    private static final ColorSpaceTransform CM_A = cst(10000, 6892, 196, -3223, -7131, 18376, -1520, -884, 2258, 6427);
    private static final ColorSpaceTransform CM_D65 = cst(10000, 6178, -1002, -970, -4729, 14118, 345, -861, 2593, 4441);
    private static final ColorSpaceTransform CC_A = cst(128, 127, 0, 0, 0, 128, 0, 0, 0, 128);
    private static final ColorSpaceTransform CC_D65 = cst(128, 127, 0, 0, 0, 128, 0, 0, 0, 130);

    @Before public void setUp() {
        final com.scamera.app.api.Settings settings = new com.scamera.app.api.Settings();
        settings.colourMethod = 1;
        app = mockStatic(com.scamera.app.app.ScameraApp.class);
        app.when(com.scamera.app.app.ScameraApp::getSettings).thenReturn(settings);
        prefsKeys = mockStatic(PreferenceKeys.class, invocation -> {
            final String name = invocation.getMethod().getName();
            if (name.equals("isScamEnabled")) return true;
            if (name.equals("routeInternalValue")) return 1f;
            return invocation.callRealMethod();
        });
        previous = CaptureController.mCameraCharacteristics;
        final CameraCharacteristics c = ParametersSceneCctTest.phy110();
        when(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)).thenReturn(17);
        when(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)).thenReturn((byte) 21);
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)).thenReturn(CM_A);
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)).thenReturn(CM_D65);
        when(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)).thenReturn(CC_A);
        when(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)).thenReturn(CC_D65);
        when(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)).thenReturn(FM1);
        when(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)).thenReturn(FM2);
        CaptureController.mCameraCharacteristics = c;
    }

    @After public void tearDown() {
        CaptureController.mCameraCharacteristics = previous;
        prefsKeys.close();
        app.close();
    }

    private static Parameters shot(float[] n) {
        final Parameters p = new Parameters();
        p.physicalID = 2;
        p.resolveColour(false, result(IDENTITY, n));
        return p;
    }

    private static void second(ColorSpaceTransform cm, ColorSpaceTransform cc, ColorSpaceTransform fm, Byte illuminant) {
        final CameraCharacteristics c = CaptureController.mCameraCharacteristics;
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)).thenReturn(cm);
        when(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)).thenReturn(cc);
        when(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)).thenReturn(fm);
        when(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)).thenReturn(illuminant);
    }

    /** The photo of a HAL without the *2 keys (the reference each bad report must equal). */
    private static Parameters single(float[] n) {
        second(null, null, null, null);
        return shot(n);
    }

    private static void assertSameColour(String what, Parameters want, Parameters got) {
        assertEquals(what + ": route", want.colourSource, got.colourSource);
        for (int i = 0; i < 9; i++) {
            assertTrue(what + ": finite", Float.isFinite(got.cameraToProPhoto[i]));
            assertEquals(what + ": sensorToProPhoto " + i, want.cameraToProPhoto[i], got.cameraToProPhoto[i], 1e-6f);
            assertEquals(what + ": CCT " + i, want.colorTransform.matrix[i], got.colorTransform.matrix[i], 1e-6f);
        }
        assertEquals(what + ": scene light", want.sceneCct, got.sceneCct, 1f);
    }

    /** Neutral -> white: the channels of CCT . sensorToProPhoto . n agree within 1e-3. */
    private static void assertWhite(String what, Parameters p) {
        final float[] m = new float[9];
        Converter.multiply(p.colorTransform.matrix, p.cameraToProPhoto, m);
        final float[] w = new float[3];
        for (int r = 0; r < 3; r++) w[r] = m[r * 3] * p.asShotNeutral[0] + m[r * 3 + 1] * p.asShotNeutral[1] + m[r * 3 + 2] * p.asShotNeutral[2];
        assertEquals(what + " white R/G", 1f, w[0] / w[1], 1e-3f);
        assertEquals(what + " white B/G", 1f, w[2] / w[1], 1e-3f);
    }

    @Test public void aZeroSecondCalibrationIsNone() {
        for (double t : new double[]{2856, 4000, 6504}) {
            final float[] n = ParametersSceneCctTest.neutralAt(t);
            final Parameters want = single(n);
            assertEquals("DNG_FM", want.colourSource);
            assertWhite("single " + t, want);
            second(ZERO, ZERO, ZERO, (byte) 21);
            final Parameters got = shot(n);
            assertSameColour("zero calibration 2 at " + t + " K", want, got);
            assertEquals("the DNG's second illuminant follows the profile", 17, got.refIlluminant2);
        }
    }

    @Test public void anUnknownSecondIlluminantIsASingleCalibration() {
        final float[] n = ParametersSceneCctTest.neutralAt(6504);
        final Parameters want = single(n);
        second(CM_D65, CC_D65, FM2, (byte) 0);
        assertSameColour("illuminant 2 = 0", want, shot(n));
    }

    @Test public void aRationalOverZeroIsNoMatrix() {
        final float[] n = ParametersSceneCctTest.neutralAt(4000);
        final Parameters want = single(n);
        final Rational[] r = new Rational[9];
        for (int i = 0; i < 9; i++) r[i] = new Rational(i % 4 == 0 ? 1 : 0, 1);
        r[4] = new Rational(1, 0);
        final ColorSpaceTransform nan = new ColorSpaceTransform(r);
        assertFalse(PhotoMatrix.usableMatrix(nan));
        assertFalse(PhotoMatrix.usableMatrix(ZERO));
        assertTrue(PhotoMatrix.usableMatrix(IDENTITY));
        second(nan, nan, nan, (byte) 21);
        assertSameColour("non-finite calibration 2", want, shot(n));
    }

    /** The two calibrations as reported still interpolate (the fix only touches bad reports). */
    @Test public void aRealSecondCalibrationStillInterpolates() {
        final float[] n = ParametersSceneCctTest.neutralAt(6504);
        final Parameters dual = shot(n);
        assertEquals("DNG_FM", dual.colourSource);
        assertWhite("dual", dual);
        assertEquals(21, dual.refIlluminant2);
        assertEquals("D65 light read", 6504, dual.sceneCct, 6504 * 0.015);
    }

    /** The viewfinder's scene light (PhotoMatrix.dngWhite) takes the same single calibration. */
    @Test public void theViewfinderEstimateFollows() {
        final CameraCharacteristics c = CaptureController.mCameraCharacteristics;
        second(null, null, null, null);
        final com.scamera.app.gallery.dng.DngColor want = PhotoMatrix.dngWhite(c);
        second(ZERO, ZERO, ZERO, (byte) 21);
        final com.scamera.app.gallery.dng.DngColor got = PhotoMatrix.dngWhite(c);
        assertNotNull(got);
        assertTrue("usable", got.usable());
        final double[] nd = {0.6, 1, 0.5};
        final double[] a = want.xyForNeutral(nd), b = got.xyForNeutral(nd);
        assertEquals(a[0], b[0], 1e-9);
        assertEquals(a[1], b[1], 1e-9);
    }
}
