package com.particlesdevs.photoncamera.capture;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * P635c (owner, 2026-11-12: «PDAF судя по всему не работает с фулрезом»): what the autofocus of a full-resolution recipe
 * session does, a line a second - the AF mode the results report, the AF states of the second in their order and how often
 * they changed, the lens's focus distance (its range over the second and the frames the lens reported itself moving), the AF
 * triggers and regions of the requests. The owner's log of 2026-11-12 had the AF states of a tap only (PASSIVE_SCAN and
 * PASSIVE_FOCUSED alternating, FOCUSED_LOCKED four frames after a trigger) and no focus distance: whether the lens moved at all
 * could not be read from it.
 * <p>
 * The first {@link #ALWAYS} seconds of a session are written whatever happens, later ones only when something changed (the
 * mode, the states, a trigger, the lens moved more than {@link #MOVED} dioptres). Pure: CaptureController feeds it the total
 * results of the repeating request.
 */
public final class RecipeAfTrace {
    static final long PERIOD_MS = 1000;
    static final int ALWAYS = 10;
    static final float MOVED = 0.02f;

    private long windowStart = Long.MIN_VALUE;
    private int lines, frames, changes, moving, triggers, regionFrames;
    private int mode = -1, lastState = Integer.MIN_VALUE, lastMode = -1;
    private final StringBuilder states = new StringBuilder();
    private float low = Float.NaN, high = Float.NaN, lastLow = Float.NaN, lastHigh = Float.NaN;
    private String lastStates = "";

    /** A new camera session. */
    public void reset() {
        windowStart = Long.MIN_VALUE;
        lines = 0;
        lastMode = -1;
        lastStates = "";
        lastLow = lastHigh = Float.NaN;
        clear();
    }

    private void clear() {
        frames = changes = moving = triggers = regionFrames = 0;
        states.setLength(0);
        lastState = Integer.MIN_VALUE;
        low = high = Float.NaN;
    }

    /**
     * One total result at {@code nowMs}: the result's AF mode and state, the lens state (1: moving) and focus distance
     * (dioptres), the request's AF trigger (1: start) and whether it carried an AF region of weight. The second's line when it
     * is due, else null.
     */
    @Nullable
    public String frame(long nowMs, @Nullable Integer afMode, @Nullable Integer afState, @Nullable Integer lensState,
                        @Nullable Float focusDistance, @Nullable Integer afTrigger, boolean regions) {
        if (windowStart == Long.MIN_VALUE) windowStart = nowMs;
        frames++;
        if (afMode != null) mode = afMode;
        final int state = afState == null ? -1 : afState;
        if (state != lastState) {
            if (frames > 1) changes++;
            if (states.length() < 96) states.append(states.length() == 0 ? "" : ">").append(stateName(state));
            lastState = state;
        }
        if (lensState != null && lensState == 1) moving++;
        if (focusDistance != null && !Float.isNaN(focusDistance)) {
            low = Float.isNaN(low) ? focusDistance : Math.min(low, focusDistance);
            high = Float.isNaN(high) ? focusDistance : Math.max(high, focusDistance);
        }
        if (afTrigger != null && afTrigger == 1) triggers++;
        if (regions) regionFrames++;
        if (nowMs - windowStart < PERIOD_MS) return null;
        windowStart = nowMs;
        final String seen = states.toString();
        final boolean lensMoved = moving > 0 || differs(low, lastLow) || differs(high, lastHigh);
        final boolean changed = mode != lastMode || !seen.equals(lastStates) || triggers > 0 || lensMoved;
        String line = null;
        if (lines < ALWAYS || changed) {
            lines++;
            line = String.format(Locale.ROOT, "mode %s, states %s (%d changes in %d frames), lens %s, moving %d frames, AF triggers %d, AF region %s",
                    modeName(mode), seen, changes, frames,
                    Float.isNaN(low) ? "not reported" : Math.abs(high - low) < 0.0005f ? String.format(Locale.ROOT, "%.3f dpt", low)
                            : String.format(Locale.ROOT, "%.3f..%.3f dpt", low, high),
                    moving, triggers, regionFrames == 0 ? "none" : regionFrames == frames ? "set" : "set on " + regionFrames + " frames");
        }
        lastMode = mode;
        lastStates = seen;
        lastLow = low;
        lastHigh = high;
        clear();
        return line;
    }

    private static boolean differs(float a, float b) {
        if (Float.isNaN(a) || Float.isNaN(b)) return Float.isNaN(a) != Float.isNaN(b);
        return Math.abs(a - b) > MOVED;
    }

    static String modeName(int mode) {
        switch (mode) {
            case 0: return "OFF";
            case 1: return "AUTO";
            case 2: return "MACRO";
            case 3: return "CONTINUOUS_VIDEO";
            case 4: return "CONTINUOUS_PICTURE";
            case 5: return "EDOF";
            default: return "not reported";
        }
    }

    static String stateName(int state) {
        switch (state) {
            case 0: return "INACTIVE";
            case 1: return "PASSIVE_SCAN";
            case 2: return "PASSIVE_FOCUSED";
            case 3: return "ACTIVE_SCAN";
            case 4: return "FOCUSED_LOCKED";
            case 5: return "NOT_FOCUSED_LOCKED";
            case 6: return "PASSIVE_UNFOCUSED";
            default: return "none";
        }
    }
}
