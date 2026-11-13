package com.scamera.app.processing.opengl;

import android.app.Application;
import android.graphics.Bitmap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.nio.ByteBuffer;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The tile-to-bitmap readback must give exactly the bitmap of the old path
 * (full-frame buffer + Bitmap.copyPixelsFromBuffer), for any tile height and
 * a last tile shorter than the others, with stale bytes past each tile.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TileBlitterTest {

    private static byte[] frame(int width, int height, long seed, boolean opaque) {
        byte[] data = new byte[width * height * 4];
        Random random = new Random(seed);
        for (int i = 0; i < data.length; i += 4) {
            int a = opaque ? 255 : random.nextInt(256);
            data[i] = (byte) random.nextInt(a + 1);
            data[i + 1] = (byte) random.nextInt(a + 1);
            data[i + 2] = (byte) random.nextInt(a + 1);
            data[i + 3] = (byte) a;
        }
        return data;
    }

    private static byte[] pixels(Bitmap bitmap) {
        ByteBuffer out = ByteBuffer.allocate(bitmap.getByteCount());
        bitmap.copyPixelsToBuffer(out);
        return out.array();
    }

    private static void check(int width, int height, int tileRows, int stripRows, boolean opaque) {
        byte[] source = frame(width, height, 31L * width + height, opaque);
        Bitmap reference = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        reference.copyPixelsFromBuffer(ByteBuffer.wrap(source));

        Bitmap dst = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        ByteBuffer tile = ByteBuffer.allocateDirect(width * 4 * tileRows);
        Random stale = new Random(5);
        try (GLCoreBlockProcessing.TileBlitter blitter = new GLCoreBlockProcessing.TileBlitter(dst, stripRows)) {
            GLBlockDivider divider = new GLBlockDivider(height, tileRows);
            int[] row = new int[2];
            while (divider.nextBlock(row)) {
                tile.clear();
                byte[] junk = new byte[tile.capacity()];
                stale.nextBytes(junk);
                tile.put(junk);
                tile.clear();
                tile.put(source, row[0] * width * 4, row[1] * width * 4);
                tile.position(17); // the blitter must not depend on the caller's position
                blitter.blit(tile, row[0], row[1]);
                assertEquals(0, tile.position());
                assertEquals(tile.capacity(), tile.limit());
            }
        }
        byte[] expected = pixels(reference);
        assertArrayEquals("reference must hold the source bytes", source, expected);
        assertArrayEquals("w=" + width + " h=" + height + " tile=" + tileRows + " strip=" + stripRows,
                expected, pixels(dst));
    }

    @Test
    public void tilesGiveTheSameBitmapAsTheFullBuffer() {
        check(37, 300, 256, 16, true);   // last tile 44 rows: partial strips
        check(64, 256, 256, 16, true);   // exactly one tile
        check(29, 21, 8, 8, true);       // energy-saving tile size, short last tile
        check(40, 10, 256, 16, true);    // image shorter than one strip
        check(33, 77, 100, 100, true);   // tile size that is not a multiple of the strip
    }

    @Test
    public void rawPremultipliedBytesAreCopiedUnchanged() {
        check(31, 50, 16, 16, false);
    }

    /**
     * P440 (S4): the pixel-pack buffer readback - every tile read back into its rows of one frame buffer
     * (GLCoreBlockProcessing.pboOffset), the buffer copied into the bitmap once - gives the bitmap of the tiles blitted through
     * the strip bitmap, byte for byte, for any tile height, a short last tile, opaque or premultiplied pixels, and whatever the
     * buffer held before. (The GPU side - glReadPixels into a buffer object writes what it writes into client memory - is the
     * GL spec's; on the phone "post_ab 1" with "post_ab_readback 1" compares the two paths' md5.)
     */
    @Test
    public void oneFrameBufferAtTheTileOffsetsGivesTheBlittedBitmap() {
        final int[][] cases = {{37, 300, 256}, {64, 256, 256}, {29, 21, 8}, {40, 10, 256}, {33, 77, 100}, {4096 / 32, 3072 / 32, 8},
                {4096 / 16, 3072 / 16, 256}};
        for (int[] c : cases) {
            for (boolean opaque : new boolean[]{true, false}) {
                final int width = c[0], height = c[1], tileRows = c[2];
                final byte[] source = frame(width, height, 7L * width + height + (opaque ? 1 : 0), opaque);
                // the strip path (GLCoreBlockProcessing.drawBlocksToBitmap's tiles)
                final Bitmap blitted = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                final ByteBuffer tile = ByteBuffer.allocateDirect(width * 4 * tileRows);
                try (GLCoreBlockProcessing.TileBlitter blitter = new GLCoreBlockProcessing.TileBlitter(blitted,
                        tileRows % 16 == 0 ? 16 : tileRows)) {
                    final GLBlockDivider divider = new GLBlockDivider(height, tileRows);
                    final int[] row = new int[2];
                    while (divider.nextBlock(row)) {
                        tile.clear();
                        tile.put(source, row[0] * width * 4, row[1] * width * 4);
                        blitter.blit(tile, row[0], row[1]);
                    }
                }
                // the buffer path: the tiles at their offsets of one buffer (stale bytes before), one copy
                final ByteBuffer pbo = ByteBuffer.allocateDirect(width * height * 4);
                final byte[] junk = new byte[pbo.capacity()];
                new Random(9).nextBytes(junk);
                pbo.put(junk);
                final GLBlockDivider divider = new GLBlockDivider(height, tileRows);
                final int[] row = new int[2];
                while (divider.nextBlock(row)) {
                    pbo.position((int) GLCoreBlockProcessing.pboOffset(row[0], width));
                    pbo.put(source, row[0] * width * 4, row[1] * width * 4);
                }
                pbo.clear();
                final Bitmap direct = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                direct.copyPixelsFromBuffer(pbo);
                assertArrayEquals("w=" + width + " h=" + height + " tile=" + tileRows + " opaque=" + opaque,
                        pixels(blitted), pixels(direct));
                assertArrayEquals(source, pixels(direct));
                assertEquals(blitted.getConfig(), direct.getConfig());
                assertEquals(blitted.hasAlpha(), direct.hasAlpha());
                assertEquals(blitted.isPremultiplied(), direct.isPremultiplied());
            }
        }
    }

    /** P440: the buffer readback only up to 16 MP, never in post_ab's old GL model or its readback A/B; the timing line's form. */
    @Test
    public void theBufferReadbackIsGatedAndTimed() {
        assertTrue(GLCoreBlockProcessing.pboReadbackWanted(4096, 3072));
        assertFalse("above 16 MP", GLCoreBlockProcessing.pboReadbackWanted(8192, 6144));
        assertFalse(GLCoreBlockProcessing.pboReadbackWanted(0, 3072));
        try {
            PostGlMode.setLegacy(true);
            assertFalse("the old GL model", GLCoreBlockProcessing.pboReadbackWanted(4096, 3072));
            PostGlMode.setLegacy(false);
            PostGlMode.setTileReadback(true);
            assertFalse("post_ab_readback's old run", GLCoreBlockProcessing.pboReadbackWanted(4096, 3072));
        } finally {
            PostGlMode.setLegacy(false);
            PostGlMode.setTileReadback(false);
        }
        assertEquals(4L * 4096 * 3071, GLCoreBlockProcessing.pboOffset(3071, 4096));
        final GLCoreBlockProcessing.OutputTiming t = new GLCoreBlockProcessing.OutputTiming(false);
        t.readBack();
        t.copied();
        t.tiles = 12;
        final String line = t.line("pbo", 4096, 3072);
        assertTrue(line, line.startsWith("final output ms: path=pbo 4096x3072 tiles=12 readback="));
        assertTrue(line, line.contains(" copy=") && line.contains(" total=") && !line.contains("pending="));
    }
}
