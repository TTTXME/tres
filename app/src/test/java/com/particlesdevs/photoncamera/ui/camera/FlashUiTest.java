package com.particlesdevs.photoncamera.ui.camera;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.capture.FlashMode;
import com.particlesdevs.photoncamera.databinding.CameraFragmentBinding;
import com.particlesdevs.photoncamera.databinding.LayoutMainTopbarBinding;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.settings.ShadeCatalog;
import com.particlesdevs.photoncamera.ui.camera.model.CameraFragmentModel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * P590: the flash modes in the top row and the shade - a tap cycles Выкл. -> Авто -> Вкл. -> Фонарик, the mode is stored in
 * pref_ae_mode (default off), the item shows the mode's concept icon in the accent (off: white), a lens without a flash keeps
 * off <-> torch.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class FlashUiTest {
    private Context context;
    private SettingsManager manager;
    private MockedStatic<ScameraApp> camera;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        manager = new SettingsManager(context);
        manager.getDefaultPreferences().edit().clear().commit();
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
        FlashMode.bindLens(true);
        ShadeCatalog.setFlashAvailable(true);
    }

    @After
    public void tearDown() {
        FlashMode.bindLens(true);
        camera.close();
    }

    @Test public void theModeIsStoredAndOffByDefault() {
        assertEquals(FlashMode.OFF, PreferenceKeys.getAeMode());
        List<Integer> seen = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            PreferenceKeys.setAeMode(FlashMode.tap());
            seen.add(PreferenceKeys.getAeMode());
        }
        assertEquals(Arrays.asList(FlashMode.AUTO, FlashMode.ON, FlashMode.TORCH, FlashMode.OFF), seen);
        PreferenceKeys.setAeMode(FlashMode.AUTO);
        assertEquals(FlashMode.AUTO, FlashMode.shown());
        // the front lens (no flash unit): the stored Auto shows off, a tap lights the torch (its screen light)
        FlashMode.bindLens(false);
        assertEquals(FlashMode.OFF, FlashMode.shown());
        assertEquals(FlashMode.TORCH, FlashMode.tap());
        assertEquals("kept for the back lens", FlashMode.AUTO, PreferenceKeys.getAeMode());
    }

    @Test public void theTopRowItemShowsTheMode() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class)) {
            controller.setup();
            Activity activity = controller.get();
            CameraFragmentBinding binding = CameraFragmentBinding.inflate(LayoutInflater.from(new ContextThemeWrapper(activity,
                    R.style.Theme_Scamera_SettingsActivity)));
            CameraFragmentModel model = new CameraFragmentModel();
            model.setScreenAspectRatio(20f / 9f);
            binding.setUimodel(model);
            binding.layoutTopbar.setUimodel(model);
            binding.executePendingBindings();
            activity.setContentView(binding.getRoot());
            LayoutMainTopbarBinding tb = binding.layoutTopbar;
            for (int mode : new int[]{FlashMode.OFF, FlashMode.AUTO, FlashMode.ON, FlashMode.TORCH}) {
                PreferenceKeys.setAeMode(mode);
                TopBar.bind(tb);
                assertEquals(mode, tb.torchButton.getTag(R.id.torch_button));
                assertEquals("accent while not off", mode != FlashMode.OFF, tb.torchButton.isActivated());
                assertEquals(FlashMode.label(mode), String.valueOf(tb.torchButton.getContentDescription()));
                assertEquals(View.VISIBLE, tb.torchButton.getVisibility());
            }
            // a lens without a flash: Auto shows off
            FlashMode.bindLens(false);
            PreferenceKeys.setAeMode(FlashMode.AUTO);
            TopBar.bind(tb);
            assertEquals(FlashMode.OFF, tb.torchButton.getTag(R.id.torch_button));
            assertFalse(tb.torchButton.isActivated());
        }
    }

    @Test public void theIconsAreTheConceptGlyphs() {
        assertEquals(R.drawable.cv_bolt_off, FlashMode.icon(FlashMode.OFF));
        assertEquals(R.drawable.cv_bolt_auto, FlashMode.icon(FlashMode.AUTO));
        assertEquals(R.drawable.cv_bolt_fill, FlashMode.icon(FlashMode.ON));
        assertEquals(R.drawable.cv_torch, FlashMode.icon(FlashMode.TORCH));
        for (int m = 0; m < 4; m++) assertNotNull(context.getDrawable(FlashMode.icon(m)));
    }

    @Test public void theShadeTileCyclesTheSameModes() {
        SharedPreferences prefs = manager.getDefaultPreferences();
        ShadeCatalog catalog = new ShadeCatalog(context, prefs);
        ShadeCatalog.Entry flash = catalog.entry(ShadeCatalog.FLASH);
        assertArrayEquals(new Object[]{"1", "2", "3", "0"}, flash.values);
        assertEquals(4, flash.labels.length);
        assertEquals(context.getResources().getStringArray(R.array.shade_l_flash)[1], String.valueOf(flash.labels[1]));
        assertEquals("1", catalog.value(flash));
        assertEquals("2", catalog.nextValue(flash));
        PreferenceKeys.setAeMode(FlashMode.ON);
        assertEquals("3", catalog.value(flash));
        assertEquals("0", catalog.nextValue(flash));
        assertNotNull(catalog.icon(flash));
        FlashMode.bindLens(false);
        assertEquals("1", catalog.value(flash));
        assertEquals("0", catalog.nextValue(flash));
    }
}
