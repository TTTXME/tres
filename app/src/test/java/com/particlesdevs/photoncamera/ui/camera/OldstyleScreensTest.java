package com.particlesdevs.photoncamera.ui.camera;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.circularbarlib.ui.ExpandingManualPanel;
import com.particlesdevs.photoncamera.databinding.CameraFragmentBinding;
import com.particlesdevs.photoncamera.settings.FormatChoice;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingType;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.settings.ShadeCatalog;
import com.particlesdevs.photoncamera.settings.TopRow;
import com.particlesdevs.photoncamera.ui.camera.data.CameraLensData;
import com.particlesdevs.photoncamera.ui.camera.model.AuxButtonsModel;
import com.particlesdevs.photoncamera.ui.camera.model.CameraFragmentModel;
import com.particlesdevs.photoncamera.ui.camera.views.AuxButtonsLayout;
import com.particlesdevs.photoncamera.ui.camera.views.BottomChrome;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.SettingsBarLayout;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeHost;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * P630 / P630c «Oldstyle»: the camera screen in the mode - the three modes' switch with «Oldstyle» chosen, the manual row held open
 * (EV, ISO, S, WB, AF), «Про» stepped aside, the round style button - and the style panel: «Палитра» at rest and dragging with its
 * HUD, «Расширенные» on a few chips, the reset confirmation, on the concept's 390 x 844 phone (RU), into build/reports/oldstyle/ui_*.png; the mode's switching, the card's tiles and
 * ruler and the viewfinder's uniform checked on the way.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class OldstyleScreensTest {
    private MockedStatic<ScameraApp> camera;
    private SharedPreferences prefs;
    private SettingsManager manager;

    @Before
    public void setUp() {
        org.robolectric.shadows.ShadowBuild.setSystemOnChipModel("SM8750");
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(i -> context.getString(i.getArgument(0)));
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        camera.when(ScameraApp::getSettings).thenReturn(mock(com.particlesdevs.photoncamera.api.Settings.class));
        ScameraApp app = mock(ScameraApp.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
        PreferenceKeys.initialise(manager);
        PreferenceKeys.setDefaults(context);
        ShadeCatalog.setFlashAvailable(true);
        com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.k(context);
    }

    @After
    public void tearDown() {
        TopBar.dcgAvailable(false);
        com.particlesdevs.photoncamera.capture.Dcg14Mode.setMode(com.particlesdevs.photoncamera.capture.Dcg14Mode.OFF);
        camera.close();
    }

    /** The real camera_fragment as the camera screen fills it, laid out at the window's size. */
    private static final class Screen {
        Activity activity;
        Context context;
        CameraFragmentBinding b;
        CameraFragmentModel model;
        FrameLayout root;
        LutTray tray;
        int w, h;
    }

    private Screen screen(boolean grid) {
        if (grid) manager.set(SettingsManager.SCOPE_GLOBAL, "pref_show_grid_key", "1");
        manager.set("default_scope", PreferenceKeys.Key.KEY_SHOW_HORIZON, false);
        TopBar.dcgAvailable(true);
        com.particlesdevs.photoncamera.capture.Dcg14Mode.setMode(com.particlesdevs.photoncamera.capture.Dcg14Mode.DCG);   // the concept's DCG on
        Screen s = new Screen();
        s.activity = Robolectric.buildActivity(AppCompatActivity.class).setup().get();
        s.activity.setTheme(R.style.Theme_Scamera_SettingsActivity);
        s.context = new ContextThemeWrapper(s.activity, R.style.Theme_Scamera_SettingsActivity);
        Context context = s.context;
        s.w = context.getResources().getDisplayMetrics().widthPixels;
        s.h = context.getResources().getDisplayMetrics().heightPixels;
        CameraFragmentBinding binding = CameraFragmentBinding.inflate(LayoutInflater.from(context));
        s.b = binding;
        CameraFragmentModel model = new CameraFragmentModel();
        model.setScreenAspectRatio((float) s.h / s.w);
        model.setDummyAspectRatio("3:4");
        model.setSheetLevel(CameraFragmentModel.SHEET_HIDDEN);
        binding.setUimodel(model);
        binding.layoutTopbar.setUimodel(model);
        s.model = model;
        AuxButtonsModel lenses = new AuxButtonsModel();
        List<CameraLensData> back = new ArrayList<>();
        float[] zoom = {.6f, 1f, 2f, 3.7f};
        for (int i = 0; i < zoom.length; i++) {
            CameraLensData lens = new CameraLensData(String.valueOf(i));
            lens.setZoomFactor(zoom[i]);
            back.add(lens);
        }
        lenses.setBackCameras(back);
        lenses.setFrontCameras(new ArrayList<>());
        binding.setAuxmodel(lenses);
        binding.executePendingBindings();
        CameraUIViewImpl.bindBadges(binding.layoutTopbar);
        AuxButtonsLayout strip = binding.layoutBottombar.auxButtonsContainer;
        strip.setAuxButtonsModel(lenses);
        strip.setActiveId("1");
        binding.layoutViewfinder.texture.setAspectRatio(s.w, s.w * 4 / 3);
        // the concept's placeholder picture: linear-gradient(170deg, #3A4656, #1E2630 55 %, #0F1318)
        GradientDrawable picture = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF3A4656, 0xFF1E2630, 0xFF0F1318});
        binding.layoutViewfinder.texture.setBackground(picture);
        var row = binding.layoutBottombar.bottomButtons;
        int accent = ShadeStyle.accent(context);
        row.shutterButton.setBackground(CameraUIViewImpl.shutter(context, accent));
        row.shutterButton.setActivated(true);   // ready
        // the gallery card: the concept's placeholder thumbnail
        Bitmap thumb = Bitmap.createBitmap(58, 58, Bitmap.Config.ARGB_8888);
        new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF6D7F96, 0xFF27303D}) {{ setBounds(0, 0, 58, 58); }}
                .draw(new Canvas(thumb));
        ((android.widget.ImageView) row.galleryImageButton).setImageBitmap(thumb);
        row.galleryPlaceholder.setVisibility(View.GONE);
        SettingsBarLayout sheet = binding.settingsBar;
        sheet.setCatalog(new ShadeCatalog(context, prefs));
        sheet.attach(new ShadeHost() {
            @Override public void applyCameraControl(SettingType type, int value) { }
            @Override public void onSettingWritten(ShadeCatalog.Entry entry) { }
            @Override public void showMessage(CharSequence text) { }
            @Override public void openCatalog() { }
            @Override public void openSettings() { }
            @Override public void onValuesChanged() { }
        });
        s.tray = new LutTray(binding.layoutTopbar.lutButton, binding.lutTray, null, () -> { }, true);
        s.root = new FrameLayout(context);
        s.root.addView(binding.getRoot(), new FrameLayout.LayoutParams(s.w, s.h));
        s.activity.setContentView(s.root);
        CameraFragment.attachBottomChrome(binding, null);
        binding.topRowGrip.setBackground(com.particlesdevs.photoncamera.ui.settings.SettingsStyle.shape(context, 0x59FFFFFF, 0, 2));
        sheet.prewarm();
        sheet.setSheetLevel(SettingsBarLayout.LEVEL_HIDDEN);
        layout(s);
        binding.layoutViewfinder.horizonIndicatorView.setVisibility(View.GONE);   // the level: a state the concept's main screen has off
        return s;
    }

    private static void layout(Screen s) {
        for (int i = 0; i < 4; i++) {
            s.root.measure(View.MeasureSpec.makeMeasureSpec(s.w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(s.h, View.MeasureSpec.EXACTLY));
            s.root.layout(0, 0, s.w, s.h);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        }
    }

    private ExpandingManualPanel oldstyleScreen(Screen s) {
        prefs.edit().putBoolean(com.particlesdevs.photoncamera.settings.OldstyleMode.PREF, true).commit();
        manager.set(SettingsManager.SCOPE_GLOBAL, com.particlesdevs.photoncamera.settings.OldstyleMode.PREF, true);
        View bar = s.b.layoutBottombar.getRoot();
        BottomChrome.bindModes(bar, false);
        ExpandingManualPanel panel = (ExpandingManualPanel) s.b.manualMode;
        int[] chipIds = {R.id.ev_option_tv, R.id.iso_option_tv, R.id.exposure_option_tv, R.id.wb_option_tv, R.id.focus_option_tv, R.id.all_auto_option_tv};
        String[] values = {"0", "620", "1/29", "Авто", "АФ", "Всё"};
        for (int i = 0; i < chipIds.length; i++) {
            com.particlesdevs.photoncamera.circularbarlib.ui.views.ManualChipView chip = panel.findViewById(chipIds[i]);
            chip.bind(values[i], i == 1 || i == 2 ? com.particlesdevs.photoncamera.circularbarlib.ui.views.ManualChipView.MANUAL : com.particlesdevs.photoncamera.circularbarlib.ui.views.ManualChipView.AUTO);
        }
        return panel;
    }

    @Test
    public void modeManualRowAndAdjustments() throws Exception {
        Screen s = screen(true);
        ExpandingManualPanel panel = oldstyleScreen(s);
        View pro = s.b.layoutBottombar.getRoot().findViewById(R.id.pro_button);
        OldstyleControls controls = new OldstyleControls(s.b.oldstyleControls, s.b.oldstyleHud, s.b.layoutViewfinder.texture, null);
        controls.apply(true, panel, pro, false);
        layout(s);
        // the mode: «Oldstyle» chosen of three, the row open, «Про» stepped aside, the controls shown
        View old = s.b.layoutBottombar.getRoot().findViewById(R.id.mode_oldstyle);
        assertTrue(old.isSelected());
        assertFalse(s.b.layoutBottombar.getRoot().findViewById(R.id.mode_photo).isSelected());
        assertTrue(panel.isExpanded());
        assertEquals(0f, pro.getAlpha(), 0f);
        assertFalse(pro.isEnabled());
        assertEquals(View.VISIBLE, s.b.oldstyleControls.getVisibility());
        assertNull("every parameter at 0: no maths in the viewfinder", com.particlesdevs.photoncamera.ui.camera.views.viewfinder.MainRenderer.oldstyleAdjust());
        Bitmap mode = draw(s);
        write(mode, "ui_oldstyle_mode");
        // the manual row, cut out of the screen with the button above it
        View row = s.b.manualMode;
        int[] at = new int[2], root = new int[2];
        row.getLocationInWindow(at);
        s.root.getLocationInWindow(root);
        int top = Math.max(0, at[1] - root[1] - s.b.oldstyleControls.getHeight() - 30), bottom = Math.min(s.h, at[1] - root[1] + row.getHeight() + 30);
        write(Bitmap.createBitmap(mode, 0, top, s.w, bottom - top), "ui_oldstyle_manual_row");
        // the mode pill 4x: «Oldstyle» (the film cassette) chosen, then «Фото» chosen with the cassette unchosen; and at 1x
        Bitmap chosen = pill(s, mode);
        Bitmap chosen1 = pill(s, mode, 1);
        manager.set(SettingsManager.SCOPE_GLOBAL, com.particlesdevs.photoncamera.settings.OldstyleMode.PREF, false);
        BottomChrome.bindModes(s.b.layoutBottombar.getRoot(), false);
        layout(s);
        assertFalse(old.isSelected());
        Bitmap otherScreen = draw(s);
        Bitmap other = pill(s, otherScreen);
        Bitmap other1 = pill(s, otherScreen, 1);
        write(stack(chosen1, other1), "ui_icon_pill_1x");
        write(stack(chosen, other), "ui_icon_pill");
        manager.set(SettingsManager.SCOPE_GLOBAL, com.particlesdevs.photoncamera.settings.OldstyleMode.PREF, true);
        BottomChrome.bindModes(s.b.layoutBottombar.getRoot(), false);
        layout(s);
        assertTrue(old.isSelected());

        // P630c: the style panel, «Палитра» at rest (the point set: tone 81, colour -100 as the reference's HUD)
        com.particlesdevs.photoncamera.settings.OldstyleMode.setStyle(com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle.Param.TONE, 81);
        com.particlesdevs.photoncamera.settings.OldstyleMode.setStyle(com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle.Param.COLOR, -100);
        controls.setOpen(true, false);
        controls.bind();
        layout(s);
        assertTrue(controls.isOpen());
        assertEquals(OldstyleControls.PALETTE, controls.tab());
        assertEquals(0f, panel.getAlpha(), 0f);
        assertEquals(View.VISIBLE, s.b.oldstyleHud.getVisibility());
        assertEquals("База: Natural  ·  Тон: 81  ·  Цвет: -100", s.b.oldstyleHud.getText().toString());
        assertEquals(View.VISIBLE, s.b.oldstyleControls.findViewWithTag("oldstyle_reset").getVisibility());
        float[] live = com.particlesdevs.photoncamera.ui.camera.views.viewfinder.MainRenderer.oldstyleAdjust();
        assertNotNull(live);
        assertEquals(.81f, live[0], 1e-6f);
        assertEquals(-1f, live[1], 1e-6f);
        write(draw(s), "ui_panel_palette");
        // dragging: the dots grow under the finger, the HUD follows
        OldstyleControls.PalettePad pad = controls.pad();
        pad.pressAt(.72f, .3f);
        layout(s);
        assertTrue(pad.isDragging());
        com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle st = com.particlesdevs.photoncamera.settings.OldstyleMode.style();
        assertEquals(Math.round(.72f * 200 - 100), st.get(com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle.Param.COLOR));
        assertEquals(Math.round(100 - .3f * 200), st.get(com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle.Param.TONE));
        write(draw(s), "ui_panel_palette_drag");
        pad.release();
        // «Расширенные»: exposure +35, then «Ореол» (a stepped one) on «Средн.», then «Виньетка»
        controls.setTab(OldstyleControls.ADVANCED, false);
        com.particlesdevs.photoncamera.settings.OldstyleMode.setStyle(com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle.Param.EXPOSURE, 35);
        controls.bind();
        layout(s);
        assertEquals(OldstyleControls.ADVANCED, controls.tab());
        assertEquals(View.GONE, s.b.oldstyleHud.getVisibility());
        assertEquals(35, controls.ruler().value());
        write(draw(s), "ui_panel_advanced_exposure");
        controls.choose(com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle.Param.HALO);
        com.particlesdevs.photoncamera.settings.OldstyleMode.setStyle(com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle.Param.HALO, 2);
        controls.bind();
        layout(s);
        assertEquals(com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle.Param.HALO, controls.chosen());
        assertEquals(2, controls.ruler().value());
        assertEquals("Средн.", controls.label(com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle.Param.HALO, 2));
        write(draw(s), "ui_panel_advanced_halo");
        controls.choose(com.particlesdevs.photoncamera.processing.oldstyle.OldstyleStyle.Param.VIGNETTE);
        layout(s);
        write(draw(s), "ui_panel_advanced_vignette");
        // reset: the confirmation, then everything back to 0 («Стиль сброшен»)
        androidx.appcompat.app.AlertDialog d = controls.askReset();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        Bitmap under = draw(s);
        View dialog = d.findViewById(android.R.id.content).getRootView();
        // the dialog's window is a centred card of its own size (wrap content), drawn over the dimmed screen
        int cardW = Math.round(s.w * .88f);
        dialog.measure(View.MeasureSpec.makeMeasureSpec(cardW, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(s.h, View.MeasureSpec.AT_MOST));
        dialog.layout(0, 0, cardW, dialog.getMeasuredHeight());
        Canvas dc = new Canvas(under);
        dc.drawColor(0x73000000);
        dc.translate((s.w - cardW) / 2f, (s.h - dialog.getMeasuredHeight()) / 2f);
        dialog.draw(dc);
        write(under, "ui_panel_reset_dialog");
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertTrue(com.particlesdevs.photoncamera.settings.OldstyleMode.style().neutral());
        assertNull(com.particlesdevs.photoncamera.ui.camera.views.viewfinder.MainRenderer.oldstyleAdjust());
        layout(s);
        assertEquals(View.INVISIBLE, s.b.oldstyleControls.findViewWithTag("oldstyle_reset").getVisibility());
        // back closes the panel: the manual row returns
        controls.setOpen(false, false);
        assertEquals(1f, panel.getAlpha(), 0f);
        write(draw(s), "ui_oldstyle_adjust");
        // leaving the mode: the row closes, «Про» back, the viewfinder without the style
        controls.apply(false, panel, pro, false);
        assertFalse(panel.isExpanded());
        assertEquals(1f, pro.getAlpha(), 0f);
        assertNull(com.particlesdevs.photoncamera.ui.camera.views.viewfinder.MainRenderer.oldstyleAdjust());
        assertEquals(View.GONE, s.b.oldstyleControls.getVisibility());
    }

    private static void idle(int ms) {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private static void assertRowHidden(String when, ExpandingManualPanel panel) {
        assertFalse(when, panel.shown());
        assertEquals(when, View.INVISIBLE, panel.getVisibility());
        assertEquals(when, 0f, panel.getAlpha(), 0f);
        final long t = android.os.SystemClock.uptimeMillis();
        final android.view.MotionEvent down = android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, 40f, 20f, 0);
        assertFalse(when + ": no touches", panel.dispatchTouchEvent(down));
        down.recycle();
    }

    private static void assertRowShown(String when, ExpandingManualPanel panel) {
        assertTrue(when, panel.shown());
        assertEquals(when, View.VISIBLE, panel.getVisibility());
        assertEquals(when, 1f, panel.getAlpha(), 0f);
    }

    /**
     * P630g (owner's screenshot: the style panel open, the manual row showing through it; «у нас иногда не скрываются ручные
     * настройки»): the row is hidden exactly while the panel is open - one rule (the shade down and nothing in its place), whatever
     * comes in between: the shade going up and down under the open panel (its return faded the row back to 1: the screenshot), a
     * binding refresh, a close during the open fade, an open during the shade's fade, the chrome rebuilt (a rotation, a resume)
     * over the same row, the mode left with the panel open. A hidden row takes no touches from the start of its fade.
     */
    @Test
    public void manualRowHiddenExactlyWhileThePanelIsOpen() throws Exception {
        Screen s = screen(true);
        ExpandingManualPanel panel = oldstyleScreen(s);
        View pro = s.b.layoutBottombar.getRoot().findViewById(R.id.pro_button);
        OldstyleControls controls = new OldstyleControls(s.b.oldstyleControls, s.b.oldstyleHud, s.b.layoutViewfinder.texture, null);
        controls.apply(true, panel, pro, false);
        layout(s);
        assertRowShown("the mode, the panel closed", panel);
        // open, animated: no touches at once, hidden when the fade ends
        controls.setOpen(true, true);
        assertFalse(panel.shown());
        final long t0 = android.os.SystemClock.uptimeMillis();
        final android.view.MotionEvent early = android.view.MotionEvent.obtain(t0, t0, android.view.MotionEvent.ACTION_DOWN, 40f, 20f, 0);
        assertFalse("a tap on a manual tile while the panel opens", panel.dispatchTouchEvent(early));
        early.recycle();
        idle(600);
        assertRowHidden("open", panel);
        // the screenshot's interleaving: the shade up and down again under the open panel (the binding's return of the row)
        com.particlesdevs.photoncamera.ui.camera.binding.CustomBinding.setManualPanelShown(panel, false);
        controls.setShadeShown(true);
        idle(600);
        assertRowHidden("the shade up", panel);
        com.particlesdevs.photoncamera.ui.camera.binding.CustomBinding.setManualPanelShown(panel, true);
        controls.setShadeShown(false);
        idle(600);
        assertTrue(controls.isOpen());
        assertRowHidden("the shade down again, the panel still open", panel);
        layout(s);
        write(draw(s), "ui_panel_open_row_hidden");
        // the parent animates layout changes (camera_container): its transition is not let at the row - no animator of its own
        // fades the row in after ours hid it (the «иногда» of the owner's report)
        final android.animation.LayoutTransition transition = ((ViewGroup) panel.getParent()).getLayoutTransition();
        assertTrue("the fixture has the layout's transition", transition != null && transition.isTransitionTypeEnabled(android.animation.LayoutTransition.APPEARING));
        controls.setOpen(false, false);
        assertRowShown("closed: shown at once, no transition fading it in from 0", panel);
        assertFalse(transition.isRunning());
        controls.setOpen(true, true);
        idle(600);
        assertRowHidden("open again", panel);
        assertTrue(transition.isTransitionTypeEnabled(android.animation.LayoutTransition.APPEARING)
                && transition.isTransitionTypeEnabled(android.animation.LayoutTransition.DISAPPEARING));
        // a binding refresh (the same value again, laid out or not), the 300 ms refreshes of the mode: nothing shows it
        for (int i = 0; i < 3; i++) {
            com.particlesdevs.photoncamera.ui.camera.binding.CustomBinding.setManualPanelShown(panel, true);
            panel.setShadeShown(true, i % 2 == 0);
            controls.bind();
            idle(100);
        }
        idle(600);
        assertRowHidden("refreshes while open", panel);
        // someone's fade of the row cancelled half way and its alpha set from outside: the next refresh puts it back
        panel.setAlpha(1f);
        assertFalse("still no touches", panel.shown());
        controls.setShadeShown(true);
        controls.setShadeShown(false);
        idle(600);
        assertRowHidden("after a foreign alpha", panel);
        // close during the open fade, open during the close fade
        controls.setOpen(false, true);
        idle(60);
        controls.setOpen(true, true);
        idle(60);
        controls.setOpen(false, true);
        idle(600);
        assertFalse(controls.isOpen());
        assertRowShown("closed after open / close / open / close", panel);
        controls.setOpen(true, true);
        idle(60);
        controls.setOpen(false, true);
        idle(30);
        controls.setOpen(true, true);
        idle(600);
        assertRowHidden("open after close / open", panel);
        // open during the shade's own fade of the row (the shade leaving, then back)
        controls.setOpen(false, false);
        assertRowShown("closed", panel);
        panel.setShadeShown(false, true);
        idle(60);
        controls.setOpen(true, true);
        idle(60);
        panel.setShadeShown(true, true);
        idle(600);
        assertRowHidden("opened while the shade's fade ran", panel);
        // the chrome rebuilt over the same row (a rotation, a resume): the new controls are closed - the row is given back
        OldstyleControls rebuilt = new OldstyleControls(s.b.oldstyleControls, s.b.oldstyleHud, s.b.layoutViewfinder.texture, null);
        rebuilt.apply(true, panel, pro, false);
        layout(s);
        assertFalse(rebuilt.isOpen());
        assertRowShown("rebuilt, closed", panel);
        rebuilt.setOpen(true, false);
        assertRowHidden("rebuilt, open", panel);
        // the mode left with the panel open: the row is the other mode's again (collapsed there, but not covered)
        rebuilt.apply(false, panel, pro, false);
        assertFalse(panel.isCovered());
        assertTrue(panel.shown());
        assertEquals(1f, panel.getAlpha(), 0f);
        assertEquals(View.VISIBLE, panel.getVisibility());
        // and with the shade up outside the mode the row follows the shade as before
        panel.setShadeShown(false, false);
        assertEquals(View.INVISIBLE, panel.getVisibility());
        panel.setShadeShown(true, false);
        assertRowShown("the shade's own rule", panel);
    }

    private static Bitmap stack(Bitmap a, Bitmap b) {
        Bitmap both = Bitmap.createBitmap(a.getWidth(), a.getHeight() * 2, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(both);
        c.drawBitmap(a, 0, 0, null);
        c.drawBitmap(b, 0, a.getHeight(), null);
        return both;
    }

    /** The bottom mode switch cut out of {@code screen} (a margin round it), 4x. */
    private static Bitmap pill(Screen s, Bitmap screen) {
        return pill(s, screen, 4);
    }

    /** The bottom mode switch cut out of {@code screen}, {@code scale} times (1: the phone's own pixels). */
    private static Bitmap pill(Screen s, Bitmap screen, int scale) {
        View sw = s.b.layoutBottombar.getRoot().findViewById(R.id.mode_switch);
        int[] at = new int[2], root = new int[2];
        sw.getLocationInWindow(at);
        s.root.getLocationInWindow(root);
        int m = 12, x = Math.max(0, at[0] - root[0] - m), y = Math.max(0, at[1] - root[1] - m);
        int w = Math.min(s.w - x, sw.getWidth() + 2 * m), h = Math.min(s.h - y, sw.getHeight() + 2 * m);
        final Bitmap cut = Bitmap.createBitmap(screen, x, y, w, h);
        return scale == 1 ? cut : Bitmap.createScaledBitmap(cut, w * scale, h * scale, true);
    }

    private static Bitmap draw(Screen s) {
        Bitmap image = Bitmap.createBitmap(s.w, s.h, Bitmap.Config.ARGB_8888);
        image.eraseColor(0xFF000000);
        s.root.draw(new Canvas(image));
        return image;
    }

    private static void write(Bitmap image, String state) throws Exception {
        File dir = new File("build/reports/oldstyle");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, state + ".png"))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
