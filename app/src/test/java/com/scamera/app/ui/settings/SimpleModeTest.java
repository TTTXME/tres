package com.scamera.app.ui.settings;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;

import com.scamera.app.R;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.settings.FormatChoice;
import com.scamera.app.settings.MigrationManager;
import com.scamera.app.settings.PhotoStyle;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.SettingsLevel;
import com.scamera.app.settings.SettingsManager;
import com.scamera.app.settings.ShadeCatalog;

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
import org.robolectric.shadows.ShadowDialog;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * P708: the Simple / Advanced settings mode - every row of the built settings (generated rows too) has a level where it needs one,
 * the Simple mode's rows and groups on the main page and on a Simple page, switching never changes a value, the banner of changed
 * hidden settings and its reset, the wizard only on a fresh install, the search finding hidden rows, and the wizard and the switch
 * with the system's animations off and on.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h880dp-mdpi")
public class SimpleModeTest {
    private Context context;
    private SettingsManager manager;
    private SharedPreferences prefs;
    private MockedStatic<ScameraApp> camera;

    @Before public void setUp() {
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8750");
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
        // a fresh start: the migrations and this phone's defaults, then the XML defaults (as CameraActivity / the settings do)
        manager = new SettingsManager(context);
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
        SharedPreferences.Editor e = prefs.edit();
        String[][] backs = {{"0", "1×"}, {"4", "3×"}};
        for (int i = 0; i < backs.length; i++)
            e.putString("module_auto_back" + i, backs[i][0]).putString("module_label_back" + i, backs[i][1]).putBoolean("module_visible_back" + i, true);
        e.putString("module_active", "back0").commit();
    }

    @After public void tearDown() {
        DialogStyle.dismissShown();
        animatorScale(1f);
        MigrationManager.readAgain = false;
        if (camera != null) camera.close();
    }

    /** The system's animator duration scale (0: «remove animations»; ValueAnimator.setDurationScale is hidden API). */
    private static void animatorScale(float scale) {
        try {
            android.animation.ValueAnimator.class.getMethod("setDurationScale", float.class).invoke(null, scale);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        assertEquals(scale != 0f, android.animation.ValueAnimator.areAnimatorsEnabled());
    }

    private static void idle() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(800));
    }

    private static Fragment current(SettingsActivity activity) {
        FragmentManager fm = activity.getSupportFragmentManager();
        fm.executePendingTransactions();
        idle();
        return fm.findFragmentById(R.id.settings_container);
    }

    private static SettingsActivity.SettingsFragment page(String root) {
        SettingsActivity.SettingsFragment f = new SettingsActivity.SettingsFragment();
        Bundle args = new Bundle();
        if (root != null) args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, root);
        f.setArguments(args);
        return f;
    }

    private SettingsActivity.SettingsFragment show(SettingsActivity activity, String root) {
        activity.getSupportFragmentManager().beginTransaction().replace(R.id.settings_container, page(root)).addToBackStack(null).commit();
        return (SettingsActivity.SettingsFragment) current(activity);
    }

    private void mode(boolean simple) {
        mode(simple ? SettingsLevel.SIMPLE_MODE : SettingsLevel.ADVANCED_MODE);
    }

    private void mode(String m) {
        prefs.edit().putString(SettingsLevel.MODE_KEY, m).commit();
    }

    /** Shown on its page: it and every group up to the page visible. */
    private static boolean shown(PreferenceGroup page, String key) {
        Preference p = page.findPreference(key);
        assertNotNull(key, p);
        for (Preference q = p; q != null && q != page; q = q.getParent()) if (!q.isVisible()) return false;
        return true;
    }

    /** The keys the list shows now. */
    private static List<String> listed(SettingsActivity.SettingsFragment f) {
        List<String> out = new ArrayList<>();
        RecyclerView list = f.getListView();
        PreferenceGroupAdapter adapter = (PreferenceGroupAdapter) list.getAdapter();
        for (int i = 0; i < adapter.getItemCount(); i++) {
            Preference p = adapter.getItem(i);
            if (p != null && p.getKey() != null) out.add(p.getKey());
        }
        return out;
    }

    /** The row of {@code key} as the list binds it. */
    private static View bound(SettingsActivity.SettingsFragment f, String key) {
        RecyclerView list = f.getListView();
        PreferenceGroupAdapter adapter = (PreferenceGroupAdapter) list.getAdapter();
        int pos = adapter.getPreferenceAdapterPosition(key);
        assertTrue(key + " listed", pos >= 0);
        androidx.preference.PreferenceViewHolder h = (androidx.preference.PreferenceViewHolder) adapter.onCreateViewHolder(list, adapter.getItemViewType(pos));
        adapter.onBindViewHolder(h, pos);
        return h.itemView;
    }

    private static SettingsLevel.Row tree(Preference p) {
        SettingsLevel.Row r = new SettingsLevel.Row(p.getKey());
        if (p instanceof PreferenceGroup)
            for (int i = 0; i < ((PreferenceGroup) p).getPreferenceCount(); i++) r.add(tree(((PreferenceGroup) p).getPreference(i)));
        return r;
    }

    /** Every row of the built main page (XML, generated, module cards, the mode's own rows) has a level where it needs one. */
    @Test public void everyBuiltRowHasALevel() {
        mode(true);
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity.SettingsFragment root = (SettingsActivity.SettingsFragment) current(controller.get());
            // a module card open: its rows are built
            Preference card = root.getPreferenceScreen().findPreference(ModuleRows.CARD + "back0");
            assertNotNull(card);
            List<String> missing = SettingsLevel.missing(tree(root.getPreferenceScreen()));
            assertTrue("rows without a level: " + missing, missing.isEmpty());
            assertNotNull(root.findPreference(SettingsLevel.MODE_ROW));
            assertNotNull(root.findPreference(ShadeCatalog.MIRROR));
            assertTrue(SettingsLevel.simple(ShadeCatalog.MIRROR));
            assertEquals(SettingsLevel.Level.SIMPLE, SettingsLevel.declared(ModuleRows.NAME + "back0"));
            assertEquals(SettingsLevel.Level.SIMPLE, SettingsLevel.declared(ModuleRows.BUTTON + "back0"));
            assertEquals(SettingsLevel.Level.ADVANCED, SettingsLevel.declared(ModuleRows.LENS_ROW + "back0"));
            assertEquals(SettingsLevel.Level.ADVANCED, SettingsLevel.declared(ModuleRows.CARD_ID + "back0"));
            assertEquals(SettingsLevel.Level.ADVANCED, SettingsLevel.declared(ModuleRows.ACTIONS));
            assertEquals(SettingsLevel.Level.SIMPLE, SettingsLevel.declared(ModuleRows.GROUP));
            assertEquals(SettingsLevel.Level.ADVANCED, SettingsLevel.declared(ModuleRows.LENS));
        }
    }

    /** The Simple mode's main page and pages: the essentials shown, the technical rows and groups hidden; Advanced: all back. */
    @Test public void simpleShowsTheEssentialsAndAdvancedEverything() {
        mode(true);
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsActivity.SettingsFragment root = (SettingsActivity.SettingsFragment) current(activity);
            PreferenceGroup screen = root.getPreferenceScreen();
            for (String key : new String[]{SettingsLevel.MODE_ROW, SettingsLevel.WIZARD_ROW, "pref_scam_hybrid_processing",
                    "tone_settings_screen", "modules_screen", "output_settings_screen", "photo_size_screen", "watermark_screen",
                    "viewfinder_screen", "photo_style_screen", "backup_settings_screen", "pref_app_key"})
                assertTrue(key + " shown in Simple", shown(screen, key));
            for (String key : new String[]{"scam_hybrid_dnmerge_screen", "pref_scam_hybrid_tone_version", "oldstyle_tone_screen",
                    "pref_scam_hybrid_fringe_method", "root_lab", "root_logs", "shooting_screen", "portrait_screen",
                    "scam_hybrid_why_sharp_screen", "pref_file_name_pattern", "pref_antibanding_hz_key", SettingsLevel.BANNER_ROW})
                assertFalse(key + " hidden in Simple", shown(screen, key));
            List<String> rows = listed(root);
            assertEquals("the mode heads the main page", SettingsLevel.MODE_ROW, rows.get(0));
            assertFalse("no expert group title in the list", rows.contains("root_lab") || rows.contains("root_logs"));
            assertTrue(rows.contains("root_photo"));
            // a Simple page: «Формат файла» shows the format alone, «Настройки тона» its three sliders, a module card its basics
            SettingsActivity.SettingsFragment output = show(activity, "output_settings_screen");
            assertTrue(shown(output.getPreferenceScreen(), "pref_photo_format"));
            for (String key : new String[]{"pref_jpeg_quality", "pref_jpeg_fast_encoder", "pref_dng_lossless", "pref_photo_color_space"})
                assertFalse(key, shown(output.getPreferenceScreen(), key));
            SettingsActivity.SettingsFragment tone = show(activity, "tone_settings_screen");
            assertTrue(shown(tone.getPreferenceScreen(), "pref_scam_hybrid_why_ae_target"));
            assertFalse(shown(tone.getPreferenceScreen(), "scam_hybrid_why_curves_screen"));
            assertFalse(shown(tone.getPreferenceScreen(), "scam_hybrid_hdr_shoulder_category"));
            SettingsActivity.SettingsFragment modules = show(activity, ModuleRows.PAGE);
            Preference card = modules.findPreference(ModuleRows.CARD + "back0");
            card.performClick();
            idle();
            List<String> open = listed(modules);
            assertTrue(open.toString(), open.contains(ModuleRows.NAME + "back0") && open.contains(ModuleRows.ZOOM + "back0"));
            assertFalse(open.toString(), open.contains(ModuleRows.LENS_ROW + "back0") || open.contains(ModuleRows.CARD_ID + "back0")
                    || open.contains(ModuleRows.ADD) || open.contains(ModuleRows.COPY + "back0"));
            // Advanced (from a page: back on the main page every row is there again)
            mode(false);
            activity.getSupportFragmentManager().popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
            root = (SettingsActivity.SettingsFragment) current(activity);
            screen = root.getPreferenceScreen();
            for (String key : new String[]{"scam_hybrid_dnmerge_screen", "pref_scam_hybrid_tone_version", "root_lab", "root_logs",
                    "shooting_screen", "pref_file_name_pattern"})
                assertTrue(key + " shown in Advanced", shown(screen, key));
            assertTrue(listed(root).contains("root_lab"));
        }
    }

    /** Switching the mode back and forth (the switch row, the banner's button) changes no stored value but the mode. */
    @Test public void switchingNeverResetsAValue() {
        mode(false);
        prefs.edit().putString("pref_scam_hybrid_cdm", "0.21").putBoolean("pref_scam_hybrid_denoise", false)
                .putString("pref_jpeg_quality", "77").putString("pref_scam_hybrid_why_ae_target", "0.3").commit();
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity.SettingsFragment root = (SettingsActivity.SettingsFragment) current(controller.get());
            Map<String, Object> before = new HashMap<>(prefs.getAll());
            androidx.preference.ListPreference row = root.findPreference(SettingsLevel.MODE_ROW);
            for (String m : new String[]{SettingsLevel.SIMPLE_MODE, SettingsLevel.CUSTOM_MODE, SettingsLevel.ADVANCED_MODE,
                    SettingsLevel.CUSTOM_MODE, SettingsLevel.SIMPLE_MODE}) {
                assertTrue(row.callChangeListener(m));
                row.setValue(m);
                idle();
            }
            assertTrue(SimpleMode.on(prefs));
            Map<String, Object> after = new HashMap<>(prefs.getAll());
            before.remove(SettingsLevel.MODE_KEY);
            after.remove(SettingsLevel.MODE_KEY);
            assertEquals(before, after);
            assertFalse("hidden, not gone", root.findPreference("pref_scam_hybrid_cdm").isVisible());
        }
    }

    /**
     * The banner: none on a fresh start (every hidden row at this phone's default); a hidden row changed shows it with the count;
     * its reset (confirmed) puts back only the hidden rows, its «Открыть расширенные» switches the mode.
     */
    @Test public void bannerOfChangedHiddenSettings() {
        mode(true);
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsActivity.SettingsFragment root = (SettingsActivity.SettingsFragment) current(activity);
            List<String> fresh = SimpleMode.changed(context, root.getPreferenceScreen(), prefs.getAll(), prefs);
            assertTrue("a fresh start shows no banner: " + fresh, fresh.isEmpty());
            SimpleMode.Banner banner = root.findPreference(SettingsLevel.BANNER_ROW);
            assertFalse(banner.isVisible());
            // two hidden settings and a shown one changed
            prefs.edit().putString("pref_scam_hybrid_cdm", "0.21").putString("pref_jpeg_quality", "77")
                    .putString("pref_scam_hybrid_why_ae_target", "0.3").commit();
            idle();
            assertTrue(banner.isVisible());
            assertEquals(2, banner.count());
            assertEquals(SettingsLevel.BANNER_ROW, listed(root).get(2));
            View reset = bound(root, SettingsLevel.BANNER_ROW).findViewById(R.id.mode_banner_reset);
            assertNotNull("the banner's button", reset);
            reset.performClick();
            Dialog d = ShadowDialog.getLatestDialog();
            assertTrue(d instanceof AlertDialog && d.isShowing());
            ((AlertDialog) d).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            idle();
            activity = controller.get();   // the settings built anew (every row reads its value again)
            assertEquals("0.3", prefs.getString("pref_scam_hybrid_why_ae_target", null));
            assertEquals(PageReset.defaultOf(context, "pref_jpeg_quality", null), prefs.getString("pref_jpeg_quality", null));
            assertFalse(com.scamera.app.settings.SettingsLevel.differs(prefs.getAll().get("pref_scam_hybrid_cdm"),
                    PageReset.defaultOf(context, "pref_scam_hybrid_cdm", null)));
            root = (SettingsActivity.SettingsFragment) current(activity);
            assertFalse(((SimpleMode.Banner) root.findPreference(SettingsLevel.BANNER_ROW)).isVisible());
            // changed again: «Открыть расширенные»
            prefs.edit().putString("pref_jpeg_quality", "60").commit();
            idle();
            bound(root, SettingsLevel.BANNER_ROW).findViewById(R.id.mode_banner_open).performClick();
            idle();
            assertFalse(SimpleMode.on(prefs));
            assertEquals("60", prefs.getString("pref_jpeg_quality", null));
            assertTrue(root.findPreference("pref_jpeg_quality").isVisible() || shown(root.getPreferenceScreen(), "output_settings_screen"));
            assertFalse(root.findPreference(SettingsLevel.BANNER_ROW).isVisible());
        }
    }

    /** A fresh install starts in Simple with the wizard due; an update from before the modes stays Advanced without it. */
    @Test public void wizardOnlyOnAFreshInstall() {
        SharedPreferences version = context.getSharedPreferences(context.getPackageName() + PreferenceKeys.Key.KEY_PREF_VERSION.mValue, 0);
        // an update: the layout version is stored, the mode is not
        version.edit().clear().commit();
        manager.set(PreferenceKeys.Key.KEY_PREF_VERSION.mValue, PreferenceKeys.Key.KEY_PREF_VERSION, 4);
        prefs.edit().remove(SettingsLevel.MODE_KEY).remove(SettingsLevel.WIZARD_KEY).putString("pref_jpeg_quality", "70").commit();
        MigrationManager.migrate(manager);
        assertEquals(SettingsLevel.ADVANCED_MODE, prefs.getString(SettingsLevel.MODE_KEY, null));
        assertFalse(SettingsWizard.pending(prefs));
        assertEquals("nothing wiped", "70", prefs.getString("pref_jpeg_quality", null));
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            assertFalse(SettingsWizard.maybeShowFirstRun(controller.get()));
            assertFalse(SettingsWizard.showing());
        }
        // a fresh install: no layout version stored
        version.edit().clear().commit();
        prefs.edit().clear().commit();
        MigrationManager.migrate(manager);
        assertEquals(SettingsLevel.SIMPLE_MODE, prefs.getString(SettingsLevel.MODE_KEY, null));
        assertTrue(SettingsWizard.pending(prefs));
        // the next start: the mode stays, the wizard still due until it is done
        MigrationManager.migrate(manager);
        assertEquals(SettingsLevel.SIMPLE_MODE, prefs.getString(SettingsLevel.MODE_KEY, null));
        assertTrue(SettingsWizard.pending(prefs));
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            assertTrue(SettingsWizard.maybeShowFirstRun(controller.get()));
            idle();
            assertTrue(SettingsWizard.showing());
            assertFalse("one at a time", SettingsWizard.maybeShowFirstRun(controller.get()));
            // closed without finishing: not asked again, the mode as it was
            ShadowDialog.getLatestDialog().cancel();
            idle();
            assertFalse(SettingsWizard.pending(prefs));
            assertFalse(SettingsWizard.maybeShowFirstRun(controller.get()));
            assertTrue(SimpleMode.on(prefs));
        }
    }

    /** The search finds the rows the Simple mode hides, marked; their hit asks for the Advanced mode and then opens them. */
    @Test public void searchFindsHiddenRows() {
        mode(true);
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsActivity.SettingsFragment root = (SettingsActivity.SettingsFragment) current(activity);
            Map<String, SettingsSearchFragment.Entry> index = new HashMap<>();
            for (SettingsSearchFragment.Entry e : root.searchIndex()) index.put(e.key, e);
            for (String key : new String[]{"pref_scam_hybrid_cdm", "pref_jpeg_quality", "scam_hybrid_dnmerge_screen",
                    "pref_scam_hybrid_tone_version", "pref_af_mode_key"})
                assertTrue(key + " found and marked", index.containsKey(key) && index.get(key).advanced);
            for (String key : new String[]{"pref_photo_format", "pref_show_grid_key", "pref_scam_hybrid_processing"})
                assertTrue(key + " found, not marked", index.containsKey(key) && !index.get(key).advanced);
            assertFalse("the hidden row's words are found", root.searchIndex().stream().noneMatch(e -> e.advanced && e.matches("JPEG")));
            activity.openSearchResult(index.get("pref_jpeg_quality"));
            Dialog d = ShadowDialog.getLatestDialog();
            assertTrue(d instanceof AlertDialog && d.isShowing());
            assertTrue(SimpleMode.on(prefs));
            ((AlertDialog) d).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Fragment opened = current(activity);
            assertFalse(SimpleMode.on(prefs));
            assertTrue(opened instanceof SettingsActivity.SettingsFragment);
            assertEquals("output_settings_screen", opened.requireArguments().getString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT));
            assertTrue(((SettingsActivity.SettingsFragment) opened).findPreference("pref_jpeg_quality").isVisible());
        }
    }

    /** The wizard's steps with the system's animations off and on: no crash, the choices written, the switch follows. */
    @Test public void wizardWithAndWithoutAnimations() {
        for (float scale : new float[]{0f, 1f}) {
            animatorScale(scale);
            mode(false);
            prefs.edit().putString("pref_scam_hybrid_processing", "fast").putBoolean("pref_show_watermark_key", true).commit();
            FormatChoice.store(FormatChoice.JPEG);
            PhotoStyle.apply(prefs, PhotoStyle.NORMAL);
            prefs.edit().putString("pref_scam_hybrid_tone_version", "v1").commit();
            try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
                controller.setup();
                SettingsActivity activity = controller.get();
                SettingsActivity.SettingsFragment root = (SettingsActivity.SettingsFragment) current(activity);
                root.findPreference(SettingsLevel.WIZARD_ROW).performClick();
                idle();
                assertTrue(SettingsWizard.showing());
                View sheet = ShadowDialog.getLatestDialog().getWindow().getDecorView();
                assertEquals("Advanced now: one step", View.GONE, sheet.findViewWithTag("wizard_back").getVisibility());
                sheet.findViewWithTag("wizard_simple").performClick();
                idle();
                sheet.findViewWithTag("wizard_next").performClick();
                idle();
                assertNotNull(sheet.findViewWithTag("wizard_step_1"));
                sheet.findViewWithTag("wizard_quality").performClick();
                sheet.findViewWithTag("wizard_raw_1").performClick();
                idle();
                sheet.findViewWithTag("wizard_next").performClick();
                idle();
                sheet.findViewWithTag("wizard_style_vivid").performClick();
                sheet.findViewWithTag("wizard_watermark_0").performClick();
                // back and forth keeps the choices
                sheet.findViewWithTag("wizard_back").performClick();
                idle();
                sheet.findViewWithTag("wizard_next").performClick();
                idle();
                assertNull("the old step is gone", sheet.findViewWithTag("wizard_step_1"));
                sheet.findViewWithTag("wizard_next").performClick();   // «Готово»
                idle();
                assertFalse(SettingsWizard.showing());
                assertTrue(SimpleMode.on(prefs));
                assertEquals("quality", prefs.getString("pref_scam_hybrid_processing", null));
                assertEquals(FormatChoice.RAW_JPEG, FormatChoice.current());
                assertFalse(PreferenceKeys.isShowWatermarkOn());
                assertEquals(PhotoStyle.VIVID, PhotoStyle.current(prefs));
                assertEquals("a preset is a V2 tone", "v2", prefs.getString("pref_scam_hybrid_tone_version", ""));
                assertFalse(SettingsWizard.pending(prefs));
                // the main page follows: its switch and processing row, the Simple rows
                assertEquals(SettingsLevel.SIMPLE_MODE, ((androidx.preference.ListPreference) root.findPreference(SettingsLevel.MODE_ROW)).getValue());
                assertEquals("quality", ((androidx.preference.ListPreference) root.findPreference("pref_scam_hybrid_processing")).getValue());
                assertFalse(shown(root.getPreferenceScreen(), "root_lab"));
                // the switch back to Advanced animates the rows in (or places them at once)
                mode(false);
                idle();
                assertTrue(shown(root.getPreferenceScreen(), "root_lab"));
                // the wizard choosing Advanced: one step, only the mode written
                root.findPreference(SettingsLevel.WIZARD_ROW).performClick();
                idle();
                sheet = ShadowDialog.getLatestDialog().getWindow().getDecorView();
                sheet.findViewWithTag("wizard_advanced").performClick();
                idle();
                sheet.findViewWithTag("wizard_next").performClick();
                idle();
                assertFalse(SimpleMode.on(prefs));
                assertEquals("quality", prefs.getString("pref_scam_hybrid_processing", null));
            }
        }
    }

    // ───── the Custom mode

    private CustomSetFragment editor(SettingsActivity activity) {
        SettingsActivity.SettingsFragment root = (SettingsActivity.SettingsFragment) current(activity);
        root.findPreference(SettingsLevel.CUSTOM_ROW).performClick();
        Fragment f = current(activity);
        assertTrue(String.valueOf(f), f instanceof CustomSetFragment);
        return (CustomSetFragment) f;
    }

    private static CustomSetFragment.Group group(CustomSetFragment f, String key) {
        for (CustomSetFragment.Group g : f.groups()) if (g.key.equals(key)) return g;
        throw new AssertionError("no group " + key);
    }

    /**
     * Custom: the stored set decides (a non-Simple setting chosen is shown with its page and group, a Simple one taken out is
     * hidden); with no choices it is the Simple set; a setting no choice names follows its level (the new-key policy);
     * «Настроить…» only in this mode.
     */
    @Test public void customShowsTheChosenSettings() {
        mode(SettingsLevel.CUSTOM_MODE);
        com.scamera.app.settings.CustomSet set = new com.scamera.app.settings.CustomSet(null, null);
        set.set("pref_jpeg_quality", false, true);
        set.set("pref_scam_hybrid_cdm", false, true);
        set.set("pref_show_grid_key", true, false);
        SimpleMode.store(prefs, set);
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsActivity.SettingsFragment root = (SettingsActivity.SettingsFragment) current(activity);
            PreferenceGroup screen = root.getPreferenceScreen();
            assertTrue(shown(screen, SettingsLevel.CUSTOM_ROW));
            assertTrue("a chosen setting brings its page", shown(screen, "scam_hybrid_dnmerge_screen"));
            assertTrue(shown(screen, "output_settings_screen"));
            assertFalse(shown(screen, "root_lab"));
            assertFalse(shown(screen, "oldstyle_tone_screen"));
            SettingsActivity.SettingsFragment output = show(activity, "output_settings_screen");
            assertTrue(shown(output.getPreferenceScreen(), "pref_photo_format"));
            assertTrue("a chosen setting", shown(output.getPreferenceScreen(), "pref_jpeg_quality"));
            assertFalse(shown(output.getPreferenceScreen(), "pref_dng_lossless"));
            SettingsActivity.SettingsFragment merge = show(activity, "scam_hybrid_dnmerge_screen");
            assertTrue(shown(merge.getPreferenceScreen(), "pref_scam_hybrid_cdm"));
            assertFalse(shown(merge.getPreferenceScreen(), "pref_scam_hybrid_denoise"));
            List<String> rows = listed(merge);
            assertTrue(rows.toString(), rows.size() <= 3);   // the setting and its group
            SettingsActivity.SettingsFragment viewfinder = show(activity, "viewfinder_screen");
            assertFalse("a Simple setting taken out", shown(viewfinder.getPreferenceScreen(), "pref_show_grid_key"));
            assertTrue(shown(viewfinder.getPreferenceScreen(), "pref_horizon"));
            // a setting of a later version: a Simple one shows, any other stays hidden until chosen
            Preference future = new Preference(root.requireContext());
            future.setKey("pref_future_setting");
            future.setTitle("Future");
            ((PreferenceGroup) screen.findPreference("root_photo")).addPreference(future);
            SimpleMode.Filter f = SimpleMode.filter(prefs, screen, false);
            assertTrue(f.hides(future));
            assertFalse(f.hides(screen.findPreference("pref_horizon")));
            set = SimpleMode.custom(prefs);
            set.set("pref_future_setting", SimpleMode.simpleLevel(future), true);
            SimpleMode.store(prefs, set);
            assertFalse(SimpleMode.filter(prefs, screen, false).hides(future));
            // the stored choices: the user's changes against the levels
            assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("pref_jpeg_quality", "pref_scam_hybrid_cdm", "pref_future_setting")),
                    prefs.getStringSet(com.scamera.app.settings.CustomSet.SHOWN_KEY, null));
            assertEquals(java.util.Collections.singleton("pref_show_grid_key"),
                    prefs.getStringSet(com.scamera.app.settings.CustomSet.HIDDEN_KEY, null));
            // the Simple mode: «Настроить…» goes; the set is kept
            mode(true);
            activity.getSupportFragmentManager().popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
            root = (SettingsActivity.SettingsFragment) current(activity);
            assertFalse(shown(root.getPreferenceScreen(), SettingsLevel.CUSTOM_ROW));
            assertFalse(shown(root.getPreferenceScreen(), "scam_hybrid_dnmerge_screen"));
            assertEquals(3, SimpleMode.custom(prefs).shown().size());
        }
    }

    /**
     * «Свой набор»: a group's mark (all / some / none, a tap shows all or hides all), a setting's switch, «Показать все» /
     * «Только главное», the count; stored at once; the main page follows; no value changes - with the animations off and on.
     */
    @Test public void customSetEditor() {
        for (float scale : new float[]{0f, 1f}) {
            animatorScale(scale);
            mode(SettingsLevel.CUSTOM_MODE);
            SimpleMode.store(prefs, new com.scamera.app.settings.CustomSet(null, null));
            try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
                controller.setup();
                SettingsActivity activity = controller.get();
                Map<String, Object> values = new HashMap<>(prefs.getAll());
                CustomSetFragment f = editor(activity);
                CustomSetFragment.Group watermark = group(f, "watermark_screen");
                assertEquals(com.scamera.app.settings.CustomSet.ALL, f.set().state(watermark.keys(), watermark.levels()));
                CustomSetFragment.Group merge = group(f, "scam_hybrid_dnmerge_screen");
                assertEquals(com.scamera.app.settings.CustomSet.NONE, f.set().state(merge.keys(), merge.levels()));
                int[] before = f.shown();
                View counter = f.requireView().findViewWithTag("custom_counter");
                assertTrue(((android.widget.TextView) counter).getText().toString().contains(String.valueOf(before[1])));
                // the group's mark on screen: a tap hides the whole watermark page's settings
                View mark = f.requireView().findViewWithTag("custom_group_check_watermark_screen");
                if (mark == null) f.toggleGroup(watermark);
                else mark.performClick();
                idle();
                assertEquals(com.scamera.app.settings.CustomSet.NONE, f.set().state(watermark.keys(), watermark.levels()));
                assertTrue(SimpleMode.custom(prefs).hidden().containsAll(watermark.keys()));
                assertEquals(before[0] - watermark.items.size(), f.shown()[0]);
                // one setting back: some
                f.setItem(watermark.items.get(0), true);
                assertEquals(com.scamera.app.settings.CustomSet.SOME, f.set().state(watermark.keys(), watermark.levels()));
                f.toggleGroup(watermark);   // some -> all
                assertEquals(com.scamera.app.settings.CustomSet.ALL, f.set().state(watermark.keys(), watermark.levels()));
                // a group opened: its switches; one switched on
                f.toggleOpen(merge);
                idle();
                View leaf = f.requireView().findViewWithTag("custom_leaf_" + merge.items.get(0).key);
                if (leaf == null) f.setItem(merge.items.get(0), true);
                else leaf.performClick();
                idle();
                assertEquals(com.scamera.app.settings.CustomSet.SOME, f.set().state(merge.keys(), merge.levels()));
                assertTrue(SimpleMode.custom(prefs).shown().contains(merge.items.get(0).key));
                f.toggleOpen(merge);
                idle();
                // the search narrows the groups
                ((android.widget.EditText) f.requireView().findViewWithTag("custom_search")).setText("JPEG");
                idle();
                f.requireView().findViewWithTag("custom_all").performClick();
                idle();
                assertEquals(f.shown()[1], f.shown()[0]);
                assertTrue(((android.widget.TextView) counter).getText().toString().contains(f.shown()[1] + ""));
                f.requireView().findViewWithTag("custom_essentials").performClick();
                idle();
                assertTrue(SimpleMode.custom(prefs).shown().isEmpty() && SimpleMode.custom(prefs).hidden().isEmpty());
                assertEquals(before[0], f.shown()[0]);
                // back on the main page: its rows follow the set; no value changed but the set
                f.setItem(merge.items.get(0), true);
                activity.getOnBackPressedDispatcher().onBackPressed();
                SettingsActivity.SettingsFragment root = (SettingsActivity.SettingsFragment) current(activity);
                assertTrue(shown(root.getPreferenceScreen(), "scam_hybrid_dnmerge_screen"));
                Map<String, Object> after = new HashMap<>(prefs.getAll());
                for (String k : new String[]{com.scamera.app.settings.CustomSet.SHOWN_KEY, com.scamera.app.settings.CustomSet.HIDDEN_KEY}) {
                    after.remove(k);
                    values.remove(k);
                }
                assertEquals(values, after);
            }
        }
    }

    /** Custom: a hidden setting's search hit offers to show it in the set (the mode stays) and opens it. */
    @Test public void customSearchShowsTheSetting() {
        mode(SettingsLevel.CUSTOM_MODE);
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsActivity.SettingsFragment root = (SettingsActivity.SettingsFragment) current(activity);
            SettingsSearchFragment.Entry hit = null;
            for (SettingsSearchFragment.Entry e : root.searchIndex()) if (e.key.equals("pref_af_mode_key")) hit = e;
            assertNotNull(hit);
            assertTrue(hit.advanced && hit.setting && !hit.simple);
            activity.openSearchResult(hit);
            Dialog d = ShadowDialog.getLatestDialog();
            assertTrue(d instanceof AlertDialog && d.isShowing());
            ((AlertDialog) d).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Fragment opened = current(activity);
            assertEquals(SimpleMode.CUSTOM, SimpleMode.mode(prefs));
            assertTrue(SimpleMode.custom(prefs).shown().contains("pref_af_mode_key"));
            assertEquals("focus_screen", opened.requireArguments().getString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT));
            assertTrue(shown(((SettingsActivity.SettingsFragment) opened).getPreferenceScreen(), "pref_af_mode_key"));
        }
    }

    /** The wizard's third choice: Custom from everything (the set takes every setting) or from the essentials; its set opens. */
    @Test public void wizardCustom() {
        mode(false);
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsActivity.SettingsFragment root = (SettingsActivity.SettingsFragment) current(activity);
            root.findPreference(SettingsLevel.WIZARD_ROW).performClick();
            idle();
            View sheet = ShadowDialog.getLatestDialog().getWindow().getDecorView();
            sheet.findViewWithTag("wizard_custom").performClick();
            sheet.findViewWithTag("wizard_next").performClick();
            idle();
            sheet.findViewWithTag("wizard_start_all").performClick();
            sheet.findViewWithTag("wizard_next").performClick();   // «Готово»
            idle();
            assertEquals(SimpleMode.CUSTOM, SimpleMode.mode(prefs));
            assertNull("taken from the tree", prefs.getString(com.scamera.app.settings.CustomSet.START_KEY, null));
            assertTrue(SimpleMode.custom(prefs).shown().contains("pref_scam_hybrid_cdm"));
            assertTrue(current(activity) instanceof CustomSetFragment);
            activity.getOnBackPressedDispatcher().onBackPressed();
            root = (SettingsActivity.SettingsFragment) current(activity);
            assertTrue(shown(root.getPreferenceScreen(), "root_lab"));
            // again, from the essentials: the choices dropped
            root.findPreference(SettingsLevel.WIZARD_ROW).performClick();
            idle();
            sheet = ShadowDialog.getLatestDialog().getWindow().getDecorView();
            assertNotNull(sheet.findViewWithTag("wizard_custom"));
            sheet.findViewWithTag("wizard_next").performClick();
            idle();
            sheet.findViewWithTag("wizard_start_essentials").performClick();
            sheet.findViewWithTag("wizard_next").performClick();
            idle();
            assertTrue(SimpleMode.custom(prefs).shown().isEmpty());
            assertEquals(SimpleMode.CUSTOM, SimpleMode.mode(prefs));
        }
    }
}
