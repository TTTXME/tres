package com.scamera.app.ui.settings;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.scamera.app.R;
import com.scamera.app.api.VendorTagUtils;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.settings.FullResProfiles;
import com.scamera.app.settings.ModuleRegistry;
import com.scamera.app.settings.PreferenceKeys;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * P592 (owner, 2026-11-10: «Убираем все описания у параметров, оставляем подробное описание только по зажатию на параметре»;
 * «Речь про полностью все настройки»): every page the settings open - each PreferenceScreen reachable from the main page, the
 * module pages (a module's lens page with its generated sensor rows and camera key, its own photo settings, «Камеры и модули»
 * with an open card), the developer page, the lab - shows under a row's title nothing but its state (SettingHelp.stateLine), no
 * page subtitle or tip line, and every keyed row answers a long press with a non-empty description.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "w400dp-h880dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SettingsCoverageTest {
    private Context context;
    private SharedPreferences prefs;
    private MockedStatic<ScameraApp> camera;
    private MockedStatic<FullResProfiles> fullRes;

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
        fullRes = mockStatic(FullResProfiles.class, CALLS_REAL_METHODS);
        fullRes.when(() -> FullResProfiles.variants("back1")).thenReturn(Arrays.asList("back1@full50", "back1@full200"));
        // the phone: 1× (ID 0, the camera's), 3× (ID 4, a camera key), 0.6× (ID 2), the front (ID 1), a 1.3× copy of 1×
        SharedPreferences.Editor e = prefs.edit();
        String[][] backs = {{"0", "1×"}, {"4", "3×"}, {"2", "0.6×"}};
        for (int i = 0; i < backs.length; i++)
            e.putString("module_auto_back" + i, backs[i][0]).putString("module_label_back" + i, backs[i][1]).putBoolean("module_visible_back" + i, true);
        for (int i = 3; i < 8; i++) e.putString("module_auto_back" + i, "0").putString("module_label_back" + i, "1×").putBoolean("module_visible_back" + i, false);
        e.putString("module_auto_front0", "1").putString("module_label_front0", "1×").putBoolean("module_visible_front0", true);
        e.putString("module_active", "back0");
        VendorTagUtils.TunableKey key = new VendorTagUtils.TunableKey();
        key.kind = VendorTagUtils.TunableKey.TYPE_SESSION;
        key.tag = "vivo.control.forceSensorMode";
        key.text = "3";
        key.sent = true;
        key.known = true;
        e.putString("pref_sensorconfig_back1_tunablekeys", new Gson().toJson(Collections.singletonList(key)));
        e.commit();
        ModuleRegistry.duplicate("back0", 1.3f);
    }

    @After public void tearDown() {
        DialogStyle.dismissShown();
        if (fullRes != null) fullRes.close();
        if (camera != null) camera.close();
    }

    private static void idle() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
    }

    private static Fragment current(SettingsActivity activity) {
        FragmentManager fm = activity.getSupportFragmentManager();
        fm.executePendingTransactions();
        idle();
        return fm.findFragmentById(R.id.settings_container);
    }

    private static void show(SettingsActivity activity, Fragment f) {
        activity.getSupportFragmentManager().beginTransaction().replace(R.id.settings_container, f).addToBackStack(null).commit();
    }

    private static SettingsActivity.SettingsFragment page(@androidx.annotation.Nullable String root) {
        SettingsActivity.SettingsFragment f = new SettingsActivity.SettingsFragment();
        Bundle args = new Bundle();
        if (root != null) args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, root);
        f.setArguments(args);
        return f;
    }

    private static void screens(PreferenceGroup g, List<String> out) {
        for (int i = 0; i < g.getPreferenceCount(); i++) {
            Preference p = g.getPreference(i);
            if (p instanceof PreferenceScreen && p.getKey() != null) out.add(p.getKey());
            if (p instanceof PreferenceGroup) screens((PreferenceGroup) p, out);
        }
    }

    /** The texts shown outside the list (the header, the chips): a description there is a page subtitle, hint or note. */
    private static void texts(View v, List<String> out) {
        if (v instanceof RecyclerView || v.getVisibility() != View.VISIBLE) return;
        if (v instanceof TextView && ((TextView) v).getText() != null && ((TextView) v).getText().length() > 0) out.add(((TextView) v).getText().toString());
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) texts(((ViewGroup) v).getChildAt(i), out);
    }

    private static final String[] GONE_TAGS = {"settings_tips", "format_summary", "format_photo_line", "shade_transparency_hint"};

    /** One page: its rows bound as the list binds them; returns how many keyed rows it checked. */
    private int check(SettingsActivity.SettingsFragment f, String name, List<String> failures) {
        PreferenceScreen screen = f.getPreferenceScreen();
        List<String> outside = new ArrayList<>();
        texts(f.requireView(), outside);
        CharSequence about = screen == null ? null : screen.getSummary();
        for (String t : outside) {
            if (about != null && about.length() > 0 && t.equals(about.toString())) failures.add(name + ": the page's description under its title: " + t);
            if (t.startsWith("Hold a row")) failures.add(name + ": the tip line");
        }
        for (String tag : GONE_TAGS) if (f.requireView().findViewWithTag(tag) != null) failures.add(name + ": " + tag);
        RecyclerView list = f.getListView();
        PreferenceGroupAdapter adapter = (PreferenceGroupAdapter) list.getAdapter();
        int rows = 0;
        for (int i = 0; i < adapter.getItemCount(); i++) {
            Preference p = adapter.getItem(i);
            if (p == null) continue;
            PreferenceViewHolder holder = (PreferenceViewHolder) adapter.onCreateViewHolder(list, adapter.getItemViewType(i));
            adapter.onBindViewHolder(holder, i);
            for (String tag : GONE_TAGS) if (holder.itemView.findViewWithTag(tag) != null) failures.add(name + "/" + p.getKey() + ": " + tag);
            View summary = holder.findViewById(android.R.id.summary);
            if (summary instanceof TextView && summary.getVisibility() == View.VISIBLE && ((TextView) summary).getText().length() > 0) {
                CharSequence state = SettingHelp.stateLine(p);
                String shown = ((TextView) summary).getText().toString().replaceAll("…$", "");
                if (state == null || !state.toString().startsWith(shown))
                    failures.add(name + "/" + p.getKey() + " shows a description: " + shown);
            }
            if (p.getKey() == null || p instanceof PreferenceCategory) continue;
            rows++;
            CharSequence help = SettingHelp.text(f.requireContext(), p);
            if (help == null || help.toString().trim().isEmpty()) failures.add(name + "/" + p.getKey() + ": no long-press description");
            if (SettingHelp.helps(p) && !holder.itemView.isLongClickable()) failures.add(name + "/" + p.getKey() + ": no long press");
        }
        return rows;
    }

    @Test public void everyPageShowsOnlyStatesAndEveryRowHasItsLongPress() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            List<String> failures = new ArrayList<>();
            SettingsActivity.SettingsFragment root = (SettingsActivity.SettingsFragment) current(activity);
            int rows = check(root, "main", failures);
            List<String> pages = new ArrayList<>();
            screens(root.getPreferenceScreen(), pages);
            assertTrue("pages: " + pages, pages.size() >= 30);
            int checkedPages = 1;
            for (String key : pages) {
                if (ScamLabFragment.SCREEN.equals(key)) continue;   // its own fragment: below
                show(activity, page(key));
                Fragment f = current(activity);
                assertTrue(key + ": " + f, f instanceof SettingsActivity.SettingsFragment);
                rows += check((SettingsActivity.SettingsFragment) f, key, failures);
                checkedPages++;
            }
            // the module pages: «Камеры и модули» with the 1× card open, a module's lens page (generated sensor rows, its camera
            // key, the probe), its own photo settings and one of their pages, the FULLRES profile's page
            show(activity, page(ModuleRows.PAGE));
            SettingsActivity.SettingsFragment modules = (SettingsActivity.SettingsFragment) current(activity);
            Preference card = modules.findPreference(ModuleRows.CARD + "back0");
            assertNotNull(card);
            card.performClick();
            idle();
            rows += check(modules, "modules with a card open", failures);
            for (Fragment f : new Fragment[]{SettingsActivity.SettingsFragment.sensorPage("back1"),
                    SettingsActivity.SettingsFragment.modulePage("back1", null),
                    SettingsActivity.SettingsFragment.modulePage("back1", "scam_hybrid_why_ae_screen"),
                    SettingsActivity.SettingsFragment.modulePage("back1@full50", null)}) {
                show(activity, f);
                Fragment shown = current(activity);
                assertTrue(String.valueOf(shown), shown instanceof SettingsActivity.SettingsFragment);
                rows += check((SettingsActivity.SettingsFragment) shown, "module " + f.getArguments(), failures);
                checkedPages++;
            }
            // the lab: its explanations are long presses, not text on the page
            activity.openLab();
            Fragment lab = current(activity);
            assertTrue(String.valueOf(lab), lab instanceof ScamLabFragment);
            List<String> labTexts = new ArrayList<>();
            texts(lab.requireView(), labTexts);
            for (String t : labTexts)
                if (t.startsWith("Take a burst with the switch below") || t.equals("The settings on your own RAW bursts")
                        || t.startsWith("Every SHDR shot keeps") || t.startsWith("Every shot keeps"))
                    failures.add("lab: " + t);
            System.out.println("P592 coverage: " + checkedPages + " pages, " + rows + " keyed rows");
            assertTrue(String.join("\n", failures), failures.isEmpty());
            assertTrue("rows checked: " + rows, rows > 500);
            assertTrue("pages checked: " + checkedPages, checkedPages > 35);
        }
    }
}
