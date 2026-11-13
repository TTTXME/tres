package com.scamera.app.processing.opengl.postpipeline;

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
        final String why = read("src/main/java/com/scamera/app/processing/opengl/postpipeline/WhyCombine.java");
        assertTrue(why.contains("if (hlLocal[0] > 0f) shaderProg.setDefine(\"HL_LOCAL\", 1);"));
        assertTrue(why.contains("shaderProg.setVar(\"hlLocalU\", hlLocal[0] * nightFade, hlLocal[1], hlLocal[2], hlLocal[3]);"));
    }

    /** P607 round 3: at night HL_LOCAL and the hr detail fade with WhyAe's night weight (scam_dev hybrid_why_hl_local_night). */
    @Test public void theHighlightDetailFadesAtNight() throws IOException {
        assertEquals(1f, WhyTone.hlLocalNight(), 1e-6f);
        final String why = read("src/main/java/com/scamera/app/processing/opengl/postpipeline/WhyCombine.java");
        assertTrue(why.contains("final float nightFade = 1f - Math.max(0f, Math.min(1f, r.nightW)) * WhyTone.hlLocalNight();"));
        assertTrue("the hr detail fades too", why.contains("hrTone[2] * nightFade"));
        assertTrue("HL_LOCAL stays compiled: a day shot is as before", why.contains("if (hlLocal[0] > 0f) shaderProg.setDefine(\"HL_LOCAL\", 1);"));
    }

    /**
     * P623 (owner, 2026-11-11, the sun on «Быстрый»: a grey ring round a white disc): the fast merge's measured light above white goes
     * over to the real-headroom shoulder (why/combine HL_MEASURED) only with its valid short frames, no Bento headroom, data above the
     * clip and a real clip; faded at night; scam_dev hybrid_why_hl_measured 0 = off.
     */
    @Test public void theFastMergesMeasuredLightTakesTheRealHeadroomShoulder() throws IOException {
        final String fast = "HYBRID FAST MERGE: Sabre per-frame merge, 30 frames\nHYBRID FAST MERGE HIGHLIGHT: accumulated validity of 2 valid "
                + "short frame(s) applied to the highlight mask; frame 30 passed\n";
        assertEquals(2, WhyCombine.fastShortFrames(fast));
        assertEquals(0, WhyCombine.fastShortFrames(null));
        assertEquals(0, WhyCombine.fastShortFrames("HYBRID FAST MERGE HIGHLIGHT: every short frame rejected"));
        assertEquals(0, WhyCombine.fastShortFrames("HYBRID FAST MERGE HIGHLIGHT: accumulated validity of 0 valid short frame(s)"));
        assertEquals(0, WhyCombine.fastShortFrames("HYBRID BENTO: applied factor=8"));
        final WhyAe.Result r = new WhyAe.Result();
        r.clip = 4f;
        r.gmax = 8.44f;
        assertTrue(WhyTone.hlMeasured());
        assertEquals(1f, WhyCombine.measuredShoulder(r, true, fast, true), 0f);
        assertEquals("scam_dev hybrid_why_hl_measured 0", 0f, WhyCombine.measuredShoulder(r, true, fast, false), 0f);
        assertEquals("no real clip: no kernel ceiling to replace", 0f, WhyCombine.measuredShoulder(r, false, fast, true), 0f);
        assertEquals("no short frames merged", 0f, WhyCombine.measuredShoulder(r, true, "HYBRID FAST MERGE: Sabre", true), 0f);
        r.nightW = 0.25f;
        assertEquals("faded at dusk", 0.75f, WhyCombine.measuredShoulder(r, true, fast, true), 1e-6f);
        r.nightW = 1f;
        assertEquals("off at night (the P607 lights as before)", 0f, WhyCombine.measuredShoulder(r, true, fast, true), 0f);
        r.nightW = 0f;
        r.gmax = 3.9f;
        assertEquals("nothing above the clip", 0f, WhyCombine.measuredShoulder(r, true, fast, true), 0f);
        r.gmax = 8.44f;
        r.hr = 8.44f;
        assertEquals("Bento headroom: HEADROOM_WIDE's", 0f, WhyCombine.measuredShoulder(r, true, fast, true), 0f);
        final String c = read("src/main/assets/shaders/why/combine.glsl");
        assertTrue(c.contains("#define HL_MEASURED 0\n"));
        assertTrue("the light at and below white as before", WhyTone.HL_MEASURED_LO >= 1f);
        assertTrue("round 2's gate (scam_dev hybrid_why_hl_measured_max 0), round 3's brightest channel (1)", c.contains("smoothstep(hlMeasU.x, hlMeasU.y, max(mix(mf, max3(lin), min(hlMeasShoulderU.w, 1.0)), hlMeasSceneU * measScene))\n                * (1.0 - smoothstep(MEAS_SAT.x, MEAS_SAT.y, satLight) * (1.0 - mix(smoothstep(MEAS_HIGH.x, MEAS_HIGH.y, mf), smoothstep(MEAS_HIGH_MAX.x, MEAS_HIGH_MAX.y, max3(lin)), min(hlMeasShoulderU.w, 1.0))));"));
        final String why = read("src/main/java/com/scamera/app/processing/opengl/postpipeline/WhyCombine.java");
        assertTrue(why.contains("if (measured > 0f) shaderProg.setDefine(\"HL_MEASURED\", 1);"));
        // round 2: the neutral measured light lands on white (hlMeasU.w), HL_LOCAL's mean bilinear (HL_LOCAL_SMOOTH); 0 = before
        assertEquals(1f, WhyTone.hlMeasuredLanding(), 0f);
        assertTrue(WhyTone.hlLocalSmooth());
        assertTrue(why.contains("WhyTone.hlMeasuredLanding());"));
        assertTrue(why.contains("if (hlLocal[0] > 0f && WhyTone.hlLocalSmooth()) shaderProg.setDefine(\"HL_LOCAL_SMOOTH\", 1);"));
        assertTrue(c.contains("#define HL_LOCAL_SMOOTH 0\n"));
        assertTrue(c.contains("int corners = 1;"));
        // round 3: the brightest channel shouldered with no saturation gate, the landing on the measured ceiling; 0 = round 2
        assertTrue(WhyTone.hlMeasuredMax());
        final WhyAe.Result m = new WhyAe.Result();
        m.clip = 4f;
        m.gmax = 21.7f;
        assertEquals(21.7f, WhyCombine.measuredCeiling(m, 1f, true), 1e-5f);
        assertEquals("dusk: half way", 12.85f, WhyCombine.measuredCeiling(m, 0.5f, true), 1e-4f);
        assertEquals("round 2", 0f, WhyCombine.measuredCeiling(m, 1f, false), 0f);
        assertEquals("no measured shoulder", 0f, WhyCombine.measuredCeiling(m, 0f, true), 0f);
        m.gmax = 100f;
        assertEquals("at most 16 x the clip", 64f, WhyCombine.measuredCeiling(m, 1f, true), 1e-5f);
        assertTrue(why.contains("Math.max(Math.max(Math.max(r.clip, r.hr), measCeil), 1e-6f)"));
        assertTrue(c.contains("if (hlMeasCeilU > clipCeiling) clipCeiling = hlMeasCeilU;"));
        // round 4: the measured light's own detail above white, by its measured level; 0 = round 3
        assertEquals(0.5f, WhyTone.hlMeasuredDetail(), 0f);
        assertTrue(why.contains("shaderProg.setVar(\"hlMeasDetailU\", st.inputReleased ? 0f : WhyTone.hlMeasuredDetail());"));
        assertTrue(c.contains("float wM = hlMeasU.z * smoothstep(hlMeasU.x, hlMeasU.y, measScene);"));
        // round 6: P349's rim stop lifted where its far side is brighter than the reference (a dark strip between two bright sides)
        assertTrue(WhyTone.rimFar());
        assertTrue(why.contains("if (WhyTone.rimFar()) shaderProg.setDefine(\"RIM_FAR_SIDE\", 1);"));
        assertTrue(c.contains("#define RIM_FAR_SIDE 0\n"));
        assertTrue(c.contains("#if RIM_FAR_SIDE == 1\n    if (bright) rim *= 1.0 - smoothstep(1.0, 1.3, yFar / max(yRef, 0.000001));\n#endif"));
        // round 6 (lens): beside a measured glare streak the light above white fades towards white; 0 = round 5
        assertEquals(0.7f, WhyTone.hlMeasuredGlare(), 0f);
        assertTrue(why.contains("shaderProg.setVar(\"hlMeasGlareU\", st.inputReleased ? 0f : WhyTone.hlMeasuredGlare());"));
        assertTrue(why.contains("shaderProg.setVar(\"hlMeasGlareU\", 0f);"));
        assertTrue(c.contains("if (hlMeasGlareU > 0.0 && fullInputU == 1) {"));
        assertTrue(c.contains("if (measGlareW > 0.0) outRgb = mix(outRgb, vec3(max3(outRgb)), measGlareW);"));
        // round 5: the shoulder blends in by the light's own measured level too; 0 = round 4
        assertTrue(WhyTone.hlMeasuredScene());
        assertTrue(why.contains("shaderProg.setVar(\"hlMeasSceneU\", WhyTone.hlMeasuredScene() ? 1f : 0f);"));
    }
}
