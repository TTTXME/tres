package com.scamera.app.processing.render;

import static com.scamera.app.processing.render.ParametersSceneCctTest.cst;
import static com.scamera.app.processing.render.ParametersSceneCctTest.result;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.ColorSpaceTransform;

import com.scamera.app.capture.CaptureController;
import com.scamera.app.processing.color.PlainColourMemoryAccess;
import com.scamera.app.processing.color.SceneIlluminant;
import com.scamera.app.settings.PreferenceKeys;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * P370 (A6): «Источник цвета» acts where the settings say it does. On a 12 MP shot the ISP branch takes the result's matrix for
 * every value (0 / 1 / 2 render the same); on a mosaic shot with the app's own white balance and real forward matrices
 * «Характеристики» (1) is the DNG chain and «Захват» (2) the transform. P370 (A7): SceneIlluminant is asked with the physical
 * camera, so a logical-physical camera id ("0-3") of the vivo X200 Ultra still finds its daylight neutral.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ParametersColourMethodTest {
    private MockedStatic<com.scamera.app.app.ScameraApp> app;
    private MockedStatic<PreferenceKeys> prefsKeys;
    private CameraCharacteristics previous;
    private com.scamera.app.api.Settings settings;
    /** The X200 Ultra main camera's ISP matrix of a daylight shot (rows about 1). */
    private static final ColorSpaceTransform VIVO = cst(128, 205, -63, -14, -22, 166, -16, 4, -64, 188);

    @Before public void setUp() {
        settings = new com.scamera.app.api.Settings();
        app = mockStatic(com.scamera.app.app.ScameraApp.class);
        app.when(com.scamera.app.app.ScameraApp::getSettings).thenReturn(settings);
        prefsKeys = mockStatic(PreferenceKeys.class, invocation -> {
            final String name = invocation.getMethod().getName();
            if (name.equals("isScamEnabled")) return true;
            if (name.equals("routeInternalValue")) return 1f;
            return invocation.callRealMethod();
        });
        previous = CaptureController.mCameraCharacteristics;
        final CameraCharacteristics c = ParametersSceneCctTest.phy110();
        when(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)).thenReturn(cst(128, 70, 40, 14, 30, 90, 8, 4, 20, 82));
        when(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)).thenReturn(cst(128, 72, 38, 14, 31, 89, 8, 5, 22, 80));
        CaptureController.mCameraCharacteristics = c;
        final SharedPreferences prefs = RuntimeEnvironment.getApplication().getSharedPreferences("colour_method_test", 0);
        prefs.edit().clear().commit();
        PlainColourMemoryAccess.useStore(prefs, "vivo/PD2454/PD2454:16/1");
    }

    @After public void tearDown() {
        org.robolectric.shadows.ShadowBuild.reset();
        CaptureController.mCameraCharacteristics = previous;
        PlainColourMemoryAccess.reset();
        prefsKeys.close();
        app.close();
    }

    private Parameters shot(int method, boolean mosaicOwn) {
        settings.colourMethod = method;
        final Parameters p = new Parameters();
        p.physicalID = 3;
        p.cameraID = "3";
        if (mosaicOwn) {
            p.mosaicShot = true;
            p.ownNeutral = true;
            p.customNeutral = new float[]{0.42f, 1f, 0.66f};
            p.resolveColour(true, result(VIVO, new float[]{0.3945f, 1f, 0.6396f}));
        } else {
            p.resolveColour(false, result(VIVO, new float[]{0.3945f, 1f, 0.6396f}));
        }
        return p;
    }

    @Test public void a12MpShotRendersTheSameForEveryValue() {
        final Parameters one = shot(1, false);
        assertEquals("ISP_CST", one.colourSource);
        for (int method : new int[]{0, 2}) {
            final Parameters p = shot(method, false);
            assertEquals(one.colourSource, p.colourSource);
            for (int i = 0; i < 9; i++) {
                assertEquals("CCT " + i, one.colorTransform.matrix[i], p.colorTransform.matrix[i], 0f);
                assertEquals("s2p " + i, one.cameraToProPhoto[i], p.cameraToProPhoto[i], 0f);
            }
        }
    }

    @Test public void aMosaicShotWithItsOwnNeutralFollowsTheChoice() {
        final Parameters characteristics = shot(1, true);
        assertEquals("DNG_FM", characteristics.colourSource);
        final Parameters capture = shot(2, true);
        assertEquals("LEGACY_CAPTURED", capture.colourSource);
        assertTrue(capture.mosaicMatrixSource, capture.mosaicMatrixSource.contains("LEGACY_CAPTURED"));
        assertEquals(1f / 0.42f, capture.cameraToProPhoto[0], 1e-5f);
        assertTrue(Math.abs(capture.colorTransform.matrix[0] - characteristics.colorTransform.matrix[0]) > 0.05f);
    }

    /** A7: the X200 Ultra (PD2454) with the logical-physical id "0-3": the scene light from its camera 3's daylight neutral. */
    @Test public void sceneIlluminantKnowsThePhysicalCamera() {
        org.robolectric.shadows.ShadowBuild.setDevice("PD2454");
        settings.colourMethod = 1;
        final Parameters p = new Parameters();
        p.physicalID = 3;
        p.cameraID = "0-3";
        p.resolveColour(false, result(VIVO, new float[]{0.55f, 1f, 0.45f}));
        assertEquals("ISP_CST", p.colourSource);
        final float want = SceneIlluminant.cct("3", p.asShotNeutral, p.colorTransform.matrix);
        assertTrue(want > 0f);
        assertEquals(want, p.sceneCct, 0f);
        assertTrue(p.sceneCctSource, p.sceneCctSource.contains("SceneIlluminant"));
        assertEquals(-1f, SceneIlluminant.cct("0-3", p.asShotNeutral, p.colorTransform.matrix), 0f);
        assertNotNull(p.colourSummary());
    }
}
