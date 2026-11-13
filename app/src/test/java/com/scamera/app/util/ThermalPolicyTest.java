package com.scamera.app.util;

import android.app.Application;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import com.scamera.app.settings.PreferenceKeys;

import static org.junit.Assert.*;

/** P238 (speed-ux D2): the hot-phone policy - the viewfinder cap, the press notice, the status names. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ThermalPolicyTest {
    @After public void cleanup() {
        ThermalPolicy.processing(false);
        com.scamera.app.processing.ProcessingKeepAlive.busy = () -> false;
        ThermalPolicy.set(-1);
        ThermalPolicy.resetNotice();
    }

    @Test public void longMergeDoesNotLoseItsPreviewCapAfterTwentySeconds() {
        ThermalPolicy.processing(true);
        com.scamera.app.processing.ProcessingKeepAlive.busy = () -> true;
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

    @Test public void onlyNewerRawCapableDevicesUseThirtyFpsWhileProcessing() {
        assertEquals(30, ThermalPolicy.busyFpsForDevice(8L << 30, "sun", true));
        assertEquals(30, ThermalPolicy.busyFpsForDevice(12L << 30, "MT6991", true));
        assertEquals(15, ThermalPolicy.busyFpsForDevice(8L << 30, "pineapple", false));
        assertEquals(15, ThermalPolicy.busyFpsForDevice(0, "sun", true));
        assertEquals(2, ThermalPolicy.busyFpsForDevice(4L << 30, "sun", true));
        assertEquals(2, ThermalPolicy.busyFpsForDevice(8L << 30, "msm8998", true));
    }

    @Test public void theReducedTargetStartsImmediatelyAndEndsWithProcessing() throws Exception {
        java.lang.reflect.Field field = ThermalPolicy.class.getDeclaredField("defaultBusyVfFps");
        field.setAccessible(true);
        int previous = field.getInt(null);
        try {
            field.setInt(null, 30);
            assertFalse(ThermalPolicy.reducedRawViewfinder());
            ThermalPolicy.processing(true);
            assertTrue(ThermalPolicy.reducedRawViewfinder());
            ThermalPolicy.processing(false);
            assertFalse(ThermalPolicy.reducedRawViewfinder());
            field.setInt(null, 15);
            ThermalPolicy.processing(true);
            assertFalse("older devices keep their original target", ThermalPolicy.reducedRawViewfinder());
        } finally {
            field.setInt(null, previous);
        }
    }

    @Test public void thirtyFpsPolicyKeepsExplicitOverridesAndTheThermalCap() throws Exception {
        java.lang.reflect.Field field = ThermalPolicy.class.getDeclaredField("defaultBusyVfFps");
        field.setAccessible(true);
        int previous = field.getInt(null);
        try (org.mockito.MockedStatic<PreferenceKeys> prefs = org.mockito.Mockito.mockStatic(PreferenceKeys.class)) {
            field.setInt(null, 30);
            ThermalPolicy.processing(true);
            prefs.when(() -> PreferenceKeys.scamDevSwitch("thermal_policy", true)).thenReturn(true);
            int[] caps = {30, 15, 5, 0};
            int[] expected = {60, 30, 10, 60};
            for (int c = 0; c < caps.length; c++) {
                prefs.when(() -> PreferenceKeys.scamDevNumber("vf_busy_fps", 30)).thenReturn((float) caps[c]);
                ThermalPolicy.resetNotice();
                ThermalPolicy.set(0);
                int shown = 0;
                for (int i = 0; i < 60; i++)
                    if (ThermalPolicy.viewfinderFrameDue(5_000_000_000L + i * 33_333_333L)) shown++;
                assertEquals("explicit vf_busy_fps=" + caps[c], expected[c], shown);
                assertTrue("the pixel budget does not override a requested frame rate", ThermalPolicy.reducedRawViewfinder());
            }
            ThermalPolicy.resetNotice();
            ThermalPolicy.set(3);
            int shown = 0;
            for (int i = 0; i < 60; i++)
                if (ThermalPolicy.viewfinderFrameDue(9_000_000_000L + i * 33_333_333L)) shown++;
            assertEquals("SEVERE still caps an unlimited explicit frame rate", 30, shown);
        } finally {
            field.setInt(null, previous);
        }
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

    /**
     * P611: the cap counts frames instead of a minimum gap after the last one shown. The gap halved a stream slower than twice the
     * cap: the Adreno 750 log of 2026-11-08 toned 9 - 10 frames a second through the merge from a ~20 fps RAW stream under the
     * 15 fps processing cap. Now every stream rate keeps min(its rate, the cap), and a 30 fps stream keeps every second frame.
     */
    @Test public void theCapKeepsItsRateForAStreamSlowerThanTwiceTheCap() {
        final double[] streamFps = {30, 24, 20, 17, 15, 12};
        final int[] cap15 = {15, 15, 15, 15, 15, 12};
        for (int k = 0; k < streamFps.length; k++) {
            ThermalPolicy.resetNotice();
            final long period = Math.round(1e9 / streamFps[k]);
            int shown = 0;
            final int frames = (int) Math.round(streamFps[k] * 4); // 4 s
            for (int i = 0; i < frames; i++) if (ThermalPolicy.due(7_000_000_000L + i * period, 15)) shown++;
            assertEquals("a " + streamFps[k] + " fps stream under the 15 fps cap", cap15[k] * 4, shown, 1);
        }
        // the old minimum gap (one period less an eighth) for the same streams: 15, 12, 10, 17, 15, 12 a second
        // a 30 fps stream with jitter (28 - 38 ms) at the 30 fps cap (the newer SoCs' processing cap) keeps every frame; the old gap
        // (29.2 ms) dropped the early ones
        ThermalPolicy.resetNotice();
        long t = 3_000_000_000L;
        int shown = 0;
        final long[] steps = {33_000_000L, 28_000_000L, 38_000_000L, 31_000_000L, 36_000_000L};
        for (int i = 0; i < 120; i++) {
            t += steps[i % steps.length];
            if (ThermalPolicy.due(t, 30)) shown++;
        }
        assertEquals("the stream is 30.1 fps: one frame over the cap in 4 s (the old gap: 96 of 120)", 120, shown, 1);
        // the jittered stream under the 15 fps cap: 15 a second
        ThermalPolicy.resetNotice();
        t = 3_000_000_000L;
        shown = 0;
        for (int i = 0; i < 120; i++) {
            t += steps[i % steps.length];
            if (ThermalPolicy.due(t, 15)) shown++;
        }
        assertEquals(60, shown, 1);
        // a pause earns at most two frames (the credit's cap): no long burst; a time going back starts again
        ThermalPolicy.resetNotice();
        assertTrue(ThermalPolicy.due(1_000_000_000L, 15));
        assertTrue("half a second later", ThermalPolicy.due(1_500_000_000L, 15));
        assertTrue(ThermalPolicy.due(1_533_333_333L, 15));
        assertTrue(ThermalPolicy.due(1_566_666_666L, 15));
        assertFalse("then the cap again", ThermalPolicy.due(1_600_000_000L, 15));
        assertTrue(ThermalPolicy.due(1_633_333_333L, 15));
        assertTrue("a new session's clock", ThermalPolicy.due(500_000_000L, 15));
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
