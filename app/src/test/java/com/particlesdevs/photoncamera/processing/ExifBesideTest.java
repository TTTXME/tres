package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.particlesdevs.photoncamera.processing.color.IccEmbed;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** P636: the EXIF block made beside the JPEG encode gives the file the encode-after-the-block gave. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public class ExifBesideTest {
    /** SOI, a JFIF APP0, an encoder's own EXIF APP1, DQT-like data, EOI. */
    static byte[] jpeg() {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xFF); o.write(0xD8);
        o.write(0xFF); o.write(0xE0); o.write(0); o.write(7); o.write('J'); o.write('F'); o.write('I'); o.write('F'); o.write(0);
        o.write(0xFF); o.write(0xE1); o.write(0); o.write(10); for (char c : "Exif".toCharArray()) o.write(c); o.write(0); o.write(0); o.write(1); o.write(2);
        o.write(0xFF); o.write(0xDB); o.write(0); o.write(4); o.write(9); o.write(8);
        for (int i = 0; i < 5000; i++) o.write(i * 7);
        o.write(0xFF); o.write(0xD9);
        return o.toByteArray();
    }

    static byte[] app1() {
        final byte[] b = new byte[40];
        b[0] = (byte) 0xFF; b[1] = (byte) 0xE1; b[2] = 0; b[3] = 38;
        System.arraycopy("Exif".getBytes(), 0, b, 4, 4);
        for (int i = 10; i < b.length; i++) b[i] = (byte) (i * 3);
        return b;
    }

    /** The encoder's writes: the header in small pieces, then the rest. */
    static void encode(OutputStream to, byte[] jpeg) throws Exception {
        to.write(jpeg[0]);
        to.write(jpeg, 1, 6);
        to.write(jpeg, 7, 30);
        to.write(jpeg, 37, jpeg.length - 37);
    }

    static byte[] before(byte[] app1, byte[] icc) throws Exception {
        // ImageSaver before P636: the encoder's own stream without a block and a profile, else the inserting stream, closed
        final ByteArrayOutputStream file = new ByteArrayOutputStream();
        if (app1 == null && icc == null) {
            encode(file, jpeg());
        } else {
            final OutputStream tagged = IccEmbed.jpegInserting(file, app1, icc);
            encode(tagged, jpeg());
            tagged.close();
        }
        return file.toByteArray();
    }

    static byte[] now(byte[] app1, byte[] icc, boolean beside) throws Exception {
        final ExifBeside exif = new ExifBeside(() -> app1, beside);
        final ByteArrayOutputStream file = new ByteArrayOutputStream();
        final ExifBeside.Stream tagged = exif.stream(file, icc);
        encode(tagged, jpeg());
        tagged.finish();
        assertArrayEquals(app1, exif.await());
        assertEquals(beside, exif.beside());
        return file.toByteArray();
    }

    @Test public void theFileIsTheOneOfTheBlockBeforeTheEncode() throws Exception {
        final byte[] icc = new byte[700];
        for (int i = 0; i < icc.length; i++) icc[i] = (byte) (i * 11);
        for (byte[] app1 : new byte[][]{app1(), null})
            for (byte[] profile : new byte[][]{null, icc})
                for (boolean beside : new boolean[]{true, false})
                    assertArrayEquals("block " + (app1 != null) + " profile " + (profile != null) + " beside " + beside,
                            before(app1, profile), now(app1, profile, beside));
        // without a block and a profile the encoder's bytes are the file's (no stream in between drops or moves a segment)
        assertArrayEquals(jpeg(), now(null, null, true));
    }

    @Test public void theFirstByteWaitsForTheBlockMadeOnAnotherThread() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Thread> maker = new AtomicReference<>();
        final ExifBeside exif = new ExifBeside(() -> {
            maker.set(Thread.currentThread());
            release.await(5, TimeUnit.SECONDS);
            return app1();
        }, true);
        final ByteArrayOutputStream file = new ByteArrayOutputStream();
        final ExifBeside.Stream tagged = exif.stream(file, null);
        // the encode starts (and here ends its strips) while the block is still being made: nothing is written yet
        assertEquals(0, file.size());
        final Thread letGo = new Thread(() -> {
            try { Thread.sleep(60); } catch (InterruptedException ignored) { }
            release.countDown();
        });
        letGo.start();
        encode(tagged, jpeg());
        tagged.finish();
        letGo.join();
        assertTrue(maker.get() != null && maker.get() != Thread.currentThread());
        assertArrayEquals(before(app1(), null), file.toByteArray());
        assertTrue(exif.waitedMs() >= 30 && exif.blockMs() >= 30);
    }

    @Test public void aMakerThatFailsLeavesTheFileWithoutABlock() throws Exception {
        for (boolean beside : new boolean[]{true, false}) {
            final ExifBeside exif = new ExifBeside(() -> { throw new IllegalStateException("no block"); }, beside);
            final ByteArrayOutputStream file = new ByteArrayOutputStream();
            final ExifBeside.Stream tagged = exif.stream(file, null);
            encode(tagged, jpeg());
            tagged.finish();
            assertNull(exif.await());
            assertArrayEquals(jpeg(), file.toByteArray());
        }
        // not beside: the block is made by the constructor on the caller's thread
        final Thread[] seen = {null};
        final ExifBeside inline = new ExifBeside(() -> { seen[0] = Thread.currentThread(); return null; }, false);
        assertTrue(seen[0] == Thread.currentThread());
        assertFalse(inline.beside());
    }

    @Test public void anEncodeThatWritesNothingLeavesNothing() throws Exception {
        final ExifBeside exif = new ExifBeside(ExifBesideTest::app1, true);
        final ByteArrayOutputStream file = new ByteArrayOutputStream();
        final ExifBeside.Stream tagged = exif.stream(file, null);
        tagged.finish();
        final ByteArrayOutputStream was = new ByteArrayOutputStream();
        IccEmbed.jpegInserting(was, app1(), null).close();
        assertArrayEquals(was.toByteArray(), file.toByteArray());
    }
}
