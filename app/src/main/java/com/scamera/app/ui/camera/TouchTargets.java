package com.scamera.app.ui.camera;

import android.graphics.Rect;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;

/**
 * A thin control (the grabbers: 56 x 12 dp, 56 x 18 dp) with a touch area of at least {@code min} each way without changing
 * what is drawn: its parent's {@link TouchDelegate} takes the area around it, kept in step with every layout. The parent only
 * hands a touch to the delegate when no child took it, so the buttons next to the grabber keep their own taps.
 */
public final class TouchTargets {
    private TouchTargets() {}

    /** Gives {@code v} at least {@code minPx} x {@code minPx} to touch (centred on it, inside its parent). */
    public static void ensure(View v, int minPx) {
        // once per view: a screen set up again replaces the listener instead of adding another
        final Object before = v.getTag(com.scamera.app.R.id.touch_target_listener);
        if (before instanceof View.OnLayoutChangeListener) v.removeOnLayoutChangeListener((View.OnLayoutChangeListener) before);
        final View.OnLayoutChangeListener update = (x, l, t, r, b, ol, ot, or, ob) -> apply(v, minPx);
        v.setTag(com.scamera.app.R.id.touch_target_listener, update);
        v.addOnLayoutChangeListener(update);
        apply(v, minPx);
    }

    /** The area {@code v} answers to: its bounds grown to {@code minPx} each way, inside a parent of {@code pw} x {@code ph}. */
    static Rect area(int left, int top, int right, int bottom, int pw, int ph, int minPx) {
        final Rect r = new Rect(left, top, right, bottom);
        final int dx = Math.max(0, minPx - r.width()), dy = Math.max(0, minPx - r.height());
        r.left -= dx / 2;
        r.right += dx - dx / 2;
        r.top -= dy / 2;
        r.bottom += dy - dy / 2;
        // against an edge of the parent: the rest of the growth on the other side
        if (r.top < 0) { r.bottom = Math.min(ph, r.bottom - r.top); r.top = 0; }
        if (r.bottom > ph) { r.top = Math.max(0, r.top - (r.bottom - ph)); r.bottom = ph; }
        if (r.left < 0) { r.right = Math.min(pw, r.right - r.left); r.left = 0; }
        if (r.right > pw) { r.left = Math.max(0, r.left - (r.right - pw)); r.right = pw; }
        return r;
    }

    private static void apply(View v, int minPx) {
        if (!(v.getParent() instanceof ViewGroup)) return;
        final ViewGroup parent = (ViewGroup) v.getParent();
        if (v.getWidth() == 0 || v.getHeight() == 0) return;
        final Rect r = area(v.getLeft(), v.getTop(), v.getRight(), v.getBottom(), parent.getWidth(), parent.getHeight(), minPx);
        parent.setTouchDelegate(new TouchDelegate(r, v));
    }
}
