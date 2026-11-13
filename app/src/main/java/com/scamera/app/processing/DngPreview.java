package com.scamera.app.processing;

import androidx.annotation.Nullable;

import com.scamera.app.util.Log;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * P620: the developed photo as the DNG's preview. The DNG is written before the post pipeline (the reference RAW / the merged
 * mosaic leave memory first), so the photo does not exist then; once it is saved this appends to the DNG, without moving a
 * byte of it: the preview's baseline JPEG, its IFD (NewSubFileType 1, Compression 7, YCbCr, PreviewColorSpace) and a copy of
 * IFD 0 with a SubIFDs tag (330) pointing at it, then the header's IFD 0 offset is moved to the copy - the last write, so a
 * reader in between sees the old file. The raw image stays IFD 0 (its NewSubFileType 0): every DNG reader opens it as before;
 * exiftool lists the preview as PreviewImage, Adobe's DNG SDK and LibRaw read SubIFD previews. The DNG's Orientation turns the
 * preview as the raw, so it is stored in the sensor's orientation (PhotoThumbnail.dngSource).
 */
public final class DngPreview {
    private static final String TAG = "DngPreview";
    static final int TAG_SUB_IFDS = 330;

    private DngPreview() {}

    /** PreviewColorSpace (50970): 2 sRGB, 0 unknown (a Display P3 photo). */
    public static final int COLOUR_SRGB = 2, COLOUR_UNKNOWN = 0;

    /**
     * Appends the baseline JPEG {@code jpeg} ({@code width} x {@code height}, 4:2:0 YCbCr as Bitmap.compress writes it) as the
     * DNG {@code dng}'s preview. False (logged) when the file is no DNG this writer made sense of (a SubIFDs tag already, an
     * offset past 4 GB) or on an I/O error; the file is then as it was.
     */
    public static boolean append(Path dng, byte[] jpeg, int width, int height, int colourSpace, @Nullable String software) {
        if (dng == null || jpeg == null || jpeg.length == 0 || width <= 0 || height <= 0) return false;
        try (RandomAccessFile f = new RandomAccessFile(dng.toFile(), "rw")) {
            final byte[] head = new byte[8];
            f.readFully(head);
            final ByteOrder order = head[0] == 'I' && head[1] == 'I' ? ByteOrder.LITTLE_ENDIAN
                    : head[0] == 'M' && head[1] == 'M' ? ByteOrder.BIG_ENDIAN : null;
            if (order == null || ByteBuffer.wrap(head).order(order).getShort(2) != 42) return fail(dng, "no TIFF header");
            final long ifd0 = ByteBuffer.wrap(head).order(order).getInt(4) & 0xFFFFFFFFL;
            final long length = f.length();
            if (ifd0 < 8 || ifd0 + 2 > length) return fail(dng, "IFD 0 outside the file");
            f.seek(ifd0);
            final byte[] countBytes = new byte[2];
            f.readFully(countBytes);
            final int count = ByteBuffer.wrap(countBytes).order(order).getShort() & 0xFFFF;
            if (ifd0 + 2 + 12L * count + 4 > length) return fail(dng, "IFD 0 cut short");
            final byte[] entries = new byte[12 * count + 4];
            f.readFully(entries);
            final ByteBuffer e = ByteBuffer.wrap(entries).order(order);
            final List<byte[]> kept = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int tag = e.getShort(12 * i) & 0xFFFF;
                if (tag == TAG_SUB_IFDS) return fail(dng, "a SubIFDs tag already");
                final byte[] one = new byte[12];
                System.arraycopy(entries, 12 * i, one, 0, 12);
                kept.add(one);
            }
            final int nextIfd = e.getInt(12 * count);

            final byte[] app = software == null ? new byte[0] : (software + "\0").getBytes(StandardCharsets.US_ASCII);
            // the appended part: [pad] JPEG [pad] application name [pad] BitsPerSample [pad] preview IFD [pad] IFD 0 copy
            final long jpegAt = even(length);
            final long appAt = even(jpegAt + jpeg.length);
            final long bitsAt = even(appAt + app.length);
            final long previewAt = even(bitsAt + 6);
            final int previewEntries = 13 + (app.length > 0 ? 1 : 0);
            final long ifd0At = even(previewAt + 2 + 12L * previewEntries + 4);
            final long end = ifd0At + 2 + 12L * (count + 1) + 4;
            if (end > 0xFFFFFFFFL) return fail(dng, "past 4 GB");

            final ByteBuffer out = ByteBuffer.allocate((int) (end - length)).order(order);
            final int base = (int) length;
            out.position((int) (jpegAt - base));
            out.put(jpeg);
            out.position((int) (appAt - base));
            out.put(app);
            out.position((int) (bitsAt - base));
            out.putShort((short) 8).putShort((short) 8).putShort((short) 8);
            out.position((int) (previewAt - base));
            out.putShort((short) previewEntries);
            entry(out, 254, 4, 1, 1);                      // NewSubFileType: reduced-resolution image
            entry(out, 256, 4, 1, width);                  // ImageWidth
            entry(out, 257, 4, 1, height);                 // ImageLength
            entry(out, 258, 3, 3, (int) bitsAt);           // BitsPerSample 8, 8, 8
            entryShort(out, 259, 7);                       // Compression: JPEG
            entryShort(out, 262, 6);                       // PhotometricInterpretation: YCbCr
            entry(out, 273, 4, 1, (int) jpegAt);           // StripOffsets
            entryShort(out, 277, 3);                       // SamplesPerPixel
            entry(out, 278, 4, 1, height);                 // RowsPerStrip
            entry(out, 279, 4, 1, jpeg.length);            // StripByteCounts
            entryShort(out, 284, 1);                       // PlanarConfiguration: chunky
            // YCbCrSubSampling 2, 2 (Bitmap.compress: 4:2:0), in the entry
            out.putShort((short) 530).putShort((short) 3).putInt(2).putShort((short) 2).putShort((short) 2);
            if (app.length > 4) entry(out, 50966, 2, app.length, (int) appAt); // PreviewApplicationName
            else if (app.length > 0) { // a name of up to 3 characters fits the entry
                out.putShort((short) 50966).putShort((short) 2).putInt(app.length);
                final byte[] four = new byte[4];
                System.arraycopy(app, 0, four, 0, app.length);
                out.put(four);
            }
            entry(out, 50970, 4, 1, colourSpace);          // PreviewColorSpace
            out.putInt(0);
            // IFD 0 again with SubIFDs, sorted
            out.position((int) (ifd0At - base));
            out.putShort((short) (count + 1));
            boolean placed = false;
            for (byte[] one : kept) {
                final int tag = ByteBuffer.wrap(one).order(order).getShort(0) & 0xFFFF;
                if (!placed && tag > TAG_SUB_IFDS) {
                    entry(out, TAG_SUB_IFDS, 4, 1, (int) previewAt);
                    placed = true;
                }
                out.put(one);
            }
            if (!placed) entry(out, TAG_SUB_IFDS, 4, 1, (int) previewAt);
            out.putInt(nextIfd);

            f.seek(length);
            f.write(out.array());
            f.getFD().sync();
            // the switch: IFD 0's offset in the header
            final ByteBuffer offset = ByteBuffer.allocate(4).order(order);
            offset.putInt((int) ifd0At);
            f.seek(4);
            f.write(offset.array());
            return true;
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "DNG preview of " + dng + " not written: " + e);
            return false;
        }
    }

    private static long even(long at) {
        return (at + 1) & ~1L;
    }

    private static void entry(ByteBuffer out, int tag, int type, int count, int value) {
        out.putShort((short) tag).putShort((short) type).putInt(count).putInt(value);
    }

    private static void entryShort(ByteBuffer out, int tag, int value) {
        out.putShort((short) tag).putShort((short) 3).putInt(1).putShort((short) value).putShort((short) 0);
    }

    private static boolean fail(Path dng, String why) {
        Log.w(TAG, "DNG preview of " + dng + " not written: " + why);
        return false;
    }
}
