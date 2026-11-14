package com.scamera.app.processing;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.media.Image;
import android.util.Pair;

import com.scamera.app.capture.CaptureController;
import com.scamera.app.capture.RawFrameQuality;
import com.scamera.app.control.GyroBurst;
import com.scamera.app.processing.parameters.IsoExpoSelector;
import com.scamera.app.processing.render.NoiseModeler;
import com.scamera.app.util.Allocator;
import com.scamera.app.util.Log;
import com.scamera.app.util.RawUnpack;
import com.scamera.app.util.ShotArena;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * One frame of a burst as the merge takes it: plain 16-bit samples in native memory (the shot's arena, a native copy, or a view
 * of its ZSL ring slot), with what the capture knows of it - its result, role, exposure and noise. A ZSL ring frame may stay in
 * the reader until {@link #materialize} copies it (the bracket can be submitted before ~20 RAWs are copied out).
 *
 * <p>The host checks (tools/check_scam_reference_metadata.py and its users) compile this class alone with stubs.
 */
public class ImageFrame {
    /** Packed RAW10 / RAW12 (ImageFormat.RAW10 / RAW12). */
    private static final int RAW10 = 0x25, RAW12 = 0x26;

    /** The samples (null after close, or for a frame refused before its copy). */
    public ByteBuffer buffer;
    /** The frame's size in pixels and its place in the burst (the logs). */
    public int width, height, number;
    /** The sensor timestamp; the frame's result is the one with this SENSOR_TIMESTAMP. The frame came from the ZSL ring. */
    public long timestamp;
    public boolean fromZsl;
    /** The exposure pair the frame was requested with, and the gyro's shake over its exposure (ScamProcessor). */
    public IsoExpoSelector.ExpoPair pair;
    public GyroBurst frameGyro;
    /** RawPayloadCheck verdict of the reader Image this frame was copied from; null = plain 16-bit. */
    public String rawPayloadError;
    /**
     * P72: format the RAW was read in (the stream's, or the packed layout behind RAW_SENSOR: RAW10, RawUnpack.RAW14*). The 17
     * Ultra tele in in-sensor zoom alternates RAW10 and RAW14 frames; one merge takes frames of one layout only.
     */
    public int sourceFormat;
    /** P646 «Склейка YUV»: bits of a YUV frame's packed planar samples (8, 10; 0: a RAW frame), and the session's HLG10 stream. */
    public int yuvBits;
    public boolean yuvHlg;
    /** P667: the YCbCr range the camera's Image declares (its dataspace, Android 13): 0 not declared, 1 full, 2 limited. */
    public int yuvRange;
    /** P646: packs a deferred YUV ring frame's three planes at its copy (null: the RAW copy of plane 0). */
    public java.util.function.Supplier<ByteBuffer> yuvPacker;
    /** SCAM: L exposure ratio for an L built from the ZSL N frames (0 = L was captured). */
    public float syntheticLongRatio;
    /**
     * Relative gradient energy of this frame, NaN until computeSharpness() runs.
     * Comparable only between frames of equal exposure and ISO: the measure is
     * normalised by mean level, not by the noise model, so a brighter or noisier
     * frame would score higher for reasons unrelated to focus or motion blur.
     */
    public float sharpness = Float.NaN;
    /** From the frame's own result ({@link #setCaptureMetadata}): exposure (ns), ISO, the HAL noise model per sample. */
    public long measuredExposure;
    public int measuredIso;
    public float noiseSlope = Float.NaN, noiseOffset = Float.NaN;
    /** P27: sensor samples averaged into one sample of this frame (RawBin; 1 = as captured). Divides the noise model. */
    public int binnedSamples = 1;
    public float focusDiopters = Float.NaN;
    public boolean lensMoving;
    public double blurPixels = Double.NaN;
    private CaptureResult captureMetadata;

    public enum CaptureRole { NORMAL, LONG, SHORT, EXTRA_SHORT }

    /** The tag of a SCAM bracket request: its series, its place in it and its role. */
    public static final class ScamCaptureTag {
        public final long generation;
        public final int index;
        public final CaptureRole role;

        public ScamCaptureTag(long generation, int index, CaptureRole role) {
            if (role == null || index < 0) throw new IllegalArgumentException("Invalid SCAM request tag");
            this.role = role;
            this.index = index;
            this.generation = generation;
        }

        @Override public String toString() {
            return role + " series=" + generation + " index=" + index;
        }
    }

    /**
     * P27: the role this post-shutter frame really has by its measured exposure when the HAL delivered another exposure than
     * the request asked for (HybridPlan.classify, set by ScamCaptureSequence.bindAndValidate); null = the request's role.
     */
    public CaptureRole measuredRole;

    /**
     * P641d: the overflow read of a DCG + LOFIC exposure travels with its main frame until the merge (the packed frame of the
     * second stream and what the pair measured; LoficPair) ...
     */
    public Overflow overflow;

    /**
     * The overflow frame a shot's main frame takes along: a copy of the packed frame and the ratio the session proved (filled by
     * capture.LoficPair; kept here so that this class stands without the capture package - the host checks compile it alone).
     */
    public static final class Overflow {
        public ByteBuffer packed;
        public int width, height, stride, firstRow;
        /** The session's proven ratio (0: none): the shot's own measure has to agree with it. */
        public double sessionRatio;
        /** The variant that ran, for the shot's log. */
        public String rung = "";
    }

    /**
     * P679: the Short read of a Xiaomi 17 Ultra LOFIC pair travels with its Long frame (this frame) until the shot merges the pair
     * into one frame before the burst merge (processing.LoficMerge); null on every other frame.
     */
    public Lofic lofic;

    /** The Short image of a LOFIC pair: packed RAW10 rows back to back, its rows and the ratio the shot measured (0: not yet). */
    public static final class Lofic {
        public ByteBuffer shortPacked;
        public int rows;
        public double k;
    }

    /** ... and, as a frame of the burst, names that main frame: its metadata is the main frame's (one exposure, one result). */
    public ImageFrame pairedMain;

    // ---- the deferred ZSL copy ----

    /** The reader's frame until {@link #materialize}, and how its rows are to be copied. */
    private RingFrame pending;
    private int pendingFormat, pendingWidth, pendingStride, pendingShift, pendingCapacity;
    private boolean pendingBinning;
    /** P91: the frame is a view of its ZSL ring slot (no copy was made). */
    public boolean zeroCopy;
    /** P30: the shot's arena the deferred copy goes into (null: a native copy). */
    public ShotArena arena;

    private ImageFrame() {
    }

    /** A copy of {@code in}'s rows (see the 7-argument constructor), without an arena. */
    public ImageFrame(ByteBuffer in, int format, int width, int row_stride, int shift, int capacity) {
        this(in, format, width, row_stride, shift, capacity, null);
    }

    /**
     * {@code capacity} bytes of rows {@code row_stride} apart from byte {@code shift} of {@code in}, {@code width} pixels each, as
     * plain uint16 (packed formats unpacked, binned 2x2 under Allocator.binning). P30: straight into the shot's arena when there
     * is one, else a native copy.
     */
    public ImageFrame(ByteBuffer in, int format, int width, int row_stride, int shift, int capacity, ShotArena arena) {
        buffer = copied(in, format, width, row_stride, shift, capacity, Allocator.binning, arena);
    }

    /** A native copy of the whole of {@code in} as it is. */
    public ImageFrame(ByteBuffer in) {
        buffer = rewound(Allocator.allocateAndCopy(in.capacity(), in, 0));
    }

    /** P609: a frame over a buffer already made (TetraBin's binned still: an arena view or a native buffer), no copy; null: none. */
    public static ImageFrame wrap(ByteBuffer buffer, int width, int height) {
        final ImageFrame f = new ImageFrame();
        f.buffer = buffer;
        f.width = width;
        f.height = height;
        return f;
    }

    public static ImageFrame deferred(Image image, int format, int width, int rowStride, int shift, int capacity) {
        return deferred(RingFrame.of(image), format, width, rowStride, shift, capacity);
    }

    /** P91: a ZSL ring frame (the camera's Image or its copy in the ring's memfd), copied when {@link #materialize} runs. */
    public static ImageFrame deferred(RingFrame image, int format, int width, int rowStride, int shift, int capacity) {
        final ImageFrame f = new ImageFrame();
        f.pending = image;
        f.pendingFormat = format;
        f.pendingWidth = width;
        f.pendingStride = rowStride;
        f.pendingShift = shift;
        f.pendingCapacity = capacity;
        f.pendingBinning = Allocator.binning;
        return f;
    }

    private static ByteBuffer rewound(ByteBuffer b) {
        b.position(0);
        return b;
    }

    /**
     * The rows as plain uint16 at position 0: the arena's copy (not when binning), P72 a packed RAW14 unpacked here (the native
     * library has no binning copy for it), else the native library's copy - RAW10 / RAW12 unpacked, binned when asked.
     */
    private static ByteBuffer copied(ByteBuffer in, int format, int width, int stride, int shift, int capacity, boolean binning,
                                     ShotArena arena) {
        if (arena != null && !binning) {
            final ByteBuffer inArena = Allocator.isPackedRaw(format)
                    ? arena.copyUnpacked(in, shift, format, width, stride, capacity) : arena.copy(in, shift, capacity);
            if (inArena != null) return rewound(inArena);
        }
        if (RawUnpack.isRaw14(format)) return unpackRaw14(in, shift, format, width, stride, capacity, binning);
        final ByteBuffer out;
        switch (format) {
            case RAW10:
                out = binning ? Allocator.allocateAndCopyConvertBinning(capacity, in, width, stride, shift)
                        : Allocator.allocateAndCopyConvert(capacity, in, width, stride, shift);
                break;
            case RAW12:
                out = binning ? Allocator.allocateAndCopyConvert12Binning(capacity, in, width, stride, shift)
                        : Allocator.allocateAndCopyConvert12(capacity, in, width, stride, shift);
                break;
            default:
                out = binning ? Allocator.allocateAndCopyBinning(capacity, in, width, capacity / stride, stride)
                        : Allocator.allocateAndCopy(capacity, in, shift);
        }
        return rewound(out);
    }

    /**
     * P72: a packed RAW14 frame ({@code capacity} bytes of rows from {@code offset}) to uint16, binned 2x2 when asked. P681: the
     * unpacked rows are native (Allocator; the frame's close frees them there - a Java heap buffer went to Allocator.free, which
     * is no Java buffer's free), and the unbinned rows are freed as soon as the binned copy is made, not left to a GC.
     */
    private static ByteBuffer unpackRaw14(ByteBuffer in, int offset, int format, int width, int rowStride, int capacity, boolean binning) {
        final int rows = capacity / rowStride;
        final int bytes = width * rows * 2;
        final ByteBuffer from = in.duplicate();
        from.position(offset);
        final ByteBuffer plain = Allocator.allocate(bytes);
        if (plain == null) throw new IllegalStateException("no memory for the unpacked RAW14 frame (" + (bytes >> 20) + " MB)");
        plain.order(ByteOrder.nativeOrder());
        final ByteBuffer out;
        try {
            if (!RawUnpack.unpack(from, format, width, rows, rowStride, plain))
                throw new IllegalStateException("RAW14 frame does not fit its geometry");
            out = binning ? Allocator.allocateAndCopyBinning(bytes, plain, width, rows, width * 2) : plain;
        } catch (RuntimeException | Error e) {
            Allocator.free(plain);
            throw e;
        }
        if (out != plain) Allocator.free(plain);
        return out == null ? null : rewound(out);
    }

    /** Bytes the deferred copy will take (0 when not deferred): a packed RAW10 / RAW12 frame is unpacked to 16 bit. */
    public int pendingBytes() {
        if (pending == null) return 0;
        if (Allocator.isPackedRaw(pendingFormat) && pendingStride > 0) return pendingWidth * (pendingCapacity / pendingStride) * 2;
        return pendingCapacity;
    }

    /** P91: the deferred frame can go to the worker as a view of its ring slot (plain RAW16, no crop / binning / unpack). */
    public boolean viewable() {
        if (pending == null || pending.arenaSlot() == null) return false;
        if (pendingBinning || pendingShift != 0) return false;
        if (Allocator.isPackedRaw(pendingFormat) || RawUnpack.isRaw14(pendingFormat)) return false;
        final int rows = pendingStride > 0 ? pendingCapacity / pendingStride : 0;
        final int rowBytes = pendingWidth * 2;
        return pendingStride == rowBytes && pendingCapacity == rowBytes * rows;
    }

    /** Copies a deferred frame out of the ImageReader and releases its slot. */
    public synchronized void materialize() {
        final RingFrame source = pending;
        if (source == null) return;
        try {
            if (yuvPacker != null) {
                buffer = yuvPacker.get(); // P646: a YUV ring frame keeps its colour planes (packed planar)
                return;
            }
            // P91: a plain RAW16 frame in the ring's memfd goes to the worker as one more view of its slot (pinned until the
            // merge frees it): no copy after the press
            if (viewable()) {
                final long[] slot = source.arenaSlot();
                final ByteBuffer view = Allocator.arenaView((int) slot[0], slot[1], pendingCapacity);
                if (view != null) {
                    buffer = rewound(view);
                    zeroCopy = true;
                    return;
                }
            }
            buffer = copied(source.buffer(), pendingFormat, pendingWidth, pendingStride, pendingShift, pendingCapacity,
                    pendingBinning, arena);
        } finally {
            source.close();
            pending = null;
        }
    }

    /** The frame's role in the bracket: from its own result's request (its tag), the measured one, NORMAL for ZSL; null: unknown. */
    public CaptureRole getCaptureRole() {
        final CaptureResult own = getMatchedCaptureMetadata();
        if (own == null) return null;
        if (fromZsl) return CaptureRole.NORMAL;
        if (measuredRole != null) return measuredRole;
        final CaptureRequest request = own.getRequest();
        final Object tag = request != null ? request.getTag() : null;
        if (tag instanceof ScamCaptureTag) return ((ScamCaptureTag) tag).role;
        if (tag instanceof CaptureRole) return (CaptureRole) tag;
        return null;
    }

    /** This RAW's own result (full calibration comes from it, never the burst's last callback); null when none matches. */
    public CaptureResult getMatchedCaptureMetadata() {
        if (pairedMain != null) return pairedMain.getMatchedCaptureMetadata(); // P641d
        final CaptureResult r = captureMetadata;
        if (r == null) return null;
        final Long at = r.get(CaptureResult.SENSOR_TIMESTAMP);
        return at != null && at == timestamp ? r : null;
    }

    /**
     * Takes {@code result} as the frame's own: the exposure, ISO, focus and the HAL noise model (the channels' mean; P473 the
     * offset in sensor units by the declared white level, NoiseModeler.halOffset). A null result keeps the one taken: a ZSL frame
     * already owns its result and a second drain of the metadata map can return null.
     */
    public void setCaptureMetadata(CaptureResult result) {
        if (result == null) return;
        captureMetadata = result;
        final Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        final Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
        measuredExposure = exposure != null ? exposure : 0;
        measuredIso = iso != null ? iso : 0;
        takeNoiseModel(result.get(CaptureResult.SENSOR_NOISE_PROFILE));
        final Float focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
        final Integer lens = result.get(CaptureResult.LENS_STATE);
        focusDiopters = focus != null ? focus : Float.NaN;
        lensMoving = lens != null && lens == CaptureResult.LENS_STATE_MOVING;
    }

    private void takeNoiseModel(Pair<Double, Double>[] channels) {
        noiseSlope = Float.NaN;
        noiseOffset = Float.NaN;
        if (channels == null || channels.length == 0) return;
        double s = 0, o = 0;
        for (Pair<Double, Double> c : channels) {
            if (c == null || c.first == null || c.second == null) return; // incomplete: no model
            s += c.first;
            o += c.second;
        }
        if (!Double.isFinite(s + o) || s < 0 || o < 0) return;
        final CameraCharacteristics camera = CaptureController.mCameraCharacteristics;
        final Integer white = camera != null ? camera.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) : null;
        noiseSlope = (float) (s / channels.length);
        noiseOffset = (float) NoiseModeler.halOffset(o / channels.length, white != null ? white : 0);
    }

    public void computeSharpness() {
        computeSharpness(1);
    }

    /** {@link #sharpness} measured over blocks of {@code block} px (RawFrameQuality on the uint16 samples). */
    public void computeSharpness(int block) {
        sharpness = (float) RawFrameQuality.score(buffer, width, height, width * 2, 2, block);
    }

    public long getTimestamp() {
        return timestamp;
    }

    /** Releases the reader's frame of a deferred copy and the samples; any number of times. */
    public synchronized void close() {
        final RingFrame source = pending;
        pending = null;
        if (source != null) source.close();
        final ByteBuffer samples = buffer;
        buffer = null;
        if (samples != null) Allocator.free(samples);
        else Log.d("ImageFrame", "Buffer is already null, nothing to close.");
    }
}
