package com.particlesdevs.photoncamera.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.ContextThemeWrapper;
import androidx.preference.*;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class UnspektraAePreferencesTest {
    private MockedStatic<ScameraApp> app;
    private Context context;
    private SharedPreferences prefs;
    private PreferenceCategory category;
    private String prefix = ModuleSensorSettings.prefix("2");

    @Before public void setup() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_Scamera_SettingsActivity);
        SettingsManager manager = new SettingsManager(context); prefs = manager.getDefaultPreferences(); prefs.edit().clear().commit();
        app = mockStatic(ScameraApp.class);
        app.when(ScameraApp::getAppContext).thenReturn(context);
        app.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        app.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        app.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        PreferenceKeys.initialise(manager); PreferenceKeys.setDefaults(context);
        PreferenceManager pm = new PreferenceManager(context);
        pm.setSharedPreferencesName(context.getPackageName()+"_preferences");
        PreferenceScreen screen = pm.createPreferenceScreen(context); pm.setPreferences(screen);
        category = new PreferenceCategory(context); screen.addPreference(category);
        UnspektraAePreferences.addRows(context,category,"2",null);
    }

    @After public void teardown() { app.close(); }
    private ListPreference engine() { return category.findPreference(prefix+UnspektraAePreferences.ENGINE); }
    private EditTextPreference edit(String key) { return category.findPreference(prefix+key); }

    @Test public void androidDefaultIsModuleScopedAndRowsFollowTheEngine() {
        assertEquals("0",engine().getValue()); assertEquals(6,category.getPreferenceCount());
        assertFalse(edit(UnspektraAePreferences.MIN_ISO).isEnabled());
        assertTrue(engine().callChangeListener("1")); engine().setValue("1");
        assertTrue(edit(UnspektraAePreferences.MIN_ISO).isEnabled());
        assertTrue(UnspektraAePreferences.selected("2")); assertFalse(UnspektraAePreferences.selected("3"));
        UnspektraAePreferences.addRows(context,category,"2",null); assertEquals(6,category.getPreferenceCount());
    }

    @Test public void invalidAndCrossedBoundsAreRejected() {
        EditTextPreference min = edit(UnspektraAePreferences.MIN_ISO), max = edit(UnspektraAePreferences.MAX_ISO);
        max.setText("800"); assertFalse(min.callChangeListener("1600")); assertTrue(min.callChangeListener("100"));
        assertFalse(min.callChangeListener("NaN")); assertFalse(min.callChangeListener("Infinity")); assertFalse(min.callChangeListener("-1"));
        min.setText("200"); assertFalse(max.callChangeListener("100")); assertTrue(max.callChangeListener("0"));
        EditTextPreference fast = edit(UnspektraAePreferences.FAST), slow = edit(UnspektraAePreferences.SLOW);
        assertFalse(fast.callChangeListener("10")); fast.setText("1000");
        assertFalse(slow.callChangeListener("2000")); assertTrue(slow.callChangeListener("60"));
    }

    @Test public void defaultsDoNotMarkTheModuleAndSensorResetRemovesAllAeValues() {
        assertFalse(ModuleSensorSettings.customised(prefs.getAll(),"2"));
        engine().setValue("1"); assertTrue(ModuleSensorSettings.customised(prefs.getAll(),"2"));
        ModuleSensorSettings.reset("2");
        for (String key : UnspektraAePreferences.KEYS) assertFalse(prefs.contains(prefix+key));
        assertFalse(UnspektraAePreferences.selected("2"));
    }
}
