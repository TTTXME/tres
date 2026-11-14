package com.scamera.app.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * P699 (owner, X300 Ultra 2026-11-14: the 200 MP still-only stills are the HAL's remosaicked Bayer, its full-size mode not reported
 * - the decision frame said vcf.parameter.seamlessMode 1, not 768): is a 16320-wide Bayer still detail at its own grid, or the
 * 50 MP readout blown up? Measured on the green sites (the Bayer's only same-colour sites closer than 2 px):
 * <ul>
 * <li>{@code smooth}: the mean |G - G| of diagonal neighbours (sqrt 2 px) over that of the next ones on the same diagonal
 * (2 sqrt 2 px). Noise and detail at the full grid give about 0.7 - 1; an interpolated (bilinear) 2x upscale about 0.5, as
 * a strong denoise may. A diagnostic only: logged, never acted on.</li>
 * <li>{@code cell}: within the 2x2 cells of the 50 MP grid (either alignment), the two green sites' mean difference over that of
 * the same sites' diagonal neighbours in the next cell. A real 200 MP readout gives about 1 at both alignments; a still whose 2x2
 * cells repeat one 50 MP value per colour gives 0 - a 50 MP still dressed as 200 MP, never shown as 200 MP.</li>
 * </ul>
 * Sites at the black level or near clipping are left out; the green phase is the one whose diagonal neighbours differ least (red
 * and blue differ from each other by their colour). A 2x2 replication of raw values (each Bayer site copied into a 2x2 block) is a
 * Quad pattern to MosaicBlockDetector, which a still-only series already refuses (VivoSdkFullResCapture.seriesBlock).
 */
final class FullGridCheck {
    private FullGridCheck() { }

    /** Below: the still is smooth at its own grid (an upscale or a strong denoise; logged). */
    static final double SMOOTH = 0.6;
    /** Below: the 2x2 cells repeat their green - a 50 MP readout replicated to the full size. */
    static final double REPLICATED = 0.2;
    /** Green pairs measured at least, for either verdict. */
    static final long MIN_SAMPLES = 2000;

    static final class Result {
        final double smooth, cell;
        final int greenParity;
        final long samples;

        Result(double smooth, double cell, int greenParity, long samples) {
            this.smooth = smooth;
            this.cell = cell;
            this.greenParity = greenParity;
            this.samples = samples;
        }

        boolean replicated() {
            return samples >= MIN_SAMPLES && cell < REPLICATED;
        }

        boolean smoothGrid() {
            return samples >= MIN_SAMPLES && smooth < SMOOTH;
        }

        String words() {
            if (samples < MIN_SAMPLES) return "too few green sites to tell";
            if (replicated()) return "its 2x2 cells repeat their green: a 50 MP readout replicated to the full size";
            if (smoothGrid()) return "smooth at its own grid: an interpolated upscale of the 50 MP readout or a strong denoise";
            return "detail at its own grid: a full-size readout";
        }

        @Override public String toString() {
            return String.format(Locale.ROOT, "diagonal ratio %.3f (under %.2f: smooth), 2x2 cell ratio %.3f (under %.2f: replicated), "
                    + "green phase %d, %d pairs: %s", smooth, SMOOTH, cell, REPLICATED, greenParity, samples, words());
        }
    }

    /**
     * The still {@code raw} (uint16 little-endian sites, {@code rowStride} bytes a row) over its centre (the middle 80 %), every
     * {@code rowStep}-th pair of rows; {@code black} / {@code white} its levels.
     */
    static Result measure(ByteBuffer raw, int width, int height, int rowStride, int black, int white, int rowStep) {
        if (raw == null || width < 64 || height < 64 || rowStride < width * 2) return new Result(1, 1, 0, 0);
        final ByteBuffer b = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        final int base = b.position();
        if ((long) (height - 1) * rowStride + width * 2L > b.limit() - base) return new Result(1, 1, 0, 0);
        final int x0 = (width / 10) & ~1, x1 = (width * 9 / 10) & ~1, y0 = (height / 10) & ~1, y1 = (height * 9 / 10) & ~1;
        final int step = Math.max(2, rowStep & ~1), lo = black + 2, hi = black + (int) (0.95 * (white - black));
        // per phase: sums of |d1| (sqrt 2 px), |d2| (2 sqrt 2 px); per phase and cell alignment: in-cell / next-cell sums
        final long[] d1 = new long[2], d2 = new long[2], n = new long[2];
        final long[][] in = new long[2][2], out = new long[2][2], cells = new long[2][2];
        for (int yy = y0; yy + 3 < y1; yy += step) {
            for (int y = yy; y <= yy + 1; y++) {
                final int r0 = base + y * rowStride, r1 = r0 + rowStride, r2 = r1 + rowStride;
                for (int x = x0 + 2; x + 3 < x1; x++) {
                    final int p = (x + y) & 1;
                    final int v = b.getShort(r0 + 2 * x) & 0xFFFF;
                    if (v <= lo || v >= hi) continue;
                    final int dr = b.getShort(r1 + 2 * (x + 1)) & 0xFFFF, dl = b.getShort(r1 + 2 * (x - 1)) & 0xFFFF;
                    final int far = b.getShort(r2 + 2 * (x + 2)) & 0xFFFF;
                    if (dr <= lo || dr >= hi || dl <= lo || dl >= hi || far <= lo || far >= hi) continue;
                    d1[p] += Math.abs(v - dr);
                    d2[p] += Math.abs(v - far);
                    n[p]++;
                    // y is a cell's top row for the alignment off = y & 1; the in-cell green partner is the diagonal neighbour
                    // inside the same 2-column cell, the other diagonal neighbour is in the next cell
                    final int off = y & 1;
                    final boolean leftInCell = ((x - off) & 1) == 1;
                    in[p][off] += Math.abs(v - (leftInCell ? dl : dr));
                    out[p][off] += Math.abs(v - (leftInCell ? dr : dl));
                    cells[p][off]++;
                }
            }
        }
        final double m0 = n[0] == 0 ? Double.MAX_VALUE : (double) d1[0] / n[0], m1 = n[1] == 0 ? Double.MAX_VALUE : (double) d1[1] / n[1];
        final int g = m0 <= m1 ? 0 : 1;
        if (n[g] == 0) return new Result(1, 1, g, 0);
        final double smooth = d2[g] == 0 ? 1 : (double) d1[g] / d2[g];
        double cell = Double.MAX_VALUE;
        for (int off = 0; off < 2; off++)
            if (cells[g][off] > 0 && out[g][off] > 0) cell = Math.min(cell, (double) in[g][off] / out[g][off]);
        if (cell == Double.MAX_VALUE) cell = 1;
        return new Result(smooth, cell, g, n[g]);
    }
}
