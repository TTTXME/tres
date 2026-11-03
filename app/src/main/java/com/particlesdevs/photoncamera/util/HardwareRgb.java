package com.particlesdevs.photoncamera.util;

import java.nio.ByteBuffer;

/**
 * P482 (ahb_transport, scam-ahb.cpp): the merge worker's half-float RGB in an AHardwareBuffer of the app instead of the output memfd.
 * The app allocates it, sends its handle over the job's unix socket (the worker's fast merge writes the halves into it on the GPU) and
 * takes it back as a CPU view of the locked buffer ({@link #view}); ScamRgb's input imports that view into the post's GL context
 * ({@link #importGl}) instead of uploading its bytes. {@link Allocator#free} of a view waits for its {@link #fence}, unlocks and
 * releases the buffer.
 */
public final class HardwareRgb {
    static { System.loadLibrary("allocator"); }

    private HardwareRgb() {}

    /** A BLOB hardware buffer of {@code bytes} (GPU data, CPU read often); 0 when refused. */
    public static native long allocate(long bytes);
    /** Its handle over a unix socket (the other end's process receives its own reference). */
    public static native boolean send(long handle, int socketFd);
    /** A handle no view took. */
    public static native void release(long handle);
    /** The buffer locked for CPU reads as a direct buffer of {@code bytes}; the view owns the handle from here. Null: not locked. */
    public static native ByteBuffer view(long handle, int bytes);
    /** Whether {@code buffer} is such a view. */
    public static native boolean isView(ByteBuffer buffer);
    /** The view's buffer as a new GL buffer object (storage buffer) of the current context; 0 when refused. */
    public static native int importGl(ByteBuffer view);
    /** An EGL fence after this thread's GL reads of the view, waited for by its free; false: none (finish the GL work first). */
    public static native boolean fence(ByteBuffer view);
}
