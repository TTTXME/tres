package com.particlesdevs.photoncamera.manual;

import org.junit.Test;

import static org.junit.Assert.*;

public class UnspektraMeterTest {
    private static final float[] Y = {.2126f, .7152f, .0722f};

    private static double meter(float[] rgb, int w, int h, UnspektraMeter.Method method, boolean spot) {
        return UnspektraMeter.errorEv(rgb, w, h, 3, method, spot, w / 2, h / 2, Y);
    }

    @Test public void middleGreyNeedsNoCorrectionAndDoublingLightRemovesOneStop() {
        for (UnspektraMeter.Method method : UnspektraMeter.Method.values()) {
            assertEquals(0, meter(new float[]{.184f, .184f, .184f}, 1, 1, method, false), 1e-6);
            assertEquals(-1, meter(new float[]{.368f, .368f, .368f}, 1, 1, method, false), 1e-6);
        }
    }

    @Test public void medianResistsSmallSpecularHighlights() {
        float[] rgb = new float[81 * 3];
        java.util.Arrays.fill(rgb, .184f);
        rgb[120] = rgb[121] = rgb[122] = 100;
        assertEquals(0, meter(rgb, 9, 9, UnspektraMeter.Method.MEDIAN, false), 1e-6);
        assertTrue(meter(rgb, 9, 9, UnspektraMeter.Method.CENTRE_WEIGHTED, false) < -1);
    }

    @Test public void spotInfluenceCannotExceedOneStopOfGlobalMeter() {
        float[] rgb = new float[64 * 64 * 3];
        java.util.Arrays.fill(rgb, .184f);
        for (int y = 25; y < 39; y++) for (int x = 25; x < 39; x++)
            for (int c = 0; c < 3; c++) rgb[(y * 64 + x) * 3 + c] = 100;
        double global = meter(rgb, 64, 64, UnspektraMeter.Method.MEDIAN, false);
        double spot = meter(rgb, 64, 64, UnspektraMeter.Method.MEDIAN, true);
        assertEquals(global - 1, spot, 1e-6);
    }

    @Test public void blackAndNegativeFramesRemainFinite() {
        for (UnspektraMeter.Method method : UnspektraMeter.Method.values()) {
            assertEquals(0, meter(new float[12], 2, 2, method, true), 0);
            assertEquals(0, meter(new float[]{-1, -1, -1}, 1, 1, method, false), 0);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void truncatedRgbIsRejected() {
        meter(new float[11], 2, 2, UnspektraMeter.Method.MEDIAN, false);
    }
}
