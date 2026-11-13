package com.scamera.app.gallery.glide;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;


/** P620: the thumbnail decoder's plan - the kind of a file, the size math, the JPEG header with its EXIF thumbnail. */
public class ThumbPlanTest {
    // ---------------------------------------------------------------- fixtures

    /**
     * The markers of a baseline (or progressive) JPEG of {@code w x h}: SOI, APP0 (JFIF), DQT, SOFn, DHT, SOS, a few scan bytes,
     * EOI (the header the plan reads; the pixels are decoded on the phone only).
     */
    public static byte[] jpeg(int w, int h, boolean progressive) {
        ByteBuffer b = ByteBuffer.allocate(512).order(ByteOrder.BIG_ENDIAN);
        b.putShort((short) 0xFFD8);
        b.putShort((short) 0xFFE0).putShort((short) 16).put("JFIF".getBytes(StandardCharsets.US_ASCII)).put((byte) 0)
                .put((byte) 1).put((byte) 1).put((byte) 0).putShort((short) 1).putShort((short) 1).put((byte) 0).put((byte) 0);
        b.putShort((short) 0xFFDB).putShort((short) 67).put((byte) 0);
        for (int i = 0; i < 64; i++) b.put((byte) 1);
        b.putShort((short) (progressive ? 0xFFC2 : 0xFFC0)).putShort((short) 17).put((byte) 8).putShort((short) h).putShort((short) w).put((byte) 3);
        for (int c = 1; c <= 3; c++) b.put((byte) c).put((byte) (c == 1 ? 0x22 : 0x11)).put((byte) 0);
        b.putShort((short) 0xFFC4).putShort((short) (2 + 1 + 16 + 1)).put((byte) 0);
        for (int i = 0; i < 16; i++) b.put((byte) (i == 0 ? 1 : 0));
        b.put((byte) 0);
        b.putShort((short) 0xFFDA).putShort((short) 12).put((byte) 3);
        for (int c = 1; c <= 3; c++) b.put((byte) c).put((byte) 0);
        b.put((byte) 0).put((byte) 63).put((byte) 0);
        b.put(new byte[]{0x12, 0x34, 0x56});
        b.putShort((short) 0xFFD9);
        return java.util.Arrays.copyOf(b.array(), b.position());
    }

    /** An EXIF APP1 (TIFF in {@code order}): IFD0 with the orientation, IFD1 with a JPEG thumbnail ({@code thumb}, may be null). */
    public static byte[] exifApp1(ByteOrder order, int orientation, byte[] thumb) {
        int ifd0 = 8, ifd0Size = 2 + 12 + 4, ifd1 = ifd0 + ifd0Size, ifd1Size = 2 + 2 * 12 + 4, data = ifd1 + ifd1Size;
        int tiffLen = data + (thumb != null ? thumb.length : 0);
        ByteBuffer t = ByteBuffer.allocate(tiffLen).order(order);
        t.put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M').put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
        t.putShort((short) 42).putInt(ifd0);
        t.putShort((short) 1);
        t.putShort((short) 0x112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        t.putInt(thumb != null ? ifd1 : 0);
        if (thumb != null) {
            t.putShort((short) 2);
            t.putShort((short) 0x201).putShort((short) 4).putInt(1).putInt(data);
            t.putShort((short) 0x202).putShort((short) 4).putInt(1).putInt(thumb.length);
            t.putInt(0);
            t.put(thumb);
        }
        byte[] tiff = t.array();
        ByteBuffer app = ByteBuffer.allocate(4 + 6 + tiff.length).order(ByteOrder.BIG_ENDIAN);
        app.put((byte) 0xFF).put((byte) 0xE1).putShort((short) (2 + 6 + tiff.length));
        app.put("Exif".getBytes(StandardCharsets.US_ASCII)).put((byte) 0).put((byte) 0).put(tiff);
        return app.array();
    }

    /** {@code jpeg} with {@code app} inserted right after SOI. */
    public static byte[] withApp(byte[] jpeg, byte[] app) {
        byte[] out = new byte[jpeg.length + app.length];
        System.arraycopy(jpeg, 0, out, 0, 2);
        System.arraycopy(app, 0, out, 2, app.length);
        System.arraycopy(jpeg, 2, out, 2 + app.length, jpeg.length - 2);
        return out;
    }

    // ---------------------------------------------------------------- JPEG header

    @Test
    public void jpegFrameOrientationAndExifThumbnail() throws Exception {
        byte[] thumb = jpeg(160, 120, false);
        for (ByteOrder order : new ByteOrder[]{ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
            byte[] file = withApp(jpeg(640, 480, false), exifApp1(order, 6, thumb));
            ThumbPlan.Jpeg j = ThumbPlan.parseJpeg(ByteBuffer.wrap(file));
            assertNotNull(j);
            assertEquals(640, j.width);
            assertEquals(480, j.height);
            assertFalse(j.progressive);
            assertEquals(6, j.orientation);
            assertTrue(j.hasThumb());
            assertEquals(160, j.thumbWidth);
            assertEquals(120, j.thumbHeight);
            assertEquals(thumb.length, j.thumbLength);
            assertEquals(0xFF, file[j.thumbOffset] & 0xFF);
            assertEquals(0xD8, file[j.thumbOffset + 1] & 0xFF); // the thumbnail's SOI, in place (no copy of the file)
        }
    }

    @Test
    public void progressiveWithoutExif() throws Exception {
        ThumbPlan.Jpeg j = ThumbPlan.parseJpeg(ByteBuffer.wrap(jpeg(320, 200, true)));
        assertNotNull(j);
        assertTrue(j.progressive);
        assertEquals(1, j.orientation);
        assertFalse(j.hasThumb());
        assertEquals(320, j.width);
        assertEquals(200, j.height);
    }

    @Test
    public void exifWithoutThumbnailOrDamagedKeepsTheFrame() throws Exception {
        ThumbPlan.Jpeg j = ThumbPlan.parseJpeg(ByteBuffer.wrap(withApp(jpeg(64, 48, false), exifApp1(ByteOrder.BIG_ENDIAN, 8, null))));
        assertNotNull(j);
        assertEquals(8, j.orientation);
        assertFalse(j.hasThumb());
        // a thumbnail whose length runs past the APP1: no thumbnail, the frame still read
        byte[] app = exifApp1(ByteOrder.LITTLE_ENDIAN, 3, jpeg(16, 16, false));
        int lenAt = 4 + 6 + 8 + 18 + 2 + 12 + 8; // IFD1's second entry's value
        ByteBuffer.wrap(app).order(ByteOrder.LITTLE_ENDIAN).putInt(lenAt, 1 << 20);
        j = ThumbPlan.parseJpeg(ByteBuffer.wrap(withApp(jpeg(64, 48, false), app)));
        assertNotNull(j);
        assertEquals(3, j.orientation);
        assertFalse(j.hasThumb());
        assertEquals(64, j.width);
        assertNull(ThumbPlan.parseJpeg(ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5})));
    }

    // ---------------------------------------------------------------- size math

    @Test
    public void a50MpPhotoDecodesAtAnEighthOrLessForACell() {
        // 8160 x 6144 for a 360 px cell: the short side covers it (478 x 360), the decode samples by 16 (510 x 384)
        assertArrayEquals(new int[]{478, 360}, ThumbPlan.targetSize(8160, 6144, 360, 360, true));
        assertEquals(16, ThumbPlan.sampleSize(8160, 6144, 360, 360, true));
        // 200 MP (16320 x 12240): 1/32
        assertEquals(32, ThumbPlan.sampleSize(16320, 12240, 360, 360, true));
        // portrait the same
        assertEquals(16, ThumbPlan.sampleSize(6144, 8160, 360, 360, true));
        // the viewer fits 4096: a 50 MP photo at 1/1 then scaled (1/2 would be 4080 < 4096), a 200 MP one at 1/2
        assertArrayEquals(new int[]{4096, 3084}, ThumbPlan.targetSize(8160, 6144, 4096, 4096, false));
        assertEquals(1, ThumbPlan.sampleSize(8160, 6144, 4096, 4096, false));
        assertEquals(2, ThumbPlan.sampleSize(16320, 12240, 4096, 4096, false));
        // never up
        assertArrayEquals(new int[]{200, 100}, ThumbPlan.targetSize(200, 100, 360, 360, true));
        assertEquals(1, ThumbPlan.sampleSize(200, 100, 360, 360, true));
    }

    @Test
    public void anEmbeddedThumbnailIsUsedOnlyWhenLargeEnoughAndOfTheSameAspect() {
        assertTrue(ThumbPlan.enough(512, 384, 8160, 6144, 360, 360, true));   // 512 x 384 for 478 x 360
        assertTrue(ThumbPlan.enough(400, 300, 8160, 6144, 360, 360, true));   // 84 % of the side
        assertFalse(ThumbPlan.enough(320, 240, 8160, 6144, 360, 360, true));  // 67 %: soft in the cell
        assertFalse(ThumbPlan.enough(160, 120, 8160, 6144, 360, 360, true));  // the EXIF default size
        assertFalse(ThumbPlan.enough(512, 384, 8192, 4608, 360, 360, true));  // a 4:3 thumbnail of a 16:9 photo (bars)
        assertFalse(ThumbPlan.enough(512, 384, 8160, 6144, 4096, 4096, false)); // the viewer
        assertTrue(ThumbPlan.worthScaling(510, 384, new int[]{360, 271}));
        assertFalse(ThumbPlan.worthScaling(400, 300, new int[]{360, 270}));
    }

    @Test
    public void rotations() {
        assertEquals(0, ThumbPlan.rotationOf(1));
        assertEquals(0, ThumbPlan.rotationOf(0));
        assertEquals(90, ThumbPlan.rotationOf(6));
        assertEquals(180, ThumbPlan.rotationOf(3));
        assertEquals(270, ThumbPlan.rotationOf(8));
        assertEquals(-1, ThumbPlan.rotationOf(5));
    }

    // ---------------------------------------------------------------- kind

    static byte[] bytes(String ascii, int pad) {
        byte[] a = ascii.getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = new byte[a.length + pad];
        System.arraycopy(a, 0, out, 0, a.length);
        return out;
    }

    static byte[] ftyp(String major, String... compatible) {
        ByteBuffer b = ByteBuffer.allocate(16 + 4 * compatible.length + 8).order(ByteOrder.BIG_ENDIAN);
        b.putInt(16 + 4 * compatible.length).put("ftyp".getBytes(StandardCharsets.US_ASCII)).put(major.getBytes(StandardCharsets.US_ASCII)).putInt(0);
        for (String c : compatible) b.put(c.getBytes(StandardCharsets.US_ASCII));
        return b.array();
    }

    /** A little TIFF whose one IFD has {@code tag}. */
    static byte[] tiff(int tag) {
        ByteBuffer b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8).putShort((short) 1);
        b.putShort((short) tag).putShort((short) 1).putInt(4).putInt(0x00000401).putInt(0);
        return b.array();
    }

    @Test
    public void kindsByTheirFirstBytes() throws Exception {
        byte[] j = jpeg(8, 8, false);
        assertEquals(ThumbPlan.Kind.JPEG, ThumbPlan.kindOf("x.heic", j, j.length)); // the bytes win over the name
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        assertEquals(ThumbPlan.Kind.PNG, ThumbPlan.kindOf("a.png", png, png.length));
        byte[] webp = bytes("RIFF\0\0\0\0WEBPVP8 ", 4);
        assertEquals(ThumbPlan.Kind.WEBP, ThumbPlan.kindOf("a.webp", webp, webp.length));
        byte[] heic = ftyp("heic", "mif1", "heic");
        assertEquals(ThumbPlan.Kind.HEIF, ThumbPlan.kindOf("a.heic", heic, heic.length));
        byte[] avif = ftyp("avif", "mif1", "miaf");
        assertEquals(ThumbPlan.Kind.AVIF, ThumbPlan.kindOf("a.avif", avif, avif.length));
        byte[] mp4 = ftyp("isom", "isom", "mp42");
        assertEquals(ThumbPlan.Kind.VIDEO, ThumbPlan.kindOf("clip.bin", mp4, mp4.length));
        assertEquals(ThumbPlan.Kind.VIDEO, ThumbPlan.kindOf("clip.mp4", heic, heic.length)); // a video by its name first
        byte[] dng = tiff(0xC612);
        assertEquals(ThumbPlan.Kind.DNG, ThumbPlan.kindOf("IMG.dng", dng, dng.length));
        byte[] nef = tiff(0x100);
        assertEquals(ThumbPlan.Kind.RAW, ThumbPlan.kindOf("DSC.nef", nef, nef.length));
        assertEquals(ThumbPlan.Kind.DNG, ThumbPlan.kindOf("IMG.dng", nef, nef.length)); // a .dng without DNGVersion in the head
        assertEquals(ThumbPlan.Kind.OTHER, ThumbPlan.kindOf("a.bin", new byte[8], 8));
        assertEquals(ThumbPlan.Kind.HEIF, ThumbPlan.kindOf("a.heic", new byte[8], 8));
    }
}
