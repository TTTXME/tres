package com.scamera.app.processing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Random;

/** P681: the streamed JPEG's chunked collection keeps every byte in order across chunk boundaries (StreamedJpegSink). */
public class ChunkedBytesTest {
    @Test public void sameBytesAsAnArrayStream() throws Exception {
        final Random random = new Random(681);
        for (int round = 0; round < 6; round++) {
            final ChunkedBytes chunks = new ChunkedBytes();
            final ByteArrayOutputStream array = new ByteArrayOutputStream();
            final int total = round == 0 ? 0 : round == 1 ? ChunkedBytes.CHUNK : random.nextInt(5 * ChunkedBytes.CHUNK);
            int written = 0;
            while (written < total) {
                if (random.nextInt(8) == 0) {
                    final int b = random.nextInt(256);
                    chunks.write(b);
                    array.write(b);
                    written++;
                    continue;
                }
                final byte[] piece = new byte[1 + random.nextInt(round == 5 ? 3 * ChunkedBytes.CHUNK : 70_000)];
                random.nextBytes(piece);
                final int off = random.nextInt(piece.length), len = Math.min(total - written, random.nextInt(piece.length - off + 1));
                chunks.write(piece, off, len);
                array.write(piece, off, len);
                written += len;
            }
            assertEquals(array.size(), chunks.size());
            assertArrayEquals(array.toByteArray(), chunks.toByteArray());
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            chunks.writeTo(out);
            assertArrayEquals("round " + round, array.toByteArray(), out.toByteArray());
        }
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void aWriteOutsideItsArrayIsRefused() {
        new ChunkedBytes().write(new byte[4], 2, 3);
    }
}
