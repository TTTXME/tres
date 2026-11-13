package com.scamera.app.capture;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;

/**
 * P641d (owner, 2026-11-12: «Как будет работать наша склейка при съемке DCG+LOFIC? Придумай вариант чтобы склеивать их вместе»):
 * the Xiaomi 17 Ultra's DCG + LOFIC as a pair of frames of one exposure. Nothing here ran on a phone.
 * <p>
 * The device report: in the vendor's own sessions LOFIC is two reads on two streams (mivistreamformat.json LoficFormat [324, 37];
 * smartae «Lofic zsl 4ev0, 4ev-» with ev 0 / -3 pairs; DCGPlusVS = long 14 bit + short 10 bit) - the 14-bit dual-gain frame and a
 * 10-bit frame of the same exposure about 3 EV down. The stream sets give the main camera a second RAW10 stream of the vendor
 * stream use case 524554 («raw10 stream of wide dol2 short») beside the main one. That this stream is where the overflow read
 * arrives, and that a third-party session gets it, is our inference: the first rung of the LOFIC ladder asks for it
 * (XiaomiDualGain «lofic-pair») and trusts it only when the frames prove it.
 * <p>
 * The proof and every number are measured on the frames (block means of the two frames of one sensor timestamp): the overflow
 * frame's codes are a straight line of the main frame's where neither clips - its slope is the ratio of the two reads, its
 * intercept the overflow stream's black - the line fits within a tenth, the ratio is well above 1 (a copy of the main frame, or
 * a second frame of the same read, is 1), and where the main frame is clipped the overflow frame is not. A pair that does not
 * show this is not used.
 * <p>
 * The merge is the app's own: the overflow frame, rescaled to the main frame's levels, is that exposure's short frame - the
 * same photons, so no time offset and no motion between them - with its exposure the main frame's over the measured ratio.
 */
public final class LoficPair {
    private LoficPair() {}

    /** The vendor stream use case of the second RAW10 stream (524554). */
    public static final long USE_CASE = 0x8010AL;
    /** The blocks a frame is measured on, and the samples a side of a block. */
    static final int GRID_X = 48, GRID_Y = 36, QUADS = 4;
    /** A ratio under this is no second read (a copy is 1; the vendor's pairs are 3 EV apart). */
    static final double MIN_RATIO = 2.0;
    /** The line's residual against the signal, and two measured ratios against each other. */
    static final double MAX_RESIDUAL = 0.10, AGREE = 0.10;

    // ---------------------------------------------------------------- a frame's samples, whatever its layout

    /** The sample at a pixel. */
    public interface Samples {
        int get(int x, int y);
    }

    /** Plain little-endian 16-bit rows {@code stride} bytes apart, the frame's row 0 at {@code firstRow} of the buffer. */
    public static Samples plain16(ByteBuffer buffer, int stride, int firstRow) {
        final ByteBuffer b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        return (x, y) -> b.getShort((y + firstRow) * stride + 2 * x) & 0xffff;
    }

    /** Packed MIPI rows of {@code bits} (10, 12, 14) a sample, {@code stride} bytes apart, the frame's row 0 at {@code firstRow}. */
    public static Samples packed(ByteBuffer buffer, int bits, int stride, int firstRow) {
        final ByteBuffer b = buffer.duplicate();
        if (bits == 10) return (x, y) -> {
            final int at = (y + firstRow) * stride + (x >> 2) * 5;
            return ((b.get(at + (x & 3)) & 0xff) << 2) | (((b.get(at + 4) & 0xff) >> (2 * (x & 3))) & 3);
        };
        if (bits == 12) return (x, y) -> {
            final int at = (y + firstRow) * stride + (x >> 1) * 3;
            final int low = b.get(at + 2) & 0xff;
            return ((b.get(at + (x & 1)) & 0xff) << 4) | ((x & 1) == 0 ? low & 0x0f : low >> 4);
        };
        return (x, y) -> com.scamera.app.util.RawUnpack.raw14(b, (y + firstRow) * stride + (x >> 2) * 7)[x & 3];
    }

    /** A frame's block means and maxima (GRID_X x GRID_Y blocks, the four CFA sites of QUADS x QUADS places a block). */
    public static final class Stats {
        public final float[] mean = new float[GRID_X * GRID_Y], max = new float[GRID_X * GRID_Y];
    }

    /** The blocks of a {@code width} x {@code height} frame; both frames of a pair are read at the same places. */
    public static Stats stats(Samples s, int width, int height) {
        final Stats out = new Stats();
        final int bw = width / GRID_X, bh = height / GRID_Y;
        for (int by = 0; by < GRID_Y; by++)
            for (int bx = 0; bx < GRID_X; bx++) {
                long sum = 0;
                int max = 0, n = 0;
                for (int j = 0; j < QUADS; j++) {
                    final int y = (by * bh + (2 * j + 1) * bh / (2 * QUADS)) & ~1;
                    for (int i = 0; i < QUADS; i++) {
                        final int x = (bx * bw + (2 * i + 1) * bw / (2 * QUADS)) & ~1;
                        for (int site = 0; site < 4; site++) {
                            final int px = Math.min(width - 1, x + (site & 1)), py = Math.min(height - 1, y + (site >> 1));
                            final int v = s.get(px, py);
                            sum += v;
                            if (v > max) max = v;
                            n++;
                        }
                    }
                }
                out.mean[by * GRID_X + bx] = (float) sum / n;
                out.max[by * GRID_X + bx] = max;
            }
        return out;
    }

    // ---------------------------------------------------------------- the measurement of a pair

    public enum Verdict {
        /** The overflow frame of this exposure: a stable ratio well above 1, the main frame's clipped areas held. */
        PAIR,
        /** A stable line of ratio about 1: a copy of the main frame, or a frame of the same read. */
        COPY,
        /** No line: another exposure, another moment, another picture. */
        UNSTABLE,
        /** Where the main frame is clipped the other one is clipped too (or darker than the clip point): it rescues nothing. */
        NO_HIGHLIGHTS,
        /** Too few usable blocks, or a scene too flat for a line: nothing is said. */
        NO_DATA
    }

    /** What a pair of frames shows. */
    public static final class Measure {
        public Verdict verdict = Verdict.NO_DATA;
        /** The main frame's signal over the overflow frame's, both as shares of their ranges (the vendor's 3 EV would be 8). */
        public double ratio;
        /** The overflow stream's black: the line's intercept. */
        public double black;
        /** The line's rms residual over the median signal. */
        public double residual;
        /** Blocks the line was fitted on, blocks clipped in the main frame, and those of them the overflow frame holds. */
        public int blocks, clipped, rescued;
        public String why = "";

        public boolean highlightsChecked() {
            return clipped >= 4;
        }

        @Override public String toString() {
            return String.format(Locale.ROOT, "%s: ratio %.3f, overflow black %.1f, residual %.3f, %d blocks, %d clipped in the main frame"
                    + " (%d held by the overflow frame)%s", verdict, ratio, black, residual, blocks, clipped, rescued, why.isEmpty() ? "" : "; " + why);
        }
    }

    /**
     * The line of the overflow frame's block means over the main frame's (above {@code mainBlack}, of white {@code mainWhite})
     * where neither is clipped, and what the overflow frame (of white {@code overflowWhite}) holds where the main one is.
     */
    public static Measure measure(Stats main, float mainBlack, int mainWhite, Stats overflow, int overflowWhite) {
        final Measure m = new Measure();
        final float range = mainWhite - mainBlack;
        if (range <= 0 || overflowWhite <= 0) return m;
        final int n = main.mean.length;
        final double[] x = new double[n], y = new double[n];
        final boolean[] use = new boolean[n];
        int count = 0;
        double lowX = Double.MAX_VALUE, highX = 0;
        for (int i = 0; i < n; i++) {
            final double net = main.mean[i] - mainBlack;
            final boolean mainClipped = main.max[i] >= mainBlack + 0.92f * range;
            if (main.mean[i] >= mainBlack + 0.97f * range) m.clipped++;
            if (mainClipped || overflow.max[i] >= 0.95f * overflowWhite || net < 0.01 * range) continue;
            x[i] = net;
            y[i] = overflow.mean[i];
            use[i] = true;
            count++;
            lowX = Math.min(lowX, net);
            highX = Math.max(highX, net);
        }
        if (count < 24 || highX - lowX < 0.05 * range) {
            m.blocks = count;
            m.why = count < 24 ? "too few blocks unclipped in both frames" : "the scene is too flat for a line";
            return m;
        }
        double a = 0, b = 0, rms = 0;
        for (int pass = 0; pass < 2; pass++) {
            double sx = 0, sy = 0, sxx = 0, sxy = 0;
            int k = 0;
            for (int i = 0; i < n; i++) if (use[i]) { sx += x[i]; sy += y[i]; sxx += x[i] * x[i]; sxy += x[i] * y[i]; k++; }
            final double den = k * sxx - sx * sx;
            if (k < 24 || den <= 0) { m.blocks = k; m.why = "too few blocks after the outliers"; return m; }
            a = (k * sxy - sx * sy) / den;
            b = (sy - a * sx) / k;
            double ss = 0;
            for (int i = 0; i < n; i++) if (use[i]) { final double r = y[i] - (a * x[i] + b); ss += r * r; }
            rms = Math.sqrt(ss / k);
            count = k;
            if (pass == 0) for (int i = 0; i < n; i++) if (use[i] && Math.abs(y[i] - (a * x[i] + b)) > 2.5 * rms) use[i] = false;
        }
        final double[] nets = new double[count];
        int k = 0;
        for (int i = 0; i < n; i++) if (use[i]) nets[k++] = x[i];
        Arrays.sort(nets, 0, k);
        final double median = nets[k / 2];
        m.blocks = count;
        m.black = b;
        final double overflowRange = overflowWhite - b;
        if (a <= 0 || overflowRange <= 0) {
            m.verdict = Verdict.UNSTABLE;
            m.why = "the overflow frame does not grow with the main one";
            return m;
        }
        m.ratio = overflowRange / (a * range);
        m.residual = rms / (a * median);
        // the main frame's clipped blocks: held when the overflow frame is unclipped there and at least as bright as the clip point
        final double clipPoint = b + 0.85 * overflowRange / m.ratio;
        for (int i = 0; i < n; i++)
            if (main.mean[i] >= mainBlack + 0.97f * range && overflow.max[i] < 0.97f * overflowWhite && overflow.mean[i] >= clipPoint) m.rescued++;
        if (m.residual > MAX_RESIDUAL) {
            m.verdict = Verdict.UNSTABLE;
            m.why = "the line does not fit";
        } else if (m.ratio < MIN_RATIO) {
            m.verdict = Verdict.COPY;
            m.why = "no second read: a ratio near 1";
        } else if (m.highlightsChecked() && 2 * m.rescued < m.clipped) {
            m.verdict = Verdict.NO_HIGHLIGHTS;
            m.why = "the main frame's clipped areas are not held";
        } else m.verdict = Verdict.PAIR;
        return m;
    }

    /** Two measured ratios are one stream's: within a tenth of each other. */
    static boolean agree(double first, double second) {
        return first > 0 && second > 0 && Math.abs(first / second - 1) <= AGREE;
    }

    // ---------------------------------------------------------------- pairing by the sensor timestamp

    /**
     * For each main frame the index of its overflow frame, -1 without one: the one of the nearest timestamp within
     * {@code tolerance} ns (the same exposure has the same sensor timestamp); an overflow frame is one main frame's only (the
     * nearer one's), a repeated overflow timestamp counts once (the first).
     */
    public static int[] pair(long[] mains, long[] overflows, long tolerance) {
        final int[] out = new int[mains.length];
        Arrays.fill(out, -1);
        final int[] owner = new int[overflows.length];
        Arrays.fill(owner, -1);
        for (int i = 0; i < mains.length; i++) {
            int best = -1;
            for (int j = 0; j < overflows.length; j++) {
                final long d = Math.abs(overflows[j] - mains[i]);
                if (d > tolerance) continue;
                if (best < 0 || d < Math.abs(overflows[best] - mains[i])) best = j; // an equal timestamp later in the list never wins
            }
            if (best < 0) continue;
            final int other = owner[best];
            if (other >= 0 && Math.abs(overflows[best] - mains[other]) <= Math.abs(overflows[best] - mains[i])) continue;
            if (other >= 0) out[other] = -1;
            owner[best] = i;
            out[i] = best;
        }
        return out;
    }

    // ---------------------------------------------------------------- the overflow frame as the exposure's short frame

    /** The exposure the merge is told for the overflow frame: the main frame's over the measured ratio. */
    public static long exposure(long mainExposure, double ratio) {
        return ratio > 0 ? Math.max(1L, Math.round(mainExposure / ratio)) : mainExposure;
    }

    /**
     * The overflow frame's noise in its own normalised units, {slope, offset}: the same photons as the main frame over a range
     * {@code ratio} times as wide (the main frame's slope over the ratio - after the merge's exposure scaling the two frames'
     * shot noise is one), and the main frame's read term plus the quantisation of a read of {@code range} codes. A model, not a
     * measurement: the worker's own noise check has the last word.
     */
    public static float[] noise(float mainSlope, float mainOffset, double ratio, double range) {
        final double step = range > 0 ? 1.0 / range : 0;
        return new float[]{(float) (mainSlope / Math.max(1.0, ratio)), (float) (mainOffset + step * step / 12.0)};
    }

    /**
     * The overflow frame at the main frame's levels: {@code (code - black) x (main range / overflow range) + the site's main black},
     * so its white is the main white and its black the main black; plain little-endian 16-bit rows of {@code width} into
     * {@code out}.
     */
    public static void rescale(Samples overflow, int width, int height, double black, int overflowWhite, float[] mainBlack, int mainWhite,
                               ByteBuffer out) {
        float meanBlack = 0;
        for (float v : mainBlack) meanBlack += v / mainBlack.length;
        final double gain = (mainWhite - meanBlack) / Math.max(1.0, overflowWhite - black);
        final ByteBuffer o = out.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                final double v = (overflow.get(x, y) - black) * gain + mainBlack[(y & 1) * 2 + (x & 1)];
                o.putShort((y * width + x) * 2, (short) Math.max(0, Math.min(65535, Math.round(v))));
            }
    }

    /** The partner of a main frame of {@code width} x {@code height}: a copy of {@code frame}, its row 0 at {@code firstRow}. */
    public static com.scamera.app.processing.ImageFrame.Overflow partner(Frame frame, int width, int height, int firstRow, double sessionRatio, String rung) {
        final com.scamera.app.processing.ImageFrame.Overflow p = new com.scamera.app.processing.ImageFrame.Overflow();
        p.packed = ByteBuffer.allocateDirect(frame.data.remaining());
        p.packed.put(frame.data.duplicate());
        p.packed.flip();
        p.width = width;
        p.height = height;
        p.stride = frame.stride;
        p.firstRow = firstRow;
        p.sessionRatio = sessionRatio;
        p.rung = rung;
        return p;
    }

    /**
     * The overflow frame of a shot as the merge takes it, measured once more against the main frame at the shot's final levels
     * ({@code mainBlack} per CFA site, {@code mainWhite}): the plain 16-bit frame at those levels, its exposure and its noise.
     * {@code buffer} is null when this pair is none at those levels (the shot goes on without it; {@code measure} says why).
     */
    public static final class Short {
        public ByteBuffer buffer;
        public long exposure;
        public float noiseSlope, noiseOffset;
        public Measure measure;
    }

    @Nullable
    public static Short asShort(Samples main, com.scamera.app.processing.ImageFrame.Overflow partner, float[] mainBlack, int mainWhite, long mainExposure, float mainSlope,
                                float mainOffset, int overflowWhite) {
        float meanBlack = 0;
        for (float v : mainBlack) meanBlack += v / mainBlack.length;
        final Samples overflow = packed(partner.packed, 10, partner.stride, partner.firstRow);
        final Measure m = measure(stats(main, partner.width, partner.height), meanBlack, mainWhite,
                stats(overflow, partner.width, partner.height), overflowWhite);
        final Short out = new Short();
        out.measure = m;
        if (m.verdict == Verdict.PAIR && partner.sessionRatio > 0 && !agree(m.ratio, partner.sessionRatio)) {
            m.verdict = Verdict.UNSTABLE;
            m.why = String.format(Locale.ROOT, "not the session's ratio %.3f", partner.sessionRatio);
        }
        if (m.verdict != Verdict.PAIR) return out;
        out.buffer = ByteBuffer.allocateDirect(partner.width * partner.height * 2);
        rescale(overflow, partner.width, partner.height, m.black, overflowWhite, mainBlack, mainWhite, out.buffer);
        out.exposure = exposure(mainExposure, m.ratio);
        final float[] noise = noise(mainSlope, mainOffset, m.ratio, overflowWhite - m.black);
        out.noiseSlope = noise[0];
        out.noiseOffset = noise[1];
        return out;
    }

    // ---------------------------------------------------------------- the session's overflow frames

    /** One overflow frame as its reader delivered it: packed RAW10 rows. */
    public static final class Frame {
        public long timestamp;
        public ByteBuffer data;
        public int width, height, stride;
    }

    /** The newest overflow frames of the session (copies: the reader's images are returned at once). */
    public static final class Ring {
        private final ArrayDeque<Frame> frames = new ArrayDeque<>();
        private final ArrayDeque<ByteBuffer> pool = new ArrayDeque<>();
        private final int capacity;
        private boolean frozen;
        private long stored, dropped;

        public Ring(int capacity) {
            this.capacity = Math.max(1, capacity);
        }

        /** A new frame; the oldest one leaves. Nothing is taken while a shot holds the ring ({@link #freeze}). */
        public synchronized void store(long timestamp, ByteBuffer source, int width, int height, int stride) {
            final int bytes = Math.min(source.remaining(), stride * height);
            if (frozen || bytes <= 0) { dropped++; return; }
            Frame f = frames.size() >= capacity ? frames.pollFirst() : new Frame();
            if (f.data == null || f.data.capacity() < bytes) f.data = pool.isEmpty() || pool.peek().capacity() < bytes
                    ? ByteBuffer.allocateDirect(bytes) : pool.poll();
            f.data.clear();
            final ByteBuffer src = source.duplicate();
            src.limit(src.position() + bytes);
            f.data.put(src);
            f.data.flip();
            f.timestamp = timestamp;
            f.width = width;
            f.height = height;
            f.stride = stride;
            frames.addLast(f);
            stored++;
        }

        /** The frame of {@code timestamp} (within {@code tolerance} ns, the nearest), null without one. */
        @Nullable
        public synchronized Frame find(long timestamp, long tolerance) {
            Frame best = null;
            for (Frame f : frames)
                if (Math.abs(f.timestamp - timestamp) <= tolerance
                        && (best == null || Math.abs(f.timestamp - timestamp) < Math.abs(best.timestamp - timestamp))) best = f;
            return best;
        }

        public synchronized long[] timestamps() {
            final long[] out = new long[frames.size()];
            int i = 0;
            for (Frame f : frames) out[i++] = f.timestamp;
            return out;
        }

        /** A shot reads the ring: it keeps what it has until {@link #release}. */
        public synchronized void freeze() {
            frozen = true;
        }

        public synchronized void release() {
            frozen = false;
        }

        public synchronized int size() {
            return frames.size();
        }

        public synchronized String describe() {
            return frames.size() + " of " + capacity + " frames, " + stored + " stored, " + dropped + " not taken";
        }

        public synchronized void clear() {
            for (Frame f : frames) if (f.data != null) pool.add(f.data);
            frames.clear();
            frozen = false;
        }
    }
}
