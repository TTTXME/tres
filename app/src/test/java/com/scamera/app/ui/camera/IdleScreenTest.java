package com.scamera.app.ui.camera;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;
import android.view.Window;
import android.view.WindowManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/** P705: the camera screen keeps the display on only while it is used, and never lets it go during a shot. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class IdleScreenTest {

    private static boolean kept(Window w) {
        return (w.getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0;
    }

    private static void idle(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    @Test
    public void theIdleTimeAndTheNextLook() {
        assertEquals(180_000, IdleScreen.idleMs(3f));
        assertEquals(0, IdleScreen.idleMs(0f));
        assertEquals(0, IdleScreen.idleMs(-1f));
        assertEquals(0, IdleScreen.idleMs(Float.NaN));
        assertEquals(-1, IdleScreen.nextCheckMs(1_000_000, 0, false));
        assertEquals(80_000, IdleScreen.nextCheckMs(100_000, 180_000, false));
        assertEquals(0, IdleScreen.nextCheckMs(180_000, 180_000, false));
        assertEquals(IdleScreen.BUSY_RECHECK_MS, IdleScreen.nextCheckMs(500_000, 180_000, true));
    }

    @Test
    public void theFlagGoesAfterTheIdleTimeAndComesBackWithATouch() {
        final Window w = Robolectric.buildActivity(Activity.class).setup().get().getWindow();
        final boolean[] busy = {false};
        final IdleScreen idle = new IdleScreen(w, () -> busy[0]);
        idle.resume(1f);
        assertTrue(kept(w));
        idle(30_000);
        idle.touched();            // counted from here: 60 s more
        idle(45_000);
        assertTrue("45 s after the touch", kept(w));
        idle(20_000);
        assertFalse("60 s after the touch", kept(w));
        idle.touched();
        assertTrue("a touch keeps it on again", kept(w));
        idle(61_000);
        assertFalse(kept(w));
    }

    @Test
    public void aShotBeingProcessedKeepsItOn() {
        final Window w = Robolectric.buildActivity(Activity.class).setup().get().getWindow();
        final boolean[] busy = {true};
        final IdleScreen idle = new IdleScreen(w, () -> busy[0]);
        idle.resume(1f);
        idle(5 * 60_000);
        assertTrue("busy: kept", kept(w));
        busy[0] = false;
        idle(IdleScreen.BUSY_RECHECK_MS + 1);
        assertFalse("the shot done: let go at the next look", kept(w));
    }

    @Test
    public void pausedOrSwitchedOffItNeverLetsGo() {
        final Window w = Robolectric.buildActivity(Activity.class).setup().get().getWindow();
        final IdleScreen off = new IdleScreen(w, () -> false);
        off.resume(0f);
        idle(60 * 60_000);
        assertTrue("idle_screen_min 0: always on", kept(w));
        final IdleScreen paused = new IdleScreen(w, () -> false);
        paused.resume(1f);
        paused.pause();
        idle(10 * 60_000);
        assertTrue("no look after the pause", kept(w));
    }
}
