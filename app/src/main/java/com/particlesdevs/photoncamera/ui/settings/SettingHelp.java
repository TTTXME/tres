package com.particlesdevs.photoncamera.ui.settings;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.TwoStatePreference;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.settings.PreferenceNumber;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.ModuleRowPreference;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.ModuleSection;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableCheckBoxPreference;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableSeekBarPreference;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.UniversalSeekBarPreference;
import com.particlesdevs.photoncamera.ui.style.Fonts;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * P319 (user report 2026-10-22: «Неплохо было бы добавить описание пунктов по длинному нажатию»): a long press on a settings row
 * opens its help. P592 (owner, 2026-11-10: «Убираем все описания у параметров, оставляем подробное описание только по зажатию на
 * параметре, само всплывающее меню с описанием переделай под новый стиль»): a row shows its title and its value only; what it
 * does is here and nowhere else.
 * <p>
 * The text of a row ({@link #text}): the description its builder gave it ({@link #describe}), else its own text of res
 * strings_help.xml where one is written (found through the map of keys and key families below, never by name: the release build
 * shrinks unreferenced strings), else its summary - the android:summary of preferences.xml is the row's description, never drawn in the
 * row. What a row may still show under its title is a state ({@link #stateLine}): «Нет на этом объективе», «Выбран вами», a
 * failed FULLRES trial - {@link #state} for a line beside a description, {@link #summaryIsState} for a summary that is one.
 * <p>
 * The sheet ({@link #show}): the camera sheets' card (#2C2C2E, 30dp top corners, the grabber), the title 18dp semibold white,
 * the text in white at 70 %, then «Сейчас» / «По умолчанию» / «Диапазон» for a row that has them, the shade's pin for a setting
 * the shade can hold and «Вернуть по умолчанию» for a changed slider or tunable switch.
 */
public final class SettingHelp {
    private SettingHelp() {}

    /** The sheet's card, its inner card of facts, its body text (white at 70 %) and its labels (white at 55 %). */
    public static final int CARD = SettingsStyle.ROW, FACTS = SettingsStyle.CARD_NESTED, BODY = 0xB3FFFFFF, LABEL = SettingsStyle.SECTION;
    /** The title's size and the text's, dp (the concept's px). */
    public static final float TITLE_DP = 18f, BODY_DP = 15f;

    private static final String EXTRA_HELP = "p592_help", EXTRA_STATE = "p592_state", EXTRA_SUMMARY_IS_STATE = "p592_summary_is_state";

    /** Exact keys, then families (a regex over the key): the first match gives the help text. */
    private static final Map<String, Integer> EXACT = new LinkedHashMap<>();
    private static final Map<Pattern, Integer> FAMILIES = new LinkedHashMap<>();

    static {
        EXACT.put("scam_hybrid_nr_why_luma_screen", R.string.help_nr_luma_table);
        EXACT.put("scam_hybrid_nr_why_chroma_screen", R.string.help_nr_chroma_table);
        FAMILIES.put(Pattern.compile("pref_scam_hybrid_dn_(luma|chroma)_t\\d_snr"), R.string.help_nr_table_snr);
        FAMILIES.put(Pattern.compile("pref_scam_hybrid_dn_luma_t\\d_strength"), R.string.help_nr_luma_strength);
        FAMILIES.put(Pattern.compile("pref_scam_hybrid_dn_chroma_t\\d_strength"), R.string.help_nr_chroma_strength);
        FAMILIES.put(Pattern.compile("pref_scam_hybrid_dn_(luma|chroma)_t\\d_revert"), R.string.help_nr_table_revert);
        FAMILIES.put(Pattern.compile("pref_scam_hybrid_dn_(luma|chroma)_t\\d_outlier"), R.string.help_nr_table_outlier);
        EXACT.put("pref_camera_sounds_all", R.string.help_sounds);
        EXACT.put("settings_layout_editor", R.string.help_settings_layout);
        EXACT.put("pref_live_viewfinder_raw_key", R.string.help_live_viewfinder_raw);
        EXACT.put("pref_photo_format", R.string.help_photo_format);
        EXACT.put("pref_film_lut", R.string.help_film_lut);
        EXACT.put("photo_size_screen", R.string.help_photo_size);
        EXACT.put("pref_scam_hybrid_output", R.string.help_output);
        EXACT.put("pref_wide169_key", R.string.help_wide169);
        EXACT.put("pref_crop_upscale", R.string.help_crop_upscale);
        EXACT.put("pref_scam_hybrid_resize", R.string.help_resize);
        EXACT.put("pref_ultrahdr_key", R.string.help_ultrahdr);
        EXACT.put("pref_photo_color_space", R.string.help_colour_space);
        EXACT.put("watermark_screen", R.string.help_watermark);
        EXACT.put("pref_motion_mode", R.string.help_motion_mode);
        EXACT.put("pref_antibanding_hz_key", R.string.help_antibanding);
        EXACT.put("pref_show_grid_key", R.string.help_grid);
        EXACT.put("pref_horizon", R.string.help_horizon);
        EXACT.put("pref_peak_method_key", R.string.help_peaking);
        EXACT.put("pref_face_detect_mode", R.string.help_face_detect);
        EXACT.put("pref_tracking_af_mode", R.string.help_tracking_af);
        EXACT.put("pref_split_af_ae", R.string.help_split_af_ae);
        EXACT.put("pref_low_light_boost", R.string.help_low_light_boost);
        EXACT.put("pref_mosaic_wb_corrections", R.string.help_mosaic_wb);
        EXACT.put("photo_style_screen", R.string.help_photo_style);
        EXACT.put("lens_filter_screen", R.string.help_lens_filter);
        EXACT.put("film_sim_screen", R.string.help_film_sim);
        EXACT.put("pref_scam_hybrid_colour_warmth", R.string.help_warmth);
        EXACT.put("pref_scam_hybrid_colour_tint", R.string.help_tint);
        EXACT.put("colour_mixer_screen", R.string.help_colour_mixer);
        FAMILIES.put(Pattern.compile("pref_scam_hybrid_colour_mix_\\w+_(hue|sat|light)"), R.string.help_colour_mixer);
        EXACT.put("colour_grading_screen", R.string.help_colour_grading);
        FAMILIES.put(Pattern.compile("pref_scam_hybrid_colour_grade_\\w+"), R.string.help_colour_grading);
        EXACT.put("colour_calibration_screen", R.string.help_colour_calibration);
        FAMILIES.put(Pattern.compile("pref_scam_hybrid_colour_cal_\\w+"), R.string.help_colour_calibration);
        EXACT.put("pref_color_method_key", R.string.help_colour_method);
        EXACT.put("backup_settings_screen", R.string.help_backup);
        // 2026-10-27 (owner: settings anyone can set up alone): the rows of «Снимок», «Съёмка» and «Экран камеры» that came from
        // the expert pages say what they do and where they were
        EXACT.put("pref_scam_hybrid_why_ae_target", R.string.help_brightness);
        EXACT.put("pref_scam_hybrid_why_macro_contrast", R.string.help_contrast);
        EXACT.put("pref_scam_hybrid_why_agx_sat", R.string.help_saturation);
        EXACT.put("pref_scam_hybrid_why_sharp_gain", R.string.help_sharpness);
        EXACT.put("pref_scam_hybrid_why_dn_factor", R.string.help_noise);   // P443: the everyday denoise slider
        EXACT.put("pref_scam_hybrid_denoise", R.string.help_hybrid_denoise);   // P600: «Все шумодавы», what it turns off
        EXACT.put("pref_ettr", R.string.help_ettr);
        EXACT.put("focus_screen", R.string.help_focus);
        EXACT.put("pref_af_mode_key", R.string.help_af_mode);
        EXACT.put("pref_show_afdata_key", R.string.help_info_overlay);
        // P384 (owner, 2026-10-28: «Переместить эту группу настроек на главный экран»): the camera groups of the main page
        EXACT.put("root_modules", R.string.help_modules_group);
        EXACT.put("modules_screen", R.string.help_modules_group);
        EXACT.put("expert_module_sensor", R.string.help_module_sensor);
        EXACT.put("lens_screen", R.string.help_module_sensor);   // P413
        FAMILIES.put(Pattern.compile("module_row_card_id_\\w+"), R.string.help_module_ids);   // P413: the Camera ID in the card
        FAMILIES.put(Pattern.compile("module_row_lens_\\w+"), R.string.help_module_sensor);
        EXACT.put("module_sensor_picker", R.string.help_module_sensor);
        EXACT.put("module_row_per_lens", R.string.help_module_profile);
        FAMILIES.put(Pattern.compile("module_card_\\w+"), R.string.help_module_card);
        FAMILIES.put(Pattern.compile("module_row_(name|button)_\\w+"), R.string.help_module_card);
        FAMILIES.put(Pattern.compile("module_row_zoom_\\w+"), R.string.help_module_zoom);
        FAMILIES.put(Pattern.compile("module_row_(crop|copy|delete)_\\w+"), R.string.help_module_crop);
        EXACT.put("module_row_add_copy", R.string.help_module_crop);
        FAMILIES.put(Pattern.compile("module_row_profile_.+"), R.string.help_module_profile);
        FAMILIES.put(Pattern.compile("module_row_id_\\w+"), R.string.help_module_ids);
        FAMILIES.put(Pattern.compile("module_row_trials_\\w+"), R.string.help_fullres_trials);
        FAMILIES.put(Pattern.compile("pref_sensorconfig_\\w+_sensormoderow"), R.string.help_sensor_mode);
        // P592: the rows of preferences.xml that had no description of their own, and the rows whose summary is their state
        EXACT.put("photo_size_choice", R.string.help_photo_size);
        EXACT.put("pref_scam_hybrid_resize_mp", R.string.help_resize_mp);
        EXACT.put("pref_scam_hybrid_resize_px", R.string.help_resize_px);
        EXACT.put("pref_watermark_text", R.string.help_watermark_text); // P628: the one custom text (the two lines before)
        EXACT.put("pref_shade_transparency", R.string.help_shade_transparency);
        EXACT.put("photo_style", R.string.help_photo_style);
        EXACT.put("film_lut_changes", R.string.help_film_lut_changes);
        EXACT.put("pref_film_sim_stock", R.string.help_film_sim);
        EXACT.put("pref_lens_filter", R.string.help_lens_filter);
        EXACT.put("profiles_page", R.string.help_backup);
        EXACT.put("pref_oplus_spoof_package_key", R.string.help_spoof_package);
        EXACT.put("pref_generic_spoof_package_key", R.string.help_spoof_package);
        EXACT.put("pref_binder_spoof_package_key", R.string.help_spoof_package);
        EXACT.put("pref_version_key", R.string.help_version);
        EXACT.put("scamera_log_clear", R.string.help_log_clear);
        EXACT.put("pref_file_name_pattern", R.string.help_file_name);
    }

    /** The rows of preferences.xml whose summary the page writes as their state (the log's size, the installed version). */
    private static final java.util.Set<String> STATE_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            "scamera_log_clear", "pref_version_key", "pref_app_key", "film_data_credit", "unspektra_ae_credit"));   // the about line and the credits (attribution, never a description)

    /** The help text of {@code key}, 0 without one of its own. */
    @StringRes
    static int helpRes(@Nullable String key) {
        if (key == null) return 0;
        Integer exact = EXACT.get(key);
        if (exact != null) return exact;
        for (Map.Entry<Pattern, Integer> e : FAMILIES.entrySet()) if (e.getKey().matcher(key).matches()) return e.getValue();
        return 0;
    }

    /**
     * Whether a long press on {@code p} opens its help: a keyed row (a page too; P384: a module card's header), not a category
     * header. P592: a tunable check box too (its reset is in the sheet).
     */
    static boolean helps(@Nullable Preference p) {
        if (p instanceof ModuleSection) return p.getKey() != null;
        return p != null && p.getKey() != null && !(p instanceof PreferenceCategory);
    }

    // ───── a row's description and its state ─────

    /** {@code p}'s description for the sheet when it is not its summary (a row built in code whose summary is its state). */
    public static void describe(Preference p, @Nullable CharSequence text) {
        p.getExtras().putCharSequence(EXTRA_HELP, text);
    }

    /**
     * The line {@code p} shows under its title, beside the description its summary holds (null: none): set before the summary,
     * whose change binds the row again.
     */
    public static void state(Preference p, @Nullable CharSequence state) {
        p.getExtras().putCharSequence(EXTRA_STATE, state);
    }

    /** {@code p}'s whole summary is its state: shown under the title, never taken for the description. */
    public static void summaryIsState(Preference p) {
        p.getExtras().putBoolean(EXTRA_SUMMARY_IS_STATE, true);
    }

    static boolean isSummaryState(Preference p) {
        if (p instanceof ModuleSection) return true;   // a module card: «Сейчас в камере · ID 0»
        if (p.getKey() != null && STATE_KEYS.contains(p.getKey())) return true;
        return p.peekExtras() != null && p.peekExtras().getBoolean(EXTRA_SUMMARY_IS_STATE);
    }

    /** What {@code p} shows under its title: its state, never its description; null for none. */
    @Nullable
    public static CharSequence stateLine(@Nullable Preference p) {
        if (p == null) return null;
        if (p instanceof ModuleRowPreference && ((ModuleRowPreference) p).status() != null) return ((ModuleRowPreference) p).status();
        CharSequence state = p.peekExtras() == null ? null : p.peekExtras().getCharSequence(EXTRA_STATE);
        if (!TextUtils.isEmpty(state)) return state;
        if (isSummaryState(p)) {
            CharSequence s = safeSummary(p);
            return TextUtils.isEmpty(s) ? null : s;
        }
        return null;
    }

    private static CharSequence safeSummary(Preference p) {
        try {
            return p.getSummary();
        } catch (RuntimeException e) {
            return null;   // a list's «%s» summary before its value is set
        }
    }

    /** The description of {@code p}: what it does and when to touch it (a state row: its full text); null when it has none. */
    @Nullable
    public static CharSequence text(Context c, @Nullable Preference p) {
        if (p == null) return null;
        // the text its builder gave this very row first (it knows the row's case), then the key's own help text
        CharSequence own = p.peekExtras() == null ? null : p.peekExtras().getCharSequence(EXTRA_HELP);
        if (!TextUtils.isEmpty(own)) return own;
        final int res = helpRes(p.getKey());
        if (res != 0) return c.getText(res);
        // a row whose line is its state (the credits, the version): its whole text, uncut
        if (isSummaryState(p)) return safeSummary(p);
        // a summary that only shows the value (the library's simple providers) is no description
        if (p.getSummaryProvider() instanceof ListPreference.SimpleSummaryProvider
                || p.getSummaryProvider() instanceof androidx.preference.EditTextPreference.SimpleSummaryProvider) return null;
        return description(safeSummary(p), stateLine(p), p instanceof ListPreference ? ((ListPreference) p).getEntry()
                : p instanceof androidx.preference.EditTextPreference ? ((androidx.preference.EditTextPreference) p).getText() : null);
    }

    /**
     * A summary as a description: without the row's state at its end, without the value a «%s. …» summary began with and
     * without a placeholder left in it.
     */
    @Nullable
    static CharSequence description(@Nullable CharSequence summary, @Nullable CharSequence state, @Nullable CharSequence value) {
        if (TextUtils.isEmpty(summary)) return null;
        String s = summary.toString();
        if (!TextUtils.isEmpty(state)) {
            final String st = state.toString();
            if (s.equals(st)) return null;
            if (s.endsWith("\n" + st)) s = s.substring(0, s.length() - st.length() - 1);
        }
        if (!TextUtils.isEmpty(value)) {
            final String v = value.toString();
            if (s.equals(v)) return null;
            if (s.startsWith(v + ". ") || s.startsWith(v + "\n")) s = s.substring(v.length() + (s.startsWith(v + ". ") ? 2 : 1));
        }
        s = SettingsStyle.PLACEHOLDER.matcher(s).replaceAll("").replaceAll("^[.\\s]+", "").trim();
        return s.isEmpty() ? null : s;
    }

    // ───── the sheet ─────

    /** One line of the sheet's facts: «По умолчанию» - «1,00». */
    static final class Fact {
        final String label;
        final CharSequence value;
        final boolean now;

        Fact(String label, CharSequence value, boolean now) {
            this.label = label;
            this.value = value;
            this.now = now;
        }
    }

    /** The help sheet of {@code p}. */
    public static BottomSheetDialog show(Context c, Preference p) {
        return show(c, p, null);
    }

    /**
     * The help sheet of {@code p} on a page of lens {@code lens} (a module profile; null on any other page). P606: a value row of a
     * lens page gets «В шторку» for that lens only (ShadeCatalog.lensPin).
     */
    public static BottomSheetDialog show(Context c, Preference p, @Nullable String lens) {
        CharSequence text = text(c, p);
        if (TextUtils.isEmpty(text)) text = Lang.t(c, "Описания для этого пункта пока нет.", "No description for this row yet.");
        if (restarts(p)) text = text + "\n\n" + Lang.t(c, "После изменения камера перезапустится.", "The camera restarts after a change.");
        final CharSequence title = p.getTitle() == null || p.getTitle().length() == 0 ? fallbackTitle(c, p) : p.getTitle();
        final BottomSheetDialog[] shown = new BottomSheetDialog[1];
        final Runnable reset = reset(p);
        shown[0] = show(c, title, text, facts(c, p), pinKey(c, p.getKey(), lens),
                reset == null ? null : () -> {
                    reset.run();
                    if (shown[0] != null) shown[0].dismiss();
                });
        return shown[0];
    }

    /** The shade pin of a row: its lens's pin on a lens page (P606), else the key itself where the shade can hold it. */
    @Nullable
    static String pinKey(Context c, @Nullable String key, @Nullable String lens) {
        if (key == null) return null;
        if (lens != null) {
            try {
                String token = com.particlesdevs.photoncamera.settings.ShadeCatalog.get(c).lensPin(lens, key);
                if (token != null) return token;
            } catch (RuntimeException e) {
                // no catalog: no pin
            }
        }
        return SettingsStyle.pinnable(c, key) ? key : null;
    }

    /**
     * P214g / P592: a change of {@code p} takes a new camera session (SessionKeys; a camera key of a module): the camera restarts.
     * The row no longer wears a badge for it; its help says it.
     */
    public static boolean restarts(@Nullable Preference p) {
        return p != null && (p instanceof com.particlesdevs.photoncamera.ui.settings.custompreferences.TunableKeyPreference
                || com.particlesdevs.photoncamera.settings.SessionKeys.needsNewSession(p.getKey()));
    }

    /** The help sheet of a row that is no preference (the lab's rows, a film parameter): {@code title} and {@code text}. */
    public static BottomSheetDialog show(Context c, CharSequence title, CharSequence text) {
        return show(c, title, text, null, null, null);
    }

    static BottomSheetDialog show(Context c, CharSequence title, CharSequence text, @Nullable List<Fact> facts, @Nullable String pinKey,
                                  @Nullable Runnable reset) {
        final BottomSheetDialog d = SettingsStyle.sheet(c, body(c, title, text, facts, pinKey, reset));
        d.show();
        DialogStyle.track(d);
        return d;
    }

    /**
     * The sheet's content (tag "setting_help"): the grabber, the title ("help_title"), the text ("help_text"), the facts
     * ("help_facts": rows "help_fact_" + n), the pin ("sheet_pin") and the reset ("help_reset").
     */
    static LinearLayout body(Context c, CharSequence title, CharSequence text, @Nullable List<Fact> facts, @Nullable String pinKey,
                             @Nullable Runnable reset) {
        final int accent = SettingsStyle.accent(c);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setTag("setting_help");
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        final float r = SettingsStyle.dp(c, 30);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        body.setBackground(bg);
        body.setPadding(SettingsStyle.dp(c, 20), SettingsStyle.dp(c, 6), SettingsStyle.dp(c, 20), SettingsStyle.dp(c, 22));
        View grab = new View(c);
        grab.setTag("help_grabber");
        grab.setBackground(SettingsStyle.shape(c, SettingsStyle.GRABBER, 0, 3));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(SettingsStyle.dp(c, 36), SettingsStyle.dp(c, 5));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.bottomMargin = SettingsStyle.dp(c, 16);
        body.addView(grab, gp);
        TextView head = SettingsStyle.text(c, title, TITLE_DP, SettingsStyle.ROW_TITLE);
        head.setTypeface(Fonts.of(c, Fonts.SEMIBOLD));
        head.setTag("help_title");
        SettingsStyle.lineHeight(head, 23);
        if (android.os.Build.VERSION.SDK_INT >= 28) head.setAccessibilityHeading(true);
        body.addView(head, new LinearLayout.LayoutParams(-1, -2));
        TextView t = SettingsStyle.text(c, text, BODY_DP, BODY);
        t.setTag("help_text");
        SettingsStyle.lineHeight(t, 21);
        t.setTextIsSelectable(false);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = SettingsStyle.dp(c, 8);
        body.addView(t, tp);
        if (facts != null && !facts.isEmpty()) {
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setTag("help_facts");
            card.setBackground(SettingsStyle.shape(c, FACTS, 0, 16));
            GradientDrawable hair = new GradientDrawable();
            hair.setColor(SettingsStyle.HAIRLINE);
            hair.setSize(1, Math.max(1, Math.round(.5f * c.getResources().getDisplayMetrics().density)));
            card.setDividerDrawable(new android.graphics.drawable.InsetDrawable(hair, SettingsStyle.dp(c, 14), 0, 0, 0));
            card.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
            for (int i = 0; i < facts.size(); i++) {
                final Fact f = facts.get(i);
                LinearLayout row = new LinearLayout(c);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(SettingsStyle.dp(c, 38));
                row.setPadding(SettingsStyle.dp(c, 14), SettingsStyle.dp(c, 7), SettingsStyle.dp(c, 14), SettingsStyle.dp(c, 7));
                row.setTag("help_fact_" + i);
                TextView label = SettingsStyle.text(c, f.label, 14, LABEL);
                row.addView(label, new LinearLayout.LayoutParams(-2, -2));
                TextView value = SettingsStyle.text(c, f.value, 14, f.now ? accent : SettingsStyle.ROW_TITLE);
                value.setTypeface(Fonts.of(c, f.now ? Fonts.SEMIBOLD : Fonts.MEDIUM));
                value.setGravity(Gravity.END);
                value.setTag(f.now ? "help_now" : null);
                LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, -2, 1);
                vp.setMarginStart(SettingsStyle.dp(c, 12));
                row.addView(value, vp);
                row.setContentDescription(f.label + ": " + f.value);
                card.addView(row);
            }
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.topMargin = SettingsStyle.dp(c, 14);
            body.addView(card, cp);
        }
        TextView pin = SettingsStyle.pinChip(c, pinKey);
        TextView back = null;
        if (reset != null) {
            back = SettingsStyle.text(c, Lang.t(c, "Вернуть по умолчанию", "Back to the default"), 15, accent);
            back.setTypeface(Fonts.of(c, Fonts.SEMIBOLD));
            back.setTag("help_reset");
            back.setGravity(Gravity.CENTER);
            back.setMinHeight(SettingsStyle.dp(c, 40));
            back.setPadding(SettingsStyle.dp(c, 16), 0, SettingsStyle.dp(c, 16), 0);
            back.setFocusable(true);
            back.setOnClickListener(v -> reset.run());
        }
        if (pin != null || back != null) {
            LinearLayout actions = new LinearLayout(c);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            for (TextView chip : new TextView[]{pin, back}) {
                if (chip == null) continue;
                // the capsules a step lighter than the sheet's card (on the pages they sit on #1C1C1E)
                chip.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x1FFFFFFF),
                        SettingsStyle.shape(c, FACTS, 0, 20), null));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                if (actions.getChildCount() > 0) lp.setMarginStart(SettingsStyle.dp(c, 8));
                actions.addView(chip, lp);
            }
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
            ap.topMargin = SettingsStyle.dp(c, 14);
            body.addView(actions, ap);
        }
        return body;
    }

    private static CharSequence fallbackTitle(Context c, Preference p) {
        return p.getLayoutResource() == R.layout.preference_info ? Lang.t(c, "Пояснение", "Note") : String.valueOf(p.getKey());
    }

    /** «Сейчас», «По умолчанию» and «Диапазон» of {@code p}: only what the row really has. */
    static List<Fact> facts(Context c, Preference p) {
        final List<Fact> out = new ArrayList<>();
        final String now = Lang.t(c, "Сейчас", "Now"), def = Lang.t(c, "По умолчанию", "Default"), range = Lang.t(c, "Диапазон", "Range");
        if (p instanceof UniversalSeekBarPreference) {
            final UniversalSeekBarPreference u = (UniversalSeekBarPreference) p;
            final int decimals = PreferenceNumber.gridDecimals(u.stepPerUnit(), u.minimum());
            out.add(new Fact(now, SettingsStyle.number(c, shown(u.getValue(), u.decimal(), decimals)), true));
            out.add(new Fact(def, SettingsStyle.number(c, number(u.defaultNumber(), u.decimal(), decimals)), false));
            out.add(new Fact(range, SettingsStyle.number(c, number(u.minimum(), u.decimal(), decimals)) + " … "
                    + SettingsStyle.number(c, number(u.maximum(), u.decimal(), decimals)), false));
        } else if (p instanceof TunableSeekBarPreference) {
            final TunableSeekBarPreference t = (TunableSeekBarPreference) p;
            out.add(new Fact(now, SettingsStyle.number(c, t.valueText(t.getFloatValue())), true));
            out.add(new Fact(def, SettingsStyle.number(c, t.valueText(t.defaultNumber())), false));
            out.add(new Fact(range, SettingsStyle.number(c, PreferenceNumber.format(t.minimum(), t.decimal())) + " … "
                    + SettingsStyle.number(c, PreferenceNumber.format(t.maximum(), t.decimal())), false));
        } else if (p instanceof TunableCheckBoxPreference) {
            final TunableCheckBoxPreference t = (TunableCheckBoxPreference) p;
            out.add(new Fact(now, onOff(c, t.isChecked()), true));
            out.add(new Fact(def, onOff(c, t.defaultInt() != 0), false));
        } else if (p instanceof TwoStatePreference) {
            out.add(new Fact(now, onOff(c, ((TwoStatePreference) p).isChecked()), true));
            final String d = xmlDefault(c, p.getKey());
            if (d != null) out.add(new Fact(def, onOff(c, "true".equalsIgnoreCase(d) || "1".equals(d)), false));
        } else if (p instanceof ListPreference) {
            final ListPreference l = (ListPreference) p;
            if (!TextUtils.isEmpty(l.getEntry())) out.add(new Fact(now, l.getEntry(), true));
            final String d = xmlDefault(c, p.getKey());
            final int i = d == null ? -1 : l.findIndexOfValue(d);
            if (i >= 0 && l.getEntries() != null && i < l.getEntries().length) out.add(new Fact(def, l.getEntries()[i], false));
        } else {
            final CharSequence v = SettingsStyle.valueOf(p);
            if (!TextUtils.isEmpty(v) && !"—".contentEquals(v)) out.add(new Fact(now, v, true));
        }
        return out;
    }

    private static String onOff(Context c, boolean on) {
        return on ? Lang.t(c, "включено", "on") : Lang.t(c, "выключено", "off");
    }

    /** The android:defaultValue of a row of preferences.xml (this device's factory value where there is one); null: none. */
    @Nullable
    private static String xmlDefault(Context c, @Nullable String key) {
        if (key == null) return null;
        try {
            return PageReset.defaultOf(c, key, null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String shown(@Nullable String stored, boolean decimal, int decimals) {
        try {
            return number(Float.parseFloat(String.valueOf(stored).replace(',', '.')), decimal, decimals);
        } catch (NumberFormatException e) {
            return String.valueOf(stored);
        }
    }

    private static String number(float v, boolean decimal, int decimals) {
        return decimal ? PreferenceNumber.gridText(v, decimals) : Integer.toString(Math.round(v));
    }

    /** Puts a changed slider or tunable switch back to its default; null when {@code p} is at it or has no such reset. */
    @Nullable
    private static Runnable reset(Preference p) {
        if (!p.isEnabled()) return null;
        if (p instanceof UniversalSeekBarPreference) return ((UniversalSeekBarPreference) p).isChanged() ? ((UniversalSeekBarPreference) p)::resetToDefault : null;
        if (p instanceof TunableSeekBarPreference) return ((TunableSeekBarPreference) p).isChanged() ? () -> ((TunableSeekBarPreference) p).resetToDefault(true) : null;
        if (p instanceof TunableCheckBoxPreference) {
            final TunableCheckBoxPreference t = (TunableCheckBoxPreference) p;
            return t.isChecked() != (t.defaultInt() != 0) ? t::resetToDefault : null;
        }
        return null;
    }
}
