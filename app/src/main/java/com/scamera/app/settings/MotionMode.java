package com.scamera.app.settings;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
import android.util.Size;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * P86 Photo mode «Движение» (owner, 2026-10-11: «максимально хорошее движение и максимально быстрый буфер чтобы успевать ловить
 * всё»; the switch beside the settings gear in the viewfinder's top bar). While it is on:
 * <ul>
 *   <li>the frame rate is the module's («Частота кадров RAW», P266; owner, 2026-10-19: «фпс ... автоматически», by hand
 *       there): the mode's own rate row (the camera's fastest fixed rate) is gone - one setting for one thing;</li>
 *   <li>P110: the shutter of the ring frames has a ceiling (pref_scam_hybrid_mm_shutter, 1/250 s; manual/MotionShutter):
 *       a longer shutter of the camera's AE becomes the ceiling with the ISO raised (the fixed frame rate alone left 1/30 s,
 *       the photo mode's own indoor shutter);</li>
 *   <li>P111, after the stock vivo «Моментальный снимок» (motion deblur): the ceiling drops by how fast the scene moves
 *       (capture/MotionMeter on the ring's RAW, MotionShutter.Levels), the moment is the frame on the screen at the touch of the
 *       shutter (capture v2 anchors two ring frames earlier), ring frames with a longer shutter stay out, the frames after the shutter keep
 *       the ceiling, the moving subject keeps its detail (no widened base kernel), lamp flicker is checked on the frames;</li>
 *   <li>the hybrid takes its own values for the merge (pref_scam_hybrid_mm_&lt;key&gt;, the «Движение» group of the Hybrid
 *       settings) instead of the photo values: fewer N frames, a speed-limited donor window, no bracketed frames,
 *       and the strongest motion robustness. Sharpness priority reduces luma filtering where donors were rejected.</li>
 * </ul>
 * The mode flag is global (not per module); its merge values follow the module profiles like every hybrid setting.
 */
public final class MotionMode {
    public static final String PREF = "pref_motion_mode";
    /** Prefix (after pref_scam_hybrid_) of the mode's own merge values. */
    static final String PREFIX = "mm_";
    private static final ThreadLocal<Snapshot> SHOT = new ThreadLocal<>();

    public static final class Snapshot {
        final boolean on, captureV2, sharpPriority;
        /** P501: the exposure the «Движение» shutter ceiling took from the RAW at the press (1: none; {@link #underGain}). */
        final float underGain;
        Snapshot(boolean on, boolean captureV2, boolean sharpPriority, float underGain) {
            this.on = on;
            this.captureV2 = captureV2;
            this.sharpPriority = sharpPriority;
            this.underGain = underGain;
        }
    }

    public static Snapshot snapshot() {
        Snapshot s = SHOT.get();
        if (s != null) return s;
        final boolean enabled = on();
        return new Snapshot(enabled, enabled && PreferenceKeys.scamDevSwitch("motion_capture_v2", true),
                enabled && PreferenceKeys.scamDevSwitch("motion_sharp_priority", true), enabled ? liveUnderGain : 1f);
    }

    /**
     * P501 (owner, 2026-11-04, vivo X200 Ultra at night: «Ты не исправил экспозицию в спорт режиме, всё ещё темнит сильно»): the
     * ceiling keeps the moving scene's short shutter below the camera AE's exposure (ParamController: «1.58 stops under, viewfinder
     * gain»), and the viewfinder and the photo are developed from that RAW by the WHY tone, whose noise clamp then held the gain at
     * 1.0 at ISO 6400 - both came out that much darker than the same scene without the mode. The ratio the ceiling took (its
     * underRatio, set by ParamController on every viewfinder result) widens the tone's gain limits by as much: the brightness of
     * the photo mode with the mode's noise. 1 without the mode or with the ceiling off.
     */
    private static volatile float liveUnderGain = 1f;
    public static void setLiveUnderGain(double gain) {
        liveUnderGain = Double.isFinite(gain) && gain > 1 ? (float) Math.min(gain, 64) : 1f;
    }
    /** P501: the shot's (its press's snapshot) or else the viewfinder's under-exposure gain, at least 1. */
    public static float underGain() {
        Snapshot s = SHOT.get();
        if (s != null) return s.underGain;
        return on() ? liveUnderGain : 1f;
    }

    public static void beginShot(Snapshot snapshot) {
        SHOT.set(snapshot != null ? snapshot : snapshot());
    }

    public static void endShot() { SHOT.remove(); }

    static Boolean shotOn() {
        Snapshot s = SHOT.get();
        return s == null ? null : s.on;
    }

    /** The hybrid keys the mode replaces and their defaults (values of the «Движение» group). */
    static final Map<String, Float> DEFAULTS;
    static {
        Map<String, Float> d = new HashMap<>();
        d.put("zsl_frames", 8f);        // up to 8 ring frames, before the temporal and sharpness filters
        d.put("shasta", 0f);            // switch: no bracketed long frames
        d.put("bento", 0f);             // no extra-short frames in motion mode
        d.put("boost_value", 10f);      // motion robustness boost at its maximum
        d.put("motion_threshold", 0.5f); // tiles count as moving from 0.5 RAW px of local flow (photo: 1.5)
        d.put("motion_boost", 1f);      // switch: the robustness boost on
        // P111: no widened base kernel where donors were rejected (the moving subject): its detail stays, the denoise (stronger
        // there through the effective-frame map) takes the noise
        d.put("widen_below", 0f);
        // P471: the moving subject's luma keeps all its detail in the resolve denoise (rejected / moving areas revert it fully,
        // photo 0.5; the reference app's sport mode does the same); its chroma and the later coverage-aware denoise stay
        // P471 revisited (owner, 2026-11-03, A/B on the moving burst 172902: «0.5 выглядит лучше»): 1.0 only added grain on the
        // motion-blurred subject (no real detail there), so «Спорт» keeps the photo's 0.5
        d.put("why_dn_motion_revert", 0.5f);
        DEFAULTS = Collections.unmodifiableMap(d);
    }
    private MotionMode() {}

    public static boolean on() {
        return PreferenceKeys.isMotionMode();
    }

    public static boolean captureV2() {
        Snapshot s = SHOT.get();
        if (s != null) return s.captureV2;
        return on() && PreferenceKeys.scamDevSwitch("motion_capture_v2", true);
    }

    public static boolean sharpPriority() {
        Snapshot s = SHOT.get();
        if (s != null) return s.sharpPriority;
        return on() && PreferenceKeys.scamDevSwitch("motion_sharp_priority", true);
    }

    public static void set(boolean value) {
        PreferenceKeys.setMotionMode(value);
    }

    /**
     * P638: «Спорт» was switched on outside the viewfinder's mode switch - the shade's tile, the settings' switch, a restored
     * backup - while «Oldstyle» is the camera's mode. Both flags stayed on: the switch went on showing «Oldstyle» with its manual
     * row and its tone in the viewfinder, while the shots were «Спорт»'s (the ring alone at the shutter ceiling, the mode's merge
     * values) under the Oldstyle tone. The mode becomes «Спорт» as the switch makes it (OldstyleMode.select: the Oldstyle flag off,
     * the photo modes' manual values back in {@code manual}). True when it changed the mode: the camera's screen follows
     * ({@link #takeSettled}), and the caller restarts a running session where nothing else does.
     */
    public static boolean settleWithOldstyle(@Nullable android.content.SharedPreferences manual) {
        try {
            if (!PreferenceKeys.isMotionMode() || !OldstyleMode.on()) return false;
            if (!OldstyleMode.select(OldstyleMode.Mode.SPORT, manual)) return false;
        } catch (RuntimeException noSettings) {
            return false;
        }
        settled = true;
        return true;
    }

    private static volatile boolean settled;

    /** P638: whether {@link #settleWithOldstyle} changed the mode since the last call (the camera's screen takes it once). */
    public static boolean takeSettled() {
        final boolean was = settled;
        settled = false;
        return was;
    }

    /**
     * P113: ring frames a «Движение» shot takes while {@code pending} earlier shots wait for their merge: all it wants with an
     * empty queue, at most 4 behind 2-3 shots, 2 behind more (the stock snapshot mode drops to fewer frames, down to one, when its
     * capture queue fills): a fast series keeps going without the memory and the merge time of full bursts piling up.
     */
    public static int framesForQueue(int wanted, int pending) {
        if (pending >= 4) return Math.max(1, Math.min(wanted, 2));
        if (pending >= 2) return Math.max(1, Math.min(wanted, 4));
        return wanted;
    }

    /** P488: ring frames a fast ring may give one shot (SPORT-COMPARISON item 5: 60 fps holds ~15 frames in the 250 ms window). */
    static final int FAST_RING_FRAMES = 12;
    /** P488: the longest median frame gap of a «fast» ring (60 fps: 16.7 ms; 30 fps rings keep the mode's count). */
    static final long FAST_RING_GAP_NS = 20_000_000L;

    /**
     * P488: the ring frames of a capture v2 shot after its donor window: at a fast ring ({@code medianGapNs} at most FAST_RING_GAP_NS)
     * with no earlier shot waiting for its merge, up to FAST_RING_FRAMES of the {@code inWindow} frames inside the window instead of
     * {@code wanted}; a smaller count set for the mode (below its default) or any queue keeps {@code wanted} (framesForQueue's cut).
     */
    public static int ringFrames(int wanted, int pending, int inWindow, long medianGapNs) {
        if (pending > 0 || medianGapNs <= 0 || medianGapNs > FAST_RING_GAP_NS || wanted < Math.round(DEFAULTS.get("zsl_frames"))) return wanted;
        return Math.max(wanted, Math.min(FAST_RING_FRAMES, inWindow));
    }

    /** Whether the mode replaces this hybrid key (after pref_scam_hybrid_). */
    public static boolean replaces(String key) {
        return DEFAULTS.containsKey(key);
    }

    /** The mode's own preference of a hybrid key it replaces: pref_scam_hybrid_mm_&lt;key&gt; (the lab compares it on a «Движение» shot). */
    public static String prefKey(String key) {
        return "pref_scam_hybrid_" + PREFIX + key;
    }

}
