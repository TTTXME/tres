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
        mark(frame, image.getFormat());
        if (packed == null) frame.rawPayloadError = "YUV frame not packed";
        return frame;
    }

    /** A deferred ZSL ring frame of a YUV stream: its copy packs the three planes of the ring's Image (P91 holds them as Images). */
    public static void deferPack(ImageFrame frame, com.scamera.app.processing.RingFrame ring, int format) {
        if (!isYuv(format)) return;
        mark(frame, format);
        frame.yuvPacker = () -> ring.image() == null ? null : pack(ring.image());
    }

    /** The YUV fields of a frame of {@code format}. */
    public static void mark(ImageFrame frame, int format) {
        frame.yuvBits = format == P010 ? 10 : 8;
        frame.yuvHlg = format == P010 && sessionHlg;
    }
}
