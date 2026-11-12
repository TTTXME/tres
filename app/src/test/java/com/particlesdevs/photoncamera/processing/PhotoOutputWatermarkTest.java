package com.particlesdevs.photoncamera.processing;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;

import com.particlesdevs.photoncamera.processing.watermark.PhotoWatermark;
import com.particlesdevs.photoncamera.processing.watermark.WatermarkFonts;
import com.particlesdevs.photoncamera.processing.watermark.WatermarkPatch;
import com.particlesdevs.photoncamera.processing.watermark.WatermarkSpec;
import com.particlesdevs.photoncamera.processing.watermark.WatermarkStore;
import com.particlesdevs.photoncamera.processing.watermark.WatermarkText;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * P633: each file of a shot takes the watermark record of the precision it was coded from (its save line says which), appended
 * after the encoder wrote it: the 10-bit HEIC / AVIF the 10-bit record, the JPEG / WebP / 8-bit HEIC the 8-bit one.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PhotoOutputWatermarkTest {
    @Test public void eachFileTakesTheRecordOfItsOwnPrecision() {
        assertFalse(PhotoOutput.tenBitFile(PhotoFormat.JPEG, true, " Display P3"));
        assertFalse(PhotoOutput.tenBitFile(PhotoFormat.WEBP, true, ""));
        assertTrue(PhotoOutput.tenBitFile(PhotoFormat.HEIC, true, " 10-bit Display P3"));
        assertTrue(PhotoOutput.tenBitFile(PhotoFormat.HEIC, true, " 10-bit + ISO gain map"));
        assertFalse(PhotoOutput.tenBitFile(PhotoFormat.HEIC, true, " 8-bit"));
        assertFalse(PhotoOutput.tenBitFile(PhotoFormat.HEIC, false, " 10-bit"));
        assertTrue(PhotoOutput.tenBitFile(PhotoFormat.AVIF, true, " from 10-bit Display P3"));
        assertFalse(PhotoOutput.tenBitFile(PhotoFormat.AVIF, true, " Display P3"));
    }

    @Test public void theShotsFilesGetTheirRecords() throws Exception {
        Bitmap ten = Bitmap.createBitmap(640, 480, Bitmap.Config.RGBA_1010102, true, ColorSpace.get(ColorSpace.Named.DISPLAY_P3));
        ten.eraseColor(0xFF4A6B8C);
        WatermarkText.Info info = WatermarkText.Info.sample("vivo X200 Ultra", 1_794_405_900_000L);
        PhotoWatermark.Drawn d = PhotoWatermark.draw(ten, new WatermarkSpec(), info, new WatermarkFonts(RuntimeEnvironment.getApplication()), true, true);
        assertNotNull(d.record);
        Bitmap eight = d.photo.copy(Bitmap.Config.ARGB_8888, false);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        assertTrue(eight.compress(Bitmap.CompressFormat.JPEG, 95, jpeg));
        File j = File.createTempFile("shot", ".jpg");
        try (FileOutputStream o = new FileOutputStream(j)) {
            o.write(jpeg.toByteArray());
        }
        PhotoOutput.appendWatermark(j.toPath(), d.record, PhotoOutput.tenBitFile(PhotoFormat.JPEG, true, ""));
        WatermarkPatch back = WatermarkStore.read(j);
        assertNotNull(back);
        assertEquals(WatermarkPatch.FORMAT_8888, back.format);
        assertEquals(WatermarkPatch.SPACE_P3, back.colourSpace);
        assertArrayEquals(d.patch().eightBit().toBytes(), back.toBytes());
        File h = File.createTempFile("shot", ".heic");
        try (FileOutputStream o = new FileOutputStream(h)) {
            o.write(new byte[]{0, 0, 0, 16, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0, 0, 0, 0, 8, 'm', 'd', 'a', 't'});
        }
        PhotoOutput.appendWatermark(h.toPath(), d.record, PhotoOutput.tenBitFile(PhotoFormat.HEIC, true, " 10-bit"));
        WatermarkPatch tenBack = WatermarkStore.read(h);
        assertNotNull(tenBack);
        assertEquals(WatermarkPatch.FORMAT_1010102, tenBack.format);
        assertTrue(tenBack.verify());
    }
}
