package com.scamera.app.processing.yuv;

import android.app.Application;

import com.scamera.app.capture.YuvMergeRoute;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P701 (owner, 2026-11-14: the vivo X200 Pro front camera's YUV series, a washed-out photo without short frames): where the camera
 * applies the points of our capture curve. The bracket measures it in the merge (tools/check_yuv_merge.cpp checkCurveOverSrgb);
 * here the prior of a series without a bracket, its memory per firmware, the log of the curve the result reports and the decode
 * of the two models on the owner's numbers.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class YuvCurveModelTest {
    private static double srgbDecode(double e) {
        return e <= 0.04045 ? e / 12.92 : Math.pow((e + 0.055) / 1.055, 2.4);
    }

    /** The merge's decode through the points (Transfer::decodePoints, POINTS). */
    private static double decode(float[] pairs, float e) {
        if (e <= pairs[1]) return pairs[0];
        for (int i = 2; i < pairs.length; i += 2)
            if (e <= pairs[i + 1]) {
                final float span = pairs[i + 1] - pairs[i - 1];
                final float t = span > 0 ? (e - pairs[i - 1]) / span : 0f;
                return pairs[i - 2] + t * (pairs[i] - pairs[i - 2]);
            }
        return pairs[pairs.length - 2];
    }

    @Test public void thePriorIsTheForcedThenTheMeasuredThenTheSocs() {
        assertEquals(YuvCurveModel.OVER_SRGB, YuvCurveModel.prior(0, YuvCurveModel.UNKNOWN, true));
        assertEquals(YuvCurveModel.LINEAR, YuvCurveModel.prior(0, YuvCurveModel.UNKNOWN, false));
        // a bracket of this camera measured linear points: the MediaTek default does not override it
        assertEquals(YuvCurveModel.LINEAR, YuvCurveModel.prior(0, YuvCurveModel.LINEAR, true));
        assertEquals(YuvCurveModel.OVER_SRGB, YuvCurveModel.prior(0, YuvCurveModel.OVER_SRGB, false));
        // scam_dev yuv_curve_model 1 / 2 wins; any other number is «auto»
        assertEquals(YuvCurveModel.LINEAR, YuvCurveModel.prior(1, YuvCurveModel.OVER_SRGB, true));
        assertEquals(YuvCurveModel.OVER_SRGB, YuvCurveModel.prior(2, YuvCurveModel.LINEAR, false));
        assertEquals(YuvCurveModel.OVER_SRGB, YuvCurveModel.prior(7, YuvCurveModel.UNKNOWN, true));
    }

    @Test public void aMeasuredModelHoldsOnItsFirmwareOnly() {
        final String fp = "vivo/PD2405/PD2405:16/BP2A/compiler11141200:user/release-keys";
        assertEquals(YuvCurveModel.OVER_SRGB, YuvCurveModel.stored(YuvCurveModel.stamp(YuvCurveModel.OVER_SRGB, fp), fp));
        assertEquals(YuvCurveModel.LINEAR, YuvCurveModel.stored(YuvCurveModel.stamp(YuvCurveModel.LINEAR, fp), fp));
        assertEquals(YuvCurveModel.UNKNOWN, YuvCurveModel.stored(YuvCurveModel.stamp(YuvCurveModel.OVER_SRGB, fp), fp + "x"));
        assertEquals(YuvCurveModel.UNKNOWN, YuvCurveModel.stored(null, fp));
        assertEquals(YuvCurveModel.UNKNOWN, YuvCurveModel.stored("x|" + fp, fp));
        assertEquals(YuvCurveModel.UNKNOWN, YuvCurveModel.stored("5|" + fp, fp));
        assertEquals(YuvCurveModel.UNKNOWN, YuvCurveModel.stored(fp, fp));
    }

    @Test public void mediatekByTheSocOrTheHardware() {
        assertTrue(YuvCurveModel.mediatek("mt6989", null));
        assertTrue(YuvCurveModel.mediatek("qcom", "Mediatek"));
        assertTrue(YuvCurveModel.mediatek(null, "MediaTek"));
        assertFalse(YuvCurveModel.mediatek("qcom", "QTI"));
        assertFalse(YuvCurveModel.mediatek(null, null));
    }

    /**
     * The owner's series (scam_yuvburst-20261114-132722): the ES frames' Y quantiles against the N frames' (exposure x0.125) - the
     * decode through the points and sRGB's inverse gives the exposure ratio, the points alone ~its 0.47th power.
     */
    @Test public void theOwnersBracketFitsThePointsOverSrgb() {
        final float[] pairs = YuvMergeRoute.curvePairs(64);
        final int[][] quantiles = {{151, 90}, {181, 111}, {206, 132}, {222, 143}, {239, 154}};  // N, ES Y codes at 10/26/50/82/98 %
        final double ratio = 19987745.0 * 204 / (20000000.0 * 1631);
        for (int[] q : quantiles) {
            final double nAsked = decode(pairs, q[0] / 255f), eAsked = decode(pairs, q[1] / 255f);
            final double kAsked = Math.log(eAsked / nAsked) / Math.log(ratio);
            final double kOver = Math.log(srgbDecode(eAsked) / srgbDecode(nAsked)) / Math.log(ratio);
            assertTrue("as asked k " + kAsked, kAsked > 0.4 && kAsked < 0.6);
            assertEquals("over sRGB k", 1.0, kOver, 0.06);
        }
    }

    @Test public void theReportedCurveIsSampledInTheLog() {
        final float[] asked = YuvMergeRoute.curvePairs(64);
        final float[] reported = new float[202];
        for (int i = 0; i <= 100; i++) {
            reported[2 * i] = i / 100f;
            reported[2 * i + 1] = i / 100f;  // a camera that reports the identity
        }
        final String line = YuvBurst.curveReport(asked, reported);
        assertTrue(line, line.contains("the result reports 101 points"));
        assertTrue(line, line.contains("0.18: 0.461/0.180"));
        assertEquals(0.5f, YuvBurst.at(reported, 0.5f), 1e-6f);
        assertEquals(0f, YuvBurst.at(reported, -1f), 0f);
        assertEquals(1f, YuvBurst.at(reported, 2f), 0f);
    }

    @Test public void theShadowToeAndTheMetersFactor() {
        final float[] t = YuvBurst.toe();
        assertEquals(YuvBurst.TOE, t[0], 0f);
        assertEquals(YuvBurst.TOE_CAP, t[1], 0f);
        // the owner's series: the toe raised the scene's geometric mean 0.2597 -> 0.2781
        assertEquals(0.2781f / 0.2597f, YuvBurst.aeScale(0.2597f, 0.2781f), 1e-6f);
        assertEquals(1f, YuvBurst.aeScale(0f, 0.3f), 0f);
        assertEquals(1f, YuvBurst.aeScale(0.3f, Float.NaN), 0f);
        assertEquals(2f, YuvBurst.aeScale(0.1f, 0.9f), 0f);
        assertTrue(YuvBurst.aeScale(0.3f, 0.3f) > 0f);  // a YUV shot is marked even without a toe
    }

    @Test public void theChromaFactorDefaultsToTheCalibratedOne() {
        assertEquals(0.8f, YuvBurst.CHROMA, 0f);
        final float k = YuvBurst.chroma();
        assertTrue("chroma " + k, k >= 0.5f && k <= 1.5f);
    }
}
