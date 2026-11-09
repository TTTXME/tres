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
 * P583 (owner, 2026-11-09: «Убедись что всё выглядит один в один как на концептах»): the camera screen's states on the concept's
 * 390 x 844 phone (xxhdpi: 3 px a dp, the concept rendered at the same scale), written to build/reports/viewfinder/concept/
 * cam_&lt;state&gt;_app.png for a side-by-side check with research/concept/Viewfinder.dc.html: the main screen, the open shade,
 * the «Верхняя строка» sheet, the LUT tray, the format and size sheet, the Pro panel. The preview is the concept's placeholder
 * gradient (no camera runs here).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ConceptScreensTest {
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

    /** The focus brackets and the brightness sun at the preview's centre, as the concept's main screen shows them. */
    private static void focus(Screen s) {
        View ring = s.b.layoutViewfinder.touchFocus;
        ring.setVisibility(View.VISIBLE);
        layout(s);
        com.particlesdevs.photoncamera.ui.camera.views.ExposureSunView sun = s.b.layoutViewfinder.evSun;
        sun.setVisibility(View.VISIBLE);
        View parent = (View) sun.getParent();
        sun.placeBeside(ring.getX() + ring.getWidth() / 2f, ring.getY() + ring.getHeight() / 2f, ring.getWidth() / 2f,
                parent.getWidth(), parent.getHeight());
        layout(s);
    }

    @Test
    public void main() throws Exception {
        Screen s = screen(true);
        focus(s);
        write(draw(s), "main");
    }

    @Test
    public void shade() throws Exception {
        Screen s = screen(true);
        s.b.settingsBar.setSheetLevel(SettingsBarLayout.LEVEL_PEEK);
        layout(s);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        layout(s);
        write(draw(s), "shade");
    }

    @Test
    public void toprow() throws Exception {
        Screen s = screen(true);
        Bitmap under = draw(s);
        TopRowSheet.Editor e = new TopRowSheet.Editor(TopRow.DEFAULT, id -> true);
        ViewGroup card = TopRowSheet.build(s.context, e, x -> { }, () -> { });
        FrameLayout over = new FrameLayout(s.context);
        View scrim = new View(s.context);
        scrim.setBackgroundColor(0x73000000);
        over.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        int m = Math.round(8 * s.context.getResources().getDisplayMetrics().density * com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.k(s.context));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        lp.setMargins(m, m, m, 0);
        over.addView(card, lp);
        assertNotNull(card.findViewWithTag("top_row_done"));
        over.measure(View.MeasureSpec.makeMeasureSpec(s.w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(s.h, View.MeasureSpec.EXACTLY));
        over.layout(0, 0, s.w, s.h);
        Canvas c = new Canvas(under);
        over.draw(c);
        write(under, "toprow");
    }

    @Test
    public void lut() throws Exception {
        Screen s = screen(true);
        s.tray.setOpen(true);
        layout(s);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        layout(s);
        write(draw(s), "lut");
    }

    @Test
    public void pro() throws Exception {
        Screen s = screen(true);
        ExpandingManualPanel panel = (ExpandingManualPanel) s.b.manualMode;
        panel.setExpanded(true, false);
        panel.findViewById(R.id.knobViewContainer).setVisibility(View.VISIBLE);
        panel.findViewById(R.id.linearScaleView).setVisibility(View.VISIBLE);
        // the concept's Pro state: the shutter chosen at 1/400, ISO 72, brightness 0, focus 1,4 m, colour 7400K
        com.particlesdevs.photoncamera.circularbarlib.ui.views.scaleview.LinearScaleView scale = panel.findViewById(R.id.linearScaleView);
        java.util.List<com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.KnobItemInfo> items = new ArrayList<>();
        String[] stops = {"1/4000", "1/2000", "1/1000", "1/500", "1/400", "1/250", "1/125", "1/60", "1/30", "1/15", "1/4", "1\""};
        for (int i = 0; i < stops.length; i++) items.add(new com.particlesdevs.photoncamera.circularbarlib.ui.views.knobview.KnobItemInfo(null, stops[i], i, i));
        scale.setItems(items, 4);
        scale.setSelectedItem(items.get(4));
        scale.setAutoLabel("Авто");
        int[] chipIds = {R.id.iso_option_tv, R.id.exposure_option_tv, R.id.ev_option_tv, R.id.focus_option_tv, R.id.wb_option_tv, R.id.all_auto_option_tv};
        String[] values = {"72", "1/400", "0", "1,4 м", "7400K", "Всё"};
        for (int i = 0; i < chipIds.length; i++) {
            com.particlesdevs.photoncamera.circularbarlib.ui.views.ManualChipView chip = panel.findViewById(chipIds[i]);
            chip.bind(values[i], com.particlesdevs.photoncamera.circularbarlib.ui.views.ManualChipView.AUTO);
            chip.setChosen(i == 1);
        }
        BottomChrome.setSideOn(s.b.layoutBottombar.getRoot(), R.id.pro_button, true);
        layout(s);
        write(draw(s), "pro");
    }

    /**
     * The phone held sideways (the concept's ViewfinderLand): the camera screen keeps its portrait layout and turns its glyphs
     * with the phone (bindRotate, the lens labels); the render is turned to landscape for the side-by-side.
     */
    @Test
    public void land() throws Exception {
        Screen s = screen(true);
        focus(s);
        s.model.setOrientation(90);
        s.b.executePendingBindings();
        s.b.layoutBottombar.auxButtonsContainer.rotateLabels(90, 0);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        layout(s);
        Bitmap portrait = draw(s);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postRotate(-90);
        write(Bitmap.createBitmap(portrait, 0, 0, portrait.getWidth(), portrait.getHeight(), m, true), "land");
    }

    @Test
    public void format() throws Exception {
        sheet(CameraSheets.TAB_FORMAT, "format");
    }

    @Test
    public void size() throws Exception {
        sheet(CameraSheets.TAB_SIZE, "size");
    }

    private void sheet(int tab, String name) throws Exception {
        Screen s = screen(true);
        Bitmap under = draw(s);
        int[] size = {PhotoSize.NORMAL};
        BottomSheetDialog d = CameraSheets.formatAndSize((AppCompatActivity) s.activity, Arrays.asList(FormatChoice.values()), FormatChoice.HEIC,
                com.particlesdevs.photoncamera.processing.PhotoFormat.JPEG, x -> { }, new CameraSheets.SizeSource() {
                    @Override public int current() { return size[0]; }
                    @Override public boolean full() { return true; }
                    @Override public boolean max() { return true; }
                    @Override public void pick(int v) { size[0] = v; }
                }, tab);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        View root = d.findViewById(android.R.id.content).getRootView();
        root.measure(View.MeasureSpec.makeMeasureSpec(s.w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(s.h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, s.w, s.h);
        Canvas c = new Canvas(under);
        c.drawColor(0x73000000);
        root.draw(c);
        write(under, name);
    }

    private static Bitmap draw(Screen s) {
        Bitmap image = Bitmap.createBitmap(s.w, s.h, Bitmap.Config.ARGB_8888);
        image.eraseColor(0xFF000000);
        s.root.draw(new Canvas(image));
        return image;
    }

    private static void write(Bitmap image, String state) throws Exception {
        File dir = new File("build/reports/viewfinder/concept");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, "cam_" + state + "_app.png"))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
