package com.particlesdevs.photoncamera.processing.opengl;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * P477 (memory): the post pipeline's texture storage in one place.
 * <ul>
 * <li>The inventory (always): the bytes of texture storage alive on the post thread, its peak in the run and what was alive then
 *     (node : size format, counted), logged once a run ({@link #report}); a texture kept in the pool still counts (it holds its
 *     storage).</li>
 * <li>The reuse pool (scam_dev.txt "tex_pool", on while a post run is active, {@link #begin} .. {@link #end}): a texture of 4 MB or
 *     more that a node closes keeps its storage and its name, and the next texture of the same size and format without data takes
 *     it (its filter and wrap set again) instead of a new allocation next to storage the driver frees only once the GPU is done with
 *     it. Every pass draws every pixel of a new target before anything reads it, so the old contents never show (a pass that did
 *     not would differ from the old route: the complete30 JPEG is the check). At most {@link #CAP_BYTES} kept, nothing across runs:
 *     {@link #end} deletes what is left (GLTexture.closeAll).</li>
 * </ul>
 * GL thread only (the thread of {@link #begin}); other threads' textures (the viewfinder) are neither counted nor pooled.
 */
public final class GLTexturePool {
    private GLTexturePool() {}

    static final long MIN_BYTES = 4L << 20;
    static final long CAP_BYTES = 640L << 20;

    private static volatile Thread owner;
    private static boolean pooling, pooledRun;
    private static String node = "";
    private static long live, peak, pooledBytes, reused, reusedBytes;
    private static String peakAt = "", peakSnapshot = "";
    private static final Map<String, ArrayDeque<int[]>> POOL = new HashMap<>();
    /** live textures: name -> {bytes, node, size format} (for the peak's snapshot). */
    private static final Map<Integer, Object[]> ALIVE = new HashMap<>();

    /** The start of a post run on this thread: the inventory reset, the pool on when {@code pool}. */
    public static void begin(boolean pool) {
        drain();
        owner = Thread.currentThread();
        pooling = pool;
        pooledRun = pool;
        live = peak = 0;
        reused = reusedBytes = 0;
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

    static String key(int w, int h, int internalFormat) {
        return w + "x" + h + ":" + internalFormat;
    }

    /** A kept name of this size and format (its storage as it was), or 0. */
    static int take(int w, int h, int internalFormat) {
        if (!pooling || !mine()) return 0;
        final ArrayDeque<int[]> q = POOL.get(key(w, h, internalFormat));
        if (q == null || q.isEmpty()) return 0;
        final int[] e = q.pop();
        pooledBytes -= e[1] & 0xFFFFFFFFL;
        reused++;
        reusedBytes += e[1] & 0xFFFFFFFFL;
        return e[0];
    }

    /** True: the closing texture's name and storage are kept (the caller does not delete it). */
    static boolean give(int id, int w, int h, int internalFormat, long bytes) {
        if (!pooling || !mine() || bytes < MIN_BYTES || bytes > Integer.MAX_VALUE || pooledBytes + bytes > CAP_BYTES) return false;
        POOL.computeIfAbsent(key(w, h, internalFormat), k -> new ArrayDeque<>()).push(new int[]{id, (int) bytes});
        pooledBytes += bytes;
        return true;
    }

    static void allocated(int id, long bytes, String what, boolean fromPool) {
        if (!mine()) return;
        if (!fromPool) live += bytes; // a reused name's storage was counted (and kept) all along
        ALIVE.put(id, new Object[]{bytes, node, what});
        if (live > peak) {
            peak = live;
            peakAt = node;
            peakSnapshot = null; // made on demand (report): the last peak's set
            snapshotNow();
        }
    }

    /** {@code kept}: into the pool (its storage still held). */
    static void closed(int id, long bytes, boolean kept) {
        if (!mine()) return;
        if (!kept) live -= bytes;
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
        if (pooledBytes > 0) sb.append(" pooled=").append(pooledBytes >> 20).append("MB");
        peakSnapshot = sb.toString();
    }

    /** The run's line (SCAM_PIPELINE): the peak of texture storage, where, and what was alive then. */
    public static void report(String label) {
        if (!mine()) return;
        com.particlesdevs.photoncamera.util.Log.i("SCAM_PIPELINE", "texture memory " + label + ": peak " + (peak >> 20) + " MB at " + peakAt
                + (pooledRun ? " (pool: " + reused + " reused, " + (reusedBytes >> 20) + " MB)" : " (no pool)") + ":" + peakSnapshot);
    }

    /** The end of a run: the pool's names deleted (GLTexture.closeAll); the pool off. */
    public static void end() {
        if (owner != null && !mine()) return;
        drain();
        pooling = false;
    }

    private static void drain() {
        if (POOL.isEmpty()) return;
        int n = 0;
        for (ArrayDeque<int[]> q : POOL.values()) n += q.size();
        final int[] ids = new int[n];
        int i = 0;
        for (ArrayDeque<int[]> q : POOL.values()) for (int[] e : q) ids[i++] = e[0];
        POOL.clear();
        if (mine()) live -= pooledBytes;
        pooledBytes = 0;
        if (n > 0) android.opengl.GLES30.glDeleteTextures(n, ids, 0);
    }
}
