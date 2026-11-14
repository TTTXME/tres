package com.scamera.app.processing.opengl.postpipeline;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.scamera.app.processing.DevelopRecord;
import com.scamera.app.processing.render.Converter;
import com.scamera.app.processing.render.Parameters;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Map;
import java.util.TreeMap;

/**
 * P696: the develop record of a DNG without one (DevelopDefaults), made from the DNG's tags: the Parameters the post pipeline
 * reads (colour, white, noise of the RGB over the headroom, exposure, rotation, lens shading), the settings it is given, and the
 * record through the file as the developer reads it - while a camera's record stays what it is.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class DevelopDefaultsTest {
    private static <T> T withApp(java.util.concurrent.Callable<T> body) throws Exception {
        try (org.mockito.MockedStatic<com.scamera.app.app.ScameraApp> app =
                     org.mockito.Mockito.mockStatic(com.scamera.app.app.ScameraApp.class)) {
            app.when(com.scamera.app.app.ScameraApp::getSettings).thenReturn(new com.scamera.app.api.Settings());
            return body.call();
        }
    }

    static DevelopDefaults.Facts facts() {
        final DevelopDefaults.Facts f = new DevelopDefaults.Facts();
        f.width = 4080;
        f.height = 3072;
        f.neutral = new float[]{.484375f, 1f, .6416016f};
        f.cameraToXyzD50 = new float[]{1.01f, .254f, .344f, .253f, .837f, .063f, .035f, -.499f, 2.04f};
        f.sceneCct = 5825f;
        f.noiseProfile = new double[]{2e-4, 4e-7, 3e-4, 5e-7, 4e-4, 6e-7};
        f.iso = 50;
        f.exposureTime = 1 / 254.0;
        f.fNumber = 2.27f;
        f.focalMm = 22.48f;
        f.rotationDeg = 90;
        f.headroom = 2f;
        f.whiteLevel = 65535;
        f.blackLevel = 4100f;
        f.software = "SCAMERA 0.98 (31254) 5b8d8d04";
        f.shadingW = 3;
        f.shadingH = 2;
        f.shadingMap = new float[4 * 6];
        for (int i = 0; i < f.shadingMap.length; i++) f.shadingMap[i] = 1f + i * .01f;
        return f;
    }

    @Test public void theParametersAreTheDngsTags() throws Exception {
        withApp(() -> {
            final DevelopDefaults.Facts f = facts();
            final Parameters p = DevelopDefaults.parameters(f);
            assertEquals(4080, p.rawSize.x);
            assertEquals(3072, p.rawSize.y);
            assertEquals(new android.graphics.Rect(0, 0, 4080, 3072), p.activeArea);
            assertArrayEquals(f.neutral, p.asShotNeutral, 0);
            // camera -> ProPhoto: the DNG's camera -> XYZ D50 (the white balance in it), then the standard ProPhoto -> sRGB
            final float[] want = new float[9];
            Converter.multiply(Converter.sXYZtoProPhoto, f.cameraToXyzD50, want);
            assertArrayEquals(want, p.cameraToProPhoto, 1e-6f);
            final float[] srgb = new float[9];
            Converter.multiply(Converter.sXYZtoSRGB, Converter.sProPhotoToXYZ, srgb);
            assertArrayEquals(srgb, p.proPhotoToSrgb, 1e-6f);
            assertArrayEquals(srgb, p.colorTransform.matrix, 1e-6f);
            assertEquals(5825f, p.sceneCct, 0);
            assertEquals(50, p.iso);
            assertEquals(1 / 254.0, p.exposureTime, 1e-12);
            assertEquals(2.27f, p.fNumber, 0);
            assertEquals(22.48f, p.focalMm, 0);
            assertEquals(90, p.rotationDeg);
            assertEquals(1f, p.outputScale, 0);
            assertNull(p.hybridFinalSize);
            // the noise of the RGB the pipeline takes: the DNG's level times the headroom (S x 2, O x 4), one frame
            for (int c = 0; c < 3; c++) {
                assertEquals(f.noiseProfile[2 * c] * 2, p.noiseModel.rawModel[c].first, 1e-12);
                assertEquals(f.noiseProfile[2 * c + 1] * 4, p.noiseModel.rawModel[c].second, 1e-15);
                assertEquals(p.noiseModel.rawModel[c].first, p.noiseModel.stackModel[c].first, 0);
                assertEquals(p.noiseModel.rawModel[c].second, p.noiseModel.stackModel[c].second, 0);
            }
            // the DNG's lens-shading map
            assertEquals(3, p.shadingGrid.x);
            assertEquals(2, p.shadingGrid.y);
            assertArrayEquals(f.shadingMap, p.shadingMap, 0);
            return null;
        });
    }

    @Test public void withoutMatricesNoiseOrShadingTheDefaultsStand() throws Exception {
        withApp(() -> {
            final DevelopDefaults.Facts f = new DevelopDefaults.Facts();
            f.width = 64;
            f.height = 48;
            f.neutral = new float[]{.5f, 1f, .8f};
            f.rotationDeg = 270;
            final Parameters p = DevelopDefaults.parameters(f);
            // the white balance alone, the camera RGB taken as sRGB
            assertArrayEquals(new float[]{2f, 0, 0, 0, 1f, 0, 0, 0, 1.25f}, p.cameraToProPhoto, 1e-6f);
            assertArrayEquals(new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1}, p.proPhotoToSrgb, 0);
            assertEquals(Parameters.ColourSource.IDENTITY_FALLBACK.name(), p.colourSource);
            // NoiseModeler's generic sensor at ISO 100, positive and finite
            assertEquals(100, p.iso);
            for (int c = 0; c < 3; c++) {
                assertTrue(p.noiseModel.rawModel[c].first > 0 && Double.isFinite(p.noiseModel.rawModel[c].first));
                assertTrue(p.noiseModel.stackModel[c].second > 0);
            }
            // a flat shading map
            assertEquals(4 * p.shadingGrid.x * p.shadingGrid.y, p.shadingMap.length);
            for (float v : p.shadingMap) assertEquals(1f, v, 0);
            assertEquals(270, p.rotationDeg);
            return null;
        });
    }

    @Test public void theRecordReadsBackAsTheDevelopsAndSaysWhereItCameFrom() throws Exception {
        withApp(() -> {
            final DevelopDefaults.Facts f = facts();
            final Map<String, Object> prefs = new TreeMap<>();
            prefs.put("pref_scam_hybrid_why_sharp_gain", "1.6");
            prefs.put("pref_scam_hybrid_denoise", false);
            final Map<String, Float> dev = new TreeMap<>();
            dev.put("hybrid_sun_bloom_no_short", 0f);
            final DevelopRecord r = DevelopRunTest.throughTheFile(DevelopDefaults.record(f, prefs, dev));
            assertTrue(DevelopDefaults.fromTags(r));
            assertEquals(f.software, r.meta.getString("software"));
            assertEquals(2f, r.headroom(), 0);
            // the develop's Parameters at the DNG's grid are the ones made from the tags, field for field
            final Parameters q = DevelopRun.parameters(r, 4080, 3072);
            assertEquals(ScamLabParams.toJson(DevelopDefaults.parameters(f)).toString(), ScamLabParams.toJson(q).toString());
            // the settings given, the lab's base rule over them
            final Map<String, Object> shot = DevelopRun.shotValues(r);
            assertEquals("1.6", shot.get("pref_scam_hybrid_why_sharp_gain"));
            assertEquals(Boolean.FALSE, shot.get("pref_scam_hybrid_denoise"));
            assertEquals(Boolean.FALSE, shot.get("pref_scam_hybrid_diagnostics"));
            assertEquals(Float.valueOf(0f), DevelopRun.devLines(r).get("hybrid_sun_bloom_no_short"));
            // one frame's merge numbers, no maps, no crop
            final DevelopRecord.Worker w = r.worker();
            assertFalse(w.bentoApplied);
            assertEquals(1, w.normals);
            assertEquals(1f, w.noiseFactor, 0);
            assertTrue(Float.isNaN(w.effectiveMedian));
            assertNull(r.clipFlags);
            assertNull(r.frames);
            assertNull(r.hlMask);
            assertEquals(1.0, r.meta.getJSONObject("crop").getDouble("zoom"), 0);
            assertFalse(r.meta.getJSONObject("crop").getBoolean("isz4x3"));
            return null;
        });
    }

    @Test public void aCamerasRecordIsNotFromTags() throws Exception {
        withApp(() -> {
            final DevelopRecord r = DevelopRunTest.throughTheFile(DevelopCapture.record(DevelopRunTest.shot(), DevelopRunTest.prefs(),
                    new TreeMap<>(), DevelopRunTest.worker(), 4f, 4096, 3072, 1f, false));
            assertFalse(DevelopDefaults.fromTags(r));
            assertFalse(DevelopDefaults.fromTags(null));
            return null;
        });
    }
}
