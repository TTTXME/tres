package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * P506 (owner, 2026-11-05, sunsets beside the reference app): why/combine LIGHT_WARM - with the own curve (TONE_OP 1) the light
 * tones of an orange-yellow colour keep the curve's own hue and more chroma instead of the locked orange (a peach-white aureole round
 * the sun). Off by define the shader is the one before; the photo and the RAW viewfinder set it alike, never with AgX (LIGHT_CHROMA).
 * And the Quad fast merge's period-4 notch round recovered lights (scam-fast-gpu.h kFastOutput, u_quad_lift).
 */
public class WhyLightWarmTest {
    private static String read(String path) throws IOException {
        File f = new File(path);
        if (!f.isFile()) f = new File("app/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test public void theOwnCurveKeepsWarmLightTonesYellow() throws IOException {
        final String src = "src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/";
        final String shader = read("src/main/assets/shaders/why/combine.glsl");
        assertTrue(shader.contains("#define LIGHT_WARM 0\n"));
        assertTrue(shader.contains("#if LIGHT_WARM == 1\nuniform vec4 lightWarmU;"));
        // the lock is untouched without the define
        // nested #if, not #elif: tools/check_colour_output.py's preprocessor has no #elif (CI 531)
        assertTrue(shader.contains("#else\n#if LIGHT_WARM == 1\n    if (look != 3 && inChroma > 0.000001) post.yz = lightWarm(post, hueDir, xy, size);\n#else\n"
                + "    if (look != 3 && inChroma > 0.000001) post.yz = length(post.yz) * hueDir;\n#endif\n#endif"));
        // skin (under 60 deg) stays locked; the band reaches lower only near a light «Inpaint opposed» rebuilt
        assertTrue(shader.contains("smoothstep(mix(62.0, 40.0, near), mix(70.0, 50.0, near), h)"));
        assertTrue(shader.contains("#if HL_OPPOSED == 1\n    near = smoothstep(0.05, 0.35, textureLod(OppShare,"));
        final String why = read(src + "WhyCombine.java"), live = read(src + "LiveWhyTone.java");
        assertTrue(why.contains("toneOp != com.particlesdevs.photoncamera.processing.color.ToneCurve.AGX ? WhyTone.lightWarm() : null"));
        assertTrue(why.contains("glProg.setDefine(\"LIGHT_WARM\", 1);"));
        assertTrue(why.contains("glProg.setVar(\"lightWarmU\", lightWarm[0], lightWarm[1], lightWarm[2], lightWarm[3]);"));
        assertTrue(live.contains("if (toneOp != ToneCurve.AGX && lightWarm != null && lightWarm[0] > 0f) defs.add(new String[]{\"LIGHT_WARM\", \"1\"});"));
        assertTrue(live.contains("loc(p, \"lightWarmU\")"));
        assertTrue("on by default", read(src + "WhyTone.java").contains("value(\"light_warm\", 1f)"));
    }

    @Test public void theQuadMergeNotchesTheLatticeRoundRecoveredLights() throws IOException {
        final String gpu = read("src/main/cpp/scam-fast-gpu.h"), merge = read("src/main/cpp/scam-fast-merge.h");
        assertTrue(gpu.contains("uniform float u_quad_lift;"));
        assertTrue(gpu.contains("if(u_quad_lift>0.0)v=quadDeLattice(p,v);"));
        // only a Quad stream with short frames merged: plain Bayer output is the same
        assertTrue(merge.contains("const bool quadLift=quad&&!std::getenv(\"SCAM_FM_NO_QUAD_LIFT\");"));
        assertTrue(merge.contains("g.u1f(A::pOutput,\"u_quad_lift\",quadLift&&validShorts>0?1.f:0.f);"));
    }

    @Test public void aSunWithoutShortFramesBloomsNearWhiteOnly() throws IOException {
        final String src = "src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/";
        assertTrue(read("src/main/assets/shaders/scamhdr/suncore.glsl").contains("if ((f & 7) == 7 && ((f & 32) != 0 || noShortU != 0)) c++;"));
        assertTrue(read("src/main/assets/shaders/scamhdr/suncore_cs.glsl").contains("((f & 32) != 0 || noShortU != 0)"));
        assertTrue(read(src + "ScamRgb.java").contains("return !ScamHybridBurst.lastBentoApplied&&PreferenceKeys.hybridSwitch(\"sun_bloom_no_short\",true);"));
        assertTrue(read(src + "WhyTone.java").contains("ScamRgb.noShortBloom() ? new float[]{0.80f, 0.95f} : new float[]{0.55f, 0.85f}"));
        final String combine = read("src/main/assets/shaders/why/combine.glsl");
        assertTrue("unset: P349's gate", combine.contains("vec2 sg = sunGateU.y > sunGateU.x ? sunGateU : vec2(0.55, 0.85);"));
    }

    @Test public void theOpposedRimGoesYellowWhiteNotLime() throws IOException {
        final String s = read("src/main/assets/shaders/scamhdr/scamrgb.glsl");
        assertTrue(s.contains("tY.g = max(r.g, 0.88 * r.r + 0.12 * tY.b);"));
        assertTrue(s.contains("if (clipped.g && oppWarmU > 0.0) {"));
        assertTrue(read("src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/ScamRgb.java")
                .contains("PreferenceKeys.hybridValue(\"highlight_opposed_warm\",1f)"));
    }
}
