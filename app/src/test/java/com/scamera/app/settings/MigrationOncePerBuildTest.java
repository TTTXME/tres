package com.scamera.app.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * P662: the rename of the stored files and the obsolete-key pass run once per build (a marker), not at every process start;
 * a restored backup and an applied module profile still get both on their own.
 */
@RunWith(RobolectricTestRunner.class)
public class MigrationOncePerBuildTest {
    private static final String OBSOLETE = "pref_scam_hybrid_despeckle";

    @Test
    public void theObsoletePassRunsOncePerBuild() {
        final Context context = RuntimeEnvironment.getApplication();
        SettingsManager first = new SettingsManager(context);
        final SharedPreferences prefs = first.getDefaultPreferences();
        final SharedPreferences marker = context.getSharedPreferences(SettingsManager.MIGRATED_FILE, Context.MODE_PRIVATE);
        assertEquals("the first start of this build writes the marker", com.scamera.app.BuildConfig.VERSION_CODE,
                marker.getInt(SettingsManager.MIGRATED_KEY, -1));
        assertFalse(SettingsManager.migrationsDue(marker, com.scamera.app.BuildConfig.VERSION_CODE));
        assertTrue("another build runs them again", SettingsManager.migrationsDue(marker, com.scamera.app.BuildConfig.VERSION_CODE + 1));

        prefs.edit().putString(OBSOLETE, "1").commit();
        new SettingsManager(context); // the next start of the same build
        assertTrue("skipped: the same build already ran it", prefs.contains(OBSOLETE));

        marker.edit().putInt(SettingsManager.MIGRATED_KEY, com.scamera.app.BuildConfig.VERSION_CODE - 1).commit(); // an update
        new SettingsManager(context);
        assertFalse("an update runs the pass", prefs.contains(OBSOLETE));
        assertEquals(com.scamera.app.BuildConfig.VERSION_CODE, marker.getInt(SettingsManager.MIGRATED_KEY, -1));
    }

    @Test
    public void theMarkerHasNoOldNameAndTheOtherMigrationsStillRunAtEveryStart() {
        assertEquals("a name the rename leaves as it is", SettingsManager.MIGRATED_FILE, BrandMigration.text(SettingsManager.MIGRATED_FILE));
        assertEquals(SettingsManager.MIGRATED_KEY, BrandMigration.text(SettingsManager.MIGRATED_KEY));
        final Context context = RuntimeEnvironment.getApplication();
        final SharedPreferences prefs = new SettingsManager(context).getDefaultPreferences();
        prefs.edit().remove("settings_antibanding_list_v1").commit();
        new SettingsManager(context);
        assertTrue("the markers of the one-time migrations are written at every start, as before", prefs.contains("settings_antibanding_list_v1"));
    }

    @Test
    public void anAppliedProfileStillLosesItsObsoleteKeys() {
        final Context context = RuntimeEnvironment.getApplication();
        new SettingsManager(context);
        final SharedPreferences profile = context.getSharedPreferences("p662_profile", Context.MODE_PRIVATE);
        profile.edit().putString(OBSOLETE, "1").putString("pref_scam_hybrid_why_metering", "1.00").commit();
        assertTrue(SettingsMigration.removeObsolete(profile)); // what BackupRestoreUtil / ModuleProfiles run on their own
        assertFalse(profile.contains(OBSOLETE));
        assertEquals("1", profile.getString("pref_scam_hybrid_why_metering", null));
    }
}
