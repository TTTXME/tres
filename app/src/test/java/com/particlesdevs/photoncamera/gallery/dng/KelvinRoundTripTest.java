package com.particlesdevs.photoncamera.gallery.dng;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.RggbChannelVector;
import android.util.Rational;

import com.particlesdevs.photoncamera.circularbarlib.camera.ManualWhiteBalance;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * P581 (owner, 2026-11-09: «Перепроверь что наша камера в целом работает с CCM/CCT верно»): the colour temperature round trips.
 * <ul>
 * <li>CCT / Duv -> xy -> CCT / Duv on the Planckian locus (DngColor, Krystek), 2000 .. 12000 K, Duv -0.025 .. 0.025;</li>
 * <li>through the camera matrices: xy -> camera neutral -> the DNG SDK's xy iteration -> CCT / Duv (PHY110, X200 Ultra, X8 Ultra);</li>
 * <li>a hand-set kelvin (ManualWhiteBalance's gains, as the request gets them) -> the photo's white point (G / R, 1, G / B, as
 * Parameters reads an AWB-off result) -> the photo's colour temperature, and a grey card lit at that kelvin (its camera colour
 * the DNG model's neutral there) ends grey. Before P581 ManualWhiteBalance interpolated the HAL's raw ColorMatrix rationals
 * without the SDK's normalisation: on the X200 Ultra (CM2 x D50 = 1.13) a hand-set 5000 K was 5048 K in the photo and the grey
 * card R / G 1.0077.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class KelvinRoundTripTest {
    static ColorSpaceTransform cst(double[] m) {
        final Rational[] r = new Rational[9];
        for (int i = 0; i < 9; i++) r[i] = new Rational((int) Math.round(m[i] * 10000), 10000);
        return new ColorSpaceTransform(r);
    }

    /** Characteristics with the camera's colour tags (CM1 at D65, CM2 at A) and manual post-processing. */
    static CameraCharacteristics camera(double[][] m) {
        final CameraCharacteristics c = mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)).thenReturn(21);
        when(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)).thenReturn((byte) 17);
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)).thenReturn(cst(m[0]));
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)).thenReturn(cst(m[1]));
        when(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)).thenReturn(cst(m[2]));
        when(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)).thenReturn(cst(m[3]));
        when(c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)).thenReturn(new int[]{0, 1});
        when(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)).thenReturn(new int[]{2});
        return c;
    }

    /** The colour tags as the characteristics carry them (4 decimals, as cst rounds them). */
    static double[] rounded(double[] m) {
        final double[] out = new double[9];
        for (int i = 0; i < 9; i++) out[i] = Math.round(m[i] * 10000) / 10000.0;
        return out;
    }

    static final double[][] PHY110 = {DngWhiteTest.PHY_CM1, DngWhiteTest.PHY_CM2, DngWhiteTest.PHY_CC1, DngWhiteTest.PHY_CC2};
    static final double[][] X200U = {DngWhiteTest.X200U_CM1, DngWhiteTest.X200U_CM2, DngWhiteTest.X200U_CC1, DngWhiteTest.X200U_CC2};
    static final double[][] X8U = {DngWhiteTest.X8U_CM1, DngWhiteTest.X8U_CM2, DngWhiteTest.X8U_CC1, DngWhiteTest.X8U_CC2};

    static DngColor white(double[][] m) {
        return DngColor.forCamera(rounded(m[0]), rounded(m[1]), rounded(m[2]), rounded(m[3]), 21, 17);
    }

    @Test public void theLocusRoundTrips() {
        double dt = 0, dd = 0;
        for (double t = 2000; t <= 12000; t += 50)
            for (int k = -10; k <= 10; k++) {
                final double duv = 0.0025 * k;
                final double[] cd = DngColor.cctDuv(DngColor.xyFromCctDuv(t, duv));
                dt = Math.max(dt, Math.abs(cd[0] - t) / t);
                dd = Math.max(dd, Math.abs(cd[1] - duv));
            }
        assertTrue("relative CCT error " + dt, dt < 3e-4);   // measured 1.7e-4 (2 K at 12000 K)
        assertTrue("Duv error " + dd, dd < 1e-6);            // measured 3e-9
    }

    /** Duv > 0 is above the locus (green), as the editor's «Оттенок» and ColourLook's tint read it. */
    @Test public void positiveDuvIsGreen() {
        final double[] xy = DngColor.xyFromCctDuv(5000, 0.01), p = DngColor.xyFromCctDuv(5000, 0);
        assertTrue(xy[1] > p[1]);
        assertEquals(0.01, DngColor.cctDuv(xy)[1], 1e-6);
    }

    @Test public void theCameraMatricesRoundTrip() {
        for (double[][] m : new double[][][]{PHY110, X200U, X8U}) {
            final DngColor dc = white(m);
            double dt = 0, dd = 0;
            for (double t = 2000; t <= 12000; t += 100)
                for (int k = -4; k <= 4; k++) {
                    final double duv = 0.005 * k;
                    final double[] cd = DngColor.cctDuv(dc.xyForNeutral(dc.neutralForXy(DngColor.xyFromCctDuv(t, duv))));
                    dt = Math.max(dt, Math.abs(cd[0] - t));
                    dd = Math.max(dd, Math.abs(cd[1] - duv));
                }
            assertTrue("CCT error " + dt + " K", dt < 3);     // measured 1.5 K
            assertTrue("Duv error " + dd, dd < 1e-6);
        }
    }

    /** A hand-set kelvin is the photo's colour temperature and a grey card at that light ends grey. */
    @Test public void aHandSetKelvinIsThePhotosTemperature() {
        check(PHY110, 2000);
        check(X200U, 2000);
        // the X8U tags as the old log printed them (row-normalised: R 2.5 x G at 2000 K) need a blue gain above the request's 8 below
        // 2300 K - not a real neutral; the clamp is P581's open point
        check(X8U, 2300);
    }

    private static void check(double[][] m, int from) {
        final CameraCharacteristics c = camera(m);
        final DngColor dc = white(m);
        for (int k = from; k <= 10000; k += 100) {
            final RggbChannelVector g = ManualWhiteBalance.gains(c, k);
            final float green = 0.5f * (g.getGreenEven() + g.getGreenOdd());
            final double[] wp = {green / g.getRed(), 1, green / g.getBlue()};   // Parameters' AWB-off white point
            final double[] cd = DngColor.cctDuv(dc.xyForNeutral(wp));
            // the gains and the estimate on the same (Krystek) locus: within 2 K (Kang's fit before P581: 10 K)
            assertEquals("hand-set " + k + " K read as", k, cd[0], 3);
            assertTrue("hand-set " + k + " K: Duv " + cd[1], Math.abs(cd[1]) < 5e-5);
            // the card's light on the locus (before P581: up to 0.56 % on the PHY110 and 0.78 % on the X200 Ultra - Kang's fit 3.2e-4
            // off the locus in uv at 2500 K, the X200 Ultra's CM2 13 % larger than its CM1)
            final double[] card = dc.neutralForXy(DngColor.xyFromCctDuv(k, 0));
            assertEquals("grey card R/G at " + k + " K", 1, card[0] / wp[0], 1e-3);
            assertEquals("grey card B/G at " + k + " K", 1, card[2] / wp[2], 1e-3);
        }
    }
}
