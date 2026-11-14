package com.scamera.app.processing.opengl;

import android.opengl.GLES30;
import android.opengl.GLES31;

import com.scamera.app.util.Log;

import java.util.ArrayList;

/**
 * P713 (speed, owner 2026-11-14: «encode while the GPU still works on later bands»): the post's last full-size pass (WhyCombine,
 * 82 - 131 ms of the GPU at 12 MP, 386 - 763 ms at 50 MP; ~300 ms on the Mali-G78 of the Pixel 6 Pro, all of it inside the final
 * readback) drawn band by band in step with the final output's tiles instead of all at once before them, so the first tiles reach
 * the JPEG encoder's threads while the GPU still draws the later bands (GLCoreBlockProcessing.drawBlocksStreamed).
 * <p>
 * The node draws through {@link GLProg#drawBlocksDeferrable}: armed by the pipeline ({@link #arm}), that call records the GL state
 * of the draw - the program, the textures of its units, its storage-buffer bindings, the target - instead of drawing. Each later
 * {@link #lines} call puts that state back and draws the lines (rows, or columns for a 90 / 270 degree photo) the next output tiles
 * read, as viewports of the same full-screen draw: every pixel runs the same fragment shader on the same textures and uniforms
 * (gl_FragCoord is window-absolute; tools/check_post_bands.py with column bands), so the image is bit for bit the one drawn at
 * once. A texture or buffer the node closes meanwhile is closed only after the last band ({@link #holdClose}, {@link #later}).
 * Not armed: the node draws at once, as before.
 */
public final class GLDeferredDraw {
    private static final String TAG = "GLDeferredDraw";
    private static final ThreadLocal<Boolean> ARMED = new ThreadLocal<>();
    private static final ThreadLocal<GLDeferredDraw> PENDING = new ThreadLocal<>();
    /**
     * Texture units looked at for a program's samplers (GLProg gives units 0, 2, 4 .. and 1 for the scene luma: 16 fragment samplers
     * reach unit 30), at most the context's combined units.
     */
    static final int UNITS = 48;
    private static int units;

    private static int units() {
        if (units == 0) {
            final int[] v = new int[1];
            GLES30.glGetIntegerv(GLES30.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, v, 0);
            units = Math.max(1, Math.min(UNITS, v[0] > 0 ? v[0] : 32));
        }
        return units;
    }
    /** Storage-buffer bindings kept (why/combine's rim tiles use 0). */
    static final int BUFFERS = 8;

    /** The GL state a fragment draw depends on that a later draw of another program changes. */
    static final class State {
        int program;
        final int[] tex2d = new int[UNITS], tex3d = new int[UNITS];
        final int[] ssbo = new int[BUFFERS];
        boolean ssboKnown;
        int activeUnit;
        int framebuffer;

        static State capture() {
            final State s = new State();
            final int[] v = new int[1];
            GLES30.glGetIntegerv(GLES30.GL_CURRENT_PROGRAM, v, 0);
            s.program = v[0];
            GLES30.glGetIntegerv(GLES30.GL_ACTIVE_TEXTURE, v, 0);
            s.activeUnit = v[0];
            final int n = units();
            for (int u = 0; u < n; u++) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + u);
                GLES30.glGetIntegerv(GLES30.GL_TEXTURE_BINDING_2D, v, 0);
                s.tex2d[u] = v[0];
                GLES30.glGetIntegerv(GLES30.GL_TEXTURE_BINDING_3D, v, 0);
                s.tex3d[u] = v[0];
            }
            GLES30.glActiveTexture(s.activeUnit);
            drain();
            for (int b = 0; b < BUFFERS; b++) {
                try {
                    GLES31.glGetIntegeri_v(GLES31.GL_SHADER_STORAGE_BUFFER_BINDING, b, v, 0);
                } catch (RuntimeException | LinkageError noEs31) {
                    break;
                }
                if (GLES30.glGetError() != GLES30.GL_NO_ERROR) break; // GL ES 3.0: no storage buffers (none used)
                s.ssbo[b] = v[0];
                s.ssboKnown = true;
            }
            GLES30.glGetIntegerv(GLES30.GL_DRAW_FRAMEBUFFER_BINDING, v, 0);
            s.framebuffer = v[0];
            return s;
        }

        void restore() {
            GLES30.glUseProgram(program);
            final int n = units();
            for (int u = 0; u < n; u++) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + u);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex2d[u]);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, tex3d[u]);
            }
            GLES30.glActiveTexture(activeUnit);
            if (ssboKnown) for (int b = 0; b < BUFFERS; b++) GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, b, ssbo[b]);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
        }

        boolean binds(int texture) {
            if (texture == 0) return false;
            for (int u = 0; u < UNITS; u++) if (tex2d[u] == texture || tex3d[u] == texture) return true;
            return false;
        }

        private static void drain() {
            for (int i = 0; i < 16 && GLES30.glGetError() != GLES30.GL_NO_ERROR; i++) {
                // an earlier call's error: the queries below decide alone
            }
        }
    }

    private final GLProg prog;
    private final State state;
    private final GLTexture target;
    private final String pass;
    private final int attribute;
    private final int width, height;
    /** Lines drawn so far: rows, or columns once a request asked for columns (then every later one does). */
    private boolean[] drawn;
    private boolean columns;
    private int drawnCount;
    private final ArrayList<GLTexture> held = new ArrayList<>();
    private final ArrayList<Runnable> laterRuns = new ArrayList<>();
    long bands, drawnPixels;

    private GLDeferredDraw(GLProg prog, State state, GLTexture target, String pass, int attribute) {
        this.prog = prog;
        this.state = state;
        this.target = target;
        this.pass = pass;
        this.attribute = attribute;
        this.width = target.size.x;
        this.height = target.size.y;
    }

    /** Arms (or disarms) the next {@link GLProg#drawBlocksDeferrable} on this thread. */
    public static void arm(boolean on) {
        if (on) ARMED.set(Boolean.TRUE);
        else ARMED.remove();
    }

    /** The draw recorded on this thread and not finished yet, or null. */
    public static GLDeferredDraw pending() {
        return PENDING.get();
    }

    /** P713: GLProg.drawBlocksDeferrable's question - true when the draw of the program in use into {@code target} was recorded. */
    static boolean capture(GLProg prog, GLTexture target) {
        if (ARMED.get() == null || PENDING.get() != null || target == null) return false;
        ARMED.remove();
        target.ensureFramebuffer();
        target.bindFramebuffer();
        final State s = State.capture();
        final GLDeferredDraw d = new GLDeferredDraw(prog, s, target, prog.activeName, prog.positionAttribute());
        PENDING.set(d);
        return true;
    }

    /**
     * Lines [{@code from}, {@code to}) of the target drawn where they are not yet (rows, or columns with {@code columnLines}), in
     * bands sized as GLProg.drawBlocks sizes them; the GL state of the caller is put back after.
     */
    public void lines(boolean columnLines, int from, int to) {
        if (drawn == null) {
            columns = columnLines;
            drawn = new boolean[columns ? width : height];
        } else if (columns != columnLines) {
            // another axis than before: everything left (rare: the tiles always ask along one axis)
            from = 0;
            to = drawn.length;
        }
        from = Math.max(0, from);
        to = Math.min(drawn.length, to);
        if (from >= to || drawnCount == drawn.length) return;
        final State caller = State.capture();
        try {
            state.restore();
            final int across = columns ? height : width;
            int i = from;
            while (i < to) {
                if (drawn[i]) { i++; continue; }
                int end = i;
                while (end < to && !drawn[end]) end++;
                for (int at = i; at < end; ) {
                    final int n = Math.min(end - at, GpuPace.bandRows(pass, across, GLDrawParams.TILE_ROWS));
                    if (columns) GLES30.glViewport(at, 0, n, height);
                    else GLES30.glViewport(0, at, width, n);
                    prog.drawQuad(attribute);
                    GLES30.glFlush();
                    GpuPace.submitted(pass, (long) across * n);
                    for (int k = at; k < at + n; k++) drawn[k] = true;
                    drawnCount += n;
                    bands++;
                    drawnPixels += (long) across * n;
                    at += n;
                }
                i = end;
            }
        } finally {
            caller.restore();
        }
    }

    /** Every line not drawn yet (before any other reader of the target), the caller's GL state put back. */
    public void drawAll() {
        lines(columns, 0, drawn == null ? height : drawn.length);
    }

    /** The draw is complete (or given up): the textures and buffers the node closed meanwhile are closed now. */
    public void release() {
        if (PENDING.get() == this) PENDING.remove();
        for (GLTexture t : held) {
            try {
                t.closeNow();
            } catch (RuntimeException e) {
                Log.w(TAG, "a held texture did not close: " + e);
            }
        }
        held.clear();
        for (Runnable r : laterRuns) {
            try {
                r.run();
            } catch (RuntimeException e) {
                Log.w(TAG, "a held release failed: " + e);
            }
        }
        laterRuns.clear();
    }

    private int[] tileMapping;

    /**
     * P713: RotateWatermark's mapping of the output rows onto this draw's target ({@link #sourceLines}' arguments: quarter turns,
     * mirror, the texture's size, the rows skipped), given when the final pass reads this target; null until then.
     */
    public void mapTiles(GLTexture input, int rotate, boolean mirror, int skipped) {
        if (input != target) return;
        tileMapping = new int[]{rotate, mirror ? 1 : 0, width, height, skipped};
    }

    /** {@link #mapTiles}' mapping, or null (the final pass reads another texture: the draw is completed in full first). */
    public int[] tileMapping() {
        return tileMapping;
    }

    /** Whether every line of the target is drawn. */
    public boolean complete() {
        return drawn != null && drawnCount == drawn.length;
    }

    public String describe() {
        return pass + " " + width + "x" + height + " in " + bands + " " + (columns ? "column" : "row") + " bands beside the output tiles";
    }

    /** GLTexture.close's question: true when the texture is one the recorded draw reads or writes (it is closed after the draw). */
    static boolean holdClose(GLTexture texture) {
        final GLDeferredDraw d = PENDING.get();
        if (d == null || texture == null) return false;
        if (texture != d.target && !d.state.binds(texture.id)) return false;
        if (!d.held.contains(texture)) d.held.add(texture);
        return true;
    }

    /**
     * {@code release} now, or after the recorded draw when one is pending on this thread (a raw GL texture or buffer the draw may
     * read: the 3D LUT, the rim tiles' storage buffer).
     */
    public static void later(Runnable release) {
        final GLDeferredDraw d = PENDING.get();
        if (d == null) release.run();
        else d.laterRuns.add(release);
    }

    /** Drops what this thread recorded without drawing it (a failed run): its held textures and buffers are closed. */
    public static void discard() {
        ARMED.remove();
        final GLDeferredDraw d = PENDING.get();
        if (d != null) d.release();
    }

    /**
     * The lines of the post's last texture the output rows [{@code y0}, {@code y1}) of RotateWatermark/addwatermark_rotate read:
     * {from, to, 1 when they are columns}. Mirrors the shader's index arithmetic (rotate 0 / 2: rows, 1 / 3: columns; mirror
     * flips the other axis; {@code skipped} = rawSize.y - cropSize.y); clamped to the texture as texelFetch's indices are.
     */
    public static int[] sourceLines(int rotate, boolean mirror, int y0, int y1, int texW, int texH, int skipped) {
        final int lastX = texW - 1, lastY = texH - 1;
        int a, b;
        boolean cols;
        if (rotate == 1) {
            cols = true;
            a = lastX - (y1 - 1);
            b = lastX - y0;
        } else if (rotate == 2) {
            cols = false;
            if (mirror) {
                a = lastY - (lastY - y0);
                b = lastY - (lastY - (y1 - 1));
            } else {
                a = lastY - (y1 - 1);
                b = lastY - y0;
            }
        } else if (rotate == 3) {
            cols = true;
            a = y0;
            b = y1 - 1;
        } else {
            cols = false;
            final int p0 = y0 + skipped, p1 = y1 - 1 + skipped;
            if (mirror) {
                a = lastY - p1;
                b = lastY - p0;
            } else {
                a = p0;
                b = p1;
            }
        }
        final int last = cols ? lastX : lastY;
        a = Math.max(0, Math.min(last, a));
        b = Math.max(0, Math.min(last, b));
        return new int[]{Math.min(a, b), Math.max(a, b) + 1, cols ? 1 : 0};
    }
}
