package com.scamera.app.processing.portrait;

import org.junit.Test;

import static org.junit.Assert.*;

public class PortraitSkinTest {
    private static final float[] BOX = {0f, 0f, 1f, 1f};

    private static float[] colour(double light, double ratio, double hue) {
        double a = light * ratio * Math.cos(Math.toRadians(hue));
        double b = light * ratio * Math.sin(Math.toRadians(hue));
        double l = light + 0.3963377774 * a + 0.2158037573 * b;
        double m = light - 0.1055613458 * a - 0.0638541728 * b;
        double s = light - 0.0894841775 * a - 1.2914855480 * b;
        l *= l * l; m *= m * m; s *= s * s;
        return new float[]{(float) (4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
                (float) (-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
                (float) (-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)};
    }

    private static float[] image(float[] rgb, int channels) {
        float[] out = new float[64 * 64 * channels];
        for (int i = 0; i < 64 * 64; i++) System.arraycopy(rgb, 0, out, i * channels, 3);
        return out;
    }

    private static PortraitSkin.Result measure(float[] rgb) {
        return PortraitSkin.measure(image(rgb, 3), 3, 64, 64, BOX, 1f);
    }

    @Test public void naturalSkinAndDarkSkinKeepTheirHue() {
        for (double light : new double[]{0.24, 0.5, 0.8})
            for (double hue : new double[]{30, 45, 65}) {
                PortraitSkin.Result r = measure(colour(light, 0.12, hue));
                assertTrue(r.samples[0] > 100);
                assertFalse(r.corrected());
            }
    }

    @Test public void yellowCoolsAndBlueWarmsWithABoundedStrength() {
        float[] yellow = colour(0.6, 0.10, 85), blue = colour(0.6, 0.10, 5);
        float cool = measure(yellow).adjust[0], warm = measure(blue).adjust[0];
        assertEquals(-PortraitSkin.MAX_TURN, cool, 1e-6f);
        assertEquals(PortraitSkin.MAX_TURN, warm, 1e-6f);
        assertEquals(cool * 0.5f, PortraitSkin.measure(image(yellow, 4), 4, 64, 64, BOX, 0.5f).adjust[0], 1e-6f);
        assertFalse(PortraitSkin.measure(image(yellow, 3), 3, 64, 64, BOX, 0f).corrected());
    }

    @Test public void invalidClippedNeutralAndWeakSignalsDoNotCorrect() {
        for (float[] rgb : new float[][]{{Float.NaN, 0.2f, 0.1f}, {0.2f, Float.POSITIVE_INFINITY, 0.1f},
                {1f, 0.5f, 0.2f}, {0f, 0.1f, 0.1f}, {0.2f, 0.2f, 0.2f}, {0.001f, 0.0006f, 0.0003f},
                colour(0.6, 0.12, 145), colour(0.6, 0.12, 265)})
            assertFalse(measure(rgb).corrected());
        assertFalse(PortraitSkin.measure(new float[3], 3, 64, 64, BOX, 1f).corrected());
        assertFalse(PortraitSkin.measure(image(colour(0.5, 0.1, 85), 3), 3, 64, 64,
                new float[]{0f, 0f, 0.01f, 0.01f}, 1f).corrected());
    }

    @Test public void facesAreIndependentAndBackgroundDoesNotEnterTheEstimate() {
        float[] pixels = image(colour(0.6, 0.12, 45), 3);
        float[] yellow = colour(0.6, 0.1, 85), blue = colour(0.6, 0.1, 5);
        for (int y = 16; y < 48; y++)
            for (int x = 0; x < 64; x++) System.arraycopy(x < 32 ? yellow : blue, 0, pixels, (y * 64 + x) * 3, 3);
        PortraitSkin.Result r = PortraitSkin.measure(pixels, 3, 64, 64,
                new float[]{0f, 0.25f, 0.5f, 0.75f, 0.5f, 0.25f, 1f, 0.75f}, 1f);
        assertEquals(2, r.count);
        assertTrue(r.adjust[0] < 0f);
        assertTrue(r.adjust[4] > 0f);
        assertArrayEquals(new float[]{0f, 0.25f, 0.5f, 0.75f}, java.util.Arrays.copyOf(r.faces, 4), 0f);
    }

    @Test public void malformedBoxesAndExcessFacesAreBounded() {
        float[] boxes = {Float.NaN, 0f, 1f, 1f, 0.9f, 0f, 0.1f, 1f,
                -1f, -1f, 2f, 2f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 1f,
                0f, 0f, 1f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 1f};
        PortraitSkin.Result r = PortraitSkin.measure(image(colour(0.6, 0.1, 85), 3), 3, 64, 64, boxes, 1f);
        assertEquals(5, r.count);
        assertEquals(0f, r.faces[0], 0f);
        assertEquals(1f, r.faces[2], 0f);
        for (int i = 0; i < r.count; i++) assertTrue(Math.abs(r.adjust[i * 4]) <= PortraitSkin.MAX_TURN);
    }
}
