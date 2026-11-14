package com.scamera.app.processing.opengl;

import com.scamera.app.util.Log;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * P616 (owner, 2026-11-11: «Ты не исправил тормоза видоискателя во время обработки»; vivo X200 Ultra, Adreno 830, build 31254:
 * one viewfinder draw of 240 - 339 ms in every post pipeline, each inside ScamHdrDenoise, the draw blocked in the RAW frame's
 * texture upload - VF_RAW «upload 10.2/296.0» against 4 - 5 ms otherwise). The post pipeline's thread queued its passes as fast as
 * the driver took them: 48 resolve blocks of ~10 ms GPU each, the node's own CPU time (360 - 490 ms) the GPU's, i.e. the thread
 * was held inside the driver (glFlush / glFinish / a synchronous read-back) for the whole node. The viewfinder's context in the
 * same process waited behind that queue (its high priority did not help: the post and the viewfinder share the process's driver).
 * <p>
 * This paces the post's submissions: after each block (a glFlush the passes already make) a fence goes in; while more than
 * {@link #inFlight} fences are pending the thread waits for the oldest by polling it (glClientWaitSync with a zero timeout and a
 * short sleep in between - no call that blocks inside the driver), so the GPU holds at most that many blocks of the post at any
 * time and the viewfinder's work is never more than that behind. A glFinish of the post (GLProg.computeManual) becomes the same
 * polled wait for a fence. The GPU runs the same commands in the same order: the output is bit for bit the same; the GPU keeps
 * one block queued while the thread waits for the one before, so it does not idle between blocks.
 * <p>
 * Active only on the post pipeline's thread between {@link #begin} and {@link #end} (GLBasePipeline.runNodes); by default on
 * Adreno only (the Mali logs of 31253 kept 27 - 31 fps with gaps of 62 - 102 ms through the post). scam_dev post_gpu_pace: 0 off,
 * N > 0 at most N blocks in flight on any GPU, absent: {@link #DEFAULT_IN_FLIGHT} on Adreno, off elsewhere.
 * <p>
 * P668 (owner, 2026-11-13: «видоискатель тормозит, оптимизируй под старые устройства»; Xiaomi 12X, Adreno 650, build 31266: «slow
 * draw 311 / 337 / 317 / 227 ms in node WhyCombine», the pace's longest wait 119 - 142 ms). Two blocks in flight bound the
 * viewfinder's wait by two blocks' GPU time, and a 256-row band of the tone's combine is ~80 - 140 ms on that GPU (~10 ms on the
 * Adreno 830 it was sized on). The pacer now measures each block it had to wait for (from the moment the block before it was
 * seen done, or its own submission if later: never more than its real GPU time) and remembers the GPU time per pixel of each
 * program; {@link GLProg#drawBlocks(int, int)} then draws that program's bands {@link #bandRows tall enough for} about
 * {@link #DEFAULT_BAND_MS} ms (multiples of 16 rows, at least 16, never above the 256 of before), from its next band and in every
 * later shot. A band is a viewport of the same full-screen draw: every pixel runs the same fragment shader on the same textures
 * and uniforms (gl_FragCoord is window-absolute; tools/check_post_bands.py), so the photo is bit for bit the same. Fast GPUs keep
 * their 256-row bands (measured, no device names); scam_dev post_band_ms (0: off). The final output's tiles are paced as well.
 */
public final class GpuPace {
    private static final String TAG = "SCAM_PIPELINE";
    /** Blocks of the post the GPU may hold: the one running and the next (no idle GPU between blocks). */
    public static final int DEFAULT_IN_FLIGHT = 2;
    /** A pending fence is polled this often (the sleep between two zero-timeout polls). */
    static final long POLL_NS = 250_000L;
    /** A fence not signalled after this long is given up (the GPU goes on; the thread no longer waits for it). */
    static final long GIVE_UP_NS = 3_000_000_000L;
    /** P668: the GPU time a band of a paced pass is sized to (scam_dev post_band_ms). */
    public static final float DEFAULT_BAND_MS = 10f;
    /** P668: band heights are multiples of this (2 x 2 pixel quads never straddle two bands' starts), and at least this. */
    static final int BAND_STEP = 16;

    /** The GL side, a fake on the host. */
    public interface Gpu {
        /** A fence after the commands so far (and their flush); 0 when the driver gives none. */
        long fence();
        /** Whether the fence is signalled (a poll that does not wait); true for a fence the driver lost (nothing to wait for). */
        boolean signalled(long fence);
        void delete(long fence);
        /** The driver's own finish (the fall-back when no fence is made). */
        void finish();
        /** Sleeps between two polls. */
        void pause(long ns);
        long nanoTime();
    }

    /** P668: a submitted block - its fence, the program it drew (null: not a band of one) and its pixels, when it went. */
    private static final class Block {
        final long fence;
        final String pass;
        final long pixels;
        final long submittedNs;

        Block(long fence, String pass, long pixels, long submittedNs) {
            this.fence = fence;
            this.pass = pass;
            this.pixels = pixels;
            this.submittedNs = submittedNs;
        }
    }

    /** P668: GPU ns per pixel of each program's bands, as measured (process-wide: the next shot starts with them). */
    private static final java.util.concurrent.ConcurrentHashMap<String, Float> NS_PER_PIXEL = new java.util.concurrent.ConcurrentHashMap<>();
    /** P668: the band sizing logged once per process (the first pass it narrowed). */
    private static final java.util.concurrent.atomic.AtomicBoolean BANDS_LOGGED = new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile String renderer = "";

    /** One pacer (one thread's post run). */
    public static final class Pacer {
        final Gpu gpu;
        final int inFlight;
        /** P668: the GPU time a band is sized to (ns); 0: bands as the caller asks. */
        final long bandNs;
        private final ArrayDeque<Block> pending = new ArrayDeque<>();
        long fences, waits, waitedNs, maxWaitNs, givenUp, finishes, finishNs;
        /** P668: when the last block was seen done (its fence signalled), 0 before any. */
        private long lastDoneNs;
        long measured;
        /** P668: the passes whose bands were narrowed this run: {smallest rows, bands, widest rows asked}. */
        private final java.util.LinkedHashMap<String, int[]> narrowed = new java.util.LinkedHashMap<>();

        public Pacer(Gpu gpu, int inFlight) {
            this(gpu, inFlight, 0f);
        }

        public Pacer(Gpu gpu, int inFlight, float bandMs) {
            this.gpu = gpu;
            this.inFlight = Math.max(1, inFlight);
            this.bandNs = bandMs > 0f ? (long) (bandMs * 1e6) : 0L;
        }

        /** A block was submitted: its fence, then the wait for the oldest beyond {@link #inFlight}. */
        public void submitted() {
            submitted(null, 0L);
        }

        /** P668: a band of {@code pixels} of the program {@code pass} was submitted (its GPU time is measured when waited for). */
        public void submitted(String pass, long pixels) {
            final long f = gpu.fence();
            if (f == 0) return; // no fence: nothing to pace by
            fences++;
            pending.addLast(new Block(f, pass, pixels, gpu.nanoTime()));
            while (pending.size() > inFlight) {
                final long t0 = gpu.nanoTime();
                final Block b = pending.pollFirst();
                final boolean waited = waitFor(b.fence);
                gpu.delete(b.fence);
                final long now = gpu.nanoTime();
                if (waited) {
                    final long ns = now - t0;
                    waits++;
                    waitedNs += ns;
                    maxWaitNs = Math.max(maxWaitNs, ns);
                    // P668: the block ran from when the one before it was seen done (or from its submission, if later) until now: a
                    // lower bound of its GPU time (the one before may have ended earlier than it was seen; this one is seen within a
                    // poll's POLL_NS), close when the thread waited for that one too. Only a block whose fence was not yet signalled
                    // tells anything.
                    if (b.pass != null && b.pixels > 0 && lastDoneNs > 0) {
                        final long ran = now - Math.max(lastDoneNs, b.submittedNs);
                        if (ran > 0) {
                            NS_PER_PIXEL.put(b.pass, (float) ran / b.pixels);
                            measured++;
                        }
                    }
                }
                lastDoneNs = now;
            }
        }

        /**
         * P668: the rows of the next band of {@code pass}, {@code width} wide: about {@link #bandNs} of GPU time by the pass's last
         * measured time per pixel, a multiple of {@link #BAND_STEP}, at least that, at most {@code maxRows} (as asked without a
         * measurement, without band sizing or when {@code maxRows} is below the step).
         */
        public int bandRows(String pass, int width, int maxRows) {
            if (bandNs <= 0 || pass == null || width <= 0 || maxRows <= BAND_STEP) return maxRows;
            final Float nsPerPixel = NS_PER_PIXEL.get(pass);
            if (nsPerPixel == null || !(nsPerPixel > 0f)) return maxRows;
            final double rows = bandNs / ((double) nsPerPixel * width);
            if (rows >= maxRows) return maxRows;
            final int r = Math.max(BAND_STEP, (int) rows / BAND_STEP * BAND_STEP);
            int[] n = narrowed.get(pass);
            if (n == null) {
                narrowed.put(pass, n = new int[]{r, 0, maxRows});
                if (BANDS_LOGGED.compareAndSet(false, true))
                    Log.i(TAG, String.format(Locale.ROOT, "post GPU bands: %s draws a %d-row band of %s %d px wide in %.0f ms (over the %.0f ms"
                                    + " aimed at): the paced post's bands are sized to %.0f ms of this GPU from now on, %d rows here (the viewfinder"
                                    + " waits behind at most %d of them; scam_dev post_band_ms 0: 256-row bands)",
                            renderer, maxRows, pass, width, nsPerPixel * width * maxRows / 1e6, bandNs / 1e6, bandNs / 1e6, r, inFlight));
            }
            n[0] = Math.min(n[0], r);
            n[1]++;
            return r;
        }

        /** Everything submitted so far is done (a glFinish's meaning) by polling a fence; the driver's finish without one. */
        public void finish() {
            final long t0 = gpu.nanoTime();
            final long f = gpu.fence();
            if (f == 0) {
                gpu.finish();
            } else {
                final long before = givenUp;
                waitFor(f);
                gpu.delete(f);
                if (givenUp != before) gpu.finish(); // a fence given up: the driver's finish, a finish stays a finish
            }
            while (!pending.isEmpty()) gpu.delete(pending.pollFirst().fence); // all older than the finish: done
            finishes++;
            final long now = gpu.nanoTime();
            finishNs += now - t0;
            lastDoneNs = now; // P668: the GPU is idle from here
        }

        /** The pending fences dropped (the run ends; their commands are queued and finish on their own). */
        void close() {
            while (!pending.isEmpty()) gpu.delete(pending.pollFirst().fence);
        }

        int pendingCount() {
            return pending.size();
        }

        /** True when the fence was not signalled at the first poll (the thread waited). */
        private boolean waitFor(long f) {
            if (gpu.signalled(f)) return false;
            final long start = gpu.nanoTime();
            while (!gpu.signalled(f)) {
                if (gpu.nanoTime() - start > GIVE_UP_NS) {
                    givenUp++;
                    break;
                }
                gpu.pause(POLL_NS);
            }
            return true;
        }

        String summary() {
            final StringBuilder bands = new StringBuilder();
            if (bandNs > 0) {
                bands.append(String.format(Locale.ROOT, "; bands of %.0f ms (%d measured)", bandNs / 1e6, measured));
                if (narrowed.isEmpty()) bands.append(": none narrowed");
                else {
                    bands.append(", narrowed:");
                    for (java.util.Map.Entry<String, int[]> e : narrowed.entrySet())
                        bands.append(' ').append(e.getKey()).append(' ').append(e.getValue()[2]).append("->").append(e.getValue()[0])
                                .append(" rows x").append(e.getValue()[1]);
                }
            }
            return String.format(Locale.ROOT, "post GPU pace: at most %d blocks in flight, %d fences, %d waits %.0f ms (max %.1f), %d finishes polled %.0f ms%s%s",
                    inFlight, fences, waits, waitedNs / 1e6, maxWaitNs / 1e6, finishes, finishNs / 1e6,
                    givenUp > 0 ? ", " + givenUp + " fences given up after " + GIVE_UP_NS / 1_000_000 + " ms" : "", bands);
        }
    }

    private static final ThreadLocal<Pacer> CURRENT = new ThreadLocal<>();

    private GpuPace() {}

    /**
     * The blocks in flight for the scam_dev value {@code setting} (NaN: absent) on a GPU named {@code renderer}: 0 = no pacing.
     */
    public static int inFlightFor(float setting, String renderer) {
        if (!Float.isNaN(setting)) return setting >= 1f ? Math.min(64, Math.round(setting)) : 0;
        return renderer != null && renderer.toLowerCase(Locale.ROOT).contains("adreno") ? DEFAULT_IN_FLIGHT : 0;
    }

    /** P668: the band time (ms) for the scam_dev value {@code setting} (NaN: absent): 0 = bands as asked. */
    public static float bandMsFor(float setting) {
        if (Float.isNaN(setting)) return DEFAULT_BAND_MS;
        return setting > 0f ? Math.min(1000f, Math.max(1f, setting)) : 0f;
    }

    /** P683: the first-run calibration's profile (GpuProfile) seeded into this process once. */
    private static final java.util.concurrent.atomic.AtomicBoolean SEEDED = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * P683: the GPU ns per pixel of programs measured by the first-run calibration (GpuProfile) as the starting values of the band
     * sizing - a program the post measured itself keeps its own value; the viewfinder's keys ("vf:") are not the post's.
     * Returns how many were taken.
     */
    static int seed(java.util.Map<String, Float> nsPerPixel) {
        int n = 0;
        if (nsPerPixel == null) return 0;
        for (java.util.Map.Entry<String, Float> e : nsPerPixel.entrySet()) {
            final Float v = e.getValue();
            if (e.getKey() == null || e.getKey().startsWith("vf:") || v == null || !(v > 0f) || Float.isInfinite(v)) continue;
            if (NS_PER_PIXEL.putIfAbsent(e.getKey(), v) == null) n++;
        }
        return n;
    }

    /** P668: the last measured GPU ns per pixel of {@code pass}, or null (tests). */
    static Float measuredNsPerPixel(String pass) {
        return pass == null ? null : NS_PER_PIXEL.get(pass);
    }

    /** P668: the measurements forgotten (tests). */
    static void forgetBands() {
        NS_PER_PIXEL.clear();
        SEEDED.set(false);
        BANDS_LOGGED.set(false);
    }

    /** The post run on this thread starts (its context current): pacing on as the setting and the GPU say. */
    public static void begin() {
        final Pacer old = CURRENT.get();
        if (old != null) old.close();
        CURRENT.remove();
        int n;
        float bandMs;
        try {
            final String gpu = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER);
            renderer = gpu != null ? gpu : "";
            n = inFlightFor(com.scamera.app.settings.PreferenceKeys.scamDevNumber("post_gpu_pace", Float.NaN), gpu);
            bandMs = bandMsFor(com.scamera.app.settings.PreferenceKeys.scamDevNumber("post_band_ms", Float.NaN));
        } catch (RuntimeException | LinkageError e) {
            n = 0;
            bandMs = 0f;
        }
        if (n > 0) CURRENT.set(new Pacer(GL, n, bandMs));
        // P683: the first shot of a process starts from the first-run calibration's ns per pixel (GpuProfile), not from 256 rows
        if (n > 0 && bandMs > 0f && SEEDED.compareAndSet(false, true)) {
            try {
                final int seeded = seed(GpuProfile.current());
                if (seeded > 0) Log.i(TAG, "post GPU bands: " + seeded + " programs start from the first-run GPU calibration " + GpuProfile.current());
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
    }

    /** The post run on this thread ends: the pace's line, pacing off. */
    public static void end() {
        final Pacer p = CURRENT.get();
        CURRENT.remove();
        if (p == null) return;
        p.close();
        Log.i(TAG, p.summary());
    }

    /** After a block's glFlush on the post's thread; nothing elsewhere. */
    public static void submitted() {
        final Pacer p = CURRENT.get();
        if (p != null) p.submitted();
    }

    /** P668: after the glFlush of a band of {@code pixels} drawn by the program {@code pass} (measured for its band height). */
    public static void submitted(String pass, long pixels) {
        final Pacer p = CURRENT.get();
        if (p != null) p.submitted(pass, pixels);
    }

    /** P668: the next band's rows for the program {@code pass} {@code width} wide - {@code maxRows} unless paced and measured slow. */
    public static int bandRows(String pass, int width, int maxRows) {
        final Pacer p = CURRENT.get();
        return p != null ? p.bandRows(pass, width, maxRows) : maxRows;
    }

    /** A block of a pass drawn without a flush of its own (a pass's row loop): flushed and paced while pacing is on, else nothing. */
    public static void block() {
        final Pacer p = CURRENT.get();
        if (p == null) return;
        android.opengl.GLES30.glFlush();
        p.submitted();
    }

    /** glFinish of the post: a polled fence while pacing is on, else glFinish. */
    public static void finish() {
        final Pacer p = CURRENT.get();
        if (p != null) p.finish();
        else android.opengl.GLES30.glFinish();
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    private static final Gpu GL = new Gpu() {
        @Override
        public long fence() {
            final long f = android.opengl.GLES30.glFenceSync(android.opengl.GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            android.opengl.GLES30.glFlush();
            return f;
        }

        @Override
        public boolean signalled(long fence) {
            final int r = android.opengl.GLES30.glClientWaitSync(fence, 0, 0L);
            return r != android.opengl.GLES30.GL_TIMEOUT_EXPIRED; // ALREADY_SIGNALED / CONDITION_SATISFIED; WAIT_FAILED: no wait
        }

        @Override
        public void delete(long fence) {
            android.opengl.GLES30.glDeleteSync(fence);
        }

        @Override
        public void finish() {
            android.opengl.GLES30.glFinish();
        }

        @Override
        public void pause(long ns) {
            java.util.concurrent.locks.LockSupport.parkNanos(ns);
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }
    };
}
