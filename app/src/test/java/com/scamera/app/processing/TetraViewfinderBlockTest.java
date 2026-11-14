package com.scamera.app.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * P695 host check (Xiaomi 15 Ultra, the periscope's 4x ISZ row 6, owner's screenshots of 2026-11-14): a Tetra (4x4) frame drawn by
 * the viewfinder's block-2 path - P314's cell means of the 2x2 cells, then one colour per 2x2 of them (rawdevelop_fs getFastColor,
 * cfa_mode 0: R the top left, G the mean of the two others, B the bottom right; the drawing is modelled here, the shader does not run
 * on the host). Each 2x2 of cells is then one 4x4 colour block of the sensor: R = G = B, grey - and the white balance's gains (the
 * log's R 1.84 / B 1.91) make it the uniform magenta of the screenshot b67def48 and of the 07:10 photo. The picture's shapes stay
 * where they are (the scene's rows differ), so this path does not make the stripes of cb495164. With block 4 the colours come back.
 */
public class TetraViewfinderBlockTest {
    private static final int W = 64, H = 64;
    /** R, G, B of the scene's grey card under a warm lamp (DN over black), RGGB. */
    private static final int R = 300, G = 560, B = 290;

    /** A Tetra RGGB frame of a scene whose brightness grows down the frame (a factor per 4x4 block row). */
    private static ByteBuffer tetra() {
        final ByteBuffer b = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.nativeOrder());
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            final int c = ((y / 4) & 1) << 1 | ((x / 4) & 1);
            final int base = c == 0 ? R : c == 3 ? B : G;
            final float shade = 0.5f + 0.5f * (y / 8) / (H / 8f);
            b.putShort((y * W + x) * 2, (short) Math.round(base * shade));
        }
        return b;
    }

    /** rawdevelop_fs's colour of the 2x2 of sites at (2i, 2j) of a plain RGGB frame w wide: {R, G, B}. */
    private static float[] fastColor(ByteBuffer f, int w, int i, int j) {
        final int x = 2 * i, y = 2 * j;
        final float r = f.getShort((y * w + x) * 2) & 0xFFFF, g1 = f.getShort((y * w + x + 1) * 2) & 0xFFFF;
        final float g2 = f.getShort(((y + 1) * w + x) * 2) & 0xFFFF, b = f.getShort(((y + 1) * w + x + 1) * 2) & 0xFFFF;
        return new float[]{r, 0.5f * (g1 + g2), b};
    }

    @Test public void aTetraFrameAsQuadIsGreyEverywhere() {
        final ByteBuffer cells = LiveRawFrame.binMosaic(tetra(), W, H, W * 2, 0x20, 2).order(ByteOrder.nativeOrder());
        final int cw = W / 2, ch = H / 2;
        assertEquals(cw * ch * 2, cells.remaining());
        for (int j = 0; j < ch / 2; j++) for (int i = 0; i < cw / 2; i++) {
            final float[] c = fastColor(cells, cw, i, j);
            assertEquals("every 2x2 of cells is one sensor colour: R = G", c[0], c[1], 1f);
            assertEquals("and G = B", c[1], c[2], 1f);
        }
        // the white balance of the HAL's gains (R 1.84, B 1.91 over G) on that grey: magenta, the same in every pixel of a flat card
        final float[] c = fastColor(cells, cw, 3, 3);
        assertTrue(c[0] * 1.84f > 1.5f * c[1] && c[2] * 1.91f > 1.5f * c[1]);
        // the scene's shading down the frame is still there: rows differ, nothing repeats a line
        assertNotEquals(fastColor(cells, cw, 3, 0)[1], fastColor(cells, cw, 3, ch / 2 - 1)[1], 1f);
    }

    @Test public void theTetraBlockGivesTheColoursBack() {
        final ByteBuffer cells = LiveRawFrame.binMosaic(tetra(), W, H, W * 2, 0x20, 4).order(ByteOrder.nativeOrder());
        final int cw = W / 4;
        final float[] c = fastColor(cells, cw, 0, 0);
        final float shade = 0.5f;
        assertEquals(R * shade, c[0], 1f);
        assertEquals(G * shade, c[1], 1f);
        assertEquals(B * shade, c[2], 1f);
    }

    /** The detector: a Tetra frame never votes for a Quad (every 2x2 class of the Quad model holds two colours of a Tetra). */
    @Test public void theDetectorTellsTetraFromQuad() {
        final ByteBuffer big = ByteBuffer.allocateDirect(512 * 512 * 2).order(ByteOrder.LITTLE_ENDIAN);
        final java.util.Random rnd = new java.util.Random(695);
        for (int y = 0; y < 512; y++) for (int x = 0; x < 512; x++) {
            final int c = ((y / 4) & 1) << 1 | ((x / 4) & 1);
            final int base = 64 + (c == 0 ? R : c == 3 ? B : G) / 4;
            big.putShort((y * 512 + x) * 2, (short) (base + rnd.nextInt(5) - 2));
        }
        final MosaicBlockDetector.Result r = MosaicBlockDetector.detect(big, 512, 512, 1024, 64f, 1023f, 8);
        assertEquals(4, r.block);
        assertTrue(r.confident);
        assertEquals("no tile votes for the Quad", 0, r.votes[1]);
        assertTrue(MosaicStream.decisive(r));
    }
}
