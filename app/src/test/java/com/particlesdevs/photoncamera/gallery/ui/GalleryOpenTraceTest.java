package com.particlesdevs.photoncamera.gallery.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** P617: the gallery open's timing lines (each step once per open). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class GalleryOpenTraceTest {
    @Test
    public void thumbnailsAreCountedForTheFirstScreenOnly() {
        GalleryOpenTrace.opened("camera");
        assertTrue(GalleryOpenTrace.countingThumbs());
        for (int i = 0; i < GalleryOpenTrace.FIRST_THUMBS - 1; i++) GalleryOpenTrace.thumbReady(i % 2 == 0, false);
        assertTrue(GalleryOpenTrace.countingThumbs());
        GalleryOpenTrace.thumbReady(false, true);
        assertFalse("the first screen is in the log: the grid's requests stop listening", GalleryOpenTrace.countingThumbs());
        GalleryOpenTrace.opened("launcher");
        assertTrue("a new open counts again", GalleryOpenTrace.countingThumbs());
    }

    @Test
    public void summarySaysWhereTheThumbnailsCameFrom() {
        assertEquals("first 12 thumbnails ready +340 ms (memory 5, disk cache 4, decoded 3)", GalleryOpenTrace.summary(12, 5, 4, 340));
        assertEquals("first thumbnail ready +90 ms (memory 0, disk cache 0, decoded 1)", GalleryOpenTrace.summary(1, 0, 0, 90));
    }
}
