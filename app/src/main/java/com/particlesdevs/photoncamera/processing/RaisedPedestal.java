package com.particlesdevs.photoncamera.processing;

import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Locale;

/**
 * P635c: a RAW stream whose frames sit on a pedestal far above the declared black.
 * <p>
 * The Xiaomi 17 Ultra's full-resolution recipe session (FullResRecipe: the sensor in its full-size mode, the stills 8192x6144)
 * still delivers the camera's binned 4096x3072 RAW stream for the viewfinder, with the full-size mode's levels in its results
 * (black 64, white 1023) - and its frames do not sit on that black. The owner's documents measured both streams of one such
 * session on one scene: the still's 2x2 means 94.0 / 138.4 / 138.4 / 113.9, the binned frame's 608.9 / 684.5 / 684.5 / 645.9 -
 * about 558 + 1.7 x (the still less its black), a pedestal near 560 DN. The viewfinder subtracted 64: half of the range left
 * on every channel, times the white balance's 2.3 / 1 / 1.5 - magenta over the whole frame, washed and flat (owner's log
 * 2026-11-12: the darkest tenth of the developed frame at 1.0 of white, the viewfinder's AE gain 0.37 for 2.4 on the same
 * camera's normal sessions). No sample is above the declared white, so nothing reads as a wider stream (RawPayloadCheck).
 * <p>
 * The pedestal of a stream is never above the darkest area of any of its frames. A frame's floor here is the third-lowest
 * block mean over its four CFA sites; the session keeps the lowest floor it has seen. While that floor lies above the declared
 * black by more than {@link #RAISE} of the declared range - darker than no real scene's darkest block: an eighth of the range
 * in linear RAW is close to mid grey - it is the stream's black; a floor that comes down to the declared black (a stream that
 * was merely looking at a white wall) gives the declared black back. The white stays the declared one.
 * <p>
 * Used only where a session says its viewfinder stream may be so (CaptureController: a recipe session); the photo's own levels
 * never pass through here.
 */
public final class RaisedPedestal {
    /** Sites a block spans a side, and the step between the sampled ones (16 samples a block). */
    static final int BLOCK = 16, STEP = 4;
    /** The most blocks a side (a 4096x3072 frame: every other block). */
    static final int MAX_BLOCKS = 96;
    /** A floor this share of the declared range above the declared black is a pedestal, not a scene. */
    public static final float RAISE = 0.125f;

    /** One frame's block means by CFA site ((x & 1) + 2 (y & 1)): the third-lowest, the median and the 99th percentile. */
    public static final class Stats {
        public final float[] floor = new float[4], median = new float[4], high = new float[4];
        /** The largest sample read. */
        public int max;
        public int blocks;

        /** The lowest floor of the four sites. */
        public float lowest() {
            return Math.min(Math.min(floor[0], floor[1]), Math.min(floor[2], floor[3]));
        }

        @Override public String toString() {
            return String.format(Locale.ROOT, "floor %.0f/%.0f/%.0f/%.0f median %.0f/%.0f/%.0f/%.0f p99 %.0f/%.0f/%.0f/%.0f max %d",
                    floor[0], floor[1], floor[2], floor[3], median[0], median[1], median[2], median[3],
                    high[0], high[1], high[2], high[3], max);
        }
    }

    /** The stats of a plain 16-bit frame ({@code rowPx}: samples a row of the buffer); null for a frame too small to measure. */
    public static Stats measure(ShortBuffer d, int w, int h, int rowPx) {
        final int sx = w / 2, sy = h / 2; // sites a channel
        int bw = sx / BLOCK, bh = sy / BLOCK;
        if (d == null || bw < 4 || bh < 4 || rowPx < w || d.capacity() < (long) (h - 1) * rowPx + w) return null;
        final int skipX = Math.max(1, (bw + MAX_BLOCKS - 1) / MAX_BLOCKS), skipY = Math.max(1, (bh + MAX_BLOCKS - 1) / MAX_BLOCKS);
        final int nx = (bw + skipX - 1) / skipX, ny = (bh + skipY - 1) / skipY;
        final Stats out = new Stats();
        out.blocks = nx * ny;
        final float[] means = new float[nx * ny];
        for (int c = 0; c < 4; c++) {
            final int ox = c & 1, oy = c >> 1;
            int n = 0;
            for (int by = 0; by < bh; by += skipY) for (int bx = 0; bx < bw; bx += skipX) {
                long sum = 0;
                int count = 0;
                for (int j = 0; j < BLOCK; j += STEP) {
                    final int row = ((by * BLOCK + j) * 2 + oy) * rowPx;
                    for (int i = 0; i < BLOCK; i += STEP) {
                        final int v = d.get(row + (bx * BLOCK + i) * 2 + ox) & 0xFFFF;
                        sum += v;
                        count++;
                        if (v > out.max) out.max = v;
                    }
                }
                means[n++] = sum / (float) count;
            }
            Arrays.sort(means, 0, n);
            out.floor[c] = means[Math.min(n - 1, 2)];
            out.median[c] = means[n / 2];
            out.high[c] = means[Math.min(n - 1, (int) (n * 0.99f))];
        }
        return out;
    }

    /** Whether a floor of {@code lowest} is a pedestal above {@code declaredBlack} of a stream of {@code white}. */
    public static boolean raised(float lowest, float declaredBlack, float white) {
        return white > declaredBlack && lowest > declaredBlack + RAISE * (white - declaredBlack) && lowest < white;
    }

    private int session = Integer.MIN_VALUE;
    private float low = Float.NaN;
    private Stats last;

    /** A frame of camera session {@code frameSession}: its stats join the session's lowest floor. */
    public synchronized void observe(int frameSession, Stats stats) {
        if (frameSession != session) {
            session = frameSession;
            low = Float.NaN;
            last = null;
        }
        if (stats == null) return;
        last = stats;
        final float f = stats.lowest();
        if (f > 0f && (Float.isNaN(low) || f < low)) low = f;
    }

    /** The session's lowest floor (NaN: no frame measured yet). */
    public synchronized float floor(int frameSession) {
        return frameSession == session ? low : Float.NaN;
    }

    /** The newest frame's stats of the session, null without one. */
    public synchronized Stats last(int frameSession) {
        return frameSession == session ? last : null;
    }

    /**
     * The black of the session's frames: {@code declared} unless the session's lowest floor is a pedestal above it
     * ({@link #raised}) - then that floor on every site (a new array).
     */
    public synchronized float[] black(int frameSession, float[] declared, float white) {
        if (declared == null || declared.length < 4 || frameSession != session || Float.isNaN(low)) return declared;
        float min = Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) min = Math.min(min, declared[i]);
        if (!raised(low, min, white)) return declared;
        final float[] out = new float[declared.length];
        Arrays.fill(out, low);
        return out;
    }
}
