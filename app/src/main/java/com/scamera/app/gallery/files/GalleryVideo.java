package com.scamera.app.gallery.files;

import android.net.Uri;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P498 (owner, 2026-11-04: «Наша галерея ещё не умеет в видео, добавь поддержку воспроизведения и обнаружения»): the gallery's
 * videos - found in MediaStore's video collection (GalleryFileOperations.appendVideos, into the folders of their bucket), told apart
 * from photos by their URI or extension, their length kept from the query for the grid's badge, their MIME type for share / open.
 */
public final class GalleryVideo {
    private GalleryVideo() {}

    private static final String[] EXTENSIONS = {"mp4", "m4v", "3gp", "3g2", "mkv", "webm", "mov", "ts"};
    /** Length in ms by MediaStore row id (from the video query). */
    private static final Map<Long, Long> DURATION = new ConcurrentHashMap<>();

    public static boolean isVideoName(@Nullable String name) {
        if (name == null) return false;
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (String e : EXTENSIONS) if (e.equals(ext)) return true;
        return false;
    }

    public static boolean isVideo(@Nullable MediaFile f) {
        if (f == null) return false;
        Uri u = f.getFileUri();
        if (u != null && u.getPath() != null && u.getPath().contains("/video/")) return true;
        return isVideoName(f.getDisplayName());
    }

    static void remember(long id, long durationMs) {
        if (durationMs > 0) DURATION.put(id, durationMs);
    }

    /** The video's length in ms, 0 when unknown. */
    public static long durationMs(@Nullable MediaFile f) {
        if (f == null) return 0;
        Long d = DURATION.get(f.getId());
        return d != null ? d : 0;
    }

    /** «0:42», «12:05», «1:02:07». */
    public static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = s / 60 % 60, sec = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    /** The MIME type of a video file name ("video/*" when unknown). */
    public static String mime(@Nullable String name) {
        if (name == null) return "video/*";
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp4") || n.endsWith(".m4v")) return "video/mp4";
        if (n.endsWith(".3gp")) return "video/3gpp";
        if (n.endsWith(".3g2")) return "video/3gpp2";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".ts")) return "video/mp2t";
        return "video/*";
    }
}
