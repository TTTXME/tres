package com.scamera.app.processing.opengl.postpipeline;

import android.app.Application;

import com.scamera.app.processing.color.CcmPresets;
import com.scamera.app.processing.LiveRawFrame;
import com.scamera.app.processing.LiveWhyMeter;
import com.scamera.app.processing.color.ColourLook;
import com.scamera.app.processing.color.FilmSim;
import com.scamera.app.processing.render.ColorCorrectionTransform;
import com.scamera.app.processing.render.Parameters;
import com.scamera.app.settings.PreferenceKeys;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mockStatic;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class WhyCctFilmTest {
    private final Map<String, Float> values = new HashMap<>();
    private MockedStatic<PreferenceKeys> preferences;
    private boolean dynamic;

    @Before public void setUp() {
        preferences = mockStatic(PreferenceKeys.class, call -> {
            String name = call.getMethod().getName();
            if (name.equals("hybridValue")) return values.getOrDefault(call.getArgument(0), call.getArgument(1));
            if (name.equals("hybridSwitch")) return call.<String>getArgument(0).equals("why_dynamic_cct") ? dynamic : call.getArgument(1);
            if (name.equals("hybridString")) return call.getArgument(1);
            return Mockito.RETURNS_DEFAULTS.answer(call);
        });
    }

    @After public void tearDown() {
        preferences.close();
    }

    private static FilmSim.Settings film(float strength) {
        FilmSim.Stock stock = FilmSim.stock("kodak_portra_400");
        return new FilmSim.Settings(stock, FilmSim.print("", stock), FilmSim.format("135"), 1f, 1f, 1f, 0f, 0f, 0f, strength);
    }

    private static Parameters parameters(int iso) {
        Parameters p = Mockito.mock(Parameters.class);
        p.iso = iso;
        p.CCT = new ColorCorrectionTransform();
        p.CCT.matrix = new float[]{1.65f, -.45f, -.20f, -.15f, 1.35f, -.20f, .03f, -.48f, 1.45f};
        return p;
    }

    private void look(float sat, float r, float g, float b) {
        values.put("why_cct_sat", sat);
        values.put("why_cct_gain_r", r);
        values.put("why_cct_gain_g", g);
        values.put("why_cct_gain_b", b);
        values.put("why_dynamic_cct_strength", .6f);
    }

    private static void sameBits(float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) assertEquals("element " + i,
                Float.floatToRawIntBits(expected[i]), Float.floatToRawIntBits(actual[i]));
    }

    @Test public void noFilmKeepsThePreviousMatrixBitForBit() {
        float[] white = {1.07f, -.04f, -.03f, 0f, 1f, 0f, -.02f, -.03f, 1.05f};
        for (boolean d : new boolean[]{false, true}) {
            dynamic = d;
            for (int iso : new int[]{100, 1600, 6400}) {
                Parameters p = parameters(iso);
                for (float sat : new float[]{.5f, 1f, 1.1f, 1.8f}) {
                    look(sat, 1.2f, .8f, 1.1f);
                    float[] old = ColourLook.mul(white, p.CCT.matrix);
                    if (dynamic) old = CcmPresets.dynamicCct(old, iso, .6f);
                    old = ColourLook.mul(WhyTone.cctGain(new float[]{1.2f, .8f, 1.1f}, WhyTone.cctSat(sat, null)), old);
                    sameBits(old, WhyTone.colourMatrixOf(p, white, null));
                    sameBits(old, WhyTone.colourMatrixOf(p, white, film(0f)));
                }
            }
        }
    }

    @Test public void fullFilmKeepsOnlyTheCameraMatrixAndChosenWhiteBalance() {
        dynamic = true;
        look(1.9f, 1.3f, .7f, 1.2f);
        float[] white = {1.07f, -.04f, -.03f, 0f, 1f, 0f, -.02f, -.03f, 1.05f};
        for (int iso : new int[]{100, 1600, 6400}) {
            Parameters p = parameters(iso);
            float[] before = p.CCT.matrix.clone();
            sameBits(ColourLook.mul(white, p.CCT.matrix), WhyTone.colourMatrixOf(p, white, film(1f)));
            assertSame(p.CCT.matrix, WhyTone.colourMatrixOf(p, null, film(1f)));
            sameBits(before, p.CCT.matrix);
        }
    }

    @Test public void defaultSaturationOnePointOneDoesNotEnterTheFullFilm() {
        assertEquals(1.1f, WhyTone.CCT_SAT, 0f);
        assertNotNull(WhyTone.withCctSat(null));
        assertNull(WhyTone.withCctSat(null, film(1f)));
        float[] white = {1.08f, -.04f, -.04f, -.01f, 1.02f, -.01f, -.02f, -.02f, 1.04f};
        assertSame(white, WhyTone.withCctSat(white, film(1f)));
        Parameters p = parameters(6400);
        assertSame(p.CCT.matrix, WhyTone.colourMatrixOf(p, null, film(1f)));
    }

    @Test public void halfFilmScalesCreativeAmountsTowardsTheirNeutralValues() {
        dynamic = true;
        look(1.6f, 1.2f, .8f, 1.1f);
        FilmSim.Settings half = film(.5f);
        Parameters p = parameters(6400);
        float[] expected = CcmPresets.dynamicCct(p.CCT.matrix, 6400, .3f);
        expected = ColourLook.mul(WhyTone.cctGain(new float[]{1.1f, .9f, 1.05f}, WhyTone.cctSat(1.3f, null)), expected);
        assertArrayEquals(expected, WhyTone.colourMatrixOf(p, null, half), 1e-6f);
        assertEquals(1.05f, WhyTone.cctAmount(1.1f, 1f, half), 1e-6f);
        assertEquals(.3f, WhyTone.cctAmount(.6f, 0f, half), 1e-6f);
    }

    @Test public void thePipelineUsesItsFilmSnapshotAndTheDiagnosticsUseTheCurrentFilm() {
        look(1.7f, 1.2f, .8f, 1.1f);
        dynamic = true;
        Parameters p = parameters(6400);
        FilmSim.Settings full = film(1f);
        ColourLook colour = new ColourLook();
        colour.warmth = 35f;
        colour.calSat[0] = 15f;
        float[] white = colour.whiteMatrix(6504f);
        try (MockedStatic<FilmSim> films = mockStatic(FilmSim.class)) {
            films.when(FilmSim::current).thenReturn(null);
            PostPipeline pipeline = new PostPipeline();
            pipeline.mParameters = p;
            pipeline.film = full;
            pipeline.look = new ShotLook(full, "", 0f, colour);
            pipeline.why = new WhyTone.State();
            sameBits(ColourLook.mul(white, p.CCT.matrix), WhyTone.colourMatrix(pipeline));
            films.when(FilmSim::current).thenReturn(full);
            sameBits(p.CCT.matrix, WhyTone.colourMatrixNow(p));
            films.when(FilmSim::current).thenReturn(null);
            sameBits(WhyTone.colourMatrixOf(p, null), WhyTone.colourMatrixNow(p));
        }
    }

    @Test public void theLinearPreviewReceivesTheTableSettingsInsteadOfReadingTheFilmAgain() throws Exception {
        String root = "src/main/java/com/scamera/app/";
        String live = source(root + "processing/opengl/postpipeline/LiveWhyTone.java");
        int draw = live.indexOf("public boolean draw(java.util.function.Function<");
        int refresh = live.indexOf("refreshSettings();", draw);
        int table = live.indexOf("final FilmLut lut = lutNow();", draw);
        assertTrue(refresh > draw && refresh < table && table < live.indexOf("linear.apply(shownFilm)", draw));
        assertTrue(live.contains("raw.bind(p, shownFilm)"));
        assertEquals(2, live.split("observeFilm\\(shownFilm\\);", -1).length - 1);
        String combine = WhyCombineUniformParityTest.method(live, "private void combine(");
        assertFalse("the loader cannot change the table after the RAW matrix was drawn", combine.contains("lutNow()"));
        assertTrue(live.contains("combine(ae, aeNow, outFbo, w, h, true, lut)"));
        assertTrue(live.contains("combine(r, aeNow, outFbo, w, h, false, lut)"));
        String renderer = source(root + "ui/camera/views/viewfinder/LiveRawRenderer.java");
        assertEquals(2, renderer.split("WhyTone.withCctSat\\(lookBaseWhite,film\\)", -1).length - 1);
        String main = source(root + "ui/camera/views/viewfinder/MainRenderer.java");
        assertTrue(main.contains("mRawRenderer.bindFrame(program, turn, mirrored, film)"));
        assertTrue(main.contains("mWhyTone.draw(film -> mRawRenderer.drawLinear(quadXY, quadUV, turn, mirrored, film)"));
    }

    @Test public void anUnloadedOrUnreadableSceneTableKeepsTheOwnColourMatrix() {
        FilmSim.Settings full = film(1f);
        assertNull(LiveWhyTone.filmForTable(full, false));
        assertSame(full, LiveWhyTone.filmForTable(full, true));
        assertNull(LiveWhyTone.filmForTable(null, false));
        float[] before = WhyTone.withCctSat(null);
        sameBits(before, WhyTone.withCctSat(null, LiveWhyTone.filmForTable(full, false)));
        assertNull(WhyTone.withCctSat(null, LiveWhyTone.filmForTable(full, true)));
    }

    @Test public void aChangedFilmMatrixRemeasuresEvenTheSameRawFrameWithoutResettingTheExposureTrack() throws Exception {
        LiveWhyTone tone = new LiveWhyTone();
        WhyAe.Result ae = new WhyAe.Result();
        ae.ae = 2f;
        ae.clip = 1f;
        field("ae").set(tone, ae);
        field("trackedSession").setInt(tone, 7);
        field("trackedVersion").setInt(tone, 42);
        FrameExposure.Track track = (FrameExposure.Track) field("track").get(tone);
        track.next(3.2, -4, true);
        ToneEase shown = (ToneEase) field("shown").get(tone);
        shown.toward(ae, 2f, new float[]{.1f, 1f, 2f, 4f}, 3.2, 0L);
        double before = shown.logAe();
        LiveRawFrame.Frame frame = new LiveRawFrame.Frame();
        frame.session = 7;
        frame.version = 42;
        LiveWhyMeter.View view = new LiveWhyMeter.View(frame, 0, 0, 1, 1, new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1});

        tone.observeFilm(null);
        assertFalse(due(tone));
        for (FilmSim.Settings next : new FilmSim.Settings[]{film(1f), film(.5f), null}) {
            tone.observeFilm(next);
            java.lang.reflect.Method observe = LiveWhyTone.class.getDeclaredMethod("observe", LiveWhyMeter.View.class);
            observe.setAccessible(true);
            assertFalse("the frozen RAW was already observed", (Boolean) observe.invoke(tone, view));
            assertTrue("the CCT changed: meter now without a new RAW", due(tone));
            assertEquals(3.2, track.logP(), 0d);
            assertEquals(before, shown.logAe(), 0d);
            java.lang.reflect.Method metered = LiveWhyTone.class.getDeclaredMethod("metered");
            metered.setAccessible(true);
            metered.invoke(tone);
            tone.observeFilm(next);
            assertFalse("no CCT change: do not meter every redraw", due(tone));
        }
        tone.observeFilm(film(1f));
        java.lang.reflect.Method metered = LiveWhyTone.class.getDeclaredMethod("metered");
        metered.setAccessible(true);
        metered.invoke(tone);
        FilmSim.Stock other = FilmSim.stock("fujifilm_velvia_100");
        tone.observeFilm(new FilmSim.Settings(other, FilmSim.print("", other), FilmSim.format("135"), 1, 1, 1, 0, 0, 0, 1));
        assertFalse("a stock change at the same strength does not change the input CCT", due(tone));
    }

    private static boolean due(LiveWhyTone tone) throws Exception {
        java.lang.reflect.Method method = LiveWhyTone.class.getDeclaredMethod("meterDue", boolean.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(tone, false);
    }

    private static java.lang.reflect.Field field(String name) throws Exception {
        java.lang.reflect.Field field = LiveWhyTone.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static String source(String path) throws Exception {
        File f = new File(path);
        if (!f.isFile()) f = new File("app/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
