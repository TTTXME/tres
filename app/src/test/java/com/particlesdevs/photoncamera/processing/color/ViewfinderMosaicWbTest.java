package com.particlesdevs.photoncamera.processing.color;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.RggbChannelVector;
import android.util.Rational;

import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * P370 (B6): the X200 Ultra's ISZ viewfinder (a Quad stream under the vivo SDK lease, the ISP running no 3A: the identity transform,
 * gains frozen at R 2.434 / B 1.569) shows the photo's own neutral - the teacher of the same sensor blended with the locus, measured
 * on the frame (MosaicNeutral.measure2) - not the frozen gains. Results whose gains move (a running ISP) or a plain Bayer stream keep
 * the HAL's gains; the neutral shown moves in ln space past a 0.01 ln dead band.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ViewfinderMosaicWbTest {
    private MockedStatic<PreferenceKeys> keys;
    private static final ColorSpaceTransform IDENTITY = new ColorSpaceTransform(new Rational[]{
            new Rational(1, 1), new Rational(0, 1), new Rational(0, 1), new Rational(0, 1), new Rational(1, 1), new Rational(0, 1),
            new Rational(0, 1), new Rational(0, 1), new Rational(1, 1)});
    private static final int W = 512, H = 384;
    private static final float[] BLACK = {64, 64, 64, 64};

    @Before public void setUp() {
        MosaicNeutral.resetForTest();
        MosaicTeacher.resetForTest();
        keys = mockStatic(PreferenceKeys.class, invocation -> invocation.callRealMethod());
    }

    @After public void tearDown() {
        keys.close();
        MosaicNeutral.resetForTest();
        MosaicTeacher.resetForTest();
    }

    private static CaptureResult result(long timestamp, float red, float blue) {
        final CaptureResult r = mock(CaptureResult.class);
        when(r.get(CaptureResult.SENSOR_TIMESTAMP)).thenReturn(timestamp);
        when(r.get(CaptureResult.CONTROL_AWB_MODE)).thenReturn(CaptureResult.CONTROL_AWB_MODE_AUTO);
        when(r.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)).thenReturn(IDENTITY);
        when(r.get(CaptureResult.COLOR_CORRECTION_GAINS)).thenReturn(new RggbChannelVector(red, 1f, 1f, blue));
        return r;
    }

    /** A Quad (block 2) BGGR frame of a grey scene seen with camera neutral r / b (green 1), lit 20 - 70 % of white, noisy. */
    private static ByteBuffer quadFrame(float r, float b) {
        final ByteBuffer buf = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.LITTLE_ENDIAN);
        final Random rnd = new Random(7);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                final int qx = (x >> 1) & 1, qy = (y >> 1) & 1; // the 2x2 colour blocks of BGGR
                final float level = 0.2f + 0.5f * ((x / 64 + y / 64) % 4) / 3f;
                final float gain = qx == 0 && qy == 0 ? b : qx == 1 && qy == 1 ? r : 1f;
                final float v = 64 + (1023 - 64) * level * gain + (float) rnd.nextGaussian() * 2f;
                buf.putShort((y * W + x) * 2, (short) Math.round(Math.max(0, Math.min(1023, v))));
            }
        return buf;
    }

    private static MosaicNeutral.Prior prior() {
        return new MosaicNeutral.Prior(Math.log(0.996), Math.log(0.249), Math.log(0.390), Math.log(0.671), false);
    }

    /** Camera 3 (the same sensor) taught at the scene: statistics 0.36 / 0.68 -> ISP 0.384 / 0.647. */
    private static void teachCamera3(MosaicNeutral.Stat at) {
        for (int i = 0; i < 24; i++) MosaicTeacher.observe("3", at.r, at.b, 0.384f, 0.647f);
    }

    private static ViewfinderMosaicWb.Measure measure(ByteBuffer frame) {
        return new ViewfinderMosaicWb.Measure() {
            @Override public MosaicNeutral.Stat stat() {
                return MosaicNeutral.measure2(frame, W, H, W * 2, 3, BLACK, 1023f, 2, null);
            }
            @Override public MosaicNeutral.Result own() {
                return MosaicNeutral.estimate("4", frame, W, H, W * 2, 3, BLACK, 1023f, 2, prior());
            }
            @Override public float[] hal() {
                return new float[]{0.410f, 1f, 0.638f};
            }
        };
    }

    private static double off(float[] a, float[] b) {
        return Math.hypot(Math.log(a[0] / b[0]), Math.log(a[2] / b[2]));
    }

    /**
     * P370 (review): the viewfinder keeps a sample per result, not the results: the idle test on the samples is ispIdle on the
     * results (frozen, moving gains, a duplicate timestamp, AWB off, another transform, a null result).
     */
    @Test public void theSamplesTellWhatTheResultsTell() {
        final java.util.List<java.util.List<CaptureResult>> cases = new java.util.ArrayList<>();
        final java.util.List<CaptureResult> frozen = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) frozen.add(result(1_000_000_000L + 50_000_000L * i, 2.434f, 1.569f));
        cases.add(frozen);
        final java.util.List<CaptureResult> moving = new java.util.ArrayList<>(frozen);
        moving.add(result(1_500_000_000L, 2.1f, 1.7f));
        cases.add(moving);
        final java.util.List<CaptureResult> dup = new java.util.ArrayList<>(frozen);
        final CaptureResult awbOff = result(1_000_000_000L, 2.434f, 1.569f); // a duplicate timestamp: skipped before its AWB
        when(awbOff.get(CaptureResult.CONTROL_AWB_MODE)).thenReturn(CaptureResult.CONTROL_AWB_MODE_OFF);
        dup.add(awbOff);
        dup.add(null);
        cases.add(dup);
        final java.util.List<CaptureResult> off = new java.util.ArrayList<>(frozen);
        final CaptureResult manual = result(1_600_000_000L, 2.434f, 1.569f);
        when(manual.get(CaptureResult.CONTROL_AWB_MODE)).thenReturn(CaptureResult.CONTROL_AWB_MODE_OFF);
        off.add(manual);
        cases.add(off);
        final java.util.List<CaptureResult> other = new java.util.ArrayList<>(frozen);
        final CaptureResult matrix = result(1_700_000_000L, 2.434f, 1.569f);
        when(matrix.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)).thenReturn(new ColorSpaceTransform(new int[]{
                200, 128, -62, 128, -10, 128, -17, 128, 172, 128, -27, 128, 8, 128, -70, 128, 191, 128}));
        other.add(matrix);
        cases.add(other);
        for (java.util.List<CaptureResult> c : cases) {
            final java.util.List<MosaicNeutralResolver.IdleSample> samples = new java.util.ArrayList<>();
            for (CaptureResult r : c) samples.add(r == null ? null : MosaicNeutralResolver.IdleSample.of(r));
            assertEquals(MosaicNeutralResolver.ispIdle(c), MosaicNeutralResolver.ispIdleOf(samples));
        }
        assertNotNull(MosaicNeutralResolver.ispIdle(frozen));
        assertNotNull("the duplicate timestamp is skipped", MosaicNeutralResolver.ispIdle(dup));
        assertNull(MosaicNeutralResolver.ispIdle(moving));
        assertNull(MosaicNeutralResolver.ispIdle(off));
        assertNull(MosaicNeutralResolver.ispIdle(other));
    }

    @Test public void anIdleIspShowsThePhotosNeutral() {
        final ByteBuffer frame = quadFrame(0.36f, 0.68f);
        final MosaicNeutral.Stat stat = measure(frame).stat();
        assertNotNull(stat);
        teachCamera3(stat);
        final List<String> same = Collections.singletonList("3");
        // the photo: ScamProcessor.applyMosaicNeutral's idle branch on the same statistic
        final MosaicTeacher.Use use = MosaicTeacher.use("4", stat.r, stat.b, same);
        assertTrue(String.valueOf(use), use != null && use.usable());
        final float[] photo = MosaicNeutral.taught("4", stat, prior(), use).neutral;
        final ViewfinderMosaicWb vf = new ViewfinderMosaicWb();
        float[] shown = null;
        for (int i = 0; i < 40; i++)
            shown = vf.frame("4", 1, result(1_000_000_000L + 33_000_000L * i, 2.434f, 1.569f), true, measure(frame), prior(), same, i * 33L);
        assertNotNull(shown);
        assertTrue("viewfinder " + shown[0] + " / " + shown[2] + " against the photo's " + photo[0] + " / " + photo[2], off(shown, photo) < 0.02);
        // the gains the viewfinder takes
        final float[] g = ViewfinderMosaicWb.gains(shown);
        assertEquals(1f / shown[0], g[0], 1e-6f);
        assertEquals(1f, g[1], 0f);
        // the frozen HAL neutral (1 / gains) is far from it: the viewfinder showed another colour before
        assertTrue(off(shown, new float[]{1f / 2.434f, 1f, 1f / 1.569f}) > 0.05);
    }

    /** P597: a Measure whose copy (detached) counts how often it is taken and measures the same frame. */
    private static ViewfinderMosaicWb.Measure detachable(ByteBuffer frame, int[] copies) {
        final ViewfinderMosaicWb.Measure same = measure(frame);
        return new ViewfinderMosaicWb.Measure() {
            @Override public MosaicNeutral.Stat stat() { return same.stat(); }
            @Override public MosaicNeutral.Result own() { return same.own(); }
            @Override public float[] hal() { return same.hal(); }
            @Override public ViewfinderMosaicWb.Measure detached() {
                copies[0]++;
                return measure(frame);
            }
        };
    }

    /**
     * P597 (owner, 2026-11-10: «лаги при включенной тетре остались»): the measurement leaves the camera thread once a neutral is
     * shown - the same neutrals as measured on the caller (run at once they are the same frame by frame), the first one still
     * measured at once, one copy at a time (frames due meanwhile keep the shown neutral), a reset meanwhile drops the result.
     */
    @Test public void theMeasurementRunsOnItsOwnThreadWithTheSameNeutrals() {
        final ByteBuffer frame = quadFrame(0.36f, 0.68f), other = quadFrame(0.30f, 0.75f);
        teachCamera3(measure(frame).stat());
        final List<String> same = Collections.singletonList("3");
        final java.util.ArrayList<Runnable> queued = new java.util.ArrayList<>();
        try {
            // run at once: frame by frame the neutrals of the caller's measurement
            ViewfinderMosaicWb.workerHook = Runnable::run;
            final ViewfinderMosaicWb sync = new ViewfinderMosaicWb(), async = new ViewfinderMosaicWb();
            final int[] copies = {0};
            for (int i = 0; i < 60; i++) {
                final ByteBuffer f = i < 30 ? frame : other;
                final CaptureResult r = result(1_000_000_000L + 33_000_000L * i, 2.434f, 1.569f);
                final float[] a = sync.frame("4", 1, r, true, measure(f), prior(), same, i * 33L);
                final float[] b = async.frame("4", 1, r, true, detachable(f, copies), prior(), same, i * 33L);
                assertTrue("frame " + i, a == null ? b == null : b != null && java.util.Arrays.equals(a, b));
            }
            assertTrue("the measurements after the first one took copies: " + copies[0], copies[0] >= 3);
            // queued: the first neutral is measured at once, a later one on the worker, one at a time
            ViewfinderMosaicWb.workerHook = queued::add;
            final ViewfinderMosaicWb vf = new ViewfinderMosaicWb();
            copies[0] = 0;
            float[] first = null;
            int i = 0;
            for (; i < 40 && first == null; i++)
                first = vf.frame("4", 1, result(1_000_000_000L + 33_000_000L * i, 2.434f, 1.569f), true, detachable(other, copies), prior(), same, i * 33L);
            assertNotNull("the first neutral at once", first);
            assertEquals("no copy for it", 0, copies[0]);
            assertTrue(queued.isEmpty());
            float[] shown = first;
            for (int n = 0; n < 40; n++, i++)
                shown = vf.frame("4", 1, result(1_000_000_000L + 33_000_000L * i, 2.434f, 1.569f), true, detachable(frame, copies), prior(), same, i * 33L);
            assertEquals("one copy while it is measured", 1, copies[0]);
            assertEquals(1, queued.size());
            assertTrue(vf.measuring());
            assertTrue("the shown neutral until the result is in", java.util.Arrays.equals(first, shown));
            queued.remove(0).run();
            org.junit.Assert.assertFalse(vf.measuring());
            shown = vf.frame("4", 1, result(1_000_000_000L + 33_000_000L * i, 2.434f, 1.569f), true, detachable(frame, copies), prior(), same, i * 33L);
            i++;
            assertTrue("the result moved it", off(shown, first) > 0.01);
            // a reset while a measurement runs: its result is not shown
            for (int n = 0; n < 40 && queued.isEmpty(); n++, i++)
                vf.frame("4", 1, result(1_000_000_000L + 33_000_000L * i, 2.434f, 1.569f), true, detachable(other, copies), prior(), same, i * 33L);
            assertEquals(1, queued.size());
            vf.reset();
            queued.remove(0).run();
            org.junit.Assert.assertFalse(vf.measuring());
            assertNull("a plain stream after the reset", vf.frame("4", 1, result(20_000_000_000L, 2.434f, 1.569f), false, measure(frame), prior(), same, 0L));
        } finally {
            ViewfinderMosaicWb.workerHook = null;
        }
    }

    private static long ts(int i) {
        return 1_000_000_000L + 33_000_000L * i;
    }

    /**
     * The video's sequence: a running AWB while the phone moves (its gains drift), then 5 s held still (the converged gains held
     * exactly). Returns the frame (index) at which the viewfinder first showed its own neutral, -1 when it kept the HAL's gains.
     */
    private static int heldStill(ViewfinderMosaicWb vf, ByteBuffer frame, List<String> same) {
        for (int i = 0; i < 30; i++)
            assertNull("moving: the HAL's gains", vf.frame("4", 1, result(ts(i), 2.30f + 0.01f * i, 1.569f), true, measure(frame), prior(), same, i * 33L));
        for (int i = 30; i < 180; i++)
            if (vf.frame("4", 1, result(ts(i), 2.59f, 1.569f), true, measure(frame), prior(), same, i * 33L) != null) return i;
        return -1;
    }

    /**
     * P599 (owner, 2026-11-10: «Баланс белого сначала правильный, но если удержать какое-то время камеру на месте он меняется на
     * неверный»; screen recording 11:59:38, the 4.8x module): a running AWB that converged on a still scene holds its gains as an
     * ISP that runs no 3A does - before, ~2 s after the phone came to rest the idle test passed and the viewfinder's own neutral
     * replaced the HAL's in one step. Gains that moved in the session prove a running AWB: the HAL's white balance stays for the
     * session however long the camera is held, the press carries the proof; a new session or camera starts without it.
     */
    @Test public void aConvergedAwbHeldStillKeepsTheHalsWhiteBalance() {
        final ByteBuffer frame = quadFrame(0.36f, 0.68f);
        teachCamera3(measure(frame).stat());
        final List<String> same = Collections.singletonList("3");
        final ViewfinderMosaicWb vf = new ViewfinderMosaicWb();
        assertEquals("held still for 5 s: the HAL's white balance stays", -1, heldStill(vf, frame, same));
        assertTrue(vf.awbMoves("4", 1));
        org.junit.Assert.assertFalse("another session", vf.awbMoves("4", 2));
        org.junit.Assert.assertFalse("another camera", vf.awbMoves("3", 1));
        // the press: the reference app's rule with the proof (ScamProcessor keeps it over a still burst's frozen gains)
        final MosaicNeutralResolver.Press press = MosaicNeutralResolver.Press.why(new float[]{0.386f, 1f, 0.637f});
        org.junit.Assert.assertFalse(press.awbMoves);
        org.junit.Assert.assertSame(press, press.awbMoves(false));
        final MosaicNeutralResolver.Press moved = press.awbMoves(true);
        assertTrue(moved.awbMoves);
        assertEquals(MosaicNeutralResolver.ROUTE_WHY, moved.route);
        assertArrayEquals(press.viewfinder, moved.viewfinder, 0f);
        assertTrue(moved.toString(), moved.toString().endsWith("0.386 / 0.637 (the HAL's AWB moved in this session)"));
        // a new session of the same camera starts without the proof (a frozen ISP from its start is idle again)
        float[] shown = null;
        for (int i = 0; i < 60; i++) shown = vf.frame("4", 2, result(ts(200 + i), 2.434f, 1.569f), true, measure(frame), prior(), same, i * 33L);
        assertNotNull("frozen from the start: the ISP runs no 3A", shown);
        org.junit.Assert.assertFalse(vf.awbMoves("4", 2));
    }

    /** P599: scam_dev mosaic_awb_moves 0 - as before: the held camera turns to the own neutral (the owner's video). */
    @Test public void withoutTheProofAHeldCameraTookTheOwnNeutral() {
        keys.when(() -> PreferenceKeys.scamDevSwitch("mosaic_awb_moves", true)).thenReturn(false);
        final ByteBuffer frame = quadFrame(0.36f, 0.68f);
        teachCamera3(measure(frame).stat());
        final int at = heldStill(new ViewfinderMosaicWb(), frame, Collections.singletonList("3"));
        // the gains held for the idle test's second, then the next 12th frame: ~1.3 - 1.7 s after the AWB settled
        assertTrue("switched at frame " + at, at >= 30 + 30 && at <= 30 + 30 + 12);
    }

    /** P599: an ISP that runs no 3A whose first results carry start values is still idle (the base is its first settled result). */
    @Test public void aFrozenIspWithStartValuesIsStillIdle() {
        final ByteBuffer frame = quadFrame(0.36f, 0.68f);
        teachCamera3(measure(frame).stat());
        final List<String> same = Collections.singletonList("3");
        final ViewfinderMosaicWb vf = new ViewfinderMosaicWb();
        float[] shown = null;
        for (int i = 0; i < 80; i++) {
            final float r = i < ViewfinderMosaicWb.SETTLE_RESULTS ? 1f : 2.434f + (i % 3) * 0.002f; // 1/1024 steps of the HAL
            shown = vf.frame("4", 1, result(ts(i), r, i < ViewfinderMosaicWb.SETTLE_RESULTS ? 1f : 1.569f), true, measure(frame), prior(), same, i * 33L);
        }
        assertNotNull(shown);
        org.junit.Assert.assertFalse(vf.awbMoves("4", 1));
    }

    @Test public void aRunningIspOrAPlainStreamKeepsTheHalsGains() {
        // P599: frozen gains after moving ones in the same session are a converged AWB (the HAL's gains stay, by default): the
        // frozen phase runs in session 2 here (an ISP that runs no 3A from its start), and as before in session 1 with
        // mosaic_awb_moves 0 (runningThenFrozenInOneSession below)
        final ByteBuffer frame = quadFrame(0.36f, 0.68f);
        teachCamera3(measure(frame).stat());
        final List<String> same = Collections.singletonList("3");
        final ViewfinderMosaicWb vf = new ViewfinderMosaicWb();
        for (int i = 0; i < 40; i++)
            assertNull("moving gains", vf.frame("4", 1, result(1_000_000_000L + 33_000_000L * i, 2.30f + 0.01f * i, 1.569f), true,
                    measure(frame), prior(), same, i * 33L));
        float[] shown = null;
        for (int i = 0; i < 40; i++)
            shown = vf.frame("4", 2, result(5_000_000_000L + 33_000_000L * i, 2.434f, 1.569f), true, measure(frame), prior(), same, i * 33L);
        assertNotNull(shown);
        assertNull("a plain Bayer stream", vf.frame("4", 2, result(9_000_000_000L, 2.434f, 1.569f), false, measure(frame), prior(), same, 0L));
        // and its state is gone: a mosaic stream starts over (no stale neutral at the first frame)
        assertNull(vf.frame("4", 2, result(9_100_000_000L, 2.434f, 1.569f), true, measure(frame), prior(), same, 0L));
    }

    /** The test above as it was before P599 (one session): mosaic_awb_moves 0 reaches it; by default the HAL's gains stay. */
    @Test public void runningThenFrozenInOneSession() {
        final ByteBuffer frame = quadFrame(0.36f, 0.68f);
        teachCamera3(measure(frame).stat());
        final List<String> same = Collections.singletonList("3");
        for (boolean kept : new boolean[]{true, false}) {
            keys.when(() -> PreferenceKeys.scamDevSwitch("mosaic_awb_moves", true)).thenReturn(kept);
            final ViewfinderMosaicWb vf = new ViewfinderMosaicWb();
            for (int i = 0; i < 40; i++)
                assertNull("moving gains", vf.frame("4", 1, result(1_000_000_000L + 33_000_000L * i, 2.30f + 0.01f * i, 1.569f), true,
                        measure(frame), prior(), same, i * 33L));
            float[] shown = null;
            for (int i = 0; i < 40; i++)
                shown = vf.frame("4", 1, result(5_000_000_000L + 33_000_000L * i, 2.434f, 1.569f), true, measure(frame), prior(), same, i * 33L);
            if (kept) assertNull("P599: a converged AWB keeps the HAL's gains", shown);
            else assertNotNull(shown);
            assertNull("a plain Bayer stream", vf.frame("4", 1, result(9_000_000_000L, 2.434f, 1.569f), false, measure(frame), prior(), same, 0L));
            assertNull(vf.frame("4", 1, result(9_100_000_000L, 2.434f, 1.569f), true, measure(frame), prior(), same, 0L));
        }
    }

    @Test public void theShownNeutralMovesPastADeadBand() {
        final float[] a = {0.40f, 1f, 0.60f};
        assertArrayEquals(a, ViewfinderMosaicWb.smooth(null, a), 1e-6f);
        final float[] near = {(float) (0.40 * Math.exp(0.005)), 1f, 0.60f};
        assertArrayEquals("within 0.01 ln", a, ViewfinderMosaicWb.smooth(a, near), 0f);
        final float[] far = {(float) (0.40 * Math.exp(0.1)), 1f, 0.60f};
        final float[] moved = ViewfinderMosaicWb.smooth(a, far);
        assertEquals(0.40 * Math.exp(0.05), moved[0], 1e-5);
        assertEquals(0.60, moved[2], 1e-6);
    }
}
