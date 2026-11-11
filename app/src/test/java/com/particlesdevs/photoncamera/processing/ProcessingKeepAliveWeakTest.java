package com.particlesdevs.photoncamera.processing;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.ref.WeakReference;
import java.util.function.BooleanSupplier;

/** P617: the keep-alive's busy check does not keep the camera's controller (and its activity) alive. */
public class ProcessingKeepAliveWeakTest {
    static final class Owner {
        boolean busy;
        final byte[] weight = new byte[1 << 20];
    }

    @Test
    public void ownerFlagsWhileItLivesThenOnlyTheStatics() throws InterruptedException {
        Owner o = new Owner();
        final boolean[] statics = {false};
        BooleanSupplier busy = ProcessingKeepAlive.weakly(o, x -> x.busy, () -> statics[0]);
        assertFalse(busy.getAsBoolean());
        o.busy = true;
        assertTrue(busy.getAsBoolean());
        WeakReference<Owner> ref = new WeakReference<>(o);
        o = null;
        for (int i = 0; i < 50 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }
        assertNull("the supplier did not keep its owner", ref.get());
        assertFalse(busy.getAsBoolean());
        statics[0] = true;
        assertTrue(busy.getAsBoolean());
    }
}
