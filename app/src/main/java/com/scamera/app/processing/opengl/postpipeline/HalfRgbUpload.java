package com.scamera.app.processing.opengl.postpipeline;

import android.graphics.Point;
import android.opengl.GLES31;

import androidx.annotation.Nullable;

import com.scamera.app.processing.opengl.GLFormat;
import com.scamera.app.processing.opengl.GLProg;
import com.scamera.app.processing.opengl.GLTexture;
import com.scamera.app.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_NEAREST;

/**
 * P145: the worker's half-float RGB into ScamRgb's RGBA16F input on the GPU - each band of rows copied unchanged into a
 * shader storage buffer and widened to RGBA by the compute shader scamhdr/halfrgba - instead of the CPU repack of every
 * pixel (ScamRgb.uploadHalfRgba: 3 -> 4 halves a pixel on up to 8 threads, then the driver's copy of 8 B a pixel).
 * <p>
 * The texture must hold the very halves the worker wrote, as the CPU repack does. GLES lets a driver flush 16-bit denormals
 * when it converts, so once per process all 65536 half patterns go through the pass and are read back; only a driver that
 * keeps every one (NaN: any NaN) gets this path, the others keep the CPU repack.
 */
final class HalfRgbUpload {
    private HalfRgbUpload() {}

    /** 1 = the GPU pass keeps every half on this driver, -1 = it does not, 0 = not checked yet. */
    private static volatile int state;
    /** Band of rows per buffer upload (about 16 MB of halves). */
    private static final int BAND_BYTES = 16 << 20;
    /** P602: the last upload's split for ScamRgb's «scamrgb input ms» line (the processing thread's; "" when none). */
    private static String lastNote = "";
    private static long copyNs;
    private static int bands;

    /** P602: the split of the last {@link #upload} ("" when it gave no texture), once. */
    static String takeNote() {
        final String n = lastNote;
        lastNote = "";
        return n;
    }

    /** The input texture (alpha 1.0) or null when this driver does not keep the halves or a GL call failed. */
    @Nullable
    static GLTexture upload(GLProg prog, Point size, ByteBuffer rgb) {
        lastNote = "";
        if (!exact(prog)) return null;
        GLTexture tex = null;
        try {
            final long t0 = System.nanoTime();
            tex = new GLTexture(size, new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
            final long storage = System.nanoTime() - t0;
            // P482 (ahb_transport): the worker's halves in the app's hardware buffer - the pass reads them there (no upload); refused
            // (no extension, a GL error): the bands uploaded from the CPU view below, the same bytes
            final int imported = imported(rgb);
            lastNote = String.format(java.util.Locale.ROOT, ": storage %d ms, hardware buffer read in place", storage / 1_000_000);
            if (imported != 0 && runImported(prog, tex, size.x, size.y, rgb, imported)) return tex;
            final boolean fromView = isAhbView(rgb);
            copyNs = 0;
            bands = 0;
            final long t1 = System.nanoTime();
            if (!run(prog, tex, size.x, size.y, rgb)) {
                if (fromView) GLES31.glFinish();
                tex.close();
                return null;
            }
            final long pass = System.nanoTime() - t1;
            // P482: the driver may still read the uploaded bands from the view's mapping on the GPU (a hardware buffer's pages
            // are not pinned like the memfd's): done before the view may be released
            if (fromView) GLES31.glFinish();
            // P602 (Pixel 7 log of 31249: «upload=180 (half rgb, gpu repack)» on the GL thread, nothing about where): the texture's
            // storage, the bands' copies into the driver (glBufferSubData: a wait for the GPU shows here) and the rest of the pass
            lastNote = String.format(java.util.Locale.ROOT, ": storage %d ms, %d bands copied in %d ms%s, pass %d ms%s", storage / 1_000_000,
                    bands, copyNs / 1_000_000, lastMapped ? " (mapped, " + COPY.threads() + " threads)" : "", (pass - copyNs) / 1_000_000,
                    fromView ? " + finish" : "");
            return tex;
        } catch (RuntimeException e) {
            Log.w("SCAM_PIPELINE", "half RGB GPU upload failed: " + e + ", CPU repack");
            if (tex != null) tex.close();
            return null;
        }
    }

    /** The bands of {@code rgb} (w * h * 3 halves) through scamhdr/halfrgba into {@code tex}; false on a GL error. */
    static boolean run(GLProg prog, GLTexture tex, int w, int h, ByteBuffer rgb) {
        while (GLES31.glGetError() != GLES31.GL_NO_ERROR) {
            // drain: only the calls below decide
        }
        final int rowBytes = w * 6;
        final int band = Math.max(1, Math.min(h, BAND_BYTES / rowBytes));
        final int capacity = (band * rowBytes + 3) & ~3;
        final int[] buffers = new int[2];
        final long[] fences = new long[2];
        GLES31.glGenBuffers(2, buffers, 0);
        try {
            for (int b : buffers) {
                GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, b);
                GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, capacity, null, GLES31.GL_STREAM_DRAW);
            }
            prog.setLayout(16, 16, 1);
            prog.useAssetProgram("scamhdr/halfrgba", true);
            GLES31.glBindImageTexture(0, tex.id, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES31.GL_RGBA16F);
            final ByteBuffer src = rgb.duplicate().order(ByteOrder.nativeOrder());
            boolean mapped = mappedCopy();
            for (int y0 = 0, k = 0; y0 < h; y0 += band, k++) {
                final int rows = Math.min(band, h - y0);
                src.limit(src.capacity());
                src.position(y0 * rowBytes);
                src.limit(y0 * rowBytes + rows * rowBytes);
                // two buffers: the driver need not wait for the pass on the previous band before it takes the next one
                GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[k & 1]);
                final long c0 = System.nanoTime();
                // P662 (item 7): the band copied into the mapped buffer on four threads (the bytes glBufferSubData copies on this one)
                if (!mapped || !copyMapped(src, rows * rowBytes, fences, k & 1)) {
                    mapped = false;
                    GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, rows * rowBytes, src);
                }
                copyNs += System.nanoTime() - c0; // P602
                bands++;
                GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, buffers[k & 1]);
                prog.setVar("bandU", w, y0);
                prog.setVar("rowsU", rows);
                GLES31.glDispatchCompute((w + 15) / 16, (rows + 15) / 16, 1);
                // P662: the next copy into this buffer (band k + 2) waits for this pass only (the map is unsynchronized)
                if (mapped) fences[k & 1] = GLES31.glFenceSync(GLES31.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            }
            lastMapped = mapped;
            GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT | GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                    | GLES31.GL_FRAMEBUFFER_BARRIER_BIT | GLES31.GL_PIXEL_BUFFER_BARRIER_BIT);
            GLES31.glBindImageTexture(0, 0, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES31.GL_RGBA16F);
            prog.closed = true;
            int error = GLES31.glGetError();
            if (error != GLES31.GL_NO_ERROR) {
                Log.w("SCAM_PIPELINE", "half RGB GPU upload: GL error 0x" + Integer.toHexString(error) + ", CPU repack");
                return false;
            }
            return true;
        } finally {
            for (int i = 0; i < 2; i++) if (fences[i] != 0) GLES31.glDeleteSync(fences[i]);
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
            GLES31.glDeleteBuffers(2, buffers, 0);
        }
    }

    /** P662 (item 7): scam_dev post_half_mapped (on by default): the bands through a mapped buffer on four threads; 0: glBufferSubData. */
    static boolean mappedCopy() {
        try {
            return com.scamera.app.settings.PreferenceKeys.scamDevSwitch("post_half_mapped", true);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** P662: the copy threads (the caller and three helpers of their own: the processing thread waits for them). */
    private static final com.scamera.app.util.ParallelWork.Own COPY = new com.scamera.app.util.ParallelWork.Own("SCAMERA-half-copy", 4);
    /** P662: pieces of a band (16-byte aligned starts), at least this big each. */
    static final int PIECE_BYTES = 1 << 20;
    private static boolean lastMapped;

    /**
     * P662: {@code bytes} of {@code src} (from its position) into the bound storage buffer at 0 through an unsynchronized write
     * map - after the fence of the pass that read this buffer last ({@code fences[slot]}: two bands before) - copied in pieces on
     * {@link #COPY}. False when the buffer cannot be mapped or its contents were lost (the caller copies with glBufferSubData).
     */
    static boolean copyMapped(ByteBuffer src, int bytes, long[] fences, int slot) {
        if (fences[slot] != 0) {
            GLES31.glClientWaitSync(fences[slot], GLES31.GL_SYNC_FLUSH_COMMANDS_BIT, 2_000_000_000L);
            GLES31.glDeleteSync(fences[slot]);
            fences[slot] = 0;
        }
        final java.nio.Buffer view = GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, bytes,
                GLES31.GL_MAP_WRITE_BIT | GLES31.GL_MAP_INVALIDATE_RANGE_BIT | GLES31.GL_MAP_UNSYNCHRONIZED_BIT);
        if (!(view instanceof ByteBuffer) || view.capacity() < bytes) {
            if (view != null) GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER);
            while (GLES31.glGetError() != GLES31.GL_NO_ERROR) {
                // the map's error is not the pass's
            }
            return false;
        }
        try {
            copyPieces(src, (ByteBuffer) view, pieces(bytes, COPY.threads(), PIECE_BYTES), COPY);
        } catch (RuntimeException e) {
            GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER);
            return false;
        }
        return GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER);
    }

    /** P662: bytes cuts[i] .. cuts[i + 1] of {@code src} (from its position) to the same offsets of {@code to}, the pieces on {@code pool}. */
    static void copyPieces(ByteBuffer src, ByteBuffer to, int[] cuts, com.scamera.app.util.ParallelWork.Own pool) {
        final int base = src.position();
        pool.forEach(cuts.length - 1, i -> {
            final ByteBuffer from = src.duplicate();
            from.limit(base + cuts[i + 1]).position(base + cuts[i]);
            final ByteBuffer into = to.duplicate();
            into.limit(cuts[i + 1]).position(cuts[i]);
            into.put(from);
        });
    }

    /**
     * P662: the cuts {0, .., bytes} of {@code bytes} into at most {@code threads} pieces of at least {@code minPiece} bytes (but
     * one), every inner cut a multiple of 16; together they cover every byte once.
     */
    static int[] pieces(int bytes, int threads, int minPiece) {
        final int n = Math.max(1, Math.min(Math.max(1, threads), bytes / Math.max(16, minPiece)));
        final int[] cuts = new int[n + 1];
        for (int i = 1; i < n; i++) cuts[i] = (int) ((long) bytes * i / n) & ~15;
        cuts[n] = bytes;
        return cuts;
    }

    private static boolean isAhbView(ByteBuffer rgb) {
        try {
            return com.scamera.app.util.HardwareRgb.isView(rgb);
        } catch (LinkageError e) {
            return false;
        }
    }

    /** P482: the GL buffer of {@code rgb} when it is a view of the app's hardware buffer (HardwareRgb), else 0. */
    private static int imported(ByteBuffer rgb) {
        try {
            if (!com.scamera.app.util.HardwareRgb.isView(rgb)) return 0;
            final int name = com.scamera.app.util.HardwareRgb.importGl(rgb);
            if (name == 0) Log.w("SCAM_PIPELINE", "half RGB hardware buffer: not imported, uploaded from its CPU view");
            return name;
        } catch (LinkageError e) {
            return 0;
        }
    }

    /**
     * P482: the bands of the imported buffer {@code buffer} (w * h * 3 halves; deleted here) through scamhdr/halfrgba into
     * {@code tex}, each band a range of it at the storage buffer offset alignment; then a fence the view's free waits for. False
     * on a GL error.
     */
    static boolean runImported(GLProg prog, GLTexture tex, int w, int h, ByteBuffer view, int buffer) {
        while (GLES31.glGetError() != GLES31.GL_NO_ERROR) {
            // drain: only the calls below decide
        }
        final int rowBytes = w * 6;
        final int[] align = new int[1];
        GLES31.glGetIntegerv(GLES31.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT, align, 0);
        final int a = Math.max(4, align[0]);
        final int step = a / gcd(rowBytes, a); // rows per aligned band offset
        final int band = Math.max(step, Math.max(1, Math.min(h, BAND_BYTES / rowBytes)) / step * step);
        try {
            prog.setLayout(16, 16, 1);
            prog.useAssetProgram("scamhdr/halfrgba", true);
            GLES31.glBindImageTexture(0, tex.id, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES31.GL_RGBA16F);
            for (int y0 = 0; y0 < h; y0 += band) {
                final int rows = Math.min(band, h - y0);
                GLES31.glBindBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 1, buffer, y0 * rowBytes, rows * rowBytes);
                prog.setVar("bandU", w, y0);
                prog.setVar("rowsU", rows);
                GLES31.glDispatchCompute((w + 15) / 16, (rows + 15) / 16, 1);
            }
            GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT | GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT
                    | GLES31.GL_FRAMEBUFFER_BARRIER_BIT | GLES31.GL_PIXEL_BUFFER_BARRIER_BIT);
            GLES31.glBindImageTexture(0, 0, 0, false, 0, GLES31.GL_WRITE_ONLY, GLES31.GL_RGBA16F);
            prog.closed = true;
            final int error = GLES31.glGetError();
            if (error != GLES31.GL_NO_ERROR) {
                Log.w("SCAM_PIPELINE", "half RGB hardware buffer: GL error 0x" + Integer.toHexString(error) + ", uploaded instead");
                GLES31.glFinish(); // (the view may go once the fallback is done: nothing of this pass reads it then)
                return false;
            }
            if (!com.scamera.app.util.HardwareRgb.fence(view)) GLES31.glFinish();
            return true;
        } finally {
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, 0);
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0);
            GLES31.glDeleteBuffers(1, new int[]{buffer}, 0);
        }
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            final int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    /** P662: whether {@link #exact} has run in this process (the idle warm-up runs it first: PostChecks). */
    static boolean checked() {
        return state != 0;
    }

    /** Whether the pass keeps every half on this driver (checked once per process, logged). */
    static boolean exact(GLProg prog) {
        final int s = state;
        if (s != 0) return s > 0;
        final long started = System.nanoTime();
        int mismatch;
        String failure = null;
        try {
            mismatch = probe(prog);
        } catch (RuntimeException | OutOfMemoryError e) {
            mismatch = 0x10000;
            failure = e.toString();
        }
        state = mismatch < 0 ? 1 : -1;
        Log.i("SCAM_PIPELINE", "half RGB GPU upload check: " + (mismatch < 0 ? "exact for all 65536 halves"
                : failure != null ? "failed (" + failure + "), CPU repack kept"
                : mismatch > 0xffff ? "GL error, CPU repack kept"
                : "half 0x" + Integer.toHexString(mismatch) + " differs, CPU repack kept")
                + " ms=" + (System.nanoTime() - started) / 1_000_000);
        return mismatch < 0;
    }

    /** All 65536 halves (as the RGB of 128 x 171 pixels) through the pass, read back; the first one lost, -1 for none. */
    private static int probe(GLProg prog) {
        final int w = 128, h = (65536 + 3 * w - 1) / (3 * w);
        final ByteBuffer halves = ByteBuffer.allocateDirect(w * h * 6).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 65536; i++) halves.putShort(i * 2, (short) i);
        GLTexture tex = new GLTexture(new Point(w, h), new GLFormat(GLFormat.DataType.FLOAT_16, 4), null, GL_NEAREST, GL_CLAMP_TO_EDGE);
        final int[] framebuffer = new int[1], oldRead = new int[1];
        try {
            if (!run(prog, tex, w, h, halves)) return 0x10000;
            GLES31.glGetIntegerv(GLES31.GL_READ_FRAMEBUFFER_BINDING, oldRead, 0);
            GLES31.glGenFramebuffers(1, framebuffer, 0);
            GLES31.glBindFramebuffer(GLES31.GL_READ_FRAMEBUFFER, framebuffer[0]);
            GLES31.glFramebufferTexture2D(GLES31.GL_READ_FRAMEBUFFER, GLES31.GL_COLOR_ATTACHMENT0, GLES31.GL_TEXTURE_2D, tex.id, 0);
            final int halfType = WhyStats.halfReadType();
            final ByteBuffer back = ByteBuffer.allocateDirect(w * h * (halfType != 0 ? 8 : 16)).order(ByteOrder.nativeOrder());
            GLES31.glReadPixels(0, 0, w, h, GLES31.GL_RGBA, halfType != 0 ? halfType : GLES31.GL_FLOAT, back);
            if (GLES31.glGetError() != GLES31.GL_NO_ERROR) return 0x10000;
            return firstLost(back, halfType != 0, w * h);
        } finally {
            GLES31.glBindFramebuffer(GLES31.GL_READ_FRAMEBUFFER, oldRead[0]);
            if (framebuffer[0] != 0) GLES31.glDeleteFramebuffers(1, framebuffer, 0);
            tex.close();
        }
    }

    /**
     * The first half pattern h (0..65535, at RGB index h) the read-back does not give back - as halves the pattern itself, as
     * floats its exact widening; a NaN only has to stay a NaN - or -1 when none; alpha must be 1.0. Pixels past the patterns
     * hold zeros.
     */
    static int firstLost(ByteBuffer back, boolean halves, int pixels) {
        final float[] table = HalfFloat.TABLE;
        for (int p = 0; p < pixels; p++) {
            for (int c = 0; c < 4; c++) {
                final int h = c < 3 ? p * 3 + c : -1;
                final int want = c == 3 ? 0x3C00 : h < 65536 ? h : 0;
                final int at = p * 4 + c;
                final float got = halves ? table[back.getShort(at * 2) & 0xffff] : back.getFloat(at * 4);
                final boolean ok;
                if (Float.isNaN(table[want])) ok = Float.isNaN(got);
                else if (halves) ok = (back.getShort(at * 2) & 0xffff) == want;
                else ok = Float.floatToRawIntBits(got) == Float.floatToRawIntBits(table[want]);
                if (!ok) return c == 3 ? 0x3C00 : want;
            }
        }
        return -1;
    }
}
