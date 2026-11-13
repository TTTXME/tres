package com.scamera.app.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.scamera.app.capture.XiaomiSensorRow;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * P631: the Xiaomi 17 Ultra's 50 MP row came without its black pedestal (log of 31256: "36864 of 49152 blocks hold samples below
 * 16 DN"); the colour-block detector, measuring over the declared black of 64, had no answer in 12 frames and the viewfinder drew
 * the stream as a plain Bayer. Over the frame's own floor it settles, and the RAW viewfinder bins the stream by what it is.
 */
public class XiaomiRowViewfinderTest {
    private static final int W = 512, H = 384;
    private static final int[] COLOUR = {20, 45, 45, 30}; // a dim room, R G G B over a black of 0

    @Before public void store() {
        MosaicBlockStore.setInstance(new MosaicBlockStore(MosaicBlockStore.memoryBacking()));
        MosaicStream.reset();
    }

    @After public void clear() {
        MosaicStream.reset();
        MosaicBlockStore.setInstance(null);
    }

    /** A dim frame without its pedestal: a Quad mosaic ({@code quad}) or a plain Bayer, seeded noise. */
    private static ByteBuffer dim(boolean quad, long seed) {
        final ByteBuffer b = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
        final Random r = new Random(seed);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            final int cx = quad ? x / 2 : x, cy = quad ? y / 2 : y;
            final int c = (x / 64 + y / 64) % 4 == 0 ? 0 : COLOUR[(cy & 1) * 2 + (cx & 1)]; // some black patches: the floor
            b.putShort((y * W + x) * 2, (short) Math.max(0, Math.round(c + 2.0 * r.nextGaussian())));
        }
        return b;
    }

    private static MosaicBlockDetector.Result detect(ByteBuffer frame, float black) {
        return MosaicBlockDetector.detect(frame, W, H, W * 2, black, 1023f, 32);
    }

    @Test public void theDeclaredBlackLeavesEveryTileOut() {
        final MosaicBlockDetector.Result r = detect(dim(true, 1), 64f);
        assertFalse("the log's «no answer in 12 frames»: " + r, r.confident);
        assertEquals(0, r.tiles);
    }

    @Test public void overTheFloorAQuadSettlesAndTheViewfinderBinsItsCells() {
        MosaicStream.startSession("2|8192x6144||0|pick-11");
        int block = 0;
        for (int i = 0; i < 3; i++) {
            final ByteBuffer f = dim(true, 10 + i);
            final float black = XiaomiSensorRow.detectorBlack(64f, XiaomiSensorRow.frameFloor(f, W, H, W * 2));
            assertTrue("the floor of a stream without pedestal: " + black, black < 8f);
            final MosaicBlockDetector.Result r = detect(f, black);
            assertTrue(r.toString(), r.confident);
            block = MosaicStream.observe(r);
        }
        assertEquals("three agreeing frames: Quad", 2, block);
        assertEquals(2, MosaicStream.block());
        assertEquals("stored for the next session", 2, MosaicBlockStore.get().stored("2|8192x6144||0|pick-11"));
        // the viewfinder's bin of a Quad: one site of each cell - a plain Bayer of half the sides with all four colours
        final ByteBuffer binned = LiveRawFrame.binMosaic(dim(true, 20), W, H, W * 2, 0x20, 2, true).order(ByteOrder.nativeOrder());
        // output sites (32, 0) R, (33, 0) G, (33, 1) B: cells of the frame's lit part (its first 64 x 64 is a black patch)
        final int bw = W / 2;
        final int r = binned.getShort(32 * 2) & 0xFFFF, g = binned.getShort(33 * 2) & 0xFFFF, b = binned.getShort((bw + 33) * 2) & 0xFFFF;
        assertTrue("R " + r, Math.abs(r - COLOUR[0]) < 10);
        assertTrue("G " + g, Math.abs(g - COLOUR[1]) < 10);
        assertTrue("B " + b, Math.abs(b - COLOUR[3]) < 10);
        assertNotEquals("R and G sites differ after the bin", r, g);
        assertNotEquals("G and B sites differ after the bin", g, b);
    }

    @Test public void overTheFloorAPlainBayerSettlesAsTheHalsRemosaic() {
        MosaicStream.startSession("2|8192x6144||0|pick-11");
        int block = 0;
        for (int i = 0; i < 3; i++) {
            final ByteBuffer f = dim(false, 30 + i);
            final MosaicBlockDetector.Result r = detect(f, XiaomiSensorRow.detectorBlack(64f, XiaomiSensorRow.frameFloor(f, W, H, W * 2)));
            assertTrue(r.toString(), r.confident);
            block = MosaicStream.observe(r);
        }
        assertEquals(1, block);
        final int stored = MosaicBlockStore.get().stored("2|8192x6144||0|pick-11");
        assertEquals(1, stored);
        // the shot of a Xiaomi pick then merges the Bayer it is, not the row's declared Quad
        assertEquals(1, MosaicBlockStore.choose(stored, XiaomiSensorRow.shotBlock(2, true, stored)));
        assertEquals("elsewhere the declared Quad still wins, as before", 2, MosaicBlockStore.choose(stored, XiaomiSensorRow.shotBlock(2, false, stored)));
    }

    @Test public void aStreamWithItsPedestalMeasuresAsBefore() {
        final ByteBuffer f = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
        final Random r = new Random(3);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++)
            f.putShort((y * W + x) * 2, (short) Math.round(64 + COLOUR[((y / 2) & 1) * 2 + ((x / 2) & 1)] + 2.0 * r.nextGaussian()));
        assertEquals("the declared black stays", 64f, XiaomiSensorRow.detectorBlack(64f, XiaomiSensorRow.frameFloor(f, W, H, W * 2)), 0f);
        assertTrue(detect(f, 64f).confident);
        assertEquals(2, detect(f, 64f).block);
    }
}
