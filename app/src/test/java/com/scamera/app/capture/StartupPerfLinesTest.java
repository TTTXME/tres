package com.scamera.app.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.scamera.app.processing.ImageSaver;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

/** P680: the start's PERF line, the vivo mode line logged only on a change (the same text), the signalled series wait. */
public class StartupPerfLinesTest {
    @Test public void theStartLineNamesEachStep() {
        final long ms = 1_000_000L;
        assertEquals("camera start: openCamera -> onOpened 120 ms -> onConfigured 80 ms -> first frame 45 ms (245 ms in all)",
                OpenTimeline.describe(1000 * ms, 1120 * ms, 1200 * ms, 1245 * ms));
        assertEquals("camera start: openCamera -> onOpened -1 ms -> onConfigured -1 ms -> first frame -1 ms (245 ms in all)",
                OpenTimeline.describe(1000 * ms, 0, 0, 1245 * ms));
    }

    @Test public void theVivoModeLineKeepsItsText() {
        assertEquals("camera 5 mode=7 seamless=[1, 0] changed=- white=1023",
                VivoQcomModes.stateLine("5", 7, new int[]{1, 0}, null, 1023));
        assertEquals("camera null mode=null seamless=- changed=[0] white=null", VivoQcomModes.stateLine(null, null, null, new int[]{0}, null));
    }

    @Test public void theSeriesWaitWakesOnASignal() throws Exception {
        final AtomicBoolean in = new AtomicBoolean();
        final Thread saver = new Thread(() -> {
            try {
                Thread.sleep(30);
            } catch (InterruptedException ignored) {
            }
            in.set(true);
            ImageSaver.framesChanged();
        });
        final long start = System.nanoTime();
        saver.start();
        assertTrue(ImageSaver.awaitFrames(in::get, 1000, 1000)); // a long slice: only the signal ends the wait early
        final long waited = (System.nanoTime() - start) / 1_000_000;
        assertTrue("woken by the signal, " + waited + " ms", waited < 500);
        saver.join();
    }

    @Test public void theSeriesWaitEndsAtItsLimitAndLooksBetweenSignals() {
        final long start = System.nanoTime();
        assertFalse(ImageSaver.awaitFrames(() -> false, 40, 5));
        assertTrue((System.nanoTime() - start) / 1_000_000 >= 39);
        // a frame added where nothing signals: seen within a slice
        final long[] calls = {0};
        assertTrue(ImageSaver.awaitFrames(() -> ++calls[0] > 3, 1000, 2));
        assertTrue(ImageSaver.awaitFrames(() -> true, 0, 5));
    }
}
