package com.scamera.app.ui.camera;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** P705: the preview results post the HUD runnable to the main thread only when it has something to do. */
public class ViewfinderHudPostTest {

    @Test
    public void anAfChangeAlwaysGoes() {
        assertTrue(ViewfinderHud.postDue(0, true, 0, false, false));
        assertTrue(ViewfinderHud.postDue(10, true, 3, false, false));
    }

    @Test
    public void withTheDataOffNothingIsPostedWhileNothingChanged() {
        for (long since : new long[]{0, 49, 50, 1000, 60_000})
            assertFalse("since " + since, ViewfinderHud.postDue(since, false, 0, false, false));
    }

    @Test
    public void theDataShownTheTurnOrADrawingToClearPostAtMostEvery50Ms() {
        for (int data : new int[]{1, 2, 3}) {
            assertFalse(ViewfinderHud.postDue(49, false, data, false, false));
            assertTrue(ViewfinderHud.postDue(50, false, data, false, false));
        }
        assertFalse(ViewfinderHud.postDue(20, false, 0, true, false));
        assertTrue("the controls turned", ViewfinderHud.postDue(50, false, 0, true, false));
        assertTrue("the overlay still holds the data switched off", ViewfinderHud.postDue(50, false, 0, false, true));
    }
}
