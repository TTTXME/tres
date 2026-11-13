package com.scamera.app.processing.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.graphics.Point;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.RggbChannelVector;
import android.util.Pair;
import android.util.Rational;

import com.scamera.app.api.ParseExif;
import com.scamera.app.capture.CaptureController;
import com.scamera.app.processing.PedestalMemory;
import com.scamera.app.processing.parameters.ExposureIndex;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.util.Allocator;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.IdentityHashMap;

/**
 * P440: ScamProcessor's first metadata pass (the last burst callback) is now {@link Parameters#fillLevelsOnly} instead of the
 * whole {@link Parameters#fillFromFrame} (and the normal frames' exposure time and the EXIF sync after it). Every field
 * of the Parameters and of the EXIF data after the reference frame's pass and its black floor is the same either way - also when
 * the last callback's levels, ISO, exposure, noise, neutral, white balance mode, ISP transform and hot pixels differ from the
 * reference frame's, for a DCG frame with the module's overrides, a binned shot, a frame whose samples widen the levels, a Quad
 * frame of a stream that came without its black pedestal (PedestalMemory's state too). The levels pass is what keeps it so:
 * without it the darkest frame's floor comes out differently.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, shadows = ParametersShotCameraTest.NoNativeAllocator.class,
        instrumentedPackages = "com.scamera.app.util")
public class FillFrameLevelsTest {
    private static final int W = 1024, H = 768;
    private MockedStatic<com.scamera.app.app.ScameraApp> app;
    private MockedStatic<PreferenceKeys> prefsKeys;
    private CameraCharacteristics previous;
    private static int streams;

    private static ColorSpaceTransform cst(int den, int... v) {
        final Rational[] r = new Rational[9];
        for (int i = 0; i < 9; i++) r[i] = new Rational(v[i], den);
        return new ColorSpaceTransform(r);
    }

    private static final ColorSpaceTransform IDENTITY = cst(1, 1, 0, 0, 0, 1, 0, 0, 0, 1);
    private static final ColorSpaceTransform ISP = cst(128, 167, -37, -2, -19, 155, -7, 2, -59, 184);

    @Before public void setUp() {
        final com.scamera.app.api.Settings settings = new com.scamera.app.api.Settings();
        settings.colourMethod = 1; // the default (default_prefs.xml)
        app = mockStatic(com.scamera.app.app.ScameraApp.class);
        app.when(com.scamera.app.app.ScameraApp::getSettings).thenReturn(settings);
        prefsKeys = mockStatic(PreferenceKeys.class, invocation -> {
            final String name = invocation.getMethod().getName();
            if (name.equals("isRawBlackFromData")) return true;
            if (name.equals("isScamEnabled")) return true;
            if (name.equals("routeInternalValue")) return 1f;
            return invocation.callRealMethod();
        });
        final CameraCharacteristics c = mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)).thenReturn(1023);
        when(c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)).thenReturn(new BlackLevelPattern(new int[]{64, 64, 64, 64}));
        when(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)).thenReturn(21);
        when(c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)).thenReturn((byte) 17);
        when(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)).thenReturn(cst(128, 127, 0, 0, 0, 128, 0, 0, 0, 130));
        when(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)).thenReturn(cst(128, 127, 0, 0, 0, 128, 0, 0, 0, 128));
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)).thenReturn(cst(10000, 6178, -1002, -970, -4729, 14118, 345, -861, 2593, 4441));
        when(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)).thenReturn(cst(10000, 6892, 196, -3223, -7131, 18376, -1520, -884, 2258, 6427));
        when(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)).thenReturn(cst(128, 70, 40, 14, 30, 90, 8, 4, 20, 82));
        when(c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)).thenReturn(cst(128, 72, 38, 14, 31, 89, 8, 5, 22, 80));
        when(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)).thenReturn(new float[]{6.0f});
        previous = CaptureController.mCameraCharacteristics;
        CaptureController.mCameraCharacteristics = c;
    }

    @After public void tearDown() {
        Allocator.binning = false;
        CaptureController.mCameraCharacteristics = previous;
        prefsKeys.close();
        app.close();
    }

    /** A capture result: ISO, exposure, noise profile, dynamic black / white, neutral, AWB mode and gains, ISP transform. */
    @SuppressWarnings("unchecked")
    private static CaptureResult result(int iso, long exposureNs, float dynBlack, int dynWhite, float r, float b, boolean awbOff,
                                        ColorSpaceTransform transform, double slope) {
        final CaptureResult res = mock(CaptureResult.class);
        when(res.get(CaptureResult.SENSOR_SENSITIVITY)).thenReturn(iso);
        when(res.get(CaptureResult.SENSOR_EXPOSURE_TIME)).thenReturn(exposureNs);
        when(res.get(CaptureResult.SENSOR_TIMESTAMP)).thenReturn(1_000_000L * iso);
        final Pair<Double, Double>[] noise = new Pair[4];
        for (int i = 0; i < 4; i++) noise[i] = new Pair<>(slope * (1 + 0.01 * i), slope * slope * 0.1);
        when(res.get(CaptureResult.SENSOR_NOISE_PROFILE)).thenReturn(noise);
        when(res.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)).thenReturn(new float[]{dynBlack, dynBlack, dynBlack, dynBlack});
        when(res.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)).thenReturn(dynWhite);
        when(res.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)).thenReturn(new Rational[]{
                new Rational(Math.round(r * 1024), 1024), new Rational(1, 1), new Rational(Math.round(b * 1024), 1024)});
        when(res.get(CaptureResult.CONTROL_AWB_MODE)).thenReturn(awbOff ? CaptureRequest.CONTROL_AWB_MODE_OFF : CaptureRequest.CONTROL_AWB_MODE_AUTO);
        when(res.get(CaptureResult.COLOR_CORRECTION_GAINS)).thenReturn(new RggbChannelVector(1.1f / r, 1f, 1f, 0.9f / b));
        when(res.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)).thenReturn(transform);
        when(res.get(CaptureResult.LENS_APERTURE)).thenReturn(iso > 1000 ? 1.8f : 2.2f);
        when(res.get(CaptureResult.LENS_FOCAL_LENGTH)).thenReturn(6.0f);
        when(res.get(CaptureResult.STATISTICS_HOT_PIXEL_MAP)).thenReturn(new Point[]{new Point(iso % 97, 3)});
        return res;
    }

    /** The last callback: the ultrashort frame (ISO 150, 2 ms) with its own neutral, noise and the ISP transform. */
    private static CaptureResult last(float dynBlack, int dynWhite) {
        return result(150, 2_000_000L, dynBlack, dynWhite, 0.52f, 0.61f, false, ISP, 2e-5);
    }

    /** The reference (N) frame. */
    private static CaptureResult ref(float dynBlack, int dynWhite) {
        return result(2638, 8_333_333L, dynBlack, dynWhite, 0.47f, 0.60f, false, ISP, 4e-4);
    }

    /** The shot's setup before the metadata passes (fillFromCamera' levels; the per-shot flags set by ScamProcessor). */
    private static Parameters base(boolean dynamicBlack) throws Exception {
        final Parameters p = new Parameters();
        p.rawSize = new Point(W, H);
        p.cfaPattern = 0;
        p.cfaBlack = new float[]{64, 64, 64, 64};
        p.whiteLevel = 1023;
        p.whiteLevelOverride = -1;
        p.blackLevelOverride = -1f;
        p.useDynamicBlackLevel = dynamicBlack;
        p.useDynamicWhiteLevel = true;
        final Field analog = Parameters.class.getDeclaredField("analogIso");
        analog.setAccessible(true);
        analog.setInt(p, 800);
        return p;
    }

    /** One shot's setup and darkest frame (refineBlackFromDarkestFrame: its colour block, the shot's stream key). */
    private static final class Shot {
        boolean dynamicBlack = true, dcg, binning;
        int whiteOverride = -1;
        float blackOverride = -1f;
        int block = 1;
        /** The shot's stream key (MosaicBlockStore.shotKey), null: none. */
        String stream;
        ByteBuffer dark;
        CaptureResult last, ref;
        /** The normal frames' measured exposure (ScamProcessor's loop after the old first pass). */
        long normalExposureNs = 8_333_333L;

        Parameters parameters() throws Exception {
            final Parameters p = base(dynamicBlack);
            p.dcgShot = dcg;
            p.whiteLevelOverride = whiteOverride;
            p.blackLevelOverride = blackOverride;
            return p;
        }
    }

    /** {Parameters, EXIF, the stream key (null: none)} of one flow. */
    private static Object[] run(Shot s, boolean levelsOnly, boolean firstPass) throws Exception {
        Allocator.binning = s.binning;
        try {
            final Parameters p = s.parameters();
            final ParseExif.ExifData exif = new ParseExif.ExifData();
            final String stream = s.stream;
            // the memory lives for the process: each flow starts from none for its stream
            if (stream != null) PedestalMemory.clear(stream, "test");
            final CaptureRequest request = mock(CaptureRequest.class);
            if (firstPass) {
                if (levelsOnly) {
                    // P440
                    p.fillLevelsOnly(s.last);
                } else {
                    // before P440: the whole pass, the normal frames' exposure time, the EXIF sync after the black floor
                    p.fillFromFrame(s.last, request, 400);
                    p.exposureTime = ExposureIndex.time2sec(s.normalExposureNs);
                }
            }
            p.refineBlackLevel(s.dark, W, H, s.block, stream);
            p.rotationDeg = 90;
            if (firstPass && !levelsOnly) ParseExif.syncWithParameters(exif, p);
            // the reference frame's pass (unchanged)
            p.fillFromFrame(s.ref, request, 2638);
            p.refineBlackLevel(s.dark, W, H, s.block, stream);
            ParseExif.syncWithParameters(exif, p);
            return new Object[]{p, exif, stream == null ? null
                    : new Object[]{Float.floatToRawIntBits(PedestalMemory.floor(stream, 1023)), PedestalMemory.source(stream)}};
        } finally {
            Allocator.binning = false;
        }
    }

    /** Before and after P440 give the same Parameters, EXIF and PedestalMemory state; returns the Parameters after. */
    private static Parameters assertSame(String what, Shot s) throws Exception {
        final Object[] before = run(s, false, true), after = run(s, true, true);
        compare(what + ": parameters", before[0], after[0], new IdentityHashMap<>());
        compare(what + ": exif", before[1], after[1], new IdentityHashMap<>());
        if (s.stream != null) {
            final Object[] a = (Object[]) before[2], b = (Object[]) after[2];
            assertEquals(what + ": pedestal floor", a[0], b[0]);
            assertEquals(what + ": pedestal source", a[1], b[1]);
        }
        return (Parameters) after[0];
    }

    /** The darkest frame: floor ~60 DN, {@code low} blocks holding one sampled value {@code lowValue}, {@code hot} sampled hot values. */
    private static ByteBuffer dark(int low, int lowValue, int hot, int hotValue) {
        final ByteBuffer b = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.nativeOrder());
        final ShortBuffer s = b.asShortBuffer();
        final java.util.Random r = new java.util.Random(3);
        for (int i = 0; i < W * H; i++) s.put(i, (short) (60 + r.nextInt(3)));
        // the first sampled site of channel 0 of a block (BlockScan: 32 sites, every 4th read)
        for (int k = 0; k < low; k++) s.put((2 * 32 * 2) * W + (3 + 5 * k) * 32 * 2, (short) lowValue);
        for (int k = 0; k < hot; k++) s.put((5 * 32 * 2) * W + (2 + 3 * k) * 32 * 2, (short) hotValue);
        return b;
    }

    /** A frame that came without its 64 DN pedestal: a dark scene a few DN above 0 (P386's probe). */
    private static ByteBuffer withoutPedestal(int block) {
        return ParametersPedestalLossTest.frame(block, 0, 1.5, 0.3, 11, (x, y) -> {
            final double v = 2 + 30.0 * x / W;
            return new double[]{v, v, v};
        }, 0, 0, 0);
    }

    @Test public void theReferencePassGivesTheSameParametersWithTheLevelsAlone() throws Exception {
        final Shot s = new Shot();
        s.dark = dark(5, 17, 0, 0);
        s.last = last(64, 1023);
        s.ref = ref(64, 1023);
        s.dynamicBlack = false;
        assertSame("same levels", s);
        s.dynamicBlack = true;
        assertSame("same levels, dynamic black", s);
        // the last callback with AWB off (the white point replaced by a new array) and an identity ISP transform
        s.last = result(150, 2_000_000L, 64, 1023, 0.40f, 0.70f, true, IDENTITY, 2e-5);
        assertSame("AWB off last", s);
        // a last callback that is the long frame: another exposure than the normal frames' (the exposure loop of before)
        s.last = result(166, 33_333_333L, 64, 1023, 0.47f, 0.60f, false, ISP, 1e-4);
        s.normalExposureNs = 8_000_000L;
        assertSame("long frame last", s);
    }

    /**
     * The last callback reports another black (72) than fillFromCamera' 64 and the reference frame (64): the darkest frame's
     * floor was measured at the last callback's levels (black / 4 = 18 is the padding limit: the five 17s are padding) and the
     * reference frame's pass reuses it. The levels pass keeps that; a plain removal measures it at 64 (limit 16: the 17s are
     * signal) and the black comes out lower.
     */
    @Test public void aDifferentBlackOfTheLastCallbackStillGivesTheSameFloor() throws Exception {
        final Shot s = new Shot();
        s.dark = dark(5, 17, 0, 0);
        s.last = last(72, 1023);
        s.ref = ref(64, 1023);
        final Parameters a = assertSame("black 72 then 64", s);
        final Parameters c = (Parameters) run(s, true, false)[0];
        assertNotEquals("the levels pass matters here", Arrays.toString(a.cfaBlack), Arrays.toString(c.cfaBlack));
        // the other way round, and another dynamic white as well
        s.last = last(64, 1020);
        s.ref = ref(72, 1023);
        assertSame("black 64 then 72, white 1020 then 1023", s);
    }

    /** A DCG frame keeps its own levels against the module's overrides; a plain frame takes them; a binned shot scales them. */
    @Test public void overridesDcgAndBinningGiveTheSameParameters() throws Exception {
        final Shot s = new Shot();
        s.dark = dark(5, 17, 0, 0);
        s.last = last(72, 1023);
        s.ref = ref(64, 1023);
        s.whiteOverride = 1000;
        s.blackOverride = 66f;
        assertSame("overrides", s);
        s.dcg = true;
        final Parameters dcg = assertSame("overrides on a DCG frame", s);
        assertEquals("not applied on DCG", 1023, dcg.whiteLevel);
        s.dcg = false;
        s.whiteOverride = -1;
        s.blackOverride = -1f;
        s.binning = true;
        final Parameters binned = assertSame("binned", s);
        assertEquals(4 * 1023, binned.whiteLevel);
    }

    /** Samples above the white widen the levels at the first pass (RawLevels.sampledBits); the reference pass widens them again. */
    @Test public void aFrameWhoseSamplesWidenTheLevelsGivesTheSameParameters() throws Exception {
        final Shot s = new Shot();
        s.dark = dark(0, 0, 3, 3900);
        s.last = last(64, 1023);
        s.ref = ref(64, 1023);
        final Parameters p = assertSame("widened", s);
        assertTrue(p.levelSource, p.levelSource.contains("widened"));
        s.last = last(72, 1020);
        assertSame("widened, other levels at the last callback", s);
    }

    /** A Quad frame of a stream without its pedestal: the floor, the remembered stream floor and the flags are the same. */
    @Test public void aStreamWithoutItsPedestalGivesTheSameFloorAndMemory() throws Exception {
        for (int block : new int[]{1, 2}) {
            final Shot s = new Shot();
            s.block = block;
            s.stream = "5|4096x3072|p440-" + (++streams) + "|5";
            s.dark = withoutPedestal(block);
            s.last = last(64, 1023);
            s.ref = ref(64, 1023);
            final Parameters p = assertSame("no pedestal, block " + block, s);
            assertTrue(p.levelSources(), p.levelSources().contains("without its pedestal"));
            assertFalse("the stream's floor is remembered", Float.isNaN(PedestalMemory.floor(s.stream, 1023)));
            s.last = last(72, 1023);
            assertSame("no pedestal, block " + block + ", black 72 at the last callback", s);
        }
        assertFalse("the comparison itself sees a difference", sameFields(base(true), base(false)));
    }

    // ---- a deep comparison of every field (arrays element by element, the app's own objects field by field)

    private static boolean sameFields(Object a, Object b) {
        try {
            compare("", a, b, new IdentityHashMap<>());
            return true;
        } catch (AssertionError e) {
            return false;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void compare(String path, Object a, Object b, IdentityHashMap<Object, Boolean> seen) throws Exception {
        if (a == b) return;
        if (a == null || b == null) fail(path + ": " + a + " vs " + b);
        assertEquals(path + " class", a.getClass(), b.getClass());
        final Class<?> type = a.getClass();
        if (type.isArray()) {
            final int n = java.lang.reflect.Array.getLength(a);
            assertEquals(path + " length", n, java.lang.reflect.Array.getLength(b));
            for (int i = 0; i < n; i++)
                compare(path + "[" + i + "]", java.lang.reflect.Array.get(a, i), java.lang.reflect.Array.get(b, i), seen);
            return;
        }
        if (a instanceof ByteBuffer) fail(path + ": two different buffers");
        if (a instanceof Float) {
            assertEquals(path, Float.floatToRawIntBits((Float) a), Float.floatToRawIntBits((Float) b));
            return;
        }
        if (a instanceof Double) {
            assertEquals(path, Double.doubleToRawLongBits((Double) a), Double.doubleToRawLongBits((Double) b));
            return;
        }
        if (a instanceof Pair) {
            compare(path + ".first", ((Pair<?, ?>) a).first, ((Pair<?, ?>) b).first, seen);
            compare(path + ".second", ((Pair<?, ?>) a).second, ((Pair<?, ?>) b).second, seen);
            return;
        }
        if (!type.getName().startsWith("com.scamera.app.")) {
            assertEquals(path, a, b);
            return;
        }
        if (seen.put(a, Boolean.TRUE) != null) return;
        int fields = 0;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                compare(path + "." + f.getName(), f.get(a), f.get(b), seen);
                fields++;
            }
        }
        if (type == Parameters.class) assertTrue("Parameters has its fields", fields > 100);
    }
}
