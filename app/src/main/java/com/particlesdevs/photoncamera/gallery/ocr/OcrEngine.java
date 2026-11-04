package com.particlesdevs.photoncamera.gallery.ocr;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import com.googlecode.tesseract.android.TessBaseAPI;
import com.particlesdevs.photoncamera.util.Lang;
import com.particlesdevs.photoncamera.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * P504 (owner, 2026-11-05: «Поиск должен искать текст на фото. В принципе добавь возможность копировать текст с фото на любом
 * языке»): Tesseract 5 (Tesseract4Android) on the tessdata_fast models shipped in assets/tessdata (copied once to the app's files:
 * Tesseract reads a directory). One engine per language set, used by one thread at a time (TessBaseAPI is not thread safe).
 */
public final class OcrEngine {
    private OcrEngine() {}

    private static final String TAG = "GalleryOcr";
    private static final String ASSETS = "tessdata";

    /** The models in the APK, in the order of the language chips. */
    public static final List<String> LANGUAGES = java.util.Collections.unmodifiableList(Arrays.asList(
            "eng", "rus", "deu", "fra", "spa", "ita", "por", "chi_sim", "jpn", "kor", "ara", "hin"));

    /** The chip name of {@code code} in the UI language. */
    public static String name(String code) {
        switch (code) {
            case "eng": return Lang.t("Английский", "English");
            case "rus": return Lang.t("Русский", "Russian");
            case "deu": return Lang.t("Немецкий", "German");
            case "fra": return Lang.t("Французский", "French");
            case "spa": return Lang.t("Испанский", "Spanish");
            case "ita": return Lang.t("Итальянский", "Italian");
            case "por": return Lang.t("Португальский", "Portuguese");
            case "chi_sim": return Lang.t("Китайский", "Chinese");
            case "jpn": return Lang.t("Японский", "Japanese");
            case "kor": return Lang.t("Корейский", "Korean");
            case "ara": return Lang.t("Арабский", "Arabic");
            case "hin": return Lang.t("Хинди", "Hindi");
            default: return code;
        }
    }

    /**
     * The languages read with no choice made: English and Russian, plus the phone's language when a model of it is here (the
     * search index uses this set too). A Latin-script model reads most Latin text; the others are picked in the text sheet.
     */
    public static String autoLanguages() {
        return autoLanguages(Locale.getDefault().getISO3Language());
    }

    static String autoLanguages(@Nullable String phone) {
        List<String> out = new ArrayList<>(Arrays.asList("eng", "rus"));
        if (phone != null && LANGUAGES.contains(phone) && !out.contains(phone)) out.add(phone);
        if ("zho".equals(phone)) out.add("chi_sim");
        return String.join("+", out);
    }

    private static final Object LOCK = new Object();
    @Nullable private static TessBaseAPI api;
    @Nullable private static String apiLanguages;

    /** The models in the app's files (copied from the APK when missing or of another size); the directory holding «tessdata». */
    static File dataDir(Context c) throws IOException {
        File root = new File(c.getFilesDir(), "ocr");
        File dir = new File(root, "tessdata");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no " + dir);
        String[] names = c.getAssets().list(ASSETS);
        if (names == null) return root;
        for (String n : names) {
            if (!n.endsWith(".traineddata")) continue;
            File out = new File(dir, n);
            try (InputStream in = c.getAssets().open(ASSETS + "/" + n)) {
                long size = in.available();
                if (out.isFile() && out.length() == size && size > 0) continue;
                File tmp = new File(dir, n + ".part");
                try (OutputStream os = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 16];
                    for (int r; (r = in.read(buf)) > 0; ) os.write(buf, 0, r);
                }
                if (!tmp.renameTo(out)) throw new IOException("rename " + out);
            }
        }
        return root;
    }

    /**
     * The text of {@code bitmap} in {@code languages} ("eng+rus"); empty when there is none, null on an error. Blocks: call it off
     * the main thread.
     */
    @Nullable
    public static String recognize(Context c, Bitmap bitmap, String languages) {
        synchronized (LOCK) {
            try {
                if (api == null || !languages.equals(apiLanguages)) {
                    if (api != null) api.recycle();
                    api = new TessBaseAPI();
                    apiLanguages = null;
                    if (!api.init(dataDir(c.getApplicationContext()).getAbsolutePath(), languages, TessBaseAPI.OEM_LSTM_ONLY)) {
                        api.recycle();
                        api = null;
                        Log.w(TAG, "init failed: " + languages);
                        return null;
                    }
                    apiLanguages = languages;
                }
                api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
                api.setImage(bitmap);
                String text = api.getUTF8Text();
                api.clear();
                return text == null ? "" : clean(text);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "recognize: " + e);
                return null;
            }
        }
    }

    /** Stops a recognition in progress (the sheet closed). */
    public static void stop() {
        TessBaseAPI a = api;
        if (a != null) a.stop();
    }

    /** Tesseract's text without the stray one-letter lines of noise; runs of empty lines become one paragraph break. */
    static String clean(String text) {
        StringBuilder out = new StringBuilder();
        boolean paragraph = false;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                paragraph = out.length() > 0;
                continue;
            }
            if (t.codePointCount(0, t.length()) < 2) continue;
            if (out.length() > 0) out.append(paragraph ? "\n\n" : "\n");
            paragraph = false;
            out.append(t);
        }
        return out.toString();
    }

    /** {@code uri}'s image, upright (EXIF) and at most {@code maxSide} on its long side; null when it cannot be read. */
    @Nullable
    public static Bitmap load(Context c, Uri uri, int maxSide) {
        ContentResolver cr = c.getContentResolver();
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, o);
            }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            Bitmap b;
            try (InputStream in = cr.openInputStream(uri)) {
                b = BitmapFactory.decodeStream(in, null, d);
            }
            if (b == null) return null;
            int rotation = 0;
            try (InputStream in = cr.openInputStream(uri)) {
                if (in != null) rotation = new ExifInterface(in).getRotationDegrees();
            } catch (IOException | RuntimeException ignored) {
            }
            float s = Math.min(1f, (float) maxSide / Math.max(b.getWidth(), b.getHeight()));
            if (rotation == 0 && s >= 1f) return b;
            Matrix m = new Matrix();
            m.postScale(s, s);
            m.postRotate(rotation);
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            return r;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "load " + uri + ": " + e);
            return null;
        }
    }
}
