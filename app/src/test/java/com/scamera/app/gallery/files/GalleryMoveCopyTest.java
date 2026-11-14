package com.scamera.app.gallery.files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** P697: the pure part of «Переместить / Копировать в папку» - the folders, the names in the target, the RAW + photo groups. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class GalleryMoveCopyTest {
    private static final String CAMERA = "/storage/emulated/0/DCIM/Camera/";

    private static ImageFile file(long id, String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return new ImageFile(id, Uri.parse("content://media/external/images/media/" + id), name, 1000 + id, 10, path);
    }

    private static Set<String> taken(String... names) {
        Set<String> s = new HashSet<>();
        for (String n : names) s.add(n.toLowerCase(java.util.Locale.ROOT));
        return s;
    }

    @Test
    public void pathsSplitIntoRootFolderAndName() {
        assertArrayEquals(new String[]{"/storage/emulated/0/", "DCIM/Camera/", "a.jpg"}, GalleryMoveCopy.splitPath(CAMERA + "a.jpg"));
        assertArrayEquals(new String[]{"/storage/1234-ABCD/", "DCIM/", "b.dng"}, GalleryMoveCopy.splitPath("/storage/1234-ABCD/DCIM/b.dng"));
        assertArrayEquals(new String[]{"/storage/emulated/0/", "Pictures/x/", "c.png"}, GalleryMoveCopy.splitPath("/sdcard/Pictures/x/c.png"));
        assertArrayEquals(new String[]{"/storage/emulated/10/", "", "d.jpg"}, GalleryMoveCopy.splitPath("/storage/emulated/10/d.jpg"));
        assertNull(GalleryMoveCopy.splitPath("/data/user/0/app/e.jpg"));
        assertNull(GalleryMoveCopy.splitPath("/storage/self/primary/DCIM/f.jpg"));
        assertNull(GalleryMoveCopy.splitPath(null));
    }

    @Test
    public void aFileGivesItsFolderAsTheTarget() {
        GalleryMoveCopy.Target t = GalleryMoveCopy.targetOf("Camera", CAMERA + "a.jpg");
        assertEquals("Camera", t.name);
        assertEquals("/storage/emulated/0/", t.root);
        assertEquals("DCIM/Camera/", t.relativePath);
        assertEquals(CAMERA, t.absoluteDir());
        assertEquals("external_primary", t.volume());
        assertEquals("Trip", GalleryMoveCopy.targetOf(null, "/storage/emulated/0/Pictures/Trip/a.jpg").name);
        assertEquals("1234-abcd", GalleryMoveCopy.targetOf("SD", "/storage/1234-ABCD/DCIM/b.jpg").volume());
        assertNull("the storage's root", GalleryMoveCopy.targetOf("0", "/storage/emulated/0/a.jpg"));
        assertNull("an app's private folder", GalleryMoveCopy.targetOf("cache", "/storage/emulated/0/Android/data/x/a.jpg"));
        assertNull(GalleryMoveCopy.targetOf("x", null));
    }

    @Test
    public void albumsGiveEachFolderOnceByName() {
        Map<String, List<String>> albums = new LinkedHashMap<>();
        // an album merges the folders of one name: two folders on disk, two targets
        albums.put("Camera", Arrays.asList(CAMERA + "1.jpg", CAMERA + "2.dng", "/storage/emulated/0/Pictures/Camera/3.jpg"));
        albums.put("Screenshots", Collections.singletonList("/storage/emulated/0/Pictures/Screenshots/s.png"));
        albums.put("apps", Collections.singletonList("/storage/emulated/0/Android/media/x/a.jpg"));
        albums.put("Alpha", Arrays.asList("/storage/emulated/0/DCIM/Alpha/a.jpg", null));
        List<GalleryMoveCopy.Target> t = GalleryMoveCopy.targets(albums);
        assertEquals(4, t.size()); // not the apps' own folders (Android/...)
        assertEquals("Alpha", t.get(0).name);
        assertEquals("DCIM/Camera/", t.get(1).relativePath);
        assertEquals("Pictures/Camera/", t.get(2).relativePath);
        assertEquals("Screenshots", t.get(3).name);
    }

    @Test
    public void typedFolderNamesAreMadeSafe() {
        assertEquals("Отпуск 2026", GalleryMoveCopy.cleanFolderName("  Отпуск 2026 "));
        assertEquals("a_b_c_d", GalleryMoveCopy.cleanFolderName("a/b:c*d"));
        assertEquals("hidden", GalleryMoveCopy.cleanFolderName("..hidden"));
        assertEquals("end", GalleryMoveCopy.cleanFolderName("end."));
        assertEquals("tab_x", GalleryMoveCopy.cleanFolderName("tab\tx"));
        assertNull(GalleryMoveCopy.cleanFolderName("   "));
        assertNull(GalleryMoveCopy.cleanFolderName(". ."));
        assertNull(GalleryMoveCopy.cleanFolderName(null));
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 100; ++i) longName.append('я');
        assertEquals(80, GalleryMoveCopy.cleanFolderName(longName.toString()).length());
    }

    @Test
    public void aNewFolderGoesUnderDcim() {
        GalleryMoveCopy.Target t = GalleryMoveCopy.newFolder("Trip", "/storage/emulated/0");
        assertEquals("Trip", t.name);
        assertEquals("DCIM/Trip/", t.relativePath);
        assertEquals("/storage/emulated/0/DCIM/Trip/", t.absoluteDir());
        assertEquals(t, GalleryMoveCopy.targetOf("Trip", "/storage/emulated/0/dcim/trip/x.jpg")); // the storage ignores case
    }

    @Test
    public void aTakenNameGetsTheFirstFreeNumber() {
        assertEquals("IMG_1.jpg", GalleryMoveCopy.uniqueName("IMG_1.jpg", taken("IMG_2.jpg")));
        assertEquals("IMG_1 (1).jpg", GalleryMoveCopy.uniqueName("IMG_1.jpg", taken("img_1.JPG")));
        assertEquals("IMG_1 (2).jpg", GalleryMoveCopy.uniqueName("IMG_1.jpg", taken("IMG_1.jpg", "IMG_1 (1).jpg")));
        assertEquals("a (2).jpg", GalleryMoveCopy.uniqueName("a (1).jpg", taken("a (1).jpg")));
        assertEquals("README (1)", GalleryMoveCopy.uniqueName("README", taken("readme")));
        assertEquals(".nomedia (1)", GalleryMoveCopy.uniqueName(".nomedia", taken(".nomedia")));
        assertEquals("v.2.mp4", GalleryMoveCopy.uniqueName("v.2.mp4", taken()));
        assertEquals("v.2 (1).mp4", GalleryMoveCopy.uniqueName("v.2.mp4", taken("v.2.mp4")));
    }

    @Test
    public void aRawAndItsPhotoKeepOneBaseName() {
        assertEquals(Arrays.asList("IMG_1 (1).jpg", "IMG_1 (1).dng"),
                GalleryMoveCopy.uniqueNames(Arrays.asList("IMG_1.jpg", "IMG_1.dng"), taken("IMG_1.dng")));
        assertEquals(Arrays.asList("IMG_1 (2).jpg", "IMG_1 (2).dng"),
                GalleryMoveCopy.uniqueNames(Arrays.asList("IMG_1.jpg", "IMG_1.dng"), taken("IMG_1.jpg", "IMG_1 (1).dng")));
        assertEquals(Arrays.asList("IMG_1.jpg", "IMG_1.dng"),
                GalleryMoveCopy.uniqueNames(Arrays.asList("IMG_1.jpg", "IMG_1.dng"), taken("IMG_2.jpg")));
    }

    @Test
    public void aPhotoTakesItsRawAlongAndTheRawItsPhoto() {
        ImageFile jpg = file(1, CAMERA + "IMG_1.jpg"), dng = file(2, CAMERA + "IMG_1.dng"), elsewhere = file(3, "/storage/emulated/0/DCIM/Raw/IMG_1.dng");
        ImageFile video = file(4, CAMERA + "IMG_1.mp4"), other = file(5, CAMERA + "IMG_2.jpg"), plain = file(6, CAMERA + "x.jpg");
        ImageFile loose = new ImageFile(7, Uri.parse("content://media/external/images/media/7"), "IMG_2.dng", 1, 1, null);
        List<ImageFile> all = Arrays.asList(jpg, dng, elsewhere, video, other, loose);

        List<List<MediaFile>> g = GalleryMoveCopy.groups(Collections.singletonList(jpg), all);
        assertEquals(1, g.size());
        assertEquals(Arrays.asList(jpg, dng), g.get(0)); // not the DNG of another folder, not the video of the same name

        g = GalleryMoveCopy.groups(Collections.singletonList(dng), all);
        assertEquals(Arrays.asList(dng, jpg), g.get(0));

        g = GalleryMoveCopy.groups(Arrays.asList(dng, jpg, dng), all);
        assertEquals("both selected: one group, each once", 1, g.size());
        assertEquals(2, g.get(0).size());

        g = GalleryMoveCopy.groups(Arrays.asList(video, other, loose), all);
        assertEquals(3, g.size());
        assertEquals(Collections.singletonList(video), g.get(0));
        assertEquals(Collections.singletonList(other), g.get(1)); // no IMG_2.dng beside it (the one without a path is nowhere)
        assertEquals(Collections.singletonList(loose), g.get(2));
        assertEquals(3, GalleryMoveCopy.count(g));

        assertSame(plain, GalleryMoveCopy.groups(Collections.singletonList(plain), Collections.emptyList()).get(0).get(0));
    }

    @Test
    public void aFileInTheFolderAlreadyIsKnown() {
        GalleryMoveCopy.Target camera = GalleryMoveCopy.targetOf("Camera", CAMERA + "x.jpg");
        assertTrue(GalleryMoveCopy.inFolder(file(1, CAMERA + "a.jpg"), camera));
        assertTrue(GalleryMoveCopy.inFolder(file(1, "/storage/emulated/0/dcim/camera/a.jpg"), camera));
        assertFalse(GalleryMoveCopy.inFolder(file(2, "/storage/emulated/0/DCIM/Camera/sub/a.jpg"), camera));
        assertFalse(GalleryMoveCopy.inFolder(new ImageFile(3, null, "a.jpg", 1, 1, null), camera));
    }

    @Test
    public void volumes() {
        assertEquals("external_primary", GalleryMoveCopy.volumeName("/storage/emulated/0/"));
        assertEquals("external_primary", GalleryMoveCopy.volumeName("/sdcard/"));
        assertEquals("1234-abcd", GalleryMoveCopy.volumeName("/storage/1234-ABCD/"));
    }
}
