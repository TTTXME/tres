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

    /** P637: the placeholder path's lines: counted from the gallery's start and from the tap on the camera's thumbnail. */
    @Test
    public void thePlaceholderLines() {
        assertEquals("+35 ms (tap +412 ms)", GalleryOpenTrace.stamp(35, 412));
        assertEquals("opened by the launcher: no tap", "+35 ms", GalleryOpenTrace.stamp(35, -1));
        assertEquals("shutter frame 540x720, the shot is still processed", GalleryOpenTrace.placeholderLine("shutter frame", true, 540, 720));
        assertEquals("camera thumbnail 200x267, the last photo", GalleryOpenTrace.placeholderLine("camera thumbnail", false, 200, 267));
        assertEquals("none, the shot is still processed", GalleryOpenTrace.placeholderLine("none", true, 0, 0));
    }

    /** P637: a main thread that does not answer is named with what it is busy with. */
    @Test
    public void aBusyMainThreadIsLoggedWithItsStack() {
        StackTraceElement[] stack = new StackTraceElement[30];
        for (int i = 0; i < stack.length; i++) stack[i] = new StackTraceElement("android.opengl.GLSurfaceView", "m" + i, "GLSurfaceView.java", 100 + i);
        String line = GalleryOpenTrace.MainWatch.busyLine(480, GalleryOpenTrace.MainWatch.BUSY_MS, stack);
        assertTrue(line, line.startsWith("main thread busy >350 ms from +480 ms of the open, at:\n    android.opengl.GLSurfaceView.m0(GLSurfaceView.java:100)"));
        assertEquals("the top frames only", GalleryOpenTrace.MainWatch.FRAMES, line.split("\n").length - 1);
        assertFalse("no watch thread in a host test (its looper is paused)", GalleryOpenTrace.MainWatch.enabled);
    }
}
