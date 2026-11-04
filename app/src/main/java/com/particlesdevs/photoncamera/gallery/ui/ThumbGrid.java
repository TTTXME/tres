package com.particlesdevs.photoncamera.gallery.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.particlesdevs.photoncamera.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * P502: the plain square thumbnail grid of the gallery's search results and «Недавно удаленные» (the library's own grid keeps its
 * day sections): 2dp gaps, a badge at the bottom left («29 дн.», a video's mark), a check on the selected ones.
 */
public final class ThumbGrid extends RecyclerView.Adapter<ThumbGrid.Holder> {
    public interface Listener {
        void onClick(int index);

        void onLongClick(int index);
    }

    public static final class Cell {
        public final Uri uri;
        @Nullable public final String badge;
        public final boolean video;

        public Cell(Uri uri, @Nullable String badge, boolean video) {
            this.uri = uri;
            this.badge = badge;
            this.video = video;
        }
    }

    private final List<Cell> cells = new ArrayList<>();
    private final Set<Integer> selected = new HashSet<>();
    private final Listener listener;
    private int px = 240;

    public ThumbGrid(Listener listener) {
        this.listener = listener;
    }

    /** A RecyclerView with this grid in {@code columns} columns. */
    public static RecyclerView view(Context c, ThumbGrid grid, int columns) {
        RecyclerView rv = new RecyclerView(c);
        columns *= GalleryUi.widthFactor(c); // P502: a wide screen gets more
        rv.setLayoutManager(new GridLayoutManager(c, columns));
        rv.setAdapter(grid);
        rv.setClipToPadding(false);
        grid.px = Math.max(120, c.getResources().getDisplayMetrics().widthPixels / Math.max(1, columns));
        return rv;
    }

    public void set(List<Cell> list) {
        cells.clear();
        cells.addAll(list);
        selected.clear();
        notifyDataSetChanged();
    }

    public Set<Integer> selected() {
        return selected;
    }

    public void toggle(int index) {
        if (!selected.remove(index)) selected.add(index);
        notifyItemChanged(index);
    }

    public void clearSelection() {
        selected.clear();
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return cells.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(parent.getContext());
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Cell cell = cells.get(position);
        Glide.with(h.image).load(cell.uri).apply(new RequestOptions().diskCacheStrategy(DiskCacheStrategy.RESOURCE).override(px, px).centerCrop())
                .into(h.image);
        h.badge.setVisibility(cell.badge != null ? View.VISIBLE : View.GONE);
        h.badge.setText(cell.badge);
        h.video.setVisibility(cell.video ? View.VISIBLE : View.GONE);
        boolean on = selected.contains(position);
        h.check.setVisibility(on ? View.VISIBLE : View.GONE);
        h.image.setAlpha(on ? .72f : 1f);
        h.itemView.setOnClickListener(v -> listener.onClick(h.getBindingAdapterPosition()));
        h.itemView.setOnLongClickListener(v -> {
            listener.onLongClick(h.getBindingAdapterPosition());
            return true;
        });
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView image, check, video;
        final TextView badge;

        Holder(Context c) {
            super(new SquareFrame(c));
            FrameLayout f = (FrameLayout) itemView;
            int gap = GalleryUi.dp(c, 1);
            f.setPadding(gap, gap, gap, gap);
            image = new ImageView(c);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(GalleryUi.CARD);
            f.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            badge = GalleryUi.text(c, "", 11, GalleryUi.TEXT);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setShadowLayer(GalleryUi.dp(c, 2), 0, 0, 0xCC000000);
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.START);
            bp.leftMargin = bp.bottomMargin = GalleryUi.dp(c, 6);
            f.addView(badge, bp);
            video = GalleryUi.icon(c, R.drawable.ui_ic_play, GalleryUi.TEXT, 16);
            FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(GalleryUi.dp(c, 16), GalleryUi.dp(c, 16), Gravity.TOP | Gravity.END);
            vp.topMargin = vp.rightMargin = GalleryUi.dp(c, 6);
            f.addView(video, vp);
            check = GalleryUi.icon(c, R.drawable.ui_ic_check, GalleryUi.INK, 22);
            check.setBackground(GalleryUi.round(c, GalleryUi.accent(c), GalleryUi.accent(c), 999));
            int cp = GalleryUi.dp(c, 3);
            check.setPadding(cp, cp, cp, cp);
            FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(GalleryUi.dp(c, 24), GalleryUi.dp(c, 24), Gravity.TOP | Gravity.START);
            kp.topMargin = kp.leftMargin = GalleryUi.dp(c, 6);
            f.addView(check, kp);
            check.setVisibility(View.GONE);
        }
    }

    /** A cell as tall as it is wide. */
    static final class SquareFrame extends FrameLayout {
        SquareFrame(Context c) {
            super(c);
            setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec);
        }
    }
}
