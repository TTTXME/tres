package com.scamera.app.gallery.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.scamera.app.gallery.dng.DngRaw;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * P665 «Проявка»: the DNG's RGB the develop gives the camera's pipeline (the preview's 2x2 cells, the copy's Malvar-He-Cutler
 * demosaic, times the headroom, as floats or halves) and the edit's state with the develop block (JSON version 2; version 1 states
 * load as before; undo, reset, copy / paste, «before»).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class EditDevelopTest {
    /** A mosaic of {@code rgb(x, y)} sampled as DngRaw's {@code phase} says. */
    static float[] mosaic(int w, int h, int phase, java.util.function.BiFunction<Integer, Integer, float[]> rgb) {
        float[] m = new float[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) m[y * w + x] = rgb.apply(x, y)[EditDevelop.colour(phase, x, y)];
        return m;
    }

    @Test public void phasesAreDngRaws() {
        assertEquals(0, EditDevelop.colour(DngRaw.RGGB, 0, 0));
        assertEquals(2, EditDevelop.colour(DngRaw.RGGB, 1, 1));
        assertEquals(0, EditDevelop.colour(DngRaw.GRBG, 1, 0));
        assertEquals(2, EditDevelop.colour(DngRaw.GRBG, 0, 1));
        assertEquals(2, EditDevelop.colour(DngRaw.GBRG, 1, 0));
        assertEquals(0, EditDevelop.colour(DngRaw.GBRG, 0, 1));
        assertEquals(2, EditDevelop.colour(DngRaw.BGGR, 0, 0));
        assertEquals(0, EditDevelop.colour(DngRaw.BGGR, 1, 1));
        assertEquals(1, EditDevelop.colour(DngRaw.BGGR, 1, 0));
    }

    @Test public void thePreviewsCellsAreTheirColoursTimesTheHeadroom() {
        for (int phase = 0; phase < 4; phase++) {
            float[] m = mosaic(8, 6, phase, (x, y) -> new float[]{.2f, .3f + (x & 1) * .02f, .1f});
            float[] rgb = EditDevelop.superpixel(m, 8, 6, phase, 4f);
            assertEquals(4 * 3 * 3, rgb.length);
            for (int i = 0; i < rgb.length; i += 3) {
                assertEquals(.8f, rgb[i], 1e-6);
                assertEquals((.3f + .32f) / 2 * 4f, rgb[i + 1], 1e-6); // the cell's two greens averaged
                assertEquals(.4f, rgb[i + 2], 1e-6);
            }
        }
    }

    @Test public void theCopysDemosaicIsExactOnLinearLight() {
        final int w = 24, h = 18;
        for (int phase = 0; phase < 4; phase++) {
            java.util.function.BiFunction<Integer, Integer, float[]> field =
                    (x, y) -> new float[]{.1f + .01f * x + .005f * y, .2f + .004f * x + .007f * y, .3f - .003f * x + .002f * y};
            float[] m = mosaic(w, h, phase, field);
            FloatBuffer out = ByteBuffer.allocateDirect(w * h * 12).order(ByteOrder.nativeOrder()).asFloatBuffer();
            EditDevelop.mhc(m, w, h, phase, 2f, out, null);
            for (int y = 2; y < h - 2; y++)
                for (int x = 2; x < w - 2; x++) {
                    float[] want = field.apply(x, y);
                    for (int c = 0; c < 3; c++)
                        assertEquals("phase " + phase + " at " + x + "," + y + " c" + c, want[c] * 2f, out.get((y * w + x) * 3 + c), 1e-5);
                }
            // the borders mirror: a flat colour comes back everywhere
            float[] flat = mosaic(w, h, phase, (x, y) -> new float[]{.25f, .5f, .125f});
            EditDevelop.mhc(flat, w, h, phase, 1f, out, null);
            for (int i = 0; i < w * h; i++) {
                assertEquals(.25f, out.get(3 * i), 1e-6);
                assertEquals(.5f, out.get(3 * i + 1), 1e-6);
                assertEquals(.125f, out.get(3 * i + 2), 1e-6);
            }
        }
    }

    @Test public void halvesAreIeee() {
        assertEquals(0x3C00, EditDevelop.toHalf(1f) & 0xFFFF);
        assertEquals(0x0000, EditDevelop.toHalf(0f) & 0xFFFF);
        assertEquals(0x3555, EditDevelop.toHalf(1f / 3f) & 0xFFFF);
        assertEquals(0x7BFF, EditDevelop.toHalf(65504f) & 0xFFFF);
        assertEquals(0x7C00, EditDevelop.toHalf(1e6f) & 0xFFFF);
        assertEquals(0x0001, EditDevelop.toHalf(5.96e-8f) & 0xFFFF);
        assertEquals(0x4900, EditDevelop.toHalf(10f) & 0xFFFF);
        // the MHC into halves = the floats rounded
        float[] m = mosaic(8, 8, DngRaw.RGGB, (x, y) -> new float[]{.25f, .5f, 3f});
        java.nio.ShortBuffer half = ByteBuffer.allocateDirect(8 * 8 * 6).order(ByteOrder.nativeOrder()).asShortBuffer();
        EditDevelop.mhc(m, 8, 8, DngRaw.RGGB, 2f, null, half);
        assertEquals(EditDevelop.toHalf(.5f), half.get(0));
        assertEquals(EditDevelop.toHalf(1f), half.get(1));
        assertEquals(EditDevelop.toHalf(6f), half.get(2));
    }

    @Test public void thePreviewBinKeepsItUnder2048() {
        assertEquals(1, EditDevelop.binFor(new int[]{4096, 3072}));
        assertEquals(2, EditDevelop.binFor(new int[]{8160, 6144}));
        assertEquals(4, EditDevelop.binFor(new int[]{16320, 12288}));
        assertEquals(1, EditDevelop.binFor(new int[]{1000, 800}));
    }

    // ---------------------------------------------------------------- the state

    @Test public void anUneditedStateStaysVersionOne() throws Exception {
        assertEquals("{\"v\":1}", new EditState().toJson());
        EditState s = new EditState();
        s.set(EditState.Param.EXPOSURE, .5f);
        assertEquals(1, new JSONObject(s.toJson()).getInt("v"));
    }

    @Test public void theDevelopBlockRoundTripsAsVersionTwo() throws Exception {
        EditState s = new EditState();
        s.develop = true;
        s.devChanges.put("pref_scam_hybrid_why_sharp_gain", "1.5");
        s.devChanges.put("pref_scam_hybrid_denoise", false);
        s.devChanges.put("pref_film_sim_stock", "kodak_portra_400");
        String json = s.toJson();
        JSONObject o = new JSONObject(json);
        assertEquals(2, o.getInt("v"));
        assertTrue(o.getJSONObject("dv").getBoolean("on"));
        EditState back = EditState.fromJson(json);
        assertNotNull(back);
        assertEquals(s, back);
        assertEquals(s.hashCode(), back.hashCode());
        assertEquals(Boolean.FALSE, back.devChanges.get("pref_scam_hybrid_denoise"));
        assertEquals("1.5", back.devChanges.get("pref_scam_hybrid_why_sharp_gain"));
        // a later version is refused, not half read; a damaged key or value is dropped
        assertNull(EditState.fromJson(json.replace("\"v\":2", "\"v\":3")));
        EditState bad = EditState.fromJson("{\"v\":2,\"dv\":{\"on\":true,\"s\":{\"../x\":\"1\",\"pref_ok\":[1],\"pref_fine\":\"2\"}}}");
        assertNotNull(bad);
        assertEquals(1, bad.devChanges.size());
        assertEquals("2", bad.devChanges.get("pref_fine"));
    }

    @Test public void versionOneEditsStillLoad() {
        EditState old = EditState.fromJson("{\"v\":1,\"p\":{\"exposure\":0.5},\"lk\":{\"f\":\"kodak_portra_400\"}}");
        assertNotNull(old);
        assertEquals(.5f, old.get(EditState.Param.EXPOSURE), 0);
        assertEquals("kodak_portra_400", old.film);
        assertFalse(old.develop);
        assertTrue(old.devChanges.isEmpty());
    }

    @Test public void undoResetPasteAndBeforeTreatItAsTone() {
        EditState s = new EditState();
        s.develop = true;
        s.devChanges.put("pref_scam_hybrid_why_ae_target", "0.3");
        assertFalse(s.isDefaultTone());
        assertFalse(s.isDefault());
        EditState copy = s.copy();
        assertEquals(s, copy);
        copy.devChanges.put("pref_scam_hybrid_why_ae_target", "0.4");
        assertEquals("the copy is its own", "0.3", s.devChanges.get("pref_scam_hybrid_why_ae_target"));
        EditState paste = new EditState();
        paste.copyTone(s);
        assertTrue(paste.develop);
        assertEquals(s.devChanges, paste.devChanges);
        // «before»: the developed picture without the editor's sliders (the develop is the source)
        s.set(EditState.Param.CONTRAST, 30);
        EditState before = s.withNeutralTone();
        assertTrue(before.develop);
        assertEquals(s.devChanges, before.devChanges);
        assertEquals(0f, before.get(EditState.Param.CONTRAST), 0);
        s.resetTone();
        assertFalse(s.develop);
        assertTrue(s.devChanges.isEmpty());
        assertTrue(s.isDefaultTone());
        // the history keeps the develop's steps
        EditHistory h = new EditHistory(new EditState());
        EditState a = new EditState();
        a.develop = true;
        h.commit(a);
        EditState b = a.copy();
        b.devChanges.put("pref_scam_hybrid_why_sharp_gain", "2");
        h.commit(b);
        assertEquals(a, h.undo());
        assertEquals(b, h.redo());
    }

    @Test public void theCopysDescriptionSaysItWasDeveloped() {
        EditState s = new EditState();
        s.develop = true;
        assertNull(EditExporter.describe(fakeSource(EditSource.Route.JPEG), s, null));
        assertEquals("look", EditExporter.describe(fakeSource(EditSource.Route.JPEG), s, "look"));
        assertEquals("Developed by the camera's pipeline with the shot's settings",
                EditExporter.describe(fakeSource(EditSource.Route.DEVELOP), s, null));
        s.devChanges.put("pref_a", "1");
        assertEquals("Developed by the camera's pipeline; changed: pref_a=1; look",
                EditExporter.describe(fakeSource(EditSource.Route.DEVELOP), s, "look"));
    }

    static EditSource fakeSource(EditSource.Route route) {
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(4, 3, android.graphics.Bitmap.Config.ARGB_8888);
        EditSource raw = EditSource.forTest(android.net.Uri.parse("content://x/1"), "a.dng", b);
        if (route != EditSource.Route.DEVELOP) {
            raw.route = route;
            return raw;
        }
        return EditSource.developed(raw, b, 8, 6, () -> b);
    }
}
