package com.scamera.app.processing.oldstyle;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.scamera.app.processing.oldstyle.OldstyleStyle.Param;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * P630c «Oldstyle»: the style's model - the palette's two axes and the twelve advanced parameters in the reference's order, their
 * ranges (-100..100, or off / low / medium / high), the uniforms' packing the shader reads, the HUD line - and one maths for the
 * viewfinder and the photo: the GLSL function's uniforms and its two hosts (tools/check_oldstyle_tone.py renders both per parameter).
 */
public class OldstyleStyleTest {
    @Test public void rangesAndOrder() {
        assertEquals(12, OldstyleStyle.ADVANCED.length);
        assertArrayEquals(new Param[]{Param.EXPOSURE, Param.AURA, Param.HALO, Param.GRAIN, Param.WARMTH, Param.HUE, Param.HIGHLIGHTS,
                Param.SHADOWS, Param.WARM_BOOST, Param.COOL_BOOST, Param.SHARPNESS, Param.VIGNETTE}, OldstyleStyle.ADVANCED);
        for (Param p : Param.values()) {
            final boolean discrete = p == Param.AURA || p == Param.HALO || p == Param.GRAIN;
            assertEquals(p.key, discrete, p.discrete);
            assertEquals(discrete ? 0 : -100, p.min());
            assertEquals(discrete ? 3 : 100, p.max());
            assertEquals(p.max(), p.clamp(1000));
            assertEquals(p.min(), p.clamp(-1000));
            assertEquals(1f, p.unit(p.max()), 0f);
        }
        assertEquals(1 / 3f, Param.GRAIN.unit(1), 1e-7f);
        assertEquals(-0.5f, Param.SHADOWS.unit(-50), 0f);
    }

    @Test public void uniformsPacking() {
        final OldstyleStyle s = new OldstyleStyle();
        assertTrue(s.neutral());
        assertArrayEquals(new float[16], s.uniforms(), 0f);
        s.set(Param.TONE, 81).set(Param.COLOR, -100).set(Param.EXPOSURE, 10).set(Param.WARMTH, 20).set(Param.HUE, -30)
                .set(Param.HIGHLIGHTS, 40).set(Param.SHADOWS, -50).set(Param.WARM_BOOST, 60).set(Param.COOL_BOOST, -70)
                .set(Param.SHARPNESS, 80).set(Param.VIGNETTE, -90).set(Param.AURA, 3).set(Param.HALO, 2).set(Param.GRAIN, 1);
        assertFalse(s.neutral());
        assertArrayEquals(new float[]{.81f, -1f, .1f, .2f, -.3f, .4f, -.5f, .6f, -.7f, .8f, -.9f, 1f, 2 / 3f, 1 / 3f, 0f, 0f},
                s.uniforms(), 1e-6f);
        assertEquals("Base: Natural  ·  Tone: 81  ·  Color: -100", s.hud("Base", "Tone", "Color"));
        final OldstyleStyle copy = new OldstyleStyle(s);
        assertEquals(s, copy);
        copy.reset();
        assertTrue(copy.neutral());
        assertFalse(s.neutral());
    }

    @Test public void oneFunctionForViewfinderAndPhoto() throws Exception {
        final String fn = new String(Files.readAllBytes(Paths.get("src/main/assets/shaders/utils/import_oldstyle_adjust.glsl")), StandardCharsets.UTF_8);
        for (String token : new String[]{"vec3 oldstyleStyle(vec3 c, vec2 pos, vec2 size)", "uniform vec4 uOs0;", "uniform vec4 uOs1;",
                "uniform vec4 uOs2;", "uniform vec4 uOs3;", "float tone = uOs0.x, colour = uOs0.y, expo = uOs0.z, warmth = uOs0.w;",
                "float hue = uOs1.x, hl = uOs1.y, sh = uOs1.z, warmB = uOs1.w;", "float coolB = uOs2.x, sharp = uOs2.y, vig = uOs2.z, aura = uOs2.w;",
                "float halo = uOs3.x, grain = uOs3.y;", "longSide / 1600.0", "longSide / 1200.0"})
            assertTrue(token, fn.contains(token));
        assertEquals(1f, OldstyleStyle.sharpStep(1080), 0f);
        assertEquals(1200f, OldstyleStyle.GRAIN_CELLS, 0f);
        final String photo = new String(Files.readAllBytes(Paths.get("src/main/assets/shaders/oldstyle/adjust.glsl")), StandardCharsets.UTF_8);
        assertTrue(photo.contains("#define OS_TAP(off)") && photo.indexOf("#define OS_TAP") < photo.indexOf("#import oldstyle_adjust"));
        final String vf = new String(Files.readAllBytes(Paths.get("src/main/assets/shaders/preview/main_fs.glsl")), StandardCharsets.UTF_8);
        assertTrue(vf.contains("#define OS_TAP(off)") && vf.indexOf("#define OS_TAP") < vf.indexOf("//#oldstyle_adjust"));
        assertTrue(vf.contains("oldstyleStyle(clamp(color.rgb, 0.0, 1.0), floor(gl_FragCoord.xy), resolution)"));
        final String renderer = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/scamera/app/ui/camera/views/viewfinder/MainRenderer.java")), StandardCharsets.UTF_8);
        assertTrue(renderer.contains("\"shaders/utils/import_oldstyle_adjust.glsl\"") && renderer.contains("\"uOs\" + i"));
        final String node = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/scamera/app/processing/opengl/postpipeline/OldstyleStyleNode.java")), StandardCharsets.UTF_8);
        assertTrue(node.contains("glProg.setVar(\"uOs\" + i") && node.contains("useAssetProgram(\"oldstyle/adjust\""));
    }

    /** P630e: the order with the tone - the exposure before it (the TETs), every other parameter after it (its display values). */
    @Test public void exposureBeforeTheToneTheRestAfter() {
        final OldstyleStyle st = new OldstyleStyle().set(OldstyleStyle.Param.EXPOSURE, 100);
        assertEquals(1.5f, st.exposureEv(), 1e-6f);
        assertEquals(-0.75f, new OldstyleStyle().set(OldstyleStyle.Param.EXPOSURE, -50).exposureEv(), 1e-6f);
        assertTrue(st.neutralAfterTone());
        assertEquals(0f, st.uniformsAfterTone()[2], 0f);
        assertEquals(1f, st.uniforms()[2], 0f);
        for (OldstyleStyle.Param p : OldstyleStyle.Param.values()) {
            if (p == OldstyleStyle.Param.EXPOSURE) continue;
            final OldstyleStyle one = new OldstyleStyle().set(p, p.discrete ? 2 : 40);
            assertFalse(p.key, one.neutralAfterTone());
            assertArrayEquals(p.key, one.uniforms(), one.uniformsAfterTone(), 0f);
        }
        // the photo: the tone's plan takes it (ShotLook.Oldstyle), the style node the rest (PostPipeline adds it only for the rest)
        assertTrue(read("src/main/java/com/scamera/app/processing/opengl/postpipeline/OldstyleStyleNode.java")
                .contains("style.uniformsAfterTone()"));
        assertTrue(read("src/main/java/com/scamera/app/processing/opengl/postpipeline/ShotLook.java")
                .contains("params.exposureEv = style.exposureEv();"));
        assertTrue(read("src/main/java/com/scamera/app/processing/opengl/postpipeline/PostPipeline.java")
                .contains("if (!oldstyle.style.neutralAfterTone()) add(new OldstyleStyleNode(oldstyle.style));"));
    }

    static String read(String p) {
        try {
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(p)), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}
