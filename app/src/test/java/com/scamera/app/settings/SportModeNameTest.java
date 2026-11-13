package com.scamera.app.settings;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;

import com.scamera.app.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class SportModeNameTest {
    private static Context locale(String language) {
        Context app = RuntimeEnvironment.getApplication();
        Configuration config = new Configuration(app.getResources().getConfiguration());
        config.setLocale(new Locale(language));
        return app.createConfigurationContext(config);
    }

    @Test public void sportIsNamedConsistentlyInEnglishAndRussian() {
        for (String language : new String[]{"en", "ru"}) {
            Context c = locale(language);
            String name = language.equals("ru") ? "Спорт" : "Sport";
            assertEquals(name, c.getString(R.string.topbar_motion));
            assertEquals(name, c.getString(R.string.shade_t_motion));
            assertEquals(name, c.getString(R.string.prefs_scam_motion_screen_title));
            for (int id : new int[]{R.string.motion_mode_on, R.string.motion_mode_off, R.string.motion_mode_on_toast,
                    R.string.prefs_scam_motion_on_title, R.string.prefs_scam_motion_screen_summary,
                    R.string.prefs_scam_motion_shutter_summary, R.string.prefs_scam_motion_frames_summary,
                    R.string.prefs_scam_motion_bento_summary, R.string.prefs_scam_motion_shasta_summary,
                    R.string.help_motion_mode, R.string.help_low_light_boost,
                    R.string.prefs_shooting_screen_summary, R.string.prefs_shooting_settings_screen_summary}) {
                String value = c.getString(id);
                assertTrue(c.getResources().getResourceEntryName(id), value.contains(name));
                assertFalse(value.contains("Движение"));
                assertFalse(value.contains("Motion"));
            }
        }
    }

    @Test public void sportHelpDescribesTheCurrentCaptureAndLowLightPolicy() {
        String en = locale("en").getString(R.string.help_motion_mode);
        assertTrue(en.contains("Exposure bracketing is disabled"));
        assertTrue(en.contains("stronger denoising"));
        assertTrue(en.contains("before the press"));
        assertTrue("the help has an actual paragraph break", en.contains("\n\n"));
        String ru = locale("ru").getString(R.string.help_motion_mode);
        assertTrue(ru.contains("Брекетинг отключён"));
        assertTrue(ru.contains("усиленное шумоподавление"));
        assertTrue(ru.contains("до нажатия"));
    }
}
