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
 * P630 «Oldstyle Тон»: the main page with the group right under «Настройки тона», and the group's page with Oldstyle V1 and V2 chosen
 * (RU, and EN), drawn at 390 x 844 px into build/reports/oldstyle/ (ui_settings_&lt;lang&gt;_&lt;version&gt;_*.png). The other version's
 * rows greyed in place (SettingsAvailability.oldstyleVersionReason) and following the switch while the page is open.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class OldstyleSettingsShotsTest {
    private static final int W = 390, H = 844;
    private static final String GROUP = "oldstyle_tone_screen", TONE = "tone_settings_screen", VERSION = "pref_scam_hybrid_oldstyle_version";
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
        File dir = new File("build/reports/oldstyle");
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

    private void drawAll(String lang, String version) throws Exception {
        prefs.edit().putString(VERSION, version).commit();
        final boolean v2 = version.equals("v2");
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsActivity.SettingsFragment root = shown(activity);
            assertEquals(position(root, TONE) + 1, position(root, GROUP));
            ((LinearLayoutManager) root.getListView().getLayoutManager()).scrollToPositionWithOffset(Math.max(0, position(root, TONE) - 3), 0);
            shot(activity, "ui_settings_" + lang + "_main_top");
            Preference row = root.findPreference(GROUP);
            assertTrue(row.isEnabled());
            row.performClick();
            SettingsActivity.SettingsFragment group = shown(activity);
            assertEquals(GROUP, group.getPreferenceScreen().getKey());
            assertTrue(enabled(group.findPreference(VERSION)));
            // P630e: the rows of both versions (the gains' limit, dehaze / tint, output saturation) awake with either
            for (String key : com.particlesdevs.photoncamera.settings.OldstyleMode.COMMON_ROWS) assertTrue(key, enabled(group.findPreference(key)));
            assertNull(group.findPreference("pref_scam_hybrid_oldstyle_target"));
            for (String key : com.particlesdevs.photoncamera.settings.OldstyleMode.V1_ROWS) assertEquals(key, !v2, enabled(group.findPreference(key)));
            for (String key : com.particlesdevs.photoncamera.settings.OldstyleMode.V2_ROWS) assertEquals(key, v2, enabled(group.findPreference(key)));
            for (String key : com.particlesdevs.photoncamera.settings.OldstyleMode.V2_ROWS) assertTrue(key, group.findPreference(key).isVisible());
            shot(activity, "ui_settings_" + lang + "_" + version + "_group");
        }
    }

    private void followsTheSwitch() throws Exception {
        prefs.edit().putString(VERSION, "v1").commit();
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsActivity.SettingsFragment root = shown(activity);
            root.findPreference(GROUP).performClick();
            SettingsActivity.SettingsFragment group = shown(activity);
            Preference v1 = group.findPreference("pref_scam_hybrid_oldstyle_v1_lowfreq"), v2 = group.findPreference("pref_scam_hybrid_oldstyle_v2_saturation");
            assertTrue(enabled(v1));
            assertFalse(enabled(v2));
            ListPreference version = group.findPreference(VERSION);
            if (version.callChangeListener("v2")) version.setValue("v2");
            idleFor(200);
            assertFalse("greyed at once", enabled(v1));
            assertTrue("awake at once", enabled(v2));
        }
    }

    @Test public void oldstyleSettingsRu() throws Exception {
        drawAll("ru", "v1");
        drawAll("ru", "v2");
        followsTheSwitch();
    }

    @Test @Config(qualifiers = "en-w390dp-h844dp-mdpi")
    public void oldstyleSettingsEn() throws Exception {
        drawAll("en", "v1");
    }
}
