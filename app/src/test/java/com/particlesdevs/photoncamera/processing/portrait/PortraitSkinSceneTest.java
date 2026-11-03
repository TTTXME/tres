package com.particlesdevs.photoncamera.processing.portrait;

import org.junit.Test;

import static org.junit.Assert.*;

/** P473: the scene-confirmed yellow-blue skin shift (Result.adjust y) - both the background neutrals and the skin hue must agree. */
public class PortraitSkinSceneTest {
    private static final float[] FACE = {0.3f, 0.3f, 0.7f, 0.7f};

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

    /** A 64 x 64 frame: {@code background} everywhere, {@code skin} inside the face box. */
    private static float[] scene(float[] background, float[] skin) {
        float[] out = new float[64 * 64 * 3];
        for (int y = 0; y < 64; y++)
            for (int x = 0; x < 64; x++) {
                boolean face = x >= 19 && x < 45 && y >= 19 && y < 45;
                System.arraycopy(face ? skin : background, 0, out, (y * 64 + x) * 3, 3);
            }
        return out;
    }

    private static PortraitSkin.Result measure(float[] background, float[] skin, float strength) {
        return PortraitSkin.measure(scene(background, skin), 3, 64, 64, FACE, strength);
    }

    @Test public void naturalSkinOnAnyBackgroundKeepsItsColour() {
        for (double sceneB : new double[]{0.01, 0.033, 0.07, -0.03})
            for (double hue : new double[]{45, 50, 54}) {
                PortraitSkin.Result r = measure(colour(0.6, Math.abs(sceneB), sceneB > 0 ? 90 : -90), colour(0.5, 0.08, hue), 1f);
                assertTrue(Float.isFinite(r.sceneB));
                assertEquals(sceneB, r.sceneB, 0.0015);
                assertEquals(0f, r.adjust[1], 0f);
                assertFalse(r.corrected());
            }
    }

    @Test public void yellowSkinWithAYellowSceneCoolsBoundedAndNeverPastHue52() {
        float[] warm = colour(0.6, 0.056, 90);
        PortraitSkin.Result r = measure(warm, colour(0.5, 0.10, 68), 1f);
        assertEquals(0f, r.adjust[0], 0f);              // inside the broad band: the old turn stays off
        assertTrue(r.adjust[1] < -0.02f);
        assertTrue(r.adjust[1] >= -PortraitSkin.MAX_SHIFT);
        assertTrue(r.corrected());
        assertTrue(r.b[0] + r.adjust[1] >= r.a[0] * (float) Math.tan(Math.toRadians(52.0)) - 1e-6f);
        assertEquals(r.adjust[1] * 0.5f, measure(warm, colour(0.5, 0.10, 68), 0.5f).adjust[1], 1e-6f);
        // a stronger cast: still bounded
        PortraitSkin.Result strong = measure(colour(0.6, 0.09, 90), colour(0.5, 0.16, 69), 1f);
        assertEquals(-PortraitSkin.MAX_SHIFT, strong.adjust[1], 1e-6f);
    }

    @Test public void coldSkinWithABlueSceneWarms() {
        PortraitSkin.Result r = measure(colour(0.6, 0.03, -90), colour(0.5, 0.06, 30), 1f);
        assertTrue(r.adjust[1] > 0.01f);
        assertTrue(r.adjust[1] <= PortraitSkin.MAX_SHIFT);
        assertTrue(r.b[0] + r.adjust[1] <= Math.max(r.b[0], r.a[0] * (float) Math.tan(Math.toRadians(48.0))) + 1e-6f);
    }

    @Test public void oneCueAloneNeverShifts() {
        // yellow skin, neutral scene (an olive face under the right white balance)
        assertEquals(0f, measure(colour(0.6, 0.01, 90), colour(0.5, 0.10, 68), 1f).adjust[1], 0f);
        // cold-looking skin, neutral scene
        assertEquals(0f, measure(colour(0.6, 0.01, 90), colour(0.5, 0.06, 30), 1f).adjust[1], 0f);
        // no neutral background: a colourful one, or the face filling the frame
        PortraitSkin.Result green = measure(colour(0.6, 0.2, 145), colour(0.5, 0.10, 68), 1f);
        assertTrue(Float.isNaN(green.sceneB));
        assertEquals(0f, green.adjust[1], 0f);
        float[] full = scene(colour(0.5, 0.10, 68), colour(0.5, 0.10, 68));
        PortraitSkin.Result filled = PortraitSkin.measure(full, 3, 64, 64, new float[]{0f, 0f, 1f, 1f}, 1f);
        assertTrue(Float.isNaN(filled.sceneB));
        assertFalse(filled.corrected());
    }

    @Test public void shiftIsBoundedAndQuietForBadInputs() {
        for (float sceneB = -0.12f; sceneB <= 0.12f; sceneB += 0.005f)
            for (float hue = -35f; hue <= 115f; hue += 2.5f)
                for (float a : new float[]{-0.02f, 0f, 0.03f, 0.08f})
                    for (float b : new float[]{-0.05f, 0f, 0.05f, 0.12f}) {
                        float s = PortraitSkin.shift(sceneB, hue, a, b);
                        assertTrue(Float.isFinite(s) && Math.abs(s) <= PortraitSkin.MAX_SHIFT);
                        if (s < 0f) assertTrue(sceneB > 0.03f && hue > 56f);
                        if (s > 0f) assertTrue(sceneB < 0.005f && hue < 44f);
                    }
        assertEquals(0f, PortraitSkin.shift(Float.NaN, 68f, 0.03f, 0.09f), 0f);
        assertEquals(0f, PortraitSkin.shift(0.06f, Float.NaN, 0.03f, 0.09f), 0f);
        assertEquals(0f, PortraitSkin.shift(0.06f, 68f, Float.POSITIVE_INFINITY, 0.09f), 0f);
    }
}
