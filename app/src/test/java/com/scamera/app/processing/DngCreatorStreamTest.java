package com.scamera.app.processing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * P681: DngCreator.writeBuffer streams the native writer's three parts (header and metadata, the strip in place, the IFD) - the
 * file is their concatenation whether the stream is ImageSaver's file channel (the strip written from the native buffer itself)
 * or any other stream (in pieces), the strip buffer untouched. tools/check_dng_stream.cpp proves the parts are the bytes of the
 * in-memory file the writer built before (16-bit and lossless JPEG, with and without the GainMap opcodes, the Exif IFD, ...).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, shadows = DngCreatorStreamTest.NoNativeDng.class,
        instrumentedPackages = "com.scamera.app.processing")
public class DngCreatorStreamTest {
    @Implements(DngCreator.class)
    public static class NoNativeDng {
        @Implementation protected static void __staticInitializer__() { }
    }

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static byte[] bytes(int n, long seed) {
        final byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static ByteBuffer direct(byte[] content) {
        final ByteBuffer b = ByteBuffer.allocateDirect(content.length).order(ByteOrder.nativeOrder());
        b.put(content);
        b.clear();
        b.position(1); // a caller's position: the strip is written whole all the same
        return b;
    }

    private static byte[] concat(byte[]... parts) {
        final ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] p : parts) all.write(p, 0, p.length);
        return all.toByteArray();
    }

    @Test public void theFileIsHeadStripTailOnEveryStream() throws Exception {
        for (int stripBytes : new int[]{2, 4097, (3 << 20) + 11, 2 * 4080 * 64}) {
            final byte[] head = bytes(476, stripBytes), tail = bytes(stripBytes % 2 == 1 ? 451 : 450, stripBytes + 1);
            final byte[] content = bytes(stripBytes, stripBytes + 2);
            final ByteBuffer strip = direct(content);
            final byte[] expected = concat(head, content, tail);

            final ByteArrayOutputStream plain = new ByteArrayOutputStream();
            DngCreator.writeParts(plain, head, strip, tail);
            assertArrayEquals("a plain stream, " + stripBytes, expected, plain.toByteArray());

            final Path file = tmp.newFile("strip-" + stripBytes + ".dng").toPath();
            assertTrue(ImageSaver.Util.writeDng(file, out -> {
                assertTrue("ImageSaver's stream is the file channel", out instanceof DngCreator.ChannelSink);
                DngCreator.writeParts(out, head, strip, tail);
            }, "DNG"));
            assertArrayEquals("the file channel, " + stripBytes, expected, Files.readAllBytes(file));
            assertEquals("the strip buffer untouched", 1, strip.position());
            assertEquals(content.length, strip.limit());
        }
    }

    @Test public void theChannelSinkWritesEveryByte() throws Exception {
        final Path file = tmp.newFile("sink.bin").toPath();
        final byte[] data = bytes(70_001, 7);
        try (DngCreator.ChannelSink sink = new DngCreator.ChannelSink(java.nio.channels.FileChannel.open(file,
                java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING))) {
            sink.write(data[0]);
            sink.write(data, 1, 30_000);
            sink.write(data, 30_001, data.length - 30_001);
        }
        assertArrayEquals(data, Files.readAllBytes(file));
    }
}
