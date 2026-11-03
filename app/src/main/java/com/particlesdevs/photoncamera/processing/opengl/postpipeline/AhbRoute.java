package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.util.HardwareRgb;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * P482 (ahb_transport, scam_dev): one merge job's RGB in a hardware buffer of the app instead of the output memfd. A unix socket
 * pair: the worker inherits one end (job marker rgb-ahb = its fd), the app allocates the buffer (half-float RGB, 6 B a pixel) on
 * a thread of its own while the worker merges and sends its handle on the other end; the worker's fast merge writes the halves
 * into it on the GPU and answers one byte before its completion line ('A': the RGB is there, the memfd holds the trailers only;
 * 'M': the memfd as before). The worker skips the 72 MB copy into the memfd, the client its read, ScamRgb its upload
 * (HalfRgbUpload imports the buffer). Off ("ahb_transport 0"), a merged DNG, above 16 MP, not the fast merge, the root worker:
 * the memfd exactly as before.
 */
final class AhbRoute implements Closeable {
    /** The worker's answer: the RGB in the hardware buffer. */
    static final int IN_BUFFER = 'A';
    /** The worker's answer: the RGB in the memfd (or no answer). */
    static final int IN_MEMFD = 'M';

    /** The worker's end, passed to it as an inherited fd (closed here after the spawn or with the route). */
    final ParcelFileDescriptor workerEnd;
    private final ParcelFileDescriptor appEnd;
    private Thread sender;
    private volatile long handle;
    private volatile String note = "";
    private boolean taken;

    private AhbRoute(ParcelFileDescriptor workerEnd, ParcelFileDescriptor appEnd) {
        this.workerEnd = workerEnd;
        this.appEnd = appEnd;
    }

    /** Whether the route is on for a job: the switch, a half RGB of at most 16 MP without merged DNG, the fast merge in the tuning. */
    static boolean wanted(boolean hybrid, boolean rgbHalf, long outputPixels, boolean mergedDng, String tuning) {
        return hybrid && rgbHalf && outputPixels > 0 && outputPixels <= ScamHybridBurst.CLASSIC_INPUT_PIXELS && !mergedDng
                && tuningValue(tuning, "fastMerge") > 0
                && com.particlesdevs.photoncamera.settings.PreferenceKeys.scamDevSwitch("ahb_transport", false); // P482: off until the post-side faults are solved (PLAN P482)
    }

    /** The last value of a tuning line {@code name} (the worker reads the last one), 0 when none or not a number. */
    static int tuningValue(String tuning, String name) {
        int value = 0;
        if (tuning == null) return 0;
        for (String line : tuning.split("\n")) {
            final String t = line.trim();
            if (!t.startsWith(name + " ")) continue;
            try { value = (int) Float.parseFloat(t.substring(name.length() + 1).trim()); }
            catch (NumberFormatException e) { value = 0; }
        }
        return value;
    }

    /** A new socket pair, or null (the memfd route then). */
    @Nullable
    static AhbRoute create() {
        final FileDescriptor a = new FileDescriptor(), b = new FileDescriptor();
        try {
            Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_SEQPACKET | OsConstants.SOCK_CLOEXEC, 0, a, b);
        } catch (Exception | LinkageError e) {
            return null;
        }
        try {
            return new AhbRoute(ParcelFileDescriptor.dup(b), ParcelFileDescriptor.dup(a));
        } catch (IOException e) {
            return null;
        } finally {
            try { Os.close(a); } catch (Exception ignored) {}
            try { Os.close(b); } catch (Exception ignored) {}
        }
    }

    /** The worker took its end (spawned): this process's copy goes. */
    void spawned() {
        try { workerEnd.close(); } catch (IOException ignored) {}
    }

    /** The buffer of {@code bytes} allocated and its handle sent on a thread of its own; a failure closes the sending side. */
    synchronized void start(long bytes) {
        if (sender != null) return;
        sender = new Thread(() -> {
            final long t0 = System.nanoTime();
            long h = 0;
            try { h = HardwareRgb.allocate(bytes); } catch (LinkageError e) { h = 0; }
            final long t1 = System.nanoTime();
            boolean sent = false;
            if (h != 0) {
                handle = h;
                try { sent = HardwareRgb.send(h, appEnd.getFd()); } catch (LinkageError e) { sent = false; }
            }
            // no buffer: the worker's wait ends at once (its end reads the closed side), the memfd route
            if (!sent) try { Os.shutdown(appEnd.getFileDescriptor(), OsConstants.SHUT_WR); } catch (Exception ignored) {}
            note = (h == 0 ? "not allocated" : sent ? "sent" : "not sent") + " (" + (bytes >> 20) + " MB, allocated in "
                    + (t1 - t0) / 1_000_000 + " ms, sent in " + (System.nanoTime() - t1) / 1_000_000 + " ms)";
        }, "SCAMERA-ahb-rgb");
        sender.setDaemon(true);
        sender.setPriority(4); // the processing thread's (Java 4: nice 10), below the viewfinder
        sender.start();
    }

    /** What the allocation thread did (for the job's report). */
    String note() {
        join();
        return note;
    }

    /** The worker's answer ({@link #IN_BUFFER} / {@link #IN_MEMFD}), waiting at most {@code waitMs} for it; 0: none. */
    int answer(int waitMs) {
        try {
            final StructPollfd p = new StructPollfd();
            p.fd = appEnd.getFileDescriptor();
            p.events = (short) OsConstants.POLLIN;
            if (Os.poll(new StructPollfd[]{p}, waitMs) <= 0 || (p.revents & OsConstants.POLLIN) == 0) return 0;
            final byte[] b = new byte[1];
            return Os.read(appEnd.getFileDescriptor(), b, 0, 1) == 1 ? b[0] & 0xff : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** The RGB (its first {@code bytes}) as a CPU view of the locked buffer, which owns the buffer from here; null: none. */
    @Nullable
    synchronized ByteBuffer take(int bytes) {
        join();
        final long h = handle;
        if (h == 0 || taken) return null;
        ByteBuffer view;
        try { view = HardwareRgb.view(h, bytes); } catch (LinkageError e) { view = null; }
        if (view != null) { taken = true; handle = 0; }
        return view;
    }

    private void join() {
        final Thread t;
        synchronized (this) { t = sender; }
        if (t == null) return;
        boolean interrupted = false;
        for (;;) {
            try { t.join(); break; } catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /** The sockets closed, a buffer no view took released. */
    @Override public void close() {
        join();
        synchronized (this) {
            if (!taken && handle != 0) {
                try { HardwareRgb.release(handle); } catch (LinkageError ignored) {}
                handle = 0;
            }
        }
        try { workerEnd.close(); } catch (IOException ignored) {}
        try { appEnd.close(); } catch (IOException ignored) {}
    }
}
