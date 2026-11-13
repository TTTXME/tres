package com.scamera.app.processing.ultrahdr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Assembles a spec-compliant Ultra HDR (JPEG/R, ISO 21496-1) file from an SDR
 * baseline JPEG and a gain-map JPEG, both produced in Java:
 *
 * <ul>
 *   <li>APP1 XMP GContainer packet (primary image) referencing the GainMap</li>
 *   <li>APP2 MPF (Multi-Picture Format) pointing at the gain-map secondary image</li>
 *   <li>the gain-map JPEG appended after the primary, with its own XMP metadata</li>
 * </ul>
 *
 * No external dependencies - pure byte manipulation.
 *
 * <p>The MPF {@code MPEntry} offsets follow the CIPA DC-007 / libultrahdr
 * convention: they are measured relative to the MPF base (the byte just after
 * the {@code MPF\0} signature in the APP2 segment), not from the file start.
 * The gain-map entry's offset is therefore {@code gainMapSoidAbs - mpfBaseAbs};
 * measuring it from the primary SOI instead points the decoder at the wrong
 * bytes and makes the file unparseable (the gain map appears as raw garbage).
 */
public final class UltraHdrContainer {
    /** The identifiers that open an XMP APP1, the MPF APP2 and an EXIF APP1 payload (NUL-terminated ASCII). */
    private static final byte[] XMP_IDENTIFIER = ascii("http://ns.adobe.com/xap/1.0/\0"), MPF_IDENTIFIER = ascii("MPF\0"),
            EXIF_HEADER = ascii("Exif\0\0");

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private UltraHdrContainer() {
    }

    /**
     * @param sdrJpeg        baseline SDR JPEG bytes (no Ultra HDR metadata yet)
     * @param gainMapJpeg    baseline gain-map JPEG bytes (will get XMP prepended)
     * @param gainMapMin     gain-map min (base-2 log boost)
     * @param gainMapMax     gain-map max (base-2 log boost)
     * @param hdrCapacityMax max content boost (log2), equals gainMapMax
     * @return assembled Ultra HDR JPEG bytes
     */
    public static byte[] encode(byte[] sdrJpeg, byte[] gainMapJpeg,
                                float gainMapMin, float gainMapMax, float hdrCapacityMax) {
        // the gain-map image (with its XMP) first: the primary's GContainer names its whole length
        final byte[] gainImage = gainMapImage(gainMapJpeg, gainMapMin, gainMapMax, hdrCapacityMax);
        final byte[] xmp = primaryXmpSegment(gainImage.length);
        final int scanned = metadataEnd(sdrJpeg, sdrJpeg.length);
        final int insert = scanned >= 0 ? scanned : ~scanned;
        // [baseline up to insert][XMP APP1][MPF APP2][rest of the baseline][gain-map image]: the primary ends where the
        // gain map starts; its MPEntry offset counts from the MPF base, the byte after "MPF\0" (8 into the APP2)
        final int primaryLength = sdrJpeg.length + xmp.length + MPF_APP2_LENGTH;
        final int mpfBase = insert + xmp.length + 8;
        final byte[] mpf = buildMpf(primaryLength, gainImage.length, primaryLength - mpfBase);
        final ByteArrayOutputStream out = new ByteArrayOutputStream(primaryLength + gainImage.length);
        out.write(sdrJpeg, 0, insert);
        out.write(xmp, 0, xmp.length);
        out.write(mpf, 0, mpf.length);
        out.write(sdrJpeg, insert, sdrJpeg.length - insert);
        out.write(gainImage, 0, gainImage.length);
        return out.toByteArray();
    }

    /** Big-endian 16-bit length of the segment whose marker is at {@code at}. */
    private static int segmentLength(byte[] b, int at) {
        return (b[at + 2] & 0xFF) << 8 | (b[at + 3] & 0xFF);
    }

    /**
     * Where the leading APPn / COM segments of a JPEG (after its SOI) end - the first other marker or non-marker byte -
     * within its first {@code length} bytes; ~position (negative) when they run up to the end of those bytes.
     */
    private static int metadataEnd(byte[] jpeg, int length) {
        int at = 2;
        for (; at + 4 <= length; at += 2 + segmentLength(jpeg, at)) {
            if ((jpeg[at] & 0xFF) != 0xFF) return at;
            final int marker = jpeg[at + 1] & 0xFF;
            if (!(marker >= 0xE0 && marker <= 0xEF) && marker != 0xFE) return at;
        }
        return ~at;
    }

    // ------------------------------------------------------------------------
    // Streaming assembly (the primary JPEG is never held in memory)
    // ------------------------------------------------------------------------

    /** Size of the MPF APP2 segment: FF E2 + length(2) + MPF payload(86). */
    public static final int MPF_APP2_LENGTH = 90;

    /** Writes the baseline primary JPEG (e.g. {@code Bitmap.compress}) into the given stream. */
    public interface PrimaryEncoder {
        void encode(OutputStream out) throws IOException;
    }

    /** Overwrites already written bytes at an absolute offset of the output. */
    public interface Patcher {
        void patch(long offset, byte[] bytes) throws IOException;
    }

    /** The gain-map image as it is appended after the primary: baseline JPEG with its hdrgm XMP APP1 after SOI. */
    public static byte[] gainMapImage(byte[] gainMapJpeg, float gainMapMin, float gainMapMax, float hdrCapacityMax) {
        return prependApp1(gainMapJpeg, buildApp1(XmpPacket.gainMap(gainMapMin, gainMapMax, hdrCapacityMax, GainMapComputer.DECODE_OFFSET)));
    }

    /** Primary XMP APP1 (GContainer directory) referencing a gain-map image of {@code gainMapImageLength} bytes. */
    public static byte[] primaryXmpSegment(int gainMapImageLength) {
        return buildApp1(XmpPacket.primary(gainMapImageLength));
    }

    /**
     * MPF APP2 of a primary image of {@code primaryLength} bytes whose MPF base (the byte after the MPF
     * signature) sits at {@code mpfBase}; the gain map starts right after the primary.
     */
    public static byte[] mpfSegment(long primaryLength, int gainMapLength, long mpfBase) {
        if (primaryLength > Integer.MAX_VALUE) throw new IllegalArgumentException("primary image too large for MPF");
        return buildMpf((int) primaryLength, gainMapLength, (int) (primaryLength - mpfBase));
    }

    /**
     * Streams a complete Ultra HDR file: the primary is encoded straight into {@code out} through a
     * {@link PrimaryWriter} (EXIF / XMP / MPF placed while it is written), the gain-map image follows, and the
     * MPF segment is patched once the primary length is known. Produces the same bytes as {@link #encode} applied
     * to the same baseline with {@code exifApp1} placed right after its SOI (where ExifInterface puts it), without
     * ever holding the primary JPEG in memory.
     *
     * @param exifApp1 complete EXIF APP1 segment (FF E1 ...) or null
     * @return total number of bytes written
     */
    public static long write(OutputStream out, Patcher patcher, PrimaryEncoder primary, byte[] exifApp1,
                             byte[] gainMapJpeg, float gainMapMin, float gainMapMax, float hdrCapacityMax)
            throws IOException {
        final byte[] gainImage = gainMapImage(gainMapJpeg, gainMapMin, gainMapMax, hdrCapacityMax);
        final PrimaryWriter writer = new PrimaryWriter(out, exifApp1, primaryXmpSegment(gainImage.length));
        primary.encode(writer);
        writer.finish();
        final long primaryLength = writer.written();
        out.write(gainImage);
        out.flush();
        patcher.patch(writer.mpfOffset(), mpfSegment(primaryLength, gainImage.length, writer.mpfOffset() + 8));
        return primaryLength + gainImage.length;
    }

    /**
     * Returns a copy of the first EXIF APP1 segment (FF E1, length, "Exif" 0 0, TIFF data) of a JPEG, or null
     * when there is none before the image data.
     */
    public static byte[] findExifSegment(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) return null;
        for (int at = 2; at + 4 <= jpeg.length && (jpeg[at] & 0xFF) == 0xFF; ) {
            final int marker = jpeg[at + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) return null; // SOS / EOI: no metadata after this point
            final int length = segmentLength(jpeg, at);
            if (length < 2 || at + 2 + length > jpeg.length) return null;
            if (marker == 0xE1 && length >= 8 && isExifHeader(jpeg, at + 4)) return Arrays.copyOfRange(jpeg, at, at + 2 + length);
            at += 2 + length;
        }
        return null;
    }


    private static boolean isExifHeader(byte[] b, int at) {
        for (int k = 0; k < EXIF_HEADER.length; k++) if (b[at + k] != EXIF_HEADER[k]) return false;
        return true;
    }

    /**
     * Lays out a baseline JPEG as the Ultra HDR primary while the encoder writes it: SOI, the optional EXIF APP1,
     * the encoder's own APPn/COM segments (JFIF, ICC), the primary XMP APP1 and a placeholder MPF APP2 right before
     * the first other marker (the insertion point of {@link #encode}), then the rest of the image unchanged. Only
     * the leading metadata segments are buffered.
     */
    public static final class PrimaryWriter extends OutputStream {
        private static final int MAX_HEAD = 1 << 20;
        /** Largest piece of one write that is buffered before the insertion point is known. */
        private static final int HEAD_STEP = 16 * 1024;
        private final OutputStream out;
        private final byte[] exifApp1;
        private final byte[] xmpApp1;
        private byte[] head = new byte[8192];
        private int headLength;
        private boolean streaming;
        private long written;
        private long mpfOffset = -1;

        public PrimaryWriter(OutputStream out, byte[] exifApp1, byte[] xmpApp1) {
            this.out = out;
            this.exifApp1 = exifApp1;
            this.xmpApp1 = xmpApp1;
        }

        /** Bytes of the primary image written so far (all of it after {@link #finish}). */
        public long written() {
            return written;
        }

        /** Absolute offset of the MPF APP2 placeholder; valid after {@link #finish}. */
        public long mpfOffset() {
            return mpfOffset;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            // Before the insertion point is known the bytes are buffered in small steps: a caller writing a large
            // chunk (a whole pre-encoded JPEG in one call) must neither be copied into the head nor trip MAX_HEAD,
            // which bounds the metadata, not the write size.
            while (len > 0 && !streaming) {
                final int n = Math.min(len, HEAD_STEP);
                appendHead(b, off, n);
                off += n;
                len -= n;
            }
            if (len > 0) {
                out.write(b, off, len);
                written += len;
            }
        }

        private void appendHead(byte[] b, int off, int len) throws IOException {
            if (headLength + len > head.length) {
                if (headLength + len > MAX_HEAD) throw new IOException("JPEG metadata header larger than 1 MB");
                head = Arrays.copyOf(head, Math.min(MAX_HEAD, Math.max(head.length * 2, headLength + len)));
            }
            System.arraycopy(b, off, head, headLength, len);
            headLength += len;
            final int insert = insertionPoint(false);
            if (insert >= 0) emitHead(insert);
        }

        /** Completes the primary image; the encoder must have written all of it. */
        public void finish() throws IOException {
            if (!streaming) {
                final int insert = insertionPoint(true);
                if (insert < 0) throw new IOException("truncated JPEG header");
                emitHead(insert);
            }
            out.flush();
        }

        @Override
        public void flush() {
            // The encoder flushing must not force the buffered head out early; finish() flushes.
        }

        @Override
        public void close() {
            // The underlying stream stays open: the gain map and the MPF patch follow.
        }

        /** Position before the first non-APPn/COM marker, or -1 when more bytes are needed to tell. */
        private int insertionPoint(boolean complete) throws IOException {
            if (headLength < 2) return -1;
            if ((head[0] & 0xFF) != 0xFF || (head[1] & 0xFF) != 0xD8) throw new IOException("primary image is not a JPEG");
            final int end = metadataEnd(head, headLength);
            if (end >= 0) return end;
            // the segments run to the end of what was written: complete, the insertion point of encode(); else wait for more
            return complete && ~end <= headLength ? ~end : -1;
        }

        private void emitHead(int insert) throws IOException {
            writeRaw(head, 0, 2);
            if (exifApp1 != null) writeRaw(exifApp1, 0, exifApp1.length);
            writeRaw(head, 2, insert - 2);
            writeRaw(xmpApp1, 0, xmpApp1.length);
            mpfOffset = written;
            // Placeholder of the final size: the real entries need the primary length (patched afterwards).
            final byte[] placeholder = buildMpf(0, 0, 0);
            writeRaw(placeholder, 0, placeholder.length);
            writeRaw(head, insert, headLength - insert);
            head = null;
            headLength = 0;
            streaming = true;
        }

        private void writeRaw(byte[] b, int off, int len) throws IOException {
            if (len <= 0) return;
            out.write(b, off, len);
            written += len;
        }
    }

    // ------------------------------------------------------------------------
    // APP1 wrapper (XMP)
    // ------------------------------------------------------------------------

    /** An XMP APP1 segment of {@code packet} (padded with a space to an even length). */
    private static byte[] buildApp1(byte[] packet) {
        final int pad = (2 + XMP_IDENTIFIER.length + packet.length) % 2;
        final int payload = 2 + XMP_IDENTIFIER.length + packet.length + pad;
        final ByteBuffer segment = ByteBuffer.allocate(2 + payload).order(ByteOrder.BIG_ENDIAN);
        segment.put((byte) 0xFF).put((byte) 0xE1).putShort((short) payload).put(XMP_IDENTIFIER).put(packet);
        if (pad != 0) segment.put((byte) ' ');
        return segment.array();
    }

    /** {@code jpeg} with {@code app1} right after its SOI. */
    private static byte[] prependApp1(byte[] jpeg, byte[] app1) {
        return ByteBuffer.allocate(jpeg.length + app1.length).put(jpeg, 0, 2).put(app1).put(jpeg, 2, jpeg.length - 2).array();
    }

    /** An IFD entry of the MPF directory (tag, type, count, then the 4-byte value or offset). */
    private static void entry(ByteBuffer b, int tag, int type, int count) {
        b.putShort((short) tag).putShort((short) type).putInt(count);
    }

    /**
     * The MPF APP2 segment (CIPA DC-007, as libultrahdr writes it): "MPF\0", a little-endian TIFF header and one IFD of
     * MPFVersion (UNDEFINED "0100": Skia's parser of Android 14+ refused the ASCII one and found the gain map only through
     * the XMP), NumberOfImages 2 and the two MPEntry records - the primary (type 0x030000, at the file start) and the gain
     * map ({@code gainMapOffset} from the MPF base, see the class comment).
     */
    private static byte[] buildMpf(int primaryLength, int gainMapLength, int gainMapOffset) {
        final int payload = MPF_APP2_LENGTH - 4;
        final ByteBuffer segment = ByteBuffer.allocate(MPF_APP2_LENGTH).order(ByteOrder.BIG_ENDIAN);
        segment.put((byte) 0xFF).put((byte) 0xE2).putShort((short) (2 + payload));
        final ByteBuffer mpf = ByteBuffer.allocate(payload).order(ByteOrder.LITTLE_ENDIAN);
        mpf.put(MPF_IDENTIFIER).put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        mpf.putShort((short) 3);
        entry(mpf, 0xB000, 7, 4);
        mpf.put(new byte[]{'0', '1', '0', '0'});
        entry(mpf, 0xB001, 4, 1);
        mpf.putInt(2);
        entry(mpf, 0xB002, 7, 32);
        mpf.putInt(50); // the MPEntry records, from "II"
        mpf.putInt(0); // no next IFD
        mpf.putInt(0x00030000).putInt(primaryLength).putInt(0).putInt(0);
        mpf.putInt(0).putInt(gainMapLength).putInt(gainMapOffset).putInt(0);
        segment.put(mpf.array());
        return segment.array();
    }
}
