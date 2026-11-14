package com.scamera.app.capture;

import static org.junit.Assert.assertArrayEquals;
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
        assertTrue(line, line.startsWith("FULLRES RECIPE: frame 512x384 valid 512x384+0+0 (100% of the buffer, cells with a picture 100%) floor "));
        assertTrue(line, line.contains(" 2x2std="));
        assertEquals("the whole buffer holds the picture", 1.0, r.boxShare, 0);
        assertFalse(r.notReal(true));
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

    /**
     * P635b: the binned frame {@code half} (w/2 x h/2) at its own pitch in the start of a w x h buffer - two binned rows side by
     * side in each of the first h/4 buffer rows, zeros below: what the Xiaomi 17 Ultra's HAL gave for the rows 11 / 0 / 22 (and
     * the nubia's for its maximum-resolution stream, P608).
     */
    static ByteBuffer packedQuarter(ByteBuffer half, int w, int h) {
        final ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.LITTLE_ENDIAN);
        final ByteBuffer in = half.duplicate();
        in.position(0);
        b.duplicate().put(in);
        return b;
    }

    @Test public void theBinnedFrameInAQuarterOfTheBufferIsNoFullFrame() {
        final int w = 1024, h = 768;
        final ByteBuffer half = bayer(w / 2, h / 2, 9);
        final ByteBuffer frame = packedQuarter(half, w, h);
        final FullResTruth.Result r = FullResTruth.check(frame, w, h, w * 2, 64);
        assertEquals(r.line(), FullResTruth.Verdict.PARTIAL, r.verdict);
        assertArrayEquals("the picture: every column of the first quarter of the rows", new int[]{0, 0, w, h / 4}, r.box);
        assertEquals(0.25, r.boxShare, 1e-9);
        assertEquals(0.25, r.validShare, 1e-9);
        assertTrue(r.packedQuarter());
        assertTrue(r.notReal(false));
        assertFalse("its own samples keep the pedestal: " + r.floor, r.pedestalLost());
        assertTrue(r.floor > 48);
        final String line = r.line("FULLRES ROW");
        assertTrue(line, line.startsWith("FULLRES ROW: frame 1024x768 valid 1024x192+0+0 (25% of the buffer, cells with a picture 25%) floor "));
        assertTrue(line, line.contains("-> PARTIAL") && line.contains("the binned frame at its own pitch in the first quarter of the rows"));
        // the binned frame comes back whole
        final ByteBuffer out = ByteBuffer.allocateDirect(w / 2 * (h / 2) * 2).order(ByteOrder.nativeOrder());
        assertTrue(FullResTruth.unpackQuarter(out, frame, w, h, w * 2));
        for (int i = 0; i < w / 2 * (h / 2); i += 7) assertEquals("site " + i, half.getShort(i * 2), out.order(ByteOrder.LITTLE_ENDIAN).getShort(i * 2));
        assertFalse("padded rows are another pitch", FullResTruth.unpackQuarter(out, frame, w, h, w * 2 + 64));
        assertFalse(FullResTruth.unpackQuarter(ByteBuffer.allocateDirect(8), frame, w, h, w * 2));
    }

    /**
     * P635b: why P631 passed that frame - every statistic the log had looked only at the samples above the floor, a fifth of the
     * centre («1 % equal of 190008» of about a million pairs; MosaicStream «5391/0/0 of 23751» tiles of about 125 000; «36864 of
     * 49152 blocks below 16 DN», exactly three quarters): a confident plain Bayer of a buffer that is three quarters empty.
     */
    @Test public void theOldStatisticsSawOnlyTheFilledQuarter() {
        final int w = 1024, h = 768;
        final ByteBuffer frame = packedQuarter(bayer(w / 2, h / 2, 10), w, h);
        // the frame's 2 % percentile is the empty part's zero: «floor 0 DN, no pedestal»
        assertEquals(0f, XiaomiSensorRow.frameFloor(frame, w, h, w * 2), 0f);
        // the pair statistics count the lit rows of the centre only, and call them a plain Bayer
        final double[] pairs = XiaomiSensorRow.cellStats(frame, w, h, w * 2, 0f);
        final double all = Math.ceil((h * 0.9 - (h / 10 & ~1)) / 16.0) * (((w * 9 / 10) & ~3) - ((w / 10) & ~3) - 4) / 2.0;
        assertTrue("a fifth of the pairs: " + pairs[3] + " of " + all, pairs[3] > 0.1 * all && pairs[3] < 0.3 * all);
        assertEquals(XiaomiSensorRow.Grain.BAYER, XiaomiSensorRow.grain(pairs));
        // the colour-block detector votes on the lit tiles only: a confident block 1
        final com.scamera.app.processing.MosaicBlockDetector.Result block =
                com.scamera.app.processing.MosaicBlockDetector.detect(frame, w, h, w * 2, 0f, 1023f, 8);
        assertTrue(block.toString(), block.confident);
        assertEquals(1, block.block);
        // the truth check looks at the buffer itself
        assertEquals(FullResTruth.Verdict.PARTIAL, FullResTruth.check(frame, w, h, w * 2, 64).verdict);
    }

    @Test public void aPictureInAnotherPartOfTheBufferIsPartialButNotTheBinnedFrame() {
        final int w = 1024, h = 768;
        final ByteBuffer full = bayer(w, h, 11);
        // the right quarter of the columns only
        final ByteBuffer strip = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < h; y++) for (int x = w * 3 / 4; x < w; x++) strip.putShort((y * w + x) * 2, full.getShort((y * w + x) * 2));
        final FullResTruth.Result r = FullResTruth.check(strip, w, h, w * 2, 64);
        assertEquals(FullResTruth.Verdict.PARTIAL, r.verdict);
        assertArrayEquals(new int[]{w * 3 / 4, 0, w, h}, r.box);
        assertFalse("not the layout the shot can take the binned frame from", r.packedQuarter());
        assertTrue(r.line(), r.line().contains("valid 256x768+768+0 (25% of the buffer"));
        // an empty rest of one value under the black (not zero) is as empty
        final ByteBuffer below = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < w * h; i++) below.putShort(i * 2, (short) 16);
        for (int y = 0; y < h / 2; y++) for (int x = 0; x < w; x++) below.putShort((y * w + x) * 2, full.getShort((y * w + x) * 2));
        assertEquals(FullResTruth.Verdict.PARTIAL, FullResTruth.check(below, w, h, w * 2, 64).verdict);
        assertEquals("without a declared black only zeros are empty", FullResTruth.Verdict.REAL, FullResTruth.check(below, w, h, w * 2, 0).verdict);
    }

    @Test public void aFullFrameWithoutThePedestalTheCameraDeclares() {
        final ByteBuffer real = bayer(W, H, 12);
        final FullResTruth.Result with = FullResTruth.check(real, W, H, W * 2, 64);
        assertEquals(FullResTruth.Verdict.REAL, with.verdict);
        assertFalse("the documents' real frame: floor 48 .. 53 for a black of 64 - here " + with.floor, with.pedestalLost());
        assertFalse(with.notReal(true));
        // the same picture with its pedestal taken off
        final ByteBuffer lost = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < W * H; i++) lost.putShort(i * 2, (short) Math.max(0, (real.getShort(i * 2) & 0xFFFF) - 64));
        final FullResTruth.Result without = FullResTruth.check(lost, W, H, W * 2, 64);
        assertEquals("its grain is a full frame's", FullResTruth.Verdict.REAL, without.verdict);
        assertTrue(without.pedestalLost());
        assertTrue("not real where the pedestal matters", without.notReal(true));
        assertFalse("kept where it does not", without.notReal(false));
        assertTrue(without.line(), without.line().contains("NO PEDESTAL") && without.line().contains("WITHOUT its pedestal"));
        assertFalse("no black declared: nothing to lose", FullResTruth.check(lost, W, H, W * 2, 0).pedestalLost());
        // a dark frame of a stream without pedestal: black cells here and there, the picture's box still the whole buffer
        final ByteBuffer night = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
        final Random r = new Random(13);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++)
            night.putShort((y * W + x) * 2, (short) (((x / 64 + y / 64) % 3 == 0) ? 0 : Math.max(0, Math.round(6 + 3 * r.nextGaussian()))));
        final FullResTruth.Result dark = FullResTruth.check(night, W, H, W * 2, 0);
        assertFalse(dark.line(), dark.verdict == FullResTruth.Verdict.PARTIAL);
        assertTrue(dark.validShare < 0.8 && dark.boxShare >= 0.9);
    }

    @Test public void aShotFollowsItsFirstFramesVerdictWithThePedestalPolicy() {
        final int w = 1024, h = 768;
        final FullResRecipeShot shot = new FullResRecipeShot("2", "test", 64, true);
        assertFalse(shot.notReal());
        shot.judge(packedQuarter(bayer(w / 2, h / 2, 14), w, h), w, h, w * 2);
        assertTrue(shot.notReal());
        assertFalse(shot.upscaled());
        final FullResRecipeShot real = new FullResRecipeShot("2", "test", 64, true);
        real.judge(bayer(W, H, 15), W, H, W * 2);
        assertFalse(real.notReal());
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

    /** P689: a uint16 frame ({@code w} x {@code h}, packed rows) as MIPI RAW10 rows of {@code stride} bytes. */
    static ByteBuffer raw10(ByteBuffer plain, int w, int h, int stride) {
        final ByteBuffer b = ByteBuffer.allocateDirect(stride * h).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < h; y++) for (int g = 0; g < w / 4; g++) {
            int low = 0;
            for (int k = 0; k < 4; k++) {
                final int v = Math.min(1023, plain.getShort((y * w + 4 * g + k) * 2) & 0xFFFF);
                b.put(y * stride + g * 5 + k, (byte) (v >> 2));
                low |= (v & 3) << (2 * k);
            }
            b.put(y * stride + g * 5 + 4, (byte) low);
        }
        return b;
    }

    /**
     * P689 (the OPPO Find X9 Ultra's 50 MP recipe takes its stills as RAW10): a packed RAW10 frame is judged on the same sites as
     * its uint16 twin - a real frame real, a doubled one doubled, the binned frame in a quarter of the buffer partial - and a
     * doubled or quarter frame comes back as the uint16 binned frame.
     */
    @Test public void raw10FramesAreJudgedOnTheirSites() {
        final int stride = W * 10 / 8;
        final ByteBuffer real = bayer(W, H, 21);
        final FullResTruth.Result plain = FullResTruth.check(real, W, H, W * 2, 64);
        final FullResTruth.Result packed = FullResTruth.check(raw10(real, W, H, stride), W, H, stride, 64, true);
        assertEquals(packed.line(), FullResTruth.Verdict.REAL, packed.verdict);
        assertEquals(plain.line(), packed.line());
        // padded RAW10 rows
        final FullResTruth.Result padded = FullResTruth.check(raw10(real, W, H, stride + 48), W, H, stride + 48, 64, true);
        assertEquals(plain.line(), padded.line());
        assertEquals("a RAW10 stride is no uint16 row", FullResTruth.Verdict.UNKNOWN, FullResTruth.check(raw10(real, W, H, stride), W, H, stride, 64).verdict);
        // the binned frame written twice over
        final ByteBuffer half = bayer(W / 2, H / 2, 22);
        final ByteBuffer twice = raw10(doubled(half, W, H, 0, 0), W, H, stride);
        final FullResTruth.Result up = FullResTruth.check(twice, W, H, stride, 64, true);
        assertEquals(up.line(), FullResTruth.Verdict.UPSCALED, up.verdict);
        final ByteBuffer out = ByteBuffer.allocateDirect(W / 2 * (H / 2) * 2).order(ByteOrder.nativeOrder());
        assertTrue(FullResTruth.halveRaw10(out, twice, 0, W, H, stride, up.phase));
        for (int i = 0; i < W / 2 * (H / 2); i += 5) assertEquals("site " + i, half.getShort(i * 2), out.order(ByteOrder.LITTLE_ENDIAN).getShort(i * 2));
        assertFalse(FullResTruth.halveRaw10(ByteBuffer.allocateDirect(8), twice, 0, W, H, stride, 0));
        // the binned frame at its own RAW10 pitch in the first quarter of the rows
        final int w = 1024, h = 768, quarterStride = w * 10 / 8;
        final ByteBuffer binned = bayer(w / 2, h / 2, 23);
        final ByteBuffer frame = ByteBuffer.allocateDirect(quarterStride * h).order(ByteOrder.LITTLE_ENDIAN);
        final ByteBuffer packedHalf = raw10(binned, w / 2, h / 2, (w / 2) * 10 / 8);
        packedHalf.position(0);
        frame.duplicate().put(packedHalf);
        final FullResTruth.Result quarter = FullResTruth.check(frame, w, h, quarterStride, 64, true);
        assertEquals(quarter.line(), FullResTruth.Verdict.PARTIAL, quarter.verdict);
        assertTrue(quarter.packedQuarter());
        final ByteBuffer back = ByteBuffer.allocateDirect(w / 2 * (h / 2) * 2).order(ByteOrder.nativeOrder());
        assertTrue(FullResTruth.unpackQuarterRaw10(back, frame, w, h, quarterStride));
        for (int i = 0; i < w / 2 * (h / 2); i += 7) assertEquals("site " + i, binned.getShort(i * 2), back.order(ByteOrder.LITTLE_ENDIAN).getShort(i * 2));
        assertFalse("padded rows are another pitch", FullResTruth.unpackQuarterRaw10(back, frame, w, h, quarterStride + 16));
        // a recipe shot judges its RAW10 stills
        final FullResRecipeShot shot = new FullResRecipeShot("2", "OPPO Find X9 Ultra 50 MP (1x)", 64, false);
        assertEquals(FullResTruth.Verdict.REAL, shot.judge(raw10(real, W, H, stride), W, H, stride, true).verdict);
    }
}
