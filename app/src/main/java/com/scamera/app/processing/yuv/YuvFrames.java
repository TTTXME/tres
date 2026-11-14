package com.scamera.app.processing.yuv;

import android.graphics.ImageFormat;
import android.media.Image;

import androidx.annotation.Nullable;

import com.scamera.app.processing.ImageFrame;
import com.scamera.app.util.Allocator;
import com.scamera.app.util.Log;

import java.nio.ByteBuffer;

/**
 * P646 «Склейка YUV»: a camera YUV_420_888 / YCBCR_P010 Image as a burst frame - its three planes packed planar into one native
 * buffer (YuvMergeNative.pack): 8-bit samples, or for P010 16-bit samples of the 10-bit value. The frame's {@link ImageFrame#yuvBits}
 * says which; {@link ImageFrame#yuvHlg} that the stream is the session's HLG10 one (YuvMergeRoute).
 */
public final class YuvFrames {
    private YuvFrames() {}

    static final String TAG = "SCAM_CAPTURE";
    public static final int P010 = 0x36; // ImageFormat.YCBCR_P010 (API 31)

    /** The frame formats of the YUV route. */
    public static boolean isYuv(int format) {
        return format == ImageFormat.YUV_420_888 || format == P010;
    }

    /** Bytes of a packed frame of {@code width} x {@code height} at {@code bits}. */
    public static long packedBytes(int width, int height, int bits) {
        final long samples = (long) width * height + 2L * ((width + 1) / 2) * ((height + 1) / 2);
        return samples * (bits > 8 ? 2 : 1);
    }

    /** The session's P010 stream is HLG10 (set by the camera when it configured it). */
    public static volatile boolean sessionHlg;

    /** The packed copy of {@code image} (a native buffer the frame's close frees), null when it cannot be packed (logged). */
    @Nullable
    public static ByteBuffer pack(Image image) {
        try {
            final Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length < 3) return null;
            final int w = image.getWidth(), h = image.getHeight();
            final boolean ten = image.getFormat() == P010;
            final long bytes = packedBytes(w, h, ten ? 10 : 8);
            if (bytes > Integer.MAX_VALUE || !YuvMergeNative.load()) return null;
            final ByteBuffer out = Allocator.allocate((int) bytes);
            if (out == null) return null;
            final boolean ok = YuvMergeNative.pack(planes[0].getBuffer(), planes[1].getBuffer(), planes[2].getBuffer(),
                    planes[0].getRowStride(), planes[0].getPixelStride(), planes[1].getRowStride(), planes[1].getPixelStride(),
                    planes[2].getRowStride(), planes[2].getPixelStride(), w, h, ten ? 2 : 1, ten ? 6 : 0, out);
            if (!ok) {
                Allocator.free(out);
                Log.w(TAG, "YUV MERGE: frame " + image.getTimestamp() + " " + w + "x" + h + " format " + image.getFormat()
                        + ": its planes do not fit their strides, dropped");
                return null;
            }
            out.position(0);
            return out;
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "YUV MERGE: frame not packed: " + e);
            return null;
        }
    }

    /** A burst frame of {@code image} (its timestamp and size; no buffer when it could not be packed), null when not YUV. */
    @Nullable
    public static ImageFrame frame(Image image) {
        if (!isYuv(image.getFormat())) return null;
        final ByteBuffer packed = pack(image);
        final ImageFrame frame = ImageFrame.wrap(packed, image.getWidth(), image.getHeight());
        frame.timestamp = image.getTimestamp();
        frame.sourceFormat = image.getFormat();
        mark(frame, image.getFormat(), dataSpace(image));
        if (packed == null) frame.rawPayloadError = "YUV frame not packed";
        return frame;
    }

    /** A deferred ZSL ring frame of a YUV stream: its copy packs the three planes of the ring's Image (P91 holds them as Images). */
    public static void deferPack(ImageFrame frame, com.scamera.app.processing.RingFrame ring, int format) {
        if (!isYuv(format)) return;
        mark(frame, format, dataSpace(ring.image()));
        frame.yuvPacker = () -> ring.image() == null ? null : pack(ring.image());
    }

    /** The YUV fields of a frame of {@code format}. */
    public static void mark(ImageFrame frame, int format) {
        mark(frame, format, 0);
    }

    /**
     * The YUV fields of a frame of {@code format} whose Image declares {@code dataSpace} (0: none). P667: the HLG flag also from
     * the dataspace's transfer, and the YCbCr range from its range: Camera2 tags an HLG10 stream DataSpace.DATASPACE_BT2020_HLG,
     * which is FULL range - the merge guessed narrow from the frame's Y extremes, and a scene without deep blacks or clipped
     * whites (a room) looked narrow, its highlights decoded up to x1.4 too bright through HLG's exponential top.
     */
    public static void mark(ImageFrame frame, int format, int dataSpace) {
        frame.yuvBits = format == P010 ? 10 : 8;
        frame.yuvHlg = format == P010 && (sessionHlg || transfer(dataSpace) == TRANSFER_HLG);
        frame.yuvRange = range(dataSpace);
    }

    /** android.hardware.DataSpace fields (API 33): TRANSFER_HLG, RANGE_FULL, RANGE_LIMITED. */
    public static final int TRANSFER_HLG = 8, RANGE_FULL = 1, RANGE_LIMITED = 2;

    public static int transfer(int dataSpace) {
        return (dataSpace >>> 22) & 0x1f;
    }

    /** 1 full, 2 limited, 0 not declared (or extended). */
    public static int range(int dataSpace) {
        final int r = (dataSpace >>> 27) & 0x7;
        return r == RANGE_FULL || r == RANGE_LIMITED ? r : 0;
    }

    /** The Image's dataspace (Android 13), 0 before or when it cannot be read. */
    static int dataSpace(@Nullable Image image) {
        if (image == null || android.os.Build.VERSION.SDK_INT < 33) return 0;
        try {
            return image.getDataSpace();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * P667: the merge's decode of frames that are {@code hlg} with the declared {@code range} (YuvMergeNative.merge's setting 14):
     * 0 JFIF BT.601 full range (8-bit YUV_420_888), 1 BT.2020 narrow unless the frame's Y extremes say full (no range declared),
     * 2 BT.2020 full range, 3 BT.2020 narrow range (declared).
     */
    public static int decodeMode(boolean hlg, int range) {
        if (!hlg) return 0;
        return range == RANGE_FULL ? 2 : range == RANGE_LIMITED ? 3 : 1;
    }
}
