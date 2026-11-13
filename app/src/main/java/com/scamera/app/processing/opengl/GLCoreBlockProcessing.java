package com.scamera.app.processing.opengl;

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

import com.scamera.app.processing.JpegliEncoder;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.util.Log;

import java.nio.Buffer;
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
import static android.opengl.GLES30.glReadPixels;
import static android.opengl.GLES30.glViewport;

/**
 * The processing context of a pipeline with its output: the last pass is drawn tile by tile (at most
 * {@link GLDrawParams#TILE_ROWS} rows at the origin of a small renderbuffer) and read back - into a bitmap
 * ({@link #drawBlocksToBitmap}), through one pixel-pack buffer of the frame (P440) or into a sink that encodes the rows
 * straight away (P483). The renderbuffer only holds one tile: a full-frame one was ~200 MB (RGBA8) / ~400 MB (FP16) of GPU
 * memory at 50 MP for the whole run.
 */
public class GLCoreBlockProcessing extends GLContext implements AutoCloseable {
    private static final String TAG = "GLCoreBlockProcessing";
    /** Rows per copy into the destination bitmap: a small strip bitmap instead of a full-frame buffer. */
    private static final int BITMAP_STRIP_ROWS = 16;
    /** P440: the pixel-pack buffer read-back for an output up to this many pixels (64 MB of GPU memory for a moment). */
    static final long PBO_MAX_PIXELS = 16_000_000L;

    /** The image the output was made for (closed with this processing). */
    public GLImage outImage = null;
    /** One tile of read-back rows (null after the last read-back). */
    public ByteBuffer tileBuffer;
    /** The output's size and its format. */
    private final int outW, outH;
    private final GLFormat outFormat;
    /**
     * 10-bit HEIC: the tile target is RGB10_A2 and {@link #drawBlocksToBitmap()} returns an RGBA_1010102 bitmap. Off for every
     * other use; cleared when the driver cannot render or read it.
     */
    private boolean tenBit;

    /**
     * P483 (speed): a consumer of the final read-back's mapped RGBA rows (tightly packed, width x height) in place of the
     * bitmap - the JPEG encode of a caller that only saves the photo. True: it took them, {@link #drawBlocksToBitmap} returns
     * null and {@link #sinkTook} is set; false: the bitmap is made from the same mapping.
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
     * mapped for reading and writing; {@link #sinkWritable} tells the sink whether it got that mapping (false: read only -
     * the sink then leaves the rows to the bitmap).
     */
    public boolean sinkWrites;
    public boolean sinkWritable;

    /** Logs (verbose) a pending GL error of {@code op}; it is cleared either way. */
    public static void checkEglError(String op) {
        final int error = GLES30.glGetError();
        if (error == GLES30.GL_NO_ERROR) return;
        Log.v(TAG, op + ": glError: " + GLUtils.getEGLErrorString(error) + " (" + Integer.toHexString(error) + ")");
    }

    /** A processing context of its own, for an output of {@code size} in {@code format}. */
    public GLCoreBlockProcessing(Point size, GLFormat format, GLDrawParams.Allocate allocation) {
        super(size.x, GLDrawParams.TILE_ROWS);
        outFormat = format;
        outW = size.x;
        outH = size.y;
        tileBuffer = newTileBuffer();
        createTileTarget();
    }

    public GLCoreBlockProcessing(Point size, GLImage out, GLFormat format, GLDrawParams.Allocate allocation) {
        this(size, format, allocation);
        outImage = out;
    }

    /**
     * P353: the post pipeline's processing, on the context kept for the process when {@code shared} (GLContext(w, h, true)).
     * {@code tenBitOutput} (Android 13+, an RGBA8 output format): the last pass goes into an RGB10_A2 tile target and is read
     * back as RGBA_1010102 for the 10-bit HEIC - the same 4 bytes per pixel.
     */
    public GLCoreBlockProcessing(Point size, GLImage out, GLFormat format, GLDrawParams.Allocate allocation, boolean tenBitOutput,
                                 boolean shared) {
        super(size.x, GLDrawParams.TILE_ROWS, shared);
        outFormat = format;
        outW = size.x;
        outH = size.y;
        tileBuffer = newTileBuffer();
        createTileTarget();
        outImage = out;
        if (tenBitOutput) enableTenBit();
    }

    private ByteBuffer newTileBuffer() {
        return ByteBuffer.allocateDirect(outW * GLDrawParams.TILE_ROWS * outFormat.mFormat.mSize * outFormat.mChannels);
    }

    private int tileRows() {
        return Math.max(1, Math.min(outH, GLDrawParams.TILE_ROWS));
    }

    private void createTileTarget() {
        glGenFramebuffers(1, tileFramebuffer, 0);
        glGenRenderbuffers(1, tileRenderbuffer, 0);
        storeTileTarget(outFormat.getGLFormatInternal());
    }

    /** (Re)stores the tile renderbuffer in {@code internalFormat} and attaches it to the tile framebuffer (draw target). */
    private void storeTileTarget(int internalFormat) {
        glBindRenderbuffer(GL_RENDERBUFFER, tileRenderbuffer[0]);
        glRenderbufferStorage(GL_RENDERBUFFER, internalFormat, outW, tileRows());
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, tileFramebuffer[0]);
        glFramebufferRenderbuffer(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, tileRenderbuffer[0]);
    }

    /** Whether the read-back gives the RGBA_1010102 bitmap of the 10-bit HEIC. */
    public boolean isTenBit() {
        return tenBit;
    }

    private boolean rgba8Output() {
        return outFormat.mChannels == 4 && outFormat.mFormat.mSize == 1;
    }

    /**
     * The tile target as RGB10_A2 (colour-renderable in GL ES 3.0; glReadPixels returns it as RGBA /
     * UNSIGNED_INT_2_10_10_10_REV, R in the low 10 bits: the layout of Bitmap.Config.RGBA_1010102). Back to the output's own
     * format when the framebuffer is not complete.
     */
    private void enableTenBit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !rgba8Output()) {
            Log.w(TAG, "10-bit readback needs Android 13 and an RGBA8 output: 8-bit output");
            return;
        }
        storeTileTarget(GLES30.GL_RGB10_A2);
        final int status = GLES30.glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
        final int error = glGetError();
        if (status == GLES30.GL_FRAMEBUFFER_COMPLETE && error == GL_NO_ERROR) {
            tenBit = true;
            Log.d(TAG, "10-bit output: RGB10_A2 tile target " + outW + "x" + tileRows());
            return;
        }
        Log.w(TAG, "RGB10_A2 target unusable (status 0x" + Integer.toHexString(status) + ", error 0x" + Integer.toHexString(error)
                + "): 8-bit output");
        restoreOutputTarget();
    }

    /** Back to the tile target of the output format (after a failed 10-bit setup or read-back). */
    private void restoreOutputTarget() {
        tenBit = false;
        storeTileTarget(outFormat.getGLFormatInternal());
    }

    private void requireTileBuffer() {
        final int rows = GLDrawParams.TILE_ROWS;
        if (tileBuffer == null || tileBuffer.capacity() < outW * 4 * Math.min(rows, outH))
            throw new IllegalStateException("tile buffer does not match TILE_ROWS " + rows);
    }

    /** One tile of the output program at the origin of the tile target (rows y .. y + rows - 1 of the frame). */
    private void drawTile(GLProg program, int y, int rows, OutputTiming timing) {
        program.setVar("yOffset", y); // the tile's first row in the frame
        glViewport(0, 0, outW, rows);
        checkEglError("tile viewport");
        program.draw();
        checkEglError("tile draw");
        timing.rendered();
    }

    /**
     * Renders the output program tile by tile straight into a new bitmap of the output size (ARGB_8888; RGBA_1010102 for the
     * 10-bit HEIC): the pixels a full-frame read-back and {@code Bitmap.copyPixelsFromBuffer} would give, without the
     * full-frame buffer next to the bitmap (~200 MB at 50 MP). Up to 16 MP through one pixel-pack buffer (P440), else through
     * the tile buffer and a strip bitmap. Only for 4-channel 8-bit output formats. Null when {@link #rgbaSink} took the rows.
     */
    public Bitmap drawBlocksToBitmap() {
        sinkTook = false;
        // P187: above 100 MP the textures the last nodes gave back are freed only once the GPU is done with them; the read-back
        // waits for that GPU work anyway, so the 0.8 GB bitmap is made after their storage is back, not on top of it
        if ((long) outW * outH > 100_000_000L) GLES30.glFinish();
        // P440 step 0c: where the time between the last node and the bitmap goes (SCAM_PIPELINE "final output ms"); with
        // scam_dev post_sync 1 the earlier nodes' GPU work is finished first and every step is its own
        final OutputTiming timing = new OutputTiming(PostGlMode.syncNodes());
        if (timing.sync) {
            GLES30.glFinish();
            timing.pending = timing.lap();
        }
        if (tenBit) return drawBlocksToBitmap1010102(timing);
        if (!rgba8Output())
            throw new IllegalStateException("bitmap readback needs an RGBA8 output, not " + outFormat.mFormat + "x" + outFormat.mChannels);
        requireTileBuffer();
        String path = "tiles";
        if (pboReadbackWanted(outW, outH)) {
            final Bitmap direct = drawBlocksToBitmapPbo(timing);
            if (sinkTook) {
                timing.log("pbo -> jpeg (no bitmap)", outW, outH);
                return null;
            }
            if (direct != null) {
                timing.log("pbo", outW, outH);
                return direct;
            }
            timing.reset();
            path = "tiles (after the pixel-pack buffer failed)";
        }
        // P662 (item 4): above 16 MP the tiles through a ring of pixel-pack buffers, each mapped once the next tile's read-back
        // is queued, its rows copied into the bitmap in one native copy (the same bytes as the strip bitmap below)
        if (ringReadbackWanted(outW, outH)) {
            final Bitmap ringed = drawBlocksToBitmapRing(timing);
            if (ringed != null) {
                timing.log("tiles ring", outW, outH);
                return ringed;
            }
            timing.reset();
            path = "tiles (after the pixel-pack ring failed)";
        }
        final Bitmap dst = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        try (TileBlitter blitter = new TileBlitter(dst, stripRows())) {
            glBindFramebuffer(GL_FRAMEBUFFER, tileFramebuffer[0]);
            final GLBlockDivider tiles = new GLBlockDivider(outH, GLDrawParams.TILE_ROWS);
            final int[] tile = new int[2];
            while (tiles.nextBlock(tile)) {
                drawTile(program, tile[0], tile[1], timing);
                tileBuffer.clear();
                glReadPixels(0, 0, outW, tile[1], outFormat.getGLFormatExternal(), outFormat.getGLType(), tileBuffer);
                checkEglError("glReadPixels");
                timing.readBack();
                blitter.blit(tileBuffer, tile[0], tile[1]);
                timing.copied();
                timing.tiles++;
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            tileBuffer = null;
            timing.log(path, outW, outH);
            return dst;
        } catch (RuntimeException e) {
            dst.recycle();
            throw e;
        }
    }

    private static int stripRows() {
        return GLDrawParams.TILE_ROWS % BITMAP_STRIP_ROWS == 0 ? BITMAP_STRIP_ROWS : GLDrawParams.TILE_ROWS;
    }

    /**
     * P440 (step 0c): the final read-back's times (ms): pending = the earlier nodes' GPU work (post_sync only), render = the
     * final pass's draws (post_sync only, else inside readback), readback = glReadPixels (the pixel-pack buffer: also its map,
     * which waits for the GPU's copies), copy = into the bitmap. One line per read-back; only System.nanoTime, plus the
     * glFinish calls under post_sync.
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
            final long now = System.nanoTime(), elapsed = now - last;
            last = now;
            return elapsed;
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

    /**
     * P440: scam_dev.txt "post_pbo_readback" (on by default; 0: the tiles through the strip bitmap), up to 16 MP; never in the
     * old GL model of post_ab (PostGlMode.legacy) or its read-back A/B (PostGlMode.tileReadback).
     */
    static boolean pboReadbackWanted(int width, int height) {
        if (width <= 0 || height <= 0 || (long) width * height > PBO_MAX_PIXELS) return false;
        if (PostGlMode.legacy() || PostGlMode.tileReadback()) return false;
        try {
            return PreferenceKeys.scamDevSwitch("post_pbo_readback", true);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * P662 (item 4): scam_dev post_pbo_ring (on by default): the tiles above PBO_MAX_PIXELS through a ring of pixel-pack buffers
     * (drawBlocksToBitmapRing); never in post_ab's old model or its read-back A/B, nor without the native row copy. 0: as before.
     */
    static boolean ringReadbackWanted(int width, int height) {
        if (width <= 0 || height <= 0 || (long) width * height <= PBO_MAX_PIXELS) return false;
        if (PostGlMode.legacy() || PostGlMode.tileReadback()) return false;
        try {
            return PreferenceKeys.scamDevSwitch("post_pbo_ring", true) && com.scamera.app.processing.JpegliEncoder.available();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** P662: buffers in the ring - three, two above 100 MP (a 200 MP tile is 16 MB). */
    static int ringSize(long pixels) {
        return pixels > 100_000_000L ? 2 : 3;
    }

    /**
     * P662: the ring's order for {@code tiles} tiles and {@code ring} buffers - the steps {tile, 0} draw tile and queue its
     * read-back into buffer tile % ring, {tile, 1} map that buffer and copy its rows; a tile is mapped only once the read-back of
     * the next {@code ring - 1} tiles is queued (or there are none), and a buffer is read into again only after its tile was copied.
     */
    static java.util.List<int[]> ringSteps(int tiles, int ring) {
        final java.util.ArrayList<int[]> steps = new java.util.ArrayList<>(tiles * 2);
        final int lag = Math.max(1, ring) - 1;
        int copied = 0;
        for (int k = 0; k < tiles; k++) {
            steps.add(new int[]{k, 0});
            while (copied <= k - lag) steps.add(new int[]{copied++, 1});
        }
        while (copied < tiles) steps.add(new int[]{copied++, 1});
        return steps;
    }

    /**
     * P662 (item 4): {@link #drawBlocksToBitmap} above 16 MP. The strip path read each tile back synchronously into the tile
     * buffer, copied it into a strip bitmap and drew that into the frame (50 MP 220 - 857 ms, 200 MP 789 ms of "final output"):
     * here each tile's read-back goes into a buffer of a ring (ringSteps), the GPU draws and reads the next tiles while a mapped
     * one is copied into the bitmap's rows (JpegliEncoder.copyRows: the same bytes, TileBlitterTest). Null when a GL call fails,
     * a buffer cannot be mapped or lost its contents, or the copy is refused: the caller draws the tiles again.
     */
    private Bitmap drawBlocksToBitmapRing(OutputTiming timing) {
        final long tileBytes = (long) outW * GLDrawParams.TILE_ROWS * 4;
        if (tileBytes > Integer.MAX_VALUE) return null;
        final java.util.ArrayList<int[]> tileList = new java.util.ArrayList<>();
        final GLBlockDivider divider = new GLBlockDivider(outH, GLDrawParams.TILE_ROWS);
        for (int[] t = new int[2]; divider.nextBlock(t); ) tileList.add(t.clone());
        final int ring = Math.min(ringSize((long) outW * outH), Math.max(1, tileList.size()));
        drainGlErrors();
        final int[] pbo = new int[ring];
        GLES30.glGenBuffers(ring, pbo, 0);
        for (int b : pbo) if (b == 0) { GLES30.glDeleteBuffers(ring, pbo, 0); return null; }
        boolean mapped = false;
        Bitmap dst = null;
        try {
            for (int b : pbo) {
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, b);
                GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, (int) tileBytes, null, GLES30.GL_STREAM_READ);
            }
            if (glGetError() != GL_NO_ERROR) return null;
            glBindFramebuffer(GL_FRAMEBUFFER, tileFramebuffer[0]);
            final boolean invalidate = PostGlMode.invalidate();
            final int[] colour = {GLES30.GL_COLOR_ATTACHMENT0};
            for (int[] step : ringSteps(tileList.size(), ring)) {
                final int[] tile = tileList.get(step[0]);
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo[step[0] % ring]);
                final int bytes = outW * tile[1] * 4;
                if (step[1] == 0) {
                    // the tile's old rows are not needed (P479: the rows drawn are the rows read back)
                    if (invalidate) GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 1, colour, 0);
                    glViewport(0, 0, outW, tile[1]);
                    program.setVar("yOffset", tile[0]);
                    program.draw();
                    timing.rendered();
                    GLES30.glReadPixels(0, 0, outW, tile[1], outFormat.getGLFormatExternal(), outFormat.getGLType(), 0);
                    timing.readBack();
                    timing.tiles++;
                    if (dst == null) {
                        // made while the GPU draws the first tiles, its pages faulted in on four threads (P473)
                        dst = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                        com.scamera.app.processing.JpegliEncoder.touchPixels(dst);
                    }
                    continue;
                }
                final Buffer view = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, bytes, GLES30.GL_MAP_READ_BIT);
                mapped = view != null;
                if (!(view instanceof ByteBuffer) || view.capacity() < bytes || glGetError() != GL_NO_ERROR) return null;
                timing.readBack(); // the map waits for the GPU's copy of this tile
                final ByteBuffer src = (ByteBuffer) view;
                src.clear();
                if (dst == null || !com.scamera.app.processing.JpegliEncoder.copyRows(src, dst, tile[0], tile[1], 4)) return null;
                mapped = false;
                // false: the buffer's contents were lost while it was mapped: the rows copied are not used
                if (!GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)) {
                    Log.w(TAG, "final readback: a ring buffer was lost while mapped, the tiles are read again");
                    return null;
                }
                timing.copied();
            }
            if (glGetError() != GL_NO_ERROR) return null;
            tileBuffer = null;
            final Bitmap out = dst;
            dst = null;
            return out;
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "final readback through the pixel-pack ring failed (" + e + "), the tiles are read again");
            return null;
        } finally {
            if (dst != null) dst.recycle();
            if (mapped) GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER);
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
            GLES30.glDeleteBuffers(ring, pbo, 0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            drainGlErrors(); // the fallback starts clean
        }
    }

    /** P440: where the tile of rows {@code y..} starts in the frame's pixel-pack buffer (tightly packed RGBA8 rows). */
    static long pboOffset(int y, int width) {
        return (long) y * width * 4;
    }

    /** P440: GL errors left over (the nodes' log-only checks) cleared, so the read-back's own checks see only its own; bounded. */
    private static void drainGlErrors() {
        int left = 32;
        while (left-- > 0 && glGetError() != GL_NO_ERROR) {
            // an error of an earlier call
        }
    }

    /**
     * P642: the frame's pixel-pack buffer mapped - read / write for a sink that draws into the rows (read only when the driver
     * refuses that), read only otherwise. Sets {@link #sinkWritable}.
     */
    private Buffer mapFrame(int bytes, RgbaSink sink) {
        sinkWritable = false;
        if (sink != null && sinkWrites) {
            final Buffer rw = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, bytes, GLES30.GL_MAP_READ_BIT | GLES30.GL_MAP_WRITE_BIT);
            if (rw != null && glGetError() == GL_NO_ERROR) {
                sinkWritable = true;
                return rw;
            }
            if (rw != null) GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER);
            drainGlErrors();
            Log.w(TAG, "final readback: no read / write mapping for the streamed JPEG's watermark, read only");
        }
        return GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, bytes, GLES30.GL_MAP_READ_BIT);
    }

    /**
     * P440 (S4): {@link #drawBlocksToBitmap} through one pixel-pack buffer of the whole frame: every tile is drawn and read back
     * into its rows of the buffer (the GPU copies them while it draws the next tile), the buffer is mapped once and copied into
     * the bitmap - the full-frame buffer and copyPixelsFromBuffer the strip path was proven equal to (TileBlitterTest),
     * without a synchronous read per tile, the strip bitmap and a drawBitmap per 16 rows. glReadPixels writes the same bytes
     * into a buffer object as into client memory (same format, type and pack state). Null when a GL call fails, the buffer
     * cannot be mapped or its contents were lost while mapped: the caller draws the tiles again (the final pass is a pure
     * function of its textures and uniforms).
     */
    private Bitmap drawBlocksToBitmapPbo(OutputTiming timing) {
        final long frameBytes = (long) outW * outH * 4;
        if (frameBytes > Integer.MAX_VALUE) return null;
        final int bytes = (int) frameBytes;
        drainGlErrors();
        final int[] pbo = new int[1];
        GLES30.glGenBuffers(1, pbo, 0);
        if (pbo[0] == 0) return null;
        boolean mapped = false;
        Bitmap dst = null;
        try {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo[0]);
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, bytes, null, GLES30.GL_STREAM_READ);
            if (glGetError() != GL_NO_ERROR) return null;
            glBindFramebuffer(GL_FRAMEBUFFER, tileFramebuffer[0]);
            final GLBlockDivider tiles = new GLBlockDivider(outH, GLDrawParams.TILE_ROWS);
            final int[] tile = new int[2];
            final boolean invalidate = PostGlMode.invalidate();
            final int[] colour = {GLES30.GL_COLOR_ATTACHMENT0};
            while (tiles.nextBlock(tile)) {
                // P479: the tile's old rows are not needed (the rows drawn are the rows read back)
                if (invalidate) GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 1, colour, 0);
                glViewport(0, 0, outW, tile[1]);
                program.setVar("yOffset", tile[0]);
                program.draw();
                timing.rendered();
                GLES30.glReadPixels(0, 0, outW, tile[1], outFormat.getGLFormatExternal(), outFormat.getGLType(),
                        (int) pboOffset(tile[0], outW));
                timing.readBack();
                timing.tiles++;
            }
            if (glGetError() != GL_NO_ERROR) return null;
            final RgbaSink sink = rgbaSink;
            if (sink == null) {
                // no bitmap is made for a sink; P473: one made here is touched while the GPU still draws the tiles
                try {
                    dst = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                    JpegliEncoder.touchPixels(dst);
                } catch (OutOfMemoryError pendingGpuStorage) {
                    Log.w(TAG, "bitmap allocation postponed until queued GPU work finishes");
                }
            }
            final Buffer view = mapFrame(bytes, sink);
            mapped = view != null;
            if (!(view instanceof ByteBuffer) || view.capacity() < bytes || glGetError() != GL_NO_ERROR) return null;
            timing.readBack(); // the map waits for the GPU's copies
            final ByteBuffer src = (ByteBuffer) view;
            src.clear();
            src.limit(bytes);
            // P483: the rows straight to the sink (the JPEG encode) while mapped - before any bitmap copy; a sink that fails
            // leaves the bitmap path below
            if (sink != null && sink.accept(src, outW, outH)) {
                mapped = false;
                if (!GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)) {
                    Log.w(TAG, "final readback: the pixel-pack buffer was lost while mapped, the sink's result is dropped, the tiles are read again");
                    return null;
                }
                timing.copied();
                tileBuffer = null;
                sinkTook = true;
                return null;
            }
            if (dst == null) dst = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            if (!JpegliEncoder.copyPixels(src, dst)) dst.copyPixelsFromBuffer(src);
            mapped = false;
            // false: the buffer's contents were lost while it was mapped (e.g. a display mode change): the copy is not used
            if (!GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)) {
                Log.w(TAG, "final readback: the pixel-pack buffer was lost while mapped, the tiles are read again");
                return null;
            }
            timing.copied();
            tileBuffer = null;
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
     * The 10-bit HEIC's {@link #drawBlocksToBitmap()}: the same tiles from the RGB10_A2 target, read back as
     * GL_UNSIGNED_INT_2_10_10_10_REV words into an RGBA_1010102 bitmap (both hold the same packed 32-bit pixels). A driver
     * that rejects the first 10-bit read-back gets the whole output rendered again in 8 bits.
     */
    private Bitmap drawBlocksToBitmap1010102(OutputTiming timing) {
        requireTileBuffer();
        final Bitmap dst = Bitmap.createBitmap(outW, outH, Bitmap.Config.RGBA_1010102);
        boolean done = false;
        try (TileBlitter blitter = new TileBlitter(dst, stripRows())) {
            glBindFramebuffer(GL_FRAMEBUFFER, tileFramebuffer[0]);
            final GLBlockDivider tiles = new GLBlockDivider(outH, GLDrawParams.TILE_ROWS);
            final int[] tile = new int[2];
            boolean first = true;
            while (tiles.nextBlock(tile)) {
                drawTile(program, tile[0], tile[1], timing);
                tileBuffer.clear();
                glReadPixels(0, 0, outW, tile[1], GLES30.GL_RGBA, GLES30.GL_UNSIGNED_INT_2_10_10_10_REV, tileBuffer);
                timing.readBack();
                if (first) {
                    first = false;
                    final int error = glGetError();
                    if (error != GL_NO_ERROR) {
                        Log.w(TAG, "10-bit readback rejected (0x" + Integer.toHexString(error) + "): the output is rendered in 8 bits");
                        glBindFramebuffer(GL_FRAMEBUFFER, 0);
                        restoreOutputTarget();
                        dst.recycle();
                        return drawBlocksToBitmap();
                    }
                }
                blitter.blit(tileBuffer, tile[0], tile[1]);
                timing.copied();
                timing.tiles++;
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            tileBuffer = null;
            done = true;
            timing.log("tiles 10-bit", outW, outH);
            return dst;
        } finally {
            if (!done && !dst.isRecycled()) dst.recycle();
        }
    }

    /**
     * Copies tightly packed 4-byte rows (a read-back tile, row 0 first) into rows of a destination bitmap through a small
     * strip bitmap of the destination's config: raw bytes as {@code Bitmap.copyPixelsFromBuffer} takes them, drawn with SRC
     * (no blending, filtering or colour conversion between two bitmaps of one config).
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
            stripBytes = rowBytes * this.stripRows;
            strip = Bitmap.createBitmap(width, this.stripRows, dst.getConfig());
            canvas = new Canvas(dst);
            copy.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
            copy.setFilterBitmap(false);
            copy.setDither(false);
        }

        /**
         * Rows {@code y .. y + height - 1} from the start of {@code tile} (whatever its position). copyPixelsFromBuffer always
         * takes a whole strip: the tile buffer must hold whole strips (TILE_ROWS rows); bytes past the tile are stale and never
         * drawn.
         */
        void blit(ByteBuffer tile, int y, int height) {
            final int capacity = tile.capacity();
            try {
                for (int done = 0; done < height; done += stripRows) {
                    final int rows = Math.min(stripRows, height - done);
                    final int start = done * rowBytes;
                    if (start + stripBytes > capacity)
                        throw new IllegalStateException("strip " + start + "+" + stripBytes + " outside the tile buffer " + capacity);
                    tile.limit(start + stripBytes).position(start);
                    strip.copyPixelsFromBuffer(tile);
                    tile.limit(capacity);
                    from.set(0, 0, width, rows);
                    to.set(0, y + done, width, y + done + rows);
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

    private interface Step { void run() throws Exception; }

    private static void quietly(Step step) {
        try {
            step.run();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() {
        // the GPU work completes before the EGL state goes
        quietly(GLES30::glFinish);
        // P353: the tile target goes with the shot (a kept context would hold one per shot otherwise)
        quietly(() -> {
            makeCurrent();
            GLES30.glDeleteFramebuffers(1, tileFramebuffer, 0);
            GLES30.glDeleteRenderbuffers(1, tileRenderbuffer, 0);
            // P638: gone - the deletes below are only for a context that could not be made current here (they ran again after
            // super.close() had released the context: «call to OpenGL ES API with no current context» in every shot's log)
            tileFramebuffer[0] = 0;
            tileRenderbuffer[0] = 0;
        });
        quietly(super::close);
        final GLImage out = outImage;
        outImage = null;
        if (out != null) quietly(out::close);
        final ByteBuffer tile = tileBuffer;
        tileBuffer = null;
        if (tile != null) quietly(tile::clear);
        if (tileFramebuffer[0] != 0) quietly(() -> GLES30.glDeleteFramebuffers(1, tileFramebuffer, 0));
        if (tileRenderbuffer[0] != 0) quietly(() -> GLES30.glDeleteRenderbuffers(1, tileRenderbuffer, 0));
    }
}
