package com.scamera.app.processing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import androidx.exifinterface.media.ExifInterface;

import com.scamera.app.api.ParseExif;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * P620: the photo's EXIF carries a 512 px JPEG thumbnail in IFD1 (ExifInterface reads it: getThumbnailRange), the photo's
 * aspect and pixel orientation, the APP1 within 64 KB, the image's own bytes untouched; the DNG gets the developed photo as a
 * SubIFD preview appended without moving a byte of the raw file (tools/check_dng_metadata.cpp: the native writer's file).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PhotoThumbnailTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** Red top-left, green top-right, blue bottom-left, white bottom-right quadrants. */
    private static Bitmap quadrants(int w, int h) {
        final Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                b.setPixel(x, y, y < h / 2 ? (x < w / 2 ? Color.RED : Color.GREEN) : (x < w / 2 ? Color.BLUE : Color.WHITE));
        return b;
    }

    private static void assertNear(String what, int expected, int actual) {
        assertTrue(what + ": " + Integer.toHexString(actual), Math.abs(Color.red(expected) - Color.red(actual)) < 40
                && Math.abs(Color.green(expected) - Color.green(actual)) < 40 && Math.abs(Color.blue(expected) - Color.blue(actual)) < 40);
    }

    /** The quadrants of {@code b} (sampled at their centres) in the order of {@link #quadrants}. */
    private static void assertQuadrants(String what, Bitmap b) {
        final int w = b.getWidth(), h = b.getHeight();
        assertNear(what + " top left", Color.RED, b.getPixel(w / 4, h / 4));
        assertNear(what + " top right", Color.GREEN, b.getPixel(3 * w / 4, h / 4));
        assertNear(what + " bottom left", Color.BLUE, b.getPixel(w / 4, 3 * h / 4));
        assertNear(what + " bottom right", Color.WHITE, b.getPixel(3 * w / 4, 3 * h / 4));
    }

    private static ParseExif.ExifData exif() {
        final ParseExif.ExifData d = new ParseExif.ExifData();
        d.SENSITIVITY_TYPE = String.valueOf(ExifInterface.SENSITIVITY_TYPE_ISO_SPEED);
        d.PHOTOGRAPHIC_SENSITIVITY = "100";
        d.DATETIME = "2026:11:11 14:03:07";
        d.COLOR_SPACE = "sRGB";
        return d;
    }

    @Test
    public void sizeKeepsTheAspect() {
        assertArrayEquals(new int[]{512, 384}, PhotoThumbnail.size(8192, 6144, 512));
        assertArrayEquals(new int[]{384, 512}, PhotoThumbnail.size(3072, 4096, 512));
        assertArrayEquals(new int[]{512, 288}, PhotoThumbnail.size(4096, 2304, 512));
        assertArrayEquals(new int[]{1024, 768}, PhotoThumbnail.size(4080, 3060, 1024));
        assertArrayEquals(new int[]{300, 200}, PhotoThumbnail.size(300, 200, 512));
    }

    @Test
    public void theJpegCarriesItsThumbnailAndItsOwnBytes() throws Exception {
        final Bitmap photo = quadrants(1600, 1200);
        final Path jpg = tmp.getRoot().toPath().resolve("t.jpg");
        final long started = System.nanoTime();
        assertTrue(ImageSaver.Util.saveBitmapAsJPG(jpg, photo.copy(Bitmap.Config.ARGB_8888, false), 95, exif(), true));
        System.out.println("P620 host: JPEG 1600x1200 with its thumbnail saved in " + (System.nanoTime() - started) / 1000000 + " ms");
        final byte[] file = Files.readAllBytes(jpg);
        final ExifInterface e = new ExifInterface(jpg.toFile());
        assertTrue(e.hasThumbnail());
        assertNotNull(e.getThumbnailRange());
        final byte[] thumb = e.getThumbnailBytes();
        assertNotNull(thumb);
        assertTrue("thumbnail " + thumb.length + " B", thumb.length > 1000 && thumb.length < 64 << 10);
        final Bitmap t = BitmapFactory.decodeByteArray(thumb, 0, thumb.length);
        assertEquals(512, t.getWidth());
        assertEquals(384, t.getHeight());
        assertQuadrants("thumbnail", t); // the photo's own orientation
        final byte[] app1 = com.scamera.app.processing.ultrahdr.UltraHdrContainer.findExifSegment(file);
        assertNotNull(app1);
        assertTrue(app1.length + " B", app1.length <= PhotoThumbnail.APP1_MAX);
        assertEquals("100", e.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)); // the other tags still read
        // the photo's bytes are the encoder's: from its quantisation tables on, the file is the plain JPEG of the same pixels
        // (no jpegli here; the APP1 comes right after SOI)
        assertEquals(2, indexOf(file, app1));
        if (!JpegliEncoder.available()) {
            final ByteArrayOutputStream plain = new ByteArrayOutputStream();
            JpegliEncoder.compressFallback(photo, 95, plain);
            final byte[] p = plain.toByteArray();
            final byte[] dqt = {(byte) 0xFF, (byte) 0xDB};
            // the main image's tables, after the APP1 (whose thumbnail has tables of its own)
            final int a = 2 + app1.length + indexOf(Arrays.copyOfRange(file, 2 + app1.length, file.length), dqt), b = indexOf(p, dqt);
            assertTrue(a >= 2 + app1.length && b > 0);
            assertArrayEquals(Arrays.copyOfRange(p, b, p.length), Arrays.copyOfRange(file, a, file.length));
        }
        final Bitmap main = BitmapFactory.decodeFile(jpg.toString());
        assertEquals(1600, main.getWidth());
        assertQuadrants("photo", main);
    }

    @Test
    public void aThumbnailTooLargeForTheSegmentIsSmallerOrNone() {
        final ParseExif.ExifData d = exif();
        final byte[] app1 = ExifBlock.app1Segment(d, 512, 512);
        assertNotNull(app1);
        // noise compresses badly: 85 does not fit 64 KB at 512 x 512
        final Bitmap noise = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        final Random r = new Random(5);
        for (int y = 0; y < 512; y++) for (int x = 0; x < 512; x++) noise.setPixel(x, y, 0xFF000000 | r.nextInt(0xFFFFFF));
        final byte[] out = PhotoThumbnail.withThumbnail(app1, noise);
        assertTrue(out.length + " B", out.length <= PhotoThumbnail.APP1_MAX);
        // no thumbnail: the segment as it was; a segment with an IFD1 already is not extended again
        assertTrue(Arrays.equals(app1, PhotoThumbnail.withThumbnail(app1, (Bitmap) null)));
        final byte[] once = PhotoThumbnail.withThumbnail(app1, quadrants(512, 384));
        assertTrue(once.length > app1.length);
        assertTrue(Arrays.equals(once, PhotoThumbnail.withThumbnail(once, quadrants(512, 384))));
    }

    @Test
    public void theStreamedJpegsThumbnailComesFromItsPixels() {
        // RGBA rows with padding (the readback's stride), the photo a sub-rectangle as the ISZ crop's
        final int w = 1000, h = 750, stride = 4 * w + 64;
        final ByteBuffer rgba = ByteBuffer.allocateDirect(stride * h).order(ByteOrder.nativeOrder());
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                final int c = y < h / 2 ? (x < w / 2 ? Color.RED : Color.GREEN) : (x < w / 2 ? Color.BLUE : Color.WHITE);
                final int at = y * stride + 4 * x;
                rgba.put(at, (byte) Color.red(c)).put(at + 1, (byte) Color.green(c)).put(at + 2, (byte) Color.blue(c)).put(at + 3, (byte) 255);
            }
        final long started = System.nanoTime();
        final Bitmap t = PhotoThumbnail.scaledRgba(rgba, w, h, stride, 512);
        System.out.println("P620 host: 1000x750 readback -> 512 px in " + (System.nanoTime() - started) / 1000000 + " ms");
        assertNotNull(t);
        assertEquals(512, t.getWidth());
        assertEquals(384, t.getHeight());
        assertQuadrants("streamed", t);
        assertNull(PhotoThumbnail.scaledRgba(rgba, w, h + 10, stride, 512)); // past the buffer
        // the cost on a 12 MP readback (host JVM; the phone's is in the log: «EXIF thumbnail ... in N ms», «DNG preview ... in N ms»)
        final ByteBuffer big = ByteBuffer.allocateDirect(4 * 4000 * 3000);
        for (int k : new int[]{PhotoThumbnail.EXIF_LONG_SIDE, PhotoThumbnail.DNG_LONG_SIDE}) {
            final long t0 = System.nanoTime();
            final Bitmap s = PhotoThumbnail.scaledRgba(big, 4000, 3000, 4 * 4000, k);
            System.out.println("P620 host: 4000x3000 readback -> " + s.getWidth() + "x" + s.getHeight() + " in "
                    + (System.nanoTime() - t0) / 1000000 + " ms");
            s.recycle();
        }
        final Bitmap photo = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888);
        final long t0 = System.nanoTime();
        final Bitmap small = PhotoThumbnail.exifSource(photo);
        final byte[] app1 = PhotoThumbnail.withThumbnail(ExifBlock.app1Segment(exif(), 4000, 3000), small);
        System.out.println("P620 host: 4000x3000 bitmap -> EXIF thumbnail " + small.getWidth() + "x" + small.getHeight() + ", APP1 "
                + app1.length + " B in " + (System.nanoTime() - t0) / 1000000 + " ms");
    }

    /** A little-endian TIFF / DNG of IFD 0 only: a 16 x 8 16-bit raw strip and a few tags (an out-of-line Make). */
    private static byte[] rawDng() {
        final ByteBuffer b = ByteBuffer.allocate(8 + 256 + 6 + 2 + 2 + 12 * 8 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8 + 256 + 6 + 2);
        for (int i = 0; i < 128; i++) b.putShort((short) (64 + i));
        b.put("vivo\0\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putShort((short) 0);
        b.putShort((short) 8);
        int[][] e = {{254, 4, 1, 0}, {256, 4, 1, 16}, {257, 4, 1, 8}, {258, 3, 1, 16}, {262, 3, 1, 32803}, {271, 2, 5, 8 + 256},
                {273, 4, 1, 8}, {274, 3, 1, 6}};
        for (int[] x : e) {
            b.putShort((short) x[0]).putShort((short) x[1]).putInt(x[2]);
            if (x[1] == 3) b.putShort((short) x[3]).putShort((short) 0);
            else b.putInt(x[3]);
        }
        b.putInt(0);
        return Arrays.copyOf(b.array(), b.position());
    }

    private static Map<Integer, int[]> ifd(ByteBuffer f, int at) {
        final Map<Integer, int[]> m = new HashMap<>();
        final int n = f.getShort(at) & 0xFFFF;
        int last = -1;
        for (int i = 0; i < n; i++) {
            final int e = at + 2 + 12 * i, tag = f.getShort(e) & 0xFFFF, type = f.getShort(e + 2);
            assertTrue("sorted", tag > last);
            last = tag;
            m.put(tag, new int[]{type, f.getInt(e + 4), type == 3 && f.getInt(e + 4) == 1 ? f.getShort(e + 8) & 0xFFFF : f.getInt(e + 8)});
        }
        return m;
    }

    @Test
    public void theDngGetsTheDevelopedPhotoAsASubIfdPreview() throws Exception {
        final Path dng = tmp.getRoot().toPath().resolve("p.dng");
        final byte[] before = rawDng();
        Files.write(dng, before);
        // the saved photo upright (rotation 90: the sensor's frame is the photo turned 90 degrees counter-clockwise)
        final Bitmap photo = PhotoThumbnail.scaled(quadrants(1200, 1600), PhotoThumbnail.DNG_LONG_SIDE);
        assertEquals(768, photo.getWidth());
        assertEquals(1024, photo.getHeight());
        final Bitmap sensor = PhotoThumbnail.dngSource(photo, 90);
        assertEquals(1024, sensor.getWidth());
        assertEquals(768, sensor.getHeight());
        // the photo's top-left (red) is the sensor frame's bottom-left after turning back
        assertNear("sensor bottom left", Color.RED, sensor.getPixel(sensor.getWidth() / 4, 3 * sensor.getHeight() / 4));
        assertNear("sensor top left", Color.GREEN, sensor.getPixel(sensor.getWidth() / 4, sensor.getHeight() / 4));
        final byte[] jpeg = PhotoThumbnail.jpeg(sensor, 90);
        final long started = System.nanoTime();
        assertTrue(DngPreview.append(dng, jpeg, sensor.getWidth(), sensor.getHeight(), DngPreview.COLOUR_SRGB, "SCAMERA 0.98 (1) 0123abcd"));
        System.out.println("P620 host: DNG preview 1024x768 " + jpeg.length / 1024 + " KB appended in " + (System.nanoTime() - started) / 1000000 + " ms");
        final byte[] after = Files.readAllBytes(dng);
        // nothing of the raw file moved: only the IFD 0 offset in the header changed
        for (int i = 0; i < before.length; i++) if (i < 4 || i >= 8) assertEquals("byte " + i, before[i], after[i]);
        final ByteBuffer f = ByteBuffer.wrap(after).order(ByteOrder.LITTLE_ENDIAN);
        final int ifd0 = f.getInt(4);
        assertTrue(ifd0 >= before.length && ifd0 % 2 == 0);
        final Map<Integer, int[]> t0 = ifd(f, ifd0);
        final Map<Integer, int[]> old = ifd(ByteBuffer.wrap(before).order(ByteOrder.LITTLE_ENDIAN), ByteBuffer.wrap(before).order(ByteOrder.LITTLE_ENDIAN).getInt(4));
        for (Map.Entry<Integer, int[]> x : old.entrySet()) assertArrayEquals("tag " + x.getKey(), x.getValue(), t0.get(x.getKey()));
        assertEquals(old.size() + 1, t0.size());
        assertEquals(0, t0.get(254)[2]); // the raw stays the main image
        final int sub = t0.get(DngPreview.TAG_SUB_IFDS)[2];
        final Map<Integer, int[]> p = ifd(f, sub);
        assertEquals(1, p.get(254)[2]);
        assertEquals(7, p.get(259)[2]);
        assertEquals(6, p.get(262)[2]);
        assertEquals(1024, p.get(256)[2]);
        assertEquals(768, p.get(257)[2]);
        assertEquals(DngPreview.COLOUR_SRGB, p.get(50970)[2]);
        final Bitmap preview = BitmapFactory.decodeByteArray(after, p.get(273)[2], p.get(279)[2]);
        assertNotNull(preview);
        assertEquals(1024, preview.getWidth());
        assertEquals(768, preview.getHeight());
        assertNear("preview top left", Color.GREEN, preview.getPixel(256, 192));
        // a DNG with a preview already is left alone
        assertFalse(DngPreview.append(dng, jpeg, 1024, 768, DngPreview.COLOUR_SRGB, null));
        assertArrayEquals(after, Files.readAllBytes(dng));
    }

    private static int indexOf(byte[] in, byte[] part) {
        outer:
        for (int i = 0; i + part.length <= in.length; i++) {
            for (int j = 0; j < part.length; j++) if (in[i + j] != part[j]) continue outer;
            return i;
        }
        return -1;
    }
}
