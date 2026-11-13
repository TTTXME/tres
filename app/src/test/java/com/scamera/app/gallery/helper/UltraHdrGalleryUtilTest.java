package com.scamera.app.gallery.helper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** The header test for Ultra HDR JPEGs: which segments mark a gain map, and that plain or broken data is not one. */
public class UltraHdrGalleryUtilTest {

    /** A JPEG of the given segments ({marker, payload...}) between SOI and, when {@code closed}, EOI. */
    private static byte[] jpeg(boolean closed, Object... markerThenPayload) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0xFF);
        b.write(0xD8);
        for (int i = 0; i < markerThenPayload.length; i += 2) {
            byte[] body = (byte[]) markerThenPayload[i + 1];
            int size = body.length + 2;
            b.write(0xFF);
            b.write((Integer) markerThenPayload[i]);
            b.write(size >> 8);
            b.write(size & 0xFF);
            b.write(body, 0, body.length);
        }
        if (closed) {
            b.write(new byte[]{0x5A, 0x3C, 0x7E}, 0, 3); // scan data
            b.write(0xFF);
            b.write(0xD9);
        }
        return b.toByteArray();
    }

    private static byte[] text(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static boolean marked(byte[] file) {
        return UltraHdrGalleryUtil.containsUltraHdrMarkers(file, file.length);
    }

    @Test
    public void anXmpPacketWithTheGainMapNamespaceIsUltraHdr() {
        assertTrue(marked(jpeg(false, 0xE1, text("http://ns.adobe.com/xap/1.0/\0"
                + "<x:xmpmeta xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\">"))));
    }

    @Test
    public void aMultiPictureSegmentIsUltraHdr() {
        assertTrue(marked(jpeg(false, 0xE2, new byte[]{'M', 'P', 'F', 0, 'I', 'I', 0x2A, 0x00})));
    }

    @Test
    public void anIso21496SegmentIsUltraHdr() {
        assertTrue(marked(jpeg(false, 0xE2, text("urn:iso:std:iso:ts:21496:-1\0\0\0\0"))));
    }

    @Test
    public void aPlainJpegIsNot() {
        assertFalse(marked(jpeg(true,
                0xE0, new byte[]{'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 72, 0, 72, 0, 0},
                0xDB, new byte[]{0, 2, 4, 6},
                0xC0, new byte[]{8, 1},
                0xDA, new byte[]{1, 0})));
    }

    @Test
    public void anXmpPacketWithoutTheGainMapIsNot() {
        assertFalse(marked(jpeg(false, 0xE1, text("http://ns.adobe.com/xap/1.0/\0<x:xmpmeta>nothing</x:xmpmeta>"))));
    }

    @Test
    public void missingShortOrCutDataIsNot() {
        assertFalse(UltraHdrGalleryUtil.containsUltraHdrMarkers(null, 0));
        assertFalse(UltraHdrGalleryUtil.containsUltraHdrMarkers(new byte[]{(byte) 0xFF, (byte) 0xD8}, 2));
        assertFalse(UltraHdrGalleryUtil.containsUltraHdrMarkers(new byte[]{(byte) 0xFF, (byte) 0xE2, 0x00}, 3));
    }
}
