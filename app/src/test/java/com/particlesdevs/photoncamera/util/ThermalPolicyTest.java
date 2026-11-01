package com.particlesdevs.photoncamera.util;

import android.app.Application;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** P238 (speed-ux D2): the hot-phone policy - the viewfinder cap, the press notice, the status names. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ThermalPolicyTest {
    @After public void cleanup() {
        ThermalPolicy.processing(false);
        com.particlesdevs.photoncamera.processing.ProcessingKeepAlive.busy = () -> false;
        ThermalPolicy.set(-1);
        ThermalPolicy.resetNotice();
    }

    @Test public void longMergeDoesNotLoseItsPreviewCapAfterTwentySeconds() {
        ThermalPolicy.processing(true);
        com.particlesdevs.photoncamera.processing.ProcessingKeepAlive.busy = () -> true;
        long now = android.os.SystemClock.elapsedRealtimeNanos() + ThermalPolicy.BUSY_MAX_NS + 1;
        assertTrue(ThermalPolicy.busy(now));
        assertTrue(ThermalPolicy.busy(now + ThermalPolicy.BUSY_MAX_NS + 1));
        ThermalPolicy.processing(false);
        assertFalse(ThermalPolicy.busy(now + ThermalPolicy.BUSY_MAX_NS + 2));
    }

    @Test public void abandonedProcessingFlagStillExpires() {
        ThermalPolicy.processing(true);
        assertFalse(ThermalPolicy.busy(android.os.SystemClock.elapsedRealtimeNanos() + ThermalPolicy.BUSY_MAX_NS + 1));
    }

    @Test public void smallMemoryDevicesReserveGpuTimeForProcessing() {
        assertEquals(2, ThermalPolicy.busyFpsForMemory(4L << 30));
        assertEquals(2, ThermalPolicy.busyFpsForMemory(3L << 30));
        assertEquals(15, ThermalPolicy.busyFpsForMemory(6L << 30));
        assertEquals(15, ThermalPolicy.busyFpsForMemory(0));
    }

    @Test public void nubiaBoardReservesGpuTimeEvenWithMoreRam() {
        assertEquals(2, ThermalPolicy.busyFpsForDevice(6L << 30, "msm8998"));
        assertEquals(2, ThermalPolicy.busyFpsForDevice(8L << 30, "MSM8998"));
        assertEquals(15, ThermalPolicy.busyFpsForDevice(8L << 30, "kalama"));
        assertEquals(15, ThermalPolicy.busyFpsForDevice(0, null));
    }

    @Test public void thermalStatusCannotRaiseAStricterProcessingCap() throws Exception {
        java.lang.reflect.Field field = ThermalPolicy.class.getDeclaredField("defaultBusyVfFps");
        field.setAccessible(true);
        int previous = field.getInt(null);
        try {
            field.setInt(null, 2);
            ThermalPolicy.resetNotice();
            ThermalPolicy.set(3);
            ThermalPolicy.processing(true);
            int shown = 0;
            for (int i = 0; i < 60; i++)
                if (ThermalPolicy.viewfinderFrameDue(5_000_000_000L + i * 33_333_333L)) shown++;
            assertTrue("a hot busy device retains the 2 fps cap: " + shown, shown >= 3 && shown <= 5);
            ThermalPolicy.processing(false);
            ThermalPolicy.resetNotice();
            shown = 0;
            for (int i = 0; i < 60; i++)
                if (ThermalPolicy.viewfinderFrameDue(9_000_000_000L + i * 33_333_333L)) shown++;
            assertEquals(30, shown);
        } finally {
            field.setInt(null, previous);
        }
    }

    @Test public void belowSevereEveryViewfinderFrameIsShown() {
        ThermalPolicy.resetNotice();
        ThermalPolicy.set(2); // MODERATE
        assertFalse(ThermalPolicy.severe());
        for (int i = 0; i < 30; i++) assertTrue(ThermalPolicy.viewfinderFrameDue(1_000_000_000L + i * 33_333_333L));
        assertNull("no notice below SEVERE", ThermalPolicy.pressNotice(10_000, 50));
    }

    @Test public void atSevereTheViewfinderShowsAtMost15FramesASecond() {
        ThermalPolicy.resetNotice();
        ThermalPolicy.set(3);
        assertTrue(ThermalPolicy.severe());
        int shown = 0;
        for (int i = 0; i < 60; i++) if (ThermalPolicy.viewfinderFrameDue(5_000_000_000L + i * 33_333_333L)) shown++; // 2 s at 30 fps
        assertEquals("every second frame of a 30 fps stream", 30, shown);
        shown = 0;
        for (int i = 0; i < 48; i++) if (ThermalPolicy.viewfinderFrameDue(9_000_000_000L + i * 41_666_666L)) shown++; // 2 s at 24 fps
        assertTrue("a 24 fps stream at most 15 a second: " + shown, shown <= 30 && shown >= 20);
    }

    @Test public void theHotNoticeComesOnceInFiveMinutes() {
        ThermalPolicy.resetNotice();
        ThermalPolicy.set(4); // CRITICAL
        final String first = ThermalPolicy.pressNotice(1_000_000, 50);
        assertNotNull(first);
        assertTrue(first, first.contains("50"));
        assertNull("not again at once", ThermalPolicy.pressNotice(1_000_000 + 60_000, 50));
        assertNotNull("after five minutes", ThermalPolicy.pressNotice(1_000_000 + ThermalPolicy.NOTICE_EVERY_MS, 200));
    }

    @Test public void statusNamesAndSamples() {
        assertEquals("severe", ThermalState.name(3));
        assertEquals("unknown", ThermalState.name(-1));
        assertEquals("moderate/0.85", new ThermalState.Sample(2, 0.85f).toString());
        assertEquals("unknown", ThermalState.UNKNOWN.toString());
        assertTrue(new ThermalState.Sample(3, Float.NaN).severe());
        assertFalse(new ThermalState.Sample(2, Float.NaN).severe());
        ThermalPolicy.observed(new ThermalState.Sample(3, 1.0f));
        assertEquals(3, ThermalPolicy.status());
        ThermalPolicy.observed(ThermalState.UNKNOWN);
        assertEquals("an unknown sample keeps the last status", 3, ThermalPolicy.status());
    }
}
