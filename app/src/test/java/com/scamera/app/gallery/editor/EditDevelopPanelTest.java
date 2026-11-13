package com.scamera.app.gallery.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.gallery.ui.GalleryUi;
import com.scamera.app.processing.DevelopRecord;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.SettingsManager;
import com.scamera.app.ui.settings.DevelopCatalog;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

/**
 * P665: the editor's «Проявка» tab as built (EditDevelopPanel) for a DNG of ours with its record and for a finished JPEG: the
 * switch, the camera's groups, the rows starting at the shot's values, a change stored over the shot's (and taken back at the
 * shot's value), the greyed rows with why. Pictures of the panels (390 dp, 2x) go to build/reports/edit-develop/ or
 * $EDIT_SHOTS_DIR.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class EditDevelopPanelTest {
    private Context context;
    private MockedStatic<ScameraApp> camera;
    private EditState state;
    private int commits;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        final SettingsManager manager = new SettingsManager(context);
        manager.getDefaultPreferences().edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        camera.when(ScameraApp::getSettings).thenReturn(new com.scamera.app.api.Settings());
        final ScameraApp app = mock(ScameraApp.class, RETURNS_DEEP_STUBS);
        when(app.getSettingsManager()).thenReturn(manager);
        camera.when(() -> ScameraApp.getInstance(any(Context.class))).thenReturn(app);
        PreferenceKeys.initialise(manager);
        state = new EditState();
        EditDevelopPanel.openGroup = DevelopCatalog.TONE;
    }

    @After public void tearDown() {
        if (camera != null) camera.close();
    }

    static DevelopRecord record() throws Exception {
        DevelopRecord r = new DevelopRecord();
        r.meta.put("v", DevelopRecord.VERSION);
        r.meta.put("params", new JSONObject().put("rawSize", new JSONObject().put("x", 4096).put("y", 3072)));
        r.meta.put("prefs", new JSONObject()
                .put("pref_scam_hybrid_why_sharp_gain", new JSONArray().put("s").put("1.4"))
                .put("pref_scam_hybrid_tone_version", new JSONArray().put("s").put("v2"))
                .put("pref_scam_hybrid_denoise", new JSONArray().put("b").put(true)));
        r.meta.put("worker", new DevelopRecord.Worker().toJson());
        r.meta.put("dng", new JSONObject().put("headroom", 2.0).put("w", 4096).put("h", 3072));
        return r;
    }

    private EditPanels panels(EditDevelop develop, EditSource source) {
        return new EditPanels(context, new EditPanels.Host() {
            @Override public EditState state() { return state; }
            @Override public void changed(boolean commit) { if (commit) commits++; }
            @Override public EditSource source() { return source; }
            @Override public void rotate() { }
            @Override public void flip() { }
            @Override public void resetCrop() { }
            @Override public void aspect(float ratio) { }
            @Override public void straighten(float degrees, boolean commit) { }
            @Override public void style(int index, float amount, boolean commit) { }
            @Override public void lookThumbnail(EditPanels.ThumbSink sink) { }
            @Override public EditDevelop develop() { return develop; }
        });
    }

    private static File dir() {
        String d = System.getenv("EDIT_SHOTS_DIR");
        File f = new File(d != null ? d : "build/reports/edit-develop");
        //noinspection ResultOfMethodCallIgnored
        f.mkdirs();
        return f;
    }

    /** The panel on the editor's sheet, {@code maxDp} tall at most (the tab's visible part), as a PNG. */
    private static void shot(View panel, String name, int maxDp) throws Exception {
        int w = Math.round(390 * 2f);
        if (panel.getParent() instanceof ViewGroup) ((ViewGroup) panel.getParent()).removeView(panel);
        FrameLayout sheet = new FrameLayout(panel.getContext());
        sheet.setBackground(GalleryUi.card(panel.getContext(), GalleryUi.SHEET, 30));
        sheet.setPadding(0, GalleryUi.dp(panel.getContext(), 8), 0, GalleryUi.dp(panel.getContext(), 12));
        sheet.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheet.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = Math.min(sheet.getMeasuredHeight(), maxDp * 2);
        sheet.layout(0, 0, w, sheet.getMeasuredHeight());
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFF000000);
        sheet.draw(new Canvas(bitmap));
        try (FileOutputStream out = new FileOutputStream(new File(dir(), name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    private static EditSource dngSource() {
        Bitmap b = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888);
        EditSource s = EditSource.forTest(android.net.Uri.parse("content://x/1"), "shot.dng", b);
        s.route = EditSource.Route.DNG;
        return s;
    }

    @Test public void ourDngOffersEveryGroupFromTheShotsValues() throws Exception {
        EditDevelop d = EditDevelop.of(record(), new File("shot.dng"));
        EditPanels p = panels(d, dngSource());
        View v = p.forTab(8);
        assertNotNull(v.findViewWithTag("develop_switch"));
        for (String id : new String[]{DevelopCatalog.TONE, DevelopCatalog.SHARP, DevelopCatalog.NOISE, DevelopCatalog.COLOUR,
                DevelopCatalog.FILM, DevelopCatalog.LUT, DevelopCatalog.MERGE})
            assertNotNull(id, v.findViewWithTag("develop_group_" + id));
        // switched off: the rows wait for the switch (greyed)
        View tone = v.findViewWithTag("develop_row_pref_scam_hybrid_tone_version");
        assertNotNull(tone);
        assertTrue(tone.getAlpha() < 1f);
        shot(v, "develop_off_ru", 700);
        // on: the rows act
        v.findViewWithTag("develop_switch").performClick();
        assertTrue(state.develop);
        assertEquals(1, commits);
        p.refresh();
        tone = v.findViewWithTag("develop_row_pref_scam_hybrid_tone_version");
        assertEquals(1f, tone.getAlpha(), 0);
        shot(v, "develop_tone_ru", 1400);
        // Резкость: the shot's 1.4 is the slider's start and default
        v.findViewWithTag("develop_group_" + DevelopCatalog.SHARP).performClick();
        EditSlider gain = v.findViewWithTag("develop_row_pref_scam_hybrid_why_sharp_gain");
        assertNotNull(gain);
        assertEquals(1.4f, gain.value(), 1e-4);
        shot(v, "develop_sharp_ru", 1400);
        // Шумоподавление and Плёнка, Склейка greyed
        v.findViewWithTag("develop_group_" + DevelopCatalog.NOISE).performClick();
        shot(v, "develop_noise_ru", 1400);
        v.findViewWithTag("develop_group_" + DevelopCatalog.FILM).performClick();
        assertNotNull(v.findViewWithTag("develop_row_" + com.scamera.app.processing.color.FilmSim.HALATION_KEY));
        shot(v, "develop_film_ru", 1400);
        v.findViewWithTag("develop_group_" + DevelopCatalog.MERGE).performClick();
        View cdm = v.findViewWithTag("develop_row_pref_scam_hybrid_cdm");
        assertNotNull(cdm);
        assertTrue("the merge's rows are greyed", cdm.getAlpha() < 1f);
        shot(v, "develop_merge_ru", 900);
    }

    @Test public void aChangeIsKeptOverTheShotsAndTakenBackAtIt() throws Exception {
        EditDevelop d = EditDevelop.of(record(), new File("shot.dng"));
        state.develop = true;
        EditPanels p = panels(d, dngSource());
        View v = p.forTab(8);
        // the shot was taken with the denoise on: switching it off is a change, on again is none
        EditDevelopPanel.openGroup = DevelopCatalog.NOISE;
        v = p.forTab(8);
        View dn = v.findViewWithTag("develop_row_pref_scam_hybrid_denoise");
        assertNotNull(dn);
        dn.performClick();
        assertEquals(Boolean.FALSE, state.devChanges.get("pref_scam_hybrid_denoise"));
        dn = v.findViewWithTag("develop_row_pref_scam_hybrid_denoise");
        dn.performClick();
        assertFalse(state.devChanges.containsKey("pref_scam_hybrid_denoise"));
        // a list: the tone version
        EditDevelopPanel.openGroup = DevelopCatalog.TONE;
        v = p.forTab(8);
        assertNotNull(v.findViewWithTag("develop_row_pref_scam_hybrid_tone_version"));
    }

    @Test public void aFinishedPictureSaysWhyAndGreysTheRows() throws Exception {
        Bitmap b = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888);
        EditPanels p = panels(null, EditSource.forTest(android.net.Uri.parse("content://x/2"), "photo.jpg", b));
        View v = p.forTab(8);
        assertNull(v.findViewWithTag("develop_switch"));
        TextView status = v.findViewWithTag("develop_status");
        assertTrue(status.getText().toString().contains("Готовый снимок"));
        View tone = v.findViewWithTag("develop_row_pref_scam_hybrid_tone_version");
        assertNotNull(tone);
        assertTrue(tone.getAlpha() < 1f);
        shot(v, "develop_jpeg_ru", 700);
        // a DNG without the record says so
        p = panels(null, dngSource());
        v = p.forTab(8);
        status = v.findViewWithTag("develop_status");
        assertTrue(status.getText().toString().contains("нет записи обработки"));
    }
}
