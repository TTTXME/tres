package com.scamera.app.processing.heif;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.List;

/**
 * P681: the 10-bit HEIC's P010 tiles come out the same when the encoder hands each tile's array back for a later tile
 * (Heic10Encoder.TileSource.recycle): every byte of a tile is written again, so a reused array carries nothing of its last tile,
 * and only the two bands in flight are ever allocated (a new array per tile was ~150 MB of garbage at 50 MP).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class Heic10TileSourceTest {
    private static Bitmap picture(int w, int h) {
        final Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.RGBA_1010102);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) b.setPixel(x, y, Color.rgb((x * 7 + y) & 255, (y * 5) & 255, (x * y) & 255));
        return b;
    }

    private static List<byte[]> tiles(Bitmap img, TileGrid grid, boolean recycle, int[] allocated) throws Exception {
        final List<byte[]> out = new ArrayList<>();
        try (Heic10Encoder.TileSource source = new Heic10Encoder.TileSource(img, grid, P010.Matrix.BT709)) {
            final long deadline = System.nanoTime() + 60_000_000_000L;
            for (int i = 0; i < grid.count(); i++) {
                final byte[] tile = source.tile(i, deadline);
                out.add(tile.clone()); // what the encoder's input buffer received
                if (recycle) {
                    java.util.Arrays.fill(tile, (byte) 0x5A); // a stale array must not show through
                    source.recycle(tile);
                }
            }
            allocated[0] = source.allocated.get();
        }
        return out;
    }

    @Test public void reusedArraysGiveTheSameTiles() throws Exception {
        final Bitmap img = picture(230, 390); // 4 x 7 tiles of 64, the last column and row partial
        final TileGrid grid = new TileGrid(img.getWidth(), img.getHeight(), 64);
        final int[] fresh = {0}, reused = {0};
        final List<byte[]> a = tiles(img, grid, false, fresh);
        final List<byte[]> b = tiles(img, grid, true, reused);
        assertEquals(grid.count(), a.size());
        for (int i = 0; i < a.size(); i++) assertArrayEquals("tile " + i, a.get(i), b.get(i));
        assertEquals("one array per tile without recycling", grid.count(), fresh[0]);
        assertTrue("two bands of arrays at most: " + reused[0], reused[0] <= 2 * grid.columns);
        img.recycle();
    }
}
