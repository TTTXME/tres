package com.scamera.app.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;

import android.app.Application;
import android.content.Context;

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
 * P680: the viewfinder's per-frame settings (the AF mode) from a snapshot instead of a preferences read per frame - the snapshot
 * always equals what the settings say: kept while nothing changes, read again after any change.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class SessionPrefsTest {
    private MockedStatic<ScameraApp> app;
    private SettingsManager settings;

    @Before public void setup() {
        final Context context = RuntimeEnvironment.getApplication();
        settings = new SettingsManager(context);
        app = mockStatic(ScameraApp.class);
        app.when(ScameraApp::getAppContext).thenReturn(context);
        app.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        app.when(ScameraApp::getSettingsManagerStatic).thenReturn(settings);
        app.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(i -> context.getString(i.getArgument(0)));
        PreferenceKeys.initialise(settings);
    }

    @After public void cleanup() {
        app.close();
    }

    private void afMode(String value) {
        settings.getDefaultPreferences().edit().putString("pref_af_mode_key", value).commit();
    }

    @Test public void theSnapshotAlwaysEqualsTheSettings() {
        for (String mode : new String[]{"1", "4", "3", "0", "1"}) {
            afMode(mode);
            final SessionPrefs snapshot = SessionPrefs.read();
            assertEquals(PreferenceKeys.getAfMode(), snapshot.afMode);
            assertEquals(Integer.parseInt(mode), snapshot.afMode);
            assertEquals(SessionPrefs.read(), snapshot);
        }
    }

    @Test public void keptWhileNothingChangesReadAgainAfterAChange() {
        afMode("4");
        SessionPrefs kept = SessionPrefs.read();
        assertTrue(kept.current());
        assertSame("no change: the same snapshot, no read", kept, SessionPrefs.refresh(kept));
        afMode("1");
        assertFalse("a setting changed", kept.current());
        final SessionPrefs again = SessionPrefs.refresh(kept);
        assertNotSame(kept, again);
        assertEquals(1, again.afMode);
        assertEquals(PreferenceKeys.getAfMode(), again.afMode);
        // any other setting counts as a change too (the snapshot is read again, the same value)
        settings.getDefaultPreferences().edit().putBoolean("some_other_key", true).commit();
        final SessionPrefs third = SessionPrefs.refresh(again);
        assertNotSame(again, third);
        assertEquals(again, third);
        assertNotSame(third, SessionPrefs.refresh(null));
    }
}
