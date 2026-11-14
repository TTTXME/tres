package com.scamera.app.ui.camera.views.viewfinder;

import java.util.Locale;

/**
 * P683 (owner: «ускорь и облегчи, особенно на старых/слабых»; vivo / Xiaomi 12X logs: «fresh 18.3/s» of a 30 fps stream, «draw
 * 7.7/26.5 ms»): the RAW viewfinder's WHY tone ran at the frame's full colour grid (1260 x 1680 on the 12X) whatever the GPU,
 * smaller only while a shot is processed (RawPreviewBudget's 750k). This decides from MainRenderer's 2 s windows whether the
 * tone keeps up: a window is slow when its new frames' mean draw on the GL thread is above {@link #SLOW_DRAW_MS} or fewer than
 * {@link #FRESH_SHARE} of the frames that arrived (drawn + dropped) were drawn; {@link #SLOW_WINDOWS} slow windows in a row
 * reduce the tone to {@link #REDUCED_PIXELS} (the filter / peaking pass scales it to the screen as for a zoomed frame; the photo
 * is not touched). Hysteresis: the full size is tried again only after {@link #RECOVER_WINDOWS} good windows in a row at the
 * reduced size (every frame drawn, the draw scaled linearly to the full size's pixels under {@link #SLOW_DRAW_MS}); a try that
 * is slow in its first {@link #TRIAL_WINDOWS} windows goes back at once and doubles the good windows the next try waits for (up
 * to {@link #MAX_RECOVER_WINDOWS}: one slow window every 5 min at worst). Windows while a shot is processed are not counted (the
 * busy budget applies then; the post pipeline shares the GPU). Every switch is logged (VF_RAW). Fast GPUs never get here: their
 * frames are all drawn in a few ms. scam_dev vf_adaptive_tone 0: off (the full size as before).
 *
 * <p>P711 (owner, 2026-11-14: «добавь оптимизацию live viewfinder, он должен хорошо работать даже на Snapdragon 865»): the tone's
 * GPU time is measured (ToneGpuTimer: GL_EXT_disjoint_timer_query around the tone's passes, read a few frames later, never
 * waited for) and goes into the window ({@link #gpu}): a window is slow too when the tone's mean GPU time is above
 * {@link #GPU_SLOW_MS} (half a 30 fps frame: the GL thread's own time hides a GPU that is the bottleneck until frames are lost), a
 * try of the size above needs the GPU time scaled to it under {@link #GPU_GOOD_MS}; and the reduction has steps ({@link #CAPS}:
 * ~1.1, 0.75 and 0.5 MP) - still slow at one step, {@link #SLOW_WINDOWS} windows go one step further, the steps back up as from
 * the first one (good windows, a try, its back-off). An Adreno 650 at ~5.7 ns a tone pixel (P314's 2.8 ns on an Adreno 750, half
 * the ALU) settles where the tone is ~6 - 9 ms of GPU: 1.1 MP at a 1080 x 1440 viewport. The first-run calibration's seed starts
 * at the first step whose estimate is under {@link #SEED_GPU_MS}.
 */
final class RawPreviewLoad {
    static final int REDUCED_PIXELS = 1_100_000;
    /** P711: the tone's pixel cap of each step (0: none, the full size). */
    static final int[] CAPS = {Integer.MAX_VALUE, REDUCED_PIXELS, 750_000, 500_000};
    static final float SLOW_DRAW_MS = 11f;
    static final float FRESH_SHARE = 0.8f;
    static final int SLOW_WINDOWS = 2;
    /** Frames drawn of those that arrived in a good window (the reduced size). */
    static final float RECOVER_FRESH_SHARE = 0.95f;
    static final int RECOVER_WINDOWS = 3, MAX_RECOVER_WINDOWS = 150;
    /** A try of the full size this many windows long: one slow window in it reduces again. */
    static final int TRIAL_WINDOWS = 3;
    /** P711: a window whose tone took more GPU time a frame than this is slow (half a 30 fps frame). */
    static final float GPU_SLOW_MS = 16f;
    /** P711: the step above is tried only when the GPU time scaled to its pixels stays under this. */
    static final float GPU_GOOD_MS = 12f;

    /**
     * P683: the first-run GPU calibration's estimate (GpuProfile: the viewfinder tone's ns per pixel x the full size's pixels)
     * above this many ms of GPU time a frame starts the tone reduced - half a 30 fps frame, the rest left to the linear pass, the
     * upload and the filter / peaking pass.
     */
    static final float SEED_GPU_MS = 16f;

    private int level;
    private boolean seeded;
    private int slow, good, recoverWindows = RECOVER_WINDOWS, trial;
    private double gpuMs = Double.NaN;
    private String last = "";

    boolean reduced() {
        return level > 0;
    }

    /** P711: the step (0 the full size .. CAPS.length - 1). */
    int level() {
        return level;
    }

    /** P711: the tone's pixel cap now (Integer.MAX_VALUE at the full size). */
    int cap() {
        return CAPS[level];
    }

    /**
     * P711: the tone's mean GPU time a toned frame in the window that ends next (ms; NaN: not measured - no timer query on this
     * GPU, or no result yet). Read by the next {@link #window} only.
     */
    void gpu(double ms) {
        gpuMs = ms;
    }

    private static String mp(long pixels) {
        return String.format(Locale.ROOT, "%.1f", pixels / 1e6);
    }

    /**
     * P683: the starting size from the first-run GPU calibration, once (before any window): {@code gpuMs} the estimated GPU time of
     * the full-size tone (NaN: no profile). True when it starts reduced (then {@link #last} says why); the windows correct it as for
     * a reduction of their own (the full size tried after {@link #RECOVER_WINDOWS} good windows). P711: at the first step whose
     * estimate (scaled by its pixels) is under SEED_GPU_MS.
     */
    boolean seed(double gpuMs, long fullPixels) {
        if (seeded) return false;
        seeded = true;
        if (level > 0 || !(gpuMs > SEED_GPU_MS) || fullPixels <= REDUCED_PIXELS) return false;
        int to = 1;
        while (to < CAPS.length - 1 && CAPS[to] < fullPixels && gpuMs * CAPS[to] / (double) fullPixels > SEED_GPU_MS) to++;
        level = to;
        slow = good = trial = 0;
        recoverWindows = RECOVER_WINDOWS;
        last = String.format(Locale.ROOT, "RAW viewfinder tone starts at ~%s MP (not %s MP): the first-run GPU calibration puts the"
                + " full-size tone at ~%.1f ms of GPU a frame (above %.0f); full size tried after %d good windows", mp(CAPS[level]),
                mp(fullPixels), gpuMs, SEED_GPU_MS, recoverWindows);
        return true;
    }

    /** The last switch's text (for the log). */
    String last() {
        return last;
    }

    /**
     * One 2 s window: {@code drawMs} the mean draw of its new frames (NaN: none drawn), {@code fresh} / {@code arrived} new frames
     * drawn / arrived per second (drawn + dropped), the tone's pixels in the window and the full (unreduced) size's, whether a shot
     * was processed during it. True when the size switches (then {@link #last} says why). P711: with the GPU time {@link #gpu} gave.
     */
    boolean window(double drawMs, double fresh, double arrived, long tonePixels, long fullPixels, boolean processing) {
        seeded = true; // P683: a measured window comes first: no calibration's start any more
        final double gpu = gpuMs;
        gpuMs = Double.NaN;
        if (processing || Double.isNaN(drawMs) || !(arrived > 0) || tonePixels <= 0 || fullPixels <= 0) return false;
        final double share = Math.min(1.0, fresh / arrived);
        if (level == 0 && fullPixels <= REDUCED_PIXELS) {
            slow = trial = 0;
            return false;
        }
        final boolean isSlow = drawMs > SLOW_DRAW_MS || share < FRESH_SHARE || gpu > GPU_SLOW_MS;
        final boolean inTrial = trial > 0;
        if (inTrial) trial--;
        if (isSlow) {
            good = 0;
            slow++;
            // P711: one step down - a step that would not make the tone smaller is no step
            final boolean room = level + 1 < CAPS.length && CAPS[level + 1] < Math.min(tonePixels, fullPixels);
            if (!room || slow < (inTrial ? 1 : SLOW_WINDOWS)) return false;
            level++;
            if (inTrial) recoverWindows = Math.min(MAX_RECOVER_WINDOWS, recoverWindows * 2);
            else recoverWindows = RECOVER_WINDOWS;
            slow = good = trial = 0;
            final String why = (inTrial ? "the full size's try was slow" : SLOW_WINDOWS + " windows slow") + ", draw "
                    + String.format(Locale.ROOT, "%.1f ms (slow above %.0f)", drawMs, SLOW_DRAW_MS)
                    + (Double.isNaN(gpu) ? "" : String.format(Locale.ROOT, ", GPU %.1f ms (slow above %.0f)", gpu, GPU_SLOW_MS))
                    + String.format(Locale.ROOT, ", %.1f of %.1f frames/s drawn", fresh, arrived);
            last = level == 1
                    ? String.format(Locale.ROOT, "RAW viewfinder tone reduced to ~%s MP (from %s MP): %s; full size tried again after %d good"
                    + " windows", mp(CAPS[1]), mp(fullPixels), why, recoverWindows)
                    : String.format(Locale.ROOT, "RAW viewfinder tone reduced further to ~%s MP (step %d, from %s MP): %s; the step above"
                    + " tried again after %d good windows", mp(CAPS[level]), level, mp(tonePixels), why, recoverWindows);
            return true;
        }
        slow = 0;
        if (level == 0) return false;
        // the step above: the full size from step 1, else the cap above (never above the full size)
        final long above = level == 1 ? fullPixels : Math.min(fullPixels, CAPS[level - 1]);
        final double scale = Math.max(1.0, above / (double) tonePixels);
        final double atAbove = drawMs * scale;
        final boolean isGood = share >= RECOVER_FRESH_SHARE && atAbove <= SLOW_DRAW_MS && !(gpu * scale > GPU_GOOD_MS);
        good = isGood ? good + 1 : 0;
        if (good < recoverWindows) return false;
        level--;
        slow = good = 0;
        trial = TRIAL_WINDOWS;
        final String gpuText = Double.isNaN(gpu) ? "" : String.format(Locale.ROOT, ", GPU %.1f ms (~%.1f ms there)", gpu, gpu * scale);
        last = level == 0
                ? String.format(Locale.ROOT, "RAW viewfinder tone back to full size %s MP (a try of %d windows): %d good windows, draw"
                + " %.1f ms at %s MP (~%.1f ms at full size)%s, %.1f of %.1f frames/s drawn", mp(fullPixels), TRIAL_WINDOWS,
                recoverWindows, drawMs, mp(tonePixels), atAbove, gpuText, fresh, arrived)
                : String.format(Locale.ROOT, "RAW viewfinder tone back up to ~%s MP (step %d, a try of %d windows): %d good windows, draw"
                + " %.1f ms at %s MP (~%.1f ms there)%s, %.1f of %.1f frames/s drawn", mp(CAPS[level]), level, TRIAL_WINDOWS,
                recoverWindows, drawMs, mp(tonePixels), atAbove, gpuText, fresh, arrived);
        return true;
    }
}
