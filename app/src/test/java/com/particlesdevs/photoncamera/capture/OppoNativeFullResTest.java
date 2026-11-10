package com.particlesdevs.photoncamera.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import android.content.SharedPreferences;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.LensShadingMap;
import android.util.Rational;

import com.particlesdevs.photoncamera.api.CameraReflectionApi;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBuild;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * P192: the OPPO Find X7 Ultra's native FULLRES modes, measured on PHY110 (2026-10-17) and nowhere else. P214j: the route on the
 * family (OPPO, OnePlus, realme) gated by what a camera lists, with the OnePlus 15's recorded tables.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class OppoNativeFullResTest {
    @Test public void anyOppoAndThePhy110() {
        assertTrue(OppoNativeFullRes.model("OPPO", "PHY110"));
        assertTrue(OppoNativeFullRes.model("oppo", "PHY110"));
        assertFalse("Find X8 Ultra is not the measured model", OppoNativeFullRes.model("OPPO", "PKJ110"));
        assertTrue("but an OPPO phone", OppoNativeFullRes.brand("OPPO"));
        assertFalse(OppoNativeFullRes.brand("vivo"));
        assertFalse(OppoNativeFullRes.brand("OnePlus"));
        assertFalse(OppoNativeFullRes.brand(null));
    }

    @Test public void theMeasuredModesOfThePhy110() {
        assertEquals(35, OppoNativeFullRes.mode("2"));
        assertEquals(16, OppoNativeFullRes.mode("3"));
        assertEquals(19, OppoNativeFullRes.mode("4"));
        assertEquals(17, OppoNativeFullRes.mode("5"));
        assertEquals(-1, OppoNativeFullRes.mode("0"));
        assertEquals(-1, OppoNativeFullRes.mode("1"));
    }

    /** A table of {count, 3, then w, h, fps of each row} with the given full-size rows (8192x6144) among 4096x3072 ones. */
    private static int[] table(int count, int fullFps, int... fullRows) {
        int[] t = new int[2 + count * 3];
        t[0] = count;
        t[1] = 3;
        for (int i = 0; i < count; i++) { t[2 + i * 3] = 4096; t[3 + i * 3] = 3072; t[4 + i * 3] = 30; }
        for (int r : fullRows) { t[2 + r * 3] = 8192; t[3 + r * 3] = 6144; t[4 + r * 3] = fullFps; }
        return t;
    }

    /** The PHY110's tables (SCAM TEST 2026-10-11): the measured modes are the full-size rows after row 0, first in order. */
    @Test public void theCandidatesAreTheFullSizeRowsAfterRowZero() {
        assertArrayEquals("main", new int[]{8192, 6144, 24, 35, 37}, OppoNativeFullRes.fullModes(table(38, 24, 0, 35, 37)));
        assertArrayEquals("ultra-wide", new int[]{8192, 6144, 15, 16}, OppoNativeFullRes.fullModes(table(18, 15, 0, 16)));
        assertArrayEquals("3x", new int[]{8192, 6144, 24, 19}, OppoNativeFullRes.fullModes(table(21, 24, 0, 19)));
        assertArrayEquals("6x", new int[]{8192, 6144, 15, 17, 20}, OppoNativeFullRes.fullModes(table(21, 15, 0, 17, 20)));
        assertNull("only the hardware-remosaic row (the front camera)", OppoNativeFullRes.fullModes(table(9, 24, 0)));
        assertNull(OppoNativeFullRes.fullModes(null));
        assertNull("short", OppoNativeFullRes.fullModes(new int[]{38, 3, 8192}));
    }

    @Test public void anIdentityTransformIsNotTheIspColour() {
        assertTrue(OppoNativeFullRes.identity(new ColorSpaceTransform(new int[]{1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1})));
        assertFalse(OppoNativeFullRes.identity(new ColorSpaceTransform(new int[]{18, 10, -5, 10, -3, 10, -2, 10, 15, 10, -3, 10, 0, 1, -6, 10, 16, 10})));
    }

    @Test public void theOwnNeutralIsSmoothedInTheLogDomain() {
        OppoNativeFullRes.forgetNeutral("t");
        OppoNativeFullRes.measuredNeutral("t", 0.5f, 0.6f);
        assertEquals(0.5f, OppoNativeFullRes.neutral("t")[0], 1e-6f);
        OppoNativeFullRes.measuredNeutral("t", 1.0f, 0.6f);
        assertEquals((float) Math.exp(0.7 * Math.log(0.5) + 0.3 * Math.log(1.0)), OppoNativeFullRes.neutral("t")[0], 1e-5f);
        OppoNativeFullRes.measuredNeutral("t", Float.NaN, 0.6f); // ignored
        assertTrue(OppoNativeFullRes.neutral("t")[0] < 1f);
        OppoNativeFullRes.forgetNeutral("t");
    }

    /** The framework's map (its constructor is hidden in the SDK stubs: rows, then columns). */
    private static LensShadingMap lsm(float[] gains, int cols, int rows) throws Exception {
        final java.lang.reflect.Constructor<LensShadingMap> c = LensShadingMap.class.getDeclaredConstructor(float[].class, int.class, int.class);
        c.setAccessible(true);
        return c.newInstance(gains, rows, cols);
    }

    private static SharedPreferences store(String name, long wallMs) {
        final SharedPreferences prefs = RuntimeEnvironment.getApplication().getSharedPreferences(name, 0);
        OppoPlainState.useStoreForTest(prefs, "fw", () -> wallMs);
        return prefs;
    }

    /**
     * P212e (W1, M2 VERIFY): a plain session of an earlier process leaves the camera's neutral and shading map in the store, so a
     * process opened straight into FULLRES has a neutral (neutral() non-null) and the plain map (17 x 13, copyGainFactors order).
     */
    @Test public void aPlainSessionOfAnEarlierProcessReachesAColdStart() throws Exception {
        final SharedPreferences prefs = store("oppo_plain_state_fr_test", 1_800_000_000_000L);
        prefs.edit().clear().commit();
        OppoNativeFullRes.resetForTest();
        OppoNativeWb.resetForTest();
        try {
            OppoNativeFullRes.offerForTest("2", new int[]{8192, 6144, 24, 35});
            final CaptureResult plain = mock(CaptureResult.class);
            when(plain.get(CaptureResult.CONTROL_AWB_STATE)).thenReturn(CaptureResult.CONTROL_AWB_STATE_CONVERGED);
            when(plain.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)).thenReturn(new Rational[]{new Rational(408, 1000), new Rational(1, 1),
                    new Rational(614, 1000)});
            final float[] g = OppoPlainStateTest.map(17, 13, 4.7f, 0.03f);
            when(plain.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)).thenReturn(lsm(g, 17, 13));
            OppoNativeFullRes.rememberPlain("2", plain);
            // a new process, straight into FULLRES
            OppoNativeFullRes.resetForTest();
            store("oppo_plain_state_fr_test", 1_800_000_060_000L);
            assertNull("the camera's native size unknown yet", OppoNativeFullRes.neutral("2"));
            OppoNativeFullRes.offerForTest("2", new int[]{8192, 6144, 24, 35});
            assertArrayEquals(new float[]{0.408f, 1f, 0.614f}, OppoNativeFullRes.neutral("2"), 1e-4f);
            final OppoPlainState.Shading s = OppoNativeFullRes.storedShading("2", null);
            assertEquals(17, s.cols);
            assertEquals(13, s.rows);
            assertArrayEquals(g, s.gains, 0f);
            // the session's white balance starts from it
            OppoNativeFullRes.begin("2", null);
            assertArrayEquals(new float[]{0.408f, 1f, 0.614f}, OppoNativeWb.neutral("2"), 1e-4f);
            OppoNativeFullRes.end();
        } finally {
            OppoNativeFullRes.resetForTest();
            OppoNativeWb.resetForTest();
            OppoPlainState.resetForTest();
        }
    }

    /**
     * P212e (W1, M2 step 5): complete() writes a neutral only when it is a finite white point - nothing for a session with no
     * neutral at all and the 3745 K init AWB signature (the HAL's NaN stays, the photo keeps its own); the held gains of a plain
     * session just before are taken.
     */
    @Test public void completeNeverWritesNan() {
        store("oppo_plain_state_nan_test", 1_800_000_000_000L).edit().clear().commit();
        OppoNativeFullRes.resetForTest();
        OppoNativeWb.resetForTest();
        try (MockedStatic<CameraReflectionApi> api = mockStatic(CameraReflectionApi.class)) {
            api.when(() -> CameraReflectionApi.setCached(any(), any(), any())).thenReturn(true);
            final CaptureResult r = mock(CaptureResult.class);
            when(r.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)).thenReturn(new Rational[]{Rational.NaN, Rational.NaN, Rational.NaN});
            final CaptureResult.Key<float[]> rk = new CaptureResult.Key<>("org.quic.camera2.statsconfigs.AWBFrameControlRGain", float[].class);
            final CaptureResult.Key<float[]> bk = new CaptureResult.Key<>("org.quic.camera2.statsconfigs.AWBFrameControlBGain", float[].class);
            final CaptureResult.Key<int[]> ck = new CaptureResult.Key<>("org.quic.camera2.statsconfigs.AWBFrameControlCCT", int[].class);
            when(r.get(rk)).thenReturn(new float[]{2.225807f});
            when(r.get(bk)).thenReturn(new float[]{2.369458f});
            when(r.get(ck)).thenReturn(new int[]{3745});
            OppoNativeWb.begin("2", 0L, null, -1, null, null);
            assertEquals("", OppoNativeFullRes.complete("2", r));
            api.verify(() -> CameraReflectionApi.setCached(eq(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT), any(), any()), never());
            // the gains a plain session left (v10-camera2-engineer35-rawonly-ae: 2.4490006 / 1.6287329, 5546 K)
            when(r.get(rk)).thenReturn(new float[]{2.4490006f});
            when(r.get(bk)).thenReturn(new float[]{1.6287329f});
            when(r.get(ck)).thenReturn(new int[]{5546});
            OppoNativeWb.begin("2", 0L, null, -1, null, null);
            final String filled = OppoNativeFullRes.complete("2", r);
            assertTrue(filled, filled.contains("neutral 0.408 / 0.614"));
            final ArgumentCaptor<Rational[]> written = ArgumentCaptor.forClass(Rational[].class);
            api.verify(() -> CameraReflectionApi.setCached(eq(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT), written.capture(), eq(r)));
            for (Rational q : written.getValue()) assertTrue(q + " finite", q.floatValue() > 0f && !Float.isInfinite(q.floatValue()));
        } finally {
            OppoNativeFullRes.resetForTest();
            OppoNativeWb.resetForTest();
            OppoPlainState.resetForTest();
        }
    }

    /**
     * P212f (W2 = M3 VERIFY): a hand-set white balance is the own neutral as it is (setNeutral, not the k 0.3 average of
     * measuredNeutral), and complete() writes exactly its inverse gains into the next result - what the photo's AWB-off branch reads.
     */
    @Test public void aHandSetNeutralIsFilledExactly() {
        OppoNativeFullRes.resetForTest();
        OppoNativeWb.resetForTest();
        try (MockedStatic<CameraReflectionApi> api = mockStatic(CameraReflectionApi.class)) {
            api.when(() -> CameraReflectionApi.setCached(any(), any(), any())).thenReturn(true);
            OppoNativeFullRes.measuredNeutral("2", 0.33f, 0.70f); // the session's own estimate so far
            OppoNativeFullRes.setNeutral("2", 0.50f, 0.6667f);    // 3200 K by hand
            assertArrayEquals(new float[]{0.50f, 1f, 0.6667f}, OppoNativeFullRes.neutral("2"), 0f);
            final CaptureResult r = mock(CaptureResult.class);
            when(r.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)).thenReturn(new Rational[]{Rational.NaN, Rational.NaN, Rational.NaN});
            final String filled = OppoNativeFullRes.complete("2", r);
            assertTrue(filled, filled.contains("neutral 0.500 / 0.667"));
            final ArgumentCaptor<android.hardware.camera2.params.RggbChannelVector> gains =
                    ArgumentCaptor.forClass(android.hardware.camera2.params.RggbChannelVector.class);
            api.verify(() -> CameraReflectionApi.setCached(eq(CaptureResult.COLOR_CORRECTION_GAINS), gains.capture(), eq(r)));
            assertEquals(2.0f, gains.getValue().getRed(), 1e-6f);
            assertEquals(1f / 0.6667f, gains.getValue().getBlue(), 1e-5f);
            // not a white point: ignored
            OppoNativeFullRes.setNeutral("2", Float.NaN, 0.6f);
            assertEquals(0.50f, OppoNativeFullRes.neutral("2")[0], 0f);
        } finally {
            OppoNativeFullRes.resetForTest();
            OppoNativeWb.resetForTest();
        }
    }

    /**
     * P212e: a map goes into a result through the raw keys and must read back in its own orientation (the synthesized key's
     * setter gave 13 x 17 for the 17 x 13 map); the size is tried both ways, and a map that never reads back right is taken out.
     */
    @Test public void aMapReadsBackInItsOwnOrientationOrNotAtAll() throws Exception {
        final float[] g = OppoPlainStateTest.map(17, 13, 4.7f, 0f);
        try (MockedStatic<CameraReflectionApi> api = mockStatic(CameraReflectionApi.class)) {
            api.when(() -> CameraReflectionApi.setCached(any(), any(), any())).thenReturn(true);
            final CaptureResult ok = mock(CaptureResult.class);
            when(ok.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)).thenReturn(lsm(g, 13, 17), lsm(g, 17, 13));
            assertTrue(OppoNativeFullRes.putShading(ok, g, 17, 13));
            api.verify(() -> CameraReflectionApi.setCached(any(), eq(new android.util.Size(17, 13)), eq(ok)));
            api.verify(() -> CameraReflectionApi.setCached(any(), eq(new android.util.Size(13, 17)), eq(ok)));
            api.verify(() -> CameraReflectionApi.setCached(any(), isNull(), eq(ok)), never());
            final CaptureResult transposed = mock(CaptureResult.class);
            when(transposed.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)).thenReturn(lsm(g, 13, 17));
            assertFalse(OppoNativeFullRes.putShading(transposed, g, 17, 13));
            api.verify(() -> CameraReflectionApi.setCached(any(), isNull(), eq(transposed)));
            assertFalse("a short map", OppoNativeFullRes.putShading(ok, new float[8], 17, 13));
        }
    }

    // P214j: recorded sensor-mode tables (org.quic.camera2.sensormode.info.SensorModeTable, dumpsys static information): SCAM TEST
    // OnePlus CPH2747 2026-10-11 (HAL devices 2 main, 3 ultra-wide, 4 tele, 1 front) and OPPO PHY110 2026-10-11 (device 2, main)
    private static final int[] ONEPLUS15_MAIN = {41, 3, 8192, 6144, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072,
            30, 4096, 3072, 30, 8192, 6144, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 1728, 1296, 30, 4096, 3072, 30, 4096,
            3072, 30, 4096, 3072, 30, 4096, 2304, 30, 4096, 2304, 60, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 2304, 30,
            4096, 2304, 60, 4096, 3072, 60, 2048, 1152, 240, 2048, 1152, 480, 4096, 2304, 30, 4096, 3072, 30, 4096, 3072, 30, 4096,
            3072, 30, 4096, 3072, 24, 4096, 3072, 24, 4096, 2304, 30, 4096, 2304, 120, 8192, 4608, 30, 4096, 2304, 60, 4096, 2304, 30,
            4096, 2304, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 2304, 60, 4096, 3072, 30, 3840, 2160, 120};
    private static final int[] ONEPLUS15_WIDE = {12, 3, 4096, 3072, 30, 4096, 2304, 30, 4096, 2304, 30, 4096, 2304, 60, 2048, 1152,
            120, 1920, 1080, 240, 1792, 1344, 30, 3200, 2400, 30, 4096, 2304, 30, 4096, 2304, 30, 4096, 2304, 60, 4096, 3072, 30};
    private static final int[] ONEPLUS15_TELE = {29, 3, 8192, 6144, 15, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072,
            30, 4096, 3072, 30, 4096, 2304, 30, 4096, 3072, 30, 4096, 2304, 30, 4096, 2304, 30, 4096, 2304, 30, 4096, 2304, 60, 4096,
            2304, 60, 4096, 2304, 30, 2048, 1152, 120, 2048, 1152, 240, 4096, 3072, 30, 4096, 2304, 60, 4096, 3072, 30, 8192, 6144,
            15, 4096, 3072, 30, 4096, 2304, 30, 4096, 2304, 30, 4096, 2304, 60, 4096, 3072, 30, 4096, 2304, 30, 4096, 3072, 30, 4096,
            3072, 30, 4096, 2304, 30};
    private static final int[] ONEPLUS15_FRONT = {10, 3, 6560, 4928, 24, 3280, 2464, 30, 3280, 2464, 30, 3280, 1856, 30, 3280, 1856,
            60, 3280, 1856, 30, 3280, 1856, 30, 3280, 1856, 30, 3136, 1760, 30, 1632, 1224, 30};
    private static final int[] PHY110_MAIN = {38, 3, 8192, 6144, 24, 4096, 3072, 30, 4096, 3072, 30, 4096, 2304, 60, 4096, 2304, 30,
            4096, 2304, 30, 4096, 2304, 60, 2016, 1512, 24, 2048, 1152, 240, 2048, 1152, 480, 4096, 3072, 30, 4096, 3072, 30, 4096,
            3072, 30, 1920, 1440, 24, 4096, 3072, 24, 4096, 3072, 30, 4096, 3072, 24, 4096, 3072, 24, 4096, 3072, 30, 4096, 3072, 30,
            4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 60, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 24, 4096, 3072,
            24, 4096, 2304, 60, 4096, 3072, 24, 2048, 1536, 30, 4096, 3072, 24, 4096, 2304, 60, 4096, 2304, 60, 4096, 2304, 60, 8192,
            6144, 24, 4096, 3072, 30, 8192, 6144, 30};

    /** P214j: OPPO and its ColorOS siblings take the route; the telephotos' package of the PHY110 probe stays OPPO's. */
    @Test public void theFamilyIsOppoOnePlusAndRealme() {
        assertTrue(OppoNativeFullRes.family("OPPO"));
        assertTrue(OppoNativeFullRes.family("oppo"));
        assertTrue("OnePlus 15", OppoNativeFullRes.family("OnePlus"));
        assertTrue(OppoNativeFullRes.family("ONEPLUS"));
        assertTrue("realme GT8 Pro", OppoNativeFullRes.family("realme"));
        assertTrue(OppoNativeFullRes.family("Realme"));
        assertFalse(OppoNativeFullRes.family("vivo"));
        assertFalse(OppoNativeFullRes.family("Xiaomi"));
        assertFalse(OppoNativeFullRes.family("Google"));
        assertFalse(OppoNativeFullRes.family(""));
        assertFalse(OppoNativeFullRes.family(null));
        assertFalse("the Meitu package is the OPPO's alone", OppoNativeFullRes.brand("realme"));
    }

    /** P214j: the phone decides the route, the PHY110 alone its measured modes, OPPO alone the automatic telephoto package. */
    @Test public void onePlusAndRealmeRunTheRouteWithoutThePhy110sTuning() {
        ShadowBuild.setManufacturer("OnePlus");
        ShadowBuild.setModel("CPH2747");
        assertTrue(OppoNativeFullRes.family());
        assertTrue(OppoNativeFullRes.device());
        assertFalse("no automatic Oplus package: the owner's spoof settings only", OppoNativeFullRes.teleAccess());
        assertFalse("not the measured phone", OppoNativeFullRes.model());
        ShadowBuild.setManufacturer("realme");
        ShadowBuild.setModel("RMX5200");
        assertTrue(OppoNativeFullRes.device());
        assertFalse(OppoNativeFullRes.teleAccess());
        assertFalse(OppoNativeFullRes.model());
        ShadowBuild.setManufacturer("OPPO");
        ShadowBuild.setModel("PKJ110");
        assertTrue("another OPPO: as before", OppoNativeFullRes.device());
        assertTrue(OppoNativeFullRes.teleAccess());
        assertFalse(OppoNativeFullRes.model());
        ShadowBuild.setModel("PHY110");
        assertTrue(OppoNativeFullRes.device());
        assertTrue(OppoNativeFullRes.teleAccess());
        assertTrue(OppoNativeFullRes.model());
        ShadowBuild.setManufacturer("vivo");
        ShadowBuild.setModel("V2366GA");
        assertFalse(OppoNativeFullRes.family());
        assertFalse(OppoNativeFullRes.device());
        assertFalse(OppoNativeFullRes.teleAccess());
    }

    /** P214j: the OnePlus 15's recorded tables give its main row 6 and tele row 19; the ultra-wide and the front none. */
    @Test public void theOnePlus15CandidatesAreItsFullSizeRows() {
        assertArrayEquals("main", new int[]{8192, 6144, 30, 6}, OppoNativeFullRes.fullModes(ONEPLUS15_MAIN));
        assertArrayEquals("tele", new int[]{8192, 6144, 15, 19}, OppoNativeFullRes.fullModes(ONEPLUS15_TELE));
        assertNull("ultra-wide: 4096x3072 (rows 0, 11) is its plain size, no binned row of it", OppoNativeFullRes.fullModes(ONEPLUS15_WIDE));
        assertNull("front: only row 0 has the full size", OppoNativeFullRes.fullModes(ONEPLUS15_FRONT));
        assertArrayEquals("the PHY110's own main table: its measured modes, as before", new int[]{8192, 6144, 24, 35, 37},
                OppoNativeFullRes.fullModes(PHY110_MAIN));
    }

    /** P214j: a full size counts only beside a binned row of it (a half or a quarter a side, within 2 %); the block from the size. */
    @Test public void aFullSizeNeedsABinnedRowOfIt() {
        assertArrayEquals("200 MP Tetra and its 4x4-binned row", new int[]{16320, 12240, 7, 2},
                OppoNativeFullRes.fullModes(new int[]{3, 3, 16320, 12240, 7, 4080, 3060, 30, 16320, 12240, 7}));
        assertArrayEquals("200 MP with a 2x2-binned row", new int[]{16320, 12240, 7, 2},
                OppoNativeFullRes.fullModes(new int[]{3, 3, 16320, 12240, 7, 8160, 6120, 15, 16320, 12240, 7}));
        assertArrayEquals("within 2 % a side", new int[]{8192, 6144, 24, 2},
                OppoNativeFullRes.fullModes(new int[]{3, 3, 8192, 6144, 24, 4080, 3060, 30, 8192, 6144, 24}));
        assertNull("no binned row: two plain 12 MP rows",
                OppoNativeFullRes.fullModes(new int[]{3, 3, 4096, 3072, 30, 4096, 2304, 60, 4096, 3072, 30}));
        assertNull("a 16:9 row is not a binned 4:3 one",
                OppoNativeFullRes.fullModes(new int[]{3, 3, 8192, 6144, 24, 4096, 2304, 60, 8192, 6144, 24}));
        assertEquals(4, OppoNativeFullRes.block(16320, 12240));
        assertEquals(2, OppoNativeFullRes.block(8192, 6144));
        assertEquals(2, OppoNativeFullRes.block(8160, 6144));
    }

    /** P214j: the capability gate - the session key and full-size rows; the PHY110's measured modes only on the PHY110. */
    @Test public void aCameraIsGatedByWhatItLists() {
        assertNull("no session key: nothing, whatever the table", OppoNativeFullRes.candidates("2", false, ONEPLUS15_MAIN, false));
        assertNull("not even on the PHY110", OppoNativeFullRes.candidates("2", false, PHY110_MAIN, true));
        assertArrayEquals(new int[]{8192, 6144, 30, 6}, OppoNativeFullRes.candidates("2", true, ONEPLUS15_MAIN, false));
        assertNull("no table on a OnePlus / realme: no PHY110 modes", OppoNativeFullRes.candidates("2", true, null, false));
        assertNull(OppoNativeFullRes.candidates("3", true, ONEPLUS15_WIDE, false));
        assertArrayEquals("the PHY110 without a table: its measured mode", new int[]{8192, 6144, 24, 35},
                OppoNativeFullRes.candidates("2", true, null, true));
        assertNull("the PHY110's front", OppoNativeFullRes.candidates("1", true, null, true));
        assertArrayEquals("its table first", new int[]{8192, 6144, 24, 35, 37}, OppoNativeFullRes.candidates("2", true, PHY110_MAIN, true));
    }

    /** P214j: a learned OnePlus 15 camera tries its row as a Quad at the table's frame rate; one without candidates has no button. */
    @Test public void theOnePlus15MainTriesItsRowAsAQuad() {
        try {
            assertArrayEquals(new int[]{8192, 6144, 30, 6}, OppoNativeFullRes.learn("p214j-main", true, ONEPLUS15_MAIN, false));
            VivoSdkFullRes.Profile p = OppoNativeFullRes.profile("p214j-main");
            assertEquals(8192, p.size.getWidth());
            assertEquals(6144, p.size.getHeight());
            assertEquals(6, p.sensorMode);
            assertEquals(2, p.nativeBlock);
            assertTrue(p.nativeRoute());
            assertFalse(p.nativeBayer());
            assertEquals(1_000_000_000L / 30, OppoNativeFullRes.frameNs("p214j-main"));
            assertFalse("a mode left: not dimmed", OppoNativeFullRes.exhausted("p214j-main"));
            assertArrayEquals(new int[]{8192, 6144, 15, 19}, OppoNativeFullRes.learn("p214j-tele", true, ONEPLUS15_TELE, false));
            assertEquals(19, OppoNativeFullRes.profile("p214j-tele").sensorMode);
            assertEquals(1_000_000_000L / 15, OppoNativeFullRes.frameNs("p214j-tele"));
            assertNull(OppoNativeFullRes.learn("p214j-wide", true, ONEPLUS15_WIDE, false));
            assertNull(OppoNativeFullRes.profile("p214j-wide"));
            assertFalse("nothing offered: no dimmed button either", OppoNativeFullRes.exhausted("p214j-wide"));
            assertNull(OppoNativeFullRes.learn("p214j-nokey", false, ONEPLUS15_MAIN, false));
            assertNull(OppoNativeFullRes.profile("p214j-nokey"));
            ShadowBuild.setManufacturer("OnePlus");
            assertTrue("a plain session keeps its colour for the native mode", OppoNativeFullRes.keepsColour("p214j-main"));
            assertFalse(OppoNativeFullRes.keepsColour("p214j-wide"));
            assertFalse(OppoNativeFullRes.keepsColour(null));
            ShadowBuild.setManufacturer("vivo");
            assertFalse(OppoNativeFullRes.keepsColour("p214j-main"));
        } finally {
            for (String id : Arrays.asList("p214j-main", "p214j-tele", "p214j-wide", "p214j-nokey")) OppoNativeFullRes.forget(id);
        }
    }

    /** P214j: the mode goes as a byte (PHY110, PKJ110, OnePlus 15); a HAL that refuses the byte gets an int32, from then on. */
    @Test public void theModeKeyIsAByteUnlessTheHalRefusesIt() {
        OppoNativeFullRes.resetModeType();
        try {
            final List<String> sent = new ArrayList<>();
            OppoNativeFullRes.setMode(35, (asInt, m) -> sent.add((asInt ? "int " : "byte ") + m));
            assertEquals(Arrays.asList("byte 35"), sent);
            sent.clear();
            final OppoNativeFullRes.ModeSink int32 = (asInt, m) -> {
                if (!asInt) throw new UnsupportedOperationException("Could not find marshaler for Byte and native type 1");
                sent.add("int " + m);
            };
            OppoNativeFullRes.setMode(9, int32);
            assertEquals(Arrays.asList("int 9"), sent);
            sent.clear();
            OppoNativeFullRes.setMode(5, (asInt, m) -> {
                assertTrue("remembered: the byte is not tried again", asInt);
                sent.add("int " + m);
            });
            assertEquals(Arrays.asList("int 5"), sent);
            OppoNativeFullRes.resetModeType();
            boolean thrown = false;
            try {
                OppoNativeFullRes.setMode(6, (asInt, m) -> { throw new IllegalArgumentException("refused as " + (asInt ? "int" : "byte")); });
            } catch (IllegalArgumentException both) {
                thrown = true;
                assertEquals("a key refused in both types fails as before (the session notes the mode)", "refused as int", both.getMessage());
            }
            assertTrue(thrown);
        } finally {
            OppoNativeFullRes.resetModeType();
        }
    }

    /** P227: the mode in effect is read under both CamX names; either one naming another mode is a mismatch, none says nothing. */
    @Test public void theModeReportIsReadUnderBothNames() {
        assertTrue(OppoNativeFullRes.modeMatches(null, null, 35));
        assertTrue(OppoNativeFullRes.modeMatches(new int[]{35}, null, 35));
        assertTrue(OppoNativeFullRes.modeMatches(null, new int[]{35}, 35));
        assertFalse(OppoNativeFullRes.modeMatches(new int[]{1}, null, 35));
        assertFalse(OppoNativeFullRes.modeMatches(null, new int[]{1}, 35));
        assertFalse(OppoNativeFullRes.modeMatches(new int[]{35}, new int[]{1}, 35));
        assertTrue(OppoNativeFullRes.modeMatches(new int[0], new int[0], 35));
    }

    // P586: the OnePlus 16 (PYB110) report's tables (NDK tag 0x80d00000, SCAM TEST 2026-11-09): the main (camera 2, line 7340, and
    // logical 0, line 634) and the tele (camera 4, line 13964) as OppoNativeFullRes records them; the ultra-wide (camera 3, line
    // 10642) and the front (camera 1, line 3997) here
    static final int[] PYB110_WIDE = {14, 3, 4096, 3072, 30, 4096, 3072, 30, 4096, 2560, 30, 4096, 2304, 60, 4096, 2560, 30,
            4096, 2304, 60, 4096, 2304, 30, 4096, 3072, 30, 1920, 1080, 120, 3520, 2640, 30, 1920, 1440, 30, 3408, 2556, 30,
            1824, 1368, 30, 4096, 2304, 60};
    static final int[] PYB110_FRONT = {11, 3, 4096, 3072, 30, 4096, 3072, 30, 4096, 3072, 30, 4096, 2560, 30, 4096, 2304, 60,
            4096, 2560, 30, 4096, 2304, 60, 4096, 2304, 30, 4096, 2560, 30, 4096, 2304, 60, 2048, 1536, 20};

    /** P586: the main's 50 MP-class FULLRES is row 9 (8160x6144 @ 30, Quad), its 200 MP row 24 (16320x12288, 4x4); row 0 never. */
    @Test public void theOnePlus16MainOffersRow9AndRow24() {
        final OppoNativeFullRes.Offer o = OppoNativeFullRes.rows(OppoNativeFullRes.PYB110_MAIN, 4080, 3072, null);
        assertEquals("P593: both 50 MP-class rows", 2, o.small.length);
        assertArrayEquals("row 9 full size QBC (30 fps: the rows at 24 fps or more first)", new int[]{9, 8160, 6144, 30, 2}, o.small[0]);
        assertArrayEquals("P593: row 25 (8160x6144 @ 16, [0x8038][0x8001]) after it", new int[]{25, 8160, 6144, 16, 2}, o.small[1]);
        assertEquals(1, o.large.length);
        assertArrayEquals("row 24, not row 0 (the HAL's highpixel hwremosaic)", new int[]{24, 16320, 12288, 8, 4}, o.large[0]);
        assertArrayEquals("the P214j form: the 50 MP class", new int[]{8160, 6144, 30, 9, 25},
                OppoNativeFullRes.candidates("2", true, OppoNativeFullRes.PYB110_MAIN, false));
        assertNull("no session key: nothing", OppoNativeFullRes.candidates("2", false, OppoNativeFullRes.PYB110_MAIN, false));
        assertArrayEquals("the same table on logical 0", OppoNativeFullRes.PYB110_MAIN, OppoNativeFullRes.PYB110_MAIN.clone());
    }

    @Test public void theOnePlus16TeleOffersRow9AndTheUltraWideAndFrontNothing() {
        final OppoNativeFullRes.Offer tele = OppoNativeFullRes.rows(OppoNativeFullRes.PYB110_TELE, 4096, 3072, null);
        assertEquals(1, tele.small.length);
        assertArrayEquals(new int[]{9, 8192, 6144, 15, 2}, tele.small[0]);
        assertEquals("50 MP: one size", 0, tele.large.length);
        assertTrue("ultra-wide: no binned row of 4096x3072", OppoNativeFullRes.rows(PYB110_WIDE, 4096, 3072, null).empty());
        assertNotNull("the front's 4096x3072 beside 2048x1536 looks like a full size by the table alone",
                OppoNativeFullRes.fullModes(PYB110_FRONT));
        assertTrue("but it is its public RAW size: no FULLRES", OppoNativeFullRes.rows(PYB110_FRONT, 4096, 3072, null).empty());
        assertTrue(OppoNativeFullRes.learn("p586-front", true, PYB110_FRONT, false, 4096, 3072, null, "test").empty());
        assertNull("no profile, no button", OppoNativeFullRes.profile("p586-front"));
        OppoNativeFullRes.forget("p586-front");
    }

    /** P586: the OEM's custom RAW list puts its sizes first among the 50 MP-class rows; P214j's phones keep their order. */
    @Test public void theCustomRawListGoesFirst() {
        final int[][] rows = {{3, 8192, 4608, 30, 2}, {9, 8160, 6144, 30, 2}};
        final java.util.List<int[]> custom = Arrays.asList(new int[]{8160, 6144}, new int[]{8160, 4590});
        assertEquals(9, OppoNativeFullRes.preferCustom(rows, custom)[0][0]);
        assertEquals(3, OppoNativeFullRes.preferCustom(rows, null)[0][0]);
        assertArrayEquals("the PHY110's modes unchanged with any list", new int[]{8192, 6144, 24, 35, 37},
                OppoNativeFullRes.legacy(OppoNativeFullRes.rows(PHY110_MAIN, 4096, 3072, custom).small));
    }

    /** P586: the PYB110 learns its two sizes, 50 MP by default, 200 MP when chosen, with one log line naming every route. */
    @Test public void theOnePlus16MainHasTwoSizes() {
        ShadowBuild.setManufacturer("OnePlus");
        ShadowBuild.setModel("PYB110");
        try {
            assertTrue(OppoNativeFullRes.pyb110());
            assertFalse(OppoNativeFullRes.pyb110("OnePlus", "CPH2747"));
            assertFalse("OPPO-family default: 50 MP until 200 is chosen", PixelPro10.tele200Default());
            final OppoNativeFullRes.Offer o = OppoNativeFullRes.learn("p586-main", true, OppoNativeFullRes.PYB110_MAIN, false, 4080, 3072,
                    Arrays.asList(new int[]{8160, 6144}), "test");
            assertEquals("P604: row 25 at the HAL's 8192x6144, row 25 in opMode 0x8001, row 9 at 8192x6144, then the table's rows", 5, o.small.length);
            assertArrayEquals(new int[]{25, 8192, 6144, 16, 2, 1025}, o.small[0]);
            assertArrayEquals(new int[]{25, 8160, 6144, 16, 2, 2025}, o.small[1]);
            assertArrayEquals(new int[]{9, 8192, 6144, 30, 2, 1009}, o.small[2]);
            assertArrayEquals("P593: the row that died (9) after its 16 fps twin", new int[]{25, 8160, 6144, 16, 2}, o.small[3]);
            assertArrayEquals(new int[]{9, 8160, 6144, 30, 2}, o.small[4]);
            assertEquals("the first row runs the normal session", 0, OppoNativeFullRes.operationMode("p586-main", OppoNativeFullRes.profile("p586-main", false)));
            assertTrue(OppoNativeFullRes.twoSizes("p586-main"));
            VivoSdkFullRes.Profile fifty = OppoNativeFullRes.profile("p586-main", false);
            assertEquals(25, fifty.sensorMode);
            assertEquals("the row's own frame time: 16 fps (the request's frame duration is at least this)", 1_000_000_000L / 16,
                    OppoNativeFullRes.frameNs("p586-main"));
            assertEquals("P604: the HAL's frame size first", 8192, fifty.size.getWidth());
            assertEquals(6144, fifty.size.getHeight());
            assertEquals(2, fifty.nativeBlock);
            assertTrue("the native route: one RAW stream, no ISP stream", fifty.nativeRoute() && !fifty.stillOnly);
            VivoSdkFullRes.Profile large = OppoNativeFullRes.profile("p586-main", true);
            assertEquals(24, large.sensorMode);
            assertEquals(16320, large.size.getWidth());
            assertEquals(12288, large.size.getHeight());
            assertEquals("its own 4x4 mosaic (the Tetra route)", 4, large.nativeBlock);
            assertTrue(large.nativeRoute() && !large.stillOnly);
            assertEquals("the profile of the 50 MP size (P214j's call)", 25, OppoNativeFullRes.profile("p586-main").sensorMode);
            assertTrue(OppoNativeFullRes.anyLive("p586-main"));
            assertTrue("the learn line is kept for the session log", OppoNativeFullRes.learnLine("p586-main").contains("row 9 last"));
            assertFalse(OppoNativeFullRes.sizeExhausted("p586-main", true));
            assertFalse(OppoNativeFullRes.exhausted("p586-main"));
            final OppoNativeFullRes.Offer tele = OppoNativeFullRes.learn("p586-tele", true, OppoNativeFullRes.PYB110_TELE, false, 4096, 3072,
                    null, "test");
            assertFalse("the tele: one size", OppoNativeFullRes.twoSizes("p586-tele"));
            assertEquals(9, OppoNativeFullRes.profile("p586-tele", true).sensorMode);
            assertEquals(1_000_000_000L / 15, OppoNativeFullRes.frameNs("p586-tele"));
            final String line = OppoNativeFullRes.line("2", "OnePlus PYB110", true, "fallback", 4080, 3072, o,
                    Arrays.asList(new int[]{8160, 6144}), OplusIsz.row(true, "2", OppoNativeFullRes.PYB110_MAIN),
                    OplusDcg.rows(true, "2", OppoNativeFullRes.PYB110_MAIN));
            assertTrue(line, line.startsWith("OPPO family camera 2 (OnePlus PYB110): mode key true, table fallback"));
            assertTrue(line, line.contains("FULLRES mode 25 8160x6144@16 block 2, FULLRES mode 9 8160x6144@30 block 2"));
            assertTrue(line, line.contains("200 MP mode 24 16320x12288@8 block 4 (not in OemSupportedCustomRAWFullSizes)"));
            assertTrue(line, line.contains("ISZ mode 11 4080x3072"));
            assertTrue("P593: row 28, then its PDAF-short twin 38: " + line, line.contains("DCG modes 28, 38"));
            assertEquals(9, tele.small[0][0]);
        } finally {
            OppoNativeFullRes.forget("p586-main");
            OppoNativeFullRes.forget("p586-tele");
        }
    }

    /**
     * P593 (owner's log 2026-11-09: the main's row 9 died twice, its trial is stored as «9:died» with the count 1): the trials go
     * on from the rows that failed, whatever the order of the candidates - row 25 next, never the 200 MP row for a 50 MP ask.
     */
    @Test public void aStoredDeathOfRow9MovesOnToRow25NotTo200Mp() {
        final OppoNativeFullRes.Offer table = OppoNativeFullRes.rows(OppoNativeFullRes.PYB110_MAIN, 4080, 3072, null);
        final OppoNativeFullRes.Offer o = OppoNativeFullRes.demoted(table, OppoNativeFullRes.PYB110_MAIN_DEAD);
        assertEquals(25, o.small[0][0]);
        assertEquals(9, o.small[1][0]);
        assertEquals("the 200 MP rows untouched", 24, o.large[0][0]);
        assertSame("nothing to demote: the same offer", table, OppoNativeFullRes.demoted(table, 31));
        final OppoNativeFullRes.Offer tele = OppoNativeFullRes.rows(OppoNativeFullRes.PYB110_TELE, 4096, 3072, null);
        assertSame("one row: as it is", tele, OppoNativeFullRes.demoted(tele, 9));

        final SharedPreferences p = RuntimeEnvironment.getApplication().getSharedPreferences("p593_trials", 0);
        p.edit().clear().commit();
        assertEquals("no trial: the first row", 25, OppoNativeFullRes.liveRow(o.small, 0, OppoNativeFullRes.failedModes(p, "2", "s1"))[0]);
        OppoNativeFullRes.note(p, "2", 1, 9, "camera error 4", "s1"); // as the owner's phone has it
        assertEquals(1, OppoNativeFullRes.failures(p, "2", "s1"));
        final java.util.Set<Integer> failed = OppoNativeFullRes.failedModes(p, "2", "s1");
        assertTrue(failed.contains(9));
        assertEquals("«9:died» moves on to row 25, in either order of the candidates", 25, OppoNativeFullRes.liveRow(o.small, 1, failed)[0]);
        assertEquals(25, OppoNativeFullRes.liveRow(table.small, 1, failed)[0]);
        assertEquals("50 MP asked: row 25", 25, OppoNativeFullRes.rowFor(o, false, true, 1, failed, 0)[0]);
        assertEquals("200 MP asked: row 24", 24, OppoNativeFullRes.rowFor(o, true, true, 1, failed, 0)[0]);
        OppoNativeFullRes.note(p, "2", 2, 25, "no preview frame for 3000 ms", "s1");
        final java.util.Set<Integer> both = OppoNativeFullRes.failedModes(p, "2", "s1");
        assertNull("both 50 MP rows failed: none left", OppoNativeFullRes.liveRow(o.small, 2, both));
        assertNull("a 50 MP ask never becomes 200 MP", OppoNativeFullRes.rowFor(o, false, true, 2, both, 0));
        assertEquals("200 MP stays its own choice", 24, OppoNativeFullRes.rowFor(o, true, true, 2, both, 0)[0]);
        assertEquals("200 MP asked with its row failed: the 50 MP row", 25, OppoNativeFullRes.rowFor(o, true, true, 1, failed, 1)[0]);
        assertNull(OppoNativeFullRes.rowFor(o, true, true, 2, both, 1));
        // a camera whose only FULLRES rows are its 200 MP ones keeps them
        final OppoNativeFullRes.Offer only = new OppoNativeFullRes.Offer(new int[0][], o.large);
        assertEquals(24, OppoNativeFullRes.rowFor(only, false, true, 0, null, 0)[0]);
        // trials of a build before P214h count rows without naming them: the first rows in order
        assertEquals(9, OppoNativeFullRes.liveRow(o.small, 1, java.util.Collections.<Integer>emptySet())[0]);
        assertEquals(25, OppoNativeFullRes.liveRow(o.small, 0, null)[0]);
        assertNull(OppoNativeFullRes.liveRow(o.small, 2, null));
        // the status names the rows as they failed: 9 first, though 25 is offered before it
        assertArrayEquals(new int[]{9, 25}, OppoNativeFullRes.trialOrder(new int[]{25, 9}, java.util.Arrays.asList(9)));
        assertArrayEquals(new int[]{25, 9}, OppoNativeFullRes.trialOrder(new int[]{25, 9}, java.util.Collections.<Integer>emptyList()));
        ShadowBuild.setManufacturer("OnePlus");
        ShadowBuild.setModel("PYB110");
        try {
            OppoNativeFullRes.learn("2", true, OppoNativeFullRes.PYB110_MAIN, false, 4080, 3072, null, "test");
            final String status = OppoNativeFullRes.status(p, "2", "s1");
            assertTrue(status, status.contains("9") && status.contains("25"));
            p.edit().clear().commit();
            OppoNativeFullRes.note(p, "2", 1, 9, "camera error 4", "s1");
            final String next = OppoNativeFullRes.status(p, "2", "s1");
            assertTrue("mode 9 failed, mode 25 next: " + next, next.indexOf("9") >= 0 && next.indexOf("25") > next.indexOf("9"));
        } finally {
            OppoNativeFullRes.forget("2");
        }
    }

    /**
     * P604 (owner's log 2026-11-10: rows 25 and 9 at 8160x6144 both died, «25:died,9:died» stored): the main's 50 MP rows at the
     * HAL's buffer size (8192x6144: [SupportedQCFAImageSizespokemonmain], [UHPRawSize], the stock's 62914560-byte QBC 50M buffer) are
     * trials of their own - the stored deaths do not cover them, they go first.
     */
    @Test public void theHalFrameReadersAreTrialsOfTheirOwn() {
        final OppoNativeFullRes.Offer table = OppoNativeFullRes.demoted(OppoNativeFullRes.rows(OppoNativeFullRes.PYB110_MAIN, 4080, 3072, null),
                OppoNativeFullRes.PYB110_MAIN_DEAD);
        final OppoNativeFullRes.Offer o = OppoNativeFullRes.halFrames(table, 8192, 6144);
        assertEquals(4, o.small.length);
        assertEquals(1025, OppoNativeFullRes.trialId(o.small[0]));
        assertEquals(1009, OppoNativeFullRes.trialId(o.small[1]));
        assertEquals(25, OppoNativeFullRes.trialId(o.small[2]));
        assertSame("the 200 MP rows untouched", table.large, o.large);
        assertEquals("the tele's 8192x6144 row is the HAL's size already: nothing added",
                OppoNativeFullRes.rows(OppoNativeFullRes.PYB110_TELE, 4096, 3072, null).small.length,
                OppoNativeFullRes.halFrames(OppoNativeFullRes.rows(OppoNativeFullRes.PYB110_TELE, 4096, 3072, null), 8192, 6144).small.length);
        assertEquals("a variant is never varied again (only the two table rows get one more)", o.small.length,
                OppoNativeFullRes.halFrames(o, 8192, 6144).small.length - 2);
        final SharedPreferences p = RuntimeEnvironment.getApplication().getSharedPreferences("p604_trials", 0);
        p.edit().clear().commit();
        OppoNativeFullRes.note(p, "2", 1, 25, "camera error 4", "s1");
        OppoNativeFullRes.note(p, "2", 2, 9, "camera error 4", "s1"); // the owner's phone: 25:died,9:died
        final java.util.Set<Integer> failed = OppoNativeFullRes.failedModes(p, "2", "s1");
        assertNull("the table's rows: none left", OppoNativeFullRes.liveRow(table.small, 2, failed));
        assertArrayEquals("the HAL-frame reader of row 25 next", new int[]{25, 8192, 6144, 16, 2, 1025}, OppoNativeFullRes.liveRow(o.small, 2, failed));
        OppoNativeFullRes.note(p, "2", 3, 1025, "camera error 4", "s1");
        assertEquals(1009, OppoNativeFullRes.trialId(OppoNativeFullRes.liveRow(o.small, 3, OppoNativeFullRes.failedModes(p, "2", "s1"))));
        OppoNativeFullRes.note(p, "2", 4, 1009, "no preview frame for 3000 ms", "s1");
        assertNull("all four failed: 50 MP off", OppoNativeFullRes.liveRow(o.small, 4, OppoNativeFullRes.failedModes(p, "2", "s1")));
        assertNull("never 200 MP for a 50 MP ask", OppoNativeFullRes.rowFor(o, false, true, 4, OppoNativeFullRes.failedModes(p, "2", "s1"), 0));
        assertEquals("25", OppoNativeFullRes.rowLabel(25));
        assertTrue(OppoNativeFullRes.rowLabel(1025).startsWith("25 ("));
        final VivoSdkFullRes.Profile hal = new VivoSdkFullRes.Profile(8192, 6144, 25, 2), own = new VivoSdkFullRes.Profile(8160, 6144, 25, 2);
        assertEquals(1025, OppoNativeFullRes.trialId(o.small, hal));
        assertEquals(25, OppoNativeFullRes.trialId(o.small, own));
        final String status = FullResTrials.rows(new int[]{25, 1025}, FullResTrials.reasons("25:died,1025:died"));
        assertTrue(status, status.contains("25, 25 ("));
        // the operation-mode variant: after the first HAL-frame reader, a trial of its own, its own label
        final OppoNativeFullRes.Offer op = OppoNativeFullRes.opModes(o, 1, OppoNativeFullRes.PYB110_OPMODE_ROWS);
        assertEquals(5, op.small.length);
        assertArrayEquals(new int[]{25, 8160, 6144, 16, 2, 2025}, op.small[1]);
        assertEquals(1009, OppoNativeFullRes.trialId(op.small[2]));
        assertSame("no such row: the same offer", o, OppoNativeFullRes.opModes(o, 1, 31));
        assertTrue(OppoNativeFullRes.rowLabel(2025).contains("0x8001"));
        assertArrayEquals("25:died,9:died,1025:died: the 0x8001 trial of row 25 next", new int[]{25, 8160, 6144, 16, 2, 2025},
                OppoNativeFullRes.liveRow(op.small, 3, FullResTrials.reasons("25:died,9:died,1025:died").keySet()));
        assertArrayEquals("its own failure moves on to row 9's HAL frame", new int[]{9, 8192, 6144, 30, 2, 1009},
                OppoNativeFullRes.liveRow(op.small, 4, FullResTrials.reasons("25:died,9:died,1025:died,2025:refused").keySet()));
    }

    /** P586: the recorded tables stand in on the PYB110 only, for its main and tele. */
    @Test public void theRecordedTablesAreThePyb110s() {
        assertArrayEquals(OppoNativeFullRes.PYB110_MAIN, OppoNativeFullRes.recordedTable(true, "2"));
        assertArrayEquals(OppoNativeFullRes.PYB110_TELE, OppoNativeFullRes.recordedTable(true, "4"));
        assertNull(OppoNativeFullRes.recordedTable(true, "3"));
        assertNull(OppoNativeFullRes.recordedTable(true, "1"));
        assertNull("another phone", OppoNativeFullRes.recordedTable(false, "2"));
        assertEquals(39, OppoNativeFullRes.PYB110_MAIN[0]);
        assertEquals(2 + 39 * 3, OppoNativeFullRes.PYB110_MAIN.length);
        assertEquals(2 + 19 * 3, OppoNativeFullRes.PYB110_TELE.length);
    }

    /** P586: the 200 MP size's trials have keys of their own (the 50 MP ones stay), shown as a line of their own; a Quad is noted. */
    @Test public void the200MpTrialsAreTheirOwn() {
        final SharedPreferences p = RuntimeEnvironment.getApplication().getSharedPreferences("p586_trials", 0);
        p.edit().clear().commit();
        try {
            OppoNativeFullRes.learn("p586-t", true, OppoNativeFullRes.PYB110_MAIN, false, 4080, 3072, null, "test");
            OppoNativeFullRes.noteAt(p, OppoNativeFullRes.base200("p586-t"), 1, 24, "session configuration failed", "s1");
            assertEquals(1, OppoNativeFullRes.countAt(p, OppoNativeFullRes.base200("p586-t"), "s1"));
            assertEquals("the 50 MP size untouched", 0, OppoNativeFullRes.failures(p, "p586-t", "s1"));
            final String status = OppoNativeFullRes.status(p, "p586-t", "s1");
            assertTrue(status, status.contains("200"));
            assertFalse(OppoNativeFullRes.quadNoted(p, "p586-t", 24, "s1"));
            OppoNativeFullRes.noteQuad(p, "p586-t", 24, "s1");
            assertTrue(OppoNativeFullRes.quadNoted(p, "p586-t", 24, "s1"));
            assertFalse("another firmware", OppoNativeFullRes.quadNoted(p, "p586-t", 24, "s2"));
            assertTrue("«Попробовать снова» clears both sizes", OppoNativeFullRes.clearFailures(p, "p586-t") > 0);
            assertEquals(0, OppoNativeFullRes.countAt(p, OppoNativeFullRes.base200("p586-t"), "s1"));
        } finally {
            OppoNativeFullRes.forget("p586-t");
        }
    }
}
