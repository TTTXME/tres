package com.particlesdevs.photoncamera.settings;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle;

/**
 * P630 «Oldstyle» (owner, 2026-11-11): the third camera mode of the viewfinder's mode switch, beside «Спорт» and «Фото». The photo
 * mode's capture and merge, with three things of its own:
 * <ul>
 *   <li>the tone: «Oldstyle V1» (the 2.7 look) or «Oldstyle V2» (the 3.1 look) of the owner's tone-port module
 *       (third_party/tone_port, OldstyleNative, postpipeline/OldstyleTone; P630b) instead of «Why тон» - only in this mode (the
 *       group «Oldstyle Тон» of the settings);</li>
 *   <li>the manual controls always open (EV, ISO, S, WB, AF of the «Про» panel, its own values: {@link #swapManual});</li>
 *   <li>the style (P630c, OldstyleStyle): the palette's «Тон» / «Цвет» and twelve advanced parameters - the same maths in the
 *       viewfinder (preview/main_fs, utils/import_oldstyle_adjust) and the photo (postpipeline/OldstyleStyleNode).</li>
 * </ul>
 * The mode flag is global (pref_oldstyle_mode), as «Спорт»'s; the two never together ({@link #select}).
 */
public final class OldstyleMode {
    private OldstyleMode() {}

    public static final String PREF = "pref_oldstyle_mode";
    /** The tone version of the mode (module-local as every tone row): "v1" (2.7) or "v2" (3.1). */
    public static final String VERSION = "pref_scam_hybrid_oldstyle_version";
    /** P630c: the style's values (global): pref_oldstyle_style_&lt;OldstyleStyle.Param.key&gt;. */
    public static final String STYLE = "pref_oldstyle_style_";
    /** The manual controls' values of the «Про» panel (circularbarlib ManualModel: pref_manual_&lt;model&gt;). */
    static final String[] MANUAL_MODELS = {"evmodel", "isomodel", "shuttermodel", "whitebalancemodel", "focusmodel"};
    static final String MANUAL = "pref_manual_";
    /** Where one mode keeps the other mode's manual values while it is away. */
    static final String OLDSTYLE_MANUAL = "pref_oldstyle_manual_", PHOTO_MANUAL = "pref_photo_manual_";

    /** The camera modes of the switch. */
    public enum Mode { SPORT, PHOTO, OLDSTYLE }

    private static volatile Boolean testOverride;

    public static boolean on() {
        if (testOverride != null) return testOverride;
        try {
            final SettingsManager m = com.particlesdevs.photoncamera.app.ScameraApp.getSettingsManagerStatic();
            return m != null && m.getBoolean(SettingsManager.SCOPE_GLOBAL, PREF, false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Tests: the mode without a settings manager (null: the stored one). */
    public static void overrideForTest(@Nullable Boolean value) { testOverride = value; }

    public static Mode current() {
        if (on()) return Mode.OLDSTYLE;
        return MotionMode.on() ? Mode.SPORT : Mode.PHOTO;
    }

    /**
     * Chooses a mode: the two flags (pref_motion_mode, pref_oldstyle_mode) as the mode says, and the manual values moved when
     * Oldstyle is entered or left ({@link #swapManual}). Returns whether anything changed (the caller restarts the camera then).
     */
    public static boolean select(Mode mode, @Nullable SharedPreferences manual) {
        final Mode was = current();
        if (was == mode) return false;
        final SettingsManager m = com.particlesdevs.photoncamera.app.ScameraApp.getSettingsManagerStatic();
        if (m != null) {
            m.set(SettingsManager.SCOPE_GLOBAL, PREF, mode == Mode.OLDSTYLE);
            m.set(SettingsManager.SCOPE_GLOBAL, MotionMode.PREF, mode == Mode.SPORT);
        }
        if (manual != null && (was == Mode.OLDSTYLE) != (mode == Mode.OLDSTYLE)) swapManual(manual, mode == Mode.OLDSTYLE);
        return true;
    }

    /**
     * «values persist per mode»: the «Про» panel's stored values (pref_manual_&lt;model&gt;, read back by the panel when the
     * camera opens) are the mode's own. Entering Oldstyle keeps the photo modes' values under pref_photo_manual_* and puts Oldstyle's
     * (pref_oldstyle_manual_*) in their place; leaving does the reverse. A value a mode never set is no key (Auto).
     */
    public static void swapManual(SharedPreferences p, boolean entering) {
        final String keep = entering ? PHOTO_MANUAL : OLDSTYLE_MANUAL, take = entering ? OLDSTYLE_MANUAL : PHOTO_MANUAL;
        final SharedPreferences.Editor e = p.edit();
        for (String model : MANUAL_MODELS) {
            final String live = MANUAL + model;
            if (p.contains(live)) e.putString(keep + model, p.getString(live, "")); else e.remove(keep + model);
            if (p.contains(take + model)) e.putString(live, p.getString(take + model, "")); else e.remove(live);
        }
        e.commit();
    }

    /** «Oldstyle V2» (3.1) chosen; else V1 (2.7). */
    public static boolean v2() {
        return "v2".equals(PreferenceKeys.hybridString("oldstyle_version", "v1"));
    }

    public static void setV2(boolean v2) {
        final SettingsManager m = com.particlesdevs.photoncamera.app.ScameraApp.getSettingsManagerStatic();
        if (m != null) m.set(SettingsManager.SCOPE_GLOBAL, VERSION, v2 ? "v2" : "v1");
    }

    /** The style now (the viewfinder's; a shot takes a snapshot at the press, ShotLook). */
    public static OldstyleStyle style() {
        final OldstyleStyle st = new OldstyleStyle();
        final SettingsManager m = com.particlesdevs.photoncamera.app.ScameraApp.getSettingsManagerStatic();
        if (m == null) return st;
        for (OldstyleStyle.Param p : OldstyleStyle.Param.values()) {
            try {
                st.set(p, Integer.parseInt(m.getString(SettingsManager.SCOPE_GLOBAL, STYLE + p.key, "0")));
            } catch (RuntimeException e) {
                st.set(p, 0);
            }
        }
        return st;
    }

    public static void setStyle(OldstyleStyle.Param p, int value) {
        final SettingsManager m = com.particlesdevs.photoncamera.app.ScameraApp.getSettingsManagerStatic();
        if (m != null) m.set(SettingsManager.SCOPE_GLOBAL, STYLE + p.key, Integer.toString(p.clamp(value)));
    }

    /** «Сбросить стиль по умолчанию?»: every parameter back to 0 (the base «Natural» as it is). */
    public static void resetStyle() {
        for (OldstyleStyle.Param p : OldstyleStyle.Param.values()) setStyle(p, 0);
    }

    /**
     * The tone's settings of the version (the group «Oldstyle Тон»). P630e: the exposure plan is the owner's supplement; the rows
     * were the gains' limit M, V2's saturation_adjustment and levels, V1's low-frequency colour, and the supplement's YUV-route
     * stages on our route (dehaze / tint, output saturation). P630h (owner: «Добавь больше настроек для Oldstyle»): the numbers of
     * the supplement's rules, of the module's calls and of our glue each a row too ({@link #ROWS}); every default the behaviour
     * before the rows - a stored value at its row's default reads as the exact number ({@link #value}).
     */
    public static com.particlesdevs.photoncamera.processing.oldstyle.OldstyleAe.Params params(boolean v2) {
        return params(v2, STORED);
    }

    /** Where the rows' values come from: the settings ({@link #STORED}), or a test's own. Keys without pref_scam_hybrid_. */
    interface Source {
        float number(String key, float fallback);

        boolean flag(String key, boolean fallback);

        String text(String key, String fallback);
    }

    static final Source STORED = new Source() {
        @Override public float number(String key, float fallback) { return PreferenceKeys.hybridValue(key, fallback); }
        @Override public boolean flag(String key, boolean fallback) { return PreferenceKeys.hybridSwitch(key, fallback); }
        @Override public String text(String key, String fallback) { return PreferenceKeys.hybridString(key, fallback); }
    };

    static com.particlesdevs.photoncamera.processing.oldstyle.OldstyleAe.Params params(boolean v2, Source s) {
        final com.particlesdevs.photoncamera.processing.oldstyle.OldstyleAe.Params p =
                new com.particlesdevs.photoncamera.processing.oldstyle.OldstyleAe.Params();
        p.v31 = v2;
        p.maxGain = value(s, "oldstyle_max_gain", p.maxGain);
        p.saturation = value(s, "oldstyle_v2_saturation", p.saturation);
        p.lowFrequency = s.flag("oldstyle_v1_lowfreq", p.lowFrequency);
        p.lfThreshold = Math.round(value(s, "oldstyle_v1_lowfreq_threshold", p.lfThreshold));
        p.dehaze = s.flag("oldstyle_dehaze", p.dehaze);
        p.maxDark = Math.round(value(s, "oldstyle_dehaze_max_dark", p.maxDark));
        p.percentile = value(s, "oldstyle_dehaze_percentile", p.percentile);
        p.cornerStrength = value(s, "oldstyle_dehaze_corner", p.cornerStrength);
        p.dehazeShading = s.flag("oldstyle_dehaze_shading", p.dehazeShading);
        p.outSat = value(s, "oldstyle_out_sat", p.outSat);
        p.vibrance = value(s, "oldstyle_out_vibrance", p.vibrance);
        p.satPerStop = value(s, "oldstyle_out_sat_per_stop", p.satPerStop);
        p.vibrancePerStop = value(s, "oldstyle_out_vibrance_per_stop", p.vibrancePerStop);
        // ---- P630h ----
        // «Экспозиция и HDR»
        final String hdr = s.text("oldstyle_hdr", "auto");
        p.hdrMode = "on".equals(hdr) ? com.particlesdevs.photoncamera.processing.oldstyle.OldstyleAe.HDR_ON
                : "off".equals(hdr) ? com.particlesdevs.photoncamera.processing.oldstyle.OldstyleAe.HDR_OFF
                : com.particlesdevs.photoncamera.processing.oldstyle.OldstyleAe.HDR_AUTO;
        p.baseEv = value(s, "oldstyle_ev", p.baseEv);
        p.shortWhite = value(s, "oldstyle_highlight_target", p.shortWhite);
        p.highPercentile = scaled(s, "oldstyle_highlight_percentile", p.highPercentile, 100f);
        p.shadowEv = value(s, "oldstyle_shadow_ev", p.shadowEv);
        p.centerSigma = value(s, "oldstyle_center", p.centerSigma);
        p.fusionBase = value(s, "oldstyle_fusion_base", p.fusionBase);
        p.v1HdrMin = value(s, "oldstyle_v1_hdr_min", p.v1HdrMin);
        p.v1HdrMax = value(s, "oldstyle_v1_hdr_max", p.v1HdrMax);
        p.v1RatioCap = value(s, "oldstyle_v1_hdr_cap", p.v1RatioCap);
        p.v2HdrMin = value(s, "oldstyle_v2_hdr_min", p.v2HdrMin);
        p.v2RatioMax = value(s, "oldstyle_v2_hdr_max", p.v2RatioMax);
        p.v2Forgiveness = value(s, "oldstyle_v2_hdr_forgive", p.v2Forgiveness);
        // «Тон и кривая»
        p.curveMid = value(s, "oldstyle_curve_mid", p.curveMid);
        p.curveContrast = value(s, "oldstyle_curve_contrast", p.curveContrast);
        p.memoryShare = scaled(s, "oldstyle_v2_memory", p.memoryShare, 100f);
        // «Цвет»
        p.ccmStrength = value(s, "oldstyle_v1_ccm", p.ccmStrength);
        p.lfStrength = value(s, "oldstyle_v1_lowfreq_strength", p.lfStrength);
        p.lfChroma = s.flag("oldstyle_v1_lowfreq_chroma", p.lfChroma);
        // «Серия»
        p.burstFrames = Math.round(value(s, "oldstyle_burst_frames", p.burstFrames));
        p.burstSeconds = value(s, "oldstyle_burst_time", p.burstSeconds);
        p.shutterFloorMs = value(s, "oldstyle_shutter_floor", p.shutterFloorMs);
        // «Видоискатель»
        p.vfTone = s.flag("oldstyle_vf_tone", p.vfTone);
        p.vfEv = value(s, "oldstyle_vf_ev", p.vfEv);
        p.vfMatch = value(s, "oldstyle_vf_match", p.vfMatch);
        p.vfSmooth = value(s, "oldstyle_vf_smooth", p.vfSmooth);
        return p;
    }

    /**
     * A number row's value within its range (SettingsNumericRules: a stored or scam_dev.txt value past an end is the end - the ends
     * are what the module was checked at, tools/check_tone_port.py), {@code fallback} when it is absent, not a number, or at the
     * row's own default: the XML default of a row is its number rounded for the slider (0.9756 for 0.9756097793579102, 6.8 for
     * 6.804, 16.7 ms for 1/60 s) - within half a slider step of {@code fallback} it is {@code fallback}, so a row put back to its
     * default gives the picture of the row never touched.
     */
    static float value(Source s, String key, float fallback) {
        final float v = s.number(key, fallback);
        if (!Float.isFinite(v)) return fallback;
        final Float step = STEPS.get(key);
        if (Math.abs(v - fallback) <= (step != null ? step * 0.5f : 0f) + 1e-5f * Math.max(1f, Math.abs(fallback))) return fallback;
        final double[] range = SettingsNumericRules.bounds("pref_scam_hybrid_" + key);
        return range == null ? v : (float) Math.max(range[0], Math.min(range[1], v));
    }

    /** {@link #value} of a row shown in other units ({@code scale} of the number: percent); at the default the exact number. */
    private static float scaled(Source s, String key, float fallback, float scale) {
        final float shown = fallback * scale, v = value(s, key, shown);
        return v == shown ? fallback : v / scale;
    }

    /** The slider step of the rows whose XML default is their number rounded (half of it is "at the default"). */
    private static final java.util.Map<String, Float> STEPS = new java.util.HashMap<>();
    static {
        STEPS.put("oldstyle_fusion_base", 0.0001f);
        STEPS.put("oldstyle_v1_hdr_max", 0.01f);
        STEPS.put("oldstyle_shutter_floor", 0.1f);
    }

    /** V2: the pyramid of a full frame (10 levels) or of a postview (8): pref_scam_hybrid_oldstyle_v2_levels. */
    public static boolean v2Postview() {
        return "8".equals(PreferenceKeys.hybridString("oldstyle_v2_levels", "10"));
    }

    private static final String K = "pref_scam_hybrid_oldstyle_";
    /** The settings rows of each version (SettingsAvailability greys the other version's: the keys' v1_ / v2_). */
    public static final String[] V1_ROWS = {K + "v1_lowfreq", K + "v1_lowfreq_threshold",
            // P630h
            K + "v1_hdr_min", K + "v1_hdr_max", K + "v1_hdr_cap", K + "v1_ccm", K + "v1_lowfreq_strength", K + "v1_lowfreq_chroma"};
    public static final String[] V2_ROWS = {K + "v2_saturation", K + "v2_levels",
            // P630h
            K + "v2_hdr_min", K + "v2_hdr_max", K + "v2_hdr_forgive", K + "v2_memory"};
    /** P630e: the rows of both versions (the gains' limit, the supplement's stages on our route); P630h: the rest. */
    public static final String[] COMMON_ROWS = {K + "max_gain", K + "dehaze", K + "dehaze_max_dark", K + "dehaze_percentile",
            K + "dehaze_corner", K + "dehaze_shading", K + "out_sat", K + "out_vibrance", K + "out_sat_per_stop",
            K + "out_vibrance_per_stop",
            // P630h
            K + "hdr", K + "ev", K + "highlight_target", K + "highlight_percentile", K + "shadow_ev", K + "center", K + "fusion_base",
            K + "curve_mid", K + "curve_contrast", K + "burst_frames", K + "burst_time", K + "shutter_floor",
            K + "vf_tone", K + "vf_ev", K + "vf_match", K + "vf_smooth"};
    /** P630h: the group's sub-categories, in the page's order. */
    public static final String[] CATEGORIES = {"oldstyle_exposure_category", "oldstyle_curve_category", "oldstyle_colour_category",
            "oldstyle_dehaze_category", "oldstyle_burst_category", "oldstyle_viewfinder_category"};
    /** P630h «Сбросить Oldstyle»: the row that puts every row of the categories back to its default (the version stays). */
    public static final String RESET = "pref_oldstyle_tone_reset";
}
