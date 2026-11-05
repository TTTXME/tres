package com.particlesdevs.photoncamera.gallery.files;

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
import android.os.Bundle;
import android.provider.MediaStore;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import com.particlesdevs.photoncamera.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * P502 (owner, 2026-11-05: the gallery «аналогичной тому что на этом видео» - its «Недавно удаленные» album): Android 11's MediaStore
 * trash. A delete in the gallery moves the files there (this app's own rows directly, another app's through the system's trash
 * request, its dialog the confirmation); the system keeps them 30 days (DATE_EXPIRES) and removes them after. The «Недавно
 * удаленные» page lists them with the days left, puts them back or deletes them for good. Before Android 11 there is no trash: the
 * gallery deletes as before.
 */
public final class GalleryTrash {
    private GalleryTrash() {}

    /** The request code of a trash page action (restore / delete for good) that went through a system dialog. */
    public static final int REQUEST_TRASH_ACTION = 1011;

    public static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    /** One trashed photo or video. */
    public static final class Item {
        public final Uri uri;
        public final String name;
        public final boolean video, own;
        public final long expiresMs, size;

        Item(Uri uri, String name, boolean video, boolean own, long expiresMs, long size) {
            this.uri = uri;
            this.name = name;
            this.video = video;
            this.own = own;
            this.expiresMs = expiresMs;
            this.size = size;
        }

        /** Whole days left before the system removes it (at least 0). */
        public int daysLeft(long nowMs) {
            return GalleryTrash.daysLeft(expiresMs, nowMs);
        }
    }

    /** Days from {@code nowMs} to {@code expiresMs}, rounded up, at least 0 (0 = today). */
    public static int daysLeft(long expiresMs, long nowMs) {
        if (expiresMs <= nowMs) return 0;
        return (int) ((expiresMs - nowMs + 86_399_999L) / 86_400_000L);
    }

    /** The trashed photos and videos, the latest expiry (the newest trashed) first; empty before Android 11 or on an error. */
    public static List<Item> load(Context c) {
        List<Item> out = new ArrayList<>();
        if (!supported()) return out;
        try {
            loadInto(c, out);
        } catch (RuntimeException e) {
            Log.w("GalleryTrash", "load: " + e);
        }
        out.sort((a, b) -> Long.compare(b.expiresMs, a.expiresMs));
        return out;
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static void loadInto(Context c, List<Item> out) {
        final String own = c.getPackageName();
        Uri files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
        Bundle args = new Bundle();
        args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY);
        args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, MediaStore.Files.FileColumns.MEDIA_TYPE + " IN (?, ?)");
        args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[]{
                String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE), String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)});
        String[] cols = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.DATE_EXPIRES, MediaStore.MediaColumns.SIZE};
        try (Cursor cur = c.getContentResolver().query(files, cols, args, null)) {
            if (cur == null) return;
            while (cur.moveToNext()) {
                long id = cur.getLong(0);
                boolean video = cur.getInt(2) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
                Uri base = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                out.add(new Item(ContentUris.withAppendedId(base, id), cur.getString(1), video, own.equals(cur.getString(3)),
                        cur.getLong(4) * 1000L, cur.getLong(5)));
            }
        }
    }

    /**
     * This app's own rows into the trash ({@code trashed}) or back out of it, without a dialog; false on an error (then the caller
     * deletes or asks the system).
     */
    public static boolean setOwnTrashed(Context c, List<Uri> uris, boolean trashed) {
        if (!supported()) return false;
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.IS_TRASHED, trashed ? 1 : 0);
        boolean ok = true;
        for (Uri u : uris) {
            try {
                ok &= c.getContentResolver().update(u, v, null, null) > 0;
            } catch (RuntimeException e) {
                Log.w("GalleryTrash", "trash " + trashed + " " + u + ": " + e);
                ok = false;
            }
        }
        return ok;
    }

    /** The system's trash request (into, or out with {@code trashed} false) for files of other apps; null before Android 11. */
    @Nullable
    public static PendingIntent trashRequest(Context c, List<Uri> uris, boolean trashed) {
        if (!supported() || uris.isEmpty()) return null;
        return safe(() -> MediaStore.createTrashRequest(c.getContentResolver(), uris, trashed));
    }

    /**
     * P511 (Pixel 10 Pro XL, Android 17, 2026-11-04: the gallery crashed on «Удалить» - the system's request threw «Invalid Uri:
     * content://media/external/images/media/24486», a row no longer there): the request null (nothing asked) instead of a crash.
     */
    @Nullable
    static PendingIntent safe(java.util.function.Supplier<PendingIntent> request) {
        try {
            return request.get();
        } catch (IllegalArgumentException | SecurityException e) {
            Log.w("GalleryTrash", "system request: " + e);
            return null;
        }
    }

    /** Deletes for good: the app's own rows at once; another app's through the system's delete request. Returns whether it asked. */
    public static boolean deleteForGood(Activity a, List<Item> items) {
        List<Uri> foreign = new ArrayList<>();
        for (Item i : items) {
            if (i.own) {
                try {
                    a.getContentResolver().delete(i.uri, null, null);
                } catch (RuntimeException e) {
                    Log.w("GalleryTrash", "delete " + i.uri + ": " + e);
                    foreign.add(i.uri);
                }
            } else foreign.add(i.uri);
        }
        if (foreign.isEmpty() || !supported()) return false;
        return send(a, safe(() -> MediaStore.createDeleteRequest(a.getContentResolver(), foreign)));
    }

    /** Puts {@code items} back: the app's own at once; another app's through the system's request. Returns whether it asked. */
    public static boolean restore(Activity a, List<Item> items) {
        List<Uri> own = new ArrayList<>(), foreign = new ArrayList<>();
        for (Item i : items) (i.own ? own : foreign).add(i.uri);
        if (!own.isEmpty() && !setOwnTrashed(a, own, false)) foreign.addAll(own);
        if (foreign.isEmpty()) return false;
        return send(a, trashRequest(a, foreign, false));
    }

    static boolean send(Activity a, @Nullable PendingIntent pi) {
        if (pi == null) return false;
        try {
            ActivityCompat.startIntentSenderForResult(a, pi.getIntentSender(), REQUEST_TRASH_ACTION, null, 0, 0, 0, null);
            return true;
        } catch (IntentSender.SendIntentException e) {
            Log.w("GalleryTrash", "request: " + e);
            return false;
        }
    }
}
