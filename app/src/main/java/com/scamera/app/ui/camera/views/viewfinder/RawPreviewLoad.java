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
 */
final class RawPreviewLoad {
    static final int REDUCED_PIXELS = 1_100_000;
    static final float SLOW_DRAW_MS = 11f;
    static final float FRESH_SHARE = 0.8f;
    static final int SLOW_WINDOWS = 2;
    /** Frames drawn of those that arrived in a good window (the reduced size). */
    static final float RECOVER_FRESH_SHARE = 0.95f;
    static final int RECOVER_WINDOWS = 3, MAX_RECOVER_WINDOWS = 150;
    /** A try of the full size this many windows long: one slow window in it reduces again. */
    static final int TRIAL_WINDOWS = 3;

    /**
     * P683: the first-run GPU calibration's estimate (GpuProfile: the viewfinder tone's ns per pixel x the full size's pixels)
     * above this many ms of GPU time a frame starts the tone reduced - half a 30 fps frame, the rest left to the linear pass, the
     * upload and the filter / peaking pass.
     */
    static final float SEED_GPU_MS = 16f;

    private boolean reduced, seeded;
    private int slow, good, recoverWindows = RECOVER_WINDOWS, trial;
    private String last = "";

    boolean reduced() {
        return reduced;
    }

    /**
     * P683: the starting size from the first-run GPU calibration, once (before any window): {@code gpuMs} the estimated GPU time of
     * the full-size tone (NaN: no profile). True when it starts reduced (then {@link #last} says why); the windows correct it as for
     * a reduction of their own (the full size tried after {@link #RECOVER_WINDOWS} good windows).
     */
    boolean seed(double gpuMs, long fullPixels) {
        if (seeded) return false;
        seeded = true;
        if (reduced || !(gpuMs > SEED_GPU_MS) || fullPixels <= REDUCED_PIXELS) return false;
        reduced = true;
        slow = good = trial = 0;
        recoverWindows = RECOVER_WINDOWS;
        last = String.format(Locale.ROOT, "RAW viewfinder tone starts at ~%.1f MP (not %.1f MP): the first-run GPU calibration puts the"
                + " full-size tone at ~%.1f ms of GPU a frame (above %.0f); full size tried after %d good windows", REDUCED_PIXELS / 1e6,
                fullPixels / 1e6, gpuMs, SEED_GPU_MS, recoverWindows);
        return true;
    }

    /** The last switch's text (for the log). */
    String last() {
        return last;
    }

    /**
     * One 2 s window: {@code drawMs} the mean draw of its new frames (NaN: none drawn), {@code fresh} / {@code arrived} new frames
     * drawn / arrived per second (drawn + dropped), the tone's pixels in the window and the full (unreduced) size's, whether a shot
     * was processed during it. True when the size switches (then {@link #last} says why).
     */
    boolean window(double drawMs, double fresh, double arrived, long tonePixels, long fullPixels, boolean processing) {
        seeded = true; // P683: a measured window comes first: no calibration's start any more
        if (processing || Double.isNaN(drawMs) || !(arrived > 0) || tonePixels <= 0 || fullPixels <= 0) return false;
        final double share = Math.min(1.0, fresh / arrived);
        if (!reduced) {
            if (fullPixels <= REDUCED_PIXELS) {
                slow = trial = 0;
                return false;
            }
            final boolean isSlow = drawMs > SLOW_DRAW_MS || share < FRESH_SHARE;
            slow = isSlow ? slow + 1 : 0;
            final boolean inTrial = trial > 0;
            if (inTrial) trial--;
            if (slow < (inTrial ? 1 : SLOW_WINDOWS)) return false;
            reduced = true;
            if (inTrial) recoverWindows = Math.min(MAX_RECOVER_WINDOWS, recoverWindows * 2);
            else recoverWindows = RECOVER_WINDOWS;
            slow = good = trial = 0;
            last = String.format(Locale.ROOT, "RAW viewfinder tone reduced to ~%.1f MP (from %.1f MP): %s, draw %.1f ms (slow above %.0f),"
                            + " %.1f of %.1f frames/s drawn; full size tried again after %d good windows", REDUCED_PIXELS / 1e6,
                    fullPixels / 1e6, inTrial ? "the full size's try was slow" : SLOW_WINDOWS + " windows slow", drawMs, SLOW_DRAW_MS,
                    fresh, arrived, recoverWindows);
            return true;
        }
        final double atFull = drawMs * Math.max(1.0, fullPixels / (double) tonePixels);
        final boolean isGood = share >= RECOVER_FRESH_SHARE && atFull <= SLOW_DRAW_MS;
        good = isGood ? good + 1 : 0;
        if (good < recoverWindows) return false;
        reduced = false;
        slow = good = 0;
        trial = TRIAL_WINDOWS;
        last = String.format(Locale.ROOT, "RAW viewfinder tone back to full size %.1f MP (a try of %d windows): %d good windows, draw"
                + " %.1f ms at %.1f MP (~%.1f ms at full size), %.1f of %.1f frames/s drawn", fullPixels / 1e6, TRIAL_WINDOWS,
                recoverWindows, drawMs, tonePixels / 1e6, atFull, fresh, arrived);
        return true;
    }
}
