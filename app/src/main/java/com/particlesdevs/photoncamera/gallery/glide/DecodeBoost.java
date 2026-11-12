package com.particlesdevs.photoncamera.gallery.glide;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import com.particlesdevs.photoncamera.util.Log;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P637 (owner, 2026-11-12: the viewer black for ~6 s when opened while an «Oldstyle» shot was processed): the gallery's decode
 * threads ran at background priority all the time - Glide's source threads at nice 9, the viewer's tile decodes (AsyncTask) and
 * the preview / EXIF / strip threads at nice 10 - while a shot's CPU work runs at nice 0 on every core (ParallelWork's helpers,
 * the encoders): against one such thread on its core a nice-10 thread gets about a tenth of the core (CFS weights 110 : 1024),
 * so a 300 ms chain of header read, tile init and base tiles took seconds. For the open's first seconds ({@link #forOpen}) and
 * for every tile of the viewer ({@link #here}) the decode threads run at the default priority, one step above it while a shot
 * is processed; afterwards Glide's threads are background again (a scrolling grid must not compete with the UI thread).
 */
public final class DecodeBoost {
    private DecodeBoost() {}

    private static final String TAG = "GalleryOpen";
    /** Glide's own priority of its source threads (THREAD_PRIORITY_BACKGROUND + THREAD_PRIORITY_MORE_FAVORABLE). */
    static final int IDLE = Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE;
    /** The open's decodes: the default priority; with a shot in work one step above (the shot's helpers are at the default). */
    static final int FRONT = Process.THREAD_PRIORITY_DEFAULT, FRONT_BUSY = Process.THREAD_PRIORITY_FOREGROUND;
    /** How long an open keeps the threads in front (the first photo is ready long before on an idle phone). */
    static final long OPEN_MS = 4000;

    private static final Set<Integer> TIDS = ConcurrentHashMap.newKeySet();
    private static volatile int level = IDLE;
    private static volatile long until;
    private static Handler main;
    private static final Runnable LOWER = DecodeBoost::lower;

    /** The priority a decode thread has now. */
    static int level() {
        return SystemClock.uptimeMillis() < until ? level : IDLE;
    }

    /** The level of an open ({@code busy}: a shot is being processed). */
    static int front(boolean busy) {
        return busy ? FRONT_BUSY : FRONT;
    }

    /**
     * The gallery opens (main thread): every decode thread to the front for {@link #OPEN_MS}; {@code busy}: a shot is still
     * processed (its CPU threads compete).
     */
    public static void forOpen(boolean busy) {
        gridBoosted = false;
        level = front(busy);
        until = SystemClock.uptimeMillis() + OPEN_MS;
        apply(level);
        if (main == null) main = new Handler(Looper.getMainLooper());
        main.removeCallbacks(LOWER);
        main.postDelayed(LOWER, OPEN_MS);
        Log.i(TAG, "decode threads at priority " + level + " for " + OPEN_MS + " ms (" + TIDS.size() + " threads"
                + (busy ? ", a shot is processed" : "") + ")");
    }

    /** P645: the grid of this open had its boost ({@link #forGrid}). */
    private static volatile boolean gridBoosted;

    /**
     * P645 (owner, 2026-11-12: the gallery slow from the launcher too; vivo log: the list +5877 ms, the open's boost long over,
     * the first thumbnail +13583 ms while a 153 s «Статика» merge ran at the default priority): the grid's first fill of an open
     * puts the decode threads in front again for {@link #OPEN_MS} from then (its first screen of thumbnails, however late the list
     * came or the user went back from the viewer), once per open. Main thread.
     */
    public static void forGrid() {
        if (gridBoosted) return;
        gridBoosted = true;
        boolean busy = false;
        try {
            busy = com.particlesdevs.photoncamera.processing.ProcessingKeepAlive.busy.getAsBoolean();
        } catch (RuntimeException | LinkageError ignored) {
        }
        forOpen(busy);
        gridBoosted = true;
    }

    private static void lower() {
        until = 0;
        apply(IDLE);
    }

    private static void apply(int priority) {
        for (Integer tid : TIDS) {
            try {
                Process.setThreadPriority(tid, priority);
            } catch (RuntimeException e) {
                TIDS.remove(tid); // the thread ended
            }
        }
    }

    /**
     * The calling thread decodes what the viewer shows now (a tile, a page's preview, its EXIF): at the front level - AsyncTask
     * puts its threads at background priority before every task, so nothing is restored.
     */
    public static void here() {
        try {
            int want = Math.min(level(), FRONT);
            if (Process.getThreadPriority(Process.myTid()) > want) Process.setThreadPriority(want);
        } catch (RuntimeException ignored) {
            // the decode runs at the priority it has
        }
    }

    /**
     * Glide's source threads (GlideExecutor's builder): as Glide makes them, at {@link #IDLE} - or the front level while an open
     * is on - and known to {@link #forOpen}.
     */
    public static ThreadFactory factory() {
        final AtomicInteger n = new AtomicInteger();
        return r -> new Thread(() -> {
            final int tid = Process.myTid();
            try {
                Process.setThreadPriority(level());
            } catch (RuntimeException ignored) {
            }
            TIDS.add(tid);
            try {
                r.run();
            } finally {
                TIDS.remove(tid);
            }
        }, "gallery-decode-" + n.incrementAndGet());
    }

    /** Tests: no open is on. */
    static void reset() {
        gridBoosted = false;
        until = 0;
        level = IDLE;
    }

    /** Tests: whether the thread {@code tid} is one of the decode threads. */
    static boolean known(int tid) {
        return TIDS.contains(tid);
    }
}
