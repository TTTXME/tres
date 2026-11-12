package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.exifinterface.media.ExifInterface;

import com.particlesdevs.photoncamera.api.ParseExif;
import com.particlesdevs.photoncamera.processing.color.IccEmbed;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * P483: the streamed save (PostPipeline.streamJpeg's plan -> PhotoOutput.saveStreamedJpeg) writes the file ImageSaver writes for
 * the same encoder output: the EXIF APP1 inserted into the encoder's stream after SOI, the bytes then written as they are. The
 * host has no jpegli, so both sides take Android's encoder (ImageSaver's fallback) on the same synthetic picture.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class JpegStreamSaveTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static ParseExif.ExifData exif() {
        ParseExif.ExifData d = new ParseExif.ExifData();
        d.SENSITIVITY_TYPE = String.valueOf(ExifInterface.SENSITIVITY_TYPE_ISO_SPEED);
        d.PHOTOGRAPHIC_SENSITIVITY = "400";
        d.F_NUMBER = "1.8";
        d.EXPOSURE_TIME = "0.01";
        d.DATETIME = "2026:11:03 10:10:10";
        d.COLOR_SPACE = "sRGB";
        return d;
    }

    private static Bitmap picture(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) b.setPixel(x, y, Color.rgb(x * 255 / w, y * 255 / h, (x * 7 + y * 13) & 255));
        return b;
    }

    @Test public void streamedFileEqualsImageSaversFile() throws Exception {
        final int w = 320, h = 240;
        final Bitmap image = picture(w, h);
        final Path saved = tmp.newFile("saver.jpg").toPath();
        assertTrue(ImageSaver.Util.saveBitmapAsJPG(saved, image, 98, exif(), false));

        // the plan's wrap (ScamProcessor): COMPRESSION 6, the APP1 of the photo's size, inserted after SOI
        final ParseExif.ExifData e = exif();
        e.COMPRESSION = ParseExif.COMPRESSION_JPEG;
        // P620: and its IFD1 thumbnail from the readback's pixels (PhotoThumbnail.scaledRgba: the samples of the bitmap path)
        final java.nio.ByteBuffer rgba = java.nio.ByteBuffer.allocateDirect(4 * w * h); // the GL readback's R, G, B, A bytes
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                final int c = image.getPixel(x, y);
                rgba.put((byte) Color.red(c)).put((byte) Color.green(c)).put((byte) Color.blue(c)).put((byte) 255);
            }
        rgba.rewind();
        final Bitmap thumb = PhotoThumbnail.scaledRgba(rgba, w, h, 4 * w, PhotoThumbnail.EXIF_LONG_SIDE);
        final byte[] app1 = PhotoThumbnail.withThumbnail(ExifBlock.app1Segment(e, w, h), thumb);
        assertTrue("the EXIF block", app1 != null);
        assertTrue("with its thumbnail", app1.length > ExifBlock.app1Segment(e, w, h).length);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final OutputStream tagged = IccEmbed.jpegInserting(bytes, app1, null);
        // the encoder's output arrives in pieces (jpegli: 1 MB chunks; odd sizes here to cross the insertion's head buffer)
        final ByteArrayOutputStream plain = new ByteArrayOutputStream();
        JpegliEncoder.compressFallback(image, 98, plain);
        final byte[] jpeg = plain.toByteArray();
        for (int at = 0; at < jpeg.length; at += 777) tagged.write(jpeg, at, Math.min(777, jpeg.length - at));
        tagged.close();
        final Path base = tmp.getRoot().toPath().resolve("streamed");
        final PhotoOutput.Result result = PhotoOutput.saveStreamedJpeg(base, bytes.toByteArray(), e, true);
        assertEquals(1, result.files.size());
        assertArrayEquals("the same file", Files.readAllBytes(saved), Files.readAllBytes(result.files.get(0)));
        image.recycle();
    }

    @Test public void writeAllKeepsEveryByte() throws Exception {
        final byte[] data = new byte[(3 << 20) + 12345];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 31);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        PhotoOutput.writeAll(out, data);
        assertArrayEquals(data, out.toByteArray());
    }
}
