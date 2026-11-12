package com.particlesdevs.photoncamera.gallery.editor;

import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.gallery.ui.GallerySheets;
import com.particlesdevs.photoncamera.gallery.ui.GalleryUi;
import com.particlesdevs.photoncamera.processing.watermark.WatermarkExif;
import com.particlesdevs.photoncamera.processing.watermark.WatermarkPatch;
import com.particlesdevs.photoncamera.processing.watermark.WatermarkSpec;
import com.particlesdevs.photoncamera.processing.watermark.WatermarkText;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.List;

/**
 * P633 (owner, 2026-11-11: «По поводу вотермарок, дай возможность их редактировать у нас в галерее, добавлять, убирать на наших фото,
 * если они включены и так далее»): the editor's «Знак» tab. What happens to the photo's watermark - «Как есть», «Убрать» (only for a
 * photo that kept the pixels under its watermark: SCAMERA's, shot with it on; otherwise greyed, a tap says why), «Изменить» (taken off
 * and drawn again) / «Добавить» (a photo without a record) - and for a watermark drawn, the camera's own settings with the camera's
 * names and ranges (WatermarkSpec; the settings page's texts as the long-press help): style, position, colour, font, size, opacity,
 * margin, then every field on its own switch; a field this photo's EXIF lacks is greyed with «нет в EXIF». The copy draws it with
 * the camera's code (PhotoWatermark.draw), the picture shows it live (WatermarkOverlayView).
 */
final class EditWatermarkPanel {
    static final int MODE_KEEP = 0, MODE_REMOVE = 1, MODE_DRAW = 2;

    private final Context c;
    private final EditPanels.Host host;
    private final EditPanels panels;
    private final List<Runnable> controls = new ArrayList<>();
    @Nullable private com.particlesdevs.photoncamera.ui.controls.IosSegmented modes;
    @Nullable private LinearLayout body;
    @Nullable private TextView status;
    private int builtMode = -1;

    EditWatermarkPanel(Context c, EditPanels.Host host, EditPanels panels) {
        this.c = c;
        this.host = host;
        this.panels = panels;
    }

    /** The state's choice: a watermark to draw («Изменить» / «Добавить»), the own one taken off, or the photo as it is. */
    static int mode(EditState s) {
        return !s.wm.isEmpty() ? MODE_DRAW : s.wmRemove ? MODE_REMOVE : MODE_KEEP;
    }

    @Nullable
    private WatermarkPatch record() {
        EditSource s = host.source();
        return s != null ? s.watermark : null;
    }

    private WatermarkText.Info info() {
        EditSource s = host.source();
        return s != null && s.watermarkInfo != null ? s.watermarkInfo : new WatermarkText.Info();
    }

    /** The mode's labels: «Изменить» with a record (the old one goes), «Добавить» without. */
    String[] modeNames() {
        boolean rec = record() != null;
        return new String[]{Lang.t("Как есть", "As is"), Lang.t("Убрать", "Remove"),
                rec ? Lang.t("Изменить", "Change") : Lang.t("Добавить", "Add")};
    }

    /** Why «Убрать» is not offered for a photo without a record. */
    static String noRecordText() {
        return Lang.t("«Убрать» недоступно: в файле нет пикселей, которые были под знаком. Так бывает у снимков, сделанных до этой версии "
                        + "SCAMERA, без знака, в другом приложении или пересохранённых другим редактором. Добавить новый знак можно.",
                "«Remove» is not available: the file has no pixels of what was under a watermark. That is so for photos taken before this "
                        + "version of SCAMERA, without a watermark, in another app or saved again by another editor. A new watermark can be added.");
    }

    String statusText() {
        WatermarkPatch r = record();
        if (r == null) return noRecordText();
        WatermarkSpec s = WatermarkSpec.fromJson(r.spec);
        String what = s == null ? "" : " («" + entry(R.array.watermark_layout_entries, s.layout.ordinal()) + "», "
                + entry(R.array.watermark_position_entries, s.position.ordinal()).toLowerCase(java.util.Locale.ROOT) + ")";
        return Lang.t("Снято со знаком SCAMERA" + what + ". Пиксели под ним хранятся в файле: «Убрать» вернёт снимок без знака "
                        + "без потерь, «Изменить» — уберёт и нарисует новый.",
                "Shot with the SCAMERA watermark" + what + ". The pixels under it are kept in the file: «Remove» gives the photo back "
                        + "without it, losslessly; «Change» takes it off and draws a new one.");
    }

    private String entry(int array, int index) {
        String[] e = c.getResources().getStringArray(array);
        return index >= 0 && index < e.length ? e[index] : "";
    }

    View build() {
        LinearLayout l = EditPanels.column(c);
        l.setTag("wm_panel");
        status = hint(statusText());
        status.setTag("wm_status");
        l.addView(status);
        View seg = panels.segments(modeNames(), mode(host.state()), this::pick);
        seg.setTag("wm_modes");
        modes = (com.particlesdevs.photoncamera.ui.controls.IosSegmented) ((ViewGroup) seg).getChildAt(0);
        if (record() == null) {
            View remove = modes.getChildAt(MODE_REMOVE);
            remove.setAlpha(.38f);
            remove.setContentDescription(Lang.t("Убрать — недоступно", "Remove — not available"));
        }
        help(seg, Lang.t("Водяной знак", "Watermark"), statusText());
        l.addView(seg);
        body = EditPanels.column(c);
        body.setTag("wm_body");
        l.addView(body);
        buildBody();
        panels.refresher(this::refresh);
        return l;
    }

    private void pick(int m) {
        EditState st = host.state();
        if (m == MODE_REMOVE && record() == null) {
            // greyed: back to what it was, and why
            if (modes != null) modes.setSelected(mode(st), true);
            com.particlesdevs.photoncamera.gallery.ui.GalleryToast.show(c, noRecordText(), true);
            return;
        }
        if (m == mode(st)) return;
        switch (m) {
            case MODE_KEEP:
                st.wmRemove = false;
                st.wm = "";
                break;
            case MODE_REMOVE:
                st.wmRemove = true;
                st.wm = "";
                break;
            default:
                st.wmRemove = record() != null; // «Изменить»: the old one comes off first
                st.wm = initial().toJson();
        }
        host.changed(true); // its refresh builds the mode's controls
        if (builtMode != mode(st)) buildBody();
    }

    /** A new watermark starts as the photo's own (its record's spec) or as the camera's settings. */
    WatermarkSpec initial() {
        WatermarkPatch r = record();
        WatermarkSpec s = r != null ? WatermarkSpec.fromJson(r.spec) : null;
        return s != null ? s : camera();
    }

    WatermarkSpec camera() {
        try {
            return WatermarkSpec.from(PreferenceManager.getDefaultSharedPreferences(c));
        } catch (RuntimeException e) {
            return new WatermarkSpec();
        }
    }

    private WatermarkSpec spec() {
        WatermarkSpec s = host.state().watermark();
        return s != null ? s : initial();
    }

    private interface Edit {
        void apply(WatermarkSpec s);
    }

    private void edit(Edit e, boolean commit) {
        WatermarkSpec s = spec();
        e.apply(s);
        host.state().wm = s.toJson();
        host.changed(commit);
    }

    private void refresh() {
        int m = mode(host.state());
        if (modes != null && modes.getSelected() != m) modes.setSelected(m, true);
        if (m != builtMode) buildBody();
        else for (Runnable r : new ArrayList<>(controls)) r.run();
    }

    private void buildBody() {
        if (body == null) return;
        body.removeAllViews();
        controls.clear();
        builtMode = mode(host.state());
        if (builtMode == MODE_REMOVE) {
            body.addView(hint(Lang.t("Копия будет без знака: пиксели под ним вернутся такими, какими их сняла камера.",
                    "The copy has no watermark: the pixels under it come back as the camera took them.")));
            return;
        }
        if (builtMode != MODE_DRAW) return;
        body.addView(panels.section(Lang.t("Вид", "Look")));
        final WatermarkSpec now = spec();
        body.addView(choice(R.string.prefs_watermark_layout_title, R.string.prefs_watermark_layout_summary, R.array.watermark_layout_entries,
                now.layout.ordinal(), i -> edit(s -> s.layout = WatermarkSpec.Layout.values()[i], true), () -> spec().layout.ordinal(), "wm_layout"));
        body.addView(chips(R.string.prefs_watermark_position_title, R.string.prefs_watermark_position_summary, entries(R.array.watermark_position_entries),
                () -> spec().position.ordinal(), i -> edit(s -> s.position = WatermarkSpec.Position.values()[i], true), "wm_position"));
        body.addView(choice(R.string.prefs_watermark_color_title, R.string.prefs_watermark_color_summary, R.array.watermark_color_entries,
                now.colour.ordinal(), i -> edit(s -> s.colour = WatermarkSpec.Colour.values()[i], true), () -> spec().colour.ordinal(), "wm_colour"));
        body.addView(choice(R.string.prefs_watermark_font_title, R.string.prefs_watermark_font_summary, R.array.watermark_font_entries,
                now.font.ordinal(), i -> edit(s -> s.font = WatermarkSpec.Font.values()[i], true), () -> spec().font.ordinal(), "wm_font"));
        body.addView(slider(R.string.prefs_watermark_size_title, R.string.prefs_watermark_size_summary, WatermarkSpec.MIN_SIZE,
                WatermarkSpec.MAX_SIZE, WatermarkSpec.DEFAULT_SIZE, () -> spec().size, (s, v) -> s.size = v, v -> String.valueOf(Math.round(v)), "wm_size"));
        body.addView(slider(R.string.prefs_watermark_opacity_title, R.string.prefs_watermark_opacity_summary, WatermarkSpec.MIN_OPACITY, 100,
                WatermarkSpec.DEFAULT_OPACITY, () -> spec().opacity * 100f, (s, v) -> s.opacity = v / 100f, v -> Math.round(v) + " %", "wm_opacity"));
        body.addView(slider(R.string.prefs_watermark_margin_title, R.string.prefs_watermark_margin_summary, 0, WatermarkSpec.MAX_MARGIN,
                WatermarkSpec.DEFAULT_MARGIN, () -> spec().margin, (s, v) -> s.margin = v, v -> Math.round(v) + " %", "wm_margin"));

        body.addView(panels.section(Lang.t("Что показывать", "What it shows")));
        final WatermarkText.Info info = info();
        LinearLayout group = GalleryUi.group(c, 16);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gp.leftMargin = gp.rightMargin = GalleryUi.dp(c, 18);
        gp.topMargin = GalleryUi.dp(c, 6);
        body.addView(group, gp);
        group.addView(toggle(R.string.prefs_watermark_show_text_title, R.string.prefs_watermark_show_text_summary, null, true,
                () -> spec().showText, (s, on) -> s.showText = on, "wm_show_text"));
        group.addView(textRow());
        group.addView(toggle(R.string.prefs_watermark_show_device_title, R.string.prefs_watermark_show_device_summary,
                value(info, WatermarkExif.Field.DEVICE), WatermarkExif.has(info, WatermarkExif.Field.DEVICE), () -> spec().showDevice, (s, on) -> s.showDevice = on, "wm_show_device"));
        group.addView(toggle(R.string.prefs_watermark_show_lens_title, R.string.prefs_watermark_show_lens_summary,
                value(info, WatermarkExif.Field.LENS), WatermarkExif.has(info, WatermarkExif.Field.LENS), () -> spec().showLens, (s, on) -> s.showLens = on, "wm_show_lens"));
        group.addView(toggle(R.string.prefs_watermark_show_aperture_title, R.string.prefs_watermark_show_aperture_summary,
                value(info, WatermarkExif.Field.APERTURE), WatermarkExif.has(info, WatermarkExif.Field.APERTURE), () -> spec().showAperture, (s, on) -> s.showAperture = on, "wm_show_aperture"));
        group.addView(toggle(R.string.prefs_watermark_show_shutter_title, R.string.prefs_watermark_show_shutter_summary,
                value(info, WatermarkExif.Field.SHUTTER), WatermarkExif.has(info, WatermarkExif.Field.SHUTTER), () -> spec().showShutter, (s, on) -> s.showShutter = on, "wm_show_shutter"));
        group.addView(toggle(R.string.prefs_watermark_show_iso_title, R.string.prefs_watermark_show_iso_summary,
                value(info, WatermarkExif.Field.ISO), WatermarkExif.has(info, WatermarkExif.Field.ISO), () -> spec().showIso, (s, on) -> s.showIso = on, "wm_show_iso"));
        group.addView(toggle(R.string.prefs_watermark_show_time_title, R.string.prefs_watermark_show_time_summary,
                value(info, WatermarkExif.Field.TIME), WatermarkExif.has(info, WatermarkExif.Field.TIME), () -> spec().showTime, (s, on) -> s.showTime = on, "wm_show_time"));
        group.addView(toggle(R.string.prefs_watermark_logo_title, R.string.prefs_desc_pref_watermark_logo, null, true,
                () -> spec().logo, (s, on) -> s.logo = on, "wm_logo"));
        if (WatermarkExif.has(info, WatermarkExif.Field.TIME)) {
            body.addView(chips(R.string.prefs_watermark_date_format_title, R.string.prefs_watermark_date_format_summary, dates(info),
                    () -> spec().dateFormat.ordinal(), i -> edit(s -> s.dateFormat = WatermarkSpec.DateFormat.values()[i], true), "wm_date_format"));
        }
        TextView reset = panels.chip(Lang.t("Как в настройках камеры", "As in the camera's settings"), false, v -> {
            host.state().wm = camera().toJson();
            host.changed(true);
        });
        reset.setTag("wm_camera");
        help(reset, Lang.t("Как в настройках камеры", "As in the camera's settings"),
                Lang.t("Знак с настройками камеры (Настройки → Водяной знак), как на новых снимках.",
                        "The watermark with the camera's settings (Settings → Watermark), as new photos get it."));
        LinearLayout rr = new LinearLayout(c);
        rr.setPadding(GalleryUi.dp(c, 18), GalleryUi.dp(c, 12), GalleryUi.dp(c, 18), GalleryUi.dp(c, 4));
        rr.addView(reset);
        body.addView(rr);
        body.addView(hint(Lang.t("Знак рисуется на копии кодом камеры: прямо, в выбранном углу, по короткой стороне кадра. Значения — из EXIF этого снимка; чего в EXIF нет, того на знаке не будет.",
                "The copy gets the watermark from the camera's own code: upright, in the chosen corner, sized on the short side. The values come from this photo's EXIF; what it lacks is left out.")));
    }

    /** The field's value from the photo («24mm»), «нет в EXIF» without it. */
    static String value(WatermarkText.Info info, WatermarkExif.Field f) {
        if (!WatermarkExif.has(info, f)) return Lang.t("нет в EXIF", "not in EXIF");
        WatermarkSpec one = new WatermarkSpec().only(false, f == WatermarkExif.Field.DEVICE, f == WatermarkExif.Field.LENS,
                f == WatermarkExif.Field.APERTURE, f == WatermarkExif.Field.SHUTTER, f == WatermarkExif.Field.ISO,
                f == WatermarkExif.Field.TIME, false);
        WatermarkText t = WatermarkText.of(one, info);
        List<String> g = t.groups();
        return g.isEmpty() ? "" : g.get(0);
    }

    /** The date formats as this photo's date reads in each. */
    private String[] dates(WatermarkText.Info info) {
        WatermarkSpec.DateFormat[] f = WatermarkSpec.DateFormat.values();
        String[] out = new String[f.length];
        long t = info.timeMs > 0 ? info.timeMs : System.currentTimeMillis();
        for (int i = 0; i < f.length; i++) out[i] = WatermarkText.time(t, info.zone, f[i], Lang.ru());
        return out;
    }

    private String[] entries(int array) {
        return c.getResources().getStringArray(array);
    }

    private TextView hint(String text) {
        TextView t = GalleryUi.text(c, text, 12, GalleryUi.MUTED);
        t.setPadding(GalleryUi.dp(c, 18), GalleryUi.dp(c, 6), GalleryUi.dp(c, 18), GalleryUi.dp(c, 4));
        return t;
    }

    /** Long press on {@code v} (and its children: a segmented control's segments, a chip row's chips): the help sheet. */
    static void help(View v, String title, String text) {
        v.setOnLongClickListener(x -> {
            com.particlesdevs.photoncamera.ui.settings.SettingHelp.show(x.getContext(), title, text);
            return true;
        });
        if (v instanceof ViewGroup && !(v instanceof EditSlider)) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) help(g.getChildAt(i), title, text);
        }
    }

    private interface Index {
        int get();
    }

    private View choice(int title, int summary, int array, int on, java.util.function.IntConsumer pick, Index now, String tag) {
        LinearLayout box = EditPanels.column(c);
        box.setTag(tag);
        TextView label = caption(c.getString(title));
        box.addView(label);
        View seg = panels.segments(entries(array), on, pick);
        box.addView(seg);
        com.particlesdevs.photoncamera.ui.controls.IosSegmented s =
                (com.particlesdevs.photoncamera.ui.controls.IosSegmented) ((ViewGroup) seg).getChildAt(0);
        controls.add(() -> {
            if (s.getSelected() != now.get()) s.setSelected(now.get(), true);
        });
        help(box, c.getString(title), c.getString(summary));
        return box;
    }

    private View chips(int title, int summary, String[] names, Index now, java.util.function.IntConsumer pick, String tag) {
        LinearLayout box = EditPanels.column(c);
        box.setTag(tag);
        box.addView(caption(c.getString(title)));
        List<TextView> list = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            TextView t = panels.chip(names[i], i == now.get(), v -> pick.accept(k));
            t.setTag(tag + "_" + i);
            list.add(t);
        }
        HorizontalScrollView row = panels.chipRow(list);
        box.addView(row);
        controls.add(() -> {
            int on = now.get();
            for (int i = 0; i < list.size(); i++) if (list.get(i).isSelected() != (i == on)) GalleryUi.paintChip(list.get(i), i == on);
        });
        help(box, c.getString(title), c.getString(summary));
        return box;
    }

    private TextView caption(String text) {
        TextView t = GalleryUi.text(c, text, 15, GalleryUi.TEXT);
        t.setPadding(GalleryUi.dp(c, 18), GalleryUi.dp(c, 10), GalleryUi.dp(c, 18), 0);
        return t;
    }

    private interface Value {
        float get();
    }

    private interface SetValue {
        void set(WatermarkSpec s, float v);
    }

    private View slider(int title, int summary, float min, float max, float def, Value now, SetValue set, EditSlider.Format format, String tag) {
        EditSlider s = new EditSlider(c).setup(c.getString(title), min, max, def, 1f, false);
        s.setTag(tag);
        s.format(format);
        s.set(now.get(), false);
        s.listener(new EditSlider.Listener() {
            @Override
            public void changed(float value) {
                edit(sp -> set.set(sp, value), false);
            }

            @Override
            public void committed() {
                edit(sp -> set.set(sp, s.value()), true);
            }
        });
        controls.add(() -> s.set(now.get(), false));
        if (s.getChildCount() > 0) help(s.getChildAt(0), c.getString(title), c.getString(summary) + "\n\n" + EditSlider.hint());
        return s;
    }

    private interface Flag {
        boolean get();
    }

    private interface SetFlag {
        void set(WatermarkSpec s, boolean on);
    }

    /**
     * A field's row: the name, the photo's value under it (or «нет в EXIF»: the row greyed, its switch off and still), the switch.
     */
    private View toggle(int title, int summary, @Nullable String value, boolean available, Flag now, SetFlag set, String tag) {
        LinearLayout row = new LinearLayout(c);
        row.setTag(tag);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(GalleryUi.dp(c, GalleryUi.ROW_MIN));
        row.setBackground(GalleryUi.pressable(GalleryUi.round(c, 0, 0, 0)));
        row.setPadding(GalleryUi.dp(c, 16), GalleryUi.dp(c, 8), GalleryUi.dp(c, 16), GalleryUi.dp(c, 8));
        LinearLayout texts = EditPanels.column(c);
        TextView t = GalleryUi.text(c, c.getString(title), 16, GalleryUi.TEXT);
        texts.addView(t);
        if (value != null && !value.isEmpty()) {
            TextView v = GalleryUi.text(c, value, 12, GalleryUi.MUTED);
            v.setSingleLine(true);
            v.setEllipsize(android.text.TextUtils.TruncateAt.END);
            v.setTag(tag + "_value");
            texts.addView(v);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        View sw = GallerySheets.toggleView(c, available && now.get());
        sw.setTag(tag + "_switch");
        row.addView(sw);
        row.setContentDescription(c.getString(title) + (value != null ? ", " + value : ""));
        if (available) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(x -> {
                boolean on = !now.get();
                GallerySheets.setToggle(sw, on);
                edit(s -> set.set(s, on), true);
            });
            controls.add(() -> GallerySheets.setToggle(sw, now.get()));
        } else {
            row.setEnabled(false);
            row.setAlpha(.45f);
        }
        String why = available ? c.getString(summary)
                : c.getString(summary) + "\n\n" + Lang.t("В EXIF этого снимка такого значения нет: на знаке его не будет.",
                "This photo's EXIF has no such value: the watermark leaves it out.");
        row.setOnLongClickListener(x -> {
            com.particlesdevs.photoncamera.ui.settings.SettingHelp.show(x.getContext(), c.getString(title), why);
            return true;
        });
        return row;
    }

    /** The own text, typed in place (64 characters at most, as the camera draws), one undo step when the typing ends. */
    private View textRow() {
        EditText e = new EditText(c);
        e.setTag("wm_text");
        e.setSingleLine(true);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(WatermarkSpec.MAX_TEXT)});
        e.setTextColor(GalleryUi.TEXT);
        e.setHintTextColor(GalleryUi.MUTED);
        e.setHint(c.getString(R.string.prefs_watermark_text_title));
        e.setTextSize(16);
        e.setBackground(null);
        e.setPadding(GalleryUi.dp(c, 16), GalleryUi.dp(c, 10), GalleryUi.dp(c, 16), GalleryUi.dp(c, 10));
        e.setMinHeight(GalleryUi.dp(c, GalleryUi.ROW_MIN));
        e.setText(spec().text);
        e.setEnabled(spec().showText);
        e.setAlpha(spec().showText ? 1f : .45f);
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                String v = s.toString();
                if (v.equals(spec().text)) return;
                edit(sp -> sp.text = v, false);
            }
        });
        e.setOnFocusChangeListener((v, has) -> {
            if (!has) host.changed(true);
        });
        e.setOnEditorActionListener((v, action, ev) -> {
            if (action == EditorInfo.IME_ACTION_DONE) {
                v.clearFocus();
                host.changed(true);
            }
            return false;
        });
        controls.add(() -> {
            WatermarkSpec s = spec();
            if (!e.getText().toString().equals(s.text)) e.setText(s.text);
            e.setEnabled(s.showText);
            e.setAlpha(s.showText ? 1f : .45f);
        });
        help(e, c.getString(R.string.prefs_watermark_text_title), c.getString(R.string.prefs_watermark_show_text_summary));
        return e;
    }
}
