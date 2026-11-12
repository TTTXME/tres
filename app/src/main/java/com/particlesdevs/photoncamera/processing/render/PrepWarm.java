package com.particlesdevs.photoncamera.processing.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P625 (owner, 2026-11-11: «ускорь подготовку снимка»): the first shot of a process prepared its burst ~150 ms slower than the
 * next ones (vivo X200 Ultra, log adb59147: HYBRID JAVA PREP meta=172 / 179 / 177 on the shots right after a process start,
 * GLLimits' first query, against 14 - 20 on the others; the same route, frames and camera). The pure Java pixel loop in that span
 * is the darkest frame's black floor (Parameters.BlockScan: 4 x 192 k samples of a 12 MP frame, every one through the histogram,
 * the extremes and the padding tests), run interpreted until ART's JIT compiles it. Here it runs on a synthetic frame on the
 * session's low-priority prewarm thread once a process, so the shot calls compiled code. A pure function of its arguments: the
 * scan of a shot's frame gives what it gave (the warm-up writes no state; only the JIT's code cache). Its log line gives each
 * run's time (a later run much faster: compiled). scam_dev prep_warm 0: none.
 */
public final class PrepWarm {
    /** The synthetic frame: a quarter of 12 MP, enough loop iterations for the JIT's hotness threshold in one run. */
    static final int WIDTH = 2048, HEIGHT = 1536;
    static final int RUNS = 3;
    private static final AtomicBoolean DONE = new AtomicBoolean();

    private PrepWarm() {}

    /** The warm-up once a process (the first call), its log line; null on a later call, when off or on a failure. */
    public static String once() {
        if (!on() || !DONE.compareAndSet(false, true)) return null;
        try {
            final long[] ms = run(RUNS);
            final StringBuilder sb = new StringBuilder("PREP WARM (P625): black floor scan of " + WIDTH + "x" + HEIGHT + " x" + RUNS + " ms");
            for (int i = 0; i < ms.length; i++) sb.append(i == 0 ? " " : " / ").append(ms[i]);
            return sb.toString();
        } catch (RuntimeException | OutOfMemoryError e) {
            return "PREP WARM (P625): skipped (" + e + ")";
        }
    }

    private static boolean on() {
        try {
            return com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("prep_warm", true);
        } catch (RuntimeException | LinkageError e) {
            return true;
        }
    }

    /** {@code runs} scans of the synthetic frame (the shot's arguments: bulk rows, plain Bayer, a pedestal), each run's ms. */
    static long[] run(int runs) {
        final ShortBuffer frame = synthetic(WIDTH, HEIGHT);
        final int bw = WIDTH / 2 / Parameters.BlockScan.BS, bh = HEIGHT / 2 / Parameters.BlockScan.BS;
        final long[] ms = new long[runs];
        long sink = 0;
        for (int i = 0; i < runs; i++) {
            final long t0 = System.nanoTime();
            final Parameters.BlockScan scan = Parameters.BlockScan.of(frame, WIDTH, bw, bh, 16f, true, 1, 3f);
            sink += scan.max2 + scan.padding + Parameters.clipPlateau(scan.values, scan.counted);
            ms[i] = (System.nanoTime() - t0) / 1_000_000;
        }
        if (sink == Long.MIN_VALUE) throw new IllegalStateException(); // keeps the results alive
        return ms;
    }

    /** A frame around black 64 with a deterministic texture, a few clipped samples and a zero column (the padding test's path). */
    static ShortBuffer synthetic(int w, int h) {
        final ShortBuffer s = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                final int v = x == w - 1 ? 0 : (x * 7 + y * 13) % 997 == 0 ? 1023 : 60 + ((x * 31 + y * 17) & 255);
                s.put(y * w + x, (short) v);
            }
        s.clear();
        return s;
    }
}
