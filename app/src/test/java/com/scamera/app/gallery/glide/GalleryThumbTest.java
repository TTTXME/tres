package com.scamera.app.gallery.glide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.net.Uri;

import org.robolectric.RuntimeEnvironment;

import com.scamera.app.gallery.files.ImageFile;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;

/** P620: the thumbnail model's cache identity, the grid size the viewer asks for, the disk cache's size, the decode log. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class GalleryThumbTest {
    @Test
    public void theModelFollowsTheFileAndTheKindOfRequest() throws Exception {
        File f = File.createTempFile("IMG_", ".jpg");
        try {
            Files.write(f.toPath(), new byte[100]);
            Uri uri = Uri.parse("content://media/external/images/media/7");
            ImageFile item = new ImageFile(7, uri, f.getName(), 5_000L, 100, f.getAbsolutePath());
            GalleryThumb a = GalleryThumb.of(item);
            assertEquals(a, GalleryThumb.of(item));
            assertEquals(a.hashCode(), GalleryThumb.of(item).hashCode());
            assertEquals("the disk cache key is stable", a.toString(), GalleryThumb.of(item).toString());
            assertTrue(a.cover);
            assertEquals(f.getAbsolutePath(), a.path);
            assertNotEquals("the viewer's fitted picture is another entry", a, GalleryThumb.fit(item));
            Files.write(f.toPath(), new byte[5000]); // rewritten (EXIF / ICC after the first decode)
            assertNotEquals(a, GalleryThumb.of(item));
            assertNotEquals(a, GalleryThumb.of(uri));
            assertEquals(GalleryThumb.of(uri), GalleryThumb.of(uri));
            ImageFile noPath = new ImageFile(8, uri, "IMG_8.jpg", 5_000L, 100, "content://media/8");
            assertEquals("a content string is no path", null, GalleryThumb.of(noPath).path);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    @Test
    public void theViewerAsksForTheGridsSize() {
        Application app = RuntimeEnvironment.getApplication();
        GalleryThumb.setGridPx(app, 384);
        assertEquals(384, GalleryThumb.gridPx(app));
        GalleryThumb.setGridPx(null, -1);
        assertEquals(384, GalleryThumb.gridPx(null));
    }

    @Test
    public void theDiskCacheHoldsALibrary() {
        long mb = ScameraGlideModule.MB;
        assertEquals("Glide's default as the least", 250 * mb, ScameraGlideModule.diskCacheBytes(1024 * mb));
        assertEquals(500 * mb, ScameraGlideModule.diskCacheBytes(10_000 * mb));
        assertEquals("at most 768 MB", 768 * mb, ScameraGlideModule.diskCacheBytes(200_000 * mb));
        assertEquals(250 * mb, ScameraGlideModule.diskCacheBytes(0));
    }

    @Test
    public void theDecodeLogCountsFormatsAndSources() {
        ThumbTrace.reset();
        assertEquals("", ThumbTrace.sources());
        ThumbTrace.decoded("jpeg", "decoded 1/16", 180, 8160, 6144, 478, 360);
        ThumbTrace.decoded("jpeg", "decoded 1/8", 90, 4080, 3072, 478, 360);
        ThumbTrace.decoded("dng", "dng-raw sparse", 120, 8160, 6144, 510, 384);
        assertEquals(", decoded: jpeg/decoded 2, dng/dng-raw 1", ThumbTrace.sources());
        ThumbTrace.reset();
        assertEquals("", ThumbTrace.sources());
        assertEquals("exif", ThumbTrace.family("exif 512x384"));
    }
}
