package com.particlesdevs.photoncamera.ui.controls;

import android.app.Application;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P574: the shared slider as the camera screen uses it - a tap jumps where the finger is, a double tap goes to the default and
 * stays there (the second tap's finger-up is no jump), a hold is the view's long click, a value chosen in code as the user's is
 * told with {@code fromUser} while no finger is down, and the tracking callbacks wrap a horizontal drag or a tap only (a vertical
 * scroll of the list over it or a cancelled touch reports nothing).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class IosSliderTest {
    private static IosSlider slider() {
        IosSlider s = new IosSlider(RuntimeEnvironment.getApplication());
        new android.widget.FrameLayout(RuntimeEnvironment.getApplication()).addView(s);
        s.setRange(0, 100, 1);
        s.setDefaultValue(50);
        s.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY));
        s.layout(0, 0, 1000, 120);
        return s;
    }

    private static void tap(IosSlider s, long t, float x) {
        s.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, 60, 0));
        s.dispatchTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, 60, 0));
    }

    @Test
    public void aTapJumpsAndADoubleTapGoesToTheDefault() {
        IosSlider s = slider();
        List<String> heard = new ArrayList<>();
        s.setListener(new IosSlider.Listener() {
            @Override public void onValue(IosSlider x, float v, boolean user) { heard.add(Math.round(v) + (user ? "u" : "")); }
            @Override public void onTracking(IosSlider x, boolean on) { heard.add(on ? "down" : "up"); }
        });
        long t = SystemClock.uptimeMillis();
        tap(s, t, 900);
        assertTrue("a tap near the end: a high value " + s.getValue(), s.getValue() > 85);
        assertEquals("down", heard.get(0));
        assertEquals("up", heard.get(heard.size() - 1));
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
        // a double tap far from the default: the default, not where the second finger lifted
        long t2 = SystemClock.uptimeMillis();
        tap(s, t2, 900);
        tap(s, t2 + 120, 900);
        assertEquals(50f, s.getValue(), 0f);
        assertFalse(s.isTracking());
    }

    @Test
    public void aValueChosenInCodeAsTheUsersIsToldWithoutAFinger() {
        IosSlider s = slider();
        boolean[] seen = {false};
        s.setListener((x, v, user) -> {
            assertTrue(user);
            assertFalse("no finger down", x.isTracking());
            seen[0] = true;
        });
        s.setValueFromUser(73.4f);
        assertTrue(seen[0]);
        assertEquals(73f, s.getValue(), 0f);
    }

    private static List<String> listen(IosSlider s) {
        List<String> heard = new ArrayList<>();
        s.setListener(new IosSlider.Listener() {
            @Override public void onValue(IosSlider x, float v, boolean user) {
                heard.add(Math.round(v) + (user ? "u" : "") + (x.isTracking() ? "t" : ""));
            }
            @Override public void onTracking(IosSlider x, boolean on) { heard.add(on ? "down" : "up"); }
        });
        return heard;
    }

    private static void touch(IosSlider s, long t, int action, float x, float y) {
        s.dispatchTouchEvent(MotionEvent.obtain(t, t, action, x, y, 0));
    }

    @Test
    public void aVerticalScrollOverTheSliderReportsNothingAndItsLaterMovesDoNothing() {
        IosSlider s = slider();
        s.setValueNow(20);
        List<String> heard = listen(s);
        long t = SystemClock.uptimeMillis();
        touch(s, t, MotionEvent.ACTION_DOWN, 200, 60);
        assertFalse("a finger down alone is no change yet", s.isTracking());
        touch(s, t + 16, MotionEvent.ACTION_MOVE, 205, 160);   // the list scrolls: the slider lets go
        // the list did not take the rest of the touch: a sideways move and the finger-up still reach the slider
        touch(s, t + 32, MotionEvent.ACTION_MOVE, 700, 170);
        touch(s, t + 48, MotionEvent.ACTION_UP, 700, 170);
        assertTrue("no tracking, no value: " + heard, heard.isEmpty());
        assertEquals(20f, s.getValue(), 0f);
        assertFalse(s.isTracking());
        // dragging was reset with the release: a value set in code glides the knob again (and is told as not the user's)
        s.setValue(40);
        assertEquals(java.util.Collections.singletonList("40"), heard);
    }

    @Test
    public void aTouchCancelledBeforeItWasADragOrATapReportsNothing() {
        IosSlider s = slider();
        s.setValueNow(20);
        List<String> heard = listen(s);
        long t = SystemClock.uptimeMillis();
        touch(s, t, MotionEvent.ACTION_DOWN, 600, 60);
        touch(s, t + 16, MotionEvent.ACTION_MOVE, 601, 61);   // within the touch slop
        touch(s, t + 32, MotionEvent.ACTION_CANCEL, 601, 61);  // the parent list took it
        assertTrue("" + heard, heard.isEmpty());
        assertEquals(20f, s.getValue(), 0f);
    }

    @Test
    public void aHorizontalDragIsTrackedFromItsFirstMoveToItsEnd() {
        IosSlider s = slider();
        s.setValueNow(20);
        List<String> heard = listen(s);
        long t = SystemClock.uptimeMillis();
        touch(s, t, MotionEvent.ACTION_DOWN, 200, 60);
        touch(s, t + 16, MotionEvent.ACTION_MOVE, 400, 62);
        assertTrue(s.isTracking());
        touch(s, t + 32, MotionEvent.ACTION_UP, 400, 62);
        assertFalse(s.isTracking());
        assertEquals("down", heard.get(0));
        assertEquals("up", heard.get(heard.size() - 1));
        assertTrue("the values in between are the tracked user's: " + heard, heard.get(1).endsWith("ut"));
        assertTrue(s.getValue() > 20f);
    }

    @Test
    public void aDoubleTapRunsTheResetListenerInsteadOfTheDefault() {
        IosSlider s = slider();
        s.setValueNow(80);
        int[] reset = {0};
        s.setOnResetListener(() -> reset[0]++);
        long t = SystemClock.uptimeMillis();
        tap(s, t, 900);
        float afterTap = s.getValue();
        tap(s, t + 120, 900);
        assertEquals(1, reset[0]);
        assertEquals("the default (50) is not written", afterTap, s.getValue(), 0f);
        assertFalse(s.isTracking());
        // without it: the default again
        s.setOnResetListener(null);
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
        long t2 = SystemClock.uptimeMillis();
        tap(s, t2, 900);
        tap(s, t2 + 120, 900);
        assertEquals(50f, s.getValue(), 0f);
    }

    @Test
    public void talkBacksSetProgressIsTheUsersValueWithNoTouch() {
        IosSlider s = slider();
        s.setValueNow(10);
        List<String> heard = listen(s);
        android.view.accessibility.AccessibilityNodeInfo info = android.view.accessibility.AccessibilityNodeInfo.obtain();
        s.onInitializeAccessibilityNodeInfo(info);
        assertTrue(info.getActionList().contains(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS));
        android.os.Bundle args = new android.os.Bundle();
        args.putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 66.6f);
        assertTrue(s.performAccessibilityAction(android.R.id.accessibilityActionSetProgress, args));
        assertEquals(67f, s.getValue(), 0f);
        assertEquals(java.util.Collections.singletonList("67u"), heard);
    }

    @Test
    public void inRtlTheStartIsTheRightEdge() {
        IosSlider s = slider();
        s.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        long t = SystemClock.uptimeMillis();
        tap(s, t, 100);
        assertTrue("a tap near the left edge: a high value " + s.getValue(), s.getValue() > 85);
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
        s.setValueNow(50);
        long t2 = SystemClock.uptimeMillis();
        touch(s, t2, MotionEvent.ACTION_DOWN, 500, 60);
        touch(s, t2 + 16, MotionEvent.ACTION_MOVE, 300, 60);   // to the left: up
        touch(s, t2 + 32, MotionEvent.ACTION_UP, 300, 60);
        assertTrue("a drag to the left raises it " + s.getValue(), s.getValue() > 60);
        s.setValueNow(50);
        s.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals("D-pad left raises it in RTL", 51f, s.getValue(), 0f);
    }

    @Test
    public void aHoldIsTheLongClickAndNoJump() {
        IosSlider s = slider();
        s.setValueNow(20);
        boolean[] held = {false};
        s.setOnLongClickListener(v -> held[0] = true);
        long t = SystemClock.uptimeMillis();
        s.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 900, 60, 0));
        ShadowLooper.idleMainLooper(android.view.ViewConfiguration.getLongPressTimeout() + 100, java.util.concurrent.TimeUnit.MILLISECONDS);
        s.dispatchTouchEvent(MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 900, 60, 0));
        assertTrue(held[0]);
        assertEquals("the hold's release moves nothing", 20f, s.getValue(), 0f);
    }
}
