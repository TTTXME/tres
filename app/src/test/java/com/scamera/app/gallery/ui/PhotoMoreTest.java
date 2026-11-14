package com.scamera.app.gallery.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;


/** P492: the words of the details sheet's EXIF values (P706: the rows themselves: PhotoPropertiesTest). */
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
}
