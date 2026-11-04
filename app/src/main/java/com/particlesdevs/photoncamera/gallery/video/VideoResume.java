package com.particlesdevs.photoncamera.gallery.video;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/**
 * P499 (owner, 2026-11-04: «галерея должна запоминать если видео проигрывалось ... и при следующем включении этого же видео оно
 * продолжается там же где было закончено»): where each video was left, by its content URI. Nothing is kept for the first 3 s or the
 * last 3 s (a video watched to the end starts over); the 300 most recent are kept. The grid draws the watched part under a thumbnail.
 */
public final class VideoResume {
    private VideoResume() {}

    private static final String PREFS = "gallery_video_resume";
    static final long EDGE_MS = 3000;
    static final int KEEP = 300;

    /** Where to start from a saved position: 0 near either end. */
    public static long resumeAtMs(long savedMs, long durationMs) {
        if (savedMs < EDGE_MS) return 0;
        if (durationMs > 0 && savedMs > durationMs - EDGE_MS) return 0;
        return savedMs;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** "position/duration/savedAt" → {position, duration, savedAt}, null when unreadable. */
    static long[] decode(String v) {
        if (v == null) return null;
        String[] p = v.split("/");
        if (p.length != 3) return null;
        try {
            return new long[]{Long.parseLong(p[0]), Long.parseLong(p[1]), Long.parseLong(p[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The saved position in ms (0: start over). */
    public static long load(Context c, String key) {
        long[] v = decode(prefs(c).getString(key, null));
        return v == null ? 0 : resumeAtMs(v[0], v[1]);
    }

    /** The watched share 0..1 for the grid, or -1 when nothing is kept. */
    public static float progress(Context c, String key) {
        long[] v = decode(prefs(c).getString(key, null));
        if (v == null || v[1] <= 0 || resumeAtMs(v[0], v[1]) == 0) return -1;
        return Math.min(1f, v[0] / (float) v[1]);
    }

    public static void save(Context c, String key, long positionMs, long durationMs) {
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor e = p.edit();
        if (resumeAtMs(positionMs, durationMs) == 0) e.remove(key);
        else {
            e.putString(key, positionMs + "/" + durationMs + "/" + System.currentTimeMillis());
            Map<String, ?> all = p.getAll();
            if (all.size() > KEEP) { // drop the oldest
                String oldest = null;
                long at = Long.MAX_VALUE;
                for (Map.Entry<String, ?> en : all.entrySet()) {
                    long[] v = decode(String.valueOf(en.getValue()));
                    long t = v == null ? 0 : v[2];
                    if (t < at && !en.getKey().equals(key)) {
                        at = t;
                        oldest = en.getKey();
                    }
                }
                if (oldest != null) e.remove(oldest);
            }
        }
        e.apply();
    }
}
