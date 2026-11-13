package com.scamera.app.processing.opengl;

import android.opengl.GLES30;

/**
 * P479 (speed, profiling only): the GPU time of every program switch of the post pipeline, by GL_EXT_disjoint_timer_query
 * (GL_TIME_ELAPSED_EXT), under scam_dev.txt "post_sync 1" only ({@link PostGlMode#syncNodes()}): one query from each
 * {@link #mark} to the next (a program's draws or dispatches, the uploads and read-backs issued before the next program), the
 * results read once at the end of the run and logged as one SCAM_PIPELINE line per node / program, slowest first. It only
 * measures: no draw, no texture and no uniform changes, so the pixels are those of a run without it. Off: every call returns at
 * once.
 * <p>
 * P671 (owner, 2026-11-13: speed on older GPUs, «замерь каждый проход»): on by default on Adreno (the paced post keeps that GPU
 * busy, so a query's span is the program's GPU time), as «PERF post GPU» lines - the nodes' sums, then the programs - so every
 * phone log shows where the GPU time goes and an A/B of a scam_dev speed switch shows its gain. scam_dev post_gpu_timer 0: off,
 * 1: on on any GPU with the extension.
 */
public final class GLGpuTimer {
    private GLGpuTimer() {}

    private static final int TIME_ELAPSED_EXT = 0x88BF, GPU_DISJOINT_EXT = 0x8FBB;
    private static volatile boolean on;
    private static boolean active;
    /** The thread (and so the context) of the run being measured: other threads' programs (the viewfinder) are not. */
    private static volatile Thread owner;
    private static String node = "", current;
    private static final java.util.ArrayList<int[]> queries = new java.util.ArrayList<>();
    private static final java.util.ArrayList<String> names = new java.util.ArrayList<>();
    private static int supported; // 0 unknown, 1 yes, -1 no
    private static boolean adreno; // P671: the default (known with supported)

    /** The start of a run: on under post_sync where the driver has the extension. */
    public static void begin() {
        if (on && active && owner == Thread.currentThread()) GLES30.glEndQuery(TIME_ELAPSED_EXT); // a failed run's
        queries.clear();
        names.clear();
        active = false;
        current = null;
        node = "";
        on = false;
        allocNs = allocBytes = freeNs = 0;
        allocs = 0;
        // P479b: also in a normal run with scam_dev.txt "post_gpu_timer 1" (a query spans from one mark to the next, so there it
        // holds the GPU's idle time too: where the GPU waits for the CPU)
        if (supported == 0) {
            final String ext = GLES30.glGetString(GLES30.GL_EXTENSIONS);
            supported = ext != null && ext.contains("GL_EXT_disjoint_timer_query") ? 1 : -1;
            final String gpu = GLES30.glGetString(GLES30.GL_RENDERER);
            adreno = gpu != null && gpu.contains("Adreno");
        }
        if (!PostGlMode.syncNodes() && !com.scamera.app.settings.PreferenceKeys.scamDevSwitch("post_gpu_timer", adreno)) return;
        owner = Thread.currentThread();
        on = supported > 0;
        if (on) {
            final int[] d = new int[1];
            GLES30.glGetIntegerv(GPU_DISJOINT_EXT, d, 0); // clears the flag
        }
    }

    private static long allocNs, allocBytes, freeNs;
    private static int allocs;

    /** Whether this thread's run is measured (for the CPU time of texture storage, {@link #alloc} / {@link #free}). */
    public static boolean measuring() {
        return on && owner == Thread.currentThread();
    }

    /** CPU time of one texture's storage (glTexStorage2D without data) of {@code bytes}. */
    public static void alloc(long ns, long bytes) {
        allocNs += ns;
        allocBytes += bytes;
        allocs++;
    }

    /** CPU time of one texture's delete. */
    public static void free(long ns) {
        freeNs += ns;
    }

    /** The node whose programs follow. */
    public static void node(String name) {
        if (!on || owner != Thread.currentThread()) return;
        node = name;
        mark("(node)");
    }

    /** From here on the GPU time is {@code what}'s (of the current node); returns the previous name (for {@link #mark} back). */
    public static String mark(String what) {
        if (!on || owner != Thread.currentThread()) return null;
        final String previous = current;
        if (active) GLES30.glEndQuery(TIME_ELAPSED_EXT);
        final int[] id = new int[1];
        GLES30.glGenQueries(1, id, 0);
        GLES30.glBeginQuery(TIME_ELAPSED_EXT, id[0]);
        queries.add(id);
        current = what;
        names.add(node + "/" + what);
        active = true;
        return previous;
    }

    /** The end of a run: every query read (they wait for the GPU) and logged. */
    public static void report(String label) {
        if (!on || owner != Thread.currentThread()) return;
        on = false;
        if (active) GLES30.glEndQuery(TIME_ELAPSED_EXT);
        active = false;
        final java.util.LinkedHashMap<String, long[]> sums = new java.util.LinkedHashMap<>();
        long total = 0;
        final int[] r = new int[1];
        for (int i = 0; i < queries.size(); i++) {
            final int id = queries.get(i)[0];
            GLES30.glGetQueryObjectuiv(id, GLES30.GL_QUERY_RESULT, r, 0);
            GLES30.glDeleteQueries(1, queries.get(i), 0);
            final long ns = r[0] & 0xFFFFFFFFL;
            total += ns;
            final long[] s = sums.computeIfAbsent(names.get(i), k -> new long[2]);
            s[0] += ns;
            s[1]++;
        }
        final int[] d = new int[1];
        GLES30.glGetIntegerv(GPU_DISJOINT_EXT, d, 0);
        final java.util.ArrayList<java.util.Map.Entry<String, long[]>> list = new java.util.ArrayList<>(sums.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        com.scamera.app.util.Log.i("SCAM_PIPELINE", nodeLine(label, sums, total, d[0] != 0));
        final StringBuilder sb = new StringBuilder("PERF ").append(label).append(" GPU programs, total ms=")
                .append(String.format(java.util.Locale.ROOT, "%.1f", total / 1e6)).append(d[0] != 0 ? " (disjoint)" : "")
                .append(String.format(java.util.Locale.ROOT, " cpu storage ms=%.1f (%d textures, %d MB) delete ms=%.1f",
                        allocNs / 1e6, allocs, allocBytes >> 20, freeNs / 1e6));
        sb.append(':');
        // a few long lines (the log quota): "node/program ms xN" by GPU time
        for (java.util.Map.Entry<String, long[]> e : list) {
            if (e.getValue()[0] < 200_000L) continue;
            if (sb.length() > 700) {
                com.scamera.app.util.Log.i("SCAM_PIPELINE", sb.toString());
                sb.setLength(0);
                sb.append("PERF ").append(label).append(" GPU programs ...");
            }
            sb.append(String.format(java.util.Locale.ROOT, " %s %.2f x%d;", e.getKey(), e.getValue()[0] / 1e6, e.getValue()[1]));
        }
        com.scamera.app.util.Log.i("SCAM_PIPELINE", sb.toString());
        queries.clear();
        names.clear();
    }

    /**
     * P671: «PERF post GPU nodes ms total T: node a, node b, ...» - each node's queries summed ("node/program" names; the order of
     * the run), {@code total} in ns; tagged when the driver reported a disjoint (the times are then not to be trusted).
     */
    static String nodeLine(String label, java.util.Map<String, long[]> sums, long total, boolean disjoint) {
        final java.util.LinkedHashMap<String, Long> nodes = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, long[]> e : sums.entrySet()) {
            final String key = e.getKey();
            final int slash = key.indexOf('/');
            final String node = slash > 0 ? key.substring(0, slash) : slash == 0 ? "(start)" : key;
            nodes.merge(node, e.getValue()[0], Long::sum);
        }
        final StringBuilder sb = new StringBuilder("PERF ").append(label).append(" GPU nodes ms total ")
                .append(String.format(java.util.Locale.ROOT, "%.1f", total / 1e6)).append(disjoint ? " (disjoint)" : "").append(':');
        boolean first = true;
        for (java.util.Map.Entry<String, Long> e : nodes.entrySet()) {
            sb.append(first ? " " : ", ").append(e.getKey()).append(' ').append(String.format(java.util.Locale.ROOT, "%.1f", e.getValue() / 1e6));
            first = false;
        }
        return sb.toString();
    }
}
