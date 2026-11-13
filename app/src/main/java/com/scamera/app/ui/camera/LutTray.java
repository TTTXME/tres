package com.scamera.app.ui.camera;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.scamera.app.R;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.circularbarlib.ui.UiScale;
import com.scamera.app.processing.color.FilmLooks;
import com.scamera.app.processing.color.FilmLut;
import com.scamera.app.processing.color.FilmSim;
import com.scamera.app.processing.color.LensFilters;
import com.scamera.app.settings.ParamHelp;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.SettingsManager;
import com.scamera.app.ui.camera.views.settingsbar.ShadeStyle;
import com.scamera.app.ui.controls.IosMotion;
import com.scamera.app.ui.controls.IosSegmented;
import com.scamera.app.ui.controls.IosSlider;
import com.scamera.app.ui.settings.PageReset;
import com.scamera.app.ui.settings.custompreferences.FilmSimPreference;
import com.scamera.app.ui.settings.custompreferences.LensFilterPreference;
import com.scamera.app.util.Lang;
import com.scamera.app.util.Log;
import com.scamera.app.util.ScameraFolder;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SIMPLE_UI §1, the owner's requirement «менять LUT без меню и настроек»: the LUT pill in the viewfinder's top-left corner
 * (28dp, translucent dark, the film icon and the LUT's name - «LUT» with none; the name in the accent while a LUT is on,
 * the pill in the accent while the tray is open) and the LUT tray in the Pro panel's place above row 1 (Pro and the tray
 * close each other): a snap row of every LUT («Нет», the five film looks, every .cube of Download/SCAMERA/LUT) as cards
 * with their names (owner, 2026-10-19: no small live previews in the viewfinder); the strength slider; «Все LUT ›» to the settings' LUT row. The choice is the same state as the settings'
 * (pref_film_lut / pref_film_lut_strength): the live viewfinder (LiveRawRenderer) and the photo (WhyCombine) follow it.
 * P228 / P287: under the strength a second row of the lens filters («Нет» and the reference's four diffusion filters) by
 * name; a card is the settings' pref_lens_filter. The pill names the filter after the
 * LUT («Кино 500 · Pro-Mist»).
 * Owner, 2026-10-28: the sections as tabs of one segmented control («LUT · Фильтр · Плёнка · Цвет», the last one kept), the
 * chosen filter's and film's adjustments in their pages (TrayAdjust), a long press on a parameter shows what it does (ParamHelp),
 * «Все ›» opens the shown section's settings page. P381: the film page's «Печать» and «Формат» among the film's adjustments; P382:
 * a double tap on a tab's title puts that tab's settings back to their defaults (a tap on the hint line undoes it).
 */
public final class LutTray {
    /** The LUT button's fill at rest (the tray itself: the shade's glass, ShadeStyle.sheet - P509). */
    static final int PILL_FILL = 0x9E0D0F12;
    /** An unchosen chip: a faint glass fill with a faint edge. */
    static final int CHIP_FILL = 0x17FFFFFF, CHIP_LINE = 0x12FFFFFF;
    /** The warmth / tint tracks: cool -> warm, green -> magenta (as a photo editor shows them). */
    static final int[] WARMTH_TRACK = {0xFF5AA0FF, 0xFFB9C2CC, 0xFFFFB04A}, TINT_TRACK = {0xFF57D07A, 0xFFB9C2CC, 0xFFE46AD6};
    /**
     * Owner, 2026-10-28 («дай возможность настраивать луты, фильтры и пленки прямо в этом подменю. Делай так чтобы оно не
     * получалось слишком массивным»): the tray's four sections as tabs of one segmented control - only the chosen section's page
     * shows, with the chosen filter's / film's adjustments (TrayAdjust: parameter chips and one slider) under its chips.
     */
    static final int TAB_LUT = 0, TAB_FILTER = 1, TAB_FILM = 2, TAB_COLOUR = 3;
    static final String[] TAB_IDS = {"lut", "filter", "film", "colour"};
    /** The tab shown last (kept while the app runs). */
    static int lastTab = TAB_LUT;
    private String[] tabTitles;
    private final TextView pill;
    private final LinearLayout tray;
    private final LinearLayout row;
    private final IosSlider strength;
    private final TextView strengthValue;
    @Nullable private final SurfaceView preview;
    private final Runnable openAll;
    /** Opens a settings page (its key, the row to show) for «Все ›» of the filter and film tabs; null: the LUT page. */
    @Nullable private java.util.function.BiConsumer<String, String> pageOpener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> values = new ArrayList<>();
    private final LinearLayout filterRow;
    /** P281: the film simulation's stocks (FilmSim). */
    private final LinearLayout filmRow;
    private final IosSegmented tabs;
    private final LinearLayout pages;
    private final TextView all, hint;
    private final TrayAdjust filterAdjust, filmAdjust;
    private int tab = -1;
    /** The tray's height animation (a tab, an adjust strip or the description changed what it holds). */
    @Nullable private android.animation.ValueAnimator resizing;
    private final Runnable hideHint = this::hideHint;
    /** P382: what a tap on the hint line does besides closing it (a tab reset's undo); null: it only closes. */
    @Nullable private Runnable hintAction;
    /**
     * P319 (user report 2026-10-22: «Много лишнего, в том числе того, что должно быть только в видоискателе (фильтр на объектив,
     * тепло, и т.п.)»): «Тепло» and «Оттенок» of «Цвет» in the tray too, the same settings (pref_scam_hybrid_colour_warmth /
     * _tint, -100..100); the RAW viewfinder shows them at once, the photo always.
     */
    static final String WARMTH_KEY = ParamHelp.WARMTH_KEY, TINT_KEY = ParamHelp.TINT_KEY;
    private final IosSlider warmth, tint;
    private final TextView warmthValue, tintValue;
    @Nullable private Runnable onOpen;
    private boolean open;
    /** The values the filter and film chips stand for (the chips are updated in place, the row's pill glides). */
    private final List<String> filterValues = new ArrayList<>(), filmValues = new ArrayList<>();
    /** The pill's colours as last drawn (its change animates). */
    @Nullable private GradientDrawable pillBg;
    private int pillFill, pillInk;

    /**
     * P574 (the approved concept: the LUT button in the top row): the button is a round icon (no name on it) - white, the accent
     * while a LUT / filter / film is on, on an accent tint while the tray is open; the tray stays at the viewfinder's bottom.
     */
    private final boolean iconOnly;

    public LutTray(TextView pill, LinearLayout tray, @Nullable SurfaceView preview, Runnable openAll) {
        this(pill, tray, preview, openAll, false);
    }

    /** {@code iconOnly}: the top row's round LUT button (P574) instead of the named pill. */
    public LutTray(TextView pill, LinearLayout tray, @Nullable SurfaceView preview, Runnable openAll, boolean iconOnly) {
        this.iconOnly = iconOnly;
        this.pill = pill;
        this.tray = tray;
        this.preview = preview;
        this.openAll = openAll;
        Context c = tray.getContext();
        int accent = ShadeStyle.accent(c);
        // the pill
        pill.setOnClickListener(v -> setOpen(!open));
        if (!iconOnly) {
            pill.setCompoundDrawablePadding(UiScale.dp(c, 5));
            pill.setPaddingRelative(UiScale.dp(c, 8), 0, UiScale.dp(c, 10), 0);
            ViewGroup.LayoutParams plp = pill.getLayoutParams();
            plp.height = UiScale.dp(c, 28);
            pill.setLayoutParams(plp);
            UiScale.textFixed(pill, 12);
        }
        tray.setOrientation(LinearLayout.VERTICAL);
        tray.setPadding(UiScale.dp(c, 10), UiScale.dp(c, 10), UiScale.dp(c, 10), UiScale.dp(c, 10));
        // P583 (the concept's tray): rgba(20, 20, 22, .92) with 24px corners, 10px inside
        tray.setBackground(com.scamera.app.ui.camera.ConceptStyle.shape(c, 0xEB141416, 24, false));
        tray.setElevation(UiScale.dpF(c, 18));
        tray.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        tray.setClickable(true);
        tray.removeAllViews();
        // the head: the segmented control of the sections and «Все ›» to the chosen section's settings page
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setTag("lut_tray_head");
        // P574 (the approved concept): the shared segmented control - a grey thumb gliding on a spring, the chosen title in the accent
        tabs = new IosSegmented(c);
        tabs.setTag("lut_tray_tabs");
        tabs.setColours(accent, 0x3D767680, 0xFF636366);
        final String[] titles = tabTitles = new String[]{"LUT", Lang.t(c, "Фильтр", "Filter"), Lang.t(c, "Плёнка", "Film"), Lang.t(c, "Цвет", "Colour")};
        tabs.setItems(titles);
        for (int i = 0; i < titles.length; i++) {
            final int t = i;
            TextView chip = (TextView) tabs.getChildAt(i);
            chip.setMaxLines(1);
            chip.setTag("lut_tray_tab_" + TAB_IDS[i]);
            chip.setContentDescription(titles[i]);
            // a tap on the shown tab does nothing (the second tap of a double tap)
            chip.setOnClickListener(v -> {
                if (tab == t) return;
                TrayMotion.tick(v);
                showTab(t, true);
            });
            // P382 (owner, 2026-10-28): a double tap on a tab's title puts the tab's settings back to their defaults; the first tap
            // switches to it as a single tap does
            com.scamera.app.ui.controls.DoubleTapReset.attach(chip, () -> resetTab(t), TrayMotion.press());
        }
        head.addView(tabs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // P583 (the concept): «Все» 14px semibold in the accent with its 14px chevron, a 30px button (8 / 6 px sides)
        all = text(c, Lang.t(c, "Все", "All"), 14, accent);
        all.setTag("lut_tray_all");
        com.scamera.app.ui.style.Fonts.apply(all, com.scamera.app.ui.style.Fonts.SEMIBOLD);
        all.setCompoundDrawablesRelative(null, null, com.scamera.app.ui.camera.ConceptStyle.icon(c, R.drawable.cv_chev_right, accent, UiScale.dp(c, 14)), null);
        all.setCompoundDrawablePadding(UiScale.dp(c, 2));
        all.setGravity(Gravity.CENTER_VERTICAL);
        all.setMinHeight(UiScale.dp(c, 30));
        all.setPaddingRelative(UiScale.dp(c, 8), 0, UiScale.dp(c, 6), 0);
        all.setBackground(ShadeStyle.ripple(c, 10));
        all.setOnClickListener(v -> openPage());
        LinearLayout.LayoutParams allp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        allp.setMarginStart(UiScale.dp(c, 6));
        head.addView(all, allp);
        tray.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // the pages, one shown
        pages = new LinearLayout(c);
        pages.setOrientation(LinearLayout.VERTICAL);
        pages.setTag("lut_tray_pages");
        LinearLayout.LayoutParams pgp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pgp.topMargin = UiScale.dp(c, 10);
        tray.addView(pages, pgp);
        // LUT: the chips and the strength
        LinearLayout lutPage = page(c, TAB_LUT);
        row = chipRow(c, lutPage, "lut_tray_luts", CARD_CORNER_DP);
        LinearLayout lr = new LinearLayout(c);
        lr.setOrientation(LinearLayout.HORIZONTAL);
        lr.setGravity(Gravity.CENTER_VERTICAL);
        lr.setTag("lut_tray_strength");
        TextView label = sliderLabel(c, Lang.t(c, "Сила", "Strength"));
        helpOnHold(label, label.getText().toString(), FilmLut.STRENGTH_KEY);
        lr.addView(label);
        // P574: the shared slider (a double tap: the settings' 100 %)
        strength = slider(c, 0, 100, 1, 100);
        strength.setContentDescription(Lang.t(c, "Сила LUT", "LUT strength"));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lr.addView(strength, slp);
        strengthValue = valueLabel(c);
        lr.addView(strengthValue);
        LinearLayout.LayoutParams lrp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lrp.topMargin = UiScale.dp(c, 4);
        lutPage.addView(lr, lrp);
        // P228: the lens filters, with the chosen filter's strength, own parameter and fine controls
        LinearLayout filterPage = page(c, TAB_FILTER);
        filterRow = chipRow(c, filterPage, "lut_tray_filters", CARD_CORNER_DP);
        filterAdjust = new TrayAdjust(c, "lut_tray_filter_adjust");
        filterPage.addView(filterAdjust.view);
        // P281: the film simulation's stocks, with the chosen film's strength, grain, halation, softness, exposures, contrast
        LinearLayout filmPage = page(c, TAB_FILM);
        filmRow = chipRow(c, filmPage, "lut_tray_films", CARD_CORNER_DP);
        filmAdjust = new TrayAdjust(c, "lut_tray_film_adjust");
        filmPage.addView(filmAdjust.view);
        for (TrayAdjust a : new TrayAdjust[]{filterAdjust, filmAdjust}) {
            a.setOnChange(() -> { bindPill(); bindTabs(); });
            a.setOnHelp(this::showHint);
        }
        // P319: the colour's warmth and tint
        LinearLayout colourPage = page(c, TAB_COLOUR);
        warmthValue = valueLabel(c);
        warmth = colourSlider(c, colourPage, Lang.t(c, "Тепло", "Warmth"), WARMTH_KEY, warmthValue, "lut_tray_warmth", WARMTH_TRACK);
        tintValue = valueLabel(c);
        tint = colourSlider(c, colourPage, Lang.t(c, "Оттенок", "Tint"), TINT_KEY, tintValue, "lut_tray_tint", TINT_TRACK);
        // the description of a parameter held (owner, 2026-10-28: «добавь описание для параметров по длинному зажатию»)
        hint = text(c, "", 12.5f, ShadeStyle.TEXT);
        hint.setTag("lut_tray_hint");
        hint.setMaxLines(6);
        hint.setLineSpacing(UiScale.dp(c, 2), 1f);
        hint.setPadding(UiScale.dp(c, 12), UiScale.dp(c, 9), UiScale.dp(c, 12), UiScale.dp(c, 10));
        GradientDrawable hb = new GradientDrawable();
        hb.setCornerRadius(UiScale.dpF(c, 14));
        hb.setColor(CHIP_FILL);
        hint.setBackground(hb);
        hint.setVisibility(View.GONE);
        hint.setOnClickListener(v -> {
            final Runnable action = hintAction;
            hintAction = null;
            if (action != null) action.run();
            hideHint();
        });
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = UiScale.dp(c, 8);
        tray.addView(hint, hlp);
        // P357: a double tap puts the default back (the settings' 100 %) - the slider's own (P574)
        strength.setListener(new IosSlider.Listener() {
            @Override public void onValue(IosSlider s, float v, boolean user) {
                final int p = Math.round(v);
                strengthValue.setText(p + " %");
                if (!user) return;
                settings().set(SettingsManager.SCOPE_GLOBAL, FilmLut.STRENGTH_KEY, String.valueOf(p));
                if (!s.isTracking()) { bindPill(); bindTabs(); } // a double tap, the keyboard, TalkBack
            }
            @Override public void onTracking(IosSlider s, boolean on) {
                track(strengthValue, on);
                if (!on) { bindPill(); bindTabs(); }
            }
        });
        TrayMotion.setShown(tray, false);
        bindPill();
    }

    /** A section's page in the pages' box (shown by its tab). */
    private LinearLayout page(Context c, int t) {
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setTag("lut_tray_page_" + TAB_IDS[t]);
        p.setVisibility(View.GONE);
        pages.addView(p, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return p;
    }

    /** «Все ›» opens the settings page of the shown section (the LUT's through the camera's own route). */
    public void setPageOpener(@Nullable java.util.function.BiConsumer<String, String> opener) {
        pageOpener = opener;
    }

    private void openPage() {
        setOpen(false);
        if (tab == TAB_FILTER && pageOpener != null) pageOpener.accept("lens_filter_screen", LensFilters.KEY);
        else if (tab == TAB_FILM && pageOpener != null)
            pageOpener.accept("film_sim_screen", com.scamera.app.processing.color.FilmSim.STOCK_KEY);
        else openAll.run();
    }

    /** A long press on {@code v} shows {@code key}'s description (ParamHelp) under the page. */
    private void helpOnHold(View v, String title, String key) {
        v.setOnLongClickListener(x -> {
            String text = ParamHelp.of(x.getContext(), key);
            if (text == null) return false;
            showHint(title, text);
            return true;
        });
    }

    /** The description line: the parameter's name, then what it does; it stays 8 s, a tap on it closes it. */
    void showHint(String title, String text) {
        showHint(title, text, null, 8000);
    }

    /** The hint line with {@code title} and {@code text} for {@code ms}; a tap on it runs {@code action} (if any) and closes it. */
    private void showHint(String title, String text, @Nullable Runnable action, long ms) {
        hintAction = action;
        Context c = tray.getContext();
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(title);
        b.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, title.length(), 0);
        b.append("  ");
        int at = b.length();
        b.append(text);
        b.setSpan(new android.text.style.ForegroundColorSpan(ShadeStyle.MUTED), at, b.length(), 0);
        main.removeCallbacks(hideHint);
        if (hint.getVisibility() == View.VISIBLE) {
            hint.setText(b);
        } else {
            resize(() -> { hint.setText(b); hint.setVisibility(View.VISIBLE); });
            hint.setAlpha(0f);
            hint.animate().alpha(1f).setStartDelay(60).setDuration(200).setInterpolator(TrayMotion.EASE_OUT).start();
        }
        hint.setContentDescription(title + ". " + text);
        main.postDelayed(hideHint, ms);
    }

    private void hideHint() {
        main.removeCallbacks(hideHint);
        hintAction = null;
        if (hint.getVisibility() != View.VISIBLE) return;
        hint.animate().cancel();
        resize(() -> hint.setVisibility(View.GONE));
    }

    /**
     * Runs {@code change} (what the tray holds) and lets the tray's height follow on the glide spring instead of jumping
     * (the tray is anchored at its bottom: it grows and shrinks at its top edge).
     */
    private void resize(Runnable change) {
        final int h0 = tray.getHeight();
        if (resizing != null) resizing.cancel();
        change.run();
        if (!open || h0 == 0 || !tray.isAttachedToWindow() || !android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        tray.measure(View.MeasureSpec.makeMeasureSpec(tray.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int h1 = tray.getMeasuredHeight();
        if (h1 == h0) return;
        final ViewGroup.LayoutParams lp = tray.getLayoutParams();
        lp.height = h0;
        tray.setLayoutParams(lp);
        final android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(TrayMotion.GLIDE.durationMs);
        a.setInterpolator(TrayMotion.GLIDE);
        a.addUpdateListener(v -> {
            lp.height = Math.round(h0 + (h1 - h0) * (float) v.getAnimatedValue());
            tray.setLayoutParams(lp);
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator x) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                tray.setLayoutParams(lp);
                if (resizing == a) resizing = null;
            }
        });
        resizing = a;
        a.start();
    }

    /** Shows tab {@code t}'s page: the pill glides to its tab, the page slides in from the side it lies on, the height follows. */
    private void showTab(int t, boolean animate) {
        final int from = tab;
        tab = t;
        lastTab = t;
        if (from != t) hideHintNow();
        tabs.setSelected(t, animate && from >= 0);
        for (int i = 0; i < tabs.getChildCount(); i++) tabs.getChildAt(i).setActivated(i == t);
        bindTabs();
        all.setVisibility(t == TAB_COLOUR ? View.INVISIBLE : View.VISIBLE);
        final View in = pages.getChildAt(t);
        final Runnable change = () -> { for (int i = 0; i < pages.getChildCount(); i++) pages.getChildAt(i).setVisibility(i == t ? View.VISIBLE : View.GONE); };
        in.animate().cancel();
        if (!animate || from < 0 || from == t) {
            change.run();
            in.setAlpha(1f);
            in.setTranslationX(0f);
            return;
        }
        resize(change);
        in.setAlpha(0f);
        in.setTranslationX(UiScale.dpF(tray.getContext(), 18) * (t > from ? 1 : -1));
        in.animate().alpha(1f).translationX(0f).setStartDelay(30).setDuration(260).setInterpolator(TrayMotion.EASE_OUT).start();
    }

    private void hideHintNow() {
        main.removeCallbacks(hideHint);
        hintAction = null;
        hint.animate().cancel();
        hint.setVisibility(View.GONE);
    }

    /** A tab whose section is on (a LUT, a filter, a film, a colour shift) carries a small dot in the accent. */
    private void bindTabs() {
        Context c = tray.getContext();
        final boolean[] on = {
                !PreferenceKeys.filmLut().isEmpty() && PreferenceKeys.filmLutStrength() > 0f,
                !PreferenceKeys.lensFilter().isEmpty() && PreferenceKeys.lensFilterStrength() > 0f,
                com.scamera.app.processing.color.FilmSim.current() != null,
                colourValue(WARMTH_KEY) != 0 || colourValue(TINT_KEY) != 0};
        for (int i = 0; i < tabs.getChildCount(); i++) {
            TextView chip = (TextView) tabs.getChildAt(i);
            if (!on[i]) {
                chip.setText(tabTitles[i]);
                continue;
            }
            // the dot right after the title (a compound drawable sits at the tab's edge, half off the selected pill)
            android.text.SpannableString t = new android.text.SpannableString(tabTitles[i] + " ");
            t.setSpan(new DotSpan(ShadeStyle.accent(c), UiScale.dpF(c, 2.5f), UiScale.dpF(c, 4)),
                    t.length() - 1, t.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            chip.setText(t);
        }
    }

    /** A tab's «something is on here» dot: a small circle {@code gap} after the title, at the middle of its letters. */
    private static final class DotSpan extends android.text.style.ReplacementSpan {
        private final int color;
        private final float r, gap;

        DotSpan(int color, float r, float gap) {
            this.color = color;
            this.r = r;
            this.gap = gap;
        }

        @Override
        public int getSize(android.graphics.Paint p, CharSequence text, int start, int end, @Nullable android.graphics.Paint.FontMetricsInt fm) {
            return Math.round(gap + 2 * r);
        }

        @Override
        public void draw(android.graphics.Canvas cv, CharSequence text, int start, int end, float x, int top, int y, int bottom, android.graphics.Paint p) {
            final int old = p.getColor();
            p.setColor(color);
            cv.drawCircle(x + gap + r, y + p.ascent() * .36f, r, p);
            p.setColor(old);
        }
    }

    /**
     * P382 (owner, 2026-10-28: a double tap on a tab's title resets every parameter of that tab): tab {@code t}'s settings back to
     * the values the settings pages' reset (P357, PageReset) puts back - the LUT «Нет» at its strength's default, the lens filter's
     * and the film's own keys as their pages reset them (no filter / film; strength, fine controls, print, format, effects), the
     * colour rows' defaults. The page follows in place, the tab's dot goes, and the hint line says it - a tap on it puts the
     * values back as they were.
     */
    void resetTab(int t) {
        final Context c = tray.getContext();
        final SharedPreferences prefs = settings().getDefaultPreferences();
        final Map<String, Object> before = tabValues(prefs, t);
        final boolean changed = resetToDefaults(c, prefs, t);
        Log.d("LutTray", "tab " + TAB_IDS[t] + " reset to defaults" + (changed ? "" : " (it was)"));
        resize(() -> refreshTab(t));
        bindPill();
        bindTabs();
        if (changed) showHint(tabTitles[t], Lang.t(c, "всё по умолчанию · нажмите, чтобы вернуть", "all back to defaults · tap to undo"),
                () -> restore(t, before), 5000);
        else showHint(tabTitles[t], Lang.t(c, "всё уже по умолчанию", "everything is at its default already"), null, 2500);
    }

    /** P382: tab {@code t}'s keys back to their defaults in {@code prefs}; true when anything was not at its default. */
    static boolean resetToDefaults(Context c, SharedPreferences prefs, int t) {
        switch (t) {
            case TAB_LUT: {
                final String def = PageReset.defaultOf(c, FilmLut.STRENGTH_KEY, "100");
                final boolean changed = !PreferenceKeys.filmLut().isEmpty()
                        || Math.abs(PreferenceKeys.filmLutStrength() * 100f - number(def, 100f)) > .5f;
                prefs.edit().putString(FilmLut.KEY, "").putString(FilmLut.STRENGTH_KEY, def).apply();
                return changed;
            }
            case TAB_FILTER:
                return LensFilterPreference.resetKeys(prefs);
            case TAB_FILM:
                return FilmSimPreference.resetKeys(prefs);
            default: {
                final String w = PageReset.defaultOf(c, WARMTH_KEY, "0"), n = PageReset.defaultOf(c, TINT_KEY, "0");
                final boolean changed = colourValue(WARMTH_KEY) != Math.round(number(w, 0f))
                        || colourValue(TINT_KEY) != Math.round(number(n, 0f));
                prefs.edit().putString(WARMTH_KEY, w).putString(TINT_KEY, n).apply();
                return changed;
            }
        }
    }

    /** P382: whether setting {@code key} belongs to tab {@code t} (what its reset puts back). */
    static boolean ownedBy(int t, String key) {
        switch (t) {
            case TAB_LUT: return FilmLut.KEY.equals(key) || FilmLut.STRENGTH_KEY.equals(key);
            case TAB_FILTER: return LensFilters.KEY.equals(key) || LensFilters.STRENGTH_KEY.equals(key) || key.startsWith(LensFilters.TUNE_PREFIX);
            case TAB_FILM: return java.util.Arrays.asList(FilmSim.KEYS).contains(key);
            default: return WARMTH_KEY.equals(key) || TINT_KEY.equals(key);
        }
    }

    /** P382: tab {@code t}'s stored settings (key -> value as stored). */
    static Map<String, Object> tabValues(SharedPreferences prefs, int t) {
        final Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) if (ownedBy(t, e.getKey())) out.put(e.getKey(), e.getValue());
        return out;
    }

    /** P382's undo: tab {@code t}'s settings as they were stored before its reset ({@code before}). */
    private void restore(int t, Map<String, Object> before) {
        final SharedPreferences prefs = settings().getDefaultPreferences();
        final SharedPreferences.Editor e = prefs.edit();
        for (String k : tabValues(prefs, t).keySet()) e.remove(k);
        for (Map.Entry<String, Object> v : before.entrySet()) {
            final Object x = v.getValue();
            if (x instanceof Boolean) e.putBoolean(v.getKey(), (Boolean) x);
            else if (x instanceof Integer) e.putInt(v.getKey(), (Integer) x);
            else if (x instanceof Long) e.putLong(v.getKey(), (Long) x);
            else if (x instanceof Float) e.putFloat(v.getKey(), (Float) x);
            else if (x instanceof java.util.Set) {
                final java.util.Set<String> set = new java.util.HashSet<>();
                for (Object o : (java.util.Set<?>) x) set.add(String.valueOf(o));
                e.putStringSet(v.getKey(), set);
            } else if (x != null) e.putString(v.getKey(), x.toString());
        }
        e.apply();
        Log.d("LutTray", "tab " + TAB_IDS[t] + " reset undone");
        resize(() -> refreshTab(t));
        bindPill();
        bindTabs();
    }

    /** P382: tab {@code t}'s page from the settings again, in place (the chips' pill glides, the adjustments follow). */
    private void refreshTab(int t) {
        switch (t) {
            case TAB_LUT: {
                final String chosen = PreferenceKeys.filmLut();
                final int i = values.indexOf(chosen);
                select(row, i, CARD_CORNER_DP, open);
                bindLutState(chosen, open);
                if (i >= 0) centre(row, row.getChildAt(i));
                break;
            }
            case TAB_FILTER: {
                final String chosen = PreferenceKeys.lensFilter();
                final int i = filterValues.indexOf(chosen);
                select(filterRow, i, CARD_CORNER_DP, open);
                bindFilterAdjust(chosen);
                if (i >= 0) centre(filterRow, filterRow.getChildAt(i));
                break;
            }
            case TAB_FILM: {
                final String chosen = settings().getString(SettingsManager.SCOPE_GLOBAL, FilmSim.STOCK_KEY, "");
                final int i = filmValues.indexOf(FilmSim.stock(chosen) == null ? "" : FilmSim.stock(chosen).id);
                select(filmRow, i, CARD_CORNER_DP, open);
                bindFilmAdjust(chosen);
                if (i >= 0) centre(filmRow, filmRow.getChildAt(i));
                break;
            }
            default:
                setColour(warmth, warmthValue, colourValue(WARMTH_KEY));
                setColour(tint, tintValue, colourValue(TINT_KEY));
        }
    }

    /** P319: a «label · slider · value» row of a -100..100 colour setting, written as the settings page writes it. */
    private IosSlider colourSlider(Context c, LinearLayout parent, String label, String key, TextView value, String tag, int[] track) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setTag(tag);
        TextView l = sliderLabel(c, label);
        helpOnHold(l, label, key);
        r.addView(l);
        // P574 (the approved concept): the shared slider, the accent from the left as every slider of the camera screen (the old
        // cool -> warm track is gone); the neutral middle is its default (a double tap) and is felt (the slider's tick)
        IosSlider bar = slider(c, -100, 100, 1, 0);
        bar.setContentDescription(label);
        r.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(value);
        bar.setListener(new IosSlider.Listener() {
            @Override public void onValue(IosSlider s, float v, boolean user) {
                final int p = Math.round(v);
                value.setText(signed(p));
                if (!user) return;
                settings().set(SettingsManager.SCOPE_GLOBAL, key, String.valueOf(p));
                if (!s.isTracking()) bindTabs();
            }
            @Override public void onTracking(IosSlider s, boolean on) {
                LutTray.track(value, on);
                if (!on) bindTabs();
            }
        });
        // a long press on the slider: what it does
        bar.setOnLongClickListener(x -> {
            String text = ParamHelp.of(x.getContext(), key);
            if (text == null) return false;
            showHint(label, text);
            return true;
        });
        parent.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    /** A colour slider and its value at {@code v} (the settings' state, not a user's move). */
    private static void setColour(IosSlider bar, TextView value, int v) {
        bar.setValue(v);
        value.setText(signed(v));
    }

    /**
     * P574: the camera screen's slider (the shared IosSlider: accent fill, glass knob while held, rubber band, double tap =
     * {@code def}) on {@code min..max} in {@code step}.
     */
    static IosSlider slider(Context c, float min, float max, float step, float def) {
        IosSlider s = new IosSlider(c);
        s.setRange(min, max, step);
        s.setDefaultValue(def);
        s.setAccent(ShadeStyle.accent(c));
        return s;
    }

    /** P574: a slider row's name (the concept: 13sp, muted, a fixed column so the sliders line up). */
    static TextView sliderLabel(Context c, CharSequence label) {
        // P583 (the concept): 13px at 75 %, a 92px column (4px in), 10px before the slider
        TextView l = text(c, label, 13, 0xBFFFFFFF);
        l.setMinWidth(UiScale.dp(c, 96));
        l.setMaxWidth(UiScale.dp(c, 110));
        l.setPaddingRelative(UiScale.dp(c, 4), 0, UiScale.dp(c, 10), 0);
        return l;
    }

    /** P574: a slider row's value (the concept: 13sp semibold, tabular figures, in the accent, at the row's end). */
    static TextView valueLabel(Context c) {
        TextView v = text(c, "", 13, ShadeStyle.accent(c));
        com.scamera.app.ui.style.Fonts.apply(v, com.scamera.app.ui.style.Fonts.SEMIBOLD);
        v.setMinWidth(UiScale.dp(c, 58));
        v.setGravity(Gravity.END);
        v.setFontFeatureSettings("tnum");
        return v;
    }

    /** P574: the value lifts (bigger) while the knob is held and pops once when the finger leaves (IosMotion). */
    static void track(TextView value, boolean held) {
        final int accent = ShadeStyle.accent(value.getContext());
        if (held) IosMotion.lift(value, true, accent, accent);
        else IosMotion.pop(value);
    }

    static String signed(int v) {
        return v > 0 ? "+" + v : String.valueOf(v);
    }

    /** P319: a -100..100 colour setting's value now (a stored text, as UniversalSeekBarPreference keeps it). */
    static int colourValue(String key) {
        try {
            return Math.max(-100, Math.min(100, Math.round(PreferenceKeys.hybridValue(key.substring("pref_scam_hybrid_".length()), 0f))));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** A stored number's text ({@code fallback} when it is none). */
    private static float number(@Nullable String v, float fallback) {
        try {
            return v == null ? fallback : Float.parseFloat(v.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static SettingsManager settings() {
        return ScameraApp.getSettingsManagerStatic();
    }

    private static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setMaxLines(1);
        // P583: the concept's px as dp (times the chrome's scale), Roboto Flex
        com.scamera.app.ui.camera.ConceptStyle.text(t, sp, com.scamera.app.ui.style.Fonts.REGULAR);
        return t;
    }

    /**
     * A section's horizontal row of chips in its scroll, added to the tray: the row draws its selection pill under the chips
     * (TrayMotion.ChipRow), which glides to the chosen chip; the row's ends fade into the tray.
     */
    private LinearLayout chipRow(Context c, LinearLayout parent, String tag, float radiusDp) {
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setHorizontalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(UiScale.dp(c, 18));
        TrayMotion.ChipRow r = new TrayMotion.ChipRow(c, ShadeStyle.accent(c), UiScale.dpF(c, radiusDp));
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setTag(tag);
        scroll.addView(r, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiScale.dp(c, 4);
        parent.addView(scroll, lp);
        return r;
    }

    /**
     * A chip's look: clear when chosen (the row's selection pill in the accent lies under it), the glass fill and edge otherwise;
     * a ripple bounded by its shape.
     */
    static android.graphics.drawable.Drawable chipBackground(Context c, boolean on, int accent, float radiusDp) {
        // P583 (the concept): an unchosen chip is clear (the chosen one lies on the row's #3A3A3C plate)
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(UiScale.dpF(c, radiusDp));
        fill.setColor(0x00000000);
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(UiScale.dpF(c, radiusDp));
        mask.setColor(0xFFFFFFFF);
        return new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(on ? 0x33000000 : 0x29FFFFFF), fill, mask);
    }

    /** P509: a card's thumbnail side (dp) and corner (dp); the card's own corner. */
    // P583 (the concept's five thumbnails across the tray): 53px pictures with 14px corners on 18px cards
    static final int THUMB_DP = 53, THUMB_CORNER_DP = 14, CARD_CORNER_DP = 18;

    /**
     * P509 (owner, 2026-11-05: small pictures that say what a LUT, a film or a filter is - not live previews): a card with a still
     * thumbnail ({@link LookThumbs}) and the name under it; chosen, it lies on the row's accent plate (the gliding selection) with
     * its name in INK.
     */
    static LinearLayout thumbCard(Context c, String label, boolean on, int accent) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        final int pad = UiScale.dp(c, 4), side = UiScale.dp(c, THUMB_DP);
        card.setPadding(pad, pad, pad, UiScale.dp(c, 6));
        card.setBackground(chipBackground(c, on, accent, CARD_CORNER_DP));
        card.setActivated(on);
        android.widget.ImageView img = new android.widget.ImageView(c);
        img.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        GradientDrawable ph = new GradientDrawable();
        ph.setCornerRadius(UiScale.dpF(c, THUMB_CORNER_DP));
        ph.setColor(CHIP_FILL);
        img.setBackground(ph);
        img.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        img.setClipToOutline(true);
        img.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(img, new LinearLayout.LayoutParams(side, side));
        TextView t = text(c, label, 11, on ? accent : ShadeStyle.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        com.scamera.app.ui.style.Fonts.apply(t, com.scamera.app.ui.style.Fonts.SEMIBOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(side, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = UiScale.dp(c, 5);
        card.addView(t, tp);
        TrayMotion.pressable(card);
        return card;
    }

    /** P509: the thumbnail of a {@link #thumbCard}. */
    static android.widget.ImageView thumb(View card) {
        return (android.widget.ImageView) ((ViewGroup) card).getChildAt(0);
    }

    /**
     * A row's chips show {@code chosen} (an index of the row, -1 none): the chosen chip clear over the selection pill, its texts in
     * INK, the others on glass - the colours cross-fade and the pill glides when {@code animate}, else they are set at once.
     */
    private void select(LinearLayout row, int chosen, float radiusDp, boolean animate) {
        Context c = row.getContext();
        int accent = ShadeStyle.accent(c);
        View target = null;
        for (int i = 0; i < row.getChildCount(); i++) {
            View chip = row.getChildAt(i);
            boolean on = i == chosen;
            if (on) target = chip;
            if (chip.isActivated() == on && chip.getBackground() != null && animate) continue;
            chip.setActivated(on);
            chip.setBackground(chipBackground(c, on, accent, radiusDp));
            recolour(chip, on, animate);
        }
        if (!(row instanceof TrayMotion.ChipRow)) return;
        TrayMotion.ChipRow chips = (TrayMotion.ChipRow) row;
        if (target == null) chips.hide();
        else chips.moveTo(target, animate);
    }

    /** A chip's texts: INK on the pill, TEXT (and the brand line MUTED) on glass; cross-faded over 180 ms when {@code animate}. */
    private static void recolour(View chip, boolean on, boolean animate) {
        // P583 (the concept): the chosen chip's text in the accent on its #3A3A3C plate
        final int accent = ShadeStyle.accent(chip.getContext());
        if (chip instanceof TextView) {
            fade((TextView) chip, on ? accent : ShadeStyle.TEXT, animate);
        } else if (chip instanceof ViewGroup && ((ViewGroup) chip).getChildCount() == 2 && ((ViewGroup) chip).getChildAt(0) instanceof android.widget.ImageView) {
            fade((TextView) ((ViewGroup) chip).getChildAt(1), on ? accent : ShadeStyle.TEXT, animate); // P509: a thumbnail card
        } else if (chip instanceof ViewGroup && ((ViewGroup) chip).getChildCount() == 2) {
            ViewGroup g = (ViewGroup) chip;
            fade((TextView) g.getChildAt(0), on ? (accent & 0x00FFFFFF) | 0xB3000000 : ShadeStyle.MUTED, animate);
            fade((TextView) g.getChildAt(1), on ? accent : ShadeStyle.TEXT, animate);
        }
    }

    private static void fade(TextView t, int to, boolean animate) {
        final int from = t.getCurrentTextColor();
        t.setTextColor(to); // the state at once; the animator's first frame (before the next draw) starts from the old colour
        if (!animate || from == to) return;
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(180);
        a.setInterpolator(TrayMotion.EASE_OUT);
        a.addUpdateListener(v -> t.setTextColor(TrayMotion.mix(from, to, (float) v.getAnimatedValue())));
        a.start();
    }

    /** P509: a thumbnail card's place in its row: its own size, 6dp after the previous. */
    private static LinearLayout.LayoutParams thumbParams(Context c, ViewGroup row) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (row.getChildCount() > 0) lp.setMarginStart(UiScale.dp(c, 6));
        return lp;
    }

    /** Scrolls a row so the chosen chip sits in its middle. */
    static void centre(LinearLayout row, @Nullable View target) {
        if (target != null) row.post(() -> {
            View scroll = (View) row.getParent();
            if (scroll instanceof HorizontalScrollView)
                ((HorizontalScrollView) scroll).smoothScrollTo(Math.max(0, target.getLeft() + target.getWidth() / 2 - scroll.getWidth() / 2), 0);
        });
    }

    /** Pro opens: the tray closes (they are mutually exclusive). */
    public void setOnOpen(@Nullable Runnable onOpen) {
        this.onOpen = onOpen;
    }

    public boolean isOpen() {
        return open;
    }

    public void setOpen(boolean value) {
        if (value == open) return;
        open = value;
        if (resizing != null) resizing.cancel(); // its end puts the height back to wrap the content
        if (open) {
            if (onOpen != null) onOpen.run();
            tray.animate().setListener(null);
            hideHintNow();
            rebuild();
            // visible at once (the state); P509: above the button, growing out of it on a spring (the motion)
            placeAbovePill();
            TrayMotion.appear(tray, pill);
        } else {
            main.removeCallbacks(hideHint);
            TrayMotion.disappear(tray, pill, () -> {
                if (open) return;
                TrayMotion.setShown(tray, false);
                TrayMotion.rest(tray);
                hideHintNow();
            });
        }
        bindPill(true);
    }

    /** P509: the gap between the tray's bottom and the button's top. */
    static final int ABOVE_PILL_DP = 10;

    /**
     * P509: the tray's bottom {@link #ABOVE_PILL_DP} above the LUT button (the button at the viewfinder's bottom centre stays in sight
     * and closes the tray again): its bottom margin over its anchor (the Pro panel's bottom), never below the anchor.
     */
    private void placeAbovePill() {
        // P574: the top row's button is far above: the tray keeps its place at the viewfinder's bottom (it grows out of the button)
        if (iconOnly) return;
        if (!(tray.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) || !(tray.getParent() instanceof View)) return;
        final View parent = (View) tray.getParent();
        final View anchor = parent.findViewById(R.id.pro_panel);
        if (anchor == null || pill.getHeight() == 0) return;
        final int[] p = new int[2], b = new int[2];
        parent.getLocationInWindow(p);
        pill.getLocationInWindow(b);
        final int want = b[1] - p[1] - UiScale.dp(tray.getContext(), ABOVE_PILL_DP);
        final ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) tray.getLayoutParams();
        final int margin = Math.max(0, anchor.getBottom() - want);
        if (lp.bottomMargin != margin) {
            lp.bottomMargin = margin;
            tray.setLayoutParams(lp);
        }
    }

    /** The current LUT's name, «LUT» with none. */
    static String name(Context c, String value) {
        if (value == null || value.isEmpty()) return Lang.t(c, "Нет", "None");
        String look = FilmLooks.label(c, value);
        if (look != null) return look;
        return value.toLowerCase(java.util.Locale.ROOT).endsWith(".cube") ? value.substring(0, value.length() - 5) : value;
    }

    /** The filter's short name for the pill («Pro-Mist», «Туман»). */
    static String filterShort(Context c, String id) {
        return LensFilters.BLACK_PRO_MIST.equals(id) ? "Pro-Mist" : LensFilters.name(c, id);
    }

    /** The pill's label and colours from the settings (also after the settings screen changed them). */
    public void bindPill() {
        bindPill(false);
    }

    /** As {@link #bindPill()}; {@code animate}: the pill's colours cross-fade and it gives a little under the change (the tray opening). */
    private void bindPill(boolean animate) {
        Context c = pill.getContext();
        String value = PreferenceKeys.filmLut();
        String filter = PreferenceKeys.lensFilter();
        boolean filterOn = !filter.isEmpty() && PreferenceKeys.lensFilterStrength() > 0f;
        // P281: the film simulation's stock first on the pill
        final com.scamera.app.processing.color.FilmSim.Settings film = com.scamera.app.processing.color.FilmSim.current();
        boolean on = !value.isEmpty() && PreferenceKeys.filmLutStrength() > 0f || filterOn || film != null;
        int accent = ShadeStyle.accent(c);
        String label = value.isEmpty() ? (film != null ? film.stock.name : "LUT") : (film != null ? film.stock.name + " · " : "") + name(c, value);
        if (iconOnly) {
            bindIcon(c, on, accent, animate, label + (filterOn ? " · " + filterShort(c, filter) : ""));
            return;
        }
        pill.setText(filterOn ? label + " · " + filterShort(c, filter) : label);
        pill.setEllipsize(TextUtils.TruncateAt.END);
        int fg = open ? ShadeStyle.INK : on ? accent : 0xFFFFFFFF;
        final int fill = open ? accent : PILL_FILL;
        final int fromFg = pillInk, fromFill = pillFill;
        final boolean fadeIn = animate && pillBg != null && (fromFg != fg || fromFill != fill);
        pillInk = fg;
        pillFill = fill;
        pill.setTextColor(fg);
        android.graphics.drawable.Drawable icon = com.scamera.app.ui.Icons.inked(c, R.drawable.cv_lut, fg); // P570
        if (icon != null) {
            int s = UiScale.dp(c, 16);
            icon.setBounds(0, 0, s, s);
        }
        pill.setCompoundDrawablesRelative(icon, null, null, null);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(UiScale.dpF(c, 14));
        pill.setBackground(bg);
        pillBg = bg;
        if (fadeIn) {
            // the final colours are set above; the animator's first frame (before the next draw) starts from the old ones
            final android.graphics.drawable.Drawable[] icons = pill.getCompoundDrawablesRelative();
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(200);
            a.setInterpolator(TrayMotion.EASE_OUT);
            a.addUpdateListener(v -> {
                float k = (float) v.getAnimatedValue();
                if (pillBg != bg) return; // a newer bind took over
                bg.setColor(TrayMotion.mix(fromFill, fill, k));
                int ink = TrayMotion.mix(fromFg, fg, k);
                pill.setTextColor(ink);
                if (icons[0] != null) com.scamera.app.ui.Icons.recolour(c, R.drawable.cv_lut, icons[0], ink);
            });
            a.start();
            // the pill gives a little and springs back
            pill.animate().cancel();
            pill.setScaleX(.92f);
            pill.setScaleY(.92f);
            pill.animate().scaleX(1f).scaleY(1f).setDuration(TrayMotion.RELEASE.durationMs).setStartDelay(0).setInterpolator(TrayMotion.RELEASE).start();
        }
        pill.setContentDescription(Lang.t(c, "Плёнка (LUT): ", "Film (LUT): ") + name(c, value)
                + (filterOn ? Lang.t(c, ", фильтр: ", ", filter: ") + LensFilters.name(c, filter) : ""));
        pill.setActivated(open);
    }

    /**
     * P574: the top row's round LUT button (the concept's 40dp item): open, the accent icon on the accent's tint; a LUT, filter or
     * film on, the accent icon; else white. Its description names what is on.
     */
    private void bindIcon(Context c, boolean on, int accent, boolean animate, String what) {
        final int fg = open || on ? accent : ShadeStyle.TEXT;
        final int fill = open ? (accent & 0x00FFFFFF) | 0x29000000 : 0x00000000;
        final int fromFg = pillInk, fromFill = pillFill;
        final boolean fade = animate && pillBg != null && (fromFg != fg || fromFill != fill);
        pillInk = fg;
        pillFill = fill;
        pill.setText("");
        pill.setCompoundDrawablesRelative(null, null, null, null);
        final GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(fade ? fromFill : fill);
        final android.graphics.drawable.Drawable icon = com.scamera.app.ui.Icons.inked(c, R.drawable.cv_lut, fade ? fromFg : fg);
        final android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                icon != null ? new android.graphics.drawable.Drawable[]{bg, icon} : new android.graphics.drawable.Drawable[]{bg});
        if (icon != null) {
            final int s = UiScale.dp(c, 22);
            layers.setLayerSize(1, s, s);
            layers.setLayerGravity(1, Gravity.CENTER);
        }
        pill.setBackground(layers);
        pillBg = bg;
        if (fade) {
            final android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(200);
            a.setInterpolator(TrayMotion.EASE_OUT);
            a.addUpdateListener(v -> {
                if (pillBg != bg) return; // a newer bind took over
                final float k = (float) v.getAnimatedValue();
                bg.setColor(TrayMotion.mix(fromFill, fill, k));
                if (icon != null) com.scamera.app.ui.Icons.recolour(c, R.drawable.cv_lut, icon, TrayMotion.mix(fromFg, fg, k));
            });
            a.start();
            // the button gives a little and springs back (no cancel(): the button's turn with the phone may be running)
            pill.setScaleX(.9f);
            pill.setScaleY(.9f);
            pill.animate().scaleX(1f).scaleY(1f).setDuration(TrayMotion.RELEASE.durationMs).setStartDelay(0).setInterpolator(TrayMotion.RELEASE).start();
        }
        pill.setContentDescription(Lang.t(c, "LUT и плёнка: ", "LUT and film: ") + (on ? what : Lang.t(c, "выкл.", "off")));
        androidx.core.view.ViewCompat.setStateDescription(pill, open ? Lang.t(c, "открыто", "open") : null);
        pill.setActivated(open);
    }

    /** Every LUT as a card: «Нет», our looks, the folder's .cube files - in the user's order (P279, ItemOrder). */
    private void rebuild() {
        Context c = tray.getContext();
        values.clear();
        values.addAll(com.scamera.app.settings.ItemOrder.luts(c));
        row.removeAllViews();
        String chosen = PreferenceKeys.filmLut();
        int accent = ShadeStyle.accent(c);
        View selected = null;
        for (String value : values) {
            boolean on = value.equals(chosen);
            // owner, 2026-10-19 («Убираем маленькие живые превью в видоискателе»): no live previews; P509: a still thumbnail
            LinearLayout card = thumbCard(c, name(c, value), on, accent);
            LookThumbs.lut(thumb(card), value);
            card.setContentDescription(name(c, value));
            card.setOnClickListener(v -> { TrayMotion.tick(v); choose(value); });
            // P279: hold and drag a card to move it; the settings' strip shows the same order
            com.scamera.app.ui.settings.StripReorder.attach(card, row.getChildCount(), (from, to) -> {
                com.scamera.app.settings.ItemOrder.store(com.scamera.app.settings.ItemOrder.LUT_KEY, new ArrayList<>(values), from, to);
                rebuild();
            });
            row.addView(card, thumbParams(c, row));
            if (on) selected = card;
        }
        select(row, values.indexOf(chosen), CARD_CORNER_DP, false);
        rebuildFilters();
        rebuildFilms();
        // P319: the colour sliders follow the settings (also after the settings page changed them)
        setColour(warmth, warmthValue, colourValue(WARMTH_KEY));
        setColour(tint, tintValue, colourValue(TINT_KEY));
        bindLutState(chosen, false);
        // the chosen chip scrolled to the centre
        centre(row, selected);
        tab = -1;
        showTab(lastTab, false);
    }

    /** The strength row for the LUT {@code chosen}. */
    private void bindLutState(String chosen, boolean animate) {
        int s = Math.round(PreferenceKeys.filmLutStrength() * 100);
        strength.setValue(s);
        strengthValue.setText(s + " %");
        // without a LUT the strength has nothing to act on: dimmed
        View strengthRow = (View) strength.getParent();
        float alpha = chosen.isEmpty() ? .4f : 1f;
        if (animate) strengthRow.animate().alpha(alpha).setDuration(200).setStartDelay(0).setInterpolator(TrayMotion.EASE_OUT).start();
        else strengthRow.setAlpha(alpha);
    }

    /** P228: «Нет» and the 12 lens filters as cards, the chosen one in the accent, and the chosen filter's adjustments. */
    private void rebuildFilters() {
        Context c = tray.getContext();
        filterRow.removeAllViews();
        String chosen = PreferenceKeys.lensFilter();
        int accent = ShadeStyle.accent(c);
        View selected = null;
        filterValues.clear();
        java.util.Collections.addAll(filterValues, filterIds());
        for (String id : filterValues) {
            boolean on = id.equals(chosen);
            // owner, 2026-10-19: no live previews of the lens filters; P509: a still thumbnail of the filter on lights at night
            LinearLayout card = thumbCard(c, id.isEmpty() ? Lang.t(c, "Без фильтра", "No filter") : LensFilters.name(c, id), on, accent);
            LookThumbs.filter(thumb(card), id);
            card.setContentDescription(Lang.t(c, "Фильтр: ", "Filter: ") + LensFilters.name(c, id));
            card.setTag("lut_tray_filter_" + id);
            card.setOnClickListener(v -> { TrayMotion.tick(v); chooseFilter(id); });
            // P279: the filters' order, shared with the settings' strip
            com.scamera.app.ui.settings.StripReorder.attach(card, filterRow.getChildCount(), (from, to) -> {
                com.scamera.app.settings.ItemOrder.store(com.scamera.app.settings.ItemOrder.FILTER_KEY, java.util.Arrays.asList(filterIds()), from, to);
                rebuildFilters();
            });
            filterRow.addView(card, thumbParams(c, filterRow));
            if (on) selected = card;
        }
        select(filterRow, filterValues.indexOf(chosen), CARD_CORNER_DP, false);
        bindFilterAdjust(chosen);
        centre(filterRow, selected);
    }

    /** The chosen filter's adjustments (none without a filter): its strength, own parameter and fine controls. */
    private void bindFilterAdjust(String id) {
        if (id.isEmpty()) {
            filterAdjust.view.setVisibility(View.GONE);
            return;
        }
        filterAdjust.setParams(filterParams(tray.getContext(), id));
        filterAdjust.view.setVisibility(View.VISIBLE);
    }

    /**
     * A filter's parameters as its settings page has them (LensFilterPreference): the strength (0..100, 50), its own parameter
     * and its fine controls (0..100, the middle as designed: LensFilters.Tune) - written as that page writes them.
     */
    static List<TrayAdjust.Param> filterParams(Context c, String id) {
        final List<TrayAdjust.Param> out = new ArrayList<>();
        out.add(new TrayAdjust.Param("strength", Lang.t(c, "Сила", "Strength"), ParamHelp.of(c, LensFilters.STRENGTH_KEY),
                0, 100, 1, 50, " %", true,
                () -> PreferenceKeys.lensFilterStrength() * 100f,
                v -> settings().set(SettingsManager.SCOPE_GLOBAL, LensFilters.STRENGTH_KEY, String.valueOf(Math.round(v)))));
        final String pn = LensFilters.paramName(c, id);
        if (pn != null) {
            final int colon = pn.indexOf(':');
            out.add(new TrayAdjust.Param("param", colon > 0 ? pn.substring(0, colon) : pn, ParamHelp.filterParam(c, id),
                    0, 100, 1, Math.round(LensFilters.defaultParam(id) * 100f), "", true,
                    () -> PreferenceKeys.lensFilterTune(id).param * 100f,
                    v -> {
                        LensFilters.Tune t = PreferenceKeys.lensFilterTune(id);
                        t.param = Math.round(v) / 100f;
                        settings().set(SettingsManager.SCOPE_GLOBAL, LensFilters.tuneKey(id), t.serialize());
                    }));
        }
        final int fine = LensFilters.tunable(id);
        for (int control : LensFilters.CONTROLS) {
            if ((fine & control) == 0) continue;
            out.add(new TrayAdjust.Param("tune_" + control, LensFilters.controlName(c, id, control), ParamHelp.filterControl(c, id, control),
                    0, 100, 1, 50, "", true,
                    () -> PreferenceKeys.lensFilterTune(id).get(control) * 100f,
                    v -> {
                        LensFilters.Tune t = PreferenceKeys.lensFilterTune(id);
                        t.set(control, Math.round(v) / 100f);
                        settings().set(SettingsManager.SCOPE_GLOBAL, LensFilters.tuneKey(id), t.serialize());
                    }));
        }
        return out;
    }

    /**
     * A film's parameters as the film page has them (FilmSimPreference, the same keys, ranges, steps and defaults): the
     * simulation's strength (written as it moves: the viewfinder mixes it), P381 the print (the paper or print film of every
     * negative, as on the page: a black-and-white one's grain follows it) and the format (every stock) as choices, grain, halation, softness, the
     * film's exposure, its contrast (the measured colour stocks: our black-and-white model has none) and the print's exposure
     * (not for a slide or a reversal film, which are not printed) - the enlarger's filters stay on the page («Все ›»).
     * P380: the film's diffusion (a choice: Нет and the four families) and its «Сила фильтра» after the format, and
     * «Цветоразделение (DIR)» (the measured colour stocks) after the contrast - the film page's rows.
     */
    static List<TrayAdjust.Param> filmParams(Context c, @Nullable com.scamera.app.processing.color.FilmSim.Stock st) {
        final List<TrayAdjust.Param> out = new ArrayList<>();
        out.add(filmParam(c, "strength", Lang.t(c, "Сила", "Strength"), com.scamera.app.processing.color.FilmSim.STRENGTH_KEY, 0, 100, 1, 100, " %", true));
        // P381 (owner, 2026-10-28, the film page's «Плёнка · Печать · Формат · Эффекты» circled): its print and format here too
        if (st == null || !st.reversal()) out.add(printChoice(c, st));
        out.add(formatChoice(c));
        // P380: the film's camera diffusion and its grade
        out.add(diffusionChoice(c));
        out.add(diffusionStrength(c));
        out.add(filmParam(c, "grain", Lang.t(c, "Зерно", "Grain"), FilmSim.GRAIN_KEY, 0, 200, 1, FilmSim.defaultPercent(FilmSim.GRAIN_KEY), " %", false));
        out.add(filmParam(c, "halation", Lang.t(c, "Ореол", "Halation"), FilmSim.HALATION_KEY, 0, 400, 1, FilmSim.defaultPercent(FilmSim.HALATION_KEY), " %", false));
        out.add(filmParam(c, "softness", Lang.t(c, "Мягкость", "Softness"), FilmSim.SOFTNESS_KEY, 0, 300, 1, FilmSim.defaultPercent(FilmSim.SOFTNESS_KEY), " %", false));
        out.add(filmParam(c, "exposure", Lang.t(c, "Экспозиция", "Exposure"), com.scamera.app.processing.color.FilmSim.EXPOSURE_KEY, -2, 2, .1f, 0, " EV", false));
        // only what acts on this stock: the curve's steepness on the measured (colour) stocks, the print on what is printed
        if (st == null || st.spectral != null)
            out.add(filmParam(c, "contrast", Lang.t(c, "Контраст", "Contrast"), com.scamera.app.processing.color.FilmSim.TONE_KEY, -100, 100, 1, 0, "", false));
        // P380: the DIR couplers' amount, as on the page (the measured colour stocks)
        if (st == null || st.spectral != null)
            out.add(filmParam(c, "dir", Lang.t(c, "Цветоразделение (DIR)", "Colour separation (DIR)"), FilmSim.DIR_KEY, 0, 100, 1, FilmSim.defaultPercent(FilmSim.DIR_KEY), " %", false));
        if (st == null || st.type != com.scamera.app.processing.color.FilmSim.SLIDE && st.type != com.scamera.app.processing.color.FilmSim.BW_REVERSAL)
            out.add(filmParam(c, "print", Lang.t(c, "Экспозиция печати", "Print exposure"), com.scamera.app.processing.color.FilmSim.PRINT_EV_KEY, -1, 1, .05f, 0, " EV", false));
        return out;
    }

    /**
     * P381: the film page's «Печать» - the paper or print film the negative is printed on (FilmSim.PRINTS in the page's order),
     * stored as the page stores it (its id in PRINT_KEY); an unset measured recipe reads as Supra Endura.
     */
    static TrayAdjust.Param printChoice(Context c, @Nullable FilmSim.Stock st) {
        final FilmSim.Print[] prints = FilmSim.PRINTS;
        final String[] labels = new String[prints.length], info = new String[prints.length];
        for (int i = 0; i < prints.length; i++) {
            labels[i] = prints[i].name;
            info[i] = prints[i].brand + " " + prints[i].name + ", " + (prints[i].film ? Lang.t(c, "кинопечать", "print film") : Lang.t(c, "бумага", "paper"));
        }
        return new TrayAdjust.Param("paper", Lang.t(c, "Печать", "Print"), ParamHelp.of(c, FilmSim.PRINT_KEY), labels, info,
                printIndex(FilmSim.print("", st).id),
                () -> (float) printIndex(FilmSim.print(settings().getString(SettingsManager.SCOPE_GLOBAL, FilmSim.PRINT_KEY, ""), st).id),
                v -> settings().set(SettingsManager.SCOPE_GLOBAL, FilmSim.PRINT_KEY, prints[Math.max(0, Math.min(prints.length - 1, Math.round(v)))].id));
    }

    /**
     * P380: the film page's «Диффузия» - none or a camera diffusion family (FilmSim.DIFFUSIONS), stored as the page stores it (its
     * id in DIFFUSION_KEY, "" none: the default).
     */
    static TrayAdjust.Param diffusionChoice(Context c) {
        final String[] ids = FilmSim.DIFFUSIONS;
        final String[] labels = new String[ids.length], info = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            labels[i] = FilmSim.diffusionName(c, ids[i]);
            info[i] = labels[i] + ", " + (ids[i].isEmpty() ? Lang.t(c, "без диффузии", "no diffusion") : LensFilters.hint(c, ids[i]));
        }
        return new TrayAdjust.Param("diffusion", Lang.t(c, "Диффузия", "Diffusion"), ParamHelp.of(c, FilmSim.DIFFUSION_KEY), labels, info, 0,
                () -> (float) diffusionIndex(settings().getString(SettingsManager.SCOPE_GLOBAL, FilmSim.DIFFUSION_KEY, "")),
                v -> settings().set(SettingsManager.SCOPE_GLOBAL, FilmSim.DIFFUSION_KEY, ids[Math.max(0, Math.min(ids.length - 1, Math.round(v)))]));
    }

    private static int diffusionIndex(String id) {
        for (int i = 0; i < FilmSim.DIFFUSIONS.length; i++) if (FilmSim.DIFFUSIONS[i].equals(id)) return i;
        return 0;
    }

    /** P380: the film page's «Сила фильтра» - the diffusion's grade 0 .. 2 (step 0.05, 0.5 by default), stored x 100 as the page. */
    static TrayAdjust.Param diffusionStrength(Context c) {
        return new TrayAdjust.Param("diffusion_strength", Lang.t(c, "Сила фильтра", "Filter strength"), ParamHelp.of(c, FilmSim.DIFFUSION_STRENGTH_KEY),
                0, 2, .05f, FilmSim.DIFFUSION_DEFAULT, "", false,
                () -> FilmSim.diffusionGrade(settings().getString(SettingsManager.SCOPE_GLOBAL, FilmSim.DIFFUSION_STRENGTH_KEY, "50")),
                v -> settings().set(SettingsManager.SCOPE_GLOBAL, FilmSim.DIFFUSION_STRENGTH_KEY, String.valueOf(Math.round(v * 100f))));
    }

    private static int printIndex(String id) {
        for (int i = 0; i < FilmSim.PRINTS.length; i++) if (FilmSim.PRINTS[i].id.equals(id)) return i;
        return 0;
    }

    /**
     * P381: the film page's «Формат» - the frame's size (FilmSim.FORMATS; «Выкл.» no grain, halation or film softness), stored as
     * the page stores it (its id in FORMAT_KEY, 35 mm by default).
     */
    static TrayAdjust.Param formatChoice(Context c) {
        final FilmSim.Format[] formats = FilmSim.FORMATS;
        final String[] labels = new String[formats.length], info = new String[formats.length];
        for (int i = 0; i < formats.length; i++) {
            labels[i] = FilmSim.formatName(c, formats[i]);
            info[i] = labels[i] + ", " + FilmSim.formatHint(c, formats[i]);
        }
        return new TrayAdjust.Param("format", Lang.t(c, "Формат", "Format"), ParamHelp.of(c, FilmSim.FORMAT_KEY), labels, info,
                formatIndex(FilmSim.format(FilmSim.DEFAULT_FORMAT).id),
                () -> (float) formatIndex(FilmSim.format(settings().getString(SettingsManager.SCOPE_GLOBAL, FilmSim.FORMAT_KEY, FilmSim.DEFAULT_FORMAT)).id),
                v -> settings().set(SettingsManager.SCOPE_GLOBAL, FilmSim.FORMAT_KEY, formats[Math.max(0, Math.min(formats.length - 1, Math.round(v)))].id));
    }

    private static int formatIndex(String id) {
        for (int i = 0; i < FilmSim.FORMATS.length; i++) if (FilmSim.FORMATS[i].id.equals(id)) return i;
        return 0;
    }

    private static TrayAdjust.Param filmParam(Context c, String id, String title, String key, float min, float max, float step, float def,
                                              String unit, boolean live) {
        return new TrayAdjust.Param(id, title, ParamHelp.of(c, key), min, max, step, def, unit, live,
                () -> {
                    try {
                        return Float.parseFloat(settings().getString(SettingsManager.SCOPE_GLOBAL, key, String.valueOf(def)).replace(',', '.'));
                    } catch (RuntimeException e) {
                        return def;
                    }
                },
                // as the film page stores it (FilmSimPreference.slider)
                v -> settings().set(SettingsManager.SCOPE_GLOBAL, key, step < 1 ? String.format(java.util.Locale.ROOT, "%.2f", v) : String.valueOf(Math.round(v))));
    }

    /** «Без фильтра» and every filter, in the user's order (P279). */
    static String[] filterIds() {
        return com.scamera.app.settings.ItemOrder.filters().toArray(new String[0]);
    }

    /** P281: «Без плёнки» and every film stock as a small text card (maker, name); a tap makes it the camera's. */
    private void rebuildFilms() {
        Context c = tray.getContext();
        filmRow.removeAllViews();
        final String chosen = settings().getString(SettingsManager.SCOPE_GLOBAL, com.scamera.app.processing.color.FilmSim.STOCK_KEY, "");
        final int accent = ShadeStyle.accent(c);
        final int count = com.scamera.app.processing.color.FilmSim.STOCKS.length + 1;
        View selected = null;
        filmValues.clear();
        for (int i = 0; i < count; i++) {
            final com.scamera.app.processing.color.FilmSim.Stock st = i == 0 ? null : com.scamera.app.processing.color.FilmSim.STOCKS[i - 1];
            final String id = st == null ? "" : st.id;
            filmValues.add(id);
            final boolean on = id.equals(chosen);
            // P509: a still thumbnail of the stock's colour and its name under it (the maker in the description)
            LinearLayout card = thumbCard(c, st == null ? Lang.t(c, "Без плёнки", "No film") : st.name, on, accent);
            LookThumbs.film(thumb(card), id);
            card.setContentDescription(st == null ? Lang.t(c, "Без плёнки", "No film") : st.brand + " " + st.name);
            card.setTag("lut_tray_film_" + id);
            card.setOnClickListener(v -> {
                TrayMotion.tick(v);
                settings().set(SettingsManager.SCOPE_GLOBAL, com.scamera.app.processing.color.FilmSim.STOCK_KEY, id);
                // in place: the pill glides to the stock, the row is not built again; its adjustments follow (the height too)
                select(filmRow, filmValues.indexOf(id), CARD_CORNER_DP, open);
                resize(() -> bindFilmAdjust(id));
                centre(filmRow, v);
                bindPill();
                bindTabs();
            });
            filmRow.addView(card, thumbParams(c, filmRow));
            if (on) selected = card;
        }
        select(filmRow, filmValues.indexOf(chosen), CARD_CORNER_DP, false);
        bindFilmAdjust(chosen);
        centre(filmRow, selected);
    }

    /** The chosen film's adjustments (none without a film). */
    private void bindFilmAdjust(String id) {
        final com.scamera.app.processing.color.FilmSim.Stock st = com.scamera.app.processing.color.FilmSim.stock(id);
        if (id.isEmpty() || st == null) {
            filmAdjust.view.setVisibility(View.GONE);
            return;
        }
        filmAdjust.setParams(filmParams(tray.getContext(), st));
        filmAdjust.view.setVisibility(View.VISIBLE);
    }

    /** A filter card tapped: that filter becomes the camera's (the settings' state). */
    void chooseFilter(String id) {
        settings().set(SettingsManager.SCOPE_GLOBAL, LensFilters.KEY, id);
        if (!id.isEmpty() && PreferenceKeys.lensFilterStrength() <= 0f)
            settings().set(SettingsManager.SCOPE_GLOBAL, LensFilters.STRENGTH_KEY, "50");
        int i = filterValues.indexOf(id);
        if (open && i >= 0 && filterRow.getChildCount() == filterValues.size() && filterValues.equals(java.util.Arrays.asList(filterIds()))) {
            // in place: the pill glides to the filter, its adjustments follow (the height too)
            select(filterRow, i, CARD_CORNER_DP, true);
            resize(() -> bindFilterAdjust(id));
            centre(filterRow, filterRow.getChildAt(i));
        } else rebuildFilters();
        bindPill();
        bindTabs();
    }

    /** A card tapped: that LUT becomes the camera's (the settings' state), the tray shows it chosen. */
    void choose(String value) {
        settings().set(SettingsManager.SCOPE_GLOBAL, FilmLut.KEY, value);
        if (!value.isEmpty() && PreferenceKeys.filmLutStrength() <= 0f)
            settings().set(SettingsManager.SCOPE_GLOBAL, FilmLut.STRENGTH_KEY, "100");
        int i = values.indexOf(value);
        if (open && i >= 0 && row.getChildCount() == values.size()) {
            // in place: the pill glides to the LUT, the strength row follows
            select(row, i, CARD_CORNER_DP, true);
            bindLutState(value, true);
            centre(row, row.getChildAt(i));
        } else rebuild();
        bindPill();
        bindTabs();
    }

    /** The next / previous LUT (a horizontal swipe on the viewfinder); returns its name for the message band. */
    public String step(int direction) {
        Context c = tray.getContext();
        if (!open) {
            values.clear();
            values.addAll(com.scamera.app.settings.ItemOrder.luts(c));
        }
        int i = values.indexOf(PreferenceKeys.filmLut());
        int n = values.size();
        String next = values.get(((i < 0 ? 0 : i) + direction % n + n) % n);
        choose(next);
        return Lang.t(c, "LUT: ", "LUT: ") + name(c, next);
    }

    /** The camera screen goes. */
    public void release() {
        main.removeCallbacksAndMessages(null);
        open = false;
        if (resizing != null) resizing.cancel();
        tray.animate().cancel();
        TrayMotion.rest(tray);
    }
}
