package com.scamera.app.processing;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * P681: an encoded file collected in chunks of {@link #CHUNK} bytes instead of one growing array: the streamed JPEG used a
 * ByteArrayOutputStream of 8 MB that doubled past it (16, 32 MB with a copy each time) and toByteArray copied the file once more.
 * Here every byte is copied once on the way in and written out from where it is; the bytes are the same, in the same order.
 */
public final class ChunkedBytes extends OutputStream {
    /** Bytes of one chunk (the size of the writes of the file as well). */
    public static final int CHUNK = 1 << 20;
    private final List<byte[]> chunks = new ArrayList<>();
    /** Bytes used of the last chunk. */
    private int last = CHUNK;
    private long size;

    @Override
    public void write(int b) {
        if (last == CHUNK) grow();
        chunks.get(chunks.size() - 1)[last++] = (byte) b;
        size++;
    }

    @Override
    public void write(byte[] b, int off, int len) {
        if (off < 0 || len < 0 || off + len > b.length || off + len < 0) throw new IndexOutOfBoundsException();
        while (len > 0) {
            if (last == CHUNK) grow();
            final int n = Math.min(len, CHUNK - last);
            System.arraycopy(b, off, chunks.get(chunks.size() - 1), last, n);
            last += n;
            off += n;
            len -= n;
            size += n;
        }
    }

    private void grow() {
        chunks.add(new byte[CHUNK]);
        last = 0;
    }

    /** Bytes written. */
    public long size() {
        return size;
    }

    /** Writes every byte to {@code out}, a chunk at a time. */
    public void writeTo(OutputStream out) throws IOException {
        for (int i = 0; i < chunks.size(); i++) {
            final int n = i == chunks.size() - 1 ? last : CHUNK;
            if (n > 0) out.write(chunks.get(i), 0, n);
        }
    }

    /** One array of every byte (tests, small files). */
    public byte[] toByteArray() {
        if (size > Integer.MAX_VALUE - 8) throw new OutOfMemoryError(size + " bytes");
        final byte[] all = new byte[(int) size];
        int at = 0;
        for (int i = 0; i < chunks.size(); i++) {
            final int n = i == chunks.size() - 1 ? last : CHUNK;
            System.arraycopy(chunks.get(i), 0, all, at, n);
            at += n;
        }
        return all;
    }

}
