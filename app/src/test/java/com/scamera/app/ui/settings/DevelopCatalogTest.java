package com.scamera.app.ui.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.processing.color.FilmLut;
import com.scamera.app.processing.color.FilmSim;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.PreferenceValue;
import com.scamera.app.settings.SettingsManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P665: the camera settings the gallery developer offers (DevelopCatalog): the groups in the camera's order (Тон with V1 / V2 ...
 * Склейка), Oldstyle only for a shot of that mode, every processing page of the settings in them, the merge's rows greyed, and
 * each offered row's change reaching the setting the camera's stages read (PreferenceValue under the develop's overlay, and the
 * typed getters the nodes call).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w400dp-h880dp-mdpi")
public class DevelopCatalogTest {
    private Context context;
    private MockedStatic<ScameraApp> camera;
    private SettingsManager manager;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        manager = new SettingsManager(context);
        manager.getDefaultPreferences().edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        final ScameraApp app = mock(ScameraApp.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
        PreferenceKeys.initialise(manager);
    }

    @After public void tearDown() {
        PreferenceValue.overlay(null, null);
        if (camera != null) camera.close();
    }

    private static DevelopCatalog.Group group(List<DevelopCatalog.Group> gs, String id) {
        for (DevelopCatalog.Group g : gs) if (g.id.equals(id)) return g;
        return null;
    }

    private static DevelopCatalog.Row row(List<DevelopCatalog.Group> gs, String key) {
        for (DevelopCatalog.Group g : gs) for (DevelopCatalog.Row r : g.rows) if (r.key.equals(key)) return r;
        return null;
    }

    @Test public void theGroupsAreTheCamerasInItsOrder() {
        final List<DevelopCatalog.Group> gs = DevelopCatalog.groups(context, new HashMap<>());
        assertEquals(Arrays.asList(DevelopCatalog.TONE, DevelopCatalog.LIGHTS, DevelopCatalog.SHARP, DevelopCatalog.NOISE,
                DevelopCatalog.COLOUR, DevelopCatalog.FILM, DevelopCatalog.LUT, DevelopCatalog.PORTRAIT, DevelopCatalog.MERGE),
                DevelopCatalog.ids(gs));
        assertEquals("Тон", gs.get(0).title);
        // Oldstyle only for a shot taken in that mode
        final Map<String, Object> oldstyle = new HashMap<>();
        oldstyle.put(com.scamera.app.settings.OldstyleMode.PREF, true);
        assertTrue(DevelopCatalog.ids(DevelopCatalog.groups(context, oldstyle)).contains(DevelopCatalog.OLDSTYLE));
        for (DevelopCatalog.Group g : gs) assertFalse(g.id + " has rows", g.rows.isEmpty());
    }

    @Test public void everyProcessingSettingTheOwnerListedIsOffered() {
        final List<DevelopCatalog.Group> gs = DevelopCatalog.groups(context, new HashMap<>());
        // tone V1 / V2 and its pages, AE, HDR shoulder, sky; sharpening with RL and its halo margin; denoise (Resolve, raw detail,
        // chroma / luma, post SNR); colour (CCM, WB, saturation, vibrance, CCT); the film's tray; the LUT
        for (String key : new String[]{"pref_scam_hybrid_tone_version", "pref_scam_hybrid_why_ae_target", "pref_scam_hybrid_why_metering",
                "pref_scam_hybrid_why_highlight_str", "pref_scam_hybrid_why_shadow_str", "pref_scam_hybrid_why_hr_strength",
                "pref_scam_hybrid_why_sharp_gain", "pref_scam_hybrid_why_sharp_usm_amount", "pref_scam_hybrid_why_sharp_rl_halo_margin",
                "pref_scam_hybrid_why_sharp_rl1_amount", "pref_scam_hybrid_why_dn_factor", "pref_scam_hybrid_denoise",
                "pref_scam_hybrid_post_snr", "pref_scam_hybrid_why_dn_luma_revert", "pref_scam_hybrid_why_ms_chroma",
                "pref_scam_hybrid_why_ms_luma", "pref_scam_hybrid_colour_warmth", "pref_scam_hybrid_why_vibrance",
                "pref_scam_hybrid_colour_tint", "pref_scam_hybrid_why_ccm_profile", "pref_scam_hybrid_why_cct_sat",
                "pref_scam_hybrid_highlight_recovery", FilmLut.KEY, FilmLut.STRENGTH_KEY}) {
            final DevelopCatalog.Row r = row(gs, key);
            assertNotNull(key + " offered", r);
            assertNull(key + " acts on a develop", r.fixed);
        }
        for (String key : FilmSim.KEYS) assertNotNull(key + " in Плёнка", row(gs, key));
        assertEquals(FilmSim.KEYS.length, group(gs, DevelopCatalog.FILM).rows.size());
    }

    @Test public void theMergesRowsAreGreyedWithWhy() {
        final List<DevelopCatalog.Group> gs = DevelopCatalog.groups(context, new HashMap<>());
        for (DevelopCatalog.Row r : group(gs, DevelopCatalog.MERGE).rows) assertNotNull(r.key + " greyed", r.fixed);
        // a merge tuning key wherever it sits, and a value fixed at the shot
        for (DevelopCatalog.Group g : gs)
            for (DevelopCatalog.Row r : g.rows) {
                if (PreferenceKeys.mergeTuningKey(r.key)) assertNotNull(r.key, r.fixed);
                if (DevelopCatalog.fixedAtShot(r.key)) assertNotNull(r.key, r.fixed);
            }
        assertTrue(PreferenceKeys.mergeTuningKey("pref_scam_hybrid_cdm"));
        assertFalse(PreferenceKeys.mergeTuningKey("pref_scam_hybrid_why_sharp_gain"));
    }

    @Test public void eachChangeReachesTheSettingTheStagesRead() throws Exception {
        final List<DevelopCatalog.Group> gs = DevelopCatalog.groups(context, new HashMap<>());
        final Set<String> keys = DevelopCatalog.keys(gs);
        assertTrue("several hundred rows", keys.size() > 200);
        final SharedPreferences prefs = manager.getDefaultPreferences();
        int checked = 0;
        for (DevelopCatalog.Group g : gs)
            for (DevelopCatalog.Row r : g.rows) {
                if (r.fixed != null) continue;
                final Object changed;
                switch (r.kind) {
                    case DevelopCatalog.SWITCH: changed = !DevelopCatalog.on(r.def); break;
                    case DevelopCatalog.LIST: changed = r.values.length > 1 ? r.values[r.values.length - 1] : "x"; break;
                    default: changed = r.number(r.max); break;
                }
                final Map<String, Object> values = new HashMap<>();
                values.put(r.key, changed);
                PreferenceValue.overlay(prefs, values);
                try {
                    assertEquals(r.key, changed, PreferenceValue.get(prefs, r.key));
                } finally {
                    PreferenceValue.overlay(null, null);
                }
                checked++;
            }
        assertTrue(checked > 150);
        // the typed getters the nodes call take the develop's value (the sharpening's gain, the film, the LUT's strength)
        final Map<String, Object> v = new HashMap<>();
        v.put("pref_scam_hybrid_why_sharp_gain", "1.7");
        v.put(FilmSim.STOCK_KEY, FilmSim.STOCKS[0].id);
        v.put(FilmLut.STRENGTH_KEY, "40");
        PreferenceValue.overlay(prefs, v);
        try {
            assertEquals(1.7f, PreferenceKeys.hybridValue("why_sharp_gain", 1f), 1e-6);
            final FilmSim.Settings film = FilmSim.current();
            assertNotNull(film);
            assertEquals(FilmSim.STOCKS[0].id, film.stock.id);
            assertEquals(.4f, PreferenceKeys.filmLutStrength(), 1e-6);
        } finally {
            PreferenceValue.overlay(null, null);
        }
    }

    @Test public void valuesAreTheChangeTheShotsOrTheDefault() {
        final List<DevelopCatalog.Group> gs = DevelopCatalog.groups(context, new HashMap<>());
        final DevelopCatalog.Row r = row(gs, "pref_scam_hybrid_why_sharp_gain");
        final Map<String, Object> shot = new HashMap<>();
        final Map<String, Object> changes = new java.util.TreeMap<>();
        assertEquals(r.def, DevelopCatalog.value(r, shot, changes));
        shot.put(r.key, "1.4");
        assertEquals("1.4", DevelopCatalog.value(r, shot, changes));
        changes.put(r.key, "0.8");
        assertEquals("0.8", DevelopCatalog.value(r, shot, changes));
        assertEquals(.8f, DevelopCatalog.number("0.8", 0), 0);
        assertTrue(DevelopCatalog.on("true"));
        assertFalse(DevelopCatalog.on(null));
    }
}
