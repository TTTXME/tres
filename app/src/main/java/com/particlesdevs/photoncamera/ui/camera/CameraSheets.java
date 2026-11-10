package com.particlesdevs.photoncamera.ui.camera;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiScale;
import com.particlesdevs.photoncamera.processing.PhotoFormat;
import com.particlesdevs.photoncamera.settings.FormatChoice;
import com.particlesdevs.photoncamera.ui.Motion;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * SIMPLE_UI §2, the camera's sheets as the concept draws them (.sheet over the camera: the see-through sheet colour, 28dp
 * top corners, the grab, a 16.5sp title; group titles in the accent, 11.5sp caps): «Формат файла» - two choices instead
 * of one long list, «Фото» (JPEG / HEIC / WebP / AVIF) and «RAW (DNG)» (Нет / + RAW / Только RAW), each a segmented control
 * with a one-line explanation, the summary «Сохранится: …» and the note; selecting does not close it. It is a UI over the
 * existing pref_save_raw_key and photo codec (FormatChoice): (X, Нет) -> X, (X, + RAW) -> RAW + X, (any, Только RAW) -> RAW;
 * «Только RAW» keeps the last photo choice so «+ RAW» restores it. «Размер фото» - the three sizes as radio rows.
 * P574 (the approved concept): one sheet with the tabs «Формат | Размер» (the shared segmented control), the choices as the shared
 * segmented control (a grey thumb gliding, the chosen label in the accent) updated in place, «Сохранится» a tinted card, the sizes
 * as rows with a trailing accent tick; the sheet a solid dark card (less glass).
 */
public final class CameraSheets {
    private CameraSheets() {}

    /** The photo codecs of the «Фото» control, in order. */
    static final PhotoFormat[] PHOTO = {PhotoFormat.JPEG, PhotoFormat.HEIC, PhotoFormat.WEBP, PhotoFormat.AVIF};
    /** The RAW modes: 0 none, 1 + RAW, 2 RAW only (the save mode). */
    static final int RAW_NONE = 0, RAW_PAIRED = 1, RAW_ONLY = 2;

    /** The choice of a photo codec and a RAW mode (the 9 FormatChoice values). */
    static FormatChoice choice(PhotoFormat photo, int rawMode) {
        return FormatChoice.of(rawMode, photo);
    }

    /** The «Фото» name of a codec. */
    static String photoName(PhotoFormat f) {
        switch (f) {
            case HEIC: return "HEIC";
            case WEBP: return "WebP";
            case AVIF: return "AVIF";
            default: return "JPEG";
        }
    }

    static String photoLine(Context c, PhotoFormat f) {
        switch (f) {
            case HEIC: return Lang.t(c, "Как JPEG, но вдвое меньше места", "Like JPEG in half the space");
            case WEBP: return Lang.t(c, "Для сайтов и мессенджеров", "For websites and messengers");
            case AVIF: return Lang.t(c, "Самый компактный, открывается не везде", "The most compact; not every app opens it");
            default: return Lang.t(c, "Обычное фото, открывается везде", "A usual photo that opens everywhere");
        }
    }

    static String rawName(Context c, int mode) {
        return mode == RAW_ONLY ? Lang.t(c, "Только RAW", "RAW only") : mode == RAW_PAIRED ? "+ RAW" : Lang.t(c, "Нет", "None");
    }

    static String rawLine(Context c, int mode) {
        return mode == RAW_ONLY ? Lang.t(c, "Только DNG, без готового фото", "DNG only, no finished photo")
                : mode == RAW_PAIRED ? Lang.t(c, "Фото и сырой снимок DNG рядом — для обработки на компьютере",
                "The photo and a raw DNG beside it, to edit on a computer")
                : Lang.t(c, "Сохраняется только фото", "Only the photo is saved");
    }

    /** «RAW (DNG) + HEIC» / «HEIC» / «RAW (DNG)». */
    static String summary(PhotoFormat photo, int rawMode) {
        if (rawMode == RAW_ONLY) return "RAW (DNG)";
        return rawMode == RAW_PAIRED ? "RAW (DNG) + " + photoName(photo) : photoName(photo);
    }

    // ───── the sheet frame

    /**
     * P495: the sheet's rows (the title, the format's groups and lines, the size card) come in one after another while the sheet
     * rises (Motion.enter, once it is on screen); the grab is there at once. Hidden before the show, so no frame shows them still.
     */
    static void enterRows(LinearLayout body) {
        List<View> rows = new ArrayList<>();
        for (int i = 1; i < body.getChildCount(); i++) {
            View v = body.getChildAt(i);
            if ("format_sheet".equals(v.getTag()) && v instanceof ViewGroup)
                for (int j = 0; j < ((ViewGroup) v).getChildCount(); j++) rows.add(((ViewGroup) v).getChildAt(j));
            else rows.add(v);
        }
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        for (View v : rows) v.setAlpha(0f);
        body.post(() -> Motion.enter(rows, 60, 30));
    }

    /** The see-through camera sheet with the grab and the title; returns {dialog body}: add the content to the body. */
    static LinearLayout frame(Context c, @Nullable CharSequence title) {
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        // P574: the concept's solid sheet (#1C1C1E), no see-through glass over the picture
        bg.setColor(SHEET);
        // P583 (the concept's sheets): 36px corners all round, padding 8 14 18
        bg.setCornerRadius(UiScale.dpF(c, 36));
        body.setBackground(bg);
        int side = UiScale.dp(c, 14);
        body.setPadding(side, UiScale.dp(c, 12), side, UiScale.dp(c, 18));
        View grab = new View(c);
        grab.setBackground(SettingsStyle.shape(c, 0x4DFFFFFF, 0, 2.5f));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(UiScale.dp(c, 36), UiScale.dp(c, 5));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.bottomMargin = UiScale.dp(c, 12);
        body.addView(grab, gp);
        if (title == null) return body;
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(ShadeStyle.TEXT);
        com.particlesdevs.photoncamera.ui.style.Fonts.apply(t, com.particlesdevs.photoncamera.ui.style.Fonts.SEMIBOLD);
        px(t, 16.5f);
        t.setPadding(UiScale.dp(c, 4), 0, 0, UiScale.dp(c, 12));
        body.addView(t);
        return body;
    }

    /** P574: the concept's sheet and row colours (#1C1C1E, #2C2C2E) and its «Сохранится» card (the accent's dark tint). */
    static final int SHEET = 0xFF1C1C1E, ROW = 0xFF2C2C2E;

    /** A group title: 12sp, 600, caps, letter-spaced, muted (P574: the concept's «ФОТО», «RAW (DNG)»). */
    static TextView groupTitle(Context c, CharSequence text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setAllCaps(true);
        t.setLetterSpacing(1f / 12f);
        t.setTextColor(0x8CFFFFFF);
        com.particlesdevs.photoncamera.ui.style.Fonts.apply(t, com.particlesdevs.photoncamera.ui.style.Fonts.SEMIBOLD);
        px(t, 12f);
        t.setPadding(UiScale.dp(c, 6), UiScale.dp(c, 4), 0, UiScale.dp(c, 8));
        return t;
    }

    /**
     * P574: a segmented control - the shared IosSegmented (a capsule track, the grey thumb gliding to the chosen segment, its label
     * in the accent); each segment is tagged {@code seg_i}; {@code disabled}: dimmed, no taps. {@code pick} hears the index.
     */
    static com.particlesdevs.photoncamera.ui.controls.IosSegmented segmented(Context c, List<String> labels, int selected, boolean disabled,
                                                                           java.util.function.IntConsumer pick) {
        com.particlesdevs.photoncamera.ui.controls.IosSegmented seg = new com.particlesdevs.photoncamera.ui.controls.IosSegmented(c);
        seg.setColours(ShadeStyle.accent(c), 0x3D767680, 0xFF636366);
        seg.setItems(labels.toArray(new CharSequence[0]));
        for (int i = 0; i < seg.getChildCount(); i++) {
            TextView b = (TextView) seg.getChildAt(i);
            b.setTag("seg_" + i);
            b.setMaxLines(1);
            px(b, 14f);
            com.particlesdevs.photoncamera.ui.style.Fonts.apply(b, com.particlesdevs.photoncamera.ui.style.Fonts.SEMIBOLD);
            b.setMinHeight(UiScale.dp(c, 36));
        }
        seg.setSelected(selected, false);
        seg.setListener((s, index) -> pick.accept(index));
        setDisabled(seg, disabled);
        return seg;
    }

    /** P574: a segmented control dimmed and without taps ({@code off}), or back. */
    static void setDisabled(ViewGroup seg, boolean off) {
        seg.setAlpha(off ? .35f : 1f);
        for (int i = 0; i < seg.getChildCount(); i++) seg.getChildAt(i).setEnabled(!off);
    }

    static TextView line(Context c, CharSequence text, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color);
        com.particlesdevs.photoncamera.ui.style.Fonts.apply(t, com.particlesdevs.photoncamera.ui.style.Fonts.REGULAR);
        px(t, sp);
        return t;
    }

    /** P583: the concept's px as dp times the chrome's scale (the system font size does not count), no autosize. */
    static void px(TextView t, float px) {
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeWithDefaults(t, androidx.core.widget.TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, UiScale.dpF(t.getContext(), px));
    }

    /** P583 (the concept's floating sheets): 8px from the screen's sides and bottom. */
    static void floating(LinearLayout body) {
        final View host = (View) body.getParent();
        if (host == null) return;
        final int m = UiScale.dp(body.getContext(), 8);
        host.setPadding(m, 0, m, m);
        if (host instanceof ViewGroup) ((ViewGroup) host).setClipToPadding(false);
    }

    // ───── «Формат файла»

    /** The state the sheet edits: the photo codec (kept under «Только RAW») and the RAW mode. */
    static final class FormatState {
        PhotoFormat photo;
        int raw;

        FormatState(FormatChoice now, PhotoFormat storedCodec) {
            raw = now.saveMode;
            photo = now.codec != null ? now.codec : storedCodec != null ? storedCodec : PhotoFormat.JPEG;
        }
    }

    /**
     * Shows «Формат файла» at {@code current}. {@code offered}: the choices this phone offers (FormatChoice.offered); {@code apply} stores a
     * choice (the caller's path: CameraUIController FORMAT_CHOICE, the badges, the sheet's tiles).
     */
    public static BottomSheetDialog format(Context c, List<FormatChoice> offered, FormatChoice current, PhotoFormat storedCodec,
                                         Consumer<FormatChoice> apply) {
        LinearLayout body = frame(c, Lang.t(c, "Формат файла", "File format"));
        body.addView(formatContent(c, offered, current, storedCodec, apply));
        BottomSheetDialog d = SettingsStyle.sheet(c, body);
        floating(body);
        enterRows(body);
        d.show();
        return d;
    }

    /** P574: what the «Размер» tab of the combined sheet shows and does (the open camera's sizes, read again after a pick). */
    public interface SizeSource {
        int current();

        boolean full();

        boolean max();

        /** A size chosen (the caller applies it and says what happened). */
        void pick(int size);

        /** A size this lens lacks was tapped. */
        default void refuse(CharSequence why) {}

        /** P214h: a size off by the camera's trials (dimmed; its tap goes to {@link #pick}, which explains). */
        default boolean dead(int size) { return false; }
    }

    /** The tabs of the combined sheet. */
    public static final int TAB_FORMAT = 0, TAB_SIZE = 1;

    /**
     * P574 (the approved concept): one sheet for the top row's «JPEG / 12 Мп» badge - the tabs «Формат | Размер» (the shared
     * segmented control) over the format's choices or the size rows; {@code tab} the one shown first. A choice never closes it.
     */
    public static BottomSheetDialog formatAndSize(Context c, List<FormatChoice> offered, FormatChoice current, PhotoFormat storedCodec,
                                                  Consumer<FormatChoice> apply, SizeSource sizes, int tab) {
        LinearLayout body = frame(c, null);
        List<String> titles = new ArrayList<>();
        titles.add(Lang.t(c, "Формат", "Format"));
        titles.add(Lang.t(c, "Размер", "Size"));
        LinearLayout formatPage = formatContent(c, offered, current, storedCodec, apply);
        LinearLayout sizePage = new LinearLayout(c);
        sizePage.setOrientation(LinearLayout.VERTICAL);
        sizePage.setTag("size_page");
        Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            sizePage.removeAllViews();
            sizePage.addView(sizeCard(c, sizes.current(), sizes.full(), sizes.max(), size -> {
                sizes.pick(size);
                // the tick follows what the camera took (a size it refused keeps the old one)
                sizePage.post(fill[0]);
            }, sizes::refuse, sizes::dead));
            // P592: the line under the sizes («Какие размеры есть, зависит от объектива…») is in each size's long press
        };
        fill[0].run();
        final int[] shownTab = {tab};
        com.particlesdevs.photoncamera.ui.controls.IosSegmented tabs = segmented(c, titles, tab, false, t -> {
            if (t == shownTab[0]) return;   // a tap on the shown tab: no new page
            shownTab[0] = t;
            formatPage.setVisibility(t == TAB_FORMAT ? View.VISIBLE : View.GONE);
            sizePage.setVisibility(t == TAB_SIZE ? View.VISIBLE : View.GONE);
            View in = t == TAB_FORMAT ? formatPage : sizePage;
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                in.setAlpha(0f);
                in.setTranslationX(UiScale.dpF(c, 16) * (t == TAB_SIZE ? 1 : -1));
                in.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(TrayMotion.EASE_OUT).start();
            }
        });
        tabs.setTag("format_size_tabs");
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.bottomMargin = UiScale.dp(c, 12);
        body.addView(tabs, tp);
        formatPage.setVisibility(tab == TAB_FORMAT ? View.VISIBLE : View.GONE);
        sizePage.setVisibility(tab == TAB_SIZE ? View.VISIBLE : View.GONE);
        body.addView(formatPage);
        body.addView(sizePage);
        BottomSheetDialog d = SettingsStyle.sheet(c, body);
        floating(body);
        enterRows(body);
        d.show();
        return d;
    }

    /** The choice itself (the «Фото» and «RAW (DNG)» controls): the camera's sheet and the settings' «Формат файла» page. */
    public static LinearLayout formatContent(Context c, List<FormatChoice> offered, FormatChoice current, PhotoFormat storedCodec,
                                             Consumer<FormatChoice> apply) {
        LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setTag("format_sheet");
        FormatState state = new FormatState(current, storedCodec);
        List<PhotoFormat> photos = new ArrayList<>();
        for (PhotoFormat f : PHOTO) if (offered.contains(choice(f, RAW_NONE))) photos.add(f);
        if (!photos.contains(state.photo)) state.photo = photos.isEmpty() ? PhotoFormat.JPEG : photos.get(0);
        // P574: built once and updated in place (the segmented controls' thumbs glide to a new choice)
        List<String> names = new ArrayList<>();
        for (PhotoFormat f : photos) names.add(photoName(f));
        List<String> raws = new ArrayList<>();
        for (int m = 0; m < 3; m++) raws.add(rawName(c, m));
        // P592 (owner, 2026-11-10, the crossed-out «Формат файла»: the hints under the controls, the «Сохранится» card and the
        // note go): the two controls alone; what each choice means and the advice are their long press (SettingHelp's sheet),
        // and TalkBack still hears a choice's meaning with its name
        Runnable[] update = new Runnable[1];
        com.particlesdevs.photoncamera.ui.controls.IosSegmented[] photoSeg = new com.particlesdevs.photoncamera.ui.controls.IosSegmented[1];
        final String photoTitle = Lang.t(c, "Фото", "Photo");
        content.addView(groupTitle(c, photoTitle));
        photoSeg[0] = segmented(c, names, photos.indexOf(state.photo), state.raw == RAW_ONLY, i -> {
            state.photo = photos.get(i);
            store(state, offered, apply);
            update[0].run();
        });
        photoSeg[0].setTag("format_photo");
        for (int i = 0; i < photoSeg[0].getChildCount() && i < photos.size(); i++)
            photoSeg[0].getChildAt(i).setContentDescription(photoName(photos.get(i)) + ", " + photoLine(c, photos.get(i)));
        described(photoSeg[0], photoTitle, photoHelp(c, photos));
        content.addView(photoSeg[0]);
        content.addView(groupTitle(c, "RAW (DNG)"));
        com.particlesdevs.photoncamera.ui.controls.IosSegmented rawSeg = segmented(c, raws, state.raw, false, m -> {
            state.raw = m;
            store(state, offered, apply);
            update[0].run();
        });
        rawSeg.setTag("format_raw");
        for (int m = 0; m < rawSeg.getChildCount() && m < 3; m++)
            rawSeg.getChildAt(m).setContentDescription(rawName(c, m) + ", " + rawLine(c, m));
        described(rawSeg, "RAW (DNG)", rawHelp(c));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = UiScale.dp(c, 12);
        content.addView(rawSeg, rp);
        update[0] = () -> {
            boolean rawOnly = state.raw == RAW_ONLY;
            setDisabled(photoSeg[0], rawOnly);
            // «Только RAW»: the photo control is dimmed; TalkBack hears why
            androidx.core.view.ViewCompat.setStateDescription(photoSeg[0],
                    rawOnly ? Lang.t(c, "Не сохраняется: выбрано «Только RAW»", "Not saved: «RAW only» is chosen") : null);
            content.setContentDescription(Lang.t(c, "Сохранится: ", "Saved: ") + summary(state.photo, state.raw));
        };
        update[0].run();
        return content;
    }

    /** P592: what each photo format is and which to take - the long press of the «Фото» control. */
    static String photoHelp(Context c, List<PhotoFormat> photos) {
        StringBuilder b = new StringBuilder();
        for (PhotoFormat f : photos) {
            if (b.length() > 0) b.append('\n');
            b.append(photoName(f)).append(" — ").append(photoLine(c, f)).append('.');
        }
        return b + "\n\n" + Lang.t(c, "Не знаете, что выбрать — берите JPEG.", "Not sure which to take? JPEG.");
    }

    /** P592: what the three RAW choices save - the long press of the «RAW (DNG)» control. */
    static String rawHelp(Context c) {
        return Lang.t(c, "«Нет» — сохраняется только готовое фото.\n«+ RAW» — рядом с фото ложится сырой снимок DNG, чтобы обработать его на компьютере, например в Lightroom или RawTherapee.\n«Только RAW» — один DNG, без готового фото.\n\nЕсли вы не обрабатываете снимки сами, RAW не нужен.",
                "«None»: only the finished photo is saved.\n«+ RAW»: a raw DNG is saved beside the photo, to edit on a computer in Lightroom or RawTherapee, for example.\n«RAW only»: just the DNG, no finished photo.\n\nIf you do not edit your photos yourself, you do not need RAW.");
    }

    /**
     * P592: {@code control}'s description on a long press of it or of any of its parts (a segmented control's buttons take the
     * touch themselves): the settings' help sheet.
     */
    static void described(View control, CharSequence title, CharSequence help) {
        final View.OnLongClickListener open = v -> {
            com.particlesdevs.photoncamera.ui.settings.SettingHelp.show(v.getContext(), title, help);
            return true;
        };
        control.setOnLongClickListener(open);
        if (control instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) control).getChildCount(); i++) ((ViewGroup) control).getChildAt(i).setOnLongClickListener(open);
    }

    /** Stores the state's choice when this phone offers it (AVIF without its encoder, HEIC before Android 9: not offered). */
    static void store(FormatState state, List<FormatChoice> offered, Consumer<FormatChoice> apply) {
        FormatChoice choice = choice(state.photo, state.raw);
        if (offered.contains(choice)) apply.accept(choice);
    }

    // ───── «Размер фото»

    /**
     * Shows «Размер фото»: «Обычный · 12 Мп», «Подробный · 50 Мп», «Максимум · 200 Мп» as radio rows with what each gives;
     * a size the module lacks is dimmed and says why; a pick applies, closes the sheet and the caller shows the message.
     */
    public static BottomSheetDialog size(Context c, int current, boolean full, boolean max, java.util.function.IntConsumer pick,
                                         @Nullable Consumer<CharSequence> refuse) {
        return size(c, current, full, max, pick, refuse, null);
    }

    /** {@code dead}: P214h - a size off by the camera's trials, dimmed, its tap goes to {@code pick} (which explains). */
    public static BottomSheetDialog size(Context c, int current, boolean full, boolean max, java.util.function.IntConsumer pick,
                                         @Nullable Consumer<CharSequence> refuse, @Nullable java.util.function.IntPredicate dead) {
        LinearLayout body = frame(c, Lang.t(c, "Размер фото", "Photo size"));
        BottomSheetDialog[] dialog = new BottomSheetDialog[1];
        body.addView(sizeCard(c, current, full, max, size -> {
            pick.accept(size);
            if (dialog[0] != null) body.postDelayed(dialog[0]::dismiss, 180);
        }, refuse, dead));
        dialog[0] = SettingsStyle.sheet(c, body);
        floating(body);
        enterRows(body);
        dialog[0].show();
        return dialog[0];
    }

    /** The three sizes as radio rows (the camera's sheet and the settings' «Размер фото» page). */
    public static LinearLayout sizeCard(Context c, int current, boolean full, boolean max, java.util.function.IntConsumer pick,
                                        @Nullable Consumer<CharSequence> refuse) {
        return sizeCard(c, current, full, max, pick, refuse, null);
    }

    public static LinearLayout sizeCard(Context c, int current, boolean full, boolean max, java.util.function.IntConsumer pick,
                                        @Nullable Consumer<CharSequence> refuse, @Nullable java.util.function.IntPredicate dead) {
        return sizeCard(c, current, full, max, pick, refuse, dead, null);
    }

    /** P319: {@code normalSize}: the «Обычный» row's size (a module of another camera); null: the open camera's. */
    public static LinearLayout sizeCard(Context c, int current, boolean full, boolean max, java.util.function.IntConsumer pick,
                                        @Nullable Consumer<CharSequence> refuse, @Nullable java.util.function.IntPredicate dead,
                                        @Nullable String normalSize) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        // P574: the concept's grouped rows (#2C2C2E, 24dp corners, no line round them)
        card.setBackground(SettingsStyle.shape(c, ROW, 0, UiScale.dpF(c, 24) / c.getResources().getDisplayMetrics().density));
        card.setClipToOutline(true);
        card.setTag("size_sheet");
        String[][] rows = {
                // P319: the lens's own normal size, not a fixed «12»
                {Lang.t(c, "Обычный · ", "Normal · ") + (normalSize != null ? normalSize : PhotoSize.normalBadge()), Lang.t(c, "Быстро и немного места. Подходит почти всегда", "Fast and small. Fits nearly always")},
                {Lang.t(c, "Подробный · 50 Мп", "Detailed · 50 MP"), Lang.t(c, "Больше деталей при хорошем свете. Снимок дольше", "More detail in good light. A slower shot")},
                {Lang.t(c, "Максимум · 200 Мп", "Maximum · 200 MP"), Lang.t(c, "Для печати и кадрирования", "For prints and cropping")}};
        int[] sizes = {PhotoSize.NORMAL, PhotoSize.FULL, PhotoSize.MAX};
        int accent = ShadeStyle.accent(c);
        for (int i = 0; i < 3; i++) {
            final int size = sizes[i];
            boolean available = i == 0 || i == 1 && full || i == 2 && max;
            boolean off = dead != null && dead.test(size);   // P214h: every row of the size failed
            // P319 (user report: a front camera listed 50 and 200 MP): a size the lens does not have is not listed (one failed by
            // its trials stays, dimmed, with its reason)
            if (!available && !off) continue;
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int ph = UiScale.dp(c, 16), pv = UiScale.dp(c, 12);
            row.setPadding(ph, pv, ph, pv);
            row.setMinimumHeight(UiScale.dp(c, 60));
            row.setBackground(ShadeStyle.ripple(c, 0));
            row.setAlpha(available && !off ? 1f : .45f);
            row.setTag("size_" + size);
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView t = line(c, rows[i][0], 16f, ShadeStyle.TEXT);
            com.particlesdevs.photoncamera.ui.style.Fonts.apply(t, com.particlesdevs.photoncamera.ui.style.Fonts.SEMIBOLD);
            texts.addView(t);
            // P217 / P267: without our remosaic a FULLRES shot is saved as its merged mosaic DNG only
            final boolean binned = i > 0 && available && !off && !ourRemosaic();
            // P592: under the name only a state (why the size is off, what is saved); what the size gives is its long press
            final String state = off ? Lang.t(c, "Режимы не сработали · подробнее", "Its modes failed · details")
                    : binned ? Lang.t(c, "«Наш ремозаик» выключен: сохранится только DNG", "«Our remosaic» is off: only the DNG is saved")
                    : available ? null : Lang.t(c, "Нет на этом объективе", "Not on this lens");
            if (state != null) texts.addView(line(c, state, 13f, ShadeStyle.MUTED));
            final String about = rows[i][1] + ".\n\n" + Lang.t(c, "Какие размеры есть, зависит от объектива: выберите другой на зуме, чтобы увидеть его размеры.",
                    "The sizes depend on the lens: choose another one on the zoom to see its sizes.");
            described(row, rows[i][0], about);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            // P574: the chosen size's trailing tick in the accent (the concept, iOS' list choice) instead of a radio circle
            View tick = check(c, size == current, accent);
            int ts = UiScale.dp(c, 20);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ts, ts);
            tp.setMarginStart(UiScale.dp(c, 12));
            row.addView(tick, tp);
            row.setSelected(size == current);
            // the subtitle too: TalkBack hears why a size is unavailable (the row's own description hides its children)
            row.setContentDescription(rows[i][0] + ", " + (state != null ? state : rows[i][1]));
            androidx.core.view.ViewCompat.setStateDescription(row, size == current ? Lang.t(c, "выбрано", "selected") : null);
            row.setOnClickListener(v -> {
                if (!available && !off) {
                    if (refuse != null) refuse.accept(Lang.t(c, size + " Мп — нет на этом объективе", size + " MP: not on this lens"));
                    return;
                }
                pick.accept(size);
            });
            Motion.press(row, .98f);   // P495: a wide row gives a little less
            if (i > 0) {
                View divider = new View(c);
                divider.setBackgroundColor(ShadeStyle.LINE);
                card.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, UiScale.dp(c, 1))));
            }
            card.addView(row);
        }
        return card;
    }

    /** P217: «Наш ремозаик» (the hybrid's mosaic remosaic) is on; true when the settings cannot be read. */
    static boolean ourRemosaic() {
        try {
            return com.particlesdevs.photoncamera.settings.PreferenceKeys.isHybridMosaicRemosaic();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** P574: the chosen row's tick (the shared menu's tick in the accent); a row not chosen keeps the room for it. */
    static View check(Context c, boolean on, int accent) {
        ImageView v = new ImageView(c);
        // P583: the concept's tick (17 x 14, a 2.4 stroke) in the accent
        ConceptStyle.icon(v, R.drawable.cv_check, accent);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        v.setSelected(on);
        return v;
    }

    /** The concept's .radio: a 22dp ring (2dp), the accent ring and a 10dp dot when on. */
    static View radio(Context c, boolean on, int accent) {
        View v = new View(c);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(UiScale.dp(c, 2), on ? accent : 0xFF6C757C);
        if (on) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(accent);
            android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{ring, dot});
            int inset = UiScale.dp(c, 6);
            layers.setLayerInset(1, inset, inset, inset, inset);
            v.setBackground(layers);
        } else {
            v.setBackground(ring);
        }
        v.setSelected(on);
        return v;
    }
}
