package com.scamera.app.processing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * P609 (the OnePlus 16 main's 50 MP from its 200 MP row): each 2x2 box inside a Tetra colour cell averaged into one site gives the
 * sensor's own Quad of half the sides in the same colour order, the levels kept. The Java rows here (the native twin,
 * allocator.cpp binBlocks2, has tools/check_tetra_bin.cpp); the native library is not loaded on the host, so TetraBin.bin takes
 * the Java rows.
 */
public class TetraBinTest {
    private static final int[] LEVEL = {300, 520, 610, 180};

    /** Tetra: 4x4 cells in the 2x2 order 0 1 / 2 3, an 8x8 period. */
    private static int tetraColour(int x, int y) { return ((y >> 2) & 1) * 2 + ((x >> 2) & 1); }
    private static int quadColour(int x, int y) { return ((y >> 1) & 1) * 2 + ((x >> 1) & 1); }

    private interface Value { int at(int x, int y); }

    private static ByteBuffer frame(int w, int h, int stride, int offset, Value v) {
        final ByteBuffer b = ByteBuffer.allocateDirect(offset + stride * h).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < b.capacity(); i++) b.put(i, (byte) 0xEE); // padding never read as a sample
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) b.putShort(offset + y * stride + 2 * x, (short) v.at(x, y));
        return b;
    }

    private static int at(ByteBuffer out, int ow, int x, int y) {
        return out.order(ByteOrder.nativeOrder()).getShort((y * ow + x) * 2) & 0xFFFF;
    }

    @Test public void theBinnedSizeKeepsWholeTetraPeriods() {
        assertArrayEquals("the PYB110 main's 200 MP row", new int[]{8160, 6144}, TetraBin.outSize(16320, 12288));
        assertArrayEquals(new int[]{16, 8}, TetraBin.outSize(37, 21));
        assertArrayEquals(new int[]{0, 0}, TetraBin.outSize(7, 7));
        assertEquals(8160L * 6144 * 2, TetraBin.outBytes(16320, 12288));
        assertEquals("a quarter of the 200 MP RAW", 16320L * 12288 * 2 / 4, TetraBin.outBytes(16320, 12288));
    }

    @Test public void tetraCellsBecomeQuadCellsOfTheSameColourOrder() {
        final int w = 64, h = 48;
        final ByteBuffer in = frame(w, h, w * 2, 0, (x, y) -> LEVEL[tetraColour(x, y)]);
        final ByteBuffer out = TetraBin.binned(in, 0, 0x20, w, h, w * 2);
        assertNotNull(out);
        for (int y = 0; y < h / 2; y++)
            for (int x = 0; x < w / 2; x++) assertEquals("site " + x + "," + y, LEVEL[quadColour(x, y)], at(out, w / 2, x, y));
    }

    @Test public void eachSiteIsTheRoundedMeanOfItsBoxAndTheLevelsStay() {
        final int w = 32, h = 16;
        final Value ramp = (x, y) -> (x * 7 + y * 13) % 1024;
        final ByteBuffer out = TetraBin.binned(frame(w, h, w * 2, 0, ramp), 0, 0x20, w, h, w * 2);
        assertNotNull(out);
        for (int y = 0; y < h / 2; y++)
            for (int x = 0; x < w / 2; x++) {
                final int s = ramp.at(2 * x, 2 * y) + ramp.at(2 * x + 1, 2 * y) + ramp.at(2 * x, 2 * y + 1) + ramp.at(2 * x + 1, 2 * y + 1);
                assertEquals((s + 2) >> 2, at(out, w / 2, x, y));
            }
        final ByteBuffer black = TetraBin.binned(frame(16, 16, 32, 0, (x, y) -> 64), 0, 0x20, 16, 16, 32);
        final ByteBuffer white = TetraBin.binned(frame(16, 16, 32, 0, (x, y) -> 1023), 0, 0x20, 16, 16, 32);
        for (int i = 0; i < 64; i++) {
            assertEquals("black 64: the mean, not the sum", 64, at(black, 8, i % 8, i / 8));
            assertEquals("white 1023 stays the white", 1023, at(white, 8, i % 8, i / 8));
        }
    }

    @Test public void oddSizesStridesAndOffsets() {
        final int w = 37, h = 21, stride = 96, offset = 6;
        final ByteBuffer in = frame(w, h, stride, offset, (x, y) -> LEVEL[tetraColour(x, y)]);
        final ByteBuffer out = TetraBin.binned(in, offset, 0x20, w, h, stride);
        assertNotNull(out);
        assertEquals(16 * 8 * 2, out.capacity());
        for (int y = 0; y < 8; y++) for (int x = 0; x < 16; x++) assertEquals(LEVEL[quadColour(x, y)], at(out, 16, x, y));
    }

    @Test public void refusals() {
        final int w = 32, h = 16;
        final ByteBuffer in = frame(w, h, w * 2, 0, (x, y) -> 100);
        assertNull("a stride below one row", TetraBin.binned(in, 0, 0x20, w, h, w * 2 - 2));
        assertNull("a frame below one period", TetraBin.binned(in, 0, 0x20, 4, 4, 8));
        assertNull("a source shorter than its rows", TetraBin.binned(in, 64, 0x20, w, h, w * 2));
        assertFalse("packed RAW10 needs the native unpack", TetraBin.binJava(ByteBuffer.allocateDirect(512), in, 0, 0x25, w, h, 40));
        assertFalse("a destination too small", TetraBin.bin(ByteBuffer.allocateDirect(10), in, 0, 0x20, w, h, w * 2));
        assertTrue(TetraBin.describe(16320, 12288, 80, true).contains("16320x12288 -> 8160x6144"));
        assertTrue(TetraBin.describe(16320, 12288, 80, true).startsWith("50 MP from the 200 MP mode, 2x2 binned per Tetra cell"));
    }

    @Test public void theBinnedFrameCarriesFourSamples() {
        final ByteBuffer b = ByteBuffer.allocateDirect(8160 * 6144 * 2 / 64);
        final ImageFrame f = TetraBin.frame(b, 16320 / 8, 12288 / 8);
        assertEquals(1020, f.width);
        assertEquals(768, f.height);
        assertEquals("the merge's noise model divides by it (ScamHybridBurst.noiseFor)", 4, f.binnedSamples);
        assertTrue(f.buffer == b);
        assertEquals("a plain wrap: as captured", 1, ImageFrame.wrap(b, 2, 2).binnedSamples);
        assertNull(ImageFrame.wrap(null, 4, 4).buffer);
    }
}
