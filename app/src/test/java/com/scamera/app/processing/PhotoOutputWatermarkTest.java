package com.scamera.app.processing;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;

import com.scamera.app.processing.watermark.PhotoWatermark;
import com.scamera.app.processing.watermark.WatermarkFonts;
import com.scamera.app.processing.watermark.WatermarkPatch;
import com.scamera.app.processing.watermark.WatermarkSpec;
import com.scamera.app.processing.watermark.WatermarkStore;
import com.scamera.app.processing.watermark.WatermarkText;

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

    /**
     * P642: the streamed JPEG's file is the stream's bytes with the record of the watermark drawn into its rows appended as the
     * bitmap route appends it to its JPEG (the 8-bit record at the end); without a record the stream's bytes alone.
     */
    @Test public void theStreamedJpegGetsItsRecord() throws Exception {
        final int w = 640, h = 480;
        final java.nio.ByteBuffer rows = java.nio.ByteBuffer.allocateDirect(4 * w * h);
        for (int i = 0; i < w * h; i++) rows.put((byte) (i * 7)).put((byte) (i / w)).put((byte) (i * 3 + 90)).put((byte) 0xFF);
        rows.position(0);
        WatermarkText.Info info = WatermarkText.Info.sample("vivo X200 Ultra", 1_794_405_900_000L);
        PhotoWatermark.DrawnInto d = PhotoWatermark.drawInto(new com.scamera.app.processing.watermark.RgbaRows(rows, w, h, 4 * w),
                new WatermarkSpec(), info, new WatermarkFonts(RuntimeEnvironment.getApplication()), true, true);
        assertNotNull(d.record);
        assertNotNull(d.undo);
        // the stream's JPEG of those rows (any encoder here: the file's assembly is what is checked)
        Bitmap marked = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        marked.copyPixelsFromBuffer(rows);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        assertTrue(marked.compress(Bitmap.CompressFormat.JPEG, 95, jpeg));
        File dir = java.nio.file.Files.createTempDirectory("streamed").toFile();
        PhotoOutput.Result saved = PhotoOutput.saveStreamedJpeg(new File(dir, "SCA_1").toPath(), jpeg.toByteArray(), null, true, d.record);
        assertEquals(1, saved.files.size());
        File file = saved.files.get(0).toFile();
        // the bitmap route's way: the JPEG written, then appendWatermark of the 8-bit record
        File other = new File(dir, "other.jpg");
        try (FileOutputStream o = new FileOutputStream(other)) {
            o.write(jpeg.toByteArray());
        }
        PhotoOutput.appendWatermark(other.toPath(), d.record, false);
        assertArrayEquals(java.nio.file.Files.readAllBytes(other.toPath()), java.nio.file.Files.readAllBytes(file.toPath()));
        WatermarkPatch back = WatermarkStore.read(file);
        assertNotNull(back);
        assertArrayEquals(d.record.get().eightBit().toBytes(), back.toBytes());
        assertTrue(back.verify());
        // no record: the stream's bytes
        PhotoOutput.Result plain = PhotoOutput.saveStreamedJpeg(new File(dir, "SCA_2").toPath(), jpeg.toByteArray(), null, true);
        assertArrayEquals(jpeg.toByteArray(), java.nio.file.Files.readAllBytes(plain.files.get(0)));
    }
}
