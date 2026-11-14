package com.scamera.app.processing.opengl.postpipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Random;

/**
 * P671: the clip band's tile map (ClipBandTiles). The bit-for-bit equality of the passes with and without the map is
 * tools/check_band_tiles.py (moderngl); this pins the map itself against a brute-force search: every pixel that has a flagged
 * pixel (bits 0 - 3) within {@code reach} px on each axis lies in a tile of the map (the passes may only skip pixels without one),
 * the parallel walk (as ScamRgb's helper does it, word by word, parts ending anywhere) gives the same map as one walk, and a frame
 * whose flags hold only bits 4 - 7, or nothing, has no map at all.
 */
public class ClipBandTilesTest {
    private static ClipBandTiles walk(byte[] flags, int width, int height, int parts) {
        final ClipBandTiles t = ClipBandTiles.create(width, height, flags.length, parts);
        assertNotNull(t);
        final ByteBuffer f = ByteBuffer.wrap(flags);
        final int n = flags.length, step = ((n + parts - 1) / parts + 7) & ~7;
        for (int k = 0; k < parts; k++) {
            final int s = (int) Math.min(n, (long) k * step), e = (int) Math.min(n, (long) s + step);
            t.scan(f, s, e, k);
        }
        return t;
    }

    private static void requireCovers(byte[] flags, int width, int height, int reach, byte[] map, int tilesX) {
        // per pixel: is there a flagged pixel within reach on both axes (the windows' taps, clamped to the frame, lie there)?
        final int[] colNear = new int[width * height]; // flagged within reach along the row
        for (int y = 0; y < height; y++) {
            int last = Integer.MIN_VALUE / 2;
            for (int x = 0; x < width; x++) {
                if ((flags[y * width + x] & 15) != 0) last = x;
                colNear[y * width + x] = x - last <= reach ? 1 : 0;
            }
            last = Integer.MAX_VALUE / 2;
            for (int x = width - 1; x >= 0; x--) {
                if ((flags[y * width + x] & 15) != 0) last = x;
                if (last - x <= reach) colNear[y * width + x] = 1;
            }
        }
        int needed = 0;
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                boolean near = false;
                for (int dy = -reach; dy <= reach && !near; dy++) {
                    final int yy = y + dy;
                    if (yy >= 0 && yy < height) near = colNear[yy * width + x] != 0;
                }
                if (!near) continue;
                needed++;
                assertTrue("pixel " + x + "," + y + " has a flagged pixel within " + reach + " px but its tile is off",
                        map != null && map[(y / ClipBandTiles.TILE) * tilesX + x / ClipBandTiles.TILE] != 0);
            }
        if (map == null) assertEquals(0, needed);
    }

    @Test
    public void everyPixelNearAFlagIsInTheMap() {
        final Random rnd = new Random(671);
        final int[][] sizes = {{203, 141}, {320, 96}, {64, 64}, {97, 33}, {1000, 7}};
        for (int[] wh : sizes) {
            final int w = wh[0], h = wh[1];
            for (int trial = 0; trial < 6; trial++) {
                final byte[] flags = new byte[w * h];
                final int points = 1 + rnd.nextInt(12);
                for (int i = 0; i < points; i++) flags[rnd.nextInt(flags.length)] = (byte) (1 + rnd.nextInt(255));
                if (trial == 1) flags[0] = 8;                    // the corners and a tile corner
                if (trial == 2) flags[flags.length - 1] = 1;
                if (trial == 3 && w > 33 && h > 33) flags[32 * w + 32] = 4;
                for (int reach : new int[]{0, 3, 6, 12, 40}) {
                    final ClipBandTiles one = walk(flags, w, h, 1);
                    final byte[] map = one.activeMap(reach);
                    requireCovers(flags, w, h, reach, map, one.tilesX);
                    for (int parts : new int[]{3, 7, 16}) {
                        final byte[] split = walk(flags, w, h, parts).activeMap(reach);
                        assertEquals(map == null, split == null);
                        if (map != null) assertTrue("parts " + parts, java.util.Arrays.equals(map, split));
                    }
                }
            }
        }
    }

    @Test
    public void highBitsAloneOrNothingMakeNoMap() {
        final int w = 131, h = 77;
        final byte[] flags = new byte[w * h];
        assertNull(walk(flags, w, h, 4).activeMap(12));
        for (int i = 0; i < flags.length; i += 5) flags[i] = (byte) (16 << (i % 4)); // bits 4 - 7 only
        assertNull(walk(flags, w, h, 4).activeMap(12));
        flags[w * 40 + 70] |= 2;
        final ClipBandTiles t = walk(flags, w, h, 4);
        final byte[] map = t.activeMap(6);
        assertNotNull(map);
        int on = 0;
        for (byte b : map) if (b != 0) on++;
        assertEquals(9, on); // the flagged tile (2, 1) and its eight neighbours (the reach widens by one whole tile)
        assertTrue(ClipBandTiles.share(map) > 0f);
    }

    @Test
    public void refusesMismatchedSizes() {
        assertNull(ClipBandTiles.create(100, 10, 999, 4));
        assertNull(ClipBandTiles.create(0, 10, 0, 4));
        assertNull(ClipBandTiles.create(100, 10, 1000, 0));
        assertNotNull(ClipBandTiles.create(100, 10, 1000, 4));
    }
}
