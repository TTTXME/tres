package com.scamera.app.ui.camera;

import com.scamera.app.settings.PhotoStyle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

/**
 * P718: the presets' card thumbnails (LookThumbs.presetPixels, the CPU sketch of WhyV2Ae.applyStyle on the drawn sample) go the
 * presets' way: Обычный the sample itself, Насыщенный brighter shadows, no brighter lights and richer muted colours, Нейтральный
 * darker shadows and quieter colours.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = android.app.Application.class)
public class LookThumbsPresetTest {
    private static final int W = 64, H = 64;

    /** Left half a dark shadow (with a muted green patch), right half a bright sky (muted blue). */
    private static int[] sample() {
        final int[] px = new int[W * H];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
                px[y * W + x] = x < W / 2 ? (y > H / 2 ? rgb(30, 44, 30) : rgb(20, 20, 22)) : rgb(190, 205, 230);
        return px;
    }

    private static int rgb(int r, int g, int b) {
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static float luma(int p) {
        return 0.2126f * ((p >> 16) & 255) + 0.7152f * ((p >> 8) & 255) + 0.0722f * (p & 255);
    }

    private static float chroma(int p) {
        final int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }

    @Test
    public void presetsGoTheirWay() {
        final int[] src = sample();
        assertArrayEquals(src, LookThumbs.presetPixels(src, W, H, PhotoStyle.NORMAL));
        final int[] vivid = LookThumbs.presetPixels(src, W, H, PhotoStyle.VIVID);
        final int[] neutral = LookThumbs.presetPixels(src, W, H, PhotoStyle.NEUTRAL);
        final int shadow = 8 * W + 8, green = (H - 8) * W + 8, sky = 8 * W + W - 8;
        assertTrue("Vivid lifts the shadows", luma(vivid[shadow]) > luma(src[shadow]));
        assertTrue("Vivid does not brighten the sky", luma(vivid[sky]) <= luma(src[sky]) + 0.5f);
        assertTrue("Vivid: richer muted colours", chroma(vivid[green]) > chroma(src[green]) && chroma(vivid[sky]) > chroma(src[sky]));
        assertTrue("Neutral: gentler contrast", luma(neutral[sky]) - luma(neutral[shadow]) < luma(src[sky]) - luma(src[shadow]));
        assertTrue("Neutral: no extra shadow lift", luma(neutral[shadow]) < luma(vivid[shadow]));
        assertTrue("Neutral: quieter colours", chroma(neutral[green]) < chroma(src[green]) && chroma(neutral[sky]) < chroma(src[sky]));
    }
}
