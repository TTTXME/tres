package com.particlesdevs.photoncamera.gallery.ui;

import android.content.Context;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.particlesdevs.photoncamera.gallery.glide.GalleryKeys;
import com.bumptech.glide.Glide;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.gallery.helper.UltraHdrGalleryUtil;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P59b: the library grid: day sections («Сегодня» ... «N фото») over a 3-column grid of square cards (14dp radius, 1dp line)
 * with the format badge and an Ultra HDR badge (a sun) bottom-left on a solid dark backing, and while selecting an accent
 * check circle; a selected card shrinks inside an accent border. Selection itself lives in the fragment ({@link Host}).
 */
public final class LibraryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    static final int HEADER = 0, PHOTO = 1;

    /** The fragment: what is selected, what a tap / long press does. */
    public interface Host {
        boolean selecting();

        boolean isSelected(GalleryItem item);

        void onPhotoClicked(int itemIndex, View view);

        /** P498: a day's header tapped while selecting: the day's photos all selected (or all cleared when they were). */
        default void onDayClicked(int firstItem, int count) {}

        /** P498: whether a file is a favourite (its heart on the thumbnail). */
        default boolean isFavourite(GalleryItem item) { return false; }
    }

    /** A row: a day header or a photo (index into the items). */
    static final class Row {
        final int type, item, count;
        final String label;

        Row(int type, int item, String label, int count) {
            this.type = type;
            this.item = item;
            this.label = label;
            this.count = count;
        }
    }

    private static final Map<Uri, Boolean> UHDR = new ConcurrentHashMap<>();
    private static final ExecutorService PROBE = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GalleryUhdrProbe");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /** P617: the gallery open's first thumbnails in the log (GalleryOpenTrace); no state of its own. */
    private static final com.bumptech.glide.request.RequestListener<android.graphics.Bitmap> THUMB_TRACE =
            new com.bumptech.glide.request.RequestListener<android.graphics.Bitmap>() {
                @Override
                public boolean onLoadFailed(@androidx.annotation.Nullable com.bumptech.glide.load.engine.GlideException e, @androidx.annotation.Nullable Object model,
                                            @androidx.annotation.NonNull com.bumptech.glide.request.target.Target<android.graphics.Bitmap> target, boolean isFirstResource) {
                    return false;
                }

                @Override
                public boolean onResourceReady(@androidx.annotation.NonNull android.graphics.Bitmap resource, @androidx.annotation.NonNull Object model,
                                               com.bumptech.glide.request.target.Target<android.graphics.Bitmap> target,
                                               @androidx.annotation.NonNull com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                    GalleryOpenTrace.thumbReady(dataSource == com.bumptech.glide.load.DataSource.MEMORY_CACHE,
                            dataSource == com.bumptech.glide.load.DataSource.RESOURCE_DISK_CACHE || dataSource == com.bumptech.glide.load.DataSource.DATA_DISK_CACHE);
                    return false;
                }
            };

    private final Host host;
    private List<GalleryItem> items = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private Set<String> rawBases = new java.util.HashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    long now = System.currentTimeMillis();

    public LibraryAdapter(Host host) {
        this.host = host;
        setHasStableIds(true);
    }

    // ---------------------------------------------------------------- P622: the list and timeline views (RowStyle)

    /** P622: the rows and holders of another view (null: this grid, unchanged). */
    @androidx.annotation.Nullable private RowStyle style;

    /** P622: another view's rows and holders (null: the grid); the list is laid out again at once (no diff across views). */
    public void setStyle(@androidx.annotation.Nullable RowStyle s) {
        style = s;
        rows.clear();
        rows.addAll(s != null ? s.rows(items, now) : rowsOf(items, now));
        notifyDataSetChanged();
    }

    @androidx.annotation.Nullable
    public RowStyle style() { return style; }

    Host host() { return host; }

    Set<String> rawBases() { return rawBases; }

    /** P622: the row at an adapter position (null outside). */
    @androidx.annotation.Nullable
    Row rowAt(int position) { return position >= 0 && position < rows.size() ? rows.get(position) : null; }

    public void setItems(List<GalleryItem> list) {
        final List<GalleryItem> next = list != null ? list : new ArrayList<>();
        final long t = System.currentTimeMillis();
        final List<Row> nextRows = style != null ? style.rows(next, t) : rowsOf(next, t); // P622: the view's own rows
        // P498: a delete, an undo or a filter moves the cells on (DiffUtil + the grid's item animator) instead of a jump cut; the
        // first fill and big changes stay one refresh
        final boolean diff = !rows.isEmpty() && !nextRows.isEmpty() && Math.abs(rows.size() - nextRows.size()) < 400 && rows.size() < 4000;
        final List<Row> oldRows = new ArrayList<>(rows);
        final List<GalleryItem> oldItems = items;
        items = next;
        rawBases = GalleryFormat.rawBaseNames(items);
        now = t;
        rows.clear();
        rows.addAll(nextRows);
        if (!diff) {
            notifyDataSetChanged();
            return;
        }
        androidx.recyclerview.widget.DiffUtil.calculateDiff(new androidx.recyclerview.widget.DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldRows.size(); }
            @Override public int getNewListSize() { return nextRows.size(); }

            @Override
            public boolean areItemsTheSame(int o, int n) {
                return keyOf(oldRows.get(o), oldItems).equals(keyOf(nextRows.get(n), next));
            }

            @Override
            public boolean areContentsTheSame(int o, int n) {
                Row a = oldRows.get(o), b = nextRows.get(n);
                return a.item == b.item && a.count == b.count && java.util.Objects.equals(a.label, b.label);
            }
        }, false).dispatchUpdatesTo(this);
    }

    /** A row's identity: its day for a header, its file for a photo. */
    static String keyOf(Row r, List<GalleryItem> items) {
        if (r.type == HEADER) return "d" + (r.item < items.size() ? dayKey(items.get(r.item).getFile().getLastModified()) : r.label);
        return r.item < items.size() ? com.particlesdevs.photoncamera.gallery.model.GalleryView.key(items.get(r.item).getFile()) : "?";
    }

    /** P498: the thumbnails' decode size (px), from the cell width (the column count). */
    private int thumbPx = 240;

    public void setThumbPx(int px) {
        // P620: rounded up to 32 px steps (a slightly different width keeps the cached thumbnails)
        thumbPx = Math.max(120, Math.min(720, (px + 31) / 32 * 32));
    }

    public int thumbPx() {
        return thumbPx;
    }

    /** P498: the day of the row at an adapter position (the fast scroller's bubble). */
    public String dayLabelAt(int position) {
        for (int p = Math.min(position, rows.size() - 1); p >= 0; --p) if (rows.get(p).type == HEADER) return rows.get(p).label;
        return "";
    }

    /** P498: the item indices of a day (its header's first item and count). */
    public List<GalleryItem> dayItems(int firstItem, int count) {
        List<GalleryItem> out = new ArrayList<>();
        for (int i = firstItem; i < Math.min(items.size(), firstItem + count); i++) out.add(items.get(i));
        return out;
    }

    /** Day sections in list order (the list is newest first). */
    static List<Row> rowsOf(List<GalleryItem> items, long now) {
        List<Row> out = new ArrayList<>();
        int i = 0;
        while (i < items.size()) {
            long day = dayKey(items.get(i).getFile().getLastModified());
            int j = i;
            while (j < items.size() && dayKey(items.get(j).getFile().getLastModified()) == day) j++;
            out.add(new Row(HEADER, i, GalleryUi.dayLabel(items.get(i).getFile().getLastModified(), now), j - i));
            for (int k = i; k < j; ++k) out.add(new Row(PHOTO, k, null, 0));
            i = j;
        }
        return out;
    }

    static long dayKey(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR);
    }

    public List<GalleryItem> items() { return items; }

    /** The item index of an adapter position, -1 for a header. */
    public int itemAt(int position) {
        if (position < 0 || position >= rows.size()) return -1;
        Row r = rows.get(position);
        return r.type == PHOTO ? r.item : -1;
    }

    public int positionOf(int itemIndex) {
        for (int p = 0; p < rows.size(); ++p) if (rows.get(p).type == PHOTO && rows.get(p).item == itemIndex) return p;
        return -1;
    }

    public GridLayoutManager.SpanSizeLookup spans(int columns) {
        return new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (style != null) return position < rows.size() ? style.span(rows.get(position), columns) : 1; // P622
                return position < rows.size() && rows.get(position).type == HEADER ? columns : 1;
            }
        };
    }

    @Override
    public int getItemViewType(int position) { return style != null ? style.viewType(rows.get(position)) : rows.get(position).type; }

    @Override
    public long getItemId(int position) {
        Row r = rows.get(position);
        // P498: the same id for the same day / file wherever it moves (a delete or a filter animates the cells on)
        if (r.type == HEADER) return -2 - dayKey(items.get(r.item).getFile().getLastModified());
        return keyOf(r, items).hashCode() * 2L + 1;
    }

    @Override
    public int getItemCount() { return rows.size(); }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Context c = parent.getContext();
        if (style != null && viewType > PHOTO) return style.create(parent, viewType, this); // P622: the view's own holders
        return viewType == HEADER ? new HeaderHolder(c) : new CellHolder(c);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row r = rows.get(position);
        if (holder instanceof HeaderHolder) {
            int videos = 0; // P498: «3 фото · 1 видео»
            for (int k = r.item; k < r.item + r.count && k < items.size(); ++k)
                if (com.particlesdevs.photoncamera.gallery.files.GalleryVideo.isVideo(items.get(k).getFile())) ++videos;
            ((HeaderHolder) holder).bind(r, videos);
            ((HeaderHolder) holder).applySelecting(host.selecting());
            holder.itemView.setOnClickListener(v -> {
                if (host.selecting()) host.onDayClicked(r.item, r.count);
            });
        }
        else if (holder instanceof CellHolder) ((CellHolder) holder).bind(items.get(r.item), r.item);
        else if (style != null) style.bind(holder, r, this); // P622
    }

    /** Refreshes the selection state of the bound cells without reloading their pictures (animated). */
    public void refreshSelection(RecyclerView rv) {
        for (int i = 0; i < rv.getChildCount(); ++i) {
            RecyclerView.ViewHolder h = rv.getChildViewHolder(rv.getChildAt(i));
            if (h instanceof CellHolder) ((CellHolder) h).applySelection(true);
            else if (h instanceof HeaderHolder) ((HeaderHolder) h).applySelecting(host.selecting());
            else if (style != null) style.refreshSelection(h, this); // P622
        }
    }

    // ---------------------------------------------------------------- views

    static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView label, count;
        final ImageView all;

        HeaderHolder(Context c) {
            super(new LinearLayout(c));
            LinearLayout row = (LinearLayout) itemView;
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // P579: the day as the settings' section caption (12sp semibold capitals, 55 % white), the count at 55 % too
            row.setPadding(GalleryUi.dp(c, 8), GalleryUi.dp(c, 16), GalleryUi.dp(c, 8), GalleryUi.dp(c, 8));
            label = GalleryUi.text(c, "", 12, GalleryUi.SECTION);
            label.setLetterSpacing(.06f);
            label.setTypeface(com.particlesdevs.photoncamera.ui.style.Fonts.of(label.getContext(), 600));
            label.setAllCaps(true);
            count = GalleryUi.text(c, "", 12, GalleryUi.SECTION);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(count);
            // P498: while selecting, the day's header selects the whole day
            all = GalleryUi.icon(c, R.drawable.ui_ic_select_all, GalleryUi.accent(c), 18);
            com.particlesdevs.photoncamera.ui.Icons.ink(all, R.drawable.ui_ic_select_all, GalleryUi.accent(c)); // P579: in the accent
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(GalleryUi.dp(c, 18), GalleryUi.dp(c, 18));
            ap.setMarginStart(GalleryUi.dp(c, 8));
            row.addView(all, ap);
            all.setVisibility(View.GONE);
            row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        void applySelecting(boolean selecting) {
            if ((all.getVisibility() == View.VISIBLE) == selecting) return;
            all.setVisibility(selecting ? View.VISIBLE : View.GONE);
            if (selecting) com.particlesdevs.photoncamera.ui.Motion.pop(all);
            itemView.setClickable(selecting);
            itemView.setBackground(selecting ? GalleryUi.pressable(GalleryUi.round(itemView.getContext(), 0, 0, 12)) : null);
            if (selecting) itemView.setContentDescription(label.getText() + ", " + count.getText() + ", "
                    + com.particlesdevs.photoncamera.util.Lang.t("выбрать день", "select the day"));
        }

        void bind(Row r, int videos) {
            label.setText(r.label);
            count.setText(GalleryUi.dayCount(r.count - videos, videos));
            itemView.setContentDescription(r.label + ", " + count.getText());
        }
    }

    /** A square view (width = height). */
    static final class Square extends FrameLayout {
        Square(Context c) { super(c); }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, w);
        }
    }

    final class CellHolder extends RecyclerView.ViewHolder {
        final Square cell;
        final ImageView image;
        final LinearLayout badges;
        final FrameLayout check;
        final ImageView checkMark;
        final ImageView heart;
        /** P499: how much of a video was watched (a line along the bottom over a dim track). */
        final FrameLayout watched;
        final View watchedLine;
        GalleryItem item;
        int index;

        CellHolder(Context c) {
            super(new Square(c));
            cell = (Square) itemView;
            int gap = GalleryUi.dp(c, 3);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(gap, gap, gap, gap); // 6dp between cells
            cell.setLayoutParams(lp);
            cell.setBackground(GalleryUi.card(c, GalleryUi.CARD, 14));
            cell.setClipToOutline(true);
            cell.setClickable(true);
            cell.setFocusable(true);
            image = new ImageView(c);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), GalleryUi.dp(view.getContext(), 14));
                }
            });
            image.setClipToOutline(true);
            cell.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            badges = new LinearLayout(c);
            badges.setOrientation(LinearLayout.HORIZONTAL);
            badges.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.BOTTOM);
            bp.leftMargin = bp.bottomMargin = GalleryUi.dp(c, 6);
            cell.addView(badges, bp);
            check = new FrameLayout(c);
            checkMark = GalleryUi.icon(c, R.drawable.ui_ic_check, GalleryUi.INK, 14);
            check.addView(checkMark, new FrameLayout.LayoutParams(GalleryUi.dp(c, 14), GalleryUi.dp(c, 14), Gravity.CENTER));
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(GalleryUi.dp(c, 24), GalleryUi.dp(c, 24), Gravity.END | Gravity.TOP);
            cp.topMargin = cp.rightMargin = GalleryUi.dp(c, 6);
            cell.addView(check, cp);
            check.setVisibility(View.GONE);
            // P498: a favourite's heart, top left
            heart = GalleryUi.icon(c, R.drawable.ui_ic_heart_on, 0xFFFF6B81, 16);
            FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(GalleryUi.dp(c, 16), GalleryUi.dp(c, 16), Gravity.START | Gravity.TOP);
            hp.topMargin = hp.leftMargin = GalleryUi.dp(c, 7);
            heart.setElevation(GalleryUi.dp(c, 2));
            cell.addView(heart, hp);
            heart.setVisibility(View.GONE);
            watched = new FrameLayout(c);
            watched.setBackgroundColor(0x66000000);
            watchedLine = new View(c);
            watchedLine.setBackgroundColor(GalleryUi.accent(c));
            watchedLine.setPivotX(0);
            watched.addView(watchedLine, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            cell.addView(watched, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, GalleryUi.dp(c, 3), Gravity.BOTTOM));
            watched.setVisibility(View.GONE);
            com.particlesdevs.photoncamera.ui.Motion.press(cell, .96f);
            cell.setOnClickListener(v -> {
                if (item != null) host.onPhotoClicked(index, v);
            });
        }

        void bind(GalleryItem item, int index) {
            this.item = item;
            this.index = index;
            Context c = cell.getContext();
            GalleryFormat format = GalleryFormat.of(item, rawBases);
            badges.removeAllViews();
            if (format == GalleryFormat.VIDEO) badges.addView(videoBadge(c, com.particlesdevs.photoncamera.gallery.files.GalleryVideo.durationMs(item.getFile())));
            else badges.addView(badge(c, format.icon));
            heart.setVisibility(host.isFavourite(item) ? View.VISIBLE : View.GONE);
            Uri uri = item.getFile().getFileUri();
            float seen = format == GalleryFormat.VIDEO && uri != null ? com.particlesdevs.photoncamera.gallery.video.VideoResume.progress(c, uri.toString()) : -1;
            watched.setVisibility(seen > 0 ? View.VISIBLE : View.GONE);
            if (seen > 0) watchedLine.setScaleX(seen);
            Boolean uhdr = uri != null ? UHDR.get(uri) : Boolean.FALSE;
            if (Boolean.TRUE.equals(uhdr)) badges.addView(badge(c, R.drawable.ui_ic_sun));
            else if (uhdr == null && uri != null && loadPictures && isJpeg(item)) probe(c, uri);
            cell.setContentDescription(describe(item, format, Boolean.TRUE.equals(uhdr)));
            if (uri != null && loadPictures) loadThumb(item, uri);
            applySelection(false);
        }

        /** The cell's picture; P89: after the camera has finished writing the file. */
        private void loadThumb(GalleryItem it, Uri uri) {
            if (GalleryKeys.waitForFile(image, it.getFile(), () -> {
                if (item == it) loadThumb(it, uri);
            })) {
                Glide.with(image).clear(image);
                return;
            }
            // P620: the cheapest large-enough source (GalleryThumb / ThumbDecoder), not cropped (the view crops), shared with
            // the viewer's film strip and its first picture; visible cells before the preloads
            com.bumptech.glide.RequestBuilder<android.graphics.Bitmap> request = com.particlesdevs.photoncamera.gallery.glide.GalleryThumb
                    .request(Glide.with(image), com.particlesdevs.photoncamera.gallery.glide.GalleryThumb.of(it.getFile()), thumbPx)
                    .priority(com.bumptech.glide.Priority.HIGH);
            if (GalleryOpenTrace.countingThumbs()) request = request.listener(THUMB_TRACE); // P617: the first screen's timing
            request.into(image);
        }

        private void probe(Context c, Uri uri) {
            final Context app = c.getApplicationContext();
            final GalleryItem bound = item;
            PROBE.execute(() -> {
                boolean hdr = UltraHdrGalleryUtil.isUltraHdrJpeg(app, uri);
                UHDR.put(uri, hdr);
                if (hdr) main.post(() -> {
                    if (item == bound) bind(bound, index);
                });
            });
        }

        void applySelection(boolean animate) {
            if (item == null) return;
            Context c = cell.getContext();
            boolean selecting = host.selecting(), on = selecting && host.isSelected(item);
            boolean was = checkMark.getVisibility() == View.VISIBLE && check.getVisibility() == View.VISIBLE;
            int accent = GalleryUi.accent(c);
            // P579: the chosen one framed in the accent (2dp), no outline otherwise
            android.graphics.drawable.GradientDrawable frame = GalleryUi.card(c, GalleryUi.CARD, 14);
            if (on) frame.setStroke(GalleryUi.dp(c, 2), accent);
            cell.setBackground(frame);
            GalleryMotion.select(image, on, animate); // P498: the thumbnail sinks into its frame on the spring
            if (animate && on && !was) com.particlesdevs.photoncamera.ui.Motion.pop(check);
            FrameLayout.LayoutParams bp = (FrameLayout.LayoutParams) badges.getLayoutParams();
            bp.leftMargin = bp.bottomMargin = GalleryUi.dp(c, on ? 12 : 6);
            badges.setLayoutParams(bp);
            check.setVisibility(selecting ? View.VISIBLE : View.GONE);
            check.setBackground(GalleryUi.round(c, on ? accent : 0x66000000, on ? accent : 0xFFFFFFFF, 999));
            checkMark.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            if (selecting) androidx.core.view.ViewCompat.setStateDescription(cell, on ? com.particlesdevs.photoncamera.util.Lang.t("выбрано", "selected")
                    : com.particlesdevs.photoncamera.util.Lang.t("не выбрано", "not selected"));
            else androidx.core.view.ViewCompat.setStateDescription(cell, null);
        }
    }

    /** P498: a video's badge: the play glyph and its length («0:42») on the dark backing. */
    static View videoBadge(Context c, long durationMs) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackground(GalleryUi.round(c, GalleryUi.SCRIM, GalleryUi.SCRIM, 9));
        b.setPadding(GalleryUi.dp(c, 4), GalleryUi.dp(c, 3), GalleryUi.dp(c, durationMs > 0 ? 7 : 4), GalleryUi.dp(c, 3));
        b.addView(GalleryUi.icon(c, R.drawable.ui_ic_play, GalleryUi.TEXT, 14));
        if (durationMs > 0) {
            TextView t = GalleryUi.text(c, com.particlesdevs.photoncamera.gallery.files.GalleryVideo.clock(durationMs), 11, GalleryUi.TEXT);
            t.setTypeface(com.particlesdevs.photoncamera.ui.style.Fonts.of(t.getContext(), 700));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.leftMargin = GalleryUi.dp(c, 3);
            b.addView(t, tp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = GalleryUi.dp(c, 4);
        b.setLayoutParams(lp);
        b.setTag(R.drawable.ui_ic_play);
        return b;
    }

    /** A badge: the icon (16dp, at least 14dp) on a solid dark rounded backing. */
    static View badge(Context c, int icon) {
        FrameLayout b = new FrameLayout(c);
        b.setBackground(GalleryUi.round(c, GalleryUi.SCRIM, GalleryUi.SCRIM, 9));
        int pad = GalleryUi.dp(c, 4);
        b.setPadding(pad, pad, pad, pad);
        ImageView i = GalleryUi.icon(c, icon, GalleryUi.TEXT, 16);
        b.addView(i, new FrameLayout.LayoutParams(GalleryUi.dp(c, 16), GalleryUi.dp(c, 16)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = GalleryUi.dp(c, 4);
        b.setLayoutParams(lp);
        b.setTag(icon);
        return b;
    }

    String describe(GalleryItem item, GalleryFormat format, boolean uhdr) {
        StringBuilder s = new StringBuilder(GalleryUi.dayTime(item.getFile().getLastModified(), now));
        if (format == GalleryFormat.VIDEO) {
            s.append(", ").append(com.particlesdevs.photoncamera.util.Lang.t("видео", "video"));
            long d = com.particlesdevs.photoncamera.gallery.files.GalleryVideo.durationMs(item.getFile());
            if (d > 0) s.append(" ").append(com.particlesdevs.photoncamera.gallery.files.GalleryVideo.clock(d));
        }
        if (!format.label.isEmpty()) s.append(", ").append(format.label);
        if (host.isFavourite(item)) s.append(", ").append(com.particlesdevs.photoncamera.util.Lang.t("избранное", "favourite"));
        if (uhdr) s.append(", Ultra HDR");
        return s.toString();
    }

    /** Test hook: marks a file as Ultra HDR (or not) without reading it. */
    static void knownUltraHdr(Uri uri, boolean hdr) {
        UHDR.put(uri, hdr);
    }

    static boolean isJpeg(GalleryItem item) {
        String ext = GalleryFormat.extension(item.getFile().getDisplayName());
        return "jpg".equals(ext) || "jpeg".equals(ext);
    }

    /** Test hook: false = no picture loads and no Ultra HDR probes (layout tests). */
    static boolean loadPictures = true;
}
