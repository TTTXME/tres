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
     * The tone's settings of the version (the group «Oldstyle Тон»). P630e: the exposure plan is the owner's supplement (its fixed
     * ratios, no rows); the rows are the gains' limit M, V2's saturation_adjustment and levels, V1's low-frequency colour, and the
     * supplement's YUV-route stages on our route (dehaze / tint, output saturation); defaults the module's behaviour.
     */
    public static com.particlesdevs.photoncamera.processing.oldstyle.OldstyleAe.Params params(boolean v2) {
        final com.particlesdevs.photoncamera.processing.oldstyle.OldstyleAe.Params p =
                new com.particlesdevs.photoncamera.processing.oldstyle.OldstyleAe.Params();
        p.v31 = v2;
        p.maxGain = PreferenceKeys.hybridValue("oldstyle_max_gain", p.maxGain);
        p.saturation = PreferenceKeys.hybridValue("oldstyle_v2_saturation", p.saturation);
        p.lowFrequency = PreferenceKeys.hybridSwitch("oldstyle_v1_lowfreq", p.lowFrequency);
        p.lfThreshold = Math.round(PreferenceKeys.hybridValue("oldstyle_v1_lowfreq_threshold", p.lfThreshold));
        p.dehaze = PreferenceKeys.hybridSwitch("oldstyle_dehaze", p.dehaze);
        p.maxDark = Math.round(PreferenceKeys.hybridValue("oldstyle_dehaze_max_dark", p.maxDark));
        p.percentile = PreferenceKeys.hybridValue("oldstyle_dehaze_percentile", p.percentile);
        p.cornerStrength = PreferenceKeys.hybridValue("oldstyle_dehaze_corner", p.cornerStrength);
        p.dehazeShading = PreferenceKeys.hybridSwitch("oldstyle_dehaze_shading", p.dehazeShading);
        p.outSat = PreferenceKeys.hybridValue("oldstyle_out_sat", p.outSat);
        p.vibrance = PreferenceKeys.hybridValue("oldstyle_out_vibrance", p.vibrance);
        p.satPerStop = PreferenceKeys.hybridValue("oldstyle_out_sat_per_stop", p.satPerStop);
        p.vibrancePerStop = PreferenceKeys.hybridValue("oldstyle_out_vibrance_per_stop", p.vibrancePerStop);
        return p;
    }

    /** V2: the pyramid of a full frame (10 levels) or of a postview (8): pref_scam_hybrid_oldstyle_v2_levels. */
    public static boolean v2Postview() {
        return "8".equals(PreferenceKeys.hybridString("oldstyle_v2_levels", "10"));
    }

    /** The settings rows of each version (SettingsAvailability greys the other version's). */
    public static final String[] V1_ROWS = {"pref_scam_hybrid_oldstyle_v1_lowfreq", "pref_scam_hybrid_oldstyle_v1_lowfreq_threshold"};
    public static final String[] V2_ROWS = {"pref_scam_hybrid_oldstyle_v2_saturation", "pref_scam_hybrid_oldstyle_v2_levels"};
    /** P630e: the rows of both versions (the gains' limit, the supplement's stages on our route). */
    public static final String[] COMMON_ROWS = {"pref_scam_hybrid_oldstyle_max_gain", "pref_scam_hybrid_oldstyle_dehaze",
            "pref_scam_hybrid_oldstyle_dehaze_max_dark", "pref_scam_hybrid_oldstyle_dehaze_percentile",
            "pref_scam_hybrid_oldstyle_dehaze_corner", "pref_scam_hybrid_oldstyle_dehaze_shading", "pref_scam_hybrid_oldstyle_out_sat",
            "pref_scam_hybrid_oldstyle_out_vibrance", "pref_scam_hybrid_oldstyle_out_sat_per_stop",
            "pref_scam_hybrid_oldstyle_out_vibrance_per_stop"};
}
