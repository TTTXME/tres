package com.scamera.app.capture;

import com.scamera.app.util.Log;

/**
 * P680 (performance audit): the camera's start in PERF lines - the device asked for (openCamera), onOpened, onConfigured, the
 * first viewfinder result of the session; once per process also the time from the process's start to that first frame. Each
 * step is an async android.os.Trace section too (Perfetto), so the start can be read on a trace beside the threads' own work.
 */
public final class OpenTimeline {
    private static final String TAG = "PERF";
    private static final int COOKIE = 680;

    private static volatile long askedNs, openedNs, configuredNs;
    private static volatile boolean firstOfProcessLogged;

    private OpenTimeline() {}

    /** The device's open is asked for (CameraManager.openCamera). */
    static void deviceAsked() {
        askedNs = System.nanoTime();
        openedNs = configuredNs = 0;
        beginAsync("camera start");
    }

    /** onOpened of the device asked for. */
    static void opened() {
        openedNs = System.nanoTime();
    }

    /** onConfigured of the session that follows. */
    static void configured() {
        configuredNs = System.nanoTime();
    }

    /** The session's first viewfinder result: the PERF line of this start (once). */
    static void firstFrame() {
        final long asked = askedNs;
        if (asked == 0) return;
        askedNs = 0;
        final long now = System.nanoTime();
        endAsync("camera start");
        Log.i(TAG, describe(asked, openedNs, configuredNs, now));
        if (!firstOfProcessLogged) {
            firstOfProcessLogged = true;
            final long sinceStart = sinceProcessStartMs();
            if (sinceStart >= 0) Log.i(TAG, "app start -> first preview frame " + sinceStart + " ms (process start to the first viewfinder result)");
        }
    }

    /** The PERF line of one start: openCamera -> onOpened -> onConfigured -> first frame, ms between them (-1: not seen). */
    static String describe(long askedNs, long openedNs, long configuredNs, long firstNs) {
        return "camera start: openCamera -> onOpened " + ms(askedNs, openedNs) + " ms -> onConfigured " + ms(openedNs, configuredNs)
                + " ms -> first frame " + ms(configuredNs, firstNs) + " ms (" + ms(askedNs, firstNs) + " ms in all)";
    }

    private static long ms(long from, long to) {
        return from <= 0 || to <= 0 ? -1 : (to - from) / 1_000_000;
    }

    private static long sinceProcessStartMs() {
        try {
            return android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime();
        } catch (RuntimeException | LinkageError e) {
            return -1;
        }
    }

    private static void beginAsync(String name) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) android.os.Trace.beginAsyncSection(name, COOKIE);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    private static void endAsync(String name) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) android.os.Trace.endAsyncSection(name, COOKIE);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }
}
