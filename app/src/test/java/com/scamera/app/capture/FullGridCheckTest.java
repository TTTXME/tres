package com.scamera.app.capture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * P699 (X300 Ultra 200 MP: the HAL's remosaicked Bayer with seamlessMode 1, not the full-size mode's 768): FullGridCheck tells a
 * still with detail at its own grid from a 50 MP readout blown up - an interpolated upscale is logged as smooth, a still whose 2x2
 * cells repeat one value per colour is a replicated 50 MP readout - for every Bayer phase.
 */
public class FullGridCheckTest {
    static final int N = 512;

    /** R, G, B response of the synthetic scene (no white balance). */
    private static final double[] COLOUR = {0.45, 1.0, 0.7};

    /** The Bayer colour (0 R, 1 G, 2 B) of site (x, y) for CFA {@code cfa} (0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR). */
    static int colour(int cfa, int x, int y) {
        final int[][] c = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
        return c[cfa][((y & 1) << 1) | (x & 1)];
    }

    static ByteBuffer sites(double[][][] rgb, int cfa) {
        final ByteBuffer b = ByteBuffer.allocateDirect(N * N * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < N; y++)
            for (int x = 0; x < N; x++)
                b.putShort((y * N + x) * 2, (short) Math.max(0, Math.min(1023, Math.round(64 + rgb[y][x][colour(cfa, x, y)]))));
        return b;
    }

    /** A textured scene at the full grid with per-site noise: a real full-size readout. */
    static ByteBuffer full(int cfa, long seed) {
        final Random r = new Random(seed);
        final double[][][] rgb = new double[N][N][3];
        for (int y = 0; y < N; y++)
            for (int x = 0; x < N; x++) {
                final double scene = 300 + 150 * Math.sin(x * 0.05) * Math.cos(y * 0.04);
                for (int c = 0; c < 3; c++) rgb[y][x][c] = scene * COLOUR[c] + r.nextGaussian() * 6;
            }
        return sites(rgb, cfa);
    }

    /** A half-size noisy scene (the 50 MP readout). */
    private static double[][][] half(long seed) {
        final Random r = new Random(seed);
        final int h = N / 2;
        final double[][][] s = new double[h][h][3];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < h; x++) {
                final double scene = 300 + 150 * Math.sin(x * 0.1) * Math.cos(y * 0.08);
                for (int c = 0; c < 3; c++) s[y][x][c] = scene * COLOUR[c] + r.nextGaussian() * 6;
            }
        return s;
    }

    /** The half-size scene blown up 2x by bilinear interpolation, then remosaicked to Bayer. */
    static ByteBuffer bilinear(int cfa, long seed) {
        final double[][][] s = half(seed);
        final int h = N / 2;
        final double[][][] rgb = new double[N][N][3];
        for (int y = 0; y < N; y++)
            for (int x = 0; x < N; x++) {
                final double u = Math.max(0, Math.min(h - 1, (x + 0.5) / 2 - 0.5)), v = Math.max(0, Math.min(h - 1, (y + 0.5) / 2 - 0.5));
                final int u0 = (int) Math.floor(u), v0 = (int) Math.floor(v), u1 = Math.min(h - 1, u0 + 1), v1 = Math.min(h - 1, v0 + 1);
                final double fu = u - u0, fv = v - v0;
                for (int c = 0; c < 3; c++)
                    rgb[y][x][c] = (s[v0][u0][c] * (1 - fu) + s[v0][u1][c] * fu) * (1 - fv) + (s[v1][u0][c] * (1 - fu) + s[v1][u1][c] * fu) * fv;
            }
        return sites(rgb, cfa);
    }

    /** The half-size scene with every pixel repeated over a 2x2 cell, then remosaicked to Bayer. */
    static ByteBuffer replicated(int cfa, long seed) {
        final double[][][] s = half(seed);
        final double[][][] rgb = new double[N][N][3];
        for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) rgb[y][x] = s[y / 2][x / 2];
        return sites(rgb, cfa);
    }

    private static FullGridCheck.Result measure(ByteBuffer b) {
        return FullGridCheck.measure(b, N, N, N * 2, 64, 1023, 2);
    }

    @Test public void aFullSizeReadoutHasDetailAtItsOwnGrid() {
        for (int cfa = 0; cfa < 4; cfa++) {
            final FullGridCheck.Result r = measure(full(cfa, cfa));
            assertFalse("CFA " + cfa + ": " + r, r.replicated());
            assertFalse("CFA " + cfa + ": " + r, r.smoothGrid());
            assertTrue("CFA " + cfa + ": " + r, r.cell > 0.8 && r.words().contains("full-size"));
        }
    }

    @Test public void anInterpolatedUpscaleIsSmoothButNotReplicated() {
        for (int cfa = 0; cfa < 4; cfa++) {
            final FullGridCheck.Result r = measure(bilinear(cfa, 10 + cfa));
            assertTrue("CFA " + cfa + ": " + r, r.smoothGrid());
            assertFalse("logged only: " + r, r.replicated());
        }
    }

    @Test public void aReplicatedFiftyMegapixelReadoutIsFound() {
        for (int cfa = 0; cfa < 4; cfa++) {
            final FullGridCheck.Result r = measure(replicated(cfa, 20 + cfa));
            assertTrue("CFA " + cfa + ": " + r, r.replicated());
            assertTrue(r.words(), r.words().contains("replicated"));
        }
    }

    @Test public void aFlatOrClippedFrameSaysNothing() {
        final ByteBuffer clipped = ByteBuffer.allocateDirect(N * N * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < N * N; i++) clipped.putShort(i * 2, (short) 1023);
        final FullGridCheck.Result r = measure(clipped);
        assertFalse(r.toString(), r.replicated() || r.smoothGrid());
        assertTrue(r.words(), r.words().contains("too few"));
        assertFalse(FullGridCheck.measure(null, N, N, N * 2, 64, 1023, 2).replicated());
        assertFalse("a buffer shorter than its size", FullGridCheck.measure(ByteBuffer.allocateDirect(100), N, N, N * 2, 64, 1023, 2).replicated());
    }
}
