package com.scamera.app.processing.oldstyle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.util.Random;

/**
 * P643d «ScamMaster»: the viewfinder's preview of the tone (OldstyleLive) - a bilateral grid that carries the screen frame to the
 * module's own picture of the viewfinder's frame (owner: «AE должен быть как в автомате, а тонмапинг полностью из 2.7/3.1»: the
 * 2.7 / 3.1 look on the screen, local tone and colour included). Nothing is drawn before a fit or with its inputs stale; the fit
 * reproduces a transform that varies smoothly over the frame and with luma; the two frames' orientation is found; noise in the
 * pairs does not make steps between cells (the old preview's blotches); the screen's white is not extrapolated past the picture's;
 * the level is held for an HDR picture and not for an SDR one; the rows of «Видоискатель»; the renderer gets a copy once a
 * generation. The real chain (the module, the shader) on scenes and real RAW: tools/check_oldstyle_live.py.
 * <p>
 * P643b's tests of the luma curve and P630g's of the plan's gains over a solved ISP gain went with them.
 */
public class OldstyleLiveTest {
    static final long MS = 1_000_000L;
    static final int T = 64;
    static final float[] LUMA = {0.2126f, 0.7152f, 0.0722f};

    /** A screen tile: a wall, a lit door, a desk; display RGB, row 0 at t = 0. */
    static float[] tile() {
        final float[] t = new float[T * T * 3];
        for (int j = 0; j < T; j++)
            for (int i = 0; i < T; i++) {
                float r = 0.35f + 0.25f * i / T, g = 0.33f + 0.2f * i / T, b = 0.28f + 0.1f * j / T;
                if (i > 22 && i < 34 && j > 12 && j < 44) { r = 0.85f; g = 0.78f; b = 0.42f; }
                if (j > 48) { r = g = b = 0.80f + 0.15f * (j - 48) / 16f; }
                if (j < 6) { r = 0.08f; g = 0.09f; b = 0.12f; }
                t[(j * T + i) * 3] = r;
                t[(j * T + i) * 3 + 1] = g;
                t[(j * T + i) * 3 + 2] = b;
            }
        return t;
    }

    /** A "module's picture" of the tile: a curve, a warmer colour, and a lift that grows to the right of the frame (local tone). */
    static float[] picture(float[] tile, boolean flipDown) {
        final float[] p = new float[tile.length];
        for (int j = 0; j < T; j++)
            for (int i = 0; i < T; i++) {
                final int k = j * T + i, dst = ((flipDown ? T - 1 - j : j) * T + i) * 3;
                final float lift = 1f + 0.3f * i / T;
                for (int c = 0; c < 3; c++) {
                    final float x = tile[k * 3 + c];
                    p[dst + c] = Math.min(1f, (float) Math.pow(x, 0.85) * lift * (c == 0 ? 1.04f : c == 2 ? 0.94f : 1f));
                }
            }
        return p;
    }

    static float[] flat(float v) {
        final float[] f = new float[T * T * 3];
        java.util.Arrays.fill(f, v);
        return f;
    }

    static OldstyleAe.Params params() {
        return new OldstyleAe.Params();
    }

    @Test public void nothingBeforeAFitOrWithItsInputsStale() {
        final OldstyleLive live = new OldstyleLive();
        assertNull(live.grid(0));
        assertFalse(live.wantsScreen());
        live.screen(0, tile(), T, T);
        assertNull("a tile before a picture is not kept", live.grid(0));
        live.photo(0, new OldstyleLive.Picture(picture(tile(), false), true), T, T, params());
        assertTrue(live.wantsScreen());
        assertNull("a picture without a tile: no fit yet", live.grid(10 * MS));
        live.screen(20 * MS, tile(), T, T);
        live.photo(30 * MS, new OldstyleLive.Picture(picture(tile(), false), true), T, T, params());
        final OldstyleLive.Grid g = live.grid(40 * MS);
        assertNotNull(g);
        assertEquals(OldstyleLive.FLOATS, g.coefficients.length);
        assertNull("the tile stale", live.grid(20 * MS + OldstyleLive.STALE_NS + MS));
        // «Тон в видоискателе» off, no library (no picture), outside the mode: nothing, no tile asked for
        final OldstyleAe.Params off = params();
        off.vfTone = false;
        live.photo(50 * MS, new OldstyleLive.Picture(picture(tile(), false), true), T, T, off);
        assertNull(live.grid(50 * MS));
        assertFalse(live.wantsScreen());
        live.photo(60 * MS, null, T, T, params());
        assertNull(live.grid(60 * MS));
        // a picture that does not match the frame in any orientation: no fit
        final OldstyleLive other = new OldstyleLive();
        other.photo(0, new OldstyleLive.Picture(flat(0.5f), true), T, T, params());
        other.screen(0, tile(), T, T);
        other.photo(10 * MS, new OldstyleLive.Picture(flat(0.5f), true), T, T, params());
        assertNull(other.grid(20 * MS));
    }

    static OldstyleLive.Grid fitted(float[] tile, float[] picture, boolean hdr, OldstyleAe.Params p) {
        final OldstyleLive live = new OldstyleLive();
        live.photo(0, new OldstyleLive.Picture(picture, hdr), T, T, p);
        live.screen(0, tile, T, T);
        live.photo(10 * MS, new OldstyleLive.Picture(picture, hdr), T, T, p);
        return live.grid(20 * MS);
    }

    static double error(float[] grid, float[] tile, float[] picture, boolean flipped) {
        double sum = 0;
        int n = 0;
        for (int j = 2; j < T - 2; j++)
            for (int i = 2; i < T - 2; i++) {
                final int k = j * T + i, w = ((flipped ? T - 1 - j : j) * T + i) * 3;
                final float[] o = OldstyleLive.apply(grid, 1f, new float[]{tile[k * 3], tile[k * 3 + 1], tile[k * 3 + 2]}, (i + 0.5f) / T, (j + 0.5f) / T);
                for (int c = 0; c < 3; c++) sum += Math.abs(o[c] - picture[w + c]);
                n += 3;
            }
        return sum / n;
    }

    @Test public void theFitCarriesTheFrameToThePictureLocalToneAndColourIncluded() {
        final float[] t = tile(), p = picture(t, false);
        final OldstyleLive.Grid g = fitted(t, p, true, params());
        // within 2 % on average: the lift across the frame (local), the curve (luma), the warmer colour
        final double e = error(g.coefficients, t, p, false);
        assertTrue("mean error " + e, e < 0.02);
        // the colour moved: the red over the blue of the wall is the picture's (its power and its warmer balance), not the frame's
        final float[] wall = OldstyleLive.apply(g.coefficients, 1f, new float[]{0.45f, 0.41f, 0.30f}, 0.5f, 0.5f);
        final float want = (float) Math.pow(0.45 / 0.30, 0.85) * 1.04f / 0.94f;
        assertEquals(want, wall[0] / wall[2], want * 0.03f);
        assertTrue(wall[0] / wall[2] > 0.45f / 0.30f * 1.02f);
        // and the right of the frame lifted more than the left (the picture's local tone): the wall's own pixels there, each at
        // the picture's value
        float gainLeft = 0f, gainRight = 0f;
        for (int side = 0; side < 2; side++) {
            final int i = side == 0 ? 6 : 58, k = 32 * T + i;
            final float[] x = {t[k * 3], t[k * 3 + 1], t[k * 3 + 2]};
            final float[] o = OldstyleLive.apply(g.coefficients, 1f, x, (i + 0.5f) / T, 32.5f / T);
            for (int c = 0; c < 3; c++) assertEquals(side + " " + c, p[k * 3 + c], o[c], 0.03f);
            final float gain = o[1] / x[1] / (float) Math.pow(x[1], -0.15);   // over the curve's own part
            if (side == 0) gainLeft = gain; else gainRight = gain;
        }
        assertTrue(gainLeft + " < " + gainRight, gainRight > gainLeft * 1.15f);
    }

    @Test public void theOrientationIsFound() {
        final float[] t = tile();
        for (boolean flipped : new boolean[]{false, true}) {
            final float[] p = picture(t, flipped);
            assertEquals(flipped ? 2 : 0, OldstyleLive.orientation(t, T, T, p, T, T, -1));
            final OldstyleLive.Grid g = fitted(t, p, true, params());
            assertTrue("flipped " + flipped, error(g.coefficients, t, p, flipped) < 0.02);
        }
        // the previous orientation kept unless another is clearly better
        assertEquals(2, OldstyleLive.orientation(t, T, T, picture(t, true), T, T, 2));
    }

    @Test public void noiseDoesNotMakeStepsBetweenCells() {
        final float[] t = tile(), p = picture(t, false);
        final Random random = new Random(643);
        for (int i = 0; i < p.length; i++) p[i] = Math.max(0f, Math.min(1f, p[i] + (float) random.nextGaussian() * 0.05f));
        final float[] g = fitted(t, p, true, params()).coefficients;
        // a flat colour through the grid changes by little from one cell centre to the next
        for (float[] probe : new float[][]{{0.2f, 0.2f, 0.2f}, {0.5f, 0.5f, 0.5f}, {0.85f, 0.85f, 0.85f}, {0.8f, 0.6f, 0.3f}}) {
            float worst = 0f;
            for (int gy = 0; gy < OldstyleLive.GY; gy++)
                for (int gx = 0; gx + 1 < OldstyleLive.GX; gx++) {
                    final float[] a = OldstyleLive.apply(g, 1f, probe, (gx + 0.5f) / OldstyleLive.GX, (gy + 0.5f) / OldstyleLive.GY),
                            b = OldstyleLive.apply(g, 1f, probe, (gx + 1.5f) / OldstyleLive.GX, (gy + 0.5f) / OldstyleLive.GY);
                    for (int c = 0; c < 3; c++) worst = Math.max(worst, Math.abs(a[c] - b[c]));
                }
            assertTrue("probe " + probe[0] + ": " + worst, worst < 0.1f);
        }
        for (float v : g) assertTrue(Float.isFinite(v));
    }

    @Test public void theScreensWhiteIsNotExtrapolatedPastThePictures() {
        // the tile's brightest is 0.95 (a mean of the frame's pixels); the picture compresses its top to 0.9
        final float[] t = tile(), p = picture(t, false);
        for (int i = 0; i < p.length; i++) p[i] = Math.min(p[i], 0.9f);
        final float[] g = fitted(t, p, true, params()).coefficients;
        for (float u = 0.05f; u < 1f; u += 0.1f)
            for (float v = 0.05f; v < 1f; v += 0.1f) {
                final float[] white = OldstyleLive.apply(g, 1f, new float[]{1f, 1f, 1f}, u, v);
                assertTrue(u + ", " + v + ": " + white[1], white[1] < 0.97f);
            }
    }

    @Test public void theLevelIsHeldForAnHdrPictureOnly() {
        final float[] t = tile(), p = picture(t, false), darker = new float[t.length];
        for (int i = 0; i < t.length; i++) darker[i] = (float) Math.pow(Math.pow(t[i], 2.2) * 0.5, 1 / 2.2);
        for (boolean hdr : new boolean[]{true, false}) {
            final OldstyleLive live = new OldstyleLive();
            live.photo(0, new OldstyleLive.Picture(p, hdr), T, T, params());
            live.screen(0, t, T, T);
            live.photo(10 * MS, new OldstyleLive.Picture(p, hdr), T, T, params());
            assertEquals(1f, live.grid(20 * MS).level, 1e-4f);
            live.screen(50 * MS, darker, T, T);   // the AE a stop down: past LEVEL_SNAP, at once
            final float level = live.grid(60 * MS).level;
            // HDR: the photo's brightness is the estimator's (fusion_gains over the frame's TET) - the frame drawn at the fit's light;
            // without HDR the photo is the frame at the AE's exposure - drawn as it is
            assertEquals("hdr " + hdr, hdr ? 2f : 1f, level, hdr ? 0.02f : 0f);
        }
    }

    @Test public void theViewfinderRows() {
        final float[] t = tile(), p = picture(t, false);
        // «Подгонка под снимок» 0: the frame as it is
        final OldstyleAe.Params none = params();
        none.vfMatch = 0f;
        final float[] g0 = fitted(t, p, true, none).coefficients;
        assertTrue(error(g0, t, t, false) < 0.01);
        // «Яркость видоискателя» +1: the picture a stop brighter on the screen (display x 2 ^ (1 / 2.2), short of white)
        final OldstyleAe.Params up = params();
        up.vfEv = 1f;
        final float[] g1 = fitted(t, p, true, up).coefficients, g = fitted(t, p, true, params()).coefficients;
        final float base = OldstyleLive.apply(g, 1f, new float[]{0.3f, 0.3f, 0.3f}, 0.3f, 0.5f)[1],
                lifted = OldstyleLive.apply(g1, 1f, new float[]{0.3f, 0.3f, 0.3f}, 0.3f, 0.5f)[1];
        assertEquals(base * (float) Math.pow(2, 1 / 2.2), lifted, base * 0.05f);
        // «Плавность видоискателя»: a new fit followed faster with a shorter time
        final double[] moved = new double[2];
        int i = 0;
        for (float seconds : new float[]{0.05f, 2f}) {
            final OldstyleAe.Params a = params(), b = params();
            a.vfSmooth = b.vfSmooth = seconds;
            b.vfEv = 1f;
            final OldstyleLive live = new OldstyleLive();
            live.photo(0, new OldstyleLive.Picture(p, true), T, T, a);
            live.screen(0, t, T, T);
            live.photo(10 * MS, new OldstyleLive.Picture(p, true), T, T, a);
            final float before = OldstyleLive.apply(live.grid(20 * MS).coefficients, 1f, new float[]{0.3f, 0.3f, 0.3f}, 0.3f, 0.5f)[1];
            live.photo(300 * MS, new OldstyleLive.Picture(p, true), T, T, b);
            live.screen(300 * MS, t, T, T);
            final float after = OldstyleLive.apply(live.grid(600 * MS).coefficients, 1f, new float[]{0.3f, 0.3f, 0.3f}, 0.3f, 0.5f)[1];
            moved[i++] = (after - before) / (lifted - base);
        }
        assertEquals(1.0, moved[0], 0.05);
        assertTrue(moved[1] + " a seventh or so", moved[1] > 0.08 && moved[1] < 0.25);
    }

    @Test public void theRendererGetsACopyOnceAGeneration() {
        final OldstyleLive live = new OldstyleLive();
        live.photo(0, new OldstyleLive.Picture(picture(tile(), false), true), T, T, params());
        live.screen(0, tile(), T, T);
        live.photo(10 * MS, new OldstyleLive.Picture(picture(tile(), false), true), T, T, params());
        final OldstyleLive.Grid a = live.grid(20 * MS, Integer.MIN_VALUE);
        assertNotNull(a.coefficients);
        final OldstyleLive.Grid b = live.grid(21 * MS, a.generation);
        assertEquals(a.generation, b.generation);
        assertNull("the renderer has it", b.coefficients);
        a.coefficients[0] = 99f;
        assertTrue("a copy", live.grid(22 * MS).coefficients[0] != 99f);
        live.off();
        assertNull(live.grid(30 * MS));
    }

    @Test public void theFrameForThePictureFromTheRaw() {
        // the shown part cut to the stream's shape: a 16:9 stream of a 4:3 part keeps its width and the middle of its height
        final float[] part = OldstyleAe.shownPart(new float[]{0f, 0f, 1f, 1f}, 4000, 3000, 16f / 9f);
        assertEquals(0f, part[0], 1e-6f);
        assertEquals(1f, part[2], 1e-6f);
        assertEquals(0.75f, part[3], 1e-4f);
        assertEquals(0.125f, part[1], 1e-4f);
        assertEquals(1f, OldstyleAe.shownPart(new float[]{0f, 0f, 1f, 1f}, 4000, 3000, 0f)[3], 0f);
        // the box of quads: a flat RAW at half the range is 0.5 x the white balance, the lens shading map applied per site
        final int w = 64, h = 48;
        final java.nio.ShortBuffer raw = java.nio.ShortBuffer.allocate(w * h);
        for (int i = 0; i < w * h; i++) raw.put(i, (short) (64 + (1023 - 64) / 2));
        final float[] lsc = new float[2 * 2 * 4];
        for (int n = 0; n < 4; n++) {
            lsc[n * 4] = 2f;
            lsc[n * 4 + 1] = lsc[n * 4 + 2] = 1f;
            lsc[n * 4 + 3] = 1.5f;
        }
        final float[] cells = OldstyleAe.bayerBox(raw, w, w, h, 0, new float[]{64, 64, 64, 64}, 1023f, new float[]{2f, 1f, 1.5f}, 8, 6, 3,
                lsc, 2, 2, 0, 0, w, h);
        for (int i = 0; i < 8 * 6; i++) {
            assertEquals(0.5f * 2f * 2f, cells[i * 3], 0.01f);
            assertEquals(0.5f, cells[i * 3 + 1], 0.01f);
            assertEquals(0.5f * 1.5f * 1.5f, cells[i * 3 + 2], 0.01f);
        }
    }
}
