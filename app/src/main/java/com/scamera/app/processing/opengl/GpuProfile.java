package com.scamera.app.processing.opengl;

import android.graphics.Point;
import android.opengl.GLES20;
import android.opengl.GLES30;

import com.scamera.app.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * P683 (owner: «в начальную компиляцию встроить то что поможет ускорить и оптимизировать обработку, работу приложения»): a short
 * GPU calibration in the first-run dialog (FirstRunCompile, after the programs are compiled). A few representative programs are
 * drawn over a {@link #SIZE} x {@link #SIZE} target from inputs of that size, each timed by GL_EXT_disjoint_timer_query (else by
 * glFinish), giving the GPU's ns per pixel of each. They are kept in the preferences under the first-run stamp (an install, an
 * update or a new system build measures again) and are the starting values of:
 * <ul>
 * <li>GpuPace's band sizing (P668: NS_PER_PIXEL of the post's programs, so a slow GPU's first shot draws ~10 ms bands from its
 * first band instead of a 256-row one first; the post's own measurements replace them as before);</li>
 * <li>the RAW viewfinder's tone size (P683 RawPreviewLoad: a GPU whose full-size tone would take more than
 * RawPreviewLoad.SEED_GPU_MS starts at ~1.1 MP; its windows bring it back to the full size when it keeps up).</li>
 * </ul>
 * The programs, their pixels and the photo stay the same: only how the work is cut and the viewfinder's starting size.
 */
public final class GpuProfile {
    private GpuProfile() {}

    private static final String TAG = "SCAM_PIPELINE";
    static final String PREFS = "scam_gpu_profile", KEY_STAMP = "stamp", KEY_NS = "ns_per_pixel";
    /** The viewfinder's tone (why/combine with the viewfinder's per-pixel defines): the key RawPreviewLoad's seed reads. */
    public static final String VIEWFINDER_TONE = "vf:why/combine";
    /** The side of the calibration's target and inputs. */
    static final int SIZE = 512;
    /** Draws timed per program (the one before them, its first use, is not). */
    static final int RUNS = 3;

    /** {name (GpuPace's pass: the asset; VIEWFINDER_TONE), asset, defines}. */
    static final Object[][] PROGRAMS = {
            {VIEWFINDER_TONE, "why/combine", new String[][]{{"POINT_FUSED", "1"}, {"MACRO_TOE", "1"}, {"TONE_OP", "1"}, {"YELLOW_HUE", "1"},
                    {"HL_NEUTRAL", "1"}, {"WHY_BASE", "1"}, {"AGX_INSET", "1"}, {"WHY_VIBRANCE", "1"}}},
            {"why/combine", "why/combine", new String[][]{{"DITHER_TPDF", "1"}, {"HL_LOCAL", "1"}, {"HL_LOCAL_SMOOTH", "1"}, {"RIM_SMOOTH", "1"},
                    {"EDGE_HOLD", "1"}, {"WHY_BASE", "1"}, {"WHY_VIBRANCE", "1"}, {"HL_LANDING", "1"}, {"MACRO_TOE", "1"}, {"TONE_OP", "1"}}},
            {"why/lpyr", "why/lpyr", new String[][]{{"MODE", "2"}}},
            {"why/sharp_r1", "why/sharp_r1", null},
    };

    /** Where the profile is kept (the app's preferences; a map in tests). */
    public interface Store {
        String get(String key);

        void put(Map<String, String> values);
    }

    // ---- the profile's text: "name=ns;name=ns" (ns per pixel, finite and positive)

    static String encode(Map<String, Float> nsPerPixel) {
        final StringBuilder s = new StringBuilder();
        for (Map.Entry<String, Float> e : nsPerPixel.entrySet()) {
            final Float v = e.getValue();
            if (v == null || !(v > 0f) || Float.isInfinite(v) || e.getKey().indexOf('=') >= 0 || e.getKey().indexOf(';') >= 0) continue;
            if (s.length() > 0) s.append(';');
            s.append(e.getKey()).append('=').append(String.format(Locale.ROOT, "%.4f", v));
        }
        return s.toString();
    }

    static Map<String, Float> decode(String text) {
        final Map<String, Float> out = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) return out;
        for (String item : text.split(";")) {
            final int eq = item.indexOf('=');
            if (eq <= 0) continue;
            try {
                final float v = Float.parseFloat(item.substring(eq + 1));
                if (v > 0f && !Float.isInfinite(v)) out.put(item.substring(0, eq), v);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** The profile measured under {@code stamp} into {@code store}. */
    static void save(Store store, String stamp, Map<String, Float> nsPerPixel) {
        final Map<String, String> v = new LinkedHashMap<>();
        v.put(KEY_STAMP, stamp);
        v.put(KEY_NS, encode(nsPerPixel));
        store.put(v);
    }

    /** The profile kept under {@code stamp}; empty when none was measured for it (another install or system build). */
    static Map<String, Float> load(Store store, String stamp) {
        if (store == null || stamp == null || !stamp.equals(store.get(KEY_STAMP))) return new LinkedHashMap<>();
        return decode(store.get(KEY_NS));
    }

    // ---- the profile of this process (loaded once; set by the calibration)

    private static volatile Map<String, Float> current;

    /** The profile of this install and system build (empty without one; never null). */
    public static Map<String, Float> current() {
        Map<String, Float> c = current;
        if (c != null) return c;
        try {
            final android.content.Context app = com.scamera.app.app.ScameraApp.getAppContext();
            c = load(prefs(app), com.scamera.app.ui.camera.FirstRunCompile.stamp(app));
        } catch (RuntimeException | LinkageError e) {
            c = new LinkedHashMap<>();
        }
        current = c;
        return c;
    }

    /** Tests and the calibration: the profile of this process. */
    static void setCurrent(Map<String, Float> profile) {
        current = profile;
    }

    /** The GPU ns per pixel of {@code name} in the profile, NaN without one. */
    public static double nsPerPixel(String name) {
        final Float v = current().get(name);
        return v == null ? Double.NaN : v;
    }

    static Store prefs(android.content.Context context) {
        final android.content.SharedPreferences p = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
        return new Store() {
            @Override
            public String get(String key) {
                return p.getString(key, null);
            }

            @Override
            public void put(Map<String, String> values) {
                final android.content.SharedPreferences.Editor e = p.edit();
                for (Map.Entry<String, String> v : values.entrySet()) e.putString(v.getKey(), v.getValue());
                e.apply();
            }
        };
    }

    // ---- the calibration (the first-run dialog's thread)

    /**
     * Measures {@link #PROGRAMS} in a context of its own, keeps the profile under {@code stamp} and makes it this process's;
     * {@code idle} false stops between programs. Returns the log text. Never throws.
     */
    public static String calibrate(android.content.Context context, String stamp, java.util.function.BooleanSupplier idle) {
        final long t0 = android.os.SystemClock.elapsedRealtime();
        final Map<String, Float> measured = new LinkedHashMap<>();
        GLContext gl = null;
        final StringBuilder log = new StringBuilder();
        boolean timer = false;
        try {
            gl = new GLContext(1, 1);
            final GLProg prog = gl.program;
            final String ext = GLES20.glGetString(GLES20.GL_EXTENSIONS);
            timer = ext != null && ext.contains("GL_EXT_disjoint_timer_query");
            final FloatBuffer pattern = ByteBuffer.allocateDirect(SIZE * SIZE * 16).order(ByteOrder.nativeOrder()).asFloatBuffer();
            for (int y = 0; y < SIZE; y++)
                for (int x = 0; x < SIZE; x++) {
                    // a smooth scene with texture: the programs' branches see shadows, mid tones and highlights
                    final float v = (float) (0.02 + 0.9 * x / SIZE + 0.05 * Math.sin(x * 0.37) * Math.cos(y * 0.29));
                    pattern.put(v).put(0.8f * v + 0.1f * y / SIZE).put(0.6f * v + 0.3f * (1f - (float) y / SIZE)).put(1f);
                }
            pattern.flip();
            final GLTexture input = new GLTexture(new Point(SIZE, SIZE), new GLFormat(GLFormat.DataType.FLOAT_16, 4), pattern,
                    GLES20.GL_LINEAR, GLES20.GL_CLAMP_TO_EDGE);
            final GLTexture target = new GLTexture(new Point(SIZE, SIZE), new GLFormat(GLFormat.DataType.FLOAT_16, 4), null,
                    GLES20.GL_NEAREST, GLES20.GL_CLAMP_TO_EDGE);
            try {
                for (Object[] p : PROGRAMS) {
                    if (idle != null && !idle.getAsBoolean()) break;
                    final double ns = time(prog, (String) p[1], (String[][]) p[2], input, target, timer);
                    final double perPixel = ns / ((double) SIZE * SIZE);
                    if (ns > 0 && Double.isFinite(ns)) measured.put((String) p[0], (float) perPixel);
                    log.append(log.length() > 0 ? ", " : "").append(p[0]).append(' ')
                            .append(ns > 0 ? String.format(Locale.ROOT, "%.2f ns/px", perPixel) : "failed");
                }
            } finally {
                input.close();
                target.close();
            }
        } catch (RuntimeException | LinkageError e) {
            log.append(log.length() > 0 ? "; " : "").append(e);
        } finally {
            if (gl != null) try { gl.close(); } catch (RuntimeException ignored) { }
        }
        if (!measured.isEmpty()) {
            try {
                save(prefs(context), stamp, measured);
            } catch (RuntimeException e) {
                log.append("; not kept: ").append(e);
            }
            setCurrent(measured);
            GpuPace.seed(measured);
        }
        final String text = "GPU calibration (" + (timer ? "timer queries" : "glFinish") + ", " + SIZE + "x" + SIZE + ") in "
                + (android.os.SystemClock.elapsedRealtime() - t0) + " ms: " + log;
        Log.i(TAG, text);
        return text;
    }

    private static final int TIME_ELAPSED_EXT = 0x88BF, GPU_DISJOINT_EXT = 0x8FBB;

    /** The least GPU time (ns) of {@link #RUNS} draws of the program over {@code target}; every sampler2D reads {@code input}. */
    private static double time(GLProg prog, String asset, String[][] defines, GLTexture input, GLTexture target, boolean timer) {
        try {
            if (defines != null) for (String[] d : defines) prog.setDefine(d[0], d[1]);
            prog.useAssetProgram(asset, false);
            final int[] id = new int[1];
            GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, id, 0);
            final int[] count = new int[1], size = new int[1], type = new int[1];
            GLES20.glGetProgramiv(id[0], GLES20.GL_ACTIVE_UNIFORMS, count, 0);
            for (int i = 0; i < count[0]; i++) {
                final String name = GLES20.glGetActiveUniform(id[0], i, size, 0, type, 0);
                if (type[0] == GLES20.GL_SAMPLER_2D && name != null) prog.setTexture(name.replace("[0]", ""), input);
            }
            GLES20.glGetError();
            prog.drawBlocks(target); // its first use (the driver's own work) is not timed
            GLES20.glFinish();
            double best = Double.MAX_VALUE;
            for (int r = 0; r < RUNS; r++) {
                if (timer) {
                    final int[] q = new int[1], disjoint = new int[1];
                    GLES20.glGetIntegerv(GPU_DISJOINT_EXT, disjoint, 0);
                    GLES30.glGenQueries(1, q, 0);
                    GLES30.glBeginQuery(TIME_ELAPSED_EXT, q[0]);
                    prog.drawBlocks(target);
                    GLES30.glEndQuery(TIME_ELAPSED_EXT);
                    GLES20.glFinish();
                    final int[] ns = new int[1];
                    GLES30.glGetQueryObjectuiv(q[0], GLES30.GL_QUERY_RESULT, ns, 0);
                    GLES20.glGetIntegerv(GPU_DISJOINT_EXT, disjoint, 0);
                    GLES30.glDeleteQueries(1, q, 0);
                    if (disjoint[0] == 0 && ns[0] != 0) best = Math.min(best, ns[0] & 0xffffffffL);
                } else {
                    final long t = System.nanoTime();
                    prog.drawBlocks(target);
                    GLES20.glFinish();
                    best = Math.min(best, System.nanoTime() - t);
                }
            }
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return -1;
            return best == Double.MAX_VALUE ? -1 : best;
        } catch (RuntimeException e) {
            Log.w(TAG, "GPU calibration of " + asset + ": " + e);
            return -1;
        }
    }
}
