package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.app.Application;
import android.graphics.Point;
import android.hardware.camera2.CaptureResult;
import com.particlesdevs.photoncamera.capture.CameraResumeTest;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** P27: one frame never costs the Hybrid shot; frames that missed their plan are merged in the role their exposure gives. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class, shadows=CameraResumeTest.ShadowAllocator.class,
        instrumentedPackages="com.particlesdevs.photoncamera.util")
public class ScamHybridBurstRolesTest {
    private MockedStatic<PreferenceKeys> prefs;
    private MockedStatic<com.particlesdevs.photoncamera.app.ScameraApp> appStatic;
    private long clock = 1_000_000_000L;

    @Before public void setup() {
        // PreferenceKeys' class initialisation reads strings through ScameraApp (as in CameraResumeTest).
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        com.particlesdevs.photoncamera.settings.SettingsManager settings = new com.particlesdevs.photoncamera.settings.SettingsManager(context);
        appStatic = mockStatic(com.particlesdevs.photoncamera.app.ScameraApp.class);
        appStatic.when(com.particlesdevs.photoncamera.app.ScameraApp::getAppContext).thenReturn(context);
        appStatic.when(com.particlesdevs.photoncamera.app.ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        appStatic.when(com.particlesdevs.photoncamera.app.ScameraApp::getSettingsManagerStatic).thenReturn(settings);
        appStatic.when(com.particlesdevs.photoncamera.app.ScameraApp::getSettings).thenReturn(new com.particlesdevs.photoncamera.api.Settings());
        appStatic.when(() -> com.particlesdevs.photoncamera.app.ScameraApp.getStringStatic(anyInt())).thenAnswer(i -> context.getString(i.getArgument(0)));
        prefs = mockStatic(PreferenceKeys.class);
        prefs.when(() -> PreferenceKeys.hybridSwitch(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        prefs.when(() -> PreferenceKeys.hybridValue(anyString(), anyFloat())).thenAnswer(i -> i.getArgument(1));
        prefs.when(() -> PreferenceKeys.hybridString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        prefs.when(PreferenceKeys::hybridOutputMode).thenReturn("sensor");
        prefs.when(PreferenceKeys::hybridDownsamplerName).thenReturn("test");
        prefs.when(() -> PreferenceKeys.hybridFinalSize(anyInt(), anyInt())).thenAnswer(i -> new Point(i.getArgument(0), i.getArgument(1)));
    }
    @After public void cleanup() { prefs.close(); appStatic.close(); }

    private ImageFrame frame(ImageFrame.CaptureRole role, long ns, int iso) {
        ImageFrame f = mock(ImageFrame.class);
        f.width = 64; f.height = 64; f.buffer = ByteBuffer.allocate(64 * 64 * 2);
        f.timestamp = clock += 33_000_000L; f.number = (int) (clock / 33_000_000L);
        f.measuredExposure = ns; f.measuredIso = iso;
        f.noiseSlope = .00015f; f.noiseOffset = .000002f; f.sharpness = 1f;
        when(f.getCaptureRole()).thenReturn(role);
        CaptureResult metadata = mock(CaptureResult.class);
        when(f.getMatchedCaptureMetadata()).thenReturn(metadata);
        return f;
    }
    private static Parameters parameters() {
        Parameters p = new Parameters();
        p.rawSize = new Point(64, 64); p.cfaPattern = 0; p.whiteLevel = 1023; p.blackLevel = new float[]{64, 64, 64, 64};
        return p;
    }
    private static Object burst(List<ImageFrame> frames) throws Exception {
        Constructor<ScamHybridBurst> c = ScamHybridBurst.class.getDeclaredConstructor(List.class, Parameters.class, boolean.class);
        c.setAccessible(true);
        try { return c.newInstance(frames, parameters(), false); }
        catch (InvocationTargetException e) { throw (Exception) e.getCause(); }
    }
    @SuppressWarnings("unchecked")
    private static <T> List<T> list(Object burst, String name) throws Exception {
        Field f = ScamHybridBurst.class.getDeclaredField(name); f.setAccessible(true); return (List<T>) f.get(burst);
    }
    private static ImageFrame base(Object burst) throws Exception {
        Field f = ScamHybridBurst.class.getDeclaredField("base"); f.setAccessible(true); return (ImageFrame) f.get(burst);
    }

    /**
     * P641d: a DCG + LOFIC shot - the overflow read of an N frame's own exposure (LoficPair) is a short frame of the burst with
     * the exposure and the noise it was handed, while a real bracket (a longer frame of another moment) is still left out of a
     * DCG shot; the base frame's own overflow frame goes first.
     */
    @Test public void aDcgShotKeepsItsOverflowPairsAndDropsRealBrackets() throws Exception {
        List<ImageFrame> frames = new ArrayList<>();
        for (int i = 0; i < 3; i++) frames.add(frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 50));
        ImageFrame overflow = frame(ImageFrame.CaptureRole.EXTRA_SHORT, 1_250_000L, 50); // the main frame's exposure over the measured 8
        overflow.pairedMain = frames.get(2);
        overflow.timestamp = frames.get(2).timestamp + 1;
        overflow.noiseSlope = .00015f / 8; overflow.noiseOffset = .000002f + 9.1e-8f;
        frames.add(overflow);
        frames.add(frame(ImageFrame.CaptureRole.LONG, 40_000_000L, 50));
        Parameters p = parameters(); p.dcgShot = true;
        Constructor<ScamHybridBurst> c = ScamHybridBurst.class.getDeclaredConstructor(List.class, Parameters.class, boolean.class);
        c.setAccessible(true);
        ScamHybridBurst b = c.newInstance(frames, p, false);
        List<ImageFrame> sent = list(b, "frames");
        List<Float> exposures = list(b, "exposures");
        List<float[]> noise = list(b, "noise");
        assertEquals("three N frames and the overflow frame; the long bracket left out", 4, sent.size());
        assertTrue(sent.contains(overflow));
        for (ImageFrame f : sent) assertNotEquals(ImageFrame.CaptureRole.LONG, f.getCaptureRole());
        int at = sent.indexOf(overflow);
        assertEquals("its exposure against the base: one over the measured ratio", 0.125f, exposures.get(at), 1e-4f);
        assertEquals("its own noise description", .00015f / 8, noise.get(at)[0], 1e-9f);
        assertEquals(1, ScamHybridBurst.lastOverflowPairs);
        // the base frame's own overflow frame before another N frame's, a real short frame after both
        ImageFrame base = frames.get(0), other = frames.get(1);
        ImageFrame ofOther = frame(ImageFrame.CaptureRole.EXTRA_SHORT, 1_250_000L, 50), ofBase = frame(ImageFrame.CaptureRole.EXTRA_SHORT, 1_250_000L, 50);
        ImageFrame real = frame(ImageFrame.CaptureRole.EXTRA_SHORT, 1_250_000L, 50);
        ofOther.pairedMain = other; ofBase.pairedMain = base;
        List<ImageFrame> shorts = new ArrayList<>(java.util.Arrays.asList(real, ofOther, ofBase));
        assertEquals(2, ScamHybridBurst.overflowPairs(shorts));
        ScamHybridBurst.pairedFirst(shorts, base);
        assertEquals(java.util.Arrays.asList(ofBase, ofOther, real), shorts);
    }

    /** P72: 17 Ultra in-sensor zoom alternates packed RAW10 and RAW14 frames: one merge keeps the layout most frames have. */
    @Test public void framesOfOneRawLayoutOnly() {
        List<ImageFrame> frames = new ArrayList<>();
        int[] formats = {0x7E14, 0x25, 0x25, 0x7E14, 0x25};
        for (int f : formats) { ImageFrame frame = frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 100); frame.sourceFormat = f; frames.add(frame); }
        ScamHybridBurst.keepOneLayout(frames);
        assertEquals(3, frames.size());
        for (ImageFrame f : frames) assertEquals(0x25, f.sourceFormat);
        // a tie keeps the first frame's layout; one layout: nothing dropped
        List<ImageFrame> tie = new ArrayList<>();
        for (int f : new int[]{0x7E14, 0x25}) { ImageFrame frame = frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 100); frame.sourceFormat = f; tie.add(frame); }
        ScamHybridBurst.keepOneLayout(tie);
        assertEquals(1, tie.size());
        assertEquals(0x7E14, tie.get(0).sourceFormat);
        List<ImageFrame> plain = new ArrayList<>();
        for (int i = 0; i < 3; i++) { ImageFrame frame = frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 100); frame.sourceFormat = 0x20; plain.add(frame); }
        ScamHybridBurst.keepOneLayout(plain);
        assertEquals(3, plain.size());
    }

    /** P366 (owner, 2026-10-27, X200 Ultra ISZ at sea at noon: «вот такие фото получаю с исз, исправь»): the base frame's EV100 goes
     *  to the worker, which holds its lamp-flicker corrections of the ultrashort frames from daylight up. */
    @Test public void sceneBrightnessOfTheBaseFrameGoesToTheWorker() throws Exception {
        assertEquals("the owner's sea, 12:57:50", 11.19f, ScamHybridBurst.sceneEv100(2_382_580L, 72, 2.0f), 0.01f);
        assertEquals("his LED bar, 2026-10-25 11:58:45", 5.07f, ScamHybridBurst.sceneEv100(9_999_991L, 851, 1.69f), 0.01f);
        assertTrue(Float.isNaN(ScamHybridBurst.sceneEv100(0L, 72, 2f)) && Float.isNaN(ScamHybridBurst.sceneEv100(2_000_000L, 0, 2f))
                && Float.isNaN(ScamHybridBurst.sceneEv100(2_000_000L, 72, 0f)) && Float.isNaN(ScamHybridBurst.sceneEv100(2_000_000L, 72, Float.NaN)));
        assertEquals("usSceneEv 11.19\n", ScamHybridBurst.sceneEvTuning(11.187f));
        assertEquals("usSceneEv -1.50\n", ScamHybridBurst.sceneEvTuning(-1.5f));
        assertEquals("", ScamHybridBurst.sceneEvTuning(Float.NaN));
        // a burst: the merge's base frame (N 2.38 ms at ISO 72), not its ultrashort or long frames, at the shot's aperture
        List<ImageFrame> frames = new ArrayList<>();
        for (int i = 0; i < 3; i++) frames.add(frame(ImageFrame.CaptureRole.NORMAL, 2_382_580L, 72));
        frames.add(frame(ImageFrame.CaptureRole.EXTRA_SHORT, 313_000L, 72));
        frames.add(frame(ImageFrame.CaptureRole.LONG, 4_765_160L, 72));
        Parameters p = parameters(); p.aperture = 2.0f;
        Constructor<ScamHybridBurst> c = ScamHybridBurst.class.getDeclaredConstructor(List.class, Parameters.class, boolean.class);
        c.setAccessible(true);
        ScamHybridBurst b = c.newInstance(frames, p, false);
        assertEquals(11.19f, b.sceneEv, 0.01f);
        assertTrue(b.tuningOverride(), b.tuningOverride().contains("usSceneEv 11.19\n"));
        // no aperture known: no line (the worker's daylight rule stays off)
        ScamHybridBurst unknown = c.newInstance(new ArrayList<>(frames), parameters(), false);
        assertFalse(unknown.tuningOverride(), unknown.tuningOverride().contains("usSceneEv"));
    }

    /** P523 (Ark X8U Quad bursts, moving people: pink stripes): the RAW's neutral goes to the worker for the Quad / Tetra base
     *  demosaic; no usable white point, no line (the worker takes the base frame's grey world). */
    @Test public void cameraNeutralGoesToTheWorker() throws Exception {
        assertArrayEquals(new float[]{0.4787f, 0.6535f}, ScamHybridBurst.neutralOf(new float[]{0.4787f, 1f, 0.6535f}), 1e-6f);
        assertArrayEquals(new float[]{0.5f, 0.25f}, ScamHybridBurst.neutralOf(new float[]{1f, 2f, 0.5f}), 1e-6f);
        assertNull(ScamHybridBurst.neutralOf(new float[3]));
        assertNull(ScamHybridBurst.neutralOf(null));
        assertNull(ScamHybridBurst.neutralOf(new float[]{0.01f, 1f, 0.6f}));
        assertNull(ScamHybridBurst.neutralOf(new float[]{Float.NaN, 1f, 0.6f}));
        assertEquals("wbR 0.47870\nwbB 0.65350\n", ScamHybridBurst.neutralTuning(new float[]{0.4787f, 0.6535f}));
        assertEquals("", ScamHybridBurst.neutralTuning(null));
        List<ImageFrame> frames = new ArrayList<>();
        for (int i = 0; i < 3; i++) frames.add(frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 100));
        Constructor<ScamHybridBurst> c = ScamHybridBurst.class.getDeclaredConstructor(List.class, Parameters.class, boolean.class);
        c.setAccessible(true);
        Parameters p = parameters(); p.whitePoint = new float[]{0.4787f, 1f, 0.6535f};
        ScamHybridBurst b = c.newInstance(frames, p, false);
        assertTrue(b.tuningOverride(), b.tuningOverride().contains("wbR 0.47870\nwbB 0.65350\n"));
        ScamHybridBurst none = c.newInstance(new ArrayList<>(frames), parameters(), false);
        assertFalse(none.tuningOverride(), none.tuningOverride().contains("wbR"));
    }

    @Test public void longFramesDeliveredAtNAreMergedAsN() throws Exception {
        List<ImageFrame> frames = new ArrayList<>();
        for (int i = 0; i < 3; i++) frames.add(frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 320));
        for (int i = 0; i < 5; i++) frames.add(frame(ImageFrame.CaptureRole.LONG, 10_000_000L, 320));
        Object b = burst(frames);
        List<Integer> roles = list(b, "roles");
        assertEquals(8, roles.size());
        for (int role : roles) assertEquals(1, role);
    }

    /** P111 «Движение»: the base is the frame of the moment (the newest ring frame or one of the two before it), not the sharper
     *  frame taken after the shutter; outside the mode the SCAM window as before. */
    @Test public void motionBaseIsTheFrameOfTheMoment() throws Exception {
        List<ImageFrame> frames = new ArrayList<>();
        float[] ringSharpness = {9f, 0.5f, 2f, 1f}; // oldest .. newest ring frame
        for (float sharp : ringSharpness) {
            ImageFrame f = frame(ImageFrame.CaptureRole.NORMAL, 4_000_000L, 20000);
            f.fromZsl = true; f.sharpness = sharp; frames.add(f);
        }
        ImageFrame after = frame(ImageFrame.CaptureRole.NORMAL, 4_000_000L, 20000);
        after.sharpness = 5f; frames.add(after); // the frame after the shutter, the sharpest of the window
        assertSame(after, base(burst(new ArrayList<>(frames))));
        prefs.when(PreferenceKeys::isMotionMode).thenReturn(true);
        // the newest ring frame (1) and the two before it (2, 0.5): the sharper one; the oldest (9) is outside the 3 candidates
        assertSame(frames.get(2), base(burst(new ArrayList<>(frames))));
        assertEquals(-1, ScamHybridBurst.baseFrom(java.util.Collections.singletonList(after), true));
        assertEquals(-1, ScamHybridBurst.baseFrom(frames, false));
        // P471: the anchor stays unless the frame before it is at least 1.15x sharper (noise ties keep the moment)
        org.junit.Assert.assertFalse(ScamHybridBurst.momentSwitch(1.0, 1.1));
        org.junit.Assert.assertTrue(ScamHybridBurst.momentSwitch(1.0, 1.2));
        org.junit.Assert.assertFalse(ScamHybridBurst.momentSwitch(0.0, 0.0));
    }

    @Test public void noNFrameMakesTheClosestFrameTheBase() throws Exception {
        // X300 Ultra 2026-10-06 shape: every N request lost, L x4.29, S x0.25 and ES x0.0625 arrived.
        List<ImageFrame> frames = new ArrayList<>();
        ImageFrame l = frame(ImageFrame.CaptureRole.LONG, 42_900_000L, 100);
        frames.add(l);
        frames.add(frame(ImageFrame.CaptureRole.EXTRA_SHORT, 2_500_000L, 100));
        frames.add(frame(ImageFrame.CaptureRole.EXTRA_SHORT, 625_000L, 100));
        Object b = burst(frames);
        assertSame(l, base(b));
        assertSame(l, list(b, "frames").get(0));
        assertEquals(1, (int) list(b, "roles").get(0));
    }

    @Test public void colourOrderOfTheStreamIsMergedAndNoBayerIsRefused() throws Exception {
        // The hybrid takes the stream's 2x2 colour order and measures a Quad / Tetra colour block itself (P118: the -2 mark of
        // «Quad Bayer — совместимость» is gone); a pattern that is no 2x2 order is refused.
        Parameters p = parameters();
        p.cfaPattern = 3; p.baseCfaPattern = 3;
        Constructor<ScamHybridBurst> c = ScamHybridBurst.class.getDeclaredConstructor(List.class, Parameters.class, boolean.class);
        c.setAccessible(true);
        ScamHybridBurst b = c.newInstance(List.of(frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 100)), p, false);
        assertEquals(3, b.cfa());
        p.cfaPattern = -2;
        try {
            c.newInstance(List.of(frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 100)), p, false);
            fail("cfaPattern -2 must be refused");
        } catch (InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof java.io.IOException);
        }
    }

    @Test public void oneFrameIsEnoughAndBadFramesAreDroppedNotFatal() throws Exception {
        assertEquals(1, list(burst(List.of(frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 100))), "frames").size());
        List<ImageFrame> frames = new ArrayList<>();
        ImageFrame good = frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 100);
        frames.add(good);
        ImageFrame packed = frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 100); packed.rawPayloadError = "RAW is not plain 16-bit";
        frames.add(packed);
        ImageFrame noData = frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 100); noData.buffer = null;
        frames.add(noData);
        ImageFrame noRole = frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 100); when(noRole.getCaptureRole()).thenReturn(null);
        frames.add(noRole);
        ImageFrame tiny = frame(ImageFrame.CaptureRole.EXTRA_SHORT, 10_000L, 50);   // x1/2000: outside the merge range
        frames.add(tiny);
        frames.add(null);
        Object b = burst(frames);
        List<ImageFrame> used = list(b, "frames");
        assertEquals(1, used.size());
        assertSame(good, used.get(0));
    }

    @Test public void missingNoiseProfileFallsBackInsteadOfFailing() throws Exception {
        List<ImageFrame> frames = new ArrayList<>();
        ImageFrame a = frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 400); a.noiseSlope = Float.NaN; a.noiseOffset = Float.NaN;
        ImageFrame b = frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 200);
        frames.add(a); frames.add(b);
        Object burst = burst(frames);
        List<float[]> noise = list(burst, "noise");
        assertEquals(2, noise.size());
        for (float[] n : noise) { assertTrue(n[0] > 0 && Float.isFinite(n[0])); assertTrue(n[1] >= 0 && Float.isFinite(n[1])); }
    }

    @Test public void nothingUsableStillFailsClearly() {
        ImageFrame noData = frame(ImageFrame.CaptureRole.NORMAL, 10_000_000L, 100); noData.buffer = null;
        assertThrows(java.io.IOException.class, () -> burst(List.of(noData)));
    }
}
