package com.scamera.app.processing.opengl;

import java.util.HashMap;
import java.util.Map;

/**
 * P477 (memory): the post pipeline's texture inventory - the bytes of texture storage alive on the post thread, its peak in the
 * run and what was alive then (node : size format, counted), logged once a run ({@link #report}).
 *
 * <p>P477's reuse pool (closed textures kept for the next of their size and format, scam_dev "tex_pool") was removed: on the
 * complete30 replay it raised the texture peak 564 -> 938 MB and slowed the post, and it was never on by default.
 *
 * <p>GL thread only (the thread of {@link #begin}); other threads' textures (the viewfinder) are not counted.
 */
public final class GLTexturePool {
    private GLTexturePool() {}

    private static volatile Thread owner;
    private static String node = "";
    private static long live, peak;
    private static String peakAt = "", peakSnapshot = "";
    /** live textures: name -> {bytes, node, size format} (for the peak's snapshot). */
    private static final Map<Integer, Object[]> ALIVE = new HashMap<>();

    /** The start of a post run on this thread: the inventory reset. */
    public static void begin() {
        owner = Thread.currentThread();
        live = peak = 0;
        peakAt = "";
        peakSnapshot = "";
        node = "";
        ALIVE.clear();
    }

    static boolean mine() {
        return owner == Thread.currentThread();
    }

    public static void node(String name) {
        if (mine()) node = name;
    }

    static void allocated(int id, long bytes, String what) {
        if (!mine()) return;
        live += bytes;
        ALIVE.put(id, new Object[]{bytes, node, what});
        if (live > peak) {
            peak = live;
            peakAt = node;
            snapshotNow();
        }
    }

    static void closed(int id, long bytes) {
        if (!mine()) return;
        live -= bytes;
        ALIVE.remove(id);
    }

    private static void snapshotNow() {
        final Map<String, long[]> by = new HashMap<>();
        for (Object[] v : ALIVE.values()) {
            final long[] c = by.computeIfAbsent(v[1] + ":" + v[2], k -> new long[2]);
            c[0]++;
            c[1] += (Long) v[0];
        }
        final StringBuilder sb = new StringBuilder();
        by.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1])).limit(12).forEach(e ->
                sb.append(' ').append(e.getKey()).append(" x").append(e.getValue()[0]).append('=').append(e.getValue()[1] >> 20).append("MB;"));
        peakSnapshot = sb.toString();
    }

    /** The run's line (SCAM_PIPELINE): the peak of texture storage, where, and what was alive then. */
    public static void report(String label) {
        if (!mine()) return;
        com.scamera.app.util.Log.i("SCAM_PIPELINE", "texture memory " + label + ": peak " + (peak >> 20) + " MB at " + peakAt
                + ":" + peakSnapshot);
    }
}
