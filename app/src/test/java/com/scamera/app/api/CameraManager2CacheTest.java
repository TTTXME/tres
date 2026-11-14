package com.scamera.app.api;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;
import android.hardware.camera2.CameraManager;

import com.google.gson.Gson;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.SettingsManager;
import com.scamera.app.ui.camera.data.CameraLensData;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;

/**
 * P680: the lens directory is built once while nothing it is built from changes (it was parsed again at every open) and again
 * when the package spoof, the user's cameras, the scan's revisions, the stored scan or the activity's CameraManager change.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class CameraManager2CacheTest {
    private MockedStatic<ScameraApp> app;
    private SettingsManager settings;
    private String cameras;

    @Before public void setup() throws Exception {
        final Context context = RuntimeEnvironment.getApplication();
        settings = new SettingsManager(context);
        app = mockStatic(ScameraApp.class);
        app.when(ScameraApp::getAppContext).thenReturn(context);
        app.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        app.when(ScameraApp::getSettingsManagerStatic).thenReturn(settings);
        app.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(i -> context.getString(i.getArgument(0)));
        PreferenceKeys.initialise(settings);
        CameraManager2.forgetCache();
        cameras = PreferenceKeys.Key.CAMERAS_PREFERENCE_FILE_NAME.mValue;
        final CameraLensData main = new CameraLensData("3");
        main.setFacing(1);
        main.setCameraFocalLength(11.56f);
        main.setCameraAperture(1.85f);
        main.setCamera35mmFocalLength(36.4f);
        main.setZoomFactor(1f);
        settings.set(cameras, "spoof_signature", "off");
        settings.set(cameras, "aux_revision", "aux4");
        settings.set(cameras, PreferenceKeys.Key.ALL_CAMERA_IDS_KEY.mValue, new LinkedHashSet<>(Collections.singletonList("3")));
        settings.set(cameras, PreferenceKeys.Key.ALL_CAMERA_LENS_KEY.mValue, new LinkedHashSet<>(Collections.singletonList(new Gson().toJson(main))));
    }

    @After public void cleanup() {
        CameraManager2.forgetCache();
        app.close();
    }

    private static CameraManager noCameras() throws Exception {
        final CameraManager manager = mock(CameraManager.class);
        when(manager.getCameraCharacteristics(anyString())).thenThrow(new IllegalArgumentException("no such camera"));
        return manager;
    }

    @Test public void builtOnceWhileItsSourcesStay() throws Exception {
        final CameraManager manager = noCameras();
        final CameraManager2 first = CameraManager2.get(manager, settings);
        assertArrayEquals(new String[]{"3"}, first.getCameraIdList());
        assertSame(first, CameraManager2.get(manager, settings));
        assertSame("another SettingsManager of the same files", first, CameraManager2.get(manager, new SettingsManager(RuntimeEnvironment.getApplication())));
    }

    @Test public void builtAgainWhenTheUsersCamerasChange() throws Exception {
        final CameraManager manager = noCameras();
        final CameraManager2 first = CameraManager2.get(manager, settings);
        settings.getDefaultPreferences().edit().putStringSet("user_camera_ids", new HashSet<>(Arrays.asList("9"))).commit();
        final CameraManager2 second = CameraManager2.get(manager, settings);
        assertNotSame(first, second);
        assertSame(second, CameraManager2.get(manager, settings));
    }

    @Test public void builtAgainWhenTheScanOrItsRevisionChanges() throws Exception {
        final CameraManager manager = noCameras();
        final CameraManager2 first = CameraManager2.get(manager, settings);
        settings.set(cameras, "scan_revision", "p66");
        final CameraManager2 second = CameraManager2.get(manager, settings);
        assertNotSame(first, second);
        settings.set(cameras, PreferenceKeys.Key.ALL_CAMERA_IDS_KEY.mValue, new LinkedHashSet<>(Arrays.asList("3", "5")));
        assertNotSame(second, CameraManager2.get(manager, settings));
    }

    @Test public void builtAgainWhenTheSpoofChanges() throws Exception {
        final CameraManager manager = noCameras();
        final CameraManager2 first = CameraManager2.get(manager, settings);
        settings.getDefaultPreferences().edit().putBoolean("pref_camera_package_spoof_enabled", true).commit();
        final CameraManager2 second = CameraManager2.get(manager, settings);
        assertNotSame(first, second);
        assertSame("the spoofed scan is kept in its turn", second, CameraManager2.get(manager, settings));
    }

    @Test public void builtAgainForAnotherActivitysCameraManager() throws Exception {
        final CameraManager2 first = CameraManager2.get(noCameras(), settings);
        assertNotSame(first, CameraManager2.get(noCameras(), settings));
    }
}
