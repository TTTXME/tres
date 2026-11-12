package com.particlesdevs.photoncamera.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.ShotLook;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * P630 «Oldstyle»: the third camera mode - choosing among «Спорт», «Фото» and «Oldstyle» (one flag at a time), the manual values
 * of the «Про» row kept per mode, the adjustments stored and clamped, the press's snapshot (ShotLook.Oldstyle), the settings group
 * «Oldstyle Тон» right under «Настройки тона» with each version's rows greyed with the other.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class OldstyleModeTest {
    private MockedStatic<ScameraApp> camera;
    private SettingsManager manager;
    private SharedPreferences prefs;

    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        PreferenceKeys.initialise(manager);
    }

    @After public void tearDown() {
        camera.close();
    }

    @Test public void threeModesOneAtATime() {
        assertEquals(OldstyleMode.Mode.PHOTO, OldstyleMode.current());
        assertTrue(OldstyleMode.select(OldstyleMode.Mode.SPORT, prefs));
        assertTrue(MotionMode.on());
        assertEquals(OldstyleMode.Mode.SPORT, OldstyleMode.current());
        assertTrue(OldstyleMode.select(OldstyleMode.Mode.OLDSTYLE, prefs));
        assertFalse("Oldstyle is the photo mode: no «Спорт» with it", MotionMode.on());
        assertTrue(OldstyleMode.on());
        assertEquals(OldstyleMode.Mode.OLDSTYLE, OldstyleMode.current());
        assertFalse("the chosen mode again: nothing to restart", OldstyleMode.select(OldstyleMode.Mode.OLDSTYLE, prefs));
        assertTrue(OldstyleMode.select(OldstyleMode.Mode.PHOTO, prefs));
        assertFalse(OldstyleMode.on());
        assertFalse(MotionMode.on());
    }

    /**
     * P630e (owner: «Sabre при этом у нас должен работать, 1X/1.5X/2X»): the mode keeps the merge's Sabre scales as «Фото» has them -
     * each output setting gives the same output mode and photo size with Oldstyle on as off (12 and 50 MP sensors), and the tone
     * takes the merged grid as it is (OldstyleToneTest: its sizes, memory route and padding).
     */
    @Test public void sabreScalesAsInThePhotoMode() {
        for (String mode : new String[]{"sensor", "1.5x", "2x"}) {
            manager.set(SettingsManager.SCOPE_GLOBAL, "pref_scam_hybrid_output", mode);
            OldstyleMode.select(OldstyleMode.Mode.PHOTO, prefs);
            final String photo = PreferenceKeys.hybridOutputMode();
            final android.graphics.Point p12 = PreferenceKeys.hybridFinalSize(4000, 3000), p50 = PreferenceKeys.hybridFinalSize(8160, 6144);
            OldstyleMode.select(OldstyleMode.Mode.OLDSTYLE, prefs);
            assertTrue(OldstyleMode.on());
            assertEquals(mode, photo);
            assertEquals(mode, PreferenceKeys.hybridOutputMode());
            assertEquals(p12, PreferenceKeys.hybridFinalSize(4000, 3000));
            assertEquals(p50, PreferenceKeys.hybridFinalSize(8160, 6144));
            final int k = mode.equals("2x") ? 2 : 1;
            if (!mode.equals("1.5x")) assertEquals(new android.graphics.Point(4000 * k, 3000 * k), p12);
            else assertEquals(new android.graphics.Point(6000, 4500), p12);
        }
        OldstyleMode.select(OldstyleMode.Mode.PHOTO, prefs);
    }

    @Test public void manualValuesPersistPerMode() {
        prefs.edit().putString("pref_manual_isomodel", "400").putString("pref_manual_shuttermodel", "0.01").commit();
        OldstyleMode.select(OldstyleMode.Mode.OLDSTYLE, prefs);
        assertFalse("Oldstyle starts on Auto", prefs.contains("pref_manual_isomodel"));
        prefs.edit().putString("pref_manual_isomodel", "1600").putString("pref_manual_whitebalancemodel", "5200").commit();
        OldstyleMode.select(OldstyleMode.Mode.SPORT, prefs);
        assertEquals("400", prefs.getString("pref_manual_isomodel", null));
        assertEquals("0.01", prefs.getString("pref_manual_shuttermodel", null));
        assertFalse(prefs.contains("pref_manual_whitebalancemodel"));
        // «Спорт» and «Фото» share the photo modes' values: no swap between them
        OldstyleMode.select(OldstyleMode.Mode.PHOTO, prefs);
        assertEquals("400", prefs.getString("pref_manual_isomodel", null));
        OldstyleMode.select(OldstyleMode.Mode.OLDSTYLE, prefs);
        assertEquals("1600", prefs.getString("pref_manual_isomodel", null));
        assertEquals("5200", prefs.getString("pref_manual_whitebalancemodel", null));
        assertFalse(prefs.contains("pref_manual_shuttermodel"));
    }

    @Test public void styleAndTheShotSnapshot() {
        assertNull("outside the mode no Oldstyle shot", ShotLook.Oldstyle.now());
        OldstyleMode.select(OldstyleMode.Mode.OLDSTYLE, prefs);
        assertTrue(OldstyleMode.style().neutral());
        OldstyleMode.setStyle(com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle.Param.TONE, 140);
        OldstyleMode.setStyle(com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle.Param.COLOR, -100);
        OldstyleMode.setStyle(com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle.Param.GRAIN, 7);
        OldstyleMode.setStyle(com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle.Param.VIGNETTE, -12);
        final com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle st = OldstyleMode.style();
        assertEquals(100, st.get(com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle.Param.TONE));
        assertEquals(-100, st.get(com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle.Param.COLOR));
        assertEquals(3, st.get(com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle.Param.GRAIN));
        assertEquals(-12, st.get(com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle.Param.VIGNETTE));
        // stored for the mode: a new read (the next camera session) has them
        assertEquals("-12", manager.getString(SettingsManager.SCOPE_GLOBAL, OldstyleMode.STYLE + "vignette", "0"));
        ShotLook.Oldstyle shot = ShotLook.Oldstyle.now();
        assertNotNull(shot);
        assertFalse(shot.v2);
        assertEquals(st, shot.style);
        // P630e: the supplement's rules (no ratio rows); the gains' limit and the stages' defaults; the style's exposure in the tone
        assertFalse(shot.params.v31);
        assertEquals(16f, shot.params.maxGain, 0f);
        assertTrue(shot.params.dehaze && shot.params.dehazeShading && shot.params.lowFrequency);
        assertEquals(com.particlesdevs.photoncamera.processing.oldstyle.OldstyleAe.DEHAZE_MAX_DARK, shot.params.maxDark);
        assertEquals(0f, shot.params.outSat, 0f);
        assertEquals(1f, shot.params.vibrance, 0f);
        assertEquals(st.exposureEv(), shot.params.exposureEv, 0f);
        assertNull(shot.capture);
        OldstyleMode.setV2(true);
        shot = ShotLook.Oldstyle.now();
        assertTrue(shot.v2);
        assertTrue(shot.params.v31);
        assertEquals(16f, shot.params.maxGain, 0f);
        manager.set(SettingsManager.SCOPE_GLOBAL, "pref_scam_hybrid_oldstyle_max_gain", "8");
        manager.set(SettingsManager.SCOPE_GLOBAL, "pref_scam_hybrid_oldstyle_dehaze", false);
        manager.set(SettingsManager.SCOPE_GLOBAL, "pref_scam_hybrid_oldstyle_out_sat", "0.3");
        final ShotLook.Oldstyle set = ShotLook.Oldstyle.now();
        assertEquals(8f, set.params.maxGain, 0f);
        assertFalse(set.params.dehaze);
        assertEquals(0.3f, set.params.outSat, 1e-6f);
        assertEquals(1f, set.params.post()[4], 0f);   // the output saturation's stage on once it is not neutral
        assertEquals(0f, set.params.post()[0], 0f);
        assertFalse(shot.postview);
        manager.set(SettingsManager.SCOPE_GLOBAL, "pref_scam_hybrid_oldstyle_v2_levels", "8");
        assertTrue(ShotLook.Oldstyle.now().postview);
        // «Сбросить стиль по умолчанию?»: every parameter back to 0; the snapshot taken before keeps its own
        OldstyleMode.resetStyle();
        assertTrue(OldstyleMode.style().neutral());
        assertFalse(shot.style.neutral());
    }

    private static SettingsAvailability with(String version) {
        final Map<String, Object> values = new HashMap<>();
        if (version != null) values.put("pref_scam_hybrid_oldstyle_version", version);
        return new SettingsAvailability(values);
    }

    @Test public void eachVersionGreysTheOthersRows() {
        final SettingsAvailability v1 = with(null), v2 = with("v2");
        for (String key : OldstyleMode.V1_ROWS) {
            assertNull(key, v1.reason(key));
            assertNotNull(key, v2.reason(key));
            assertTrue(key, v2.reason(key).contains("V1"));
        }
        for (String key : OldstyleMode.V2_ROWS) {
            assertNotNull(key, v1.reason(key));
            assertTrue(key, v1.reason(key).contains("V2"));
            assertNull(key, v2.reason(key));
        }
        for (String key : OldstyleMode.COMMON_ROWS) {
            assertNull(key, v1.reason(key));
            assertNull(key, v2.reason(key));
        }
        assertNull(v1.reason(OldstyleMode.VERSION));
        assertNull(v2.reason(OldstyleMode.VERSION));
    }

    @Test public void theGroupSitsUnderTheToneGroup() throws IOException {
        final String xml = new String(Files.readAllBytes(Paths.get("src/main/res/xml/preferences.xml")), StandardCharsets.UTF_8);
        final int tone = xml.indexOf("<PreferenceScreen android:key=\"tone_settings_screen\"");
        final int old = xml.indexOf("<PreferenceScreen android:key=\"oldstyle_tone_screen\"");
        assertTrue(tone > 0 && old > tone);
        // nothing between the end of «Настройки тона» and the group
        final int toneEnd = xml.lastIndexOf("</PreferenceScreen>", old);
        assertFalse(xml.substring(toneEnd, old).contains("android:key="));
        final String group = xml.substring(old, xml.indexOf("</PreferenceScreen>", old));
        for (String key : OldstyleMode.V1_ROWS) assertTrue(key, group.contains("android:key=\"" + key + "\""));
        for (String key : OldstyleMode.V2_ROWS) assertTrue(key, group.contains("android:key=\"" + key + "\""));
        for (String key : OldstyleMode.COMMON_ROWS) assertTrue(key, group.contains("android:key=\"" + key + "\""));
        assertTrue(group.contains("android:key=\"" + OldstyleMode.VERSION + "\""));
        // P630e: the supplement fixes the plan's ratios - no rows of them, no brightness target; the stages' defaults are the app's
        for (String gone : new String[]{"oldstyle_target", "oldstyle_v1_skip_ratio", "oldstyle_v1_max_ratio", "oldstyle_v2_max_ratio",
                "oldstyle_v2_forgiveness", "oldstyle_v2_max_gain"})
            assertFalse(gone, group.contains("pref_scam_hybrid_" + gone + "\""));
        assertTrue(group.contains("pref_scam_hybrid_oldstyle_dehaze_max_dark\" android:title=\"@string/prefs_scam_hybrid_oldstyle_dehaze_max_dark_title\""
                + " android:summary=\"@string/prefs_scam_hybrid_oldstyle_dehaze_max_dark_summary\" android:defaultValue=\""
                + com.particlesdevs.photoncamera.processing.oldstyle.OldstyleAe.DEHAZE_MAX_DARK + "\""));
        assertTrue(group.contains("pref_scam_hybrid_oldstyle_max_gain\" android:title=\"@string/prefs_scam_hybrid_oldstyle_max_gain_title\""
                + " android:summary=\"@string/prefs_scam_hybrid_oldstyle_max_gain_summary\" android:defaultValue=\"16\""));
    }
}
