package com.scamera.app.capture;

import android.graphics.ImageFormat;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.view.Surface;

import androidx.annotation.Nullable;

import com.scamera.app.processing.ImageFrame;
import com.scamera.app.util.Allocator;
import com.scamera.app.util.Lang;
import com.scamera.app.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * P679: the Xiaomi 17 Ultra's LOFIC pair session's readers (the recipe's dummy RAW, video, JPEG, RAW A / RAW B and P700's YUV
 * companion), its ring of Long / Short pairs and the shot's drain of them. P707: moved out of {@link CaptureController} unchanged
 * (the controller's session build, preview request, close and shot call these); what it needs of the controller is {@link Host}.
 */
final class XiaomiLoficReaders {
    /** What the readers ask of the camera. */
    interface Host {
        /** P179: the camera runs its plain session for a merge (a frame then is not kept). */
        boolean closedForProcessing();

        /** A setup failed its checks (XiaomiLofic logged which): the session opens again with the next setup or the normal one. */
        void setupFailed();
    }

    /** A shot's frames and the newest pair's result (the shot's N base). */
    static final class Drained {
        final List<ImageFrame> frames;
        @Nullable final TotalCaptureResult base;

        Drained(List<ImageFrame> frames, @Nullable TotalCaptureResult base) {
            this.frames = frames;
            this.base = base;
        }
    }

    private final Host host;

    XiaomiLoficReaders(Host host) {
        this.host = host;
    }

    /** The recipe's readers of a LOFIC session (null in any other): the dummy RAW, the video stream, the JPEG, RAW A and RAW B. */
    volatile ImageReader dummy, video, jpeg, rawA, rawB;
    /** P700: the YUV companion of a 4:3 companion variant (XiaomiLofic.Setup.companion), its frames dropped; null otherwise. */
    volatile ImageReader companion;
    /** The session's newest pairs (copies of the used bytes). */
    final XiaomiLofic.Ring pairs = new XiaomiLofic.Ring(XiaomiLofic.PAIRS);
    /** Images measured exactly (the used length) at the session's start; later ones by the quick test. */
    private int exactImages;
    /** The last kept timestamp: at 60 fps every second frame is kept (the copies' cost). */
    private long keptTs;

    static void closeQuietly(@Nullable ImageReader r) {
        if (r != null) try { r.close(); } catch (RuntimeException ignored) { }
    }

    /** The LOFIC readers and pairs go (the session ended, or the next one is not a LOFIC one). */
    void close() {
        close(readers());
    }

    /** P680: the LOFIC session's readers now (closeCamera takes them when it schedules the close). */
    ImageReader[] readers() {
        return new ImageReader[]{dummy, video, jpeg, rawA, rawB, companion};
    }

    /**
     * P680: the LOFIC readers {@code readers} ({@link #readers}) close; a field still holding its reader is cleared,
     * and the pairs go unless a new LOFIC session has its readers already.
     */
    void close(ImageReader[] readers) {
        if (dummy == readers[0]) dummy = null;
        if (video == readers[1]) video = null;
        if (jpeg == readers[2]) jpeg = null;
        if (rawA == readers[3]) rawA = null;
        if (rawB == readers[4]) rawB = null;
        if (companion == readers[5]) companion = null; // P700
        for (ImageReader r : readers) closeQuietly(r);
        if (rawA == null) pairs.clear();
    }

    /** The recipe's readers for {@code s}; false when one cannot be made here. */
    boolean open(XiaomiLofic.Setup s, @Nullable Handler handler) {
        close();
        if (Build.VERSION.SDK_INT < 29 || handler == null) return false;
        try {
            dummy = ImageReader.newInstance(XiaomiLofic.RAW_W, XiaomiLofic.RAW_H, ImageFormat.RAW_SENSOR, 2);
            video = ImageReader.newInstance(s.videoW, s.videoH, ImageFormat.PRIVATE, 3,
                    android.hardware.HardwareBuffer.USAGE_VIDEO_ENCODE);
            jpeg = ImageReader.newInstance(s.jpegW, s.jpegH, ImageFormat.JPEG, 1);
            // P700: the capture variant's RAW A / B with the 50 MP recipe's reader usage
            rawA = s.rawUsage != 0 ? ImageReader.newInstance(XiaomiLofic.RAW_W, XiaomiLofic.RAW_H, ImageFormat.RAW_SENSOR, 4, s.rawUsage)
                    : ImageReader.newInstance(XiaomiLofic.RAW_W, XiaomiLofic.RAW_H, ImageFormat.RAW_SENSOR, 4);
            rawB = s.rawUsage != 0 ? ImageReader.newInstance(XiaomiLofic.RAW_W, XiaomiLofic.RAW_H, ImageFormat.RAW_SENSOR, 4, s.rawUsage)
                    : ImageReader.newInstance(XiaomiLofic.RAW_W, XiaomiLofic.RAW_H, ImageFormat.RAW_SENSOR, 4);
            final ImageReader.OnImageAvailableListener drop = r -> {
                try { final Image i = r.acquireLatestImage(); if (i != null) i.close(); } catch (RuntimeException ignored) { }
            };
            video.setOnImageAvailableListener(drop, handler);
            if (s.companion) { // P700: the 50 MP recipe's YUV companion on every request, its frames dropped at once
                companion = ImageReader.newInstance(XiaomiLofic.COMPANION_W, XiaomiLofic.COMPANION_H, ImageFormat.YUV_420_888, 2);
                companion.setOnImageAvailableListener(drop, handler);
            }
            rawA.setOnImageAvailableListener(this::onRaw, handler);
            rawB.setOnImageAvailableListener(this::onRaw, handler);
            pairs.capacity(XiaomiLofic.pairsWanted() + 2);
            exactImages = 0;
            keptTs = 0;
            return true;
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "XIAOMI LOFIC: readers not made: " + e + " (P679)");
            close();
            return false;
        }
    }

    /** The six outputs of the recipe in its order (XiaomiLofic.outputs); P700: a companion variant's YUV companion after them. */
    List<Surface> surfaces(Surface viewfinder) {
        final List<Surface> out = new ArrayList<>(Arrays.asList(dummy.getSurface(), viewfinder, video.getSurface(),
                jpeg.getSurface(), rawA.getSurface(), rawB.getSurface()));
        final ImageReader companion = this.companion;
        if (companion != null) out.add(companion.getSurface());
        return out;
    }

    /**
     * An image of RAW A or RAW B: classified by its used length (exactly for the session's first images, which also prove the
     * setup's rows; then by the quick test), its used bytes copied into the ring of pairs, the reader's buffer given back at once.
     */
    void onRaw(ImageReader reader) {
        Image img = null;
        try {
            img = reader.acquireNextImage();
            final XiaomiLofic.Setup s = XiaomiLofic.setup();
            if (img == null || s == null || host.closedForProcessing()) return;
            final java.nio.ByteBuffer b = img.getPlanes()[0].getBuffer();
            final long ts = img.getTimestamp();
            final boolean exact = exactImages < 8 || !XiaomiLofic.proven();
            final XiaomiLofic.Stream stream;
            final int used;
            if (exact) {
                exactImages++;
                used = XiaomiLofic.usedBytes(b);
                stream = XiaomiLofic.classify(used);
                if (exactImages <= 4) Log.i("SCAM_CAPTURE", "XIAOMI LOFIC " + s.label + ": " + (reader == rawA ? "RAW A" : "RAW B")
                        + " image " + img.getWidth() + "x" + img.getHeight() + " stride " + img.getPlanes()[0].getRowStride()
                        + ", used " + used + " B -> " + stream + " " + XiaomiLofic.rows(used, stream) + " rows (P679)");
            } else {
                stream = XiaomiLofic.quick(b, s.rows);
                used = stream == XiaomiLofic.Stream.LONG ? s.longBytes() : stream == XiaomiLofic.Stream.SHORT ? s.shortBytes() : 0;
            }
            // at 60 fps every second exposure is kept (both of its images: the second one finds the pair begun)
            final long gap = s.fps >= 50 ? 25_000_000L : 0L;
            if (!exact && gap > 0 && !pairs.has(ts) && ts - keptTs < gap) return;
            final XiaomiLofic.Pair done = pairs.store(stream, ts, b, used);
            if (done == null && stream != XiaomiLofic.Stream.NONE) keptTs = ts;
            if (done != null && exact && XiaomiLofic.onPair(done.longImage.rows, done.shortImage.rows, ts) == XiaomiLofic.Verdict.FAILED)
                host.setupFailed();
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "XIAOMI LOFIC: image not read: " + e + " (P679)");
        } finally {
            if (img != null) img.close();
        }
    }

    /**
     * A shot of a LOFIC session: the newest pairs before the press (ExposureCount 2: the Short read usable) as the shot's N
     * frames, each Long frame unpacked (14 bit) with its Short read along (ImageFrame.lofic, merged by ScamProcessor before the
     * burst merge). Without a pair of ExposureCount 2 the Long frames alone. Throws while the setup is not proven or the ring is
     * empty (the shot is refused, as P316's empty ring).
     */
    Drained drain(int requested, long scamZslShutterTimestamp, Map<Long, Double> exposureByFrame) {
        final XiaomiLofic.Setup s = XiaomiLofic.setup();
        if (s == null || !XiaomiLofic.proven()) {
            Log.w("SCAM_CAPTURE", "XIAOMI LOFIC shot refused: the setup is not proven yet (" + XiaomiLofic.lastFailure() + ") (P679)");
            throw new IllegalStateException(Lang.t("LOFIC: камера ещё готовится. Повторите снимок.", "LOFIC: the camera is still getting ready. Take the shot again."));
        }
        final int want = Math.max(1, Math.min(requested > 0 ? requested : XiaomiLofic.PAIRS, XiaomiLofic.pairsWanted()));
        this.pairs.freeze();
        final String before = this.pairs.describe();
        final boolean usable = this.pairs.usableCount() > 0;
        final List<XiaomiLofic.Pair> pairs = usable ? this.pairs.take(want, scamZslShutterTimestamp)
                : this.pairs.takeLongOnly(want, scamZslShutterTimestamp);
        Log.i("SCAM_HDR", "XIAOMI LOFIC shot (" + s.label + "): " + pairs.size() + " of " + want + " pairs wanted"
                + (usable ? " with ExposureCount 2" : " - no pair with ExposureCount 2: the Long frames ALONE") + "; ring " + before
                + "; cutoff " + scamZslShutterTimestamp + " (P679)");
        if (pairs.isEmpty())
            throw new IllegalStateException(Lang.t("LOFIC: нет кадров. Повторите снимок.", "LOFIC: no frames yet. Take the shot again."));
        Allocator.binning = false; // the Short read has the Long frame's grid
        final List<ImageFrame> out = new ArrayList<>();
        for (XiaomiLofic.Pair p : pairs) {
            final XiaomiLofic.Image l = p.longImage;
            final ImageFrame f = new ImageFrame(l.data, com.scamera.app.util.RawUnpack.RAW14, XiaomiLofic.RAW_W, XiaomiLofic.LONG_ROW, 0,
                    l.rows * XiaomiLofic.LONG_ROW);
            f.timestamp = l.timestamp;
            f.fromZsl = true;
            f.sourceFormat = ImageFormat.RAW_SENSOR;
            f.setCaptureMetadata(p.result);
            f.width = XiaomiLofic.RAW_W;
            f.height = l.rows;
            if (usable && p.shortImage != null) {
                f.lofic = new ImageFrame.Lofic();
                f.lofic.shortPacked = p.shortImage.data;
                f.lofic.rows = p.shortImage.rows;
            }
            exposureByFrame.put(f.timestamp, f.measuredExposure > 0 && f.measuredIso > 0 ? f.measuredExposure / 1e9 * f.measuredIso : 1.0);
            out.add(f);
            Log.i("SCAM_HDR", "XIAOMI LOFIC pair " + l.timestamp + ": Long " + l.rows + " rows, Short "
                    + (p.shortImage == null ? "none" : p.shortImage.rows + " rows") + ", ExposureCount " + p.exposureCount + ", exposure "
                    + f.measuredExposure + " ns ISO " + f.measuredIso + " (P679)");
        }
        out.sort(java.util.Comparator.comparingLong(f -> f.timestamp));
        final CaptureResult newest = pairs.get(0).result; // the newest pair
        return new Drained(out, newest instanceof TotalCaptureResult ? (TotalCaptureResult) newest : null);
    }
}
