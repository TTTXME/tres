package com.scamera.app.gallery.editor;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.scamera.app.gallery.ui.GallerySheets;
import com.scamera.app.gallery.ui.GalleryUi;
import com.scamera.app.ui.settings.DevelopCatalog;
import com.scamera.app.util.Lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * P665 «Проявка» / «Develop»: the editor's tab with every processing setting of the camera (DevelopCatalog), grouped as the
 * camera's settings - Тон, Света, Резкость, Шумоподавление, Цвет, Плёнка, LUT, Портрет (Oldstyle for a shot of that mode), and
 * Склейка greyed. A DNG of ours with its develop record: the switch «Проявка камерой» develops it with the camera's own pipeline,
 * each row starting at the value the photo was shot with (its record); a change re-develops the preview. P696: a Bayer DNG without
 * the record (ours by its Software, or another app's with its colour tags) develops the same way from its own tags, each row
 * starting at the camera's current setting. Any other photo: the rows are shown greyed with why (a finished picture already has
 * the camera's tone, noise reduction and sharpening in it; a RAW the developer cannot take says which ones it takes), and where
 * the finished-picture equivalents are.
 */
final class EditDevelopPanel {
    private final Context c;
    private final EditPanels.Host host;
    private final EditPanels panels;
    @Nullable private LinearLayout body;
    @Nullable private TextView status;
    @Nullable private View toggle;
    /** The groups (built once a tab: the settings tree is inflated for them). */
    @Nullable private List<DevelopCatalog.Group> groups;
    /** What the rows were built for: the group, the switch and the changes. */
    @Nullable private String builtFor;
    static String openGroup = DevelopCatalog.TONE;

    EditDevelopPanel(Context c, EditPanels.Host host, EditPanels panels) {
        this.c = c;
        this.host = host;
        this.panels = panels;
    }

    /** Why the develop is not offered for this photo, null when it is. */
    @Nullable
    String unavailable() {
        EditDevelop d = host.develop();
        if (d != null) return null;
        EditSource s = host.source();
        // P696: a DNG without the record is developed from its own tags (EditDevelop.fromTags); what is left cannot be
        if (s != null && s.isRaw())
            return Lang.t("В этом RAW нет записи обработки SCAMERA, и по его собственным тегам он не проявляется: проявка камерой "
                            + "берёт Bayer-DNG — SCAMERA или с цветовыми тегами (матрица цвета, баланс белого). Этот RAW другого "
                            + "формата, с Quad-мозаикой, зеркальной ориентацией, без цветовых тегов или не прочитан. RAW правится "
                            + "вкладками «Свет», «Цвет» и «Детали».",
                    "This RAW has no SCAMERA processing record and cannot be developed from its own tags: the camera's develop "
                            + "takes a Bayer DNG - a SCAMERA one, or one with its colour tags (colour matrix, white balance). This RAW is "
                            + "another format, a Quad mosaic, mirrored, without colour tags or could not be read. The RAW is edited "
                            + "in «Light», «Colour» and «Detail».");
        return Lang.t("Готовый снимок: тон, шумоподавление и резкость камеры уже в нём, заново их не применить. Для готовых снимков: "
                        + "плёнка и LUT камеры — вкладка «LUT», цвет — «Цвет», резкость и шум — «Детали», зерно — «Эффекты». "
                        + "Все настройки камеры работают на DNG SCAMERA.",
                "A finished picture: the camera's tone, noise reduction and sharpening are already in it and cannot be applied again. "
                        + "For finished pictures: the camera's film and LUT are in «LUT», colour in «Colour», sharpening and noise in "
                        + "«Detail», grain in «Effects». Every camera setting works on a SCAMERA DNG.");
    }

    /** The shot's settings (the record's), empty without a develop. */
    Map<String, Object> shot() {
        EditDevelop d = host.develop();
        if (d == null) return Collections.emptyMap();
        try {
            return com.scamera.app.processing.opengl.postpipeline.DevelopRun.shotValues(d.record);
        } catch (org.json.JSONException e) {
            return Collections.emptyMap();
        }
    }

    View build() {
        LinearLayout l = EditPanels.column(c);
        l.setTag("develop_panel");
        String why = unavailable();
        status = hint(why != null ? why : statusText());
        status.setTag("develop_status");
        l.addView(status);
        if (why == null) {
            toggle = switchRow(Lang.t("Проявка камерой", "Camera develop"),
                    Lang.t("Конвейер камеры: тон, свет, резкость, шумоподавление, цвет, плёнка — с настройками снимка",
                            "The camera's pipeline: tone, highlights, sharpness, noise reduction, colour, film - with the shot's settings"),
                    host.state().develop, true, on -> {
                        host.state().develop = on;
                        host.changed(true);
                    }, "develop_switch");
            l.addView(toggle);
        }
        groups = DevelopCatalog.groups(c, shot());
        List<String> names = new ArrayList<>();
        int open = 0;
        for (int i = 0; i < groups.size(); i++) {
            names.add(groups.get(i).title);
            if (groups.get(i).id.equals(openGroup)) open = i;
        }
        List<TextView> chips = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            final int k = i;
            TextView t = panels.chip(names.get(i), i == open, v -> pickGroup(k, chips));
            t.setTag("develop_group_" + groups.get(i).id);
            chips.add(t);
        }
        l.addView(panels.chipRow(chips));
        body = EditPanels.column(c);
        body.setTag("develop_rows");
        l.addView(body);
        buildRows();
        panels.refresher(this::refresh);
        return l;
    }

    private void pickGroup(int k, List<TextView> chips) {
        if (groups == null) return;
        openGroup = groups.get(k).id;
        for (int i = 0; i < chips.size(); i++) GalleryUi.paintChip(chips.get(i), i == k);
        buildRows();
    }

    /** The status line: what the develop does now. */
    String statusText() {
        String busy = host.developStatus();
        if (busy != null) return busy;
        EditDevelop d = host.develop();
        if (d != null && d.fromTags())
            // P696: no record of the shot in this DNG: its own tags and the camera's settings now
            return host.state().develop
                    ? Lang.t("Проявлено конвейером камеры. В этом DNG нет записи обработки: цвет и шум — из его тегов, значения по "
                            + "умолчанию — текущие настройки камеры; изменённые подсвечены.",
                    "Developed by the camera's pipeline. This DNG has no processing record: colour and noise come from its tags, "
                            + "the defaults are the camera's current settings; changed ones are highlighted.")
                    : Lang.t("Включите «Проявка камерой», чтобы проявить DNG с текущими настройками камеры (записи обработки в нём нет).",
                    "Switch on «Camera develop» to develop the DNG with the camera's current settings (it has no processing record).");
        return host.state().develop
                ? Lang.t("Проявлено конвейером камеры. Значения по умолчанию — как при съёмке; изменённые подсвечены.",
                "Developed by the camera's pipeline. The defaults are the shot's values; changed ones are highlighted.")
                : Lang.t("Включите «Проявка камерой», чтобы проявить DNG с настройками камеры.",
                "Switch on «Camera develop» to develop the DNG with the camera's settings.");
    }

    /** After undo / redo / reset / a style, or the develop's status changed: the switch, the status and (changed) the rows. */
    void refresh() {
        if (status != null) {
            String why = unavailable();
            status.setText(why != null ? why : statusText());
        }
        if (toggle != null) {
            View sw = toggle.findViewWithTag("develop_switch_switch");
            if (sw != null) GallerySheets.setToggle(sw, host.state().develop);
        }
        if (!Objects.equals(builtFor, key())) buildRows();
    }

    private String key() {
        EditState s = host.state();
        return openGroup + "|" + s.develop + "|" + s.devChanges;
    }

    /** The open group's rows: its sections' captions, a control per setting, greyed where it changes nothing. */
    private void buildRows() {
        if (body == null || groups == null) return;
        body.removeAllViews();
        builtFor = key();
        DevelopCatalog.Group g = null;
        for (DevelopCatalog.Group x : groups) if (x.id.equals(openGroup)) g = x;
        if (g == null && !groups.isEmpty()) g = groups.get(0);
        if (g == null) return;
        final boolean active = unavailable() == null && host.state().develop;
        final Map<String, Object> shot = shot();
        final Map<String, Object> values = new java.util.HashMap<>(shot);
        values.putAll(host.state().devChanges);
        if (DevelopCatalog.MERGE.equals(g.id))
            body.addView(hint(Lang.t("Склейка кадров уже сделана при съёмке: в DNG её результат. Эти настройки показаны для полноты.",
                    "The frames were merged at the shot: the DNG holds the result. These settings are shown for completeness.")));
        String section = null;
        for (DevelopCatalog.Row r : g.rows) {
            if (r.section != null && !r.section.equals(section)) {
                section = r.section;
                body.addView(panels.section(section));
            }
            String inert = !active ? (unavailable() != null ? unavailable() : Lang.t("Включите «Проявка камерой».",
                    "Switch on «Camera develop».")) : DevelopCatalog.inert(r, values);
            body.addView(row(r, shot, inert));
        }
    }

    /** One setting's control, greyed with {@code inert} (why) when it changes nothing. */
    private View row(DevelopCatalog.Row r, Map<String, Object> shot, @Nullable String inert) {
        final Object now = DevelopCatalog.value(r, shot, host.state().devChanges);
        final Object asShot = shot.containsKey(r.key) ? shot.get(r.key) : r.def;
        final String help = (r.help != null ? r.help : r.title) + (inert != null ? "\n\n" + inert : "");
        View v;
        switch (r.kind) {
            case DevelopCatalog.SWITCH:
                v = switchRow(r.title, changedNote(r, now, asShot), DevelopCatalog.on(now), inert == null,
                        on -> set(r, on, asShot), "develop_row_" + r.key);
                break;
            case DevelopCatalog.LIST: {
                int on = 0;
                for (int i = 0; i < r.values.length; i++) if (r.values[i].equals(String.valueOf(now))) on = i;
                LinearLayout box = EditPanels.column(c);
                box.setTag("develop_row_" + r.key);
                box.addView(caption(r.title + changedSuffix(r, now, asShot)));
                if (r.values.length <= 3 && r.values.length > 0) {
                    String[] names = new String[r.labels.length];
                    for (int i = 0; i < names.length; i++) names[i] = String.valueOf(r.labels[i]);
                    box.addView(panels.segments(names, on, i -> set(r, r.values[i], asShot)));
                } else {
                    List<TextView> chips = new ArrayList<>();
                    for (int i = 0; i < r.values.length; i++) {
                        final int k = i;
                        chips.add(panels.chip(i < r.labels.length ? String.valueOf(r.labels[i]) : r.values[i], i == on,
                                x -> set(r, r.values[k], asShot)));
                    }
                    box.addView(panels.chipRow(chips));
                }
                v = box;
                break;
            }
            case DevelopCatalog.SLIDER: {
                final float def = DevelopCatalog.number(asShot, DevelopCatalog.number(r.def, r.min));
                final float start = DevelopCatalog.number(now, def);
                final float lo = Math.min(r.min, start), hi = Math.max(r.max, start);
                EditSlider s = new EditSlider(c).setup(r.title, lo, hi, Math.max(lo, Math.min(hi, def)), r.step, false);
                s.setTag("develop_row_" + r.key);
                s.set(start, false);
                if (r.decimal) s.format(r::number);
                s.listener(new EditSlider.Listener() {
                    @Override public void changed(float value) {
                        // the develop runs on the release (one run a gesture)
                    }

                    @Override public void committed() {
                        set(r, r.number(s.value()), asShot);
                    }
                });
                v = s;
                break;
            }
            default: {
                LinearLayout box = EditPanels.column(c);
                box.setTag("develop_row_" + r.key);
                box.addView(caption(r.title));
                box.addView(hint(Lang.t("Как снято: ", "As shot: ") + String.valueOf(asShot)));
                v = box;
                inert = inert != null ? inert : DevelopCatalog.inert(r, Collections.emptyMap());
            }
        }
        if (inert != null) {
            v.setAlpha(.45f);
            disable(v);
        }
        EditWatermarkPanel.help(v, r.title, help);
        return v;
    }

    /** Stores {@code value} as the change of {@code r} (none when it is the shot's), one undo step. */
    private void set(DevelopCatalog.Row r, Object value, Object asShot) {
        EditState s = host.state();
        boolean same = String.valueOf(value).equals(String.valueOf(asShot))
                || r.kind == DevelopCatalog.SWITCH && DevelopCatalog.on(value) == DevelopCatalog.on(asShot)
                || r.kind == DevelopCatalog.SLIDER && DevelopCatalog.number(value, Float.NaN) == DevelopCatalog.number(asShot, Float.NaN);
        if (same) s.devChanges.remove(r.key);
        else s.devChanges.put(r.key, value instanceof Boolean ? value : String.valueOf(value));
        host.changed(true);
        if (!Objects.equals(builtFor, key())) buildRows();
    }

    private static String changedSuffix(DevelopCatalog.Row r, Object now, Object asShot) {
        return String.valueOf(now).equals(String.valueOf(asShot)) ? "" : " •";
    }

    @Nullable
    private static String changedNote(DevelopCatalog.Row r, Object now, Object asShot) {
        if (DevelopCatalog.on(now) == DevelopCatalog.on(asShot)) return null;
        return Lang.t("Изменено (как снято: ", "Changed (as shot: ") + (DevelopCatalog.on(asShot) ? Lang.t("вкл", "on") : Lang.t("выкл", "off")) + ")";
    }

    private static void disable(View v) {
        v.setEnabled(false);
        if (v instanceof EditSlider) {
            EditSlider s = (EditSlider) v;
            for (int i = 0; i < s.getChildCount(); i++) disable(s.getChildAt(i));
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) disable(g.getChildAt(i));
        }
        v.setClickable(false);
    }

    private interface Flag {
        void set(boolean on);
    }

    /** A switch row: the name, a note under it, the switch (the camera settings' look of the editor, as «Знак»'s fields). */
    private View switchRow(String title, @Nullable String note, boolean on, boolean available, Flag set, String tag) {
        LinearLayout row = new LinearLayout(c);
        row.setTag(tag);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(GalleryUi.dp(c, GalleryUi.ROW_MIN));
        row.setBackground(GalleryUi.pressable(GalleryUi.round(c, 0, 0, 0)));
        row.setPadding(GalleryUi.dp(c, 18), GalleryUi.dp(c, 6), GalleryUi.dp(c, 18), GalleryUi.dp(c, 6));
        LinearLayout texts = EditPanels.column(c);
        texts.addView(GalleryUi.text(c, title, 15, GalleryUi.TEXT));
        if (note != null && !note.isEmpty()) {
            TextView n = GalleryUi.text(c, note, 12, GalleryUi.MUTED);
            texts.addView(n);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        View sw = GallerySheets.toggleView(c, on);
        sw.setTag(tag + "_switch");
        row.addView(sw);
        row.setContentDescription(title);
        if (available) {
            row.setClickable(true);
            row.setFocusable(true);
            final boolean[] state = {on};
            row.setOnClickListener(x -> {
                state[0] = !state[0];
                GallerySheets.setToggle(sw, state[0]);
                set.set(state[0]);
            });
        }
        return row;
    }

    private TextView caption(String text) {
        TextView t = GalleryUi.text(c, text, 15, GalleryUi.TEXT);
        t.setPadding(GalleryUi.dp(c, 18), GalleryUi.dp(c, 10), GalleryUi.dp(c, 18), 0);
        return t;
    }

    private TextView hint(String text) {
        TextView t = GalleryUi.text(c, text, 12, GalleryUi.MUTED);
        t.setPadding(GalleryUi.dp(c, 18), GalleryUi.dp(c, 6), GalleryUi.dp(c, 18), GalleryUi.dp(c, 4));
        return t;
    }
}
