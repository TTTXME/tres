package com.scamera.app.ui.camera;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Window;
import android.view.WindowManager;

import com.scamera.app.util.Log;

import java.util.function.BooleanSupplier;

/**
 * P705: the camera screen keeps the display on (FLAG_KEEP_SCREEN_ON) only while it is used. Before, the flag stayed for as long as
 * the screen was in front: a camera left open on a table ran its sensor, ISP, RAW stream, viewfinder and display until the battery
 * ran out. Now the flag goes after {@link #idleMs} without a touch or a key (scam_dev idle_screen_min, 3 minutes; 0: never) unless
 * a shot is still captured or processed; the system's own screen timeout then turns the display off (dimmed first: a touch there
 * brings the flag back), and the camera closes in onPause as for any screen-off. Main thread only.
 */
final class IdleScreen {
    private static final String TAG = "IdleScreen";
    /** The default idle time, minutes (scam_dev idle_screen_min). */
    static final float DEFAULT_MINUTES = 3f;
    /** While a shot is busy past the idle time, the next look is this much later. */
    static final long BUSY_RECHECK_MS = 10_000;

    private final Window window;
    private final BooleanSupplier busy;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable check = this::check;
    private long idleMs;
    private long touchedMs;
    private boolean kept, running;

    IdleScreen(Window window, BooleanSupplier busy) {
        this.window = window;
        this.busy = busy;
    }

    /** The idle time of {@code minutes} in ms; 0 (never) for 0 or less, or a value that is not a number. */
    static long idleMs(float minutes) {
        return minutes > 0f && minutes < 24 * 60f ? (long) (minutes * 60_000f) : 0L;
    }

    /**
     * The delay to the next look {@code sinceTouchMs} after the last touch: -1 never (no idle time); 0 the flag goes now (idle and
     * nothing busy); else the ms until the idle time is up, or {@link #BUSY_RECHECK_MS} while a shot keeps it.
     */
    static long nextCheckMs(long sinceTouchMs, long idleMs, boolean busy) {
        if (idleMs <= 0) return -1;
        if (sinceTouchMs < idleMs) return idleMs - Math.max(0, sinceTouchMs);
        return busy ? BUSY_RECHECK_MS : 0;
    }

    /** The screen is in front again: kept on, counted from now. */
    void resume(float minutes) {
        idleMs = idleMs(minutes);
        running = true;
        touchedMs = SystemClock.uptimeMillis();
        keep(true);
        main.removeCallbacks(check);
        if (idleMs > 0) main.postDelayed(check, idleMs);
    }

    /** The screen left: no more looks (the flag is set again at the next resume). */
    void pause() {
        running = false;
        main.removeCallbacks(check);
    }

    /** A touch or a key on the screen: counted from now; the display is kept on again if it was let go. */
    void touched() {
        touchedMs = SystemClock.uptimeMillis();
        if (!running || kept) return;
        keep(true);
        Log.i(TAG, "screen kept on again (touched)");
        main.removeCallbacks(check);
        if (idleMs > 0) main.postDelayed(check, idleMs);
    }

    private void check() {
        if (!running) return;
        boolean shot;
        try {
            shot = busy.getAsBoolean();
        } catch (RuntimeException e) {
            shot = false;
        }
        final long next = nextCheckMs(SystemClock.uptimeMillis() - touchedMs, idleMs, shot);
        if (next < 0) return;
        if (next > 0) {
            main.postDelayed(check, next);
            return;
        }
        keep(false);
        Log.i(TAG, "no touch for " + idleMs / 1000 + " s: the screen may turn off (the system's timeout)");
    }

    private void keep(boolean on) {
        if (kept == on && on) return;
        kept = on;
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
