package com.particlesdevs.photoncamera.gallery.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.particlesdevs.photoncamera.gallery.editor.EditState.Param;
import com.particlesdevs.photoncamera.processing.color.FilmLooks;
import com.particlesdevs.photoncamera.processing.color.FilmLut;
import com.particlesdevs.photoncamera.processing.color.FilmSim;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * P619: the «LUT» tab's state and math - the film the state stands for (the camera's own settings for the same controls), what the
 * tables depend on, the grain numbers of the camera's FilmGrain for the photo's long side and how far they reach across a tile,
 * the look in the state's JSON, undo and «before», the copy's description.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class EditLookTest {

    static EditState withFilm(String stock) {
        EditState s = new EditState();
        s.film = stock;
        s.set(Param.FILM_AMOUNT, 80);
        s.set(Param.FILM_GRAIN, 120);
        s.set(Param.FILM_EXPOSURE, .7f);
        s.set(Param.FILM_CONTRAST, -30);
        s.set(Param.FILM_DIR, 40);
        s.set(Param.FILM_PRINT_EV, -.25f);
        s.filmPrint = "fujifilm_crystal_archive_typeii";
        s.filmFormat = "67";
        return s;
    }

    @Test
    public void theFilmIsTheCamerasSettingsForTheSameControls() {
        assertEquals(FilmSim.DEFAULT_FORMAT, EditState.DEFAULT_FILM_FORMAT);
        EditState s = withFilm("kodak_portra_400");
        FilmSim.Settings f = EditLook.film(s);
        assertNotNull(f);
        assertEquals("kodak_portra_400", f.stock.id);
        assertEquals(FilmSim.print("fujifilm_crystal_archive_typeii", f.stock).id, f.print.id);
        assertEquals("67", f.format.id);
        assertEquals(1.2f, f.grain, 1e-6f);
        assertEquals(.7f, f.exposure, 1e-6f);
        assertEquals(-.3f, f.tone, 1e-6f);
        assertEquals(.4f, f.couplers, 1e-6f);
        assertEquals(-.25f, f.printEv, 1e-6f);
        assertEquals(.8f, f.strength, 1e-6f);
        assertEquals("no halation, softness or diffusion on a finished picture", 0f, f.halation + f.softness, 0f);
        assertEquals("", f.diffusion);
        // the stock's own print for "" (as the camera's tray reads an unset print)
        s.filmPrint = "";
        assertEquals(FilmSim.print("", f.stock).id, EditLook.film(s).print.id);
        s.set(Param.FILM_AMOUNT, 0);
        assertNull("strength 0 is no film (FilmSim.current)", EditLook.film(s));
        s.set(Param.FILM_AMOUNT, 100);
        s.film = "no_such_stock";
        assertNull(EditLook.film(s));
        assertFalse(EditLook.on(new EditState()));
    }

    @Test
    public void theTablesDependOnTheColourOnly() {
        EditState lut = new EditState();
        lut.lut = FilmLooks.values()[0];
        lut.set(Param.LUT_AMOUNT, 40);
        String k = EditLook.tableKey(lut);
        assertTrue(EditLook.on(lut));
        assertEquals(.4f, EditLook.strength(lut), 1e-6f);
        lut.set(Param.LUT_AMOUNT, 90);
        assertEquals("a LUT's strength is the shader's mix", k, EditLook.tableKey(lut));
        lut.set(Param.LUT_AMOUNT, 0);
        assertFalse(EditLook.on(lut));

        EditState s = withFilm("kodak_gold_200");
        String film = EditLook.tableKey(s);
        s.set(Param.FILM_AMOUNT, 30);
        s.set(Param.FILM_GRAIN, 0);
        s.filmFormat = "16mm";
        assertEquals("the film's strength, grain and format are no table's", film, EditLook.tableKey(s));
        assertEquals(.3f, EditLook.strength(s), 1e-6f);
        assertNotEquals("but the look's key", EditLook.key(withFilm("kodak_gold_200")), EditLook.key(s));
        s.set(Param.FILM_EXPOSURE, -.5f);
        assertNotEquals(film, EditLook.tableKey(s));
        EditState s2 = withFilm("kodak_gold_200");
        s2.lut = FilmLooks.values()[1];
        assertNotEquals("a LUT on the print", film, EditLook.tableKey(s2));
        String withLut = EditLook.tableKey(s2);
        s2.set(Param.LUT_AMOUNT, 50);
        assertNotEquals("its strength is baked on the print", withLut, EditLook.tableKey(s2));
        assertEquals("the shader mixes the film's", .8f, EditLook.strength(s2), 1e-6f);
    }

    @Test
    public void grainIsTheCamerasFilmGrainForThePhotosLongSide() {
        EditState s = withFilm("kodak_portra_400");
        FilmSim.Settings f = EditLook.film(s);
        EditLook.Grain g = EditLook.grain(f, 8000);
        assertNotNull(g);
        assertTrue(g.reference);
        float um = f.pixelUm(8000);
        assertEquals(70f * 1000f / 8000f, um, 1e-4f);
        assertEquals(um, g.um, 0f);
        assertEquals("FilmGrain.runReference: blur", FilmSim.grainBlurUm(f.format) / um, g.blurPx, 0f);
        assertEquals("FilmGrain.runReference: scale", f.grainApplied() / um, g.scale, 0f);
        assertEquals((float) (Math.abs("kodak_portra_400".hashCode()) % 9973), g.seed, 0f);
        assertEquals((int) Math.ceil(3.0 * g.blurPx) + 1, EditLook.reach(g));
        // the preview (a 2048 px picture): the same film over fewer pixels, a finer blur and a stronger grain per pixel
        EditLook.Grain p = EditLook.grain(f, 2048);
        assertTrue(p.blurPx < g.blurPx && p.scale < g.scale);

        FilmSim.Stock bw = null;
        for (FilmSim.Stock st : FilmSim.STOCKS) if (st.spectral == null) bw = st;
        assertNotNull("our black-and-white model", bw);
        FilmSim.Settings b = EditLook.film(withFilm(bw.id));
        EditLook.Grain c = EditLook.grain(b, 6000);
        assertFalse(c.reference);
        float u2 = b.pixelUm(6000);
        assertEquals(FilmSim.classicGrainSigma(b, u2), c.sigma, 0f);
        assertEquals(FilmSim.classicGrainCell(b, u2), c.cell, 0f);
        assertEquals(b.stock.mono() ? 1f : .55f, c.sharedPart, 0f);
        assertEquals(0, EditLook.reach(c));

        s.filmFormat = "off";
        assertNull("no format, no grain", EditLook.grain(EditLook.film(s), 8000));
        s.filmFormat = "135";
        s.set(Param.FILM_GRAIN, 0);
        assertNull(EditLook.grain(EditLook.film(s), 8000));
        assertNull(EditLook.grain(null, 8000));
    }

    @Test
    public void theTilesOverlapCoversTheGrain() {
        // the export's tiles and the zoom's regions (EditZoomLoader: overlapPx + 2) read the noise beyond their edge as far as its
        // blur reaches: for every stock and format at the photo sizes the editor meets, the editor's own overlap already covers it
        for (FilmSim.Stock st : FilmSim.STOCKS) {
            for (FilmSim.Format fm : FilmSim.FORMATS) {
                EditState s = withFilm(st.id);
                s.filmFormat = fm.id;
                s.set(Param.FILM_GRAIN, 200);
                for (int side : new int[]{1000, 2048, 4000, 8192, 12000, 16384}) {
                    EditLook.Grain g = EditLook.grain(EditLook.film(s), side);
                    int reach = EditLook.reach(g);
                    assertTrue(st.id + " " + fm.id + " " + side + ": reach " + reach, reach <= EditMath.overlapPx(side));
                    assertEquals(EditMath.overlapPx(side), EditLook.overlap(s, side));
                }
            }
        }
    }

    @Test
    public void theLookIsToneInTheStateAndItsJson() {
        EditState s = withFilm("kodak_ektar_100");
        s.lut = "My look.cube";
        s.set(Param.LUT_AMOUNT, 65);
        assertFalse(s.isDefaultTone());
        assertFalse(s.isDefaultLook());
        EditState back = EditState.fromJson(s.toJson());
        assertNotNull(back);
        assertEquals(s, back);
        assertEquals(s.hashCode(), back.hashCode());
        assertEquals("My look.cube", back.lut);
        assertEquals("67", back.filmFormat);
        assertEquals(EditLook.tableKey(s), EditLook.tableKey(back));
        assertEquals("an unedited photo is still {\"v\":1}", "{\"v\":1}", new EditState().toJson());
        assertTrue(new EditState().isDefaultLook());
        // «before» shows no look; a style keeps it; reset takes it away; paste brings it
        assertFalse(EditLook.on(s.withNeutralTone()));
        assertEquals(s.lut, EditPresets.apply(s, 0, 1f).lut);
        EditState t = new EditState();
        t.copyTone(s);
        assertEquals(EditLook.tableKey(s), EditLook.tableKey(t));
        t.resetTone();
        assertTrue(t.isDefaultLook());
        assertFalse(EditLook.on(t));
        // the preview's tone key leaves the look out: a LUT does not rebuild the main pass's tables
        EditState u = new EditState();
        u.set(Param.EXPOSURE, .5f);
        EditState v = u.copy();
        v.lut = FilmLooks.values()[2];
        assertEquals(EditorView.toneKey(u), EditorView.toneKey(v));
        // a stored name never carries a path
        assertEquals("", EditState.name("../x.cube"));
        assertEquals("a.cube", EditState.name("a.cube"));
        EditState bad = EditState.fromJson("{\"v\":1,\"lk\":{\"l\":\"/sdcard/x.cube\",\"f\":\"kodak_gold_200\"}}");
        assertNotNull(bad);
        assertEquals("", bad.lut);
        assertEquals("kodak_gold_200", bad.film);
    }

    @Test
    public void appliedOnlyWithItsOwnTables() {
        EditState s = new EditState();
        s.lut = FilmLooks.values()[4];
        s.set(Param.LUT_AMOUNT, 70);
        FilmLut l = FilmLut.identity(5);
        EditLook.Tables t = new EditLook.Tables(EditLook.tableKey(s), tableOf(EditLook.tableKey(s), l), null);
        EditLook.Applied a = EditLook.applied(s, t);
        assertNotNull(a);
        assertSame(l, a.lut);
        assertEquals(.7f, a.strength, 1e-6f);
        assertNull(a.film);
        EditState other = s.copy();
        other.lut = FilmLooks.values()[0];
        assertNull("another LUT's tables are not applied", EditLook.applied(other, t));
        assertNull(EditLook.applied(new EditState(), t));
        assertNull(EditLook.applied(s, null));
    }

    @Test
    public void theCopyNamesItsLook() {
        EditState s = new EditState();
        assertNull(EditExporter.lookLine(s, null));
        s.lut = FilmLooks.values()[0];
        s.set(Param.LUT_AMOUNT, 45);
        FilmLut l = FilmLut.identity(3);
        EditLook.Tables t = new EditLook.Tables(EditLook.tableKey(s), tableOf(EditLook.tableKey(s), l), null);
        assertEquals("Edit look: LUT " + FilmLooks.values()[0] + " 45 %", EditExporter.lookLine(s, t));
        s.film = "kodak_portra_400";
        t = new EditLook.Tables(EditLook.tableKey(s), tableOf(EditLook.tableKey(s), l), null);
        String line = EditExporter.lookLine(s, t);
        assertTrue(line, line.startsWith("Edit look: LUT " + FilmLooks.values()[0] + " 45 %; film Kodak Portra 400"));
        assertTrue(line, line.endsWith("; on the finished colour"));
        assertEquals("a\n b", EditExporter.describe("a", "b"));
        assertEquals("b", EditExporter.describe(null, "b"));
        assertTrue(EditExporter.software().startsWith("SCAMERA"));
    }

    /** A FinishedLook.Table for a test (its constructor is the package's). */
    static com.particlesdevs.photoncamera.processing.color.FinishedLook.Table tableOf(String key, FilmLut l) {
        try {
            java.lang.reflect.Constructor<com.particlesdevs.photoncamera.processing.color.FinishedLook.Table> c =
                    com.particlesdevs.photoncamera.processing.color.FinishedLook.Table.class.getDeclaredConstructor(String.class, FilmLut.class, boolean.class);
            c.setAccessible(true);
            return c.newInstance(key, l, false);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
