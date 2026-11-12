package com.particlesdevs.photoncamera.processing.oldstyle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * P630b «Oldstyle»: the owner's tone-port module (app/src/main/cpp/third_party/tone_port, unchanged; library scameraTone,
 * oldstyle-tone-jni.cpp) - the source of truth of the mode's tone. The app fills one planar int16 frame (the module's 12-bit
 * scale, {@link #WHITE} = white of the frame's exposure) from the GPU strip by strip ({@link #deinterleave}) and calls
 * {@link #render} once on the whole frame (the spatial operator and 3.1's shadow curve read the whole frame).
 */
public final class OldstyleNative {
    private OldstyleNative() {}

    /** The module's white at unity gain. */
    public static final int WHITE = 4095;
    /** Profiles of {@link #render}. */
    public static final int V1 = 1, V2 = 2;

    private static volatile Boolean loaded;

    /** Loads scameraTone (a host test points system property scamera.tone.lib at its own build). */
    public static synchronized boolean load() {
        if (loaded != null) return loaded;
        try {
            final String host = System.getProperty("scamera.tone.lib");
            if (host != null) System.load(host); else System.loadLibrary("scameraTone");
            loaded = true;
        } catch (Throwable t) {
            loaded = false;
        }
        return loaded;
    }

    /** The planar frame of {@code width} x {@code height} (three int16 planes), direct, native order. */
    public static ByteBuffer planar(int width, int height) {
        return ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(width, height), 6)).order(ByteOrder.nativeOrder());
    }

    /** Copies {@code rows} rows of RGBA int32 pixels ({@code strip}: the RGBA16I frame read as GL_INT) into {@code planar} at row {@code y0}. */
    public static native void deinterleave(ByteBuffer strip, ByteBuffer planar, int width, int height, int y0, int rows);

    /** P636: the same from RGBA int16 pixels (the frame read as GL_SHORT where the driver names that pair: half the bytes). */
    public static native void deinterleave16(ByteBuffer strip, ByteBuffer planar, int width, int height, int y0, int rows);

    /**
     * P636 (speed, the same bytes): the threads of the module's and the stages' row loops. 1: the single-thread path of P630e
     * (the reference of the banded one); 0 or less: the cores, at most 8. Returns the count in use.
     */
    public static native int setThreads(int count);

    /**
     * The threads of a shot's tone: scam_dev "oldstyle_threads" when set (1: the one-thread path as before P636), else the
     * cores less one (the viewfinder's), 2 .. 8.
     */
    public static int threadsFor(int cores, float dev) {
        final int asked = Math.round(dev);
        if (asked >= 1) return Math.min(16, asked);
        return Math.max(2, Math.min(8, cores - 1));
    }

    /**
     * The tone of the frame into {@code out} (width x height x 3 RGB8). {@code version}: {@link #V1} (profile 2.7: the planes are
     * linear RGB before the CCM, {@code ccm} row-major through the supplement's awb_ccm, finish_raw27_rgb, with {@code hdr} the
     * 2.7 HDR of its frame at {@code gainShort}) or {@link #V2} (profile 3.1: the planes are after the CCM, render_raw31_hdr /
     * _sdr). The gains are OldstyleAe.Plan's (fusion_gains over the frame's actual TET); without HDR {@code gainLong} is the one.
     * P630e: {@code post} (OldstyleAe.Params.post, null: none) runs the supplement's YUV-route stages on our route: the
     * low-frequency colour inside V1's HDR, then dehaze / tint and the output saturation on the RGB8; {@code shading} the dehaze's
     * U / V gain map ({@code shadingW} x {@code shadingH} x 2, null: the unit grid). {@code info} (null, 6 or 8 floats): the
     * low-frequency scale (-1 not run), the dehaze's shadow / white / dark cut, the module's and the stages' milliseconds;
     * P636: the threads used and the input gain's milliseconds (inside the module's).
     * P630h: {@code post} also carries the low-frequency colour's share and the tone curve's shape (entries 12 .. 14; a 12-entry
     * array: the module's own).
     */
    public static native int render(int version, ByteBuffer planar, int width, int height, float[] ccm, float gainShort,
                                    float gainLong, boolean hdr, boolean postview, float saturation, float[] post, float[] shading,
                                    int shadingW, int shadingH, float[] info, ByteBuffer out);

    /**
     * The supplement's plan in C++ (the host check's reference of OldstyleAe's Java port): {@code out} = {hdr scene, use HDR, S, L,
     * gS, gL}; profile 27 (plan27 + fusion_gains) or 31 (plan31 RAW + raw_hdr_gains_31).
     */
    public static native void plan(int profile, float shortCandidate, float longCandidate, float fractionLong, float actualShortTet,
                                   float maxGain, float[] out);

    /**
     * P630e: the viewfinder's tone LUT of {@code version} into {@code out} (1024 x RGBA8): RGB the module's tone of the neutral
     * linear i / 1023, A the long exposure's weight of the version's fusion at that level (255: all long).
     */
    // (P643b: the app no longer draws with this LUT - the viewfinder's preview is OldstyleLive's curve; the module's tone of a
    // neutral ramp stays for the host check of the module, tools/check_tone_port.py)
    public static native void previewLut(int version, byte[] out);

    /**
     * P630h: {@link #previewLut} with the tone curve shaped as the shot's ({@code curveMid}, {@code curveContrast}: OldstyleAe.Params;
     * 0, 0 the module's own curve - the same LUT as previewLut's).
     */
    public static native void previewLutCurve(int version, float curveMid, float curveContrast, byte[] out);

    /**
     * P630h: V1's colour matrix at a share of itself: identity + {@code strength} x (ccm - identity), row-major (1: the matrix, 0:
     * none - the white-balanced sensor colour; the rows' sums, the white, stay).
     */
    public static float[] ccmStrength(float[] ccm, float strength) {
        if (ccm == null || ccm.length != 9 || strength == 1f || !Float.isFinite(strength)) return ccm;
        final float[] out = new float[9];
        for (int i = 0; i < 9; i++) {
            final float id = i % 4 == 0 ? 1f : 0f;
            out[i] = id + strength * (ccm[i] - id);
        }
        return out;
    }

    /** Row-major a x b of 3 x 3. */
    public static float[] mul(float[] a, float[] b) {
        final float[] m = new float[9];
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 3; c++) m[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
        return m;
    }
}
