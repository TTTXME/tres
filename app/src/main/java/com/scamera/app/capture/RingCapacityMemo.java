package com.scamera.app.capture;

import java.util.function.IntSupplier;

/**
 * P707: the ZSL ring's capacity ({@link CaptureController#zslRingCapacity}) for the per-frame paths - the ring's trim after each
 * RAW frame and the ring metadata's trim after each viewfinder result. The capacity reads several stored settings (the «Кадры
 * ZSL» count, the merge's frame count, «Движение», Oldstyle, the YUV route) and built a few strings each time; the camera thread
 * asked for it three to five times a frame. It is read again at most every {@link #TTL_NS}, so a changed setting or a merge that
 * starts (its smaller plain ring) takes effect within that time instead of on the next frame. Not thread-safe: the caller holds
 * its lock (the ring's).
 */
final class RingCapacityMemo {
    /** How long a read capacity serves the per-frame paths. */
    static final long TTL_NS = 250_000_000L;

    private final IntSupplier source;
    private int value;
    private long readNs;
    private boolean valid;

    RingCapacityMemo(IntSupplier source) {
        this.source = source;
    }

    /** The capacity read within {@link #TTL_NS} before {@code nowNs} (System.nanoTime), else read now. */
    int get(long nowNs) {
        if (!valid || nowNs - readNs >= TTL_NS || nowNs < readNs) {
            value = source.getAsInt();
            readNs = nowNs;
            valid = true;
        }
        return value;
    }

    /** The next {@link #get} reads the capacity again (a new session, a closed camera). */
    void invalidate() {
        valid = false;
    }
}
