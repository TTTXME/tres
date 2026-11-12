package com.particlesdevs.photoncamera.gallery.ui;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Priority;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.request.target.Target;
import com.particlesdevs.photoncamera.gallery.glide.GalleryThumb;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;
import com.particlesdevs.photoncamera.processing.SavingFiles;

import java.util.ArrayDeque;

/**
 * P620: the grid's next rows decoded ahead of the scroll (Glide's ListPreloader for this grid; the recyclerview-integration
 * artifact is not in the build): {@link #AHEAD_ROWS} rows past the last visible cell in the direction of the scroll, at LOW
 * priority (the visible cells' HIGH requests go first), with the visible cells' exact request, so a preloaded thumbnail is a
 * memory-cache hit when its cell binds. At most {@link #MAX_TARGETS} preloads in flight: the oldest is cleared when the scroll
 * runs past it. A file the camera is still writing is not preloaded (its cell waits for it, P89).
 */
public final class ThumbPreloader extends RecyclerView.OnScrollListener {
    static final int AHEAD_ROWS = 4;
    static final int MAX_TARGETS = 48;

    private final LibraryAdapter adapter;
    private final RequestManager glide;
    private final ArrayDeque<Target<?>> targets = new ArrayDeque<>();
    /** The last range preloaded, [from, to) in adapter positions; and the direction it was for. */
    private int from = -1, to = -1;
    private boolean down = true;

    public ThumbPreloader(@NonNull RequestManager glide, @NonNull LibraryAdapter adapter) {
        this.adapter = adapter;
        this.glide = glide;
    }

    @Override
    public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
        if (dy == 0 || !(rv.getLayoutManager() instanceof GridLayoutManager)) return;
        GridLayoutManager lm = (GridLayoutManager) rv.getLayoutManager();
        int first = lm.findFirstVisibleItemPosition(), last = lm.findLastVisibleItemPosition();
        if (first < 0 || last < 0) return;
        int[] range = range(first, last, lm.getSpanCount(), adapter.getItemCount(), dy > 0);
        preload(range[0], range[1], dy > 0);
    }

    /** The positions to preload, [from, to): {@code rows} rows of {@code span} cells past the visible ones in the direction. */
    static int[] range(int first, int last, int span, int count, boolean down) {
        int n = Math.max(1, span) * AHEAD_ROWS;
        return down ? new int[]{Math.min(count, last + 1), Math.min(count, last + 1 + n)}
                : new int[]{Math.max(0, first - n), Math.max(0, first)};
    }

    private void preload(int a, int b, boolean dir) {
        if (a >= b) return;
        // only the part not asked for yet (the same direction)
        int s = a, e = b;
        if (dir == down && from >= 0) {
            if (dir) s = Math.max(a, to);
            else e = Math.min(b, from);
        }
        from = a;
        to = b;
        down = dir;
        int px = adapter.thumbPx();
        for (int i = dir ? s : e - 1; dir ? i < e : i >= s; i += dir ? 1 : -1) {
            int index = adapter.itemAt(i);
            GalleryItem item = index >= 0 && index < adapter.items().size() ? adapter.items().get(index) : null;
            if (item == null || item.getFile().getFileUri() == null) continue;
            if (SavingFiles.writing(item.getFile().getAbsolutePath())) continue;
            while (targets.size() >= MAX_TARGETS) glide.clear(targets.removeFirst());
            targets.addLast(GalleryThumb.request(glide, GalleryThumb.of(item.getFile()), px).priority(Priority.LOW).preload(px, px));
        }
    }
}
