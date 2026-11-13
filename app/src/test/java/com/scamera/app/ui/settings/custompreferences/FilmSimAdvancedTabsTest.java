package com.scamera.app.ui.settings.custompreferences;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.processing.color.FilmAdvanced;
import com.scamera.app.processing.color.FilmSim;
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
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * P487: the film page's «DIR», «Ореол» and «Проявка» tabs - the switches are the amounts, a row shows only when it acts, every
 * slider and choice stores its own key.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class FilmSimAdvancedTabsTest {
    private MockedStatic<ScameraApp> app;
    private FilmSimPreference page;
    private SharedPreferences prefs;
    private View root;

    @Before public void setup() {
        final Context c = RuntimeEnvironment.getApplication();
        final SettingsManager settings = new SettingsManager(c);
        app = mockStatic(ScameraApp.class);
        app.when(ScameraApp::getAppContext).thenReturn(c);
        app.when(ScameraApp::getSettingsManagerStatic).thenReturn(settings);
        app.when(ScameraApp::getResourcesStatic).thenReturn(c.getResources());
        app.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(i -> c.getString(i.<Integer>getArgument(0)));
        app.when(ScameraApp::getSettings).thenReturn(mock(com.scamera.app.api.Settings.class));
        PreferenceKeys.initialise(settings);
        final PreferenceManager pm = new PreferenceManager(c);
        prefs = pm.getSharedPreferences();
        prefs.edit().clear().putString(FilmSim.STOCK_KEY, "kodak_portra_400").commit();
        final PreferenceScreen screen = pm.createPreferenceScreen(c);
        pm.setPreferences(screen);
        page = new FilmSimPreference(c);
        page.setKey(FilmSim.STOCK_KEY);
        screen.addPreference(page);
        page.previewWorker = r -> { };
        root = ReflectionHelpers.callInstanceMethod(page, "build", ReflectionHelpers.ClassParameter.from(Context.class, c));
    }

    @After public void finish() {
        FilmSimPreference.tab = 0;
        app.close();
    }

    private void show(int tab) {
        FilmSimPreference.tab = tab;
        ReflectionHelpers.callInstanceMethod(page, "fill");
    }

    private boolean has(String key) { return root.findViewWithTag("film_" + key) != null; }

    private void tap(String tag, int i) {
        root.findViewWithTag(tag + i).performClick();
    }

    @Test public void theDirSwitchIsTheAmountAndShowsItsRowsOnlyWhenOn() {
        show(4);
        assertFalse("off by default (spektrafilm's amount 0)", has(FilmSim.DIR_KEY));
        tap("film_dir_switch_", 1);
        assertEquals("on = spektrafilm's full amount", "100", prefs.getString(FilmSim.DIR_KEY, ""));
        for (String k : new String[]{FilmSim.DIR_KEY, FilmAdvanced.DIR_SPREAD.key, FilmAdvanced.DIR_TAIL.key, FilmAdvanced.DIR_TAIL_WEIGHT.key,
                FilmAdvanced.DIR_SAME.key, FilmAdvanced.DIR_INTER.key}) assertTrue(k, has(k));
        for (String k : FilmAdvanced.DIR_GAMMA_KEYS) assertTrue(k, has(k));
        prefs.edit().putString(FilmSim.DIR_KEY, "40").commit();
        show(4);
        tap("film_dir_switch_", 0);
        assertEquals("0", prefs.getString(FilmSim.DIR_KEY, ""));
        assertFalse(has(FilmAdvanced.DIR_SAME.key));
        tap("film_dir_switch_", 1);
        assertEquals("its amount comes back", "40", prefs.getString(FilmSim.DIR_KEY, ""));
        // without a frame format the inhibitor's spread does nothing: hidden
        prefs.edit().putString(FilmSim.FORMAT_KEY, "off").commit();
        show(4);
        assertFalse(has(FilmAdvanced.DIR_SPREAD.key));
        assertTrue(has(FilmAdvanced.DIR_INTER.key));
    }

    @Test public void theHalationTabShowsTheBoostRowsWithABoost() {
        show(5);
        assertFalse(has(FilmAdvanced.SCATTER.key));
        tap("film_halation_switch_", 1);
        assertEquals("100", prefs.getString(FilmSim.HALATION_KEY, ""));
        for (FilmAdvanced.Param p : new FilmAdvanced.Param[]{FilmAdvanced.SCATTER, FilmAdvanced.SCATTER_SIZE, FilmAdvanced.HALO,
                FilmAdvanced.HALO_SIZE, FilmAdvanced.BOOST}) assertTrue(p.key, has(p.key));
        assertFalse("range / threshold act only with a boost", has(FilmAdvanced.BOOST_RANGE.key));
        prefs.edit().putString(FilmAdvanced.BOOST.key, "1.00").commit();
        show(5);
        assertTrue(has(FilmAdvanced.BOOST_RANGE.key));
        assertTrue(has(FilmAdvanced.PROTECT.key));
    }

    @Test public void theDevelopmentTabStoresItsChoices() {
        show(6);
        for (String k : new String[]{FilmSim.EXPOSURE_KEY, FilmAdvanced.PUSH.key, FilmAdvanced.DEV_GAMMA.key, FilmAdvanced.BLEACH.key})
            assertTrue(k, has(k));
        assertNull("the mode acts only with a push / pull", root.findViewWithTag("film_push_mode_0"));
        assertFalse("the leuco coupling acts only with a bleach bypass", has(FilmAdvanced.LEUCO.key));
        assertTrue("Hanatos 2026 shown chosen by default", root.findViewWithTag("film_spectral_2").isSelected());
        tap("film_spectral_", 1);
        assertEquals("mallett2019", prefs.getString(FilmAdvanced.SPECTRAL_KEY, ""));
        tap("film_spectral_", 0);
        assertEquals("hanatos2025", prefs.getString(FilmAdvanced.SPECTRAL_KEY, ""));
        prefs.edit().putString(FilmAdvanced.PUSH.key, "0.50").putString(FilmAdvanced.BLEACH.key, "0.30").commit();
        show(6);
        assertTrue(has(FilmAdvanced.LEUCO.key));
        tap("film_push_mode_", 1);
        assertEquals("experimental", prefs.getString(FilmAdvanced.PUSH_MODE_KEY, ""));
    }

    @Test public void aBlackAndWhiteFilmHasNoAdvancedRows() {
        FilmSim.Stock bw = null;
        for (FilmSim.Stock s : FilmSim.STOCKS)
            if (!com.scamera.app.processing.color.FilmReferenceParameters.measured(s.id)) { bw = s; break; }
        assertNotNull(bw);
        prefs.edit().putString(FilmSim.STOCK_KEY, bw.id).commit();
        for (int t = 4; t <= 6; t++) {
            show(t);
            assertFalse(has(FilmAdvanced.DIR_SAME.key));
            assertFalse(has(FilmAdvanced.PUSH.key));
            assertNull(root.findViewWithTag("film_halation_switch_1"));
        }
    }
}
