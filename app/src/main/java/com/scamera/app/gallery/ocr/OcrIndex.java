package com.scamera.app.gallery.ocr;

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
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.scamera.app.gallery.files.MediaFile;
import com.scamera.app.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P504: the gallery's text index - the words Tesseract reads on each photo, kept in the app's database by the photo's id and its
 * modification time, so «Поиск» finds a photo by the text on it. While the gallery is open a background thread reads the photos
 * not read yet, the newest first (a 1600 px copy, the {@link OcrEngine#autoLanguages()} set), and pauses on a low battery.
 * <p>
 * P617 (owner, 2026-11-11: «иногда очень долго открывается галерея»; the log: «index: 9179 photos to read» as the gallery opened):
 * the index no longer competes with the gallery's own start. It waits for {@link OcrPace} (6 s after the open, the grid quiet,
 * no shot in processing, the phone not warm), rests between photos (at most half the time busy), takes a new list without
 * starting over (each start used to stop the read in progress), stores nothing a stop cut short (an empty text was kept for the
 * photo for good), skips for this process the photos the engine fails on, frees the engine when it ends, and reads its database
 * without the lock {@link #start} takes on the main thread.
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
    /** P617: the database read once (not the class lock: {@link #start} on the main thread waited for the whole read). */
    private static final Object LOAD_LOCK = new Object();
    /** P617: the library the running index reads (the newest list {@link #start} got) and what it tells when photos are read. */
    @Nullable private static volatile List<MediaFile> library;
    @Nullable private static volatile Runnable changed;
    /** P617: when the index may read (uptime ms): the open's delay and the grid's quiet time ({@link #touch}). */
    private static volatile long startAt, quietUntil;
    /** P617: engine errors in a row that end the index's visit (a model that does not load fails every photo at once). */
    static final int MAX_FAILURES_IN_A_ROW = 5;
    /** P617: photos the engine failed on in this process (not tried again until the app starts again). */
    private static final Set<Long> failed = ConcurrentHashMap.newKeySet();

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
        Entry e = f == null ? null : entries.get(f.rowId());
        return e != null && e.modified == f.modifiedMs() ? e.lower : null;
    }

    /** The text on {@code f} as read, null when not read yet or changed since. */
    @Nullable
    public static String text(@Nullable MediaFile f) {
        Entry e = f == null ? null : entries.get(f.rowId());
        return e != null && e.modified == f.modifiedMs() ? e.text : null;
    }

    /** Whether the index reads {@code name} (photos Android decodes; not videos or RAW). */
    public static boolean indexable(@Nullable String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".heic")
                || n.endsWith(".heif");
    }

    /** {@code wanted} without the photos read already (or failed in this process), in its order. */
    static List<MediaFile> pending(List<MediaFile> wanted) {
        List<MediaFile> out = new ArrayList<>();
        for (MediaFile f : wanted)
            if (f != null && indexable(f.displayName()) && text(f) == null && !failed.contains(f.rowId())) out.add(f);
        return out;
    }

    /**
     * Reads the photos of {@code library} (the newest first) not read yet, in the background; {@code changed} runs on the main
     * thread after every few photos. P617: a running index takes the new list and goes on (no new start, no delay again).
     */
    public static synchronized void start(Context context, List<MediaFile> library, @Nullable Runnable changed) {
        OcrIndex.library = new ArrayList<>(library);
        OcrIndex.changed = changed;
        Thread running = worker;
        if (running != null && running.isAlive()) return;
        startAt = SystemClock.uptimeMillis() + OcrPace.START_DELAY_MS;
        final Context app = context.getApplicationContext();
        Thread t = new Thread(() -> run(app), "GalleryOcrIndex");
        t.setPriority(Thread.MIN_PRIORITY);
        worker = t;
        t.start();
    }

    /** Stops the background read (the gallery closed): the photo in progress is cut short and not stored. */
    public static synchronized void stop() {
        Thread t = worker;
        worker = null;
        changed = null;
        if (t != null) {
            t.interrupt();
            OcrEngine.stop();
        }
    }

    /** The index thread running now, null for none (tests). */
    @Nullable
    static Thread runningWorker() {
        return worker;
    }

    /** P617: the gallery is busy (the grid moved, a new list): the index waits {@link OcrPace#QUIET_MS} from now. */
    public static void touch() {
        quietUntil = SystemClock.uptimeMillis() + OcrPace.QUIET_MS;
    }

    /** P617: the text sheet closed: the engine goes unless the index uses it. */
    public static void releaseEngineIfIdle() {
        if (worker == null) OcrEngine.release();
    }

    /** Whether a shot is captured, queued or processed (the camera's merge and pipeline come first). */
    private static boolean shotBusy() {
        try {
            return com.scamera.app.processing.ProcessingKeepAlive.busy.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Waits until {@link OcrPace} lets the next photo be read; false when the index was stopped meanwhile. */
    private static boolean gate(Thread self, long[] waitedMs) {
        while (self == worker) {
            long now = SystemClock.uptimeMillis();
            long w = OcrPace.waitMs(now, startAt, quietUntil, shotBusy(), com.scamera.app.util.ThermalPolicy.status());
            if (w <= 0) return true;
            long sleep = OcrPace.sleepMs(w);
            if (!sleep(sleep)) return false;
            waitedMs[0] += sleep;
        }
        return false;
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private static void run(Context app) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        final Thread self = Thread.currentThread();
        final long t0 = SystemClock.uptimeMillis();
        final long[] waited = {0};
        int done = 0, unreadable = 0, failedInARow = 0;
        long work = 0;
        boolean finished = false;
        try {
            load(app); // the search finds the text read before at once
            if (!gate(self, waited)) return;
            final String languages = OcrEngine.autoLanguages();
            final Handler main = new Handler(Looper.getMainLooper());
            List<MediaFile> seen = null, todo = new ArrayList<>();
            int at = 0;
            boolean announced = false;
            while (self == worker) {
                List<MediaFile> lib = library;
                if (lib != seen || at >= todo.size()) {
                    // a new list from the gallery, or this one gone through: what is left of the newest one
                    seen = lib;
                    todo = pending(lib != null ? lib : new ArrayList<>());
                    at = 0;
                    if (todo.isEmpty()) {
                        finished = true;
                        break;
                    }
                    if (!announced) Log.i(TAG, "index: " + todo.size() + " photos to read (after " + (SystemClock.uptimeMillis() - t0) + " ms of waiting)");
                    announced = true;
                }
                if (!gate(self, waited)) return;
                if (done % 10 == 0 && !batteryOk(app)) {
                    Log.i(TAG, "index: paused on a low battery after " + done);
                    return;
                }
                MediaFile f = todo.get(at++);
                if (text(f) != null) continue; // read meanwhile (the text sheet)
                final long w0 = SystemClock.uptimeMillis();
                Bitmap b = OcrEngine.load(app, f.contentUri(), INDEX_SIDE);
                String text = b == null ? "" : OcrEngine.recognize(app, b, languages);
                if (b != null) b.recycle();
                final long took = SystemClock.uptimeMillis() - w0;
                if (self != worker) return; // stopped meanwhile: a read cut short is not stored
                if (text == null) {
                    failed.add(f.rowId()); // an engine error: tried again when the app starts again
                    unreadable++;
                    if (++failedInARow >= MAX_FAILURES_IN_A_ROW) {
                        Log.w(TAG, "index: the engine failed on " + failedInARow + " photos in a row, stopped until the next visit");
                        return;
                    }
                    if (!sleep(OcrPace.restMs(took, 1))) return;
                    continue;
                }
                failedInARow = 0;
                put(app, f.rowId(), f.modifiedMs(), text);
                done++;
                work += took;
                Runnable ch = changed;
                if (ch != null && done % 5 == 0) main.post(ch);
                if (done % OcrPace.BATCH == 0)
                    Log.i(TAG, "index: " + done + " read, " + work / done + " ms a photo, " + (todo.size() - at) + " left");
                final long rest = OcrPace.restMs(took, done);
                waited[0] += rest;
                if (!sleep(rest)) return;
            }
            Runnable ch = changed;
            if (ch != null && done > 0) main.post(ch);
        } finally {
            Log.i(TAG, "index: " + done + " photos read in " + (SystemClock.uptimeMillis() - t0) / 1000 + " s (" + (done > 0 ? work / done : 0)
                    + " ms a photo, " + unreadable + " unreadable, " + waited[0] / 1000 + " s waiting / resting), "
                    + (finished ? "nothing left" : "stopped"));
            synchronized (OcrIndex.class) {
                if (worker == self) worker = null;
            }
            if (worker == null) OcrEngine.release(); // P617: Tesseract's native memory does not stay beside the camera
        }
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
        synchronized (LOAD_LOCK) {
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
