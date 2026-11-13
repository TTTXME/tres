package com.scamera.app.gallery.adapters;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import com.scamera.app.gallery.interfaces.OnItemInteractionListener;

/**
 * Taps and selection drags on the library grid. A tap reports {@link OnItemInteractionListener#itemTapped}; a long press
 * reports {@link OnItemInteractionListener#itemLongPressed} and starts a selection; dragging then reports the range from
 * the long-pressed item to the item under the finger ({@link OnItemInteractionListener#onDragRange}): rows and columns
 * alike (P59b: a vertical drag in one column hovered one cell only, the cells in between were never selected). Near the
 * top / bottom edge the list scrolls and the range follows.
 */
public class DragSelectionItemTouchListener implements RecyclerView.OnItemTouchListener {
    private final OnItemInteractionListener listener;
    private final GestureDetector gestures;
    /** Where a scroll by the edge starts (px from the top / bottom). */
    private final int edge;
    /** The item under the finger since it went down, and the long-pressed one (a selection drag runs while it is set). */
    private RecyclerView.ViewHolder touched, anchor;
    private int lastPosition = RecyclerView.NO_POSITION;
    private float lastX, lastY;
    private RecyclerView scrolling;
    private int scrollStep;
    private final Runnable autoScroll = new Runnable() {
        @Override
        public void run() {
            if (scrolling == null || scrollStep == 0 || anchor == null) return;
            scrolling.scrollBy(0, scrollStep);
            reportRange(scrolling, lastX, lastY, true);
            scrolling.postOnAnimation(this);
        }
    };

    public DragSelectionItemTouchListener(Context context, OnItemInteractionListener listener) {
        this.listener = listener;
        edge = Math.round(56 * context.getResources().getDisplayMetrics().density);
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapUp(MotionEvent e) { return true; }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) { return true; }

            @Override public void onLongPress(MotionEvent e) {
                if (touched == null || DragSelectionItemTouchListener.this.listener == null) return;
                RecyclerView list = (RecyclerView) touched.itemView.getParent();
                DragSelectionItemTouchListener.this.listener.itemLongPressed(list, touched, touched.getAbsoluteAdapterPosition());
                anchor = touched;
            }
        });
        gestures.setIsLongpressEnabled(true);
    }

    private static boolean ends(MotionEvent e) {
        int a = e.getActionMasked();
        return a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP || a == MotionEvent.ACTION_CANCEL;
    }

    @Override
    public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
        if (ends(e)) {
            finish();
            return false;
        }
        if (anchor == null) watch(rv, e);
        return anchor != null;
    }

    /** Feeds the gesture detector while no selection drag runs; a tap on an item is reported here. */
    private void watch(RecyclerView rv, MotionEvent e) {
        View under = rv.findChildViewUnder(e.getX(), e.getY());
        if (under == null) return;
        touched = rv.findContainingViewHolder(under);
        if (gestures.onTouchEvent(e) && listener != null) listener.itemTapped(rv, touched, rv.getChildAdapterPosition(under));
    }

    @Override
    public void onTouchEvent(RecyclerView rv, MotionEvent e) {
        if (ends(e)) {
            finish();
            return;
        }
        if (anchor == null) return;
        lastX = e.getX();
        lastY = e.getY();
        reportRange(rv, lastX, lastY, false);
        int step = lastY < edge ? -Math.round((edge - lastY) / 4f) - 1
                : lastY > rv.getHeight() - edge ? Math.round((lastY - rv.getHeight() + edge) / 4f) + 1 : 0;
        if (step == 0) {
            stopScroll();
        } else if (scrollStep == 0) {
            scrolling = rv;
            scrollStep = step;
            rv.postOnAnimation(autoScroll);
        } else {
            scrollStep = step;
        }
    }

    @Override
    public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        // the grid keeps its own touch handling
    }

    private void finish() {
        anchor = null;
        touched = null;
        lastPosition = RecyclerView.NO_POSITION;
        stopScroll();
    }

    private void stopScroll() {
        scrollStep = 0;
        if (scrolling != null) scrolling.removeCallbacks(autoScroll);
        scrolling = null;
    }

    /** The range from the long-pressed item to the item under (x, y) (or the nearest one), once per change. */
    private void reportRange(RecyclerView rv, float x, float y, boolean again) {
        if (anchor == null || listener == null) return;
        float cx = Math.max(1, Math.min(rv.getWidth() - 1, x)), cy = Math.max(1, Math.min(rv.getHeight() - 1, y));
        View under = rv.findChildViewUnder(cx, cy);
        if (under == null) under = nearest(rv, cx, cy);
        if (under == null) return;
        int pos = rv.getChildAdapterPosition(under);
        if (pos == RecyclerView.NO_POSITION || (pos == lastPosition && !again)) return;
        lastPosition = pos;
        listener.onDragRange(rv, anchor.getAbsoluteAdapterPosition(), pos);
    }

    private static View nearest(RecyclerView rv, float x, float y) {
        View best = null;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View v = rv.getChildAt(i);
            float dx = Math.max(0, Math.max(v.getLeft() - x, x - v.getRight()));
            float dy = Math.max(0, Math.max(v.getTop() - y, y - v.getBottom()));
            float d = dx * dx + dy * dy;
            if (d < bestDistance) {
                bestDistance = d;
                best = v;
            }
        }
        return best;
    }
}
