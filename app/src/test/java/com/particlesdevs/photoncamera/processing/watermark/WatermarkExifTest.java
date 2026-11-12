package com.particlesdevs.photoncamera.processing.watermark;

import android.app.Application;
import android.graphics.Bitmap;

import androidx.exifinterface.media.ExifInterface;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P633: the editor's watermark facts come from the photo's own EXIF as the camera's from its shot - the phone (Make / Model as the
 * gallery names it), the focal length (35 mm equivalent first), aperture, exposure time, ISO, the time taken in its own offset - so
 * «Изменить» redraws the camera's text; a fact the EXIF lacks is unknown, its field greyed and left out of the text.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WatermarkExifTest {
    static File jpeg() throws Exception {
        File f = File.createTempFile("exif", ".jpg");
        f.deleteOnExit();
        Bitmap b = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888);
        try (FileOutputStream o = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.JPEG, 90, o);
        }
        return f;
    }

    @Test public void everyFactFromTheExif() throws Exception {
        File f = jpeg();
        ExifInterface w = new ExifInterface(f.getAbsolutePath());
        w.setAttribute(ExifInterface.TAG_MAKE, "vivo");
        w.setAttribute(ExifInterface.TAG_MODEL, "vivo X200 Ultra");
        w.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "690/100");
        w.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, "24");
        w.setAttribute(ExifInterface.TAG_F_NUMBER, "1.8");
        w.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.008333");
        w.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "100");
        w.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:11 14:05:09");
        w.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+03:00");
        w.saveAttributes();
        WatermarkText.Info i = WatermarkExif.info(new ExifInterface(f.getAbsolutePath()));
        assertEquals("vivo X200 Ultra", i.device);
        assertEquals(6.9, i.focalLength, 1e-9);
        assertEquals(24, i.focal35);
        assertEquals(1.8, i.fNumber, 1e-9);
        assertEquals(1 / 120.0, i.exposureTime, 1e-5);
        assertEquals(100, i.iso);
        for (WatermarkExif.Field fl : WatermarkExif.Field.values()) assertTrue(fl.name(), WatermarkExif.has(i, fl));
        WatermarkSpec all = new WatermarkSpec().only(true, true, true, true, true, true, true, false);
        WatermarkText t = WatermarkText.of(all, i);
        assertEquals("SCAMERA" + WatermarkText.JOIN + "vivo X200 Ultra", t.title);
        assertEquals("24mm" + WatermarkText.EXIF_JOIN + "f/1.8" + WatermarkText.EXIF_JOIN + "1/120s" + WatermarkText.EXIF_JOIN + "ISO 100", t.exif);
        // the wall clock as written, whatever the phone's zone is now
        TimeZone was = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            assertEquals("11.11.2026  14:05", WatermarkText.of(all, WatermarkExif.info(new ExifInterface(f.getAbsolutePath()))).time);
        } finally {
            TimeZone.setDefault(was);
        }
    }

    @Test public void missingFactsAreUnknownAndLeftOut() throws Exception {
        File f = jpeg();
        ExifInterface w = new ExifInterface(f.getAbsolutePath());
        w.setAttribute(ExifInterface.TAG_MODEL, "Pixel 7");
        w.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "6810/1000");
        w.setAttribute(ExifInterface.TAG_DATETIME, "2025:01:02 03:04:05");
        w.saveAttributes();
        WatermarkText.Info i = WatermarkExif.info(new ExifInterface(f.getAbsolutePath()));
        assertEquals("Pixel 7", i.device);
        assertTrue(WatermarkExif.has(i, WatermarkExif.Field.LENS));
        assertEquals("6.8mm", WatermarkText.focal(i));
        assertFalse(WatermarkExif.has(i, WatermarkExif.Field.APERTURE));
        assertFalse(WatermarkExif.has(i, WatermarkExif.Field.SHUTTER));
        assertFalse(WatermarkExif.has(i, WatermarkExif.Field.ISO));
        assertTrue("DateTime when there is no DateTimeOriginal", WatermarkExif.has(i, WatermarkExif.Field.TIME));
        WatermarkText t = WatermarkText.of(new WatermarkSpec().only(false, false, true, true, true, true, false, false), i);
        assertEquals("6.8mm", t.exif);
        WatermarkText.Info none = WatermarkExif.info(new ExifInterface(jpeg().getAbsolutePath()));
        assertNull(none.device);
        for (WatermarkExif.Field fl : WatermarkExif.Field.values())
            assertEquals(fl.name(), fl == WatermarkExif.Field.TEXT, WatermarkExif.has(none, fl));
        WatermarkText empty = WatermarkText.of(new WatermarkSpec().only(false, true, true, true, true, true, true, false), none);
        assertFalse(empty.any());
        assertEquals(0, WatermarkExif.info(null).timeMs);
    }

    @Test public void valuesAsExifWritesThem() {
        assertEquals(0.5, WatermarkExif.number("1/2"), 1e-9);
        assertEquals(2.0, WatermarkExif.number("2"), 1e-9);
        assertTrue(Double.isNaN(WatermarkExif.number(" ")));
        assertEquals("100", WatermarkExif.firstOf("100,100"));
        assertEquals(0, WatermarkExif.time("garbage", TimeZone.getDefault()));
        assertEquals(TimeZone.getDefault(), WatermarkExif.zone("Z"));
        assertEquals(-5 * 3600_000, WatermarkExif.zone("-05:00").getRawOffset());
    }
}
