package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * P605: the nubia's 50 MP remosaic (the HAL's plain Bayer at 8192x6144, block 1) merges as a plain Bayer burst: the worker's
 * memory budget of a block-1 stream, never more per frame than the Quad 50 MP route it replaces (no binned working copy), and not
 * the native mosaic's own budget.
 */
public class NubiaRemosaicBudgetTest {
    private static final int W = 8192, H = 6144;

    @Test public void aBayerFiftyMegapixelFrameCostsLessThanAQuadOne() {
        assertTrue(ScamHybridBurst.perFrameBytes(W, H, 1) < ScamHybridBurst.perFrameBytes(W, H, 2));
        assertFalse("the native mosaic's budget is the Quad's alone", ScamHybridBurst.workerBudgetsMosaic(W, H, 1));
        assertTrue(ScamHybridBurst.workerBudgetsMosaic(W, H, 2));
    }

    @Test public void theMemoryBudgetKeepsAtLeastAsManyFrames() {
        for (long gb : new long[]{3, 5, 8}) {
            final long available = gb << 30;
            final int bayer = ScamHybridBurst.memoryFrameBudget(W, H, 1, available);
            final int quad = ScamHybridBurst.memoryFrameBudget(W, H, 2, available);
            assertTrue(gb + " GB: " + bayer + " >= " + quad, bayer >= quad);
            assertTrue("a cap, not unbounded: 50 MP is above the classic size", bayer < Integer.MAX_VALUE - 1);
        }
        assertTrue(ScamHybridBurst.minNormals(W, H) >= 2);
    }
}
