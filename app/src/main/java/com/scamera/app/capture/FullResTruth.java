package com.scamera.app.capture;

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
 * kept as it is. Pure; uint16 little-endian rows of {@code stride} bytes (P689: or packed RAW10 rows, {@link Src}).
 */
public final class FullResTruth {
    private FullResTruth() {}

    /**
     * PARTIAL (P635b, the owner's third log and screenshot of the Xiaomi 17 Ultra: a viewfinder black on three quarters with a pale
     * strip at one edge, the photo the same): the buffer of the full size holds a picture in a part of it only and nothing in the
     * rest - the HAL stayed in its binned mode and wrote the binned frame, at its own pitch, into the start of the buffer (a
     * quarter of the rows, two binned rows side by side in each: P608's nubia frame too). The log had it all along: «RAW black
     * floor: 36864 of 49152 blocks hold samples below 16 DN» is exactly three quarters, and every statistic of the frame counted a
     * fifth of its samples.
     */
    public enum Verdict { REAL, UPSCALED, PARTIAL, UNKNOWN }

    /** The share of equal 2x2 boxes from which a frame is a doubled one. */
    static final double EQUAL_SHARE = 0.9;
    /** A doubled frame's boxes differ from their neighbours; a frame without light or all clipped has none that do. */
    static final double NEIGHBOUR_SHARE = 0.05;
    /** The Laplacian ratio under which a frame is its own nearest-2x baseline. */
    static final double LAPLACIAN_RATIO = 1.5;
    /** The side of a cell of the valid-area map, pixels; the share of the buffer a picture must cover (its bounding box). */
    static final int CELL = 64;
    static final double VALID_SHARE = 0.9;

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
        /** P635b: the box of the buffer that holds a picture {left, top, right, bottom} in pixels (right / bottom exclusive). */
        public final int[] box;
        /** P635b: the share of the buffer's cells that hold a picture, and of the buffer the box covers. */
        public final double validShare, boxShare;
        /** P635b: the low percentile (2 %) of the picture's sites, DN; the black the camera declares (0: not known). */
        public final double floor, declaredBlack;

        Result(Verdict verdict, int width, int height, double std2x2, double equalShare, int phase, double neighbourShare,
               double laplacian, double baseline, double[] parity) {
            this(verdict, width, height, std2x2, equalShare, phase, neighbourShare, laplacian, baseline, parity,
                    new int[]{0, 0, width, height}, 1, 1, 0, 0);
        }

        Result(Verdict verdict, int width, int height, double std2x2, double equalShare, int phase, double neighbourShare,
               double laplacian, double baseline, double[] parity, int[] box, double validShare, double boxShare, double floor,
               double declaredBlack) {
            this.box = box;
            this.validShare = validShare;
            this.boxShare = boxShare;
            this.floor = floor;
            this.declaredBlack = declaredBlack;
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

        /**
         * P635b: the picture lost the pedestal the camera declares (its floor under half the declared black): not what the sensor
         * reads out in a mode the HAL really runs (the documents' real frames: floor 48 .. 53 for a black of 64).
         */
        public boolean pedestalLost() {
            return verdict != Verdict.UNKNOWN && declaredBlack >= 16 && floor < declaredBlack * 0.5;
        }

        /**
         * P635b: the binned frame at its own pitch in the start of the buffer: the picture is the whole width of the first
         * quarter of the rows (within a cell).
         */
        public boolean packedQuarter() {
            return verdict == Verdict.PARTIAL && box[0] == 0 && box[1] == 0 && box[2] >= width - CELL
                    && Math.abs(box[3] - height / 4) <= CELL;
        }

        /** Not the sensor's full readout: a partial or doubled frame, or ({@code pedestalMatters}) one without its pedestal. */
        public boolean notReal(boolean pedestalMatters) {
            return verdict == Verdict.PARTIAL || verdict == Verdict.UPSCALED || pedestalMatters && pedestalLost();
        }

        /** The shot's one log line. */
        public String line() {
            return line("FULLRES RECIPE");
        }

        /** The log line under {@code tag} («FULLRES RECIPE», «FULLRES ROW»). */
        public String line(String tag) {
            return String.format(Locale.ROOT, "%s: frame %dx%d valid %dx%d+%d+%d (%.0f%% of the buffer, cells with a picture %.0f%%) floor %.0f"
                            + " (declared black %.0f%s) 2x2std=%.2f equal=%.0f%% (phase %d, neighbours differ %.0f%%)"
                            + " lap=%.1f baseline=%.1f ratio=%s parity=[[%.1f, %.1f], [%.1f, %.1f]]%s -> %s",
                    tag, width, height, box[2] - box[0], box[3] - box[1], box[0], box[1], boxShare * 100, validShare * 100, floor,
                    declaredBlack, pedestalLost() ? ": NO PEDESTAL" : "",
                    std2x2, equalShare * 100, phase, neighbourShare * 100, laplacian, baseline,
                    Double.isInfinite(ratio) ? "inf" : String.format(Locale.ROOT, "%.2f", ratio), parity[0], parity[1], parity[2], parity[3],
                    verdict == Verdict.REAL ? (bayerParity() ? " (two equal phases: plain Bayer)" : " (no two equal phases)") : "",
                    verdict == Verdict.REAL ? (pedestalLost() ? "full-size data WITHOUT its pedestal" : "real full-res")
                            : verdict == Verdict.UPSCALED ? "UPSCALED (the binned frame written twice over)"
                            : verdict == Verdict.PARTIAL ? "PARTIAL (a picture in a part of the buffer only"
                            + (packedQuarter() ? ": the binned frame at its own pitch in the first quarter of the rows)" : ")")
                            : "cannot tell (no light or all clipped), kept");
        }
    }

    private static int at(ByteBuffer b, int base, int stride, int x, int y) {
        return b.getShort(base + y * stride + 2 * x) & 0xFFFF;
    }

    /**
     * P689: one site of a frame - uint16 little-endian rows, or (the OPPO Find X9 Ultra's 50 MP recipe RAW, ImageFormat.RAW10)
     * MIPI RAW10 rows: four sites in five bytes, the high 8 bits of each in bytes 0..3, their low 2 bits in byte 4.
     */
    static final class Src {
        final ByteBuffer b;
        final int base, stride;
        final boolean raw10;

        Src(ByteBuffer b, int base, int stride, boolean raw10) {
            this.b = b;
            this.base = base;
            this.stride = stride;
            this.raw10 = raw10;
        }

        int at(int x, int y) {
            if (!raw10) return FullResTruth.at(b, base, stride, x, y);
            final int o = base + y * stride + (x >> 2) * 5, k = x & 3;
            return ((b.get(o + k) & 0xFF) << 2) | (((b.get(o + 4) & 0xFF) >> (2 * k)) & 3);
        }
    }

    /** P689: the bytes a row of {@code width} sites takes (RAW10: 5 a group of 4; else 2 a site). */
    static long rowBytes(int width, boolean raw10) {
        return raw10 ? ((long) width * 10 + 7) / 8 : width * 2L;
    }

    /** The verdict of a frame; UNKNOWN for a buffer that cannot be read. */
    public static Result check(@Nullable ByteBuffer raw, int width, int height, int stride) {
        return check(raw, width, height, stride, 0);
    }

    /**
     * P635b: {valid cells' share, box share, left, top, right, bottom, floor} of the buffer's picture. A cell of {@link #CELL}
     * pixels is empty when its 16 sampled sites are one value under half the declared black (0 when none is declared): what the
     * HAL leaves of a buffer it does not fill, never what a sensor reads (its pedestal and noise). The box bounds the cells that
     * are not empty; the floor is the 2 % percentile of their samples.
     */
    static double[] valid(Src src, int width, int height, double declaredBlack) {
        final int cx = Math.max(1, width / CELL), cy = Math.max(1, height / CELL);
        final int emptyBelow = (int) Math.max(1, declaredBlack * 0.5);
        final int[] hist = new int[4096];
        int lit = 0, left = cx, top = cy, right = -1, bottom = -1;
        long samples = 0;
        for (int j = 0; j < cy; j++) for (int i = 0; i < cx; i++) {
            final int x0 = i * CELL, y0 = j * CELL;
            final int first = src.at(x0 + 3, y0 + 3);
            boolean empty = first < emptyBelow;
            final int[] v = new int[16];
            for (int k = 0; k < 16; k++) {
                v[k] = src.at(Math.min(width - 1, x0 + 3 + (k & 3) * 15), Math.min(height - 1, y0 + 3 + (k >> 2) * 15));
                if (v[k] != first) empty = false;
            }
            if (empty) continue;
            lit++;
            left = Math.min(left, i);
            top = Math.min(top, j);
            right = Math.max(right, i);
            bottom = Math.max(bottom, j);
            for (int k = 0; k < 16; k++) hist[Math.min(4095, v[k])]++;
            samples += 16;
        }
        if (lit == 0) return new double[]{0, 0, 0, 0, 0, 0, 0};
        long seen = 0;
        int floor = 0;
        final long target = Math.max(1, samples / 50);
        for (int i = 0; i < hist.length; i++) {
            seen += hist[i];
            if (seen >= target) {
                floor = i;
                break;
            }
        }
        final int l = left * CELL, t = top * CELL, r = right == cx - 1 ? width : (right + 1) * CELL, bt = bottom == cy - 1 ? height : (bottom + 1) * CELL;
        return new double[]{(double) lit / (cx * cy), (double) (r - l) * (bt - t) / ((double) width * height), l, t, r, bt, floor};
    }

    /** {@link #check(ByteBuffer, int, int, int)} with the black the camera declares (0: not known) for the pedestal and the empty cells. */
    public static Result check(@Nullable ByteBuffer raw, int width, int height, int stride, double declaredBlack) {
        return check(raw, width, height, stride, declaredBlack, false);
    }

    /**
     * P689: {@link #check(ByteBuffer, int, int, int, double)} of a packed RAW10 frame ({@code raw10}: the OPPO Find X9 Ultra's
     * 50 MP recipe takes its stills as RAW10, as the X9 Ultra full-size recipe proved them) - the same statistics on the same
     * sites, read from their 10-bit groups.
     */
    public static Result check(@Nullable ByteBuffer raw, int width, int height, int stride, double declaredBlack, boolean raw10) {
        final double[] parity = new double[4];
        final long row = rowBytes(width, raw10);
        if (raw == null || width < 64 || height < 64 || stride < row
                || (long) raw.position() + (long) (height - 1) * stride + row > raw.limit())
            return new Result(Verdict.UNKNOWN, width, height, 0, 0, 0, 0, 0, 0, parity);
        final ByteBuffer b = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        final Src src = new Src(b, b.position(), stride, raw10);
        // P635b: where the buffer holds a picture at all
        final double[] area = valid(src, width, height, declaredBlack);
        final int[] box = {(int) area[2], (int) area[3], (int) area[4], (int) area[5]};
        // every 4th box of every 4th box row of the frame without its outer tenth
        final int x0 = (width / 10) & ~1, x1 = (width * 9 / 10) & ~1, y0 = (height / 10) & ~1, y1 = (height * 9 / 10) & ~1;
        final int[] stdHist = new int[1025]; // std in quarter DN, 256 DN and more in the last bin
        final long[] equal = new long[4], differ = new long[4];
        final double[] sum = new double[4];
        long boxes = 0;
        for (int y = y0; y + 6 <= y1; y += 8) {
            for (int x = x0; x + 6 <= x1; x += 8) {
                final int a = src.at(x, y), c = src.at(x + 1, y);
                final int d = src.at(x, y + 1), e = src.at(x + 1, y + 1);
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
                    final int v = src.at(px, py);
                    if (v == src.at(px + 1, py) && v == src.at(px, py + 1) && v == src.at(px + 1, py + 1)) {
                        equal[p]++;
                        if (v != src.at(px + 2, py)) differ[p]++;
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
        final double[] lap = laplacian(src, width, height, phase);
        final Verdict verdict;
        if (area[0] == 0) verdict = Verdict.UNKNOWN; // nothing but one value: no light, or all clipped
        else if (area[1] < VALID_SHARE) verdict = Verdict.PARTIAL; // P635b: the picture's box leaves a part of the buffer empty
        else if (equalShare >= EQUAL_SHARE) verdict = neighbourShare >= NEIGHBOUR_SHARE ? Verdict.UPSCALED : Verdict.UNKNOWN;
        else if (lap[1] > 0 && lap[0] / lap[1] < LAPLACIAN_RATIO && median == 0) verdict = Verdict.UPSCALED;
        else verdict = Verdict.REAL;
        return new Result(verdict, width, height, median, equalShare, phase, neighbourShare, lap[0], lap[1], parity, box, area[0], area[1],
                area[6], declaredBlack);
    }

    /**
     * {variance of the Laplacian, the same of the nearest-2x baseline} over a centre crop of at most 1024 x 768 sites; the
     * baseline: each 2x2 box (at the doubled frame's phase) replaced by its mean.
     */
    static double[] laplacian(Src src, int width, int height, int phase) {
        final int cw = Math.min(1024, (width - 4) & ~1), ch = Math.min(768, (height - 4) & ~1);
        if (cw < 16 || ch < 16) return new double[]{0, 0};
        final int ox = (((width - cw) / 2) & ~1) + (phase & 1), oy = (((height - ch) / 2) & ~1) + (phase >> 1);
        final int[] f = new int[cw * ch];
        final float[] m = new float[cw * ch];
        for (int y = 0; y < ch; y++) for (int x = 0; x < cw; x++) f[y * cw + x] = src.at(ox + x, oy + y);
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

    /**
     * P635b: the binned frame a {@link Result#packedQuarter} buffer holds - width / 2 x height / 2 sites at their own pitch from
     * the buffer's start (two binned rows in each buffer row) - into {@code dst} (packed uint16 rows from its position). False
     * when the buffers do not hold it or the buffer's rows are padded (the pitch is then not the binned frame's).
     */
    public static boolean unpackQuarter(@Nullable ByteBuffer dst, @Nullable ByteBuffer src, int width, int height, int stride) {
        final int bytes = (width / 2) * (height / 2) * 2;
        if (dst == null || src == null || bytes <= 0 || stride != width * 2 || dst.remaining() < bytes || src.limit() < bytes) return false;
        final ByteBuffer in = src.duplicate();
        in.position(0);
        in.limit(bytes);
        dst.duplicate().put(in);
        return true;
    }

    /**
     * P689: {@link #unpackQuarter} of a packed RAW10 buffer - the binned frame (width / 2 x height / 2 sites, RAW10 rows at its own
     * pitch, two binned rows in each buffer row) unpacked into {@code dst} as packed uint16 rows from its position. False when the
     * buffers do not hold it, the buffer's rows are padded or its width is no whole number of 8 sites.
     */
    public static boolean unpackQuarterRaw10(@Nullable ByteBuffer dst, @Nullable ByteBuffer src, int width, int height, int stride) {
        final int ow = width / 2, oh = height / 2;
        final long half = rowBytes(ow, true);
        if (dst == null || src == null || ow <= 0 || oh <= 0 || width % 8 != 0 || stride != rowBytes(width, true)
                || dst.remaining() < (long) ow * oh * 2 || half * oh > src.limit()) return false;
        return unpackRaw10(dst, new Src(src.duplicate(), 0, (int) half, true), ow, oh, 0, 0, 1);
    }

    /** P689: {@link #halve} of a packed RAW10 frame (the binned frame written twice over) into uint16 rows of {@code dst}. */
    public static boolean halveRaw10(@Nullable ByteBuffer dst, @Nullable ByteBuffer src, int offset, int width, int height, int stride, int phase) {
        final int ow = width / 2, oh = height / 2;
        if (dst == null || src == null || ow <= 0 || oh <= 0 || stride < rowBytes(width, true)
                || dst.remaining() < (long) ow * oh * 2 || (long) offset + (long) (height - 1) * stride + rowBytes(width, true) > src.limit())
            return false;
        return unpackRaw10(dst, new Src(src.duplicate(), offset, stride, true), ow, oh, phase & 1, phase >> 1, 2);
    }

    /** P689: {@code ow} x {@code oh} sites of a RAW10 source at ({@code step} x + px, {@code step} y + py) as uint16 rows. */
    private static boolean unpackRaw10(ByteBuffer dst, Src in, int ow, int oh, int px, int py, int step) {
        final java.nio.ShortBuffer out = dst.duplicate().order(dst.order()).asShortBuffer();
        final short[] row = new short[ow];
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < ow; x++) row[x] = (short) in.at(step * x + px, step * y + py);
            out.put(row);
        }
        return true;
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
