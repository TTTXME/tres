package com.scamera.app.processing.opengl.postpipeline;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.Pair;

import com.scamera.app.processing.DevelopRecord;
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

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * P665: the identity of the gallery developer's run with the shot's: what the camera writes before its post pipeline
 * (DevelopCapture) comes back from the DNG as the same Parameters, the same settings (the lab's base rule), the same scam_dev.txt
 * lines and merge numbers, and the merge's maps unchanged at the shot's size - so PostPipeline.develop runs with the shot's inputs;
 * a reduced grid (the preview) or a Sabre 2x shot's DNG grid scale only the size-bound fields.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class DevelopRunTest {
    private static <T> T withApp(java.util.concurrent.Callable<T> body) throws Exception {
        try (org.mockito.MockedStatic<com.scamera.app.app.ScameraApp> app =
                     org.mockito.Mockito.mockStatic(com.scamera.app.app.ScameraApp.class)) {
            app.when(com.scamera.app.app.ScameraApp::getSettings).thenReturn(new com.scamera.app.api.Settings());
            return body.call();
        }
    }

    @SuppressWarnings("unchecked")
    static Parameters shot() {
        final Parameters p = new Parameters();
        p.rawSize = new Point(4096, 3072);
        p.activeArea = new Rect(0, 0, 4096, 3072);
        p.hybridFinalSize = new Point(2048, 1536);
        p.asShotNeutral = new float[]{.5f, 1f, .7f};
        p.cameraToProPhoto = new float[]{1, 2, 3, 4, 5, 6, 7, 8, 9};
        p.colorTransform = new ColorCorrectionTransform();
        p.colorTransform.matrix = new float[]{9, 8, 7, 6, 5, 4, 3, 2, 1};
        p.shadingMap = new float[]{1, 1.5f, 1.5f, 2, 1, 1, 1, 1};
        p.shadingGrid = new Point(2, 1);
        p.iso = 640;
        p.rotationDeg = 90;
        p.filmFrameScale = .8f;
        p.portraitFaces = new float[]{.1f, .2f, .3f, .4f};
        final Pair<Double, Double>[] model = new Pair[]{new Pair<>(1e-4, 1e-6), new Pair<>(2e-4, 2e-6), new Pair<>(3e-4, 3e-6)};
        p.noiseModel = new NoiseModeler(model, 100, 640, 0);
        p.noiseModel.stackModel = new Pair[]{new Pair<>(1e-5, 1e-7), new Pair<>(2e-5, 2e-7), new Pair<>(3e-5, 3e-7)};
        return p;
    }

    static Map<String, Object> prefs() {
        final Map<String, Object> m = new HashMap<>();
        m.put("pref_scam_hybrid_why_sharp_gain", "1.25");
        m.put("pref_scam_hybrid_tone_version", "v2");
        m.put("pref_scam_hybrid_denoise", true);
        m.put("pref_film_sim_stock", "kodak_portra_400");
        m.put("pref_scam_hybrid_diagnostics", true);
        m.put("pref_keep_raw_burst", true);
        m.put("pref_some_int", 3);
        return m;
    }

    static DevelopRecord.Worker worker() {
        final DevelopRecord.Worker w = new DevelopRecord.Worker();
        w.bentoApplied = true;
        w.bentoFactor = 6.5f;
        w.bentoUsClipped = .02f;
        w.effectiveMedian = 11f;
        w.noiseFactor = .8f;
        w.normals = 12;
        w.report = "HYBRID FAST MERGE HIGHLIGHT: accumulated validity 0.9";
        return w;
    }

    /** The record as the camera appends it and the editor reads it back from the DNG. */
    static DevelopRecord throughTheFile(DevelopRecord r) throws Exception {
        final File f = File.createTempFile("shot", ".dng");
        try {
            Files.write(f.toPath(), new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
            DevelopRecord.append(f, r);
            final DevelopRecord back = DevelopRecord.read(f);
            assertNotNull(back);
            return back;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    @Test public void theDevelopTakesTheShotsParameters() throws Exception {
        withApp(() -> {
            final Parameters p = shot();
            final Map<String, Float> dev = new TreeMap<>();
            dev.put("hybrid_sun_bloom_no_short", 0f);
            final DevelopRecord r = throughTheFile(DevelopCapture.record(p, prefs(), dev, worker(), 4f, 4096, 3072, 1f, false));
            // the full develop: the shot's Parameters field for field (what PostPipeline.develop reads)
            final Parameters q = DevelopRun.parameters(r, 4096, 3072);
            assertEquals(ScamLabParams.toJson(p).toString(), ScamLabParams.toJson(q).toString());
            assertEquals(4f, r.headroom(), 0);
            assertEquals(Float.valueOf(0f), DevelopRun.devLines(r).get("hybrid_sun_bloom_no_short"));
            // the merge's numbers set for the run are the shot's (DevelopCapture.worker reads them back)
            final boolean half = ScamHybridBurst.lastRgbHalf;
            DevelopRun.applyWorker(r.worker(), false);
            assertEquals(worker().toJson().toString(), DevelopCapture.worker().toJson().toString());
            ScamHybridBurst.lastRgbHalf = half;
            return null;
        });
    }

    @Test public void aReducedGridScalesOnlyItsSize() throws Exception {
        withApp(() -> {
            final DevelopRecord r = throughTheFile(DevelopCapture.record(shot(), prefs(), new TreeMap<>(), worker(), 1f, 4096, 3072, 1f, false));
            final Parameters q = DevelopRun.parameters(r, 1024, 768);
            assertEquals(new Point(1024, 768), q.rawSize);
            assertEquals(new Rect(0, 0, 1024, 768), q.activeArea);
            assertEquals(new Point(512, 384), q.hybridFinalSize); // half of the grid, as the shot's
            assertEquals(.25f, q.outputScale, 1e-6);
            // the rest is the shot's
            final JSONObject a = ScamLabParams.toJson(shot()), b = ScamLabParams.toJson(q);
            for (String k : new String[]{"rawSize", "activeArea", "sensorPix", "hybridFinalSize", "outputScale"}) {
                a.remove(k);
                b.remove(k);
            }
            assertEquals(a.toString(), b.toString());
            return null;
        });
    }

    @Test public void aSabreTwoXShotsFinalSizeIsAgainstTheDngGrid() throws Exception {
        withApp(() -> {
            final Parameters p = shot();
            p.rawSize = new Point(8192, 6144); // the merge's 2x RGB
            p.activeArea = new Rect(0, 0, 8192, 6144);
            p.hybridFinalSize = new Point(4096, 3072); // «Уменьшить до» 12 MP: the DNG's own size
            p.outputScale = 2f;
            final DevelopRecord r = throughTheFile(DevelopCapture.record(p, prefs(), new TreeMap<>(), worker(), 1f, 4096, 3072, 1f, false));
            final Parameters q = DevelopRun.parameters(r, 4096, 3072);
            assertNull("the DNG grid is the final size: nothing to shrink", q.hybridFinalSize);
            assertEquals(1f, q.outputScale, 1e-6);
            return null;
        });
    }

    @Test public void theSettingsAreTheShotsWithTheChangesOverThem() throws Exception {
        withApp(() -> {
            final DevelopRecord r = throughTheFile(DevelopCapture.record(shot(), prefs(), new TreeMap<>(), worker(), 1f, 4096, 3072, 1f, false));
            final Map<String, Object> v = DevelopRun.values(r, null);
            // the lab's base rule (ScamLabRun.basePrefs): never the diagnostics, kept bursts, the lab's switch, the stamp
            assertEquals(false, v.get("pref_scam_hybrid_diagnostics"));
            assertEquals(false, v.get("pref_keep_raw_burst"));
            assertEquals(false, v.get(ScamLab.PREF));
            assertEquals(false, v.get(com.scamera.app.processing.MergeStamp.KEY));
            // typed as SharedPreferences returns them
            assertEquals("1.25", v.get("pref_scam_hybrid_why_sharp_gain"));
            assertEquals(true, v.get("pref_scam_hybrid_denoise"));
            assertEquals(3, v.get("pref_some_int"));
            final Map<String, Object> changes = new HashMap<>();
            changes.put("pref_scam_hybrid_why_sharp_gain", "0.5");
            changes.put("pref_film_sim_stock", null);
            final Map<String, Object> c = DevelopRun.values(r, changes);
            assertEquals("0.5", c.get("pref_scam_hybrid_why_sharp_gain"));
            assertFalse("a change to null unsets the key (its default)", c.containsKey("pref_film_sim_stock"));
            // read through the overlay as every node reads its setting
            final Context ctx = RuntimeEnvironment.getApplication();
            final SharedPreferences prefs = ctx.getSharedPreferences("p665", Context.MODE_PRIVATE);
            prefs.edit().putString("pref_scam_hybrid_why_sharp_gain", "9").apply();
            PreferenceValue.overlay(prefs, c);
            try {
                assertEquals("0.5", PreferenceValue.get(prefs, "pref_scam_hybrid_why_sharp_gain"));
                assertNull(PreferenceValue.get(prefs, "pref_film_sim_stock"));
            } finally {
                PreferenceValue.overlay(null, null);
            }
            assertEquals("9", PreferenceValue.get(prefs, "pref_scam_hybrid_why_sharp_gain"));
            return null;
        });
    }

    @Test public void theMapsAreTheShotsAtItsSizeAndFollowAReducedGrid() {
        final byte[] flags = new byte[16];
        flags[0] = 1;
        flags[5] = 4;
        flags[15] = 2;
        final java.nio.ByteBuffer same = DevelopRun.map(flags, 4, 4, 4, 4, true);
        assertNotNull(same);
        final byte[] back = new byte[16];
        same.get(back);
        assertArrayEquals(flags, back);
        // 2x2 cells: the flags OR-ed, the frame map averaged
        assertArrayEquals(new byte[]{5, 0, 0, 2}, DevelopRun.resampled(flags, 4, 4, 2, 2, true));
        final byte[] frames = {10, 20, 30, 40, 10, 20, 30, 40, 50, 50, 0, 0, 50, 50, 0, 0};
        assertArrayEquals(new byte[]{15, 35, 50, 0}, DevelopRun.resampled(frames, 4, 4, 2, 2, false));
        assertNull(DevelopRun.map(null, 4, 4, 4, 4, true));
    }

    @Test public void theRecordKeepsTheCropsAfterThePipeline() throws Exception {
        withApp(() -> {
            final DevelopRecord r = throughTheFile(DevelopCapture.record(shot(), prefs(), new TreeMap<>(), worker(), 1f, 4096, 3072, 2f, true));
            final JSONObject c = r.meta.getJSONObject("crop");
            assertEquals(2.0, c.getDouble("zoom"), 0);
            assertTrue(c.getBoolean("isz4x3"));
            final DevelopRecord none = throughTheFile(DevelopCapture.record(shot(), prefs(), new TreeMap<>(), worker(), 1f, 4096, 3072, Float.NaN, false));
            assertEquals(1.0, none.meta.getJSONObject("crop").getDouble("zoom"), 0);
            return null;
        });
    }
}
