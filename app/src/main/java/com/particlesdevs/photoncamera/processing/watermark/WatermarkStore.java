package com.particlesdevs.photoncamera.processing.watermark;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * P633: where a photo keeps its watermark record (WatermarkPatch) - in the file itself, appended at its very end after the encoder
 * wrote it, in the form each container ignores, so no offset written before moves:
 * <ul>
 * <li>JPEG (Ultra HDR included): after the last image's EOI (the gain map's, whose MPF offsets count from the primary image),
 * as other cameras keep their extra data; decoders stop at EOI, ExifInterface copies what follows its scan;</li>
 * <li>WebP: a «SCWM» RIFF chunk at the end, the RIFF size grown by it (unknown chunks are skipped);</li>
 * <li>HEIC / AVIF (ISO BMFF): a top-level «uuid» box at the end (the item offsets point into mdat, before it).</li>
 * </ul>
 * Every form ends with the same 12 bytes - the record's length (big-endian) and «SCWMEND1» - so a reader finds it from the end
 * whatever the container. Kept in the file, it goes wherever the photo goes (a copy, a move, a backup) and needs no table of
 * our own; an app that re-encodes the photo drops it, and then the editor says the watermark cannot be taken off. The record
 * holds pixels of the photo, so whoever has the file can take the watermark off - as the owner can.
 */
public final class WatermarkStore {
    static final byte[] END = {'S', 'C', 'W', 'M', 'E', 'N', 'D', '1'};
    static final int FOOTER = 4 + END.length;
    /** The record's uuid box type (ISO BMFF): a random version-4 UUID of SCAMERA's watermark record. */
    static final byte[] UUID = {(byte) 0x5c, (byte) 0xa3, (byte) 0x7e, (byte) 0x1a, (byte) 0x9b, (byte) 0x0d, 0x4f, (byte) 0x83,
            (byte) 0xa6, 0x21, (byte) 0xe4, 0x58, 0x2f, (byte) 0xc7, 0x10, (byte) 0x9d};
    /** The largest record read back (bytes): a 200 MP frame's lossless border stays well under it. */
    static final long MAX_RECORD = 512L << 20;

    public enum Container {JPEG, WEBP, ISOBMFF, OTHER}

    private WatermarkStore() {}

    /** The container of a file starting with {@code head}. */
    public static Container sniff(byte[] head, int n) {
        if (n >= 3 && (head[0] & 255) == 0xFF && (head[1] & 255) == 0xD8 && (head[2] & 255) == 0xFF) return Container.JPEG;
        if (n >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F' && head[8] == 'W' && head[9] == 'E'
                && head[10] == 'B' && head[11] == 'P') return Container.WEBP;
        if (n >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') return Container.ISOBMFF;
        return Container.OTHER;
    }

    /** {@code record} appended to {@code file} in its container's form; false (the file untouched) for a container we do not know. */
    public static boolean append(File file, byte[] record) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file, "rw")) {
            byte[] head = new byte[16];
            f.seek(0);
            int n = f.read(head);
            Container c = sniff(head, Math.max(0, n));
            if (c == Container.OTHER) return false;
            final long end = f.length();
            ByteBuffer footer = ByteBuffer.allocate(FOOTER).order(ByteOrder.BIG_ENDIAN);
            footer.putInt(record.length).put(END);
            f.seek(end);
            switch (c) {
                case WEBP: {
                    // [SCWM][size LE][pad][record][footer]: the chunk's data even, the footer last
                    final int data = record.length + FOOTER;
                    final int pad = data & 1;
                    ByteBuffer h = ByteBuffer.allocate(8 + pad).order(ByteOrder.LITTLE_ENDIAN);
                    h.put(new byte[]{'S', 'C', 'W', 'M'}).putInt(data + pad);
                    f.write(h.array());
                    f.write(record);
                    f.write(footer.array());
                    long riff = f.length() - 8;
                    f.seek(4);
                    ByteBuffer size = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
                    size.putInt((int) riff);
                    f.write(size.array());
                    break;
                }
                case ISOBMFF: {
                    long box = 8L + UUID.length + record.length + FOOTER;
                    if (box > 0xFFFFFFFFL) return false;
                    ByteBuffer h = ByteBuffer.allocate(8 + UUID.length).order(ByteOrder.BIG_ENDIAN);
                    h.putInt((int) box).put(new byte[]{'u', 'u', 'i', 'd'}).put(UUID);
                    f.write(h.array());
                    f.write(record);
                    f.write(footer.array());
                    break;
                }
                default:
                    f.write(record);
                    f.write(footer.array());
            }
            return true;
        }
    }

    /** The record at the end of {@code channel} (a whole file), null when there is none. */
    @Nullable
    public static byte[] read(FileChannel channel) throws IOException {
        final long size = channel.size();
        if (size < FOOTER + 16) return null;
        ByteBuffer footer = ByteBuffer.allocate(FOOTER).order(ByteOrder.BIG_ENDIAN);
        readFully(channel, footer, size - FOOTER);
        footer.flip();
        long len = footer.getInt() & 0xFFFFFFFFL;
        byte[] end = new byte[END.length];
        footer.get(end);
        if (!java.util.Arrays.equals(end, END) || len <= 0 || len > MAX_RECORD || len > size - FOOTER - 16) return null;
        ByteBuffer rec = ByteBuffer.allocate((int) len);
        readFully(channel, rec, size - FOOTER - len);
        return rec.array();
    }

    private static void readFully(FileChannel ch, ByteBuffer b, long at) throws IOException {
        long pos = at;
        while (b.hasRemaining()) {
            int n = ch.read(b, pos);
            if (n < 0) throw new IOException("short read");
            pos += n;
        }
    }

    /** The record of {@code file}, null when there is none or it cannot be read. */
    @Nullable
    public static WatermarkPatch read(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] b = read(in.getChannel());
            return b == null ? null : WatermarkPatch.fromBytes(b);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** The record of the photo at {@code uri} (a seekable descriptor), null when there is none or it cannot be read. */
    @Nullable
    public static WatermarkPatch read(Context c, Uri uri) {
        try {
            ParcelFileDescriptor pfd = c.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) return null;
            try (FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                byte[] b = read(in.getChannel());
                return b == null ? null : WatermarkPatch.fromBytes(b);
            }
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }
}
