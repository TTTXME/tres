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
 * P683: the RAW viewfinder's tone at ~1.1 MP while the GPU does not keep up at full size (Xiaomi 12X: «fresh 18.3/s» of 30 fps,
 * the tone at 1260 x 1680), with hysteresis; the full size again after good windows; a slow try goes back at once and the next try
 * waits twice as long.
 */
public class RawPreviewLoadTest {
    private static final long FULL = 1260L * 1680, REDUCED = 1_100_000L;

    @Test public void fastGpusKeepTheFullSize() {
        final RawPreviewLoad l = new RawPreviewLoad();
        for (int i = 0; i < 100; i++) assertFalse(l.window(4.0, 30, 30, FULL, FULL, false));
        assertFalse(l.reduced());
        // a single slow window (a hiccup) is not enough
        assertFalse(l.window(30.0, 12, 30, FULL, FULL, false));
        assertFalse(l.window(4.0, 30, 30, FULL, FULL, false));
        assertFalse(l.window(30.0, 12, 30, FULL, FULL, false));
        assertFalse(l.reduced());
    }

    @Test public void twoSlowWindowsReduceByDrawTimeOrByLostFrames() {
        RawPreviewLoad l = new RawPreviewLoad();
        assertFalse(l.window(12.5, 30, 30, FULL, FULL, false));
        assertTrue(l.window(12.5, 30, 30, FULL, FULL, false));
        assertTrue(l.reduced());
        assertTrue(l.last(), l.last().contains("reduced to ~1.1 MP"));
        // the 12X: 7.7 ms draws but only 18.3 of 30 frames drawn
        l = new RawPreviewLoad();
        assertFalse(l.window(7.7, 18.3, 30, FULL, FULL, false));
        assertTrue(l.window(7.7, 18.3, 30, FULL, FULL, false));
        assertTrue(l.reduced());
        // a slow camera stream (low light, 15 fps) with every frame drawn is not slow
        l = new RawPreviewLoad();
        for (int i = 0; i < 10; i++) assertFalse(l.window(6.0, 15, 15, FULL, FULL, false));
    }

    @Test public void processingWindowsAndSmallTonesAreNotCounted() {
        final RawPreviewLoad l = new RawPreviewLoad();
        for (int i = 0; i < 10; i++) assertFalse(l.window(40.0, 5, 30, FULL, FULL, true));
        for (int i = 0; i < 10; i++) assertFalse(l.window(40.0, 5, 30, 800_000, 800_000, false));
        for (int i = 0; i < 10; i++) assertFalse(l.window(Double.NaN, 0, 30, FULL, FULL, false));
        assertFalse(l.reduced());
    }

    @Test public void hysteresisAndBackOff() {
        final RawPreviewLoad l = new RawPreviewLoad();
        l.window(7.7, 18.3, 30, FULL, FULL, false);
        assertTrue(l.window(7.7, 18.3, 30, FULL, FULL, false));
        // at the reduced size: good windows (every frame, 4 ms -> ~7.7 ms at full) - the full size is tried after 3
        assertFalse(l.window(4.0, 30, 30, REDUCED, FULL, false));
        assertFalse(l.window(4.0, 30, 30, REDUCED, FULL, false));
        assertTrue(l.window(4.0, 30, 30, REDUCED, FULL, false));
        assertFalse(l.reduced());
        assertTrue(l.last(), l.last().contains("back to full size"));
        // the try is slow: back at once, the next try after 6 good windows
        assertTrue(l.window(7.7, 18.3, 30, FULL, FULL, false));
        assertTrue(l.reduced());
        assertTrue(l.last(), l.last().contains("after 6 good windows"));
        for (int i = 0; i < 5; i++) assertFalse(l.window(4.0, 30, 30, REDUCED, FULL, false));
        // a bad window restarts the count
        assertFalse(l.window(4.0, 25, 30, REDUCED, FULL, false));
        for (int i = 0; i < 5; i++) assertFalse(l.window(4.0, 30, 30, REDUCED, FULL, false));
        assertTrue(l.window(4.0, 30, 30, REDUCED, FULL, false));
        assertFalse(l.reduced());
        // this try holds: after it, two slow windows are needed again, and the back-off starts over
        for (int i = 0; i < 10; i++) assertFalse(l.window(5.0, 30, 30, FULL, FULL, false));
        assertFalse(l.window(12.0, 30, 30, FULL, FULL, false));
        assertTrue(l.window(12.0, 30, 30, FULL, FULL, false));
        assertTrue(l.last(), l.last().contains("after 3 good windows"));
    }

    @Test public void aReducedToneWhoseFullDrawWouldBeSlowStays() {
        final RawPreviewLoad l = new RawPreviewLoad();
        l.window(15, 30, 30, FULL, FULL, false);
        assertTrue(l.window(15, 30, 30, FULL, FULL, false));
        // 8 ms at 1.1 MP is ~15 ms at 2.1 MP: no try
        for (int i = 0; i < 200; i++) assertFalse(l.window(8.0, 30, 30, REDUCED, FULL, false));
        assertTrue(l.reduced());
    }

    @Test public void theBackOffIsBounded() {
        final RawPreviewLoad l = new RawPreviewLoad();
        l.window(7.7, 18.3, 30, FULL, FULL, false);
        l.window(7.7, 18.3, 30, FULL, FULL, false);
        int waited = 0;
        for (int round = 0; round < 12; round++) {
            int n = 0;
            while (l.reduced()) {
                l.window(4.0, 30, 30, REDUCED, FULL, false);
                n++;
            }
            waited = n;
            assertTrue(l.window(7.7, 18.3, 30, FULL, FULL, false));
        }
        assertEquals(RawPreviewLoad.MAX_RECOVER_WINDOWS, waited);
    }

    /** P683: the first-run GPU calibration's estimate decides the starting size, once, before any window; the windows correct it. */
    @Test public void theCalibrationSeedsTheStartingSize() {
        RawPreviewLoad l = new RawPreviewLoad();
        assertTrue("a slow GPU (the tone ~25 ms a frame) starts reduced", l.seed(25.0, FULL));
        assertTrue(l.reduced());
        assertTrue(l.last(), l.last().contains("first-run GPU calibration"));
        assertFalse("once", l.seed(25.0, FULL));
        for (int i = 0; i < 2; i++) assertFalse(l.window(4.0, 30, 30, REDUCED, FULL, false));
        assertTrue("good windows bring the full size back", l.window(4.0, 30, 30, REDUCED, FULL, false));
        assertFalse(l.reduced());
        l = new RawPreviewLoad();
        assertFalse("a fast GPU keeps the full size", l.seed(5.0, FULL));
        assertFalse(l.reduced());
        l = new RawPreviewLoad();
        assertFalse("no profile", l.seed(Double.NaN, FULL));
        assertFalse(new RawPreviewLoad().seed(25.0, 900_000));
        l = new RawPreviewLoad();
        l.window(4.0, 30, 30, FULL, FULL, false);
        assertFalse("after a measured window the calibration no longer decides", l.seed(25.0, FULL));
    }

    @Test public void theBudgetTakesTheSmallerCap() {
        assertArrayEquals(new int[]{1440, 1920}, RawPreviewBudget.size(1440, 1920, false, false));
        final int[] r = RawPreviewBudget.size(1260, 1680, false, true);
        assertTrue((long) r[0] * r[1] <= RawPreviewLoad.REDUCED_PIXELS && (long) r[0] * r[1] > 1_090_000);
        assertEquals(1260.0 / 1680, r[0] / (double) r[1], 0.002);
        assertArrayEquals(RawPreviewBudget.size(1260, 1680, true), RawPreviewBudget.size(1260, 1680, true, true));
        assertArrayEquals(new int[]{900, 1200}, RawPreviewBudget.size(900, 1200, false, true));
    }

    @Test public void theRendererFeedsItsWindowsAndSwitchIsLogged() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(new java.io.File("src").isDirectory() ? "src" : "app/src",
                "main/java/com/scamera/app/ui/camera/views/viewfinder/MainRenderer.java")), StandardCharsets.UTF_8);
        assertTrue(src.contains("mToneLoad.window(drawMs, mVfRawFresh / s, (mVfRawFresh + dropped - mVfRawDropped0) / s,"));
        assertTrue(src.contains("Log.i(\"VF_RAW\", mToneLoad.last());"));
        assertTrue(src.contains("RawPreviewBudget.size(size[0], size[1], busy, reduced)"));
        // the window's draw mean is read before VfStages.take() resets it
        assertTrue(src.indexOf("VfStages.meanMs(") < src.indexOf("Log.i(\"VF_RAW\", com.scamera.app.processing.VfStages.take());"));
    }
}
