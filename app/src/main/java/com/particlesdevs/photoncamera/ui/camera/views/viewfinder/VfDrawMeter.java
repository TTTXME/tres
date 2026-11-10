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
        recentMs[recentNext] = nowMs;
        recentNext = (recentNext + 1) % RECENT;
    }

    /** One drawn frame ({@code drawNs}: time spent in onDrawFrame). */
    static void frame(long drawNs) {
        recent(SystemClock.elapsedRealtime());
        final long shot = shotAtMs;
        if (shot < 0) return;
        final long now = SystemClock.elapsedRealtime();
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
        if (lastFrameMs >= 0) maxGapMs = Math.max(maxGapMs, now - lastFrameMs);
        lastFrameMs = now;
        ++drawn;
        final double ms = drawNs / 1e6;
        // P497 (owner, 2026-11-04: «во время обработки снимка у нас все ещё тормозит видоискатель»; vivo X200 Ultra: single draws
        // of 176 - 334 ms while a shot was processed): a slow draw named with the post node finished last
        if (ms > 100) Log.i(TAG, String.format(Locale.ROOT, "slow draw %.0f ms at t=+%.1fs after node %s", ms, (now - shot) / 1000.0,
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
        Log.i(TAG, line(now - shot, now - periodStartMs, drawn, maxGapMs, drawn > 0 ? drawSumMs / drawn : 0, drawMaxMs));
        drawn = 0;
        maxGapMs = 0;
        drawSumMs = drawMaxMs = 0;
    }

    static String line(long sinceShotMs, long periodMs, int frames, long maxGap, double drawMean, double drawMax) {
        final double fps = periodMs > 0 ? frames * 1000.0 / periodMs : 0;
        return String.format(Locale.ROOT, "t=+%.1fs drawn=%d (%.1f fps) maxGap=%dms draw=%.1f/%.1fms",
                sinceShotMs / 1000.0, frames, fps, maxGap, drawMean, drawMax);
    }
}
