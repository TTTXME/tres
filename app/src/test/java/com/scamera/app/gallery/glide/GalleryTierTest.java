package com.scamera.app.gallery.glide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** P706: the gallery's tiers by the phone's memory - only how much is kept and prepared ahead, never the pictures' size. */
public class GalleryTierTest {
    private static final long GB = 1L << 30;

    @Test public void tiersByMemory() {
        assertEquals(GalleryTier.LOW, GalleryTier.of(3 * GB, false));
        assertEquals(GalleryTier.LOW, GalleryTier.of(4 * GB, false));
        assertEquals("a low-RAM device whatever its memory", GalleryTier.LOW, GalleryTier.of(12 * GB, true));
        assertEquals(GalleryTier.MID, GalleryTier.of(6 * GB, false));
        assertEquals("an «8 GB» phone reports a little less", GalleryTier.MID, GalleryTier.of(8 * GB - 300L * 1024 * 1024, false));
        assertEquals("unknown: as before", GalleryTier.MID, GalleryTier.of(0, false));
        assertEquals(GalleryTier.HIGH, GalleryTier.of(12 * GB, false));
        assertEquals(GalleryTier.HIGH, GalleryTier.of(16 * GB, false));
    }

    @Test public void theMiddleTierIsTheGalleryBefore() {
        assertEquals(3f, GalleryTier.memoryCacheScreens(GalleryTier.MID), 0);
        assertEquals(2, GalleryTier.viewerOffscreenPages(GalleryTier.MID, false));
        assertEquals(1, GalleryTier.viewerOffscreenPages(GalleryTier.MID, true));
        assertEquals(4, GalleryTier.preloadRows(GalleryTier.MID));
    }

    @Test public void lessOnLowMoreOnHigh() {
        assertTrue(GalleryTier.memoryCacheScreens(GalleryTier.LOW) < GalleryTier.memoryCacheScreens(GalleryTier.MID));
        assertTrue(GalleryTier.memoryCacheScreens(GalleryTier.HIGH) > GalleryTier.memoryCacheScreens(GalleryTier.MID));
        assertEquals(1, GalleryTier.viewerOffscreenPages(GalleryTier.LOW, false));
        assertEquals(1, GalleryTier.viewerOffscreenPages(GalleryTier.HIGH, true));
        assertTrue(GalleryTier.preloadRows(GalleryTier.LOW) < GalleryTier.preloadRows(GalleryTier.HIGH));
    }
}
