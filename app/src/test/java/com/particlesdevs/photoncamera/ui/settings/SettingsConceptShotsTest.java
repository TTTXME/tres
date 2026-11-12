package com.particlesdevs.photoncamera.ui.settings;

import static org.junit.Assert.assertNotNull;
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

/**
 * P584 (owner, 2026-11-09: «Убедись что всё выглядит один в один как на концептах»): the settings in the concept's states at its
 * 390 x 844 px (mdpi: 1 px = 1 dp), written to build/reports/concept-compare/set_*_app.png for a side-by-side with the concept
 * boards (research/concept). Each state also checks what the concept fixes: the header's 40 dp discs and its centred crumb.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SettingsConceptShotsTest {
    private static final int W = 390, H = 844;
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

    private static Fragment current(SettingsActivity activity) {
        FragmentManager fm = activity.getSupportFragmentManager();
        fm.executePendingTransactions();
        idleFor(800);
        return fm.findFragmentById(R.id.settings_container);
    }

    /** The activity's window as drawn, with the {@code over} windows (popups, dialogs) on top, written as {@code name}. */
    private static void shot(SettingsActivity activity, String name, View... over) throws Exception {
        idleFor(1200);
        View root = activity.getWindow().getDecorView();
        Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(SettingsStyle.PAGE);
        Canvas canvas = new Canvas(bitmap);
        root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, W, H);
        root.draw(canvas);
        for (View v : over) window(canvas, v);
        File dir = new File("build/reports/concept-compare");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    /** One more window over the canvas: its dim, then the window at its gravity (popups: their x / y). */
    private static void window(Canvas canvas, View root) {
        ViewGroup.LayoutParams p = root.getLayoutParams();
        WindowManager.LayoutParams lp = p instanceof WindowManager.LayoutParams ? (WindowManager.LayoutParams) p : new WindowManager.LayoutParams();
        if ((lp.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) canvas.drawColor(Math.round(lp.dimAmount * 255) << 24);
        int ws = lp.width > 0 ? View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY)
                : View.MeasureSpec.makeMeasureSpec(W, lp.width == ViewGroup.LayoutParams.MATCH_PARENT ? View.MeasureSpec.EXACTLY : View.MeasureSpec.AT_MOST);
        int hs = lp.height > 0 ? View.MeasureSpec.makeMeasureSpec(lp.height, View.MeasureSpec.EXACTLY)
                : View.MeasureSpec.makeMeasureSpec(H, lp.height == ViewGroup.LayoutParams.MATCH_PARENT ? View.MeasureSpec.EXACTLY : View.MeasureSpec.AT_MOST);
        root.measure(ws, hs);
        int w = root.getMeasuredWidth(), h = root.getMeasuredHeight();
        root.layout(0, 0, w, h);
        int x = lp.x, y = lp.y;
        int g = lp.gravity;
        if (g != Gravity.NO_GRAVITY && g != (Gravity.TOP | Gravity.START) && g != (Gravity.TOP | Gravity.LEFT)) {
            if ((g & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL) x = (W - w) / 2 + lp.x;
            if ((g & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.CENTER_VERTICAL) y = (H - h) / 2 + lp.y;
            if ((g & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM) y = H - h - lp.y;
        }
        canvas.save();
        canvas.translate(x, y);
        root.draw(canvas);
        canvas.restore();
    }

    private static PreferenceGroupAdapter adapter(SettingsActivity.SettingsFragment f) {
        return (PreferenceGroupAdapter) f.getListView().getAdapter();
    }

    /** {@code key}'s row scrolled to {@code offset} px under the list's top; its view. */
    private static View scrollTo(SettingsActivity.SettingsFragment f, String key, int offset) {
        Preference p = f.findPreference(key);
        assertNotNull(key, p);
        RecyclerView list = f.getListView();
        int pos = adapter(f).getPreferenceAdapterPosition(p);
        ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(pos, offset);
        idleFor(500);
        RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(pos);
        assertNotNull(key, h);
        return h.itemView;
    }

    private static void open(SettingsActivity.SettingsFragment f, String key) {
        Preference p = f.findPreference(key);
        assertNotNull(key, p);
        p.performClick();
    }

    @Test public void settingsInTheConceptStates() throws Exception {
        // the phone of the concept's «Камеры и модули»: 0.4× (ID 4), 1× (ID 3, the active one), 2.4× (ID 5), the front 1× (ID 1)
        SharedPreferences.Editor e = prefs.edit();
        String[][] backs = {{"3", "1×"}, {"4", "0.4×"}, {"5", "2.4×"}};
        for (int i = 0; i < backs.length; i++)
            e.putString("module_auto_back" + i, backs[i][0]).putString("module_label_back" + i, backs[i][1]).putBoolean("module_visible_back" + i, true);
        e.putString("module_auto_front0", "1").putString("module_label_front0", "1×").putBoolean("module_visible_front0", true);
        e.putString("module_active", "back0").commit();
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class)) {
            controller.setup();
            SettingsActivity activity = controller.get();
            SettingsActivity.SettingsFragment root = shown(activity);
            shot(activity, "set_root_app");

            // the menu: «Звуки» on the main page, as the concept's
            View row = scrollTo(root, "pref_camera_sounds_all", 480);
            PopupWindow menu = SettingsStyle.listMenu(row, (ListPreference) root.findPreference("pref_camera_sounds_all"));
            idleFor(800);
            shot(activity, "set_menu_app", menu.getContentView().getRootView());
            menu.dismiss();
            idleFor(300);
            root.getListView().scrollToPosition(0);
            idleFor(300);

            // a page with sliders, a segmented control, switches: «Настройки нейросети»
            open(root, "fringe_net_screen");
            SettingsActivity.SettingsFragment page = shown(activity);
            shot(activity, "set_page_app");
            View reset = page.requireView().findViewWithTag("settings_page_reset");
            if (reset == null) reset = activity.getWindow().getDecorView().findViewWithTag("settings_page_reset");
            assertNotNull("the header's reset disc", reset);
            reset.performClick();
            idleFor(800);
            android.app.Dialog alert = ShadowDialog.getLatestDialog();
            assertNotNull(alert);
            shot(activity, "set_alert_app", alert.getWindow().getDecorView());
            alert.dismiss();
            activity.getOnBackPressedDispatcher().onBackPressed();
            root = shown(activity);

            // the number editor over «Шумоподавление и склейка» (P629)
            open(root, "scam_hybrid_dnmerge_screen");
            SettingsActivity.SettingsFragment merge = shown(activity);
            com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                    PrecisionEditor.show(activity, null, context.getString(R.string.zsl_buffer_count), 1, 100, 50, 50, false, v -> { });
            idleFor(1200);
            shot(activity, "set_number_app", sheet.getWindow().getDecorView());
            sheet.dismiss();
            activity.getOnBackPressedDispatcher().onBackPressed();
            root = shown(activity);

            // «Камеры и модули»
            open(root, "modules_screen");
            SettingsActivity.SettingsFragment modules = shown(activity);
            shot(activity, "set_modules_closed_app");
            Preference card = null;
            for (int i = 0; i < adapter(modules).getItemCount(); i++) {
                Preference p = adapter(modules).getItem(i);
                if (p != null && p.getKey() != null && p.getKey().equals(ModuleRows.CARD + "back0")) { card = p; break; }
            }
            if (card != null) {
                card.performClick();
                idleFor(1000);
                shot(activity, "set_modules_open_app");
            }
            activity.getOnBackPressedDispatcher().onBackPressed();
            root = shown(activity);

            // the film page
            open(root, "photo_style_screen");
            SettingsActivity.SettingsFragment style = shown(activity);
            open(style, "film_sim_screen");
            current(activity);
            shot(activity, "set_film_app");
            activity.getOnBackPressedDispatcher().onBackPressed();
            activity.getOnBackPressedDispatcher().onBackPressed();
            root = shown(activity);

            // the search: «Яркость»
            View search = activity.getWindow().getDecorView().findViewWithTag("settings_search");
            if (search != null) search.performClick();
            Fragment f = current(activity);
            EditText field = f.requireView().findViewWithTag("search_field");
            if (field == null) field = findEdit(f.requireView());
            assertNotNull("the search field", field);
            field.setText("Яркость");
            idleFor(800);
            shot(activity, "set_search_app");
        }
    }

    private static EditText findEdit(View v) {
        if (v instanceof EditText) return (EditText) v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            EditText e = findEdit(((ViewGroup) v).getChildAt(i));
            if (e != null) return e;
        }
        return null;
    }
}
