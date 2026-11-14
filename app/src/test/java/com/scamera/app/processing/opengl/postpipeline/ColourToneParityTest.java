package com.scamera.app.processing.opengl.postpipeline;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;

import com.scamera.app.R;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.SettingsManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * P676 (owner, 2026-11-13: «Да, сделай жёлтый в V1 как в V2. Причём не только в этой сцене, а везде»): «Why тон» V1 renders the colour
 * after its tone by V2's rule (why/combine V2_COLOUR, WhyTone.v2Colour), its luminance its own.
 * <ul>
 * <li>by default V1 takes V2's rule and its own colour stages without a V2 counterpart are off (the yellow turn, the light warm tones,
 * the light-tone chroma of AgX, pale lights to white, the path to white, the darkening detail's chroma); scam_dev
 * hybrid_why_v2_colour 0 brings each back at its own key and default;</li>
 * <li>the vibrance V1 hands its shader is V2's (the same rows, the same defaults, the user's values alike), the AgX inset's strength
 * too, and both tones read the colour through WhyTone.setColour;</li>
 * <li>the shader's V2_COLOUR branch is tonemap_final's rule (the hue lock by the blue share, the per-channel clip before and after
 * the detail), the photo and the viewfinder set it, and neither takes the signed hue with it.</li>
 * </ul>
 * The host replay (the pool burst, five more bursts and a 288-patch chart through the app's whole chain): V1 against V2 at equal
 * lightness, mean hue error 0.2 deg / chroma 0.9 % on the chart (before: 5.0 deg / 5.5 %), the pool's yellow slide -0.6 deg / +0.2 %
 * (before: -6.4 deg / -8.2 %).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ColourToneParityTest {
    private static final String PKG = "src/main/java/com/scamera/app/processing/opengl/postpipeline/";

    private SettingsManager manager;
    private MockedStatic<ScameraApp> camera;

    @Before public void setUp() {
        final Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        manager = new SettingsManager(context);
        manager.getDefaultPreferences().edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        PreferenceKeys.initialise(manager);
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        final ScameraApp app = mock(ScameraApp.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
    }

    @After public void tearDown() {
        PreferenceKeys.endShotProfile();
        if (camera != null) camera.close();
    }

    private static String read(String path) throws IOException {
        File f = new File(path);
        if (!f.exists()) f = new File("app/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private void set(String key, String value) {
        manager.set("default_scope", "pref_scam_hybrid_" + key, value);
    }

    @Test public void v1TakesV2sColourRuleByDefault() {
        assertTrue(WhyTone.v2Colour());
        // the yellow turn asked for explicitly (it is on by default on vivo only): still off with V2's rule
        set("why_yellow_hue", "1");
        assertEquals(0f, WhyTone.yellowHue(), 0f);
        assertEquals(0f, WhyTone.hlNeutral(), 0f);
        assertEquals(0f, WhyTone.lightWarm()[0], 0f);
        assertEquals(0f, WhyTone.lightChroma(), 0f);
        assertEquals(0f, WhyTone.hlWhite(), 0f);
        assertEquals(0f, WhyTone.deltaChroma(), 0f);
    }

    @Test public void theDevKeyBringsV1sOwnColourBack() {
        set("why_v2_colour", "0");
        assertFalse(WhyTone.v2Colour());
        assertEquals(0f, WhyTone.yellowHue(), 0f);   // off by default off vivo, as before
        set("why_yellow_hue", "1");
        assertEquals(1f, WhyTone.yellowHue(), 0f);
        assertEquals(1f, WhyTone.hlNeutral(), 0f);
        assertEquals(1f, WhyTone.lightWarm()[0], 0f);
        assertEquals(0.5f, WhyTone.lightWarm()[3], 0f);
        assertEquals(1f, WhyTone.lightChroma(), 0f);
        assertEquals(0.15f, WhyTone.hlWhite(), 0f);
        assertEquals(1f, WhyTone.deltaChroma(), 0f);
        // the user's own values of the rows act again
        set("why_hl_white", "0.4");
        set("why_delta_chroma", "0.5");
        assertEquals(0.4f, WhyTone.hlWhite(), 1e-6f);
        assertEquals(0.5f, WhyTone.deltaChroma(), 1e-6f);
        set("why_v2_colour", "1");
        assertEquals(0f, WhyTone.hlWhite(), 0f);
        assertEquals(0f, WhyTone.deltaChroma(), 0f);
    }

    @Test public void theRowsWithoutV2sCounterpartSayWhy() {
        final java.util.Map<String, Object> values = new java.util.HashMap<>();
        values.put("pref_scam_hybrid_tone_version", "v1");
        for (String key : new String[]{"pref_scam_hybrid_why_hl_white", "pref_scam_hybrid_why_delta_chroma"}) {
            final String reason = new com.scamera.app.settings.SettingsAvailability(values).v1V2Colour(true).reason(key);
            assertTrue(key + ": " + reason, reason != null && reason.contains("hybrid_why_v2_colour"));
            assertEquals(key, null, new com.scamera.app.settings.SettingsAvailability(values).v1V2Colour(false).reason(key));
        }
        // the other rows of the tone as before
        assertEquals(null, new com.scamera.app.settings.SettingsAvailability(values).v1V2Colour(true).reason("pref_scam_hybrid_why_hl_soft"));
        assertTrue(com.scamera.app.settings.DeviceAvailability.of(values).reason("pref_scam_hybrid_why_hl_white") != null);
    }

    /** V1's vibrance uniforms (why/combine WHY_VIBRANCE) against V2's tonemap_final parameters (WhyV2Stats.settings). */
    private static void assertVibranceAsV2() {
        final WhyV2Ae.Params p = WhyV2Stats.settings();
        final float[] why = WhyTone.vibranceWhyUniform(), skin = WhyTone.vibranceSkinUniform();
        assertTrue(WhyTone.whyVibranceOn());
        assertEquals("vibrance", p.vibStrength, WhyTone.value("vibrance", 0f), 0f);
        assertEquals("sky", p.vibSky, WhyTone.value("vibrance_sky", 0.25f), 0f);
        assertEquals("green", p.vibGreen, WhyTone.value("vibrance_green", 0.05f), 0f);
        assertEquals("saturated", p.vibSaturated, why[0], 0f);
        assertEquals("light tones", p.vibHl, why[1], 1e-7f);
        assertEquals("mid-tones", p.vibMid, why[2], 0f);
        assertEquals("shadows", p.vibShadow, why[3], 0f);
        assertEquals("skin protection", p.skinProtect, skin[0], 0f);
        // the skin centre: the menu's 41.2 deg against the reference config's 41.25 (V2's own row, WhyV2Stats.OWN)
        assertEquals("skin centre", p.skinHueCenterRad(), skin[1], (float) Math.toRadians(0.1));
        assertEquals("skin luminance", p.skinLumaMin, skin[2], 0f);
        assertEquals("skin saturation", p.skinSatShift, skin[3], 0f);
        assertEquals("skin hue", p.skinHueShift, WhyTone.vibranceSkinHue(), 0f);
        assertEquals("AgX inset", p.agxInset / 100f, WhyTone.insetStrength(), 1e-6f);
    }

    @Test public void theVibranceIsV2s() {
        assertVibranceAsV2();
        // the user's values: the merged rows reach both tones alike
        set("why_vibrance", "0.2");
        set("why_vibrance_sky", "0.4");
        set("why_vibrance_green", "0.1");
        set("why_vibrance_saturated", "0.15");
        set("why_vibrance_hl_str", "0.5");
        set("why_vibrance_mid", "0.1");
        set("why_vibrance_shadows", "-0.1");
        set("why_skin_protect", "0.6");
        set("why_skin_luma_min", "0.2");
        assertVibranceAsV2();
    }

    @Test public void theShaderRuleIsV2s() throws IOException {
        final String combine = read("src/main/assets/shaders/why/combine.glsl"), v2 = read("src/main/assets/shaders/why/v2_tonemap.glsl");
        // V2: the lock by the blue share, the per-channel clip of the graded colour and of the final colour
        assertTrue(v2.contains("float blue_weight = clamp(-in_hue_dir.y, 0.0, 1.0);")
                && v2.contains("post_oklab_a = mix(post_oklab_a, agx_chroma * in_hue_dir.x, blue_weight);"));
        assertTrue(v2.contains("graded_lin = clamp(rgb_out, 0.0, 1.0);"));
        assertTrue(v2.contains("clamp(+4.0767416621 * p_l3 - 3.3077115913 * p_m3 + 0.2309699292 * p_s3, 0.0, 1.0)"));
        assertTrue(v2.contains("rgb_out = vec3(out_luma) + (rgb_out - vec3(out_luma)) * eff_sat;"));
        // V1 with V2_COLOUR: the same rule (0 by default in the file: the shader before P676 for every other caller)
        assertTrue(combine.contains("#define V2_COLOUR 0\n"));
        assertTrue(combine.contains("    if (look != 3 && inChroma > 0.000001) post.yz = mix(post.yz, length(post.yz) * hueDir, clamp(-hueDir.y, 0.0, 1.0));"));
        assertTrue(combine.contains("        post.yz = mix(post.yz, locked, clamp(-hueDir.y, 0.0, 1.0));   // P676"));
        assertTrue(combine.contains("    graded = clamp(graded, 0.0, 1.0);\n#else"));
        assertTrue(combine.contains("    graded = clamp(fromOklab(post), 0.0, 1.0);   // P676"));
        assertTrue(combine.contains("graded = vec3(dot(graded, LUMA)) + (graded - vec3(dot(graded, LUMA))) * agxA.w;"));
        assertTrue(combine.contains("    scene = max(fromOklab(lab), vec3(0.0));   // P676"));
        // the photo and the viewfinder set it, without the signed hue; the path to white and the delta's chroma from WhyTone
        final String why = read(PKG + "WhyCombine.java"), live = read(PKG + "LiveWhyTone.java");
        assertTrue(why.contains("if (WhyTone.v2Colour()) shaderProg.setDefine(\"V2_COLOUR\", 1);"));
        assertTrue(why.contains("shaderProg.setVar(\"signedHueU\", WhyTone.signedHue() && !WhyTone.v2Colour() ? 1 : 0);"));
        assertTrue(why.contains("shaderProg.setVar(\"hlWhiteU\", WhyTone.hlWhite());")
                && why.contains("shaderProg.setVar(\"deltaChromaU\", WhyTone.deltaChroma());"));
        assertTrue(live.contains("if (v2Colour) defs.add(new String[]{\"V2_COLOUR\", \"1\"});") && live.contains("v2Colour = WhyTone.v2Colour();"));
        assertTrue(live.contains("set(p, \"signedHueU\", WhyTone.signedHue() && !v2Colour ? 1 : 0);"));
        assertTrue(live.contains("hlWhite = WhyTone.hlWhite();") && live.contains("deltaChroma = WhyTone.deltaChroma();"));
        assertFalse(why.contains("WhyTone.value(\"hl_white\"") || live.contains("WhyTone.value(\"hl_white\"")
                || why.contains("WhyTone.value(\"delta_chroma\"") || live.contains("WhyTone.value(\"delta_chroma\""));
        // one colour matrix for both tones
        assertTrue(why.contains("WhyTone.setColour(shaderProg, pipeline, gainMap, st.inScale);"));
        assertTrue(read(PKG + "WhyV2Stats.java").contains("WhyTone.setColour(shaderProg, pipeline, gainMap, st.inScale);"));
    }
}
