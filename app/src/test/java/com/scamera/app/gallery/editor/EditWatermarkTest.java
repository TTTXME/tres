package com.scamera.app.gallery.editor;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.exifinterface.media.ExifInterface;

import com.scamera.app.gallery.ui.GalleryUi;
import com.scamera.app.processing.watermark.PhotoWatermark;
import com.scamera.app.processing.watermark.WatermarkFonts;
import com.scamera.app.processing.watermark.WatermarkPainter;
import com.scamera.app.processing.watermark.WatermarkPatch;
import com.scamera.app.processing.watermark.WatermarkSpec;
import com.scamera.app.processing.watermark.WatermarkStore;
import com.scamera.app.processing.watermark.WatermarkText;
import com.scamera.app.ui.controls.IosSegmented;
import com.scamera.app.ui.settings.custompreferences.FilmLutPreference;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * P633: the editor's «Знак» tab end to end on the host - our photo shot with the watermark (a JPEG with its record) opens with the
 * record offered: the preview and the full-resolution regions with «Убрать» are the camera's unwatermarked render inside the record
 * (exact at 100 %) and the photo's own JPEG outside; the state's choices (as is / remove / change / add) through JSON, undo and paste;
 * the panel for a photo with a record and for one without (greyed «Убрать», the reason, «Добавить» from the camera's settings), the
 * fields this photo's EXIF lacks greyed; the copy's watermark drawn by the camera's code with its record after the EXIF, so the copy
 * opens with its watermark removable again; the preview's plan = the copy's. Screenshots to $WM_SHOTS_DIR (or build/reports/edit-watermark).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class EditWatermarkTest {
    private Context context;
    private EditState state;
    private final List<Boolean> commits = new ArrayList<>();
    private EditSource source;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        state = new EditState();
        commits.clear();
    }

    // ───── photos ─────

    static int[] words(Bitmap b) {
        int[] out = new int[b.getWidth() * b.getHeight()];
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(out.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.copyPixelsToBuffer(buf);
        buf.rewind();
        buf.asIntBuffer().get(out);
        return out;
    }

    /** The settings' sample landscape at {@code w x h} (the camera's render stand-in), mutable ARGB_8888 sRGB. */
    Bitmap render(int w, int h) {
        int[] px = FilmLutPreference.pixels(context, 0, w, h, "");
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.setPixels(px, 0, w, 0, 0, w, h);
        // a little grain, as a photo has (and so the record is not a flat picture)
        java.util.Random r = new java.util.Random(3);
        int[] all = new int[w * h];
        b.getPixels(all, 0, w, 0, 0, w, h);
        for (int i = 0; i < all.length; i++) {
            int n = r.nextInt(5) - 2, c = all[i];
            int R = Math.max(0, Math.min(255, ((c >> 16) & 255) + n)), G = Math.max(0, Math.min(255, ((c >> 8) & 255) + n)),
                    B = Math.max(0, Math.min(255, (c & 255) + n));
            all[i] = 0xFF000000 | R << 16 | G << 8 | B;
        }
        b.setPixels(all, 0, w, 0, 0, w, h);
        return b;
    }

    static WatermarkText.Info shotInfo() {
        WatermarkText.Info i = WatermarkText.Info.sample("vivo X200 Ultra", 1_794_405_900_000L);
        i.zone = java.util.TimeZone.getTimeZone("GMT+03:00");
        return i;
    }

    static byte[] jpeg(Bitmap b) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        assertTrue(b.compress(Bitmap.CompressFormat.JPEG, 95, o));
        return o.toByteArray();
    }

    /** A JPEG file with the shot's EXIF and, with {@code record}, the watermark's record appended (what PhotoOutput writes). */
    File photoFile(Bitmap pixels, WatermarkPatch record, String name) throws IOException {
        File f = new File(context.getCacheDir(), name);
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(jpeg(pixels));
        }
        ExifInterface e = new ExifInterface(f.getAbsolutePath());
        e.setAttribute(ExifInterface.TAG_MAKE, "vivo");
        e.setAttribute(ExifInterface.TAG_MODEL, "vivo X200 Ultra");
        e.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "690/100");
        e.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, "24");
        e.setAttribute(ExifInterface.TAG_F_NUMBER, "1.8");
        e.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:11 14:05:00");
        e.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+03:00");
        e.saveAttributes();
        if (record != null) assertTrue(WatermarkStore.append(f, record.toBytes()));
        return f;
    }

    /** Our photo: the render with the camera's watermark {@code spec} and its record, and the render itself. */
    Object[] ourPhoto(int w, int h, WatermarkSpec spec, String name) throws IOException {
        Bitmap original = render(w, h);
        Bitmap marked = original.copy(Bitmap.Config.ARGB_8888, true);
        PhotoWatermark.Drawn d = PhotoWatermark.draw(marked, spec, shotInfo(), new WatermarkFonts(context), true);
        WatermarkPatch rec = d.patch();
        assertNotNull(rec);
        return new Object[]{photoFile(marked, rec, name), original, marked, rec};
    }

    EditSource open(File f) throws IOException {
        EditSource s = EditSource.open(context, Uri.fromFile(f), f.getName(), "image/jpeg");
        source = s;
        return s;
    }

    static WatermarkSpec cameraSpec() {
        WatermarkSpec s = new WatermarkSpec().only(true, true, true, true, false, false, true, true);
        s.layout = WatermarkSpec.Layout.BAR;
        return s;
    }

    // ───── opening ─────

    @Test public void ourPhotoOpensWithItsRecordAndRemovesExactly() throws IOException {
        Object[] p = ourPhoto(1600, 1200, cameraSpec(), "IMG_ours.jpg");
        Bitmap original = (Bitmap) p[1];
        EditSource s = open((File) p[0]);
        assertNotNull("the record is offered", s.watermark);
        assertEquals(cameraSpec().toJson(), s.watermark.spec);
        assertEquals("vivo X200 Ultra", s.watermarkInfo.device);
        assertEquals(24, s.watermarkInfo.focal35);
        assertNotNull(s.previewClean);
        assertSame(s.preview, s.previewFor(false));
        assertSame(s.previewClean, s.previewFor(true));
        EditState st = new EditState();
        assertFalse(s.cleanFor(st));
        st.wmRemove = true;
        assertTrue(s.cleanFor(st));
        // the preview (full size here: 1600 px < 2048) with «Убрать»: the render inside the record
        int[] want = words(original), clean = words(s.previewClean), plainPreview = words(s.preview);
        boolean[] kept = new boolean[want.length];
        for (Rect r : s.watermark.rects()) for (int y = r.top; y < r.bottom; y++) Arrays.fill(kept, y * 1600 + r.left, y * 1600 + r.right, true);
        int inside = 0;
        for (int i = 0; i < want.length; i++) {
            if (kept[i]) {
                assertEquals("preview pixel " + i, want[i], clean[i]);
                inside++;
            } else assertEquals("outside the record the preview is the photo", plainPreview[i], clean[i]);
        }
        assertTrue(inside > 0);
        // the copy's / the zoom's full-resolution regions: the render inside the record, the photo's own JPEG outside
        EditSource.Reader rd = s.openReader(context, 1.0, EditSource.OUT_MAX);
        try {
            for (int[] g : new int[][]{{0, 900, 900, 300}, {640, 0, 960, 1200}, {0, 0, 1600, 1200}}) {
                rd.restoreWatermark(true);
                EditSource.Region region = rd.decode(g[0], g[1], g[2], g[3], 1.0);
                assertTrue(region.clean);
                int[] got = words(region.picture());
                rd.restoreWatermark(false);
                EditSource.Region asIs = rd.decode(g[0], g[1], g[2], g[3], 1.0);
                assertFalse(asIs.clean);
                int[] marked = words(asIs.picture());
                int differ = 0;
                for (int y = 0; y < g[3]; y++)
                    for (int x = 0; x < g[2]; x++) {
                        int i = y * g[2] + x, full = (g[1] + y) * 1600 + g[0] + x;
                        if (kept[full]) {
                            assertEquals("region pixel " + (g[0] + x) + "," + (g[1] + y), want[full], got[i]);
                            if (marked[i] != got[i]) differ++;
                        } else assertEquals("outside the record, as the photo", marked[i], got[i]);
                    }
                if (g[1] >= 900 || g[3] == 1200) assertTrue("the region shows the watermark taken off", differ > 0);
                region.free();
                asIs.free();
            }
        } finally {
            rd.close();
        }
        s.close();
    }

    @Test public void aPhotoWithoutARecordOffersNoRemoval() throws IOException {
        Bitmap marked = render(1200, 900);
        PhotoWatermark.draw(marked, cameraSpec(), shotInfo(), new WatermarkFonts(context), false); // an old SCAMERA photo
        EditSource s = open(photoFile(marked, null, "IMG_old.jpg"));
        assertNull(s.watermark);
        assertNull(s.previewClean);
        EditState st = new EditState();
        st.wmRemove = true;
        assertFalse("nothing to take off without a record", s.cleanFor(st));
        assertSame(s.preview, s.previewFor(true));
        s.close();
    }

    @Test public void aRecordOfAnotherPictureIsNotOffered() throws IOException {
        Object[] p = ourPhoto(800, 600, cameraSpec(), "IMG_a.jpg");
        // the record moved onto a picture of another size (an app that re-encoded and resized, keeping the tail)
        File other = photoFile(render(640, 480), (WatermarkPatch) p[3], "IMG_resized.jpg");
        EditSource s = open(other);
        assertNull(s.watermark);
        s.close();
        assertFalse(EditSource.recordFits((WatermarkPatch) p[3], 800, 600, 6));
        assertTrue(EditSource.recordFits((WatermarkPatch) p[3], 800, 600, 1));
    }

    // ───── the state ─────

    @Test public void theChoicesGoThroughJsonUndoAndPaste() {
        EditState s = new EditState();
        assertTrue(s.isDefaultWatermark());
        assertEquals("{\"v\":1}", s.toJson());
        s.wmRemove = true;
        s.wm = cameraSpec().toJson();
        EditState back = EditState.fromJson(s.toJson());
        assertNotNull(back);
        assertEquals(s, back);
        assertTrue(back.wmRemove);
        assertEquals(cameraSpec(), back.watermark());
        assertFalse(back.isDefault());
        EditHistory h = new EditHistory(new EditState());
        h.commit(s.copy());
        assertTrue(h.changed());
        assertTrue(h.undo().isDefaultWatermark());
        // «before» shows the photo as it is; paste takes the watermark, not this photo's removal
        assertTrue(s.withNeutralTone().isDefaultWatermark());
        EditState other = new EditState();
        other.copyTone(s);
        assertEquals(s.wm, other.wm);
        assertFalse(other.wmRemove);
        // a damaged spec is dropped, not kept as text
        EditState bad = EditState.fromJson("{\"v\":1,\"wm\":{\"rm\":true,\"s\":{\"layout\":\"frame\",\"size\":\"x\"}}}");
        assertNotNull(bad);
        assertEquals(WatermarkSpec.Layout.FRAME, bad.watermark().layout);
        assertEquals(WatermarkSpec.DEFAULT_SIZE, bad.watermark().size, 0f);
    }

    // ───── the panel ─────

    EditPanels panels() {
        return new EditPanels(context, new EditPanels.Host() {
            @Override public EditState state() { return state; }
            @Override public void changed(boolean commit) { commits.add(commit); }
            @Override public EditSource source() { return source; }
            @Override public void rotate() { }
            @Override public void flip() { }
            @Override public void resetCrop() { }
            @Override public void aspect(float ratio) { }
            @Override public void straighten(float degrees, boolean commit) { }
            @Override public void style(int index, float amount, boolean commit) { }
            @Override public void lookThumbnail(EditPanels.ThumbSink sink) { }
        });
    }

    static IosSegmented modes(View panel) {
        return (IosSegmented) ((ViewGroup) panel.findViewWithTag("wm_modes")).getChildAt(0);
    }

    static void tap(IosSegmented seg, int i) {
        seg.getChildAt(i).performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
    }

    @Test public void withARecordTheTabRemovesAndChanges() throws IOException {
        WatermarkSpec shot = cameraSpec();
        Object[] p = ourPhoto(1200, 900, shot, "IMG_tab.jpg");
        open((File) p[0]);
        EditPanels ep = panels();
        View v = ep.forTab(PhotoEditorFragment.TAB_WATERMARK);
        assertNotNull(v);
        IosSegmented seg = modes(v);
        assertEquals("Как есть", ((TextView) seg.getChildAt(0)).getText().toString());
        assertEquals("Убрать", ((TextView) seg.getChildAt(1)).getText().toString());
        assertEquals("Изменить", ((TextView) seg.getChildAt(2)).getText().toString());
        assertEquals(1f, seg.getChildAt(1).getAlpha(), 0f);
        assertTrue(((TextView) v.findViewWithTag("wm_status")).getText().toString().contains("без потерь"));
        assertNull("no controls while as is", v.findViewWithTag("wm_layout"));
        tap(seg, 1);
        assertTrue(state.wmRemove);
        assertEquals("", state.wm);
        assertEquals(Arrays.asList(true), commits);
        tap(seg, 2);
        assertTrue("«Изменить» takes the old one off", state.wmRemove);
        assertEquals("starts as the photo's own watermark", shot, state.watermark());
        ep.refresh();
        assertNotNull(v.findViewWithTag("wm_layout"));
        // a field off, one undo step; ISO, which this photo's EXIF lacks, greyed
        View device = v.findViewWithTag("wm_show_device");
        assertTrue(device.isEnabled());
        int n = commits.size();
        device.performClick();
        assertFalse(state.watermark().showDevice);
        assertEquals(n + 1, commits.size());
        assertTrue(commits.get(n));
        assertFalse(v.findViewWithTag("wm_show_iso").isEnabled());
        // the style: Frame
        IosSegmented layout = (IosSegmented) ((ViewGroup) ((ViewGroup) v.findViewWithTag("wm_layout")).getChildAt(1)).getChildAt(0);
        tap(layout, 3);
        assertEquals(WatermarkSpec.Layout.FRAME, state.watermark().layout);
        // undo to «Убрать»: the panel follows
        state.wm = "";
        ep.refresh();
        assertEquals(EditWatermarkPanel.MODE_REMOVE, seg.getSelected());
        assertNull(v.findViewWithTag("wm_layout"));
        tap(seg, 0);
        assertTrue(state.isDefaultWatermark());
        source.close();
    }

    @Test public void withoutARecordRemoveIsGreyedAndAddStartsFromTheCamera() throws IOException {
        open(photoFile(render(1200, 900), null, "IMG_foreign.jpg"));
        android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putString(WatermarkSpec.KEY_LAYOUT, "badge").putBoolean(WatermarkSpec.KEY_SHOW_TIME, true).commit();
        View v = panels().forTab(PhotoEditorFragment.TAB_WATERMARK);
        IosSegmented seg = modes(v);
        assertEquals("Добавить", ((TextView) seg.getChildAt(2)).getText().toString());
        assertTrue("«Убрать» greyed", seg.getChildAt(1).getAlpha() < .5f);
        String why = ((TextView) v.findViewWithTag("wm_status")).getText().toString();
        assertTrue(why, why.startsWith("«Убрать» недоступно"));
        tap(seg, 1);
        assertFalse("a greyed «Убрать» changes nothing", state.wmRemove);
        assertEquals("and says why", EditWatermarkPanel.noRecordText(), org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
        assertTrue(commits.isEmpty());
        assertEquals(0, seg.getSelected());
        tap(seg, 2);
        assertFalse("nothing of its own to take off", state.wmRemove);
        WatermarkSpec added = state.watermark();
        assertNotNull(added);
        assertEquals(WatermarkSpec.Layout.BADGE, added.layout);
        assertTrue(added.showTime);
        source.close();
    }

    @Test public void fieldsTheExifLacksAreGreyed() throws IOException {
        File f = new File(context.getCacheDir(), "IMG_noexif.jpg");
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(jpeg(render(800, 600)));
        }
        open(f);
        state.wm = new WatermarkSpec().only(true, true, true, true, true, true, true, true).toJson();
        View v = panels().forTab(PhotoEditorFragment.TAB_WATERMARK);
        for (String tag : new String[]{"wm_show_device", "wm_show_lens", "wm_show_aperture", "wm_show_shutter", "wm_show_iso", "wm_show_time"}) {
            View row = v.findViewWithTag(tag);
            assertNotNull(tag, row);
            assertFalse(tag + " greyed", row.isEnabled());
            assertEquals("нет в EXIF", ((TextView) v.findViewWithTag(tag + "_value")).getText().toString());
        }
        assertTrue(v.findViewWithTag("wm_show_text").isEnabled());
        assertTrue(v.findViewWithTag("wm_logo").isEnabled());
        assertNull("no date, no date format", v.findViewWithTag("wm_date_format"));
        source.close();
        // with the EXIF the values show
        open(photoFile(render(800, 600), null, "IMG_exif.jpg"));
        View w = panels().forTab(PhotoEditorFragment.TAB_WATERMARK);
        assertEquals("24mm", ((TextView) w.findViewWithTag("wm_show_lens_value")).getText().toString());
        assertEquals("f/1.8", ((TextView) w.findViewWithTag("wm_show_aperture_value")).getText().toString());
        assertFalse(w.findViewWithTag("wm_show_iso").isEnabled());
        assertNotNull(w.findViewWithTag("wm_date_format"));
        source.close();
    }

    // ───── the copy ─────

    @Test public void theCopyGetsTheCameraWatermarkAndItsRecord() throws IOException {
        Object[] p = ourPhoto(1200, 900, cameraSpec(), "IMG_copy.jpg");
        EditSource s = open((File) p[0]);
        EditState st = new EditState();
        st.wmRemove = true;
        WatermarkSpec next = new WatermarkSpec().only(true, true, true, true, false, false, true, true);
        next.layout = WatermarkSpec.Layout.FRAME;
        next.position = WatermarkSpec.Position.TOP_RIGHT;
        st.wm = next.toJson();
        // the export's finished picture (its tiles): here the clean full-resolution region
        EditSource.Reader rd = s.openReader(context, 1.0, EditSource.OUT_MAX);
        rd.restoreWatermark(s.cleanFor(st));
        Bitmap out = rd.decode(0, 0, 1200, 900, 1.0).picture().copy(Bitmap.Config.ARGB_8888, true);
        rd.close();
        int[] before = words(out);
        WatermarkPatch record = EditExporter.drawWatermark(context, s, st, out);
        assertNotNull(record);
        // the camera's drawing of this spec with this photo's EXIF facts
        Bitmap camera = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888);
        camera.copyPixelsFromBuffer(java.nio.IntBuffer.wrap(before));
        PhotoWatermark.draw(camera, next, s.watermarkInfo, new WatermarkFonts(context), false);
        assertArrayEquals(words(camera), words(out));
        File copy = new File(context.getCacheDir(), "IMG_copy_edit.jpg");
        EditExporter.writeCopy(context, s, out, null, record, copy, f -> { });
        ExifInterface e = new ExifInterface(copy.getAbsolutePath());
        assertEquals("vivo X200 Ultra", e.getAttribute(ExifInterface.TAG_MODEL));
        assertEquals("2026:11:11 14:05:00", e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
        s.close();
        // the copy opens with its own watermark removable: back to the copy's pixels before it
        EditSource again = open(copy);
        assertNotNull(again.watermark);
        assertEquals(next.toJson(), again.watermark.spec);
        Bitmap decoded = BitmapFactory.decodeFile(copy.getAbsolutePath(), mutable());
        again.watermark.restore(decoded);
        int[] got = words(decoded);
        for (Rect r : again.watermark.rects())
            for (int y = r.top; y < r.bottom; y++)
                for (int x = r.left; x < r.right; x++) assertEquals(before[y * 1200 + x], got[y * 1200 + x]);
        again.close();
        // without a watermark the copy carries no record
        File plain = new File(context.getCacheDir(), "IMG_plain_edit.jpg");
        EditState removeOnly = new EditState();
        removeOnly.wmRemove = true;
        assertNull(EditExporter.drawWatermark(context, again, removeOnly, out));
        EditExporter.writeCopy(context, again, out, null, null, plain, f -> { });
        assertNull(WatermarkStore.read(plain));
    }

    static BitmapFactory.Options mutable() {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inMutable = true;
        return o;
    }

    @Test public void thePreviewIsTheCopysLayout() {
        WatermarkOverlayView view = new WatermarkOverlayView(context);
        WatermarkSpec s = cameraSpec();
        WatermarkText.Info info = shotInfo();
        view.bind(s, info, 4000, 3000);
        WatermarkPainter.Plan preview = view.plan();
        WatermarkPainter.Plan copy = WatermarkPainter.plan(s, WatermarkText.of(s, info), 4000, 3000, new WatermarkFonts(context));
        assertNotNull(preview);
        assertEquals(WatermarkPainter.texts(copy), WatermarkPainter.texts(preview));
        assertEquals(copy.bounds, preview.bounds);
        view.bind(null, info, 0, 0);
        assertNull(view.plan());
    }

    // ───── screenshots ─────

    static File shotsDir() {
        String d = System.getenv("WM_SHOTS_DIR");
        File f = new File(d != null ? d : "build/reports/edit-watermark");
        //noinspection ResultOfMethodCallIgnored
        f.mkdirs();
        return f;
    }

    /** The editor as it looks: the picture (fitted, the watermark's live preview over it) above the tab's panel on the sheet. */
    void shot(Bitmap picture, View panel, String name) throws IOException {
        final int w = 780, pictureH = 600;
        Context c = context;
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF000000);
        FrameLayout area = new FrameLayout(c);
        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setImageBitmap(picture);
        area.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        WatermarkOverlayView over = new WatermarkOverlayView(c);
        WatermarkSpec s = state.watermark();
        float m = GalleryUi.dp(c, 10);
        float k = Math.min((w - 2 * m) / picture.getWidth(), (pictureH - 2 * m) / picture.getHeight());
        RectF r = new RectF((w - picture.getWidth() * k) / 2f, (pictureH - picture.getHeight() * k) / 2f, 0, 0);
        r.right = r.left + picture.getWidth() * k;
        r.bottom = r.top + picture.getHeight() * k;
        img.setPadding((int) r.left, (int) r.top, (int) (w - r.right), (int) (pictureH - r.bottom));
        if (s != null) {
            over.bind(s, source.watermarkInfo, source.fullW, source.fullH);
            int[] px = new int[picture.getWidth() * picture.getHeight()];
            picture.getPixels(px, 0, picture.getWidth(), 0, 0, picture.getWidth(), picture.getHeight());
            over.setInkPicture(px, picture.getWidth(), picture.getHeight());
        }
        over.setPicture(r);
        area.addView(over, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        col.addView(area, new LinearLayout.LayoutParams(w, pictureH));
        FrameLayout sheet = new FrameLayout(c);
        sheet.setBackground(GalleryUi.card(c, GalleryUi.SHEET, 30));
        sheet.setPadding(0, GalleryUi.dp(c, 8), 0, GalleryUi.dp(c, 12));
        sheet.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(sheet, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        col.layout(0, 0, w, col.getMeasuredHeight());
        Bitmap bitmap = Bitmap.createBitmap(w, col.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFF000000);
        col.draw(new Canvas(bitmap));
        try (FileOutputStream out = new FileOutputStream(new File(shotsDir(), name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    @Test public void screenshots() throws IOException {
        // 1. «Добавить» on a photo without a record (the camera's settings: a bar with the phone, lens, aperture and date)
        open(photoFile(render(1600, 1200), null, "IMG_add.jpg"));
        state = new EditState();
        state.wm = cameraSpec().toJson();
        shot(source.preview, panels().forTab(PhotoEditorFragment.TAB_WATERMARK), "wm_add_ru");
        // 2. the same photo, «Убрать» tapped: greyed, the reason in the panel
        state = new EditState();
        View v = panels().forTab(PhotoEditorFragment.TAB_WATERMARK);
        tap(modes(v), 1);
        shot(source.preview, v, "wm_remove_unavailable_ru");
        source.close();
        // 3. our photo shot with a caption: «Изменить» to a badge top right, the picture without the old one
        WatermarkSpec old = new WatermarkSpec().only(true, true, false, false, false, false, true, true);
        Object[] p = ourPhoto(1600, 1200, old, "IMG_change.jpg");
        open((File) p[0]);
        state = new EditState();
        state.wmRemove = true;
        WatermarkSpec badge = old.copy();
        badge.layout = WatermarkSpec.Layout.BADGE;
        badge.position = WatermarkSpec.Position.TOP_RIGHT;
        badge.showLens = badge.showAperture = true;
        state.wm = badge.toJson();
        shot(source.previewClean, panels().forTab(PhotoEditorFragment.TAB_WATERMARK), "wm_change_ru");
        // 4. the same, «Убрать»: the photo as the camera took it
        state = new EditState();
        state.wmRemove = true;
        shot(source.previewClean, panels().forTab(PhotoEditorFragment.TAB_WATERMARK), "wm_remove_ru");
        // 5. as it opened, the old watermark there
        state = new EditState();
        shot(source.preview, panels().forTab(PhotoEditorFragment.TAB_WATERMARK), "wm_as_is_ru");
        source.close();
    }

    @Test @Config(qualifiers = "en-w390dp-h844dp-xhdpi") public void screenshotsInEnglish() throws IOException {
        Object[] p = ourPhoto(1600, 1200, cameraSpec(), "IMG_en.jpg");
        open((File) p[0]);
        state = new EditState();
        state.wmRemove = true;
        WatermarkSpec frame = cameraSpec();
        frame.layout = WatermarkSpec.Layout.FRAME;
        frame.colour = WatermarkSpec.Colour.WHITE;
        state.wm = frame.toJson();
        shot(source.previewClean, panels().forTab(PhotoEditorFragment.TAB_WATERMARK), "wm_change_en");
        source.close();
        open(photoFile(render(1600, 1200), null, "IMG_en_foreign.jpg"));
        state = new EditState();
        shot(source.preview, panels().forTab(PhotoEditorFragment.TAB_WATERMARK), "wm_remove_unavailable_en");
        source.close();
    }
}
