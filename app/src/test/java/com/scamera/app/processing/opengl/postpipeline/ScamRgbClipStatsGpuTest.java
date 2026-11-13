package com.scamera.app.processing.opengl.postpipeline;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * P482: hlrecovery/clipstats_cs's arithmetic (the CPU's bin table, per-bin sums as integers of 2^-11, maxima) gives exactly the
 * levels of ScamRgb.channelClip's CPU walk - simulated here sample by sample as the shader does it.
 */
public class ScamRgbClipStatsGpuTest {
    /** The halves whose value lies in [lo, hi) (positive ones). */
    private static int[] halvesIn(float lo, float hi) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int h = 0; h < 0x8000; h++) { float v = HalfFloat.TABLE[h]; if (v >= lo && v < hi) out.add(h); }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int bitsOf(float v) {
        for (int h = 0; h < 65536; h++) if (HalfFloat.TABLE[h] == v) return h;
        throw new AssertionError(v);
    }

    private static ByteBuffer halves(int w, int h, long seed) {
        Random r = new Random(seed);
        final int[] plateau = halvesIn(0.985f, 0.995f), body = halvesIn(0f, 1.12f);
        final int inf = 0x7c00;
        ByteBuffer b = ByteBuffer.allocateDirect(w * h * 3 * 2).order(ByteOrder.nativeOrder());
        for (int i = 0; i < w * h * 3; i++) {
            final float u = r.nextFloat();
            final int bits = u < 0.04f ? plateau[r.nextInt(plateau.length)] // a clip plateau just under white
                    : u < 0.042f ? inf // an overflow sample (counted in the max only)
                    : body[r.nextInt(body.length)];
            b.putShort(i * 2, (short) bits);
        }
        return b;
    }

    /** The shader's walk on the CPU: bins from binTable, sums of round(v * 2048), maxima of positive values. */
    private static ScamRgb.ChannelClip gpuWalk(ByteBuffer rgb, int n, boolean flags) {
        final int[] table = ScamRgb.binTable();
        final long samples = (n + 16L) / 17L;
        final long[][] count = new long[3][ScamRgb.HIST_BINS], sumQ = new long[3][ScamRgb.HIST_BINS];
        final ScamRgb.ChannelClip cc = new ScamRgb.ChannelClip();
        for (long s = 0; s < samples; s++) {
            int i = (int) (s * 17L);
            for (int c = 0; c < 3; c++) {
                int bits = rgb.getShort((i * 3 + c) * 2) & 0xffff;
                float v = HalfFloat.TABLE[bits];
                int b = table[bits];
                if (v > 0f && v > cc.max[c]) cc.max[c] = v;
                if (b >= 0) { count[c][b]++; sumQ[c][b] += (long) (v * 2048f + 0.5f); }
            }
        }
        final int[][] hLo = new int[3][ScamRgb.HIST_BINS], hHi = new int[3][ScamRgb.HIST_BINS];
        final double[][] sLo = new double[3][ScamRgb.HIST_BINS], sHi = new double[3][ScamRgb.HIST_BINS];
        for (int c = 0; c < 3; c++)
            for (int b = 0; b < ScamRgb.HIST_BINS; b++) { hLo[c][b] = (int) count[c][b]; sLo[c][b] = sumQ[c][b] / 2048.0; }
        return ScamRgb.clipFrom(cc, hLo, sLo, hHi, sHi, samples, false, 1f, 0f, flags);
    }

    @Test
    public void theGpuWalkGivesTheCpuLevelsExactly() {
        for (long seed = 1; seed <= 4; seed++) {
            final int w = 640, h = 480;
            ByteBuffer rgb = halves(w, h, seed);
            for (boolean flags : new boolean[]{false, true}) {
                ScamRgb.ChannelClip cpu = ScamRgb.channelClip(rgb, false, 1f, 0f, flags, 1, true);
                ScamRgb.ChannelClip gpu = gpuWalk(rgb, w * h, flags);
                assertArrayEquals(cpu.lo, gpu.lo, 0f);
                assertArrayEquals(cpu.hi, gpu.hi, 0f);
                assertArrayEquals(cpu.max, gpu.max, 0f);
                assertEquals(cpu.nearClip, gpu.nearClip);
                assertEquals(cpu.log, gpu.log);
            }
        }
    }

    @Test
    public void theTableFilesValuesAsTheCpuHistogram() {
        final int[] t = ScamRgb.binTable();
        assertEquals(-2, t[0x7e00]); // a NaN
        assertEquals(-1, t[bitsOf(0.5f)]);
        assertEquals(-1, t[bitsOf(1.2001953f)]);
        assertEquals((int) Math.floor((1.0f - ScamRgb.HIST_T0) / ScamRgb.HIST_BIN), t[bitsOf(1.0f)]);
    }
}
