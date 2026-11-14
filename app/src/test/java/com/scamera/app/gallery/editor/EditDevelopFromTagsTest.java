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
import android.view.View;
import android.widget.TextView;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.gallery.dng.DngFixtures;
import com.scamera.app.gallery.dng.DngRaw;
import com.scamera.app.processing.DevelopRecord;
import com.scamera.app.processing.opengl.postpipeline.DevelopRun;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.SettingsManager;
import com.scamera.app.ui.settings.DevelopCatalog;

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
import java.nio.ByteBuffer;
import java.nio.file.Files;

/**
 * P696 (owner: «В галерее не даёт обрабатывать равы, хотя это Рав из нашей камеры»): a DNG without the develop record gets the
 * camera's develop from its own tags (EditDevelop.fromTags) - a SCAMERA one by its Software, another app's Bayer DNG with its
 * colour matrix and as-shot white - with the camera's settings now; a DNG with its record keeps exactly that record; what the
 * developer cannot take (another app's DNG without colour tags, a mirrored orientation, a Quad mosaic) gets none, and the tab says
 * why.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class EditDevelopFromTagsTest {
    static final String OURS = "SCAMERA 0.98 (31254) 5b8d8d04";
    private Context context;
    private SettingsManager manager;
    private MockedStatic<ScameraApp> camera;
    private EditState state;
    private final java.util.List<File> files = new java.util.ArrayList<>();

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        manager = new SettingsManager(context);
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
        for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private File write(byte[] dng) throws Exception {
        File f = File.createTempFile("shot", ".dng");
        files.add(f);
        Files.write(f.toPath(), dng);
        return f;
    }

    /** A SCAMERA merged DNG of before P665: Software, the headroom x2 as BaselineExposure, the noise, exposure, shading. */
    static DngFixtures.Tags ours() {
        DngFixtures.Tags t = new DngFixtures.Tags();
        t.software = OURS;
        t.baselineExposure = 1;
        t.noiseProfile = new double[]{2e-4, 4e-7, 2e-4, 4e-7, 2e-4, 4e-7};
        t.iso = 50;
        t.exposureTime = .01;
        t.fNumber = 2.3;
        t.focalLength = 22.5;
        t.shadingMaps = true;
        return t;
    }

    @Test public void aRecordlessScameraDngGetsADevelop() throws Exception {
        manager.getDefaultPreferences().edit().putString("pref_scam_hybrid_why_sharp_gain", "1.6").commit();
        File f = write(DngFixtures.bayer(ours()));
        assertNull("no record in the file", DevelopRecord.read(f));
        EditDevelop d = EditDevelop.open("SCA_20261111_094907.dng", f);
        assertNotNull("a SCAMERA DNG without its record is developed from its tags", d);
        assertTrue(d.fromTags());
        DevelopRecord r = d.record;
        assertEquals(2f, r.headroom(), 1e-6);
        assertEquals(DngFixtures.W, r.meta.getJSONObject("dng").getInt("w"));
        assertEquals(DngFixtures.H, r.meta.getJSONObject("dng").getInt("h"));
        // the develop's Parameters: the DNG's grid, its white, ISO, exposure, the noise over the headroom, its shading map
        com.scamera.app.processing.render.Parameters p = DevelopRun.parameters(r, DngFixtures.W, DngFixtures.H);
        assertEquals(DngFixtures.W, p.rawSize.x);
        assertEquals(DngFixtures.H, p.rawSize.y);
        assertEquals(.5f, p.asShotNeutral[0], 1e-6);
        assertEquals(.7f, p.asShotNeutral[2], 1e-6);
        assertEquals(50, p.iso);
        assertEquals(.01, p.exposureTime, 1e-9);
        assertEquals(2.3f, p.fNumber, 1e-6);
        assertEquals(2e-4 * 2, p.noiseModel.rawModel[1].first, 1e-12);
        assertEquals(4e-7 * 4, p.noiseModel.rawModel[1].second, 1e-15);
        assertEquals(3, p.shadingGrid.x);
        assertEquals(2, p.shadingGrid.y);
        assertEquals(DngFixtures.Tags.shadingGain(3, 5), p.shadingMap[5 * 4 + 3], 0);
        // the rows start at the camera's settings now
        assertEquals("1.6", DevelopRun.shotValues(r).get("pref_scam_hybrid_why_sharp_gain"));
        assertEquals(0, p.rotationDeg);
    }

    @Test public void aDngWithItsRecordKeepsIt() throws Exception {
        DngFixtures.Tags t = ours();
        t.baselineExposure = 2; // x4 by the tag; the record says x2
        File f = write(DngFixtures.bayer(t));
        DevelopRecord.append(f, EditDevelopPanelTest.record());
        DevelopRecord stored = DevelopRecord.read(f);
        assertNotNull(stored);
        EditDevelop d = EditDevelop.open("shot.dng", f);
        assertNotNull(d);
        assertFalse("the camera's record, not one from the tags", d.fromTags());
        assertEquals(stored.meta.toString(), d.record.meta.toString());
        assertEquals(2f, d.record.headroom(), 0); // the record's headroom, not the tag's
        assertEquals("1.4", DevelopRun.shotValues(d.record).get("pref_scam_hybrid_why_sharp_gain"));
    }

    @Test public void anotherAppsDngNeedsItsColourTags() throws Exception {
        DngFixtures.Tags t = new DngFixtures.Tags();
        t.software = "Some RAW app 1.0";
        t.baselineExposure = 1; // its own exposure, not a headroom
        File plain = write(DngFixtures.bayer(t));
        assertNull("no colour matrix: no develop", EditDevelop.open("other.dng", plain));
        t.colorMatrix1 = new double[]{1.13, -.42, -.18, -.31, 1.29, .01, -.1, .33, .42};
        EditDevelop d = EditDevelop.open("other.dng", write(DngFixtures.bayer(t)));
        assertNotNull("a Bayer DNG with its colour matrix and as-shot white", d);
        assertTrue(d.fromTags());
        assertEquals(1f, d.record.headroom(), 0);
        com.scamera.app.processing.render.Parameters p = DevelopRun.parameters(d.record, DngFixtures.W, DngFixtures.H);
        assertEquals(com.scamera.app.processing.render.Parameters.ColourSource.DNG_FM.name(), p.colourSource);
        // the generic noise at the default ISO, the flat shading map
        assertEquals(100, p.iso);
        for (float v : p.shadingMap) assertEquals(1f, v, 0);
    }

    @Test public void whatTheDeveloperCannotTakeGetsNone() throws Exception {
        DngFixtures.Tags mirrored = ours();
        mirrored.orientation = 2;
        assertNull(EditDevelop.open("mirrored.dng", write(DngFixtures.bayer(mirrored))));
        DngFixtures.Tags turned = ours();
        turned.orientation = 6;
        EditDevelop d = EditDevelop.open("turned.dng", write(DngFixtures.bayer(turned)));
        assertNotNull(d);
        assertEquals(90, DevelopRun.parameters(d.record, DngFixtures.W, DngFixtures.H).rotationDeg);
        assertNull("not a DNG", EditDevelop.open("x.dng", write(new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0, 0, 0, 0, 0})));
        // a Quad / LinearRaw mosaic is refused by the tags alone
        DngRaw.Info info = DngRaw.probe(ByteBuffer.wrap(DngFixtures.bayer(ours())));
        assertNull(EditDevelop.refusal(info));
        info.cell = 2;
        assertNotNull(EditDevelop.refusal(info));
        info.cell = 1;
        info.kind = DngRaw.KIND_RGB;
        assertNotNull(EditDevelop.refusal(info));
    }

    private EditPanels panels(EditDevelop develop, EditSource source) {
        return new EditPanels(context, new EditPanels.Host() {
            @Override public EditState state() { return state; }
            @Override public void changed(boolean commit) { }
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

    @Test public void theTabOffersTheDevelopAndSaysTheSettingsAreTodays() throws Exception {
        manager.getDefaultPreferences().edit().putString("pref_scam_hybrid_why_sharp_gain", "1.6").commit();
        EditDevelop d = EditDevelop.open("SCA_20261111_094907.dng", write(DngFixtures.bayer(ours())));
        assertNotNull(d);
        Bitmap b = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888);
        EditSource s = EditSource.forTest(android.net.Uri.parse("content://x/3"), "SCA_20261111_094907.dng", b);
        s.route = EditSource.Route.DNG;
        EditPanels p = panels(d, s);
        View v = p.forTab(8);
        assertNotNull("the switch is offered", v.findViewWithTag("develop_switch"));
        TextView status = v.findViewWithTag("develop_status");
        assertTrue(status.getText().toString(), status.getText().toString().contains("текущими настройками камеры"));
        v.findViewWithTag("develop_switch").performClick();
        assertTrue(state.develop);
        p.refresh();
        status = v.findViewWithTag("develop_status");
        assertTrue(status.getText().toString(), status.getText().toString().contains("текущие настройки камеры"));
        // Резкость starts at the setting now
        v.findViewWithTag("develop_group_" + DevelopCatalog.SHARP).performClick();
        EditSlider gain = v.findViewWithTag("develop_row_pref_scam_hybrid_why_sharp_gain");
        assertNotNull(gain);
        assertEquals(1.6f, gain.value(), 1e-4);
    }
}
