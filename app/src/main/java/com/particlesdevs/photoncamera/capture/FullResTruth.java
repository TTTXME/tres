package com.particlesdevs.photoncamera.capture;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * P635: whether a full-size RAW frame holds the sensor's full readout - the owner's documents: «ukuran frame bukan bukti», a
 * frame of the full size that came without its companion stream is the binned frame written twice over (every 2x2 box one
 * value: 2x2 std 0, Laplacian 0 against 20 and 75 000 of the real frame). Measured on the first frame of every recipe shot:
 * <ul>
 * <li>the median standard deviation of the 2x2 boxes and the share of boxes whose four sites are equal (at any of the four
 * phases a doubled frame could sit at);</li>
 * <li>the variance of the frame's Laplacian over that of its nearest-2x baseline (the frame's 2x2 means written twice over) on a
 * centre crop - the documents' decisive number (17 .. 460 on real frames; 1 on a doubled one, which is its own baseline);</li>
 * <li>the four phase means (the CFA's parity: two equal greens on a plain Bayer).</li>
 * </ul>
 * A frame without any light or all clipped (equal boxes that do not differ from their neighbours either) cannot be told: UNKNOWN,
 * kept as it is. Pure; uint16 little-endian rows of {@code stride} bytes.
 */
public final class FullResTruth {
    private FullResTruth() {}

    public enum Verdict { REAL, UPSCALED, UNKNOWN }

    /** The share of equal 2x2 boxes from which a frame is a doubled one. */
    static final double EQUAL_SHARE = 0.9;
    /** A doubled frame's boxes differ from their neighbours; a frame without light or all clipped has none that do. */
    static final double NEIGHBOUR_SHARE = 0.05;
    /** The Laplacian ratio under which a frame is its own nearest-2x baseline. */
    static final double LAPLACIAN_RATIO = 1.5;

    public static final class Result {
        public final Verdict verdict;
        public final int width, height;
        /** Median standard deviation of the aligned 2x2 boxes, DN. */
        public final double std2x2;
        /** The largest share of boxes with four equal sites over the four phases, and that phase (x + 2 y). */
        public final double equalShare;
        public final int phase;
        /** Share of equal boxes that differ from the box to their right. */
        public final double neighbourShare;
        /** Laplacian variance of the centre crop, of its nearest-2x baseline, and their ratio (0: no crop). */
        public final double laplacian, baseline, ratio;
        /** Means of the sites at (x & 1) + 2 (y & 1). */
        public final double[] parity;

        Result(Verdict verdict, int width, int height, double std2x2, double equalShare, int phase, double neighbourShare,
               double laplacian, double baseline, double[] parity) {
            this.verdict = verdict;
            this.width = width;
            this.height = height;
            this.std2x2 = std2x2;
            this.equalShare = equalShare;
            this.phase = phase;
            this.neighbourShare = neighbourShare;
            this.laplacian = laplacian;
            this.baseline = baseline;
            this.ratio = baseline > 0 ? laplacian / baseline : laplacian > 0 ? Double.POSITIVE_INFINITY : 0;
            this.parity = parity;
        }

        /** The two diagonal pairs of phase means: a plain Bayer has one pair equal (its greens) within 3 %. */
        public boolean bayerParity() {
            return close(parity[1], parity[2]) || close(parity[0], parity[3]);
        }

        private static boolean close(double a, double b) {
            return Math.abs(a - b) <= 0.03 * Math.max(1.0, Math.max(a, b));
        }

        /** The shot's one log line. */
        public String line() {
            return String.format(Locale.ROOT, "FULLRES RECIPE: frame %dx%d 2x2std=%.2f equal=%.0f%% (phase %d, neighbours differ %.0f%%)"
                            + " lap=%.1f baseline=%.1f ratio=%s parity=[[%.1f, %.1f], [%.1f, %.1f]]%s -> %s",
                    width, height, std2x2, equalShare * 100, phase, neighbourShare * 100, laplacian, baseline,
                    Double.isInfinite(ratio) ? "inf" : String.format(Locale.ROOT, "%.2f", ratio), parity[0], parity[1], parity[2], parity[3],
                    verdict == Verdict.REAL ? (bayerParity() ? " (two equal phases: plain Bayer)" : " (no two equal phases)") : "",
                    verdict == Verdict.REAL ? "real full-res" : verdict == Verdict.UPSCALED ? "UPSCALED (the binned frame written twice over)"
                            : "cannot tell (no light or all clipped), kept");
        }
    }

    private static int at(ByteBuffer b, int base, int stride, int x, int y) {
        return b.getShort(base + y * stride + 2 * x) & 0xFFFF;
    }

    /** The verdict of a frame; UNKNOWN for a buffer that cannot be read. */
    public static Result check(@Nullable ByteBuffer raw, int width, int height, int stride) {
        final double[] parity = new double[4];
        if (raw == null || width < 64 || height < 64 || stride < width * 2
                || (long) raw.position() + (long) (height - 1) * stride + width * 2L > raw.limit())
            return new Result(Verdict.UNKNOWN, width, height, 0, 0, 0, 0, 0, 0, parity);
        final ByteBuffer b = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        final int base = b.position();
        // every 4th box of every 4th box row of the frame without its outer tenth
        final int x0 = (width / 10) & ~1, x1 = (width * 9 / 10) & ~1, y0 = (height / 10) & ~1, y1 = (height * 9 / 10) & ~1;
        final int[] stdHist = new int[1025]; // std in quarter DN, 256 DN and more in the last bin
        final long[] equal = new long[4], differ = new long[4];
        final double[] sum = new double[4];
        long boxes = 0;
        for (int y = y0; y + 6 <= y1; y += 8) {
            for (int x = x0; x + 6 <= x1; x += 8) {
                final int a = at(b, base, stride, x, y), c = at(b, base, stride, x + 1, y);
                final int d = at(b, base, stride, x, y + 1), e = at(b, base, stride, x + 1, y + 1);
                sum[0] += a;
                sum[1] += c;
                sum[2] += d;
                sum[3] += e;
                final double mean = (a + c + d + e) / 4.0;
                final double var = ((a - mean) * (a - mean) + (c - mean) * (c - mean) + (d - mean) * (d - mean) + (e - mean) * (e - mean)) / 4.0;
                stdHist[(int) Math.min(1024, Math.round(Math.sqrt(var) * 4))]++;
                boxes++;
                for (int p = 0; p < 4; p++) {
                    final int px = x + (p & 1), py = y + (p >> 1);
                    final int v = at(b, base, stride, px, py);
                    if (v == at(b, base, stride, px + 1, py) && v == at(b, base, stride, px, py + 1) && v == at(b, base, stride, px + 1, py + 1)) {
                        equal[p]++;
                        if (v != at(b, base, stride, px + 2, py)) differ[p]++;
                    }
                }
            }
        }
        if (boxes == 0) return new Result(Verdict.UNKNOWN, width, height, 0, 0, 0, 0, 0, 0, parity);
        for (int p = 0; p < 4; p++) parity[p] = sum[p] / boxes;
        long seen = 0;
        double median = 0;
        for (int i = 0; i < stdHist.length; i++) {
            seen += stdHist[i];
            if (seen * 2 >= boxes) {
                median = i / 4.0;
                break;
            }
        }
        int phase = 0;
        for (int p = 1; p < 4; p++) if (equal[p] > equal[phase]) phase = p;
        final double equalShare = (double) equal[phase] / boxes;
        final double neighbourShare = equal[phase] == 0 ? 0 : (double) differ[phase] / equal[phase];
        final double[] lap = laplacian(b, base, stride, width, height, phase);
        final Verdict verdict;
        if (equalShare >= EQUAL_SHARE) verdict = neighbourShare >= NEIGHBOUR_SHARE ? Verdict.UPSCALED : Verdict.UNKNOWN;
        else if (lap[1] > 0 && lap[0] / lap[1] < LAPLACIAN_RATIO && median == 0) verdict = Verdict.UPSCALED;
        else verdict = Verdict.REAL;
        return new Result(verdict, width, height, median, equalShare, phase, neighbourShare, lap[0], lap[1], parity);
    }

    /**
     * {variance of the Laplacian, the same of the nearest-2x baseline} over a centre crop of at most 1024 x 768 sites; the
     * baseline: each 2x2 box (at the doubled frame's phase) replaced by its mean.
     */
    static double[] laplacian(ByteBuffer b, int base, int stride, int width, int height, int phase) {
        final int cw = Math.min(1024, (width - 4) & ~1), ch = Math.min(768, (height - 4) & ~1);
        if (cw < 16 || ch < 16) return new double[]{0, 0};
        final int ox = (((width - cw) / 2) & ~1) + (phase & 1), oy = (((height - ch) / 2) & ~1) + (phase >> 1);
        final int[] f = new int[cw * ch];
        final float[] m = new float[cw * ch];
        for (int y = 0; y < ch; y++) for (int x = 0; x < cw; x++) f[y * cw + x] = at(b, base, stride, ox + x, oy + y);
        for (int y = 0; y < ch; y += 2) for (int x = 0; x < cw; x += 2) {
            final float mean = (f[y * cw + x] + f[y * cw + x + 1] + f[(y + 1) * cw + x] + f[(y + 1) * cw + x + 1]) / 4f;
            m[y * cw + x] = m[y * cw + x + 1] = m[(y + 1) * cw + x] = m[(y + 1) * cw + x + 1] = mean;
        }
        double s1 = 0, s2 = 0, t1 = 0, t2 = 0;
        long n = 0;
        for (int y = 1; y < ch - 1; y++) for (int x = 1; x < cw - 1; x++) {
            final int i = y * cw + x;
            final double lf = 4.0 * f[i] - f[i - 1] - f[i + 1] - f[i - cw] - f[i + cw];
            final double lm = 4.0 * m[i] - m[i - 1] - m[i + 1] - m[i - cw] - m[i + cw];
            s1 += lf;
            s2 += lf * lf;
            t1 += lm;
            t2 += lm * lm;
            n++;
        }
        return new double[]{s2 / n - (s1 / n) * (s1 / n), t2 / n - (t1 / n) * (t1 / n)};
    }

    /** The size of a doubled frame's binned original. */
    public static int[] halfSize(int width, int height) {
        return new int[]{width / 2, height / 2};
    }

    /**
     * A doubled frame back to the binned frame it was made of: one site of every 2x2 box (the box at {@code phase}) into
     * {@code dst} (width / 2 x height / 2 uint16 in its byte order, packed rows from its position). False when the buffers do
     * not hold it.
     */
    public static boolean halve(@Nullable ByteBuffer dst, @Nullable ByteBuffer src, int offset, int width, int height, int stride, int phase) {
        final int ow = width / 2, oh = height / 2;
        if (dst == null || src == null || ow <= 0 || oh <= 0 || stride < width * 2
                || dst.remaining() < (long) ow * oh * 2 || (long) offset + (long) (height - 1) * stride + width * 2L > src.limit()) return false;
        final ByteBuffer in = src.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        final java.nio.ShortBuffer out = dst.duplicate().order(dst.order()).asShortBuffer();
        final int px = phase & 1, py = phase >> 1;
        final short[] row = new short[ow];
        for (int y = 0; y < oh; y++) {
            final int sy = Math.min(height - 1, 2 * y + py);
            final int rowBase = offset + sy * stride;
            for (int x = 0; x < ow; x++) row[x] = in.getShort(rowBase + 2 * Math.min(width - 1, 2 * x + px));
            out.put(row);
        }
        return true;
    }
}
