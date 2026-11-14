package com.scamera.app.capture;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;

import androidx.annotation.Nullable;

import com.scamera.app.processing.ImageFrame;
import com.scamera.app.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * P641d: DCG + LOFIC as a pair of frames (LoficPair) - the overflow stream's reader (RAW10, vendor use case 524554) of a
 * «lofic-pair» session, its ring of overflow frames, the proof of a main frame against the overflow frame of its timestamp, and a
 * shot's partners. P707: moved out of {@link CaptureController} unchanged (the controller's session build, payload check, still
 * request and shot call these).
 */
final class LoficOverflowReader {
    /** The reader of the overflow stream (RAW10, vendor use case 524554) of a «lofic-pair» session; null in any other. */
    volatile ImageReader reader;
    /** The newest overflow frames, by sensor timestamp (copies; 8 x 15 MB at 12 MP). */
    private final LoficPair.Ring ring = new LoficPair.Ring(8);
    /** A checked main frame's blocks waiting for the overflow frame of the same timestamp, and its levels. */
    private LoficPair.Stats mainStats;
    private long mainTs;
    private float mainBlack;
    private int mainWhite;
    /** Frames of one exposure share the sensor timestamp; half a millisecond covers a HAL that stamps the streams apart. */
    static final long LOFIC_PAIR_NS = 500_000L;

    void close() {
        close(reader);
    }

    /**
     * P680: the overflow reader {@code r} closes (the one closeCamera took when it scheduled the close); its field and the pairs'
     * frames go only while it is still the session's (an open meanwhile may have made its own).
     */
    void close(@Nullable ImageReader r) {
        final boolean current = reader == r;
        if (current) reader = null;
        if (r != null) try { r.close(); } catch (RuntimeException ignored) { }
        if (current) synchronized (ring) {
            ring.clear();
            mainStats = null;
        }
    }

    /** The overflow stream's reader at the main RAW's size; false when it cannot be made here (the rung fails at once). */
    boolean open(@Nullable ImageReader raw, @Nullable Handler handler, String physicalID) {
        close();
        if (Build.VERSION.SDK_INT < 33 || raw == null || handler == null) return false;
        try {
            final ImageReader reader = ImageReader.newInstance(raw.getWidth(), raw.getHeight(), ImageFormat.RAW10, 3);
            reader.setOnImageAvailableListener(r -> {
                Image img = null;
                try {
                    img = r.acquireNextImage();
                    if (img == null) return;
                    final Image.Plane plane = img.getPlanes()[0];
                    final long ts = img.getTimestamp();
                    synchronized (ring) {
                        ring.store(ts, plane.getBuffer(), img.getWidth(), img.getHeight(), plane.getRowStride());
                        proof(ts);
                    }
                } catch (RuntimeException e) {
                    Log.w("SCAM_CAPTURE", "XIAOMI DCG+LOFIC: overflow frame not read: " + e);
                } finally {
                    if (img != null) img.close();
                }
            }, handler);
            this.reader = reader;
            Log.i("SCAM_CAPTURE", "XIAOMI DCG+LOFIC camera " + physicalID + ": overflow stream " + raw.getWidth() + "x" + raw.getHeight()
                    + " RAW10, use case 0x" + Long.toHexString(LoficPair.USE_CASE) + ", in the repeating request and the stills; its frames"
                    + " are kept by timestamp (8 newest) and proven against the main frames before any is used (P641d)");
            return true;
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "XIAOMI DCG+LOFIC camera " + physicalID + ": no reader for the overflow stream: " + e);
            return false;
        }
    }

    /** A checked main frame of a pair session: its blocks wait for (or meet) the overflow frame of its timestamp. */
    void mainFrame(Image img, @Nullable com.scamera.app.processing.RawPayloadCheck.Layout layout, CameraCharacteristics characteristics) {
        if (reader == null || !XiaomiDualGain.pairWanted() || img.getFormat() != ImageFormat.RAW_SENSOR
                || com.scamera.app.processing.RawLevels.knownBits() < 12) return;
        try {
            final java.nio.ByteBuffer buffer = img.getPlanes()[0].getBuffer();
            final LoficPair.Samples samples = layout == null ? LoficPair.plain16(buffer, img.getPlanes()[0].getRowStride(), 0)
                    : LoficPair.packed(buffer, layout.packedBits(), layout.stride, 0);
            final com.scamera.app.processing.RawLevels.Levels levels =
                    com.scamera.app.processing.RawLevels.current(characteristics, null);
            float black = 0f;
            for (float b : levels.black) black += b / levels.black.length;
            synchronized (ring) {
                mainStats = LoficPair.stats(samples, img.getWidth(), img.getHeight());
                mainTs = img.getTimestamp();
                mainBlack = black;
                mainWhite = levels.white;
                proof(mainTs);
            }
        } catch (RuntimeException e) {
            Log.w("SCAM_CAPTURE", "XIAOMI DCG+LOFIC: main frame not measured: " + e);
        }
    }

    /** Under ring: the waiting main frame and the overflow frame of {@code timestamp} are both here: the pair is measured. */
    private void proof(long timestamp) {
        final LoficPair.Stats main = mainStats;
        if (main == null || Math.abs(mainTs - timestamp) > LOFIC_PAIR_NS) return;
        final LoficPair.Frame f = ring.find(mainTs, LOFIC_PAIR_NS);
        if (f == null) return;
        mainStats = null;
        XiaomiDualGain.onPair(LoficPair.measure(main, mainBlack, mainWhite,
                LoficPair.stats(LoficPair.packed(f.data, 10, f.stride, 0), f.width, f.height), 1023), mainTs);
    }

    /**
     * A shot of a proven pair session: the newest N frames of the ring take the overflow frames of their timestamps along (a
     * copy of the packed frame; two at most - the merge takes HybridPlan.bentoFrames of them). The ring frames are copied later
     * (deferred), so each pair is measured where the burst is assembled, at the shot's levels and against the session's ratio
     * (ScamProcessor.addOverflowFrames): one that is no pair there is left out.
     */
    void attachPartners(List<ImageFrame> normals) {
        final double sessionRatio = XiaomiDualGain.pairRatio();
        if (reader == null || normals == null || normals.isEmpty()) return;
        if (!(sessionRatio > 0)) {
            if (XiaomiDualGain.pairWanted()) Log.i("SCAM_HDR", "DCG+LOFIC: the overflow pair is not proven in this session yet: the shot is"
                    + " the DCG frames alone (P641d)");
            return;
        }
        final List<ImageFrame> newest = new ArrayList<>(normals);
        newest.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        int made = 0, missing = 0;
        final String rung = "lofic-pair";
        synchronized (ring) {
            for (ImageFrame f : newest) {
                if (made >= 2) break;
                if (f == null || f.width <= 0 || f.height <= 0) continue;
                final LoficPair.Frame o = ring.find(f.timestamp, LOFIC_PAIR_NS);
                if (o == null || o.width != f.width) { missing++; continue; }
                // the shot's 16:9 cut of the main frame (Wide169) is the overflow frame's too
                final int first = o.height == f.height ? 0
                        : com.scamera.app.processing.Wide169.rows(o.width, o.height) == f.height
                        ? com.scamera.app.processing.Wide169.firstRow(o.width, o.height) : -1;
                if (first < 0) { missing++; continue; }
                try {
                    f.overflow = LoficPair.partner(o, f.width, f.height, first, sessionRatio, rung);
                    made++;
                } catch (RuntimeException | OutOfMemoryError e) {
                    Log.w("SCAM_HDR", "DCG+LOFIC: frame " + f.number + "'s overflow frame not taken: " + e);
                }
            }
        }
        Log.i("SCAM_HDR", "DCG+LOFIC shot: " + made + " of the " + normals.size() + " ring frames take their overflow frame along (rung "
                + rung + ", session ratio " + String.format(Locale.ROOT, "%.3f", sessionRatio) + ", " + missing + " without a frame of their"
                + " timestamp; overflow ring " + ring.describe() + "; P641d)");
    }
}
