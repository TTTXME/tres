package com.scamera.app.processing.opengl.postpipeline;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.Pair;

import com.scamera.app.processing.render.ColorCorrectionTransform;
import com.scamera.app.processing.render.NoiseModeler;
import com.scamera.app.processing.render.Parameters;
import com.scamera.app.settings.PreferenceValue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ScamLabParamsTest {
    @SuppressWarnings("unchecked")
    @Test public void theParametersOfTheShotComeBackForThePostPipeline() throws Exception {
        try (org.mockito.MockedStatic<com.scamera.app.app.ScameraApp> app =
                     org.mockito.Mockito.mockStatic(com.scamera.app.app.ScameraApp.class)) {
            app.when(com.scamera.app.app.ScameraApp::getSettings).thenReturn(new com.scamera.app.api.Settings());
            roundTrip();
        }
    }

    @SuppressWarnings("unchecked")
    private void roundTrip() throws Exception {
        final Parameters p = new Parameters();
        p.rawSize = new Point(8192, 6144);
        p.activeArea = new Rect(0, 0, 8192, 6144);
        p.hybridFinalSize = new Point(4096, 3072);
        p.outputScale = 2f;
        p.asShotNeutral = new float[]{.5f, 1f, .7f};
        p.cameraToProPhoto = new float[]{1, 2, 3, 4, 5, 6, 7, 8, 9};
        p.colorTransform = new ColorCorrectionTransform();
        p.colorTransform.matrix = new float[]{9, 8, 7, 6, 5, 4, 3, 2, 1};
        p.shadingMap = new float[]{1, 1.5f, 1.5f, 2, 1, 1, 1, 1};
        p.shadingGrid = new Point(2, 1);
        p.shadingMapUsed = true;
        p.cfaPattern = 3;
        p.iso = 640;
        p.exposureTime = 1 / 50.0;
        p.sceneCct = 4100f;
        p.effectiveStackSamples = Double.NaN;
        p.rotationDeg = 90;
        p.toneCurves = new float[][]{{0, .5f, 1}, null, {0, 1}};
        final Pair<Double, Double>[] model = new Pair[]{new Pair<>(1e-4, 1e-6), new Pair<>(2e-4, 2e-6), new Pair<>(3e-4, 3e-6)};
        p.noiseModel = new NoiseModeler(model, 100, 640, 0);
        p.noiseModel.stackModel = new Pair[]{new Pair<>(1e-5, 1e-7), new Pair<>(2e-5, 2e-7), new Pair<>(3e-5, 3e-7)};
        p.scamRgb = java.nio.ByteBuffer.allocate(4);
        final JSONObject json = new JSONObject(ScamLabParams.toJson(p).toString());
        assertFalse(json.has("scamRgb"));
        final Parameters q = ScamLabParams.fromJson(json);
        assertEquals(8192, q.rawSize.x);
        assertEquals(6144, q.activeArea.bottom);
        assertEquals(3072, q.hybridFinalSize.y);
        assertEquals(2f, q.outputScale, 0);
        assertArrayEquals(p.asShotNeutral, q.asShotNeutral, 0);
        assertArrayEquals(p.cameraToProPhoto, q.cameraToProPhoto, 0);
        assertArrayEquals(p.colorTransform.matrix, q.colorTransform.matrix, 0);
        assertArrayEquals(p.shadingMap, q.shadingMap, 0);
        assertEquals(2, q.shadingGrid.x);
        assertTrue(q.shadingMapUsed);
        assertEquals(3, q.cfaPattern);
        assertEquals(640, q.iso);
        assertEquals(1 / 50.0, q.exposureTime, 1e-12);
        assertEquals(4100f, q.sceneCct, 0);
        assertTrue(Double.isNaN(q.effectiveStackSamples));
        assertEquals(90, q.rotationDeg);
        assertNull(q.toneCurves[1]);
        assertArrayEquals(new float[]{0, .5f, 1}, q.toneCurves[0], 0);
        assertEquals(2e-4, q.noiseModel.rawModel[1].first, 1e-12);
        assertEquals(3e-7, q.noiseModel.stackModel[2].second, 1e-15);
        assertNull(q.scamRgb);
        assertEquals(0, q.hotSites.length);
    }

    @Test public void thePreferencesOfTheShotKeepTheirTypes() throws Exception {
        final Map<String, Object> all = new HashMap<>();
        all.put("s", "nlm");
        all.put("b", true);
        all.put("i", 7);
        all.put("f", 1.5f);
        all.put("l", 9_000_000_000L);
        all.put("set", new java.util.HashSet<>(java.util.Arrays.asList("a", "b")));
        final Map<String, Object> back = ScamLabParams.prefs(new JSONObject(ScamLabParams.prefs(all).toString()));
        assertEquals(all, back);
    }

    /** The lab's overlay stands for the whole file while it is set: its values, its absent keys unset; then the stored ones. */
    @Test public void theOverlayReadsAsTheShotAndGoesAway() {
        final SharedPreferences prefs = RuntimeEnvironment.getApplication().getSharedPreferences("lab_test", Context.MODE_PRIVATE);
        prefs.edit().putString("pref_a", "stored").putBoolean("pref_b", true).commit();
        final Map<String, Object> shot = new HashMap<>();
        shot.put("pref_a", "at the shot");
        PreferenceValue.overlay(prefs, shot);
        try {
            assertTrue(PreferenceValue.overlaid());
            assertEquals("at the shot", PreferenceValue.get(prefs, "pref_a"));
            assertNull(PreferenceValue.get(prefs, "pref_b"));
            final SharedPreferences other = RuntimeEnvironment.getApplication().getSharedPreferences("lab_other", Context.MODE_PRIVATE);
            other.edit().putString("pref_a", "other file").commit();
            assertEquals("other file", PreferenceValue.get(other, "pref_a"));
        } finally {
            PreferenceValue.overlay(null, null);
        }
        assertFalse(PreferenceValue.overlaid());
        assertEquals("stored", PreferenceValue.get(prefs, "pref_a"));
        assertEquals(true, PreferenceValue.get(prefs, "pref_b"));
    }

    @Test public void theFrameMapRampRunsFromFewFramesToMany() {
        assertEquals(0xFF1B0C41, ScamLabRun.colour(0));
        assertEquals(0xFFFDE725, ScamLabRun.colour(1));
        assertEquals(0xFFFDE725, ScamLabRun.colour(3));
    }
}
