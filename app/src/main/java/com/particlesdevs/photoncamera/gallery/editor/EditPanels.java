package com.particlesdevs.photoncamera.gallery.editor;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.gallery.editor.EditState.Param;
import com.particlesdevs.photoncamera.gallery.ui.GalleryUi;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * P351: the tool panels of the editor's tabs, built in code in the gallery's look (GalleryUi): slider rows bound to the
 * state's {@link Param}s, chips for sub-sections and choices, the curve editor. {@link #refresh} puts the state's values back
 * into every bound control after undo / redo / reset / a style.
 * P579 (owner, 2026-11-09: «Не забудь что у нас есть галерея и в ней надо стиль также обновить»): the sliders are the shared slider
 * rows (EditSlider), a choice of a few (the curve's channel, the colour sections, the grading wheels, colour / B&W) the shared
 * segmented control, the long lists (aspect ratios, styles) capsule chips - the accent with dark ink when chosen, #2C2C2E otherwise.
 */
final class EditPanels {
    interface Host {
        EditState state();

        /** The state changed; {@code commit}: one undo step (a slider let go, a button). */
        void changed(boolean commit);

        EditSource source();

        /** Geometry buttons of the crop tab. */
        void rotate();

        void flip();

        void resetCrop();

        void aspect(float ratio);

        void straighten(float degrees, boolean commit);

        /** A style picked (-1: none) / its strength changed. */
        void style(int index, float amount, boolean commit);

        /** P619: the photo's picture for the «LUT» tab's cards (the main pass's, without the look), delivered on the UI thread. */
        void lookThumbnail(ThumbSink sink);
    }

    /** P619: a picture of the photo, ARGB w x h. */
    interface ThumbSink {
        void base(int[] argb, int w, int h);
    }

    private final Context c;
    private final Host host;
    private final List<Runnable> refreshers = new ArrayList<>();
    int hslBand = 0, gradeWheel = 0, curveChannel = EditState.CURVE_MASTER;
    int styleIndex = -1;
    float styleAmount = 1f;
    /** P619: the «LUT» tab's section (0 LUT, 1 film) and its cards' pictures. */
    int lookSection = 0;
    final EditLookThumbs thumbs = new EditLookThumbs();

    EditPanels(Context c, Host host) {
        this.c = c;
        this.host = host;
    }

    /** P633: a refresher of a panel built elsewhere (EditWatermarkPanel), for this tab's life. */
    void refresher(Runnable r) {
        refreshers.add(r);
    }

    void refresh() {
        // P619: a refresher may build controls (the film's, for another stock) and add theirs
        for (Runnable r : new ArrayList<>(refreshers)) r.run();
    }

    // ---------------------------------------------------------------- pieces

    static String label(Param p) {
        switch (p) {
            case EXPOSURE: return Lang.t("Экспозиция", "Exposure");
            case CONTRAST: return Lang.t("Контраст", "Contrast");
            case HIGHLIGHTS: return Lang.t("Света", "Highlights");
            case SHADOWS: return Lang.t("Тени", "Shadows");
            case WHITES: return Lang.t("Белые", "Whites");
            case BLACKS: return Lang.t("Чёрные", "Blacks");
            case TEMPERATURE: return Lang.t("Температура", "Temperature");
            case TINT: return Lang.t("Оттенок", "Tint");
            case VIBRANCE: return Lang.t("Сочность", "Vibrance");
            case SATURATION: return Lang.t("Насыщенность", "Saturation");
            case TEXTURE: return Lang.t("Текстура", "Texture");
            case CLARITY: return Lang.t("Чёткость", "Clarity");
            case DEHAZE: return Lang.t("Дымка", "Dehaze");
            case VIGNETTE: return Lang.t("Виньетка", "Vignette");
            case VIGNETTE_MID: return Lang.t("Середина виньетки", "Vignette midpoint");
            case GRAIN: return Lang.t("Зерно", "Grain");
            case GRAIN_SIZE: return Lang.t("Размер зерна", "Grain size");
            case SHARPEN: return Lang.t("Резкость", "Sharpening");
            case SHARPEN_RADIUS: return Lang.t("Радиус", "Radius");
            case LUMA_NR: return Lang.t("Шумоподавление яркости", "Luminance noise reduction");
            case COLOR_NR: return Lang.t("Шумоподавление цвета", "Colour noise reduction");
            case CURVE_SHADOWS: return Lang.t("Тени", "Shadows");
            case CURVE_DARKS: return Lang.t("Тёмные", "Darks");
            case CURVE_LIGHTS: return Lang.t("Светлые", "Lights");
            case CURVE_HIGHLIGHTS: return Lang.t("Блики", "Highlights");
            case CURVE_SPLIT_1: return Lang.t("Граница теней", "Shadows split");
            case CURVE_SPLIT_2: return Lang.t("Граница середины", "Midtones split");
            case CURVE_SPLIT_3: return Lang.t("Граница светов", "Highlights split");
            case GRADE_BLEND: return Lang.t("Смешивание", "Blending");
            case GRADE_BALANCE: return Lang.t("Баланс", "Balance");
            // P619: the camera tray's names (LutTray.filmParams)
            case LUT_AMOUNT: case FILM_AMOUNT: return Lang.t("Сила", "Strength");
            case FILM_GRAIN: return Lang.t("Зерно", "Grain");
            case FILM_EXPOSURE: return Lang.t("Экспозиция", "Exposure");
            case FILM_CONTRAST: return Lang.t("Контраст", "Contrast");
            case FILM_DIR: return Lang.t("Цветоразделение (DIR)", "Colour separation (DIR)");
            case FILM_PRINT_EV: return Lang.t("Экспозиция печати", "Print exposure");
            default: return p.key;
        }
    }

    static String bandName(int b) {
        switch (b) {
            case 0: return Lang.t("Красный", "Red");
            case 1: return Lang.t("Оранжевый", "Orange");
            case 2: return Lang.t("Жёлтый", "Yellow");
            case 3: return Lang.t("Зелёный", "Green");
            case 4: return Lang.t("Голубой", "Aqua");
            case 5: return Lang.t("Синий", "Blue");
            case 6: return Lang.t("Фиолетовый", "Purple");
            default: return Lang.t("Пурпурный", "Magenta");
        }
    }

    static final int[] BAND_COLOURS = {0xFFE5484D, 0xFFF08A24, 0xFFE8C531, 0xFF3FB950, 0xFF2EC4C4, 0xFF3B7BF6, 0xFF8E5BE8, 0xFFD94BC4};
    static final int[] HUE_GRADIENT = {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};

    EditSlider slider(Param p, String label) {
        EditSlider s = new EditSlider(c).setup(label, p.min, p.max, p.def, p.step(), p.bipolar());
        s.set(host.state().get(p), false);
        s.listener(new EditSlider.Listener() {
            @Override
            public void changed(float value) {
                host.state().set(p, value);
                host.changed(false);
            }

            @Override
            public void committed() {
                host.state().set(p, s.value());
                host.changed(true);
            }
        });
        if (p == Param.EXPOSURE) s.format(v -> (v > 0 ? "+" : "") + GalleryUi.decimal(v, 2) + " EV");
        if (p == Param.FILM_EXPOSURE || p == Param.FILM_PRINT_EV)
            s.format(v -> (v > 0 ? "+" : "") + GalleryUi.decimal(v, p == Param.FILM_EXPOSURE ? 1 : 2) + " EV");
        if (p == Param.LUT_AMOUNT || p == Param.FILM_AMOUNT || p == Param.FILM_GRAIN || p == Param.FILM_DIR) s.format(v -> Math.round(v) + " %");
        if (p == Param.TEMPERATURE || p == Param.TINT) s.format(v -> wbText(p, v));
        if (p == Param.GRADE_SH_HUE || p == Param.GRADE_MID_HUE || p == Param.GRADE_HI_HUE || p == Param.GRADE_ALL_HUE) {
            s.gradient(HUE_GRADIENT);
            s.format(v -> Math.round(v) + "°");
        }
        refreshers.add(() -> s.set(host.state().get(p), false));
        return s;
    }

    /** A RAW's white balance in K / tint from «Как снято»; a photo's relative numbers. */
    private String wbText(Param p, float v) {
        EditSource src = host.source();
        if (src != null && src.colour instanceof EditSource.RawColour) {
            double[] w = ((EditSource.RawColour) src.colour).white(host.state());
            if (w != null) {
                String asShot = host.state().get(Param.TEMPERATURE) == 0f && host.state().get(Param.TINT) == 0f
                        ? Lang.t("Как снято · ", "As shot · ") : "";
                if (p == Param.TEMPERATURE) return asShot + Math.round(w[0] / 50.0) * 50 + " K";
                return (v > 0 ? "+" : "") + Math.round(v);
            }
        }
        int i = Math.round(v);
        return i > 0 ? "+" + i : String.valueOf(i);
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    TextView chip(String text, boolean on, View.OnClickListener click) {
        TextView t = GalleryUi.text(c, text, 14, on ? GalleryUi.INK : GalleryUi.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(GalleryUi.dp(c, 36));
        t.setPadding(GalleryUi.dp(c, 14), 0, GalleryUi.dp(c, 14), 0);
        GalleryUi.paintChip(t, on);
        t.setClickable(true);
        t.setFocusable(true);
        t.setOnClickListener(click);
        com.particlesdevs.photoncamera.ui.Motion.press(t);
        return t;
    }

    /** P579: a choice of a few as the shared segmented control, 18dp from the panel's sides; {@code pick} hears a tap's index. */
    View segments(String[] names, int on, java.util.function.IntConsumer pick) {
        com.particlesdevs.photoncamera.ui.controls.IosSegmented seg = GalleryUi.segmented(c, names, on, pick);
        LinearLayout box = new LinearLayout(c);
        box.setPadding(GalleryUi.dp(c, 18), GalleryUi.dp(c, 8), GalleryUi.dp(c, 18), GalleryUi.dp(c, 6));
        box.addView(seg, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    /** A horizontally scrolling row of chips. */
    HorizontalScrollView chipRow(List<TextView> chips) {
        HorizontalScrollView sv = new HorizontalScrollView(c);
        sv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(GalleryUi.dp(c, 18), GalleryUi.dp(c, 8), GalleryUi.dp(c, 18), GalleryUi.dp(c, 6));
        for (TextView t : chips) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(GalleryUi.dp(c, 8));
            row.addView(t, lp);
        }
        sv.addView(row);
        return sv;
    }

    TextView section(String text) {
        TextView t = GalleryUi.sectionLabel(c, text);
        t.setPadding(GalleryUi.dp(c, 18), GalleryUi.dp(c, 12), GalleryUi.dp(c, 18), GalleryUi.dp(c, 2));
        return t;
    }

    // ---------------------------------------------------------------- tabs

    View light() {
        LinearLayout l = column(c);
        for (Param p : new Param[]{Param.EXPOSURE, Param.CONTRAST, Param.HIGHLIGHTS, Param.SHADOWS, Param.WHITES, Param.BLACKS})
            l.addView(slider(p, label(p)));
        l.addView(section(Lang.t("Кривая", "Curve")));
        LinearLayout curveBox = column(c);
        // P579: the channel on the shared segmented control, built once (a pick rebuilds the curve under it, the thumb glides)
        l.addView(segments(new String[]{"RGB", Lang.t("Красный", "Red"), Lang.t("Зелёный", "Green"), Lang.t("Синий", "Blue")}, curveChannel, ch -> {
            curveChannel = ch;
            buildCurve(curveBox);
        }));
        l.addView(curveBox);
        buildCurve(curveBox);
        for (Param p : new Param[]{Param.CURVE_HIGHLIGHTS, Param.CURVE_LIGHTS, Param.CURVE_DARKS, Param.CURVE_SHADOWS,
                Param.CURVE_SPLIT_1, Param.CURVE_SPLIT_2, Param.CURVE_SPLIT_3})
            l.addView(slider(p, label(p)));
        return l;
    }

    private void buildCurve(LinearLayout box) {
        box.removeAllViews();
        int[] colours = {0xFFFFFFFF, 0xFFFF5A5F, 0xFF4CD964, 0xFF4A8CFF};
        CurveView cv = new CurveView(c);
        cv.set(host.state().curves[curveChannel], colours[curveChannel]);
        cv.setListener((pts, done) -> {
            host.state().curves[curveChannel] = pts;
            host.changed(done);
        });
        refreshers.add(() -> cv.set(host.state().curves[curveChannel], colours[curveChannel]));
        TextView hint = GalleryUi.text(c, Lang.t("Касание — новая точка, двойное касание по точке — убрать её", "Tap adds a point, double tap on a point removes it"),
                12, GalleryUi.MUTED);
        hint.setPadding(GalleryUi.dp(c, 18), 0, GalleryUi.dp(c, 18), GalleryUi.dp(c, 4));
        box.addView(cv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(hint);
    }

    int colourSection = 0;

    View colour() {
        LinearLayout l = column(c);
        LinearLayout body = column(c);
        String[] names = {Lang.t("Основное", "Basic"), Lang.t("Смешение", "Mixer"), Lang.t("Цветокоррекция", "Grading"), Lang.t("Ч/Б", "B&W")};
        LinearLayout tabs = column(c);
        l.addView(tabs);
        l.addView(body);
        buildColourSections(tabs, body, names);
        return l;
    }

    private void buildColourSections(LinearLayout tabs, LinearLayout body, String[] names) {
        // P579: the sections on the shared segmented control, built once; a pick rebuilds the body under it
        if (tabs.getChildCount() == 0) tabs.addView(segments(names, colourSection, k -> {
            colourSection = k;
            buildColourSections(tabs, body, names);
        }));
        body.removeAllViews();
        switch (colourSection) {
            case 1: mixer(body); break;
            case 2: grading(body); break;
            case 3: mono(body); break;
            default:
                for (Param p : new Param[]{Param.TEMPERATURE, Param.TINT, Param.VIBRANCE, Param.SATURATION}) {
                    EditSlider s = slider(p, label(p));
                    if (p == Param.TEMPERATURE) s.gradient(new int[]{0xFF4A8CFF, 0xFFDDDDDD, 0xFFFFB13B});
                    if (p == Param.TINT) s.gradient(new int[]{0xFF4CD964, 0xFFDDDDDD, 0xFFE24BD8});
                    body.addView(s);
                }
        }
    }

    private void bandDots(LinearLayout body, int selected, java.util.function.IntConsumer pick) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(GalleryUi.dp(c, 12), GalleryUi.dp(c, 8), GalleryUi.dp(c, 12), GalleryUi.dp(c, 4));
        for (int b = 0; b < 8; b++) {
            final int band = b;
            View dot = new View(c);
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            d.setColor(BAND_COLOURS[b]);
            if (b == selected) d.setStroke(GalleryUi.dp(c, 3), Color.WHITE);
            dot.setBackground(d);
            dot.setContentDescription(bandName(b));
            dot.setClickable(true);
            dot.setFocusable(true);
            dot.setOnClickListener(v -> pick.accept(band));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(GalleryUi.dp(c, 30), GalleryUi.dp(c, 30));
            lp.leftMargin = lp.rightMargin = GalleryUi.dp(c, 5);
            row.addView(dot, lp);
        }
        body.addView(row);
    }

    private void mixer(LinearLayout body) {
        bandDots(body, hslBand, b -> {
            hslBand = b;
            body.removeAllViews();
            mixer(body);
        });
        String n = bandName(hslBand);
        body.addView(slider(Param.hue(hslBand), Lang.t("Тон", "Hue") + " · " + n));
        body.addView(slider(Param.sat(hslBand), Lang.t("Насыщенность", "Saturation") + " · " + n));
        body.addView(slider(Param.lum(hslBand), Lang.t("Яркость", "Luminance") + " · " + n));
    }

    private void grading(LinearLayout body) {
        String[] names = {Lang.t("Тени", "Shadows"), Lang.t("Средние", "Midtones"), Lang.t("Света", "Highlights"), Lang.t("Общий", "Global")};
        LinearLayout wheel = column(c);
        body.addView(segments(names, gradeWheel, k -> {
            gradeWheel = k;
            gradingSliders(wheel);
        }));
        body.addView(wheel);
        gradingSliders(wheel);
    }

    private void gradingSliders(LinearLayout wheel) {
        wheel.removeAllViews();
        wheel.addView(slider(Param.grade(gradeWheel, 0), Lang.t("Тон", "Hue")));
        wheel.addView(slider(Param.grade(gradeWheel, 1), Lang.t("Насыщенность", "Saturation")));
        wheel.addView(slider(Param.grade(gradeWheel, 2), Lang.t("Яркость", "Luminance")));
        wheel.addView(slider(Param.GRADE_BLEND, label(Param.GRADE_BLEND)));
        wheel.addView(slider(Param.GRADE_BALANCE, label(Param.GRADE_BALANCE)));
    }

    private void mono(LinearLayout body) {
        LinearLayout bands = column(c);
        body.addView(segments(new String[]{Lang.t("Цвет", "Colour"), Lang.t("Ч/Б", "B&W")}, host.state().mono() ? 1 : 0, k -> {
            host.state().set(Param.MONO, k == 1 ? 1f : 0f);
            host.changed(true);
            monoBands(bands);
        }));
        body.addView(bands);
        monoBands(bands);
    }

    private void monoBands(LinearLayout bands) {
        bands.removeAllViews();
        if (!host.state().mono()) return;
        for (int b = 0; b < 8; b++) {
            EditSlider s = slider(Param.bw(b), bandName(b));
            s.gradient(new int[]{0xFF202020, BAND_COLOURS[b], 0xFFF0F0F0});
            bands.addView(s);
        }
    }

    View effects() {
        LinearLayout l = column(c);
        for (Param p : new Param[]{Param.TEXTURE, Param.CLARITY, Param.DEHAZE, Param.VIGNETTE, Param.VIGNETTE_MID, Param.GRAIN, Param.GRAIN_SIZE})
            l.addView(slider(p, label(p)));
        return l;
    }

    View detail() {
        LinearLayout l = column(c);
        for (Param p : new Param[]{Param.SHARPEN, Param.SHARPEN_RADIUS, Param.LUMA_NR, Param.COLOR_NR}) l.addView(slider(p, label(p)));
        TextView t = GalleryUi.text(c, Lang.t("Резкость и шумоподавление видны в полном размере; на экране — уменьшенный снимок",
                "Sharpening and noise reduction show at full size; the screen shows the photo scaled down"), 12, GalleryUi.MUTED);
        t.setPadding(GalleryUi.dp(c, 18), GalleryUi.dp(c, 4), GalleryUi.dp(c, 18), GalleryUi.dp(c, 8));
        l.addView(t);
        return l;
    }

    View crop() {
        LinearLayout l = column(c);
        LinearLayout chipsBox = column(c);
        l.addView(chipsBox);
        buildAspect(chipsBox);
        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(GalleryUi.dp(c, 8), 0, GalleryUi.dp(c, 8), 0);
        View rot = GalleryUi.actionButton(c, com.particlesdevs.photoncamera.R.drawable.ui_ic_rotate, Lang.t("Повернуть", "Rotate"), false, v -> host.rotate());
        View flip = GalleryUi.actionButton(c, com.particlesdevs.photoncamera.R.drawable.ui_ic_mirror, Lang.t("Отразить", "Flip"), false, v -> host.flip());
        View reset = GalleryUi.actionButton(c, com.particlesdevs.photoncamera.R.drawable.ui_ic_backup, Lang.t("Сбросить кадр", "Reset crop"), false, v -> host.resetCrop());
        for (View b : new View[]{rot, flip, reset}) buttons.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        l.addView(buttons);
        EditSlider s = new EditSlider(c).setup(Lang.t("Выпрямить", "Straighten"), -EditState.MAX_STRAIGHTEN, EditState.MAX_STRAIGHTEN, 0f, 0.1f, true);
        s.format(v -> (v > 0 ? "+" : "") + GalleryUi.decimal(v, 1) + "°");
        s.set(host.state().straighten, false);
        s.listener(new EditSlider.Listener() {
            @Override
            public void changed(float value) {
                host.straighten(value, false);
            }

            @Override
            public void committed() {
                host.straighten(s.value(), true);
            }
        });
        refreshers.add(() -> s.set(host.state().straighten, false));
        l.addView(s);
        refreshers.add(() -> buildAspect(chipsBox));
        return l;
    }

    private void buildAspect(LinearLayout box) {
        box.removeAllViews();
        List<TextView> chips = new ArrayList<>();
        int on = CropMath.presetIndex(host.state().aspect);
        for (int i = 0; i < CropMath.PRESETS.length; i++) {
            final float ratio = CropMath.PRESETS[i];
            String name = i == 0 ? Lang.t("Свободный", "Free") : i == 1 ? Lang.t("Оригинал", "Original") : CropMath.PRESET_RATIOS[i];
            chips.add(chip(name, i == on, v -> {
                host.aspect(ratio);
                buildAspect(box);
            }));
        }
        box.addView(chipRow(chips));
    }

    View styles() {
        LinearLayout l = column(c);
        LinearLayout chipsBox = column(c);
        l.addView(chipsBox);
        buildStyles(chipsBox);
        EditSlider s = new EditSlider(c).setup(Lang.t("Сила", "Amount"), 0, 100, 100, 1, false);
        s.format(v -> Math.round(v) + " %");
        s.set(styleAmount * 100f, false);
        s.listener(new EditSlider.Listener() {
            @Override
            public void changed(float value) {
                styleAmount = value / 100f;
                if (styleIndex >= 0) host.style(styleIndex, styleAmount, false);
            }

            @Override
            public void committed() {
                if (styleIndex >= 0) host.style(styleIndex, styleAmount, true);
            }
        });
        l.addView(s);
        TextView t = GalleryUi.text(c, Lang.t("Стиль ложится поверх ваших настроек; кадр не меняется",
                "A style goes on top of your settings; the crop stays"), 12, GalleryUi.MUTED);
        t.setPadding(GalleryUi.dp(c, 18), GalleryUi.dp(c, 4), GalleryUi.dp(c, 18), GalleryUi.dp(c, 8));
        l.addView(t);
        return l;
    }

    private void buildStyles(LinearLayout box) {
        box.removeAllViews();
        List<TextView> chips = new ArrayList<>();
        chips.add(chip(Lang.t("Нет", "None"), styleIndex < 0, v -> {
            host.style(-1, 0f, true);
            styleIndex = -1;
            buildStyles(box);
        }));
        for (int i = 0; i < EditPresets.STYLES.length; i++) {
            final int k = i;
            chips.add(chip(EditPresets.name(i), i == styleIndex, v -> {
                styleIndex = k;
                host.style(k, styleAmount, true);
                buildStyles(box);
            }));
        }
        box.addView(chipRow(chips));
    }

    static String percent(float v) {
        return String.format(Locale.ROOT, "%d %%", Math.round(v * 100));
    }

    // ---------------------------------------------------------------- P619: «LUT»

    /** P619: a LUT's name as the camera's tray gives it (LutTray.name): «Нет», our look's, the file's without «.cube». */
    static String lutName(Context c, String value) {
        if (value == null || value.isEmpty()) return Lang.t(c, "Нет", "None");
        String look = com.particlesdevs.photoncamera.processing.color.FilmLooks.label(c, value);
        if (look != null) return look;
        return value.toLowerCase(Locale.ROOT).endsWith(".cube") ? value.substring(0, value.length() - 5) : value;
    }

    /** The cards of the shown section: their pictures, bound again when the photo's picture arrives. */
    private final List<Object[]> lookCards = new ArrayList<>();

    /**
     * P619 (owner, 2026-11-11: «Добавь возможность в нашем редакторе в галерее применять луты из камеры и симуляции плёнок»): the
     * «LUT» tab - the camera's LUTs (the tray's list: «Нет», our five looks, every .cube of Download/SCAMERA/LUT, in the user's order)
     * and its film stocks, as cards with the photo through each, under the shared segmented control; the strength, and for a film
     * its print, format, grain, exposure, contrast, couplers and print exposure (the tray's controls, the same ranges). Applied on
     * the finished colour (EditLook), so the hint says how a film differs from one shot with it.
     */
    View look() {
        LinearLayout l = column(c);
        LinearLayout body = column(c);
        l.addView(segments(new String[]{"LUT", Lang.t("Плёнка", "Film")}, lookSection, k -> {
            lookSection = k;
            buildLook(body);
        }));
        l.addView(body);
        buildLook(body);
        host.lookThumbnail((px, w, h) -> {
            thumbs.setBase(px, w, h);
            bindLookCards();
        });
        return l;
    }

    private void buildLook(LinearLayout body) {
        body.removeAllViews();
        lookCards.clear();
        if (lookSection == 0) lutPage(body);
        else filmPage(body);
    }

    private void bindLookCards() {
        for (Object[] card : lookCards) {
            android.widget.ImageView img = (android.widget.ImageView) card[0];
            if ((Boolean) card[1]) thumbs.film(img, (String) card[2]);
            else thumbs.lut(img, (String) card[2]);
        }
    }

    private void lutPage(LinearLayout body) {
        final List<String> values = com.particlesdevs.photoncamera.settings.ItemOrder.luts(c);
        final LinearLayout row = cardRow(body);
        for (String v : values) {
            LinearLayout card = lookCard(lutName(c, v), v.equals(host.state().lut));
            card.setContentDescription(lutName(c, v));
            card.setOnClickListener(x -> {
                EditState st = host.state();
                st.lut = v;
                if (!v.isEmpty() && st.get(Param.LUT_AMOUNT) <= 0f) st.set(Param.LUT_AMOUNT, 100f);
                host.changed(true);
                selectCards(row, values.indexOf(v));
            });
            android.widget.ImageView img = thumb(card);
            lookCards.add(new Object[]{img, false, v});
            thumbs.lut(img, v);
            row.addView(card, cardParams(row));
        }
        refreshers.add(() -> selectCards(row, values.indexOf(host.state().lut)));
        centreCard(row, values.indexOf(host.state().lut));
        body.addView(slider(Param.LUT_AMOUNT, label(Param.LUT_AMOUNT)));
        body.addView(hint(Lang.t("Те же LUT, что в камере: свои и файлы .cube из Download/SCAMERA/LUT. Ложатся на готовый цвет снимка, после ваших настроек",
                "The camera's LUTs: ours and the .cube files of Download/SCAMERA/LUT. They go on the photo's finished colour, after your settings")));
    }

    private void filmPage(LinearLayout body) {
        final List<String> ids = new ArrayList<>();
        ids.add("");
        for (com.particlesdevs.photoncamera.processing.color.FilmSim.Stock st : com.particlesdevs.photoncamera.processing.color.FilmSim.STOCKS)
            ids.add(st.id);
        final LinearLayout row = cardRow(body);
        final LinearLayout params = column(c);
        for (String id : ids) {
            com.particlesdevs.photoncamera.processing.color.FilmSim.Stock st = id.isEmpty() ? null
                    : com.particlesdevs.photoncamera.processing.color.FilmSim.stock(id);
            String name = st == null ? Lang.t("Без плёнки", "No film") : st.name;
            LinearLayout card = lookCard(name, id.equals(host.state().film));
            card.setContentDescription(st == null ? name : st.brand + " " + st.name);
            card.setOnClickListener(x -> {
                EditState s2 = host.state();
                s2.film = id;
                if (!id.isEmpty() && s2.get(Param.FILM_AMOUNT) <= 0f) s2.set(Param.FILM_AMOUNT, 100f);
                host.changed(true); // the refresher below shows the stock's controls
            });
            android.widget.ImageView img = thumb(card);
            lookCards.add(new Object[]{img, true, id});
            thumbs.film(img, id);
            row.addView(card, cardParams(row));
        }
        centreCard(row, ids.indexOf(host.state().film));
        body.addView(params);
        refreshers.add(() -> {
            selectCards(row, ids.indexOf(host.state().film));
            if (!host.state().film.equals(filmBuilt)) filmParams(params);
        });
        filmParams(params);
        body.addView(hint(Lang.t("Цвет плёнки — как в видоискателе камеры: от готового цвета снимка, а не от света сцены, как при съёмке с плёнкой. Ореол, мягкость и диффузия есть только при съёмке",
                "The film's colour as the camera's viewfinder shows it: from the photo's finished colour, not the scene light a shot with the film gets. Halation, softness and diffusion only when shooting")));
    }

    /** The chosen stock's controls (none without a film): only what acts on it, as the tray (LutTray.filmParams). */
    /** The stock whose controls are shown. */
    @Nullable private String filmBuilt;

    private void filmParams(LinearLayout params) {
        params.removeAllViews();
        filmBuilt = host.state().film;
        final com.particlesdevs.photoncamera.processing.color.FilmSim.Stock st = host.state().film.isEmpty() ? null
                : com.particlesdevs.photoncamera.processing.color.FilmSim.stock(host.state().film);
        if (st == null) return;
        params.addView(slider(Param.FILM_AMOUNT, label(Param.FILM_AMOUNT)));
        if (!st.reversal()) {
            params.addView(section(Lang.t("Печать", "Print")));
            LinearLayout prints = column(c);
            params.addView(prints);
            buildPrints(prints, st);
            refreshers.add(() -> buildPrints(prints, st));
        }
        params.addView(section(Lang.t("Формат", "Format")));
        LinearLayout formats = column(c);
        params.addView(formats);
        buildFormats(formats);
        refreshers.add(() -> buildFormats(formats));
        params.addView(slider(Param.FILM_GRAIN, label(Param.FILM_GRAIN)));
        params.addView(slider(Param.FILM_EXPOSURE, label(Param.FILM_EXPOSURE)));
        if (st.spectral != null) {
            params.addView(slider(Param.FILM_CONTRAST, label(Param.FILM_CONTRAST)));
            params.addView(slider(Param.FILM_DIR, label(Param.FILM_DIR)));
        }
        if (st.type != com.particlesdevs.photoncamera.processing.color.FilmSim.SLIDE
                && st.type != com.particlesdevs.photoncamera.processing.color.FilmSim.BW_REVERSAL)
            params.addView(slider(Param.FILM_PRINT_EV, label(Param.FILM_PRINT_EV)));
    }

    private void buildPrints(LinearLayout box, com.particlesdevs.photoncamera.processing.color.FilmSim.Stock st) {
        final com.particlesdevs.photoncamera.processing.color.FilmSim.Print[] prints = com.particlesdevs.photoncamera.processing.color.FilmSim.PRINTS;
        final String on = com.particlesdevs.photoncamera.processing.color.FilmSim.print(host.state().filmPrint, st).id;
        int chosen = -1;
        for (int i = 0; i < prints.length; i++) if (prints[i].id.equals(on)) chosen = i;
        if (repaint(box, chosen)) return;
        List<TextView> chips = new ArrayList<>();
        for (com.particlesdevs.photoncamera.processing.color.FilmSim.Print p : prints) {
            TextView t = chip(p.name, p.id.equals(on), v -> {
                host.state().filmPrint = p.id;
                host.changed(true); // its refresher paints the chips
            });
            t.setContentDescription(p.brand + " " + p.name + ", " + (p.film ? Lang.t("кинопечать", "print film") : Lang.t("бумага", "paper")));
            chips.add(t);
        }
        HorizontalScrollView row = chipRow(chips);
        box.addView(row);
        centreCard((LinearLayout) row.getChildAt(0), chosen);
    }

    private void buildFormats(LinearLayout box) {
        final com.particlesdevs.photoncamera.processing.color.FilmSim.Format on = com.particlesdevs.photoncamera.processing.color.FilmSim.format(host.state().filmFormat);
        int chosen = java.util.Arrays.asList(com.particlesdevs.photoncamera.processing.color.FilmSim.FORMATS).indexOf(on);
        if (repaint(box, chosen)) return;
        List<TextView> chips = new ArrayList<>();
        for (com.particlesdevs.photoncamera.processing.color.FilmSim.Format f : com.particlesdevs.photoncamera.processing.color.FilmSim.FORMATS) {
            TextView t = chip(com.particlesdevs.photoncamera.processing.color.FilmSim.formatName(c, f), f == on, v -> {
                host.state().filmFormat = f.id;
                host.changed(true);
            });
            t.setContentDescription(com.particlesdevs.photoncamera.processing.color.FilmSim.formatName(c, f) + ", "
                    + com.particlesdevs.photoncamera.processing.color.FilmSim.formatHint(c, f));
            chips.add(t);
        }
        HorizontalScrollView row = chipRow(chips);
        box.addView(row);
        centreCard((LinearLayout) row.getChildAt(0), chosen);
    }

    /** A built chip row in {@code box}: its chips painted for {@code chosen} (true), or none built yet (false). */
    private static boolean repaint(LinearLayout box, int chosen) {
        if (box.getChildCount() == 0 || !(box.getChildAt(0) instanceof HorizontalScrollView)) return false;
        LinearLayout row = (LinearLayout) ((HorizontalScrollView) box.getChildAt(0)).getChildAt(0);
        for (int i = 0; i < row.getChildCount(); i++) {
            TextView t = (TextView) row.getChildAt(i);
            if (t.isSelected() != (i == chosen)) GalleryUi.paintChip(t, i == chosen);
        }
        return true;
    }

    private TextView hint(String text) {
        TextView t = GalleryUi.text(c, text, 12, GalleryUi.MUTED);
        t.setPadding(GalleryUi.dp(c, 18), GalleryUi.dp(c, 4), GalleryUi.dp(c, 18), GalleryUi.dp(c, 8));
        return t;
    }

    /** The cards' side (dp) and corners: the camera tray's (LutTray.THUMB_DP, THUMB_CORNER_DP, CARD_CORNER_DP). */
    static final int CARD_THUMB_DP = 53, CARD_THUMB_CORNER_DP = 14, CARD_CORNER_DP = 18;
    /** The chosen card's plate (the tray's row plate, P583). */
    static final int CARD_PLATE = 0xFF3A3A3C;

    private LinearLayout cardRow(LinearLayout body) {
        HorizontalScrollView sv = new HorizontalScrollView(c);
        sv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(GalleryUi.dp(c, 14), GalleryUi.dp(c, 8), GalleryUi.dp(c, 14), GalleryUi.dp(c, 6));
        sv.addView(row);
        body.addView(sv);
        return row;
    }

    private LinearLayout.LayoutParams cardParams(LinearLayout row) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (row.getChildCount() > 0) lp.setMarginStart(GalleryUi.dp(c, 6));
        return lp;
    }

    /** A card: the picture (53dp, 14dp corners) and the name under it; chosen, on the plate with its name in the accent. */
    LinearLayout lookCard(String label, boolean on) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = GalleryUi.dp(c, 4), side = GalleryUi.dp(c, CARD_THUMB_DP);
        card.setPadding(pad, pad, pad, GalleryUi.dp(c, 6));
        android.widget.ImageView img = new android.widget.ImageView(c);
        img.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        img.setBackground(GalleryUi.round(c, 0x17FFFFFF, 0, CARD_THUMB_CORNER_DP));
        img.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        img.setClipToOutline(true);
        img.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(img, new LinearLayout.LayoutParams(side, side));
        TextView t = GalleryUi.text(c, label, 11, GalleryUi.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setTypeface(com.particlesdevs.photoncamera.ui.style.Fonts.of(c, 600));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(side, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = GalleryUi.dp(c, 5);
        card.addView(t, tp);
        card.setClickable(true);
        card.setFocusable(true);
        paintCard(card, on);
        com.particlesdevs.photoncamera.ui.Motion.press(card);
        return card;
    }

    static android.widget.ImageView thumb(View card) {
        return (android.widget.ImageView) ((ViewGroup) card).getChildAt(0);
    }

    private void paintCard(LinearLayout card, boolean on) {
        card.setSelected(on);
        card.setActivated(on);
        card.setBackground(GalleryUi.pressable(GalleryUi.round(c, on ? CARD_PLATE : 0x00000000, 0, CARD_CORNER_DP)));
        ((TextView) card.getChildAt(1)).setTextColor(on ? GalleryUi.accent(c) : GalleryUi.TEXT);
    }

    private void selectCards(LinearLayout row, int chosen) {
        for (int i = 0; i < row.getChildCount(); i++) {
            LinearLayout card = (LinearLayout) row.getChildAt(i);
            boolean on = i == chosen;
            if (card.isSelected() != on) paintCard(card, on);
        }
    }

    private static void centreCard(LinearLayout row, int index) {
        if (index < 0) return;
        row.post(() -> {
            View target = row.getChildAt(index);
            View scroll = (View) row.getParent();
            if (target != null && scroll instanceof HorizontalScrollView)
                ((HorizontalScrollView) scroll).scrollTo(Math.max(0, target.getLeft() + target.getWidth() / 2 - scroll.getWidth() / 2), 0);
        });
    }

    @Nullable
    View forTab(int tab) {
        refreshers.clear();
        switch (tab) {
            case 0: return crop();
            case 1: return styles();
            case 2: return look();
            case 3: return light();
            case 4: return colour();
            case 5: return effects();
            case 6: return detail();
            case 7: return new EditWatermarkPanel(c, host, this).build(); // P633: «Знак»
            default: return null;
        }
    }
}
