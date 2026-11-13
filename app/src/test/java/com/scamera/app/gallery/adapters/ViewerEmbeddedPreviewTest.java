package com.scamera.app.gallery.adapters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPoolAdapter;
import com.scamera.app.gallery.files.ImageFile;
import com.scamera.app.gallery.glide.GalleryThumb;
import com.scamera.app.gallery.glide.ThumbDecoder;
import com.scamera.app.gallery.glide.ThumbPlan;
import com.scamera.app.gallery.glide.ThumbPlanTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * P637: the viewer's first picture of a photo taken a moment ago (no cached grid thumbnail): the file's own EXIF thumbnail,
 * decoded from the header - never the picture itself. The main image of the fixtures has no decodable pixels at all: a decode of
 * it would fail the test.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ViewerEmbeddedPreviewTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static byte[] thumb(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFF3060A0);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 85, o);
        b.recycle();
        return o.toByteArray();
    }

    /** A 4096 x 3072 JPEG header (no pixels) with an EXIF thumbnail of {@code tw x th}, or without one. */
    private static byte[] photo(int tw, int th, int orientation) {
        byte[] main = ThumbPlanTest.jpeg(4096, 3072, false);
        return ThumbPlanTest.withApp(main, ThumbPlanTest.exifApp1(ByteOrder.LITTLE_ENDIAN, orientation, tw > 0 ? thumb(tw, th) : null));
    }

    @Test
    public void theExifThumbnailIsThePagesPreview() {
        byte[] file = photo(512, 384, 6);
        ByteBuffer head = ByteBuffer.wrap(file);
        ThumbPlan.Jpeg j = ThumbPlan.parseJpeg(head);
        assertNotNull(j);
        final long t0 = System.nanoTime();
        Bitmap p = ViewerPreview.embedded(j, head);
        final long ms = (System.nanoTime() - t0) / 1_000_000;
        assertNotNull("the camera's own thumbnail (P620), no cached grid thumbnail needed", p);
        // stored orientation: the view turns the preview and the tiles alike
        assertEquals(512, p.getWidth());
        assertEquals(384, p.getHeight());
        assertEquals(90, ThumbPlan.rotationOf(j.orientation));
        assertTrue("decoded from the header: " + ms + " ms", ms < 500);
        System.out.println("P637 EXIF thumbnail 512x384 as the page's preview: " + ms + " ms (host)");
    }

    @Test
    public void noPreviewWithoutAThumbnailOfThePicturesAspect() {
        byte[] none = photo(0, 0, 1);
        ByteBuffer head = ByteBuffer.wrap(none);
        assertNull(ViewerPreview.embedded(ThumbPlan.parseJpeg(head), head));
        // a thumbnail of another aspect (a 16:9 crop's old 4:3 thumbnail) would be stretched by the view
        byte[] other = photo(512, 288, 1);
        head = ByteBuffer.wrap(other);
        assertNull(ViewerPreview.embedded(ThumbPlan.parseJpeg(head), head));
        // the thumbnail lies beyond the bytes read
        byte[] ok = photo(512, 384, 1);
        ThumbPlan.Jpeg j = ThumbPlan.parseJpeg(ByteBuffer.wrap(ok));
        ByteBuffer cut = ByteBuffer.wrap(ok, 0, j.thumbOffset + j.thumbLength / 2).slice();
        assertNull(ViewerPreview.embedded(j, cut));
        assertNull(ViewerPreview.embedded(j, null));
    }

    private ImageFile write(String name, byte[] bytes) throws IOException {
        File f = tmp.newFile(name);
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(bytes);
        }
        return new ImageFile(-1, android.net.Uri.fromFile(f), name, f.lastModified(), f.length(), f.getAbsolutePath());
    }

    @Test
    public void anEmbeddedOnlyRequestNeverDecodesThePicture() throws Exception {
        ThumbDecoder decoder = new ThumbDecoder(RuntimeEnvironment.getApplication(), new BitmapPoolAdapter());
        Options embedded = new Options().set(ThumbDecoder.EMBEDDED_ONLY, true);
        // upright: the thumbnail turned by the EXIF orientation (Glide's bitmap is shown as it is)
        Bitmap b = decoder.decode(GalleryThumb.fit(write("with.jpg", photo(512, 384, 6))), 1024, 1024, embedded).get();
        assertEquals(384, b.getWidth());
        assertEquals(512, b.getHeight());
        try {
            decoder.decode(GalleryThumb.fit(write("without.jpg", photo(0, 0, 1))), 1024, 1024, embedded);
            org.junit.Assert.fail("a file without an embedded picture fails the request (the page's own decode shows it)");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("no embedded preview"));
        }
        try {
            decoder.decode(GalleryThumb.fit(write("p.png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0})), 1024, 1024, embedded);
            org.junit.Assert.fail("a PNG embeds nothing");
        } catch (IOException expected) {
            // nothing decoded
        }
    }
}
