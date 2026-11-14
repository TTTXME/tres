package com.scamera.app.ui.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import com.scamera.app.R;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.settings.PhotoStyle;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.SettingsLevel;
import com.scamera.app.settings.SettingsManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * P718 (owner, 2026-11-14): the «Стиль» presets' cards - the wizard's «Вид снимков → Стиль» and the settings' «Стиль снимка» page -
 * at 360dp: Насыщенный / Обычный / Нейтральный in that order, Обычный chosen at first, each with its thumbnail (LookThumbs.preset)
 * and its whole name, a tap choosing the preset and «Why тон» V2. Drawn into build/reports/style-presets/ (&lt;lang&gt;_wizard.png,
 * &lt;lang&gt;_settings.png).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StylePresetsShotsTest {
    private Context context;
    private SharedPreferences prefs;
    private MockedStatic<ScameraApp> camera;

    @Before public void setUp() {
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8750");
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
        SettingsManager manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        PreferenceKeys.initialise(manager);
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        ScameraApp app = mock(ScameraApp.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
        PreferenceManager.setDefaultValues(context, R.xml.preferences, true);
        prefs.edit().putString("module_auto_back0", "0").putString("module_label_back0", "1×").putBoolean("module_visible_back0", true)
                .putString("module_active", "back0").putString("pref_scam_hybrid_tone_version", "v1").commit();
    }

    @After public void tearDown() {
        DialogStyle.dismissShown();
        if (camera != null) camera.close();
    }

    private static void idle() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(800));
    }

    /** The cards' thumbnails drawn (LookThumbs' worker, then its post to the main thread). */
    private static void thumbnails(View root, String prefix) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            boolean all = true;
            for (int s : PhotoStyle.order()) {
                ViewGroup card = root.findViewWithTag(prefix + PhotoStyle.id(s));
                all &= ((ImageView) card.getChildAt(0)).getDrawable() != null;
            }
            if (all) return;
            Thread.sleep(25);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        }
        throw new AssertionError("the thumbnails were not drawn");
    }

    /** The three cards in the owner's order, side by side, one chosen; each with a picture and its name unshortened. */
    private void checkRow(View root, String prefix, int chosen) {
        int lastX = -1;
        for (int s : PhotoStyle.order()) {
            ViewGroup card = root.findViewWithTag(prefix + PhotoStyle.id(s));
            assertNotNull(prefix + PhotoStyle.id(s), card);
            int[] at = new int[2];
            card.getLocationOnScreen(at);
            assertTrue("Насыщенный / Обычный / Нейтральный from the left", at[0] > lastX);
            lastX = at[0];
            assertEquals(PhotoStyle.id(s), s == chosen, card.isSelected());
            assertNotNull(((ImageView) card.getChildAt(0)).getDrawable());
            TextView name = (TextView) card.getChildAt(1);
            assertEquals(PhotoStyle.name(context, s), name.getText().toString());
            assertTrue("the whole name: " + name.getText() + " " + name.getWidth() + " px at " + name.getTextSize() + " px",
                    name.getLayout() != null && name.getLayout().getEllipsisCount(0) == 0);
            assertTrue("a description for the screen reader", String.valueOf(card.getContentDescription()).startsWith(PhotoStyle.name(context, s)));
        }
    }

    private static void shot(View root, int w, int h, String name) throws Exception {
        idle();
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(SettingsStyle.PAGE);
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        root.draw(new Canvas(bitmap));
        File dir = new File("build/reports/style-presets");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    private void wizard(String lang) throws Exception {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            FragmentManager fm = activity.getSupportFragmentManager();
            fm.executePendingTransactions();
            idle();
            SettingsActivity.SettingsFragment root = (SettingsActivity.SettingsFragment) fm.findFragmentById(R.id.settings_container);
            root.findPreference(SettingsLevel.WIZARD_ROW).performClick();
            idle();
            View sheet = ShadowDialog.getLatestDialog().getWindow().getDecorView();
            sheet.findViewWithTag("wizard_simple").performClick();
            idle();
            sheet.findViewWithTag("wizard_next").performClick();
            idle();
            sheet.findViewWithTag("wizard_next").performClick();
            idle();
            thumbnails(sheet, "wizard_style_");
            idle();
            shot(sheet, sheet.getWidth(), sheet.getHeight(), lang + "_wizard");
            checkRow(sheet, "wizard_style_", PhotoStyle.NORMAL);
            sheet.findViewWithTag("wizard_style_neutral").performClick();
            idle();
            checkRow(sheet, "wizard_style_", PhotoStyle.NEUTRAL);
            shot(sheet, sheet.getWidth(), sheet.getHeight(), lang + "_wizard_neutral");
            sheet.findViewWithTag("wizard_next").performClick();   // «Готово»
            idle();
            assertFalse(SettingsWizard.showing());
            assertEquals(PhotoStyle.NEUTRAL, PhotoStyle.current(prefs));
            assertEquals(PhotoStyle.NEUTRAL, PreferenceKeys.photoStyle());
            assertEquals("a preset is a V2 tone", "v2", prefs.getString("pref_scam_hybrid_tone_version", ""));
        }
    }

    private void settingsPage(String lang) throws Exception {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsActivity.SettingsFragment f = new SettingsActivity.SettingsFragment();
            Bundle args = new Bundle();
            args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, "photo_style_screen");
            f.setArguments(args);
            activity.getSupportFragmentManager().beginTransaction().replace(R.id.settings_container, f).addToBackStack(null).commit();
            FragmentManager fm = activity.getSupportFragmentManager();
            fm.executePendingTransactions();
            idle();
            Fragment shown = fm.findFragmentById(R.id.settings_container);
            View root = activity.getWindow().getDecorView();
            assertNotNull(shown);
            thumbnails(root, "style_option_");
            idle();
            shot(root, root.getWidth(), root.getHeight(), lang + "_settings");
            checkRow(root, "style_option_", PhotoStyle.NORMAL);
            root.findViewWithTag("style_option_vivid").performClick();
            idle();
            checkRow(root, "style_option_", PhotoStyle.VIVID);
            assertEquals(PhotoStyle.about(context, PhotoStyle.VIVID), ((TextView) root.findViewWithTag("style_about")).getText().toString());
            assertEquals(PhotoStyle.VIVID, PhotoStyle.current(prefs));
            assertEquals(PhotoStyle.VIVID, PreferenceKeys.photoStyle());
            assertEquals("v2", prefs.getString("pref_scam_hybrid_tone_version", ""));
            shot(root, root.getWidth(), root.getHeight(), lang + "_settings_vivid");
        }
    }

    @Test public void wizardRu() throws Exception {
        wizard("ru");
    }

    @Test public void settingsRu() throws Exception {
        settingsPage("ru");
    }

    @Config(qualifiers = "en-w360dp-h760dp-xhdpi")
    @Test public void wizardEn() throws Exception {
        wizard("en");
    }

    @Config(qualifiers = "en-w360dp-h760dp-xhdpi")
    @Test public void settingsEn() throws Exception {
        settingsPage("en");
    }
}
