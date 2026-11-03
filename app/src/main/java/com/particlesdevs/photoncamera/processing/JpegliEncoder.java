package com.particlesdevs.photoncamera.processing;

import android.graphics.Bitmap;

import com.particlesdevs.photoncamera.util.Log;

import java.io.IOException;
import java.io.OutputStream;

/**
 * P24: the saved JPEGs (plain photo, Ultra HDR base image and gain map) are encoded by jpegli (native, scamera-jpeg.cpp),
 * YCbCr 4:4:4: Bitmap.compress always subsamples chroma 4:2:0. On the OPPO PHY110 a 12.6 MP photo at q98 is 4.55 MB in
 * ~160 ms, the size Bitmap.compress q98 4:2:0 gave for the same shot.
 */
public final class JpegliEncoder {
    private static final String TAG = "JpegliEncoder";
    private static final boolean AVAILABLE;

    static {
        boolean loaded;
        try {
            System.loadLibrary("scameraJpeg");
            loaded = true;
        } catch (Throwable t) {
            Log.w(TAG, "jpegli unavailable, Bitmap.compress (4:2:0) is used: " + t);
            loaded = false;
        }
        AVAILABLE = loaded;
    }

    private JpegliEncoder() {}

    public static boolean available() {
        return AVAILABLE;
    }

    /**
     * Null on success, else the reason; bytes may already be in {@code out} when it fails. P147: {@code threads} > 1 encodes
     * a photo of 4 MP or more in strips on that many threads (one baseline JPEG with restart markers, the same pixels;
     * 3.6x faster and ~8 % smaller than the one-thread progressive file on the X200 Ultra, tools/jpeg-check); {@code mode[0]}
     * gets the threads used (1 = one thread).
     */
    private static native String encode(Bitmap bitmap, int quality, OutputStream out, int threads, int[] mode);

    /**
     * P443: jpegli's adaptive quantisation for the next encodes (scamera-jpeg-core.h): it raised the zero bias of the AC coefficients
     * in flat and dark blocks (~0.85 of a quantisation step instead of 0.59), and at q98 it erased the night shadows' fine detail and
     * half the grain of a dark sky that the reference app's photo keeps (host emulation with the app's tables on the night port
     * 230628: the fenders' high-pass +2.5 %, the hull +1.2 %, a dark sky +34 % of the grain the JPEG had erased). Off; scam_dev.txt
     * "jpeg_aq 1" = on, as before.
     */
    private static native void setAdaptiveQuantization(boolean on);

    /** P483: {@link #encode} of RGBA rows {@code stride} bytes apart in a direct buffer (the bytes of a bitmap holding these pixels). */
    private static native String encodeBuffer(java.nio.ByteBuffer src, int width, int height, int stride, int quality, OutputStream out, int threads, int[] mode);

    private static native void setFastHuffmanTables(boolean on);

    private static native boolean copyPixels(java.nio.ByteBuffer src, Bitmap dst, int threads);

    private static native boolean touchPixels(Bitmap dst, int threads);

    /** P473: the new bitmap's pages faulted in on four threads (its contents undefined until copyPixels); false: not done. */
    public static boolean touchPixels(Bitmap dst) {
        if (!AVAILABLE || dst == null) return false;
        try {
            return touchPixels(dst, 4);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /**
     * P473 (speed): {@code src} (direct, tightly packed RGBA8 rows from its start) into the RGBA_8888 {@code dst} on four threads,
     * the bytes Bitmap.copyPixelsFromBuffer would copy; false when the library or the buffers do not allow it (copy as before).
     */
    public static boolean copyPixels(java.nio.ByteBuffer src, Bitmap dst) {
        if (!AVAILABLE || src == null || !src.isDirect() || dst == null) return false;
        try {
            return copyPixels(src, dst, 4);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    static boolean adaptiveQuantization() {
        return com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("jpeg_aq", false);
    }

    /** P147: threads of the JPEG encode - every core, at most 8; scam_dev.txt "jpeg_threads" (1 = the one-thread encode). */
    static int threads() {
        final int cores = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        final int dev = Math.round(com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevNumber("jpeg_threads", 0f));
        return dev >= 1 ? Math.min(dev, 16) : cores;
    }

    /**
     * Writes {@code bitmap} as a 4:4:4 JPEG. Throws when the native encoder is missing or failed: the caller retries with
     * {@link #compressFallback} on a fresh stream (a failed encode may have written part of a file).
     */
    public static void compress(Bitmap bitmap, int quality, OutputStream out) throws IOException {
        if (!AVAILABLE) throw new IOException("jpegli unavailable");
        if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) throw new IOException("jpegli needs ARGB_8888, got " + bitmap.getConfig());
        compress(bitmap, null, bitmap.getWidth(), bitmap.getHeight(), 0, quality, out);
    }

    /**
     * P483 (speed): {@link #compress} of the tightly packed RGBA rows of the direct buffer {@code rgba} ({@code width} x
     * {@code height}: the final readback's mapped pixel-pack buffer) - the same settings and the same bytes as a bitmap of these
     * pixels, without the bitmap and the copy into it. Throws as {@link #compress} (the caller has the pixels for a retry).
     */
    public static void compress(java.nio.ByteBuffer rgba, int width, int height, int quality, OutputStream out) throws IOException {
        compress(rgba, width, height, 4 * width, quality, out);
    }

    /**
     * P483: as above for rows {@code stride} bytes apart from the buffer's start - a sub-rectangle of a frame as a slice at its first
     * pixel (the ISZ 4:3 crop: the bytes of Bitmap.createBitmap(source, x, y, w, h) encoded).
     */
    public static void compress(java.nio.ByteBuffer rgba, int width, int height, int stride, int quality, OutputStream out) throws IOException {
        if (!AVAILABLE) throw new IOException("jpegli unavailable");
        if (rgba == null || !rgba.isDirect() || width <= 0 || height <= 0 || stride < 4 * width
                || (long) rgba.capacity() < (long) (height - 1) * stride + 4L * width) throw new IOException("jpegli: no direct RGBA buffer");
        compress(null, rgba, width, height, stride, quality, out);
    }

    private static void compress(Bitmap bitmap, java.nio.ByteBuffer rgba, int width, int height, int stride, int quality, OutputStream out) throws IOException {
        final int[] mode = {1};
        final long started = System.nanoTime();
        final boolean aq = adaptiveQuantization();
        try {
            setAdaptiveQuantization(aq);
        } catch (UnsatisfiedLinkError old) {
            Log.w(TAG, "jpegli without the adaptive quantisation switch: " + old);
        }
        boolean fastTables = true;
        try {
            fastTables = com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("jpeg_fast_tables", true);
            setFastHuffmanTables(fastTables);
        } catch (UnsatisfiedLinkError old) {
            fastTables = false;
            Log.w(TAG, "jpegli without the Huffman statistics switch: " + old);
        }
        final String error;
        try {
            error = bitmap != null ? encode(bitmap, quality, out, threads(), mode) : encodeBuffer(rgba, width, height, stride, quality, out, threads(), mode);
        } catch (UnsatisfiedLinkError old) {
            throw new IOException("jpegli without the buffer entry: " + old);
        }
        if (error != null) throw new IOException("jpegli: " + error);
        Log.d(TAG, "jpegli " + width + "x" + height + (bitmap != null ? "" : " (mapped buffer)") + " q" + quality + " "
                + (mode[0] > 1 ? "strips on " + mode[0] + " threads" : "one thread") + (aq ? " aq" : "")
                + (mode[0] > 1 && fastTables ? " token reuse allowed" : "") + " ms=" + (System.nanoTime() - started) / 1000000);
    }

    /** Android's encoder (4:2:0), the fallback when jpegli failed. */
    public static void compressFallback(Bitmap bitmap, int quality, OutputStream out) throws IOException {
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out))
            throw new IOException("JPEG encoder failed for " + bitmap.getWidth() + "x" + bitmap.getHeight());
    }
}
