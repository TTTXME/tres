package com.particlesdevs.photoncamera.processing.render;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;

/** P558: the white from the frame's clip plateau (Parameters.clipPlateau). */
public class ParametersWhitePlateauTest {
    private static int[] histogram(int clip, int clipped, int bright, double sigma, int hot, long seed) {
        final int[] h = new int[65536];
        final Random r = new Random(seed);
        for (int i = 0; i < 100_000; i++) h[64 + r.nextInt(500)]++;
        for (int i = 0; i < clipped; i++) h[clip]++;
        for (int i = 0; i < bright; i++) h[(int) Math.max(0, Math.min(clip, Math.round(clip - 40 + sigma * r.nextGaussian())))]++;
        for (int i = 0; i < hot; i++) h[1023]++;
        return h;
    }

    private static long count(int[] h) { long n = 0; for (int v : h) n += v; return n; }

    @Test public void aBlownAreaPilesOnItsClip() {
        final int[] h = histogram(1000, 3000, 2000, 8, 0, 1);
        assertEquals(1000, Parameters.clipPlateau(h, count(h)));
        // a few hot pixels above the clip are no plateau of their own
        final int[] g = histogram(1000, 3000, 2000, 8, 3, 2);
        assertEquals(1000, Parameters.clipPlateau(g, count(g)));
    }

    @Test public void brightContentAndSmallSpotsAreNoClip() {
        final int[] h = histogram(1000, 0, 5000, 12, 0, 3);
        assertEquals("flat bright content spreads with its noise", 0, Parameters.clipPlateau(h, count(h)));
        final int[] g = histogram(1000, 50, 2000, 8, 0, 4);
        assertEquals("a spot of 50 samples in ~100k is under 0.2 %", 0, Parameters.clipPlateau(g, count(g)));
    }
}
