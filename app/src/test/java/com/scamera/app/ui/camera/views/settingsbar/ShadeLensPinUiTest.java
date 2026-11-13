package com.scamera.app.ui.camera.views.settingsbar;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.scamera.app.R;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.PreferenceNumber;
import com.scamera.app.settings.SettingType;
import com.scamera.app.settings.SettingsManager;
import com.scamera.app.settings.ShadeCatalog;
import com.scamera.app.settings.ShadeTiles;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * P606: a lens page's row in the shade - «В шторку» in its long press, its tile live on its lens (a tap changes it, the lens page's
 * key is written) and greyed on another (a tap only says where it works, nothing changes), rendered (RENDER_DIR, else
 * build/reports/viewfinder).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ShadeLensPinUiTest {
    private static final String GAMUT = "pref_scam_hybrid_colour_gamut", BLACK = "pref_raw_black_from_data";
    private MockedStatic<ScameraApp> camera;
    private ActivityController<AppCompatActivity> controller;
    private AppCompatActivity activity;
    private SettingsManager manager;
    private SharedPreferences prefs;
    private ShadeCatalog catalog;
    private SettingsBarLayout sheet;
    private final List<String> messages = new ArrayList<>();
    private final List<String> written = new ArrayList<>();

    @Before
    public void setUp() {
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8750");
        controller = Robolectric.buildActivity(AppCompatActivity.class);
        activity = controller.get();
        activity.setTheme(R.style.Theme_Scamera_SettingsActivity);
        controller.setup();
        Context context = activity;
        manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        camera.when(ScameraApp::getSettings).thenReturn(mock(com.scamera.app.api.Settings.class));
        ScameraApp app = mock(ScameraApp.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
        PreferenceKeys.initialise(manager);
        PreferenceKeys.setDefaults(context);
        ShadeCatalog.setFlashAvailable(true);
        ShadeCatalog.reset();
        for (int i = 0; i < 3; i++)
            prefs.edit().putString("module_auto_back" + i, "" + (3 + i)).putString("module_label_back" + i, new String[]{"1×", "0,6×", "3×"}[i])
                    .putBoolean("module_visible_back" + i, true).commit();
        prefs.edit().putString("module_active", "back2").putBoolean("pref_save_per_lens_settings", true).commit();
        PreferenceKeys.profiles().changed("pref_save_per_lens_settings");
        catalog = new ShadeCatalog(context, prefs);
    }

    @After
    public void tearDown() {
        ShadeCatalog.reset();
        camera.close();
        controller.pause().stop().destroy();
    }

    private void buildShade() {
        sheet = new SettingsBarLayout(activity, null);
        sheet.setCatalog(catalog);
        sheet.attach(new ShadeHost() {
            @Override public void applyCameraControl(SettingType type, int value) { }
            @Override public void onSettingWritten(ShadeCatalog.Entry entry) { written.add(entry.key); }
            @Override public void showMessage(CharSequence text) { messages.add(text.toString()); }
            @Override public void openCatalog() { }
            @Override public void openSettings() { }
            @Override public void onValuesChanged() { }
        });
        CoordinatorLayout coordinator = new CoordinatorLayout(activity);
        CoordinatorLayout.LayoutParams lp = new CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setBehavior(new BottomSheetBehavior<SettingsBarLayout>());
        coordinator.addView(sheet, lp);
        activity.setContentView(coordinator, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private int dp(float v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private void layoutFull() {
        int width = dp(360), height = Math.round(dp(480) * .86f);
        sheet.setMaxSheetHeight(height);
        sheet.prewarm();
        for (int i = 0; i < 3; i++) {
            sheet.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            sheet.layout(0, 0, width, height);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        }
    }

    private static File dir() {
        String out = System.getenv("RENDER_DIR");
        File dir = new File(out == null || out.isEmpty() ? "build/reports/viewfinder" : out);
        dir.mkdirs();
        return dir;
    }

    private static void save(View view, String name) throws Exception {
        Bitmap image = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        image.eraseColor(0xFF45525B);
        view.draw(new Canvas(image));
        try (FileOutputStream out = new FileOutputStream(new File(dir(), name))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    private ShadeTileView tile(String key) {
        RecyclerView grid = sheet.findViewWithTag("shade_tiles");
        for (int i = 0; i < grid.getChildCount(); i++) {
            ShadeTileView t = (ShadeTileView) grid.getChildAt(i);
            if (key.equals(t.key)) return t;
        }
        return null;
    }

    /** The camera's module now (what a lens button or the zoom does: a settings change the shade follows). */
    private void select(String slot) {
        prefs.edit().putString("module_active", slot).commit();
        sheet.refresh();
        layoutFull();
    }

    @Test
    public void helpSheetOfALensRowPinsForItsLens() throws Exception {
        PreferenceManager pm = new PreferenceManager(activity);
        pm.setSharedPreferencesName("shade_lens_pin_ui_test");
        PreferenceScreen screen = pm.inflateFromResource(activity, R.xml.preferences, null);
        Preference gamut = screen.findPreference(GAMUT);
        assertNotNull(gamut);
        // on another page (no lens) the row has no pin: the shade holds no setting of every lens of it
        BottomSheetDialog plain = com.scamera.app.ui.settings.SettingHelp.show(activity, gamut);
        assertNull(plain.getWindow().getDecorView().findViewWithTag("sheet_pin"));
        plain.dismiss();
        // the lens page of 3×: «В шторку» for 3×
        BottomSheetDialog d = com.scamera.app.ui.settings.SettingHelp.show(activity, gamut, "back2");
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        View body = d.getWindow().getDecorView().findViewWithTag("setting_help");
        TextView pin = d.getWindow().getDecorView().findViewWithTag("sheet_pin");
        assertNotNull(pin);
        assertEquals("В шторку", pin.getText().toString());
        int width = dp(360);
        body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        body.layout(0, 0, width, body.getMeasuredHeight());
        save(body, "help-lens-pin.png");
        pin.performClick();
        String token = ShadeTiles.lensToken("back2", GAMUT);
        assertTrue(ShadeTiles.isPinned(prefs, token));
        assertEquals("В шторке", pin.getText().toString());
        body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        body.layout(0, 0, width, body.getMeasuredHeight());
        save(body, "help-lens-pinned.png");
        // the sheet takes it out again
        pin.performClick();
        assertFalse(ShadeTiles.isPinned(prefs, token));
        d.dismiss();
    }

    @Test
    public void tileLiveOnItsLensGreyOnAnother() throws Exception {
        String gamut = ShadeTiles.lensToken("back2", GAMUT), black = ShadeTiles.lensToken("back2", BLACK);
        ShadeTiles.save(prefs, Arrays.asList(ShadeCatalog.FLASH, ShadeCatalog.TIMER, "pref_show_grid_key", ShadeCatalog.FORMAT,
                black, gamut, ShadeCatalog.OUTPUT));
        buildShade();
        layoutFull();
        assertEquals(Arrays.asList(ShadeCatalog.FLASH, ShadeCatalog.TIMER, "pref_show_grid_key", ShadeCatalog.FORMAT, black, gamut,
                ShadeCatalog.OUTPUT), sheet.pinnedKeys());
        ShadeTileView blackTile = tile(black), gamutTile = tile(gamut);
        assertNotNull(blackTile);
        assertNotNull(gamutTile);
        assertFalse(blackTile.dimmed);
        assertFalse(gamutTile.dimmed);
        // the row's name, its lens on the value line (the name may shrink and cut; the lens stays readable)
        assertEquals(catalog.entry(black).lensShortTitle.toString(), blackTile.name.getText().toString());
        assertTrue(blackTile.value.getText().toString(), blackTile.value.getText().toString().startsWith("3× · "));
        assertEquals(View.VISIBLE, blackTile.value.getVisibility());
        assertEquals(0, blackTile.value.getLayout().getEllipsisCount(0));
        // on its lens: a tap switches it, the lens page's own key is written
        boolean before = prefs.getBoolean(BLACK, true);
        blackTile.performClick();
        assertEquals(!before, prefs.getBoolean(BLACK, true));
        assertTrue(written.contains(black));
        // the slider opens its card
        gamutTile.performClick();
        layoutFull();
        assertEquals(View.VISIBLE, ((View) sheet.findViewWithTag("shade_slider_card")).getVisibility());
        save(sheet, "shade-lens-on.png");

        // another lens: greyed; a tap only says where it works
        written.clear();
        select("back0");
        blackTile = tile(black);
        gamutTile = tile(gamut);
        assertTrue(blackTile.dimmed);
        assertTrue(gamutTile.dimmed);
        assertEquals("the card closes", View.GONE, ((View) sheet.findViewWithTag("shade_slider_card")).getVisibility());
        assertFalse("no open outline left", gamutTile.open);
        int messagesBefore = messages.size();
        blackTile.performClick();
        gamutTile.performClick();
        assertEquals(!before, prefs.getBoolean(BLACK, true));
        assertTrue(written.isEmpty());
        assertEquals(messagesBefore + 2, messages.size());
        assertEquals("Доступно на линзе 3×", messages.get(messages.size() - 1));
        assertFalse(((View) sheet.findViewWithTag("shade_slider_card")).getVisibility() == View.VISIBLE);
        save(sheet, "shade-lens-off.png");

        // back on 3×: live again
        select("back2");
        assertFalse(tile(black).dimmed);
        tile(black).performClick();
        assertEquals(before, prefs.getBoolean(BLACK, true));
        // the edit mode's «×» takes it out of the shade
        sheet.setEditing(true);
        layoutFull();
        tile(gamut).remove.performClick();
        assertFalse(sheet.pinnedKeys().contains(gamut));
        assertFalse(ShadeTiles.stored(prefs).contains(gamut));
        assertEquals(0, (int) Math.round(PreferenceNumber.read(prefs.getAll().get(GAMUT), 0)));
    }
}
