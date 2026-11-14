package com.scamera.app.ui.camera;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import com.scamera.app.util.Log;

/**
 * P705 (owner, 2026-11-14: on the 8 Gen 3 and newer phones use their power - a smooth 90 / 120 Hz UI, without a drain while
 * idle): while a finger is on the camera screen and {@link #HOLD_MS} after it lifts (the sheet, the zoom dial and the tray
 * animate then) the window asks for the panel's highest refresh rate among the seamless alternatives of its present mode (the same
 * resolution: no blank mode switch); then it asks for nothing again and the system's policy decides (an LTPO panel idles low
 * while the viewfinder runs at the camera's ~30 fps). Only on phones of the normal memory class (above 8 GB: the recent
 * flagships) from Android 12 (Display.Mode.getAlternativeRefreshRates); scam_dev ui_touch_refresh 0: never. Camera fps unchanged.
 */
final class TouchRefresh {
    private static final String TAG = "TouchRefresh";
    /** The rate is kept this long after the last finger lifted. */
    static final long HOLD_MS = 1500;

    private final Window window;
    private final int modeId;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable release = () -> ask(0);
    private boolean boosted;

    private TouchRefresh(Window window, int modeId) {
        this.window = window;
        this.modeId = modeId;
    }

    /** The boost of this window's display, null when there is none to ask for (not a strong phone, one rate, switched off). */
    @Nullable
    static TouchRefresh of(Window window, @Nullable Display display, boolean strong) {
        if (!strong || display == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null;
        try {
            final Display.Mode now = display.getMode();
            final Display.Mode[] modes = display.getSupportedModes();
            final int n = modes.length;
            final int[] ids = new int[n], widths = new int[n], heights = new int[n];
            final float[] rates = new float[n];
            for (int i = 0; i < n; i++) {
                ids[i] = modes[i].getModeId();
                widths[i] = modes[i].getPhysicalWidth();
                heights[i] = modes[i].getPhysicalHeight();
                rates[i] = modes[i].getRefreshRate();
            }
            final int id = boostMode(ids, widths, heights, rates, now.getPhysicalWidth(), now.getPhysicalHeight(),
                    now.getRefreshRate(), now.getAlternativeRefreshRates());
            if (id == 0) return null;
            Log.i(TAG, "touches ask for display mode " + id + " (now " + now.getModeId() + " at " + now.getRefreshRate() + " Hz)");
            return new TouchRefresh(window, id);
        } catch (RuntimeException e) {
            Log.w(TAG, "display modes unreadable: " + e);
            return null;
        }
    }

    /**
     * The mode to ask for: the highest refresh rate above 61 Hz among the modes of the present resolution whose rate is the present
     * one or one of its seamless alternatives ({@code seamless}); 0 when there is none (a 60 Hz panel, no seamless switch).
     */
    static int boostMode(int[] ids, int[] widths, int[] heights, float[] rates, int width, int height, float rate, float[] seamless) {
        int best = 0;
        float bestRate = 61f;
        for (int i = 0; i < ids.length; i++) {
            if (widths[i] != width || heights[i] != height || rates[i] <= bestRate) continue;
            if (Math.abs(rates[i] - rate) >= 0.5f && !listed(seamless, rates[i])) continue;
            best = ids[i];
            bestRate = rates[i];
        }
        return best;
    }

    private static boolean listed(float[] seamless, float rate) {
        if (seamless == null) return false;
        for (float r : seamless) if (Math.abs(r - rate) < 0.5f) return true;
        return false;
    }

    /** A touch of the screen (Activity.dispatchTouchEvent, before the views). */
    void onTouch(MotionEvent event) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            main.removeCallbacks(release);
            ask(modeId);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            main.removeCallbacks(release);
            main.postDelayed(release, HOLD_MS);
        }
    }

    /** The screen left: no rate asked for any more. */
    void pause() {
        main.removeCallbacks(release);
        ask(0);
    }

    private void ask(int id) {
        if (boosted == (id != 0)) return;
        try {
            final WindowManager.LayoutParams lp = window.getAttributes();
            lp.preferredDisplayModeId = id;
            window.setAttributes(lp);
            boosted = id != 0;
        } catch (RuntimeException e) {
            Log.w(TAG, "display mode not asked for: " + e);
        }
    }
}
