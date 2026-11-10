package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * P508 (owner, 2026-11-05: «больше информации в светах, но так чтобы в остальных сценариях это ничего не испортило»; sun scenes
 * full of detail, no artefacts, the sky not darker): (1) scamhdr/scamrgb - the fast merge flags R, G, B at >= 0.94 of white, also
 * where its short frames measured far more; above 1.03 .. 1.10 x the clip the neutral of a blown core (opposed full / band,
 * blown, all3) fades out, the censoring and the G lift stay; (2) why/combine HL_LOCAL - the light above white keeps its local
 * contrast against an edge-aware log mean of whyLow, darkening only, above a 0.15 EV noise floor, not beside a light 12 x brighter.
 */
public class HighlightDetailTest {
    private static String read(String path) throws IOException {
        File f = new File(path);
        if (!f.isFile()) f = new File("app/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test public void measuredLightIsNoBlownNeutralCore() throws IOException {
        final String s = read("src/main/assets/shaders/scamhdr/scamrgb.glsl");
        assertTrue(s.contains("float keepC = L.g > 0.0 ? 1.0 - smoothstep(1.03, 1.10, max(max(rel.r, rel.g), rel.b)) : 1.0;"));
        assertTrue(s.contains("float all3 = sat.r * sat.g * sat.b * keepC;"));
        assertTrue(s.contains("clamp(2.0 - Ub, 0.0, 1.0) * keepC;"));
        assertTrue(s.contains("max(full, band) * (1.0 - hdrO));"));
        // the censoring itself is unchanged (a mixed value is still a lower bound: no pink / lime)
        assertTrue(s.contains("vec3 sat = L.g > 0.0 ? smoothstep(vec3(0.90), vec3(0.985), rel) : vec3(0.0);"));
        assertTrue(s.contains("if (clipped.g) r.g = max(r.g, min(r.r, r.b));"));
    }

    @Test public void theLightAboveWhiteKeepsItsLocalContrast() throws IOException {
        final String c = read("src/main/assets/shaders/why/combine.glsl");
        assertTrue(c.contains("#define HL_LOCAL 0\n"));
        assertTrue(c.contains("#if HL_LOCAL == 1\nuniform vec4 hlLocalU;"));
        assertTrue("the low grid's luminance, not the full-size noise", c.contains("float yP = yLow * ae;"));
        assertTrue("darkening only, above the noise floor", c.contains("clamp(lp - lm + 0.15, -2.0, 0.0)"));
        assertTrue(c.contains("(1.0 - smoothstep(guard, 2.0 * guard, yMax / yP))"));
        final float[] u = WhyTone.hlLocal();
        assertEquals(0.8f, u[0], 1e-6f);
        assertEquals(12f, u[3], 1e-6f);
        final String why = read("src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/WhyCombine.java");
        assertTrue(why.contains("if (hlLocal[0] > 0f) glProg.setDefine(\"HL_LOCAL\", 1);"));
        assertTrue(why.contains("glProg.setVar(\"hlLocalU\", hlLocal[0] * nightFade, hlLocal[1], hlLocal[2], hlLocal[3]);"));
    }

    /** P607 round 3: at night HL_LOCAL and the hr detail fade with WhyAe's night weight (scam_dev hybrid_why_hl_local_night). */
    @Test public void theHighlightDetailFadesAtNight() throws IOException {
        assertEquals(1f, WhyTone.hlLocalNight(), 1e-6f);
        final String why = read("src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/WhyCombine.java");
        assertTrue(why.contains("final float nightFade = 1f - Math.max(0f, Math.min(1f, r.nightW)) * WhyTone.hlLocalNight();"));
        assertTrue("the hr detail fades too", why.contains("hrTone[2] * nightFade"));
        assertTrue("HL_LOCAL stays compiled: a day shot is as before", why.contains("if (hlLocal[0] > 0f) glProg.setDefine(\"HL_LOCAL\", 1);"));
    }
}
