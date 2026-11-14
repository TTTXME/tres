package com.scamera.app.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.util.Range;

import com.scamera.app.processing.ImageFrame;
import com.scamera.app.processing.yuv.YuvFrames;
import com.scamera.app.settings.PreferenceKeys;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * P646 «Склейка YUV»: the series after the shutter (YuvMergePlan) - N at the metered exposure, the short frames only where the
 * ring frame clips (gain lowered first), one long frame where its shadows are deep (gain raised first, the shutter within the
 * hand-held cap), no bracket at the camera's curve, the count within the memory budget of packed frames; the capture curve and
 * the series' controls (YuvMergeRoute).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class YuvMergePlanTest {
    private MockedStatic<PreferenceKeys> prefs;
    private static final Range<Long> TIMES = new Range<>(100_000L, 430_464_311L);   // the X200 Pro front (report)
    private static final Range<Integer> ISOS = new Range<>(50, 1536);
    private static final long FRAME_8BIT = YuvFrames.packedBytes(3264, 2448, 8), FRAME_P010 = YuvFrames.packedBytes(3264, 2448, 10);

    @Before public void setup() {
        prefs = mockStatic(PreferenceKeys.class);
        prefs.when(() -> PreferenceKeys.hybridSwitch(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        prefs.when(() -> PreferenceKeys.hybridValue(anyString(), anyFloat())).thenAnswer(i -> i.getArgument(1));
        prefs.when(PreferenceKeys::hdrFrames).thenReturn(true);
    }

    @After public void cleanup() { prefs.close(); }

    private static int count(HybridPlan plan, ImageFrame.CaptureRole role) {
        int n = 0;
        for (HybridPlan.Request r : plan.requests) n += r.role == role ? 1 : 0;
        return n;
    }

    private static CaptureRequest.Builder newRequestBuilder() throws Exception {
        Class<?> metadata = Class.forName("android.hardware.camera2.impl.CameraMetadataNative");
        return CaptureRequest.Builder.class.getConstructor(metadata, boolean.class, int.class, String.class, java.util.Set.class)
                .newInstance(metadata.getConstructor().newInstance(), false, -1, "0", null);
    }

    private static YuvMergePlan.Tuning tuning() { return new YuvMergePlan.Tuning(6, true, 3f, 2f); }

    @Test public void aSceneWithoutClipOrShadowsTakesTheNormalFramesOnly() {
        final HybridPlan plan = YuvMergePlan.build(20_000_000L, 820, TIMES, ISOS, 0f, 0.01f, tuning(), FRAME_8BIT, true);
        assertEquals(6, plan.requests.size());
        assertEquals(6, count(plan, ImageFrame.CaptureRole.NORMAL));
        for (HybridPlan.Request r : plan.requests) assertTrue(r.shutterNs == 20_000_000L && r.iso == 820);
        assertTrue(plan.description, plan.description.startsWith("YUV MERGE plan:"));
    }

    @Test public void clippedHighlightsTakeTwoShortFramesGainFirst() {
        final HybridPlan plan = YuvMergePlan.build(20_000_000L, 820, TIMES, ISOS, 0.03f, 0.01f, tuning(), FRAME_8BIT, true);
        assertEquals(2, count(plan, ImageFrame.CaptureRole.EXTRA_SHORT));
        for (HybridPlan.Request r : plan.requests) {
            if (r.role != ImageFrame.CaptureRole.EXTRA_SHORT) continue;
            assertEquals(1 / 8.0, r.ratio, 0.01);
            assertTrue(r.shutterNs > 19_500_000L && r.shutterNs <= 20_000_000L);   // the gain goes down first: ISO 820 -> 103
            assertEquals(103, r.iso);
        }
        // at the gain floor the shutter goes down instead
        final HybridPlan low = YuvMergePlan.build(20_000_000L, 50, TIMES, ISOS, 0.03f, 0.01f, tuning(), FRAME_8BIT, true);
        for (HybridPlan.Request r : low.requests)
            if (r.role == ImageFrame.CaptureRole.EXTRA_SHORT) assertTrue(r.iso == 50 && r.shutterNs == 2_500_000L);
    }

    @Test public void deepShadowsTakeOneLongFrameWithinTheHandHeldCap() {
        final HybridPlan plan = YuvMergePlan.build(20_000_000L, 300, TIMES, ISOS, 0f, 0.2f, tuning(), FRAME_8BIT, true);
        assertEquals(1, count(plan, ImageFrame.CaptureRole.LONG));
        for (HybridPlan.Request r : plan.requests)
            if (r.role == ImageFrame.CaptureRole.LONG) assertTrue(r.iso == 1200 && r.shutterNs == 20_000_000L && Math.abs(r.ratio - 4) < 0.01);
        // the gain at its top: the shutter grows, never past 66 ms
        final HybridPlan dark = YuvMergePlan.build(40_000_000L, 1536, TIMES, ISOS, 0f, 0.2f, tuning(), FRAME_8BIT, true);
        for (HybridPlan.Request r : dark.requests)
            if (r.role == ImageFrame.CaptureRole.LONG) assertTrue(r.shutterNs <= 66_000_000L && r.ratio >= 1.4);
    }

    @Test public void noBracketAtTheCamerasCurveOrWhenSwitchedOff() {
        assertEquals(6, YuvMergePlan.build(20_000_000L, 820, TIMES, ISOS, 0.05f, 0.3f, tuning(), FRAME_8BIT, false).requests.size());
        final YuvMergePlan.Tuning off = new YuvMergePlan.Tuning(6, false, 3f, 2f);
        assertEquals(6, YuvMergePlan.build(20_000_000L, 820, TIMES, ISOS, 0.05f, 0.3f, off, FRAME_8BIT, true).requests.size());
    }

    @Test public void theSeriesFitsTheMemoryBudget() {
        final YuvMergePlan.Tuning many = new YuvMergePlan.Tuning(12, true, 3f, 2f);
        final HybridPlan ten = YuvMergePlan.build(20_000_000L, 820, TIMES, ISOS, 0.05f, 0.3f, many, FRAME_P010, true);
        assertTrue(ten.description, (long) ten.requests.size() * FRAME_P010 <= YuvMergePlan.BUDGET_BYTES);
        assertEquals(23_970_816L, FRAME_P010);   // 3264 x 2448 P010: 24 MB a frame
        final HybridPlan eight = YuvMergePlan.build(20_000_000L, 820, TIMES, ISOS, 0.05f, 0.3f, many, FRAME_8BIT, true);
        assertEquals(15, eight.requests.size());   // 12 N + 2 ES + 1 L
    }

    @Test public void anUnknownExposureLeavesTheFramesToTheCamerasAe() {
        final HybridPlan plan = YuvMergePlan.build(0, 0, TIMES, ISOS, 0.05f, 0.3f, tuning(), FRAME_8BIT, true);
        assertEquals(6, plan.requests.size());
        for (HybridPlan.Request r : plan.requests) assertTrue(r.autoExposure && r.role == ImageFrame.CaptureRole.NORMAL);
    }

    @Test public void theFrameCountFollowsTheGeneralZslSetting() {
        // P650 (owner: «привяжи количество кадров в склейке к общей настройке количества кадров»): the N count is the general
        // «Кадры ZSL» setting (getHybridZslFrames), not the old YUV-only cap of 12
        prefs.when(PreferenceKeys::getHybridZslFrames).thenReturn(60);
        final YuvMergePlan.Tuning t = YuvMergePlan.Tuning.read();
        assertEquals(60, t.frames);
        // the series then uses that many N, clamped only by the packed-frame memory budget and the camera's limits
        final int memCap = (int) Math.max(2, Math.min(YuvMergePlan.MAX_FRAMES, YuvMergePlan.BUDGET_BYTES / FRAME_8BIT));
        assertTrue(memCap < 60);
        final HybridPlan plan = YuvMergePlan.build(20_000_000L, 820, TIMES, ISOS, 0f, 0.01f, t, FRAME_8BIT, true);
        assertEquals(memCap, count(plan, ImageFrame.CaptureRole.NORMAL));
        assertTrue(plan.description, plan.description.contains("N 60->" + memCap));   // the memory cap is logged, as on the RAW path
    }

    @Test public void aLowGeneralSettingIsHonouredWithoutAMemoryCapNote() {
        prefs.when(PreferenceKeys::getHybridZslFrames).thenReturn(5);
        final YuvMergePlan.Tuning t = YuvMergePlan.Tuning.read();
        assertEquals(5, t.frames);
        final HybridPlan plan = YuvMergePlan.build(20_000_000L, 820, TIMES, ISOS, 0f, 0.01f, t, FRAME_8BIT, true);
        assertEquals(5, count(plan, ImageFrame.CaptureRole.NORMAL));
        assertFalse(plan.description, plan.description.contains("memory:"));   // nothing was cut, so no cap note
    }

    @Test public void anExplicitYuvFrameCountOverridesTheGeneralSetting() {
        // «Кадры YUV» non-zero is a per-lens override; 0 (the default) follows «Кадры ZSL»
        prefs.when(PreferenceKeys::getHybridZslFrames).thenReturn(30);
        prefs.when(() -> PreferenceKeys.hybridValue("yuv_frames", 0f)).thenReturn(8f);
        assertEquals(8, YuvMergePlan.Tuning.read().frames);
    }

    @Test public void theCaptureCurveIsMonotoneSrgb() {
        final float[] pairs = YuvMergeRoute.curvePairs(101);
        assertEquals(128, pairs.length);   // 64 points at most
        assertEquals(0f, pairs[0], 0f);
        assertEquals(1f, pairs[126], 1e-6f);
        assertEquals(1f, pairs[127], 1e-5f);
        for (int i = 1; i < 64; i++) assertTrue(pairs[2 * i] > pairs[2 * i - 2] && pairs[2 * i + 1] > pairs[2 * i - 1]);
        assertEquals(0.5f, YuvMergeRoute.srgb(0.214f), 0.001f);
        assertEquals(4, YuvMergeRoute.curvePairs(2).length);
    }

    @Test public void theSeriesControlsAreOurCurveMinimalNoiseReductionAndNoEdge() throws Exception {
        final CameraCharacteristics c = mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS)).thenReturn(101);
        when(c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)).thenReturn(new int[]{0, 1, 2, 3, 4});
        when(c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)).thenReturn(new int[]{0, 1, 2, 3});
        when(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)).thenReturn(new int[]{0, 1, 2, 5, 6, 7, 18, 19, 20});
        when(c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)).thenReturn(new int[]{0, 1, 2});
        assertTrue(YuvMergeRoute.curveSupported(c));
        final CaptureRequest.Builder b = newRequestBuilder();
        final YuvMergeRoute.Session s = new YuvMergeRoute.Session("1", YuvMergeRoute.YUV, false, true, true, "");
        final String done = YuvMergeRoute.applyCaptureControls(b, c, s);
        assertEquals(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE, (int) b.get(CaptureRequest.TONEMAP_MODE));
        assertEquals(CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL, (int) b.get(CaptureRequest.NOISE_REDUCTION_MODE));
        assertEquals(CameraMetadata.EDGE_MODE_OFF, (int) b.get(CaptureRequest.EDGE_MODE));
        assertEquals(64, b.get(CaptureRequest.TONEMAP_CURVE).getPointCount(android.hardware.camera2.params.TonemapCurve.CHANNEL_GREEN));
        assertTrue(done, done.contains("CONTRAST_CURVE 64") && done.contains("MINIMAL") && done.contains("edge OFF"));
        // a camera without MANUAL_POST_PROCESSING takes no curve
        when(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)).thenReturn(new int[]{0, 1});
        assertFalse(YuvMergeRoute.curveSupported(c));
    }

    @Test public void theAfterShutterSeriesLocksAwbForOneWhiteBalance() throws Exception {
        // P650: the after-shutter series' frames carry the camera's white balance baked in; locking AWB keeps every frame on one
        // white point so the merge is colour-consistent (no yellow-green / magenta split). The ring route keeps the running AWB.
        final CameraCharacteristics c = mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE)).thenReturn(Boolean.TRUE);
        when(c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)).thenReturn(new int[]{0});
        when(c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)).thenReturn(new int[]{0});
        prefs.when(() -> PreferenceKeys.scamDevSwitch(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        final CaptureRequest.Builder after = newRequestBuilder();
        final String done = YuvMergeRoute.applyCaptureControls(after, c, new YuvMergeRoute.Session("1", YuvMergeRoute.YUV, false, true, false, ""));
        assertEquals(Boolean.TRUE, after.get(CaptureRequest.CONTROL_AWB_LOCK));
        assertTrue(done, done.contains("AWB locked"));
        // the ZSL ring route (not after the shutter) keeps the camera's running AWB
        final CaptureRequest.Builder ring = newRequestBuilder();
        YuvMergeRoute.applyCaptureControls(ring, c, new YuvMergeRoute.Session("1", YuvMergeRoute.YUV, false, false, false, ""));
        assertNull(ring.get(CaptureRequest.CONTROL_AWB_LOCK));
        // a camera that cannot lock AWB is left alone
        when(c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE)).thenReturn(Boolean.FALSE);
        final CaptureRequest.Builder noLock = newRequestBuilder();
        YuvMergeRoute.applyCaptureControls(noLock, c, new YuvMergeRoute.Session("1", YuvMergeRoute.YUV, false, true, false, ""));
        assertNull(noLock.get(CaptureRequest.CONTROL_AWB_LOCK));
    }

    /**
     * P656: the «Из буфера ZSL» ring follows the general count + 2 (P650) within its memory: unchanged at the default 20 at 8 and
     * 10 bits (8 MP), cut at a large count (65: 67 frames of 24 MB before - the reader's 56, ~1.3 GB of 10-bit Images).
     */
    @Test public void theYuvRingIsCutToItsMemory() {
        final long eight = YuvFrames.packedBytes(3264, 2448, 8), ten = YuvFrames.packedBytes(3264, 2448, 10);
        assertEquals(22, YuvMergePlan.ringFrames(50, 20, eight, 4));
        assertEquals(22, YuvMergePlan.ringFrames(50, 20, ten, 4));
        assertEquals(20, YuvMergePlan.ringFrames(20, 20, ten, 4)); // the ZSL buffer setting stays the ceiling
        final int big = YuvMergePlan.ringFrames(100, 65, ten, 4);
        assertTrue(big < 67 && big * ten <= YuvMergePlan.RING_BUDGET_BYTES + 2 * ten);
        assertEquals(67, YuvMergePlan.ringFrames(100, 65, 0, 4)); // unknown size: as before
        assertEquals(4, YuvMergePlan.ringFrames(1, 1, ten, 4));
        final String src;
        try {
            src = new String(java.nio.file.Files.readAllBytes(new java.io.File(
                    "src/main/java/com/scamera/app/capture/CaptureController.java").toPath()), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        // P693: within the budget of the phone's memory class
        assertTrue(src.contains("YuvMergePlan.ringFrames(PreferenceKeys.getZslBufferCountValue(), PreferenceKeys.getHybridZslFrames(), yuv.frameBytes, SUSPENDED_RING,"));
        assertTrue(src.contains("YuvMergePlan.budget()); // P693"));
    }

    /** P690 (owner's log 2026-11-14): N frames filling the whole budget left no room for the brackets of a backlit scene. */
    @Test public void theBracketsKeepTheirRoomWhenTheNFillTheBudget() {
        final YuvMergePlan.Tuning twenty = new YuvMergePlan.Tuning(20, true, 3f, 2f);
        final long frame = 18L << 20; // 4096 x 3072 8-bit: 16 frames in the budget
        final long tight = 288L << 20; // the 16-frame budget of the owner's log
        final HybridPlan plan = YuvMergePlan.build(8_333_326L, 78, TIMES, ISOS, 0.1681f, 0.162f, twenty, frame, true, tight);
        int n = 0, es = 0, l = 0;
        for (HybridPlan.Request r : plan.requests) {
            if (r.role == ImageFrame.CaptureRole.NORMAL) n++;
            else if (r.role == ImageFrame.CaptureRole.EXTRA_SHORT) es++;
            else if (r.role == ImageFrame.CaptureRole.LONG) l++;
        }
        assertEquals(2, es);
        assertEquals(1, l);
        assertEquals(13, n);
        assertTrue(plan.requests.size() <= 16);
        // a scene with nothing clipped and no deep shadows keeps every frame for N
        assertEquals(16, YuvMergePlan.build(8_333_326L, 78, TIMES, ISOS, 0f, 0.01f, twenty, frame, true, tight).requests.size());
        assertEquals(0, YuvMergePlan.bracketsWanted(twenty, false, 0.2f, 0.2f));
    }

    /** P693 (owner, 2026-11-14: «Увеличь бюджет памяти для yuv»): a phone above the low memory class keeps the whole series. */
    @Test public void aPhoneAboveTheLowClassHasTheLargerBudget() {
        assertEquals(YuvMergePlan.BUDGET_BYTES, YuvMergePlan.budget(true, 8L << 30));
        assertEquals(YuvMergePlan.BUDGET_BYTES, YuvMergePlan.budget(false, 0));
        assertEquals(YuvMergePlan.BUDGET_BYTES_HIGH, YuvMergePlan.budget(false, 16L << 30));
        final YuvMergePlan.Tuning twenty = new YuvMergePlan.Tuning(20, true, 3f, 2f);
        final long eight = 18L << 20, ten = 36L << 20; // 4096 x 3072 at 8 / 10 bits
        final HybridPlan big = YuvMergePlan.build(8_333_326L, 78, TIMES, ISOS, 0.1681f, 0.162f, twenty, eight, true,
                YuvMergePlan.BUDGET_BYTES_HIGH);
        assertEquals("20 N + 2 ES + 1 L", 23, big.requests.size());
        final HybridPlan big10 = YuvMergePlan.build(8_333_326L, 78, TIMES, ISOS, 0.1681f, 0.162f, twenty, ten, true,
                YuvMergePlan.BUDGET_BYTES_HIGH);
        assertEquals("20 N + 2 ES + 1 L", 23, big10.requests.size());
        assertTrue((long) big10.requests.size() * ten <= YuvMergePlan.BUDGET_BYTES_HIGH);
        // P694: 1 GB above 8 GB, 0.5 GB at 8 GB and below - the low class takes the whole series of 4096 x 3072 at 8 bits too
        assertEquals(1024L << 20, YuvMergePlan.BUDGET_BYTES_HIGH);
        assertEquals(512L << 20, YuvMergePlan.BUDGET_BYTES);
        assertEquals(23, YuvMergePlan.build(8_333_326L, 78, TIMES, ISOS, 0.1681f, 0.162f, twenty, eight, true).requests.size());
        // and at 10 bits 14 frames: 11 N + 2 ES + 1 L
        assertEquals(14, YuvMergePlan.build(8_333_326L, 78, TIMES, ISOS, 0.1681f, 0.162f, twenty, ten, true).requests.size());
        // the ring of «Из буфера ZSL» follows the budget
        assertEquals(2 * YuvMergePlan.BUDGET_BYTES_HIGH / eight + 2, YuvMergePlan.ringFrames(200, 198, eight, 4, YuvMergePlan.BUDGET_BYTES_HIGH));
    }
}
