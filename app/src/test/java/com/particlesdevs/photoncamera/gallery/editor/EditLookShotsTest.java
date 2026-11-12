package com.particlesdevs.photoncamera.gallery.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.particlesdevs.photoncamera.gallery.ui.GalleryUi;
import com.particlesdevs.photoncamera.processing.color.FilmLooks;
import com.particlesdevs.photoncamera.processing.color.FilmSim;
import com.particlesdevs.photoncamera.processing.color.FilmSpectral;
import com.particlesdevs.photoncamera.ui.settings.custompreferences.FilmLutPreference;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;

/**
 * P619: the editor's «LUT» tab as built (EditPanels.look) on the editor's #1C1C1E sheet at 390 dp (2x): the LUT section with its
 * cards (the photo through every camera LUT: «Нет» and our five looks first, as the tray), the film section with a stock chosen and
 * its controls (strength, print and format chips, grain, exposure, contrast, couplers, print exposure). Written to
 * build/reports/edit-look/ (or $EDIT_SHOTS_DIR); the cards' picture is the settings' drawn landscape, or $EDIT_SHOTS_PHOTO.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class EditLookShotsTest {
    private Context context;
    private EditState state;
    private int[] base;
    private int bw, bh;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        File assets = new File("src/main/assets/film");
        if (!assets.isDirectory()) assets = new File("app/src/main/assets/film");
        final File dir = assets;
        java.lang.reflect.Method m = FilmSpectral.class.getDeclaredMethod("source", FilmSpectral.Source.class);
        m.setAccessible(true);
        m.invoke(null, (FilmSpectral.Source) name -> new java.io.FileInputStream(new File(dir, name)));
        state = new EditState();
        String photo = System.getenv("EDIT_SHOTS_PHOTO");
        Bitmap b = photo != null && new File(photo).isFile() ? BitmapFactory.decodeFile(photo) : null;
        if (b != null) {
            float k = EditLookThumbs.SIDE_PX / (float) Math.max(b.getWidth(), b.getHeight());
            Bitmap s = Bitmap.createScaledBitmap(b, Math.max(8, Math.round(b.getWidth() * k)), Math.max(8, Math.round(b.getHeight() * k)), true);
            bw = s.getWidth();
            bh = s.getHeight();
            base = new int[bw * bh];
            s.getPixels(base, 0, bw, 0, 0, bw, bh);
        } else {
            bw = 160;
            bh = 120;
            base = FilmLutPreference.pixels(context, 0, bw, bh, "");
        }
    }

    private EditPanels panels() {
        return new EditPanels(context, new EditPanels.Host() {
            @Override public EditState state() { return state; }
            @Override public void changed(boolean commit) { }
            @Override public EditSource source() { return null; }
            @Override public void rotate() { }
            @Override public void flip() { }
            @Override public void resetCrop() { }
            @Override public void aspect(float ratio) { }
            @Override public void straighten(float degrees, boolean commit) { }
            @Override public void style(int index, float amount, boolean commit) { }
            @Override public void lookThumbnail(EditPanels.ThumbSink sink) { sink.base(base, bw, bh); }
        });
    }

    /** Waits for the cards' pictures (drawn on their worker, shown on the main thread), at most two minutes. */
    private static void settle(View panel) throws InterruptedException {
        LinearLayout row = cards(panel);
        for (int i = 0; i < 480; i++) {
            Thread.sleep(250);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
            boolean all = true;
            for (int k = 0; k < row.getChildCount(); k++)
                all &= ((ImageView) ((ViewGroup) row.getChildAt(k)).getChildAt(0)).getDrawable() != null;
            if (all) break;
        }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
    }

    private static File dir() {
        String d = System.getenv("EDIT_SHOTS_DIR");
        File f = new File(d != null ? d : "build/reports/edit-look");
        f.mkdirs();
        return f;
    }

    private static void shot(View panel, String name) throws Exception {
        int w = Math.round(390 * 2f);
        if (panel.getParent() instanceof ViewGroup) ((ViewGroup) panel.getParent()).removeView(panel);
        FrameLayout sheet = new FrameLayout(panel.getContext());
        sheet.setBackground(GalleryUi.card(panel.getContext(), GalleryUi.SHEET, 30));
        sheet.setPadding(0, GalleryUi.dp(panel.getContext(), 8), 0, GalleryUi.dp(panel.getContext(), 12));
        sheet.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheet.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        sheet.layout(0, 0, w, sheet.getMeasuredHeight());
        Bitmap bitmap = Bitmap.createBitmap(w, sheet.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFF000000);
        sheet.draw(new Canvas(bitmap));
        try (FileOutputStream out = new FileOutputStream(new File(dir(), name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    /** The cards of the panel's first card row. */
    private static LinearLayout cards(View panel) {
        LinearLayout body = (LinearLayout) ((LinearLayout) panel).getChildAt(1);
        return (LinearLayout) ((HorizontalScrollView) body.getChildAt(0)).getChildAt(0);
    }

    @Test public void theLutSection() throws Exception {
        state.lut = FilmLooks.values()[3];
        state.set(EditState.Param.LUT_AMOUNT, 80);
        EditPanels p = panels();
        View v = p.look();
        settle(v);
        LinearLayout row = cards(v);
        // «Нет», our five looks (no .cube in this test's folder) - the camera tray's list
        assertEquals(1 + FilmLooks.values().length, row.getChildCount());
        assertTrue("the chosen LUT's card is the chosen one", row.getChildAt(4).isSelected());
        for (int i = 0; i < row.getChildCount(); i++)
            assertNotNull("card " + i + " shows the photo", ((ImageView) ((ViewGroup) row.getChildAt(i)).getChildAt(0)).getDrawable());
        shot(v, "edit_lut_ru");
    }

    @Test public void theFilmSection() throws Exception {
        state.film = "kodak_portra_400";
        state.set(EditState.Param.FILM_GRAIN, 60);
        state.filmFormat = "67";
        EditPanels p = panels();
        p.lookSection = 1;
        View v = p.look();
        settle(v);
        LinearLayout row = cards(v);
        assertEquals(1 + FilmSim.STOCKS.length, row.getChildCount());
        int chosen = -1;
        for (int i = 0; i < row.getChildCount(); i++) if (row.getChildAt(i).isSelected()) chosen = i;
        assertEquals(1 + java.util.Arrays.asList(FilmSim.STOCKS).indexOf(FilmSim.stock("kodak_portra_400")), chosen);
        shot(v, "edit_film_ru");
        // the cards scrolled to the chosen stock, as the tray: a second shot with the row moved there
        ((HorizontalScrollView) row.getParent()).scrollTo(row.getChildAt(chosen).getLeft() - 40, 0);
        shot(v, "edit_film_ru_scrolled");
    }

    @Test @Config(qualifiers = "en-w390dp-h844dp-xhdpi") public void theFilmSectionInEnglish() throws Exception {
        FilmSim.Stock bwStock = null;
        for (FilmSim.Stock st : FilmSim.STOCKS) if (st.mono()) bwStock = st;
        assertNotNull(bwStock);
        state.film = bwStock.id;
        EditPanels p = panels();
        p.lookSection = 1;
        View v = p.look();
        settle(v);
        shot(v, "edit_film_en_bw");
    }

    /**
     * With $EDIT_SHOTS_DIR: the tables and grain numbers of a few looks for the before / after renders (tools-side, the editor's own
     * shaders on the host GPU): the first-phase display tables (the JVM has no reference engine), float32 red fastest.
     */
    @Test public void tablesForTheBeforeAfter() throws Exception {
        String out = System.getenv("EDIT_SHOTS_DIR");
        if (out == null) return;
        Object[][] looks = {
                {"lut_cine500", FilmLooks.values()[3], 100f, "", "135", 0f, 0f},
                {"film_portra400", "", 0f, "kodak_portra_400", "135", 100f, 100f},
                {"film_gold200_warm400", FilmLooks.values()[0], 50f, "kodak_gold_200", "645", 80f, 60f},
                {"film_bw", "", 0f, null, "135", 100f, 100f},
        };
        for (FilmSim.Stock st : FilmSim.STOCKS) if (st.mono() && looks[3][3] == null) looks[3][3] = st.id;
        StringBuilder index = new StringBuilder("{");
        for (Object[] l : looks) {
            EditState st = new EditState();
            st.lut = (String) l[1];
            st.set(EditState.Param.LUT_AMOUNT, (Float) l[2]);
            st.film = (String) l[3];
            st.filmFormat = (String) l[4];
            st.set(EditState.Param.FILM_AMOUNT, st.film.isEmpty() ? 100f : (Float) l[5]);
            st.set(EditState.Param.FILM_GRAIN, (Float) l[6]);
            java.util.List<com.particlesdevs.photoncamera.processing.color.FinishedLook.Table> got = new java.util.ArrayList<>();
            FilmSim.Settings f = EditLook.film(st);
            com.particlesdevs.photoncamera.processing.color.FinishedLook.load(context, f, EditLook.lut(st), EditLook.lutStrength(st), () -> true, got::add);
            com.particlesdevs.photoncamera.processing.color.FilmLut t = got.get(0).lut;
            write(new File(out, l[0] + ".lut.f32"), t.rgb);
            index.append(index.length() > 1 ? "," : "").append("\n\"").append(l[0]).append("\": {\"size\": ").append(t.size)
                    .append(", \"min\": [").append(t.domainMin[0]).append(",").append(t.domainMin[1]).append(",").append(t.domainMin[2])
                    .append("], \"max\": [").append(t.domainMax[0]).append(",").append(t.domainMax[1]).append(",").append(t.domainMax[2])
                    .append("], \"strength\": ").append(EditLook.strength(st)).append(", \"name\": \"")
                    .append(f == null ? "LUT " + EditPanels.lutName(context, st.lut) : f.stock.brand + " " + f.stock.name
                            + (st.lut.isEmpty() ? "" : " + " + EditPanels.lutName(context, st.lut) + " " + Math.round(st.get(EditState.Param.LUT_AMOUNT)) + " %")
                            + ", " + f.format.name + ", grain " + Math.round(f.grain * 100) + " %, strength " + Math.round(f.strength * 100) + " %")
                    .append("\", \"grain\": {");
            if (EditLook.wantsGrainLut(f)) {
                com.particlesdevs.photoncamera.processing.color.FilmLut g = FilmSim.grainLut(f, EditLook.GRAIN_LUT);
                write(new File(out, l[0] + ".grain.f32"), g.rgb);
            }
            boolean first = true;
            for (int side : new int[]{4096, 2048, 1536}) {
                EditLook.Grain g = EditLook.grain(f, side);
                if (g == null) continue;
                index.append(first ? "" : ",").append("\"").append(side).append("\": {\"reference\": ").append(g.reference)
                        .append(", \"blurPx\": ").append(g.blurPx).append(", \"scale\": ").append(g.scale).append(", \"sigma\": ").append(g.sigma)
                        .append(", \"cell\": ").append(g.cell).append(", \"sharedPart\": ").append(g.sharedPart).append(", \"slide\": ")
                        .append(g.slide ? 1 : 0).append(", \"mono\": ").append(g.mono ? 1 : 0).append(", \"seed\": ").append(g.seed).append("}");
                first = false;
            }
            index.append("}}");
        }
        index.append("\n}\n");
        try (FileOutputStream o = new FileOutputStream(new File(out, "looks.json"))) {
            o.write(index.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static void write(File f, float[] v) throws Exception {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(v.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float x : v) b.putFloat(x);
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(b.array());
        }
    }
}
