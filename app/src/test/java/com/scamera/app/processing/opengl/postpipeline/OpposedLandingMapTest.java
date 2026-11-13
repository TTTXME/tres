package com.scamera.app.processing.opengl.postpipeline;

import org.junit.Test;

import static com.scamera.app.processing.opengl.postpipeline.FilmGatesTest.portra;
import static com.scamera.app.processing.opengl.postpipeline.FilmGatesTest.pt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P378 (owner, 2026-10-28: the pink / orange ring round a sun clipped at the sensor floor, «везде»): the map of the lights «Inpaint
 * opposed» rebuilt (ScamRgb.opposedShare, WhyCombine's highlight landing) - its blocks ~192 on the frame's shorter side at any size
 * and, P277, the whole frame's on a lab fragment (a fragment's lights fill the blocks the photo's do), its radius ~6.3 blocks at
 * every size, and no map when the landing is not drawn (why_hl_soft 0, a film at 100 %). The sun bloom's blocks are the same.
 */
public class OpposedLandingMapTest {
    @Test public void theBlocksAreAboutThe192ndOfTheShorterSide() {
        assertEquals(16, ScamRgb.shareBlock(ScamRgb.wholeShorter(null, 2, pt(4096, 3072))));
        assertEquals(32, ScamRgb.shareBlock(ScamRgb.wholeShorter(null, 2, pt(8192, 6144))));
        assertEquals(64, ScamRgb.shareBlock(ScamRgb.wholeShorter(null, 2, pt(16320, 12288))));
        assertEquals("portrait: the shorter side", 16, ScamRgb.shareBlock(ScamRgb.wholeShorter(null, 2, pt(3072, 4096))));
        assertEquals("a tiny image: one pixel", 1, ScamRgb.shareBlock(ScamRgb.wholeShorter(null, 2, pt(120, 80))));
    }

    @Test public void aLabFragmentTakesTheWholeFramesBlocks() {
        final LabFrame whole = new LabFrame();
        whole.lowWidth = 2048;
        whole.lowHeight = 1536;
        assertEquals("a quarter-width fragment of a 12 MP shot: the photo's 16 px, not its own 4", 3072,
                ScamRgb.wholeShorter(whole, 2, pt(1024, 768)));
        assertEquals(16, ScamRgb.shareBlock(ScamRgb.wholeShorter(whole, 2, pt(1024, 768))));
        assertEquals(4, ScamRgb.shareBlock(ScamRgb.wholeShorter(null, 2, pt(1024, 768))));
        assertEquals("the 2x grid: whyLow x 4", 32, ScamRgb.shareBlock(ScamRgb.wholeShorter(whole, 4, pt(2048, 1536))));
        final LabFrame old = new LabFrame();
        old.lowWidth = 2048;   // recorded before P333: no height, the fragment's own
        assertEquals(768, ScamRgb.wholeShorter(old, 2, pt(1024, 768)));
    }

    @Test public void theRadiusIsAboutSixBlocksAtEverySize() {
        for (int shorter : new int[]{2304, 3072, 4096, 6144, 12288}) {
            final float blocks = ScamRgb.OPPOSED_RADIUS * shorter / ScamRgb.shareBlock(shorter);
            assertTrue(shorter + ": " + blocks, blocks > 6.2f && blocks < 6.5f);
        }
    }

    @Test public void noMapWithoutTheLanding() {
        assertTrue(WhyCombine.landingDrawn(0.7f, null));
        assertFalse("«Мягкость засветов» off", WhyCombine.landingDrawn(0f, null));
        assertFalse("a film at 100 %: the film alone", WhyCombine.landingDrawn(0.7f, portra(1f)));
        assertTrue("a film under 100 % keeps our landing", WhyCombine.landingDrawn(0.7f, portra(0.6f)));
    }
    /**
     * P602: the landing's block statistics - the read in place (scam_dev hybrid_highlight_landing_async 0) and the logged shot's read
     * at the end of the run give the same line from the same weights.
     */
    @Test public void theStatisticsCountTheFilledBlocksAndTheGlow() {
        final java.nio.FloatBuffer v = java.nio.FloatBuffer.wrap(new float[]{0f, 0f, 0f, 0f, 0.5f, 0f, 0.25f, 10f, 1f, 0f, 0.375f, 30f});
        final double[] st = ScamRgb.opposedStats(v, 3);
        assertEquals(2, st[0], 0);
        assertEquals(40, st[1], 0);
        assertEquals(0.25 * 10 + 0.375 * 30, st[2], 1e-9);
        assertEquals("highlight opposed landing: 2 of 255x192@16 blocks filled, radius 6.25, glow saturation 0.344 over 40 px",
                ScamRgb.opposedStatsLine((int) st[0], pt(255, 192), 16, 6.25f, true, st[1], st[2]));
        assertEquals("highlight opposed landing: 0 of 255x192@16 blocks filled, radius 6.25 (no clip flags), glow saturation none",
                ScamRgb.opposedStatsLine(0, pt(255, 192), 16, 6.25f, false, 0, 0));
    }
}
