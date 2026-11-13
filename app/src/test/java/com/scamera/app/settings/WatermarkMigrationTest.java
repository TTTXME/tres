package com.scamera.app.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.scamera.app.processing.watermark.WatermarkSpec;
import com.scamera.app.processing.watermark.WatermarkText;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P628 (owner, 2026-11-11: «убери по дефолту надпись "Shot on"»): the two caption lines become the one custom text once - a line at
 * its former default is the new default (no «SHOT ON»), a line the user typed stays, a stored custom text is never touched.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class WatermarkMigrationTest {
    private SharedPreferences prefs;

    @Before public void setUp() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("watermark_migration_test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
    }

    private String shown() {
        return WatermarkText.of(WatermarkSpec.from(prefs), new WatermarkText.Info()).title;
    }

    @Test public void aFreshInstallGetsTheAppNameAlone() {
        assertFalse(SettingsMigration.migrateWatermark(prefs));
        assertTrue(prefs.contains(SettingsMigration.WATERMARK_REV));
        assertFalse(prefs.contains("pref_watermark_text"));
        assertEquals("SCAMERA", shown());
    }

    @Test public void theFormerDefaultsDropShotOn() {
        prefs.edit().putString("pref_watermark_line1", "SHOT ON").putString("pref_watermark_line2", "SCAMERA").commit();
        assertTrue(SettingsMigration.migrateWatermark(prefs));
        assertFalse(prefs.contains("pref_watermark_line1"));
        assertFalse(prefs.contains("pref_watermark_line2"));
        assertFalse("the default is not stored", prefs.contains("pref_watermark_text"));
        assertEquals("SCAMERA", shown());
        // only the first line stored (the second at its default), in another case and spacing: the same
        prefs.edit().clear().putString("pref_watermark_line1", " shot  On ").commit();
        assertTrue(SettingsMigration.migrateWatermark(prefs));
        assertEquals("SCAMERA", shown());
    }

    @Test public void typedLinesStay() {
        prefs.edit().putString("pref_watermark_line1", "Shot on").putString("pref_watermark_line2", "Ivan P.").commit();
        assertTrue(SettingsMigration.migrateWatermark(prefs));
        assertEquals("Ivan P.", prefs.getString("pref_watermark_text", null));
        prefs.edit().clear().putString("pref_watermark_line1", "PHOTO BY").commit();
        assertTrue(SettingsMigration.migrateWatermark(prefs));
        assertEquals("PHOTO BY SCAMERA", prefs.getString("pref_watermark_text", null));
        prefs.edit().clear().putString("pref_watermark_line1", "").putString("pref_watermark_line2", "").commit();
        assertTrue(SettingsMigration.migrateWatermark(prefs));
        assertEquals("both emptied: no text", "", prefs.getString("pref_watermark_text", null));
        assertEquals("", shown());
    }

    @Test public void runsOnceAndKeepsAStoredText() {
        prefs.edit().putString("pref_watermark_text", "Mine").putString("pref_watermark_line1", "SHOT ON")
                .putString("pref_watermark_line2", "OTHER").commit();
        assertTrue(SettingsMigration.migrateWatermark(prefs));
        assertEquals("Mine", prefs.getString("pref_watermark_text", null));
        // a second run (the next start) changes nothing, a text typed since stays
        prefs.edit().putString("pref_watermark_text", "Typed later").commit();
        assertFalse(SettingsMigration.migrateWatermark(prefs));
        assertFalse(SettingsMigration.migrateWatermark(prefs));
        assertEquals("Typed later", prefs.getString("pref_watermark_text", null));
        assertEquals(1, prefs.getInt(SettingsMigration.WATERMARK_REV, 0));
    }
}
