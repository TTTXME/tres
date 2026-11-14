package com.scamera.app.ui.camera.views.viewfinder;

import android.opengl.GLES20;
import android.opengl.GLES30;

/**
 * P711 (owner, 2026-11-14: «добавь оптимизацию live viewfinder, он должен хорошо работать даже на Snapdragon 865»): the GPU time
 * of the RAW viewfinder's tone on the phone, for RawPreviewLoad's windows. The GL thread's own time of a draw (VF_RAW «draw») is
 * only how long it queued the passes: a GPU that is the bottleneck (an Adreno 650 at the full 1080 x 1440 tone) shows it only once
 * frames are lost. One GL_EXT_disjoint_timer_query (GL_TIME_ELAPSED_EXT) spans a draw of the RAW tone (the linear pass, the tone,
 * the filter / peaking pass); its result is read a few frames later when available (GL_QUERY_RESULT_AVAILABLE, never waited
 * for: no stall), dropped when the GPU says its timing was disjoint; only draws that toned a frame count (a redraw of the same
 * frame is the copy pass alone). {@link #RING} queries in flight at most: a draw with none free is not timed. No extension, an
 * error from the query calls, or scam_dev vf_gpu_timer 0: nothing timed (NaN; RawPreviewLoad decides by the draw and the frames
 * drawn as before). The time goes to VF_RAW «stages» as «gpu» too (VfStages.Stage.GPU, unless vf_stage_gpu's glFinish measures it).
 */
final class ToneGpuTimer {
    private static final int TIME_ELAPSED_EXT = 0x88BF, GPU_DISJOINT_EXT = 0x8FBB;
    static final int RING = 4;

    private int supported; // 0 unknown, 1 yes, -1 no
    private final int[] ids = new int[RING];
    private final boolean[] pending = new boolean[RING], toned = new boolean[RING];
    private int next;
    private boolean active;
    private final Mean mean = new Mean();

    /** The window's results: the mean of the toned draws' GPU times (pure Java). */
    static final class Mean {
        private double sumMs;
        private int count;

        void add(double ms) {
            if (!(ms >= 0) || Double.isInfinite(ms)) return;
            sumMs += ms;
            count++;
        }

        /** The mean in ms since the last take (NaN: none), and a new window. */
        double take() {
            final double m = count > 0 ? sumMs / count : Double.NaN;
            sumMs = 0;
            count = 0;
            return m;
        }
    }

    /** A new GL context: the queries went with the old one. */
    void onContextCreated() {
        supported = 0;
        java.util.Arrays.fill(ids, 0);
        java.util.Arrays.fill(pending, false);
        active = false;
        next = 0;
        mean.take();
    }

    private boolean on() {
        if (supported == 0) {
            final String ext = GLES20.glGetString(GLES20.GL_EXTENSIONS);
            supported = ext != null && ext.contains("GL_EXT_disjoint_timer_query") ? 1 : -1;
            if (supported > 0) {
                GLES30.glGenQueries(RING, ids, 0);
                final int[] d = new int[1];
                GLES20.glGetIntegerv(GPU_DISJOINT_EXT, d, 0); // clears the flag
                if (GLES20.glGetError() != GLES20.GL_NO_ERROR) supported = -1;
            }
        }
        return supported > 0 && com.scamera.app.settings.PreferenceKeys.scamDevSwitch("vf_gpu_timer", true);
    }

    /** Before the RAW tone's passes: a query begins when one is free (results of earlier ones read first). */
    void begin() {
        if (active) end(false); // a draw that threw between them: its query closed, not counted
        if (!on()) return;
        // an error another pass left is not this timer's (LiveRawRenderer.dropForeignErrors' case): this draw is not timed
        if (!clean()) return;
        poll();
        if (supported < 0) return;
        if (pending[next]) return;
        GLES30.glBeginQuery(TIME_ELAPSED_EXT, ids[next]);
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
            supported = -1;
            return;
        }
        active = true;
    }

    /** After them: {@code tonedFrame} whether the draw toned a frame (only those count). */
    void end(boolean tonedFrame) {
        if (!active) return;
        active = false;
        GLES30.glEndQuery(TIME_ELAPSED_EXT);
        pending[next] = true;
        toned[next] = tonedFrame;
        next = (next + 1) % RING;
    }

    /** The available results into the window (none waited for); a disjoint GPU drops every pending one. */
    private void poll() {
        final int[] v = new int[1];
        GLES20.glGetIntegerv(GPU_DISJOINT_EXT, v, 0);
        final boolean disjoint = v[0] != 0;
        for (int k = 1; k <= RING; k++) {
            final int i = (next + k) % RING; // oldest first
            if (!pending[i]) continue;
            GLES30.glGetQueryObjectuiv(ids[i], GLES30.GL_QUERY_RESULT_AVAILABLE, v, 0);
            if (v[0] == 0) break; // later ones finish later
            GLES30.glGetQueryObjectuiv(ids[i], GLES30.GL_QUERY_RESULT, v, 0);
            pending[i] = false;
            if (disjoint || !toned[i]) continue;
            final long ns = v[0] & 0xffffffffL;
            mean.add(ns / 1e6);
            if (!com.scamera.app.settings.PreferenceKeys.scamDevSwitch("vf_stage_gpu", false))
                com.scamera.app.processing.VfStages.note(com.scamera.app.processing.VfStages.Stage.GPU, ns);
        }
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) supported = -1;
    }

    private int foreign;

    /** No GL error pending (one that is pending is taken off and logged, at most 3 times: another pass's). */
    private boolean clean() {
        final int e = GLES20.glGetError();
        if (e == GLES20.GL_NO_ERROR) return true;
        if (foreign++ < 3) com.scamera.app.util.Log.w("VF_RAW", "GL error=" + e + " left by another pass before the tone's GPU timer (dropped)");
        return false;
    }

    /** The mean GPU time of the toned draws since the last call (ms; NaN: none measured). */
    double takeMeanMs() {
        return mean.take();
    }
}
