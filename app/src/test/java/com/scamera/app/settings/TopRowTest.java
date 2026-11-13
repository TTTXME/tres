package com.scamera.app.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.ContextThemeWrapper;

import com.scamera.app.R;
import com.scamera.app.app.ScameraApp;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * P574: the camera screen's top row in ui_top_row - the default row until something is stored, the format and the LUT always in it
 * (their sheet and tray have no other way in), unknown ids and repeats dropped, at most six; a ui_ key (not per module, untouched by the obsolete-key cleanup) that travels in the
 * config file's main part.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class TopRowTest {
    private Context context;
    private SharedPreferences prefs;
    private MockedStatic<ScameraApp> camera;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        SettingsManager manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(() -> ScameraApp.getStringStatic(org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(i -> RuntimeEnvironment.getApplication().getString(i.<Integer>getArgument(0)));
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        camera.when(ScameraApp::getSettings).thenReturn(mock(com.scamera.app.api.Settings.class));
        ScameraApp app = mock(ScameraApp.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
        PreferenceKeys.initialise(manager);
        PreferenceKeys.setDefaults(context);
    }

    @After
    public void tearDown() {
        camera.close();
    }

    @Test
    public void theDefaultRowUntilSomethingIsStoredAndTheFormatAndLutAlwaysIn() {
        assertNull(TopRow.stored(prefs));
        assertEquals(Arrays.asList("format", "torch", "timer", "lut", "dcg"), TopRow.load(prefs));
        TopRow.save(prefs, Collections.emptyList());
        assertEquals("every other item taken out: the two that stay", "format,lut", prefs.getString(TopRow.KEY, null));
        assertEquals(Arrays.asList("format", "lut"), TopRow.load(prefs));
        // a row stored without them (an older build, a config file): they come back on read, the others give way past six
        prefs.edit().putString(TopRow.KEY, "").commit();
        assertEquals(Arrays.asList("format", "lut"), TopRow.load(prefs));
        prefs.edit().putString(TopRow.KEY, "torch,timer,dcg,grid,horizon,uhdr").commit();
        assertEquals(Arrays.asList("format", "torch", "timer", "dcg", "grid", "lut"), TopRow.load(prefs));
        prefs.edit().putString(TopRow.KEY, "grid,lut,torch").commit();
        assertEquals(Arrays.asList("format", "grid", "lut", "torch"), TopRow.load(prefs));
    }

    @Test
    public void unknownIdsAndRepeatsGoAndSixAtMost() {
        prefs.edit().putString(TopRow.KEY, "grid, torch,nonsense,torch,format,lut,timer,dcg,horizon,uhdr").commit();
        assertEquals(Arrays.asList("grid", "torch", "format", "lut", "timer", "dcg"), TopRow.load(prefs));
        TopRow.save(prefs, Arrays.asList("uhdr", "uhdr", "horizon"));
        assertEquals("format,uhdr,horizon,lut", prefs.getString(TopRow.KEY, null));
    }

    @Test
    public void aUiKeyThatTravelsInTheConfigFile() throws Exception {
        assertFalse("the row does not change with the lens", ModuleProfiles.isLocal(TopRow.KEY));
        TopRow.save(prefs, Arrays.asList("lut", "format", "grid"));
        assertFalse("the obsolete-key cleanup keeps it", SettingsMigration.removeObsolete(prefs) && !prefs.contains(TopRow.KEY));
        assertEquals("lut,format,grid", prefs.getString(TopRow.KEY, null));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ConfigXml.write(out, BackupRestoreUtil.header(RuntimeEnvironment.getApplication()), BackupRestoreUtil.collect(RuntimeEnvironment.getApplication()));
        ConfigXml.Config config = ConfigXml.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("lut,format,grid", config.files.get(ConfigXml.MAIN).get(TopRow.KEY));
        TopRow.save(prefs, Arrays.asList("torch", "lut", "format"));
        BackupRestoreUtil.apply(RuntimeEnvironment.getApplication(), config);
        assertEquals(Arrays.asList("lut", "format", "grid"), TopRow.stored(prefs));
    }
}
