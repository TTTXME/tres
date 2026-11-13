package com.scamera.app.processing;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.RggbChannelVector;
import android.media.Image;
import android.media.ImageReader;

import androidx.exifinterface.media.ExifInterface;

import com.scamera.app.api.ParseExif;
import com.scamera.app.capture.FullResRecipeShot;
import com.scamera.app.control.GyroBurst;
import com.scamera.app.processing.color.MosaicNeutralResolver;
import com.scamera.app.processing.opengl.postpipeline.LensFilter;
import com.scamera.app.processing.opengl.postpipeline.ShotLook;
import com.scamera.app.processing.parameters.IsoExpoSelector;
import com.scamera.app.processing.render.Parameters;
import com.scamera.app.settings.MotionMode;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.util.Log;
import com.scamera.app.util.ShotArena;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

/**
 * The capture's side of a shot: the frames of the burst arrive here ({@link #initProcess}) and go to the saver of their format
 * (ImageSaverSelector), with what was true at the press; {@link #detachForQueue} hands a finished burst to its own saver so the
 * camera can take the next shot while this one is processed. The shared frame list is {@link SaverImplementation#FRAME_QUEUE}.
 */
public class ImageSaver {
    private static final String TAG = "ImageSaver";

    /** The saver the frames of the current format go to (swapped when the format changes). */
    public SaverImplementation saverImpl;
    private int lastFormat;
    /** Frames this burst still takes ({@code -1}: any number) and frames seen so far. */
    private int wanted, seen;
    /**
     * P495 (OPPO 2026-11-03, the first shot after the start: «Index 1 out of bounds for length 1»): the savers frames were detached
     * into, oldest first. A RAW arriving just after the detach swapped {@link #implementation} (the constructor's DefaultSaver -> the
     * RAW16Saver), so runRaw ran on a saver without the shot's frames and read the shared buffer's one stray frame as the burst.
     */
    private final ArrayDeque<DefaultSaver> detached = new ArrayDeque<>();

    // ---- the press's state, carried to the saver and the processing ----

    /** P30: the shot's arena the RAWs of this burst are copied into (ShotArena), or null. */
    public volatile ShotArena shotArena;
    public boolean iszAspect4x3;
    /** P609: the stills are binned 2x2 per Tetra cell into the 50 MP Quad as they are copied (SaverImplementation.tetraBin). */
    public boolean tetraBin;
    /** P635: the shot is a full-resolution recipe's: its first frame is checked, a doubled frame halved (SaverImplementation.recipeShot). */
    public FullResRecipeShot recipeShot;
    /** P190: frames of a FULLRES series the merge keeps whole (0: not a series) and the frames asked for (0: not FULLRES). */
    public int fullResSeries, fullResRequested;
    /** P320: the shot's frames come from a DCG sensor mode (Dcg14Mode.active at the press). */
    public boolean dcgShot;
    /** P212f (W2 = M3): the hand-set white balance's gains at the press (null: automatic). */
    public RggbChannelVector manualWbAtPress;
    /** P212g (W6 = M6): the mosaic white balance's route at the press (MosaicNeutralResolver; null: none). */
    public MosaicNeutralResolver.Press mosaicWbAtPress;
    /** The digital zoom's crop of the shot (ZoomController.shotResidual at the press; the next press marks its own). */
    public float zoomResidual = 1f;
    /** The camera of the shot (Settings.mCameraID at the press; null: the one open when the processing starts). */
    public String cameraIdAtPress;
    /** The shot's capture plan as logged (HybridPlan.description; null: none): MergeStamp's «why no ES / L». */
    public String planNote;
    /** Owner, 2026-10-28: the lens filter the viewfinder showed at the press (LensFilter.Choice.now; null: the settings when processed). */
    public LensFilter.Choice lensFilterAtPress;
    /** The film, LUT and «Цвет» at the press (ShotLook.now; null: the settings when processed). */
    public ShotLook lookAtPress;
    public MotionMode.Snapshot motionAtPress;

    public ImageSaver(ProcessingEvents listener) {
        saverImpl = new DefaultSaver(listener);
        ImageSaverSelector.init(saverImpl);
    }

    public void setFrameCount(int frames) {
        wanted = frames;
    }

    public void setImageFormat(int format) {
        lastFormat = format;
    }

    /** {@link #setFrameCount} for a burst already under way: its saver learns it too. */
    public void updateFrameCount(int frames) {
        setFrameCount(frames);
        saverImpl.frameCount = frames;
    }

    public synchronized int bufferSize() {
        return SaverImplementation.FRAME_QUEUE.size();
    }

    public synchronized ArrayList<ImageFrame> snapshotFrames() {
        return new ArrayList<>(SaverImplementation.FRAME_QUEUE);
    }

    /**
     * Moves this burst's frames and exposure pairs out of the shared buffers
     * so the camera can capture the next shot while this one is processed.
     */
    public synchronized void detachForQueue() {
        detachForQueue(null);
    }

    /** P27: {@code hybridRoute} is the merge route the shot was captured for; processing keeps it even if the setting changes. */
    public synchronized void detachForQueue(Boolean hybridRoute) {
        if (!(saverImpl instanceof DefaultSaver))
            throw new IllegalStateException("Queued processing requires the RAW saver");
        final DefaultSaver saver = (DefaultSaver) saverImpl;
        saver.ownedHybridRoute = hybridRoute;
        saver.ownedIszAspect4x3 = iszAspect4x3;
        saver.ownedFullResSeries = fullResSeries;
        saver.ownedFullResRequested = fullResRequested;
        saver.ownedDcgShot = dcgShot;
        saver.ownedManualWb = manualWbAtPress;
        saver.ownedMosaicWb = mosaicWbAtPress;
        saver.ownedZoomResidual = zoomResidual;
        saver.ownedCameraId = cameraIdAtPress;
        saver.ownedPlanNote = planNote;
        saver.ownedLensFilter = lensFilterAtPress;
        saver.ownedLook = lookAtPress;
        saver.ownedMotion = motionAtPress;
        saver.ownedFrames = snapshotFrames();
        saver.ownedPairs = new ArrayList<>(IsoExpoSelector.shotPlan);
        SaverImplementation.FRAME_QUEUE.clear();
        // named at the press like the stock camera, not when the processing starts; one name for the DNG and the photo (P119),
        // and P618 that instant is the shot's time in its EXIF and DNG
        final long pressedAt = System.currentTimeMillis();
        final String name = ShotNames.reserve(ImagePath.newBaseName(pressedAt));
        saver.ownedShotTimeMs = pressedAt;
        saver.ownedDngFile = ImagePath.dngFile(name);
        saver.ownedImageFile = ImagePath.imageFile(name);
        detached.addLast(saver);
    }

    /** File names of the default template have one-second resolution; queued shots of one second get _1, _2, ... */
    static final class ShotNames {
        private static String last = "";
        private static int repeats;

        static synchronized String reserve(String base) {
            if (!base.equals(last)) {
                last = base;
                repeats = 0;
                return base;
            }
            return base + "_" + ++repeats;
        }
    }

    /** Removes and closes the given frames of this burst (post-shutter RAWs the hybrid dropped). */
    public synchronized void removeFrames(Collection<ImageFrame> frames) {
        for (ImageFrame f : frames) {
            if (SaverImplementation.FRAME_QUEUE.remove(f) && f != null) f.close();
        }
    }

    public synchronized void discardFrames() {
        wanted = 0;
        for (ImageFrame f : SaverImplementation.FRAME_QUEUE) f.close();
        SaverImplementation.FRAME_QUEUE.clear();
    }

    public void initProcess(ImageReader reader) {
        final Image next;
        try {
            next = reader.acquireNextImage();
        } catch (Exception noImage) {
            return;
        }
        initProcess(next);
    }

    /** Accepts an owned image after preview/still routing by sensor timestamp. */
    public synchronized void initProcess(Image image) {
        if (image == null) return;
        if (wanted == -1 || seen < wanted) take(image);
        else image.close();
        seen++;
    }

    private void take(Image image) {
        lastFormat = image.getFormat();
        saverImpl = ImageSaverSelector.getImageSaver(lastFormat, saverImpl);
        // the saver can change with the format: it gets the burst's state every frame (P30, P609, P635)
        saverImpl.shotArena = shotArena;
        saverImpl.iszAspect4x3 = iszAspect4x3;
        saverImpl.tetraBin = tetraBin;
        saverImpl.recipeShot = recipeShot;
        saverImpl.frameCount = wanted;
        saverImpl.addImage(image);
    }

    public void runRaw(CameraCharacteristics characteristics, CaptureResult captureResult, CaptureRequest captureRequest,
                       ArrayList<GyroBurst> burstShakiness, int cameraRotation, HashMap<Long, Double> exposures) {
        final SaverImplementation saver;
        synchronized (this) {
            final DefaultSaver queued = detached.pollFirst();
            saver = queued == null ? saverImpl : queued;
        }
        saver.iszAspect4x3 = iszAspect4x3;
        saver.zoomResidual = zoomResidual;
        saver.cameraIdAtPress = cameraIdAtPress;
        saver.planNote = planNote;
        saver.lensFilterAtPress = lensFilterAtPress;
        saver.lookAtPress = lookAtPress;
        saver.motionAtPress = motionAtPress;
        saver.runRaw(lastFormat, characteristics, captureResult, captureRequest, burstShakiness, cameraRotation, exposures);
    }

    public void processStart(CameraCharacteristics characteristics, CaptureResult captureResult, CaptureRequest captureRequest,
                             int cameraRotation) {
        saverImpl = ImageSaverSelector.getImageSaver(ImageFormat.RAW_SENSOR, saverImpl);
        saverImpl.processStart(lastFormat, characteristics, captureResult, captureRequest, cameraRotation);
    }

    public void finishShot() {
        saverImpl.finishShot();
    }

    /** The photo and DNG writers. */
    public static class Util {
        /** Output buffer of the JPEG writers: Bitmap.compress hands its encoder output over in 4 KB chunks. */
        public static final int SAVE_BUFFER_BYTES = 256 * 1024;

        private interface BitmapEncoder {
            void encode(Bitmap bitmap, int quality, OutputStream out) throws IOException;
        }

        /**
         * Encodes {@code img} straight into the file (no in-memory JPEG copy); recycles it when the encoders are done (and on
         * failure) when {@code recycle} - at 50 MP the bitmap is ~200 MB of native memory that must not wait for a GC; false
         * keeps it for another encode (a HEIC / WebP photo with its extra JPEG). A file whose image data was not written to its
         * end is deleted, so a truncated JPEG never reaches the gallery.
         */
        public static boolean saveBitmapAsJPG(Path fileToSave, Bitmap img, int jpgQuality, ParseExif.ExifData exifData, boolean recycle) {
            return saveBitmapAsJPG(fileToSave, img, jpgQuality, exifData, recycle, null);
        }

        /**
         * As above; {@code icc} (P46, «Цветовое пространство» Display P3) goes into the JPEG as APP2 ICC_PROFILE right after SOI
         * (ExifInterface keeps it when it writes the EXIF). Null: the encoders write into the file stream as before.
         *
         * <p>Jpegli (4:4:4) first, the file written again from the start by Android's encoder (4:2:0) when it fails; P80
         * «Быстрый JPEG»: Android's encoder alone. P147: the EXIF goes into the stream as its APP1 (ExifBlock: the same tags);
         * only without the block (it failed) is the file's EXIF rewritten afterwards. P636: the block (its temporary file and
         * the 512 px thumbnail of the photo) is made on a helper thread beside the encode - jpegli encodes its strips in memory
         * before the first byte is written, and the stream takes the block at that byte (ExifBeside); scam_dev
         * "exif_beside_encode 0": before the encode.
         */
        public static boolean saveBitmapAsJPG(Path fileToSave, Bitmap img, int jpgQuality, ParseExif.ExifData exifData, boolean recycle,
                                              byte[] icc) {
            exifData.compression = ParseExif.COMPRESSION_JPEG;
            final int w = img.getWidth(), h = img.getHeight();
            final ExifBeside exif = new ExifBeside(() -> ExifBlock.app1Segment(exifData, w, h, img), // P620: with its thumbnail
                    PreferenceKeys.scamDevSwitch("exif_beside_encode", true));
            final long started = System.nanoTime();
            boolean written = false;
            try {
                if (JpegliEncoder.available() && !PreferenceKeys.getJpegFastEncoder()) {
                    final Exception failed = encodeInto(fileToSave, exif, icc, img, jpgQuality, JpegliEncoder::compress);
                    written = failed == null;
                    if (!written) Log.w(TAG, "jpegli save failed, Android encoder (4:2:0): " + failed);
                }
                if (!written) {
                    final Exception failed = encodeInto(fileToSave, exif, icc, img, jpgQuality, JpegliEncoder::compressFallback);
                    written = failed == null;
                    if (!written) Log.e(TAG, "JPEG save failed: " + Log.getStackTraceString(failed));
                }
            } finally {
                exif.await(); // the helper reads the bitmap: never recycled under it
                if (recycle && !img.isRecycled()) img.recycle();
            }
            if (!written) {
                deleteQuietly(fileToSave);
                return false;
            }
            final long encoded = System.nanoTime();
            Log.d(TAG, "JPEG encode ms=" + (encoded - started) / 1000000); // P33
            if (exif.await() != null) {
                Log.d(TAG, "EXIF in the stream (block ms=" + exif.blockMs()
                        + (exif.beside() ? " beside the encode, waited " + exif.waitedMs() : "") + "), no rewrite"); // P147, P636
                return true;
            }
            rewriteExif(fileToSave, exifData);
            Log.d(TAG, "EXIF rewrite ms=" + (System.nanoTime() - encoded) / 1000000); // P33 W2.6
            return true;
        }

        /** One encode of the whole file through the EXIF stream; null when it was written to its end, else the failure. */
        private static Exception encodeInto(Path file, ExifBeside exif, byte[] icc, Bitmap img, int quality, BitmapEncoder encoder) {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), SAVE_BUFFER_BYTES)) {
                final ExifBeside.Stream tagged = exif.stream(out, icc);
                encoder.encode(img, quality, tagged);
                tagged.finish();
                out.flush();
                return null;
            } catch (IOException | RuntimeException e) {
                return e;
            }
        }

        /** The EXIF written into the finished file; a failure costs only the tags (the image itself is complete). */
        private static void rewriteExif(Path file, ParseExif.ExifData exifData) {
            try {
                final ExifInterface tags = ParseExif.setAllAttributes(file.toFile(), exifData);
                if (tags != null) tags.saveAttributes();
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "EXIF write failed: " + Log.getStackTraceString(e));
            }
        }

        private static void deleteQuietly(Path file) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // nothing more to do about a file that cannot be deleted
            }
        }

        /** The merged frame's DNG (the photo's own parameters), compressed as the «DNG без потерь» setting says. */
        public static boolean saveStackedRaw(Path dngFilePath, ByteBuffer buffer, Parameters parameters) {
            return saveSingleRaw(dngFilePath, buffer, parameters, false);
        }

        /** P267: the merged mosaic DNG without our remosaic, uncompressed for Scam Remosaic (see saveSingleRaw). */
        public static boolean saveStackedRaw(Path dngFilePath, ByteBuffer buffer, Parameters parameters, boolean uncompressed) {
            return saveSingleRaw(dngFilePath, buffer, parameters, uncompressed);
        }

        /**
         * One DNG of {@code buffer} at rawSize; {@code uncompressed}: no «DNG без потерь» compression whatever the setting (P258:
         * the mosaic DNG for Scam Remosaic, which reads uncompressed 16-bit CFA without a LinearizationTable only). P27: a DNG
         * that cannot be written (I/O, or a DngCreator RuntimeException) costs only the DNG, never the photo.
         */
        public static boolean saveSingleRaw(Path dngFilePath, ByteBuffer buffer, Parameters parameters, boolean uncompressed) {
            final DngCreator creator = new DngCreator();
            try {
                creator.setParameters(parameters);
                creator.setCompression(!uncompressed && PreferenceKeys.isDngLossless());
                final int w = parameters.rawSize.x, h = parameters.rawSize.y;
                return writeDng(dngFilePath, out -> creator.writeBuffer(out, buffer, w, h), "DNG");
            } finally {
                // P187: its image (the whole RAW again, 0.4-0.54 GB at 200 MP) now, not when a GC runs the finalizer
                creator.close();
            }
        }

        /** Writes one DNG's bytes (the stream is the caller's file). */
        interface DngWrite {
            void to(OutputStream out) throws IOException;
        }

        /**
         * The DNG at {@code dngFilePath}; false (logged) on an I/O error or a DngCreator RuntimeException. A file not written to
         * its end is deleted, an OutOfMemoryError's too (it still goes up to the caller): a truncated DNG stayed next to the photo.
         */
        static boolean writeDng(Path dngFilePath, DngWrite write, String what) {
            boolean complete = false;
            try {
                try (OutputStream out = Files.newOutputStream(dngFilePath)) {
                    write.to(out);
                }
                complete = true;
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, what + " save failed: " + Log.getStackTraceString(e));
            } finally {
                if (!complete && dngFilePath != null) dropPartial(dngFilePath, what);
            }
            return complete;
        }

        private static void dropPartial(Path file, String what) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, what + ": the partial file stays: " + e);
            }
        }
    }
}
