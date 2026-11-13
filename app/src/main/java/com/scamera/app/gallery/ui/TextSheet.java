package com.scamera.app.gallery.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.scamera.app.gallery.files.MediaFile;
import com.scamera.app.gallery.ocr.OcrEngine;
import com.scamera.app.gallery.ocr.OcrIndex;
import com.scamera.app.util.Lang;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P504 (owner, 2026-11-05: «добавь возможность копировать текст с фото на любом языке»): the viewer's «Текст на фото» sheet - the
 * photo read by Tesseract («Авто»: English, Russian and the phone's language; a chip picks another language), the text selectable
 * for copying a part, «Копировать всё» and «Поделиться» for the whole. A read in «Авто» also goes into the search index.
 */
public final class TextSheet {
    private TextSheet() {}

    /** The long side the sheet reads a photo at (small print needs more than the index's 1600). */
    static final int SHEET_SIDE = 2600;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> new Thread(r, "GalleryOcrSheet"));
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void show(Context c, MediaFile file) {
        LinearLayout s = GallerySheets.frame(c);
        s.addView(GallerySheets.title(c, Lang.t("Текст на фото", "Text in the photo")));
        List<String> codes = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        codes.add(null);
        labels.add(Lang.t("Авто", "Auto"));
        for (String code : OcrEngine.LANGUAGES) {
            codes.add(code);
            labels.add(OcrEngine.name(code));
        }
        TextView status = GallerySheets.sub(c, "");
        TextView text = GalleryUi.text(c, "", 15, GalleryUi.TEXT);
        text.setTextIsSelectable(true);
        text.setLineSpacing(0, 1.25f);
        int p = GalleryUi.dp(c, 14);
        text.setPadding(p, p, p, p);
        text.setBackground(GalleryUi.card(c, GalleryUi.CARD, 24)); // P579: a #2C2C2E card, 24dp corners
        p = GalleryUi.dp(c, 16);
        text.setPadding(p, p, p, p);
        text.setMinHeight(GalleryUi.dp(c, 96));
        final String[] result = {""};
        final int[] run = {0};
        final Runnable[] read = new Runnable[1];
        final int[] chosen = {0};
        read[0] = () -> {
            final int id = ++run[0];
            final String code = codes.get(chosen[0]);
            final String languages = code == null ? OcrEngine.autoLanguages() : code.equals("eng") ? "eng" : code + "+eng";
            status.setText(Lang.t("Распознаю…", "Reading…"));
            text.setText("");
            text.setVisibility(View.GONE);
            final Context app = c.getApplicationContext();
            IO.execute(() -> {
                Bitmap b = OcrEngine.load(app, file.getFileUri(), SHEET_SIDE);
                String r = b == null ? null : OcrEngine.recognize(app, b, languages);
                if (b != null) b.recycle();
                if (r != null && code == null) OcrIndex.put(app, file.getId(), file.getLastModified(), r);
                MAIN.post(() -> {
                    if (id != run[0]) return;
                    result[0] = r == null ? "" : r;
                    if (r == null) status.setText(Lang.t("Не удалось прочитать снимок.", "Could not read the photo."));
                    else if (r.isEmpty()) status.setText(Lang.t("Текст не найден. Попробуйте выбрать язык.", "No text found. Try picking the language."));
                    else {
                        status.setText(Lang.t("Выделите часть текста или скопируйте весь.", "Select a part of the text or copy all of it."));
                        text.setText(r);
                        text.setVisibility(View.VISIBLE);
                    }
                });
            });
        };
        // P579: the language a row of a card with the shared pull-down menu (it was a row of chips that scrolled sideways)
        LinearLayout language = GalleryUi.group(c, 16);
        language.addView(GallerySheets.menuRow(c, Lang.t("Язык", "Language"), labels.toArray(new String[0]), 0, index -> {
            chosen[0] = index;
            read[0].run();
        }, "ocr_language"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = GalleryUi.dp(c, 10);
        lp.bottomMargin = GalleryUi.dp(c, 4);
        s.addView(language, lp);
        s.addView(status);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = GalleryUi.dp(c, 4);
        s.addView(text, tp);
        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView share = GallerySheets.wideButton(c, Lang.t("Поделиться", "Share"), false);
        TextView copy = GallerySheets.wideButton(c, Lang.t("Копировать всё", "Copy all"), true);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        buttons.addView(share, bp);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        cp.leftMargin = GalleryUi.dp(c, 10);
        buttons.addView(copy, cp);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wp.topMargin = GalleryUi.dp(c, 14);
        s.addView(buttons, wp);
        copy.setOnClickListener(v -> {
            if (result[0].isEmpty()) return;
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(Lang.t("Текст с фото", "Text from the photo"), result[0]));
            com.scamera.app.gallery.ui.GalleryToast.show(c, Lang.t("Текст скопирован", "Text copied"));
        });
        share.setOnClickListener(v -> {
            if (result[0].isEmpty()) return;
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, result[0]);
            try {
                c.startActivity(Intent.createChooser(i, null));
            } catch (RuntimeException ignored) {
            }
        });
        status.setTypeface(com.scamera.app.ui.style.Fonts.of(status.getContext(), 400));
        BottomSheetDialog d = GallerySheets.show(c, s);
        d.setOnDismissListener(x -> {
            run[0]++;
            OcrEngine.stop();
            IO.execute(OcrIndex::releaseEngineIfIdle); // P617: the engine's native memory goes with the sheet (after its read)
        });
        read[0].run();
    }
}
