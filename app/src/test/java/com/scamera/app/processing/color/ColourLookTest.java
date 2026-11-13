package com.scamera.app.processing.color;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P134 «Цвет»: the white-balance matrix and the uniforms of the colour look (why/combine.glsl: tools/check_colour_look.py). */
public class ColourLookTest {
    private static float[] white(float[] m) {
        return new float[]{m[0] + m[1] + m[2], m[3] + m[4] + m[5], m[6] + m[7] + m[8]};
    }

    private static float luminance(float[] rgb) {
        return 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2];
    }

    @Test public void defaultsChangeNothing() {
        ColourLook c = new ColourLook();
        assertTrue(c.shaderNeutral());
        assertNull(c.whiteMatrix(6504f));
        assertNull("the scene light is off by default, whatever the light", c.whiteMatrix(2700f));
        assertArrayEquals(new float[]{0f, 0f, 0f}, c.monoUniform(), 0f);
        float[][] g = c.gradeUniforms();
        assertArrayEquals(new float[]{0f, 0f, 0f, 0f}, g[0], 0f);
        assertEquals(0.5f, g[1][3], 0f);
        assertFalse(c.mixerOn());
    }

    @Test public void warmthAndTintMoveWhiteKeepingItsLuminance() {
        ColourLook c = new ColourLook();
        c.warmth = 50f;
        float[] w = white(c.whiteMatrix(6504f));
        assertEquals(1f, luminance(w), 1e-4f);
        assertTrue("warmer: red over blue " + java.util.Arrays.toString(w), w[0] > w[2] + 0.05f);
        c.warmth = -50f;
        w = white(c.whiteMatrix(6504f));
        assertTrue("cooler: blue over red", w[2] > w[0] + 0.05f);
        c.warmth = 0f;
        c.tint = 60f;
        w = white(c.whiteMatrix(6504f));
        assertEquals(1f, luminance(w), 1e-4f);
        assertTrue("magenta: green under red and blue " + java.util.Arrays.toString(w), w[1] < Math.min(w[0], w[2]));
        c.tint = -60f;
        w = white(c.whiteMatrix(6504f));
        assertTrue("green", w[1] > Math.max(w[0], w[2]));
        // a small step gives a small matrix, symmetric in sign
        c.tint = 0f;
        c.warmth = 1f;
        float[] small = c.whiteMatrix(6504f);
        assertEquals(1f, small[0] + small[1] + small[2], 0.02f);
    }

    @Test public void sceneLightIsKeptOnlyForWarmOrCoolLight() {
        ColourLook c = new ColourLook();
        c.keepWarm = 100f;
        assertNull("daylight: nothing to keep", c.whiteMatrix(5600f));
        float[] w = white(c.whiteMatrix(2700f));
        assertTrue("tungsten stays warm " + java.util.Arrays.toString(w), w[0] > w[2] * 1.3f);
        float[] half = white(c.whiteMatrix(3500f));
        assertTrue("less at 3500 K", half[0] / half[2] < w[0] / w[2]);
        c.keepWarm = 0f;
        c.keepCool = 100f;
        assertNull(c.whiteMatrix(2700f));
        w = white(c.whiteMatrix(12000f));
        assertTrue("shade stays blue " + java.util.Arrays.toString(w), w[2] > w[0] * 1.1f);
        assertNull("unknown light", c.whiteMatrix(Float.NaN));
    }

    @Test public void wheelHuesMapOntoOklab() {
        assertEquals(29.2, ColourLook.oklabHueOfWheel(0), 1.0);      // sRGB red
        assertEquals(110.0, ColourLook.oklabHueOfWheel(60), 2.0);    // yellow
        assertEquals(142.5, ColourLook.oklabHueOfWheel(120), 1.5);   // green
        assertEquals(264.1, ColourLook.oklabHueOfWheel(240), 1.0);   // blue
        assertEquals(ColourLook.oklabHueOfWheel(10), ColourLook.oklabHueOfWheel(370), 1e-9);
        ColourLook c = new ColourLook();
        c.gradeStr[2] = 100f;
        c.gradeHue[2] = 60f;
        float[][] g = c.gradeUniforms();
        assertEquals(0.06, Math.hypot(g[1][0], g[1][1]), 1e-6);
        assertTrue("yellow highlights: +b", g[1][1] > 0.05f);
        assertFalse(c.shaderNeutral());
    }

    @Test public void primariesCalibrationKeepsWhite() {
        ColourLook c = new ColourLook();
        assertNull(c.calibrationMatrix());
        c.calHue[0] = 100f;
        float[] m = c.calibrationMatrix();
        float[] w = white(m);
        assertArrayEquals("white stays white", new float[]{1f, 1f, 1f}, w, 1e-4f);
        assertTrue("red turned towards yellow: green in the red column " + java.util.Arrays.toString(m), m[3] > 0.02f);
        assertFalse(c.matrixNeutral(6504f));
        assertArrayEquals("the white matrix is the calibration alone", m, c.whiteMatrix(6504f), 1e-6f);
        c.calHue[0] = 0f;
        c.calSat[2] = 100f;
        m = c.calibrationMatrix();
        assertArrayEquals(new float[]{1f, 1f, 1f}, white(m), 1e-4f);
        // white stays white, so the richer blue shows in the column of blue: less green (and red) mixed into it
        assertTrue("richer blue: a blue input carries less green " + java.util.Arrays.toString(m), m[5] < -0.02f);
        c.warmth = 40f;
        float[] both = c.whiteMatrix(6504f);
        assertTrue("warmth still acts with the calibration", white(both)[0] > white(both)[2]);
    }

    @Test public void uniformsAreScaledAndClamped() {
        ColourLook c = new ColourLook();
        c.mix[5 * 3 + 1] = -100f;
        c.mix[0] = 250f;
        float[] u = c.mixUniform();
        assertEquals(-1f, u[16], 0f);
        assertEquals(1f, u[0], 0f);
        assertTrue(c.mixerOn());
        c.mono = 4;
        assertArrayEquals(new float[]{1f, 0.2f, 0.02f}, c.monoUniform(), 0f);
        c.skinProtect = 50f;
        c.skinHue = -100f;
        assertArrayEquals(new float[]{0.5f, -1f, 0f}, c.skinUniform(), 0f);
    }

    /** OKLab of a linear sRGB colour. */
    private static double[] oklab(float[] rgb) {
        double l = Math.cbrt(0.4122214708 * rgb[0] + 0.5363325363 * rgb[1] + 0.0514459929 * rgb[2]);
        double m = Math.cbrt(0.2119034982 * rgb[0] + 0.6806995451 * rgb[1] + 0.1073969566 * rgb[2]);
        double s = Math.cbrt(0.0883024619 * rgb[0] + 0.2817188376 * rgb[1] + 0.6299787005 * rgb[2]);
        return new double[]{0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s, 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s};
    }

    private static void finite(String what, float[] m) {
        for (float v : m) assertTrue(what + " finite " + java.util.Arrays.toString(m), Float.isFinite(v));
    }

    /**
     * P577 colour audit: «Тепло» / «Оттенок» over their whole range (-100 .. 100, also the LUT tray's «Цвет» tab): white moves
     * steadily warmer (OKLab b up) with the warmth and towards magenta (OKLab a up, green down) with the tint, keeps its
     * luminance, the two rows stay apart (the warmth barely tints, the tint is mostly OKLab a), finite at the ends.
     */
    @Test public void warmthAndTintAreMonotonicOverTheirRange() {
        ColourLook c = new ColourLook();
        double lastB = -1, lastA = -1;
        for (int v = -100; v <= 100; v += 5) {
            c.warmth = v;
            c.tint = 0f;
            float[] m = v == 0 ? new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1} : c.whiteMatrix(6504f);
            finite("warmth " + v, m);
            double[] w = oklab(white(m));
            assertEquals("warmth " + v + " keeps the luminance", 1f, luminance(white(m)), 1e-4f);
            assertTrue("warmth " + v + ": warmer than " + (v - 5) + " (b " + w[2] + ")", w[2] > lastB);
            assertTrue("warmth " + v + ": no tint (a " + w[1] + ", b " + w[2] + ")", Math.abs(w[1]) < 0.25 * Math.abs(w[2]) + 0.002);
            lastB = w[2];
            c.warmth = 0f;
            c.tint = v;
            m = v == 0 ? new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1} : c.whiteMatrix(6504f);
            finite("tint " + v, m);
            w = oklab(white(m));
            assertEquals("tint " + v + " keeps the luminance", 1f, luminance(white(m)), 1e-4f);
            assertTrue("tint " + v + ": more magenta than " + (v - 5) + " (a " + w[1] + ")", w[1] > lastA);
            // (the Duv normal of the daylight locus lies at OKLab hue ~150 / ~330 deg at D65: green-yellow / pink-magenta, as an
            // editor's tint perpendicular to the locus does - mostly a, some b)
            assertTrue("tint " + v + ": mostly a (a " + w[1] + ", b " + w[2] + ")", Math.abs(w[2]) < 0.7 * Math.abs(w[1]) + 0.002);
            lastA = w[1];
        }
        // the range ends: a visible, bounded change (no clip of a mid grey through the matrix)
        c.tint = 0f;
        c.warmth = 100f;
        double bWarm = oklab(white(c.whiteMatrix(6504f)))[2];
        c.warmth = -100f;
        double bCool = oklab(white(c.whiteMatrix(6504f)))[2];
        assertTrue("warmth ends " + bWarm + " / " + bCool, bWarm > 0.02 && bWarm < 0.12 && bCool < -0.02 && bCool > -0.12);
    }

    /** P577: «Сохранить тепло / синеву» over the light's whole range: finite, only ever warmer (cooler) the warmer (cooler) the light. */
    @Test public void keptSceneLightIsMonotonicInTheLight() {
        ColourLook c = new ColourLook();
        c.keepWarm = 100f;
        double last = -1;
        for (float cct = 5000f; cct >= 1000f; cct -= 250f) {
            float[] m = c.whiteMatrix(cct);
            if (m == null) continue;
            finite("keep warm " + cct, m);
            double b = oklab(white(m))[2];
            assertTrue("keep warm at " + cct + " K: warmer than at a higher light (" + b + ")", b >= last - 1e-9);
            last = b;
        }
        c.keepWarm = 0f;
        c.keepCool = 100f;
        last = 1;
        for (float cct = 7000f; cct <= 40000f; cct += 1000f) {
            float[] m = c.whiteMatrix(cct);
            if (m == null) continue;
            finite("keep cool " + cct, m);
            double b = oklab(white(m))[2];
            assertTrue("keep cool at " + cct + " K: cooler than at a lower light (" + b + ")", b <= last + 1e-9);
            last = b;
        }
        assertNull("above 40000 K: unknown", c.whiteMatrix(41000f));
    }

    /** P577: the primaries calibration at every range end keeps white white and stays finite. */
    @Test public void calibrationRangeEndsKeepWhite() {
        float[] ends = {-100f, 100f};
        for (int p = 0; p < 3; p++)
            for (float h : ends)
                for (float s : ends) {
                    ColourLook c = new ColourLook();
                    c.calHue[p] = h;
                    c.calSat[p] = s;
                    float[] m = c.calibrationMatrix();
                    finite("primary " + p, m);
                    assertArrayEquals("primary " + p + " hue " + h + " sat " + s, new float[]{1f, 1f, 1f}, white(m), 1e-4f);
                }
        ColourLook all = new ColourLook();
        for (int p = 0; p < 3; p++) { all.calHue[p] = 100f; all.calSat[p] = -100f; }
        assertArrayEquals(new float[]{1f, 1f, 1f}, white(all.calibrationMatrix()), 1e-4f);
    }
}
