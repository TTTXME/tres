package com.scamera.app.ui.camera;

import android.app.Activity;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The grabbers' touch area: at least 48dp each way around what is drawn, inside the parent, the children next to it first. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class TouchTargetsTest {
    @Test
    public void theAreaGrowsToTheMinimumInsideTheParent() {
        // 56 x 12 at the parent's top: the growth goes down
        assertEquals(new Rect(100, 0, 156, 48), TouchTargets.area(100, 0, 156, 12, 400, 200, 48));
        // 56 x 18 at the parent's bottom: the growth goes up
        assertEquals(new Rect(100, 152, 156, 200), TouchTargets.area(100, 182, 156, 200, 400, 200, 48));
        // in the middle: centred, the width already enough
        assertEquals(new Rect(100, 79, 156, 127), TouchTargets.area(100, 97, 156, 109, 400, 200, 48));
    }

    @Test
    public void aTapBesideTheThinGrabberReachesItButAButtonThereKeepsItsTap() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout parent = new FrameLayout(a);
        a.setContentView(parent);
        View grab = new View(a);
        boolean[] grabbed = {false}, pressed = {false};
        grab.setOnClickListener(v -> grabbed[0] = true);
        View button = new View(a);
        button.setOnClickListener(v -> pressed[0] = true);
        parent.addView(grab, new FrameLayout.LayoutParams(56, 12));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(20, 20);
        bp.leftMargin = 0;
        bp.topMargin = 30;
        parent.addView(button, bp);
        parent.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY));
        parent.layout(0, 0, 400, 200);
        TouchTargets.ensure(grab, 48);
        tap(parent, 40, 40);   // below the drawn grabber, inside its area
        assertTrue(grabbed[0]);
        grabbed[0] = false;
        tap(parent, 10, 40);   // on the button inside the area: the button's
        assertTrue(pressed[0]);
        assertFalse(grabbed[0]);
    }

    private static void tap(View v, float x, float y) {
        long t = SystemClock.uptimeMillis();
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0));
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 30, MotionEvent.ACTION_UP, x, y, 0));
        org.robolectric.shadows.ShadowLooper.idleMainLooper();   // the click is posted
    }
}
