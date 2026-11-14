package com.scamera.app.gallery.ui;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

/** P620: the grid's preload range - the next rows in the direction of the scroll, within the list. */
public class ThumbPreloaderTest {
    @Test
    public void nextRowsInTheScrollDirection() {
        int n = 4 * ThumbPreloader.AHEAD_ROWS;
        assertArrayEquals(new int[]{40, 40 + n}, ThumbPreloader.range(20, 39, 4, 1000, true));
        assertArrayEquals(new int[]{20 - n, 20}, ThumbPreloader.range(20, 39, 4, 1000, false));
        assertArrayEquals("the list's end", new int[]{40, 45}, ThumbPreloader.range(20, 39, 4, 45, true));
        assertArrayEquals("the list's start", new int[]{0, 3}, ThumbPreloader.range(3, 20, 4, 45, false));
        // P706: the rows ahead by the phone's tier
        assertArrayEquals(new int[]{40, 40 + 4 * 6}, ThumbPreloader.range(20, 39, 4, 1000, true, 6));
        assertArrayEquals(new int[]{20 - 4 * 2, 20}, ThumbPreloader.range(20, 39, 4, 1000, false, 2));
    }
}
