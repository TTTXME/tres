package com.particlesdevs.photoncamera.ui.settings;

import android.app.Application;
import android.content.Context;

import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;
import org.robolectric.RuntimeEnvironment;

import com.particlesdevs.photoncamera.settings.ScamDevFile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** P475: a developer row writes its scam_dev.txt line (a switch "key 1/0", a number its value, an empty number removes it). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class DeveloperPreferencesTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        ScamDevFile.file(context).delete();
    }

    @After public void tearDown() {
        ScamDevFile.file(context).delete();
    }

    @Test public void rowsWriteTheFileWithoutOne() {
        assertFalse(ScamDevFile.file(context).exists());
        PreferenceManager manager = new PreferenceManager(context);
        PreferenceScreen root = manager.createPreferenceScreen(context);
        PreferenceScreen page = manager.createPreferenceScreen(context);
        page.setKey(DeveloperPreferences.SCREEN);
        root.addPreference(page);
        DeveloperPreferences.populate(root, context, null);
        SwitchPreferenceCompat sw = root.findPreference("dev_snr_cs");
        assertNotNull(sw);
        assertTrue(sw.callChangeListener(false));
        assertEquals("0", ScamDevFile.get(context, "snr_cs"));
        EditTextPreference num = root.findPreference("dev_hybrid_tex_maps");
        assertNotNull(num);
        num.callChangeListener("31");
        assertEquals("31", ScamDevFile.get(context, "hybrid_tex_maps"));
        assertEquals("31", num.getText());
        num.callChangeListener("");
        assertNull(ScamDevFile.get(context, "hybrid_tex_maps"));
        assertEquals("0", ScamDevFile.get(context, "snr_cs"));
    }
}
