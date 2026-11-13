package com.scamera.app.processing;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P315: the RAW viewfinder's black of a stream that came without its pedestal (OPPO Find X8 Ultra, sensor mode 8: data floor ~0.4
 * DN for a declared 64 - the viewfinder was green and half black). Only non-zero samples far under the declared black prove the
 * pedestal lost; zero padding, read noise around the pedestal and a slight drift never do.
 */
public class PedestalFloorTest {
    private static final int W = 256, H = 192;

    /** A plain RGGB frame: a dark left half (signal ~2 DN), a lit right half, {@code pedestal} added, noise {@code sigma}. */
    private static ByteBuffer frame(int pedestal, double sigma, int zeroRows, long seed) {
        Random random = new Random(seed);
        ByteBuffer b = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.nativeOrder());
        ShortBuffer s = b.asShortBuffer();
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            final boolean green = ((x ^ y) & 1) == 1;
            final double signal = x < W / 2 ? 2 : (green ? 120 : 60);
            long v = Math.round(pedestal + signal + sigma * random.nextGaussian());
            if (y < zeroRows) v = 0;
            s.put(y * W + x, (short) Math.max(0, Math.min(1023, v)));
        }
        return b;
    }

    /** A frame without a pedestal and without dark samples (every site ~100 DN): it proves nothing either way. */
    private static ByteBuffer bright(long seed) {
        Random random = new Random(seed);
        ByteBuffer b = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.nativeOrder());
        ShortBuffer s = b.asShortBuffer();
        for (int i = 0; i < W * H; i++) s.put(i, (short) Math.round(100 + 1.5 * random.nextGaussian()));
        return b;
    }

    private static float measure(ByteBuffer b) {
        return PedestalFloor.measure(b.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer(), W, H, W, 1, 64f);
    }

    @Test
    public void aStreamWithoutItsPedestalIsFound() {
        final float floor = measure(frame(0, 1.5, 0, 1));
        assertFalse(Float.isNaN(floor));
        assertTrue("the floor is the dark blocks' level, not the declared 64: " + floor, floor < 6f);
    }

    @Test
    public void aPedestalPaddingOrNoiseIsNeverTakenForALoss() {
        assertTrue(Float.isNaN(measure(frame(64, 1.5, 0, 2))));
        assertTrue("zero padding rows prove nothing", Float.isNaN(measure(frame(64, 1.5, 10, 3))));
        assertTrue("read noise around the pedestal", Float.isNaN(measure(frame(64, 8, 0, 4))));
        assertTrue("a 64 -> 60 drift", Float.isNaN(measure(frame(60, 1.5, 0, 5))));
    }

    /**
     * P386: a daylight frame without its pedestal (vivo X200 Ultra camera 5, owner's zip 2026-10-28): the left half deep shade - each
     * Bayer cell a dark gap (R 2, G 6, B 4 DN) or a lit twig (R 12, G 30, B 20) - the right half lit; the shot's noise model.
     */
    private static ByteBuffer daylight(long seed) {
        Random random = new Random(seed);
        ByteBuffer b = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.nativeOrder());
        ShortBuffer s = b.asShortBuffer();
        for (int cy = 0; cy < H / 2; cy++) for (int cx = 0; cx < W / 2; cx++) {
            final boolean gap = random.nextBoolean();
            for (int p = 0; p < 4; p++) {
                final int x = 2 * cx + (p & 1), y = 2 * cy + (p >> 1);
                final int colour = p == 0 ? 0 : p == 3 ? 2 : 1;   // RGGB
                final double[] rgb = x >= W / 2 ? new double[]{60, 120, 90} : gap ? new double[]{2, 6, 4} : new double[]{12, 30, 20};
                final double signal = rgb[colour];
                s.put(y * W + x, (short) Math.max(0, Math.round(signal + Math.sqrt(0.133 + 0.0678 * signal) * random.nextGaussian())));
            }
        }
        return b;
    }

    @Test
    public void aDaylightFrameWithoutItsPedestalTakesItsDarkestSamples() {
        // its darkest block means still hold the twigs' light (~4.6 DN of red): taken as the black, the viewfinder subtracted that
        // much too much - whites cool, red short in the shade; the smallest samples bound the black at ~1 DN
        final float floor = measure(daylight(9));
        assertFalse(Float.isNaN(floor));
        assertTrue("the floor is the darkest samples', not the block means' ~4.6: " + floor, floor < 3f);
    }

    @Test
    public void aPartlyLostPedestalKeepsTheBlockFloor() {
        // P386 review: part of the pedestal lost at a high ISO (true black 20, read noise 5 DN): the smallest samples' noise tail
        // reaches ~6 DN, the first fix took it - the viewfinder's shadows lifted magenta. Not near zero, nor held by dark blocks (they
        // sit at ~22): the darkest block means are the floor
        final float floor = measure(frame(20, 5, 0, 10));
        assertFalse(Float.isNaN(floor));
        assertTrue("the block means' floor, not the samples' tail: " + floor, floor > 17f && floor < 23f);
        // a 2048x1536 frame (a 4096x3072 Quad stream's cell means): its noise tail reaches ~1 DN, in blocks that sit at ~22
        final int w = 2048, h = 1536;
        final Random random = new Random(12);
        final ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder());
        final ShortBuffer s = b.asShortBuffer();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            final double signal = (x / 128 + y / 128) % 3 == 0 ? (((x ^ y) & 1) == 1 ? 120 : 60) : 2;
            s.put(y * w + x, (short) Math.max(0, Math.round(20 + signal + 5 * random.nextGaussian())));
        }
        final float big = PedestalFloor.measure(b.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer(), w, h, w, 1, 64f, 1023f);
        assertTrue("full frame: " + big, big > 17f && big < 23f);
    }

    @Test
    public void aSwitchedViewfinderGivesItsStreamTheFloor() throws Exception {
        // P386: the photo of the session's stream without deep shade takes the floor the viewfinder proved (PedestalMemory)
        MosaicBlockStore.setInstance(new MosaicBlockStore(MosaicBlockStore.memoryBacking()));
        final String key = "5|4096x3072|vf-seed|5";
        try {
            MosaicStream.startSession(key);
            PedestalFloor p = new PedestalFloor();
            final float[] declared = {64, 64, 64, 64};
            final ByteBuffer lost = daylight(11);
            p.black(1, lost, W, H, W * 2, 1, declared, 1023f);
            assertTrue("one measurement gives nothing", Float.isNaN(PedestalMemory.floor(key, 1023f)));
            Thread.sleep(PedestalFloor.INTERVAL_NS / 1_000_000L + 20);
            final float[] black = p.black(1, lost, W, H, W * 2, 1, declared, 1023f);
            assertEquals("the viewfinder's floor is the stream's", black[0], PedestalMemory.floor(key, 1023f), 0f);
            assertTrue(black[0] < 3f);
            assertEquals("viewfinder", PedestalMemory.source(key));
        } finally {
            PedestalMemory.clear(key, "test");
            MosaicStream.reset();
            MosaicBlockStore.setInstance(null);
        }
    }

    @Test
    public void theViewfinderTakesTheFloorAfterTwoMeasurementsOfTheSession() throws Exception {
        PedestalFloor p = new PedestalFloor();
        final float[] declared = {64, 64, 64, 64};
        final ByteBuffer lost = frame(0, 1.5, 0, 6);
        assertEquals("one measurement is not enough", 64f, p.black(1, lost, W, H, W * 2, 1, declared)[0], 0f);
        Thread.sleep(PedestalFloor.INTERVAL_NS / 1_000_000L + 20);
        final float[] black = p.black(1, lost, W, H, W * 2, 1, declared);
        assertTrue("the second agreeing measurement switches: " + black[0], black[0] < 6f);
        // a bright frame of the same session proves nothing and keeps the floor
        Thread.sleep(PedestalFloor.INTERVAL_NS / 1_000_000L + 20);
        assertTrue(Float.isNaN(measure(bright(7))));
        assertTrue(p.black(1, bright(7), W, H, W * 2, 1, declared)[0] < 6f);
        // a new camera session starts from the declared black again
        assertEquals(64f, p.black(2, frame(64, 1.5, 0, 8), W, H, W * 2, 1, declared)[0], 0f);
        assertEquals("the declared array is never changed", 64f, declared[0], 0f);
    }

    /**
     * P557 (OPPO Find X8 Ultra ISZ, owner 2026-11-08: «видоискатель всё равно зелёный»): the pedestal kept but drifted 64 -> 60.6:
     * after two agreeing measurements the viewfinder subtracts the frames' floor as the photo does; a black that did not move and
     * read noise of a high-ISO frame around it never lower it.
     */
    @Test
    public void aDriftedBlackIsFollowedAndNoiseIsNot() throws Exception {
        final float[] declared = {64, 64, 64, 64};
        PedestalFloor p = new PedestalFloor();
        final ByteBuffer drifted = frame(60, 0.8, 0, 11);
        assertEquals("one measurement is not enough", 64f, p.black(1, drifted, W, H, W * 2, 1, declared, 1023f)[0], 0f);
        Thread.sleep(PedestalFloor.INTERVAL_NS / 1_000_000L + 20);
        final float b = p.black(1, drifted, W, H, W * 2, 1, declared, 1023f)[0];
        assertTrue("the drifted floor (~62 = 60 + the dark half's 2 DN), not 64: " + b, b > 60f && b < 63f);
        PedestalFloor q = new PedestalFloor();
        for (int i = 0; i < 4; i++) {
            final float k = q.black(2, frame(64, 4.0, 0, 20 + i), W, H, W * 2, 1, declared, 1023f)[0];
            assertEquals("read noise of 4 DN around a black of 64 keeps it", 64f, k, 0f);
            Thread.sleep(PedestalFloor.INTERVAL_NS / 1_000_000L + 20);
        }
    }

    /**
     * P669 (owner, 2026-11-13, vivo X200 Pro camera 2: «видоискатель становится розовым после снимка пока ты не сменишь модули
     * туда-сюда»): a 4096 x 3072 RAW with its picture in 4000 x 3000 and zeros to the right (96) and below (72), {@code pedestal}
     * plus ~2 DN of signal in its right half (next to the padding), noise 1.5 DN.
     */
    private static ByteBuffer padded(int pedestal, long seed) {
        final int w = 4096, h = 3072;
        Random random = new Random(seed);
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder());
        ShortBuffer s = b.asShortBuffer();
        final short[] row = new short[w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final boolean green = ((x ^ y) & 1) == 1;
                final double signal = x >= w / 2 ? 2 : (green ? 120 : 60);   // the dark half next to the padding
                final long v = Math.round(pedestal + signal + 1.5 * random.nextGaussian());
                row[x] = x >= 4000 || y >= 3000 ? 0 : (short) Math.max(0, Math.min(1023, v));
            }
            s.position(y * w);
            s.put(row);
        }
        b.clear();
        return b;
    }

    /** P616: the frame the viewfinder draws while a shot is processed - one 2 x 2 quad of each 4 x 4, 2048 x 1536. */
    private static ByteBuffer busyHalf(ByteBuffer full) {
        return LiveRawFrame.halveBayer(full, 4096, 3072, 4096 * 2,
                ByteBuffer.allocateDirect(2048 * 1536 * 2).order(ByteOrder.nativeOrder()));
    }

    private static float drift(ByteBuffer b, int w, int h) {
        final float[] low = new float[1];
        PedestalFloor.measure(b.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer(), w, h, w, 1, 64f, 1023f, low);
        return low[0];
    }

    /**
     * P669: the after-shot sequence of one camera session - whole frames, halved frames while the shot is processed (P616), whole
     * frames again. The halved frame's blocks at the picture's right edge hold half zeros (mean 32 + 3 standard errors 24 = 56: the
     * log's «black 63.927002 -> 56.22714»), and the session's lowest drift stayed until a module switch: R x 2.46 and B x 1.79 of
     * ~8 DN of the pedestal - a magenta viewfinder. The padding's zeros are no drifted black: the declared 64 is kept throughout.
     */
    @Test
    public void theBusyHalfOfAPaddedFrameKeepsTheBlack() throws Exception {
        final ByteBuffer full = padded(64, 31), half = busyHalf(full);
        assertTrue("the whole frame's drift floor: " + drift(full, 4096, 3072), drift(full, 4096, 3072) > 63f);
        assertTrue("the halved frame's drift floor is the picture's, not 56: " + drift(half, 2048, 1536),
                drift(half, 2048, 1536) > 63f);
        final float[] declared = {64, 64, 64, 64};
        PedestalFloor p = new PedestalFloor();
        final ByteBuffer[] frames = {full, full, half, half, half, full, full};
        for (int i = 0; i < frames.length; i++) {
            final boolean whole = frames[i] == full;
            final float[] black = p.black(1, frames[i], whole ? 4096 : 2048, whole ? 3072 : 1536, (whole ? 4096 : 2048) * 2, 1,
                    declared, 1023f);
            for (int c = 0; c < 4; c++) assertEquals("frame " + i + " channel " + c + " keeps the declared black", 64f, black[c], 0f);
            Thread.sleep(PedestalFloor.INTERVAL_NS / 1_000_000L + 20);
        }
    }

    /** P669 with P557: a drifted black of the same padded stream is still followed on the halved frames - at the picture's level. */
    @Test
    public void aDriftedBlackOfAPaddedHalfIsFollowedAtThePicturesLevel() throws Exception {
        final ByteBuffer half = busyHalf(padded(60, 41));
        final float[] declared = {64, 64, 64, 64};
        PedestalFloor p = new PedestalFloor();
        assertEquals("one measurement is not enough", 64f, p.black(1, half, 2048, 1536, 4096, 1, declared, 1023f)[0], 0f);
        Thread.sleep(PedestalFloor.INTERVAL_NS / 1_000_000L + 20);
        final float b = p.black(1, half, 2048, 1536, 4096, 1, declared, 1023f)[0];
        assertTrue("the drifted floor (~62 = 60 + the dark half's 2 DN), not the padding's edge (~54): " + b, b > 60f && b < 63f);
    }
}
