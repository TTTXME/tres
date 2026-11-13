package com.scamera.app.processing.color;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class FilmPreviewLutTest {
    @Before public void setup() { FilmPreviewLut.resetForTests(); }
    @After public void finish() { FilmPreviewLut.resetForTests(); }

    private static FilmSim.Settings settings(String stock, String format, float strength, float grain, float couplers) {
        return new FilmSim.Settings(FilmSim.stock(stock), FilmSim.print("kodak_portra_endura"), FilmSim.format(format),
                grain, grain, grain, -.5f, .25f, .1f, 3, -2, strength, LensFilters.BLACK_PRO_MIST, .8f, couplers);
    }

    @Test public void nativeColourKeyIncludesProfileAndRecipeButExcludesSpatialSettings() {
        FilmSim.Settings a = settings("kodak_portra_400", "135", 1, 1, .7f);
        FilmSim.Settings b = settings("kodak_portra_400", "67", .3f, 0, .7f);
        String key = FilmPreviewLut.key(a, 41, FilmPreviewLut.Domain.SCENE);
        assertEquals(key, FilmPreviewLut.key(b, 41, FilmPreviewLut.Domain.SCENE));
        assertNotEquals(key, FilmPreviewLut.key(settings("kodak_gold_200", "135", 1, 1, .7f), 41, FilmPreviewLut.Domain.SCENE));
        assertNotEquals(key, FilmPreviewLut.key(settings("kodak_portra_400", "135", 1, 1, .2f), 41, FilmPreviewLut.Domain.SCENE));
        assertNotEquals(key, FilmPreviewLut.key(a, 33, FilmPreviewLut.Domain.SCENE));
        assertNotEquals(key, FilmPreviewLut.key(a, 41, FilmPreviewLut.Domain.DISPLAY));
        ByteBuffer lut = FilmPreviewLut.parameters(a).buffer();
        ByteBuffer photo = FilmReferenceParameters.from(a, false, 1).buffer();
        for (int offset : new int[]{256, 436, 492, 536, 580}) assertEquals(0, lut.getInt(offset));
        assertEquals(.7f, lut.getFloat(196), 0);
        assertEquals(0, lut.getFloat(200), 0);
        assertEquals(0, lut.getFloat(204), 0);
        for (int i = 0; i < 9; i++) assertEquals(photo.getFloat(220 + i * 4), lut.getFloat(220 + i * 4), 0);
        assertEquals("The photo settings were not mutated", 1, photo.getInt(256));
        assertEquals("The photo's DIR still diffuses", 20, photo.getFloat(200), 0);
    }

    @Test public void gridsUseTheSameRedFastestLayoutAndHalfContractAsNativeImages() {
        for (int code = 0; code <= 0x7bff; code++)
            assertEquals(code, FilmPreviewLut.half(FilmSpectral.half((short) code)) & 65535);
        int size = 5;
        for (FilmPreviewLut.Domain domain : FilmPreviewLut.Domain.values()) {
            ByteBuffer grid = FilmPreviewLut.input(size, domain);
            assertTrue(grid.isDirect());
            assertEquals(size * size * size * 8, grid.remaining());
            for (int b = 0; b < size; b++) for (int g = 0; g < size; g++) for (int r = 0; r < size; r++) {
                int offset = ((b * size + g) * size + r) * 8;
                assertEquals(FilmPreviewLut.half((float) FilmPreviewLut.grid(r, size, domain)), grid.getShort(offset));
                assertEquals(FilmPreviewLut.half((float) FilmPreviewLut.grid(g, size, domain)), grid.getShort(offset + 2));
                assertEquals(FilmPreviewLut.half((float) FilmPreviewLut.grid(b, size, domain)), grid.getShort(offset + 4));
                assertEquals(0x3c00, grid.getShort(offset + 6));
            }
        }
        assertEquals(FilmSim.FILM_GREY, FilmPreviewLut.grid(2, 5, FilmPreviewLut.Domain.SCENE), 1e-12);
        assertEquals(0, FilmPreviewLut.grid(0, 5, FilmPreviewLut.Domain.DISPLAY), 0);
    }

    @Test public void nativeOutputAndTrilinearInterpolationKeepProfileColour() {
        ByteBuffer image = FilmPreviewLut.input(3, FilmPreviewLut.Domain.DISPLAY);
        for (int i = 0; i < image.capacity(); i += 8) {
            image.putShort(i, FilmPreviewLut.half(.25f));
            image.putShort(i + 2, FilmPreviewLut.half(.5f));
            image.putShort(i + 4, FilmPreviewLut.half(.75f));
        }
        FilmLut lut = FilmPreviewLut.output(image, 3, "test");
        assertArrayEquals(new float[]{.25f, .5f, .75f}, lut.sample(.37f, .12f, .93f), 1e-7f);
        image.putShort(0, (short) 0x7e00);
        try { FilmPreviewLut.output(image, 3, "bad"); fail("NaN LUT must not replace the legacy preview"); }
        catch (IllegalStateException expected) { }
    }

    @Test public void displayReferenceMixesTheUserLutInsideThePhotoFilmStrength() {
        FilmLut film = FilmLut.identity(5);
        float[] white = new float[film.rgb.length]; java.util.Arrays.fill(white, 1);
        FilmLut user = FilmLut.of(5, white, "white");
        assertArrayEquals(new float[]{.625f, .75f, .875f},
                FilmChain.previewDisplay(film, .5f, user, 1).sample(.25f, .5f, .75f), 1e-6f);
        assertArrayEquals(new float[]{.25f, .5f, .75f},
                FilmChain.previewDisplay(film, 0, user, 1).sample(.25f, .5f, .75f), 1e-6f);
        assertArrayEquals(new float[]{1, 1, 1},
                FilmChain.previewDisplay(film, 1, user, 1).sample(.25f, .5f, .75f), 1e-6f);
        assertArrayEquals("The legacy route keeps its old composition", new float[]{1, 1, 1},
                FilmChain.compose(film, .5f, user, 1).sample(.25f, .5f, .75f), 1e-6f);
    }

    @Test public void rapidStockChangesKeepOnlyOneDrainAndRenderTheLatestRequest() {
        List<Runnable> queue = new ArrayList<>();
        FilmPreviewLut.executor = queue::add;
        AtomicInteger renders = new AtomicInteger(), shown = new AtomicInteger();
        FilmPreviewLut.renderer = (context, settings, size, domain) -> { renders.incrementAndGet(); return FilmLut.identity(size); };
        AtomicInteger latest = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            final int ticket = latest.incrementAndGet();
            FilmSim.Stock stock = FilmSim.STOCKS[i];
            FilmPreviewLut.load(null, settings(stock.id, "135", 1, 1, 1), 3, FilmPreviewLut.Domain.SCENE,
                    () -> latest.get() == ticket, lut -> shown.set(ticket));
        }
        assertEquals("A queue of obsolete GPU renders is never created", 1, queue.size());
        queue.remove(0).run();
        assertEquals(1, renders.get());
        assertEquals(20, shown.get());
    }

    @Test public void changedRequestCannotPublishAnAlreadyRunningOlderStock() {
        List<Runnable> queue = new ArrayList<>(); FilmPreviewLut.executor = queue::add;
        AtomicReference<String> selected = new AtomicReference<>("kodak_portra_400");
        List<String> shown = new ArrayList<>();
        FilmPreviewLut.renderer = (context, settings, size, domain) -> {
            selected.set("kodak_gold_200");
            return FilmLut.identity(size);
        };
        FilmPreviewLut.load(null, settings(selected.get(), "135", 1, 1, 1), 3, FilmPreviewLut.Domain.SCENE,
                () -> selected.get().equals("kodak_portra_400"), lut -> shown.add("stale"));
        queue.remove(0).run();
        assertTrue(shown.isEmpty());
        assertNotNull("A completed table can still be cached for returning to that stock",
                FilmPreviewLut.cached(settings("kodak_portra_400", "135", 1, 1, 1), 3, FilmPreviewLut.Domain.SCENE));
    }

    @Test public void selectedLegacyFilmStaysUntilTheSameRecipeReferenceTableArrives() throws Exception {
        java.io.File directory = new java.io.File("app/src/main/assets/film");
        if (!directory.isDirectory()) directory = new java.io.File("src/main/assets/film");
        final java.io.File assets = directory;
        FilmSpectral.source(name -> new java.io.FileInputStream(new java.io.File(assets, name)));
        FilmChain.forget();
        List<Runnable> queue = new ArrayList<>(); FilmPreviewLut.executor = queue::add;
        FilmPreviewLut.renderer = (context, settings, size, domain) -> FilmLut.identity(size);
        FilmSim.Settings selected = settings("kodak_gold_200", "135", 1, 1, 1);
        List<FilmChain.Scene> shown = new ArrayList<>();
        FilmChain.loadScene(null, selected, "", 0, () -> true, shown::add);
        assertEquals(1, shown.size());
        assertNotNull(shown.get(0).lut);
        assertFalse(shown.get(0).reference);
        assertTrue(shown.get(0).key.contains("kodak_gold_200"));
        queue.remove(0).run();
        assertEquals(2, shown.size());
        assertTrue(shown.get(1).reference);
        assertEquals(shown.get(0).key, shown.get(1).key);
        shown.clear();
        FilmChain.loadScene(null, selected, "", 0, () -> true, shown::add);
        assertEquals("Returning to a cached recipe does not flash its legacy table", 1, shown.size());
        assertTrue(shown.get(0).reference);
        assertTrue(queue.isEmpty());
    }
}
