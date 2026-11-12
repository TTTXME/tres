package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.yuv.YuvFrames;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

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
}
