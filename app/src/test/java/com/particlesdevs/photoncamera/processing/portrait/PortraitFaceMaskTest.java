package com.particlesdevs.photoncamera.processing.portrait;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * P512 (owner, 2026-11-05): «Маска лица» in «Портрет» (the face's own exposure, contrast, colour, smoothing and detail on a mask of
 * the face's ellipse and its skin colour) and, with a face found, for the whole frame: «Приоритет лиц в AE», «Нейтрализация
 * скинтона (Face AWB)» and the portrait tone curve.
 */
public class PortraitFaceMaskTest {
    private static String read(String path) throws IOException {
        File f = new File(path);
        if (!f.isFile()) f = new File("app/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static PortraitSkin.Result skin(double hueDeg, double chroma) {
        PortraitSkin.Result r = new PortraitSkin.Result();
        r.count = 1;
        r.samples[0] = 100;
        r.a[0] = (float) (chroma * Math.cos(Math.toRadians(hueDeg)));
        r.b[0] = (float) (chroma * Math.sin(Math.toRadians(hueDeg)));
        r.luma[0] = 0.05f;
        return r;
    }

    @Test public void faceAwbMovesASkinCastTowardsTheNaturalHueOnly() {
        // natural skin (within 6 degrees of 50): nothing
        float[] none = PortraitSkin.faceAwb(skin(47.0, 0.06), 1f);
        assertEquals(0f, none[0], 0f);
        assertEquals(0f, none[1], 0f);
        // a cold skin (hue 30) at full strength lands on the dead zone's edge (44 degrees), never past MAX_AWB
        PortraitSkin.Result cold = skin(30.0, 0.05);
        float[] d = PortraitSkin.faceAwb(cold, 1f);
        double hue = Math.toDegrees(Math.atan2(cold.b[0] + d[1], cold.a[0] + d[0]));
        assertEquals(44.0, hue, 0.5);
        assertTrue(Math.hypot(d[0], d[1]) <= PortraitSkin.MAX_AWB + 1e-6);
        // half the strength: half the way
        float[] half = PortraitSkin.faceAwb(cold, 0.5f);
        assertEquals(d[0] * 0.5f, half[0], 1e-6f);
        // a strong yellow cast is capped
        float[] yellow = PortraitSkin.faceAwb(skin(80.0, 0.12), 1f);
        assertEquals(PortraitSkin.MAX_AWB, Math.hypot(yellow[0], yellow[1]), 1e-5);
        assertEquals(0.05f, PortraitSkin.faceLuma(cold), 1e-6f);
    }

    @Test public void facePriorityInAeKeepsWellExposedFacesAndLiftsABacklitOne() throws IOException {
        final String tone = read("src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/WhyTone.java");
        assertTrue(tone.contains("static final float FACE_LO = 0.09f, FACE_HI = 0.20f;"));
        assertTrue(tone.contains("Math.max(0.5, Math.min(2.0, Math.pow(target / now, priority)))"));
        assertTrue("default 1.0 as the owner's config v3_3", tone.contains("value(\"portrait_ae\", 1f)"));
        assertTrue("never past the scene's noise limit", tone.contains("if (factor > 1f && r.maxGainByNoise > 0f) ae = Math.min(ae, Math.max(r.ae, r.maxGainByNoise));"));
        assertTrue("the portrait curve only with a face and its switch",
                tone.contains("if (!face || !PreferenceKeys.hybridSwitch(\"why_portrait_curve_on\", true)) return curveSamples();"));
    }

    @Test public void theFaceMaskIsItsEllipseTimesTheSkinColour() throws IOException {
        final String c = read("src/main/assets/shaders/why/combine.glsl");
        assertTrue(c.contains("#define FACE_MASK 0\n"));
        assertTrue(c.contains("#define FACE_AWB 0\n"));
        assertTrue("the mask before Face AWB moves the skin", c.indexOf("float faceM = textureLod(FaceMask,") < c.indexOf("lab.yz += faceAwbU * lab.x;"));
        // the mask's own pass: the centred ellipse, its always-full core, the skin's colour and lightness, a cross-bilateral blur
        final String m = read("src/main/assets/shaders/why/facemask.glsl");
        assertTrue(m.contains("vec2 p = (uv - 0.5 * (b.xy + b.zw)) / (0.5 * (b.zw - b.xy) * 1.15 * max(faceMaskU.x, 0.1));"));
        assertTrue(m.contains("float core = 1.0 - smoothstep(0.25, 0.50, dot(p, p));"));
        assertTrue(m.contains("skin *= 1.0 - smoothstep(0.38, 0.60, abs(lab.x - faceLightU[i]) / faceLightU[i]);"));
        assertTrue(m.contains("float tol = max(0.06 * centre.g, 0.004);"));
        assertTrue(c.contains("delta *= max(1.0 - faceBU.y * faceM, 0.0) * (1.0 + faceBU.z * faceM);"));
        final String combine = read("src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/WhyCombine.java");
        assertTrue(combine.contains("final boolean faceShot = r.portraitFace && st.portrait != null && st.portrait.count > 0;"));
        assertTrue(combine.contains("WhyTone.curveSamples(r.portraitFace)"));
        final String prefs = read("src/main/res/xml/preferences.xml");
        assertTrue(prefs.contains("android:key=\"portrait_mask_screen\""));
        assertTrue(prefs.contains("pref_scam_hybrid_why_portrait_ae\" android:title=\"@string/prefs_portrait_ae_title\" android:summary=\"@string/prefs_portrait_ae_summary\" android:defaultValue=\"1\""));
        assertTrue(prefs.contains("pref_scam_hybrid_why_portrait_awb\" android:title=\"@string/prefs_portrait_awb_title\" android:summary=\"@string/prefs_portrait_awb_summary\" android:defaultValue=\"1\""));
        assertTrue(prefs.contains("android:key=\"pref_scam_hybrid_why_portrait_curve_points\""));
    }
}
