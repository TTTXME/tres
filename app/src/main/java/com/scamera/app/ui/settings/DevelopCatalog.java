package com.scamera.app.ui.settings;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;

import com.scamera.app.processing.color.FilmLut;
import com.scamera.app.processing.color.FilmSim;
import com.scamera.app.processing.color.LensFilters;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.SettingsAvailability;
import com.scamera.app.util.Lang;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P665: the camera's processing settings as the gallery developer offers them - every row of the settings that changes the
 * photo after the merge, in the settings' own order and names, read from the settings tree (preferences.xml) by the lab's rules
 * (ScamLabFragment.spec: a switch, a list, a slider, a number list, the film's and the LUT's own rows), grouped as the camera's
 * settings are: Тон (V1 / V2 and its pages), Света, Резкость, Шумоподавление, Цвет, Плёнка, LUT, Портрет, Oldstyle (a shot of
 * that mode), and Склейка - the merge's rows, greyed: the DNG holds the merge's result.
 */
public final class DevelopCatalog {
    public static final String TONE = "tone", LIGHTS = "lights", SHARP = "sharp", NOISE = "noise", COLOUR = "colour", FILM = "film",
            LUT = "lut", PORTRAIT = "portrait", OLDSTYLE = "oldstyle", MERGE = "merge";
    /** The groups in the editor's order and the settings tree's parts each takes (a screen, a category or one row). */
    static final String[][] GROUPS = {
            {TONE, "pref_scam_hybrid_tone_version", "tone_settings_screen"},
            {LIGHTS, "scam_hybrid_artifacts_screen", "pref_scam_hybrid_fringe_method", "fringe_net_screen"},
            {SHARP, "pref_scam_hybrid_why_sharp_gain", "scam_hybrid_why_sharp_screen", "scam_hybrid_why_rl_screen"},
            {NOISE, "pref_scam_hybrid_why_dn_factor", "scam_hybrid_nr_merge_category", "scam_hybrid_resolve_optics_category",
                    "scam_hybrid_ms_general_category", "scam_hybrid_ms_luma_category", "scam_hybrid_ms_chroma_category"},
            {COLOUR, "pref_scam_hybrid_colour_warmth", "pref_scam_hybrid_colour_mono", "photo_processing_screen", "lens_colour_category"},
            {FILM},
            {LUT, FilmLut.KEY, FilmLut.STRENGTH_KEY, LensFilters.KEY, LensFilters.STRENGTH_KEY},
            {PORTRAIT, "portrait_screen"},
            {OLDSTYLE, "oldstyle_tone_screen"},
            {MERGE, "scam_hybrid_merge_frames_category", "scam_hybrid_merge_align_category", "scam_hybrid_merge_reject_category",
                    "scam_hybrid_merge_kernel_category", "scam_hybrid_merge_quad_category", "scam_hybrid_merge_final_category",
                    "scam_hybrid_hdr_screen"}};

    /** Kinds of a row (ScamLabFragment.Spec's). */
    public static final int SWITCH = ScamLabFragment.Spec.SWITCH, LIST = ScamLabFragment.Spec.LIST, SLIDER = ScamLabFragment.Spec.SLIDER,
            CUSTOM = ScamLabFragment.Spec.CUSTOM, TEXT = ScamLabFragment.Spec.TEXT;

    /** One setting. */
    public static final class Row {
        public final String key, title;
        @Nullable public final String help;
        /** The caption of the part it is in (a category or a page of the group), null for the group's own rows. */
        @Nullable public final String section;
        public final int kind;
        public final CharSequence[] labels;
        public final String[] values;
        public final float min, max, step;
        public final boolean decimal;
        /** The setting's default (Boolean or String, as the camera stores it). */
        public final Object def;
        /** Why it never changes a develop (the merge's, fixed at the shot, edited on the camera's settings page), null: it can. */
        @Nullable public final String fixed;

        Row(ScamLabFragment.Spec s, @Nullable String help, @Nullable String section, @Nullable String fixed) {
            key = s.key;
            title = s.title == null ? s.key : s.title;
            this.help = help != null ? help : s.summary;
            this.section = section;
            kind = s.kind;
            labels = s.labels == null ? new CharSequence[0] : s.labels;
            values = s.values == null ? new String[0] : s.values;
            min = s.min;
            max = s.max;
            step = s.step > 0 ? s.step : 1f;
            decimal = s.decimal;
            def = s.def == null ? "" : s.def;
            this.fixed = fixed;
        }

        /** A slider's number as stored (the lab's: whole or with the step's decimals). */
        public String number(float v) {
            if (!decimal) return String.valueOf(Math.round(v));
            final int decimals = Math.max(0, Math.min(4, (int) Math.ceil(Math.log10(Math.max(1, 1f / step)))));
            return String.format(java.util.Locale.ROOT, "%." + decimals + "f", v);
        }
    }

    /** One group: its id, name and rows. */
    public static final class Group {
        public final String id, title;
        public final List<Row> rows;

        Group(String id, String title, List<Row> rows) {
            this.id = id;
            this.title = title;
            this.rows = Collections.unmodifiableList(rows);
        }
    }

    private DevelopCatalog() {}

    /** The group's name (Russian first). */
    public static String title(@Nullable Context c, String id) {
        switch (id) {
            case TONE: return Lang.t(c, "Тон", "Tone");
            case LIGHTS: return Lang.t(c, "Света", "Highlights");
            case SHARP: return Lang.t(c, "Резкость", "Sharpness");
            case NOISE: return Lang.t(c, "Шумоподавление", "Noise reduction");
            case COLOUR: return Lang.t(c, "Цвет", "Colour");
            case FILM: return Lang.t(c, "Плёнка", "Film");
            case LUT: return "LUT";
            case PORTRAIT: return Lang.t(c, "Портрет", "Portrait");
            case OLDSTYLE: return "Oldstyle";
            default: return Lang.t(c, "Склейка", "Merge");
        }
    }

    /** Rows whose value the shot fixed (its plan, its burst, its colour transform): the lab's AT_SHOT and BURST_FRAMES. */
    static boolean fixedAtShot(String key) {
        return ScamLabFragment.AT_SHOT.contains(key) || ScamLabFragment.BURST_FRAMES.contains(key);
    }

    private static PreferenceScreen tree;
    private static Context treeContext;

    /** The settings tree with its defaults (inflated once a process: ~800 rows). */
    static synchronized PreferenceScreen tree(Context c) {
        final Context app = c.getApplicationContext();
        if (tree == null || treeContext != app) {
            tree = ScamLabFragment.defaultsTree(app);
            treeContext = app;
        }
        return tree;
    }

    /** The groups for a shot whose settings were {@code shot} (the record's); the Oldstyle group only for a shot of that mode. */
    public static List<Group> groups(Context c, Map<String, ?> shot) {
        final PreferenceScreen tree = tree(c);
        final List<Group> out = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        final boolean oldstyle = Boolean.TRUE.equals(shot.get(com.scamera.app.settings.OldstyleMode.PREF));
        for (String[] g : GROUPS) {
            final String id = g[0];
            if (OLDSTYLE.equals(id) && !oldstyle) continue;
            final List<Row> rows = new ArrayList<>();
            if (FILM.equals(id)) {
                for (String k : FilmSim.KEYS) add(rows, seen, tree, c, k, null, null, false, shot);
            } else {
                for (int i = 1; i < g.length; i++) {
                    final Preference p = tree.findPreference(g[i]);
                    if (p instanceof PreferenceGroup) walk(rows, seen, tree, c, (PreferenceGroup) p, i == 1 && g.length == 2 ? null
                            : String.valueOf(p.getTitle()), MERGE.equals(id), shot);
                    else add(rows, seen, tree, c, g[i], p, null, MERGE.equals(id), shot);
                }
            }
            if (!rows.isEmpty()) out.add(new Group(id, title(c, id), rows));
        }
        return out;
    }

    private static void walk(List<Row> rows, Set<String> seen, PreferenceScreen tree, Context c, PreferenceGroup group,
                             @Nullable String section, boolean merge, Map<String, ?> shot) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            final Preference p = group.getPreference(i);
            if (!p.isVisible() || ScamLabFragment.hiddenFor(p.getKey(), shot, false)) continue;
            if (p instanceof PreferenceCategory || p instanceof PreferenceScreen) {
                walk(rows, seen, tree, c, (PreferenceGroup) p, String.valueOf(p.getTitle()), merge, shot);
            } else if (p instanceof com.scamera.app.ui.settings.custompreferences.FilmSimPreference) {
                for (String k : FilmSim.KEYS) add(rows, seen, tree, c, k, null, section, merge, shot);
            } else if (ScamLabFragment.comparable(p)) {
                add(rows, seen, tree, c, p.getKey(), p, section, merge, shot);
            }
        }
    }

    private static void add(List<Row> rows, Set<String> seen, PreferenceScreen tree, Context c, String key, @Nullable Preference p,
                            @Nullable String section, boolean merge, Map<String, ?> shot) {
        if (key == null || !seen.add(key)) return;
        if (p != null && (!p.isVisible() || ScamLabFragment.hiddenFor(key, shot, false))) return;
        final ScamLabFragment.Spec s = ScamLabFragment.spec(tree, c, key);
        if (s == null) return;
        final CharSequence help = p != null ? SettingHelp.text(c, p) : null;
        rows.add(new Row(s, help == null ? null : help.toString(), section, fixedReason(c, key, s.kind, merge)));
    }

    /** Why a row never changes a develop, null: it can. */
    @Nullable
    static String fixedReason(@Nullable Context c, String key, int kind, boolean merge) {
        if (merge || PreferenceKeys.mergeTuningKey(key))
            return Lang.t(c, "Склейка кадров уже сделана: DNG хранит её результат.", "The frames are already merged: the DNG holds the result.");
        if (fixedAtShot(key))
            return Lang.t(c, "Задаётся при съёмке: в DNG уже учтено.", "Fixed at the shot: already in the DNG.");
        if (kind == CUSTOM || kind == TEXT)
            return Lang.t(c, "Меняется на странице этой настройки в камере; проявка берёт значение снимка.",
                    "Edited on the camera's page of this setting; the develop takes the shot's value.");
        return null;
    }

    /**
     * Why a row changes nothing with these {@code values} (the shot's with the changes; SettingsAvailability, as the settings grey
     * a row), its {@link Row#fixed} reason first; null: it acts.
     */
    @Nullable
    public static String inert(Row row, Map<String, ?> values) {
        if (row.fixed != null) return row.fixed;
        return new SettingsAvailability(values).reason(row.key);
    }

    /** The row's value: the change, else the shot's stored value, else the default. */
    public static Object value(Row row, Map<String, ?> shot, Map<String, Object> changes) {
        if (changes.containsKey(row.key)) return changes.get(row.key);
        final Object v = shot.get(row.key);
        return v != null ? v : row.def;
    }

    /** A switch's state of {@code v} (a Boolean, "true" / "1"). */
    public static boolean on(@Nullable Object v) {
        return v instanceof Boolean ? (Boolean) v : v != null && ("true".equalsIgnoreCase(v.toString()) || "1".equals(v.toString()));
    }

    /** A slider's number of {@code v}, {@code otherwise} when it is not one. */
    public static float number(@Nullable Object v, float otherwise) {
        if (v instanceof Number) return ((Number) v).floatValue();
        try {
            return v == null ? otherwise : Float.parseFloat(v.toString().trim());
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }

    /** Every key of the groups (the tests: each reaches the develop's settings). */
    public static Set<String> keys(List<Group> groups) {
        final Set<String> out = new LinkedHashSet<>();
        for (Group g : groups) for (Row r : g.rows) out.add(r.key);
        return out;
    }

    /** The groups' ids in order (the tests, the panel's tabs). */
    public static List<String> ids(List<Group> groups) {
        final List<String> out = new ArrayList<>();
        for (Group g : groups) out.add(g.id);
        return out;
    }

    static List<String> allGroupIds() {
        final List<String> out = new ArrayList<>();
        for (String[] g : GROUPS) out.add(g[0]);
        return Arrays.asList(out.toArray(new String[0]));
    }
}
