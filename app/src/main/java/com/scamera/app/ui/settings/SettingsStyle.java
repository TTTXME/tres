package com.scamera.app.ui.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.widget.NestedScrollView;
import androidx.preference.DialogPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.EditTextPreferenceAccess;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.TwoStatePreference;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.scamera.app.R;
import com.scamera.app.circularbarlib.ui.AccentPalette;
import com.scamera.app.circularbarlib.ui.UiTokens;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.ui.settings.custompreferences.BackupPreferences;
import com.scamera.app.ui.settings.custompreferences.ModuleChipsPreference;
import com.scamera.app.ui.settings.custompreferences.ModuleRowPreference;
import com.scamera.app.ui.settings.custompreferences.ModuleSection;
import com.scamera.app.ui.settings.custompreferences.ResetPreferences;
import com.scamera.app.ui.settings.custompreferences.RestorePreference;
import com.scamera.app.ui.settings.custompreferences.TunableKeyPreference;
import com.scamera.app.ui.settings.custompreferences.TunableSeekBarPreference;
import com.scamera.app.ui.settings.custompreferences.UniversalSeekBarPreference;
import com.scamera.app.util.Lang;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * One look for every settings screen (P6b): the card style of «Камеры и сенсоры». The tokens and builders are shared with
 * {@link ModuleConceptFragment}; {@link #apply} picks each preference's row layout by its class, {@link #bind} adds what
 * depends on the user's accent and on the current state (value line, switch and slider tint, dimmed rows).
 */
public final class SettingsStyle {
    private SettingsStyle() {}

    // The shared tokens live in circularbarlib's UiTokens (one source of truth for the settings, the shade and the manual
    // controls); res/values/settings_style.xml mirrors them for the layouts.
    public static final int BG = UiTokens.BG, CARD = UiTokens.CARD, TEXT = UiTokens.TEXT, MUTED = UiTokens.MUTED, LINE = UiTokens.LINE;
    public static final int FIELD = UiTokens.FIELD, SHEET = UiTokens.SHEET, INK = UiTokens.INK;
    public static final int WARN = 0xFFFFB4A8;
    public static final int OFF_TRACK = 0xFF3A3E45, OFF_THUMB = 0xFF9AA0A7;
    /** P384: the rows of an open module card, a step lighter than the card (res: settings_card_nested). */
    public static final int CARD_NESTED = 0xFF3A3A3C;
    /**
     * P574 (owner, 2026-11-09: «Отлично, реализуем» - the settings in the look of the camera's own sheets, not blue iOS): the page,
     * its group cards, the 0.5dp hairlines between rows, a row's title, a value, a chevron, a section title, a slider's ends, the
     * grey fill of a capsule button and the red of a destructive one (res: settings_page ... settings_range). The camera's
     * colours (BG, CARD, ... from UiTokens) stay as they are: the shade and the gallery read them through this class.
     */
    public static final int PAGE = 0xFF1C1C1E, ROW = 0xFF2C2C2E, HAIRLINE = 0xA6545458, ROW_TITLE = 0xFFFFFFFF,
            VALUE = 0x8CFFFFFF, SECTION = 0x8CFFFFFF, CHEVRON = 0x59FFFFFF, RANGE = 0x66FFFFFF, CAPSULE = 0x4D767680,
            RED = 0xFFFF453A;
    /** P384: the expert groups of the main page (their title carries the «для опытных» pill). */
    static final Set<String> EXPERT_GROUPS = new HashSet<>(Arrays.asList("expert_module_sensor"));
    public static final float DIMMED = UiTokens.DIMMED;
    /**
     * P139 / P574: the line under a row's title, white at 60 % (res: settings_summary); titles are semibold white. P592: that
     * line is the row's state only - its description is in the long press (SettingHelp).
     */
    public static final int SUMMARY = 0x99FFFFFF;

    /** Rows drawn as tiles three to a line (SIMPLE_UI §4: the profile actions are rows now, as the concept; none left). */
    static final Set<String> TILES = new HashSet<>();
    /** Plain preferences that open a page (handled in SettingsFragment.onPreferenceTreeClick): a chevron, not an action. */
    static final Set<String> NAVIGATION = new HashSet<>(Arrays.asList("pref_dcp_profile_key", "pref_theme_accent_key",
            "pref_file_name_pattern"));

    public static int dp(Context c, float v) { return UiTokens.dp(c, v); }

    /** P574: the settings take the camera's accent (yellow by default, the user's choice otherwise), as its sheets. */
    public static int accent(Context c) { return AccentPalette.camera(c); }

    /** The accent at a given alpha over the card colour (chips, pills). */
    public static int tint(int accent, float alpha) {
        return UiTokens.tint(accent, alpha);
    }

    public static GradientDrawable shape(Context c, int color, int stroke, float radius) {
        return UiTokens.shape(c, color, stroke, radius);
    }

    public static TextView text(Context c, CharSequence value, float size, int color) {
        TextView t = new TextView(c);
        t.setText(value);
        // P584: the concept's px as dp (the phone's font scale does not change the settings) in its Roboto Flex
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, size);
        t.setTypeface(com.scamera.app.ui.style.Fonts.of(c, com.scamera.app.ui.style.Fonts.REGULAR));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    // ───── Header (P574, the concept: a bar of round 40dp buttons, the large page title, the search capsule) ─────

    public static final class Header {
        /** The whole header (bar, then titles) for pages that keep it still; SettingsActivity takes the two apart. */
        public LinearLayout view;
        /** The bar: back, the breadcrumb (or the page's title once the large one scrolled away), search, arrange, reset. */
        public LinearLayout bar;
        /** The large title and its subtitle. */
        public LinearLayout titles;
        public TextView heading, subtitle;
        /** The bar's centre: the path to this page ({@link #crumb}) and the small title ({@link #small}). */
        public TextView crumb, small;
        public ImageView back, search, reset, arrange;
    }

    /** P385: the header's «Расположение» disc (the page's order), before the reset disc. */
    public static ImageView arrangeDisc(Context c, Header h, Runnable onArrange) {
        LinearLayout row = h.bar;
        h.arrange = disc(c, R.drawable.cs_ic_arrange, 19);
        h.arrange.setContentDescription(Lang.t(c, "Расположение настроек страницы", "This page's layout"));
        h.arrange.setTag("settings_page_arrange");
        h.arrange.setFocusable(true);
        h.arrange.setOnClickListener(v -> onArrange.run());
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40));
        ap.setMarginStart(dp(c, 8));
        row.addView(h.arrange, h.reset != null ? row.indexOfChild(h.reset) : row.getChildCount(), ap);
        balance(c, h);
        return h.arrange;
    }

    /**
     * The header of a settings page. view = LinearLayout {bar (back disc, centre (crumb, small title), search disc), titles
     * (heading, subtitle)}. {@code onSearch} null: no search button.
     */
    public static Header header(Context c, Runnable onBack, Runnable onSearch) {
        return header(c, onBack, onSearch, null);
    }

    /** The header with, for {@code onReset}, a reset disc at its end (P357: the page's rows back to their defaults). */
    public static Header header(Context c, Runnable onBack, Runnable onSearch, Runnable onReset) {
        Header h = new Header();
        h.view = new LinearLayout(c);
        h.view.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(c);
        h.bar = row;
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 44));
        h.back = disc(c, R.drawable.cs_back, 20);   // P584: the concept's chevron (20dp, 2.4 stroke)
        h.back.setContentDescription(Lang.t(c,"Назад","Back"));
        h.back.setFocusable(true);
        h.back.setOnClickListener(v -> onBack.run());
        row.addView(h.back, new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)));
        // the centre: the path to the page at rest, the page's title once its large title scrolled away (collapse)
        FrameLayout centre = new FrameLayout(c);
        h.crumb = text(c, "", 13, SECTION);
        h.small = text(c, "", 16, ROW_TITLE);
        h.small.setTypeface(com.scamera.app.ui.style.Fonts.of(h.small.getContext(), 600));
        h.small.setAlpha(0f);
        for (TextView t : new TextView[]{h.crumb, h.small}) {
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            centre.addView(t, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        }
        h.small.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1f);
        cp.setMarginStart(dp(c, 8));   // P584: the end discs bring their own 8dp gap (the concept's gap: 8 on each side)
        row.addView(centre, cp);
        if (onSearch != null) {
            h.search = disc(c, R.drawable.cs_search, 19);
            h.search.setContentDescription(Lang.t(c,"Поиск настройки","Search settings"));
            h.search.setTag("settings_search");
            h.search.setFocusable(true);
            h.search.setOnClickListener(v -> onSearch.run());
            LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40));
            qp.setMarginStart(dp(c, 8));
            row.addView(h.search, qp);
        }
        if (onReset != null) {
            h.reset = disc(c, R.drawable.cs_page_reset, 19);
            h.reset.setContentDescription(Lang.t(c, "Сбросить настройки страницы", "Reset this page"));
            h.reset.setTag("settings_page_reset");
            h.reset.setFocusable(true);
            h.reset.setOnClickListener(v -> onReset.run());
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40));
            rp.setMarginStart(dp(c, 8));
            row.addView(h.reset, rp);
        }
        h.view.addView(row, new LinearLayout.LayoutParams(-1, -2));
        balance(c, h);
        // P584 (the concept's h1): 14dp over the title, 12dp under it; 700, -0.4px apart, the line 1.12 of the size
        h.titles = new LinearLayout(c);
        h.titles.setOrientation(LinearLayout.VERTICAL);
        h.titles.setPadding(dp(c, 4), dp(c, 14), dp(c, 4), dp(c, 12));
        h.heading = text(c, "", 34, ROW_TITLE);
        h.heading.setTypeface(com.scamera.app.ui.style.Fonts.of(c, com.scamera.app.ui.style.Fonts.BOLD));
        h.heading.setLetterSpacing(-0.4f / 34f);
        lineHeight(h.heading, 34 * 1.12f);
        h.heading.setMaxLines(2);
        h.heading.setEllipsize(TextUtils.TruncateAt.END);
        // the concept: 34sp, 30sp past 16 letters, 26sp past 24 («Приоритет умеренных светов»); the bar's small title follows
        h.heading.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int n) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                h.heading.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, titleSize(s));
                h.heading.setLetterSpacing(-0.4f / titleSize(s));
                lineHeight(h.heading, titleSize(s) * 1.12f);
                h.small.setText(s.toString());
            }
        });
        h.titles.addView(h.heading, new LinearLayout.LayoutParams(-1, -2));
        // the page's description: 14 / 19dp, white at 65 %, 4dp into the title's margin, 14dp over the list
        h.subtitle = text(c, "", 14, 0xA6FFFFFF);
        lineHeight(h.subtitle, 19);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(c, 8);
        sp.bottomMargin = dp(c, 2);
        h.titles.addView(h.subtitle, sp);
        h.view.addView(h.titles, new LinearLayout.LayoutParams(-1, -2));
        return h;
    }

    /** P584: {@code t}'s lines {@code dp} apart (the concept's line-height; before Android 9 the font's own). */
    public static void lineHeight(TextView t, float dp) {
        if (android.os.Build.VERSION.SDK_INT < 28) return;
        final int px = Math.round(dp * t.getResources().getDisplayMetrics().density);
        t.setLineHeight(px);
        // the concept's half-leading: Android adds a line's extra under it but not under the last one - half of it over the first
        // line and half under the last make each line box {@code dp} tall as in the concept
        final int extra = Math.max(0, px - t.getPaint().getFontMetricsInt(null));
        t.setPaddingRelative(t.getPaddingStart(), extra / 2, t.getPaddingEnd(), extra - extra / 2);
    }

    /**
     * P584 (owner: «названия не отцентрованы»): the bar's crumb centred on the screen, as the concept's between its two discs - a
     * bar with more discs at its end (search, arrange, reset) gives the crumb as much room at its start.
     */
    static void balance(Context c, Header h) {
        if (h.bar == null || h.crumb == null || !(h.crumb.getParent() instanceof View)) return;
        int end = 0;
        for (ImageView v : new ImageView[]{h.search, h.arrange, h.reset}) if (v != null && v.getParent() == h.bar) end++;
        // the back disc and its 8dp gap at the start, each end disc with its 8dp gap at the end
        View centre = (View) h.crumb.getParent();
        centre.setPaddingRelative(Math.max(0, end - 1) * dp(c, 48), 0, end == 0 ? dp(c, 48) : 0, 0);
    }

    /** The large title's size for {@code title} (dp): 34, 30 past 16 letters, 26 past 24. */
    static float titleSize(@androidx.annotation.Nullable CharSequence title) {
        int n = title == null ? 0 : title.length();
        return n > 24 ? 26 : n > 16 ? 30 : 34;
    }

    /**
     * How far the large title has scrolled under the bar ({@code k} 0..1): it fades out, the bar's small title fades in over the
     * path. Driven by the list's scroll, frame by frame (no animation of its own).
     */
    public static void collapse(Header h, float k) {
        k = Math.max(0f, Math.min(1f, k));
        h.titles.setAlpha(1f - Math.min(1f, k * 1.4f));
        float shown = Math.max(0f, (k - .55f) / .45f);
        h.small.setAlpha(shown);
        h.crumb.setAlpha(1f - shown);
    }

    private static android.graphics.Typeface semibold;

    /**
     * A semibold face: P584 the concept's Roboto Flex 600 (the application's resources; the system's 600 only when none is set
     * up yet). Views take {@code Fonts.of(context, 600)}.
     */
    public static android.graphics.Typeface semibold() {
        android.content.Context app = null;
        try { app = com.scamera.app.app.ScameraApp.getAppContext(); } catch (RuntimeException ignored) { /* none yet */ }
        if (app != null) return com.scamera.app.ui.style.Fonts.of(app, 600);
        if (semibold == null) semibold = android.os.Build.VERSION.SDK_INT >= 28
                ? android.graphics.Typeface.create(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL), 600, false)
                : android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL);
        return semibold;
    }

    /**
     * SIMPLE_UI §4 / P574: the search under the root's large title - the concept's capsule (#2C2C2E, 44dp, 22dp corners), the glass
     * and «Поиск» at 55 % white; a tap opens the search page.
     */
    public static LinearLayout searchPill(Context c, Runnable onSearch) {
        LinearLayout pill = new LinearLayout(c);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setMinimumHeight(dp(c, 44));
        pill.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        pill.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), shape(c, ROW, 0, 22), null));
        ImageView icon = new ImageView(c);
        icon.setImageResource(R.drawable.cs_search);   // P584: the concept's glass, 19dp, white at 55 %
        icon.setColorFilter(new android.graphics.PorterDuffColorFilter(SECTION, android.graphics.PorterDuff.Mode.SRC_IN));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(c, 19), dp(c, 19));
        ip.setMarginEnd(dp(c, 8));
        pill.addView(icon, ip);
        pill.addView(text(c, Lang.t(c, "Поиск", "Search"), 17, SECTION), new LinearLayout.LayoutParams(0, -2, 1f));
        pill.setContentDescription(Lang.t(c, "Поиск настройки", "Search settings"));
        pill.setTag("settings_search");
        pill.setFocusable(true);
        pill.setOnClickListener(v -> onSearch.run());
        return pill;
    }

    /** P574: the concept's round button - a 40dp #2C2C2E disc with a white line icon. */
    static ImageView disc(Context c, int icon) {
        return disc(c, icon, 0);
    }

    /** P584: the disc with its icon {@code size} dp (the concept's 20 for the back chevron, 19 for the others; 0: its own size). */
    static ImageView disc(Context c, int icon, int size) {
        ImageView v = new ImageView(c);
        v.setImageResource(icon);
        SettingsTiles.ink(v, ROW_TITLE);   // P574: white on the round button (P584: a concept glyph as it is)
        if (size > 0) {
            int pad = Math.round((40 - size) / 2f * c.getResources().getDisplayMetrics().density);
            v.setPadding(pad, pad, pad, pad);
            v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        } else v.setScaleType(ImageView.ScaleType.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(ROW);
        v.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), d, null));
        return v;
    }

    /**
     * The module screen's chip: «Настраивается: …», «Активна: …». P574 (the approved «Камеры и модули»): a #2C2C2E capsule, 40dp,
     * white 15sp semibold text; a trailing «▾» (the module picker) in the accent.
     */
    public static TextView chip(Context c, CharSequence value) {
        int accent = accent(c);
        CharSequence shown = value;
        String s = value == null ? "" : value.toString();
        if (s.endsWith("▾")) {
            android.text.SpannableString span = new android.text.SpannableString(s);
            span.setSpan(new android.text.style.ForegroundColorSpan(accent), s.length() - 1, s.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            shown = span;
        }
        TextView t = text(c, shown, 15, ROW_TITLE);
        t.setTypeface(com.scamera.app.ui.style.Fonts.of(t.getContext(), 600));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(dp(c, 40));
        t.setPadding(dp(c, 16), dp(c, 8), dp(c, 14), dp(c, 8));
        t.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), shape(c, ROW, 0, 20), null));
        t.setTag("settings_chip");
        return t;
    }

    // ───── Tree: row layout by preference class ─────

    public static void apply(PreferenceGroup group) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference p = group.getPreference(i);
            style(p, group);
            if (p instanceof PreferenceGroup) apply((PreferenceGroup) p);
        }
    }

    /**
     * The row layout of {@code p} in {@code parent} (P384: also for a row built later, before it is added - the list fixes a row's
     * layout when it first sees it): a module card's header, the expert groups' titles, a module's sensor caption, the gap under
     * the module cards, the module chips, and inside an open module card the nested row.
     */
    public static void style(Preference p, @androidx.annotation.Nullable PreferenceGroup parent) {
        if (p instanceof ModuleSection) {
            p.setLayoutResource(R.layout.preference_module_card);
            p.setSingleLineTitle(false);
            return;
        }
        if (p instanceof ModuleChipsPreference) {
            p.setLayoutResource(R.layout.preference_module_chips);
            return;
        }
        if (p instanceof PreferenceCategory) {
            String key = p.getKey();
            if (key != null && EXPERT_GROUPS.contains(key)) p.setLayoutResource(R.layout.preference_category_expert);
            else if (key != null && key.startsWith(com.scamera.app.settings.SensorConfigPreferenceGenerator.CATEGORY_PREFIX))
                p.setLayoutResource(R.layout.preference_subcategory_layout);
            else if ("module_actions".equals(key)) p.setLayoutResource(R.layout.preference_spacer);
            else p.setLayoutResource(R.layout.preference_category_layout);
            return;
        }
        rowLayout(p);
        if (parent instanceof ModuleSection && p.getLayoutResource() == R.layout.preference_card)
            p.setLayoutResource(R.layout.preference_card_nested);
        if (emptyNote(p) && p.isVisible()) p.setVisible(false);   // P592
    }

    private static void rowLayout(Preference p) {
        p.setSingleLineTitle(false);
        // P144: the own tone curve draws its own card (graph and shortcuts); P162: so does the crop picture
        if (p instanceof com.scamera.app.ui.settings.custompreferences.ToneCurvePreference
                || p instanceof com.scamera.app.ui.settings.custompreferences.RawCropPreference
                || p instanceof com.scamera.app.ui.settings.custompreferences.SegmentedListPreference // P490
                    && ((com.scamera.app.ui.settings.custompreferences.SegmentedListPreference) p).segments() // P574
                || page(p)) {
            p.setIconSpaceReserved(false);
            return;
        }
        if (p.getKey() != null && TILES.contains(p.getKey())) {
            p.setLayoutResource(R.layout.preference_tile);
            return;
        }
        if (p instanceof PreferenceGroup) {
            p.setLayoutResource(R.layout.preference_card);
            p.setWidgetLayoutResource(R.layout.settings_widget_chevron);
            defaultIcon(p, R.drawable.cs_gear);
            return;
        }
        if (com.scamera.app.processing.color.FilmLut.STRENGTH_KEY.equals(p.getKey())) {
            p.setLayoutResource(R.layout.preference_seekbar_inline);   // SIMPLE_UI §4c: «Сила» on the LUT page
        } else if (p instanceof UniversalSeekBarPreference || p instanceof TunableSeekBarPreference) {
            p.setLayoutResource(R.layout.preference_seekbar);
            defaultIcon(p, R.drawable.cs_ic_sliders);
        } else if (p instanceof TwoStatePreference) {
            p.setLayoutResource(R.layout.preference_card);
            // P574: the settings' switch (a check box of a choice list stays a check box)
            if (!(p instanceof androidx.preference.CheckBoxPreference)) p.setWidgetLayoutResource(R.layout.settings_widget_switch);
            defaultIcon(p, R.drawable.cs_ic_toggle);
        } else if (p instanceof ListPreference) {
            p.setLayoutResource(R.layout.preference_card);
            // P574: a list opens its pull-down menu at the row: the up / down chevron after the value (the restore keeps its dialog)
            p.setWidgetLayoutResource(p instanceof RestorePreference ? R.layout.settings_widget_chevron : R.layout.settings_widget_updown);
            defaultIcon(p, R.drawable.cs_ic_list);
        } else if (p instanceof EditTextPreference) {
            p.setLayoutResource(R.layout.preference_card);
            p.setWidgetLayoutResource(R.layout.settings_widget_chevron);
            defaultIcon(p, R.drawable.cs_ic_edit);
        } else if (p instanceof DialogPreference || p instanceof TunableKeyPreference || p instanceof ModuleRowPreference) {
            p.setLayoutResource(R.layout.preference_card);
        } else if (p.getClass() == Preference.class && !p.isSelectable() && p.getIcon() == null) {
            p.setLayoutResource(R.layout.preference_info);
        } else {
            p.setLayoutResource(R.layout.preference_card);
            if (p.getFragment() != null || p.getIntent() != null || (p.getKey() != null && NAVIGATION.contains(p.getKey())))
                p.setWidgetLayoutResource(R.layout.settings_widget_chevron);
            defaultIcon(p, p.isSelectable() ? R.drawable.cs_gear : R.drawable.cs_ic_info);
        }
        p.setIconSpaceReserved(true);
    }

    /** P592: a note - a plain row that does nothing and only explains (the info layout): a tap opens its text. */
    static boolean note(@androidx.annotation.Nullable Preference p) {
        return p != null && p.getClass() == Preference.class && !p.isSelectable() && p.getLayoutResource() == R.layout.preference_info
                && p.getKey() != null && !TextUtils.isEmpty(p.getTitle()) && TextUtils.isEmpty(SettingHelp.stateLine(p));
    }

    /**
     * P592: a note without a title was nothing but a description under the rows; with the descriptions in the long press it
     * has nothing to show and leaves the page (its text is in the help of the rows it explained). One that shows a state stays.
     */
    static boolean emptyNote(Preference p) {
        return p.getClass() == Preference.class && !p.isSelectable() && p.getIcon() == null && TextUtils.isEmpty(p.getTitle())
                && TextUtils.isEmpty(SettingHelp.stateLine(p));
    }

    /**
     * SIMPLE_UI §4: rows that draw their own card or are no row (the tone curve, the crop picture, the «Конфиг» tiles, the
     * info notes): they stand outside the group cards of SettingsCardDecoration.
     */
    public static boolean standalone(@androidx.annotation.Nullable Preference p) {
        if (p == null) return true;
        if (p instanceof ModuleChipsPreference) return true;   // P384: the module chips sit on the page, not in a card
        if (p instanceof com.scamera.app.ui.settings.custompreferences.ToneCurvePreference
                || p instanceof com.scamera.app.ui.settings.custompreferences.RawCropPreference) return true;
        if (p.getKey() != null && TILES.contains(p.getKey())) return true;
        if (page(p) || p.getLayoutResource() == R.layout.preference_seekbar_inline) return true;
        return p.getLayoutResource() == R.layout.preference_info;
    }

    /** SIMPLE_UI §4c: the parts of the «Плёночный LUT» page draw themselves (preview, strip, «Что меняется»). */
    static boolean page(Preference p) {
        return p instanceof com.scamera.app.ui.settings.custompreferences.FilmLutPreference
                || p instanceof com.scamera.app.ui.settings.custompreferences.FilmSimPreference
                || p instanceof com.scamera.app.ui.settings.custompreferences.LutChangesPreference
                || p instanceof com.scamera.app.ui.settings.custompreferences.LensFilterPreference
                || p instanceof com.scamera.app.ui.settings.custompreferences.PhotoStylePreference
                || p instanceof com.scamera.app.ui.settings.custompreferences.ShadeTransparencyPreference
                || p instanceof com.scamera.app.ui.settings.custompreferences.WatermarkPreviewPreference // P628
                || p instanceof com.scamera.app.ui.settings.custompreferences.ProfilesPreference
                || p instanceof com.scamera.app.ui.settings.custompreferences.FormatPickerPreference
                || p instanceof com.scamera.app.ui.settings.custompreferences.SizePickerPreference;
    }

    private static void defaultIcon(Preference p, int icon) {
        if (p.getIcon() == null) {
            p.setIcon(icon);
            DEFAULTED.put(p, Boolean.TRUE);
        }
    }

    /** P584: rows whose icon is the style's stand-in (none in the XML): the concept's search shows them without a tile. */
    private static final java.util.Map<Preference, Boolean> DEFAULTED = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static boolean defaulted(Preference p) {
        return DEFAULTED.containsKey(p);
    }

    /** A clickable plain row that is an action (import, probe, export): the title in the accent, as in the concept. */
    private static boolean action(Preference p) {
        return p.getClass() == Preference.class && p.isSelectable() && p.getWidgetLayoutResource() == 0
                && p.getFragment() == null && p.getIntent() == null;
    }

    // ───── Bind: accent, value line, state ─────

    /** P248: the right-hand value's share of a row's text width at most (the title and the description keep the rest). */
    static final float VALUE_SHARE = 0.38f;
    /** P584: the concept's right-hand value, at most 130dp (its max-width). */
    static final float VALUE_MAX = 130f;

    /**
     * The widest the value on the right may be, in pixels: its share of what a settings row leaves for text on this screen (the
     * screen less the card's margins, the row's padding, the icon tile and the chevron).
     */
    public static int valueShare(Context c) {
        float density = c.getResources().getDisplayMetrics().density;
        float text = c.getResources().getDisplayMetrics().widthPixels - (16 * 2 + 14 * 2 + 36 + 12 + 24) * density;
        // P584: the concept's value is at most 130dp wide; a narrow screen keeps half of the text for the title
        return Math.max(Math.round(48 * density), Math.min(Math.round(VALUE_MAX * density), Math.round(.5f * text)));
    }

    /**
     * P406 (owner, 2026-10-29: «описание у всех параметров должно быть не больше двух строк, расширенное описание у нас
     * показывается по зажатию по параметру»; P574, 2026-11-09: three lines, never cut in the middle of a word): a row's summary
     * shows at most {@link #SUMMARY_LINES} lines - its whole sentences that fit, else its whole words that fit with «…»; the long
     * press (SettingHelp) shows the whole text. Fitted again whenever the row's width changes. P592 (owner, 2026-11-10: «Убираем
     * все описания у параметров, оставляем подробное описание только по зажатию»): the line is the row's state alone
     * ({@link SettingHelp#stateLine}); a description is never drawn in a row.
     */
    static final int SUMMARY_LINES = 3;

    static void twoLines(TextView summary) {
        fitLines(summary);
    }

    static void fitLines(TextView summary) {
        summary.setMaxLines(SUMMARY_LINES);
        summary.setEllipsize(TextUtils.TruncateAt.END);
        summary.setTag(R.id.settings_full_summary, summary.getText());
        if (summary.getTag(R.id.settings_two_lines) == null) {
            summary.setTag(R.id.settings_two_lines, Boolean.TRUE);
            summary.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitSummary((TextView) v));
        }
        fitSummary(summary);
    }

    private static void fitSummary(TextView summary) {
        final Object full = summary.getTag(R.id.settings_full_summary);
        final int width = summary.getWidth() - summary.getTotalPaddingLeft() - summary.getTotalPaddingRight();
        if (!(full instanceof CharSequence) || width <= 0) return;
        final CharSequence fit = fitText((CharSequence) full, summary.getPaint(), width, SUMMARY_LINES);
        if (!TextUtils.equals(fit, summary.getText())) {
            summary.setText(fit);
            // fitted in the middle of a layout pass: measured again on the next frame (a shorter text, a lower row)
            summary.post(summary::requestLayout);
        }
    }

    /**
     * {@code text} if it takes at most {@code max} lines of {@code width} px; else its longest run of whole sentences that does,
     * else its longest run of whole words with «…» that does (never a word cut in two).
     */
    static CharSequence fitText(CharSequence text, android.text.TextPaint paint, int width, int max) {
        if (lines(text, paint, width) <= max) return text;
        final CharSequence head = sentences(text, paint, width, max);
        if (head != null) return head;
        final String s = text.toString();
        String best = null;
        int lo = 0;
        for (int i = s.indexOf(' '); i > 0; i = s.indexOf(' ', i + 1)) {
            final String cut = s.substring(0, i).replaceAll("[\\s,;:—–-]+$", "") + "…";
            if (lines(cut, paint, width) > max) break;
            best = cut;
            lo = i;
        }
        return best != null && lo > 0 ? best : text;
    }

    @androidx.annotation.Nullable
    private static String sentences(CharSequence text, android.text.TextPaint paint, int width, int max) {
        final String s = text.toString();
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile("[.!?…;](?=\\s)").matcher(s);
        String best = null;
        while (m.find()) {
            final String head = s.substring(0, m.end());
            if (lines(head, paint, width) > max) break;
            best = head.endsWith(";") ? head.substring(0, head.length() - 1) + "…" : head;
        }
        return best;
    }

    private static int lines(CharSequence text, android.text.TextPaint paint, int width) {
        return android.text.StaticLayout.Builder.obtain(text, 0, text.length(), paint, width).build().getLineCount();
    }

    /** A value whose one line is wider than {@code share} pixels goes under the title (on the right it squeezed the text). */
    static boolean valueBelow(float valueWidth, int share) {
        return valueWidth > share;
    }

    /** P614: the extra of a group greyed as a whole (SettingsAvailability.groupReason). */
    static final String EXTRA_GROUP_OFF = "settings_group_off";

    /**
     * P614: marks the group {@code p} greyed as a whole ({@code reason}, its note under the title) or not (null). Returns whether
     * the group is or was such a group: only those have their enabled state set (the other categories keep theirs).
     */
    static boolean groupOff(Preference p, @androidx.annotation.Nullable String reason) {
        final boolean was = p.peekExtras() != null && p.peekExtras().getBoolean(EXTRA_GROUP_OFF);
        if (reason == null && !was) return false;
        p.getExtras().putBoolean(EXTRA_GROUP_OFF, reason != null);
        SettingHelp.state(p, reason);
        return true;
    }

    /** P384: the alpha a bound row rests at (a disabled one, a dimmed module row), for SettingsCardDecoration's fades. */
    public static float restingAlpha(@androidx.annotation.Nullable Preference p) {
        if (p instanceof PreferenceCategory)   // P614: a group greyed as a whole
            return p.peekExtras() != null && p.peekExtras().getBoolean(EXTRA_GROUP_OFF) ? DIMMED : 1f;
        if (p == null) return 1f;
        if (p instanceof ModuleRowPreference && ((ModuleRowPreference) p).dimmed()) return .55f;
        return p.isEnabled() ? 1f : DIMMED;
    }

    /** P384: a row whose action loses something - the two resets, a module's sensor reset, deleting a copy: warning colours. */
    static boolean warn(Preference p) {
        String key = p.getKey();
        return "pref_reset_preferences_key".equals(key) || (key != null && key.startsWith("pref_reset_sensor_config_settings"))
                || (p instanceof ModuleRowPreference && ((ModuleRowPreference) p).warn());
    }

    public static void bind(PreferenceViewHolder holder, Preference p) {
        View row = holder.itemView;
        Context c = row.getContext();
        int accent = accent(c);
        String key = p.getKey();
        // P384: the preference this view shows (SettingsCardDecoration reads it, also while the row fades out of the list)
        final Object before = row.getTag(R.id.settings_bound_preference);
        row.setTag(R.id.settings_bound_preference, p);
        // P614: a row bound again turning on or off (the «Why тон» switch) fades to its new alpha; a new row takes it at once
        SettingsMotion.rest(row, restingAlpha(p), before == p);
        if (p instanceof PreferenceCategory) {
            // a category is never "enabled" (PreferenceCategory.isEnabled is false): its label is not a dimmed row (restingAlpha:
            // 1, P614: dimmed as a group of the other tone version)
            TextView title = (TextView) holder.findViewById(android.R.id.title);
            // P574: a group's title in white at 55 %; P384: a module's sensor caption stays quiet (its layout's colour)
            if (title != null && p.getLayoutResource() != R.layout.preference_subcategory_layout) {
                title.setTextColor(SECTION);
                title.setTypeface(com.scamera.app.ui.style.Fonts.of(title.getContext(), 600));
            }
            TextView pill = (TextView) holder.findViewById(R.id.settings_expert_pill);
            if (pill != null) {
                pill.setTextColor(accent);
                pill.setBackground(shape(c, pillFill(accent), 0, 999));
            }
            stateLine(holder, p);   // P592: a group's title alone (its hint is its help)
            ConceptFonts.apply(row);   // P584
            return;
        }
        if (p instanceof ModuleSection) { ConceptFonts.apply(row); return; }   // P384: the card header binds its badge and chevron itself
        ImageView icon = (ImageView) holder.findViewById(android.R.id.icon);
        View tile = holder.findViewById(R.id.settings_icon_tile);
        boolean warn = warn(p);
        // P570: the row's colour icon takes its pressed pose with the row
        if (icon != null) {
            icon.setDuplicateParentStateEnabled(true);
            if (icon.getParent() instanceof View && icon.getParent() != holder.itemView) ((View) icon.getParent()).setDuplicateParentStateEnabled(true);
        }
        // P574 (owner: the coloured tiles stay): the tile in the dark tone of the icon's hue, the icon in its light tone; the reset
        // rows in the red pair; a row without an icon has no tile (its text starts at the card's edge)
        if (tile != null) tile.setVisibility(icon != null && icon.getDrawable() != null ? View.VISIBLE : View.GONE);
        if (icon != null && icon.getDrawable() != null && !(p instanceof TunableKeyPreference)) {
            int[] pair = warn ? SettingsTiles.RED : SettingsTiles.pair(icon.getDrawable());
            if (tile != null) tile.setBackground(shape(c, pair[0], 0, tile.getLayoutParams() != null && tile.getLayoutParams().width < dp(c, 29) ? 8 : 9));
            SettingsTiles.ink(icon, pair[1]);   // P584: a concept glyph in its light tone as it is
            // P574 (the approved «Камеры и модули»): «Добавить кнопку с кропом» - the «+» in the accent on its tile (the accent at
            // 28 % over the card)
            if (p instanceof ModuleRowPreference && ((ModuleRowPreference) p).accentTile()) {
                if (tile != null) tile.setBackground(shape(c, SettingsMotion.mix(ROW, accent, .28f), 0, 9));
                SettingsTiles.ink(icon, accent);
            }
        } else if (p instanceof TunableKeyPreference) {
            // its icon is the key's status dot, drawn as it is (a recycled row's tone goes)
            if (icon != null) icon.clearColorFilter();
            if (tile != null) tile.setBackground(shape(c, SettingsTiles.GREY[0], 0, 9));
        }
        TextView title = (TextView) holder.findViewById(android.R.id.title);
        if (title != null && warn) title.setTextColor(WARN);
        else if (title != null && action(p)) title.setTextColor(accent);
        else if (title != null) title.setTextColor(ROW_TITLE);   // P384: a recycled view that showed a warning or an action row
        if (title != null) {
            title.setTypeface(com.scamera.app.ui.style.Fonts.of(c, 600));
            if (p.getLayoutResource() != R.layout.preference_module_card) lineHeight(title, 21);   // P584: 16 / 21dp
        }
        // P384: a camera key's name breaks after its dots («vivo.control.forceSensorMo / de» broke inside a word); shown only
        if (title != null && p instanceof TunableKeyPreference && p.getTitle() != null)
            title.setText(p.getTitle().toString().replace(".", ".\u200B"));
        // P214g: «↻» - the camera session reads it when it is built (SessionKeys: the module's session type, OIS, vendor keys,
        // 16:9, live RAW ...): a change takes a new session, the camera restarts with it
        // SIMPLE_UI §4: as the concept, a row with its own restart pill says it in words («Камера перезапустится») under the
        // description; the other rows keep the mark on the title
        // P592 (owner's review, 2026-11-10: the badges go with the descriptions): no pill and no «↻» on the row; that a change
        // restarts the camera is said in its long press (SettingHelp.restarts), and the camera's message says it when it does
        TextView restart = (TextView) holder.findViewById(R.id.settings_restart);
        if (restart != null) restart.setVisibility(View.GONE);
        // P592: under the title only the row's state (an unavailable row's reason, «Выбран вами», a trial's failure); what the
        // row does is its help (the long press)
        TextView summary = stateLine(holder, p);
        TextView value = (TextView) holder.findViewById(R.id.settings_value);
        if (value != null) {
            CharSequence shown = valueOf(p);
            value.setText(shown);
            value.setTextColor(VALUE);   // P574: the value on the right, white at 55 % (the concept's .rv)
            // P248 (owner, 2026-10-18: «Режим автофокуса», «Кадры светов», «Разрешение» - the title and the description one
            // word a line beside a long value): a value wider than its share of the row goes under the title
            TextView below = (TextView) holder.findViewById(R.id.settings_value_below);
            int share = valueShare(c);
            boolean under = below != null && !TextUtils.isEmpty(shown) && valueBelow(value.getPaint().measureText(shown.toString()), share);
            value.setMaxWidth(share);
            value.setVisibility(TextUtils.isEmpty(shown) || under ? View.GONE : View.VISIBLE);
            if (below != null) {
                below.setText(under ? shown : null);
                below.setTextColor(VALUE);
                below.setVisibility(under ? View.VISIBLE : View.GONE);
            }
            // the value has its own place: a state that only repeats it is not shown twice
            if (summary != null && !TextUtils.isEmpty(shown) && summary.getText() != null
                    && summary.getText().toString().equals(shown.toString())) summary.setVisibility(View.GONE);
        }
        if (summary != null && summary.getVisibility() == View.VISIBLE) {
            if (p.getLayoutResource() != R.layout.preference_module_card) lineHeight(summary, 17);   // P584: 13 / 17dp
            summary.setTextColor(SUMMARY);
            fitLines(summary);
        }
        View widget = holder.findViewById(androidx.preference.R.id.switchWidget);
        if (widget instanceof com.scamera.app.ui.controls.IosSwitch && p instanceof TwoStatePreference)
            bindSwitch((com.scamera.app.ui.controls.IosSwitch) widget, (TwoStatePreference) p);
        else if (widget instanceof SwitchCompat) {
            // a switch row built without SettingsStyle.apply keeps the theme's switch: in the settings' colours
            SwitchCompat sw = (SwitchCompat) widget;
            int[][] states = {{android.R.attr.state_checked}, {}};
            sw.setThumbTintList(new ColorStateList(states, new int[]{0xFFFFFFFF, OFF_THUMB}));
            sw.setTrackTintList(new ColorStateList(states, new int[]{accent, OFF_TRACK})); // P574: the theme's colour, as IosSwitch
        }
        View bar = holder.findViewById(R.id.seekbar);
        if (bar instanceof com.scamera.app.ui.controls.IosSlider) {
            com.scamera.app.ui.controls.IosSlider s = (com.scamera.app.ui.controls.IosSlider) bar;
            s.setAccent(accent);
            s.setEnabled(p.isEnabled());
            // the concept: under the title, past the tile when the row has one
            View range = holder.findViewById(R.id.seekbar_row);
            if (range != null) range.setPaddingRelative(tile != null && tile.getVisibility() == View.VISIBLE ? dp(c, 44) : 0,
                    range.getPaddingTop(), range.getPaddingEnd(), range.getPaddingBottom());
            TextView pill = (TextView) holder.findViewById(R.id.seekbar_value);
            if (pill != null) {
                boolean changed = p instanceof UniversalSeekBarPreference ? ((UniversalSeekBarPreference) p).isChanged()
                        : p instanceof TunableSeekBarPreference && ((TunableSeekBarPreference) p).isChanged();
                paintPill(pill, changed, accent);
            }
        }
        ConceptFonts.apply(row);   // P584: the row's texts in the concept's Roboto Flex at their weights, sizes in dp
    }

    /**
     * P592: the row's summary view as its state line - {@link SettingHelp#stateLine}'s text, gone without one (the summary of a
     * row of preferences.xml is its description: never drawn). Returns the view, null when the row's layout has none.
     */
    @androidx.annotation.Nullable
    static TextView stateLine(PreferenceViewHolder holder, Preference p) {
        TextView summary = (TextView) holder.findViewById(android.R.id.summary);
        if (summary == null) return null;
        CharSequence state = SettingHelp.stateLine(p);
        // a recycled row's fitted text and its full one go with the old preference
        summary.setTag(R.id.settings_full_summary, null);
        summary.setText(state);
        summary.setVisibility(TextUtils.isEmpty(state) ? View.GONE : View.VISIBLE);
        return summary;
    }

    /**
     * P584: a number as the concept writes it on a Russian screen - the decimal comma and the true minus («0,02», «−100»); the
     * stored value keeps its dot.
     */
    public static String number(Context c, CharSequence value) {
        if (value == null) return "";
        String s = value.toString();
        if (!Lang.ru(c)) return s;
        return s.replaceAll("(?<=\\d)\\.(?=\\d)", ",").replaceAll("^-(?=\\d)", "\u2212");
    }

    /** A '%s' (or '%1$s') of a description. */
    static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("%(1\\$)?s");

    /**
     * P574: a row's switch - the preference's state at once when the row was bound to another preference (a recycled row: no slide
     * from the old one's state), with the slide when it changed on this row; a tap on the switch itself goes through the preference
     * (its change listener may refuse it: the switch slides back).
     */
    static void bindSwitch(com.scamera.app.ui.controls.IosSwitch sw, TwoStatePreference p) {
        if (sw.getTag(R.id.settings_bound_preference) != p) sw.setCheckedNow(p.isChecked());
        else if (sw.isChecked() != p.isChecked()) sw.setChecked(p.isChecked());
        sw.setTag(R.id.settings_bound_preference, p);
        sw.setEnabled(p.isEnabled());
        // the row is the focus stop and the tap target (as the stock widget: not clickable, not focusable); TalkBack reads the
        // switch's state with the row. P574: a touch on the switch is the row's click (so its knob never stays pressed)
        sw.setFocusable(false);
        sw.setClickable(false);
        sw.setOnTouchListener((v, e) -> {
            View row = v;
            while (row.getParent() instanceof View && !(row.getParent() instanceof androidx.recyclerview.widget.RecyclerView)) row = (View) row.getParent();
            if (row == v) return false;
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP && e.getX() >= 0 && e.getY() >= 0
                    && e.getX() <= v.getWidth() && e.getY() <= v.getHeight()) row.performClick();
            return true;
        });
        sw.setContentDescription(p.getTitle());
        sw.setOnCheckedChangeListener((s, on) -> {
            if (on == p.isChecked()) return;
            if (p.callChangeListener(on)) p.setChecked(on);
            else s.setChecked(p.isChecked());
        });
    }

    /** P574: the fill of a value pill at the default - the accent at 29 % over black (#4A3D00 for the camera's yellow). */
    public static int pillFill(int accent) {
        return 0xFF000000 | Math.round(((accent >> 16) & 255) * .29f) << 16 | Math.round(((accent >> 8) & 255) * .29f) << 8
                | Math.round((accent & 255) * .29f);
    }

    /**
     * P168 (owner, 2026-10-16: «Если параметр отличается от стандартного, то пускай пилюля со значением меняет цвет на
     * противоположный от используемой темы»): the value pill of a setting off its default in the theme's opposite - the
     * theme's light text colour as its fill, its dark background colour as the text; at the default the accent's tint with
     * the accent, as before.
     */
    public static void paintPill(TextView pill, boolean changed, int accent) {
        // P574: at the default the concept's pill (the accent on its dark tone), off it still the opposite (P168)
        pill.setBackgroundTintList(ColorStateList.valueOf(changed ? TEXT : pillFill(accent)));
        pill.setTextColor(changed ? PAGE : accent);
    }

    static CharSequence valueOf(Preference p) {
        if (p instanceof RestorePreference) return null;
        if (p instanceof ModuleRowPreference) return ((ModuleRowPreference) p).value();
        // SIMPLE_UI §4: «Плёночный LUT» shows the chosen look on the right (the concept's .rv)
        try {
            if ("output_settings_screen".equals(p.getKey())) return com.scamera.app.settings.FormatChoice.current().shortLabel();
            if ("photo_size_screen".equals(p.getKey()))
                return com.scamera.app.ui.camera.PhotoSize.label(com.scamera.app.ui.camera.PhotoSize.current());
        } catch (RuntimeException e) {
            return null;   // no camera state yet (tests)
        }
        if ("backup_settings_screen".equals(p.getKey()))
            return com.scamera.app.settings.ConfigState.label(p.getContext());
        if ("shade_transparency_screen".equals(p.getKey()))
            return com.scamera.app.ui.settings.custompreferences.ShadeTransparencyPreference.label(p.getContext(),
                    Math.round((1f - com.scamera.app.ui.camera.views.settingsbar.ShadeStyle.sheetAlpha(p.getContext())) * 100f));
        if ("photo_style_screen".equals(p.getKey()) && p.getSharedPreferences() != null)
            return com.scamera.app.settings.PhotoStyle.name(p.getContext(),
                    com.scamera.app.settings.PhotoStyle.current(p.getSharedPreferences()));
        if ("film_lut_screen".equals(p.getKey()) && p.getSharedPreferences() != null)
            return com.scamera.app.ui.settings.custompreferences.FilmLutPreference.displayName(p.getContext(),
                    p.getSharedPreferences().getString(com.scamera.app.processing.color.FilmLut.KEY, ""));
        if ("lens_filter_screen".equals(p.getKey()) && p.getSharedPreferences() != null)
            return com.scamera.app.processing.color.LensFilters.name(p.getContext(),
                    p.getSharedPreferences().getString(com.scamera.app.processing.color.LensFilters.KEY, ""));
        if (p instanceof ListPreference) return ((ListPreference) p).getEntry();
        if (p instanceof EditTextPreference && !(p instanceof BackupPreferences)) {
            String t = ((EditTextPreference) p).getText();
            return TextUtils.isEmpty(t) ? "—" : t;
        }
        return null;
    }

    // ───── Pickers: bottom sheets ─────

    /** True when the sheet replaced the stock dialog. Backup / Restore / Reset keep their own dialogs. */
    public static boolean showDialog(Context c, Preference p) {
        return showDialog(c, p, null);
    }

    /**
     * As {@link #showDialog(Context, Preference)}; P574 (owner, 2026-11-09: «Все вот такие подменю … сделай ближе по стилю»): with
     * {@code anchor}, the row that was tapped, a list opens its pull-down menu there (IosMenu) instead of the sheet of radio rows.
     */
    public static boolean showDialog(Context c, Preference p, @androidx.annotation.Nullable View anchor) {
        if (p instanceof RestorePreference || p instanceof BackupPreferences || p instanceof ResetPreferences) return false;
        if (p instanceof ListPreference) {
            if (anchor != null && anchor.isAttachedToWindow() && anchor.getWindowToken() != null) listMenu(anchor, (ListPreference) p);
            else listSheet(c, (ListPreference) p);
            return true;
        }
        if (p instanceof EditTextPreference) { editSheet(c, (EditTextPreference) p); return true; }
        return false;
    }

    /**
     * P574: a list's pull-down menu at its row - the entries, the chosen one ticked in the accent (items tagged "option_" + the
     * value); a setting the camera's shade can hold ends in «Добавить в шторку» / «Убрать из шторки» (P137: the list sheet's pin).
     */
    public static android.widget.PopupWindow listMenu(View anchor, ListPreference p) {
        final Context c = anchor.getContext();
        CharSequence[] entries = p.getEntries(), values = p.getEntryValues();
        if (entries == null || values == null) entries = values = new CharSequence[0];
        final int n = Math.min(entries.length, values.length);
        final String key = p.getKey();
        final boolean pinnable = pinnable(c, key);
        final android.content.SharedPreferences prefs = pinnable
                ? com.scamera.app.app.ScameraApp.getSettingsManagerStatic().getDefaultPreferences() : null;
        final CharSequence[] labels = new CharSequence[n + (pinnable ? 1 : 0)], tags = new CharSequence[labels.length];
        System.arraycopy(entries, 0, labels, 0, n);
        System.arraycopy(values, 0, tags, 0, n);
        if (pinnable) {
            boolean on = com.scamera.app.settings.ShadeTiles.isPinned(prefs, key);
            labels[n] = on ? Lang.t(c, "Убрать из шторки", "Remove from the shade") : Lang.t(c, "Добавить в шторку", "Add to the shade");
            tags[n] = "pin";
        }
        final CharSequence[] vals = values;
        return DialogStyle.track(com.scamera.app.ui.controls.IosMenu.show(anchor, p.getDialogTitle() != null ? p.getDialogTitle() : p.getTitle(),
                labels, tags, p.findIndexOfValue(p.getValue()), i -> {
                    if (i >= n) {
                        if (prefs != null) com.scamera.app.settings.ShadeTiles.toggle(prefs, key);
                        return;
                    }
                    String v = vals[i].toString();
                    if (!v.equals(p.getValue()) && p.callChangeListener(v)) p.setValue(v);
                }));
    }

    /** The row of {@code p} on screen in {@code list} (the view SettingsStyle.bind tagged), null when it is not shown. */
    @androidx.annotation.Nullable
    public static View rowOf(@androidx.annotation.Nullable ViewGroup list, Preference p) {
        if (list == null) return null;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child.getTag(R.id.settings_bound_preference) == p && child.getAlpha() > 0f) return child;
        }
        return null;
    }

    private static LinearLayout sheetBody(Context c, CharSequence title) {
        return sheetBody(c, title, null);
    }

    /**
     * A picker's sheet: the grab handle and the title; with {@code pinKey} a setting the camera's shade can hold, a pin capsule
     * under the title (P137: from any value editor of the settings straight into the shade). P574 (owner, 2026-11-09: the
     * settings' sheets in the look of the camera's own): #1C1C1E, 30dp top corners, a light grabber, the title 17sp semibold
     * white, centred.
     */
    public static LinearLayout sheetBody(Context c, CharSequence title, String pinKey) {
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PAGE);
        float r = dp(c, 30);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        body.setBackground(bg);
        body.setPadding(dp(c, 16), dp(c, 6), dp(c, 16), dp(c, 24));
        View grab = new View(c);
        grab.setBackground(shape(c, GRABBER, 0, 3));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(c, 36), dp(c, 5));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.bottomMargin = dp(c, 12);
        body.addView(grab, gp);
        TextView t = text(c, title, 17, ROW_TITLE);
        t.setTypeface(com.scamera.app.ui.style.Fonts.of(t.getContext(), 600));
        t.setGravity(Gravity.CENTER);
        t.setTag("sheet_title");
        t.setPadding(dp(c, 8), dp(c, 2), dp(c, 8), dp(c, 14));
        if (android.os.Build.VERSION.SDK_INT >= 28) t.setAccessibilityHeading(true);
        body.addView(t, new LinearLayout.LayoutParams(-1, -2));
        TextView pin = pinChip(c, pinKey);
        if (pin != null) {
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
            pp.gravity = Gravity.CENTER_HORIZONTAL;
            pp.bottomMargin = dp(c, 14);
            body.addView(pin, pp);
        }
        return body;
    }

    /** P574: the sheets' grabber, white at 22 %. */
    public static final int GRABBER = 0x38FFFFFF;

    /**
     * «В шторку» / «В шторке»: pins the setting in the camera's quick-settings shade or takes it out (ShadeTiles; the shade
     * reloads its tiles when the camera comes back). Null for keys the shade cannot show.
     */
    public static boolean pinnable(Context c, String key) {
        if (key == null) return false;
        try {
            return com.scamera.app.settings.ShadeCatalog.get(c).isKnown(key);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static TextView pinChip(Context c, String key) {
        if (!pinnable(c, key)) return null;
        final android.content.SharedPreferences prefs = com.scamera.app.app.ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
        int accent = accent(c);
        // P574: a #2C2C2E capsule, white text, the pin in the accent (was an outlined accent chip)
        TextView chip = text(c, "", 15, ROW_TITLE);
        chip.setTag("sheet_pin");
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setMinHeight(dp(c, 40));
        chip.setPadding(dp(c, 12), 0, dp(c, 16), 0);
        chip.setCompoundDrawablePadding(dp(c, 8));
        chip.setCompoundDrawableTintList(ColorStateList.valueOf(accent));
        chip.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), shape(c, ROW, 0, 20), null));
        chip.setFocusable(true);
        Runnable paint = () -> {
            boolean on = com.scamera.app.settings.ShadeTiles.isPinned(prefs, key);
            chip.setText(on ? Lang.t(c, "В шторке", "In the shade") : Lang.t(c, "В шторку", "To the shade"));
            chip.setContentDescription(on ? Lang.t(c, "Убрать из шторки", "Remove from the shade") : Lang.t(c, "Добавить в шторку", "Add to the shade"));
            chip.setCompoundDrawablesRelativeWithIntrinsicBounds(on ? R.drawable.ic_sheet_pin_on : R.drawable.ic_sheet_pin, 0, 0, 0);
            chip.setSelected(on);
        };
        chip.setOnClickListener(v -> {
            com.scamera.app.settings.ShadeTiles.toggle(prefs, key);
            paint.run();
        });
        paint.run();
        return chip;
    }

    /**
     * A bottom sheet over the dark surface, opened full height (the pickers and the slider's exact-value editor). The body
     * sits in a nested-scrolling view: a sheet taller than the window (landscape) scrolls instead of being cut at the edge.
     */
    public static BottomSheetDialog sheet(Context c, View body) {
        return sheet(c, body, true);
    }

    /**
     * {@code wrap} false (P412, the list pickers): the body itself is the sheet's content, its own list the one nested scroller
     * the sheet sees. Wrapped in a second scroller the sheet watched that one (it never scrolls: the list is capped) and took
     * every downward drag of the list - the list scrolled only down, a drag up closed the sheet (owner, «Режим сенсора» video).
     */
    static BottomSheetDialog sheet(Context c, View body, boolean wrap) {
        BottomSheetDialog d = new BottomSheetDialog(c);
        if (wrap) {
            NestedScrollView scroll = new NestedScrollView(c);
            scroll.setTag("sheet_scroll");
            scroll.addView(body, new ViewGroup.LayoutParams(-1, -2));
            d.setContentView(scroll);
        } else {
            d.setContentView(body, new ViewGroup.LayoutParams(-1, -2));
        }
        d.setOnShowListener(x -> {
            View host = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (host != null) host.setBackgroundColor(0);
            if (d.getWindow() != null) ConceptFonts.follow(d.getWindow().getDecorView());   // P584
            d.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
            d.getBehavior().setSkipCollapsed(true);
        });
        return d;
    }

    /**
     * The mark of a chosen option. P574 (the concept's menus and lists): an accent tick when chosen, nothing otherwise (was a
     * radio ring); it keeps its room either way.
     */
    public static View radio(Context c, boolean on, int accent) {
        ImageView tick = new ImageView(c);
        tick.setImageResource(R.drawable.ios_menu_check);
        tick.setColorFilter(accent);
        tick.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        tick.setPadding(dp(c, 3), dp(c, 3), dp(c, 3), dp(c, 3));
        tick.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        tick.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return tick;
    }

    static BottomSheetDialog listSheet(Context c, ListPreference p) {
        CharSequence[] entries = p.getEntries(), values = p.getEntryValues();
        if (entries == null || values == null) entries = values = new CharSequence[0];
        final CharSequence[] tags = values;
        return optionSheet(c, p.getDialogTitle() != null ? p.getDialogTitle() : p.getTitle(), entries, tags,
                p.findIndexOfValue(p.getValue()), accent(c), null, i -> {
                    String v = tags[i].toString();
                    if (!v.equals(p.getValue()) && p.callChangeListener(v)) p.setValue(v);
                }, p.getKey());
    }

    /**
     * The list picker: a bottom sheet with a row per option (tag "option_" + the option's tag) in one #2C2C2E card, the selected
     * one ticked in the accent at its end (P574). A tap reports the option's index and closes the sheet. Also the list sheet of the camera's quick-settings
     * shade (P25), there with the camera accent.
     */
    public static BottomSheetDialog optionSheet(Context c, CharSequence title, CharSequence[] labels, CharSequence[] tags,
                                                int selected, int accent, java.util.function.IntConsumer onPick) {
        return optionSheet(c, title, labels, tags, selected, accent, null, onPick);
    }

    /**
     * The list picker with an accent icon per option (between the radio and the name), e.g. the format choice of the top
     * bar and the shade. {@code icons} null or shorter than the list: no icon for the rest.
     */
    public static BottomSheetDialog optionSheet(Context c, CharSequence title, CharSequence[] labels, CharSequence[] tags,
                                                int selected, int accent, int[] icons, java.util.function.IntConsumer onPick) {
        return optionSheet(c, title, labels, tags, selected, accent, icons, onPick, null);
    }

    /** The list picker of a setting: {@code pinKey} adds «В шторку» to its title line (P137). */
    static BottomSheetDialog optionSheet(Context c, CharSequence title, CharSequence[] labels, CharSequence[] tags,
                                         int selected, int accent, int[] icons, java.util.function.IntConsumer onPick, String pinKey) {
        LinearLayout body = sheetBody(c, title, pinKey);
        LinearLayout options = card(c, icons != null ? 52 : 16);
        final int max = (int) (c.getResources().getDisplayMetrics().heightPixels * .6f);
        // nested scrolling: a drag scrolls the list (a plain ScrollView let the sheet take the drag and slide away)
        NestedScrollView scroll = new NestedScrollView(c) {
            @Override protected void onMeasure(int w, int h) {
                // P495 (owner, 2026-11-03: lists ending above the screen's bottom): the list capped while it is measured, so
                // the sheet opens at its final height; capped after the show, the expanded sheet kept its old top and the
                // screen showed under it
                View root = getRootView();
                int window = root != null && root.getHeight() > 0 ? root.getHeight() : getResources().getDisplayMetrics().heightPixels;
                int rest = body.getPaddingTop() + body.getPaddingBottom();
                for (int i = 0; i < body.getChildCount(); i++) if (body.getChildAt(i) != this) rest += body.getChildAt(i).getMeasuredHeight();
                int cap = listCap(window, rest, max, getResources().getDisplayMetrics().density);
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
            }
        };
        scroll.setTag("option_list");
        scroll.addView(options);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        BottomSheetDialog d = sheet(c, body, false);   // P412: the list is the sheet's scroller (a drag down at its top closes it)
        for (int i = 0; labels != null && i < labels.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(c, 16), dp(c, 10), dp(c, 14), dp(c, 10));
            row.setMinimumHeight(dp(c, 52));
            row.setBackground(pressable(c));
            if (icons != null && i < icons.length && icons[i] != 0) {
                ImageView icon = new ImageView(c);
                com.scamera.app.ui.Icons.set(icon, icons[i], accent); // P570: a colour icon keeps its colours
                icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24));
                ip.setMarginEnd(dp(c, 12));
                row.addView(icon, ip);
            }
            TextView label = text(c, labels[i], 16, ROW_TITLE);
            label.setLineSpacing(dp(c, 2), 1f);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24));
            tp.setMarginStart(dp(c, 10));
            row.addView(radio(c, i == selected, accent), tp);
            row.setSelected(i == selected);
            row.setContentDescription(labels[i]);
            row.setTag("option_" + (tags != null && i < tags.length ? tags[i] : String.valueOf(i)));
            row.setOnClickListener(x -> {
                onPick.accept(index);
                d.dismiss();
            });
            options.addView(row);
        }
        // P412: the chosen option in view (a third down the list), not the list's top
        if (selected > 0 && selected < options.getChildCount()) scroll.post(() -> {
            View row = options.getChildAt(selected);
            scroll.scrollTo(0, Math.max(0, row.getTop() - scroll.getHeight() / 3));
        });
        d.show();
        return d;
    }

    /**
     * The option list's height limit: 60 % of the screen ({@code max}), and never more than the window leaves under the
     * sheet's title (landscape: 5 options did not fit and the last one was under the screen edge), at least two rows.
     */
    public static int listCap(int windowHeight, int rest, int max, float density) {
        if (windowHeight <= 0) return max;
        // the sheet opens below the status bar: on the X200 Ultra in landscape it starts 62 dp down
        int room = windowHeight - rest - Math.round(72 * density);
        return Math.max(Math.round(104 * density), Math.min(max, room));
    }

    static BottomSheetDialog editSheet(Context c, EditTextPreference p) {
        int accent = accent(c);
        LinearLayout body = sheetBody(c, p.getDialogTitle() != null ? p.getDialogTitle() : p.getTitle(), p.getKey());
        if (!TextUtils.isEmpty(p.getDialogMessage())) {
            TextView m = text(c, p.getDialogMessage(), 14, SUMMARY);
            m.setLineSpacing(dp(c, 2), 1f);
            m.setPadding(dp(c, 4), 0, dp(c, 4), dp(c, 12));
            body.addView(m);
        }
        // P574 (the concept's inline edit): the field on the rows' colour, 12dp corners; «Готово» an accent capsule
        EditText field = field(new EditText(c));
        field.setText(p.getText());
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setTag("sheet_field");
        EditTextPreference.OnBindEditTextListener setup = EditTextPreferenceAccess.listener(p);
        if (setup != null) setup.onBindEditText(field); // the same input type / filters as the stock dialog
        field.setSelection(field.getText().length());
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.bottomMargin = dp(c, 14);
        body.addView(field, fp);
        TextView save = capsule(c, Lang.t(c, "Готово", "Done"), accent);
        save.setTag("sheet_save");
        body.addView(save, new LinearLayout.LayoutParams(-1, -2));
        BottomSheetDialog d = sheet(c, body);
        Runnable commit = () -> {
            String v = field.getText().toString();
            if (p.callChangeListener(v)) { p.setText(v); d.dismiss(); }
        };
        save.setOnClickListener(x -> commit.run());
        field.setOnEditorActionListener((v, action, e) -> { if (action == EditorInfo.IME_ACTION_DONE) { commit.run(); return true; } return false; });
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.show();
        field.requestFocus();
        return d;
    }

    // ───── P574: the sheets' parts (the concept's language, shared by every sheet and page of the settings) ─────

    /** A text field of a sheet: the rows' colour #2C2C2E, 12dp corners, no outline, white text, the hint at 55 %, accent cursor. */
    public static <T extends EditText> T field(T field) {
        Context c = field.getContext();
        field.setSingleLine(true);
        field.setTextSize(17);
        field.setTextColor(ROW_TITLE);
        field.setHintTextColor(SECTION);
        field.setBackground(shape(c, ROW, 0, 12));
        field.setMinHeight(dp(c, 48));
        field.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        int accent = accent(c);
        field.setHighlightColor(tint(accent, .35f));
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            GradientDrawable cursor = new GradientDrawable();
            cursor.setColor(accent);
            cursor.setSize(dp(c, 2), 1);
            field.setTextCursorDrawable(cursor);
        }
        return field;
    }

    /** A sheet's main action: an accent capsule with dark ink (17sp semibold, 50dp), as «Готово» in the concept. */
    public static TextView capsule(Context c, CharSequence label, int accent) {
        TextView b = text(c, label, 17, INK);
        b.setTypeface(com.scamera.app.ui.style.Fonts.of(b.getContext(), 600));
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 50));
        b.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        b.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33000000), shape(c, accent, 0, 25), null));
        b.setFocusable(true);
        return b;
    }

    /** A secondary action: a #2C2C2E capsule, the label in {@code colour} (white; RED for a destructive one). */
    public static TextView quietCapsule(Context c, CharSequence label, int colour) {
        TextView b = text(c, label, 17, colour);
        b.setTypeface(com.scamera.app.ui.style.Fonts.of(b.getContext(), 600));
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 50));
        b.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        b.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), shape(c, ROW, 0, 25), null));
        b.setFocusable(true);
        return b;
    }

    /**
     * A group card: #2C2C2E, 24dp corners (its rows clipped to them), a 0.5dp hairline between rows from {@code inset} dp past
     * its start.
     */
    public static LinearLayout card(Context c, float inset) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(shape(c, ROW, 0, 24));
        card.setClipToOutline(true);
        GradientDrawable hair = new GradientDrawable();
        hair.setColor(HAIRLINE);
        hair.setSize(1, Math.max(1, Math.round(.5f * c.getResources().getDisplayMetrics().density)));
        card.setDividerDrawable(new android.graphics.drawable.InsetDrawable(hair, dp(c, inset), 0, 0, 0));
        card.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        return card;
    }

    /** The press of a row inside a card: a light ripple, no fill of its own. */
    public static android.graphics.drawable.Drawable pressable(Context c) {
        return new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF), null,
                new android.graphics.drawable.ColorDrawable(0xFFFFFFFF));
    }

    /** Whether the page holds settings stored per module (then the page shows the module chip). */
    static boolean hasModuleSettings(PreferenceGroup group) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference p = group.getPreference(i);
            if (p instanceof PreferenceGroup) { if (hasModuleSettings((PreferenceGroup) p)) return true; }
            else if (p.getKey() != null && com.scamera.app.settings.ModuleProfiles.isLocal(p.getKey())) return true;
        }
        return false;
    }
}
