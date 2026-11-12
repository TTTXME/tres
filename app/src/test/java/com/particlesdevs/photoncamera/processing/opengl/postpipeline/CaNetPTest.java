package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;

import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsAvailability;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.settings.SettingsMigration;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.SegmentedListPreference;

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
import java.util.HashMap;
import java.util.Map;

/**
 * P493 (owner, 2026-11-03): «Подавление аберраций» - the fringe net (CaNetP v4, P615) or the classic P399, never both, never neither; the
 * net's settings («Настройки нейросети») reach its shader's defines / uniform with the trained values as defaults; the weight
 * asset is P615's v4 (sha256, v3's layer table: weights_v3/weights.json).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class CaNetPTest {
    private Context context;
    private SettingsManager manager;
    private MockedStatic<ScameraApp> camera;

    private static File src(String path) {
        File f = new File(path);
        return f.exists() ? f : new File("app/" + path);
    }

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(src(path).toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Before public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        manager = new SettingsManager(context);
        manager.getDefaultPreferences().edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        PreferenceKeys.initialise(manager);
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
    }

    @After public void tearDown() { if (camera != null) camera.close(); }

    /** Which method runs for each setting. */
    @Test public void routing() {
        final long mp12 = 4000L * 3000, mp50 = 8160L * 6144, mp200 = 16320L * 12288;
        // P399's strength 0 (hybrid_highlight_purple 0, or no per-channel recovery): neither, as before
        for (String m : new String[]{CaNetP.NEURAL, CaNetP.CLASSIC})
            assertEquals(CaNetP.Route.NONE, CaNetP.route(0f, m, mp12, true));
        assertEquals(CaNetP.Route.NEURAL, CaNetP.route(1f, CaNetP.NEURAL, mp12, true));
        assertEquals(CaNetP.Route.NEURAL, CaNetP.route(1f, CaNetP.NEURAL, mp50, true));
        assertEquals(CaNetP.Route.CLASSIC, CaNetP.route(1f, CaNetP.CLASSIC, mp12, true));
        // the net cannot run (weights, programs, size): P399, never neither
        assertEquals(CaNetP.Route.CLASSIC, CaNetP.route(1f, CaNetP.NEURAL, mp12, false));
        assertEquals(CaNetP.Route.CLASSIC, CaNetP.route(1f, CaNetP.NEURAL, mp200, true));
        assertEquals(CaNetP.Route.CLASSIC, CaNetP.route(0.5f, "anything", mp12, false));
        // the stored choice: «Классическое» by default (until the net is proven on the phones), «Нейросеть» when chosen
        assertEquals(CaNetP.CLASSIC, CaNetP.method());
        manager.set("default_scope", "pref_scam_hybrid_fringe_method", "classic");
        assertEquals(CaNetP.CLASSIC, CaNetP.method());
        manager.set("default_scope", "pref_scam_hybrid_fringe_method", "neural");
        assertEquals(CaNetP.NEURAL, CaNetP.method());
        // scamrgb: purpleU only on the classic route, the net after the pass only on its own
        final String rgb = read_("src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/ScamRgb.java");
        assertTrue(rgb.contains("final float p399=fringe==CaNetP.Route.CLASSIC?purple:0f;"));
        assertTrue(rgb.contains("glProg.setVar(\"purpleU\",p399);"));
        assertTrue(rgb.contains("if(fringe==CaNetP.Route.NEURAL){"));
        assertTrue(rgb.contains("if(p399>0f&&!com.particlesdevs.photoncamera.processing.opengl.PostGlMode.legacy()&&PreferenceKeys.scamDevSwitch(\"purple_bound\",true)){"));
    }

    private static String read_(String path) {
        try { return read(path); } catch (IOException e) { throw new AssertionError(e); }
    }

    /** The weight asset: P615's v4 file (the only one shipped), its layout the layer table's. */
    @Test public void weightsAreV4() throws Exception {
        assertEquals("canet/canetp_v4.bin", CaNetP.WEIGHTS);
        assertFalse("v3 is not shipped beside v4", src("src/main/assets/canet/canetp_v3.bin").exists());
        final byte[] blob = Files.readAllBytes(src("src/main/assets/canet/canetp_v4.bin").toPath());
        assertEquals(CaNetP.WEIGHTS_SHA256, CaNetP.sha256(blob));
        assertEquals(2309, CaNetP.totalTexels());
        // weights_v3/weights.json: texels and offsets of c1 .. c6
        final int[] texels = {292, 580, 580, 580, 132, 145}, offsets = {0, 292, 872, 1452, 2032, 2164};
        for (int i = 0, off = 0; i < 6; i++) {
            assertEquals("c" + (i + 1), texels[i], CaNetP.texels(CaNetP.LAYERS[i]));
            assertEquals("c" + (i + 1), offsets[i], off);
            off += texels[i];
        }
        final float[] w = CaNetP.parse(blob);
        assertEquals(4 * 2309, w.length);
        final byte[] bad = blob.clone();
        bad[100] ^= 1;
        try { CaNetP.parse(bad); fail("a changed weight file accepted"); } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("sha256"));
        }
    }

    /** Every row of «Настройки нейросети» reaches its define / uniform; untouched, the trained settings (P615: YB_LO 0.05). */
    @Test public void settingsReachTheShader() throws IOException {
        final String shader = read("src/main/assets/shaders/canet/canet_p.glsl");
        CaNetP.Settings s = CaNetP.Settings.read();
        Map<String, String> gate = s.gateDefines(), apply = s.applyDefines();
        assertEquals("4", gate.get("MODE"));
        assertEquals(205f, Float.parseFloat(gate.get("HUE_LO")), 0f);
        assertEquals("1", gate.get("GREEN"));
        assertEquals("1", gate.get("SRC_RULES"));
        assertEquals(1.5f, Float.parseFloat(gate.get("NEAR_LO")), 0f);
        assertEquals(0.05f, Float.parseFloat(gate.get("YB_LO")), 1e-6f);
        assertEquals("12", gate.get("REACH"));
        assertEquals("3", apply.get("MODE"));
        assertEquals("1", apply.get("BOOST"));
        assertEquals(0.3f, Float.parseFloat(apply.get("BOOST_MIN")), 1e-6f);
        assertEquals("1", apply.get("NO_FLIP"));
        assertEquals(1f, s.strengthUniform(1f), 0f);
        // the shader's own defaults are the same trained values (handoff README: HUE_LO 205, GREEN 1, SRC_RULES 1, NEAR_LO 1.5, BOOST 1,
        // BOOST_MIN 0.3, REACH 12; P615: YB_LO 0.05)
        for (String d : new String[]{"#define HUE_LO 205.0\n", "#define GREEN 1\n", "#define SRC_RULES 1\n", "#define NEAR_LO 1.5\n",
                "#define YB_LO 0.05\n", "#define BOOST 1\n", "#define BOOST_MIN 0.3\n", "#define REACH 12\n", "#define NO_FLIP 1\n"})
            assertTrue(d, shader.contains(d));
        assertTrue(shader.contains("uniform float strengthU;"));
        // GLInterface.readProgram replaces a #define line containing " NAME ": no define line may name another define
        final String[] names = {"MODE", "CIN", "K", "POOL", "UPCAT", "COUT", "GF", "HUE_LO", "GREEN", "SRC_RULES", "BOOST", "BOOST_MIN", "NEAR_LO",
                "YB_LO", "REACH", "NO_FLIP"};
        for (String line : shader.split("\n")) {
            if (!line.contains("#define")) continue;
            int hits = 0;
            for (String n : names) if (line.contains(" " + n + " ")) hits++;
            assertTrue(line, hits <= 1);
        }
        // every row changed: its define / uniform changes
        manager.set("default_scope", "pref_scam_hybrid_fringe_net_strength", "0.4");
        manager.set("default_scope", "pref_scam_hybrid_fringe_net_reach", "6");
        manager.set("default_scope", "pref_scam_hybrid_fringe_net_hue", "230");
        manager.set("default_scope", "pref_scam_hybrid_fringe_net_near", "2.5");
        manager.set("default_scope", "pref_scam_hybrid_fringe_net_light", "0.3");
        manager.set("default_scope", "pref_scam_hybrid_fringe_net_green", false);
        manager.set("default_scope", "pref_scam_hybrid_fringe_net_glow", false);
        manager.set("default_scope", "pref_scam_hybrid_fringe_net_boost", false);
        manager.set("default_scope", "pref_scam_hybrid_fringe_net_noflip", false);
        s = CaNetP.Settings.read();
        gate = s.gateDefines();
        apply = s.applyDefines();
        assertEquals(0.4f, s.strengthUniform(1f), 1e-6f);
        assertEquals(0.2f, s.strengthUniform(0.5f), 1e-6f);   // the old hybrid_highlight_purple scales either method
        assertEquals("6", gate.get("REACH"));
        assertEquals(230f, Float.parseFloat(gate.get("HUE_LO")), 0f);
        assertEquals(2.5f, Float.parseFloat(gate.get("NEAR_LO")), 1e-6f);
        assertEquals(0.3f, Float.parseFloat(gate.get("YB_LO")), 1e-6f);
        assertEquals("0", gate.get("GREEN"));
        assertEquals("0", gate.get("SRC_RULES"));
        assertEquals("0", apply.get("BOOST"));
        assertEquals("0", apply.get("NO_FLIP"));
        for (Map<String, String> d : new Map[]{gate, apply})
            for (String k : d.keySet()) assertTrue(k, shader.contains("#define " + k + " "));
        // out of range: clamped to the rows' bounds
        manager.set("default_scope", "pref_scam_hybrid_fringe_net_hue", "400");
        manager.set("default_scope", "pref_scam_hybrid_fringe_net_strength", "-1");
        s = CaNetP.Settings.read();
        assertEquals(260f, s.hueLo, 0f);
        assertEquals(0f, s.strength, 0f);
    }

    /** The layers' defines: c1 8 inputs, c2 pooled, c5 the upsampled concatenation, c6 the head. */
    @Test public void layerDefines() {
        assertEquals("8", CaNetP.layerDefines(0).get("CIN"));
        assertEquals("1", CaNetP.layerDefines(1).get("POOL"));
        assertEquals("0", CaNetP.layerDefines(2).get("POOL"));
        assertEquals("1", CaNetP.layerDefines(4).get("UPCAT"));
        assertEquals("1", CaNetP.layerDefines(4).get("K"));
        assertEquals("32", CaNetP.layerDefines(4).get("CIN"));
        assertEquals("2", CaNetP.layerDefines(5).get("MODE"));
        assertEquals("2", CaNetP.layerDefines(5).get("COUT"));
        assertEquals(CaNetP.layerDefines(2), CaNetP.layerDefines(3));   // c3 / c4: one program, the dilation a uniform
        assertNotEquals(CaNetP.layerDefines(1), CaNetP.layerDefines(2));
    }

    /** The switch under «Режим обработки», «Нейросеть» by default; the net's page only with it; the rows' defaults = the code's. */
    @Test public void theSwitchAndThePage() {
        SettingsMigration.prepare(context, manager.getDefaultPreferences());
        final PreferenceManager pm = new PreferenceManager(context);
        final PreferenceScreen root = pm.inflateFromResource(context, R.xml.preferences, null);
        assertEquals("pref_scam_hybrid_processing", root.getPreference(0).getKey());
        // P629: the group «Шумоподавление и склейка» sits right under «Режим обработки»; the switch and its page follow it
        // (P638: this class was not in the workflow's list and kept the order of before P629)
        assertEquals("scam_hybrid_dnmerge_screen", root.getPreference(1).getKey());
        final Preference method = root.getPreference(2);
        assertEquals("pref_scam_hybrid_fringe_method", method.getKey());
        assertTrue(method instanceof SegmentedListPreference);
        final SegmentedListPreference list = (SegmentedListPreference) method;
        assertEquals(2, list.getEntryValues().length);
        assertEquals("neural", list.getEntryValues()[0].toString());
        assertEquals("classic", list.getEntryValues()[1].toString());
        final String xmlRoot = read_("src/main/res/xml/preferences.xml");
        assertTrue(xmlRoot.contains("android:entryValues=\"@array/scam_hybrid_fringe_method_values\" android:defaultValue=\"classic\""));
        assertEquals("fringe_net_screen", root.getPreference(3).getKey());
        assertTrue(root.getPreference(3) instanceof PreferenceScreen);
        final PreferenceGroup page = (PreferenceGroup) root.getPreference(3);
        assertEquals(9, page.getPreferenceCount());
        final Map<String, Object> v = new HashMap<>();
        assertTrue(new SettingsAvailability(v).hidden("fringe_net_screen"));
        v.put("pref_scam_hybrid_fringe_method", "neural");
        assertFalse(new SettingsAvailability(v).hidden("fringe_net_screen"));
        v.put("pref_scam_hybrid_fringe_method", "classic");
        assertTrue(new SettingsAvailability(v).hidden("fringe_net_screen"));
        // XML defaults (the stored value untouched) read back as the trained settings
        final CaNetP.Settings s = CaNetP.Settings.read();
        final Map<String, Float> expected = new HashMap<>();
        expected.put("pref_scam_hybrid_fringe_net_strength", s.strength);
        expected.put("pref_scam_hybrid_fringe_net_reach", (float) s.reach);
        expected.put("pref_scam_hybrid_fringe_net_hue", s.hueLo);
        expected.put("pref_scam_hybrid_fringe_net_near", s.nearLo);
        expected.put("pref_scam_hybrid_fringe_net_light", s.ybLo);
        for (int i = 0; i < page.getPreferenceCount(); i++) {
            final Preference p = page.getPreference(i);
            assertNotNull(p.getTitle());
            assertNotNull(p.getSummary());
            assertTrue(p.getKey(), p.getKey().startsWith("pref_scam_hybrid_fringe_net_"));
        }
        final String xml = read_("src/main/res/xml/preferences.xml");
        for (Map.Entry<String, Float> e : expected.entrySet()) {
            final int at = xml.indexOf("android:key=\"" + e.getKey() + "\"");
            assertTrue(e.getKey(), at > 0);
            final int d = xml.indexOf("android:defaultValue=\"", at) + "android:defaultValue=\"".length();
            assertEquals(e.getKey(), e.getValue(), Float.parseFloat(xml.substring(d, xml.indexOf('"', d))), 1e-6f);
        }
        for (String k : new String[]{"green", "glow", "boost", "noflip"}) {
            final int at = xml.indexOf("android:key=\"pref_scam_hybrid_fringe_net_" + k + "\"");
            assertTrue(k, at > 0 && xml.startsWith("android:defaultValue=\"true\"", xml.indexOf("android:defaultValue=", at)));
        }
    }
}
