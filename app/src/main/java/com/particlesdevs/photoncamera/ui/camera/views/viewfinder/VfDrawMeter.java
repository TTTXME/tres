package com.particlesdevs.photoncamera.ui.camera.views.viewfinder;

import android.os.SystemClock;

import com.particlesdevs.photoncamera.util.Log;

import java.util.Locale;

/**
 * P57: what the user sees of the viewfinder while a shot is processed. The camera keeps delivering ~30 fps after the shutter
 * (STAB_TRACE, X300U log 2026-10-08), so a stutter is in drawing: this counts the frames the viewfinder renderer DRAWS, per
 * 2 s, from the shutter press for {@link #WINDOW_MS} (one line per period: "VF_DRAW t=+4.0s drawn=58 maxGap=41ms
 * draw=1.2/6.8ms"). Next to the shot timeline it shows which stage costs frames. Called on the GL thread only.
 */
public final class VfDrawMeter {
    private static final String TAG = "VF_DRAW";
    static final long PERIOD_MS = 2000, WINDOW_MS = 30000;

    private static volatile long shotAtMs = -1;
    private static long periodStartMs = -1, lastFrameMs = -1;
    private static int drawn;
    private static long maxGapMs;
    /** P616: the cause of the period's longest gap ({@link #classify}). */
    private static byte maxGapCause;
    private static double drawSumMs, drawMaxMs;

    private VfDrawMeter() {}

    /** The shutter was pressed: the next {@link #WINDOW_MS} are measured. */
    public static void shot() {
        shotAtMs = SystemClock.elapsedRealtime();
    }

    /**
     * P236: the draw times of the last {@link #RECENT} frames (any time, not only after a shot), for {@link #fpsLast2s}; P611: 4096
     * (2 min at 30 fps, 32 KB) so that {@link #span} covers a 200 MP shot's merge and post pipeline (36 s on the OnePlus 16).
     */
    static final int RECENT = 4096;
    private static final long[] recentMs = new long[RECENT];
    private static int recentNext;

    /**
     * P236 (speed-ux F): the frames the viewfinder drew in the last 2 s, per second (the shot summary at the press and at the
     * end); 0 when nothing was drawn. Any thread.
     */
    public static double fpsLast2s() {
        return fpsSince(SystemClock.elapsedRealtime(), PERIOD_MS);
    }

    static synchronized double fpsSince(long nowMs, long windowMs) {
        int n = 0;
        for (long t : recentMs) if (t > 0 && nowMs - t <= windowMs && t <= nowMs) n++;
        return windowMs > 0 ? n * 1000.0 / windowMs : 0;
    }

    /**
     * P611 (owner, 2026-11-10: «Исправь так же тормоза видоискателя во время обработки фото»): the viewfinder through one stage of
     * a shot (ShotTimeline: the merge, the post pipeline), from the recent draw times: "fps/longest gap", e.g. "29.8fps/45ms" - the
     * draws from {@code fromMs} to {@code toMs} (SystemClock.elapsedRealtime) per second and the longest time without a draw there,
     * its edges included (a draw before the start and none until 300 ms in is a 300 ms gap). "-" for a span under 100 ms or the
     * wrong way round, "?" when the draws kept no longer reach back to its start. Any thread.
     */
    public static String span(long fromMs, long toMs) {
        synchronized (VfDrawMeter.class) {
            return spanOf(recentMs, recentNext, fromMs, toMs);
        }
    }

    /** {@link #span} over the ring {@code times} whose oldest slot is {@code next} (0 = never drawn). */
    static String spanOf(long[] times, int next, long fromMs, long toMs) {
        if (toMs - fromMs < 100) return "-";
        final long oldest = times[next % times.length];
        if (oldest > 0 && oldest > fromMs) return "?";
        long prev = fromMs, maxGap = 0;
        int n = 0;
        for (int k = 0; k < times.length; k++) {
            final long t = times[(next + k) % times.length];
            if (t <= 0 || t < fromMs || t > toMs) continue;
            maxGap = Math.max(maxGap, t - prev);
            prev = t;
            n++;
        }
        maxGap = Math.max(maxGap, toMs - prev);
        return String.format(Locale.ROOT, "%.1ffps/%dms", n * 1000.0 / (toMs - fromMs), maxGap);
    }

    static synchronized void recent(long nowMs) {
        recent(nowMs, NONE, 0, 0, null);
    }

    /** One draw into the ring with the cause of the gap before it ({@link #classify}), its own and its upload's ms, the post node. */
    static synchronized void recent(long nowMs, byte cause, long drawMs, long uploadMs, String node) {
        recentMs[recentNext] = nowMs;
        recentCause[recentNext] = cause;
        recentDrawMs[recentNext] = (short) Math.min(Short.MAX_VALUE, Math.max(0, drawMs));
        recentUploadMs[recentNext] = (short) Math.min(Short.MAX_VALUE, Math.max(0, uploadMs));
        recentNode[recentNext] = node;
        recentNext = (recentNext + 1) % RECENT;
    }

    // ---- P616 (owner, 2026-11-11: «Ты не исправил тормоза видоискателя во время обработки»): why the longest gap of a stage
    // was one. The 31254 log's worst stages were not processing at all: the first shot's vf_post=0.0fps/1008ms and vf_merge 1348 ms
    // were the settings screen opened during the merge (the viewfinder paused until the post was done), a 755 ms vf_merge a lens
    // switch (restartCamera) in the merge, a 321 ms one the new camera's RAW stream at ~17 frames/s; the post's own stall was one
    // 240 - 339 ms draw inside ScamHdrDenoise, blocked in the RAW frame's upload. Each draw now keeps the cause of the gap before it.
    /** Causes of the gap before a draw. */
    static final byte NONE = 0, DRAW = 'd', UPLOAD = 'u', CAMERA = 'c', WAIT = 'w', HIDDEN = 'h', RESTART = 'r';
    private static final byte[] recentCause = new byte[RECENT];
    private static final short[] recentDrawMs = new short[RECENT], recentUploadMs = new short[RECENT];
    private static final String[] recentNode = new String[RECENT];
    /** GL thread: the end of the last draw (elapsedRealtime ms, -1 none) and the RAW upload time of the draw running now. */
    private static long lastDrawEndMs = -1;
    private static long uploadNs;
    /** When the newest ISP / RAW frame arrived (elapsedRealtime ms, -1 none), the viewfinder was paused (-1: it is not), restarted. */
    private static volatile long ispArrivedMs = -1, rawArrivedMs = -1, hiddenSinceMs = -1, restartAtMs = -1;

    /** An ISP frame is available (MainRenderer.onFrameAvailable) / a RAW frame was published (LiveRawFrame); any thread. */
    public static void arrived(boolean raw) {
        final long now = SystemClock.elapsedRealtime();
        if (raw) rawArrivedMs = now;
        else ispArrivedMs = now;
    }

    /** The viewfinder is paused (GLPreview.onPause: the activity left, the settings screen opened); the next draw ends it. */
    public static void hidden() {
        if (hiddenSinceMs < 0) hiddenSinceMs = SystemClock.elapsedRealtime();
    }

    /** The camera is opened again (CaptureController.restartCamera: a lens switch, a session setting). */
    public static void restart() {
        restartAtMs = SystemClock.elapsedRealtime();
    }

    /** GL thread: the RAW frame's texture upload inside the draw running now (LiveRawRenderer). */
    static void upload(long ns) {
        uploadNs += Math.max(0, ns);
    }

    /**
     * The cause of the gap from the draw before ({@code prevEndMs}, -1 none) to this one ending at {@code endMs}: paused or the
     * camera restarted in it; else the draw itself when it took half the gap or more (the upload when that took half the draw);
     * else the camera when the frame drawn came half the gap or more after the draw before (no frame to draw); else a wait between
     * a frame there and its draw (the render thread late: its swap, the main thread, the CPU).
     */
    static byte classify(long prevEndMs, long endMs, long drawMs, long uploadMs, long arrivedMs, boolean hidden, boolean restarted) {
        if (prevEndMs < 0 || endMs <= prevEndMs) return NONE;
        if (hidden) return HIDDEN;
        if (restarted) return RESTART;
        final long gap = endMs - prevEndMs;
        if (drawMs * 2 >= gap) return uploadMs * 2 >= drawMs ? UPLOAD : DRAW;
        if (arrivedMs >= 0 && (arrivedMs - prevEndMs) * 2 >= gap) return CAMERA;
        return WAIT;
    }

    static String causeName(byte cause) {
        switch (cause) {
            case DRAW: return "draw";
            case UPLOAD: return "upload";
            case CAMERA: return "camera";
            case WAIT: return "wait";
            case HIDDEN: return "hidden";
            case RESTART: return "restart";
            default: return "edge";
        }
    }

    /**
     * P616: a stage of a shot as {"fps/longest gap", the gap's note or null}: the paused and the camera-restart gaps are left out
     * of both (the fps over the time left; "?" with none left, or when the ring no longer reaches back), the note names the
     * longest gap's cause when it is 100 ms or more - "298ms:upload(296)@ScamHdrDenoise" - and the time left out ("hidden=1008ms").
     * Any thread.
     */
    public static String[] stage(long fromMs, long toMs) {
        synchronized (VfDrawMeter.class) {
            return stageOf(recentMs, recentCause, recentDrawMs, recentUploadMs, recentNode, recentNext, fromMs, toMs, hiddenSinceMs);
        }
    }

    static String[] stageOf(long[] times, byte[] causes, short[] drawMs, short[] uploadMs, String[] nodes, int next, long fromMs,
                            long toMs, long hiddenSince) {
        if (toMs - fromMs < 100) return new String[]{"-", null};
        final int len = times.length;
        final long oldest = times[next % len];
        if (oldest > 0 && oldest > fromMs) return new String[]{"?", null};
        long prev = fromMs, maxGap = 0, hiddenMs = 0, restartMs = 0;
        int n = 0, maxAt = -1;
        int after = -1; // the first draw after the stage
        for (int k = 0; k < len; k++) {
            final int i = (next + k) % len;
            final long t = times[i];
            if (t <= 0 || t < fromMs) continue;
            if (t > toMs) {
                if (after < 0) after = i;
                continue;
            }
            final long gap = t - prev;
            if (causes[i] == HIDDEN) hiddenMs += gap;
            else if (causes[i] == RESTART) restartMs += gap;
            else if (gap > maxGap) {
                maxGap = gap;
                maxAt = i;
            }
            prev = t;
            n++;
        }
        final long tail = toMs - prev;
        final byte tailCause = after >= 0 ? causes[after] : hiddenSince >= 0 && hiddenSince <= toMs ? HIDDEN : NONE;
        if (tailCause == HIDDEN) hiddenMs += tail;
        else if (tailCause == RESTART) restartMs += tail;
        else if (tail > maxGap) {
            maxGap = tail;
            maxAt = after >= 0 ? after : -2; // -2: no draw after it yet
        }
        final long visible = toMs - fromMs - hiddenMs - restartMs;
        final String span = visible < 100 ? "?" : String.format(Locale.ROOT, "%.1ffps/%dms", n * 1000.0 / visible, maxGap);
        final StringBuilder note = new StringBuilder();
        if (maxGap >= 100) {
            note.append(maxGap).append("ms:");
            if (maxAt == -2) note.append("no_draw_yet");
            else if (maxAt >= 0) {
                final byte c = causes[maxAt];
                note.append(causeName(c));
                if (c == UPLOAD || c == DRAW) note.append('(').append(c == UPLOAD ? uploadMs[maxAt] : drawMs[maxAt]).append(')');
                final String node = nodes[maxAt];
                if (node != null && !node.isEmpty()) note.append('@').append(node);
            } else note.append("edge");
        }
        if (hiddenMs > 0) note.append(note.length() > 0 ? "," : "").append("hidden=").append(hiddenMs).append("ms");
        if (restartMs > 0) note.append(note.length() > 0 ? "," : "").append("restart=").append(restartMs).append("ms");
        return new String[]{span, note.length() > 0 ? note.toString() : null};
    }

    /**
     * One drawn frame ({@code drawNs}: time spent in onDrawFrame; {@code raw}: the developed RAW frame was drawn, not the ISP one).
     * P616: the gap before it classified ({@link #classify}) and kept with it, and the post node running at its end.
     */
    static void frame(long drawNs, boolean raw) {
        final long end = SystemClock.elapsedRealtime();
        final long drawMs = drawNs / 1_000_000L, upMs = uploadNs / 1_000_000L;
        uploadNs = 0;
        final long hs = hiddenSinceMs, rs = restartAtMs;
        final long prevEnd = lastDrawEndMs;
        final boolean hidden = hs >= 0, restarted = rs >= 0 && prevEnd >= 0 && rs > prevEnd;
        if (hidden) hiddenSinceMs = -1;
        final String running = com.particlesdevs.photoncamera.processing.opengl.GLBasePipeline.runningNode;
        final byte cause = classify(prevEnd, end, drawMs, upMs, raw ? rawArrivedMs : ispArrivedMs, hidden, restarted);
        recent(end, cause, drawMs, upMs, running);
        lastDrawEndMs = end;
        frameInShot(end, drawNs, upMs, running, cause);
    }

    /** P57's 2 s periods after a shot and P497's slow-draw line. */
    private static void frameInShot(long end, long drawNs, long upMs, String running, byte cause) {
        final long shot = shotAtMs;
        if (shot < 0) return;
        final long now = end;
        if (now - shot > WINDOW_MS + PERIOD_MS) {
            if (periodStartMs >= 0) flush(now, shot);
            shotAtMs = -1;
            periodStartMs = -1;
            lastFrameMs = -1;
            return;
        }
        if (periodStartMs < 0 || periodStartMs < shot) { // a new shot restarts the periods
            reset(shot);
        }
        if (lastFrameMs >= 0 && now - lastFrameMs > maxGapMs) {
            maxGapMs = now - lastFrameMs;
            maxGapCause = cause;
        }
        lastFrameMs = now;
        ++drawn;
        final double ms = drawNs / 1e6;
        // P497 (owner, 2026-11-04: «во время обработки снимка у нас все ещё тормозит видоискатель»; vivo X200 Ultra: single draws
        // of 176 - 334 ms while a shot was processed): a slow draw named with the post node finished last
        // P616: with the RAW upload's share and the node running at its end ("in", P497's "after" the one finished last)
        if (ms > 100) Log.i(TAG, String.format(Locale.ROOT, "slow draw %.0f ms (upload %d) at t=+%.1fs in node %s after node %s", ms, upMs,
                (now - shot) / 1000.0, running == null || running.isEmpty() ? "-" : running,
                com.particlesdevs.photoncamera.processing.opengl.GLBasePipeline.lastNode));
        drawSumMs += ms;
        drawMaxMs = Math.max(drawMaxMs, ms);
        if (now - periodStartMs >= PERIOD_MS) {
            flush(now, shot);
            periodStartMs = now;
        }
    }

    private static void reset(long start) {
        periodStartMs = start;
        lastFrameMs = -1;
        drawn = 0;
        maxGapMs = 0;
        drawSumMs = drawMaxMs = 0;
    }

    private static void flush(long now, long shot) {
        Log.i(TAG, line(now - shot, now - periodStartMs, drawn, maxGapMs, drawn > 0 ? drawSumMs / drawn : 0, drawMaxMs)
                + (maxGapMs >= 100 ? " gap=" + causeName(maxGapCause) : "")); // P616
        drawn = 0;
        maxGapMs = 0;
        maxGapCause = NONE;
        drawSumMs = drawMaxMs = 0;
    }

    static String line(long sinceShotMs, long periodMs, int frames, long maxGap, double drawMean, double drawMax) {
        final double fps = periodMs > 0 ? frames * 1000.0 / periodMs : 0;
        return String.format(Locale.ROOT, "t=+%.1fs drawn=%d (%.1f fps) maxGap=%dms draw=%.1f/%.1fms",
                sinceShotMs / 1000.0, frames, fps, maxGap, drawMean, drawMax);
    }
}
