package com.scamera.app.gallery.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;

import com.scamera.app.gallery.files.ImageFile;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** P621: which intent opens which screen of the gallery as the system's default gallery, and what a pick returns. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class GalleryIntentRoutesTest {
    static final Uri PHOTO = Uri.parse("content://media/external/images/media/7");
    static final Uri VIDEO = Uri.parse("content://media/external/video/media/8");
    static final Uri FOREIGN = Uri.parse("content://com.example.fileprovider/shared/IMG_1.dng");

    static GalleryActivity.Route route(Intent i, String name) {
        return GalleryActivity.routeOf(i, name);
    }

    @Test
    public void eachIntentItsScreen() {
        assertEquals(GalleryActivity.Route.VIEWER, route(new Intent(Intent.ACTION_VIEW).setDataAndType(PHOTO, "image/jpeg"), "IMG.jpg"));
        assertEquals(GalleryActivity.Route.VIEWER, route(new Intent(Intent.ACTION_VIEW).setDataAndType(FOREIGN, "image/x-adobe-dng"), "IMG_1.dng"));
        assertEquals(GalleryActivity.Route.VIEWER, route(new Intent("com.android.camera.action.REVIEW", PHOTO), "IMG.jpg"));
        assertEquals(GalleryActivity.Route.VIEWER, route(new Intent("android.provider.action.REVIEW", PHOTO), "IMG.jpg"));
        assertEquals(GalleryActivity.Route.SECURE_REVIEW, route(new Intent("android.provider.action.REVIEW_SECURE", PHOTO), "IMG.jpg"));
        assertEquals(GalleryActivity.Route.VIDEO, route(new Intent(Intent.ACTION_VIEW).setDataAndType(VIDEO, "video/mp4"), "VID.mp4"));
        assertEquals("a video by its collection", GalleryActivity.Route.VIDEO, route(new Intent(Intent.ACTION_VIEW, VIDEO), null));
        assertEquals("a file manager's video without a type", GalleryActivity.Route.VIDEO,
                route(new Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/Movies/a.mkv")), "a.mkv"));
        assertEquals("a video REVIEW of the camera", GalleryActivity.Route.VIDEO, route(new Intent("com.android.camera.action.REVIEW").setDataAndType(VIDEO, "video/mp4"), null));
        assertEquals(GalleryActivity.Route.EDIT, route(new Intent(Intent.ACTION_EDIT).setDataAndType(PHOTO, "image/jpeg"), "IMG.jpg"));
        assertEquals(GalleryActivity.Route.PICK, route(new Intent(Intent.ACTION_PICK).setType("image/*"), null));
        assertEquals(GalleryActivity.Route.PICK, route(new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI), null));
        assertEquals(GalleryActivity.Route.PICK, route(new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*"), null));
        assertEquals("any content: a photo or a video answers it", GalleryActivity.Route.PICK, route(new Intent(Intent.ACTION_GET_CONTENT).setType("*/*"), null));
        assertEquals("other files are not ours", GalleryActivity.Route.CAMERA, route(new Intent(Intent.ACTION_GET_CONTENT).setType("application/pdf"), null));
        assertEquals(GalleryActivity.Route.LIBRARY, route(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), null));
        assertEquals(GalleryActivity.Route.LIBRARY, route(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY), null));
        assertEquals("the camera's thumbnail", GalleryActivity.Route.CAMERA, route(new Intent().putExtra("CameraFragment", "x"), null));
        assertEquals("a VIEW without data", GalleryActivity.Route.CAMERA, route(new Intent(Intent.ACTION_VIEW), null));
    }

    @Test
    public void neighboursOnlyForMediaStoreAndPaths() {
        assertTrue(GalleryActivity.withNeighbours(PHOTO));
        assertTrue(GalleryActivity.withNeighbours(Uri.parse("file:///storage/emulated/0/DCIM/Camera/IMG.jpg")));
        assertFalse("another app's file: just that item", GalleryActivity.withNeighbours(FOREIGN));
        assertFalse(GalleryActivity.withNeighbours(Uri.parse("content://com.android.providers.downloads.documents/document/12")));
        assertFalse(GalleryActivity.withNeighbours(null));
    }

    @Test
    public void aPickReturnsItsKindReadable() {
        assertEquals("image", GalleryActivity.pickKindOf(new Intent(Intent.ACTION_PICK).setType("image/*")));
        assertEquals("video", GalleryActivity.pickKindOf(new Intent(Intent.ACTION_GET_CONTENT).setType("video/mp4")));
        assertEquals("image", GalleryActivity.pickKindOf(new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)));
        assertEquals("video", GalleryActivity.pickKindOf(new Intent(Intent.ACTION_PICK).setType("vnd.android.cursor.dir/video")));
        assertEquals("any", GalleryActivity.pickKindOf(new Intent(Intent.ACTION_GET_CONTENT).setType("*/*")));
        ImageFile photo = new ImageFile(7, PHOTO, "IMG.jpg", 0, 1, "/sdcard/DCIM/IMG.jpg");
        ImageFile video = new ImageFile(8, VIDEO, "VID.mp4", 0, 1, "/sdcard/DCIM/VID.mp4");
        assertTrue(GalleryActivity.pickable("image", photo));
        assertFalse(GalleryActivity.pickable("image", video));
        assertTrue(GalleryActivity.pickable("video", video));
        assertTrue(GalleryActivity.pickable("any", video));
        assertFalse(GalleryActivity.pickable("any", null));
        Intent r = GalleryActivity.pickResult(PHOTO);
        assertEquals(PHOTO, r.getData());
        assertTrue((r.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        assertEquals(PHOTO, r.getClipData().getItemAt(0).getUri());
    }
}
