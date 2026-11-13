package com.scamera.app.processing.opengl.postpipeline;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;

import com.scamera.app.R;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.SettingsManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * P614 «Why тон» V2: the switch (default V1, the stored V2, the scam_dev A/B, the press's choice, V1 with a film of the old route)
 * and the reference tone's statistics, exposure and fusion strengths as decompiled (WhyV2Ae), its pyramid and AgX matrices.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class WhyV2ToneTest {
    private Context context;
    private SettingsManager manager;
    private MockedStatic<ScameraApp> camera;

    private static File src(String path) {
        File f = new File(path);
        return f.exists() ? f : new File("app/" + path);
    }

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(src(path).toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Before public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        manager = new SettingsManager(context);
        manager.getDefaultPreferences().edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        PreferenceKeys.initialise(manager);
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
    }

    @After public void tearDown() { if (camera != null) camera.close(); }

    @Test public void switchDefaultsToV1() {
        assertFalse("V1 by default (an old install has no value)", PreferenceKeys.whyToneV2());
        manager.set("default_scope", "pref_scam_hybrid_tone_version", "v2");
        assertTrue(PreferenceKeys.whyToneV2());
        manager.set("default_scope", "pref_scam_hybrid_tone_version", "v1");
        assertFalse(PreferenceKeys.whyToneV2());
        manager.set("default_scope", "pref_scam_hybrid_tone_version", "something else");
        assertFalse("an unknown value is V1", PreferenceKeys.whyToneV2());
    }

    @Test public void pipelineChoice() {
        // no press look: the settings now; a film of the old route keeps V1, the reference film engine takes V2
        assertFalse(PostPipeline.whyToneV2(null, null, false, 0L));
        manager.set("default_scope", "pref_scam_hybrid_tone_version", "v2");
        assertTrue(PostPipeline.whyToneV2(null, null, false, 0L));
        final com.scamera.app.processing.color.FilmSim.Settings film =
                org.mockito.Mockito.mock(com.scamera.app.processing.color.FilmSim.Settings.class);
        assertFalse(PostPipeline.whyToneV2(null, film, false, 0L));
        assertTrue(PostPipeline.whyToneV2(null, film, true, 0L));
        // 50 MP runs V2, the 200 MP grid keeps V1
        assertTrue(PostPipeline.whyToneV2(null, null, false, 8192L * 6144));
        assertFalse(PostPipeline.whyToneV2(null, null, false, 16320L * 12288));
    }

    /** The node order buildNodes makes (default settings, no film, no lens filter, no resize). */
    private static java.util.List<String> nodes(boolean v2) throws Exception {
        final PostPipeline p = new PostPipeline();
        p.mParameters = org.mockito.Mockito.mock(com.scamera.app.processing.render.Parameters.class);
        p.mParameters.scamRgb = java.nio.ByteBuffer.allocateDirect(4);
        p.lensFilter = LensFilter.Choice.NONE;
        p.glint = org.mockito.Mockito.mock(com.scamera.app.processing.opengl.GLInterface.class);
        final java.lang.reflect.Method m = PostPipeline.class.getDeclaredMethod("buildNodes");
        m.setAccessible(true);
        m.invoke(p);
        final java.util.List<String> out = new java.util.ArrayList<>();
        for (com.scamera.app.processing.opengl.nodes.Node n : p.Nodes) out.add(n.Name);
        return out;
    }

    @Test public void nodeOrder() throws Exception {
        ScamHybridBurst.lastHlMask = null;
        // V1: the tone nodes as before P614 (WhyStats, WhyFusion, WhyLumaSharpen, WhyCombine), the tail after them
        final java.util.List<String> v1 = nodes(false);
        final java.util.List<String> expectedV1 = new java.util.ArrayList<>(java.util.Arrays.asList("ScamRgb", "ScamHdrDenoise"));
        if (DenoiseStages.on()) expectedV1.add("WhyMsDenoise");
        expectedV1.addAll(java.util.Arrays.asList("WhyStats", "WhyFusion", "WhyLumaSharpen", "WhyCombine", "Rotate"));
        assertEquals(expectedV1, v1);
        manager.set("default_scope", "pref_scam_hybrid_tone_version", "v2");
        final java.util.List<String> v2 = nodes(true);
        final java.util.List<String> expectedV2 = new java.util.ArrayList<>(expectedV1.subList(0, expectedV1.indexOf("WhyStats")));
        expectedV2.addAll(java.util.Arrays.asList("WhyV2Stats", "WhyLumaSharpen", "WhyV2Highlights", "WhyV2Fusion", "WhyV2Tone", "Rotate"));
        assertEquals(expectedV2, v2);
        // the press's choice wins over the settings now (a switch moved while the shot is processed)
        final ShotLook pressV2 = ShotLook.now();
        assertTrue(pressV2.toneV2);
        manager.set("default_scope", "pref_scam_hybrid_tone_version", "v1");
        assertTrue(PostPipeline.whyToneV2(pressV2, null, false, 0L));
        assertFalse(PostPipeline.whyToneV2(ShotLook.now(), null, false, 0L));
    }

    @Test public void settingRowOnTheMainPage() throws IOException {
        final String xml = read("src/main/res/xml/preferences.xml");
        final int fringe = xml.indexOf("android:key=\"pref_scam_hybrid_fringe_method\"");
        final int tone = xml.indexOf("android:key=\"pref_scam_hybrid_tone_version\"");
        final int lenses = xml.indexOf("android:key=\"root_lenses\"");
        assertTrue("beside the processing mode and the fringe method, before the groups", fringe > 0 && tone > fringe && lenses > tone);
        assertTrue(xml.contains("SegmentedListPreference android:key=\"pref_scam_hybrid_tone_version\""));
        assertTrue(xml.contains("android:entryValues=\"@array/scam_hybrid_tone_version_values\" android:defaultValue=\"v1\""));
        assertEquals("Why tone", context.getString(R.string.prefs_scam_hybrid_tone_version_title));
        assertArrayEquals(new String[]{"V1", "V2"}, context.getResources().getStringArray(R.array.scam_hybrid_tone_version_entries));
        assertArrayEquals(new String[]{"v1", "v2"}, context.getResources().getStringArray(R.array.scam_hybrid_tone_version_values));
        final String ru = read("src/main/res/values-ru/strings_settings.xml");
        assertTrue(ru.contains("<string name=\"prefs_scam_hybrid_tone_version_title\">Why тон</string>"));
        // a short plain description in both languages
        for (String file : new String[]{"src/main/res/values/strings_settings.xml", "src/main/res/values-ru/strings_settings.xml"}) {
            final String s = read(file);
            final int a = s.indexOf("prefs_scam_hybrid_tone_version_summary");
            final String row = s.substring(a, s.indexOf("</string>", a));
            assertTrue(file, a > 0 && row.contains("V1") && row.contains("V2") && row.length() < 220);
        }
    }

    @Test public void strideAndLevels() {
        assertEquals(4, WhyV2Ae.stride(4096, 3072));
        assertEquals(3, WhyV2Ae.stride(4000, 3000));
        assertEquals(1, WhyV2Ae.stride(800, 600));
        assertEquals(8, WhyV2Ae.stride(8192, 6144));
        assertEquals(1000, WhyV2Ae.samples(4000, 4));
        assertEquals(1334, WhyV2Ae.samples(4000, 3));
        // 4096 x 3072: halving while both sides are above 48 - the last level 64 x 48
        final int[][] lv = WhyV2Ae.levels(4096, 3072);
        assertEquals(7, lv.length);
        assertArrayEquals(new int[]{4096, 3072}, lv[0]);
        assertArrayEquals(new int[]{2048, 1536}, lv[1]);
        assertArrayEquals(new int[]{64, 48}, lv[6]);
        // odd sizes floor
        final int[][] odd = WhyV2Ae.levels(4001, 3001);
        assertArrayEquals(new int[]{2000, 1500}, odd[1]);
        assertArrayEquals(new int[]{62, 46}, odd[odd.length - 1]);
        // a small image stays one level
        assertEquals(1, WhyV2Ae.levels(48, 400).length);
        // the native loop keeps at most 12 levels
        assertEquals(12, WhyV2Ae.levels(1 << 20, 1 << 20).length);
        assertEquals(1, WhyV2Fusion.baseScale(4096, 3072));
        // the full grid at every size V2 runs at (50 MP included), as the reference
        assertEquals(1, WhyV2Fusion.baseScale(8192, 6144));
        assertEquals(1, WhyV2Fusion.baseScale(10000, 10000));
        assertEquals(PostPipeline.V2_MAX_PIXELS, WhyV2Fusion.FULL_LIMIT);
    }

    private static WhyV2Ae.Hist hist(float[][] values, int[] counts, float clip) {
        final WhyV2Ae.Hist h = new WhyV2Ae.Hist();
        for (int i = 0; i < values.length; i++)
            for (int k = 0; k < counts[i]; k++) WhyV2Ae.add(h, values[i][0], values[i][1], values[i][2], clip, false);
        return h;
    }

    @Test public void flatGreyScene() {
        final WhyV2Ae.Params p = new WhyV2Ae.Params();
        final WhyV2Ae.Hist h = hist(new float[][]{{0.2f, 0.2f, 0.2f}}, new int[]{1000}, 1f);
        final WhyV2Ae.Result r = WhyV2Ae.finish(h, p, 1f, 0f, 0f);
        final float v = 819 / 4096f;   // bin int(0.2 x 4095.99)
        assertEquals(v, r.p10, 1e-6f);
        assertEquals(v, r.p50, 1e-6f);
        assertEquals(v, r.p98, 1e-6f);
        assertEquals(v, r.p995, 1e-6f);
        assertEquals(v + 0.0001220703125f, r.geo, 1e-5f);
        assertEquals(0.15f / r.geo, r.ae, 1e-5f);
        assertEquals(1f, r.deficit, 0f);
        // no spread: no shadow lift; the peak far under 0.85 x bright: no highlight strength; flat: the contrast boost in full
        assertEquals(0f, r.effS, 0f);
        assertEquals(0f, r.effH, 0f);
        assertEquals(1f, r.flat, 1e-6f);
        assertEquals(0.3f, r.contrastBoost, 1e-6f);
        assertEquals(2.45f + 0.3f, r.agxSlope, 1e-6f);
        assertEquals(0.59f - 0.24f, r.acesD, 1e-6f);
        assertEquals(1f + 0.225f, r.uchiA, 1e-6f);
        assertEquals(1.1f + 0.09f, r.macroContrast, 1e-6f);
        final float[] em = WhyV2Ae.exposures(r);
        assertArrayEquals(new float[]{1f, 1f, 1f, 1f}, em, 0f);
    }

    @Test public void zeroBinQuirksAndHighlights() {
        final WhyV2Ae.Params p = new WhyV2Ae.Params();
        // 30 % black (bin 0), 60 % mid grey, 10 % above white (clip 1): a p10 in bin 0 moves on to bin 1's level (the native scan
        // sets it while it is still 0, empty bins included), p98 / p995 the top bin
        final WhyV2Ae.Hist h = hist(new float[][]{{0f, 0f, 0f}, {0.1f, 0.1f, 0.1f}, {1.5f, 1.5f, 1.5f}}, new int[]{300, 600, 100}, 1f);
        final WhyV2Ae.Result r = WhyV2Ae.finish(h, p, 1f, 0f, 0f);
        assertEquals(1 / 4096f, r.p10, 1e-9f);
        assertEquals(409 / 4096f, r.p50, 1e-6f);
        assertEquals(4095 / 4096f, r.p98, 1e-6f);
        assertEquals(4095 / 4096f, r.p995, 1e-6f);
        assertEquals(0.1f, r.ovShare, 1e-6f);
        assertTrue("a large bright area: a highlight strength", r.effH > 0.1f && r.effH <= p.highlightStr);
        assertTrue("the spread lifts the shadows", r.effS > 0f);
        final float[] em = WhyV2Ae.exposures(r);
        assertEquals(Math.pow(2, -2.5 * r.effH), em[0], 1e-6);
        assertEquals(Math.pow(2, -1.2 * r.effH), em[1], 1e-6);
        assertEquals(1f, em[2], 0f);
        assertEquals(Math.min(Math.pow(2, 1.8 * r.effS), 16), em[3], 1e-5);
    }

    @Test public void overflowBlendAndNoiseCap() {
        final WhyV2Ae.Params p = new WhyV2Ae.Params();
        // a dark frame with a bright 1 %: ae from the target, the overflow limit 2 / p995 blended by hl_blend 0.5
        final WhyV2Ae.Hist h = hist(new float[][]{{0.01f, 0.01f, 0.01f}, {0.9f, 0.9f, 0.9f}}, new int[]{990, 10}, 1f);
        final WhyV2Ae.Result r = WhyV2Ae.finish(h, p, 1f, 0f, 0f);
        final float aeC = Math.min(p.maxBoost, 0.15f / r.geo);
        final float lim = p.hlOverflow / r.p995;
        assertTrue(aeC * r.p995 > p.hlOverflow);
        assertEquals(lim + 0.5f * (aeC - lim), r.ae, 1e-4f);
        assertEquals(aeC / r.ae, r.deficit, 1e-4f);
        // the noise cap: night_dim / min(max(sigma / 2, 0.026), sigma), sigma = sqrt(0.5 S + O), at least 1
        final WhyV2Ae.Result n = WhyV2Ae.finish(h, p, 1f, 0.004f, 1e-5f);
        final float sigma = (float) Math.sqrt(0.5f * 0.004f + 1e-5f);
        final float d = Math.min(Math.max(0.5f * sigma, 0.026f), sigma);
        assertEquals(0.05f / d, n.noiseCap, 1e-4f);
        assertEquals(Math.max(Math.min(r.ae, n.noiseCap), p.minLimit), n.ae, 1e-5f);
        // a very noisy model: the cap never goes under 1
        final WhyV2Ae.Result loud = WhyV2Ae.finish(h, p, 1f, 0.5f, 0.1f);
        assertEquals(1f, loud.noiseCap, 0f);
        assertEquals(1f, loud.ae, 1e-6f);
    }

    @Test public void faceMetering() {
        final WhyV2Ae.Params p = new WhyV2Ae.Params();
        final WhyV2Ae.Hist h = new WhyV2Ae.Hist();
        for (int i = 0; i < 900; i++) WhyV2Ae.add(h, 0.3f, 0.3f, 0.3f, 1f, false);
        for (int i = 0; i < 100; i++) WhyV2Ae.add(h, 0.05f, 0.05f, 0.05f, 1f, true);
        final WhyV2Ae.Result r = WhyV2Ae.finish(h, p, 1f, 0f, 0f);
        assertTrue(r.face);
        final float face = (float) (h.faceLumaSum * 0.001) / 100f;
        assertEquals(r.geo + 0.7f * (face - r.geo), r.meter, 1e-5f);
        // (the face maths of histogram_cs / the AE, kept; the reference's photo path itself never has a face: WhyV2Stats passes none)
    }

    @Test public void clipCeiling() {
        // the reference passes its highlight recovery factor (8) with short frames in the merge, whatever the data's maximum
        assertEquals(1f, WhyV2Stats.clipCeiling(false, 6f), 0f);
        assertEquals(8f, WhyV2Stats.clipCeiling(true, 1.02f), 0f);
        assertEquals(8f, WhyV2Stats.clipCeiling(true, 3f), 0f);
        assertEquals(8f, WhyV2Stats.clipCeiling(true, 40f), 0f);
        assertEquals(1f, WhyV2Ae.clipCeiling(1.05f), 0f);
        // short frames merged: the Bento route's flag, or the fast route's valid short frames in its report
        assertTrue(WhyV2Stats.shortFramesMerged(true, null));
        assertFalse(WhyV2Stats.shortFramesMerged(false, null));
        assertTrue(WhyV2Stats.shortFramesMerged(false,
                "HYBRID FAST MERGE HIGHLIGHT: accumulated validity of 2 valid short frame(s) applied to the highlight mask; frame 30"));
        assertFalse(WhyV2Stats.shortFramesMerged(false,
                "HYBRID FAST MERGE HIGHLIGHT: accumulated validity of 0 valid short frame(s) applied to the highlight mask"));
        assertFalse(WhyV2Stats.shortFramesMerged(false, "HYBRID BENTO: not applied (no ultrashort)"));
        // the histograms scale by the ceiling
        final WhyV2Ae.Hist h = new WhyV2Ae.Hist();
        WhyV2Ae.add(h, 2f, 2f, 2f, 4f, false);
        assertEquals(1, h.luma[(int) (0.5f * 4095.99f)]);
    }

    @Test public void ownerFloatsAndNativeDefaults() {
        // the floats the owner's configuration stores (the menu's slider steps), not their rounded labels
        final WhyV2Ae.Params p = new WhyV2Ae.Params();
        assertEquals(Float.floatToIntBits(0.14999999f), Float.floatToIntBits(p.aeTarget));
        assertEquals(Float.floatToIntBits(0.049999997f), Float.floatToIntBits(p.nightDim));
        assertEquals(Float.floatToIntBits(0.90000004f), Float.floatToIntBits(p.brightThresh));
        assertEquals(Float.floatToIntBits(0.45000002f), Float.floatToIntBits(p.blendSmoothness));
        assertEquals(Float.floatToIntBits(2.1000001f), Float.floatToIntBits(p.highlightStr));
        assertTrue(0.14999999f != 0.15f && 2.1000001f != 2.1f);
        // a key the configuration does not hold: the native reader's default (0x42250000), radians by its float multiply
        assertEquals(0x42250000, Float.floatToIntBits(p.skinHueCenterDeg));
        assertEquals(Float.floatToIntBits(41.25f * 0.017453292f), Float.floatToIntBits(p.skinHueCenterRad()));
        // the native fmadd: one rounding (0.1 x 3 + -0.3 is 0 unfused in float, -7.450581E-9 fused)
        assertEquals(0f, 0.1f * 3f + -0.3f, 0f);
        assertEquals((float) (0.1f * 3.0 - 0.3f), WhyV2Ae.fma(0.1f, 3f, -0.3f), 0f);
        assertTrue(WhyV2Ae.fma(0.1f, 3f, -0.3f) != 0f);
    }

    @Test public void referenceNoiseIsTheOneFrameProfileMean() {
        // the reference's capture code: the mean of every SENSOR_NOISE_PROFILE pair (R, Gr, Gb, B), one frame, no stacking
        @SuppressWarnings("unchecked")
        final android.util.Pair<Double, Double>[] hal = new android.util.Pair[]{new android.util.Pair<>(4e-5, 2e-7),
                new android.util.Pair<>(2e-5, 1e-7), new android.util.Pair<>(3e-5, 1e-7), new android.util.Pair<>(5e-5, 4e-7)};
        final float[] n = com.scamera.app.processing.render.NoiseModeler.reportedMean(hal);
        assertEquals((float) (14e-5 / 4), n[0], 0f);
        assertEquals((float) (8e-7 / 4), n[1], 0f);
        // our stacked model of the same frames is a different (smaller) noise: V2 does not read it
        final com.scamera.app.processing.render.NoiseModeler m =
                new com.scamera.app.processing.render.NoiseModeler(hal, 100, 100, 0);
        m.computeStackingNoiseModel(8);
        assertArrayEquals(n, WhyV2Stats.referenceNoise(m), 0f);
        // no profile (or not above 0): the reference's fallback S 0.00015, O 1e-5
        assertArrayEquals(new float[]{0.00015f, 1e-5f}, WhyV2Stats.referenceNoise(null), 0f);
        assertArrayEquals(new float[]{0.00015f, 1e-5f},
                com.scamera.app.processing.render.NoiseModeler.reportedMean(new android.util.Pair[0]), 0f);
    }

    @Test public void insetOutsetInverse() {
        for (float s : new float[]{0f, 0.5f, 50f, 100f}) {
            final float[] a = WhyV2Ae.insetMatrix(s), b = WhyV2Ae.outsetMatrix(s);
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++) {
                    double v = 0;
                    for (int k = 0; k < 3; k++) v += a[k * 3 + i] * b[j * 3 + k];   // column-major (row i, col j)
                    assertEquals("strength " + s + " (" + i + "," + j + ")", i == j ? 1.0 : 0.0, v, 1e-5);
                }
            // the rows of the inset sum to 1 within the AgX matrix's own rounding (1.00014 at full strength): a grey stays grey
            for (int i = 0; i < 3; i++) assertEquals(1.0, a[i] + a[3 + i] + a[6 + i], 2e-4);
        }
        assertArrayEquals(new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1}, WhyV2Ae.insetMatrix(0f), 0f);
        // 50 (a percentage) is 0.5
        assertArrayEquals(WhyV2Ae.insetMatrix(0.5f), WhyV2Ae.insetMatrix(50f), 1e-7f);
        assertEquals(0.5f * 1f + 0.5f * 0.842479062253094f, WhyV2Ae.insetMatrix(50f)[0], 1e-6f);
    }

    @Test public void ownCurve() {
        final float[] c = WhyV2Tone.curveSamples(new WhyV2Ae.Params());
        assertEquals(1024, c.length);
        assertEquals(0f, c[0], 1e-6f);
        assertEquals(1f, c[1023], 1e-6f);
        for (int i = 1; i < c.length; i++) assertTrue("monotone at " + i, c[i] >= c[i - 1] - 1e-6f);
        // the configuration's middle point (0.5 -> 0.51)
        assertEquals(0.51f, c[512] + (c[511] - c[512]) * (512 - 511.5f), 2e-3f);
    }

    @Test public void ownCurveAsTheReferenceBuildsIt() {
        // the owner's points: the same table as our editor's curve (ToneCurve, the same monotone cubic) at 1024 samples
        final float[] ours = com.scamera.app.processing.color.ToneCurve.parse(WhyV2Ae.OWNER_POINTS).samples(1024);
        assertArrayEquals(ours, WhyV2Curve.samples(WhyV2Ae.OWNER_POINTS, new float[7], 1024), 0f);
        // the modifiers move the POINTS (then the cubic through them): offset +20 -> every point +0.1, clamped at 1
        final float[] p = WhyV2Curve.parse("0:0,0.5:0.5,1:1");
        final float[] q = WhyV2Curve.applyModifiers(p, new float[]{0, 0, 0, 0, 0, 0, 20});
        assertArrayEquals(new float[]{0f, 0.1f, 0.5f, 0.6f, 1f, 1f}, q, 1e-6f);
        // centre lift 100 at x 0.5: 2.5e-6 x 1000 x 100 = 0.25
        assertEquals(0.75f, WhyV2Curve.applyModifiers(p, new float[]{100, 0, 0, 0, 0, 0, 0})[3], 1e-6f);
        // stored as "%.4f" and read back; fewer than two points: the reference's default points
        assertArrayEquals(new float[]{0f, 0.1235f, 1f, 1f}, WhyV2Curve.stored(new float[]{0f, 0.123456f, 1f, 1f}), 0f);
        assertArrayEquals(WhyV2Curve.parse(WhyV2Curve.DEFAULT_POINTS), WhyV2Curve.parse("0.5:0.5"), 0f);
        // unsorted points are sorted, not merged
        assertArrayEquals(new float[]{0f, 0f, 0.5f, 0.3f, 1f, 1f}, WhyV2Curve.parse("1:1,0:0,0.5:0.3"), 0f);
    }

    @Test public void settingsReachTheParams() {
        // no stored value: the defaults (the owner's configuration / the native defaults)
        final WhyV2Ae.Params d = WhyV2Stats.settings(), p0 = new WhyV2Ae.Params();
        assertEquals(Float.floatToIntBits(p0.aeTarget), Float.floatToIntBits(d.aeTarget));
        assertEquals(p0.toneOperator, d.toneOperator);
        assertEquals(p0.curvePoints, d.curvePoints);
        assertEquals(41.25f, d.skinHueCenterDeg, 0f);
        assertTrue(d.agxHueLock);
        // P614: V1's rows at V1's decimal defaults (as setDefaultValues stores them) are the owner's floats, bit for bit
        final android.content.SharedPreferences prefs = manager.getDefaultPreferences();
        prefs.edit().putString("pref_scam_hybrid_why_ae_target", "0.15").putString("pref_scam_hybrid_why_bright_thresh", "0.9")
                .putString("pref_scam_hybrid_why_night_thresh", "0.05").putString("pref_scam_hybrid_why_highlight_str", "2.1")
                .putString("pref_scam_hybrid_why_blend_smoothness", "0.45").commit();
        final WhyV2Ae.Params at = WhyV2Stats.settings();
        for (float[] f : new float[][]{{at.aeTarget, p0.aeTarget}, {at.brightThresh, p0.brightThresh}, {at.nightDim, p0.nightDim},
                {at.highlightStr, p0.highlightStr}, {at.blendSmoothness, p0.blendSmoothness}})
            assertEquals(Float.floatToIntBits(f[1]), Float.floatToIntBits(f[0]));
        assertEquals(0.15f, WhyTone.value("ae_target", 0.15f), 0f);   // V1 reads its own decimal
        // the merged rows (V1's keys) and V2's own rows reach their fields; the operator is V1's switches
        prefs.edit().putString("pref_scam_hybrid_why_ae_target", "0.3").putBoolean("pref_scam_hybrid_why_curve_on", false)
                .putBoolean("pref_scam_hybrid_why_v2_agx_hue_lock", false).putString("pref_scam_hybrid_why_curve_offset", "-12")
                .putString("pref_scam_hybrid_why_v2_hr_amount", "0.75").putString("pref_scam_hybrid_why_bracket_denoise", "0.5")
                .putString("pref_scam_hybrid_why_agx_inset", "80").putString("pref_scam_hybrid_why_curve_points", "0:0,0.5:0.6,1:1")
                .putString("pref_scam_hybrid_why_vibrance_hl_str", "0.4").putString("pref_scam_hybrid_why_hr_room", "0.2")
                .putString("pref_scam_hybrid_why_classic_weight_mid", "1.5").putString("pref_scam_hybrid_why_night_thresh", "0.08")
                .putString("pref_scam_hybrid_why_v2_skin_hue_center", "50").putString("pref_scam_hybrid_why_skin_hue_center", "30")
                .putString("pref_scam_hybrid_why_v2_clarity", "0.5").putString("pref_scam_hybrid_why_clarity", "1.5").commit();
        final WhyV2Ae.Params p = WhyV2Stats.settings();
        assertEquals(0.3f, p.aeTarget, 0f);
        assertEquals(0, p.toneOperator);                          // no switch on: AgX
        assertFalse(p.agxHueLock);
        assertEquals(-12f, p.curveMods[6], 0f);
        assertEquals(0.75f, p.hrAmount, 0f);
        assertEquals(0.5f, p.bracketDenoise, 0f);
        assertEquals(80f, p.agxInset, 0f);
        assertEquals("0:0,0.5:0.6,1:1", p.curvePoints);
        assertEquals(0.4f, p.vibHl, 0f);
        assertEquals(0.2f, p.shoulderHeadroom, 0f);
        assertEquals(1.5f, p.weightMid, 0f);
        assertEquals(0.08f, p.nightDim, 0f);
        assertEquals(50f, p.skinHueCenterDeg, 0f);                // V2's own row, not V1's
        assertEquals(0.5f, p.clarity, 0f);
        // the switches' order is V1's: the own curve, Uchimura, ACES
        prefs.edit().putBoolean("pref_scam_hybrid_why_aces_on", true).commit();
        assertEquals(1, WhyV2Stats.settings().toneOperator);
        prefs.edit().putBoolean("pref_scam_hybrid_why_uchi_on", true).commit();
        assertEquals(2, WhyV2Stats.settings().toneOperator);
        prefs.edit().putBoolean("pref_scam_hybrid_why_curve_on", true).commit();
        assertEquals(3, WhyV2Stats.settings().toneOperator);
        // «Все шумодавы» off: no short frame denoise, as V1's
        prefs.edit().putBoolean("pref_scam_hybrid_denoise", false).commit();
        assertEquals(0f, WhyV2Stats.settings().bracketDenoise, 0f);
        // a value off the owner's float by more than two ulps stays as stored
        assertEquals(0.1500001f, WhyV2Stats.owner(0.1500001f, 0.14999999f), 0f);
        assertEquals(0.14999999f, WhyV2Stats.owner(0.15f, 0.14999999f), 0f);
    }

    @Test public void everyV2RowHasItsField() throws IOException {
        // P614: the merged rows are V1's rows of «Настройки изображения», V2's own rows pref_scam_hybrid_why_v2_*; WhyV2Stats reads
        // each, and nothing it reads lacks a row
        final String xml = read("src/main/res/xml/preferences.xml");
        final String stats = read("src/main/java/com/scamera/app/processing/opengl/postpipeline/WhyV2Stats.java");
        final java.util.Set<String> v2 = new java.util.HashSet<>();
        for (String[] m : WhyV2Stats.MERGED) {
            assertTrue(m[1], xml.contains("android:key=\"pref_scam_hybrid_why_" + m[1] + "\""));
            assertTrue(m[1], stats.contains("\"" + m[1] + "\"") || stats.contains("\"why_" + m[1] + "\""));
            assertTrue(m[0], v2.add(m[0]));
        }
        for (String own : WhyV2Stats.OWN) {
            assertTrue(own, xml.contains("android:key=\"pref_scam_hybrid_why_v2_" + own + "\""));
            assertTrue(own, v2.add(own));
        }
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile("android:key=\"pref_scam_hybrid_why_v2_([a-z0-9_]+)\"").matcher(xml);
        int n = 0;
        while (m.find()) { n++; assertTrue(m.group(1), java.util.Arrays.asList(WhyV2Stats.OWN).contains(m.group(1))); }
        assertEquals(WhyV2Stats.OWN.length, n);
        // every setting of V2's Params is reached: the 66 merged, the 5 own and the operator (V1's three switches)
        assertEquals(72, v2.size() + 1);
        final java.util.regex.Matcher r = java.util.regex.Pattern.compile("\"(?:why_)?(v2_[a-z0-9_]+)\"").matcher(stats);
        while (r.find()) assertTrue(r.group(1), xml.contains("android:key=\"pref_scam_hybrid_why_" + r.group(1) + "\""));
        // no page of its own any more
        assertFalse(xml.contains("scam_hybrid_why_v2_screen"));
    }

    @Test public void highlightStagesAsTheReferenceGatesThem() {
        assertFalse(WhyV2Highlights.bracketOn(1f, 1f));          // no short frames merged
        assertFalse(WhyV2Highlights.bracketOn(8f, 0.001f));
        assertTrue(WhyV2Highlights.bracketOn(8f, 0.0011f));
        assertFalse(WhyV2Highlights.hrOn(0.001f));
        assertTrue(WhyV2Highlights.hrOn(0.002f));
        assertEquals(new android.graphics.Point(2048, 1536), WhyV2Highlights.half(new android.graphics.Point(4096, 3072)));
        assertEquals(new android.graphics.Point(1, 1), WhyV2Highlights.half(new android.graphics.Point(1, 1)));
        assertEquals(0.85f, WhyV2Highlights.HR_THRESHOLD, 0f);
        assertEquals(24, WhyV2Highlights.HR_RADIUS);
    }

    @Test public void shadersStayStrict() throws IOException {
        // GLSL ES 3.x: no implicit int * float in the ported shaders, the reference names of the uniforms kept
        final String tone = read("src/main/assets/shaders/why/v2_tonemap.glsl");
        assertTrue(tone.contains("uniform float u_hdr_shoulder_headroom;"));
        assertTrue(tone.contains("#define FILM_LUT 0") && tone.contains("#define P3_OUT 0") && tone.contains("#define EF_SCALED 0"));
        for (String name : new String[]{"v2_clean", "v2_stride", "v2_ef_prep", "v2_pyr_down", "v2_ef_coarsest", "v2_ef_collapse", "v2_tonemap",
                "v2_bracket_dn", "v2_hr_extract", "v2_box", "v2_hr_apply"}) {
            final String s = read("src/main/assets/shaders/why/" + name + ".glsl");
            assertFalse(name + ": no #version of its own (GLProg adds it and highp int)", s.contains("#version"));
            assertFalse(name + ": no mediump", s.contains("mediump"));
        }
    }
}
