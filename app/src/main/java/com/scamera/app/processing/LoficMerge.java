package com.scamera.app.processing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Locale;

/**
 * P679: a LOFIC pair of the Xiaomi 17 Ultra (capture.XiaomiLofic) merged into one frame before our burst merge: the Long frame
 * (14 bit, black 1024, white 16383) and its Short read (10 bit, black 64, white 1023, the same moment, pixel-aligned: no motion
 * between them, nothing to align) become one linear frame on the Long scale, M = the noise-weighted mean of L and k x S.
 * <p>
 * Weights by the noise of the two reads at the pixel's level (the Long read's own noise model; k x S's from the photons of a read
 * 12.55 times shorter plus the Short read noise of 0.75 DN times k): under {@link #TAPER_LO} of the Long range the
 * inverse-variance mean (the Long read rules: k x S is 4 - 9 times noisier there); from it up to {@link #TAPER_HI} the Long share
 * goes down so that the merged noise rises geometrically from that mean's to k x S's along a smoothstep (no noise step: never
 * more than about 10 % for 1 % of the level, where the inverse-variance weights alone would jump 1.4 x a step at the end of the
 * taper); from TAPER_HI and at the Long clip k x S alone - up to k x 959 on the Long scale (3.7 - 7.7 stops over the Long clip in
 * the release's measures). The taper's place is read on L (its noise is the smaller one: the weight's correlation with it biases
 * M by a few DN, far under the noise). scam_dev lofic_blend 1 runs the release's fixed ramp instead (w = clamp((L - 11000) /
 * 3000, 0, 1)).
 * <p>
 * The merged frames go to the worker as ordinary 16-bit frames: code = black + M / G with one G of the burst, chosen so that the
 * Long clip sits at 1 / H of the frame's range, H = a power of two at least the burst's largest k x 959 / 15359 (the merge's
 * result times H is back on the Long scale: 1 = the Long clip, the highlights above it; a power of two scales the half-float
 * result exactly). The noise model of a merged frame is the Long one in those units (slope / H, offset / H^2) below the clip;
 * the worker's two-zone noise fit (the DCG check, header flag 32) measures the zone above it.
 */
public final class LoficMerge {
    private LoficMerge() {}

    public static final int LONG_BLACK = 1024, LONG_WHITE = 16383, SHORT_BLACK = 64, SHORT_WHITE = 1023;
    /** The Long read's weight goes from 1 at this share of its range down to 0 at {@link #TAPER_HI}. */
    public static final double TAPER_LO = 0.70, TAPER_HI = 0.96;
    /** Photons of the Long read over the Short read's (the exposure ratio of the release's model). */
    public static final double PHOTON_RATIO = 12.55;
    public static final double SHORT_READ_NOISE = 0.75;
    /** The release's fixed ramp (Long DN over black). */
    public static final double RAMP_LO = 11000, RAMP_HI = 14000;
    public static final int BLEND_NOISE = 0, BLEND_RAMP = 1;
    /** A pair adds less than this over the Long clip: no LOFIC merge for the burst (the Long frames alone). */
    public static final double MIN_GAIN = 1.1;

    /** The burst's encoding: the power-of-two headroom H and the frame's G per CFA site. */
    public static final class Encoding {
        public final int headroomLog2;
        public final double headroom;
        /** Long DN per output code, per CFA site (index (y & 1) * 2 + (x & 1)). */
        public final double[] g = new double[4];
        public final float[] black;
        public final int longWhite;

        Encoding(int log2, float[] black, int longWhite) {
            headroomLog2 = log2;
            headroom = Math.scalb(1.0, log2);
            this.black = black.clone();
            this.longWhite = longWhite;
            for (int i = 0; i < 4; i++) {
                final double range = longWhite - black[i];
                g[i] = headroom * range / (65535.0 - black[i]);
            }
        }

        @Override public String toString() {
            return String.format(Locale.ROOT, "headroom x%.0f (2^%d) over the Long clip, %.3f Long DN a code", headroom, headroomLog2, g[0]);
        }
    }

    /** The light a pair of ratio {@code k} holds over the Long clip: k x 959 / (white - black). */
    public static double gain(double k, float black, int longWhite) {
        return k * (SHORT_WHITE - SHORT_BLACK) / Math.max(1.0, longWhite - black);
    }

    /**
     * The encoding of a burst whose largest measured ratio is {@code kMax}; null when no pair adds {@link #MIN_GAIN} over the
     * Long clip (the Long frames go alone).
     */
    public static Encoding encoding(double kMax, float[] black, int longWhite) {
        float minBlack = black[0];
        for (float b : black) minBlack = Math.min(minBlack, b);
        final double gain = gain(kMax, minBlack, longWhite);
        if (!(gain >= MIN_GAIN) || !Double.isFinite(gain)) return null;
        int log2 = (int) Math.ceil(Math.log(gain) / Math.log(2) - 1e-9);
        log2 = Math.max(1, Math.min(10, log2));
        return new Encoding(log2, black, longWhite);
    }

    /** The noise model of a merged frame in its units (slope, offset), from the Long one in the Long frame's units. */
    public static float[] noise(float slope, float offset, Encoding e) {
        return new float[]{(float) (slope / e.headroom), (float) (offset / (e.headroom * e.headroom))};
    }

    /** What one merged pair showed. */
    public static final class Stats {
        public long pixels, longClipped, recovered, bothClipped;
        public double maxLevel; // the highest M over the Long clip
        public double k;

        @Override public String toString() {
            return String.format(Locale.ROOT, "k %.2f: %d of %d px clipped in Long (%.3f %%), %d held by Short, %d clipped in both; peak x%.2f"
                    + " of the Long clip", k, longClipped, pixels, 100.0 * longClipped / Math.max(1, pixels), recovered, bothClipped, maxLevel);
        }
    }

    /** Smoothstep from 1 at {@code lo} to 0 at {@code hi}. */
    static double taper(double v, double lo, double hi) {
        if (v <= lo) return 1;
        if (v >= hi) return 0;
        final double t = (v - lo) / (hi - lo);
        return 1 - t * t * (3 - 2 * t);
    }

    /**
     * The merged level M of one pixel on the Long scale (DN over black): Long {@code L} and Short {@code S} over their blacks,
     * {@code longClipped} / {@code shortClipped} at their clips, the Long noise {@code aL} L + {@code bL} (DN^2), the Long range.
     */
    public static double mergePixel(double L, double S, boolean longClipped, boolean shortClipped, double k, double aL, double bL,
                                    double range, int blend) {
        final double kS = k * S;
        if (blend == BLEND_RAMP) {
            if (longClipped) return shortClipped ? Math.max(L, kS) : kS;
            final double w = Math.min(1, Math.max(0, (L - RAMP_LO) / (RAMP_HI - RAMP_LO)));
            return (1 - w) * L + w * kS;
        }
        if (longClipped) return shortClipped ? Math.max(L, kS) : kS;
        if (shortClipped) return L; // k x 959 under the Long clip (a small k): the Short read holds nothing here
        final double s = 1 - taper(L, TAPER_LO * range, TAPER_HI * range); // 0 under the taper, 1 over it
        if (s >= 1) return kS;
        final double level = Math.max(0, L);
        final double varL = Math.max(1e-6, aL * level + bL);
        final double varK = Math.max(1e-6, PHOTON_RATIO * aL * level + k * k * SHORT_READ_NOISE * SHORT_READ_NOISE);
        final double sum = varL + varK;
        double a = varK / sum; // the Long share of the inverse-variance mean
        if (s > 0) {
            // the share whose merged variance a^2 varL + (1 - a)^2 varK is Vmin^(1 - s) varK^s (Vmin: the inverse-variance mean's)
            final double vMin = varL * varK / sum, v = Math.exp((1 - s) * Math.log(vMin) + s * Math.log(varK));
            a = (varK - Math.sqrt(Math.max(0, varK * varK - sum * (varK - v)))) / sum;
        }
        return a * L + (1 - a) * kS;
    }

    /**
     * Merges a pair in place: {@code longPlain} holds the Long frame as uint16 rows of {@code width} (native order, the codes as
     * read), {@code short10} the Short frame's packed RAW10 rows ({@code width} x 10 / 8 bytes, back to back). The Long buffer
     * gets the merged frame's codes (black + M / G). The Long noise model: {@code slope} / {@code offset} in units of the Long
     * range (variance = slope u + offset, u = (code - black) / (white - black)).
     */
    public static Stats merge(ByteBuffer longPlain, ByteBuffer short10, int width, int rows, double k, float slope, float offset,
                              Encoding e, int blend) {
        final Stats st = new Stats();
        st.k = k;
        final ShortBuffer px = whole(longPlain).asShortBuffer();
        final int shortRow = width * 10 / 8;
        final double[] aL = new double[4], bL = new double[4], range = new double[4], inv = new double[4];
        for (int i = 0; i < 4; i++) {
            range[i] = e.longWhite - e.black[i];
            aL[i] = slope * range[i];
            bL[i] = offset * range[i] * range[i];
            inv[i] = 1.0 / e.g[i];
        }
        final int clipCode = e.longWhite - 2, shortClip = SHORT_WHITE - 2;
        double peak = 0;
        for (int y = 0; y < rows; y++) {
            final int row = y * width;
            final int srow = y * shortRow;
            for (int g = 0; g < width / 4; g++) {
                final int at = srow + g * 5;
                final int low = short10.get(at + 4) & 0xff;
                for (int i = 0; i < 4; i++) {
                    final int x = 4 * g + i;
                    final int site = ((y & 1) << 1) | (x & 1);
                    final int code = px.get(row + x) & 0xffff;
                    final int s = ((short10.get(at + i) & 0xff) << 2) | ((low >> (2 * i)) & 3);
                    final boolean lc = code >= clipCode, sc = s >= shortClip;
                    final double L = code - e.black[site], S = s - SHORT_BLACK;
                    final double m = mergePixel(L, S, lc, sc, k, aL[site], bL[site], range[site], blend);
                    if (lc) {
                        st.longClipped++;
                        if (sc) st.bothClipped++;
                        else st.recovered++;
                    }
                    final double level = m / range[site];
                    if (level > peak) peak = level;
                    final long out = Math.round(e.black[site] + m * inv[site]);
                    px.put(row + x, (short) Math.max(0, Math.min(65535, out)));
                }
            }
        }
        st.pixels = (long) width * rows;
        st.maxLevel = peak;
        return st;
    }

    /** Pixels the ratio needs in its band (INTEGRATION.md: about 20 000), and the band of L over black. */
    public static final int K_MIN_PIXELS = 20000, K_LOW = 2000, K_HIGH = 14500;

    /**
     * k = sum L / sum S over the pixels with {@code K_LOW} < L < {@code K_HIGH} (both over their blacks), the Long frame as uint16
     * rows ({@code longPlain}, native order), the Short one packed RAW10 ({@code short10}); every {@code step}-th group of 4 of
     * every {@code step}-th row pair. Selected by L only: a threshold on S finds nothing at high gain. 0 when fewer than
     * {@link #K_MIN_PIXELS} pixels are in the band (a flat, dark or clipped scene).
     */
    public static double ratioPlain(ByteBuffer longPlain, ByteBuffer short10, int width, int rows, float[] black, int step) {
        step = Math.max(1, step);
        final ShortBuffer px = whole(longPlain).asShortBuffer();
        final int shortRow = width * 10 / 8;
        double sumL = 0, sumS = 0;
        long n = 0;
        for (int y = 0; y + 1 < rows; y += 2 * step)
            for (int yy = y; yy < y + 2; yy++)
                for (int g = 0; g < width / 4; g += step) {
                    final int at = yy * shortRow + g * 5;
                    final int low = short10.get(at + 4) & 0xff;
                    for (int i = 0; i < 4; i++) {
                        final int x = 4 * g + i;
                        final double L = (px.get(yy * width + x) & 0xffff) - black[((yy & 1) << 1) | (x & 1)];
                        if (L <= K_LOW || L >= K_HIGH) continue;
                        sumL += L;
                        sumS += (((short10.get(at + i) & 0xff) << 2) | ((low >> (2 * i)) & 3)) - SHORT_BLACK;
                        n++;
                    }
                }
        return n * step * step >= K_MIN_PIXELS && sumS > 0 ? sumL / sumS : 0;
    }

    /** The whole of a buffer from byte 0, in native order (a frame's buffer may sit at another position). */
    static ByteBuffer whole(ByteBuffer b) {
        final ByteBuffer d = b.duplicate();
        d.clear();
        return d.order(ByteOrder.nativeOrder());
    }

    /** The median of the measured ratios (> 0), 0 without one. */
    public static double median(double[] ks) {
        final double[] v = Arrays.stream(ks).filter(k -> k > 0 && Double.isFinite(k)).sorted().toArray();
        return v.length == 0 ? 0 : v[v.length / 2];
    }

    // ---------------------------------------------------------------- the merge's result back on the Long scale

    /** Multiplies the worker's RGB (FP16 when {@code half}, else FP32, native order) by 2^{@code log2} in place. */
    public static void scale(ByteBuffer rgb, boolean half, int log2) {
        if (rgb == null || log2 == 0) return;
        if (!half) {
            final FloatBuffer f = rgb.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer();
            final float m = (float) Math.scalb(1.0, log2);
            for (int i = 0, n = f.limit(); i < n; i++) f.put(i, f.get(i) * m);
            return;
        }
        final ShortBuffer h = rgb.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer();
        for (int i = 0, n = h.limit(); i < n; i++) h.put(i, scaleHalf(h.get(i), log2));
    }

    /** One IEEE half times 2^{@code log2}: the exponent moved (exact), a subnormal through float, an overflow to the largest half. */
    public static short scaleHalf(short v, int log2) {
        final int bits = v & 0xffff, sign = bits & 0x8000, exp = (bits >> 10) & 0x1f, mant = bits & 0x3ff;
        if (exp == 0x1f) return v; // inf / NaN
        if (exp == 0) {
            if (mant == 0) return v;
            return toHalf(Math.scalb(toFloat(v), log2));
        }
        final int e = exp + log2;
        if (e >= 0x1f) return (short) (sign | 0x7bff);
        if (e <= 0) return toHalf(Math.scalb(toFloat(v), log2));
        return (short) (sign | (e << 10) | mant);
    }

    public static float toFloat(short v) {
        final int bits = v & 0xffff, sign = bits & 0x8000, exp = (bits >> 10) & 0x1f, mant = bits & 0x3ff;
        final float m;
        if (exp == 0) m = mant * (float) Math.scalb(1.0, -24);
        else if (exp == 0x1f) m = mant == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
        else m = (float) Math.scalb(1.0 + mant / 1024.0, exp - 15);
        return sign != 0 ? -m : m;
    }

    /** Float to half, round to nearest even, overflow to the largest half. */
    public static short toHalf(float f) {
        if (Float.isNaN(f)) return (short) 0x7e00;
        final int sign = f < 0 || (f == 0 && 1 / f < 0) ? 0x8000 : 0;
        if (Float.isInfinite(f)) return (short) (sign | 0x7c00);
        double a = Math.abs((double) f);
        if (a >= 65520.0) return (short) (sign | 0x7bff);
        if (a < Math.scalb(1.0, -14)) { // subnormal
            return (short) (sign | ((int) Math.rint(a * Math.scalb(1.0, 24)) & 0x7ff)); // rint: half to even
        }
        int exp = Math.getExponent(a);
        double mant = a / Math.scalb(1.0, exp) - 1.0;
        long m = (long) Math.rint(mant * 1024);
        if (m == 1024) { m = 0; exp++; }
        if (exp + 15 >= 0x1f) return (short) (sign | 0x7bff);
        return (short) (sign | ((exp + 15) << 10) | (int) m);
    }
}
