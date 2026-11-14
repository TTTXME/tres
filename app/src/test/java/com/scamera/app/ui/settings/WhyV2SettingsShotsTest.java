package com.scamera.app.ui.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.PopupWindow;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroupAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.scamera.app.R;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.SettingsManager;
import com.scamera.app.ui.controls.PrecisionEditor;
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
import static org.junit.Assert.assertFalse;

/**
 * P614 (owner, 2026-11-11: «Сделай так чтобы в этом меню можно было настраивать V1 и V2, но когда выбирается например V1, то
 * недоступные для него настройки были не активными и наоборот»): «Настройки изображения» with V1 and with V2 chosen - the main
 * page's group and each of its pages, drawn at 390 x 844 px (mdpi) in Russian and English into build/reports/why-v2-settings/
 * (&lt;lang&gt;_&lt;version&gt;_&lt;page&gt;_NN.png). The rows of the other version are greyed, the merged rows active with both; the
 * main page has no V2 page of its own, and the switch greys or wakes the rows in place.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WhyV2SettingsShotsTest {
    private static final int W = 390, H = 844;
    /** The pages of «Настройки изображения». */
    static final String[] PAGES = {"photo_style_screen", "scam_hybrid_why_ae_screen", "scam_hybrid_why_curves_screen",
            "scam_hybrid_why_tone_screen", "portrait_screen", "photo_processing_screen", "scam_hybrid_why_sharp_screen",
            "scam_hybrid_why_rl_screen", "scam_hybrid_hdr_screen",
            "scam_hybrid_artifacts_screen"};
    private Context context;
    private SharedPreferences prefs;
    private MockedStatic<ScameraApp> camera;

    @Before public void setUp() {
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8750");
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        SettingsManager manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        PreferenceKeys.initialise(manager);
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        ScameraApp app = mock(ScameraApp.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
    }

    @After public void tearDown() {
        DialogStyle.dismissShown();
        if (camera != null) camera.close();
    }

    private static void idleFor(long ms) {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private static SettingsActivity.SettingsFragment shown(SettingsActivity activity) {
        FragmentManager fm = activity.getSupportFragmentManager();
        fm.executePendingTransactions();
        idleFor(800);
        Fragment f = fm.findFragmentById(R.id.settings_container);
        assertTrue(String.valueOf(f), f instanceof SettingsActivity.SettingsFragment);
        return (SettingsActivity.SettingsFragment) f;
    }

    private static void shot(SettingsActivity activity, String name) throws Exception {
        idleFor(1200);
        View root = activity.getWindow().getDecorView();
        Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(SettingsStyle.PAGE);
        Canvas canvas = new Canvas(bitmap);
        root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, W, H);
        root.draw(canvas);
        File dir = new File("build/reports/why-v2-settings");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    private static PreferenceGroupAdapter adapter(SettingsActivity.SettingsFragment f) {
        return (PreferenceGroupAdapter) f.getListView().getAdapter();
    }

    private static int position(SettingsActivity.SettingsFragment f, String key) {
        PreferenceGroupAdapter a = adapter(f);
        for (int i = 0; i < a.getItemCount(); i++) if (key.equals(a.getItem(i).getKey())) return i;
        return -1;
    }

    /** The page scrolled from the top, a picture every 9 rows. */
    private static void pages(SettingsActivity activity, SettingsActivity.SettingsFragment page, String name) throws Exception {
        RecyclerView list = page.getListView();
        int n = adapter(page).getItemCount();
        for (int i = 0, step = 0; i < n; i += 9, step++) {
            ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(i, 0);
            idleFor(400);
            shot(activity, String.format(java.util.Locale.ROOT, "%s_%02d", name, step));
        }
    }

    private static boolean enabled(Preference p) {
        return p != null && p.isEnabled();
    }

    /** The main page and «Настройки изображения» with {@code version} chosen, each page in turn. */
    private void drawAll(String lang, String version) throws Exception {
        prefs.edit().putString("pref_scam_hybrid_tone_version", version).commit();
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsActivity.SettingsFragment root = shown(activity);
            assertNull("no V2 page of its own", root.findPreference("scam_hybrid_why_v2_screen"));
            final boolean v2 = version.equals("v2");
            // the main page: its top (the switches) and the group «Настройки изображения»
            ((LinearLayoutManager) root.getListView().getLayoutManager()).scrollToPositionWithOffset(0, 0);
            shot(activity, lang + "_" + version + "_main_top");
            ((LinearLayoutManager) root.getListView().getLayoutManager()).scrollToPositionWithOffset(position(root, "root_colour"), 0);
            shot(activity, lang + "_" + version + "_main_image");
            // P718: «Резкость» acts with either version (V2 scales its sharpening by it)
            assertTrue(enabled(root.findPreference("pref_scam_hybrid_why_sharp_gain")));
            assertTrue(enabled(root.findPreference("pref_scam_hybrid_why_ae_target")));
            for (String key : PAGES) {
                SettingsActivity.SettingsFragment top = shown(activity);
                Preference row = top.findPreference(key);
                assertNotNull(key, row);
                assertTrue(key, row.isEnabled());
                row.performClick();
                SettingsActivity.SettingsFragment page = shown(activity);
                if (key.equals("scam_hybrid_why_ae_screen")) {
                    assertEquals(!v2, enabled(page.findPreference("pref_scam_hybrid_why_metering")));
                    assertTrue(enabled(page.findPreference("pref_scam_hybrid_why_hl_overflow")));
                }
                if (key.equals("scam_hybrid_why_curves_screen")) {
                    assertEquals(v2, enabled(page.findPreference("pref_scam_hybrid_why_v2_agx_hue_lock")));
                    assertEquals(!v2, enabled(page.findPreference("pref_scam_hybrid_colour_hue_lock")));
                    assertTrue(enabled(page.findPreference("pref_scam_hybrid_why_curve_on")));
                }
                if (key.equals("scam_hybrid_why_tone_screen")) {
                    assertEquals(!v2, enabled(page.findPreference("pref_scam_hybrid_why_fusion_layout")));
                    assertEquals(v2, enabled(page.findPreference("pref_scam_hybrid_why_v2_clarity")));
                    assertTrue(enabled(page.findPreference("pref_scam_hybrid_why_classic_weight_mid")));
                }
                if (key.equals("photo_processing_screen")) {
                    assertEquals(!v2, enabled(page.findPreference("colour_mixer_screen")));
                    assertEquals(v2, enabled(page.findPreference("pref_scam_hybrid_why_v2_skin_hue_center")));
                    assertTrue(enabled(page.findPreference("pref_scam_hybrid_why_vibrance_sky")));
                }
                if (key.equals("scam_hybrid_artifacts_screen")) {
                    // P629b: the tone's highlight rows are in «Настройки тона» (ToneSettingsShotsTest greys them there)
                    assertNull(page.findPreference("pref_scam_hybrid_why_v2_hr_amount"));
                    assertNull(page.findPreference("pref_scam_hybrid_why_hl_soft"));
                    assertTrue(enabled(page.findPreference("pref_scam_hybrid_highlight_recovery")));
                }
                pages(activity, page, lang + "_" + version + "_" + key.replace("_screen", ""));
                activity.getSupportFragmentManager().popBackStackImmediate();
                idleFor(600);
            }
        }
    }

    /** The switch flipped on the main page greys or wakes the rows in place (no rebuilt page). */
    private void flipsInPlace(String lang) throws Exception {
        prefs.edit().putString("pref_scam_hybrid_tone_version", "v1").commit();
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsActivity.SettingsFragment root = shown(activity);
            Preference sharp = root.findPreference("pref_scam_hybrid_why_sharp_gain");
            assertTrue(enabled(sharp));
            androidx.preference.ListPreference version = root.findPreference("pref_scam_hybrid_tone_version");
            assertNotNull(version);
            ((LinearLayoutManager) root.getListView().getLayoutManager()).scrollToPositionWithOffset(position(root, "root_colour") - 2, 0);
            idleFor(400);
            if (version.callChangeListener("v2")) version.setValue("v2");
            idleFor(100);
            shot(activity, lang + "_flip_to_v2");
            assertTrue("P718: still active with V2", enabled(sharp));
            if (version.callChangeListener("v1")) version.setValue("v1");
            idleFor(400);
            assertTrue("active with V1", enabled(sharp));
        }
    }

    @Test public void imageSettingsBothVersionsRu() throws Exception {
        drawAll("ru", "v1");
        drawAll("ru", "v2");
        flipsInPlace("ru");
    }

    @Test @Config(qualifiers = "en-w390dp-h844dp-mdpi")
    public void imageSettingsBothVersionsEn() throws Exception {
        drawAll("en", "v1");
        drawAll("en", "v2");
        flipsInPlace("en");
    }
}
