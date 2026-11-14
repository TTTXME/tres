package com.scamera.app.processing.yuv;

import android.app.Application;

import com.scamera.app.capture.YuvMergeRoute;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * P691 (owner, 2026-11-14: «Судя по всему тонмапинг не работает нормально с YUV»): the capture curve of the YUV route is the
 * same curve whether the camera honours the points' inputs or takes their outputs at evenly spaced inputs (the vivo X200 Ultra:
 * the curve before P691, inputs (i / 63)^2.2, came out ~linear there and decoded to 1/25 of the scene's light). The decode below
 * is the merge's (scam-yuv-merge.h Transfer POINTS: the points' inverse, piecewise linear).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class YuvCaptureCurveTest {
    private static float srgb(double x) {
        return (float) (x <= 0.0031308 ? 12.92 * x : 1.055 * Math.pow(x, 1 / 2.4) - 0.055);
    }

    /** A camera that honours the inputs: the curve through the (in, out) pairs. */
    private static float honoured(float[] pairs, float x) {
        for (int i = 2; i < pairs.length; i += 2)
            if (x <= pairs[i]) return pairs[i - 1] + (x - pairs[i - 2]) / (pairs[i] - pairs[i - 2]) * (pairs[i + 1] - pairs[i - 1]);
        return pairs[pairs.length - 1];
    }

    /** A camera that takes the outputs at evenly spaced inputs (its gamma table indexed by the point's number). */
    private static float evenInputs(float[] pairs, float x) {
        final int n = pairs.length / 2;
        final float p = Math.max(0f, Math.min(1f, x)) * (n - 1);
        final int i = Math.min(n - 2, (int) p);
        return pairs[2 * i + 1] + (p - i) * (pairs[2 * i + 3] - pairs[2 * i + 1]);
    }

    /** The merge's decode: the inverse of the points (Transfer::decodeExact, POINTS). */
    private static float decode(float[] pairs, float e) {
        if (e <= pairs[1]) return pairs[0];
        for (int i = 2; i < pairs.length; i += 2)
            if (e <= pairs[i + 1]) {
                final float span = pairs[i + 1] - pairs[i - 1];
                final float t = span > 0 ? (e - pairs[i - 1]) / span : 0f;
                return pairs[i - 2] + t * (pairs[i] - pairs[i - 2]);
            }
        return pairs[pairs.length - 2];
    }

    @Test public void theCurveHasEvenlySpacedInputsOfTheSrgbCurve() {
        for (int n : new int[]{2, 33, 64}) {
            final float[] pairs = YuvMergeRoute.curvePairs(n);
            assertEquals(2 * n, pairs.length);
            for (int i = 0; i < n; i++) {
                assertEquals((float) i / (n - 1), pairs[2 * i], 1e-7f);
                assertEquals(srgb(pairs[2 * i]), pairs[2 * i + 1], 1e-6f);
            }
        }
        assertEquals(128, YuvMergeRoute.curvePairs(101).length);
    }

    @Test public void bothCamerasApplyTheSameCurveAndTheMergeDecodesTheLight() {
        final float[] pairs = YuvMergeRoute.curvePairs(64);
        for (int k = 0; k <= 1000; k++) {
            final float x = k / 1000f;
            final float a = honoured(pairs, x), b = evenInputs(pairs, x);
            assertEquals("x " + x, a, b, 1e-6f);
            // 8-bit quantisation of the encoded value, then the merge's decode
            final float e = Math.round(b * 255f) / 255f;
            final float back = decode(pairs, e);
            final float slope = 1f / Math.max(1e-6f, (honoured(pairs, Math.min(1f, x + 1e-3f)) - honoured(pairs, Math.max(0f, x - 1e-3f))) / 2e-3f);
            assertEquals("x " + x, x, back, 0.51f / 255f * slope + 1e-6f);
        }
        // the scene's median of the owner's shot (0.064 of the white) decodes to itself
        assertEquals(0.064f, decode(pairs, evenInputs(pairs, 0.064f)), 2e-4f);
    }

    @Test public void theCurveBeforeP691DecodedTheEvenInputsCameraAtOneTwentyFifth() {
        // the regression the fix removes: inputs (i / 63)^2.2 on the camera that takes the outputs at evenly spaced inputs
        final float[] dense = new float[128];
        for (int i = 0; i < 64; i++) {
            dense[2 * i] = (float) Math.pow(i / 63.0, 2.2);
            dense[2 * i + 1] = srgb(dense[2 * i]);
        }
        final float median = decode(dense, evenInputs(dense, 0.064f));
        assertTrue("decoded " + median, median < 0.064f / 15f && median > 0.064f / 40f);
        // on a camera that honours the inputs it was right
        assertEquals(0.064f, decode(dense, honoured(dense, 0.064f)), 2e-4f);
    }

    @Test public void theMergeTakesTheInputsThenTheOutputs() {
        final float[] pairs = YuvMergeRoute.curvePairs(4);
        final float[] curve = YuvBurst.mergeCurve(pairs);
        for (int i = 0; i < 4; i++) {
            assertEquals(pairs[2 * i], curve[i], 0f);
            assertEquals(pairs[2 * i + 1], curve[4 + i], 0f);
        }
    }

    @Test public void theResultsCurveIsComparedWithTheOneAskedFor() {
        final float[] pairs = YuvMergeRoute.curvePairs(64);
        assertTrue(YuvBurst.curveReport(pairs, null).contains("the result reports no curve"));
        assertTrue(YuvBurst.curveReport(pairs, pairs.clone()).contains("the result reports them"));
        final float[] other = pairs.clone();
        other[41] += 0.05f;
        assertTrue(YuvBurst.curveReport(pairs, other).contains("differ by up to 0.0500"));
        assertTrue(YuvBurst.curveReport(pairs, YuvMergeRoute.curvePairs(32)).contains("the result reports 32 points"));
    }
}
