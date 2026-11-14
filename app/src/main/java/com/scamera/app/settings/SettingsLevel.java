package com.scamera.app.settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P708 (owner, 2026-11-14: «Делай» - a user's idea: a Simple mode that presets everything, so that a non-expert gets the app's full
 * potential from a few settings with the technical ones hidden, and an Advanced mode exactly as now): the level of every settings
 * row, SIMPLE or ADVANCED, and the settings mode itself.
 * <p>
 * A row is shown in the Simple mode when it and every group and page above it on its page are declared SIMPLE ({@link #shown}).
 * Every row of the main page and of a SIMPLE group or page declares its level here, by its key or (rows built in code) by its
 * key's prefix ({@link #missing}: tools/check_settings_model.py and SimpleModeTest fail on a row without one); the rows of an
 * ADVANCED group or page are ADVANCED with it. Pure Java (the settings model check compiles it with a JDK only).
 * <p>
 * The mode changes what is shown, never a value: hidden rows keep their values, and their defaults stay this phone's own
 * (DeviceDefaults, the per-sensor logic). {@link #MODE_KEY} and {@link #WIZARD_KEY} are settings_ keys: one for every module, and
 * a config of another phone does not bring its mode (BackupRestoreUtil.deviceOnly).
 */
public final class SettingsLevel {
    private SettingsLevel() {}

    public enum Level { SIMPLE, ADVANCED }

    /**
     * The settings mode: {@link #SIMPLE_MODE}, {@link #CUSTOM_MODE} (the user's own set, CustomSet) or {@link #ADVANCED_MODE}; none
     * stored reads as Advanced (an install from before P708).
     */
    public static final String MODE_KEY = "settings_mode", SIMPLE_MODE = "simple", CUSTOM_MODE = "custom", ADVANCED_MODE = "advanced";
    /** True from a fresh install until the first-run wizard was finished or closed (SettingsWizard). */
    public static final String WIZARD_KEY = "settings_wizard_pending";
    /**
     * The main page's rows built in code (SimpleMode): the mode switch, «Настроить…» of the Custom mode, the wizard again, the banner
     * of changed hidden rows. They belong to the mode itself: no mode hides them ({@link #modeRow}).
     */
    public static final String MODE_ROW = MODE_KEY, CUSTOM_ROW = "settings_custom_edit", WIZARD_ROW = "settings_wizard",
            BANNER_ROW = "settings_mode_banner";
    /** The key of the main page (its own level is not asked). */
    public static final String ROOT = "prefscreen";

    /** ShadeCatalog.MIRROR: «Не зеркалить селфи», a generated row moved to «Съёмка» (TunablePreferenceGenerator). */
    static final String MIRROR = "pref_tunable_parameters_disablemirror";

    private static final Map<String, Level> KEYS = new HashMap<>();
    /** Rows built in code, by the start of their keys (the longest declared start wins). */
    private static final Map<String, Level> PREFIXES = new HashMap<>();
    /** Declared rows that are built in code, not in preferences.xml (the model check knows them so). */
    static final Set<String> CODE_ROWS = new LinkedHashSet<>();

    private static void simple(String... keys) {
        for (String k : keys) KEYS.put(k, Level.SIMPLE);
    }

    private static void advanced(String... keys) {
        for (String k : keys) KEYS.put(k, Level.ADVANCED);
    }

    static {
        // ── the main page: the mode itself, then the processing mode; the merge, colour fringe, tone version, Oldstyle, lab and
        // logs are the experts' (their pages are hidden whole)
        simple(MODE_ROW, CUSTOM_ROW, WIZARD_ROW, BANNER_ROW);
        CODE_ROWS.addAll(Arrays.asList(MODE_ROW, CUSTOM_ROW, WIZARD_ROW, BANNER_ROW));
        simple("pref_scam_hybrid_processing", "tone_settings_screen", "root_lenses", "root_photo", "root_shoot", "root_colour",
                "root_about");
        advanced("scam_hybrid_dnmerge_screen", "pref_scam_hybrid_fringe_method", "fringe_net_screen", "pref_scam_hybrid_tone_version",
                "oldstyle_tone_screen", "root_lab", "root_logs");
        // ── «Настройки тона»: the three everyday sliders (brightness, contrast, saturation); the tone's own pages stay the experts'
        simple("pref_scam_hybrid_why_ae_target", "pref_scam_hybrid_why_macro_contrast", "pref_scam_hybrid_why_agx_sat");
        advanced("scam_hybrid_why_ae_screen", "scam_hybrid_why_curves_screen", "scam_hybrid_why_tone_screen",
                "scam_hybrid_hdr_shoulder_category", "scam_hybrid_why_highlights_category", "scam_hybrid_why_artifacts_category");
        // ── «Модули камеры и линзы»: the module cards (name, zoom, the viewfinder button); the lens page (sensor mode, camera keys,
        // RAW stream, levels, colour matrix, noise model) and the module copies / IDs / FULLRES profiles are the experts'
        simple("modules_screen", "pref_xiaomi_smooth_zoom", ModuleRowKeys.GROUP, ModuleRowKeys.NONE);
        advanced("lens_screen", ModuleRowKeys.ACTIONS);
        CODE_ROWS.addAll(Arrays.asList(ModuleRowKeys.NONE, ModuleRowKeys.ACTIONS));
        PREFIXES.put(ModuleRowKeys.CARD, Level.SIMPLE);
        PREFIXES.put(ModuleRowKeys.NAME, Level.SIMPLE);
        PREFIXES.put(ModuleRowKeys.ZOOM, Level.SIMPLE);
        PREFIXES.put(ModuleRowKeys.BUTTON, Level.SIMPLE);
        for (String p : new String[]{ModuleRowKeys.CARD_ID, ModuleRowKeys.ID, ModuleRowKeys.CROP, ModuleRowKeys.COPY,
                ModuleRowKeys.DELETE, ModuleRowKeys.LENS_ROW, ModuleRowKeys.PROFILE, ModuleRowKeys.TRIALS})
            PREFIXES.put(p, Level.ADVANCED);
        // ── «Фото»: format, size, 16:9, the watermark page; the codecs' qualities and depths, the merge's output size and the
        // file name are the experts'
        simple("output_settings_screen", "photo_size_screen", "pref_wide169_key", "watermark_screen", "pref_photo_format",
                "photo_size_choice");
        advanced("pref_file_name_pattern", "pref_jpeg_quality", "pref_jpeg_fast_encoder", "pref_heic_quality", "pref_heic_10bit",
                "pref_webp_quality", "pref_webp_lossless", "pref_avif_quality", "pref_avif_lossless", "pref_avif_depth",
                "pref_avif_chroma", "pref_avif_speed", "pref_photo_color_space", "pref_ultrahdr_key", "pref_photo_also_jpeg",
                "pref_dng_lossless", "photo_size_expert", "adv_output");
        simple("pref_show_watermark_key", "pref_watermark_preview", "watermark_look_category", "watermark_fields_category",
                "pref_watermark_layout", "pref_watermark_position", "pref_watermark_color", "pref_watermark_font",
                "pref_watermark_size", "pref_watermark_opacity", "pref_watermark_margin", "pref_watermark_show_text",
                "pref_watermark_text", "pref_watermark_show_device", "pref_watermark_show_lens", "pref_watermark_show_aperture",
                "pref_watermark_show_shutter", "pref_watermark_show_iso", "pref_watermark_show_time", "pref_watermark_date_format",
                "pref_watermark_logo");
        // ── «Съёмка»: the gallery icon and theme, sounds, the viewfinder's grid / corners / horizon, the look, the selfie mirror;
        // flicker, low-light boost, the shooting page (Sport, HDR frames, ETTR, focus), AF data, the RAW viewfinder and the
        // settings' layout editor are the experts'
        simple("interface_settings_screen", "pref_hide_gallery_icon_key", "pref_theme_key", "pref_camera_sounds_all",
                "pref_camera_sounds_key", "pref_timer_sound_key", "viewfinder_screen", "pref_show_roundedge_key",
                "pref_show_grid_key", "pref_horizon", "appearance_screen", "pref_theme_accent_key", "shade_transparency_screen",
                "pref_shade_transparency", MIRROR);
        CODE_ROWS.add(MIRROR);
        advanced("pref_antibanding_hz_key", "pref_low_light_boost", "shooting_screen", "pref_show_afdata_key",
                "pref_live_viewfinder_raw_key", "settings_layout_editor");
        // ── «Цвет и обработка»: the photo style (normal, B&W, film LUT, film simulation, lens filter), warmth, sharpness, noise
        // reduction and the settings profiles; portrait, the colour page, the sharpening / RL / HDR / artefact pages are the experts'
        simple("photo_style_screen", "pref_scam_hybrid_colour_warmth", "pref_scam_hybrid_why_sharp_gain",
                "pref_scam_hybrid_why_dn_factor", "backup_settings_screen", "photo_style_category", "photo_style",
                "photo_style_mono_category", "pref_scam_hybrid_colour_mono", "photo_style_film_category", "film_lut_screen",
                "film_sim_screen", "lens_filter_screen", "pref_film_lut", "pref_film_lut_strength", "film_lut_changes",
                "pref_film_sim_stock", "pref_lens_filter", "profiles_page", "pref_category_backup_restore",
                "pref_backup_preferences_key", "pref_restore_preferences_key", "pref_reset_preferences_key", "config_portable_note");
        advanced("portrait_screen", "photo_processing_screen", "scam_hybrid_why_sharp_screen", "scam_hybrid_why_rl_screen",
                "scam_hybrid_hdr_screen", "scam_hybrid_artifacts_screen");
        // ── «О приложении»
        simple("pref_app_key", "pref_version_key", "film_data_credit", "unspektra_ae_credit");
        advanced("scam_recent_messages");
    }

    /** The keys of the module rows built in code (ModuleRows; the same strings). */
    static final class ModuleRowKeys {
        static final String GROUP = "root_modules", NONE = "module_row_none", ACTIONS = "module_actions";
        static final String CARD = "module_card_", NAME = "module_row_name_", ZOOM = "module_row_zoom_",
                BUTTON = "module_row_button_", CARD_ID = "module_row_card_id_", ID = "module_row_id_", CROP = "module_row_crop_",
                COPY = "module_row_copy_", DELETE = "module_row_delete_", LENS_ROW = "module_row_lens_",
                PROFILE = "module_row_profile_", TRIALS = "module_row_trials_";

        private ModuleRowKeys() {}
    }

    /** The level {@code key} declares (by itself or the start of its key), null when it declares none. */
    public static Level declared(String key) {
        if (key == null) return null;
        final Level own = KEYS.get(key);
        if (own != null) return own;
        String best = null;
        for (String p : PREFIXES.keySet())
            if (key.startsWith(p) && key.length() > p.length() && (best == null || p.length() > best.length())) best = p;
        return best == null ? null : PREFIXES.get(best);
    }

    /** Whether a row declared SIMPLE (an undeclared row is not: the Simple mode never shows a row nobody placed in it). */
    public static boolean simple(String key) {
        return declared(key) == Level.SIMPLE;
    }

    /**
     * Whether the Simple mode shows a row whose page path is {@code chain}: the row's key and its groups' up to (not with) its
     * page's own key, nearest first. Every one of them must be SIMPLE.
     */
    public static boolean shown(List<String> chain) {
        for (String key : chain) if (!simple(key)) return false;
        return true;
    }

    /** The stored mode is Simple. */
    public static boolean simpleMode(Object stored) {
        return stored != null && SIMPLE_MODE.equals(String.valueOf(stored).trim());
    }

    /** The stored mode is Custom. */
    public static boolean customMode(Object stored) {
        return stored != null && CUSTOM_MODE.equals(String.valueOf(stored).trim());
    }

    /** A row of the mode itself (the switch, «Настроить…», the wizard, the banner): never hidden by a mode. */
    public static boolean modeRow(String key) {
        return MODE_ROW.equals(key) || CUSTOM_ROW.equals(key) || WIZARD_ROW.equals(key) || BANNER_ROW.equals(key);
    }

    // ───── coverage (the settings model check, SimpleModeTest)

    /** A row of a settings tree: its key (null for none) and its rows. */
    public static final class Row {
        public final String key;
        public final List<Row> rows = new ArrayList<>();

        public Row(String key) {
            this.key = key;
        }

        public Row add(Row r) {
            rows.add(r);
            return this;
        }
    }

    /**
     * The rows of {@code root} (the main page) without a level of their own where they need one: a row of the main page, or of a
     * group or page that is SIMPLE all the way up. A row without a key there counts as missing too (it has nothing to declare by).
     */
    public static List<String> missing(Row root) {
        final List<String> out = new ArrayList<>();
        for (Row r : root.rows) missing(r, root.key == null ? ROOT : root.key, out);
        return out;
    }

    private static void missing(Row r, String path, List<String> out) {
        final Level level = declared(r.key);
        final String where = path + "/" + (r.key == null ? "(no key)" : r.key);
        if (level == null) {
            out.add(where);
            return;
        }
        if (level == Level.ADVANCED) return;   // its rows are ADVANCED with it
        for (Row c : r.rows) missing(c, where, out);
    }

    /** The declared keys (not prefixes) that are not in {@code tree} and are not rows built in code (stale declarations). */
    public static List<String> stale(Set<String> tree) {
        final List<String> out = new ArrayList<>();
        for (String k : KEYS.keySet()) if (!tree.contains(k) && !CODE_ROWS.contains(k)) out.add(k);
        Collections.sort(out);
        return out;
    }

    /** How many keys are declared at each level (the report of the model check). */
    public static int[] counts() {
        int s = 0, a = 0;
        for (Level l : KEYS.values()) if (l == Level.SIMPLE) s++; else a++;
        return new int[]{s, a, PREFIXES.size()};
    }

    // ───── the banner: hidden rows off their recommended default

    /**
     * Whether a stored value differs from its recommended default (the row's XML default or this phone's factory value): numbers
     * by value, switches by state ("1" / "0" / "true" / "false"), text as it is. Nothing stored is the default.
     */
    public static boolean differs(Object stored, String def) {
        if (stored == null || def == null) return false;
        final String s = String.valueOf(stored).trim(), d = def.trim();
        if (s.equals(d)) return false;
        if (stored instanceof Boolean || isFlag(s) && isFlag(d))
            return PreferenceNumber.bool(stored, false) != PreferenceNumber.bool(d, false);
        final double a = PreferenceNumber.read(stored, Double.NaN), b = PreferenceNumber.read(d, Double.NaN);
        if (!Double.isNaN(a) && !Double.isNaN(b)) return Math.abs(a - b) > 1e-6 * Math.max(1, Math.abs(b));
        return true;
    }

    private static boolean isFlag(String v) {
        return "true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v) || "1".equals(v) || "0".equals(v);
    }

    /** The keys of {@code hidden} whose stored value differs from its default in {@code defaults} (keys without one are skipped). */
    public static List<String> changed(Map<String, ?> stored, Map<String, String> defaults, Iterable<String> hidden) {
        final List<String> out = new ArrayList<>();
        for (String key : hidden) {
            final String def = defaults.get(key);
            if (def != null && stored.containsKey(key) && differs(stored.get(key), def)) out.add(key);
        }
        return out;
    }

    /**
     * The value to store for {@code def} where {@code like} is stored now: the same type (a switch stored as "1" / "0" text stays
     * text), so that the row reading it back finds what it wrote.
     */
    public static Object typed(Object like, String def) {
        final String d = def.trim();
        if (like instanceof Boolean) return PreferenceNumber.bool(d, false);
        if (like instanceof Integer) return (int) Math.round(PreferenceNumber.read(d, 0));
        if (like instanceof Long) return Math.round(PreferenceNumber.read(d, 0));
        if (like instanceof Float) return (float) PreferenceNumber.read(d, 0);
        final String s = like == null ? "" : String.valueOf(like).trim();
        if (("1".equals(s) || "0".equals(s)) && ("true".equalsIgnoreCase(d) || "false".equalsIgnoreCase(d)))
            return Boolean.parseBoolean(d.toLowerCase(java.util.Locale.ROOT)) ? "1" : "0";
        return d;
    }
}
