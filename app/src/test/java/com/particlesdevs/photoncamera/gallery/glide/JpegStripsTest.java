package com.particlesdevs.photoncamera.gallery.glide;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * P620: the camera's restart-marker JPEG split into standalone strips (host check against libjpeg-turbo on a 16320 x 12288
 * file: the 48 strips decoded at 1/8 and stacked equal the whole decode, max difference 0 - PLAN P620).
 */
public class JpegStripsTest {
    static void seg(ByteArrayOutputStream o, int marker, byte[] payload) {
        o.write(0xFF);
        o.write(marker);
        o.write((payload.length + 2) >> 8);
        o.write(payload.length + 2);
        o.write(payload, 0, payload.length);
    }

    /**
     * A baseline 4:4:4 JPEG's markers: APP1 (EXIF), APP2 ICC and MPF, DQT, SOF0, DHT, DRI = {@code intervalMcus}, SOS, then
     * {@code strips} entropy segments joined by RST0..7 (each segment's bytes = its index, with an FF 00 stuffing inside).
     */
    static byte[] file(int w, int h, int intervalMcus, int strips, int sofMarker, boolean rstInOrder) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xFF);
        o.write(0xD8);
        seg(o, 0xE1, "Exif\0\0IIxx".getBytes(StandardCharsets.ISO_8859_1));
        seg(o, 0xE2, "ICC_PROFILE\0\1\1abc".getBytes(StandardCharsets.ISO_8859_1));
        seg(o, 0xE2, "MPF\0IIxx".getBytes(StandardCharsets.ISO_8859_1));
        byte[] dqt = new byte[65];
        seg(o, 0xDB, dqt);
        seg(o, sofMarker, new byte[]{8, (byte) (h >> 8), (byte) h, (byte) (w >> 8), (byte) w, 3, 1, 0x11, 0, 2, 0x11, 1, 3, 0x11, 1});
        seg(o, 0xC4, new byte[]{0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        seg(o, 0xDD, new byte[]{(byte) (intervalMcus >> 8), (byte) intervalMcus});
        seg(o, 0xDA, new byte[]{3, 1, 0, 2, 0x11, 3, 0x11, 0, 63, 0});
        for (int i = 0; i < strips; i++) {
            if (i > 0) {
                o.write(0xFF);
                o.write(0xD0 + ((rstInOrder ? i - 1 : i) & 7));
            }
            o.write(i);
            o.write(0xFF);
            o.write(0x00); // a stuffed FF inside the data
            o.write(i);
        }
        o.write(0xFF);
        o.write(0xD9);
        return o.toByteArray();
    }

    @Test
    public void stripsOfWholeMcuRows() {
        // 64 x 40, 8 MCUs a row, an interval of 2 MCU rows = 16 picture rows: 3 strips (16, 16, 8 rows)
        byte[] f = file(64, 40, 16, 3, 0xC0, true);
        ByteBuffer b = ByteBuffer.wrap(f);
        JpegStrips s = JpegStrips.parse(b, 2);
        assertNotNull(s);
        assertEquals(3, s.strips());
        assertEquals(16, s.stripRows);
        assertEquals(32, s.y0(2));
        assertEquals(8, s.rows(2));
        for (int i = 0; i < 3; i++) {
            byte[] strip = s.strip(b, i);
            ThumbPlan.Jpeg j = ThumbPlan.parseJpeg(ByteBuffer.wrap(strip));
            assertNotNull("a standalone JPEG", j);
            assertEquals(64, j.width);
            assertEquals(s.rows(i), j.height);
            String text = new String(strip, StandardCharsets.ISO_8859_1);
            assertTrue("the ICC profile stays", text.contains("ICC_PROFILE"));
            assertTrue("no EXIF, no MPF (a gain map elsewhere)", !text.contains("Exif") && !text.contains("MPF"));
            assertEquals("no DRI", -1, indexOf(strip, new byte[]{(byte) 0xFF, (byte) 0xDD}));
            assertArrayEquals("the strip's entropy bytes, then EOI", new byte[]{(byte) i, (byte) 0xFF, 0, (byte) i, (byte) 0xFF, (byte) 0xD9},
                    Arrays.copyOfRange(strip, strip.length - 6, strip.length));
        }
    }

    @Test
    public void notSplitWhenTheFileIsNotOfThatKind() {
        assertNull("progressive", JpegStrips.parse(ByteBuffer.wrap(file(64, 40, 16, 3, 0xC2, true)), 2));
        assertNull("an interval inside an MCU row", JpegStrips.parse(ByteBuffer.wrap(file(64, 40, 12, 4, 0xC0, true)), 2));
        assertNull("RST out of order", JpegStrips.parse(ByteBuffer.wrap(file(64, 40, 16, 3, 0xC0, false)), 2));
        assertNull("fewer strips than asked", JpegStrips.parse(ByteBuffer.wrap(file(64, 40, 16, 3, 0xC0, true)), 4));
        assertNull("strips that do not match the height", JpegStrips.parse(ByteBuffer.wrap(file(64, 40, 16, 2, 0xC0, true)), 2));
        assertNull("not a JPEG", JpegStrips.parse(ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5, 6}), 2));
    }

    static int indexOf(byte[] a, byte[] p) {
        outer:
        for (int i = 0; i + p.length <= a.length; i++) {
            for (int k = 0; k < p.length; k++) if (a[i + k] != p[k]) continue outer;
            return i;
        }
        return -1;
    }
}
