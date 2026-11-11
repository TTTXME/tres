package com.particlesdevs.photoncamera.gallery.files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** P617: the gallery's MediaStore scan: rows into folders by name, and the last scan reused while MediaStore is unchanged. */
public class GalleryScanTest {
    private static ImageFile file(long id, long modified) {
        return new ImageFile(id, null, "f" + id + ".jpg", modified, 1, null);
    }

    @Test
    public void rowsGoToTheirFolderNewestFirstFoldersByName() {
        GalleryFileOperations.Folders f = new GalleryFileOperations.Folders();
        ImageFile old = file(1, 10), newest = file(2, 30), raw = file(3, 20), other = file(4, 5);
        f.add("Camera", 7, old);
        f.add("Screenshots", 8, other);
        f.add("Camera", 7, newest);
        f.add("Camera", 99, raw); // a RAW of the same bucket name joins its folder
        assertTrue(f.has(3));
        assertFalse(f.has(5));
        List<GalleryFileOperations.ImagesFolder> out = f.finish();
        assertEquals(2, out.size());
        assertEquals("Camera", out.get(0).getFolderName());
        assertEquals(7, out.get(0).getFolderId());
        assertEquals(3, out.get(0).getAllImageFiles().size());
        assertSame(newest, out.get(0).getTopImage());
        assertSame(raw, out.get(0).getAllImageFiles().get(1));
        assertSame(old, out.get(0).getAllImageFiles().get(2));
        assertEquals("Screenshots", out.get(1).getFolderName());
    }

    @Test
    public void theLastScanStandsOnlyForTheSameKnownMediaStore() {
        assertTrue(GalleryFileOperations.reusable("external_primary:v1:42;", "external_primary:v1:42;", true));
        assertFalse("a new photo, a delete, a trash", GalleryFileOperations.reusable("external_primary:v1:42;", "external_primary:v1:43;", true));
        assertFalse("before Android 11 / unknown", GalleryFileOperations.reusable(null, null, true));
        assertFalse("no scan yet", GalleryFileOperations.reusable(null, "external_primary:v1:42;", true));
        assertFalse("an empty scan is read again", GalleryFileOperations.reusable("k", "k", false));
    }
}
