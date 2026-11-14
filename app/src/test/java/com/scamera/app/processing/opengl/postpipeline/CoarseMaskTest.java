package com.scamera.app.processing.opengl.postpipeline;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P713: the highlight landing mask's coarse grid (tools/check_hlmask_coarse.py mirrors the plan and measures the photo). */
public class CoarseMaskTest {
    @Test
    public void blockFollowsTheSigmaUpToEight() {
        assertNull("off", CoarseMask.plan(2040, 1536, 24.48f, false));
        assertNull("a small whyLow keeps its grid", CoarseMask.plan(700, 525, 8.4f, true));
        assertEquals(2, CoarseMask.plan(1000, 750, 12f, true).block);
        assertEquals(4, CoarseMask.plan(2040, 1536, 24.48f, true).block);   // 12 MP
        assertEquals(8, CoarseMask.plan(4096, 3072, 49.152f, true).block);  // 50 MP
        assertEquals(8, CoarseMask.plan(8160, 6144, 97.92f, true).block);   // 200 MP
    }

    @Test
    public void twelveMegapixelGrid() {
        final CoarseMask.Plan p = CoarseMask.plan(2040, 1536, 0.012f * 2040, true);
        assertNotNull(p);
        // 510 x 384 inner texels, 4 whyLow texels each; sigma 24.48 less the block's and the bilinear read's spread
        assertEquals(1.0 * 4, p.scaleX, 1e-6);
        assertEquals(1.0 * 4, p.scaleY, 1e-6);
        final double sigma = Math.sqrt(24.48 * 24.48 - 15 / 12.0 - 16 / 12.0) / 4;
        assertEquals(sigma, p.sigma, 1e-4);
        assertEquals((int) Math.ceil(3 * p.sigma) + 1, p.margin);
        assertEquals(510 + 2 * p.margin, p.width);
        assertEquals(384 + 2 * p.margin, p.height);
        assertArrayEquals(new float[]{510f / p.width, 384f / p.height, (float) p.margin / p.width, (float) p.margin / p.height},
                p.grid, 0f);
    }

    @Test
    public void uvOfTheFrameMapsOntoTheInnerGrid() {
        final CoarseMask.Plan p = CoarseMask.plan(4080, 3064, 0.012f * 4080, true);
        assertNotNull(p);
        // the frame's corners land on the inner grid's corners, in texels of the coarse grid
        assertEquals(p.margin, (0f * p.grid[0] + p.grid[2]) * p.width, 1e-3);
        assertEquals(p.width - p.margin, (1f * p.grid[0] + p.grid[2]) * p.width, 1e-3);
        assertEquals(p.height - p.margin, (1f * p.grid[1] + p.grid[3]) * p.height, 1e-3);
        // the blur's radius fits WhyCombine.hlWeights' 160 taps
        assertTrue(Math.ceil(3 * p.sigma) <= 159);
    }
}
