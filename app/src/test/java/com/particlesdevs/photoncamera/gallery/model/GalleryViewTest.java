package com.particlesdevs.photoncamera.gallery.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.particlesdevs.photoncamera.gallery.files.GalleryVideo;

import org.junit.Test;

/** P498: the gallery's view filter (media kind, file type, columns) and the video helpers. */
public class GalleryViewTest {
    @Test
    public void mediaKindSplitsPhotosAndVideos() {
        assertTrue(GalleryView.matches(GalleryView.Media.BOTH, "a.mp4"));
        assertTrue(GalleryView.matches(GalleryView.Media.BOTH, "a.jpg"));
        assertTrue(GalleryView.matches(GalleryView.Media.VIDEOS, "VID_1.mp4"));
        assertFalse(GalleryView.matches(GalleryView.Media.VIDEOS, "IMG_1.jpg"));
        assertTrue(GalleryView.matches(GalleryView.Media.PHOTOS, "IMG_1.dng"));
        assertFalse(GalleryView.matches(GalleryView.Media.PHOTOS, "clip.MOV"));
    }

    @Test
    public void typeFilter() {
        assertTrue(GalleryView.matches(GalleryView.Type.VIDEO, "a.webm"));
        assertFalse(GalleryView.matches(GalleryView.Type.VIDEO, "a.heic"));
        assertTrue(GalleryView.matches(GalleryView.Type.JPEG, "a.jpg"));
        assertFalse(GalleryView.matches(GalleryView.Type.JPEG, "a.mp4"));
        assertTrue(GalleryView.matches(GalleryView.Type.ALL, "a.mp4"));
    }

    @Test
    public void columnsClamped() {
        assertEquals(GalleryView.MIN_COLUMNS, GalleryView.clampColumns(0));
        assertEquals(GalleryView.MAX_COLUMNS, GalleryView.clampColumns(40));
        assertEquals(4, GalleryView.clampColumns(4));
    }

    @Test
    public void applyWithoutContextKeepsAll() {
        assertEquals(0, GalleryView.apply(null, null, false).size());
    }

    @Test
    public void videoHelpers() {
        assertTrue(GalleryVideo.isVideoName("x.MKV"));
        assertFalse(GalleryVideo.isVideoName("x.jpg"));
        assertFalse(GalleryVideo.isVideoName(null));
        assertEquals("0:42", GalleryVideo.clock(42_000));
        assertEquals("12:05", GalleryVideo.clock(725_000));
        assertEquals("1:02:07", GalleryVideo.clock(3_727_000));
        assertEquals("video/mp4", GalleryVideo.mime("a.MP4"));
        assertEquals("video/quicktime", GalleryVideo.mime("a.mov"));
        assertEquals("video/*", GalleryVideo.mime("a.avi"));
    }
}
