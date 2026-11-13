package com.scamera.app.gallery.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** P492: the «Подробнее» rows of the details sheet - a tag the file lacks gives no row, the file's place always one. */
public class PhotoMoreTest {
    @Test
    public void valuesInPlainWords() {
        assertEquals("03.11.2026 11:51:19", PhotoMore.taken("2026:11:03 11:51:19"));
        assertEquals("bad", PhotoMore.taken("bad"));
        assertEquals("0 EV", PhotoMore.bias(0.0));
        assertNull(PhotoMore.bias(Double.NaN));
        assertTrue(PhotoMore.bias(-0.7).startsWith("-0"));
        assertNull(PhotoMore.zoom(1.0));
        assertTrue(PhotoMore.zoom(2.0).endsWith("×"));
        assertNull(PhotoMore.whiteBalance(-1));
        assertNull(PhotoMore.metering(0));
        assertNull(PhotoMore.rotation(0));
        assertEquals("90°", PhotoMore.rotation(90));
        assertEquals("sRGB", PhotoMore.colourSpace(1));
    }

    @Test
    public void withoutExifOnlyThePath() {
        List<String[]> rows = PhotoMore.rows(null, "/storage/emulated/0/DCIM/Camera/IMG_1.jpg");
        assertEquals(1, rows.size());
        assertEquals("/storage/emulated/0/DCIM/Camera/IMG_1.jpg", rows.get(0)[1]);
        assertTrue(PhotoMore.rows(null, null).isEmpty());
    }
}
