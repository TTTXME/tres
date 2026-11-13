package com.scamera.app.gallery.ocr;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import com.googlecode.tesseract.android.TessBaseAPI;
import com.scamera.app.util.Lang;
import com.scamera.app.util.Log;

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
    /**
     * P617: guards {@link #api} against {@link #stop} from another thread: a stop of an engine recycled meanwhile threw
     * IllegalStateException (TessBaseAPI.stop checks mRecycled), and a recognition a stop cut short is told apart by {@link #stops}.
     */
    private static final Object STOP_LOCK = new Object();
    @Nullable private static volatile TessBaseAPI api;
    @Nullable private static String apiLanguages;
    /** P617: raised by every {@link #stop}: a recognition that saw it change returns null (cut short, not «no text»). */
    private static int stops;
    /**
     * P617: Tesseract4Android's OpenMP build ran its LSTM on every core at once (8 on the 8 Elite) beside the gallery's thumbnail
     * decodes; LLVM's OpenMP reads this at its first parallel region in the process, so it is set before the library's first use.
     */
    static final int OMP_THREADS = 4;

    static {
        try {
            android.system.Os.setenv("OMP_THREAD_LIMIT", String.valueOf(OMP_THREADS), false);
        } catch (Throwable ignored) {
            // the JVM of the unit tests, or a refused setenv: Tesseract's own thread count
        }
    }

    /** The models of {@code languages} ("eng+rus") in the app's files (copied from the APK when missing or of another size). */
    static File dataDir(Context c, String languages) throws IOException {
        File root = new File(c.getFilesDir(), "ocr");
        File dir = new File(root, "tessdata");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no " + dir);
        // P617: only the languages asked for (all twelve models, 26 MB, were copied at the first read and checked at every init)
        for (String code : modelsOf(languages)) {
            String n = code + ".traineddata";
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

    /** The model names of a Tesseract language string ("eng+rus" -> eng, rus), only the ones the APK has. */
    static List<String> modelsOf(@Nullable String languages) {
        List<String> out = new ArrayList<>();
        if (languages == null) return out;
        for (String code : languages.split("\\+")) if (LANGUAGES.contains(code) && !out.contains(code)) out.add(code);
        return out;
    }

    /**
     * The text of {@code bitmap} in {@code languages} ("eng+rus"); empty when there is none, null on an error or when a
     * {@link #stop} cut it short. Blocks: call it off the main thread.
     */
    @Nullable
    public static String recognize(Context c, Bitmap bitmap, String languages) {
        synchronized (LOCK) {
            try {
                TessBaseAPI a = api;
                if (a == null || !languages.equals(apiLanguages)) {
                    drop();
                    a = new TessBaseAPI();
                    if (!a.init(dataDir(c.getApplicationContext(), languages).getAbsolutePath(), languages, TessBaseAPI.OEM_LSTM_ONLY)) {
                        a.recycle();
                        Log.w(TAG, "init failed: " + languages);
                        return null;
                    }
                    apiLanguages = languages;
                    synchronized (STOP_LOCK) {
                        api = a;
                    }
                }
                final int stopsBefore;
                synchronized (STOP_LOCK) {
                    stopsBefore = stops;
                }
                a.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
                a.setImage(bitmap);
                String text = a.getUTF8Text();
                a.clear();
                synchronized (STOP_LOCK) {
                    if (stops != stopsBefore) return null; // cut short: its text is partial
                }
                return text == null ? "" : clean(text);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "recognize: " + e);
                return null;
            }
        }
    }

    /** The engine goes (under LOCK): nulled for {@link #stop} first, then recycled. */
    private static void drop() {
        TessBaseAPI a;
        synchronized (STOP_LOCK) {
            a = api;
            api = null;
        }
        apiLanguages = null;
        if (a != null) a.recycle();
    }

    /**
     * P617: the engine and its models' native memory freed (the index finished or stopped, the text sheet closed): it stayed for
     * the rest of the process, beside the camera. Waits for a recognition in progress; the next one starts it again.
     */
    public static void release() {
        synchronized (LOCK) {
            if (api != null) Log.i(TAG, "engine released");
            drop();
        }
    }

    /** Stops a recognition in progress (the sheet closed, the index stopped); safe from any thread. */
    public static void stop() {
        synchronized (STOP_LOCK) {
            stops++;
            TessBaseAPI a = api;
            if (a == null) return;
            try {
                a.stop();
            } catch (RuntimeException e) {
                Log.w(TAG, "stop: " + e);
            }
        }
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
