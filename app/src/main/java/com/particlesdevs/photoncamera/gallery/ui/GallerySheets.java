package com.particlesdevs.photoncamera.gallery.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens;
import com.particlesdevs.photoncamera.gallery.model.GalleryView;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.List;

/**
 * P59b: the gallery's bottom sheets in the card style: delete («Удалить N снимков?»), folders (switch rows) and the
 * details of a photo (replaces exif_dialog). Each is a view built here (testable on its own) shown in a BottomSheetDialog:
 * drag down or tap the scrim to close, back closes it, focus goes into it.
 * P579 (owner, 2026-11-09: «Не забудь что у нас есть галерея и в ней надо стиль также обновить»): the camera's and the settings'
 * sheets - #1C1C1E with 30dp top corners and the grabber, 17sp semibold titles, rows grouped in #2C2C2E cards (24dp corners, 0.5dp
 * hairlines), the shared switch and segmented control, capsule buttons; the delete question a centred card (30dp corners, two
 * capsules side by side, the red «Удалить»).
 */
public final class GallerySheets {
    private GallerySheets() {}

    /** The sheet frame: the grabber on top, the content below; P579: #1C1C1E with 30dp top corners, no outline. */
    static LinearLayout frame(Context c) {
        LinearLayout sheet = new LinearLayout(c);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        float r = GalleryUi.dp(c, 30);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setColor(GalleryUi.SHEET);
        sheet.setBackground(bg);
        sheet.setPadding(GalleryUi.dp(c, 16), 0, GalleryUi.dp(c, 16), GalleryUi.dp(c, 20));
        View grab = new View(c);
        grab.setBackground(GalleryUi.round(c, 0x4DFFFFFF, 0, 2.5f));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(GalleryUi.dp(c, 36), GalleryUi.dp(c, 5));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.topMargin = GalleryUi.dp(c, 10);
        gp.bottomMargin = GalleryUi.dp(c, 14);
        grab.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        sheet.addView(grab, gp);
        return sheet;
    }

    static TextView title(Context c, String value) {
        TextView t = GalleryUi.text(c, value, 17, GalleryUi.TEXT);
        t.setPadding(GalleryUi.dp(c, 4), GalleryUi.dp(c, 2), GalleryUi.dp(c, 4), GalleryUi.dp(c, 2));
        t.setTypeface(com.particlesdevs.photoncamera.ui.style.Fonts.of(t.getContext(), 600));
        t.setAccessibilityHeading(true);
        return t;
    }

    static TextView sub(Context c, String value) {
        TextView t = GalleryUi.text(c, value, 13, GalleryUi.MUTED);
        t.setPadding(GalleryUi.dp(c, 4), GalleryUi.dp(c, 4), GalleryUi.dp(c, 4), GalleryUi.dp(c, 6));
        t.setLineSpacing(0, 1.3f);
        return t;
    }

    /** Shows {@code content} as a bottom sheet; returns the dialog. */
    public static BottomSheetDialog show(Context c, View content) {
        BottomSheetDialog d = new BottomSheetDialog(c);
        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        scroll.addView(content);
        d.setContentView(scroll);
        d.setOnShowListener(x -> {
            View host = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (host != null) {
                host.setBackgroundColor(0);
                BottomSheetBehavior.from(host).setState(BottomSheetBehavior.STATE_EXPANDED);
            }
            if (d.getWindow() != null) com.particlesdevs.photoncamera.ui.settings.ConceptFonts.follow(d.getWindow().getDecorView());
            content.setFocusable(true);
            content.requestFocus();
            content.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED);
        });
        d.show();
        return d;
    }

    // ---------------------------------------------------------------- delete

    public static String deleteTitle(int n) {
        if (n == 1) return Lang.t("Удалить снимок?", "Delete the photo?");
        return Lang.t("Удалить ", "Delete ") + GalleryUi.shots(n) + "?";
    }

    /**
     * «Удалить N снимков?», the count and size, «Отмена» and the red «Удалить»; P579: the concept's alert card - the title and the
     * text centred, the two capsules side by side.
     */
    public static LinearLayout delete(Context c, int n, long bytes, Runnable onDelete, Runnable onCancel) {
        // SIMPLE_UI §5 (the concept): what happens in words, then «Отмена» | «Удалить» side by side
        LinearLayout s = confirmCard(c, deleteTitle(n), n == 1 ? Lang.t("Файл удалится с телефона. Вернуть его будет нельзя.", "The file is removed from the phone. It cannot be brought back.")
                        : GalleryUi.shots(n) + " · " + GalleryUi.size(bytes) + ". "
                        + Lang.t("Файлы удалятся с телефона, вернуть их будет нельзя.", "The files are removed from the phone and cannot be brought back."),
                Lang.t("Отмена", "Cancel"), Lang.t("Удалить", "Delete"), onCancel, onDelete);
        s.findViewWithTag("confirm_no").setTag("delete_cancel");
        s.findViewWithTag("confirm_yes").setTag("delete_confirm");
        return s;
    }

    /**
     * P579: the concept's alert as a view - {@code title} and {@code message} centred on the alert card, then two capsules side by
     * side: {@code no} (grey, tagged "confirm_no") and {@code yes} in the red (tagged "confirm_yes").
     */
    public static LinearLayout confirmCard(Context c, String title, String message, String no, String yes, Runnable onNo, Runnable onYes) {
        LinearLayout s = new LinearLayout(c);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setBackground(alertCard(c));
        s.setPadding(GalleryUi.dp(c, 16), GalleryUi.dp(c, 20), GalleryUi.dp(c, 16), GalleryUi.dp(c, 16));
        TextView t = title(c, title);
        t.setGravity(Gravity.CENTER);
        s.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView what = GalleryUi.text(c, message, 14, 0xB3FFFFFF);
        what.setGravity(Gravity.CENTER);
        what.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wp.topMargin = GalleryUi.dp(c, 6);
        s.addView(what, wp);
        TextView noButton = bigButton(c, no, false);
        noButton.setOnClickListener(v -> onNo.run());
        noButton.setTag("confirm_no");
        TextView yesButton = bigButton(c, yes, true);
        yesButton.setOnClickListener(v -> onYes.run());
        yesButton.setTag("confirm_yes");
        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(noButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams yp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        yp.setMarginStart(GalleryUi.dp(c, 8));
        buttons.addView(yesButton, yp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = GalleryUi.dp(c, 18);
        s.addView(buttons, lp);
        s.setContentDescription(title);
        return s;
    }

    /** P579: {@link #confirmCard} shown as the centred alert; either button closes it, {@code onYes} runs after. */
    public static android.app.Dialog confirm(Context c, String title, String message, String no, String yes, Runnable onYes) {
        final android.app.Dialog[] d = new android.app.Dialog[1];
        LinearLayout card = confirmCard(c, title, message, no, yes, () -> {
            if (d[0] != null) d[0].dismiss();
        }, () -> {
            if (d[0] != null) d[0].dismiss();
            onYes.run();
        });
        d[0] = alert(c, card);
        return d[0];
    }

    /** P579: the alert's card - the concept's rgba(38, 38, 40, .95), 30dp corners, a 0.5dp light edge. */
    public static GradientDrawable alertCard(Context c) {
        GradientDrawable card = new GradientDrawable();
        card.setColor(0xF2262628);
        card.setCornerRadius(GalleryUi.dp(c, 30));
        card.setStroke(Math.max(1, GalleryUi.dp(c, .5f)), 0x24FFFFFF);
        return card;
    }

    public static void showDelete(Context c, int n, long bytes, Runnable onDelete) {
        final android.app.Dialog[] d = new android.app.Dialog[1];
        LinearLayout content = delete(c, n, bytes, () -> {
            if (d[0] != null) d[0].dismiss();
            onDelete.run();
        }, () -> {
            if (d[0] != null) d[0].dismiss();
        });
        d[0] = alert(c, content);
    }

    /**
     * P579: {@code card} as a centred alert (the settings' confirmation): 290dp wide, the screen behind dimmed, the card growing in
     * on a spring (an Animator: the system's animation scale applies); back and a tap outside close it.
     */
    public static android.app.Dialog alert(Context c, View card) {
        androidx.appcompat.app.AppCompatDialog d = new androidx.appcompat.app.AppCompatDialog(c);
        d.supportRequestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        d.setContentView(card);
        d.setCanceledOnTouchOutside(true);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setLayout(GalleryUi.dp(c, 290), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(.45f);
        }
        d.setOnShowListener(x -> {
            card.setFocusable(true);
            card.requestFocus();
            card.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED);
            if (!com.particlesdevs.photoncamera.ui.Motion.enabled(card)) return;
            card.setScaleX(.86f);
            card.setScaleY(.86f);
            card.setAlpha(0f);
            card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(350)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
        });
        d.show();
        return d;
    }

    /** P579: a capsule of the alert - grey, «Удалить» in the red (the concept's alert buttons). */
    static TextView bigButton(Context c, String label, boolean warn) {
        TextView b = GalleryUi.capsule(c, label, warn ? GalleryUi.DESTRUCTIVE : GalleryUi.SECONDARY);
        GradientDrawable fill = GalleryUi.round(c, com.particlesdevs.photoncamera.ui.settings.SettingsStyle.CAPSULE, 0, 999);
        b.setBackground(GalleryUi.pressable(fill));
        UiTokens.setTextSp(b, 17);
        return b;
    }

    // ---------------------------------------------------------------- folders

    /** A folder of the folders sheet. */
    public static final class Folder {
        public final String id, name, path;
        public boolean on;

        public Folder(String id, String name, String path, boolean on) {
            this.id = id;
            this.name = name;
            this.path = path;
            this.on = on;
        }
    }

    public interface FolderToggle {
        void toggled(@Nullable Folder folder, boolean on); // folder null: the «Показывать папки» switch
    }

    /** «Папки галереи»: the chips switch, then one switch row per folder (folder icon, name, path). */
    public static LinearLayout folders(Context c, boolean chipsShown, List<Folder> folders, FolderToggle toggle) {
        return folders(c, (Boolean) chipsShown, folders, toggle);
    }

    /** P502: {@code chipsShown} null: no chips row (the tabs replaced the folder chips; the «Фото» tab's folders only). */
    public static LinearLayout folders(Context c, @Nullable Boolean chipsShown, List<Folder> folders, FolderToggle toggle) {
        LinearLayout s = frame(c);
        s.addView(title(c, chipsShown == null ? Lang.t("Папки во вкладке «Фото»", "Folders in «Photos»") : Lang.t("Папки галереи", "Gallery folders")));
        s.addView(sub(c, chipsShown == null ? Lang.t("Какие папки показывает вкладка «Фото». Все папки есть в «Альбомах».",
                "Which folders the «Photos» tab shows. Every folder is in «Albums».")
                : Lang.t("Какие папки показывать в галерее", "Which folders the gallery shows")));
        if (chipsShown != null) {
            LinearLayout rows = column(c);
            rows.addView(switchRow(c, R.drawable.ui_ic_layers, Lang.t("Показывать папки", "Show folders"),
                    Lang.t("Лента папок над сеткой", "The folder chips above the grid"), chipsShown, on -> toggle.toggled(null, on)));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = GalleryUi.dp(c, 8);
            s.addView(rows, rp);
        }
        s.addView(GalleryUi.sectionLabel(c, Lang.t("Папки", "Folders")));
        LinearLayout list = column(c);
        for (Folder f : folders)
            list.addView(switchRow(c, R.drawable.ui_ic_folder, f.name, f.path, f.on, on -> {
                f.on = on;
                toggle.toggled(f, on);
            }));
        s.addView(list);
        return s;
    }

    interface Switched {
        void on(boolean on);
    }

    /** P579: a group of rows - one #2C2C2E card, 24dp corners, hairlines from the rows' text (past a 22dp icon). */
    static LinearLayout column(Context c) {
        return GalleryUi.group(c, 52);
    }

    /** A row: icon, title / subtitle, and the switch (P579: the shared switch, the row's tap slides it). */
    static LinearLayout switchRow(Context c, @DrawableRes int icon, String t, String sub, boolean on, Switched switched) {
        LinearLayout row = cardRow(c);
        row.addView(GalleryUi.icon(c, icon, GalleryUi.accent(c), 22));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView tt = GalleryUi.text(c, t, 16, GalleryUi.TEXT);
        TextView st = GalleryUi.text(c, sub, 13, GalleryUi.MUTED);
        st.setPadding(0, GalleryUi.dp(c, 3), 0, 0);
        texts.addView(tt);
        texts.addView(st);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tp.setMarginStart(GalleryUi.dp(c, 14));
        tp.setMarginEnd(GalleryUi.dp(c, 12));
        row.addView(texts, tp);
        View sw = toggleView(c, on);
        row.addView(sw);
        row.setContentDescription(t + ", " + sub);
        androidx.core.view.ViewCompat.setStateDescription(row, on ? Lang.t("включено", "on") : Lang.t("выключено", "off"));
        row.setTag(on);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> {
            boolean now = !(Boolean) row.getTag();
            row.setTag(now);
            setToggle(sw, now);
            androidx.core.view.ViewCompat.setStateDescription(row, now ? Lang.t("включено", "on") : Lang.t("выключено", "off"));
            switched.on(now);
        });
        return row;
    }

    /** A row of a group: P579, transparent on the group's card (its ripple clipped to the card's corners), 16dp sides. */
    static LinearLayout cardRow(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(GalleryUi.dp(c, GalleryUi.ROW_MIN));
        row.setBackground(GalleryUi.pressable(GalleryUi.round(c, 0, 0, 0)));
        row.setPadding(GalleryUi.dp(c, 16), GalleryUi.dp(c, 10), GalleryUi.dp(c, 16), GalleryUi.dp(c, 10));
        return row;
    }

    /**
     * The switch of a row; P579: the shared switch (IosSwitch: the theme's colour on, a knob that slides on a spring). The row
     * drives it ({@link #setToggle}): the switch itself takes no taps, so a tap anywhere on the row toggles once.
     */
    public static View toggleView(Context c, boolean on) {
        // the row takes every touch (a switch that saw the finger kept its pressed knob without the lift)
        com.particlesdevs.photoncamera.ui.controls.IosSwitch sw = new com.particlesdevs.photoncamera.ui.controls.IosSwitch(c) {
            @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) { return false; }
        };
        sw.setCheckedNow(on);
        sw.setClickable(false);
        sw.setFocusable(false);
        sw.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        sw.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sw;
    }

    /** The row's switch to {@code on}, sliding there. */
    public static void setToggle(View track, boolean on) {
        if (track instanceof com.particlesdevs.photoncamera.ui.controls.IosSwitch)
            ((com.particlesdevs.photoncamera.ui.controls.IosSwitch) track).setChecked(on);
    }

    // ---------------------------------------------------------------- P498: the view options

    public interface Picked {
        void picked(int index);
    }

    /**
     * A choice of a few: P579, the shared segmented control (the grey thumb glides to the chosen segment, its label in the accent;
     * it was a row of pills).
     */
    static View segmented(Context c, String[] labels, int chosen, Picked picked) {
        com.particlesdevs.photoncamera.ui.controls.IosSegmented seg = GalleryUi.segmented(c, labels, chosen, picked::picked);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        seg.setLayoutParams(lp);
        return seg;
    }

    /**
     * P579: a choice of many as a row of a group - {@code title} at the start, the chosen label and the up / down chevron at the end;
     * a tap opens the shared pull-down menu (IosMenu) at the row, the chosen item ticked in the accent. Tagged {@code tag}.
     */
    static LinearLayout menuRow(Context c, String title, String[] labels, int chosen, Picked picked, String tag) {
        LinearLayout row = cardRow(c);
        row.setTag(tag);
        TextView t = GalleryUi.text(c, title, 16, GalleryUi.TEXT);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView value = GalleryUi.text(c, labels[Math.max(0, Math.min(labels.length - 1, chosen))], 16, GalleryUi.MUTED);
        value.setSingleLine(true);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.setMarginStart(GalleryUi.dp(c, 10));
        row.addView(value, vp);
        ImageView chev = new ImageView(c);
        chev.setImageResource(R.drawable.cs_updown);
        chev.setImageTintList(android.content.res.ColorStateList.valueOf(GalleryUi.SECTION));
        chev.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(GalleryUi.dp(c, 16), GalleryUi.dp(c, 16));
        cp.setMarginStart(GalleryUi.dp(c, 6));
        row.addView(chev, cp);
        final int[] now = {chosen};
        row.setContentDescription(title);
        androidx.core.view.ViewCompat.setStateDescription(row, value.getText());
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> com.particlesdevs.photoncamera.ui.controls.IosMenu.show(row, title, labels, now[0], i -> {
            if (i == now[0]) return;
            now[0] = i;
            value.setText(labels[i]);
            androidx.core.view.ViewCompat.setStateDescription(row, labels[i]);
            picked.picked(i);
        }));
        return row;
    }

    /** The years' (several at once) chips: P579, the accent capsule when chosen, grey otherwise (GalleryUi.paintChip). */
    private static void restyle(TextView chip, boolean active) {
        GalleryUi.paintChip(chip, active);
    }

    /** A caption over a control or a group: the section label (aligned with the rows' text inside the cards), 16dp above. */
    private static TextView caption(Context c, String text) {
        TextView t = GalleryUi.sectionLabel(c, text);
        t.setPadding(GalleryUi.dp(c, 16), GalleryUi.dp(c, 16), GalleryUi.dp(c, 16), GalleryUi.dp(c, 8));
        return t;
    }

    /**
     * P498 (owner, 2026-11-04: «полноценная галерея», «отображать только фото / только видео / фото и видео»): «Вид галереи» - what
     * kind of media, which file type, the order and the grid's columns. {@code changed} runs after every pick (the grid applies it).
     */
    public static LinearLayout viewOptions(Context c, Runnable changed) {
        return viewOptions(c, changed, null);
    }

    /**
     * P622 (owner, 2026-11-11: «Добавь в настройки галереи возможность выбора такого вида просмотра»): the sheet with «Вид» first -
     * «Сетка», «Список» or «Лента» ({@code view} hears the pick; the page changes behind the sheet, animated). Tagged «gallery_mode».
     */
    public static LinearLayout viewOptions(Context c, Runnable changed,
                                           @androidx.annotation.Nullable java.util.function.Consumer<com.particlesdevs.photoncamera.gallery.model.GalleryLayout.Mode> view) {
        LinearLayout s = frame(c);
        s.addView(title(c, Lang.t("Вид галереи", "Gallery view")));
        s.addView(sub(c, Lang.t("Что показывать и в каком порядке. Колонки меняются и щипком по сетке.",
                "What to show and in which order. Pinch the grid to change the columns too.")));
        if (view != null) {
            com.particlesdevs.photoncamera.gallery.model.GalleryLayout.Mode[] modes = com.particlesdevs.photoncamera.gallery.model.GalleryLayout.Mode.values();
            String[] names = new String[modes.length];
            for (int i = 0; i < modes.length; i++) names[i] = modes[i].label();
            s.addView(caption(c, Lang.t("Вид", "View")));
            View seg = segmented(c, names, com.particlesdevs.photoncamera.gallery.model.GalleryLayout.mode(c).ordinal(), i -> view.accept(modes[i]));
            seg.setTag("gallery_mode");
            s.addView(seg);
            TextView hint = sub(c, Lang.t("Сетка - карточки по дням. Список - строки с именем, размером и метками. Лента - плитки во всю ширину по месяцам; щипок и переключатель внизу - годы, месяцы, все фото.",
                    "Grid: cards by day. List: rows with the name, size and tags. Timeline: edge-to-edge tiles by month; pinch or the control below for years, months, all photos."));
            s.addView(hint);
        }
        GalleryView.Media media = GalleryView.media(c);
        s.addView(caption(c, Lang.t("Показывать", "Show")));
        s.addView(segmented(c, new String[]{Lang.t("Фото и видео", "Photos and videos"), Lang.t("Только фото", "Photos only"),
                Lang.t("Только видео", "Videos only")}, media.ordinal(), i -> {
            GalleryView.setMedia(c, GalleryView.Media.values()[i]);
            changed.run();
        }));
        GalleryView.Type type = GalleryView.type(c);
        s.addView(caption(c, Lang.t("Тип файла", "File type")));
        s.addView(segmented(c, new String[]{Lang.t("Все", "All"), "RAW", "JPEG", "HEIC", Lang.t("Видео", "Video")}, type.ordinal(), i -> {
            GalleryView.setType(c, GalleryView.Type.values()[i]);
            changed.run();
        }));
        s.addView(caption(c, Lang.t("Порядок", "Order")));
        s.addView(segmented(c, new String[]{Lang.t("Сначала новые", "Newest first"), Lang.t("Сначала старые", "Oldest first")},
                GalleryView.oldestFirst(c) ? 1 : 0, i -> {
                    GalleryView.setOldestFirst(c, i == 1);
                    changed.run();
                }));
        s.addView(caption(c, Lang.t("Колонки", "Columns")));
        int min = GalleryView.MIN_COLUMNS, max = GalleryView.MAX_COLUMNS;
        String[] cols = new String[max - min + 1];
        for (int i = 0; i < cols.length; i++) cols[i] = String.valueOf(min + i);
        s.addView(segmented(c, cols, GalleryView.columns(c) - min, i -> {
            GalleryView.setColumns(c, min + i);
            changed.run();
        }));
        return s;
    }

    /**
     * P502 (owner, 2026-11-05: the reference video's «Фильтр» sheet): what the «Фото» tab shows - kind (photos / videos), format,
     * status (favourites) and time (the library's years, several at once) - with «Сбросить» and «Готово». {@code changed} runs after
     * every pick (the grid applies it at once, the sheet stays).
     */
    public static void showFilter(Context c, java.util.Collection<Integer> libraryYears, Runnable changed) {
        LinearLayout s = frame(c);
        s.addView(title(c, Lang.t("Фильтр", "Filter")));
        GalleryView.Media media = GalleryView.media(c);
        s.addView(caption(c, Lang.t("Показывать", "Show")));
        s.addView(segmented(c, new String[]{Lang.t("Фото и видео", "Photos and videos"), Lang.t("Только фото", "Photos only"),
                Lang.t("Только видео", "Videos only")}, media.ordinal(), i -> {
            GalleryView.setMedia(c, GalleryView.Media.values()[i]);
            changed.run();
        }));
        s.addView(caption(c, Lang.t("Формат", "Format")));
        s.addView(segmented(c, new String[]{Lang.t("Все", "All"), "RAW", "JPEG", "HEIC", Lang.t("Видео", "Video")}, GalleryView.type(c).ordinal(), i -> {
            GalleryView.setType(c, GalleryView.Type.values()[i]);
            changed.run();
        }));
        s.addView(caption(c, Lang.t("Статус", "Status")));
        s.addView(segmented(c, new String[]{Lang.t("Все", "All"), "♥ " + Lang.t("Избранное", "Favourites")},
                GalleryView.favouritesOnly(c) ? 1 : 0, i -> {
                    GalleryView.setFavouritesOnly(c, i == 1);
                    changed.run();
                }));
        if (!libraryYears.isEmpty()) {
            s.addView(caption(c, Lang.t("Время", "Time")));
            android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(c);
            scroll.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            scroll.addView(row);
            java.util.Set<Integer> chosen = new java.util.TreeSet<>(GalleryView.years(c));
            for (Integer y : libraryYears) {
                TextView chip = GalleryUi.chip(c, String.valueOf(y), -1, chosen.contains(y));
                com.particlesdevs.photoncamera.ui.Motion.press(chip);
                chip.setOnClickListener(v -> {
                    if (!chosen.remove(y)) chosen.add(y);
                    restyle(chip, chosen.contains(y));
                    com.particlesdevs.photoncamera.ui.Motion.pop(v);
                    GalleryView.setYears(c, chosen);
                    changed.run();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMarginEnd(GalleryUi.dp(c, 8));
                row.addView(chip, lp);
            }
            s.addView(scroll);
        }
        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = GalleryUi.dp(c, 22);
        s.addView(buttons, bl);
        final com.google.android.material.bottomsheet.BottomSheetDialog[] dialog = new com.google.android.material.bottomsheet.BottomSheetDialog[1];
        TextView reset = wideButton(c, Lang.t("Сбросить", "Reset"), false);
        reset.setOnClickListener(v -> {
            GalleryView.clearFilter(c);
            changed.run();
            if (dialog[0] != null) dialog[0].dismiss();
        });
        TextView done = wideButton(c, Lang.t("Готово", "Done"), true);
        done.setOnClickListener(v -> {
            if (dialog[0] != null) dialog[0].dismiss();
        });
        buttons.addView(reset, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        dp.setMarginStart(GalleryUi.dp(c, 10));
        buttons.addView(done, dp);
        dialog[0] = show(c, s);
    }

    /** A wide button of a sheet's footer; P579: the accent capsule with dark ink, or the grey one. */
    static TextView wideButton(Context c, String label, boolean accent) {
        return GalleryUi.capsule(c, label, accent ? GalleryUi.PRIMARY : GalleryUi.SECONDARY);
    }

    /** One action of the viewer's «Ещё» sheet. */
    public static final class Action {
        @DrawableRes public final int icon;
        public final String label;
        public final Runnable run;

        public Action(@DrawableRes int icon, String label, Runnable run) {
            this.icon = icon;
            this.label = label;
            this.run = run;
        }
    }

    /** P498: the viewer's «Ещё»: one row per action (icon, label) in one group (P579); a tap closes the sheet and runs it. */
    public static void showActions(Context c, String heading, List<Action> actions) {
        final BottomSheetDialog[] d = new BottomSheetDialog[1];
        LinearLayout s = frame(c);
        s.addView(title(c, heading));
        LinearLayout rows = column(c);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = GalleryUi.dp(c, 12);
        s.addView(rows, rp);
        List<View> shown = new java.util.ArrayList<>();
        for (Action a : actions) {
            LinearLayout row = cardRow(c);
            row.setClickable(true);
            row.setFocusable(true);
            row.setContentDescription(a.label);
            row.addView(GalleryUi.icon(c, a.icon, GalleryUi.accent(c), 22));
            TextView t = GalleryUi.text(c, a.label, 16, GalleryUi.TEXT);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            tp.setMarginStart(GalleryUi.dp(c, 14));
            row.addView(t, tp);
            ImageView chev = GalleryUi.icon(c, R.drawable.cs_chevron, GalleryUi.SECTION, 16);
            chev.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (chev.getDrawable() != null) chev.getDrawable().mutate().setAutoMirrored(true); // points the reading way in right to left
            row.addView(chev);
            row.setOnClickListener(v -> {
                if (d[0] != null) d[0].dismiss();
                a.run.run();
            });
            rows.addView(row);
            shown.add(row);
        }
        d[0] = show(c, s);
        com.particlesdevs.photoncamera.ui.Motion.enter(shown, 60, 28);
    }

    // ---------------------------------------------------------------- details

    /** What the details sheet shows (null values: not in the file, the card / row is hidden or shows a dash). */
    public static final class Details {
        public String fileName, when, folder;
        public String iso, shutter, fnum, focal;
        @Nullable public String route;
        public String format;
        public boolean ultraHdr;
        @DrawableRes public int formatIcon = R.drawable.ic_shade_jpeg;
        @Nullable public String lens, device;
        public int width, height;
        public long bytes;
        /** P492: the «Подробнее» rows (title, value), PhotoMore.rows. */
        public java.util.List<String[]> more = new java.util.ArrayList<>();
    }

    /**
     * SIMPLE_UI §5 (the concept's «Сведения»): when / with what / how big in one card, «Как снято», the merge in words. P579: grouped
     * cards (#2C2C2E, 24dp corners, hairlines) under 12sp captions - «Как снято» one card of four values in two lines.
     */
    public static LinearLayout details(Context c, Details d, @Nullable View histogram) {
        LinearLayout s = frame(c);
        s.addView(title(c, Lang.t("Сведения", "Details")));
        if (!GalleryUi.empty(d.fileName)) s.addView(sub(c, d.fileName));
        LinearLayout card = GalleryUi.group(c, 64);
        card.setTag("details_rows");
        card.addView(detailRow(c, R.drawable.ui_ic_clock, d.when == null ? "—" : d.when,
                GalleryUi.empty(d.folder) ? null : Lang.t("Папка ", "Folder ") + d.folder, "when"));
        card.addView(detailRow(c, R.drawable.ui_ic_lens, GalleryUi.empty(d.lens) ? Lang.t("Объектив не записан", "No lens in the file") : d.lens,
                d.device, "lens"));
        String mp = d.width > 0 && d.height > 0 ? Math.max(1, Math.round(d.width * (float) d.height / 1e6f)) + Lang.t(" Мп", " MP") : null;
        String big = mp == null ? GalleryUi.size(d.bytes) : mp + " · " + GalleryUi.size(d.bytes);
        String what = (d.width > 0 && d.height > 0 ? d.width + " × " + d.height + " · " : "") + (d.format == null ? "" : d.format)
                + (d.ultraHdr ? " · Ultra HDR" : "");
        card.addView(detailRow(c, R.drawable.ui_ic_size, big, what, "format"));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = GalleryUi.dp(c, 8);
        s.addView(card, cardParams);
        s.addView(caption(c, Lang.t("Как снято", "How it was shot")));
        LinearLayout grid = GalleryUi.group(c, 0);
        grid.setTag("details_shot");
        String[][] kv = {{Lang.t("Чувствительность", "Sensitivity"), d.iso}, {Lang.t("Выдержка", "Shutter speed"), d.shutter},
                {Lang.t("Диафрагма", "Aperture"), d.fnum}, {Lang.t("Фокусное расстояние", "Focal length"), d.focal}};
        for (int r = 0; r < 2; r++) {
            LinearLayout line = new LinearLayout(c);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setDividerDrawable(verticalHairline(c));
            line.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
            for (int k = 0; k < 2; k++) {
                String[] p = kv[r * 2 + k];
                LinearLayout cell = new LinearLayout(c);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setPadding(GalleryUi.dp(c, 16), GalleryUi.dp(c, 11), GalleryUi.dp(c, 12), GalleryUi.dp(c, 11));
                cell.addView(GalleryUi.fitText(c, p[0], 12.5f, GalleryUi.MUTED));
                TextView v = GalleryUi.fitText(c, p[1] == null ? "—" : p[1], 16, GalleryUi.TEXT);
                v.setTypeface(com.particlesdevs.photoncamera.ui.style.Fonts.of(v.getContext(), 600));
                v.setFontFeatureSettings("tnum");
                LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                vp.topMargin = GalleryUi.dp(c, 3);
                cell.addView(v, vp);
                cell.setContentDescription(p[0] + " " + (p[1] == null ? "—" : p[1]));
                line.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            }
            grid.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        s.addView(grid);
        if (!d.more.isEmpty()) { // P492: the rest of the file's EXIF, one row each
            s.addView(caption(c, Lang.t("Подробнее", "More")));
            LinearLayout more = GalleryUi.group(c, 16);
            more.setTag("details_more");
            for (int i = 0; i < d.more.size(); i++) {
                String[] row = d.more.get(i);
                LinearLayout line = new LinearLayout(c);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setMinimumHeight(GalleryUi.dp(c, 44));
                line.setGravity(Gravity.CENTER_VERTICAL);
                line.setPadding(GalleryUi.dp(c, 16), GalleryUi.dp(c, 10), GalleryUi.dp(c, 16), GalleryUi.dp(c, 10));
                TextView k = GalleryUi.text(c, row[0], 14, GalleryUi.TEXT);
                TextView val = GalleryUi.text(c, row[1], 14, GalleryUi.MUTED);
                val.setGravity(Gravity.END);
                val.setTextIsSelectable(true);
                line.addView(k, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f);
                vp.setMarginStart(GalleryUi.dp(c, 10));
                line.addView(val, vp);
                line.setContentDescription(row[0] + " " + row[1]);
                more.addView(line);
            }
            s.addView(more);
        }
        if (d.route != null) {
            View note = com.particlesdevs.photoncamera.ui.settings.custompreferences.LutChangesPreference.noteView(c, Lang.t(
                    "Снимок склеен из нескольких кадров (" + d.route + "): меньше шума, светлые и тёмные места сохранены.",
                    "The photo is merged from several frames (" + d.route + "): less noise, bright and dark areas kept."));
            note.setTag("route");
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            np.topMargin = GalleryUi.dp(c, 12);
            s.addView(note, np);
        }
        if (histogram != null) {
            s.addView(caption(c, Lang.t("Гистограмма", "Histogram")));
            FrameLayout histo = new FrameLayout(c);
            histo.setBackground(GalleryUi.card(c, GalleryUi.CARD, 24));
            histo.setClipToOutline(true);
            int pad = GalleryUi.dp(c, 12);
            histo.setPadding(pad, pad, pad, pad);
            if (histogram.getParent() instanceof ViewGroup) ((ViewGroup) histogram.getParent()).removeView(histogram);
            histogram.setContentDescription(Lang.t("Гистограмма RGB", "RGB histogram"));
            histo.addView(histogram, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            s.addView(histo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, GalleryUi.dp(c, 124)));
        }
        s.setContentDescription(d.fileName);
        return s;
    }

    /** P579: the hairline between two values of a line (0.5dp wide, inset from the top and bottom). */
    static android.graphics.drawable.Drawable verticalHairline(Context c) {
        GradientDrawable line = new GradientDrawable();
        line.setColor(GalleryUi.LINE);
        line.setSize(Math.max(1, GalleryUi.dp(c, .5f)), 1);
        return new android.graphics.drawable.InsetDrawable(line, 0, GalleryUi.dp(c, 10), 0, GalleryUi.dp(c, 10));
    }

    /**
     * A row of the concept's details card: the icon tile, the line and the 60 % one under it. P579: the settings' 32dp tonal tile
     * (#3A3A3C, 8dp corners), the line 16sp semibold.
     */
    static LinearLayout detailRow(Context c, @DrawableRes int icon, String main, @Nullable String under, String tag) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(GalleryUi.dp(c, 16), GalleryUi.dp(c, 10), GalleryUi.dp(c, 16), GalleryUi.dp(c, 10));
        row.setMinimumHeight(GalleryUi.dp(c, 58));
        FrameLayout tile = new FrameLayout(c);
        tile.setBackground(GalleryUi.round(c, 0xFF3A3A3C, 0, 8));
        tile.addView(GalleryUi.icon(c, icon, GalleryUi.TEXT, 20), new FrameLayout.LayoutParams(GalleryUi.dp(c, 20), GalleryUi.dp(c, 20), Gravity.CENTER));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(GalleryUi.dp(c, 32), GalleryUi.dp(c, 32));
        tp.setMarginEnd(GalleryUi.dp(c, 16));
        row.addView(tile, tp);
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView m = GalleryUi.text(c, main, 16, GalleryUi.TEXT);
        m.setTypeface(com.particlesdevs.photoncamera.ui.style.Fonts.of(m.getContext(), 600));
        texts.addView(m);
        if (!GalleryUi.empty(under)) {
            TextView u = GalleryUi.text(c, under, 13, GalleryUi.MUTED);
            u.setPadding(0, GalleryUi.dp(c, 3), 0, 0);
            texts.addView(u);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setTag(tag);
        row.setContentDescription(main + (GalleryUi.empty(under) ? "" : ", " + under));
        return row;
    }
}
