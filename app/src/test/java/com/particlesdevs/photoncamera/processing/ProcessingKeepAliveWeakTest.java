package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.lang.ref.WeakReference;
import java.util.function.BooleanSupplier;

/**
 * P617: the keep-alive's busy check does not keep an idle camera controller (and its activity) alive; a controller with a shot
 * to finish stays in memory until the shot is processed (owner, 2026-11-11).
 */
public class ProcessingKeepAliveWeakTest {
    static final class Owner {
        volatile boolean busy;
        final byte[] weight = new byte[1 << 20];
    }

    @After
    public void clear() {
        ProcessingKeepAlive.pinned = null;
    }

    private static boolean collected(WeakReference<?> ref) throws InterruptedException {
        for (int i = 0; i < 50 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }
        return ref.get() == null;
    }

    @Test
    public void anIdleOwnerIsNotKept() throws InterruptedException {
        Owner o = new Owner();
        final boolean[] statics = {false};
        BooleanSupplier busy = ProcessingKeepAlive.weakly(o, x -> x.busy, () -> statics[0]);
        assertFalse(busy.getAsBoolean());
        WeakReference<Owner> ref = new WeakReference<>(o);
        o = null;
        assertTrue("an idle owner is not kept", collected(ref));
        assertFalse(busy.getAsBoolean());
        statics[0] = true;
        assertTrue(busy.getAsBoolean());
    }

    @Test
    public void aBusyOwnerStaysUntilItsWorkIsDone() throws InterruptedException {
        Owner o = new Owner();
        BooleanSupplier busy = ProcessingKeepAlive.weakly(o, x -> x.busy, () -> false);
        o.busy = true;
        assertTrue(busy.getAsBoolean());
        WeakReference<Owner> ref = new WeakReference<>(o);
        o = null;
        assertFalse("a closed camera with a shot to finish stays in memory", collected(ref));
        assertTrue(busy.getAsBoolean());
        ref.get().busy = false;
        assertFalse(busy.getAsBoolean());
        assertTrue("released once the work is done", collected(ref));
    }

    @Test
    public void theShutterPinsTheOwnerUntilACheckFindsNoWork() throws InterruptedException {
        Owner o = new Owner();
        BooleanSupplier busy = ProcessingKeepAlive.weakly(o, x -> x.busy, () -> false);
        o.busy = true;
        ProcessingKeepAlive.hold(null, o);
        assertNotNull(ProcessingKeepAlive.pinned);
        WeakReference<Owner> ref = new WeakReference<>(o);
        o = null;
        assertFalse(collected(ref));
        ref.get().busy = false;
        assertFalse(busy.getAsBoolean());
        assertNull(ProcessingKeepAlive.pinned);
        assertTrue(collected(ref));
    }

    /** P638: a camera pinned at its shutter and replaced by the next activity's controller goes once nothing is left to do. */
    @Test
    public void aReplacedOwnerIsReleasedWhenNoWorkIsLeft() throws InterruptedException {
        Owner old = new Owner();
        ProcessingKeepAlive.weakly(old, x -> x.busy, () -> false);
        ProcessingKeepAlive.hold(null, old); // its shutter
        Owner next = new Owner();
        BooleanSupplier busy = ProcessingKeepAlive.weakly(next, x -> x.busy, () -> false); // the activity was made again
        WeakReference<Owner> ref = new WeakReference<>(old);
        old = null;
        assertFalse(busy.getAsBoolean());
        assertNull("the former camera is not kept by the new one's idle check", ProcessingKeepAlive.pinned);
        assertTrue(collected(ref));
        assertNotNull(next);
    }
}
