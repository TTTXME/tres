package com.particlesdevs.photoncamera.processing;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.processing.color.IccEmbed;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Callable;

/**
 * P636 (speed, the same file): a JPEG's EXIF APP1 block made beside the encode. The block (ExifBlock.app1Segment: a temporary
 * file through ExifInterface, and the 512 px thumbnail read out of the photo) took 74 - 99 ms before every bitmap-route
 * encode (OPPO PHY110 12 MP, Xiaomi 17 Ultra 50 MP); the strip encoder writes its first byte only when the strips are done, so
 * the block is made on a helper thread meanwhile and {@link Stream} takes it at the first byte written. The stream is then
 * what ImageSaver made before: the encoder's own bytes when there is neither a block nor a profile, else
 * IccEmbed.jpegInserting with them - the same bytes in the same places. {@code beside} false: the block is made in the
 * constructor, on the caller's thread, as before.
 */
public final class ExifBeside {
    private final Callable<byte[]> maker;
    private final boolean beside;
    private final Thread helper;
    private byte[] block;
    private Throwable failure;
    private long blockNanos, waitedNanos;
    private boolean done;

    public ExifBeside(Callable<byte[]> maker, boolean beside) {
        this.maker = maker;
        this.beside = beside;
        if (beside) {
            helper = new Thread(this::make, "SCAMERA-exif");
            helper.start();
        } else {
            helper = null;
            make();
        }
    }

    private void make() {
        final long started = System.nanoTime();
        byte[] made = null;
        Throwable thrown = null;
        try {
            made = maker.call();
        } catch (Throwable t) {
            thrown = t;
        }
        if (thrown != null && !(thrown instanceof Error)) android.util.Log.w("ExifBeside", "EXIF block failed: " + thrown);
        synchronized (this) {
            block = made;
            failure = thrown;
            blockNanos = System.nanoTime() - started;
            done = true;
            notifyAll();
        }
    }

    /**
     * The block (null: none, the file gets its EXIF by the rewrite), waiting for the helper. A maker that threw gives no block
     * (the photo is saved, its EXIF by the rewrite); its Error is rethrown.
     */
    @Nullable
    public synchronized byte[] await() {
        final long started = System.nanoTime();
        boolean interrupted = false;
        while (!done) {
            try {
                wait();
            } catch (InterruptedException e) {
                interrupted = true; // the helper reads the caller's bitmap: waited for in any case
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        waitedNanos += System.nanoTime() - started;
        if (failure instanceof Error) throw (Error) failure;
        return block;
    }

    public boolean beside() { return beside; }

    /** The maker's own milliseconds. */
    public synchronized long blockMs() { return blockNanos / 1000000; }

    /** The milliseconds callers waited for the block. */
    public synchronized long waitedMs() { return waitedNanos / 1000000; }

    /** The encoder's stream into {@code out}; {@link Stream#finish()} after the encode. */
    public Stream stream(OutputStream out, @Nullable byte[] icc) {
        return new Stream(out, icc);
    }

    /** Chooses its target at the first byte: {@code out} itself without a block and a profile, else the inserting stream. */
    public final class Stream extends OutputStream {
        private final OutputStream out;
        private final byte[] icc;
        private OutputStream target;
        private boolean inserting;

        Stream(OutputStream out, @Nullable byte[] icc) {
            this.out = out;
            this.icc = icc;
        }

        private OutputStream target() {
            if (target == null) {
                final byte[] app1 = await();
                inserting = icc != null || app1 != null;
                target = inserting ? IccEmbed.jpegInserting(out, app1, icc) : out;
            }
            return target;
        }

        @Override public void write(int b) throws IOException { target().write(b); }

        @Override public void write(byte[] b, int off, int len) throws IOException { target().write(b, off, len); }

        @Override public void flush() throws IOException { if (target != null) target.flush(); }

        /** Completes the inserting stream's header (its close; {@code out} stays open), as ImageSaver did after the encode. */
        public void finish() throws IOException {
            if (target() != out) target.close();
        }

        @Override public void close() throws IOException { finish(); }
    }
}
