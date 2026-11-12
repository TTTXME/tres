package com.particlesdevs.photoncamera.ui.settings;

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
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.ui.controls.PrecisionEditor;
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
 * P629b (owner, 2026-11-11: «У нас под V1/V2 должна находиться группа настроек для тонмапинга, а на последних скриншотах интерфейса
 * этого нет»): the main page with «Настройки тона» right under «Why тон», the group's page and its three tone pages with V1 and with V2,
 * drawn at 390 x 844 px (mdpi) in Russian and English into build/reports/tone-settings/ (tone_&lt;lang&gt;_&lt;version&gt;_&lt;page&gt;_NN.png).
 * The other version's rows stay greyed (SettingsAvailability.toneVersionReason), shown, and follow the switch in place.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ToneSettingsShotsTest {
    private static final int W = 390, H = 844;
    private static final String GROUP = "tone_settings_screen", VERSION = "pref_scam_hybrid_tone_version";
    private static final String[] PAGES = {"scam_hybrid_why_ae_screen", "scam_hybrid_why_curves_screen", "scam_hybrid_why_tone_screen"};
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
        File dir = new File("build/reports/tone-settings");
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
        return p != null && p.isEnabled() && p.isVisible();
    }

    /** The main page's top and «Настройки тона» with its pages, {@code version} chosen. */
    private void drawAll(String lang, String version) throws Exception {
        prefs.edit().putString(VERSION, version).commit();
        final boolean v2 = version.equals("v2");
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsActivity.SettingsFragment root = shown(activity);
            // the group right under the switch; the tone pages are not in «Настройки изображения» any more
            assertEquals(position(root, VERSION) + 1, position(root, GROUP));
            final androidx.preference.PreferenceGroup image = root.findPreference("root_colour");
            for (String page : PAGES) assertNull(page, image.findPreference(page));
            assertNull(image.findPreference("pref_scam_hybrid_why_hl_soft"));
            // P629c: brightness, contrast and saturation left it for the group; warmth, sharpness and noise reduction stay
            assertNull(image.findPreference("pref_scam_hybrid_why_ae_target"));
            assertTrue(enabled(image.findPreference("pref_scam_hybrid_colour_warmth")));
            ((LinearLayoutManager) root.getListView().getLayoutManager()).scrollToPositionWithOffset(Math.max(0, position(root, VERSION) - 2), 0);
            shot(activity, "tone_" + lang + "_" + version + "_main_top");
            Preference row = root.findPreference(GROUP);
            assertTrue(row.isEnabled());
            row.performClick();
            SettingsActivity.SettingsFragment group = shown(activity);
            assertEquals(GROUP, group.getPreferenceScreen().getKey());
            for (String page : PAGES) assertTrue(page, enabled(group.findPreference(page)));
            // P629c: the three everyday tone sliders first, both versions read them
            final String[] sliders = {"pref_scam_hybrid_why_ae_target", "pref_scam_hybrid_why_macro_contrast", "pref_scam_hybrid_why_agx_sat"};
            for (int i = 0; i < sliders.length; i++) {
                assertEquals(sliders[i], group.getPreferenceScreen().getPreference(i).getKey());
                // the saturation acts with the AgX operator only (the own curve is on by default: greyed with its note, as before)
                if (i < 2) assertTrue(sliders[i], enabled(group.findPreference(sliders[i])));
                else assertTrue(sliders[i], group.findPreference(sliders[i]).isVisible());
            }
            // V1's highlight softness / detail-colour combine, V2's highlight colour; both read the shoulder and the short frames' denoise
            assertEquals(!v2, enabled(group.findPreference("pref_scam_hybrid_why_hl_soft")));
            assertEquals(!v2, enabled(group.findPreference("pref_scam_hybrid_why_delta_chroma")));
            assertEquals(v2, enabled(group.findPreference("pref_scam_hybrid_why_v2_hr_amount")));
            assertTrue(enabled(group.findPreference("pref_scam_hybrid_why_hr_room")));
            assertTrue(enabled(group.findPreference("pref_scam_hybrid_why_bracket_denoise")));
            assertEquals(!v2, SettingsStyle.restingAlpha(group.findPreference("scam_hybrid_why_artifacts_category")) == 1f);
            pages(activity, group, "tone_" + lang + "_" + version + "_group");
            for (String key : PAGES) {
                group.findPreference(key).performClick();
                SettingsActivity.SettingsFragment page = shown(activity);
                assertEquals(key, page.getPreferenceScreen().getKey());
                if (key.equals("scam_hybrid_why_ae_screen")) assertEquals(!v2, enabled(page.findPreference("pref_scam_hybrid_why_metering")));
                if (key.equals("scam_hybrid_why_curves_screen")) {
                    assertEquals(v2, enabled(page.findPreference("pref_scam_hybrid_why_v2_agx_hue_lock")));
                    assertTrue(enabled(page.findPreference("pref_scam_hybrid_why_curve_on")));
                }
                if (key.equals("scam_hybrid_why_tone_screen")) assertEquals(!v2, enabled(page.findPreference("pref_scam_hybrid_why_fusion_layout")));
                pages(activity, page, "tone_" + lang + "_" + version + "_" + key.replace("scam_hybrid_why_", "").replace("_screen", ""));
                activity.getSupportFragmentManager().popBackStackImmediate();
                group = shown(activity);
            }
        }
    }

    /** The switch flipped on the main page, the group's rows follow when it opens and in place while it is open. */
    private void followsTheSwitch(String lang) throws Exception {
        prefs.edit().putString(VERSION, "v1").commit();
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsActivity.SettingsFragment root = shown(activity);
            ListPreference version = root.findPreference(VERSION);
            if (version.callChangeListener("v2")) version.setValue("v2");
            idleFor(200);
            root.findPreference(GROUP).performClick();
            SettingsActivity.SettingsFragment group = shown(activity);
            Preference soft = group.findPreference("pref_scam_hybrid_why_hl_soft"), hr = group.findPreference("pref_scam_hybrid_why_v2_hr_amount");
            assertFalse(enabled(soft));
            assertTrue(enabled(hr));
            prefs.edit().putString(VERSION, "v1").commit();
            idleFor(100);
            assertTrue("awake at once", enabled(soft));
            assertFalse("greyed at once", enabled(hr));
            ((LinearLayoutManager) group.getListView().getLayoutManager()).scrollToPositionWithOffset(position(group, "scam_hybrid_why_highlights_category"), 0);
            shot(activity, "tone_" + lang + "_flip_to_v1");
        }
    }

    @Test public void toneSettingsBothVersionsRu() throws Exception {
        drawAll("ru", "v1");
        drawAll("ru", "v2");
        followsTheSwitch("ru");
    }

    @Test @Config(qualifiers = "en-w390dp-h844dp-mdpi")
    public void toneSettingsBothVersionsEn() throws Exception {
        drawAll("en", "v1");
        drawAll("en", "v2");
        followsTheSwitch("en");
    }
}
