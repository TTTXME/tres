package com.particlesdevs.photoncamera.gallery.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.particlesdevs.photoncamera.util.Lang;

/**
 * P498 (owner, 2026-11-04: «полноценная галерея», «удобства»): the grid's fast scroller - a pill thumb at the right edge that shows
 * while the grid scrolls and fades 1.2 s after it stops; dragged, it moves the grid by its share of the height and a bubble beside
 * the finger names the day there («6 октября»). Over the grid; only the thumb's strip takes touches.
 */
public final class FastScroller extends FrameLayout {
    /** The day label of an adapter position. */
    public interface Labels {
        String labelAt(int position);
    }

    private final RecyclerView grid;
    private final Labels labels;
    private final View thumb;
    private final TextView bubble;
    private boolean dragging;
    private int lastDay = -1;
    private final Runnable hide = () -> fade(false);

    @SuppressLint("ClickableViewAccessibility")
    public FastScroller(Context c, RecyclerView grid, Labels labels) {
        super(c);
        this.grid = grid;
        this.labels = labels;
        setClipChildren(false);
        thumb = new View(c);
        thumb.setBackground(GalleryUi.round(c, GalleryUi.accent(c), GalleryUi.accent(c), 999));
        thumb.setAlpha(0f);
        thumb.setContentDescription(Lang.t("Быстрая прокрутка", "Fast scroll"));
        addView(thumb, new LayoutParams(GalleryUi.dp(c, 6), GalleryUi.dp(c, 52), Gravity.END | Gravity.TOP));
        // P579: the day under the finger in the camera's dark capsule (its light edge), as the toasts
        bubble = GalleryUi.text(c, "", 14, GalleryUi.TEXT);
        bubble.setTypeface(com.particlesdevs.photoncamera.ui.style.Fonts.of(bubble.getContext(), 600));
        bubble.setPadding(GalleryUi.dp(c, 14), GalleryUi.dp(c, 9), GalleryUi.dp(c, 14), GalleryUi.dp(c, 9));
        bubble.setBackground(GalleryUi.bar(c, 999));
        bubble.setElevation(GalleryUi.dp(c, 4));
        bubble.setVisibility(View.GONE);
        LayoutParams bp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP);
        bp.rightMargin = GalleryUi.dp(c, 28);
        addView(bubble, bp);
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy == 0 && !dragging) return;
                place();
                if (canScroll()) {
                    fade(true);
                    removeCallbacks(hide);
                    if (!dragging) postDelayed(hide, 1200);
                }
            }
        });
    }

    private boolean canScroll() {
        return grid.computeVerticalScrollRange() > grid.getHeight() * 2;
    }

    private float fraction() {
        int range = grid.computeVerticalScrollRange() - grid.computeVerticalScrollExtent();
        return range <= 0 ? 0f : Math.max(0f, Math.min(1f, grid.computeVerticalScrollOffset() / (float) range));
    }

    private void place() {
        int track = getHeight() - thumb.getHeight() - GalleryUi.dp(getContext(), 16);
        thumb.setTranslationY(GalleryUi.dp(getContext(), 8) + track * fraction());
        thumb.setTranslationX(-GalleryUi.dp(getContext(), 4));
        bubble.setTranslationY(thumb.getTranslationY() + (thumb.getHeight() - bubble.getHeight()) / 2f);
    }

    private void fade(boolean shown) {
        thumb.animate().cancel();
        thumb.animate().alpha(shown ? 1f : 0f).scaleX(shown ? 1f : .6f).setDuration(shown ? 160 : 260).start();
        if (!shown) bubble.setVisibility(View.GONE);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        return e.getActionMasked() == MotionEvent.ACTION_DOWN && onThumb(e);
    }

    private boolean onThumb(MotionEvent e) {
        if (thumb.getAlpha() < .5f || !canScroll()) return false;
        float y = e.getY(), top = thumb.getTranslationY();
        return e.getX() > getWidth() - GalleryUi.dp(getContext(), 40)
                && y > top - GalleryUi.dp(getContext(), 24) && y < top + thumb.getHeight() + GalleryUi.dp(getContext(), 24);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!onThumb(e)) return false;
                dragging = true;
                removeCallbacks(hide);
                thumb.animate().scaleX(1.6f).setDuration(120).start();
                bubble.setVisibility(View.VISIBLE);
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through: the grid follows the finger from the first touch
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                scrollTo(e.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                bubble.setVisibility(View.GONE);
                thumb.animate().scaleX(1f).setDuration(160).start();
                postDelayed(hide, 1200);
                return true;
            default:
                return dragging;
        }
    }

    private void scrollTo(float y) {
        RecyclerView.Adapter<?> a = grid.getAdapter();
        if (a == null || a.getItemCount() == 0) return;
        float f = Math.max(0f, Math.min(1f, (y - GalleryUi.dp(getContext(), 8)) / Math.max(1f, getHeight() - GalleryUi.dp(getContext(), 16))));
        int position = Math.min(a.getItemCount() - 1, Math.round(f * (a.getItemCount() - 1)));
        RecyclerView.LayoutManager lm = grid.getLayoutManager();
        if (lm instanceof GridLayoutManager) ((GridLayoutManager) lm).scrollToPositionWithOffset(position, 0);
        else grid.scrollToPosition(position);
        String label = labels.labelAt(position);
        bubble.setText(label);
        int day = label.hashCode();
        if (day != lastDay) {
            lastDay = day;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        thumb.setTranslationY(GalleryUi.dp(getContext(), 8) + (getHeight() - thumb.getHeight() - GalleryUi.dp(getContext(), 16)) * f);
        bubble.setTranslationY(thumb.getTranslationY() + (thumb.getHeight() - bubble.getHeight()) / 2f);
    }
}
