package com.scamera.app.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * P641d: DCG + LOFIC as a pair of frames of one exposure - the pairing by the sensor timestamp, the proof on the frames (a copy
 * and an unrelated frame refused, a true pair accepted and its ratio and black measured), the overflow frame as the exposure's
 * short frame. The scene is synthetic: a 14-bit main frame (black 1024, white 16383) whose highlights clip, and a packed RAW10
 * overflow frame of the same radiance an eighth as sensitive. Nothing ran on a phone.
 */
public class LoficPairTest {
    private static final int W = 960, H = 720, MAIN_BLACK = 1024, MAIN_WHITE = 16383, OV_WHITE = 1023;

    /** The scene's radiance as a share of the main frame's range: shadows to three times its white (a third of the frame clips). */
    private static double radiance(int x, int y) {
        final double ramp = 3.0 * x / W;
        final double texture = 0.25 * Math.sin(x * 0.045) * Math.cos(y * 0.06) + 0.15 * Math.sin(y * 0.021);
        return Math.max(0.01, ramp * (1 + texture));
    }

    private static ByteBuffer main(long seed) {
        final Random r = new Random(seed);
        final ByteBuffer b = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                final double v = MAIN_BLACK + radiance(x, y) * (MAIN_WHITE - MAIN_BLACK) + r.nextGaussian() * 12;
                b.putShort((y * W + x) * 2, (short) Math.max(0, Math.min(MAIN_WHITE, Math.round(v))));
            }
        return b;
    }

    /** Overflow codes of the scene at {@code ratio} (the main signal's share over it), black {@code black}, as 10-bit values. */
    private static int[][] overflow(double ratio, double black, long seed, int shiftX) {
        final Random r = new Random(seed);
        final int[][] v = new int[H][W];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                final double s = black + radiance((x + shiftX) % W, y) / ratio * (OV_WHITE - black) + r.nextGaussian() * 0.8;
                v[y][x] = (int) Math.max(0, Math.min(OV_WHITE, Math.round(s)));
            }
        return v;
    }

    private static ByteBuffer packed10(int[][] v, int stride) {
        final ByteBuffer b = ByteBuffer.allocateDirect(stride * H);
        for (int y = 0; y < H; y++)
            for (int x = 0, at = y * stride; x < W; x += 4, at += 5) {
                final int p0 = v[y][x], p1 = v[y][x + 1], p2 = v[y][x + 2], p3 = v[y][x + 3];
                b.put(at, (byte) (p0 >> 2)); b.put(at + 1, (byte) (p1 >> 2)); b.put(at + 2, (byte) (p2 >> 2)); b.put(at + 3, (byte) (p3 >> 2));
                b.put(at + 4, (byte) ((p3 & 3) << 6 | (p2 & 3) << 4 | (p1 & 3) << 2 | (p0 & 3)));
            }
        return b;
    }

    private static LoficPair.Measure measure(ByteBuffer main, int[][] overflow) {
        final int stride = W * 10 / 8 + 20;
        return LoficPair.measure(LoficPair.stats(LoficPair.plain16(main, W * 2, 0), W, H), MAIN_BLACK, MAIN_WHITE,
                LoficPair.stats(LoficPair.packed(packed10(overflow, stride), 10, stride, 0), W, H), OV_WHITE);
    }

    // ---------------------------------------------------------------- pairing

    @Test public void framesPairByTheSensorTimestamp() {
        assertArrayEquals("each main frame its own overflow frame, whatever the order",
                new int[]{1, 0, 2}, LoficPair.pair(new long[]{100, 200, 300}, new long[]{200, 100, 300}, 10));
        assertArrayEquals("a missing partner", new int[]{0, -1, 1}, LoficPair.pair(new long[]{100, 200, 300}, new long[]{100, 300}, 10));
        assertArrayEquals("a repeated overflow timestamp counts once (the first)",
                new int[]{0, 2}, LoficPair.pair(new long[]{100, 200}, new long[]{100, 100, 200}, 10));
        assertArrayEquals("one overflow frame is the nearer main frame's only", new int[]{-1, 0}, LoficPair.pair(new long[]{100, 104}, new long[]{103}, 10));
        assertArrayEquals("the next frame (33 ms on) is no partner", new int[]{-1}, LoficPair.pair(new long[]{100}, new long[]{33_000_100L}, 500_000));
        assertArrayEquals("streams stamped a little apart", new int[]{0}, LoficPair.pair(new long[]{1_000_000}, new long[]{1_300_000}, 500_000));
        assertEquals(0, LoficPair.pair(new long[0], new long[]{1}, 10).length);
    }

    // ---------------------------------------------------------------- the proof

    /** The vendor's pair: 3 EV down. The ratio and the overflow stream's black are measured, the clipped third is held. */
    @Test public void aTruePairIsAcceptedAndMeasured() {
        final LoficPair.Measure m = measure(main(1), overflow(8, 64, 2, 0));
        assertEquals(m.toString(), LoficPair.Verdict.PAIR, m.verdict);
        assertEquals("the ratio within a few per cent", 8.0, m.ratio, 0.24);
        assertEquals("the overflow black", 64.0, m.black, 2.0);
        assertTrue(m.toString(), m.residual < 0.05);
        assertTrue("a third of the frame clips in the main frame: " + m.clipped, m.highlightsChecked() && m.clipped > 200);
        assertTrue("and is held by the overflow frame: " + m.rescued + " of " + m.clipped, m.rescued > 0.9 * m.clipped);
    }

    /** Nothing is assumed: another ratio and a raised black are measured as they are. */
    @Test public void theRatioAndTheBlackAreMeasuredNotAssumed() {
        final LoficPair.Measure six = measure(main(3), overflow(6, 64, 4, 0));
        assertEquals(LoficPair.Verdict.PAIR, six.verdict);
        assertEquals(6.0, six.ratio, 0.18);
        final LoficPair.Measure raised = measure(main(5), overflow(8, 200, 6, 0));
        assertEquals(raised.toString(), LoficPair.Verdict.PAIR, raised.verdict);
        assertEquals("a stream on a raised pedestal", 200.0, raised.black, 2.5);
        assertEquals(8.0, raised.ratio, 0.24);
        assertTrue(LoficPair.agree(8.0, 8.3));
        assertFalse(LoficPair.agree(8.0, 9.0));
        assertFalse(LoficPair.agree(0, 8.0));
    }

    /** A second frame of the same read (the main frame at 10 bits) is a line of ratio 1: no overflow frame. */
    @Test public void aCopyIsRefused() {
        final LoficPair.Measure m = measure(main(7), overflow(1, 64, 8, 0));
        assertEquals(m.toString(), LoficPair.Verdict.COPY, m.verdict);
        assertEquals(1.0, m.ratio, 0.05);
    }

    /** Another picture (the scene shifted by a third of the frame) is no line of the main frame. */
    @Test public void anUnrelatedFrameIsRefused() {
        final LoficPair.Measure m = measure(main(9), overflow(8, 64, 10, W / 3));
        assertEquals(m.toString(), LoficPair.Verdict.UNSTABLE, m.verdict);
    }

    /** A frame that follows the main one where it is unclipped but is flat and dark where it clips rescues nothing. */
    @Test public void aFrameWithoutTheHighlightsIsRefused() {
        final int[][] o = overflow(8, 64, 12, 0);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (radiance(x, y) > 0.9) o[y][x] = 64 + 40;
        final LoficPair.Measure m = measure(main(11), o);
        assertEquals(m.toString(), LoficPair.Verdict.NO_HIGHLIGHTS, m.verdict);
        assertTrue(2 * m.rescued < m.clipped);
    }

    @Test public void aFlatSceneSaysNothing() {
        final ByteBuffer flat = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < W * H; i++) flat.putShort(i * 2, (short) 5000);
        final int[][] o = new int[H][W];
        for (int[] row : o) java.util.Arrays.fill(row, 100);
        final LoficPair.Measure m = measure(flat, o);
        assertEquals(LoficPair.Verdict.NO_DATA, m.verdict);
        assertFalse(m.why.isEmpty());
    }

    // ---------------------------------------------------------------- the samples of the layouts

    @Test public void packedSamplesAreRead() {
        final int[][] v = overflow(8, 64, 13, 0);
        final int stride = W * 10 / 8 + 16;
        final LoficPair.Samples ten = LoficPair.packed(packed10(v, stride), 10, stride, 0);
        for (int y = 0; y < H; y += 53) for (int x = 0; x < W; x += 7) assertEquals(v[y][x], ten.get(x, y));
        final LoficPair.Samples cut = LoficPair.packed(packed10(v, stride), 10, stride, 90); // a 16:9 cut: the frame's row 0 is row 90
        assertEquals(v[90][5], cut.get(5, 0));
        // RAW12 (two samples in three bytes) and RAW14 (four in seven), as the dual-gain main frames may arrive
        final ByteBuffer twelve = ByteBuffer.allocateDirect(6), fourteen = ByteBuffer.allocateDirect(7);
        final int a = 0xABC, b = 0x123;
        twelve.put(0, (byte) (a >> 4)); twelve.put(1, (byte) (b >> 4)); twelve.put(2, (byte) ((a & 0xf) | (b & 0xf) << 4));
        assertEquals(a, LoficPair.packed(twelve, 12, 3, 0).get(0, 0));
        assertEquals(b, LoficPair.packed(twelve, 12, 3, 0).get(1, 0));
        final int[] p = {0x3FFF, 0x1234, 0x0001, 0x2ABC};
        for (int i = 0; i < 4; i++) fourteen.put(i, (byte) (p[i] >> 6));
        fourteen.put(4, (byte) ((p[0] & 0x3f) | ((p[1] & 3) << 6)));
        fourteen.put(5, (byte) (((p[1] >> 2) & 0x0f) | ((p[2] & 0x0f) << 4)));
        fourteen.put(6, (byte) (((p[2] >> 4) & 3) | ((p[3] & 0x3f) << 2)));
        for (int i = 0; i < 4; i++) assertEquals(p[i], LoficPair.packed(fourteen, 14, 7, 0).get(i, 0));
    }

    // ---------------------------------------------------------------- the overflow frame as the exposure's short frame

    /** At the main frame's levels: black on black, white on white, and the unclipped signal the main frame's over the ratio. */
    @Test public void theOverflowFrameAtTheMainFramesLevels() {
        final ByteBuffer main = main(15);
        final int[][] o = overflow(8, 64, 16, 0);
        final int stride = W * 10 / 8;
        final LoficPair.Samples os = LoficPair.packed(packed10(o, stride), 10, stride, 0);
        final float[] black = {1024, 1020, 1028, 1024};
        final ByteBuffer out = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
        LoficPair.rescale(os, W, H, 64, OV_WHITE, black, MAIN_WHITE, out);
        final double gain = (MAIN_WHITE - 1024.0) / (OV_WHITE - 64.0);
        for (int y = 0; y < H; y += 41) for (int x = 0; x < W; x += 13)
            assertEquals((o[y][x] - 64) * gain + black[(y & 1) * 2 + (x & 1)], out.getShort((y * W + x) * 2) & 0xffff, 0.51);
        // a dark and an unclipped bright place: x 8 of the rescaled signal is the main frame's
        double sumMain = 0, sumShort = 0;
        for (int y = 100; y < 600; y += 2) for (int x = 100; x < 280; x += 2) {
            sumMain += (main.getShort((y * W + x) * 2) & 0xffff) - 1024;
            sumShort += (out.getShort((y * W + x) * 2) & 0xffff) - 1024;
        }
        assertEquals(8.0, sumMain / sumShort, 0.2);
        // the overflow stream's white is the main white: a place clipped in both stays clipped for the merge
        final int[][] white = new int[H][W];
        for (int[] row : white) java.util.Arrays.fill(row, OV_WHITE);
        LoficPair.rescale(LoficPair.packed(packed10(white, stride), 10, stride, 0), W, H, 64, OV_WHITE, new float[]{1024, 1024, 1024, 1024}, MAIN_WHITE, out);
        assertEquals(MAIN_WHITE, out.getShort(0) & 0xffff);
    }

    /** What the worker is told: the exposure through the measured ratio, the noise scaled with it, the frame itself. */
    @Test public void theNumbersHandedToTheMerge() {
        final ByteBuffer main = main(17);
        final int stride = W * 10 / 8 + 20;
        final LoficPair.Frame frame = new LoficPair.Frame();
        frame.data = packed10(overflow(8, 64, 18, 0), stride);
        frame.width = W;
        frame.height = H;
        frame.stride = stride;
        frame.timestamp = 77;
        final com.scamera.app.processing.ImageFrame.Overflow partner = LoficPair.partner(frame, W, H, 0, 8.1, "lofic-pair");
        final LoficPair.Short s = LoficPair.asShort(LoficPair.plain16(main, W * 2, 0), partner, new float[]{1024, 1024, 1024, 1024}, MAIN_WHITE,
                10_000_000L, 2.6e-5f, 4.4e-7f, OV_WHITE);
        assertNotNull(s.buffer);
        assertEquals(LoficPair.Verdict.PAIR, s.measure.verdict);
        assertEquals("the exposure the merge scales by: the main frame's over the measured ratio", 1_250_000.0, s.exposure, 40_000);
        assertEquals(LoficPair.exposure(10_000_000L, s.measure.ratio), s.exposure);
        assertEquals("the same photons: the slope over the ratio", 2.6e-5 / s.measure.ratio, s.noiseSlope, 1e-8);
        final double step = 1.0 / (OV_WHITE - s.measure.black);
        assertEquals("the main read term and a 10-bit read's quantisation", 4.4e-7 + step * step / 12, s.noiseOffset, 1e-9);
        assertEquals(W * H * 2, s.buffer.capacity());
        // a copy of the main frame is handed over as nothing
        partner.packed = packed10(overflow(1, 64, 19, 0), stride);
        final LoficPair.Short copy = LoficPair.asShort(LoficPair.plain16(main, W * 2, 0), partner, new float[]{1024, 1024, 1024, 1024}, MAIN_WHITE,
                10_000_000L, 2.6e-5f, 4.4e-7f, OV_WHITE);
        assertNull(copy.buffer);
        assertEquals(LoficPair.Verdict.COPY, copy.measure.verdict);
        // a pair of another ratio than the session proved (another stream, another mode) is handed over as nothing either
        partner.packed = packed10(overflow(4, 64, 20, 0), stride);
        final LoficPair.Short other = LoficPair.asShort(LoficPair.plain16(main, W * 2, 0), partner, new float[]{1024, 1024, 1024, 1024}, MAIN_WHITE,
                10_000_000L, 2.6e-5f, 4.4e-7f, OV_WHITE);
        assertNull(other.buffer);
        assertEquals(LoficPair.Verdict.UNSTABLE, other.measure.verdict);
        assertEquals("no ratio: the exposure stays", 5L, LoficPair.exposure(5L, 0));
    }

    // ---------------------------------------------------------------- the session's ring and the ladder's rung

    @Test public void theRingKeepsTheNewestFrames() {
        final LoficPair.Ring ring = new LoficPair.Ring(3);
        final ByteBuffer src = ByteBuffer.allocateDirect(40);
        for (int t = 1; t <= 5; t++) {
            src.put(0, (byte) t);
            ring.store(t * 1000L, src, 4, 2, 20);
        }
        assertArrayEquals(new long[]{3000, 4000, 5000}, ring.timestamps());
        assertNull("left the ring", ring.find(1000, 100));
        assertEquals(4, ring.find(4040, 100).data.get(0));
        assertNull(ring.find(4200, 100));
        ring.freeze();
        ring.store(6000, src, 4, 2, 20);
        assertNull("a shot holds the ring", ring.find(6000, 100));
        ring.release();
        ring.store(6000, src, 4, 2, 20);
        assertNotNull(ring.find(6000, 0));
        ring.clear();
        assertEquals(0, ring.size());
    }

    /** P700: the pair rung after the single-frame LOFIC rungs (a plain session lost every request of it, log 2026-11-14). */
    @Test public void theLadderHasOnePairRungAfterTheSingleFrameOnes() {
        final java.util.List<XiaomiDualGain.Variant> lofic = XiaomiDualGain.ladder(XiaomiDualGain.Kind.LOFIC, 5, 5, true);
        final XiaomiDualGain.Variant pair = lofic.get(3);
        assertEquals("lofic-pair", pair.id);
        assertTrue(pair.pair);
        assertEquals("lofic-pair {enableLofic 1, EnableHDRDCGMode 1, current_mode 5} + the overflow stream (RAW10, use case 0x8010a)",
                pair.toString());
        for (int i = 0; i < lofic.size(); i++) if (i != 3) assertFalse("one pair rung only", lofic.get(i).pair);
        for (int i = 0; i < 3; i++) assertTrue("the single-frame LOFIC rungs first", lofic.get(i).id.startsWith("lofic+"));
        for (XiaomiDualGain.Variant v : XiaomiDualGain.ladder(XiaomiDualGain.Kind.DCG, 4, 4, true)) assertFalse(v.pair);
        assertEquals(524554L, LoficPair.USE_CASE);
    }

    /** The pair rung is proven by the depth and by two pairs in a row of one ratio; a measure that is none starts again. */
    @Test public void thePairRungsProof() {
        final XiaomiDualGain.Proof p = new XiaomiDualGain.Proof(90, true);
        p.results = 20;
        p.frames = 3;
        p.note(14, 14, "dynamic black 1024");
        assertEquals("the depth alone is not the pair", XiaomiDualGain.Verdict.PENDING, p.verdict());
        p.notePair(LoficPair.Verdict.PAIR, 8.0);
        assertFalse(p.pairProven());
        p.notePair(LoficPair.Verdict.COPY, 1.0);
        p.notePair(LoficPair.Verdict.PAIR, 8.1);
        assertFalse("not in a row", p.pairProven());
        p.notePair(LoficPair.Verdict.NO_DATA, 0);
        p.notePair(LoficPair.Verdict.PAIR, 7.9);
        assertTrue(p.pairProven());
        assertEquals(8.0, p.pairRatio, 0.01);
        assertEquals(1, p.pairBad);
        assertEquals(XiaomiDualGain.Verdict.PROVEN, p.verdict());

        final XiaomiDualGain.Proof none = new XiaomiDualGain.Proof(90, true);
        none.note(14, 14, "dynamic black 1024");
        none.frames = 4;
        none.results = 90;
        none.notePair(LoficPair.Verdict.PAIR, 8.0);
        none.notePair(LoficPair.Verdict.PAIR, 5.0);
        assertEquals("two ratios that disagree prove nothing: the rung fails, the single-frame rungs follow",
                XiaomiDualGain.Verdict.FAILED, none.verdict());
        assertEquals("a rung without the pair is proven by the depth as before", XiaomiDualGain.Verdict.PROVEN, proven(new XiaomiDualGain.Proof(90)));
    }

    private static XiaomiDualGain.Verdict proven(XiaomiDualGain.Proof p) {
        p.note(14, 0, "dynamic black");
        return p.verdict();
    }
}
