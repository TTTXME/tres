package com.scamera.app.processing.yuv;

import java.nio.ByteBuffer;

/**
 * P646 «Склейка YUV»: the native merge (library scameraYuv: app/src/main/cpp/scam-yuv-merge.h behind scam-yuv-jni.cpp). Frames
 * are packed planar 4:2:0 (Y, then U, then V; 8-bit samples or 16-bit native-order samples holding the value in their low bits).
 */
public final class YuvMergeNative {
    private YuvMergeNative() {}

    private static volatile Boolean loaded;

    public static synchronized boolean load() {
        if (loaded != null) return loaded;
        try {
            System.loadLibrary("scameraYuv");
            loaded = true;
        } catch (Throwable t) {
            loaded = false;
        }
        return loaded;
    }

    /** The planes of a camera Image into {@code out} (packed planar); {@code shift}: the 16-bit samples' right shift (P010: 6). */
    public static native boolean pack(ByteBuffer y, ByteBuffer u, ByteBuffer v, int yRow, int yPixel, int uRow, int uPixel,
                                      int vRow, int vPixel, int width, int height, int sampleBytes, int shift, ByteBuffer out);

    /**
     * The burst to linear RGB (float x 3 per pixel, native order) in {@code outRgb} and the merged frame count per pixel in
     * {@code counts} (one byte each, may be null); {@code settings} / {@code stats}: see scam-yuv-jni.cpp. Returns the report line;
     * throws IllegalStateException on invalid input.
     */
    public static native String merge(ByteBuffer[] frames, int width, int height, int bits, float[] ratios, int[] roles,
                                      float[] settings, float[] curve, float[] noise, ByteBuffer outRgb, ByteBuffer counts,
                                      float[] stats);
}
