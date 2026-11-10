package com.particlesdevs.photoncamera.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.ContextThemeWrapper;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * P606 (owner, 2026-11-10: «дай возможность добавлять настройки из линзы в шторку, в шторке можно менять их только на линзе для
 * которой ты добавил, при переключении на другую эта настройка в шторке должна становиться серой и её нельзя будет
 * регулировать»): a lens page's row pinned for its lens - the scope in the pin, live on that lens, greyed and unchanged on
 * another, the lens page's own key written, unpinned, kept over a restart, a deleted lens greyed with its name.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h880dp-mdpi")
public class ShadeLensPinTest {
    /** «Защита ярких цветов»: a slider of the lens page (lens_colour_category), a per-module setting. */
    private static final String GAMUT = "pref_scam_hybrid_colour_gamut";
    /** «Ч/б-уровень из кадра»: a switch of the lens page (lens_levels_category). */
    private static final String BLACK = "pref_raw_black_from_data";
    private static final String PER_LENS = "pref_save_per_lens_settings";
    private static final String[] IDS = {"back0", "back1", "back2", "common"};

    private Context context;
    private SettingsManager manager;
    private SharedPreferences prefs;
    private MockedStatic<ScameraApp> camera;
    private ShadeCatalog catalog;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        clearProfiles();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        com.particlesdevs.photoncamera.api.Settings settings = mock(com.particlesdevs.photoncamera.api.Settings.class);
        camera.when(ScameraApp::getSettings).thenReturn(settings);
        ScameraApp app = mock(ScameraApp.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
        PreferenceKeys.initialise(manager);
        PreferenceKeys.setDefaults(context);
        for (int i = 0; i < 3; i++)
            prefs.edit().putString("module_auto_back" + i, "" + (3 + i)).putString("module_label_back" + i, new String[]{"1×", "0.6×", "3×"}[i])
                    .putBoolean("module_visible_back" + i, true).commit();
        prefs.edit().putString("module_active", "back2").commit();
        // the lens page turns the modules' own settings on before it opens (ModuleActions.openLens)
        perLens(true);
        select("back2");
        catalog = new ShadeCatalog(context, prefs);
    }

    private void perLens(boolean on) {
        prefs.edit().putBoolean(PER_LENS, on).commit();
        PreferenceKeys.profiles().changed(PER_LENS);
    }

    @After
    public void tearDown() {
        clearProfiles();
        if (camera != null) camera.close();
    }

    private void clearProfiles() {
        context.getSharedPreferences("module_profiles_meta", Context.MODE_PRIVATE).edit().clear().commit();
        for (String id : IDS) context.getSharedPreferences("module_profile_v2_" + id, Context.MODE_PRIVATE).edit().clear().commit();
    }

    /** The camera on module {@code slot} (no FULLRES here: its own profile). */
    private void select(String slot) {
        prefs.edit().putString("module_active", slot).commit();
        try {
            PreferenceKeys.profiles().activate(slot);
        } catch (RuntimeException ignored) {
            // per-module settings off: nothing to swap
        }
    }

    @Test
    public void lensRowPinsWithItsLens() {
        // a lens row is not a setting of every lens: the plain shade catalog has none of the lens page
        assertFalse(catalog.isKnown(GAMUT));
        String token = catalog.lensPin("back2", GAMUT);
        assertEquals("lens:back2:" + GAMUT, token);
        assertEquals("back2", ShadeTiles.lensOf(token));
        assertEquals(GAMUT, ShadeTiles.keyOf(token));
        ShadeCatalog.Entry e = catalog.entry(token);
        assertNotNull(e);
        assertEquals(ShadeCatalog.SLIDER, e.kind);
        assertEquals("back2", e.lens);
        assertEquals(GAMUT, e.pref);
        assertTrue(e.title.toString(), e.title.toString().endsWith(" · 3×"));
        assertTrue(e.shortTitle.toString(), e.shortTitle.toString().endsWith(" · 3×"));
        assertTrue(ShadeTiles.toggle(prefs, token));
        assertTrue(ShadeTiles.isPinned(prefs, token));
        assertTrue(ShadeTiles.stored(prefs).contains(token));
        // a switch of the lens page is pinnable too; a row of no lens page and an action are not
        ShadeCatalog.Entry toggle = catalog.entry(catalog.lensPin("back2", BLACK));
        assertNotNull(toggle);
        assertEquals(ShadeCatalog.TOGGLE, toggle.kind);
        assertNull(catalog.lensPin("back2", "raw_crop_screen"));
        assertNull(catalog.lensPin("back2", "pref_no_such_row"));
        assertNull(catalog.lensPin(null, GAMUT));
    }

    @Test
    public void liveOnItsLensGreyAndUnchangedOnAnother() {
        ShadeCatalog.Entry e = catalog.entry(catalog.lensPin("back2", GAMUT));
        assertNull(catalog.unavailable(e));
        catalog.write(e, 40f);
        // the lens page's own key, stored as the lens page's slider stores it
        assertEquals(40, (int) Math.round(PreferenceNumber.read(prefs.getAll().get(GAMUT), -1)));
        assertEquals(40f, catalog.number(e), 0f);

        select("back0");
        String reason = catalog.unavailable(e);
        assertNotNull(reason);
        assertTrue(reason, reason.contains("3×"));
        assertFalse(ShadeCatalog.lensActive(e));
        // the main settings hold back0's profile: the pin writes nothing there, and still shows the 3× lens's value
        Object back0 = prefs.getAll().get(GAMUT);
        catalog.write(e, 77f);
        assertEquals(back0, prefs.getAll().get(GAMUT));
        assertEquals(40f, catalog.number(e), 0f);

        select("back2");
        assertNull(catalog.unavailable(e));
        assertEquals(40, (int) Math.round(PreferenceNumber.read(prefs.getAll().get(GAMUT), -1)));
        catalog.write(e, 55f);
        assertEquals(55, (int) Math.round(PreferenceNumber.read(prefs.getAll().get(GAMUT), -1)));
    }

    @Test
    public void perModuleValueStaysTheLensOwn() {
        ShadeCatalog.Entry e = catalog.entry(catalog.lensPin("back2", GAMUT));
        catalog.write(e, 30f);
        PreferenceKeys.profiles().changed(GAMUT);
        select("back0");
        // the main settings hold back0's profile now (its own gamut); the greyed tile shows the 3× lens's value, unchanged
        prefs.edit().putString(GAMUT, "5").commit();
        PreferenceKeys.profiles().changed(GAMUT);
        assertEquals(30f, catalog.number(e), 0f);
        catalog.write(e, 90f);
        assertEquals("5", prefs.getString(GAMUT, null));
        select("back2");
        assertEquals(30, (int) Math.round(PreferenceNumber.read(prefs.getAll().get(GAMUT), -1)));
        assertEquals(30f, catalog.number(e), 0f);
    }

    /**
     * The modules' own settings turned off since: a profile row would change every lens - greyed, saying so; a sensor row is the
     * module's whatever they are.
     */
    @Test
    public void sharedSettingsGreyProfileRows() {
        ShadeCatalog.Entry e = catalog.entry(catalog.lensPin("back2", GAMUT));
        perLens(false);
        assertEquals(context.getString(R.string.module_row_per_lens_off), catalog.unavailable(e));
        Object before = prefs.getAll().get(GAMUT);
        catalog.write(e, 66f);
        assertEquals(before, prefs.getAll().get(GAMUT));
        ShadeCatalog.Entry fps = catalog.entry(catalog.lensPin("back2", ModuleSensorSettings.prefix("back2") + "rawfps"));
        assertNull(catalog.unavailable(fps));
        perLens(true);
        assertNull(catalog.unavailable(e));
    }

    /**
     * A module's sensor row (pref_sensorconfig_&lt;slot&gt;_…, shared by its FULLRES profiles) pins for the module; a new session
     * reads it, so its write restarts the camera as the lens page's change does (SessionKeys); another module's row is refused.
     */
    @Test
    public void sensorRowPinsForItsModule() {
        final String session = ModuleSensorSettings.prefix("back2") + "rawfps";   // «Частота кадров RAW», a slider
        String token = catalog.lensPin("back2@full200", session);
        assertEquals("lens:back2:" + session, token);
        ShadeCatalog.Entry e = catalog.entry(token);
        assertNotNull(e);
        assertTrue(e.sessionTime);
        assertTrue(SessionKeys.needsNewSession(e.pref));
        assertNull(catalog.unavailable(e));
        catalog.write(e, 30);
        assertEquals(30, (int) Math.round(PreferenceNumber.read(prefs.getAll().get(session), -1)));
        select("back0");
        assertNotNull(catalog.unavailable(e));
        catalog.write(e, 0);
        assertEquals(30, (int) Math.round(PreferenceNumber.read(prefs.getAll().get(session), -1)));
        // a row of back0 on back2's page is no pin of back2's; «Тип сессии камеры» is free text (no value row)
        assertNull(catalog.lensPin("back2", ModuleSensorSettings.prefix("back0") + "rawfps"));
        assertNull(catalog.lensPin("back2", ModuleSensorSettings.prefix("back2") + "sessiontype"));
        // the plain lens row stays per profile
        assertFalse(SessionKeys.needsNewSession(GAMUT));
    }

    @Test
    public void unpinAndRestart() {
        String token = catalog.lensPin("back2", GAMUT);
        ShadeTiles.toggle(prefs, token);
        // a new process: the pin and its lens come back from the settings
        ShadeCatalog again = new ShadeCatalog(context, prefs);
        List<String> tiles = ShadeTiles.load(prefs, again::isKnown);
        assertTrue(tiles.contains(token));
        assertEquals("back2", again.entry(token).lens);
        assertFalse(ShadeTiles.toggle(prefs, token));
        assertFalse(ShadeTiles.isPinned(prefs, token));
        assertFalse(ShadeTiles.load(prefs, again::isKnown).contains(token));
    }

    @Test
    public void goneLensGreysWithItsName() {
        String token = catalog.lensPin("back2", GAMUT);
        ShadeTiles.toggle(prefs, token);
        catalog.entry(token);   // the shade showed it: the name is kept
        prefs.edit().remove("module_auto_back2").remove("module_label_back2").putString("module_active", "back0").commit();
        ShadeCatalog again = new ShadeCatalog(context, prefs);
        assertTrue(ShadeTiles.load(prefs, again::isKnown).contains(token));
        ShadeCatalog.Entry e = again.entry(token);
        assertTrue(e.title.toString(), e.title.toString().endsWith(" · 3×"));
        String reason = again.unavailable(e);
        assertNotNull(reason);
        assertTrue(reason, reason.contains("3×"));
        // removable
        assertFalse(ShadeTiles.toggle(prefs, token));
        assertFalse(ShadeTiles.stored(prefs).contains(token));
    }

    @Test
    public void obsoleteCleanupKeepsLensPins() {
        String token = catalog.lensPin("back2", GAMUT);
        ShadeTiles.toggle(prefs, token);
        SettingsMigration.removeObsolete(prefs);
        assertTrue(ShadeTiles.stored(prefs).contains(token));
    }

    @Test
    public void tokens() {
        assertFalse(ShadeTiles.isLensToken(GAMUT));
        assertFalse(ShadeTiles.isLensToken("lens:"));
        assertFalse(ShadeTiles.isLensToken("lens::" + GAMUT));
        assertFalse(ShadeTiles.isLensToken("lens:back2:"));
        assertTrue(ShadeTiles.isLensToken("lens:back1@full200:" + GAMUT));
        assertEquals("back1@full200", ShadeTiles.lensOf("lens:back1@full200:" + GAMUT));
        assertEquals(GAMUT, ShadeTiles.keyOf(GAMUT));
        assertNull(ShadeTiles.lensOf(GAMUT));
    }
}
