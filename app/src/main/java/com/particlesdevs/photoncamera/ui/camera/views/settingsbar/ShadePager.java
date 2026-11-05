package com.particlesdevs.photoncamera.ui.camera.views.settingsbar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SnapHelper;

/**
 * P495 (owner, 2026-11-03: «Сетку 4х4 замени на 2х4 ... свайпом перелистывать вправо или влево с красивой анимацией в стиле
 * MacOS/IOS»): the shade's tiles in pages of 4 x 2, row by row inside a page, pages side by side. The adapter's order is kept
 * (a drag in the edit mode uses the plain grid), so a page is just positions 8p .. 8p + 7. A page follows the finger, past the
 * ends the system's stretch edge effect gives the rubber band, and {@link Snap} turns it to the next page on a flick or half a
 * page on the iOS sheet curve; {@link Dots} shows where the pages are, the current one stretched to a pill.
 */
final class ShadePager {
    private ShadePager() {}

    /**
     * P515 (owner, 2026-11-05: «дай возможность настраивать сетку в шторке самостоятельно»): columns and rows of a page, set in
     * the edit mode (pref_shade_grid_cols / _rows; 4 x 2 by default; «вплоть до 10х10 сетки»).
     */
    static final int COLS_MIN = 1, COLS_MAX = 10, ROWS_MIN = 1, ROWS_MAX = 10;
    static final String KEY_COLS = "pref_shade_grid_cols", KEY_ROWS = "pref_shade_grid_rows";
    static volatile int cols = 4, rows = 2;

    static int perPage() {
        return cols * rows;
    }

    /** The stored grid (clamped); false when it did not change. */
    static boolean setGrid(int c, int r) {
        c = Math.max(COLS_MIN, Math.min(COLS_MAX, c));
        r = Math.max(ROWS_MIN, Math.min(ROWS_MAX, r));
        if (c == cols && r == rows) return false;
        cols = c;
        rows = r;
        return true;
    }

    static void load(android.content.SharedPreferences prefs) {
        setGrid(prefs.getInt(KEY_COLS, 4), prefs.getInt(KEY_ROWS, 2));
    }
    /** UIKit's sheet curve: the page settles on it. */
    private static final PathInterpolator SETTLE = new PathInterpolator(0.32f, 0.72f, 0f, 1f);

    static int pages(int items) {
        return Math.max(1, (items + perPage() - 1) / perPage());
    }

    /** Pages of 4 x 2 cells; every tile laid out (a shade has a few dozen at most), the off-page ones beside the visible page. */
    static final class Layout extends RecyclerView.LayoutManager implements RecyclerView.SmoothScroller.ScrollVectorProvider {
        private int scrollX, cellH;

        @Override public RecyclerView.LayoutParams generateDefaultLayoutParams() {
            return new RecyclerView.LayoutParams(RecyclerView.LayoutParams.WRAP_CONTENT, RecyclerView.LayoutParams.WRAP_CONTENT);
        }

        @Override public boolean isAutoMeasureEnabled() {
            return false;
        }

        private int pageWidth() {
            return Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight());
        }

        int page() {
            return Math.round(scrollX / (float) pageWidth());
        }

        /** The scroll in pages (0 = the first page at rest; 0.5 = halfway to the second). */
        float progress() {
            return scrollX / (float) pageWidth();
        }

        @Override public void onMeasure(@NonNull RecyclerView.Recycler recycler, @NonNull RecyclerView.State state, int wSpec, int hSpec) {
            final int w = View.MeasureSpec.getSize(wSpec);
            final int n = state.getItemCount();
            int h = 0;
            if (n > 0) {
                final View v = recycler.getViewForPosition(0);
                final int cw = (w - getPaddingLeft() - getPaddingRight()) / cols;
                v.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, cw), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                cellH = v.getMeasuredHeight();
                recycler.recycleView(v);
                h = cellH * Math.max(1, Math.min(rows, (n + cols - 1) / cols)) + getPaddingTop() + getPaddingBottom();
            }
            setMeasuredDimension(w, h);
        }

        @Override public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
            final int n = state.getItemCount();
            if (n == 0) {
                removeAndRecycleAllViews(recycler);
                scrollX = 0;
                return;
            }
            detachAndScrapAttachedViews(recycler);
            final int pw = pageWidth(), cw = pw / cols;
            scrollX = Math.max(0, Math.min(scrollX, (pages(n) - 1) * pw));
            for (int i = 0; i < n; i++) {
                final View v = recycler.getViewForPosition(i);
                addView(v);
                v.measure(View.MeasureSpec.makeMeasureSpec(cw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(cellH, View.MeasureSpec.EXACTLY));
                final int per = perPage(), page = i / per, r = (i % per) / cols, c = (i % per) % cols;
                final int left = getPaddingLeft() + page * pw + c * cw - scrollX, top = getPaddingTop() + r * cellH;
                layoutDecorated(v, left, top, left + cw, top + cellH);
            }
        }

        @Override public boolean canScrollHorizontally() {
            return pages(getItemCount()) > 1;
        }

        @Override public int scrollHorizontallyBy(int dx, RecyclerView.Recycler recycler, RecyclerView.State state) {
            final int max = (pages(getItemCount()) - 1) * pageWidth();
            final int next = Math.max(0, Math.min(max, scrollX + dx));
            final int moved = next - scrollX;
            scrollX = next;
            offsetChildrenHorizontal(-moved);
            return moved;
        }

        @Override public int computeHorizontalScrollOffset(@NonNull RecyclerView.State state) {
            return scrollX;
        }

        @Override public int computeHorizontalScrollExtent(@NonNull RecyclerView.State state) {
            return pageWidth();
        }

        @Override public int computeHorizontalScrollRange(@NonNull RecyclerView.State state) {
            return pages(state.getItemCount()) * pageWidth();
        }

        @Override public void scrollToPosition(int position) {
            scrollX = (position / perPage()) * pageWidth();
            requestLayout();
        }

        @Override public void smoothScrollToPosition(RecyclerView view, RecyclerView.State state, int position) {
            final LinearSmoothScroller s = scroller(view.getContext(), this);
            s.setTargetPosition(position);
            startSmoothScroll(s);
        }

        @Nullable @Override public PointF computeScrollVectorForPosition(int target) {
            return new PointF(Math.signum((target / perPage()) * pageWidth() - scrollX), 0);
        }

        /** The distance from the current scroll to the page of {@code position}. */
        int distanceTo(int position) {
            return (position / perPage()) * pageWidth() - scrollX;
        }
    }

    /** A page turn settles on the sheet curve (380 ms, longer for a longer way). */
    static LinearSmoothScroller scroller(Context c, Layout layout) {
        return new LinearSmoothScroller(c) {
            @Override protected void onTargetFound(View target, RecyclerView.State state, Action action) {
                final int dx = layout.distanceTo(getTargetPosition());
                final int ms = Math.min(520, 300 + Math.abs(dx) * 120 / Math.max(1, layout.getWidth()));
                if (dx != 0) action.update(dx, 0, ms, SETTLE);
            }

            @Override protected float calculateSpeedPerPixel(DisplayMetrics dm) {
                return 60f / dm.densityDpi;
            }
        };
    }

    /** Snaps to whole pages: a flick turns one page, a slow drag settles on the nearest. */
    static final class Snap extends SnapHelper {
        private RecyclerView list;

        @Override public void attachToRecyclerView(@Nullable RecyclerView recyclerView) {
            list = recyclerView;
            super.attachToRecyclerView(recyclerView);
        }

        @Override public int[] calculateDistanceToFinalSnap(@NonNull RecyclerView.LayoutManager lm, @NonNull View target) {
            return new int[]{lm instanceof Layout ? ((Layout) lm).distanceTo(lm.getPosition(target)) : 0, 0};
        }

        @Nullable @Override public View findSnapView(RecyclerView.LayoutManager lm) {
            if (!(lm instanceof Layout) || lm.getItemCount() == 0) return null;
            return lm.findViewByPosition(Math.min(lm.getItemCount() - 1, ((Layout) lm).page() * perPage()));
        }

        @Override public int findTargetSnapPosition(RecyclerView.LayoutManager lm, int vx, int vy) {
            if (!(lm instanceof Layout) || lm.getItemCount() == 0) return RecyclerView.NO_POSITION;
            final int last = pages(lm.getItemCount()) - 1;
            final int from = (int) Math.floor(((Layout) lm).progress());
            return Math.max(0, Math.min(last, vx > 0 ? from + 1 : from)) * perPage();
        }

        @Nullable @Override protected RecyclerView.SmoothScroller createScroller(@NonNull RecyclerView.LayoutManager lm) {
            return list != null && lm instanceof Layout ? scroller(list.getContext(), (Layout) lm) : null;
        }
    }

    /** The page dots: 6dp dots, the current page a 18dp pill that stretches as the page moves. */
    static final class Dots extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private int pages = 1;
        private float progress;

        Dots(Context c) {
            super(c);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void set(int pages, float progress) {
            if (this.pages == pages && this.progress == progress) return;
            this.pages = pages;
            this.progress = progress;
            setVisibility(pages > 1 ? VISIBLE : GONE);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            if (pages < 2) return;
            final float d = getResources().getDisplayMetrics().density, dot = 6 * d, pill = 18 * d, gap = 6 * d;
            final float total = (pages - 1) * (dot + gap) + pill;
            float x = (getWidth() - total) / 2f;
            final float y = getHeight() / 2f;
            for (int i = 0; i < pages; i++) {
                final float k = Math.max(0f, 1f - Math.abs(progress - i)); // 1 on the current page, 0 a page away
                final float w = dot + (pill - dot) * k;
                paint.setColor(com.particlesdevs.photoncamera.ui.settings.SettingsMotion.mix(0x4DF5F5F7, 0xFFF5F5F7, k));
                r.set(x, y - dot / 2, x + w, y + dot / 2);
                canvas.drawRoundRect(r, dot / 2, dot / 2, paint);
                x += w + gap;
            }
        }
    }
}
