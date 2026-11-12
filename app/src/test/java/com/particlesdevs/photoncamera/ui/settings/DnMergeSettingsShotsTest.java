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
 * P629 (owner, 2026-11-11: «Группу шумоподавление и склейка перемести под выбор склейки Быстро/Качество/Статика как мы сделали с
 * настройками тонмапинга»): the main page with «Шумоподавление и склейка» right under «Режим обработки», and the group's page with
 * each mode chosen, drawn at 390 x 844 px (mdpi) in Russian and English into build/reports/dnmerge-settings/
 * (&lt;lang&gt;_&lt;mode&gt;_&lt;page&gt;_NN.png). The rows only another mode's merge reads are greyed and still shown; the mode
 * switched (on the main page, or elsewhere while the group's page is open) greys or wakes them in place. P644: two modes («Статика»,
 * its group and rows gone); the checks of «Быстрый» and «Качество» as before.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DnMergeSettingsShotsTest {
    private static final int W = 390, H = 844;
    private static final String GROUP = "scam_hybrid_dnmerge_screen", MODE = "pref_scam_hybrid_processing";
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
        File dir = new File("build/reports/dnmerge-settings");
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

    /** The rows of each route as the group's page shows them with {@code mode} chosen. */
    private static void assertRoutes(SettingsActivity.SettingsFragment page, String mode) {
        final boolean fast = mode.equals("fast"), quality = mode.equals("quality");
        // every mode: the denoise, the frames, Quad / Tetra
        for (String key : new String[]{"pref_scam_hybrid_denoise", "pref_scam_hybrid_why_ms_chroma", "pref_scam_hybrid_zsl_frames",
                "scam_hybrid_motion_screen", "scam_hybrid_mosaic_screen"})
            assertTrue(mode + " " + key, enabled(page.findPreference(key)));
        // «Качество»'s rows and groups: greyed (still shown) with «Быстрый»
        for (String key : new String[]{"pref_scam_hybrid_cdm", "pref_scam_hybrid_local_align", "pref_scam_hybrid_mochi",
                "pref_scam_hybrid_sabre61", "pref_scam_hybrid_kernel"}) {
            assertEquals(mode + " " + key, quality, enabled(page.findPreference(key)));
            assertTrue(key, page.findPreference(key).isVisible());
        }
        // a group greyed as a whole rests dimmed (SettingsStyle.restingAlpha; a category is never "enabled"), its rows with it
        for (String group : new String[]{"scam_hybrid_merge_align_category", "scam_hybrid_merge_reject_category", "scam_hybrid_merge_final_category"}) {
            assertEquals(mode + " " + group, quality, SettingsStyle.restingAlpha(page.findPreference(group)) == 1f);
            assertTrue(group, page.findPreference(group).isVisible());
        }
        // the kernel's footprints: «Быстрый» and «Качество» both read them
        assertTrue(mode, enabled(page.findPreference("pref_scam_hybrid_kernel_texture")));
        // P644: «Статика»'s group and rows are gone
        for (String key : new String[]{"scam_hybrid_static_category", "pref_scam_hybrid_static_sr", "pref_scam_hybrid_static_iters"})
            assertNull(mode + " " + key, page.findPreference(key));
        for (String group : new String[]{"scam_hybrid_nr_merge_category", "scam_hybrid_merge_frames_category", "scam_hybrid_merge_kernel_category"})
            assertEquals(mode + " " + group, 1f, SettingsStyle.restingAlpha(page.findPreference(group)), 0f);
        assertTrue(fast || quality);
    }

    /** The main page's top and the group's page with {@code mode} chosen. */
    private void drawAll(String lang, String mode) throws Exception {
        prefs.edit().putString(MODE, mode).commit();
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsActivity.SettingsFragment root = shown(activity);
            // the group right under the processing mode; the former pages are gone from «Настройки изображения»
            assertEquals(position(root, MODE) + 1, position(root, GROUP));
            assertNull(root.findPreference("scam_hybrid_photo_screen"));
            assertNull(root.findPreference("scam_hybrid_merge_screen"));
            ((LinearLayoutManager) root.getListView().getLayoutManager()).scrollToPositionWithOffset(0, 0);
            shot(activity, lang + "_" + mode + "_main_top");
            Preference row = root.findPreference(GROUP);
            assertTrue(row.isEnabled());
            row.performClick();
            SettingsActivity.SettingsFragment page = shown(activity);
            assertEquals(GROUP, page.getPreferenceScreen().getKey());
            assertRoutes(page, mode);
            pages(activity, page, lang + "_" + mode + "_group");
        }
    }

    /** The mode switched on the main page, then from elsewhere while the group's page is open: its rows follow in place. */
    private void followsTheSwitch(String lang) throws Exception {
        prefs.edit().putString(MODE, "fast").commit();
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsActivity.SettingsFragment root = shown(activity);
            ListPreference mode = root.findPreference(MODE);
            assertNotNull(mode);
            if (mode.callChangeListener("quality")) mode.setValue("quality");
            idleFor(200);
            root.findPreference(GROUP).performClick();
            SettingsActivity.SettingsFragment page = shown(activity);
            assertRoutes(page, "quality");
            Preference cdm = page.findPreference("pref_scam_hybrid_cdm");
            // the shade or another window sets it while the page is open
            prefs.edit().putString(MODE, "fast").commit();
            idleFor(100);
            assertFalse("greyed at once", enabled(cdm));
            assertRoutes(page, "fast");
            ((LinearLayoutManager) page.getListView().getLayoutManager()).scrollToPositionWithOffset(position(page, "scam_hybrid_merge_align_category"), 0);
            shot(activity, lang + "_flip_to_fast");
            prefs.edit().putString(MODE, "quality").commit();
            idleFor(400);
            assertTrue("woken at once", enabled(cdm));
            assertRoutes(page, "quality");
            activity.getSupportFragmentManager().popBackStackImmediate();
            idleFor(600);
            // back on the main page: the group's row as before (never greyed: every mode reads rows of it)
            assertTrue(enabled(shown(activity).findPreference(GROUP)));
        }
    }

    @Test public void denoiseAndMergeEveryModeRu() throws Exception {
        for (String mode : new String[]{"fast", "quality"}) drawAll("ru", mode);
        followsTheSwitch("ru");
    }

    @Test @Config(qualifiers = "en-w390dp-h844dp-mdpi")
    public void denoiseAndMergeEveryModeEn() throws Exception {
        for (String mode : new String[]{"fast", "quality"}) drawAll("en", mode);
        followsTheSwitch("en");
    }
}
