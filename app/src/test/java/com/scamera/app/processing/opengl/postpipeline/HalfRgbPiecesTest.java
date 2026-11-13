package com.scamera.app.processing.opengl.postpipeline;

import org.junit.Test;

import static org.junit.Assert.*;

/** P662 (item 7): the half RGB band's pieces for the four copy threads cover every byte once, inner cuts 16-byte aligned. */
public class HalfRgbPiecesTest {
    @Test public void piecesCoverTheBandOnceWithAlignedCuts() {
        final int[] bands = {6, 16, 100, 1 << 20, (1 << 20) + 6, 16 << 20, 16 * 1024 * 1024 - 6 * 4080, 3 * 8192 * 6 * 341};
        for (int bytes : bands) {
            for (int threads = 1; threads <= 8; threads++) {
                final int[] cuts = HalfRgbUpload.pieces(bytes, threads, HalfRgbUpload.PIECE_BYTES);
                assertEquals(0, cuts[0]);
                assertEquals(bytes, cuts[cuts.length - 1]);
                assertTrue(cuts.length - 1 <= threads);
                for (int i = 1; i < cuts.length; i++) {
                    assertTrue(cuts[i] > cuts[i - 1]);
                    if (i < cuts.length - 1) assertEquals(0, cuts[i] % 16);
                }
                if (bytes < 2 * HalfRgbUpload.PIECE_BYTES) assertEquals("a small band in one piece", 2, cuts.length);
            }
        }
        assertEquals("a 16 MB band on four threads", 5, HalfRgbUpload.pieces(16 << 20, 4, HalfRgbUpload.PIECE_BYTES).length);
    }

    @Test public void copyingThePiecesGivesTheSameBytes() {
        final int bytes = 3 * (1 << 20) + 22;
        final java.nio.ByteBuffer src = java.nio.ByteBuffer.allocateDirect(bytes + 10);
        for (int i = 0; i < bytes + 10; i++) src.put(i, (byte) (i * 31 + 7));
        src.position(10);
        final java.nio.ByteBuffer to = java.nio.ByteBuffer.allocateDirect(bytes);
        HalfRgbUpload.copyPieces(src, to, HalfRgbUpload.pieces(bytes, 4, HalfRgbUpload.PIECE_BYTES),
                new com.scamera.app.util.ParallelWork.Own("half-copy-test", 4));
        assertEquals("the source's position is left", 10, src.position());
        for (int i = 0; i < bytes; i++) assertEquals(src.get(10 + i), to.get(i));
    }
}
