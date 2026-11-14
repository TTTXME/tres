package com.scamera.app.processing.opengl.postpipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The two-pass final resize (HybridFinalResize.separable): the output bands cover every output row once, in order, and each
 * band's RGBA32F rows hold every input row its output rows' vertical taps reach (hybrid/final_resize.glsl PASS 2:
 * base + dj for |dj| <= ceil(radius) + 1, within the frame) - for every kernel and the photo's resize ratios.
 */
public class HybridFinalResizePlanTest {
    @Test public void everyTapOfEveryOutputRowIsInItsBand() {
        final int[][] sizes = {{6144, 3072}, {6144, 4608}, {9248, 4624}, {12288, 3888}, {4624, 3792}, {733, 600}, {960, 240},
                {1230, 615}, {3000, 2999}, {6000, 1501}};
        for (int[] s : sizes) {
            for (int kernel = 0; kernel < 3; kernel++) {
                final int in = s[0], out = s[1];
                final int reach = HybridFinalResize.reach(in, out, kernel);
                final int[][] plan = HybridFinalResize.plan(in, out, HybridFinalResize.BAND_ROWS, reach + HybridFinalResize.REACH_MARGIN);
                int next = 0;
                for (int[] b : plan) {
                    assertEquals("bands in order", next, b[0]);
                    assertTrue(b[1] > 0 && b[1] <= HybridFinalResize.BAND_ROWS);
                    assertTrue(b[2] >= 0 && b[2] + b[3] <= in);
                    for (int y = b[0]; y < b[0] + b[1]; y++) {
                        final long num = (2L * y + 1) * in - out, den = 2L * out;
                        final int base = (int) (num / den);
                        // the shader's reach in float may be one above Java's: the margin covers it
                        for (int dj = -(reach + 1); dj <= reach + 1; dj++) {
                            final int row = base + dj;
                            if (row < 0 || row > in - 1) continue;
                            assertTrue(in + "->" + out + " kernel " + kernel + " row " + y + " tap " + row,
                                    row >= b[2] && row < b[2] + b[3]);
                        }
                    }
                    next = b[0] + b[1];
                }
                assertEquals("every output row", out, next);
            }
        }
    }

    @Test public void reachIsTheShadersSupport() {
        // Lanczos-3 at 2:1: radius 6, reach 7 (15 steps a row); bicubic 2 lobes; area half the ratio
        assertEquals(7, HybridFinalResize.reach(6144, 3072, 0));
        assertEquals(5, HybridFinalResize.reach(6144, 3072, 1));
        assertEquals(2, HybridFinalResize.reach(6144, 3072, 2));
        assertEquals(6, HybridFinalResize.reach(4608, 3072, 0)); // 1.5: radius 4.5
    }

    @Test public void bilinearKeepsTheOnePass() {
        assertFalse(HybridFinalResize.separableWanted(3));
    }
}
