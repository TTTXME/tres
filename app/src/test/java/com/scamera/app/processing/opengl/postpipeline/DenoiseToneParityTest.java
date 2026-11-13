package com.scamera.app.processing.opengl.postpipeline;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;

import com.scamera.app.R;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.processing.opengl.nodes.Node;
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
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * P672 (owner, 2026-11-13: «Убедись что шумодавы при переключении тонмапинга остаются одинаковыми»; «если он отличается, то у V1
 * сделай его таким же как у V2»): the noise reduction does not depend on «Why тон» V1 / V2.
 * <ul>
 * <li>the shared stages before the tone (ScamRgb, ScamHdrDenoise - WhyResolveDenoise with the finish SNR, P648c / P666 -, the highlight
 * dezipper, WhyMsDenoise) are added before the version is chosen and none of them reads it: no version symbol in their sources, no
 * row of one version among the preferences they read, the same values of those preferences and the same worker tuning with V1 and
 * V2 chosen - so their output is the same texture for both (host replay: bit for bit);</li>
 * <li>inside the tone V1 handles noise as V2: the same sharpening node (its low-light share of the same finish SNR), V2's
 * bracket_denoise rule, no noise terms in the fusion and the detail transfer at x1 up to SNR 20.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class DenoiseToneParityTest {
    private static final String PKG = "src/main/java/com/scamera/app/processing/opengl/postpipeline/";
    /** The classes of the stages before the tone and what they call for their parameters. */
    private static final String[] SHARED = {"ScamRgb", "ScamHdrDenoise", "WhyResolveDenoise", "DenoiseStats", "PostSnr", "WhyHlDezipper",
            "WhyMsDenoise", "NoiseQuantileGpu", "NoiseMath", "DenoiseStages"};
    /** What would make a stage depend on the version: its preference, its state, its nodes, the shot look's flag, Oldstyle's. */
    private static final Pattern VERSION = Pattern.compile(
            "toneV2|whyToneV2|tone_version|whyV2|WhyV2[A-Za-z]*|OldstyleMode|ShotLook|\\.look\\b|pipeline\\.why\\b");
    private static final Pattern KEYS = Pattern.compile(
            "(hybridValue|hybridSwitch|hybridString|WhyTone\\.value)\\(\\s*\"([a-z0-9_]+)\"");

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

    /** The source without comments (a version named in a comment is no dependence). */
    private static String code(String cls) throws IOException {
        return read(PKG + cls + ".java").replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    private void version(String v) {
        manager.set("default_scope", "pref_scam_hybrid_tone_version", v);
        assertEquals(v.equals("v2"), PreferenceKeys.whyToneV2());
    }

    /** The hybrid keys the shared stages read (hybridValue / hybridSwitch / hybridString "x", WhyTone.value "x" = "why_x"). */
    private static TreeSet<String> sharedKeys() throws IOException {
        final TreeSet<String> keys = new TreeSet<>();
        for (String cls : SHARED) {
            final Matcher m = KEYS.matcher(code(cls));
            while (m.find()) keys.add(m.group(1).equals("WhyTone.value") ? "why_" + m.group(2) : m.group(2));
        }
        return keys;
    }

    @Test public void theSharedStagesDoNotReadTheToneVersion() throws IOException {
        for (String cls : SHARED) {
            final Matcher m = VERSION.matcher(code(cls));
            assertFalse(cls + " reads the tone version: " + (m.find() ? m.group() : ""), VERSION.matcher(code(cls)).find());
        }
    }

    @Test public void theSharedStagesComeBeforeTheToneChoice() throws IOException {
        final String src = read(PKG + "PostPipeline.java");
        final int from = src.indexOf("private void buildNodes()");
        final String body = src.substring(from, src.indexOf("\n    }\n", from));
        final int choice = body.indexOf("whyToneV2(");
        assertTrue(choice > 0);
        for (String node : new String[]{"new ScamRgb()", "new ScamHdrDenoise()", "new WhyHlDezipper(", "new WhyMsDenoise()"}) {
            final int at = body.indexOf(node);
            assertTrue(node + " is added before the version is chosen", at > 0 && at < choice);
        }
    }

    @Test public void theSharedStagesReadNoRowOfOneVersion() throws Exception {
        final Class<?> availability = Class.forName("com.scamera.app.settings.SettingsAvailability");
        final Method v1Only = availability.getDeclaredMethod("v1Only", String.class);
        final Method v2Only = availability.getDeclaredMethod("v2Only", String.class);
        v1Only.setAccessible(true);
        v2Only.setAccessible(true);
        final TreeSet<String> keys = sharedKeys();
        assertTrue(keys.toString(), keys.contains("why_dn_factor") && keys.contains("why_ms_luma") && keys.contains("post_snr"));
        for (String key : keys) {
            final String pref = "pref_scam_hybrid_" + key;
            assertFalse(pref + " is a V1 row", (Boolean) v1Only.invoke(null, pref));
            assertFalse(pref + " is a V2 row", (Boolean) v2Only.invoke(null, pref));
        }
    }

    /** What the shared stages read of the settings, as one snapshot. */
    private Map<String, Object> snapshot(TreeSet<String> keys) {
        final Map<String, Object> s = new LinkedHashMap<>();
        for (String key : keys) {
            s.put(key, PreferenceKeys.hybridValue(key, 12345.5f));
            s.put(key + "?", PreferenceKeys.hybridSwitch(key, true) + "/" + PreferenceKeys.hybridSwitch(key, false));
        }
        s.put("denoise", DenoiseStages.on());
        s.put("ms", WhyMsDenoise.enabled());
        s.put("ms thresholds", Arrays.deepToString(WhyMsDenoise.thresholds(WhyMsDenoise.modelFactor(2e-4f, 2e-6f,
                WhyTone.value("ms_model_scale", 1.5f)))));
        s.put("post snr", PostSnr.on() + " " + PostSnr.shadowTexture() + " " + PostSnr.flatNight(9f, PostSnr.on(), 1e-4f, 1e-6f));
        s.put("resolve noise", Arrays.toString(WhyResolveDenoise.noiseModel(2e-4f, 2e-6f)) + " " + WhyResolveDenoise.vignetteBoost(2e-4f, 2e-6f));
        // the worker's merge (its own denoise stages included) takes the same tuning
        s.put("worker", PreferenceKeys.hybridTuningText());
        s.put("worker denoise", DenoiseStages.workerTuning());
        return s;
    }

    @Test public void theSharedStagesTakeTheSameParametersWithV1AndV2() throws IOException {
        final TreeSet<String> keys = sharedKeys();
        for (boolean changed : new boolean[]{false, true}) {
            if (changed) {
                // the user's own values of the denoise rows: the same for both versions as well
                manager.set("default_scope", "pref_scam_hybrid_why_dn_factor", "0.7");
                manager.set("default_scope", "pref_scam_hybrid_why_ms_luma", "0.5");
                manager.set("default_scope", "pref_scam_hybrid_post_snr", false);
                assertEquals(0.7f, PreferenceKeys.hybridValue("why_dn_factor", 0.4f), 1e-6f);
                assertFalse(PostSnr.on());
            }
            version("v1");
            final Map<String, Object> v1 = snapshot(keys);
            version("v2");
            final Map<String, Object> v2 = snapshot(keys);
            for (String k : v1.keySet()) assertEquals(k + (changed ? " (set by the user)" : ""), v1.get(k), v2.get(k));
        }
    }

    @Test public void insideTheToneV1HandlesNoiseAsV2() throws Exception {
        // the same sharpening node in both tones: its noise gate is the low-light share of the one finish SNR
        final Method v1 = PostPipeline.class.getDeclaredMethod("toneV1"), v2 = PostPipeline.class.getDeclaredMethod("toneV2");
        v1.setAccessible(true);
        v2.setAccessible(true);
        for (Method tone : new Method[]{v1, v2}) {
            final List<String> names = new ArrayList<>();
            for (Node n : (Node[]) tone.invoke(null)) names.add(n.getClass().getSimpleName());
            assertTrue(tone.getName() + ": " + names, names.contains("WhyLumaSharpen"));
            for (String dn : new String[]{"ScamHdrDenoise", "WhyMsDenoise", "WhyHlDezipper"})
                assertFalse(tone.getName() + " runs a shared denoise stage again: " + names, names.contains(dn));
        }
        // bracket_denoise: V2's rule (WhyV2Highlights.bracketOn), the same setting
        for (float clip : new float[]{0.9f, 1.05f, 1.06f, 8f})
            for (float setting : new float[]{0f, 0.0005f, 0.4f, 1f})
                assertEquals(clip + " / " + setting, WhyV2Highlights.bracketOn(clip, setting) ? setting : 0f,
                        WhyFusion.bracketDenoise(clip, setting), 0f);
        // the fusion takes no noise model (V2's has no noise terms)
        for (String v : new String[]{"v1", "v2"}) {
            version(v);
            assertFalse(WhyFusion.noiseModel());
        }
        // the detail transfer at x1 (V2's) wherever the shot's noise matters: up to SNR 20
        for (float snr : new float[]{Float.NaN, 2f, 5f, 8f, 12f, 15f, 18f, 20f}) {
            assertEquals("SNR " + snr, 1f, WhyCombine.detailBoost(snr), 0f);
            assertEquals("SNR " + snr, 1f, WhyCombine.detailGain(1f, snr, null), 0f);
        }
    }
}
