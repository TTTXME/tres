package com.scamera.app.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.processing.opengl.postpipeline.ShotLook;

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
        OldstyleMode.setStyle(com.scamera.app.processing.oldstyle.OldstyleStyle.Param.TONE, 140);
        OldstyleMode.setStyle(com.scamera.app.processing.oldstyle.OldstyleStyle.Param.COLOR, -100);
        OldstyleMode.setStyle(com.scamera.app.processing.oldstyle.OldstyleStyle.Param.GRAIN, 7);
        OldstyleMode.setStyle(com.scamera.app.processing.oldstyle.OldstyleStyle.Param.VIGNETTE, -12);
        final com.scamera.app.processing.oldstyle.OldstyleStyle st = OldstyleMode.style();
        assertEquals(100, st.get(com.scamera.app.processing.oldstyle.OldstyleStyle.Param.TONE));
        assertEquals(-100, st.get(com.scamera.app.processing.oldstyle.OldstyleStyle.Param.COLOR));
        assertEquals(3, st.get(com.scamera.app.processing.oldstyle.OldstyleStyle.Param.GRAIN));
        assertEquals(-12, st.get(com.scamera.app.processing.oldstyle.OldstyleStyle.Param.VIGNETTE));
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
        assertEquals(com.scamera.app.processing.oldstyle.OldstyleAe.DEHAZE_MAX_DARK, shot.params.maxDark);
        assertEquals(0f, shot.params.outSat, 0f);
        assertEquals(1f, shot.params.vibrance, 0f);
        assertEquals(st.exposureEv(), shot.params.exposureEv, 0f);
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

    /**
     * P643 (owner: «Для Oldstyle убери своей AE, используй такой же как и у режима фото»): the mode has no capture of its own.
     * What it still sets is read where the photo mode reads it: no ultrashort and no long frames (its series is one exposure, as
     * the HDR tile off gives it - the plan, the worker's tuning and the frame counts all ask PreferenceKeys.noHdrFrames), and the
     * frame count when the row «Кадров в серии» is set (0: the photo mode's). The shot's snapshot carries no exposure plan.
     */
    @Test public void theCaptureIsThePhotoModesWithItsBracketsOff() throws Exception {
        manager.set(SettingsManager.SCOPE_GLOBAL, "pref_scam_hybrid_bento", "2");
        manager.set(SettingsManager.SCOPE_GLOBAL, "pref_scam_hybrid_shasta", true);
        manager.set(SettingsManager.SCOPE_GLOBAL, "pref_scam_hybrid_zsl_frames", "12");
        OldstyleMode.select(OldstyleMode.Mode.PHOTO, prefs);
        assertFalse(PreferenceKeys.noHdrFrames());
        assertEquals(2f, PreferenceKeys.hybridValue("bento", 0f), 0f);
        assertTrue(PreferenceKeys.hybridSwitch("shasta", false));
        assertEquals(12, PreferenceKeys.getHybridZslFrames());
        OldstyleMode.select(OldstyleMode.Mode.OLDSTYLE, prefs);
        assertTrue(PreferenceKeys.noHdrFrames());
        assertEquals(0f, PreferenceKeys.hybridValue("bento", 2f), 0f);
        assertFalse(PreferenceKeys.hybridSwitch("shasta", true));
        assertTrue(PreferenceKeys.hybridTuningText().contains("bento 0.0"));
        assertEquals("the plan takes no ultrashort frame", 0, com.scamera.app.capture.HybridPlan.bentoMode());
        assertFalse("and no long one", com.scamera.app.capture.HybridPlan.shastaEnabled());
        assertEquals("«Кадров в серии» 0: the photo mode's count", 12, PreferenceKeys.getHybridZslFrames());
        manager.set(SettingsManager.SCOPE_GLOBAL, "pref_scam_hybrid_oldstyle_burst_frames", "9");
        assertEquals(9, PreferenceKeys.getHybridZslFrames());
        assertEquals(9, OldstyleMode.params(false).burstFrames);
        manager.set(SettingsManager.SCOPE_GLOBAL, "pref_scam_hybrid_oldstyle_burst_frames", "2");
        assertEquals("within the photo mode's 4 .. 65", 4, PreferenceKeys.getHybridZslFrames());
        // the rows of the mode's own capture are gone: a value stored under them is read by nobody
        manager.set(SettingsManager.SCOPE_GLOBAL, "pref_scam_hybrid_oldstyle_burst_time", "3");
        manager.set(SettingsManager.SCOPE_GLOBAL, "pref_scam_hybrid_oldstyle_shutter_floor", "125");
        final String xml = new String(Files.readAllBytes(Paths.get("src/main/res/xml/preferences.xml")), StandardCharsets.UTF_8);
        assertFalse(xml.contains("oldstyle_burst_time") || xml.contains("oldstyle_shutter_floor"));
        for (java.lang.reflect.Field f : com.scamera.app.processing.oldstyle.OldstyleAe.Params.class.getFields())
            assertFalse(f.getName(), f.getName().equals("burstSeconds") || f.getName().equals("shutterFloorMs"));
        // back in «Фото»: its own count and brackets again, the Oldstyle row not read
        OldstyleMode.select(OldstyleMode.Mode.PHOTO, prefs);
        assertEquals(12, PreferenceKeys.getHybridZslFrames());
        assertFalse(PreferenceKeys.noHdrFrames());
        assertEquals(2, com.scamera.app.capture.HybridPlan.bentoMode());
        assertTrue(com.scamera.app.capture.HybridPlan.shastaEnabled());
        // the controller has no branch for the mode at the shot, and nothing of the tone's plan reaches the capture
        final String controller = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/scamera/app/capture/CaptureController.java")), StandardCharsets.UTF_8);
        for (String gone : new String[]{"oldstyleShot", "meterOldstyle", "HybridPlan.oldstyle", "OldstyleAe.Capture", "OldstyleAe.meter("})
            assertFalse(gone, controller.contains(gone));
        final String plan = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/scamera/app/capture/HybridPlan.java")), StandardCharsets.UTF_8);
        for (String gone : new String[]{"HybridPlan oldstyle(", "oldstyleCap", "oldstyleFrames", "OLDSTYLE_", "factorise(", "flickerDown("})
            assertFalse(gone, plan.contains(gone));
        assertTrue(plan.contains("static boolean oneExposure() {") && plan.contains("|| com.scamera.app.settings.OldstyleMode.on();"));
        for (java.lang.reflect.Field f : ShotLook.Oldstyle.class.getFields()) assertFalse(f.getName(), f.getName().equals("capture"));
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
        // P630e: the keys of the rules before the supplement stay gone (a value stored under them was of other rules); P630h's
        // rows of the supplement's numbers have keys of their own (OldstyleSettingsTest)
        for (String gone : new String[]{"oldstyle_target", "oldstyle_v1_skip_ratio", "oldstyle_v1_max_ratio", "oldstyle_v2_max_ratio",
                "oldstyle_v2_forgiveness", "oldstyle_v2_max_gain"})
            assertFalse(gone, group.contains("pref_scam_hybrid_" + gone + "\""));
        assertTrue(group.contains("pref_scam_hybrid_oldstyle_dehaze_max_dark\" android:title=\"@string/prefs_scam_hybrid_oldstyle_dehaze_max_dark_title\""
                + " android:summary=\"@string/prefs_scam_hybrid_oldstyle_dehaze_max_dark_summary\" android:defaultValue=\""
                + com.scamera.app.processing.oldstyle.OldstyleAe.DEHAZE_MAX_DARK + "\""));
        assertTrue(group.contains("pref_scam_hybrid_oldstyle_max_gain\" android:title=\"@string/prefs_scam_hybrid_oldstyle_max_gain_title\""
                + " android:summary=\"@string/prefs_scam_hybrid_oldstyle_max_gain_summary\" android:defaultValue=\"16\""));
    }
}
