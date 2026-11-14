package com.scamera.app.gallery.ui.fragments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;

import androidx.navigation.NavController;

import com.scamera.app.R;
import com.scamera.app.gallery.files.ImageFile;
import com.scamera.app.gallery.helper.Constants;
import com.scamera.app.gallery.model.GalleryItem;
import com.scamera.app.gallery.ui.GalleryNavRecreateTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * The viewer keeps its photo when it comes back from the editor / compare or is recreated, and deleting the last photo of the
 * viewer opened from the camera leaves for the grid (navigateUp did nothing there: the deleted photo stayed on screen); back
 * from that grid leaves the gallery (the empty viewer sent the user straight back to the grid).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class ImageViewerNavigationTest {
    private static Bundle position(String key, int p) {
        Bundle b = new Bundle();
        b.putInt(key, p);
        return b;
    }

    @Test
    public void aNewViewerStartsAtItsSavedPositionElseItsArguments() {
        Bundle args = position(Constants.ARG_POSITION, 3);
        assertEquals("tapped in the grid", 3, ImageViewerFragment.startPosition(null, args));
        assertEquals("recreated: the photo shown before", 5, ImageViewerFragment.startPosition(position(ImageViewerFragment.KEY_SEEK, 5), args));
        assertEquals("the camera's viewer recreated (no arguments)", 6, ImageViewerFragment.startPosition(position(ImageViewerFragment.KEY_SEEK, 6), null));
        assertEquals("the camera's viewer: the newest photo", 0, ImageViewerFragment.startPosition(null, null));
        assertEquals("a saved state without the position", 3, ImageViewerFragment.startPosition(new Bundle(), args));
    }

    @Test
    public void theStartViewerLeavesForTheLibrary() {
        Intent camera = new Intent().putExtra("CameraFragment", true);
        try (ActivityController<GalleryNavRecreateTest.Host> c = Robolectric.buildActivity(GalleryNavRecreateTest.Host.class, camera).setup()) {
            NavController nav = GalleryNavRecreateTest.nav(c.get());
            assertEquals(R.id.gallery_viewer, nav.getCurrentDestination().getId());
            ImageViewerFragment.toGrid(nav);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(R.id.gallery_library, nav.getCurrentDestination().getId());
        }
    }

    @Test
    public void backFromTheGridLeavesAViewerWithNothingToShow() {
        Intent camera = new Intent().putExtra("CameraFragment", true);
        try (ActivityController<GalleryNavRecreateTest.Host> c = Robolectric.buildActivity(GalleryNavRecreateTest.Host.class, camera).setup()) {
            NavController nav = GalleryNavRecreateTest.nav(c.get());
            ImageViewerFragment.toGrid(nav); // the camera's viewer after its last photo was deleted
            shadowOf(Looper.getMainLooper()).idle();
            List<GalleryItem> none = new ArrayList<>();
            List<GalleryItem> one = new ArrayList<>();
            Uri u = Uri.parse("content://media/external/images/media/1");
            one.add(new GalleryItem(new ImageFile(1, u, "IMG.jpg", 0, 1, null)));
            // the empty viewer sent the user back to the grid at once: back never left the gallery
            assertTrue(ImageLibraryFragment.leavesGallery(nav.getPreviousBackStackEntry(), none));
            assertFalse("a viewer with photos: back returns to it", ImageLibraryFragment.leavesGallery(nav.getPreviousBackStackEntry(), one));
            assertFalse("no viewer behind the grid", ImageLibraryFragment.leavesGallery(null, none));
        }
    }

    @Test
    public void aViewerOpenedFromTheGridGoesBackToIt() {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        try (ActivityController<GalleryNavRecreateTest.Host> c = Robolectric.buildActivity(GalleryNavRecreateTest.Host.class, launcher).setup()) {
            NavController nav = GalleryNavRecreateTest.nav(c.get());
            nav.navigate(R.id.open_photo);
            shadowOf(Looper.getMainLooper()).idle();
            ImageViewerFragment.toGrid(nav);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(R.id.gallery_library, nav.getCurrentDestination().getId());
            assertNull("back, not a second library", nav.getPreviousBackStackEntry());
        }
    }

    /**
     * P706: the viewer's back arrow returns to the grid it was opened from; the camera's viewer (the graph's start) has no grid
     * behind it - back leaves the gallery, the feed button opens the grid.
     */
    @Test
    public void theBackArrowReturnsToTheGridOnlyWhenItIsBehind() {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        try (ActivityController<GalleryNavRecreateTest.Host> c = Robolectric.buildActivity(GalleryNavRecreateTest.Host.class, launcher).setup()) {
            NavController nav = GalleryNavRecreateTest.nav(c.get());
            nav.navigate(R.id.open_photo);
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue("opened from the grid", ImageViewerFragment.backReturnsToGrid(nav));
        }
        Intent camera = new Intent().putExtra("CameraFragment", true);
        try (ActivityController<GalleryNavRecreateTest.Host> c = Robolectric.buildActivity(GalleryNavRecreateTest.Host.class, camera).setup()) {
            NavController nav = GalleryNavRecreateTest.nav(c.get());
            assertEquals(R.id.gallery_viewer, nav.getCurrentDestination().getId());
            assertFalse("the camera's viewer: nothing behind it", ImageViewerFragment.backReturnsToGrid(nav));
            ImageViewerFragment.toGrid(nav); // the feed button
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(R.id.gallery_library, nav.getCurrentDestination().getId());
        }
    }
}
