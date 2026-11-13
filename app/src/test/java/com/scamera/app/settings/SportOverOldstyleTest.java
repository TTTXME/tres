package com.scamera.app.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.scamera.app.app.ScameraApp;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * P638: «Спорт» has a switch of its own in the settings («Съёмка») and a tile in the shade, both writing pref_motion_mode. In the
 * «Oldstyle» mode they left both mode flags on: the viewfinder's switch showed «Oldstyle» (its manual row, its tone), the shots
 * were «Спорт»'s. MotionMode.settleWithOldstyle makes it one mode again - «Спорт», as the viewfinder's switch would.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class SportOverOldstyleTest {
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
        MotionMode.takeSettled();
    }

    @After public void tearDown() {
        camera.close();
    }

    @Test public void theSportTileInOldstyleLeavesOldstyle() {
        prefs.edit().putString("pref_manual_isomodel", "400").commit();                 // the photo modes' manual ISO
        assertTrue(OldstyleMode.select(OldstyleMode.Mode.OLDSTYLE, prefs));
        prefs.edit().putString("pref_manual_isomodel", "1600").commit();                // Oldstyle's own
        // the shade's tile / the settings' switch: the flag alone
        manager.set(SettingsManager.SCOPE_GLOBAL, MotionMode.PREF, true);
        assertTrue("the state the tile left: both flags", OldstyleMode.on() && MotionMode.on());

        assertTrue(MotionMode.settleWithOldstyle(prefs));

        assertFalse(OldstyleMode.on());
        assertTrue(MotionMode.on());
        assertEquals(OldstyleMode.Mode.SPORT, OldstyleMode.current());
        assertEquals("the photo modes' manual values are back", "400", prefs.getString("pref_manual_isomodel", null));
        assertTrue("the camera's screen is told once", MotionMode.takeSettled());
        assertFalse(MotionMode.takeSettled());
        // Oldstyle keeps its own values for the next time
        assertTrue(OldstyleMode.select(OldstyleMode.Mode.OLDSTYLE, prefs));
        assertFalse(MotionMode.on());
        assertEquals("1600", prefs.getString("pref_manual_isomodel", null));
    }

    @Test public void oneModeAloneIsLeftAsItIs() {
        assertFalse("«Фото»", MotionMode.settleWithOldstyle(prefs));
        assertTrue(OldstyleMode.select(OldstyleMode.Mode.OLDSTYLE, prefs));
        assertFalse("«Oldstyle» alone", MotionMode.settleWithOldstyle(prefs));
        assertTrue(OldstyleMode.on());
        assertTrue(OldstyleMode.select(OldstyleMode.Mode.SPORT, prefs));
        assertFalse("«Спорт» alone", MotionMode.settleWithOldstyle(prefs));
        assertTrue(MotionMode.on());
        assertFalse(MotionMode.takeSettled());
    }
}
