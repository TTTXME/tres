package com.scamera.app.gallery.files;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.WorkerThread;

import com.scamera.app.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P697 (owner, 2026-11-14: «добавь в галерее возможность перемещать и копировать фото по папкам»): the gallery's «Переместить в
 * папку» / «Копировать в папку» through MediaStore. A folder is a {@link Target} (its storage root and MediaStore relative path);
 * a new folder goes under DCIM/ (MediaStore takes photos and videos there). Android 10+: a move changes the row's RELATIVE_PATH
 * (another app's rows after the system's write request, see {@link #foreignUris}); a copy inserts a new row in the target folder
 * and streams the bytes, keeping the MIME type and DATE_TAKEN. Before Android 10 the file itself is renamed / copied and its
 * row updated / inserted. A name taken in the target gets « (1)», « (2)»… (one suffix for a RAW and its photo, so they stay a
 * pair). A RAW and the photo of the same name in the same folder («RAW + JPEG» in the gallery) move and copy together
 * ({@link #groups}). Each file is done on its own: one failure does not stop the others ({@link Result}).
 */
public final class GalleryMoveCopy {
    private GalleryMoveCopy() {}

    private static final String TAG = "GalleryMoveCopy";
    /** The request code of the system's write request (another app's files to move). */
    public static final int REQUEST_PERM_WRITE = 1012;
    /** Where a new folder goes: MediaStore takes photos and videos under DCIM/. */
    public static final String NEW_FOLDER_PARENT = "DCIM/";
    /** The primary storage's root, as the paths of MediaStore's DATA column show it. */
    static final String PRIMARY_ROOT = "/storage/emulated/0/";

    public enum Mode { MOVE, COPY }

    /** A folder files can go to: the album's name, its storage root ("/storage/emulated/0/") and relative path ("DCIM/Camera/"). */
    public static final class Target {
        public final String name, root, relativePath;

        public Target(String name, String root, String relativePath) {
            this.name = name;
            this.root = root;
            this.relativePath = relativePath;
        }

        /** The folder as a path ("/storage/emulated/0/DCIM/Camera/"). */
        public String absoluteDir() {
            return root + relativePath;
        }

        /** The MediaStore volume of the folder ("external_primary", or an SD card's "1234-abcd"). */
        public String volume() {
            return volumeName(root);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Target)) return false;
            Target t = (Target) o;
            return root.equalsIgnoreCase(t.root) && relativePath.equalsIgnoreCase(t.relativePath);
        }

        @Override
        public int hashCode() {
            return (root + relativePath).toLowerCase(Locale.ROOT).hashCode();
        }

        @NonNull
        @Override
        public String toString() {
            return name + " (" + absoluteDir() + ")";
        }
    }

    /** What an operation did: files done, already in the folder (a move), failed; the first error. */
    public static final class Result {
        public int done, skipped, failed;
        @Nullable public String firstError;

        void fail(MediaFile f, Throwable e) {
            failed++;
            if (firstError == null) firstError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            Log.w(TAG, "failed " + f.displayName() + ": " + e);
        }

        @NonNull
        @Override
        public String toString() {
            return "done " + done + ", skipped " + skipped + ", failed " + failed + (firstError != null ? " (" + firstError + ")" : "");
        }
    }

    /** Progress of a running operation (any thread). */
    public interface Progress {
        void at(int finished, int total);
    }

    // ---------------------------------------------------------------- paths and names (pure)

    private static final Pattern EMULATED = Pattern.compile("^(/storage/emulated/\\d+/)(.*)$");
    private static final Pattern VOLUME = Pattern.compile("^(/storage/[^/]+/)(.*)$");
    private static final Pattern SDCARD = Pattern.compile("^(/sdcard/)(.*)$");

    /**
     * A file's path as {root, relative folder, name}: "/storage/emulated/0/DCIM/Camera/a.jpg" is {"/storage/emulated/0/",
     * "DCIM/Camera/", "a.jpg"}, an SD card's "/storage/1234-ABCD/DCIM/a.jpg" {"/storage/1234-ABCD/", "DCIM/", "a.jpg"}; null for a
     * path outside the shared storage.
     */
    @Nullable
    public static String[] splitPath(@Nullable String path) {
        if (path == null) return null;
        Matcher m = EMULATED.matcher(path);
        if (!m.matches()) {
            m = SDCARD.matcher(path);
            if (m.matches()) {
                String rest = m.group(2);
                return split(PRIMARY_ROOT, rest);
            }
            m = VOLUME.matcher(path);
            if (!m.matches() || m.group(1).equals("/storage/emulated/") || m.group(1).equals("/storage/self/")) return null;
        }
        return split(m.group(1), m.group(2));
    }

    private static String[] split(String root, String rest) {
        int slash = rest.lastIndexOf('/');
        return new String[]{root, slash < 0 ? "" : rest.substring(0, slash + 1), slash < 0 ? rest : rest.substring(slash + 1)};
    }

    /** The folder of a file, or null for one outside the shared storage, at its root, or in Android/ (apps' private data). */
    @Nullable
    public static Target targetOf(@Nullable String bucketName, @Nullable String filePath) {
        String[] p = splitPath(filePath);
        if (p == null || p[1].isEmpty() || p[1].toLowerCase(Locale.ROOT).startsWith("android/")) return null;
        String name = bucketName;
        if (name == null || name.isEmpty()) {
            String dir = p[1].substring(0, p[1].length() - 1);
            name = dir.substring(dir.lastIndexOf('/') + 1);
        }
        return new Target(name, p[0], p[1]);
    }

    /**
     * The folders of the gallery's albums: one target per folder on disk (an album merges the folders of one name), each once,
     * by name then path. {@code albums}: the album's name and its files' paths.
     */
    public static List<Target> targets(Map<String, ? extends Collection<String>> albums) {
        LinkedHashMap<Target, Target> out = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>(); // a folder's thousands of files: its path parsed once
        for (Map.Entry<String, ? extends Collection<String>> a : albums.entrySet())
            for (String path : a.getValue()) {
                String dir = dirOf(path);
                if (dir == null || !seen.add(a.getKey() + '\u0000' + dir)) continue;
                Target t = targetOf(a.getKey(), path);
                if (t != null && !out.containsKey(t)) out.put(t, t);
            }
        List<Target> list = new ArrayList<>(out.values());
        list.sort((x, y) -> {
            int c = x.name.compareToIgnoreCase(y.name);
            return c != 0 ? c : (x.root + x.relativePath).compareToIgnoreCase(y.root + y.relativePath);
        });
        return list;
    }

    /** The MediaStore volume of a storage root: the primary storage's "external_primary", an SD card's id in lower case. */
    public static String volumeName(String root) {
        if (root.startsWith("/storage/emulated/") || root.equals("/sdcard/")) return "external_primary";
        String v = root.substring("/storage/".length());
        if (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v.toLowerCase(Locale.ROOT);
    }

    /**
     * A folder name typed by the user, made safe: trimmed, the characters a path or the storage refuses ({@code / \ : * ? " < > |}
     * and control characters) as «_», no leading or trailing dots (a leading dot hides the folder), at most 80 characters; null
     * when nothing is left.
     */
    @Nullable
    public static String cleanFolderName(@Nullable String raw) {
        if (raw == null) return null;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x20 || cp == 0x7F || "/\\:*?\"<>|".indexOf(cp) >= 0) b.append('_');
            else b.appendCodePoint(cp);
        }
        String s = b.toString().trim();
        while (s.startsWith(".")) s = s.substring(1).trim();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1).trim();
        if (s.codePointCount(0, s.length()) > 80) s = s.substring(0, s.offsetByCodePoints(0, 80)).trim();
        return s.isEmpty() ? null : s;
    }

    /** The new folder {@code cleanName} ({@link #cleanFolderName}) on the primary storage, under DCIM/. */
    public static Target newFolder(String cleanName, String primaryRoot) {
        return new Target(cleanName, primaryRoot.endsWith("/") ? primaryRoot : primaryRoot + "/", NEW_FOLDER_PARENT + cleanName + "/");
    }

    private static final Pattern NUMBERED = Pattern.compile("^(.*) \\((\\d+)\\)$");

    /** {@code name} with the suffix « (n)» before its extension, in place of a suffix it has ("a (1).jpg", 2: "a (2).jpg"). */
    static String numbered(String name, int n) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        Matcher m = NUMBERED.matcher(base);
        if (m.matches() && !m.group(1).isEmpty()) base = m.group(1);
        return base + " (" + n + ")" + ext;
    }

    /** {@code name}, or its first free numbered form, against {@code taken} (lower-case names). */
    public static String uniqueName(String name, Set<String> taken) {
        return uniqueNames(Collections.singletonList(name), taken).get(0);
    }

    /**
     * The names of a group in the target: as they are when none is taken (lower-case {@code taken}, the storage ignores case),
     * else all with the same first free suffix (« (1)», « (2)»…), so a RAW and its photo keep one base name.
     */
    public static List<String> uniqueNames(List<String> names, Set<String> taken) {
        for (int n = 0; n < 10_000; ++n) {
            List<String> out = new ArrayList<>(names.size());
            Set<String> mine = new HashSet<>();
            boolean free = true;
            for (String name : names) {
                String candidate = n == 0 ? name : numbered(name, n);
                String key = candidate.toLowerCase(Locale.ROOT);
                if (taken.contains(key) || !mine.add(key)) {
                    free = false;
                    break;
                }
                out.add(candidate);
            }
            if (free) return out;
        }
        // 10 000 copies of one name: the time makes it unique
        List<String> out = new ArrayList<>(names.size());
        long now = System.currentTimeMillis();
        for (String name : names) out.add(numbered(name, (int) (now % 1_000_000_000L)));
        return out;
    }

    /** The base name ("IMG_1" of "IMG_1.dng"), lower case. */
    static String baseName(@Nullable String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)).toLowerCase(Locale.ROOT);
    }

    /** The folder of a path ("/storage/emulated/0/DCIM/Camera/"), lower case, or null. */
    @Nullable
    static String dirOf(@Nullable String path) {
        if (path == null) return null;
        int slash = path.lastIndexOf('/');
        return slash < 0 ? null : path.substring(0, slash + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean raw(MediaFile f) {
        return com.scamera.app.gallery.raw.RawFormats.isRawName(f.displayName());
    }

    /** A photo a RAW can sit beside (not a RAW, not a video). */
    private static boolean photo(MediaFile f) {
        return !raw(f) && !GalleryVideo.isVideoName(f.displayName()) && f.displayName() != null && f.displayName().lastIndexOf('.') > 0;
    }

    private static boolean same(MediaFile a, MediaFile b) {
        if (a == b) return true;
        if (a.rowId() >= 0 && a.rowId() == b.rowId()) return true;
        return a.contentUri() != null && a.contentUri().equals(b.contentUri());
    }

    /**
     * The selected files in groups that go together: a RAW and the photo of the same base name in the same folder (the gallery's
     * «RAW + JPEG») are one group, the one not selected taken from {@code all}; every other file is a group of its own. Selection
     * order; a file once.
     */
    public static List<List<MediaFile>> groups(List<? extends MediaFile> selected, Collection<? extends MediaFile> all) {
        LinkedHashMap<String, List<MediaFile>> byKey = new LinkedHashMap<>();
        List<MediaFile> placed = new ArrayList<>();
        int loose = 0;
        for (MediaFile f : selected) {
            if (f == null || contains(placed, f)) continue;
            String dir = dirOf(f.filePath());
            boolean pairs = dir != null && (raw(f) || photo(f));
            String key = pairs ? dir + '\u0000' + baseName(f.displayName()) : "\u0001" + (loose++);
            List<MediaFile> g = byKey.get(key);
            if (g == null) byKey.put(key, g = new ArrayList<>());
            g.add(f);
            placed.add(f);
            if (!pairs) continue;
            for (MediaFile o : all) {
                if (o == null || contains(placed, o) || !(raw(f) ? photo(o) : raw(o))) continue;
                if (dir.equals(dirOf(o.filePath())) && baseName(f.displayName()).equals(baseName(o.displayName()))) {
                    g.add(o);
                    placed.add(o);
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private static boolean contains(List<MediaFile> list, MediaFile f) {
        for (MediaFile o : list) if (same(o, f)) return true;
        return false;
    }

    /** True when {@code f} is in the folder {@code t} already. */
    public static boolean inFolder(MediaFile f, Target t) {
        String dir = dirOf(f.filePath());
        return dir != null && dir.equalsIgnoreCase(t.absoluteDir());
    }

    /** The number of files of the groups. */
    public static int count(List<List<MediaFile>> groups) {
        int n = 0;
        for (List<MediaFile> g : groups) n += g.size();
        return n;
    }

    // ---------------------------------------------------------------- MediaStore

    /**
     * Android 11+: the files of {@code files} this app may not change without the system's write request (another app's, or of
     * an unknown owner); empty with «Доступ ко всем файлам» or before Android 11 (Android 10's legacy storage writes them).
     */
    public static List<Uri> foreignUris(Context c, List<? extends MediaFile> files) {
        List<Uri> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) return out;
        String own = c.getPackageName();
        ContentResolver r = c.getContentResolver();
        for (MediaFile f : files) {
            if (f.contentUri() == null) continue;
            String owner = null;
            try (Cursor cur = r.query(f.contentUri(), new String[]{MediaStore.MediaColumns.OWNER_PACKAGE_NAME}, null, null, null)) {
                if (cur != null && cur.moveToFirst()) owner = cur.getString(0);
            } catch (RuntimeException e) {
                Log.w(TAG, "owner of " + f.contentUri() + ": " + e);
            }
            if (!own.equals(owner)) out.add(f.contentUri());
        }
        return out;
    }

    /** The system's write request for {@code uris} (Android 11+), or null (none needed, a row gone, older Android). */
    @Nullable
    public static android.app.PendingIntent writeRequest(Context c, List<Uri> uris) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || uris.isEmpty()) return null;
        return GalleryTrash.safe(() -> MediaStore.createWriteRequest(c.getContentResolver(), uris));
    }

    /** Moves or copies the groups into {@code target}, each file on its own; on a worker thread. */
    @WorkerThread
    public static Result run(Context c, Mode mode, List<List<MediaFile>> groups, Target target, @Nullable Progress progress) {
        Result r = new Result();
        ContentResolver resolver = c.getContentResolver();
        Set<String> taken = namesIn(resolver, target);
        int total = count(groups), finished = 0;
        for (List<MediaFile> g : groups) {
            List<MediaFile> todo = new ArrayList<>();
            for (MediaFile f : g) {
                if (mode == Mode.MOVE && inFolder(f, target)) r.skipped++;
                else todo.add(f);
            }
            if (!todo.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (MediaFile f : todo) names.add(f.displayName() != null ? f.displayName() : "file");
                List<String> fresh = uniqueNames(names, taken);
                for (int i = 0; i < todo.size(); ++i) {
                    MediaFile f = todo.get(i);
                    String name = fresh.get(i);
                    try {
                        if (mode == Mode.MOVE) move(c, resolver, f, target, name);
                        else copy(resolver, f, target, name);
                        taken.add(name.toLowerCase(Locale.ROOT));
                        r.done++;
                    } catch (IOException | RuntimeException e) {
                        r.fail(f, e);
                    }
                }
            }
            finished += g.size();
            if (progress != null) progress.at(finished, total);
        }
        Log.i(TAG, mode + " " + total + " files to " + target + ": " + r);
        return r;
    }

    /** The names in the target folder, lower case (MediaStore's rows, trashed and pending too, and what the folder lists). */
    static Set<String> namesIn(ContentResolver resolver, Target t) {
        Set<String> out = new HashSet<>();
        try {
            String[] list = new File(t.absoluteDir()).list();
            if (list != null) for (String n : list) out.add(n.toLowerCase(Locale.ROOT));
        } catch (SecurityException ignored) { /* scoped storage: MediaStore's rows below */ }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return out;
        try {
            namesInStore(resolver, t, out);
        } catch (RuntimeException e) {
            Log.w(TAG, "names in " + t + ": " + e);
        }
        return out;
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static void namesInStore(ContentResolver resolver, Target t, Set<String> out) {
        Uri files = MediaStore.Files.getContentUri(t.volume());
        String[] cols = {MediaStore.MediaColumns.DISPLAY_NAME};
        String where = MediaStore.MediaColumns.RELATIVE_PATH + " = ?";
        String[] args = {t.relativePath};
        Cursor cur;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bundle q = new Bundle();
            q.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where);
            q.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args);
            q.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE);
            q.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
            cur = resolver.query(files, cols, q, null);
        } else cur = resolver.query(files, cols, where, args, null);
        if (cur == null) return;
        try {
            while (cur.moveToNext()) {
                String n = cur.getString(0);
                if (n != null) out.add(n.toLowerCase(Locale.ROOT));
            }
        } finally {
            cur.close();
        }
    }

    private static void move(Context c, ContentResolver resolver, MediaFile f, Target t, String name) throws IOException {
        Uri uri = f.contentUri();
        if (uri == null) throw new IOException("no MediaStore row");
        String[] from = splitPath(f.filePath());
        if (from != null && !from[0].equalsIgnoreCase(t.root))
            throw new IOException("another storage: " + from[0] + " -> " + t.root); // a row cannot change its volume
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, t.relativePath);
            if (!name.equals(f.displayName())) v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            if (resolver.update(uri, v, null, null) <= 0) throw new IOException("the row was not changed");
            return;
        }
        // before Android 10: the file renamed on disk, its row pointed at the new path
        if (f.filePath() == null) throw new IOException("no path");
        File src = new File(f.filePath()), dst = new File(t.absoluteDir(), name);
        File dir = dst.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        if (!src.renameTo(dst)) throw new IOException("cannot rename " + src + " to " + dst);
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DATA, dst.getAbsolutePath());
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        try {
            if (resolver.update(uri, v, null, null) <= 0) throw new IOException("the row was not changed");
        } catch (IOException | RuntimeException e) {
            if (!dst.renameTo(src)) Log.w(TAG, "could not put back " + dst);
            throw e;
        }
        android.media.MediaScannerConnection.scanFile(c, new String[]{dst.getAbsolutePath()}, null, null);
    }

    /** The source's MIME type and DATE_TAKEN (ms, 0 unknown). */
    private static Object[] facts(ContentResolver resolver, MediaFile f) {
        String mime = null;
        long taken = 0;
        try (Cursor cur = resolver.query(f.contentUri(), new String[]{MediaStore.MediaColumns.MIME_TYPE, MediaStore.Images.ImageColumns.DATE_TAKEN},
                null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                mime = cur.getString(0);
                taken = cur.isNull(1) ? 0 : cur.getLong(1);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "facts of " + f.contentUri() + ": " + e);
        }
        if (mime == null || mime.isEmpty()) mime = GalleryVideo.isVideo(f) ? GalleryVideo.mime(f.displayName())
                : com.scamera.app.gallery.editor.ExternalEdit.mediaTypeOf(f.displayName());
        return new Object[]{mime, taken};
    }

    /** The collection a copy goes into: videos, images, or the files (other RAWs MediaStore does not file as images). */
    static Uri collection(String mime, String volume, boolean legacy) {
        String v = legacy ? "external" : volume;
        if (mime.startsWith("video/")) return MediaStore.Video.Media.getContentUri(v);
        if (mime.startsWith("image/")) return MediaStore.Images.Media.getContentUri(v);
        return MediaStore.Files.getContentUri(v);
    }

    private static void copy(ContentResolver resolver, MediaFile f, Target t, String name) throws IOException {
        if (f.contentUri() == null) throw new IOException("no MediaStore row");
        Object[] facts = facts(resolver, f);
        String mime = (String) facts[0];
        long taken = (Long) facts[1];
        boolean modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        if (taken > 0) v.put(MediaStore.Images.ImageColumns.DATE_TAKEN, taken);
        File dst = null;
        if (modern) {
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, t.relativePath);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        } else {
            // before Android 10 the row names the file: it is written there
            dst = new File(t.absoluteDir(), name);
            File dir = dst.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            v.put(MediaStore.MediaColumns.DATA, dst.getAbsolutePath());
        }
        Uri out = resolver.insert(collection(mime, t.volume(), !modern), v);
        if (out == null) throw new IOException("MediaStore refused the new row");
        boolean ok = false;
        try {
            try (InputStream in = resolver.openInputStream(f.contentUri());
                 OutputStream os = modern ? resolver.openOutputStream(out, "w") : new FileOutputStream(dst)) {
                if (in == null || os == null) throw new IOException("cannot open the file");
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) os.write(buf, 0, n);
            }
            if (modern) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(out, done, null, null);
            }
            ok = true;
        } finally {
            if (!ok) {
                try {
                    resolver.delete(out, null, null);
                } catch (RuntimeException e) {
                    Log.w(TAG, "could not remove the half copy " + out + ": " + e);
                }
                if (dst != null && dst.exists() && !dst.delete()) Log.w(TAG, "could not remove " + dst);
            }
        }
    }
}
