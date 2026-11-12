package com.particlesdevs.photoncamera.processing.opengl;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.os.Build;
import com.particlesdevs.photoncamera.util.Log;

import java.nio.ByteBuffer;

import static android.opengl.GLES20.GL_COLOR_ATTACHMENT0;
import static android.opengl.GLES20.GL_FRAMEBUFFER;
import static android.opengl.GLES20.GL_NO_ERROR;
import static android.opengl.GLES20.GL_RENDERBUFFER;
import static android.opengl.GLES20.glBindFramebuffer;
import static android.opengl.GLES20.glBindRenderbuffer;
import static android.opengl.GLES20.glFramebufferRenderbuffer;
import static android.opengl.GLES20.glGenFramebuffers;
import static android.opengl.GLES20.glGenRenderbuffers;
import static android.opengl.GLES20.glGetError;
import static android.opengl.GLES20.glRenderbufferStorage;
import static android.opengl.GLES30.GL_DRAW_FRAMEBUFFER;
import static android.opengl.GLES30.GL_RGBA8;
import static android.opengl.GLES30.glReadPixels;
import static android.opengl.GLES30.glViewport;

import com.particlesdevs.photoncamera.util.Allocator;

public class GLCoreBlockProcessing extends GLContext implements AutoCloseable {
    private static String TAG = "GLCoreBlockProcessing";
    public GLImage mOut = null;
    public Point shift = new Point(0,0);
    private final int mOutWidth, mOutHeight;
    public ByteBuffer mBlockBuffer;
    public ByteBuffer mOutBuffer;
    private final GLFormat mglFormat;
    /**
     * Full-frame readback buffer, allocated at the first full readback instead of with the context: during the
     * passes it was idle native memory (~200 MB at 50 MP RGBA8), and pipelines that never read the full frame
     * back (the Ultra HDR gain-map pass: ~400 MB FP16 at 50 MP) never allocate it at all. The allocation kind is
     * fixed here: {@link #allocation} is reassigned by the sized readback variants.
     */
    private final GLDrawParams.Allocate mOutAllocation;
    /** Bytes of the full-frame readback; above 2 GB (one Java buffer) the readback fails with a clear message when it is needed. */
    private final long mOutCapacity;

    public GLDrawParams.Allocate allocation = GLDrawParams.Allocate.Heap;
    /**
     * 10-bit HEIC: the tile target is RGB10_A2 and {@link #drawBlocksToBitmap()} returns an RGBA_1010102 bitmap
     * ({@link #drawBlocksToBitmap1010102()}). Off for every other use; cleared when the driver cannot render or read it.
     */
    private boolean tenBit;

    public static void checkEglError(String op) {
        int error = GLES30.glGetError();
        if (error != GLES30.GL_NO_ERROR) {
            String msg = op + ": glError: " + GLUtils.getEGLErrorString(error) + " (" + Integer.toHexString(error) + ")";
            String TAG = "GLCoreBlockProcessing";
            Log.v(TAG, msg);
        }
    }
    public GLCoreBlockProcessing(Point size, GLImage out, GLFormat glFormat, GLDrawParams.Allocate alloc) {
        this(size, glFormat,alloc);
        allocation = alloc;
        mOut = out;
    }

    /**
     * The post pipeline's output processing; {@code tenBitOutput} (Android 13+, an RGBA8 output format) renders the last
     * pass into an RGB10_A2 tile target and reads it back as RGBA_1010102 for the 10-bit HEIC. Same 4 bytes per pixel.
     */
    public GLCoreBlockProcessing(Point size, GLImage out, GLFormat glFormat, GLDrawParams.Allocate alloc, boolean tenBitOutput) {
        this(size, out, glFormat, alloc);
        if (tenBitOutput) enableTenBit();
    }

    /** P353: the post pipeline's processing on the context kept for the process (GLContext(w, h, true)). */
    public GLCoreBlockProcessing(Point size, GLImage out, GLFormat glFormat, GLDrawParams.Allocate alloc, boolean tenBitOutput, boolean shared) {
        super(size.x, GLDrawParams.TileSize, shared);
        allocation = alloc;
        mOutAllocation = alloc;
        mglFormat = glFormat;
        mOutWidth = size.x;
        mOutHeight = size.y;
        mBlockBuffer = ByteBuffer.allocateDirect(mOutWidth * GLDrawParams.TileSize * mglFormat.mFormat.mSize * mglFormat.mChannels);
        createTileTarget(glFormat);
        mOutCapacity = (long) mOutWidth * mOutHeight * mglFormat.mFormat.mSize * mglFormat.mChannels;
        mOut = out;
        if (tenBitOutput) enableTenBit();
    }
    public GLCoreBlockProcessing(Point size, GLImage out, GLFormat glFormat) {
        this(size, glFormat, GLDrawParams.Allocate.Direct);
        mOut = out;
    }
    public GLCoreBlockProcessing(Point size, GLFormat glFormat) {
        this(size,glFormat, GLDrawParams.Allocate.Direct);
    }
    public GLCoreBlockProcessing(Point size, GLFormat glFormat, GLDrawParams.Allocate alloc) {
        super(size.x, GLDrawParams.TileSize);
        allocation = alloc;
        mOutAllocation = alloc;
        mglFormat = glFormat;
        mOutWidth = size.x;
        mOutHeight = size.y;
        mBlockBuffer = ByteBuffer.allocateDirect(mOutWidth * GLDrawParams.TileSize * mglFormat.mFormat.mSize * mglFormat.mChannels);
        createTileTarget(glFormat);
        mOutCapacity = (long) mOutWidth * mOutHeight * mglFormat.mFormat.mSize * mglFormat.mChannels;
    }
    public GLCoreBlockProcessing(Point size, GLImage out, GLFormat glFormat,ByteBuffer output) {
        super(size.x, GLDrawParams.TileSize);
        output.position(0);
        mglFormat = glFormat;
        mOutWidth = size.x;
        mOutHeight = size.y;
        mBlockBuffer = ByteBuffer.allocateDirect(mOutWidth * GLDrawParams.TileSize * mglFormat.mFormat.mSize * mglFormat.mChannels);
        createTileTarget(glFormat);
        mOutBuffer = output;
        mOutAllocation = GLDrawParams.Allocate.None;
        // A null output was accepted before (texture-only scripts): keep that instead of failing here.
        mOutCapacity = output != null ? output.capacity() : 0;
        mOut = out;
    }

    /**
     * Render target of the readbacks: every readback draws one tile of at most TileSize rows at the origin
     * (viewport 0,0,w,tileRows) and reads it back, so the renderbuffer only needs those rows. A full-frame
     * renderbuffer was ~200 MB (RGBA8) / ~400 MB (FP16) of GPU memory at 50 MP for the whole pipeline.
     */
    private void createTileTarget(GLFormat glFormat) {
        final int rows = Math.max(1, Math.min(mOutHeight, GLDrawParams.TileSize));
        glGenFramebuffers(1,bindFB,0);
        glGenRenderbuffers(1,bindRB,0);
        glBindRenderbuffer(GL_RENDERBUFFER,bindRB[0]);
        glRenderbufferStorage(GL_RENDERBUFFER, glFormat.getGLFormatInternal(), mOutWidth, rows);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER,bindFB[0]);
        glFramebufferRenderbuffer(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, bindRB[0]);
    }

    /** Whether the readback gives the RGBA_1010102 bitmap of the 10-bit HEIC. */
    public boolean isTenBit() {
        return tenBit;
    }

    /**
     * Re-stores the tile target as RGB10_A2 (a colour-renderable format of GL ES 3.0 that glReadPixels returns as RGBA /
     * UNSIGNED_INT_2_10_10_10_REV: R in the low 10 bits, the layout of Bitmap.Config.RGBA_1010102). The output stays
     * RGBA8-sized (4 bytes per pixel). Falls back to the RGBA8 target when the framebuffer is not complete.
     */
    private void enableTenBit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || mglFormat.mChannels != 4 || mglFormat.mFormat.mSize != 1) {
            Log.w(TAG, "10-bit readback needs Android 13 and an RGBA8 output: 8-bit output");
            return;
        }
        final int rows = Math.max(1, Math.min(mOutHeight, GLDrawParams.TileSize));
        glBindRenderbuffer(GL_RENDERBUFFER, bindRB[0]);
        glRenderbufferStorage(GL_RENDERBUFFER, GLES30.GL_RGB10_A2, mOutWidth, rows);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, bindFB[0]);
        glFramebufferRenderbuffer(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, bindRB[0]);
        final int status = GLES30.glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
        final int error = glGetError();
        if (status == GLES30.GL_FRAMEBUFFER_COMPLETE && error == GL_NO_ERROR) {
            tenBit = true;
            Log.d(TAG, "10-bit output: RGB10_A2 tile target " + mOutWidth + "x" + rows);
        } else {
            Log.w(TAG, "RGB10_A2 target unusable (status 0x" + Integer.toHexString(status) + ", error 0x" + Integer.toHexString(error)
                    + "): 8-bit output");
            restoreRgba8Target();
        }
    }

    /** Back to the RGBA8 tile target of the output format (after a failed 10-bit setup or readback). */
    private void restoreRgba8Target() {
        tenBit = false;
        final int rows = Math.max(1, Math.min(mOutHeight, GLDrawParams.TileSize));
        glBindRenderbuffer(GL_RENDERBUFFER, bindRB[0]);
        glRenderbufferStorage(GL_RENDERBUFFER, mglFormat.getGLFormatInternal(), mOutWidth, rows);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, bindFB[0]);
        glFramebufferRenderbuffer(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, bindRB[0]);
    }

    /** Allocates the full-frame readback buffer on first use (see {@link #mOutAllocation}). */
    private void ensureOutBuffer() {
        if (mOutBuffer != null || mOutAllocation == GLDrawParams.Allocate.None) return;
        final int capacity = GLLimits.bufferBytes(mOutWidth, mOutHeight, (long) mglFormat.mFormat.mSize * mglFormat.mChannels, "full-frame readback");
        if (mOutAllocation == GLDrawParams.Allocate.Direct) mOutBuffer = Allocator.allocate(capacity);
        else {
            // Full-frame output on the Java heap scales with resolution
            // (~256 MB at 64 MP) and was a direct OOM source.
            // From RealJohnGalt/PhotonCamera 6d2291eb.
            mOutBuffer = ByteBuffer.allocateDirect(capacity);
        }
        if (mOutBuffer == null)
            throw new IllegalStateException("readback buffer allocation of " + mOutCapacity + " bytes failed");
    }

    public void drawBlocksToOutput() {
        ensureOutBuffer();
        glBindFramebuffer(GL_FRAMEBUFFER, bindFB[0]);
        GLProg program = super.mProgram;
        GLBlockDivider divider = new GLBlockDivider(mOutHeight, GLDrawParams.TileSize);
        int[] row = new int[2];
        mOutBuffer.position(0);
        mBlockBuffer.position(0);
        while (divider.nextBlock(row)) {
            int y = row[0];
            int height = row[1];
            glViewport(0, 0, mOutWidth, height);
            checkEglError("glViewport");
            program.setVar("yOffset", y);
            program.draw();
            checkEglError("program");
            mBlockBuffer.position(0);
            glReadPixels(0, 0, mOutWidth, height, mglFormat.getGLFormatExternal(), mglFormat.getGLType(), mBlockBuffer);
            checkEglError("glReadPixels");
            if (height < GLDrawParams.TileSize) {
                // This can only happen 2 times at edges
                byte[] data = new byte[mOutWidth * height * mglFormat.mFormat.mSize * mglFormat.mChannels];
                mBlockBuffer.get(data);
                mOutBuffer.put(data);
            } else {
                mOutBuffer.put(mBlockBuffer);
            }
        }
        mOutBuffer.position(0);
        mBlockBuffer = null;
        if (mOut != null) mOut.byteBuffer = mOutBuffer;
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }


    /** Rows per copy into the destination bitmap: a small strip bitmap instead of a full-frame buffer. */
    private static final int BITMAP_STRIP_ROWS = 16;

    /**
     * Renders the output program tile by tile straight into a new ARGB_8888 bitmap of the output size: the same
     * pixels as {@link #drawBlocksToOutput()} followed by {@code Bitmap.copyPixelsFromBuffer} (raw RGBA8 bytes,
     * copied with SRC), but without the full-frame readback buffer next to the bitmap (~200 MB at 50 MP). Only
     * for 4-channel 8-bit output formats.
     */
    /**
     * P483 (speed): a consumer of the final readback's mapped RGBA rows (tightly packed, width x height) in place of the bitmap -
     * the JPEG encode of a caller that only saves the photo. True: it took them, {@link #drawBlocksToBitmap} returns null and
     * {@link #sinkTook} is set; false: the bitmap is made from the same mapping as before.
     */
    public interface RgbaSink {
        boolean accept(ByteBuffer rgba, int width, int height);
    }

    /** P483: set by the caller before {@link #drawBlocksToBitmap} (the pixel-pack buffer path only); null: always a bitmap. */
    public RgbaSink rgbaSink;
    /** P483: the last {@link #drawBlocksToBitmap} gave its pixels to {@link #rgbaSink} (no bitmap). */
    public boolean sinkTook;
    /**
     * P642: the sink draws into the rows before it encodes them (the watermark of a streamed JPEG): the pixel-pack buffer is
     * mapped for reading and writing; {@link #sinkWritable} tells the sink whether that mapping was given (false: read only,
     * as before - the sink then leaves the rows to the bitmap).
     */
    public boolean sinkWrites;
    public boolean sinkWritable;

    public Bitmap drawBlocksToBitmap() {
        sinkTook = false;
        // P187: above 100 MP the textures the last nodes gave back are freed only once the GPU is done with them; the readback
        // waits for that GPU work anyway, so the 0.8 GB bitmap is made after their storage is back, not on top of it
        if ((long) mOutWidth * mOutHeight > 100_000_000L) GLES30.glFinish();
        // P440 (owner, 2026-10-27: «нам нужно иметь как минимум не хуже скорость обработки чем у Why камеры»), step 0c: where the
        // time between the last node and the bitmap goes, one SCAM_PIPELINE line ("final output ms"). scam_dev.txt "post_sync 1":
        // the earlier nodes' GPU work finished first ("pending") and a glFinish after every draw and readback, so render /
        // readback / copy are each their own; without it no glFinish is added and the draws' GPU time lands in the readback.
        final OutputTiming timing = new OutputTiming(PostGlMode.syncNodes());
        if (timing.sync) {
            GLES30.glFinish();
            timing.pending = timing.lap();
        }
        if (tenBit) return drawBlocksToBitmap1010102(timing);
        if (mglFormat.mChannels != 4 || mglFormat.mFormat.mSize != 1)
            throw new IllegalStateException("bitmap readback needs an RGBA8 output, not " + mglFormat.mFormat + "x" + mglFormat.mChannels);
        final int tileRows = GLDrawParams.TileSize;
        if (mBlockBuffer == null || mBlockBuffer.capacity() < mOutWidth * 4 * Math.min(tileRows, mOutHeight))
            throw new IllegalStateException("tile buffer does not match TileSize " + tileRows);
        String path = "tiles";
        if (pboReadbackWanted(mOutWidth, mOutHeight)) {
            final Bitmap direct = drawBlocksToBitmapPbo(timing);
            if (sinkTook) {
                timing.log("pbo -> jpeg (no bitmap)", mOutWidth, mOutHeight);
                return null;
            }
            if (direct != null) {
                timing.log("pbo", mOutWidth, mOutHeight);
                return direct;
            }
            timing.reset();
            path = "tiles (after the pixel-pack buffer failed)";
        }
        final int stripRows = tileRows % BITMAP_STRIP_ROWS == 0 ? BITMAP_STRIP_ROWS : tileRows;
        final Bitmap dst = Bitmap.createBitmap(mOutWidth, mOutHeight, Bitmap.Config.ARGB_8888);
        try (TileBlitter blitter = new TileBlitter(dst, stripRows)) {
            glBindFramebuffer(GL_FRAMEBUFFER, bindFB[0]);
            GLProg program = super.mProgram;
            GLBlockDivider divider = new GLBlockDivider(mOutHeight, tileRows);
            int[] row = new int[2];
            while (divider.nextBlock(row)) {
                int y = row[0];
                int height = row[1];
                glViewport(0, 0, mOutWidth, height);
                checkEglError("glViewport");
                program.setVar("yOffset", y);
                program.draw();
                checkEglError("program");
                timing.rendered();
                mBlockBuffer.clear();
                glReadPixels(0, 0, mOutWidth, height, mglFormat.getGLFormatExternal(), mglFormat.getGLType(), mBlockBuffer);
                checkEglError("glReadPixels");
                timing.readBack();
                blitter.blit(mBlockBuffer, y, height);
                timing.copied();
                timing.tiles++;
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            mBlockBuffer = null;
            timing.log(path, mOutWidth, mOutHeight);
            return dst;
        } catch (RuntimeException e) {
            dst.recycle();
            throw e;
        }
    }

    /**
     * P440 (step 0c): the final readback's times (ms): pending = the earlier nodes' GPU work (post_sync only), render = the final
     * pass's draws (post_sync only, else inside readback), readback = glReadPixels (the pixel-pack buffer: also its map, which
     * waits for the GPU's copies), copy = into the bitmap. One line per readback (SCAM_PIPELINE "final output ms"); only
     * System.nanoTime, plus the glFinish calls under post_sync.
     */
    static final class OutputTiming {
        final boolean sync;
        long pending, render, readback, copy;
        int tiles;
        private long last = System.nanoTime();
        private final long start = last;

        OutputTiming(boolean sync) {
            this.sync = sync;
        }

        long lap() {
            final long now = System.nanoTime(), d = now - last;
            last = now;
            return d;
        }

        void rendered() {
            if (!sync) return;
            GLES30.glFinish();
            render += lap();
        }

        void readBack() {
            if (sync) GLES30.glFinish();
            readback += lap();
        }

        void copied() {
            copy += lap();
        }

        /** A failed pixel-pack buffer attempt: its time stays in the total only. */
        void reset() {
            render = readback = copy = 0;
            tiles = 0;
            lap();
        }

        String line(String path, int w, int h) {
            return "final output ms: path=" + path + " " + w + "x" + h + " tiles=" + tiles
                    + (sync ? " pending=" + pending / 1_000_000 + " render=" + render / 1_000_000 : "")
                    + " readback=" + readback / 1_000_000 + " copy=" + copy / 1_000_000
                    + " total=" + (System.nanoTime() - start) / 1_000_000
                    + (sync ? " (post_sync: the GPU finished at every step)" : " (the draws' GPU time inside readback)");
        }

        void log(String path, int w, int h) {
            Log.i("SCAM_PIPELINE", line(path, w, h));
        }
    }

    /** P440: the pixel-pack buffer readback for an RGBA8 output up to this many pixels (12 MP: 48 MB of GPU memory for a moment). */
    static final long PBO_MAX_PIXELS = 16_000_000L;

    /**
     * P440: scam_dev.txt "post_pbo_readback" (on by default; 0: the tiles through the strip bitmap as before), up to 16 MP; never
     * in the old GL model of post_ab (PostGlMode.legacy) or its readback A/B (PostGlMode.tileReadback).
     */
    static boolean pboReadbackWanted(int width, int height) {
        if (width <= 0 || height <= 0 || (long) width * height > PBO_MAX_PIXELS) return false;
        if (PostGlMode.legacy() || PostGlMode.tileReadback()) return false;
        try {
            return com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("post_pbo_readback", true);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** P440: where the tile of rows {@code y..} starts in the frame's pixel-pack buffer (tightly packed RGBA8 rows). */
    static long pboOffset(int y, int width) {
        return (long) y * width * 4;
    }

    /** P440: GL errors left over (the nodes' log-only checks), cleared so the readback's own checks see only its own; bounded. */
    private static void drainGlErrors() {
        for (int i = 0; i < 32 && glGetError() != GL_NO_ERROR; i++) {
            // nothing: an error of an earlier call
        }
    }

    /**
     * P440 (S4): {@link #drawBlocksToBitmap} through one pixel-pack buffer of the whole frame: every tile is drawn and read back
     * into its rows of the buffer (the GPU copies them while it draws the next tile), the buffer is mapped once and copied into
     * the bitmap with copyPixelsFromBuffer - the full-frame buffer plus copyPixelsFromBuffer the strip path was proven equal to
     * (TileBlitterTest), without the tile buffer's synchronous read per tile, the strip bitmap and a drawBitmap per 16 rows.
     * The same bytes: glReadPixels writes the pixels it returns into a buffer object or into client memory alike (the same
     * format, type and pack state). Null when a GL call fails, the buffer cannot be mapped or its contents were lost while
     * mapped: the caller draws the tiles again the old way (the final pass is a pure function of its textures and uniforms).
     */
    private Bitmap drawBlocksToBitmapPbo(OutputTiming timing) {
        final long bytes = (long) mOutWidth * mOutHeight * 4;
        if (bytes > Integer.MAX_VALUE) return null;
        drainGlErrors();
        final int[] pbo = new int[1];
        GLES30.glGenBuffers(1, pbo, 0);
        if (pbo[0] == 0) return null;
        boolean mapped = false;
        Bitmap dst = null;
        try {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo[0]);
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, (int) bytes, null, GLES30.GL_STREAM_READ);
            if (glGetError() != GL_NO_ERROR) return null;
            glBindFramebuffer(GL_FRAMEBUFFER, bindFB[0]);
            final GLProg program = super.mProgram;
            final GLBlockDivider divider = new GLBlockDivider(mOutHeight, GLDrawParams.TileSize);
            final int[] row = new int[2];
            final boolean invalidate = PostGlMode.invalidate();
            while (divider.nextBlock(row)) {
                // P479: the tile's old rows are not needed (the rows drawn are the rows read back)
                if (invalidate) GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 1, new int[]{GLES30.GL_COLOR_ATTACHMENT0}, 0);
                glViewport(0, 0, mOutWidth, row[1]);
                program.setVar("yOffset", row[0]);
                program.draw();
                timing.rendered();
                GLES30.glReadPixels(0, 0, mOutWidth, row[1], mglFormat.getGLFormatExternal(), mglFormat.getGLType(),
                        (int) pboOffset(row[0], mOutWidth));
                timing.readBack();
                timing.tiles++;
            }
            if (glGetError() != GL_NO_ERROR) return null;
            final RgbaSink sink = rgbaSink;
            if (sink == null) try {
                dst = Bitmap.createBitmap(mOutWidth, mOutHeight, Bitmap.Config.ARGB_8888);
                com.particlesdevs.photoncamera.processing.JpegliEncoder.touchPixels(dst); // P473: while the GPU draws the tiles
            } catch (OutOfMemoryError pendingGpuStorage) {
                Log.w(TAG, "bitmap allocation postponed until queued GPU work finishes");
            }
            // P642: read / write for a sink that draws; a driver that refuses it gets the read-only mapping of before
            java.nio.Buffer view = null;
            sinkWritable = false;
            if (sink != null && sinkWrites) {
                view = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, (int) bytes, GLES30.GL_MAP_READ_BIT | GLES30.GL_MAP_WRITE_BIT);
                if (view != null && glGetError() == GL_NO_ERROR) {
                    sinkWritable = true;
                } else {
                    if (view != null) GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER);
                    drainGlErrors();
                    view = null;
                    Log.w(TAG, "final readback: no read / write mapping for the streamed JPEG's watermark, read only");
                }
            }
            if (view == null) view = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, (int) bytes, GLES30.GL_MAP_READ_BIT);
            mapped = view != null;
            if (!(view instanceof ByteBuffer) || view.capacity() < bytes || glGetError() != GL_NO_ERROR) return null;
            timing.readBack(); // the map waits for the GPU's copies
            final ByteBuffer src = (ByteBuffer) view;
            src.clear();
            src.limit((int) bytes);
            // P483: the rows straight to the sink (the JPEG encode) while mapped; a sink that fails leaves the bitmap path below
            if (sink != null && sink.accept(src, mOutWidth, mOutHeight)) {
                mapped = false;
                if (!GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)) {
                    Log.w(TAG, "final readback: the pixel-pack buffer was lost while mapped, the sink's result is dropped, the tiles are read again");
                    return null;
                }
                timing.copied();
                mBlockBuffer = null;
                sinkTook = true;
                return null;
            }
            if (dst == null) dst = Bitmap.createBitmap(mOutWidth, mOutHeight, Bitmap.Config.ARGB_8888);
            if (!com.particlesdevs.photoncamera.processing.JpegliEncoder.copyPixels(src, dst)) dst.copyPixelsFromBuffer(src);
            mapped = false;
            // GL_FALSE: the buffer's contents were lost while it was mapped (e.g. a display mode change): the copy is not used
            if (!GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)) {
                Log.w(TAG, "final readback: the pixel-pack buffer was lost while mapped, the tiles are read again");
                return null;
            }
            timing.copied();
            mBlockBuffer = null;
            final Bitmap out = dst;
            dst = null;
            return out;
        } catch (RuntimeException e) {
            Log.w(TAG, "final readback through a pixel-pack buffer failed (" + e + "), the tiles are read again");
            return null;
        } finally {
            if (dst != null) dst.recycle();
            if (mapped) GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER);
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
            GLES30.glDeleteBuffers(1, pbo, 0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            drainGlErrors(); // the fallback starts clean
        }
    }

    /**
     * {@link #drawBlocksToBitmap()} of the 10-bit HEIC: the same tiles rendered into the RGB10_A2 target, read back as
     * GL_UNSIGNED_INT_2_10_10_10_REV words and blitted into an RGBA_1010102 bitmap (the bytes of both are the same packed
     * 32-bit pixels). If the driver rejects the first 10-bit readback, the whole output is rendered again in 8 bits.
     */
    private Bitmap drawBlocksToBitmap1010102(OutputTiming timing) {
        final int tileRows = GLDrawParams.TileSize;
        if (mBlockBuffer == null || mBlockBuffer.capacity() < mOutWidth * 4 * Math.min(tileRows, mOutHeight))
            throw new IllegalStateException("tile buffer does not match TileSize " + tileRows);
        final int stripRows = tileRows % BITMAP_STRIP_ROWS == 0 ? BITMAP_STRIP_ROWS : tileRows;
        final Bitmap dst = Bitmap.createBitmap(mOutWidth, mOutHeight, Bitmap.Config.RGBA_1010102);
        boolean done = false;
        try (TileBlitter blitter = new TileBlitter(dst, stripRows)) {
            glBindFramebuffer(GL_FRAMEBUFFER, bindFB[0]);
            GLProg program = super.mProgram;
            GLBlockDivider divider = new GLBlockDivider(mOutHeight, tileRows);
            int[] row = new int[2];
            boolean first = true;
            while (divider.nextBlock(row)) {
                int y = row[0];
                int height = row[1];
                glViewport(0, 0, mOutWidth, height);
                checkEglError("glViewport");
                program.setVar("yOffset", y);
                program.draw();
                checkEglError("program");
                timing.rendered();
                mBlockBuffer.clear();
                glReadPixels(0, 0, mOutWidth, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_INT_2_10_10_10_REV, mBlockBuffer);
                timing.readBack();
                if (first) {
                    first = false;
                    final int error = glGetError();
                    if (error != GL_NO_ERROR) {
                        Log.w(TAG, "10-bit readback rejected (0x" + Integer.toHexString(error) + "): the output is rendered in 8 bits");
                        glBindFramebuffer(GL_FRAMEBUFFER, 0);
                        restoreRgba8Target();
                        dst.recycle();
                        return drawBlocksToBitmap();
                    }
                }
                blitter.blit(mBlockBuffer, y, height);
                timing.copied();
                timing.tiles++;
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            mBlockBuffer = null;
            done = true;
            timing.log("tiles 10-bit", mOutWidth, mOutHeight);
            return dst;
        } finally {
            if (!done && !dst.isRecycled()) dst.recycle();
        }
    }

    /**
     * Copies tightly packed RGBA8 rows (a read-back tile, row 0 first) into rows of a destination bitmap through
     * a small strip bitmap: raw bytes as {@code Bitmap.copyPixelsFromBuffer} takes them, blitted with SRC (no
     * blending, filtering or colour conversion between two sRGB ARGB_8888 bitmaps). The strip has the destination's
     * config: an RGBA_1010102 destination (10-bit HEIC) takes packed 2_10_10_10 words the same way.
     */
    static final class TileBlitter implements AutoCloseable {
        private final Bitmap strip;
        private final Canvas canvas;
        private final Paint copy = new Paint();
        private final Rect from = new Rect();
        private final Rect to = new Rect();
        private final int width;
        private final int rowBytes;
        private final int stripRows;
        private final int stripBytes;

        TileBlitter(Bitmap dst, int stripRows) {
            width = dst.getWidth();
            rowBytes = width * 4;
            this.stripRows = Math.max(1, Math.min(stripRows, dst.getHeight()));
            strip = Bitmap.createBitmap(width, this.stripRows, dst.getConfig());
            stripBytes = rowBytes * this.stripRows;
            canvas = new Canvas(dst);
            copy.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
            copy.setFilterBitmap(false);
            copy.setDither(false);
        }

        /**
         * Copies {@code height} rows of {@code tile} (from its start, whatever its position) to rows
         * {@code y .. y+height-1}. copyPixelsFromBuffer always takes a whole strip: the tile buffer must hold
         * whole strips (TileSize rows); bytes past the tile are stale and never drawn.
         */
        void blit(ByteBuffer tile, int y, int height) {
            final int capacity = tile.capacity();
            try {
                for (int r = 0; r < height; r += stripRows) {
                    final int rows = Math.min(stripRows, height - r);
                    final int start = r * rowBytes;
                    if (start + stripBytes > capacity)
                        throw new IllegalStateException("strip " + start + "+" + stripBytes + " outside the tile buffer " + capacity);
                    tile.limit(start + stripBytes);
                    tile.position(start);
                    strip.copyPixelsFromBuffer(tile);
                    tile.limit(capacity);
                    from.set(0, 0, width, rows);
                    to.set(0, y + r, width, y + r + rows);
                    canvas.drawBitmap(strip, from, to, copy);
                }
            } finally {
                tile.clear();
            }
        }

        @Override
        public void close() {
            strip.recycle();
        }
    }

    public ByteBuffer drawBlocksToOutput(Point size, GLFormat glFormat) {
        return drawBlocksToOutput(size,glFormat, GLDrawParams.Allocate.Heap);
    }

    public ByteBuffer drawBlocksToOutput(Point size, GLFormat glFormat,GLDrawParams.Allocate alloc) {
        ByteBuffer mOutBuffer;
        allocation = alloc;
        final int bytes = GLLimits.bufferBytes(size.x, size.y, (long) glFormat.mFormat.mSize * glFormat.mChannels, "readback");
        if(alloc == GLDrawParams.Allocate.Direct) mOutBuffer = Allocator.allocate(bytes);
        else
            mOutBuffer = ByteBuffer.allocateDirect(bytes);
        return drawBlocksToOutput(size,glFormat,mOutBuffer);
    }

    public ByteBuffer drawBlocksToOutput(Point size, GLFormat glFormat,ByteBuffer mOutBuffer) {
        glBindFramebuffer(GL_FRAMEBUFFER, bindFB[0]);
        checkEglError("glBindFramebuffer");
        GLProg program = super.mProgram;
        GLBlockDivider divider = new GLBlockDivider(size.y, GLDrawParams.TileSize);
        int[] row = new int[2];
        ByteBuffer mBlockBuffert = mBlockBuffer;
        mOutBuffer.position(0);
        mBlockBuffert.position(0);
        while (divider.nextBlock(row)) {
            int y = row[0];
            int height = row[1];
            glViewport(0, 0, size.x, height);
            checkEglError("glViewport");
            program.setVar("yOffset", y);
            program.draw();
            checkEglError("program");
            mBlockBuffert.position(0);
            glReadPixels(0, 0, size.x, height, glFormat.getGLFormatExternal(), glFormat.getGLType(), mBlockBuffert);
            checkEglError("glReadPixels");
            if (height < GLDrawParams.TileSize) {
                // This can only happen 2 times at edges
                byte[] data = new byte[size.x * height * glFormat.mFormat.mSize * glFormat.mChannels];
                mBlockBuffert.get(data);
                mOutBuffer.put(data);
            } else {
                int lim = mBlockBuffert.limit();
                mOutBuffer.put((ByteBuffer) mBlockBuffert.limit(size.x * GLDrawParams.TileSize * glFormat.mFormat.mSize * glFormat.mChannels));
                mBlockBuffert.limit(lim);
            }
        }
        mOutBuffer.position(0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return mOutBuffer;
    }

    @Override
    public void close() {
        // Ensure GPU work is complete before tearing down EGL state.
        try {
            GLES30.glFinish();
        } catch (Exception ignored) {}
        // P353: the tile target goes with the shot (a kept context would hold one per shot otherwise)
        try {
            makeCurrent();
            GLES30.glDeleteFramebuffers(1, bindFB, 0);
            GLES30.glDeleteRenderbuffers(1, bindRB, 0);
            // P638: gone - the deletes below are for a context that could not be made current here only (they ran again after
            // super.close() had released the context: «call to OpenGL ES API with no current context» in every shot's log)
            bindFB[0] = 0;
            bindRB[0] = 0;
        } catch (Exception ignored) {}
        try {
            super.close();
        } catch (Exception ignored) {}
        if (mOut != null) {
            try {
                mOut.close();
            } catch (Exception ignored) {}
            mOut = null;
        }
        if (mBlockBuffer != null) {
            try {
                mBlockBuffer.clear();
            } catch (Exception ignored) {}
            mBlockBuffer = null;
        }
        if (mOutBuffer != null) {
            try {
                if (allocation == GLDrawParams.Allocate.Direct) {
                    // Intentionally temporarily leaked: must hold the malloc
                } else {
                    mOutBuffer.clear();
                }
            } catch (Exception ignored) {}
            mOutBuffer = null;
        }
        // FBO/RBO are owned by this context; delete while context was current.
        // They are recreated per-pipeline, so stale IDs must not survive eglTerminate.
        try {
            if (bindFB[0] != 0) GLES30.glDeleteFramebuffers(1, bindFB, 0);
        } catch (Exception ignored) {}
        try {
            if (bindRB[0] != 0) GLES30.glDeleteRenderbuffers(1, bindRB, 0);
        } catch (Exception ignored) {}
    }
}
