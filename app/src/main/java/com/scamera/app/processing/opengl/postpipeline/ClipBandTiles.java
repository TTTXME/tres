package com.scamera.app.processing.opengl.postpipeline;

import java.nio.ByteBuffer;

/**
 * P671 (speed, owner 2026-11-13: «оптимизируй под старые GPU», Xiaomi 12X / Adreno 650: the highlight part of ScamRgb 330 - 615 ms
 * of GPU even on a shot without a clipped pixel). The clip band (scamhdr/clipmask + scamhdr/clipband) changes a pixel only when a
 * flagged pixel (worker clip flag bits 0 - 3) lies within its windows: the zone, the reach and the glow taps reach at most
 * {@code max(radius, zone, glow radius)} pixels on each axis (clamped taps are nearer still). Everywhere else clipband writes the
 * pixel it read and clipmask writes 0.
 * <p>
 * This marks the {@link #TILE} x {@link #TILE} tiles that hold a flagged pixel (from the flags the helper thread already walks for
 * «Inpaint opposed»), and {@link #activeMap} widens them by the windows' reach in whole tiles. A pixel of a tile outside the map
 * has no flagged pixel within the reach, so the passes there return at once with the very value they computed before (BAND_TILES
 * in both shaders): the same pixels bit for bit, the windows' 30 - 40 flag fetches a pixel and clipband's taps only near clipped
 * areas. A shot with no flagged pixel at all skips both passes (the scamrgb output is already what clipband would copy).
 * Marking is conservative: an 8-byte word with a flag marks the tiles of its first and its last byte (a word may straddle a tile
 * or a row), so a tile is never missed, at most one more is computed.
 */
final class ClipBandTiles {
    /** Tile side, px (a power of two). */
    static final int TILE = 32;
    final int width, height, tilesX, tilesY;
    private final long[][] parts;

    private ClipBandTiles(int width, int height, int parts) {
        this.width = width;
        this.height = height;
        tilesX = (width + TILE - 1) / TILE;
        tilesY = (height + TILE - 1) / TILE;
        this.parts = new long[parts][(tilesX * tilesY + 63) / 64];
    }

    /** A map for a frame of {@code width} x {@code height} flags in {@code parts} parallel parts, or null when it does not fit. */
    static ClipBandTiles create(int width, int height, long bytes, int parts) {
        if (width <= 0 || height <= 0 || (long) width * height != bytes || parts <= 0 || parts > 128) return null;
        final long tiles = (((long) width + TILE - 1) / TILE) * (((long) height + TILE - 1) / TILE);
        if (tiles * parts / 8 > 4L * 1024 * 1024) return null;
        try {
            return new ClipBandTiles(width, height, parts);
        } catch (OutOfMemoryError ignored) {
            return null;
        }
    }

    /** Marks the tile of flag byte {@code index} in part {@code part}'s bits. */
    void mark(int part, long index) {
        final int y = (int) (index / width), x = (int) (index - (long) y * width);
        final int tile = (y / TILE) * tilesX + x / TILE;
        parts[part][tile >>> 6] |= 1L << (tile & 63);
    }

    /** The flagged tiles of bytes [start, end) of {@code flags} into part {@code part} (any of flag bits 0 - 3). */
    void scan(ByteBuffer flags, int start, int end, int part) {
        int i = start;
        for (; i + 8 <= end; i += 8) {
            final long value = flags.getLong(i);
            if ((((value & 0x0F0F0F0F0F0F0F0FL) + 0x7F7F7F7F7F7F7F7FL) & 0x8080808080808080L) != 0) {
                mark(part, i);
                mark(part, i + 7);
            }
        }
        for (; i < end; i++) if ((flags.get(i) & 15) != 0) mark(part, i);
    }

    /** The flagged tiles (parts combined), one bit each, row by row. */
    long[] flagged() {
        final long[] all = new long[parts[0].length];
        for (long[] p : parts) for (int i = 0; i < all.length; i++) all[i] |= p[i];
        return all;
    }

    /**
     * The tiles whose pixels may have a flagged pixel within {@code reach} px (the flagged tiles widened by whole tiles), one byte
     * each (255 = draw, 0 = the pixel stays), row by row; null when no tile is flagged at all.
     */
    byte[] activeMap(int reach) {
        final long[] flagged = flagged();
        final int grow = Math.max(1, (Math.max(0, reach) + TILE - 1) / TILE);
        final byte[] map = new byte[tilesX * tilesY];
        boolean any = false;
        for (int w = 0; w < flagged.length; w++) {
            for (long bits = flagged[w]; bits != 0; bits &= bits - 1) {
                final int tile = (w << 6) + Long.numberOfTrailingZeros(bits), ty = tile / tilesX, tx = tile - ty * tilesX;
                any = true;
                for (int y = Math.max(0, ty - grow); y <= Math.min(tilesY - 1, ty + grow); y++)
                    for (int x = Math.max(0, tx - grow); x <= Math.min(tilesX - 1, tx + grow); x++) map[y * tilesX + x] = (byte) 255;
            }
        }
        return any ? map : null;
    }

    /** The share of tiles set in {@code map}, for the log. */
    static float share(byte[] map) {
        if (map == null || map.length == 0) return 0f;
        int n = 0;
        for (byte b : map) if (b != 0) n++;
        return (float) n / map.length;
    }
}
