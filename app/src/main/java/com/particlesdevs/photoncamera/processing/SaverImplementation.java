package com.particlesdevs.photoncamera.processing;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.media.Image;
import android.media.ImageReader;
import java.nio.ByteBuffer;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.capture.CaptureController;
import com.particlesdevs.photoncamera.control.GyroBurst;
import com.particlesdevs.photoncamera.processing.processor.ProcessorBase;
import com.particlesdevs.photoncamera.util.Allocator;

import java.util.ArrayList;
import java.util.HashMap;

public class SaverImplementation {
    private static final String TAG = "SaverImplementation";
    public volatile boolean bufferLock = true;
    public volatile boolean newBurst = false;
    public static ArrayList<ImageFrame> IMAGE_BUFFER = new ArrayList<>();
    /** P30: the arena of the shot this saver collects (post-shutter RAWs are copied into it), or null. */
    public volatile com.particlesdevs.photoncamera.util.ShotArena shotArena;
    public int frameCount = 0;
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
    public com.particlesdevs.photoncamera.capture.FullResRecipeShot recipeShot;
    /** The shot's digital-zoom crop and camera at the press (ImageSaver.zoomResidual / cameraIdAtPress). */
    public float zoomResidual = 1f;
    public String cameraIdAtPress;
    /** The shot's capture plan as logged (HybridPlan.description; null: none) for MergeStamp. */
    public String planNote;
    /** The shot's lens filter at the press (ImageSaver.lensFilterAtPress). */
    public com.particlesdevs.photoncamera.processing.opengl.postpipeline.LensFilter.Choice lensFilterAtPress;
    /** The shot's film, LUT and «Цвет» at the press (ImageSaver.lookAtPress). */
    public com.particlesdevs.photoncamera.processing.opengl.postpipeline.ShotLook lookAtPress;
    public com.particlesdevs.photoncamera.settings.MotionMode.Snapshot motionAtPress;
    private int imageFormat;
    public final ProcessingEventsListener processingEventsListener;

    public ImageFrame getFrame(Image image){
        try {
            image.getFormat();
        } catch (Exception e) {
            // This image is not valid, skip it
            return null;
        }
        // Post-shutter frames are kept but marked: the burst writers drop or refuse a frame that is not plain 16-bit.
        RawPayloadCheck.Result payload = RawPayloadCheck.check(image,
                RawPayloadCheck.whiteLevel(CaptureController.mCameraCharacteristics, null));
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        int format = image.getFormat();
        int rowStride = image.getPlanes()[0].getRowStride();
        int width;
        int height = image.getHeight();
        int offset = 0;
        int capacity = buffer.capacity();
        // packed RAW10 / RAW12 report pixel stride 0 (the Pixel 10 Pro's DCG RAW12 crashed here, 2026-10-12): the image's width
        if (format == 0x25 || format == ImageFormat.RAW12 || image.getPlanes()[0].getPixelStride() <= 0) {
            width = image.getWidth();
        } else {
            width = rowStride / image.getPlanes()[0].getPixelStride();
        }
        if (!payload.plain() && format == ImageFormat.RAW_SENSOR) {
            // Xiaomi 17 Ultra tele through logical camera 0: packed MIPI RAW10 (mode 4) or RAW14 (in-sensor zoom, mode 9, P72)
            // behind RAW_SENSOR. The frame is read in that format with the packed rows' own stride and size (the RAW16 capacity
            // made a 4080x4915 frame the merge refused).
            RawPayloadCheck.Layout layout = RawPayloadCheck.packedLayout(buffer, image.getWidth(), height,
                    RawPayloadCheck.blackLevel(CaptureController.mCameraCharacteristics),
                    RawPayloadCheck.whiteLevel(CaptureController.mCameraCharacteristics, null));
            if (layout != null) {
                com.particlesdevs.photoncamera.util.Log.w(TAG, "post-shutter RAW_SENSOR holds " + layout.describe() + ", unpacking to plain uint16");
                format = layout.format;
                width = image.getWidth();
                rowStride = layout.stride;
                capacity = layout.stride * height;
                payload = new RawPayloadCheck.Result(null, payload.impossibleShare, payload.zeroRowShare, true);
            }
        }
        if (!payload.plain()) {
            com.particlesdevs.photoncamera.util.Log.w(TAG, "post-shutter " + payload.error);
            RawPayloadCheck.dumpOnce(image, payload, ScameraApp.getSettings().mCameraID);
        }
        if(!iszAspect4x3 && ScameraApp.getSettings().aspect169 && width > height){
            // P118: the crop of the ZSL ring's frames (Wide169); this one kept an odd row count the merge dropped. P230: of the DCG
            // mode's rows (Xiaomi 15 Ultra: 2560 of 3072), not of its empty band
            final int full = com.particlesdevs.photoncamera.capture.SensorModeBand.imageRows(width, image.getHeight());
            height = Wide169.rows(width, full);
            offset = rowStride * Wide169.firstRow(width, full);
            capacity = rowStride * height;
        }
        Allocator.binning = ScameraApp.getSettings().binning;
        if (tetraBin) return tetraBinned(image, buffer, format, payload, rowStride, height);
        final com.particlesdevs.photoncamera.capture.FullResRecipeShot recipe = recipeShot;
        if (recipe != null && format == ImageFormat.RAW_SENSOR && payload.plain()) {
            final ByteBuffer rows = buffer.duplicate(); // the frame's rows (from the 16:9 crop's first one)
            rows.position(Math.min(offset, rows.limit()));
            final com.particlesdevs.photoncamera.capture.FullResTruth.Result truth = recipe.judge(rows, image.getWidth(), height, rowStride);
            if (truth.verdict == com.particlesdevs.photoncamera.capture.FullResTruth.Verdict.UPSCALED) {
                final ImageFrame halved = recipeHalved(image, buffer, offset, rowStride, height, truth.phase);
                if (halved != null) return halved;
            }
        }
        ImageFrame frame = new ImageFrame(buffer, format, width, rowStride, offset, capacity, shotArena);
        frame.rawPayloadError = payload.error;
        frame.sourceFormat = format;
        frame.timestamp = image.getTimestamp();

        if (Allocator.binning) {
            frame.width = width / 2;
            frame.height = height / 2;
        } else {
            frame.width = width;
            frame.height = height;
        }

        return frame;
    }

    /**
     * P609: a still of the 200 MP Tetra row as its 50 MP Quad (TetraBin: 2x2 boxes inside each 4x4 cell averaged, the levels kept,
     * the noise of a site the sensor's over 4). A frame that does not bin (another format, a packed RAW14, no memory) gets no
     * buffer and its reason (rawPayloadError): the series refuses it, as a still whose RAW is not plain 16-bit.
     */
    private ImageFrame tetraBinned(Image image, ByteBuffer buffer, int format, RawPayloadCheck.Result payload, int rowStride, int height) {
        final int width = image.getWidth();
        final long started = System.nanoTime();
        final com.particlesdevs.photoncamera.util.ShotArena arena = shotArena;
        final ByteBuffer binned = payload.plain() || format == 0x25 || format == 0x26
                ? TetraBin.binnedInto(arena, buffer, 0, format, width, height, rowStride) : null;
        final ImageFrame frame;
        if (binned == null) {
            frame = ImageFrame.wrap(null, width, height);
            frame.rawPayloadError = payload.error != null ? payload.error : "the 200 MP still did not bin into its 50 MP Quad (format " + format + ")";
            com.particlesdevs.photoncamera.util.Log.w(TAG, "P609 " + frame.rawPayloadError);
        } else {
            frame = TetraBin.frame(binned, width, height);
            frame.rawPayloadError = payload.plain() || format == 0x25 || format == 0x26 ? null : payload.error;
            final long ms = (System.nanoTime() - started) / 1_000_000;
            final boolean inArena = arena != null && Allocator.arenaOf(binned) != null;
            com.particlesdevs.photoncamera.util.Log.i("SCAM_CAPTURE", TetraBin.describe(width, height, ms, inArena));
            ShotTimeline.captureNote("tetra_bin", "50 MP from 200 MP, 2x2 per Tetra cell, " + ms + " ms the first still"); // P609
        }
        frame.sourceFormat = format;
        frame.timestamp = image.getTimestamp();
        return frame;
    }

    /**
     * P635: a doubled still of a recipe shot back to the binned frame it was made of (one site of every 2x2 box, FullResTruth.halve)
     * in the shot's arena, else a buffer of its own; null when it cannot be made (the frame is kept as it came).
     */
    private ImageFrame recipeHalved(Image image, ByteBuffer buffer, int offset, int rowStride, int height, int phase) {
        final int width = image.getWidth();
        final int[] half = com.particlesdevs.photoncamera.capture.FullResTruth.halfSize(width, height);
        final int bytes = half[0] * half[1] * 2;
        final com.particlesdevs.photoncamera.util.ShotArena arena = shotArena;
        ByteBuffer out = arena == null ? null : arena.allocate(bytes);
        if (out == null) {
            try {
                out = Allocator.allocate(bytes);
            } catch (LinkageError noLibrary) {
                out = ByteBuffer.allocateDirect(bytes);
            }
        }
        if (out == null) return null;
        out.order(java.nio.ByteOrder.nativeOrder());
        out.position(0);
        if (!com.particlesdevs.photoncamera.capture.FullResTruth.halve(out, buffer, offset, width, height, rowStride, phase)) return null;
        out.position(0);
        final ImageFrame frame = ImageFrame.wrap(out, half[0], half[1]);
        frame.sourceFormat = ImageFormat.RAW_SENSOR;
        frame.timestamp = image.getTimestamp();
        ShotTimeline.captureNote("recipe_upscaled", "the full-size frame is the binned one doubled: merged as " + half[0] + "x" + half[1]);
        return frame;
    }

        final ProcessorBase.ProcessingCallback processingCallback = new ProcessorBase.ProcessingCallback() {
        @Override
        public void onStarted() {
            CaptureController.isProcessing = true;
        }

        @Override
        public void onFailed() {
            onFinished();
        }

        @Override
        public void onFinished() {
            //clearImageReader(imageReader);
            CaptureController.isProcessing = false;
        }
    };
    public SaverImplementation(ProcessingEventsListener processingEventsListener){
        this.processingEventsListener = processingEventsListener;
    }

    public void addImage(Image image) {
        //image.close();
    }

    void addRAW10(Image image){
        //image.close();
    }
    protected void clearImageReader(ImageReader reader) {
        while (true) {
            try {
                reader.acquireNextImage().close();
            } catch (Exception ignored){
                break;
            }
        }
        //reader.close();
    }

    public void runRaw(int imageFormat, CameraCharacteristics characteristics, CaptureResult captureResult, CaptureRequest captureRequest, ArrayList<GyroBurst> burstShakiness, int cameraRotation, HashMap<Long, Double> exposures) {
        this.imageFormat = imageFormat;
    }
    public void processStart(int imageFormat, CameraCharacteristics characteristics, CaptureResult captureResult, CaptureRequest captureRequest, int cameraRotation) {
        this.imageFormat = imageFormat;
    }
    public void processEnd(){
    }
}
