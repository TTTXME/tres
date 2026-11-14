package com.scamera.app.gallery.editor;

import android.app.Activity;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.scamera.app.R;
import com.scamera.app.gallery.files.ImageFile;
import com.scamera.app.gallery.helper.Constants;
import com.scamera.app.gallery.ui.GallerySheets;
import com.scamera.app.gallery.ui.GalleryUi;
import com.scamera.app.gallery.viewmodel.GalleryViewModel;
import com.scamera.app.util.Lang;

/**
 * P351 (owner, 2026-10-26: «нам нужен редактор изображения в галерее с базовыми функциями как в мобильном приложении
 * лайтрума»): the gallery's photo editor - «Изменить» in the viewer opens it (a long press, or «Открыть в другом приложении» in
 * its menu, still hands the photo to another app's editor). Tabs: Кадр (ratio, rotate, flip, straighten, the crop frame),
 * Стили, Свет (+ Кривая), Цвет (basic, the 8-band mixer, grading, B&W), Эффекты, Детали; undo / redo, «Сбросить всё», copy /
 * paste of settings, a live histogram, a long press on the picture shows the photo without the tone edits. «Сохранить копию»
 * writes a new JPEG next to the original (EditExporter); back with unsaved edits asks first. Opened by another app through
 * ACTION_EDIT it returns the copy's URI.
 * P360 (owner, 2026-10-27: «сделай с более красивым интерфейсом с красивыми анимациями»): the editor arrives (the picture
 * settles, the bars rise), the tab bar's selection glides between tabs (pill, colour, icon bounce), panels slide in from the
 * side of the tab, their rows come in one after another, buttons give under a finger, pills and the save card fade / scale in
 * (EditMotion; the system's animation scale applies).
 * P367 (owner, 2026-10-27: «В редакторе фото дай возможность кропать фото, при этом оно должно быть в полном разрешении» -
 * «Я имею ввиду кропать предпосмотр во время редактирования»): outside «Кадр» the picture zooms for a closer look - pinch,
 * drag, double tap fit <-> 100 % (EditorView / EditZoom) - and past the preview's resolution shows the original's pixels as the
 * copy will have them; a pill gives the zoom (and «детали…» while they load). The view only: the copy, undo and the crop do
 * not change; «Кадр», a geometry change, saving and the first back return to the fit. A second finger ends the long-press
 * «before». Test hook: ExternalEdit.ARG_ZOOM.
 * P619 (owner, 2026-11-11: «Добавь возможность в нашем редакторе в галерее применять луты из камеры и симуляции плёнок»): the «LUT»
 * tab after «Стили» - the camera's LUTs and film stocks on the finished colour (EditPanels.look, EditLook); their tables load off the
 * main thread (EditorViewModel.lookLoader) and go to the picture, the zoom and the copy.
 * P633 (owner, 2026-11-11: «По поводу вотермарок, дай возможность их редактировать у нас в галерее, добавлять, убирать на наших фото»):
 * the «Знак» tab (EditWatermarkPanel) - the photo's own watermark taken off from the pixels its file kept (EditSource.watermark), a
 * watermark added or changed with the camera's settings; the picture shows it live (WatermarkOverlayView), the copy draws it with
 * the camera's code (EditExporter).
 */
public class PhotoEditorFragment extends Fragment implements EditPanels.Host {
    private static final String KEY_STATE = "edit_state", KEY_TAB = "edit_tab";
    static final int TAB_CROP = 0, TAB_STYLES = 1, TAB_LOOK = 2, TAB_LIGHT = 3, TAB_COLOUR = 4, TAB_EFFECTS = 5, TAB_DETAIL = 6,
            TAB_WATERMARK = 7, TAB_DEVELOP = 8;
    /** P665: the tabs in the bar's order (the ids stay: a saved tab keeps meaning the same one); «Проявка» after «Кадр». */
    static final int[] TAB_ORDER = {TAB_CROP, TAB_DEVELOP, TAB_STYLES, TAB_LOOK, TAB_LIGHT, TAB_COLOUR, TAB_EFFECTS, TAB_DETAIL, TAB_WATERMARK};

    /** P665: a tab's place in the bar. */
    static int position(int tab) {
        for (int i = 0; i < TAB_ORDER.length; i++) if (TAB_ORDER[i] == tab) return i;
        return tab;
    }
    private static final String PREFS = "scam_editor", PREF_CLIPBOARD = "clipboard";
    /** Copied settings («Копировать настройки»), kept for the app's lifetime and in prefs. */
    @Nullable private static EditState clipboard;

    private EditorViewModel vm;
    private GalleryViewModel gallery;
    private Uri uri;
    private String name, path, mime;
    private boolean external;
    private int tab = TAB_LIGHT;
    @Nullable private EditState restore;

    private EditorView gl;
    private CropOverlayView overlay;
    private EditHistogramView histogram;
    private TextView beforePill, notePill, exportText;
    /** P367: the zoom pill («250 %», «100 % · детали…») and what the editor view last said about the zoom. */
    @Nullable private TextView zoomPill;
    private int zoomPercent, zoomDetail;
    private boolean zoomed, zoomPillOn;
    private final Runnable hideNote = () -> {
        if (notePill != null) EditMotion.show(notePill, false, GalleryUi.dp(notePill.getContext(), 12));
    };
    private ProgressBar spinner, exportBar;
    private FrameLayout imageArea, exportOverlay;
    private ScrollView panelScroll;
    private LinearLayout tabBar;
    /** P360: the tab buttons, their icons, names and selection pills (built once, animated on a change). */
    private LinearLayout[] tabButtons;
    private ImageView[] tabIcons;
    private TextView[] tabNames;
    private android.graphics.drawable.GradientDrawable[] tabPills;
    private boolean entered;
    private View cover;
    private View undo, redo, save;
    private EditPanels panels;
    /** P633: the watermark's live preview over the picture, the copy's pixel limit it lays out for, «Авто»'s picture's key. */
    @Nullable private WatermarkOverlayView watermarkView;
    private long watermarkOutMax = EditSource.OUT_MAX;
    @Nullable private String watermarkInkKey;
    private boolean before;
    @Nullable private float[] straightenAnchor;
    private boolean applyingStyle;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle saved) {
        Bundle a = requireArguments();
        uri = Uri.parse(a.getString(ExternalEdit.ARG_URI, ""));
        name = a.getString(ExternalEdit.ARG_NAME, "");
        path = a.getString(ExternalEdit.ARG_PATH);
        mime = a.getString(ExternalEdit.ARG_MIME);
        external = a.getBoolean(ExternalEdit.ARG_EXTERNAL, false);
        vm = new ViewModelProvider(this).get(EditorViewModel.class);
        gallery = new ViewModelProvider(requireActivity()).get(GalleryViewModel.class);
        if (saved != null) {
            tab = saved.getInt(KEY_TAB, TAB_LIGHT);
            if (vm.history == null) restore = EditState.fromJson(saved.getString(KEY_STATE));
        }
        Context c = requireContext();
        panels = new EditPanels(c, this);
        View root = build(c);
        // P367 test hook: start zoomed (a fresh editor only: a recreated one keeps the fit)
        if (saved == null && a.containsKey(ExternalEdit.ARG_ZOOM))
            gl.startZoom(a.getFloat(ExternalEdit.ARG_ZOOM, 1f), a.getFloat(ExternalEdit.ARG_ZOOM_CX, 0.5f), a.getFloat(ExternalEdit.ARG_ZOOM_CY, 0.5f));
        vm.source.observe(getViewLifecycleOwner(), this::onSource);
        // P665: a developed picture or the develop's status: the picture swaps, the «Проявка» tab says what happens
        vm.developed.observe(getViewLifecycleOwner(), d -> {
            render();
            if (tab == TAB_DEVELOP) panels.refresh();
        });
        vm.developStatus.observe(getViewLifecycleOwner(), t -> {
            if (tab == TAB_DEVELOP) panels.refresh();
        });
        vm.error.observe(getViewLifecycleOwner(), e -> {
            if (e == null) return;
            // the system toast: leaving may close the activity (an edit for another app) and take a toast in its window along
            Toast.makeText(c, Lang.t("Не удалось открыть снимок: ", "Cannot open the photo: ") + e, Toast.LENGTH_LONG).show();
            leave(false);
        });
        vm.exportProgress.observe(getViewLifecycleOwner(), p -> {
            if (p == null || !vm.exporting()) return;
            exportOverlay.setVisibility(View.VISIBLE);
            exportBar.setProgress(Math.round(p * 1000));
            exportText.setText(Lang.t("Сохраняю копию… ", "Saving a copy… ") + Math.round(p * 100) + " %");
        });
        vm.exported.observe(getViewLifecycleOwner(), r -> {
            if (r == null) return;
            vm.exported.setValue(null);
            onExported(r);
        });
        vm.exportError.observe(getViewLifecycleOwner(), e -> {
            if (e == null) return;
            vm.exportError.setValue(null);
            exportOverlay.setVisibility(View.GONE);
            com.scamera.app.gallery.ui.GalleryToast.show(c, Lang.t("Не удалось сохранить: ", "Saving failed: ") + e, true);
        });
        vm.load(uri, name, mime, path);
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBack(true);
            }
        });
        if (clipboard == null) {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            clipboard = EditState.fromJson(p.getString(PREF_CLIPBOARD, null));
        }
        return root;
    }

    // ---------------------------------------------------------------- layout

    private View build(Context c) {
        // P579 (owner, 2026-11-09: «Не забудь что у нас есть галерея и в ней надо стиль также обновить»): the picture on black as in
        // the viewer, the tools on the #1C1C1E sheet with 30dp top corners, 40dp round buttons, the tabs' grey thumb with the accent
        FrameLayout root = new FrameLayout(c);
        root.setBackgroundColor(0xFF000000);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(GalleryUi.dp(c, 12), GalleryUi.dp(c, 14), GalleryUi.dp(c, 12), GalleryUi.dp(c, 6));
        View close = iconButton(c, R.drawable.ui_ic_close, Lang.t("Закрыть редактор", "Close the editor"), v -> onBack(false));
        GalleryUi.setEnabledLook(close, true);
        top.addView(close, new LinearLayout.LayoutParams(GalleryUi.dp(c, 40), GalleryUi.dp(c, 40)));
        TextView title = GalleryUi.fitText(c, name, 13, GalleryUi.MUTED);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tp.leftMargin = tp.rightMargin = GalleryUi.dp(c, 8);
        top.addView(title, tp);
        undo = iconButton(c, R.drawable.ui_ic_undo, Lang.t("Отменить", "Undo"), v -> undo());
        redo = iconButton(c, R.drawable.ui_ic_redo, Lang.t("Повторить", "Redo"), v -> redo());
        View more = iconButton(c, R.drawable.ui_ic_more, Lang.t("Ещё", "More"), this::menu);
        for (View b : new View[]{undo, redo, more}) {
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(GalleryUi.dp(c, 40), GalleryUi.dp(c, 40));
            bp.setMarginStart(GalleryUi.dp(c, 6));
            top.addView(b, bp);
        }
        for (View b : new View[]{close, undo, redo, more}) EditMotion.press(b);
        TextView saveBtn = GalleryUi.capsule(c, Lang.t("Сохранить копию", "Save a copy"), GalleryUi.PRIMARY); // P579: the accent capsule
        com.scamera.app.circularbarlib.ui.UiTokens.setTextSp(saveBtn, 14);
        saveBtn.setMinHeight(GalleryUi.dp(c, 40));
        saveBtn.setPadding(GalleryUi.dp(c, 14), 0, GalleryUi.dp(c, 14), 0);
        saveBtn.setOnClickListener(v -> save());
        save = saveBtn;
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMarginStart(GalleryUi.dp(c, 8));
        top.addView(saveBtn, sp);
        col.addView(top);

        imageArea = new FrameLayout(c);
        gl = new EditorView(c);
        imageArea.addView(gl, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // P360: the cover the picture is revealed from (EditMotion.enter)
        cover = new View(c);
        cover.setBackgroundColor(0xFF000000);
        cover.setClickable(false);
        imageArea.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        watermarkView = new WatermarkOverlayView(c);
        watermarkView.setVisibility(View.GONE);
        imageArea.addView(watermarkView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = new CropOverlayView(c);
        overlay.setListener(this::onCrop);
        imageArea.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        histogram = new EditHistogramView(c);
        // P367: the zoom pill, where the «before» pill goes (it hides while that one shows)
        zoomPill = GalleryUi.pill(c, "", false);
        zoomPill.setVisibility(View.GONE);
        FrameLayout.LayoutParams zp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        zp.topMargin = zp.leftMargin = GalleryUi.dp(c, 12);
        imageArea.addView(zoomPill, zp);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(GalleryUi.dp(c, 132), GalleryUi.dp(c, 60), Gravity.TOP | Gravity.END);
        hp.topMargin = hp.rightMargin = GalleryUi.dp(c, 10);
        imageArea.addView(histogram, hp);
        beforePill = GalleryUi.pill(c, Lang.t("Оригинал", "Original"), true);
        beforePill.setVisibility(View.GONE);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        bp.topMargin = bp.leftMargin = GalleryUi.dp(c, 12);
        imageArea.addView(beforePill, bp);
        notePill = GalleryUi.pill(c, "", false);
        notePill.setVisibility(View.GONE);
        notePill.setMaxLines(3);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        np.bottomMargin = np.leftMargin = np.rightMargin = GalleryUi.dp(c, 12);
        imageArea.addView(notePill, np);
        spinner = new ProgressBar(c);
        imageArea.addView(spinner, new FrameLayout.LayoutParams(GalleryUi.dp(c, 48), GalleryUi.dp(c, 48), Gravity.CENTER));
        col.addView(imageArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        // P367: pinch zooms about the fingers (no quick scale: a double tap toggles fit <-> 100 %)
        final ScaleGestureDetector pinch = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                return tab != TAB_CROP && gl != null && gl.zoomable();
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                if (gl != null) gl.zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        pinch.setQuickScaleEnabled(false);
        GestureDetector press = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                if (tab == TAB_CROP || pinch.isInProgress()) return;
                before = true;
                EditMotion.show(beforePill, true, -GalleryUi.dp(c, 6));
                updateZoomPill();
                render();
            }

            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            /** P367: one finger (or the fingers' centre during a pinch) pans a zoomed picture. */
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (tab == TAB_CROP || before || gl == null) return false;
                gl.panBy(-dx, -dy);
                return true;
            }

            /** P367: fit <-> 100 % centred on the tapped point. */
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (tab == TAB_CROP || before || gl == null) return false;
                gl.toggleZoom(e.getX(), e.getY());
                return true;
            }
        });
        gl.setOnTouchListener((v, e) -> {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN && gl != null) gl.stopZoomAnimation();
            pinch.onTouchEvent(e);
            press.onTouchEvent(e);
            // P367: a second finger is a pinch, not «before»; lifting every finger ends «before» as always
            if (before && (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
                before = false;
                EditMotion.show(beforePill, false, -GalleryUi.dp(c, 6));
                updateZoomPill();
                render();
            }
            return true;
        });
        imageArea.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateOverlay());

        panelScroll = new ScrollView(c);
        panelScroll.setFillViewport(true);
        android.graphics.drawable.GradientDrawable sheetBg = new android.graphics.drawable.GradientDrawable();
        float sheetR = GalleryUi.dp(c, 30);
        sheetBg.setCornerRadii(new float[]{sheetR, sheetR, sheetR, sheetR, 0, 0, 0, 0});
        sheetBg.setColor(GalleryUi.SHEET);
        panelScroll.setBackground(sheetBg);
        panelScroll.setClipToOutline(true);
        panelScroll.setPadding(0, GalleryUi.dp(c, 8), 0, 0);
        panelScroll.setClipToPadding(false);
        int screenH = c.getResources().getDisplayMetrics().heightPixels;
        col.addView(panelScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(GalleryUi.dp(c, 300), (int) (screenH * 0.36f))));

        tabBar = new LinearLayout(c);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(GalleryUi.SHEET);
        tabBar.setPadding(GalleryUi.dp(c, 8), GalleryUi.dp(c, 6), GalleryUi.dp(c, 8), GalleryUi.dp(c, 14));
        col.addView(tabBar);
        buildTabs(c);

        exportOverlay = new FrameLayout(c);
        exportOverlay.setBackgroundColor(0xCC000000);
        exportOverlay.setClickable(true);
        exportOverlay.setVisibility(View.GONE);
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(GallerySheets.alertCard(c)); // P579: the alert's card (30dp corners)
        card.setPadding(GalleryUi.dp(c, 20), GalleryUi.dp(c, 20), GalleryUi.dp(c, 20), GalleryUi.dp(c, 20));
        exportText = GalleryUi.text(c, Lang.t("Сохраняю копию…", "Saving a copy…"), 16, GalleryUi.TEXT);
        exportText.setTypeface(com.scamera.app.ui.style.Fonts.of(exportText.getContext(), 600));
        exportText.setGravity(Gravity.CENTER);
        card.addView(exportText);
        exportBar = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        exportBar.setMax(1000);
        exportBar.setProgressTintList(android.content.res.ColorStateList.valueOf(GalleryUi.accent(c)));
        exportBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x5C787880));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ep.topMargin = GalleryUi.dp(c, 12);
        card.addView(exportBar, ep);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(GalleryUi.dp(c, 290), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        exportOverlay.addView(card, cp);
        root.addView(exportOverlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (vm.exporting()) exportOverlay.setVisibility(View.VISIBLE);
        gl.setCallbacks(new EditorView.Callbacks() {
            @Override
            public void histogram(int[][] counts) {
                if (histogram != null) histogram.set(counts);
            }

            @Override
            public void failed(String why) {
                if (getContext() == null) return;
                com.scamera.app.gallery.ui.GalleryToast.show(getContext(), Lang.t("Редактор не запустился на GPU: ", "The editor could not start on the GPU: ") + why, true);
            }

            @Override
            public void zoom(int percent, boolean isZoomed, int detail) {
                zoomPercent = percent;
                zoomed = isZoomed;
                zoomDetail = detail;
                updateZoomPill();
            }

            @Override
            public void note(String text) {
                showNote(text);
            }

            @Override
            public void picture(RectF rect, boolean cropMode) {
                if (watermarkView != null) watermarkView.setPicture(rect);
            }
        });
        updateHistoryButtons();
        return root;
    }

    private static View iconButton(Context c, int icon, String description, View.OnClickListener click) {
        FrameLayout b = new FrameLayout(c);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(GalleryUi.CARD);
        b.setBackground(GalleryUi.pressable(d));
        b.setContentDescription(description);
        b.setTooltipText(description);
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(click);
        ImageView i = GalleryUi.icon(c, icon, GalleryUi.TEXT, 22);
        com.scamera.app.ui.Icons.ink(i, icon, GalleryUi.TEXT); // P579: white, as the settings' round buttons
        b.addView(i, new FrameLayout.LayoutParams(GalleryUi.dp(c, 22), GalleryUi.dp(c, 22), Gravity.CENTER));
        return b;
    }

    private void buildTabs(Context c) {
        tabBar.removeAllViews();
        int[] icons = {R.drawable.ui_ic_crop, R.drawable.ui_ic_styles, R.drawable.ui_ic_film, R.drawable.ui_ic_sun, R.drawable.ui_ic_palette,
                R.drawable.ui_ic_effects, R.drawable.ui_ic_detail, R.drawable.ui_ic_tag, R.drawable.ui_ic_raw};
        String[] names = {Lang.t("Кадр", "Crop"), Lang.t("Стили", "Styles"), "LUT", Lang.t("Свет", "Light"), Lang.t("Цвет", "Colour"),
                Lang.t("Эффекты", "Effects"), Lang.t("Детали", "Detail"), Lang.t("Знак", "Mark"), Lang.t("Проявка", "Develop")};
        tabButtons = new LinearLayout[icons.length];
        tabIcons = new ImageView[icons.length];
        tabNames = new TextView[icons.length];
        tabPills = new android.graphics.drawable.GradientDrawable[icons.length];
        final int accent = GalleryUi.accent(c);
        for (int n = 0; n < TAB_ORDER.length; n++) {
            final int i = TAB_ORDER[n];
            final int k = i;
            boolean on = i == tab;
            int colour = on ? accent : GalleryUi.MUTED;
            LinearLayout b = new LinearLayout(c);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, GalleryUi.dp(c, 8), 0, GalleryUi.dp(c, 6));
            // P360: the selection pill, faded between tabs; P579: the shared segmented control's grey thumb (the accent on it)
            final android.graphics.drawable.GradientDrawable pill = GalleryUi.round(c, 0x52767680, 0, 18);
            pill.setAlpha(on ? 255 : 0);
            tabPills[i] = pill;
            b.setBackground(GalleryUi.pressable(pill));
            b.setClickable(true);
            b.setFocusable(true);
            b.setSelected(on);
            b.setContentDescription(i == TAB_WATERMARK ? Lang.t("Водяной знак", "Watermark") : names[i]);
            b.setOnClickListener(v -> selectTab(k));
            tabIcons[i] = GalleryUi.icon(c, icons[i], colour, 22);
            b.addView(tabIcons[i]);
            TextView t = GalleryUi.fitText(c, names[i], 10.5f, colour);
            t.setTypeface(com.scamera.app.ui.style.Fonts.of(t.getContext(), 600));
            t.setGravity(Gravity.CENTER);
            tabNames[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = GalleryUi.dp(c, 3);
            b.addView(t, lp);
            tabButtons[i] = b;
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            bp.leftMargin = bp.rightMargin = GalleryUi.dp(c, 2);
            tabBar.addView(b, bp);
        }
    }

    private void selectTab(int t) {
        if (t == tab) return;
        final int from = tab;
        tab = t;
        animateTabs(from, t);
        showPanel(Integer.compare(position(t), position(from)));
        updateOverlay();
        render();
    }

    /** P360: the old tab fades to plain, the new one takes the pill and the accent with a small bounce of its icon. */
    private void animateTabs(int from, int to) {
        if (tabButtons == null || getContext() == null) return;
        final int accent = GalleryUi.accent(requireContext());
        for (int i = 0; i < tabButtons.length; i++) {
            if (i != from && i != to) continue;
            final boolean on = i == to;
            final ImageView icon = tabIcons[i];
            final TextView name = tabNames[i];
            final android.graphics.drawable.GradientDrawable pill = tabPills[i];
            tabButtons[i].setSelected(on);
            icon.setActivated(on); // P570: the colour icon's pressed pose for the open tab
            EditMotion.colour(on ? GalleryUi.MUTED : accent, on ? accent : GalleryUi.MUTED, EditMotion.MEDIUM, col -> {
                if (!com.scamera.app.ui.Icons.colour(icon)) icon.setImageTintList(android.content.res.ColorStateList.valueOf(col)); // P570
                name.setTextColor(col);
            });
            EditMotion.fraction(on ? 0f : 1f, on ? 1f : 0f, EditMotion.MEDIUM, EditMotion.MOVE, f -> pill.setAlpha(Math.round(255 * f)));
            if (on) {
                icon.setScaleX(.8f);
                icon.setScaleY(.8f);
                icon.animate().scaleX(1f).scaleY(1f).setDuration(EditMotion.LONG).setInterpolator(new android.view.animation.OvershootInterpolator(2.5f)).start();
            }
        }
    }

    private void showPanel() {
        showPanel(0);
    }

    /**
     * The tab's panel; P360: with {@code direction} != 0 the old panel slips out and the new one slides in from that side, its
     * rows staggered.
     */
    private void showPanel(int direction) {
        if (vm.source.getValue() == null) {
            panelScroll.removeAllViews();
            return;
        }
        final View old = panelScroll.getChildCount() > 0 ? panelScroll.getChildAt(0) : null;
        final View p = panels.forTab(tab);
        final float shift = GalleryUi.dp(requireContext(), 28) * direction;
        final Runnable in = () -> {
            panelScroll.removeAllViews();
            if (p == null) return;
            panelScroll.addView(p);
            panelScroll.scrollTo(0, 0);
            if (direction == 0) return;
            p.setAlpha(0f);
            p.setTranslationX(shift);
            p.animate().alpha(1f).translationX(0f).setDuration(EditMotion.MEDIUM).setInterpolator(EditMotion.ENTER).start();
            if (p instanceof ViewGroup) EditMotion.stagger((ViewGroup) p);
        };
        if (old == null || direction == 0) {
            in.run();
            return;
        }
        old.animate().cancel();
        old.animate().alpha(0f).translationX(-shift * .6f).setDuration(EditMotion.SHORT - 30).setInterpolator(EditMotion.EXIT)
                .withEndAction(() -> {
                    if (panelScroll != null && panels != null) in.run();
                }).start();
    }

    // ---------------------------------------------------------------- state

    private void onSource(@Nullable EditSource s) {
        if (s == null) return;
        spinner.setVisibility(View.GONE);
        if (vm.history == null) {
            vm.history = new EditHistory(new EditState());
            if (restore != null) {
                vm.state = restore;
                vm.history.commit(restore);
            }
        }
        if (s.note != null) showNote(s.note);
        // P633: the copy's pixel limit for this photo (a binder call: once a source), the size the watermark's preview lays out for
        watermarkOutMax = EditExporter.outMax(EditSource.memoryBudget(requireContext()));
        watermarkInkKey = null;
        gl.setSource(s);
        gl.setLookTables(vm.lookTables); // P619: a recreated editor keeps the loaded tables
        showPanel();
        if (!entered) {
            // P360: the editor's arrival, once
            entered = true;
            EditMotion.enter(cover, panelScroll, tabBar);
            spinner.setVisibility(View.GONE);
        }
        updateHistoryButtons();
        updateOverlay();
        render();
    }

    private void render() {
        if (gl == null) return;
        syncDevelop();
        gl.setState(vm.state, tab == TAB_CROP, before);
        requestLook();
        updateWatermark();
        EditMotion.show(histogram, tab != TAB_CROP, 0f);
        updateZoomPill();
    }

    /**
     * P665: the picture the editor works on - the developed one while «Проявка камерой» is on (the last developed: a new develop
     * replaces it when it is done), else the photo as opened - and the develop asked for the state's settings.
     */
    private void syncDevelop() {
        EditSource raw = vm.source.getValue();
        if (raw == null || gl == null) return;
        if (vm.develop != null && vm.state.develop) vm.requestDevelop(vm.state);
        EditSource want = shown();
        if (want != gl.source()) gl.setSource(want);
    }

    /** P665: the source the picture, the crop and the copy work on now. */
    @Nullable
    private EditSource shown() {
        EditSource raw = vm.source.getValue();
        EditSource dev = vm.developed.getValue();
        return vm.develop != null && vm.state.develop && dev != null ? dev : raw;
    }

    @Nullable
    @Override
    public EditDevelop develop() {
        return vm.develop;
    }

    @Nullable
    @Override
    public String developStatus() {
        return vm.developStatus.getValue();
    }

    /** A line in the note pill for 6 s (a RAW's limits; P367: full resolution unavailable). */
    private void showNote(String text) {
        if (notePill == null || getContext() == null) return;
        notePill.setText(text);
        EditMotion.show(notePill, true, GalleryUi.dp(requireContext(), 12));
        notePill.removeCallbacks(hideNote);
        notePill.postDelayed(hideNote, 6000);
    }

    /**
     * P367: the zoom pill while zoomed (not in «Кадр», not over «before»), «детали…» while the full resolution loads,
     * «уменьшено» when the original is read below its own resolution (memory; the note pill said why).
     */
    private void updateZoomPill() {
        if (zoomPill == null) return;
        boolean show = zoomed && !before && tab != TAB_CROP;
        if (show) {
            String t = zoomPercent + " %";
            if (zoomDetail == EditorView.DETAIL_LOADING) t += " · " + Lang.t("детали…", "detail…");
            else if (zoomDetail == EditorView.DETAIL_REDUCED) t += " · " + Lang.t("уменьшено", "reduced");
            if (!t.contentEquals(zoomPill.getText())) zoomPill.setText(t);
        }
        if (show != zoomPillOn) {
            zoomPillOn = show;
            EditMotion.show(zoomPill, show, -GalleryUi.dp(zoomPill.getContext(), 6));
        }
    }

    /**
     * P633: the watermark's preview - the state's watermark laid out for the copy's size, over the picture (not in «Кадр», not while
     * the long press shows the original); «Авто» from a small picture of the edited crop, asked again when the picture changed.
     */
    private void updateWatermark() {
        if (watermarkView == null) return;
        EditSource s = shown();
        com.scamera.app.processing.watermark.WatermarkSpec spec = vm.state.watermark();
        boolean show = s != null && spec != null && tab != TAB_CROP && !before;
        if (show) {
            double[] o = EditorView.orientedFull(s);
            double[] crop = CropMath.cropPixels(vm.state, o[0], o[1]);
            int[] size = EditExporter.outputSize(crop[0], crop[1], watermarkOutMax);
            watermarkView.bind(spec, s.watermarkInfo, size[0], size[1]);
            if (spec.colour == com.scamera.app.processing.watermark.WatermarkSpec.Colour.AUTO) {
                EditState picture = vm.state.copy();
                picture.wm = "";
                String key = picture.toJson();
                if (!key.equals(watermarkInkKey)) {
                    watermarkInkKey = key;
                    gl.thumbnail(EditLookThumbs.SIDE_PX, (px, w, h) -> {
                        if (watermarkView != null) watermarkView.setInkPicture(px, w, h);
                    });
                }
            }
            RectF r = gl.pictureRect();
            if (r != null) watermarkView.setPicture(r);
        } else {
            watermarkView.bind(null, s != null ? s.watermarkInfo : new com.scamera.app.processing.watermark.WatermarkText.Info(), 0, 0);
        }
        if (show != (watermarkView.getVisibility() == View.VISIBLE)) {
            if (show) {
                watermarkView.setAlpha(0f);
                watermarkView.setVisibility(View.VISIBLE);
                watermarkView.animate().alpha(1f).setDuration(EditMotion.MEDIUM).setInterpolator(EditMotion.ENTER).start();
            } else {
                watermarkView.animate().cancel();
                watermarkView.setVisibility(View.GONE);
            }
        }
    }

    /** P619: the tables of the state's LUT / film (loaded off the main thread; the picture shows them when they are there). */
    private void requestLook() {
        if (getContext() == null) return;
        if (!EditLook.on(vm.state)) {
            vm.lookLoader.forget();
            return;
        }
        vm.lookLoader.request(requireContext(), vm.state, t -> {
            vm.lookTables = t;
            if (gl != null) gl.setLookTables(t);
        });
    }

    @Override
    public void lookThumbnail(EditPanels.ThumbSink sink) {
        if (gl != null) gl.thumbnail(EditLookThumbs.SIDE_PX, sink);
    }

    @Override
    public EditState state() {
        return vm.state;
    }

    @Override
    public EditSource source() {
        return shown();
    }

    @Override
    public void changed(boolean commit) {
        if (!applyingStyle) vm.styleBase = null;
        render();
        if (commit && vm.history != null) {
            vm.history.commit(vm.state);
            updateHistoryButtons();
        }
        if (tab == TAB_CROP) updateOverlay();
        if (commit) panels.refresh();
    }

    private void updateHistoryButtons() {
        boolean u = vm.history != null && vm.history.canUndo(), r = vm.history != null && vm.history.canRedo();
        GalleryUi.setEnabledLook(undo, u);
        GalleryUi.setEnabledLook(redo, r);
    }

    private void undo() {
        if (vm.history == null || !vm.history.canUndo()) return;
        vm.state = vm.history.undo();
        afterJump();
    }

    private void redo() {
        if (vm.history == null || !vm.history.canRedo()) return;
        vm.state = vm.history.redo();
        afterJump();
    }

    private void afterJump() {
        vm.styleBase = null;
        panels.styleIndex = -1;
        panels.refresh();
        updateHistoryButtons();
        updateOverlay();
        render();
    }

    private double[] oriented() {
        EditSource s = shown();
        return s != null ? EditorView.oriented(s) : new double[]{1, 1};
    }

    private void updateOverlay() {
        if (overlay == null) return;
        boolean crop = tab == TAB_CROP && vm.source.getValue() != null;
        overlay.setVisibility(crop ? View.VISIBLE : View.GONE);
        if (!crop || imageArea.getWidth() == 0) return;
        double[] o = oriented();
        RectF canvas = EditorView.canvas(imageArea.getWidth(), imageArea.getHeight(), EditorView.margin(requireContext(), true), vm.state, o[0], o[1], true);
        double[] b = CropMath.baseSize(o[0], o[1], vm.state.quarterTurns);
        double ratio = CropMath.ratioOf(vm.state.aspect, b[0], b[1]);
        overlay.bind(canvas, b[0], b[1], vm.state.straighten, ratio,
                new float[]{vm.state.cropL, vm.state.cropT, vm.state.cropR, vm.state.cropB});
    }

    private void onCrop(float[] r, boolean done) {
        vm.state.cropL = r[0];
        vm.state.cropT = r[1];
        vm.state.cropR = r[2];
        vm.state.cropB = r[3];
        render();
        if (done) changed(true);
    }

    @Override
    public void rotate() {
        CropMath.rotateClockwise(vm.state);
        changed(true);
    }

    @Override
    public void flip() {
        CropMath.flipHorizontal(vm.state);
        changed(true);
    }

    @Override
    public void resetCrop() {
        vm.state.resetGeometry();
        changed(true);
    }

    @Override
    public void aspect(float ratio) {
        vm.state.aspect = ratio;
        double[] o = oriented();
        CropMath.applyAspect(vm.state, o[0], o[1]);
        changed(true);
    }

    @Override
    public void straighten(float degrees, boolean commit) {
        if (straightenAnchor == null) straightenAnchor = new float[]{vm.state.cropL, vm.state.cropT, vm.state.cropR, vm.state.cropB};
        vm.state.straighten = degrees;
        double[] o = oriented();
        double[] b = CropMath.baseSize(o[0], o[1], vm.state.quarterTurns);
        float[] r = CropMath.inset(straightenAnchor[0], straightenAnchor[1], straightenAnchor[2], straightenAnchor[3], b[0], b[1], degrees);
        vm.state.cropL = r[0];
        vm.state.cropT = r[1];
        vm.state.cropR = r[2];
        vm.state.cropB = r[3];
        if (commit) straightenAnchor = null;
        updateOverlay();
        if (commit) changed(true);
        else render();
    }

    @Override
    public void style(int index, float amount, boolean commit) {
        if (vm.styleBase == null) vm.styleBase = vm.state.copy();
        EditState next = index < 0 ? vm.styleBase.copy() : EditPresets.apply(vm.styleBase, index, amount);
        vm.state.copyTone(next);
        applyingStyle = true;
        changed(commit);
        applyingStyle = false;
        if (index < 0) vm.styleBase = null;
    }

    // ---------------------------------------------------------------- menu, saving, leaving

    /**
     * The editor's «Ещё»: reset, copy and paste the settings, open in another app. P579: the gallery's pull-down menu anchored at the
     * button (the shared menu's look) instead of a bottom sheet.
     */
    private void menu(View anchor) {
        Context c = requireContext();
        java.util.List<com.scamera.app.gallery.ui.GalleryMenu.Item> items = new java.util.ArrayList<>();
        items.add(new com.scamera.app.gallery.ui.GalleryMenu.Item(R.drawable.ui_ic_backup, Lang.t("Сбросить всё", "Reset all"), true, () -> {
            vm.state = new EditState();
            changed(true);
            afterJump();
        }));
        items.add(new com.scamera.app.gallery.ui.GalleryMenu.Item(R.drawable.ui_ic_layers, Lang.t("Копировать настройки", "Copy settings"), true, () -> {
            clipboard = vm.state.copy();
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_CLIPBOARD, clipboard.toJson()).apply();
            com.scamera.app.gallery.ui.GalleryToast.show(c, Lang.t("Настройки скопированы (без кадрирования)", "Settings copied (crop not included)"));
        }));
        items.add(new com.scamera.app.gallery.ui.GalleryMenu.Item(R.drawable.ui_ic_plus, Lang.t("Вставить настройки", "Paste settings"),
                clipboard != null, () -> {
            if (clipboard == null) return;
            vm.state.copyTone(clipboard);
            changed(true);
            afterJump();
        }));
        items.add(new com.scamera.app.gallery.ui.GalleryMenu.Item(R.drawable.ui_ic_share, Lang.t("Открыть в другом приложении", "Open in another app"),
                true, () -> ExternalEdit.openInOtherApp(this, uri, name)));
        com.scamera.app.gallery.ui.GalleryMenu.show(anchor, items);
    }

    private void save() {
        if (vm.source.getValue() == null || vm.exporting()) return;
        // P360: the save card fades and scales in over the dimmed editor
        exportOverlay.setAlpha(0f);
        exportOverlay.setVisibility(View.VISIBLE);
        exportOverlay.animate().alpha(1f).setDuration(EditMotion.MEDIUM).setInterpolator(EditMotion.ENTER).start();
        if (exportOverlay.getChildCount() > 0) {
            final View card = exportOverlay.getChildAt(0);
            card.setScaleX(.88f);
            card.setScaleY(.88f);
            card.animate().scaleX(1f).scaleY(1f).setDuration(EditMotion.LONG).setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
        }
        exportBar.setProgress(0);
        // P367: the zoom's reader gives its memory to the export (which waits until it is closed)
        // P665: the developed picture's copy develops the whole DNG (EditDevelop.full) on the export's thread
        vm.export(vm.state, gl != null ? gl.closeFullRes() : null, shown());
    }

    private void onExported(EditExporter.Result r) {
        exportOverlay.setVisibility(View.GONE);
        Context c = getContext();
        if (c == null) return;
        String where = r.folder != null ? " · " + r.folder : "";
        String msg = Lang.t("Копия сохранена: ", "Copy saved: ") + r.displayName + where + (r.note != null ? " (" + r.note + ")" : "");
        // an edit for another app closes the activity next: the system toast outlives it, the gallery's capsule would not
        if (external) Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
        else com.scamera.app.gallery.ui.GalleryToast.show(c, msg, true);
        vm.history = new EditHistory(vm.state); // saved: nothing unsaved any more
        if (external) {
            Activity a = requireActivity();
            Intent data = new Intent();
            data.setData(r.uri);
            data.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.setResult(Activity.RESULT_OK, data);
            a.finish();
            return;
        }
        long id = -1;
        try {
            id = ContentUris.parseId(r.uri);
        } catch (RuntimeException ignored) {
        }
        gallery.showNewFile(new ImageFile(id, r.uri, r.displayName, System.currentTimeMillis(), 0, null));
        leave(true);
    }

    /** Leaving (asks first with unsaved edits); {@code systemBack}: P367 - the first back returns a zoomed picture to the fit. */
    private void onBack(boolean systemBack) {
        if (vm.exporting()) return;
        if (systemBack && gl != null && gl.isZoomed()) {
            gl.resetZoom(true);
            return;
        }
        if (vm.history != null && vm.history.changed()) {
            // P579: the centred alert (30dp corners, «Продолжить» and the red «Выйти» side by side)
            GallerySheets.confirm(requireContext(), Lang.t("Выйти без сохранения?", "Leave without saving?"),
                    Lang.t("Изменения пропадут. Оригинал не меняется в любом случае.", "The edits are lost. The original is never changed either way."),
                    Lang.t("Продолжить", "Keep editing"), Lang.t("Выйти", "Leave"), () -> leave(false));
            return;
        }
        leave(false);
    }

    private void leave(boolean saved) {
        if (external) {
            if (!saved) requireActivity().setResult(Activity.RESULT_CANCELED);
            requireActivity().finish();
            return;
        }
        if (!NavHostFragment.findNavController(this).navigateUp()) requireActivity().finish();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Constants.REQ_EXTERNAL_EDIT && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            com.scamera.app.gallery.ui.GalleryToast.show(getContext(), Lang.t("Сохранено: ", "Saved: ") + data.getData().getPath(), true);
            gallery.fetchAllMedia();
            gallery.setUpdatePending(true);
            leave(true);
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onResume() {
        super.onResume();
        if (gl != null) gl.onResume();
    }

    @Override
    public void onPause() {
        if (gl != null) gl.onPause();
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        if (vm != null) out.putString(KEY_STATE, vm.state.toJson());
        out.putInt(KEY_TAB, tab);
    }

    @Override
    public void onDestroyView() {
        if (gl != null) {
            gl.setCallbacks(null);
            gl.release();
        }
        if (notePill != null) notePill.removeCallbacks(hideNote);
        gl = null;
        overlay = null;
        watermarkView = null;
        histogram = null;
        zoomPill = null;
        zoomPillOn = false;
        super.onDestroyView();
    }
}
