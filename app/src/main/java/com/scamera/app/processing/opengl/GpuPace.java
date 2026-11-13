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
 */
public final class GpuPace {
    private static final String TAG = "SCAM_PIPELINE";
    /** Blocks of the post the GPU may hold: the one running and the next (no idle GPU between blocks). */
    public static final int DEFAULT_IN_FLIGHT = 2;
    /** A pending fence is polled this often (the sleep between two zero-timeout polls). */
    static final long POLL_NS = 250_000L;
    /** A fence not signalled after this long is given up (the GPU goes on; the thread no longer waits for it). */
    static final long GIVE_UP_NS = 3_000_000_000L;

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

    /** One pacer (one thread's post run). */
    public static final class Pacer {
        final Gpu gpu;
        final int inFlight;
        private final ArrayDeque<Long> pending = new ArrayDeque<>();
        long fences, waits, waitedNs, maxWaitNs, givenUp, finishes, finishNs;

        public Pacer(Gpu gpu, int inFlight) {
            this.gpu = gpu;
            this.inFlight = Math.max(1, inFlight);
        }

        /** A block was submitted: its fence, then the wait for the oldest beyond {@link #inFlight}. */
        public void submitted() {
            final long f = gpu.fence();
            if (f == 0) return; // no fence: nothing to pace by
            fences++;
            pending.addLast(f);
            while (pending.size() > inFlight) {
                final long t0 = gpu.nanoTime();
                final boolean waited = waitFor(pending.peekFirst());
                gpu.delete(pending.pollFirst());
                if (waited) {
                    final long ns = gpu.nanoTime() - t0;
                    waits++;
                    waitedNs += ns;
                    maxWaitNs = Math.max(maxWaitNs, ns);
                }
            }
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
            while (!pending.isEmpty()) gpu.delete(pending.pollFirst()); // all older than the finish: done
            finishes++;
            finishNs += gpu.nanoTime() - t0;
        }

        /** The pending fences dropped (the run ends; their commands are queued and finish on their own). */
        void close() {
            while (!pending.isEmpty()) gpu.delete(pending.pollFirst());
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
            return String.format(Locale.ROOT, "post GPU pace: at most %d blocks in flight, %d fences, %d waits %.0f ms (max %.1f), %d finishes polled %.0f ms%s",
                    inFlight, fences, waits, waitedNs / 1e6, maxWaitNs / 1e6, finishes, finishNs / 1e6,
                    givenUp > 0 ? ", " + givenUp + " fences given up after " + GIVE_UP_NS / 1_000_000 + " ms" : "");
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

    /** The post run on this thread starts (its context current): pacing on as the setting and the GPU say. */
    public static void begin() {
        final Pacer old = CURRENT.get();
        if (old != null) old.close();
        CURRENT.remove();
        int n;
        try {
            n = inFlightFor(com.scamera.app.settings.PreferenceKeys.scamDevNumber("post_gpu_pace", Float.NaN),
                    android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER));
        } catch (RuntimeException | LinkageError e) {
            n = 0;
        }
        if (n > 0) CURRENT.set(new Pacer(GL, n));
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
