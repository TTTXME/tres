package com.particlesdevs.photoncamera.gallery.ui;

import android.content.Context;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.particlesdevs.photoncamera.gallery.glide.GalleryThumb;
import com.particlesdevs.photoncamera.gallery.model.GalleryLayout;
import com.particlesdevs.photoncamera.gallery.model.GalleryView;
import com.particlesdevs.photoncamera.ui.Motion;
import com.particlesdevs.photoncamera.ui.controls.IosSegmented;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * P622: the «Фото» page in the chosen view ({@link GalleryLayout.Mode}) - the library's rows and spans for the grid, the list or the
 * timeline (LibraryAdapter with a {@link RowStyle}), the thumbnail size each needs, and the motion around them:
 * <ul>
 * <li>a switch of view or of the timeline's zoom keeps the same photo on screen: the page dips (130 ms), is laid out anew around
 * that photo, its thumbnail flies from its old place to its new one on the entry spring while the rows come in one after another;</li>
 * <li>the list and the timeline collapse the page's large title as they scroll (the title shrinks, the date line slides away -
 * translation and scale only, no layout per frame) and give it back at the top;</li>
 * <li>the timeline pins the month on screen in a capsule over the photos (pushed away by the next month's title) and floats the
 * «Годы · Месяцы · Все фото» control above the tabs; a pinch walks through the columns and then the levels.</li>
 * </ul>
 * The grid itself is left as it was (P59b / P502 / P620); its pinch stays the fragment's.
 */
public final class GalleryViews {
    /** What the fragment does for the views. */
    public interface Host extends ListRows.Menu {
        /** The selection bar shows (the timeline's control steps aside). */
        boolean selectingNow();
    }

    private final Context c;
    private final RecyclerView grid;
    private final GridLayoutManager layout;
    private final LibraryAdapter adapter;
    private final FrameLayout body;
    @Nullable private final View rangeRow;
    @Nullable private final GalleryHeader header;
    private final Host host;
    private GalleryLayout.Mode mode;
    private GalleryLayout.Level level;
    private String lastOpened;
    /** The month capsule pinned over the timeline. */
    private final TextView sticky;
    /** The timeline's floating zoom control and its segments. */
    private final FrameLayout levelBar, levelHolder;
    private final IosSegmented levelSeg;
    private boolean switching;
    /** The timeline control's distance from the screen's bottom (dp): over the tab bar (14dp from the bottom, 56dp tall). */
    static final int LEVEL_ABOVE_DP = 14 + 56 + 12;

    public GalleryViews(Context c, RecyclerView grid, GridLayoutManager layout, LibraryAdapter adapter, FrameLayout body,
                        @Nullable View rangeRow, @Nullable GalleryHeader header, Host host) {
        this.c = c;
        this.grid = grid;
        this.layout = layout;
        this.adapter = adapter;
        this.body = body;
        this.rangeRow = rangeRow;
        this.header = header;
        this.host = host;
        mode = GalleryLayout.mode(c);
        level = GalleryLayout.level(c);
        lastOpened = GalleryLayout.lastOpened(c);

        sticky = GalleryUi.pill(c, "", false);
        sticky.setTextColor(GalleryUi.TEXT);
        sticky.setElevation(GalleryUi.dp(c, 4));
        sticky.setVisibility(View.GONE);
        sticky.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        sp.setMarginStart(GalleryUi.dp(c, 10));
        sp.topMargin = GalleryUi.dp(c, 8);
        body.addView(sticky, sp);

        levelBar = new FrameLayout(c);
        levelBar.setBackground(GalleryUi.bar(c, 999));
        levelBar.setElevation(GalleryUi.dp(c, 8));
        int pad = GalleryUi.dp(c, 3);
        levelBar.setPadding(pad, pad, pad, pad);
        GalleryLayout.Level[] all = GalleryLayout.Level.values();
        String[] labels = new String[all.length];
        for (int i = 0; i < all.length; i++) labels[i] = all[i].label();
        levelSeg = GalleryUi.segmented(c, labels, level.ordinal(), i -> zoom(GalleryLayout.Level.values()[i], anchorItem()));
        levelSeg.setContentDescription(Lang.t("Масштаб ленты", "Timeline zoom"));
        levelBar.addView(levelSeg, new FrameLayout.LayoutParams(GalleryUi.dp(c, 300), ViewGroup.LayoutParams.WRAP_CONTENT));
        // a holder that cancels the body's collapse movement (the control stays put over the tab bar); the bar itself rises and sinks
        levelHolder = new FrameLayout(c);
        levelHolder.setClipChildren(false);
        levelHolder.addView(levelBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = LEVEL_ABOVE_DP;
        body.addView(levelHolder, lp);
        levelBar.setVisibility(View.GONE);

        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                collapse();
                updateSticky();
            }
        });
    }

    public GalleryLayout.Mode mode() {
        return mode;
    }

    public GalleryLayout.Level level() {
        return level;
    }

    /** The view as stored, applied without motion (the page's creation). */
    public void applyStored() {
        apply();
    }

    // ---------------------------------------------------------------- the view

    /** Switches to {@code m} (stored), keeping the photo on screen, animated. */
    public void switchTo(GalleryLayout.Mode m) {
        if (m == null || m == mode) return;
        GalleryLayout.setMode(c, m);
        final GalleryLayout.Mode to = m;
        transition(() -> mode = to, anchorItem());
        grid.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
    }

    /** The timeline zoomed to {@code l} around item {@code item} (a card tapped, the control, a pinch). */
    public void zoom(GalleryLayout.Level l, int item) {
        if (mode != GalleryLayout.Mode.TIMELINE) return;
        if (l == level) {
            levelSeg.setSelected(l.ordinal(), true);
            return;
        }
        GalleryLayout.setLevel(c, l);
        transition(() -> level = l, item);
        levelSeg.setSelected(l.ordinal(), true);
        grid.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    /**
     * A pinch step on the page: true when the view took it (the timeline's columns and levels; the list has one density), false for
     * the grid (the fragment changes its columns).
     */
    public boolean pinch(boolean spread) {
        if (mode == GalleryLayout.Mode.GRID) return false;
        if (mode == GalleryLayout.Mode.LIST || switching) return true;
        int[] next = GalleryLayout.zoomStep(level, GalleryLayout.timelineColumns(c), spread);
        GalleryLayout.Level l = GalleryLayout.Level.values()[next[0]];
        if (l != level) {
            if (l == GalleryLayout.Level.ALL && level == GalleryLayout.Level.MONTHS) GalleryLayout.setTimelineColumns(c, next[1]);
            zoom(l, anchorItem());
            return true;
        }
        if (next[1] == GalleryLayout.timelineColumns(c)) return true;
        GalleryLayout.setTimelineColumns(c, next[1]);
        if (Motion.enabled(grid)) {
            android.transition.ChangeBounds move = new android.transition.ChangeBounds();
            move.setDuration(300);
            move.setInterpolator(Motion.SHEET);
            android.transition.TransitionManager.beginDelayedTransition(grid, move);
        }
        spans();
        updateThumbSize();
        grid.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        return true;
    }

    /** The adapter's rows, the spans, the padding, the thumbnail size, the timeline's pieces and the header for the view now. */
    private void apply() {
        adapter.setStyle(style());
        spans();
        int side = mode == GalleryLayout.Mode.TIMELINE ? Math.max(0, GalleryUi.dp(c, TimelineRows.EDGE - TimelineRows.GAP / 2))
                : mode == GalleryLayout.Mode.LIST ? GalleryUi.dp(c, 12) : GalleryUi.dp(c, 9);
        int bottom = GalleryUi.dp(c, mode == GalleryLayout.Mode.TIMELINE ? 120 + 56 : 120);
        grid.setPadding(side, 0, side, bottom);
        updateThumbSize();
        boolean timeline = mode == GalleryLayout.Mode.TIMELINE;
        levelSeg.setSelected(level.ordinal(), false);
        showLevelBar(timeline && !host.selectingNow());
        body.setTag(mode.name());
        // the large title collapses in the list and the timeline: the page's body reaches below the screen by the date line's
        // height once, then only moves (no layout per scroll frame)
        if (rangeRow != null) {
            LinearLayout.LayoutParams bp = body.getLayoutParams() instanceof LinearLayout.LayoutParams ? (LinearLayout.LayoutParams) body.getLayoutParams() : null;
            int want = mode == GalleryLayout.Mode.GRID ? 0 : -collapseRange();
            if (bp != null && bp.bottomMargin != want) {
                bp.bottomMargin = want;
                body.setLayoutParams(bp);
            }
        }
        FrameLayout.LayoutParams hp = (FrameLayout.LayoutParams) levelHolder.getLayoutParams();
        int above = GalleryUi.dp(c, LEVEL_ABOVE_DP) + (rangeRow != null && mode != GalleryLayout.Mode.GRID ? collapseRange() : 0);
        if (hp.bottomMargin != above) {
            hp.bottomMargin = above;
            levelHolder.setLayoutParams(hp);
        }
        collapse();
        updateSticky();
    }

    @Nullable
    private RowStyle style() {
        switch (mode) {
            case LIST: return new ListRows(host);
            case TIMELINE: return new TimelineRows(level, this::zoom);
            default: return null;
        }
    }

    /** The span count of the view (times the screen's width factor; the list is one column). */
    public int columns() {
        int wf = GalleryUi.widthFactor(c);
        switch (mode) {
            case LIST: return 1;
            case TIMELINE: return level == GalleryLayout.Level.ALL ? GalleryLayout.timelineColumns(c) * wf : wf;
            default: return GalleryView.columns(c) * wf;
        }
    }

    private void spans() {
        int n = columns();
        layout.setSpanCount(n);
        layout.setSpanSizeLookup(adapter.spans(n));
    }

    /** The thumbnail size the view's cells need (px; the grid: the cell width, as P498). */
    public int thumbPx() {
        int width = c.getResources().getDisplayMetrics().widthPixels;
        if (mode == GalleryLayout.Mode.LIST) return ListRows.thumbPx(c);
        return width / Math.max(1, columns());
    }

    public void updateThumbSize() {
        adapter.setThumbPx(thumbPx());
        GalleryThumb.setGridPx(c, adapter.thumbPx()); // P620: the viewer's first picture asks for the same size
    }

    // ---------------------------------------------------------------- the switch's motion

    /** The item on screen to keep: the first photo fully shown (a card: its first photo). */
    public int anchorItem() {
        int first = layout.findFirstCompletelyVisibleItemPosition();
        if (first < 0) first = layout.findFirstVisibleItemPosition();
        int last = layout.findLastVisibleItemPosition();
        for (int p = Math.max(0, first); p >= 0 && p <= last; p++) {
            LibraryAdapter.Row r = adapter.rowAt(p);
            if (r != null && (r.type == LibraryAdapter.PHOTO || r.type == RowStyle.GROUP)) return r.item;
        }
        for (int p = Math.max(0, first); p < adapter.getItemCount(); p++) {
            LibraryAdapter.Row r = adapter.rowAt(p);
            if (r != null && r.type != LibraryAdapter.HEADER) return r.item;
        }
        return 0;
    }

    /** The adapter position showing {@code item}: its photo, else the card holding it. */
    public int positionOfItem(int item) {
        for (int p = 0; p < adapter.getItemCount(); p++) {
            LibraryAdapter.Row r = adapter.rowAt(p);
            if (r == null) continue;
            if (r.type == LibraryAdapter.PHOTO && r.item == item) return p;
            if (r.type == RowStyle.GROUP && item >= r.item && item < r.item + r.count) return p;
        }
        return -1;
    }

    @Nullable
    private ImageView thumbOf(int position) {
        RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(position);
        if (h == null) return null;
        if (h instanceof LibraryAdapter.CellHolder) return ((LibraryAdapter.CellHolder) h).image;
        View v = h.itemView.findViewWithTag("thumb");
        return v instanceof ImageView ? (ImageView) v : null;
    }

    /** {@code v}'s bounds in the body's coordinates. */
    private Rect inBody(View v) {
        Rect r = new Rect(0, 0, v.getWidth(), v.getHeight());
        body.offsetDescendantRectToMyCoords(v, r);
        return r;
    }

    /**
     * The page re-laid out by {@code change} around {@code item}: the old rows dip out, the new ones come in one after another and
     * the item's thumbnail flies from its old place to its new one (the shared element). Without motion: the change and the scroll.
     */
    private void transition(Runnable change, int item) {
        int oldPos = positionOfItem(item);
        View oldRow = oldPos >= 0 ? layout.findViewByPosition(oldPos) : null;
        final int offset = oldRow != null ? Math.max(0, oldRow.getTop() - grid.getPaddingTop()) : 0;
        ImageView oldThumb = oldPos >= 0 ? thumbOf(oldPos) : null;
        if (!Motion.enabled(grid) || switching) {
            change.run();
            apply();
            scrollTo(item, offset);
            return;
        }
        switching = true;
        // the flying thumbnail: a copy of the old one's picture over the page
        ImageView flying = null;
        Rect from = null;
        if (oldThumb != null && oldThumb.getDrawable() != null && oldThumb.getWidth() > 0 && oldThumb.getHeight() > 0) {
            // a snapshot, not the shared drawable: the old cell's Glide bitmap goes back to the pool when the cell is rebound
            android.graphics.Bitmap shot = android.graphics.Bitmap.createBitmap(oldThumb.getWidth(), oldThumb.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            oldThumb.draw(new android.graphics.Canvas(shot));
            flying = new ImageView(c);
            flying.setScaleType(ImageView.ScaleType.FIT_XY);
            flying.setImageBitmap(shot);
            flying.setElevation(GalleryUi.dp(c, 6));
            from = inBody(oldThumb);
            FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(from.width(), from.height());
            body.addView(flying, fp);
            flying.setTranslationX(from.left);
            flying.setTranslationY(from.top);
            oldThumb.setVisibility(View.INVISIBLE);
        }
        final ImageView fly = flying;
        final Rect start = from;
        final RecyclerView.ItemAnimator animator = grid.getItemAnimator();
        grid.animate().cancel();
        grid.animate().alpha(0f).scaleX(.97f).scaleY(.97f).setDuration(130).setInterpolator(Motion.EASE_OUT).withEndAction(() -> {
            if (oldThumb != null) oldThumb.setVisibility(View.VISIBLE);
            grid.setItemAnimator(null); // the new rows come in by the stagger below, not the item animator's moves
            change.run();
            apply();
            scrollTo(item, offset);
            grid.setScaleX(1.02f);
            grid.setScaleY(1.02f);
            grid.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if (grid.getViewTreeObserver().isAlive()) grid.getViewTreeObserver().removeOnPreDrawListener(this);
                    enter(fly, start, item);
                    grid.postDelayed(() -> {
                        grid.setItemAnimator(animator);
                        switching = false;
                    }, Motion.ENTER.durationMs + 120);
                    return true;
                }
            });
        }).start();
    }

    private void enter(@Nullable ImageView fly, @Nullable Rect start, int item) {
        grid.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.ENTER.durationMs).setInterpolator(Motion.ENTER).withEndAction(null).start();
        int pos = positionOfItem(item);
        ImageView target = pos >= 0 ? thumbOf(pos) : null;
        // the rows one after another (the first screen only; at most 16 steps)
        int n = grid.getChildCount();
        for (int i = 0; i < n; i++) {
            View ch = grid.getChildAt(i);
            ch.animate().cancel();
            ch.setAlpha(0f);
            ch.setTranslationY(GalleryUi.dp(c, 12));
            ch.animate().alpha(1f).translationY(0f).setStartDelay(Math.min(i, 16) * 14L).setDuration(Motion.ENTER.durationMs)
                    .setInterpolator(Motion.ENTER).withEndAction(null).start();
        }
        if (fly == null || start == null) return;
        if (target == null || target.getWidth() <= 0) {
            fly.animate().alpha(0f).setDuration(160).withEndAction(() -> body.removeView(fly)).start();
            return;
        }
        final Rect to = inBody(target);
        final ImageView t = target;
        t.setAlpha(0f);
        // a uniform scale that covers the new box, and a clip from the whole old box to the new box's shape (centre crop): the
        // picture is never stretched between a square cell and a 4:3 row
        final int w = Math.max(1, start.width()), h = Math.max(1, start.height());
        final float end = Math.max(to.width() / (float) w, to.height() / (float) h);
        final float cw = to.width() / end, ch = to.height() / end;
        final float radius0 = GalleryUi.dp(c, 4), radius1 = GalleryUi.dp(c, mode == GalleryLayout.Mode.LIST ? 12 : mode == GalleryLayout.Mode.TIMELINE ? TimelineRows.TILE_RADIUS : 14);
        final Rect clip = new Rect();
        final float[] r = {radius0};
        fly.setPivotX(0);
        fly.setPivotY(0);
        fly.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(clip, r[0] / Math.max(.01f, view.getScaleX()));
            }
        });
        fly.setClipToOutline(true);
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(Motion.ENTER.durationMs);
        a.setInterpolator(Motion.ENTER);
        a.addUpdateListener(x -> {
            float k = (float) x.getAnimatedValue();
            float s = 1f + (end - 1f) * k;
            float vw = w + (cw - w) * k, vh = h + (ch - h) * k; // the visible part, in the snapshot's pixels
            int l = Math.round((w - vw) / 2f), tp = Math.round((h - vh) / 2f);
            clip.set(l, tp, Math.round(l + vw), Math.round(tp + vh));
            r[0] = radius0 + (radius1 - radius0) * k;
            fly.setClipBounds(clip);
            fly.invalidateOutline();
            fly.setScaleX(s);
            fly.setScaleY(s);
            // the visible part's corner lands on the new box's corner
            fly.setTranslationX(start.left + (to.left - start.left) * k - l * s);
            fly.setTranslationY(start.top + (to.top - start.top) * k - tp * s);
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                t.setAlpha(1f);
                body.removeView(fly);
            }
        });
        a.start();
    }

    private void scrollTo(int item, int offset) {
        int pos = positionOfItem(item);
        if (pos < 0) return;
        // a photo's month / day title stays above it when it was the first thing on screen
        layout.scrollToPositionWithOffset(pos, offset);
    }

    // ---------------------------------------------------------------- the large title

    private int collapseRange() {
        int h = rangeRow != null ? rangeRow.getHeight() : 0;
        return h > 0 ? h : GalleryUi.dp(c, 46);
    }

    /** How far the page has scrolled from its top, 0..range (exact near the top, the whole range further down). */
    private float collapsed() {
        int range = collapseRange();
        if (layout.findFirstVisibleItemPosition() > 0) return 1f;
        View first = layout.findViewByPosition(0);
        if (first == null) return grid.getChildCount() > 0 ? 1f : 0f;
        int scrolled = grid.getPaddingTop() - first.getTop() + (first.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                ? ((ViewGroup.MarginLayoutParams) first.getLayoutParams()).topMargin : 0);
        return Math.max(0f, Math.min(1f, scrolled / (float) range));
    }

    /** The large title shrinks and the date line slides under the header as the list scrolls (the grid: none). */
    private void collapse() {
        float t = mode == GalleryLayout.Mode.GRID ? 0f : collapsed();
        int range = collapseRange();
        body.setTranslationY(-range * t);
        levelHolder.setTranslationY(range * t);
        if (rangeRow != null) {
            rangeRow.setTranslationY(-range * t * .5f);
            rangeRow.setAlpha(1f - Math.min(1f, t * 1.6f));
            rangeRow.setVisibility(t >= .999f ? View.INVISIBLE : View.VISIBLE);
        }
        if (header != null) {
            TextView title = header.title;
            float s = 1f - .3f * t;
            title.setPivotX(title.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? title.getWidth() : 0);
            title.setPivotY(title.getHeight() / 2f);
            title.setScaleX(s);
            title.setScaleY(s);
        }
    }

    // ---------------------------------------------------------------- the timeline's capsule and control

    private void updateSticky() {
        boolean on = mode == GalleryLayout.Mode.TIMELINE && level == GalleryLayout.Level.ALL && adapter.getItemCount() > 0;
        int first = layout.findFirstVisibleItemPosition();
        View firstView = first >= 0 ? layout.findViewByPosition(first) : null;
        // at the top the month's own title shows: no capsule
        if (on && (first < 0 || firstView == null || first == 0 && firstView.getTop() >= grid.getPaddingTop() - GalleryUi.dp(c, 8))) on = false;
        if (!on) {
            if (sticky.getVisibility() == View.VISIBLE) Motion.fade(sticky, false);
            return;
        }
        LibraryAdapter.Row r = adapter.rowAt(first);
        // a month title that is still half on screen: the capsule waits for it to leave
        if (r != null && r.type == LibraryAdapter.HEADER && firstView.getBottom() > firstView.getHeight() / 2) {
            if (sticky.getVisibility() == View.VISIBLE) Motion.fade(sticky, false);
            return;
        }
        String label = adapter.dayLabelAt(first);
        if (!label.contentEquals(sticky.getText())) sticky.setText(label);
        if (sticky.getVisibility() != View.VISIBLE) Motion.fade(sticky, true);
        // the next month's title pushes the capsule up
        int last = layout.findLastVisibleItemPosition();
        float push = 0;
        int bottom = GalleryUi.dp(c, 8) + Math.max(sticky.getHeight(), GalleryUi.dp(c, 28));
        for (int p = first + 1; p <= last; p++) {
            LibraryAdapter.Row n = adapter.rowAt(p);
            if (n == null || n.type != LibraryAdapter.HEADER) continue;
            View v = layout.findViewByPosition(p);
            if (v != null) {
                int top = v.getTop() + v.getHeight() / 2;
                if (top < bottom + GalleryUi.dp(c, 6)) push = top - bottom - GalleryUi.dp(c, 6);
            }
            break;
        }
        sticky.setTranslationY(push);
    }

    private void showLevelBar(boolean on) {
        if (on == (levelBar.getVisibility() == View.VISIBLE)) return;
        GalleryMotion.rise(levelBar, on);
    }

    /** The selection bar shows or goes: the timeline's control steps aside for it. */
    public void selecting(boolean on) {
        showLevelBar(mode == GalleryLayout.Mode.TIMELINE && !on);
    }

    // ---------------------------------------------------------------- the last photo opened

    public void opened(String key) {
        lastOpened = key != null ? key : "";
        GalleryLayout.setLastOpened(c, lastOpened);
    }

    public String lastOpened() {
        return lastOpened;
    }
}
