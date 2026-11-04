package com.particlesdevs.photoncamera.gallery.ocr;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.gallery.files.MediaFile;
import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P504: the gallery's text index - the words Tesseract reads on each photo, kept in the app's database by the photo's id and its
 * modification time, so «Поиск» finds a photo by the text on it. While the gallery is open a background thread reads the photos
 * not read yet, the newest first (a 1600 px copy, the {@link OcrEngine#autoLanguages()} set), and pauses on a low battery.
 */
public final class OcrIndex {
    private OcrIndex() {}

    private static final String TAG = "GalleryOcr";
    /** The long side the index reads a photo at: enough for signs and documents, ~1 s a photo. */
    static final int INDEX_SIDE = 1600;
    /** Below this battery level (not charging) the index waits for the next gallery visit. */
    static final int MIN_BATTERY = 25;

    private static final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    private static final AtomicInteger version = new AtomicInteger();
    private static volatile boolean loaded;
    @Nullable private static volatile Thread worker;
    @Nullable private static Db db;

    private static final class Entry {
        final long modified;
        final String text, lower;

        Entry(long modified, String text) {
            this.modified = modified;
            this.text = text;
            this.lower = text.toLowerCase(Locale.ROOT);
        }
    }

    /** Grows whenever photos are read: a search cache older than it reads again. */
    public static int version() {
        return version.get();
    }

    /** The text on {@code f} (lower case for matching), null when not read yet or changed since. */
    @Nullable
    public static String searchText(@Nullable MediaFile f) {
        Entry e = f == null ? null : entries.get(f.getId());
        return e != null && e.modified == f.getLastModified() ? e.lower : null;
    }

    /** The text on {@code f} as read, null when not read yet or changed since. */
    @Nullable
    public static String text(@Nullable MediaFile f) {
        Entry e = f == null ? null : entries.get(f.getId());
        return e != null && e.modified == f.getLastModified() ? e.text : null;
    }

    /** Whether the index reads {@code name} (photos Android decodes; not videos or RAW). */
    public static boolean indexable(@Nullable String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".heic")
                || n.endsWith(".heif");
    }

    /** {@code wanted} without the photos read already, in its order. */
    static List<MediaFile> pending(List<MediaFile> wanted) {
        List<MediaFile> out = new ArrayList<>();
        for (MediaFile f : wanted) if (f != null && indexable(f.getDisplayName()) && text(f) == null) out.add(f);
        return out;
    }

    /**
     * Reads the photos of {@code library} (the newest first) not read yet, in the background; {@code changed} runs on the main
     * thread after every few photos. A running read stops first.
     */
    public static synchronized void start(Context context, List<MediaFile> library, @Nullable Runnable changed) {
        stop();
        final Context app = context.getApplicationContext();
        final List<MediaFile> todo = new ArrayList<>(library);
        Thread t = new Thread(() -> run(app, todo, changed), "GalleryOcrIndex");
        t.setPriority(Thread.MIN_PRIORITY);
        worker = t;
        t.start();
    }

    /** Stops the background read (the gallery closed). */
    public static synchronized void stop() {
        Thread t = worker;
        worker = null;
        if (t != null) {
            t.interrupt();
            OcrEngine.stop();
        }
    }

    private static void run(Context app, List<MediaFile> library, @Nullable Runnable changed) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        load(app);
        List<MediaFile> todo = pending(library);
        if (todo.isEmpty()) return;
        Log.i(TAG, "index: " + todo.size() + " photos to read");
        final String languages = OcrEngine.autoLanguages();
        final Handler main = new Handler(Looper.getMainLooper());
        int done = 0;
        for (MediaFile f : todo) {
            if (Thread.currentThread() != worker || Thread.interrupted()) return;
            if (done % 10 == 0 && !batteryOk(app)) {
                Log.i(TAG, "index: paused on a low battery after " + done);
                return;
            }
            Bitmap b = OcrEngine.load(app, f.getFileUri(), INDEX_SIDE);
            String text = b == null ? "" : OcrEngine.recognize(app, b, languages);
            if (b != null) b.recycle();
            if (text == null) continue; // an engine error: tried again on the next visit
            put(app, f.getId(), f.getLastModified(), text);
            done++;
            if (changed != null && (done % 5 == 0 || done == todo.size())) main.post(changed);
        }
        Log.i(TAG, "index: " + done + " photos read");
    }

    static boolean batteryOk(Context c) {
        try {
            Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return true;
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            if (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) return true;
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            return level < 0 || scale <= 0 || level * 100 / scale >= MIN_BATTERY;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Stores the text read on a photo (here and in the database). */
    public static void put(Context c, long id, long modified, String text) {
        entries.put(id, new Entry(modified, text));
        version.incrementAndGet();
        try {
            ContentValues v = new ContentValues();
            v.put("id", id);
            v.put("modified", modified);
            v.put("text", text);
            db(c).getWritableDatabase().insertWithOnConflict("ocr", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (RuntimeException e) {
            Log.w(TAG, "store: " + e);
        }
    }

    /** Reads the database once. */
    public static void load(Context c) {
        if (loaded) return;
        synchronized (OcrIndex.class) {
            if (loaded) return;
            try (Cursor cur = db(c).getReadableDatabase().query("ocr", new String[]{"id", "modified", "text"}, null, null, null, null, null)) {
                while (cur.moveToNext()) entries.put(cur.getLong(0), new Entry(cur.getLong(1), cur.getString(2) == null ? "" : cur.getString(2)));
            } catch (RuntimeException e) {
                Log.w(TAG, "load: " + e);
            }
            loaded = true;
            version.incrementAndGet();
        }
    }

    private static synchronized Db db(Context c) {
        if (db == null) db = new Db(c.getApplicationContext());
        return db;
    }

    private static final class Db extends SQLiteOpenHelper {
        Db(Context c) {
            super(c, "gallery_ocr.db", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase d) {
            d.execSQL("CREATE TABLE ocr (id INTEGER PRIMARY KEY, modified INTEGER, text TEXT)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase d, int oldVersion, int newVersion) {
            d.execSQL("DROP TABLE IF EXISTS ocr");
            onCreate(d);
        }
    }
}
