package com.particlesdevs.photoncamera.gallery.ui;

import android.os.SystemClock;

import com.particlesdevs.photoncamera.util.Log;

/**
 * P617 (owner, 2026-11-11: «у меня теперь иногда очень долго открывается галерея»): the gallery's open in the log, each step once
 * per open, in ms after the gallery activity's start: the media listed (and how long the read took on the loader thread), the
 * grid's first frame with photos, the first thumbnails ready (and where they came from: memory, the disk cache or a decode), the
 * viewer's first photo ready. Tag «GalleryOpen». Main thread only, except {@link #thumbReady} (Glide's callbacks are on it too).
 */
public final class GalleryOpenTrace {
    private GalleryOpenTrace() {}

    private static final String TAG = "GalleryOpen";
    /** The thumbnails counted for «first screen ready»: about a screen of the 4-column grid. */
    static final int FIRST_THUMBS = 12;

    private static long openedAt;
    private static boolean listed, grid, viewer, thumbsDone;
    private static int thumbs, fromMemory, fromDisk;
    private static String route = "";

    /** The gallery activity starts ({@code route}: what it opens on). */
    public static void opened(String route) {
        openedAt = SystemClock.uptimeMillis();
        GalleryOpenTrace.route = route;
        listed = grid = viewer = thumbsDone = false;
        thumbs = fromMemory = fromDisk = 0;
        com.particlesdevs.photoncamera.gallery.glide.ThumbTrace.reset(); // P620: the decodes' formats and sources from this open
        Log.i(TAG, "open: " + route);
    }

    static long since() {
        return openedAt > 0 ? SystemClock.uptimeMillis() - openedAt : -1;
    }

    /** The media lists are set ({@code items}: photos in «ALL», -1 on a failed read; {@code readMs}: the read on the loader thread). */
    public static void listed(int items, long readMs) {
        if (listed || openedAt == 0) return;
        listed = true;
        Log.i(TAG, "media listed +" + since() + " ms: " + items + " items, read " + readMs + " ms off the main thread");
    }

    /** The grid drew its first frame with {@code items} photos. */
    public static void gridFrame(int items) {
        if (grid || openedAt == 0 || items <= 0) return;
        grid = true;
        Log.i(TAG, "grid first frame +" + since() + " ms: " + items + " items (" + route + ")");
    }

    /** Whether the grid still counts its thumbnails for this open (the thumbnail request listens only then). */
    public static boolean countingThumbs() {
        return openedAt != 0 && !thumbsDone;
    }

    /** A thumbnail is ready ({@code memory}: from the memory cache, {@code disk}: from the disk cache, else decoded). */
    public static void thumbReady(boolean memory, boolean disk) {
        if (!countingThumbs()) return;
        thumbs++;
        if (memory) fromMemory++;
        else if (disk) fromDisk++;
        // P620: and what the decoded ones were decoded from («jpeg/decoded 8, dng/dng-raw 2»)
        if (thumbs == 1 || thumbs == FIRST_THUMBS)
            Log.i(TAG, summary(thumbs, fromMemory, fromDisk, since()) + com.particlesdevs.photoncamera.gallery.glide.ThumbTrace.sources());
        if (thumbs >= FIRST_THUMBS) thumbsDone = true;
    }

    static String summary(int n, int memory, int disk, long ms) {
        return (n == 1 ? "first thumbnail" : "first " + n + " thumbnails") + " ready +" + ms + " ms (memory " + memory + ", disk cache "
                + disk + ", decoded " + Math.max(0, n - memory - disk) + ")";
    }

    /** The viewer's first photo is ready on screen. */
    public static void viewerReady() {
        if (viewer || openedAt == 0) return;
        viewer = true;
        Log.i(TAG, "viewer first photo ready +" + since() + " ms (" + route + ")");
    }
}
