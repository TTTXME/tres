package com.particlesdevs.photoncamera.ui.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiScale;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens;
import com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle;
import com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle.Param;
import com.particlesdevs.photoncamera.settings.OldstyleMode;
import com.particlesdevs.photoncamera.ui.Motion;
import com.particlesdevs.photoncamera.ui.camera.views.viewfinder.MainRenderer;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * P630 / P630c «Oldstyle» (owner, 2026-11-12, two reference videos; «стилистика должна остаться наша»): the mode's own viewfinder
 * controls.
 * <ul>
 *   <li>The «Про» panel's manual row (EV, ISO, S, WB, AF) stays open in this mode ({@link #apply}); «Про» steps aside.</li>
 *   <li>A round button above the row's right end opens the style panel, which takes the row's place: a header (back, the segments
 *       «Палитра | Расширенные», reset - shown once anything is changed - and a menu) under
 *       <ul>
 *         <li>«Палитра»: a 2D pad over the preview's lower part (left a cool grey, right a warm salmon colour = «Цвет»; up lighter,
 *             down darker = «Тон»; a dot grid that grows under the finger, a dotted cross at the point at rest) and the HUD line
 *             «База: Natural · Тон: 81 · Цвет: -100» at the preview's top;</li>
 *         <li>«Расширенные»: a row of chips (exposure, aura, halo, grain, warmth of the light, hue shift, highlights, shadows, warm and
 *             cool boost, sharpness, vignette) under a centred tick ruler with the value above it (-100..100, or off / low / medium /
 *             high), a tick of haptics per step, a double tap back to 0.</li>
 *       </ul>
 *       Reset asks «Сбросить стиль по умолчанию?» and says «Стиль сброшен». Every change shows live in the viewfinder
 *       (MainRenderer.setOldstyleAdjust: the photo's function) and the photo takes the style at the press (ShotLook.Oldstyle).</li>
 * </ul>
 * Built in code into {@code R.id.oldstyle_controls} (camera_fragment, right above the manual row) and the HUD
 * {@code R.id.oldstyle_hud}; hidden with the shade up, as the manual row.
 */
public final class OldstyleControls {
    static final int PALETTE = 0, ADVANCED = 1;

    private final FrameLayout host;
    @Nullable private final TextView hud;
    @Nullable private final View anchor;
    private final Context context;
    @Nullable private final Consumer<CharSequence> message;
    private final int accent;
    private final LinearLayout column, panel;
    private final FrameLayout button, content;
    private final ImageView buttonIcon;
    private final View reset;
    private final TextView[] tabs = new TextView[2];
    private final PalettePad pad;
    private final LinearLayout advanced;
    private final TextView value;
    private final Ruler ruler;
    private final List<View> chips = new ArrayList<>();
    private final HorizontalScrollView chipScroll;
    @Nullable private com.particlesdevs.photoncamera.circularbarlib.ui.ExpandingManualPanel manual;
    private int tab = PALETTE;
    private Param chosen = Param.EXPOSURE;
    private boolean on, open, shadeShown;

    public OldstyleControls(FrameLayout host, @Nullable TextView hud, @Nullable View anchor, @Nullable Consumer<CharSequence> message) {
        this.host = host;
        this.hud = hud;
        this.anchor = anchor;
        this.context = host.getContext();
        this.message = message;
        this.accent = UiTokens.cameraAccent(context);
        host.removeAllViews();
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.END);
        host.addView(column, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        // the panel: the tab's content over the header
        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setTag("oldstyle_panel");
        content = new FrameLayout(context);
        panel.addView(content, new LinearLayout.LayoutParams(-1, -2));
        pad = new PalettePad(context, this::onPad);
        pad.setTag("oldstyle_pad");
        content.addView(pad, new FrameLayout.LayoutParams(-1, dp(150)));
        advanced = new LinearLayout(context);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.setTag("oldstyle_advanced");
        value = new TextView(context);
        value.setTextColor(UiTokens.TEXT);
        value.setGravity(Gravity.CENTER);
        value.setTypeface(UiTokens.font(context, 700));
        value.setShadowLayer(dp(3), 0, 0, 0x99000000);
        UiTokens.setTextSp(value, 15f);
        advanced.addView(value, new LinearLayout.LayoutParams(-1, -2));
        ruler = new Ruler(context, accent);
        ruler.setOnValue(this::onRuler);
        advanced.addView(ruler, new LinearLayout.LayoutParams(-1, dp(38)));
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        chipScroll = scroll;
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setTag("oldstyle_chips");
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), 0, dp(4), 0);
        for (Param p : OldstyleStyle.ADVANCED) {
            View chip = chip(p);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(64), dp(64));
            lp.setMarginEnd(dp(8));
            row.addView(chip, lp);
            chips.add(chip);
        }
        scroll.addView(row);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(6);
        advanced.addView(scroll, slp);
        content.addView(advanced, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        // the header
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setTag("oldstyle_header");
        View back = round(R.drawable.cv_chev_left, Lang.t(context, "Закрыть", "Close"));
        back.setOnClickListener(v -> setOpen(false, true));
        header.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        header.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));
        LinearLayout seg = new LinearLayout(context);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        seg.setBackground(UiTokens.shape(context, ConceptStyle.CONTROL, ConceptStyle.HAIRLINE, 20));
        for (int i = 0; i < 2; i++) {
            final int t = i;
            TextView label = new TextView(context);
            label.setText(i == PALETTE ? Lang.t(context, "Палитра", "Palette") : Lang.t(context, "Расширенные", "Advanced"));
            label.setGravity(Gravity.CENTER);
            label.setTypeface(UiTokens.font(context, 600));
            UiTokens.setTextSp(label, 13f);
            label.setPadding(dp(14), 0, dp(14), 0);
            label.setTag("oldstyle_tab_" + i);
            label.setOnClickListener(v -> setTab(t, true));
            seg.addView(label, new LinearLayout.LayoutParams(-2, dp(32)));
            tabs[i] = label;
        }
        header.addView(seg, new LinearLayout.LayoutParams(-2, -2));
        header.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));
        reset = round(R.drawable.cs_page_reset, Lang.t(context, "Сбросить стиль", "Reset the style"));
        reset.setTag("oldstyle_reset");
        reset.setOnClickListener(v -> askReset());
        header.addView(reset, new LinearLayout.LayoutParams(dp(40), dp(40)));
        View more = round(R.drawable.cv_os_more, Lang.t(context, "Меню стиля", "Style menu"));
        more.setOnClickListener(this::menu);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(40), dp(40));
        mlp.setMarginStart(dp(4));
        header.addView(more, mlp);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = dp(10);
        panel.addView(header, hlp);
        column.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        panel.setVisibility(View.GONE);
        // the round button above the row's right end
        button = new FrameLayout(context);
        button.setTag("oldstyle_adjust_button");
        button.setContentDescription(Lang.t(context, "Стиль ScamMaster", "ScamMaster style"));
        buttonIcon = new ImageView(context);
        buttonIcon.setImageResource(R.drawable.cv_os_style);
        button.addView(buttonIcon, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(44), dp(44));
        blp.setMarginEnd(dp(4));
        column.addView(button, blp);
        button.setOnClickListener(v -> setOpen(true, true));
        button.setOnLongClickListener(v -> {
            say(Lang.t(context, "Стиль ScamMaster: палитра «Тон» и «Цвет» и расширенные настройки. Видно сразу в видоискателе и так же на фото.",
                    "ScamMaster style: the «Tone» and «Color» palette and advanced settings. Shown live in the viewfinder, the same on the photo."));
            return true;
        });
        Motion.press(button);
        if (hud != null) {
            hud.setTextColor(UiTokens.TEXT);
            UiTokens.setTextSp(hud, 12f);
            hud.setTypeface(UiTokens.font(context, 600));
            hud.setShadowLayer(dp(3), 0, 0, 0xAA000000);
            hud.setTag("oldstyle_hud");
        }
        setTab(PALETTE, false);
        bind();
        host.setVisibility(View.GONE);
    }

    private int dp(float v) {
        return UiScale.dp(context, v);
    }

    private void say(CharSequence text) {
        if (message != null) message.accept(text);
    }

    private View round(int icon, String description) {
        FrameLayout b = new FrameLayout(context);
        b.setBackground(UiTokens.shape(context, ConceptStyle.CONTROL, ConceptStyle.HAIRLINE, 20));
        ImageView i = new ImageView(context);
        i.setImageResource(icon);
        i.setImageTintList(ColorStateList.valueOf(UiTokens.TEXT));
        b.addView(i, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        b.setContentDescription(description);
        Motion.press(b);
        return b;
    }

    /** A chip's name (RU / EN). */
    static String name(Context c, Param p) {
        switch (p) {
            case TONE: return Lang.t(c, "Тон", "Tone");
            case COLOR: return Lang.t(c, "Цвет", "Color");
            case EXPOSURE: return Lang.t(c, "Экспозиция", "Exposure");
            case AURA: return Lang.t(c, "Аура", "Aura light");
            case HALO: return Lang.t(c, "Ореол", "Halo");
            case GRAIN: return Lang.t(c, "Зерно", "Grain");
            case WARMTH: return Lang.t(c, "Теплота света", "Light warmth");
            case HUE: return Lang.t(c, "Сдвиг оттенка", "Hue shift");
            case HIGHLIGHTS: return Lang.t(c, "Света", "Highlights");
            case SHADOWS: return Lang.t(c, "Тени", "Shadows");
            case WARM_BOOST: return Lang.t(c, "Тёплые цвета", "Warm boost");
            case COOL_BOOST: return Lang.t(c, "Холодные цвета", "Cool boost");
            case SHARPNESS: return Lang.t(c, "Резкость", "Sharpness");
            default: return Lang.t(c, "Виньетка", "Vignette");
        }
    }

    /** The long-press help of a chip. */
    static String help(Context c, Param p) {
        switch (p) {
            case EXPOSURE: return Lang.t(c, "Экспозиция: весь снимок светлее или темнее, до ±1,5 ступени.", "Exposure: the whole picture lighter or darker, up to ±1.5 stops.");
            case AURA: return Lang.t(c, "Аура: мягкое широкое свечение вокруг светлых мест, как у старой оптики.", "Aura light: a soft wide glow round the light areas, like old lenses.");
            case HALO: return Lang.t(c, "Ореол: узкое свечение по краю ярких предметов.", "Halo: a tight glow along the edges of bright things.");
            case GRAIN: return Lang.t(c, "Зерно: плёночное зерно, сильнее в средних тонах.", "Grain: film grain, strongest in the mid-tones.");
            case WARMTH: return Lang.t(c, "Теплота света: светлые места теплее (плюс) или холоднее (минус).", "Light warmth: the light areas warmer (plus) or colder (minus).");
            case HUE: return Lang.t(c, "Сдвиг оттенка: все цвета поворачиваются по кругу оттенков.", "Hue shift: every colour turns round the hue circle.");
            case HIGHLIGHTS: return Lang.t(c, "Света: яркие места светлее или темнее.", "Highlights: the bright areas lighter or darker.");
            case SHADOWS: return Lang.t(c, "Тени: тёмные места светлее или глубже.", "Shadows: the dark areas lighter or deeper.");
            case WARM_BOOST: return Lang.t(c, "Тёплые цвета: насыщенность красных, оранжевых и жёлтых.", "Warm boost: the saturation of reds, oranges and yellows.");
            case COOL_BOOST: return Lang.t(c, "Холодные цвета: насыщенность синих и голубых.", "Cool boost: the saturation of blues and cyans.");
            case SHARPNESS: return Lang.t(c, "Резкость: подчёркивает мелкие детали; минус смягчает.", "Sharpness: brings out fine detail; minus softens.");
            case VIGNETTE: return Lang.t(c, "Виньетка: углы темнее (плюс) или светлее (минус).", "Vignette: the corners darker (plus) or lighter (minus).");
            default: return Lang.t(c, "Палитра: вверх — светлее, вниз — темнее; влево — серый холодный, вправо — тёплый цвет.",
                    "Palette: up lighter, down darker; left a cool grey, right a warm colour.");
        }
    }

    static int icon(Param p) {
        switch (p) {
            case EXPOSURE: return R.drawable.cv_ev;
            case AURA: return R.drawable.cv_os_aura;
            case HALO: return R.drawable.cv_os_halo;
            case GRAIN: return R.drawable.cv_os_grain;
            case WARMTH: return R.drawable.cv_thermo;
            case HUE: return R.drawable.cv_os_hue;
            case HIGHLIGHTS: return R.drawable.cv_sun;
            case SHADOWS: return R.drawable.cv_os_contrast;
            case WARM_BOOST: return R.drawable.cv_os_warm;
            case COOL_BOOST: return R.drawable.cv_os_cool;
            case SHARPNESS: return R.drawable.cv_sharp;
            default: return R.drawable.cv_os_vignette;
        }
    }

    /** A discrete step's word. */
    static String step(Context c, int v) {
        switch (v) {
            case 1: return Lang.t(c, "Низк.", "Low");
            case 2: return Lang.t(c, "Средн.", "Medium");
            case 3: return Lang.t(c, "Высок.", "High");
            default: return Lang.t(c, "Выкл.", "Off");
        }
    }

    private View chip(Param p) {
        LinearLayout t = new LinearLayout(context);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setTag("oldstyle_chip_" + p.key);
        ImageView icon = new ImageView(context);
        icon.setImageResource(icon(p));
        t.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView label = new TextView(context);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setText(name(context, p));
        UiTokens.setTextSp(label, label.getText().length() > 10 ? 8.5f : 10f);
        label.setTypeface(UiTokens.font(context, 500));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(4);
        t.addView(label, lp);
        t.setOnClickListener(v -> {
            if (chosen == p) return;
            chosen = p;
            Motion.pop(icon);
            final int target = Math.max(0, t.getLeft() + t.getWidth() / 2 - chipScroll.getWidth() / 2);
            if (Motion.enabled(chipScroll)) chipScroll.smoothScrollTo(target, 0); else chipScroll.scrollTo(target, 0);
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            bind(true);
        });
        t.setOnLongClickListener(v -> {
            say(help(context, p));
            return true;
        });
        Motion.press(t);
        return t;
    }

    /** The panel, the chips, the ruler, the pad, the HUD and the viewfinder from the stored style. */
    void bind() {
        bind(true);
    }

    private void bind(boolean moveRuler) {
        final OldstyleStyle st = OldstyleMode.style();
        for (int i = 0; i < chips.size(); i++) {
            final Param p = OldstyleStyle.ADVANCED[i];
            final View chip = chips.get(i);
            final boolean sel = p == chosen;
            final int ink = sel ? accent : UiTokens.TEXT;
            chip.setBackground(UiTokens.shape(context, sel ? ConceptStyle.dark(accent) : 0xD91C1C1E, sel ? accent : ConceptStyle.HAIRLINE, 12));
            chip.setSelected(sel);
            ((ImageView) ((ViewGroup) chip).getChildAt(0)).setImageTintList(ColorStateList.valueOf(ink));
            ((TextView) ((ViewGroup) chip).getChildAt(1)).setTextColor(ink);
            chip.setForeground(st.get(p) != 0 && !sel ? dot() : null);
            chip.setContentDescription(name(context, p) + " " + label(p, st.get(p)));
        }
        if (moveRuler) ruler.configure(chosen, st.get(chosen), false);
        value.setText(label(chosen, st.get(chosen)));
        value.setTextColor(st.get(chosen) != 0 ? accent : UiTokens.TEXT);
        pad.setPoint(st.get(Param.COLOR), st.get(Param.TONE), !pad.isDragging());
        if (hud != null) hud.setText(st.hud(Lang.t(context, "База", "Base"), Lang.t(context, "Тон", "Tone"), Lang.t(context, "Цвет", "Color")));
        reset.setVisibility(st.neutral() ? View.INVISIBLE : View.VISIBLE);
        if (on) MainRenderer.setOldstyleAdjust(st.neutral() ? null : st.uniforms());
        styleButton(st);
    }

    String label(Param p, int v) {
        return p.discrete ? step(context, v) : (v > 0 ? "+" + v : Integer.toString(v));
    }

    private android.graphics.drawable.Drawable dot() {
        final GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(accent);
        return new android.graphics.drawable.InsetDrawable(d, 0) {
            @Override public void draw(Canvas canvas) {
                final android.graphics.Rect b = getBounds();
                final int r = dp(2.5f);
                d.setBounds(b.right - dp(8) - r, b.top + dp(8) - r, b.right - dp(8) + r, b.top + dp(8) + r);
                d.draw(canvas);
            }
        };
    }

    private void onRuler(int v) {
        OldstyleMode.setStyle(chosen, v);
        bind(false);
    }

    private void onPad(int colour, int tone) {
        OldstyleMode.setStyle(Param.COLOR, colour);
        OldstyleMode.setStyle(Param.TONE, tone);
        bind(false);
    }

    private void styleButton(OldstyleStyle st) {
        button.setBackground(UiTokens.shape(context, ConceptStyle.CONTROL, st.neutral() ? ConceptStyle.HAIRLINE : accent, 22));
        buttonIcon.setImageTintList(ColorStateList.valueOf(st.neutral() ? UiTokens.TEXT : accent));
    }

    /** «Сбросить стиль по умолчанию?» OK / Отмена, then «Стиль сброшен». */
    androidx.appcompat.app.AlertDialog askReset() {
        return com.particlesdevs.photoncamera.ui.settings.DialogStyle.confirm(context, Lang.t(context, "Сбросить стиль по умолчанию?", "Restore the style to default?"),
                Lang.t(context, "Палитра и все расширенные настройки вернутся к нулю.", "The palette and every advanced setting go back to zero."),
                "OK", this::resetNow);
    }

    void resetNow() {
        OldstyleMode.resetStyle();
        bind(true);
        say(Lang.t(context, "Стиль сброшен", "Style restored to default"));
    }

    private void menu(View anchorView) {
        android.widget.PopupMenu m = new android.widget.PopupMenu(context, anchorView);
        m.getMenu().add(0, 1, 0, Lang.t(context, "Сбросить стиль", "Reset the style"));
        m.getMenu().add(0, 2, 1, Lang.t(context, "Тон: ", "Tone: ") + (OldstyleMode.v2() ? OldstyleMode.NAME + " V2 → V1" : OldstyleMode.NAME + " V1 → V2"));
        m.getMenu().add(0, 3, 2, Lang.t(context, "Справка", "Help"));
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) askReset();
            else if (item.getItemId() == 2) {
                OldstyleMode.setV2(!OldstyleMode.v2());
                say(OldstyleMode.NAME + " " + (OldstyleMode.v2() ? "V2" : "V1"));
            } else say(tab == PALETTE ? help(context, Param.TONE) : help(context, chosen));
            return true;
        });
        com.particlesdevs.photoncamera.ui.settings.DialogStyle.track(m);
        m.show();
    }

    /** The tab, the content cross-fading. */
    void setTab(int t, boolean animate) {
        tab = t;
        for (int i = 0; i < 2; i++) {
            final boolean sel = i == t;
            tabs[i].setTextColor(sel ? accent : UiTokens.TEXT);
            tabs[i].setBackground(sel ? UiTokens.shape(context, ConceptStyle.CHOSEN, 0, 16) : null);
            tabs[i].setSelected(sel);
        }
        final View in = t == PALETTE ? pad : advanced, out = t == PALETTE ? advanced : pad;
        out.animate().cancel();
        in.animate().cancel();
        if (animate && Motion.enabled(in)) {
            out.animate().alpha(0f).translationX(dp(t == PALETTE ? 24 : -24)).setDuration(160)
                    .withEndAction(() -> out.setVisibility(View.INVISIBLE)).start();
            in.setVisibility(View.VISIBLE);
            in.setAlpha(0f);
            in.setTranslationX(dp(t == PALETTE ? -24 : 24));
            in.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(Motion.EASE_OUT).start();
            if (t == ADVANCED) {
                List<View> rise = new ArrayList<>(chips);
                Motion.enter(rise, 40, 16);
            }
        } else {
            out.setVisibility(View.INVISIBLE);
            out.setAlpha(1f);
            in.setVisibility(View.VISIBLE);
            in.setAlpha(1f);
            in.setTranslationX(0f);
        }
        showHud();
    }

    int tab() { return tab; }

    Param chosen() { return chosen; }

    void choose(Param p) {
        chips.get(java.util.Arrays.asList(OldstyleStyle.ADVANCED).indexOf(p)).performClick();
    }

    Ruler ruler() { return ruler; }

    PalettePad pad() { return pad; }

    public boolean isOpen() { return open; }

    /** Opens / closes the panel: it grows out of the button on the enter spring and takes the manual row's place. */
    public void setOpen(boolean value, boolean animate) {
        if (open == value) return;
        open = value;
        panel.animate().cancel();
        button.animate().cancel();
        final boolean anim = animate && Motion.enabled(panel);
        coverManual(anim);
        // the panel takes the manual row's place: the controls move down by the row's height while open
        final float drop = value && manual != null ? manual.getHeight() + dp(8) : 0f;
        host.animate().cancel();
        if (anim) host.animate().translationY(drop).setDuration(value ? 420 : 240).setInterpolator(value ? Motion.ENTER : Motion.EASE_OUT).start();
        else host.setTranslationY(drop);
        if (value) {
            panel.setVisibility(View.VISIBLE);
            if (anim) {
                panel.setAlpha(0f);
                panel.setTranslationY(dp(28));
                panel.setScaleX(.96f);
                panel.setScaleY(.96f);
                panel.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(Motion.ENTER).start();
                button.animate().alpha(0f).scaleX(.6f).scaleY(.6f).setDuration(160).withEndAction(() -> button.setVisibility(View.GONE)).start();
            } else {
                panel.setAlpha(1f);
                panel.setTranslationY(0f);
                panel.setScaleX(1f);
                panel.setScaleY(1f);
                button.setVisibility(View.GONE);
            }
        } else {
            button.setVisibility(View.VISIBLE);
            if (anim) {
                panel.animate().alpha(0f).translationY(dp(20)).setDuration(200).setInterpolator(Motion.EASE_OUT)
                        .withEndAction(() -> panel.setVisibility(View.GONE)).start();
                button.setAlpha(0f);
                button.setScaleX(.6f);
                button.setScaleY(.6f);
                button.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(Motion.ENTER).start();
            } else {
                panel.setVisibility(View.GONE);
                button.setAlpha(1f);
                button.setScaleX(1f);
                button.setScaleY(1f);
            }
        }
        showHud();
    }

    /**
     * P630g: the manual row is hidden exactly while the panel is open - by the row's own rule (ExpandingManualPanel.setCovered:
     * the shade down and nothing in its place), not by an alpha set from here that the shade's return or a refresh undid
     * (owner's screenshot: the row showing through the open panel). Called on every change and every refresh ({@link #apply},
     * {@link #show}).
     */
    private void coverManual(boolean animate) {
        if (manual == null) return;
        final boolean covered = on && open;
        manual.setCovered(covered, animate); // idempotent: nothing when the row is already where its rule puts it
        manual.setImportantForAccessibility(covered ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    private void showHud() {
        if (hud == null) return;
        final boolean shown = on && open && !shadeShown && tab == PALETTE;
        if (shown && anchor != null) {
            final int[] a = new int[2], h = new int[2];
            anchor.getLocationInWindow(a);
            ((View) hud.getParent()).getLocationInWindow(h);
            hud.setTranslationY(a[1] - h[1] + dp(14));
        }
        // its own fade (Motion.fade would animate its placement back to 0)
        hud.animate().cancel();
        if (!Motion.enabled(hud)) {
            hud.setVisibility(shown ? View.VISIBLE : View.GONE);
            hud.setAlpha(1f);
        } else if (shown) {
            if (hud.getVisibility() != View.VISIBLE) {
                hud.setAlpha(0f);
                hud.setVisibility(View.VISIBLE);
            }
            hud.animate().alpha(1f).setDuration(220).setInterpolator(Motion.EASE_OUT).withEndAction(null).start();
        } else if (hud.getVisibility() == View.VISIBLE) {
            hud.animate().alpha(0f).setDuration(160).withEndAction(() -> hud.setVisibility(View.GONE)).start();
        }
    }

    /**
     * Enters or leaves the mode's controls: the manual row held open ({@code panel}) and «Про» stepping aside ({@code pro}); the
     * viewfinder's style on / off.
     */
    public void apply(boolean mode, @Nullable com.particlesdevs.photoncamera.circularbarlib.ui.ExpandingManualPanel panel,
                      @Nullable View pro, boolean animate) {
        // P630g: a row this object covered before (another panel instance, or the mode left) is given back
        if (manual != null && manual != panel) manual.setCovered(false, false);
        on = mode;
        manual = panel;
        if (!mode) setOpen(false, false);
        coverManual(false);
        if (panel != null && panel.isExpanded() != mode) panel.setExpanded(mode, animate);
        if (pro != null) {
            pro.setEnabled(!mode);
            if (animate && Motion.enabled(pro)) pro.animate().alpha(mode ? 0f : 1f).setDuration(220).start();
            else pro.setAlpha(mode ? 0f : 1f);
            pro.setImportantForAccessibility(mode ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        }
        bind();
        if (!mode) MainRenderer.setOldstyleAdjust(null);
        show(animate);
    }

    /** With the shade up the controls step away, as the manual row does. */
    public void setShadeShown(boolean shown) {
        if (shadeShown == shown) return;
        shadeShown = shown;
        show(true);
    }

    private void show(boolean animate) {
        coverManual(animate); // P630g: every refresh re-asserts the row's state
        final boolean visible = on && !shadeShown;
        // an alpha fade of its own (Motion.fade would animate the open panel's drop back to 0)
        host.animate().cancel();
        host.setTranslationY(open && manual != null ? manual.getHeight() + dp(8) : 0f);
        if (!animate || !Motion.enabled(host)) {
            host.setVisibility(visible ? View.VISIBLE : View.GONE);
            host.setAlpha(1f);
        } else if (visible) {
            if (host.getVisibility() != View.VISIBLE) {
                host.setAlpha(0f);
                host.setVisibility(View.VISIBLE);
            }
            host.animate().alpha(1f).setDuration(240).setInterpolator(Motion.EASE_OUT).withEndAction(null).start();
        } else if (host.getVisibility() == View.VISIBLE) {
            host.animate().alpha(0f).setDuration(180).withEndAction(() -> host.setVisibility(View.GONE)).start();
        }
        showHud();
    }

    public boolean on() {
        return on;
    }

    /**
     * The palette pad: a rounded, see-through gradient (left a cool grey, right a warm salmon colour; light at the top, dark at the
     * bottom) with a dot grid; under the finger the dots grow and brighten, at rest a dotted cross marks the point. x = «Цвет»,
     * y = «Тон» (up +), -100..100; a tick of haptics every 10, a double tap back to the centre.
     */
    static final class PalettePad extends View {
        interface Listener { void onPoint(int colour, int tone); }

        private final Listener listener;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), shade = new Paint(Paint.ANTI_ALIAS_FLAG),
                dots = new Paint(Paint.ANTI_ALIAS_FLAG), cross = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final Path clip = new Path();
        private float px = .5f, py = .5f;   // the point, 0..1 (y down)
        private boolean dragging;
        private float touchX = -1, touchY = -1, glow;
        private long lastUp;
        private int lastTickC = Integer.MIN_VALUE, lastTickT = Integer.MIN_VALUE;
        private android.animation.ValueAnimator glowAnim;

        PalettePad(Context c, Listener l) {
            super(c);
            listener = l;
            dots.setColor(0xFFFFFFFF);
            cross.setColor(0xCCFFFFFF);
            cross.setStyle(Paint.Style.STROKE);
            ring.setColor(0xFFFFFFFF);
            ring.setStyle(Paint.Style.STROKE);
            setContentDescription(Lang.t(c, "Палитра: тон и цвет", "Palette: tone and color"));
        }

        boolean isDragging() { return dragging; }

        void setPoint(int colour, int tone, boolean redraw) {
            if (dragging) return;
            px = (colour + 100) / 200f;
            py = (100 - tone) / 200f;
            if (redraw) invalidate();
        }

        /** The marker's inset from the pad's edge: -100 / +100 sit there, whole. */
        private float inset() { return d(14); }

        private float toX(float u) { return inset() + u * (getWidth() - 2 * inset()); }

        private float toY(float v) { return inset() + v * (getHeight() - 2 * inset()); }

        /** Tests: a press at x, y (0..1) without lifting. */
        void pressAt(float x, float y) {
            dragging = true;
            glow = 1f;
            move(toX(x), toY(y));
        }

        void release() {
            dragging = false;
            touchX = touchY = -1;
            glow = 0f;
            invalidate();
        }

        private float d(float v) { return v * getResources().getDisplayMetrics().density; }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            box.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(box, d(22), d(22), Path.Direction.CW);
            fill.setShader(new LinearGradient(0, 0, w, 0, new int[]{0xE08A939B, 0xE0B3A8A6, 0xE0F2907F}, null, Shader.TileMode.CLAMP));
            shade.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x40FFFFFF, 0x00000000, 0x66000000}, null, Shader.TileMode.CLAMP));
        }

        @Override protected void onDraw(Canvas c) {
            final int save = c.save();
            c.clipPath(clip);
            c.drawRect(box, fill);
            c.drawRect(box, shade);
            final float step = d(11), r0 = d(1.1f), reach = d(48);
            final float fx = dragging ? touchX : -1e6f, fy = dragging ? touchY : -1e6f;
            for (float y = step / 2; y < box.height(); y += step) {
                for (float x = step / 2; x < box.width(); x += step) {
                    final float dist = (float) Math.hypot(x - fx, y - fy);
                    final float k = dist < reach ? (1f - dist / reach) * glow : 0f;
                    dots.setAlpha(Math.round(255 * (0.42f + 0.58f * k)));
                    c.drawCircle(x, y, r0 * (1f + 1.8f * k * k), dots);
                }
            }
            final float x = toX(px), y = toY(py);
            if (!dragging) {
                cross.setStrokeWidth(d(1.2f));
                cross.setPathEffect(new DashPathEffect(new float[]{d(2), d(3)}, 0));
                c.drawLine(0, y, box.width(), y, cross);
                c.drawLine(x, 0, x, box.height(), cross);
                ring.setStrokeWidth(d(1.6f));
                c.drawCircle(x, y, d(6), ring);
                ring.setStyle(Paint.Style.FILL);
                c.drawCircle(x, y, d(2.2f), ring);
                ring.setStyle(Paint.Style.STROKE);
            } else {
                ring.setStyle(Paint.Style.FILL);
                c.drawCircle(x, y, d(7), ring);
                ring.setStyle(Paint.Style.STROKE);
            }
            c.restoreToCount(save);
        }

        private void move(float x, float y) {
            touchX = Math.max(0, Math.min(getWidth(), x));
            touchY = Math.max(0, Math.min(getHeight(), y));
            final float iw = Math.max(1f, getWidth() - 2 * inset()), ih = Math.max(1f, getHeight() - 2 * inset());
            px = Math.max(0f, Math.min(1f, (touchX - inset()) / iw));
            py = Math.max(0f, Math.min(1f, (touchY - inset()) / ih));
            final int colour = Math.round(px * 200 - 100), tone = Math.round(100 - py * 200);
            if (colour / 10 != lastTickC || tone / 10 != lastTickT) {
                if (lastTickC != Integer.MIN_VALUE) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                lastTickC = colour / 10;
                lastTickT = tone / 10;
            }
            listener.onPoint(colour, tone);
            invalidate();
        }

        private void glowTo(float to) {
            if (glowAnim != null) glowAnim.cancel();
            glowAnim = android.animation.ValueAnimator.ofFloat(glow, to);
            glowAnim.setDuration(to > 0 ? 160 : 260);
            glowAnim.addUpdateListener(a -> { glow = (float) a.getAnimatedValue(); invalidate(); });
            glowAnim.start();
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    dragging = true;
                    glowTo(1f);
                    move(e.getX(), e.getY());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    move(e.getX(), e.getY());
                    return true;
                case MotionEvent.ACTION_UP:
                    if (e.getEventTime() - lastUp < 300) {
                        dragging = false;
                        listener.onPoint(0, 0);
                    }
                    lastUp = e.getEventTime();
                    // fall through
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    glowTo(0f);
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(e);
        }
    }

    /**
     * The centred tick ruler (the chosen value under the fixed accent mark; -100..100 with a long tick every 10, or the four steps
     * off / low / medium / high wider apart), the edges fading; a drag moves the scale under it (a tick of haptics per step), a
     * double tap goes back to 0.
     */
    static final class Ruler extends View {
        interface Listener { void accept(int v); }

        private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG), mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float position;
        private int value, min = -100, max = 100;
        private boolean discrete;
        private float downX, downPos;
        private long lastUp;
        private Listener onValue;
        private android.animation.ValueAnimator settle;

        Ruler(Context c, int accent) {
            super(c);
            tick.setColor(UiTokens.TEXT);
            tick.setStrokeCap(Paint.Cap.ROUND);
            mark.setColor(accent);
            mark.setStrokeCap(Paint.Cap.ROUND);
            setContentDescription(Lang.t(c, "Шкала значения", "Value ruler"));
        }

        void setOnValue(Listener l) { onValue = l; }

        int value() { return value; }

        void configure(Param p, int v, boolean animate) {
            min = p.min();
            max = p.max();
            discrete = p.discrete;
            setValue(v, animate);
        }

        float step() {
            return getResources().getDisplayMetrics().density * (discrete ? 44f : 7f);
        }

        void setValue(int v, boolean animate) {
            v = Math.max(min, Math.min(max, v));
            value = v;
            if (settle != null) settle.cancel();
            if (!animate) {
                position = v;
                invalidate();
                return;
            }
            settle = android.animation.ValueAnimator.ofFloat(position, v);
            settle.setDuration(180);
            settle.setInterpolator(Motion.EASE_OUT);
            settle.addUpdateListener(a -> { position = (float) a.getAnimatedValue(); invalidate(); });
            settle.start();
        }

        @Override protected void onDraw(Canvas c) {
            final float w = getWidth(), h = getHeight(), mid = w / 2f, s = step(), d = getResources().getDisplayMetrics().density;
            tick.setStrokeWidth(Math.max(1f, 1.5f * d));
            for (int i = min; i <= max; i++) {
                final float x = mid + (i - position) * s;
                if (x < -s || x > w + s) continue;
                final float edge = Math.min(1f, Math.min(x, w - x) / (w * .22f));
                final boolean major = discrete || i % 10 == 0;
                tick.setAlpha(Math.round(255 * Math.max(0f, edge) * (major ? 1f : .65f)));
                final float len = (major ? .52f : .32f) * h;
                c.drawLine(x, (h - len) / 2f, x, (h + len) / 2f, tick);
            }
            mark.setStrokeWidth(3f * d);
            c.drawLine(mid, h * .12f, mid, h * .88f, mark);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (settle != null) settle.cancel();
                    downX = e.getX();
                    downPos = position;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    final float p = Math.max(min, Math.min(max, downPos - (e.getX() - downX) / step()));
                    position = p;
                    final int v = Math.round(p);
                    if (v != value) {
                        value = v;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        if (onValue != null) onValue.accept(v);
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    final long now = e.getEventTime();
                    if (Math.abs(e.getX() - downX) < step() / 2 && now - lastUp < 300) {
                        value = 0;
                        if (onValue != null) onValue.accept(0);
                    }
                    lastUp = now;
                    setValue(value, true);
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    setValue(value, true);
                    return true;
            }
            return super.onTouchEvent(e);
        }
    }
}
