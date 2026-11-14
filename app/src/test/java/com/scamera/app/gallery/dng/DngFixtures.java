package com.scamera.app.gallery.dng;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** A small synthetic Bayer DNG (DngRawTest's writer) for the editor's tests in other packages. */
public final class DngFixtures {
    private DngFixtures() {}

    public static final int W = DngPreviewTest.W, H = DngPreviewTest.H;

    public static byte[] smallBayer() {
        int[] raw = DngPreviewTest.scene(DngRawTest.N, 64, 1023);
        return DngRawTest.bayer(raw, 64, 1023).build();
    }

    /**
     * P696: the tags a develop without its record reads, on {@link #smallBayer}'s RGGB mosaic (black 64, white 1023, AsShotNeutral
     * 0.5 / 1 / 0.7); null / 0 / false: the tag is not written.
     */
    public static final class Tags {
        public String software;
        public double baselineExposure;
        /** ColorMatrix1 (XYZ -> camera, row-major) with CalibrationIlluminant1 D65. */
        public double[] colorMatrix1;
        public int orientation;
        public double[] noiseProfile;
        public int iso;
        /** Seconds (written as a rational of 1 / 10000), f-number, millimetres. */
        public double exposureTime, fNumber, focalLength;
        /** The exposure tags in an Exif IFD (0x8769, as P618 writes them) instead of IFD 0. */
        public boolean exposureInExif;
        /** OpcodeList2 (0xC741) with four GainMaps, one a 2x2 site: R {@link #shadingGain}(0, i), G (1 and 2), B (3). */
        public boolean shadingMaps;

        /** The gain of point {@code i} (of 2 x 3) of the map of channel {@code c} (R, G, G, B). */
        public static float shadingGain(int c, int i) {
            return 1.25f + .25f * c + .01f * i;
        }
    }

    public static byte[] bayer(Tags tags) {
        int[] raw = DngPreviewTest.scene(DngRawTest.N, 64, 1023);
        DngPreviewTest.Tiff t = DngRawTest.bayer(raw, 64, 1023);
        if (tags.software != null) {
            byte[] s = (tags.software + "\0").getBytes(StandardCharsets.US_ASCII);
            t.blob(0x131, 2, s.length, s);
        }
        if (tags.baselineExposure != 0) t.blob(0xC62A, 10, 1, DngPreviewTest.rationals(tags.baselineExposure));
        if (tags.colorMatrix1 != null) {
            t.blob(0xC621, 10, 9, DngPreviewTest.rationals(tags.colorMatrix1));
            t.tag(0xC65A, 3, 1, 21);
        }
        if (tags.orientation > 0) t.tag(0x112, 3, 1, tags.orientation);
        if (tags.noiseProfile != null) {
            ByteBuffer b = ByteBuffer.allocate(8 * tags.noiseProfile.length).order(ByteOrder.LITTLE_ENDIAN);
            for (double v : tags.noiseProfile) b.putDouble(v);
            t.blob(0xC761, 12, tags.noiseProfile.length, b.array());
        }
        if (tags.shadingMaps) {
            byte[] ops = shadingList();
            t.blob(0xC741, 7, ops.length, ops);
        }
        if (!tags.exposureInExif) {
            if (tags.iso > 0) t.tag(0x8827, 3, 1, tags.iso);
            if (tags.exposureTime > 0) t.blob(0x829A, 5, 1, DngPreviewTest.rationals(tags.exposureTime));
            if (tags.fNumber > 0) t.blob(0x829D, 5, 1, DngPreviewTest.rationals(tags.fNumber));
            if (tags.focalLength > 0) t.blob(0x920A, 5, 1, DngPreviewTest.rationals(tags.focalLength));
            return t.build();
        }
        // the Exif IFD goes last, after every blob: its offset is IFD 0's end plus the blobs before it
        t.tag(0x8769, 4, 1, 0);
        int at = 8 + 2 + 12 * t.entries.size() + 4;
        for (byte[] b : t.blobs) at += b.length + (b.length & 1);
        t.entries.get(t.entries.size() - 1)[3] = at;
        ByteBuffer exif = ByteBuffer.allocate(2 + 12 * 4 + 4).order(ByteOrder.LITTLE_ENDIAN);
        exif.putShort((short) 4);
        // inline values only: ISO a SHORT, the rest FLOATs (type 11)
        exif.putShort((short) 0x829A).putShort((short) 11).putInt(1).putFloat((float) tags.exposureTime);
        exif.putShort((short) 0x829D).putShort((short) 11).putInt(1).putFloat((float) tags.fNumber);
        exif.putShort((short) 0x8827).putShort((short) 3).putInt(1).putShort((short) tags.iso).putShort((short) 0);
        exif.putShort((short) 0x920A).putShort((short) 11).putInt(1).putFloat((float) tags.focalLength);
        exif.putInt(0);
        t.blobs.add(exif.array());
        return t.build();
    }

    /** Big-endian OpcodeList2: four GainMaps (2 x 3 points, pitch 2), one per site of the 2x2 pattern. */
    static byte[] shadingList() {
        final int one = 16 + 76 + 6 * 4;
        ByteBuffer b = ByteBuffer.allocate(4 + 4 * one).order(ByteOrder.BIG_ENDIAN);
        b.putInt(4);
        for (int site = 0; site < 4; site++) {
            final int top = site >> 1, left = site & 1;
            b.putInt(9).putInt(0x01030000).putInt(0).putInt(76 + 6 * 4);
            b.putInt(top).putInt(left).putInt(H).putInt(W).putInt(0).putInt(1).putInt(2).putInt(2).putInt(2).putInt(3);
            b.putDouble(1).putDouble(.5).putDouble(0).putDouble(0).putInt(1);
            // RGGB: site 0 R, 1 and 2 G, 3 B
            for (int i = 0; i < 6; i++) b.putFloat(Tags.shadingGain(site, i));
        }
        return b.array();
    }
}
