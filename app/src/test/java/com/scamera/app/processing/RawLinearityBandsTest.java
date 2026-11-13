package com.scamera.app.processing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import org.junit.Test;

/**
 * P625: the DCG frame's histogram (RawLinearity.histogram, 175 ms of a OnePlus 15 shot before its merge on one thread) in bands
 * of rows at once gives the one-thread counts exactly: every frame shape (odd widths, row strides above the width, a buffer
 * that ends inside a row, frames too low for bands), and the same linearity verdict.
 */
public class RawLinearityBandsTest {
    private static ByteBuffer frame(int samples, long seed) {
        final ByteBuffer b = ByteBuffer.allocateDirect(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        final Random r = new Random(seed);
        for (int i = 0; i < samples; i++) b.putShort(i * 2, (short) (r.nextInt(4) == 0 ? r.nextInt(65536) : 1024 + r.nextInt(3000)));
        return b;
    }

    private static void same(ByteBuffer raw, int w, int h, int strideBytes) {
        final int[][] want = RawLinearity.histogramSequential(raw, w, h, strideBytes), got = RawLinearity.histogram(raw, w, h, strideBytes);
        for (int c = 0; c < 4; c++) assertArrayEquals(w + "x" + h + " stride " + strideBytes + " phase " + c, want[c], got[c]);
    }

    @Test public void bandsGiveTheOneThreadCounts() {
        final int[][] shapes = {{1024, 768, 0}, {1023, 769, 0}, {1001, 700, 2048}, {4, 4000, 0}, {640, 63, 0}, {640, 127, 0},
                {2, 2, 0}, {7, 300, 16}};
        long seed = 1;
        for (int[] s : shapes) {
            final int stride = s[2] > 0 ? s[2] / 2 : s[0];
            same(frame(stride * s[1], seed++), s[0], s[1], s[2]);
        }
    }

    @Test public void aBufferEndingInsideARowStopsTheCountWhereTheOneThreadLoopDid() {
        final int w = 900, h = 600;
        final ByteBuffer full = frame(w * h, 9);
        // 450.5 rows of data: the rows from 450 on are not counted
        final ByteBuffer cut = full.duplicate();
        cut.limit(w * 450 * 2 + w);
        same(cut.slice().order(ByteOrder.LITTLE_ENDIAN), w, h, 0);
        long counted = 0;
        for (int[] phase : RawLinearity.histogram(cut.slice(), w, h, 0)) for (int n : phase) counted += n;
        assertEquals(450L * w, counted);
    }

    @Test public void theVerdictIsTheSame() {
        final int w = 1024, h = 768;
        final ByteBuffer raw = frame(w * h, 3);
        final float[] black = {1024, 1024, 1024, 1024};
        assertEquals(RawLinearity.analyze(RawLinearity.histogramSequential(raw, w, h, w * 2), black, 16383f).describe(),
                RawLinearity.analyze(raw, w, h, w * 2, black, 16383f).describe());
    }
}
