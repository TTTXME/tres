package com.scamera.app.control;

import android.app.Application;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;

import com.scamera.app.ui.camera.model.CameraFragmentModel;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P574 (owner, 2026-11-09: «верхняя и нижняя шторка в открытом состоянии закрываются противоположными жестами и при этом не
 * возникает никаких ошибок»): the camera screen's flings as real MotionEvent sequences through a GestureDetector wired as Swipe
 * wires it (observe every event, onFling to the Router), against a host that keeps the two sheets' state as the screen does.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class SwipeRouterTest {
    /** The camera screen in short: the shade's level, the top row's sheet, the LUT tray. */
    static final class Screen implements Swipe.Router.Host {
        int level = CameraFragmentModel.SHEET_HIDDEN;
        boolean settling, lut, top;
        float zone = 900;
        final List<String> calls = new ArrayList<>();

        @Override public int sheetLevel() { return level; }
        @Override public boolean shadeSettling() { return settling; }
        @Override public boolean lutOpen() { return lut; }
        @Override public boolean topRowOpen() { return top; }
        @Override public float topZoneBottom() { return zone; }
        @Override public void openTopRow() {
            calls.add("open");
            // as CameraFragment.openTopRowSheet: never twice, never over the shade
            if (!top && level == CameraFragmentModel.SHEET_HIDDEN && !settling) top = true;
        }
        @Override public void closeTopRow() { calls.add("close"); top = false; }
        @Override public void sheetUp() { calls.add("up"); if (level < CameraFragmentModel.SHEET_FULL) level++; }
        @Override public void sheetDown() { calls.add("down"); if (level > CameraFragmentModel.SHEET_HIDDEN) level--; }
        @Override public void stepLut(int direction) { calls.add("lut" + direction); }
    }

    private Screen screen;
    private Swipe.Router router;
    private GestureDetector detector;

    @Before
    public void setUp() {
        screen = new Screen();
        router = new Swipe.Router(RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().density, screen);
        detector = new GestureDetector(RuntimeEnvironment.getApplication(), new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) { return router.fling(e1, e2, vx, vy); }
        });
    }

    private void feed(MotionEvent e) {
        router.observe(e);
        detector.onTouchEvent(e);
    }

    /** A quick one-finger swipe from (x, y) by (dx, dy) in 100 ms. */
    private void swipe(float x, float y, float dx, float dy) {
        long t = SystemClock.uptimeMillis();
        feed(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0));
        for (int i = 1; i <= 5; i++) feed(MotionEvent.obtain(t, t + i * 20, MotionEvent.ACTION_MOVE, x + dx * i / 5, y + dy * i / 5, 0));
        feed(MotionEvent.obtain(t, t + 100, MotionEvent.ACTION_UP, x + dx, y + dy, 0));
    }

    @Test
    public void downFromTheTopOpensTheTopRowAndUpClosesItWithoutTheShade() {
        swipe(500, 300, 0, 400);
        assertTrue("hidden -> down from the top zone: the top row's sheet", screen.top);
        assertEquals(CameraFragmentModel.SHEET_HIDDEN, screen.level);
        swipe(500, 900, 0, -400);
        assertFalse("top open -> up: closed", screen.top);
        assertEquals("the shade stays hidden", CameraFragmentModel.SHEET_HIDDEN, screen.level);
        assertFalse(screen.calls.contains("up"));
    }

    @Test
    public void aSwipeDownBelowTheTopZoneOpensNothing() {
        swipe(500, 1200, 0, 400);
        assertFalse(screen.top);
        assertEquals(CameraFragmentModel.SHEET_HIDDEN, screen.level);
    }

    @Test
    public void withTheShadeUpASwipeDownLowersItAndTheTopSheetStaysClosed() {
        screen.level = CameraFragmentModel.SHEET_FULL;
        swipe(500, 300, 0, 400);
        assertEquals("FULL -> down -> PEEK", CameraFragmentModel.SHEET_PEEK, screen.level);
        assertFalse(screen.top);
        swipe(500, 300, 0, 400);
        assertEquals("PEEK -> down -> HIDDEN", CameraFragmentModel.SHEET_HIDDEN, screen.level);
        assertFalse("the swipe that lowered the shade opened nothing", screen.top);
        assertFalse(screen.calls.contains("open"));
    }

    @Test
    public void aSettlingShadeOrAnOpenLutTrayKeepsTheTopSheetClosed() {
        screen.settling = true;
        swipe(500, 300, 0, 400);
        assertFalse(screen.top);
        screen.settling = false;
        screen.lut = true;
        swipe(500, 300, 0, 400);
        assertFalse(screen.top);
    }

    @Test
    public void aPinchFlingsNothing() {
        long t = SystemClock.uptimeMillis();
        feed(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 500, 300, 0));
        MotionEvent.PointerProperties[] pp = {new MotionEvent.PointerProperties(), new MotionEvent.PointerProperties()};
        pp[0].id = 0;
        pp[1].id = 1;
        MotionEvent.PointerCoords[] pc = {new MotionEvent.PointerCoords(), new MotionEvent.PointerCoords()};
        pc[0].x = 500; pc[0].y = 300; pc[1].x = 500; pc[1].y = 600;
        feed(MotionEvent.obtain(t, t + 10, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, pp, pc, 0, 0, 1, 1, 0, 0, 0, 0));
        pc[1].y = 900;
        feed(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_MOVE, 2, pp, pc, 0, 0, 1, 1, 0, 0, 0, 0));
        feed(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, pp, pc, 0, 0, 1, 1, 0, 0, 0, 0));
        for (int i = 1; i <= 3; i++) feed(MotionEvent.obtain(t, t + 60 + i * 15, MotionEvent.ACTION_MOVE, 500, 300 + i * 120, 0));
        feed(MotionEvent.obtain(t, t + 120, MotionEvent.ACTION_UP, 500, 660, 0));
        assertTrue("no fling acted: " + screen.calls, screen.calls.isEmpty());
        assertFalse(screen.top);
    }

    @Test
    public void rapidAlternationStaysConsistent() {
        for (int i = 0; i < 6; i++) {
            swipe(500, 300, 0, 400);   // opens the top sheet
            swipe(500, 300, 0, 400);   // a second down while it is open: no second sheet, no shade
            assertTrue(screen.top);
            swipe(500, 900, 0, -400);  // up: closes it
            assertFalse(screen.top);
            swipe(500, 900, 0, -400);  // up with nothing open: the shade
            assertEquals(CameraFragmentModel.SHEET_PEEK, screen.level);
            swipe(500, 300, 0, 400);   // down with the shade up: lowers it, no top sheet
            assertEquals(CameraFragmentModel.SHEET_HIDDEN, screen.level);
            assertFalse(screen.top);
        }
        long opens = screen.calls.stream().filter("open"::equals).count();
        assertEquals("one opening per round", 6, opens);
    }

    @Test
    public void sidewaysSwipesStillStepTheLut() {
        swipe(200, 600, 500, 20);
        swipe(800, 600, -500, 20);
        assertEquals(java.util.Arrays.asList("lut-1", "lut1"), screen.calls);
        assertFalse(screen.top);
    }
}
