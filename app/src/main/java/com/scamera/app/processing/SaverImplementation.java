package com.scamera.app.processing;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.media.Image;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.capture.CaptureController;
import com.scamera.app.capture.FullResRecipeShot;
import com.scamera.app.capture.FullResTruth;
import com.scamera.app.capture.SensorModeBand;
import com.scamera.app.control.GyroBurst;
import com.scamera.app.processing.opengl.postpipeline.LensFilter;
import com.scamera.app.processing.opengl.postpipeline.ShotLook;
import com.scamera.app.processing.processor.ProcessorBase;
import com.scamera.app.processing.yuv.YuvFrames;
import com.scamera.app.settings.MotionMode;
import com.scamera.app.util.Allocator;
import com.scamera.app.util.Log;
import com.scamera.app.util.ShotArena;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * A saver of one image format (ImageSaverSelector picks it): the frames it takes become {@link ImageFrame}s in the shared
 * {@link #FRAME_QUEUE}; the RAW route's subclass (DefaultSaver) then hands the burst to the processing. This base turns a
 * camera {@link Image} into a frame ({@link #getFrame}) and does nothing with the burst itself.
 */
public class SaverImplementation {
    private static final String TAG = "SaverImplementation";
    /** Packed RAW formats whose pixel stride says nothing: 0x25 RAW10 and 0x26 RAW12. */
    private static final int RAW10 = 0x25, RAW12 = 0x26;

    /** The frames of the burst being collected (the capture's, until ImageSaver.detachForQueue moves them to a saver of its own). */
    public static ArrayList<ImageFrame> FRAME_QUEUE = new ArrayList<>();
    /** Set while DefaultSaver takes the burst; the RAW saver clears it once a frame is in (DefaultSaver waits on it). */
    public volatile boolean bufferLock = true;
    public int frameCount;
    public final ProcessingEvents processingEventsListener;

    // ---- the shot's state (ImageSaver hands it over) ----

    /** P30: the arena of the shot this saver collects (post-shutter RAWs are copied into it), or null. */
    public volatile ShotArena shotArena;
    public boolean iszAspect4x3;
    /**
     * P609: the shot's stills come from the 200 MP Tetra row of a 50 MP shot (VivoSdkFullRes.Profile.tetraBin): each is binned 2x2
     * per Tetra cell into an 8160x6144 Quad while it is copied (TetraBin; the arena slot holds the binned frame only).
     */
    public boolean tetraBin;
    /**
     * P635: the shot is a full-resolution recipe's (null: not): its first frame is measured (FullResTruth - a frame of the full
     * size may be the binned frame written twice over), and when it is doubled every still is taken back to the binned frame
     * while it is copied - the shot is merged as a normal one instead of a 50 MP file without 50 MP of detail.
     */
    public FullResRecipeShot recipeShot;
    /** The shot's digital-zoom crop and camera at the press (ImageSaver.zoomResidual / cameraIdAtPress). */
    public float zoomResidual = 1f;
    public String cameraIdAtPress;
    /** The shot's capture plan as logged (HybridPlan.description; null: none) for MergeStamp. */
    public String planNote;
    /** The shot's lens filter at the press (ImageSaver.lensFilterAtPress). */
    public LensFilter.Choice lensFilterAtPress;
    /** The shot's film, LUT and «Цвет» at the press (ImageSaver.lookAtPress). */
    public ShotLook lookAtPress;
    public MotionMode.Snapshot motionAtPress;

    /** The processing's start and end as CaptureController.isProcessing (the UI's busy state); a failure ends it too. */
    static final ProcessorBase.ProcessingCallback processingCallback = new ProcessorBase.ProcessingCallback() {
        @Override public void started() {
            CaptureController.isProcessing = true;
        }

        @Override public void finished() {
            CaptureController.isProcessing = false;
        }

        @Override public void failed() {
            finished();
        }
    };

    public SaverImplementation(ProcessingEvents listener) {
        processingEventsListener = listener;
    }

    /** Where a frame's rows sit in the image's buffer, in which format. */
    private static final class Rows {
        int format, width, height, stride, offset, capacity;
    }

    /**
     * The frame of {@code image}: a YUV / P010 image keeps its three planes (P646 «Склейка YUV»), a RAW its plane 0 - copied,
     * binned or cropped as the shot asks. Null for an image that is no longer valid. A post-shutter RAW whose payload is not
     * plain 16-bit is kept but marked (rawPayloadError): the burst writers drop or refuse it.
     */
    public ImageFrame getFrame(Image image) {
        final Rows rows = new Rows();
        try {
            rows.format = image.getFormat();
        } catch (Exception closed) {
            return null; // an image already closed or recycled
        }
        if (YuvFrames.isYuv(rows.format)) return YuvFrames.frame(image);
        final CameraCharacteristics camera = CaptureController.mCameraCharacteristics;
        RawPayloadCheck.Result payload = RawPayloadCheck.check(image, RawPayloadCheck.whiteLevel(camera, null));
        final Image.Plane plane = image.getPlanes()[0];
        final ByteBuffer buffer = plane.getBuffer();
        rows.stride = plane.getRowStride();
        rows.height = image.getHeight();
        rows.capacity = buffer.capacity();
        // packed RAW10 / RAW12 report pixel stride 0 (the Pixel 10 Pro's DCG RAW12 crashed here, 2026-10-12): the image's width
        final int pixelStride = plane.getPixelStride();
        final boolean packed = rows.format == RAW10 || rows.format == ImageFormat.RAW12 || pixelStride <= 0;
        rows.width = packed ? image.getWidth() : rows.stride / pixelStride;
        if (!payload.plain() && rows.format == ImageFormat.RAW_SENSOR) payload = unpackedLayout(image, buffer, camera, payload, rows);
        if (!payload.plain()) {
            Log.w(TAG, "post-shutter " + payload.error);
            RawPayloadCheck.dumpOnce(image, payload, ScameraApp.getSettings().cameraId);
        }
        // P635 / P635b: a recipe shot's truth check on the whole buffer, before any crop
        final FullResRecipeShot recipe = recipeShot;
        if (recipe != null && rows.format == ImageFormat.RAW_SENSOR && payload.plain()) {
            final FullResTruth.Result truth = recipe.judge(buffer, image.getWidth(), rows.height, rows.stride);
            if (truth.verdict == FullResTruth.Verdict.UPSCALED || truth.verdict == FullResTruth.Verdict.PARTIAL)
                return recipeFrame(image, buffer, rows, truth);
        }
        if (!iszAspect4x3 && ScameraApp.getSettings().wide169 && rows.width > rows.height) cropTo169(image, rows);
        Allocator.binning = ScameraApp.getSettings().binning;
        if (tetraBin) return tetraBinned(image, buffer, rows.format, payload, rows.stride, rows.height);
        final ImageFrame frame = new ImageFrame(buffer, rows.format, rows.width, rows.stride, rows.offset, rows.capacity, shotArena);
        stamp(frame, image, rows.format);
        frame.rawPayloadError = payload.error;
        // binning halves both sides as the frame is copied
        frame.width = Allocator.binning ? rows.width / 2 : rows.width;
        frame.height = Allocator.binning ? rows.height / 2 : rows.height;
        return frame;
    }

    /**
     * Xiaomi 17 Ultra tele through logical camera 0: packed MIPI RAW10 (mode 4) or RAW14 (in-sensor zoom, mode 9, P72) behind
     * RAW_SENSOR. Such a frame is read in its own format with the packed rows' stride and size (the RAW16 capacity made a
     * 4080x4915 frame the merge refused), and its payload counts as plain; {@code payload} unchanged when it is not one.
     */
    private static RawPayloadCheck.Result unpackedLayout(Image image, ByteBuffer buffer, CameraCharacteristics camera,
                                                         RawPayloadCheck.Result payload, Rows rows) {
        final RawPayloadCheck.Layout layout = RawPayloadCheck.packedLayout(buffer, image.getWidth(), rows.height,
                RawPayloadCheck.blackLevel(camera), RawPayloadCheck.whiteLevel(camera, null));
        if (layout == null) return payload;
        Log.w(TAG, "post-shutter RAW_SENSOR holds " + layout.describe() + ", unpacking to plain uint16");
        rows.format = layout.format;
        rows.width = image.getWidth();
        rows.stride = layout.stride;
        rows.capacity = layout.stride * rows.height;
        return new RawPayloadCheck.Result(null, payload.impossibleShare, payload.zeroRowShare, true);
    }

    /**
     * P118: the «16:9» crop of the ZSL ring's frames (Wide169; this path once kept an odd row count the merge dropped). P230: of
     * the DCG mode's picture rows (Xiaomi 15 Ultra: 2560 of 3072), not of its empty band.
     */
    private static void cropTo169(Image image, Rows rows) {
        final int picture = SensorModeBand.imageRows(rows.width, image.getHeight());
        rows.height = Wide169.rows(rows.width, picture);
        rows.offset = rows.stride * Wide169.firstRow(rows.width, picture);
        rows.capacity = rows.stride * rows.height;
    }

    private static void stamp(ImageFrame frame, Image image, int sourceFormat) {
        frame.sourceFormat = sourceFormat;
        frame.timestamp = image.getTimestamp();
    }

    /**
     * P635 / P635b: a recipe still that is not the sensor's full readout is replaced by the binned frame it holds (a doubled one
     * halved, the binned frame at its own pitch unpacked); one that holds a picture in another part only is refused - a frame
     * without a buffer, which the series drops.
     */
    private ImageFrame recipeFrame(Image image, ByteBuffer buffer, Rows rows, FullResTruth.Result truth) {
        final ImageFrame binned = recipeBinned(image, buffer, rows.stride, rows.height, truth);
        if (binned != null) return binned;
        final ImageFrame refused = ImageFrame.wrap(null, image.getWidth(), rows.height);
        refused.rawPayloadError = "the full-size buffer holds no full frame (" + truth.verdict + ") and no binned frame the shot could take";
        stamp(refused, image, rows.format);
        return refused;
    }

    /**
     * P609: a still of the 200 MP Tetra row as its 50 MP Quad (TetraBin: 2x2 boxes inside each 4x4 cell averaged, the levels kept,
     * the noise of a site the sensor's over 4). A frame that does not bin (another format, a packed RAW14, no memory) gets no
     * buffer and its reason (rawPayloadError): the series refuses it, as a still whose RAW is not plain 16-bit.
     */
    private ImageFrame tetraBinned(Image image, ByteBuffer buffer, int format, RawPayloadCheck.Result payload, int rowStride, int height) {
        final int width = image.getWidth();
        final long started = System.nanoTime();
        final ShotArena arena = shotArena;
        final boolean bins = payload.plain() || format == RAW10 || format == RAW12;
        final ByteBuffer binned = bins ? TetraBin.binnedInto(arena, buffer, 0, format, width, height, rowStride) : null;
        final ImageFrame frame;
        if (binned != null) {
            frame = TetraBin.frame(binned, width, height);
            frame.rawPayloadError = bins ? null : payload.error;
            final long ms = (System.nanoTime() - started) / 1_000_000;
            Log.i("SCAM_CAPTURE", TetraBin.describe(width, height, ms, arena != null && Allocator.arenaOf(binned) != null));
            ShotTimeline.captureNote("tetra_bin", "50 MP from 200 MP, 2x2 per Tetra cell, " + ms + " ms the first still"); // P609
        } else {
            frame = ImageFrame.wrap(null, width, height);
            frame.rawPayloadError = payload.error != null ? payload.error
                    : "the 200 MP still did not bin into its 50 MP Quad (format " + format + ")";
            Log.w(TAG, "P609 " + frame.rawPayloadError);
        }
        stamp(frame, image, format);
        return frame;
    }

    /**
     * P635 / P635b: the binned frame a still of a recipe shot really holds - a doubled frame halved (one site of every 2x2 box),
     * the binned frame at its own pitch in the buffer's first quarter unpacked (FullResTruth) - in the shot's arena, else a buffer
     * of its own; null when there is none to take (the caller refuses the frame).
     */
    private ImageFrame recipeBinned(Image image, ByteBuffer buffer, int rowStride, int height, FullResTruth.Result truth) {
        final int width = image.getWidth();
        final boolean doubled = truth.verdict == FullResTruth.Verdict.UPSCALED;
        if (!doubled && !truth.packedQuarter()) return null;
        final int[] half = FullResTruth.halfSize(width, height);
        final ByteBuffer out = binnedBuffer(half[0] * half[1] * 2);
        if (out == null) return null;
        final boolean made = doubled
                ? FullResTruth.halve(out, buffer, 0, width, height, rowStride, truth.phase)
                : FullResTruth.unpackQuarter(out, buffer, width, height, rowStride);
        if (!made) return null;
        out.position(0);
        final ImageFrame frame = ImageFrame.wrap(out, half[0], half[1]);
        stamp(frame, image, ImageFormat.RAW_SENSOR);
        ShotTimeline.captureNote("recipe_binned", (doubled ? "the full-size frame is the binned one doubled" : "the full-size buffer holds the binned frame")
                + ": merged as " + half[0] + "x" + half[1]);
        return frame;
    }

    /** {@code bytes} for a binned frame: the shot's arena first, then native memory (a direct buffer without the library). */
    private ByteBuffer binnedBuffer(int bytes) {
        final ShotArena arena = shotArena;
        ByteBuffer out = arena != null ? arena.allocate(bytes) : null;
        if (out == null) {
            try {
                out = Allocator.allocate(bytes);
            } catch (LinkageError noLibrary) {
                out = ByteBuffer.allocateDirect(bytes);
            }
        }
        if (out != null) {
            out.order(ByteOrder.nativeOrder());
            out.position(0);
        }
        return out;
    }

    /** A frame of the burst arrived; this base keeps nothing (the format's saver does). */
    public void addImage(Image image) {
    }

    /** The burst is complete: the RAW route processes it; nothing here. */
    public void runRaw(int imageFormat, CameraCharacteristics characteristics, CaptureResult captureResult, CaptureRequest captureRequest,
                       ArrayList<GyroBurst> burstShakiness, int cameraRotation, HashMap<Long, Double> exposures) {
    }

    public void processStart(int imageFormat, CameraCharacteristics characteristics, CaptureResult captureResult,
                             CaptureRequest captureRequest, int cameraRotation) {
    }

    public void finishShot() {
    }
}
