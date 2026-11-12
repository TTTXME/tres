package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * P635: a full-size frame is not proof of the full resolution (the owner's documents: without the companion stream the HAL
 * writes the binned frame twice over - 2x2 std 0, Laplacian 0, against 20 and 75 000 of the real frame). The check tells the
 * two apart on the frame's own numbers, and a doubled frame goes back to the binned one it was made of.
 */
public class FullResTruthTest {
    static final int W = 512, H = 384;
    static final int[] COLOUR = {110, 175, 175, 138}; // the documents' parity means of a real frame, R G G B

    /** A plain Bayer frame of {@code w} x {@code h}: black 64, a scene with detail, seeded noise. */
    static ByteBuffer bayer(int w, int h, long seed) {
        final ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.LITTLE_ENDIAN);
        final Random r = new Random(seed);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            final double scene = 0.6 + 0.4 * Math.sin(x * 0.11) * Math.cos(y * 0.07);
            final int c = COLOUR[(y & 1) * 2 + (x & 1)];
            b.putShort((y * w + x) * 2, (short) Math.max(0, Math.round(64 + c * scene + 4 * r.nextGaussian())));
        }
        return b;
    }

    /** The binned frame {@code half} (w/2 x h/2) written twice over, its boxes starting at ({@code px}, {@code py}). */
    static ByteBuffer doubled(ByteBuffer half, int w, int h, int px, int py) {
        final ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.LITTLE_ENDIAN);
        final int hw = w / 2, hh = h / 2;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            final int sx = Math.min(hw - 1, Math.max(0, Math.floorDiv(x - px, 2))), sy = Math.min(hh - 1, Math.max(0, Math.floorDiv(y - py, 2)));
            b.putShort((y * w + x) * 2, half.getShort((sy * hw + sx) * 2));
        }
        return b;
    }

    static ByteBuffer flat(int value) {
        final ByteBuffer b = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < W * H; i++) b.putShort(i * 2, (short) value);
        return b;
    }

    @Test public void aRealFrameIsReal() {
        final FullResTruth.Result r = FullResTruth.check(bayer(W, H, 1), W, H, W * 2);
        assertEquals(r.line(), FullResTruth.Verdict.REAL, r.verdict);
        assertTrue("2x2 std of a Bayer frame: " + r.std2x2, r.std2x2 > 5);
        assertTrue("no box of four equal sites: " + r.equalShare, r.equalShare < 0.01);
        assertTrue("the frame against its nearest-2x baseline: " + r.ratio, r.ratio > 5);
        assertTrue("two equal greens", r.bayerParity());
        assertEquals(r.parity[1], r.parity[2], 2.0);
        assertTrue(r.parity[0] < r.parity[3] && r.parity[3] < r.parity[1]);
        final String line = r.line();
        assertTrue(line, line.startsWith("FULLRES RECIPE: frame 512x384 2x2std="));
        assertTrue(line, line.contains("-> real full-res") && line.contains("plain Bayer") && line.contains("ratio="));
    }

    @Test public void theBinnedFrameWrittenTwiceOverIsCaught() {
        final ByteBuffer half = bayer(W / 2, H / 2, 2);
        final FullResTruth.Result r = FullResTruth.check(doubled(half, W, H, 0, 0), W, H, W * 2);
        assertEquals(r.line(), FullResTruth.Verdict.UPSCALED, r.verdict);
        assertEquals("the documents' 2x2 std 0", 0.0, r.std2x2, 0);
        assertEquals(1.0, r.equalShare, 0);
        assertEquals(0, r.phase);
        assertTrue("its boxes differ from their neighbours: " + r.neighbourShare, r.neighbourShare > 0.5);
        assertEquals("it is its own nearest-2x baseline", 1.0, r.ratio, 1e-6);
        assertTrue(r.line(), r.line().contains("-> UPSCALED"));
    }

    @Test public void aDoubledFrameAtAnotherPhaseIsCaughtToo() {
        final ByteBuffer half = bayer(W / 2, H / 2, 3);
        for (int phase = 1; phase < 4; phase++) {
            final FullResTruth.Result r = FullResTruth.check(doubled(half, W, H, phase & 1, phase >> 1), W, H, W * 2);
            assertEquals("phase " + phase + ": " + r.line(), FullResTruth.Verdict.UPSCALED, r.verdict);
            assertEquals(phase, r.phase);
            assertTrue(r.equalShare > 0.99);
        }
    }

    @Test public void aFrameWithoutLightOrAllClippedCannotBeTold() {
        for (int value : new int[]{0, 64, 1023}) {
            final FullResTruth.Result r = FullResTruth.check(flat(value), W, H, W * 2);
            assertEquals(value + ": " + r.line(), FullResTruth.Verdict.UNKNOWN, r.verdict);
            assertTrue(r.line().contains("cannot tell"));
        }
        assertEquals(FullResTruth.Verdict.UNKNOWN, FullResTruth.check(null, W, H, W * 2).verdict);
        assertEquals("a buffer too short for its size", FullResTruth.Verdict.UNKNOWN, FullResTruth.check(bayer(W, H / 2, 4), W, H, W * 2).verdict);
        // a dark real frame still has the sensor's noise in its boxes: real
        final ByteBuffer dark = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
        final Random r = new Random(5);
        for (int i = 0; i < W * H; i++) dark.putShort(i * 2, (short) Math.round(64 + 3 * r.nextGaussian()));
        assertEquals(FullResTruth.Verdict.REAL, FullResTruth.check(dark, W, H, W * 2).verdict);
    }

    @Test public void aDoubledFrameGoesBackToTheBinnedOne() {
        final ByteBuffer half = bayer(W / 2, H / 2, 6);
        final ByteBuffer out = ByteBuffer.allocateDirect(W / 2 * (H / 2) * 2).order(ByteOrder.nativeOrder());
        assertTrue(FullResTruth.halve(out, doubled(half, W, H, 0, 0), 0, W, H, W * 2, 0));
        for (int i = 0; i < W / 2 * (H / 2); i++) assertEquals("site " + i, half.getShort(i * 2), out.order(ByteOrder.LITTLE_ENDIAN).getShort(i * 2));
        assertEquals(W / 2, FullResTruth.halfSize(W, H)[0]);
        assertEquals(H / 2, FullResTruth.halfSize(W, H)[1]);
        // a padded stride
        final int stride = W * 2 + 64;
        final ByteBuffer padded = ByteBuffer.allocateDirect(stride * H).order(ByteOrder.LITTLE_ENDIAN);
        final ByteBuffer src = doubled(half, W, H, 0, 0);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) padded.putShort(y * stride + 2 * x, src.getShort((y * W + x) * 2));
        final ByteBuffer out2 = ByteBuffer.allocateDirect(W / 2 * (H / 2) * 2).order(ByteOrder.nativeOrder());
        assertTrue(FullResTruth.halve(out2, padded, 0, W, H, stride, 0));
        assertEquals(half.getShort(1234 * 2), out2.order(ByteOrder.LITTLE_ENDIAN).getShort(1234 * 2));
        assertEquals(FullResTruth.Verdict.UPSCALED, FullResTruth.check(padded, W, H, stride).verdict);
        // buffers that do not hold it
        assertFalse(FullResTruth.halve(ByteBuffer.allocateDirect(16), src, 0, W, H, W * 2, 0));
        assertFalse(FullResTruth.halve(out, null, 0, W, H, W * 2, 0));
    }

    @Test public void aShotIsJudgedOnItsFirstFrameOnce() {
        final FullResRecipeShot shot = new FullResRecipeShot("2", "test");
        assertNull(shot.verdict());
        assertFalse(shot.upscaled());
        final FullResTruth.Result first = shot.judge(doubled(bayer(W / 2, H / 2, 7), W, H, 0, 0), W, H, W * 2);
        assertEquals(FullResTruth.Verdict.UPSCALED, first.verdict);
        assertTrue(shot.upscaled());
        // the later frames follow the first one's verdict (one decision a shot: every frame the same size)
        assertSame(first, shot.judge(bayer(W, H, 8), W, H, W * 2));
        assertNotNull(shot.verdict());
    }
}
