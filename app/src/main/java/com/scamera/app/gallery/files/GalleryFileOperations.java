package com.scamera.app.gallery.files;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.IntentSender;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import com.scamera.app.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import com.scamera.app.gallery.interfaces.ImagesDeletedCallback;
import com.scamera.app.settings.PreferenceKeys;

import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The gallery's MediaStore work: the folders with photos, RAWs and videos ({@link #FindAllFoldersWithImages}), the folders
 * chosen in the gallery's settings, the newest camera photo, and deletion (into the system's trash where it has one).
 */
public class GalleryFileOperations {
    public static final int REQUEST_PERM_DELETE = 1010;
    private static final String TAG = "GalleryFileOperations";
    /** The camera folders the newest-photo query looks in (relative paths, LIKE patterns). */
    private static final String[] CAMERA_FOLDERS = {"%DCIM/SCAMERA/%", "%DCIM/Camera/%"};
    /** Shown when nothing is chosen in the gallery's settings. */
    private static final String[] DEFAULT_FOLDERS = {"Camera", "Raw"};
    private static final ArrayList<ImagesFolder> ALL_FOLDERS = new ArrayList<>();
    private static final ArrayList<ImagesFolder> SELECTED_FOLDERS = new ArrayList<>();

    /** The selected folders of the last scan, a copy (P617: the folder lists are filled on the gallery's loader thread). */
    public static synchronized ArrayList<ImagesFolder> getSelectedFolders() {
        return new ArrayList<>(SELECTED_FOLDERS);
    }

    /** P502: every folder with photos or videos found by the last scan (the «Альбомы» page), a copy. */
    public static synchronized ArrayList<ImagesFolder> getAllFolders() {
        return new ArrayList<>(ALL_FOLDERS);
    }

    /**
     * The scan ({@link #FindAllFoldersWithImages}) and, of its folders, those chosen in the gallery's settings (by name, or by
     * the id older versions stored), by name; a copy.
     */
    public static synchronized ArrayList<ImagesFolder> _fetchSelectedFolders(ContentResolver contentResolver) {
        FindAllFoldersWithImages(contentResolver);
        java.util.Set<String> chosen = new java.util.LinkedHashSet<>();
        try {
            chosen.addAll(PreferenceKeys.getStringSet(PreferenceKeys.Key.FOLDERS_LIST));
        } catch (RuntimeException e) {
            Log.d(TAG, "chosen folders unreadable: " + e);
        }
        if (chosen.isEmpty()) chosen.addAll(java.util.Arrays.asList(DEFAULT_FOLDERS));
        SELECTED_FOLDERS.clear();
        for (String wanted : chosen)
            for (ImagesFolder f : ALL_FOLDERS)
                if (f.folderName.equals(wanted) || String.valueOf(f.folderId).equals(wanted)) SELECTED_FOLDERS.add(f);
        SELECTED_FOLDERS.sort(Comparator.comparing(f -> f.folderName));
        return new ArrayList<>(SELECTED_FOLDERS);
    }

    /** Every file of the selected folders, newest first. */
    public static synchronized List<ImageFile> extractAllSelectedImages() {
        ArrayList<ImageFile> files = new ArrayList<>();
        for (ImagesFolder f : SELECTED_FOLDERS) files.addAll(f.files);
        files.sort(Comparator.comparingLong(f -> -f.getLastModified()));
        return files;
    }

    /** The newest photo of the camera folders (the camera screen's thumbnail), or null. */
    @Nullable
    public static ImageFile fetchLatestImage(ContentResolver resolver) {
        Uri table = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String folder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? MediaStore.Images.Media.RELATIVE_PATH : MediaStore.MediaColumns.DATA;
        String[] columns = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE, MediaStore.MediaColumns.DATA};
        String where = folder + " like ? OR " + folder + " like ? OR " + folder + " like ?";
        try (Cursor c = resolver.query(table, columns, where, CAMERA_FOLDERS, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (c == null || !c.moveToFirst()) return null;
            long id = c.getLong(0);
            return new ImageFile(id, ContentUris.withAppendedId(table, id), c.getString(1),
                    TimeUnit.SECONDS.toMillis(c.getLong(2)), c.getLong(3), c.getString(4));
        }
    }

    /**
     * P345 (owner, 2026-10-26, X200 Ultra screen recording: «Удалить снимок?» and then the system's «Разрешить приложению SCAMERA
     * удалить это фото?» for the same photo): true when deleting these files needs the system's MediaStore delete request, i.e. on
     * Android 11+ when one of them is not this app's own (another camera's photo, or ours from before a reinstall / the package
     * change of 2026-10-19). The system dialog is then the only confirmation; the app's own photos are deleted after the app's sheet
     * without it. An unknown owner counts as foreign.
     */
    public static boolean needsSystemDeleteRequest(Context context, List<ImageFile> files) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        String own = context.getPackageName();
        ContentResolver resolver = context.getContentResolver();
        for (ImageFile f : files) {
            String owner = null;
            try (Cursor c = resolver.query(f.getFileUri(), new String[]{MediaStore.MediaColumns.OWNER_PACKAGE_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) owner = c.getString(0);
            } catch (RuntimeException e) {
                Log.w("GalleryFileOperations", "owner of " + f.getFileUri() + ": " + e);
            }
            if (!own.equals(owner)) return true;
        }
        return false;
    }

    /**
     * Deletes the files; {@code done} learns whether they went. Android 11+: into the system's trash (P502: «Недавно удаленные»,
     * 30 days, the gallery's album puts them back) - through the system's dialog when one of them is not this app's own (P345),
     * at once otherwise. Before Android 11 they are deleted from MediaStore.
     */
    public static void deleteImageFiles(Activity activity, List<ImageFile> files, ImagesDeletedCallback done) {
        List<Uri> uris = new ArrayList<>(files.size());
        for (ImageFile f : files) uris.add(f.getFileUri());
        boolean modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
        if (modern && needsSystemDeleteRequest(activity, files)) {
            PendingIntent ask = GalleryTrash.trashRequest(activity, uris, true); // P511: null for a row already gone
            if (ask == null) {
                done.deleted(false);
                return;
            }
            try {
                ActivityCompat.startIntentSenderForResult(activity, ask.getIntentSender(), REQUEST_PERM_DELETE, null, 0, 0, 0, null);
            } catch (IntentSender.SendIntentException e) {
                Log.w(TAG, "system delete request: " + e);
                done.deleted(false);
            }
            return;
        }
        if (GalleryTrash.setOwnTrashed(activity, uris, true)) {
            done.deleted(true);
            return;
        }
        ContentResolver resolver = activity.getContentResolver();
        for (ImageFile f : files) {
            try {
                if (modern) resolver.delete(f.getFileUri(), null, null);
                else resolver.delete(f.getFileUri(), MediaStore.Images.Media._ID + "= ?", new String[]{String.valueOf(f.getId())});
            } catch (SecurityException e) {
                Log.w(TAG, "delete " + f.getFileUri() + ": " + e);
                done.deleted(false);
                return;
            }
        }
        done.deleted(true);
    }

    /** A folder of the scan: its name (MediaStore bucket), bucket id, files (newest first) and its newest file. */
    public static class ImagesFolder {
        private String folderName;
        private long folderId;
        private ArrayList<ImageFile> files;
        private ImageFile newest;

        public String getFolderName() { return folderName; }

        public long getFolderId() { return folderId; }

        public ArrayList<ImageFile> getAllImageFiles() { return files; }

        public ImageFile getTopImage() { return newest; }
    }

    /**
     * Every folder with photos, RAWs or videos (MediaStore), newest file first in each, folders by name.
     * <p>
     * P617 (owner, 2026-11-11: «иногда очень долго открывается галерея», ~9000 photos): the scan ran on the main thread at each
     * gallery start (three queries: the images, a 41-pattern LIKE over the whole Files table for other RAWs, the videos) and put
     * each row into its folder by a linear search of the folders. Now: one lock for the folder lists (the scan runs on the
     * gallery's loader thread, {@link com.scamera.app.gallery.viewmodel.GalleryViewModel#loadAsync}), a map by
     * folder name, and the last scan reused while MediaStore's content is unchanged ({@link #mediaKey}: each external volume's
     * version and generation, Android 11+). Each scan logs its time («GalleryMedia: scan ...»).
     */
    public static synchronized ArrayList<ImagesFolder> FindAllFoldersWithImages(@NonNull ContentResolver contentResolver) {
        final android.content.Context app = com.scamera.app.app.ScameraApp.getAppContext();
        final String key = mediaKey(app);
        if (reusable(lastScanKey, key, !ALL_FOLDERS.isEmpty())) {
            Log.i(SCAN_TAG, "scan: MediaStore unchanged since the last scan, " + ALL_FOLDERS.size() + " folders reused");
            return new ArrayList<>(ALL_FOLDERS);
        }
        lastScanKey = null;
        final long t0 = android.os.SystemClock.uptimeMillis();
        Folders folders = new Folders();
        int imageRows = 0;
        Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.MediaColumns.DATA, MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.SIZE};
        try (Cursor cursor = contentResolver.query(uri, projection, null, null, MediaStore.Images.Media.DATE_TAKEN + " DESC")) {
            if (cursor != null) {
                int columnData = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA);
                int columnBucketName = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
                int columnBucketId = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID);
                int columnId = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                int columnDate = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
                int columnName = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
                int columnSize = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
                while (cursor.moveToNext()) {
                    String bucketName = cursor.getString(columnBucketName);
                    if (bucketName == null) continue;
                    long id = cursor.getLong(columnId);
                    folders.add(bucketName, cursor.getLong(columnBucketId), new ImageFile(id, ContentUris.withAppendedId(uri, id),
                            cursor.getString(columnName), TimeUnit.SECONDS.toMillis(cursor.getLong(columnDate)), cursor.getLong(columnSize),
                            cursor.getString(columnData)));
                    imageRows++;
                }
            }
        }
        final long t1 = android.os.SystemClock.uptimeMillis();
        // P645: the other RAWs of the last check (memory or a small file), no query: the check runs after the list is out
        int rawRows = appendCachedRaw(OtherRawCache.rows(rawFile(app)), folders);
        final String rawOrigin = OtherRawCache.origin();
        final long t2 = android.os.SystemClock.uptimeMillis();
        int videoRows = appendVideos(contentResolver, folders);
        final long t3 = android.os.SystemClock.uptimeMillis();
        ALL_FOLDERS.clear();
        ALL_FOLDERS.addAll(folders.finish());
        lastScanKey = key;
        Log.i(SCAN_TAG, "scan: images " + imageRows + " rows " + (t1 - t0) + " ms, other RAW " + rawRows + " rows " + (t2 - t1)
                + " ms (from the " + rawOrigin + " cache, checked after the list), videos " + videoRows + " rows " + (t3 - t2) + " ms, "
                + ALL_FOLDERS.size() + " folders, total " + (android.os.SystemClock.uptimeMillis() - t0) + " ms on "
                + Thread.currentThread().getName() + (key == null ? " (no MediaStore generation: every open scans)" : ""));
        return new ArrayList<>(ALL_FOLDERS);
    }

    /** P645: the cached other RAWs into the folders (a row the images have already is not added again); the rows added. */
    static int appendCachedRaw(List<OtherRawCache.Row> rows, Folders folders) {
        int n = 0;
        for (OtherRawCache.Row r : rows) {
            if (folders.has(r.id) || r.bucket == null) continue;
            folders.add(r.bucket, r.bucketId, r.file());
            n++;
        }
        return n;
    }

    /**
     * P645: the other RAWs brought up to MediaStore now ({@link OtherRawCache#check}; any thread but the main one, outside the
     * folder lists' lock while it queries); a changed set makes the next read build the folders again. Logged.
     */
    public static OtherRawCache.Result checkOtherRaw(@NonNull Context context, @NonNull ContentResolver resolver) {
        return checkOtherRaw(OtherRawCache.mediaStore(context, resolver), rawFile(context));
    }

    /** Tests: the other-RAW cache's file instead of the app's. */
    @Nullable static java.io.File rawFileForTests;

    @Nullable
    static java.io.File rawFile(@Nullable Context c) {
        return rawFileForTests != null ? rawFileForTests : OtherRawCache.fileOf(c);
    }

    static OtherRawCache.Result checkOtherRaw(OtherRawCache.Source source, @Nullable java.io.File file) {
        OtherRawCache.Result r = OtherRawCache.check(source, file);
        if (r.changed) synchronized (GalleryFileOperations.class) {
            lastScanKey = null; // the folders are built again, with the new rows
        }
        Log.i(SCAN_TAG, "other RAW check: " + r + " on " + Thread.currentThread().getName());
        return r;
    }

    private static final String SCAN_TAG = "GalleryMedia";
    /** P617: {@link #mediaKey} at the last complete scan, null: none (or unknown). */
    @Nullable private static String lastScanKey;

    /** P617: the last scan stands when MediaStore's key is known and the same, and the scan found something. */
    static boolean reusable(@Nullable String last, @Nullable String now, boolean haveFolders) {
        return haveFolders && now != null && now.equals(last);
    }

    /**
     * P617: MediaStore's content now: each external volume's name, version and generation (any insert, update, trash or delete
     * raises the generation); null before Android 11 or when unknown.
     */
    @Nullable
    static String mediaKey(@Nullable android.content.Context c) {
        if (c == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        try {
            StringBuilder k = new StringBuilder();
            for (String v : new java.util.TreeSet<>(MediaStore.getExternalVolumeNames(c)))
                k.append(v).append(':').append(MediaStore.getVersion(c, v)).append(':').append(MediaStore.getGeneration(c, v)).append(';');
            return k.length() > 0 ? k.toString() : null;
        } catch (RuntimeException e) {
            Log.w(SCAN_TAG, "MediaStore generation: " + e);
            return null;
        }
    }

    /** P617: the scan's folders by name (a map, not a search of the list for every row). */
    static final class Folders {
        private final java.util.LinkedHashMap<String, ImagesFolder> byName = new java.util.LinkedHashMap<>();
        private final java.util.Set<Long> ids = new java.util.HashSet<>();

        void add(String bucket, long bucketId, ImageFile file) {
            ImagesFolder folder = byName.get(bucket);
            if (folder == null) {
                folder = new ImagesFolder();
                folder.folderName = bucket;
                folder.folderId = bucketId;
                folder.files = new ArrayList<>();
                byName.put(bucket, folder);
            }
            folder.files.add(file);
            ids.add(file.getId());
        }

        boolean has(long id) {
            return ids.contains(id);
        }

        /** The folders: each newest first with its top image, the folders by name. */
        ArrayList<ImagesFolder> finish() {
            ArrayList<ImagesFolder> out = new ArrayList<>(byName.values());
            for (ImagesFolder f : out) {
                f.files.sort(Comparator.comparingLong(file -> -file.getLastModified()));
                f.newest = f.files.get(0);
            }
            out.sort(Comparator.comparing(o -> o.folderName));
            return out;
        }
    }

    /**
     * P498 (owner, 2026-11-04: «Наша галерея ещё не умеет в видео, добавь поддержку воспроизведения и обнаружения»): the videos of
     * MediaStore's video collection, into the folders of their bucket (a camera's videos sit beside its photos), their length kept
     * (GalleryVideo). Without the video permission the query sees only this app's own videos, or none. Returns the rows added.
     */
    static int appendVideos(@NonNull ContentResolver resolver, Folders folders) {
        String[] projection = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.SIZE, MediaStore.MediaColumns.DATA, MediaStore.Video.Media.BUCKET_ID,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.DURATION};
        int n = 0;
        try (Cursor c = resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null, null)) {
            if (c == null) return 0;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String bucket = c.getString(6);
                if (bucket == null) continue;
                ImageFile file = new ImageFile(id, ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id), c.getString(1),
                        TimeUnit.SECONDS.toMillis(c.getLong(2)), c.getLong(3), c.getString(4));
                GalleryVideo.remember(id, c.getLong(7));
                folders.add(bucket, c.getLong(5), file);
                n++;
            }
        } catch (RuntimeException e) {
            Log.w("GalleryFileOperations", "video query: " + e);
        }
        return n;
    }

    // P352 (owner, 2026-10-26: «галерея должна работать с любыми Рав форматами»): camera RAWs MediaStore does not file as
    // images (CR3, 3FR, IIQ, MRW, X3F... as application/octet-stream or without a type) come from the Files collection by
    // extension (OtherRawCache: cached, checked after the first list) and join the folders in appendCachedRaw (P645).
}
