package com.scamera.app.ui.camera.views.viewfinder;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P711 (owner, 2026-11-14: «он должен хорошо работать даже на Snapdragon 865»): the RAW viewfinder's tone by the GPU time it takes
 * (ToneGpuTimer) and in steps - the full size, ~1.1, 0.75, 0.5 MP: a GPU still slow at one step goes one further, the steps back
 * up as from the first (good windows scaled to the step above, a try, its back-off). The P683 behaviour without a GPU time is
 * RawPreviewLoadTest's.
 */
public class RawPreviewLoadStepsTest {
    /** A 865-class phone's viewport (1080 x 1440, 4:3 of a 1080 x 2400 screen) and the X200 Ultra's. */
    private static final long FULL = 1080L * 1440, BIG = 1440L * 1920;

    /** An Adreno 650 at ~7 ns a tone pixel (the tone's passes): GPU ms of a tone of {@code pixels}. */
    private static double gpu(long pixels) {
        return 7e-6 * pixels;
    }

    private static boolean window(RawPreviewLoad l, double gpuMs, long tone, long full) {
        l.gpu(gpuMs);
        // the GL thread only queues the passes: its draw is short whatever the GPU's time; every frame drawn
        return l.window(4.0, 30, 30, tone, full, false);
    }

    @Test public void aGpuBoundToneIsReducedByItsGpuTimeBeforeFramesAreLost() {
        final RawPreviewLoad l = new RawPreviewLoad();
        // full 1.56 MP: ~10.9 ms of GPU - keeps up
        for (int i = 0; i < 10; i++) assertFalse(window(l, gpu(FULL), FULL, FULL));
        // the X200 Ultra's viewport on the same GPU: ~19 ms - two windows, then ~1.1 MP
        final RawPreviewLoad big = new RawPreviewLoad();
        assertFalse(window(big, gpu(BIG), BIG, BIG));
        assertTrue(window(big, gpu(BIG), BIG, BIG));
        assertEquals(1, big.level());
        assertTrue(big.last(), big.last().contains("reduced to ~1.1 MP") && big.last().contains("GPU 19.4 ms"));
        assertEquals(RawPreviewLoad.REDUCED_PIXELS, big.cap());
        // at 1.1 MP ~7.7 ms: good, but ~19 ms at the full size - it stays
        for (int i = 0; i < 50; i++) assertFalse(window(big, gpu(RawPreviewLoad.REDUCED_PIXELS), RawPreviewLoad.REDUCED_PIXELS, BIG));
        assertEquals(1, big.level());
    }

    @Test public void stillSlowAtAStepGoesOneFurtherAndBackUpStepByStep() {
        final RawPreviewLoad l = new RawPreviewLoad();
        // a GPU at ~20 ns a pixel (a busy or a slow one): 31 ms at the full size, 22 at 1.1 MP, 15 at 0.75 MP
        window(l, 20e-6 * FULL, FULL, FULL);
        assertTrue(window(l, 20e-6 * FULL, FULL, FULL));
        assertEquals(1, l.level());
        assertFalse(window(l, 22.0, 1_100_000, FULL));
        assertTrue(window(l, 22.0, 1_100_000, FULL));
        assertEquals(2, l.level());
        assertEquals(750_000, l.cap());
        assertTrue(l.last(), l.last().contains("reduced further to ~0.8 MP (step 2"));
        // 0.75 MP at 15 ms: not slow (16), not good enough for 1.1 MP (22 > 12): stays
        for (int i = 0; i < 20; i++) assertFalse(window(l, 15.0, 750_000, FULL));
        assertEquals(2, l.level());
        // the GPU freed (the shot's processing over): 5 ms at 0.75 MP -> 7.3 ms at 1.1 MP: up after 3 good windows, a try
        assertFalse(window(l, 5.0, 750_000, FULL));
        assertFalse(window(l, 5.0, 750_000, FULL));
        assertTrue(window(l, 5.0, 750_000, FULL));
        assertEquals(1, l.level());
        assertTrue(l.last(), l.last().contains("back up to ~1.1 MP (step 1"));
        // the try is slow at once: back to step 2, the next try after 6 good windows
        assertTrue(window(l, 17.0, 1_100_000, FULL));
        assertEquals(2, l.level());
        assertTrue(l.last(), l.last().contains("after 6 good windows"));
        for (int i = 0; i < 5; i++) assertFalse(window(l, 5.0, 750_000, FULL));
        assertTrue(window(l, 5.0, 750_000, FULL));
        assertEquals(1, l.level());
        // 7 ms at 1.1 MP: 9.9 ms at the full size (under 12): the full size after the back-off's 6 good windows
        for (int i = 0; i < 5; i++) assertFalse(window(l, 7.0, 1_100_000, FULL));
        assertTrue(window(l, 7.0, 1_100_000, FULL));
        assertEquals(0, l.level());
        assertTrue(l.last(), l.last().contains("back to full size"));
    }

    @Test public void theLastStepIsTheFloorAndAStepThatWouldNotShrinkIsNone() {
        final RawPreviewLoad l = new RawPreviewLoad();
        for (int i = 0; i < 40; i++) window(l, 40.0, Math.min(FULL, l.cap()), FULL);
        assertEquals(RawPreviewLoad.CAPS.length - 1, l.level());
        assertEquals(500_000, l.cap());
        // a tone already under the next step (a zoomed mosaic's colour grid): no step
        final RawPreviewLoad z = new RawPreviewLoad();
        window(z, 30.0, BIG, BIG);
        assertTrue(window(z, 30.0, BIG, BIG));
        for (int i = 0; i < 10; i++) assertFalse(window(z, 30.0, 700_000, BIG));
        assertEquals(1, z.level());
    }

    @Test public void noGpuTimeDecidesAsBefore() {
        final RawPreviewLoad l = new RawPreviewLoad();
        for (int i = 0; i < 10; i++) assertFalse(window(l, Double.NaN, BIG, BIG));
        assertFalse(l.reduced());
        // a GPU time is read by one window only
        l.gpu(30.0);
        assertFalse(l.window(4.0, 30, 30, BIG, BIG, false));
        assertFalse(l.window(4.0, 30, 30, BIG, BIG, false));
        assertFalse(l.reduced());
    }

    @Test public void theSeedStartsAtTheFirstStepUnderItsTime() {
        RawPreviewLoad l = new RawPreviewLoad();
        assertTrue(l.seed(40.0, BIG));
        // 40 ms at 2.76 MP: 15.9 ms at 1.1 MP
        assertEquals(1, l.level());
        l = new RawPreviewLoad();
        assertTrue(l.seed(70.0, BIG));
        // 27.9 at 1.1 MP, 19.0 at 0.75 MP, 12.7 at 0.5 MP
        assertEquals(3, l.level());
        assertTrue(l.last(), l.last().contains("starts at ~0.5 MP"));
    }

    @Test public void theBudgetTakesTheStepsCap() {
        assertArrayEquals(new int[]{1080, 1440}, RawPreviewBudget.size(1080, 1440, false, Integer.MAX_VALUE));
        final int[] r = RawPreviewBudget.size(1080, 1440, false, 750_000);
        assertTrue((long) r[0] * r[1] <= 750_000 && (long) r[0] * r[1] > 740_000);
        assertArrayEquals(RawPreviewBudget.size(1080, 1440, true), RawPreviewBudget.size(1080, 1440, true, 1_100_000));
        assertArrayEquals(RawPreviewBudget.size(1080, 1440, 500_000), RawPreviewBudget.size(1080, 1440, true, 500_000));
    }

    @Test public void theTimersMeanCountsTheTonedDraws() {
        final ToneGpuTimer.Mean m = new ToneGpuTimer.Mean();
        assertTrue(Double.isNaN(m.take()));
        m.add(10.0);
        m.add(14.0);
        m.add(Double.NaN);
        m.add(-1.0);
        assertEquals(12.0, m.take(), 1e-9);
        assertTrue("a new window", Double.isNaN(m.take()));
    }

    @Test public void theRendererTimesTheToneAndFeedsTheWindow() throws Exception {
        final String src = new String(Files.readAllBytes(Paths.get(new java.io.File("src").isDirectory() ? "src" : "app/src",
                "main/java/com/scamera/app/ui/camera/views/viewfinder/MainRenderer.java")), StandardCharsets.UTF_8);
        final int begin = src.indexOf("mToneTimer.begin();"), end = src.indexOf("mToneTimer.end(mVfRawToned != tonedBefore);");
        assertTrue(begin > 0 && end > begin);
        // the tone's passes between them: the fused / direct / P314 draws and the filter / peaking pass
        assertTrue(src.indexOf("mWhyTone.drawFused(mFusedRaw, 0,", begin) < end && src.indexOf("drawRawFiltered(peaking);", begin) < end);
        assertTrue(src.indexOf("mToneLoad.gpu(mToneTimer.takeMeanMs());") < src.indexOf("mToneLoad.window(drawMs,"));
        assertTrue(src.contains("final int reduced = adaptiveTone() ? mToneLoad.cap() : Integer.MAX_VALUE;"));
        assertTrue(src.contains("mToneTimer.onContextCreated();"));
    }
}
