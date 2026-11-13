package com.scamera.app.gallery.files;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.scamera.app.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * P645 (owner, 2026-11-12: «Галерея у нас всё ещё долго открывается, причём она долго открывается даже если открывать её с
 * рабочего стола»; vivo X200 Ultra log: «other RAW 61 rows 5772 ms» of a 5863 ms scan, 3207 ms on the next open, while the
 * 9676 images took 83 ms): the camera RAWs MediaStore does not file as images (P352: the Files collection, 41 name patterns, a
 * scan of the whole Files table - every file of the storage, not only the media - on every open whose MediaStore changed, i.e.
 * after every shot) are kept here, in memory and in a small file, so the gallery's first list takes them from the last check
 * without any query, and the check runs after the list is out ({@link #check}):
 * <ul>
 * <li>the same MediaStore (every external volume's version and generation unchanged): no query at all;</li>
 * <li>the same volume version, a newer generation (Android 11+): the rows known already are read back by their ids (a lookup by
 * the primary key; a deleted, trashed or renamed-away RAW drops out) and only the rows changed since the last check are read
 * ({@code generation_modified > last}: the 41 name patterns only for those);</li>
 * <li>otherwise (no cache, a new volume version, before Android 11): the whole query, as before - off the list's way.</li>
 * </ul>
 * The rows found go into the folders exactly as before (GalleryFileOperations: after the images, a row the images have is not
 * added again).
 */
public final class OtherRawCache {
    private OtherRawCache() {}

    static final String TAG = "GalleryMedia";
    /** The cache file's first line (another one: the file is ignored and the next check reads everything). */
    static final String FORMAT = "scam-other-raw v1";
    static final String FILE_NAME = "gallery_other_raw.txt";
    /** The ids one lookup carries at most (SQLite's limit is 999 bound variables; the ids are inlined numbers anyway). */
    static final int IDS_PER_QUERY = 400;
    /** The volume of the rows read without one (before Android 11: MediaStore's «external»). */
    static final String ANY_VOLUME = "external";

    /** One RAW row of the Files collection. */
    public static final class Row {
        final String volume;
        final long id, addedSec, size, bucketId;
        @Nullable final String name, data, bucket;

        Row(String volume, long id, @Nullable String name, long addedSec, long size, @Nullable String data, long bucketId, @Nullable String bucket) {
            this.volume = volume;
            this.id = id;
            this.name = name;
            this.addedSec = addedSec;
            this.size = size;
            this.data = data;
            this.bucketId = bucketId;
            this.bucket = bucket;
        }

        /** The gallery's file of the row (the same URI as before P645: the Files collection of «external»). */
        ImageFile file() {
            return new ImageFile(id, ContentUris.withAppendedId(MediaStore.Files.getContentUri(ANY_VOLUME), id), name,
                    TimeUnit.SECONDS.toMillis(addedSec), size, data);
        }

        String line() {
            return clean(volume) + '\t' + id + '\t' + clean(name) + '\t' + addedSec + '\t' + size + '\t' + clean(data) + '\t' + bucketId + '\t' + clean(bucket);
        }

        @Nullable
        static Row parse(String line) {
            String[] p = line.split("\t", -1);
            if (p.length != 8) return null;
            try {
                return new Row(p[0], Long.parseLong(p[1]), back(p[2]), Long.parseLong(p[3]), Long.parseLong(p[4]), back(p[5]),
                        Long.parseLong(p[6]), back(p[7]));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static String clean(@Nullable String s) {
            return s == null ? "\u0000" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
        }

        @Nullable
        private static String back(String s) {
            return "\u0000".equals(s) ? null : s;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Row && ((Row) o).line().equals(line());
        }

        @Override
        public int hashCode() {
            return line().hashCode();
        }
    }

    /** An external volume of MediaStore now: its name, version (a new one: the database was rebuilt) and generation. */
    public static final class Volume {
        final String name, version;
        final long generation;

        public Volume(String name, String version, long generation) {
            this.name = name;
            this.version = version;
            this.generation = generation;
        }
    }

    /** Where the rows come from: MediaStore in the app, a fake in the tests. */
    public interface Source {
        /** Every external volume with its version and generation; null when unknown (before Android 11): each check reads all. */
        @Nullable
        List<Volume> volumes();

        /** The Files rows of {@code volume} ({@link #ANY_VOLUME}: every one) matching {@code selection}. */
        List<Row> query(String volume, String selection, @Nullable String[] args);
    }

    /** What a check did, for the log and the gallery (a changed set is put into the lists shown). */
    public static final class Result {
        public final boolean changed;
        public final int rows;
        public final long ms;
        public final String how;

        Result(boolean changed, int rows, long ms, String how) {
            this.changed = changed;
            this.rows = rows;
            this.ms = ms;
            this.how = how;
        }

        @Override
        public String toString() {
            return how + ", " + rows + " rows, " + ms + " ms" + (changed ? ", changed" : ", unchanged");
        }
    }

    // ---------------------------------------------------------------- state (OtherRawCache.class)

    /** The rows of the last check (null: not loaded yet). */
    @Nullable private static List<Row> rows;
    /** Each volume as it was at the last complete check (empty: none, or before Android 11). */
    private static Map<String, Volume> checked = new LinkedHashMap<>();
    /** {@link #key} of the last complete check, null: none. */
    @Nullable private static String checkedKey;
    /** Where the cached rows came from at the last {@link #rows} («memory», «file», «none»), for the scan's log line. */
    private static String origin = "none";
    /** One check at a time (the gallery's RAW thread; a settings screen's scan may ask too). */
    private static final Object CHECK = new Object();

    /** The cached rows (the file read once per process), no query; empty without a cache. */
    @NonNull
    public static synchronized List<Row> rows(@Nullable File file) {
        if (rows == null) {
            rows = new ArrayList<>();
            origin = load(file) ? "file" : "none";
        } else origin = "memory";
        return new ArrayList<>(rows);
    }

    /** The rows now, the file read when nothing is loaded yet ({@link #origin} untouched otherwise). */
    private static synchronized List<Row> loaded(@Nullable File file) {
        if (rows == null) return rows(file);
        return new ArrayList<>(rows);
    }

    /** Where the rows of the last {@link #rows} came from. */
    public static synchronized String origin() {
        return origin;
    }

    /** MediaStore's state as a key: every volume's name, version and generation; null when unknown. */
    @Nullable
    static String key(@Nullable List<Volume> volumes) {
        if (volumes == null || volumes.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        for (Volume v : volumes) b.append(v.name).append(':').append(v.version).append(':').append(v.generation).append(';');
        return b.toString();
    }

    /** The selection of the RAWs MediaStore does not file as images (P352): not an image, a RAW extension in the name. */
    static String selection() {
        StringBuilder sel = new StringBuilder(MediaStore.Files.FileColumns.MEDIA_TYPE + " != " + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE + " AND (");
        boolean first = true;
        for (int i = 0; i < patterns().length; i++) {
            if (!first) sel.append(" OR ");
            first = false;
            sel.append(MediaStore.MediaColumns.DISPLAY_NAME).append(" LIKE ?");
        }
        return sel.append(')').toString();
    }

    private static String[] patterns;

    /** «%.cr3» and the rest, in a fixed order. */
    static synchronized String[] patterns() {
        if (patterns == null) {
            List<String> args = new ArrayList<>();
            for (String ext : new TreeSet<>(com.scamera.app.gallery.raw.RawFormats.EXTENSIONS)) args.add("%." + ext);
            patterns = args.toArray(new String[0]);
        }
        return patterns;
    }

    private static String[] withFirst(String first, String[] rest) {
        String[] out = new String[rest.length + 1];
        out[0] = first;
        System.arraycopy(rest, 0, out, 1, rest.length);
        return out;
    }

    /**
     * Brings the rows up to MediaStore's state now (see the class): nothing when it is unchanged since the last check, the known
     * ids and the rows changed since then when the volume is the same, else everything. Runs its queries outside the lock of
     * {@link #rows}; saves the rows to {@code file}. Any thread but the main one.
     */
    @NonNull
    public static Result check(@NonNull Source source, @Nullable File file) {
        synchronized (CHECK) {
            final long t0 = SystemClock.uptimeMillis();
            final List<Row> before = loaded(file);
            final Map<String, Volume> was;
            final String wasKey;
            synchronized (OtherRawCache.class) {
                was = new LinkedHashMap<>(checked);
                wasKey = checkedKey;
            }
            final List<Volume> now = source.volumes(); // before the queries: a row added meanwhile is in the next check's delta
            final String nowKey = key(now);
            if (nowKey != null && nowKey.equals(wasKey))
                return new Result(false, before.size(), SystemClock.uptimeMillis() - t0, "MediaStore unchanged, no query");
            final List<Row> after = new ArrayList<>();
            final StringBuilder how = new StringBuilder();
            try {
                if (now == null || now.isEmpty()) {
                    after.addAll(source.query(ANY_VOLUME, selection(), patterns()));
                    how.append("all read (no MediaStore generation)");
                } else {
                    for (Volume v : now) {
                        if (how.length() > 0) how.append("; ");
                        after.addAll(volume(source, v, was.get(v.name), before, how));
                    }
                }
            } catch (RuntimeException e) {
                // the cache stays as it was (a failed query is not «no RAWs»); the next check tries again
                return new Result(false, before.size(), SystemClock.uptimeMillis() - t0, "failed: " + e);
            }
            final boolean changed = after.size() != before.size() || !new java.util.HashSet<>(after).equals(new java.util.HashSet<>(before));
            synchronized (OtherRawCache.class) {
                rows = new ArrayList<>(after);
                checked = new LinkedHashMap<>();
                if (now != null) for (Volume v : now) checked.put(v.name, v);
                checkedKey = nowKey;
            }
            save(file, after, now);
            return new Result(changed, after.size(), SystemClock.uptimeMillis() - t0, how.toString());
        }
    }

    /** The rows of volume {@code v} now ({@code old}: as it was at the last check, null: never checked). */
    private static List<Row> volume(Source source, Volume v, @Nullable Volume old, List<Row> before, StringBuilder how) {
        final String sel = selection();
        final String[] args = patterns();
        if (old == null || !old.version.equals(v.version) || v.generation < old.generation) {
            how.append(v.name).append(": all read (").append(old == null ? "no cache" : "a new MediaStore version").append(')');
            return source.query(v.name, sel, args);
        }
        try {
            // the known rows by id (a delete, a trash, a rename drops them), then the rows changed since the last check
            Map<Long, Row> merged = new LinkedHashMap<>();
            List<Long> ids = new ArrayList<>();
            for (Row r : before) if (v.name.equals(r.volume)) ids.add(r.id);
            int known = 0, fresh = 0;
            for (int i = 0; i < ids.size(); i += IDS_PER_QUERY) {
                StringBuilder in = new StringBuilder(MediaStore.MediaColumns._ID + " IN (");
                for (int j = i; j < Math.min(ids.size(), i + IDS_PER_QUERY); j++) in.append(j > i ? "," : "").append(ids.get(j));
                in.append(") AND ").append(sel);
                for (Row r : source.query(v.name, in.toString(), args)) {
                    merged.put(r.id, r);
                    known++;
                }
            }
            if (v.generation > old.generation) {
                for (Row r : source.query(v.name, GENERATION_MODIFIED + " > ? AND " + sel, withFirst(Long.toString(old.generation), args))) {
                    merged.put(r.id, r);
                    fresh++;
                }
            }
            how.append(v.name).append(": ").append(known).append(" of ").append(ids.size()).append(" known rows by id, ")
                    .append(fresh).append(" changed since generation ").append(old.generation);
            return new ArrayList<>(merged.values());
        } catch (RuntimeException e) {
            // a provider that refuses the generation column: the whole query, as before P645
            how.append(v.name).append(": all read (the delta query failed: ").append(e).append(')');
            return source.query(v.name, sel, args);
        }
    }

    /** MediaStore.MediaColumns.GENERATION_MODIFIED (Android 11+). */
    static final String GENERATION_MODIFIED = "generation_modified";

    // ---------------------------------------------------------------- the file

    /** Reads the cache file into the state (the lock held); false without a usable one. */
    private static boolean load(@Nullable File file) {
        if (file == null || !file.isFile()) return false;
        List<Row> read = new ArrayList<>();
        Map<String, Volume> volumes = new LinkedHashMap<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            if (!FORMAT.equals(in.readLine())) return false;
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("V\t")) {
                    String[] p = line.split("\t", -1);
                    if (p.length == 4) volumes.put(p[1], new Volume(p[1], p[2], Long.parseLong(p[3])));
                } else if (line.startsWith("R\t")) {
                    Row r = Row.parse(line.substring(2));
                    if (r != null) read.add(r);
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "other RAW cache unreadable: " + e);
            return false;
        }
        rows = read;
        checked = volumes;
        checkedKey = volumes.isEmpty() ? null : key(new ArrayList<>(volumes.values()));
        return true;
    }

    private static void save(@Nullable File file, List<Row> list, @Nullable List<Volume> volumes) {
        if (file == null) return;
        File tmp = new File(file.getPath() + ".tmp");
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            out.write(FORMAT);
            out.write('\n');
            if (volumes != null)
                for (Volume v : volumes) out.write("V\t" + v.name.replace('\t', ' ') + '\t' + v.version.replace('\t', ' ') + '\t' + v.generation + '\n');
            for (Row r : list) out.write("R\t" + r.line() + '\n');
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "other RAW cache not written: " + e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(file)) Log.w(TAG, "other RAW cache not replaced");
    }

    /** The cache file of the app ({@code no_backup}: a cache of MediaStore, not the user's data). */
    @Nullable
    public static File fileOf(@Nullable Context c) {
        if (c == null) return null;
        try {
            return new File(c.getNoBackupFilesDir(), FILE_NAME);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Tests: a new process (nothing in memory; the file stays). */
    static synchronized void forget() {
        rows = null;
        checked = new LinkedHashMap<>();
        checkedKey = null;
        origin = "none";
    }

    // ---------------------------------------------------------------- MediaStore

    /** The app's source: MediaStore's Files collection through {@code resolver}. */
    public static Source mediaStore(@NonNull Context context, @NonNull ContentResolver resolver) {
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        return new Source() {
            @Nullable
            @Override
            public List<Volume> volumes() {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
                try {
                    List<Volume> out = new ArrayList<>();
                    for (String v : new TreeSet<>(MediaStore.getExternalVolumeNames(app)))
                        out.add(new Volume(v, MediaStore.getVersion(app, v), MediaStore.getGeneration(app, v)));
                    return out.isEmpty() ? null : out;
                } catch (RuntimeException e) {
                    Log.w(TAG, "MediaStore generation: " + e);
                    return null;
                }
            }

            @Override
            public List<Row> query(String volume, String selection, @Nullable String[] args) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Collections.emptyList();
                String[] projection = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED,
                        MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.BUCKET_ID,
                        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME};
                Uri files = MediaStore.Files.getContentUri(volume);
                List<Row> out = new ArrayList<>();
                // a failure goes to check() (the cache stays): not caught here
                try (Cursor c = resolver.query(files, projection, selection, args, null)) {
                    if (c == null) return out;
                    while (c.moveToNext())
                        out.add(new Row(volume, c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), c.getString(4), c.getLong(5), c.getString(6)));
                }
                return out;
            }
        };
    }
}
