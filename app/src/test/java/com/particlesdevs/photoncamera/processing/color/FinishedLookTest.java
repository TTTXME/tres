package com.particlesdevs.photoncamera.processing.color;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * P619: the gallery editor's look (FinishedLook) against the camera's display chain (FilmChain): a LUT alone is the camera's own
 * table and strength; a film's table at strength 1 mixed by the shader equals the camera's baked chain within one 8-bit code (the
 * tables stored as the GPU stores them, half floats); two phases as the viewfinder loads them.
 */
public class FinishedLookTest {
    @Before public void setUp() throws Exception {
        java.io.File directory = new java.io.File("app/src/main/assets/film");
        if (!directory.isDirectory()) directory = new java.io.File("src/main/assets/film");
        final java.io.File assets = directory;
        FilmSpectral.source(name -> new java.io.FileInputStream(new java.io.File(assets, name)));
        FilmPreviewLut.resetForTests();
        FilmChain.forget();
    }

    @After public void tearDown() {
        FilmPreviewLut.resetForTests();
        FilmChain.forget();
    }

    private static FilmSim.Settings film(String stock, float strength) {
        FilmSim.Stock st = FilmSim.stock(stock);
        return new FilmSim.Settings(st, FilmSim.print("", st), FilmSim.format("135"), 0f, 0f, 0f, .3f, -.1f, .2f, 0f, 0f, strength, "",
                FilmSim.DIFFUSION_DEFAULT, 0f, FilmAdvanced.DEFAULT);
    }

    /** A table as the 3D texture holds it (RGB16F). */
    private static FilmLut half(FilmLut l) {
        float[] v = new float[l.rgb.length];
        for (int i = 0; i < v.length; i++) v[i] = FilmSpectral.half(FilmPreviewLut.half(l.rgb[i]));
        return FilmLut.of(l.size, v, l.title);
    }

    /** max |camera - editor| in 8-bit codes over random colours: camera = its table at strength 1, editor = its table mixed by s. */
    private static float maxCodes(FilmLut camera, float cameraStrength, FilmLut editor, float editorStrength) {
        FilmLut a = half(camera), b = half(editor);
        Random rnd = new Random(619);
        float[] tmp = new float[3], x = new float[3], y = new float[3];
        float worst = 0;
        for (int i = 0; i < 20000; i++) {
            float r = rnd.nextFloat(), g = rnd.nextFloat(), bl = rnd.nextFloat();
            if (i < 64) r = g = bl = i / 63f;
            FinishedLook.apply(a, cameraStrength, r, g, bl, tmp, x);
            FinishedLook.apply(b, editorStrength, r, g, bl, tmp, y);
            for (int c = 0; c < 3; c++) worst = Math.max(worst, Math.abs(x[c] - y[c]) * 255f);
        }
        return worst;
    }

    @Test public void aLutAloneIsTheCamerasOwnTableAndStrength() {
        String name = FilmLooks.values()[3];
        List<FinishedLook.Table> got = new ArrayList<>();
        FinishedLook.load(null, null, name, .6f, () -> true, got::add);
        assertEquals(1, got.size());
        FilmLut camera = FilmChain.lut(null, FilmChain.current(null, name, .6f).key);
        assertArrayEquals("the camera's table, value for value", camera.rgb, got.get(0).lut.rgb, 0f);
        assertEquals(FilmChain.current(null, name, .6f).strength, FinishedLook.shaderStrength(null, .6f), 0f);
        assertEquals("lut:" + name, FinishedLook.key(null, name, .6f));
        assertEquals("its strength is the shader's", FinishedLook.key(null, name, .6f), FinishedLook.key(null, name, .2f));
        assertEquals("", FinishedLook.key(null, name, 0f));
        assertEquals("", FinishedLook.key(null, "", 1f));
        assertEquals(0f, maxCodes(camera, .6f, got.get(0).lut, .6f), 0f);
    }

    @Test public void theFilmsStrengthIsTheShadersMixOfTheCamerasDisplayChain() {
        // the reference model's DISPLAY table (here a stand-in with a film-like curve and a colour shift): the camera bakes the
        // strength and the LUT on the print (FilmChain.previewDisplay); the editor keeps the film at 1 and mixes in the shader
        FilmPreviewLut.executor = Runnable::run;
        FilmPreviewLut.renderer = (context, settings, size, domain) -> {
            FilmLut id = FilmLut.identity(size);
            float[] v = id.rgb.clone();
            for (int i = 0; i < v.length; i += 3) {
                float r = v[i], g = v[i + 1], b = v[i + 2];
                v[i] = (float) Math.min(1, Math.pow(r, .8) * 1.04 + .02);
                v[i + 1] = (float) (g * g * (3 - 2 * g) * .9 + g * .1);
                v[i + 2] = (float) Math.max(0, Math.pow(b, 1.2) * .95 + .01 * r);
            }
            return FilmLut.of(size, v, "stand-in");
        };
        FilmSim.Settings f = film("kodak_portra_400", .55f);
        String user = FilmLooks.values()[0];
        List<FinishedLook.Table> got = new ArrayList<>();
        FinishedLook.load(null, f, user, .7f, () -> true, got::add);
        FinishedLook.Table ref = got.get(got.size() - 1);
        assertTrue(ref.reference);
        FilmLut reference = FilmPreviewLut.cached(f, FilmLooks.SIZE, FilmPreviewLut.Domain.DISPLAY);
        assertNotNull(reference);
        FilmLut camera = FilmChain.previewDisplay(reference, f.strength, FilmLooks.lut(user), .7f);
        float codes = maxCodes(camera, 1f, ref.lut, FinishedLook.shaderStrength(f, .7f));
        System.out.println(String.format(java.util.Locale.ROOT, "film + LUT on the print, strength .55: max %.3f codes", codes));
        assertTrue("within one 8-bit code of the camera's chain (" + codes + ")", codes <= 1f);
        assertEquals("the film's strength never changes the table's key", FinishedLook.key(f, user, .7f),
                FinishedLook.key(film("kodak_portra_400", .2f), user, .7f));
        assertNotEquals("the LUT's on the print does", FinishedLook.key(f, user, .7f), FinishedLook.key(f, user, .3f));
        assertEquals(.55f, FinishedLook.shaderStrength(f, .7f), 0f);
    }

    @Test public void theFirstPhaseIsTheCamerasLegacyTable() {
        // no reference model on the JVM's queue: the first phase only (the camera's FilmChain.lut for the same recipe)
        List<Runnable> queue = new ArrayList<>();
        FilmPreviewLut.executor = queue::add;
        FilmSim.Settings f = film("kodak_gold_200", .6f);
        List<FinishedLook.Table> got = new ArrayList<>();
        FinishedLook.load(null, f, "", 0f, () -> true, got::add);
        assertEquals(1, got.size());
        assertFalse(got.get(0).reference);
        FilmLut camera = FilmChain.lut(null, FilmChain.current(f, "", 0f).key);
        float codes = maxCodes(camera, 1f, got.get(0).lut, f.strength);
        System.out.println(String.format(java.util.Locale.ROOT, "first phase, film alone at .6: max %.3f codes", codes));
        assertTrue("within one code of the camera's legacy chain (" + codes + ")", codes <= 1f);
        // at strength 1 with a LUT on the print the legacy chain and ours are the same composition
        String user = FilmLooks.values()[2];
        got.clear();
        FilmSim.Settings full = film("kodak_gold_200", 1f);
        FinishedLook.load(null, full, user, .5f, () -> true, got::add);
        FilmLut legacy = FilmChain.lut(null, FilmChain.current(full, user, .5f).key);
        assertEquals(0f, maxCodes(legacy, 1f, got.get(0).lut, 1f), 1e-3f);
        assertEquals("the reference tables were asked for (one drain)", 1, queue.size());
    }

    @Test public void aCachedReferenceTableComesAloneAndAStaleOneNever() {
        List<Runnable> queue = new ArrayList<>();
        FilmPreviewLut.executor = queue::add;
        FilmPreviewLut.renderer = (context, settings, size, domain) -> FilmLut.identity(size);
        FilmSim.Settings f = film("kodak_portra_160", 1f);
        List<FinishedLook.Table> got = new ArrayList<>();
        FinishedLook.load(null, f, "", 0f, () -> true, got::add);
        assertEquals("the first phase at once", 1, got.size());
        queue.remove(0).run();
        assertEquals(2, got.size());
        assertTrue(got.get(1).reference);
        assertEquals(got.get(0).key, got.get(1).key);
        got.clear();
        FinishedLook.load(null, f, "", 0f, () -> true, got::add);
        assertEquals("a cached reference table without the first phase's flash", 1, got.size());
        assertTrue(got.get(0).reference);
        got.clear();
        FinishedLook.load(null, film("kodak_ektar_100", 1f), "", 0f, () -> false, got::add);
        assertTrue("nothing for a state that moved on", got.isEmpty());
    }

    @Test public void theExportTakesTheFinalTable() {
        // the JVM has no reference engine: the first phase is the final one, given at once
        FilmSim.Settings f = film("kodak_gold_200", 1f);
        long t0 = System.nanoTime();
        FinishedLook.Table t = FinishedLook.blocking(null, f, "", 0f, null, 60_000);
        assertNotNull(t);
        assertTrue("no wait for an engine that is not there", (System.nanoTime() - t0) / 1e9 < 30);
        assertSame("a final table in hand is kept", t, FinishedLook.blocking(null, f, "", 0f, t, 60_000));
        assertNull(FinishedLook.blocking(null, null, "", 0f, null, 10));
    }

    @Test public void thumbnailsAreTheLookupPerPixel() {
        FilmLut l = FilmLooks.lut(FilmLooks.values()[1]);
        int[] px = {0xFF000000, 0xFFFFFFFF, 0xFF336699, 0x80C08040};
        int[] out = FinishedLook.apply(px, l, 1f);
        for (int i = 0; i < px.length; i++) {
            float[] s = l.sample(((px[i] >> 16) & 255) / 255f, ((px[i] >> 8) & 255) / 255f, (px[i] & 255) / 255f);
            assertEquals(px[i] & 0xFF000000, out[i] & 0xFF000000);
            assertEquals(Math.round(Math.max(0, Math.min(1, s[0])) * 255), (out[i] >> 16) & 255);
            assertEquals(Math.round(Math.max(0, Math.min(1, s[1])) * 255), (out[i] >> 8) & 255);
            assertEquals(Math.round(Math.max(0, Math.min(1, s[2])) * 255), out[i] & 255);
        }
        assertArrayEquals("strength 0 leaves the picture", px, FinishedLook.apply(px, l, 0f));
    }
}
