package com.particlesdevs.photoncamera.gallery.ui;

import android.os.SystemClock;

import com.particlesdevs.photoncamera.util.Log;

/**
 * P617 (owner, 2026-11-11: «у меня теперь иногда очень долго открывается галерея»): the gallery's open in the log, each step once
 * per open, in ms after the gallery activity's start: the media listed (and how long the read took on the loader thread), the
 * grid's first frame with photos, the first thumbnails ready (and where they came from: memory, the disk cache or a decode), the
 * viewer's first photo ready. Tag «GalleryOpen». Main thread only, except {@link #thumbReady} (Glide's callbacks are on it too).
 * <p>P637 (owner, 2026-11-12: the viewer black for ~6 s when opened right after a shot): the open counted from the tap on the
 * camera's thumbnail too («tap -> gallery»: the camera's pause), the placeholder («placeholder shown / on screen», «first
 * pixels»), the camera's last photo shown from its file before the lists, a shot saved while the viewer waits («shot saved»,
 * «final photo ready»), where the media read's time went (queued, read, posted late), and a watch of the main thread for the
 * open's first seconds («main thread busy ... ms» with its stack): a blocked main thread delays every one of these lines.
 */
public final class GalleryOpenTrace {
    private GalleryOpenTrace() {}

    private static final String TAG = "GalleryOpen";
    /** The thumbnails counted for «first screen ready»: about a screen of the 4-column grid. */
    static final int FIRST_THUMBS = 12;

    private static long openedAt;
    /** P637: the tap on the camera's thumbnail (uptime ms; 0: not opened by it). */
    private static long tapAt;
    private static boolean listed, grid, viewer, thumbsDone, placeholderDrawn, firstPixels, finalPhoto;
    /** P637: when the shot the viewer waited for was saved (uptime ms; 0: none). */
    private static long savedAt;
    private static int thumbs, fromMemory, fromDisk;
    private static String route = "";

    /** The gallery activity starts ({@code route}: what it opens on). */
    public static void opened(String route) {
        opened(route, 0);
    }

    /** P637: as above, {@code tapAt} the tap on the camera's thumbnail (uptime ms, 0: none): the camera's pause is in the log. */
    public static void opened(String route, long tapAt) {
        openedAt = SystemClock.uptimeMillis();
        GalleryOpenTrace.tapAt = tapAt > 0 && tapAt <= openedAt ? tapAt : 0;
        GalleryOpenTrace.route = route;
        listed = grid = viewer = thumbsDone = placeholderDrawn = firstPixels = finalPhoto = false;
        savedAt = 0;
        thumbs = fromMemory = fromDisk = 0;
        com.particlesdevs.photoncamera.gallery.glide.ThumbTrace.reset(); // P620: the decodes' formats and sources from this open
        Log.i(TAG, "open: " + route + (GalleryOpenTrace.tapAt > 0 ? " (tap -> gallery " + (openedAt - GalleryOpenTrace.tapAt) + " ms)" : ""));
        MainWatch.start(openedAt);
    }

    /** P637: «+T ms» after the gallery's start and, opened by the camera's thumbnail, after the tap. */
    static String at() {
        long now = SystemClock.uptimeMillis();
        return stamp(openedAt > 0 ? now - openedAt : -1, tapAt > 0 ? now - tapAt : -1);
    }

    static String stamp(long sinceOpen, long sinceTap) {
        return "+" + sinceOpen + " ms" + (sinceTap >= 0 ? " (tap +" + sinceTap + " ms)" : "");
    }

    /**
     * P637: the viewer built its placeholder ({@code what}: «shutter frame» / «camera thumbnail» / «none»; {@code pending}: under
     * «Обработка», the shot is not saved yet; {@code w x h}: the picture, 0 without one).
     */
    public static void placeholder(String what, boolean pending, int w, int h) {
        if (openedAt == 0) return;
        Log.i(TAG, "placeholder shown " + at() + ": " + placeholderLine(what, pending, w, h));
    }

    static String placeholderLine(String what, boolean pending, int w, int h) {
        return what + (w > 0 && h > 0 ? " " + w + "x" + h : "") + (pending ? ", the shot is still processed" : ", the last photo");
    }

    /** P637: the frame with the placeholder was drawn (the first pixels of the open when it has a picture). */
    public static void placeholderOnScreen(boolean picture) {
        if (placeholderDrawn || openedAt == 0) return;
        placeholderDrawn = true;
        Log.i(TAG, "placeholder on screen " + at());
        if (picture) firstPixels("placeholder");
    }

    /** P637: the first picture of this open on the screen ({@code what}: «placeholder» or «photo»), once. */
    public static void firstPixels(String what) {
        if (firstPixels || openedAt == 0) return;
        firstPixels = true;
        Log.i(TAG, "first pixels " + at() + ": " + what);
    }

    /** P637: the viewer shows the camera's last photo from its file, before the media lists. */
    public static void front(String name, boolean row) {
        if (openedAt == 0) return;
        Log.i(TAG, "camera's last photo " + name + " bound " + at() + (row ? " (its MediaStore row)" : " (its file, no MediaStore row yet)")
                + (listed ? "" : ", before the media lists"));
    }

    /** P637: the shot the viewer waited for is saved ({@code name}); {@code shown}: it takes the placeholder's place. */
    public static void shotSaved(String name, boolean shown) {
        if (openedAt == 0) return;
        savedAt = SystemClock.uptimeMillis();
        Log.i(TAG, "shot saved " + at() + ": " + name + (shown ? ", shown from its file" : ", put first (another photo is viewed)"));
    }

    /** P637: the wait for a shot ended without a saved file ({@code why}). */
    public static void shotGone(String why) {
        if (openedAt == 0) return;
        Log.i(TAG, "placeholder dropped " + at() + ": " + why);
    }

    /** P637: the saved shot's photo is on the screen in the placeholder's place, once per open. */
    public static void finalPhotoReady() {
        if (finalPhoto || openedAt == 0 || savedAt == 0) return;
        finalPhoto = true;
        Log.i(TAG, "final photo ready " + at() + ", " + (SystemClock.uptimeMillis() - savedAt) + " ms after it was saved");
    }

    static long since() {
        return openedAt > 0 ? SystemClock.uptimeMillis() - openedAt : -1;
    }

    /** The media lists are set ({@code items}: photos in «ALL», -1 on a failed read; {@code readMs}: the read on the loader thread). */
    public static void listed(int items, long readMs) {
        listed(items, readMs, -1, -1);
    }

    /**
     * P637: as above with where the time went: {@code queuedMs} the read waited for the loader thread, {@code postedMs} its
     * result waited for the main thread (-1: unknown). A long «posted» is a busy main thread, a long «queued» an older read.
     */
    public static void listed(int items, long readMs, long queuedMs, long postedMs) {
        if (listed || openedAt == 0) return;
        listed = true;
        Log.i(TAG, "media listed +" + since() + " ms: " + items + " items, read " + readMs + " ms off the main thread"
                + (queuedMs >= 0 ? " (queued " + queuedMs + " ms, read, posted " + postedMs + " ms late)" : ""));
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
        Log.i(TAG, "viewer first photo ready +" + since() + " ms (" + route + ")" + (tapAt > 0 ? ", tap +" + (SystemClock.uptimeMillis() - tapAt) + " ms" : ""));
        firstPixels("photo");
    }

    /**
     * P637: the main thread watched for the first {@link #WATCH_MS} of an open: a ping every {@link #PING_MS}; one not answered
     * within {@link #BUSY_MS} is logged with the main thread's stack (what it is busy with or waits for), and how long it lasted
     * when it answers again. At most {@link #MAX_REPORTS} an open. One daemon thread, asleep between opens.
     */
    static final class MainWatch {
        static final long WATCH_MS = 12_000, PING_MS = 100, BUSY_MS = 350;
        static final int MAX_REPORTS = 4, FRAMES = 14;
        private static final Object LOCK = new Object();
        private static long until, start;
        private static Thread thread;
        private static volatile long answered;

        /** Not in a host test (its main looper runs only when the test says so: every ping would be «late»). */
        static boolean enabled = !"robolectric".equals(android.os.Build.FINGERPRINT);

        static void start(long openedAt) {
            if (!enabled) return;
            synchronized (LOCK) {
                start = openedAt;
                until = openedAt + WATCH_MS;
                answered = openedAt;
                if (thread == null) {
                    thread = new Thread(() -> {
                        try {
                            run();
                        } catch (RuntimeException e) {
                            Log.w(TAG, "main thread watch ended: " + e);
                        }
                        synchronized (LOCK) {
                            thread = null;
                        }
                    }, "GalleryOpenWatch");
                    thread.setDaemon(true);
                    thread.start();
                } else LOCK.notifyAll();
            }
        }

        private static void run() {
            final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            final Thread mainThread = android.os.Looper.getMainLooper().getThread();
            int reports = 0;
            long watched = 0, busySince = 0;
            while (true) {
                final long from;
                synchronized (LOCK) {
                    while (SystemClock.uptimeMillis() >= until && busySince == 0) { // a stall under way is followed to its end
                        try {
                            LOCK.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    from = start;
                }
                if (from != watched) { // a new open
                    watched = from;
                    reports = 0;
                    busySince = 0;
                }
                final long sent = SystemClock.uptimeMillis();
                main.post(() -> answered = SystemClock.uptimeMillis());
                try {
                    Thread.sleep(PING_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (answered >= sent) {
                    if (busySince != 0) {
                        Log.i(TAG, "main thread free again +" + (answered - from) + " ms: it was busy " + (answered - busySince) + " ms");
                        busySince = 0;
                    }
                    continue;
                }
                // not answered: wait on, report once it is late
                final long late = sent;
                while (answered < sent && SystemClock.uptimeMillis() - sent < BUSY_MS) {
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (answered >= sent) {
                    if (busySince != 0) {
                        Log.i(TAG, "main thread free again +" + (answered - from) + " ms: it was busy " + (answered - busySince) + " ms");
                        busySince = 0;
                    }
                    continue;
                }
                if (busySince != 0) continue; // still the stall already reported
                busySince = late;
                if (reports++ < MAX_REPORTS)
                    Log.w(TAG, busyLine(late - from, BUSY_MS, mainThread.getStackTrace()));
            }
        }

        static String busyLine(long sinceOpen, long busyMs, StackTraceElement[] stack) {
            StringBuilder b = new StringBuilder("main thread busy >" + busyMs + " ms from +" + sinceOpen + " ms of the open, at:");
            for (int i = 0; i < Math.min(FRAMES, stack.length); i++) b.append("\n    ").append(stack[i]);
            return b.toString();
        }
    }
}
