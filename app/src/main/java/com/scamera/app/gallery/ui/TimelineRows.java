package com.scamera.app.gallery.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.scamera.app.R;
import com.scamera.app.gallery.files.GalleryVideo;
import com.scamera.app.gallery.files.MediaFile;
import com.scamera.app.gallery.model.GalleryItem;
import com.scamera.app.gallery.model.GalleryLayout;
import com.scamera.app.ui.Motion;
import com.scamera.app.util.Lang;

import java.util.List;

/**
 * P622 (owner, 2026-11-11: «придумай свой третий вид для нашей галереи в стиле IOS, но на 20% ближе к нашему»): the «Лента» view.
 * The phone library's layout - an edge-to-edge square grid with hairline gaps under month titles, zoomed out to one card per month
 * and one per year - with every shape token taken 20 % of the way from that library to this app's concept ({@link #lerp}): 2dp gaps
 * (1 → 6), 3dp tile corners (0 → 14), a 2dp page edge (0 → 12), 13dp card corners (10 → 24), 18sp month titles (20 → 12); and the
 * rest of the app as it is: the #1C1C1E page, the header, the tab bar, the accent on the selection, Roboto Flex and its weights,
 * the shared springs. A month card zooms in to its photos, a year card to its months.
 */
public final class TimelineRows implements RowStyle {
    /** A card was tapped: zoom in to {@code level} at item {@code item}. */
    public interface Nav {
        void zoom(GalleryLayout.Level level, int item);
    }

    static final int TILE = 21, MONTH = 20, MONTH_CARD = 22, YEAR_CARD = 23;

    /** The share of the way from the phone library's token to this app's (the owner's «на 20% ближе к нашему»). */
    public static final float OURS = .2f;

    public static float lerp(float library, float ours) {
        return library + (ours - library) * OURS;
    }

    /** dp: the gap between tiles, a tile's corners, the page's edge, a card's corners; sp: a month title. */
    public static final float GAP = lerp(1, 6), TILE_RADIUS = lerp(0, 14), EDGE = lerp(0, 12), CARD_RADIUS = lerp(10, 24),
            TITLE_SP = lerp(20, 12);

    private final GalleryLayout.Level level;
    private final Nav nav;

    public TimelineRows(GalleryLayout.Level level, Nav nav) {
        this.level = level;
        this.nav = nav;
    }

    public GalleryLayout.Level level() {
        return level;
    }

    @Override
    public List<LibraryAdapter.Row> rows(List<GalleryItem> items, long now) {
        switch (level) {
            case YEARS: return TimelineSections.years(items);
            case MONTHS: return TimelineSections.months(items);
            default: return TimelineSections.all(items);
        }
    }

    @Override
    public int viewType(LibraryAdapter.Row row) {
        if (row.type == LibraryAdapter.HEADER) return MONTH;
        if (row.type == GROUP) return level == GalleryLayout.Level.YEARS ? YEAR_CARD : MONTH_CARD;
        return TILE;
    }

    @Override
    public int span(LibraryAdapter.Row row, int columns) {
        return row.type == LibraryAdapter.HEADER ? columns : 1; // a card is one span: two a row on a wide screen
    }

    @Override
    public RecyclerView.ViewHolder create(ViewGroup parent, int viewType, LibraryAdapter adapter) {
        Context c = parent.getContext();
        switch (viewType) {
            case MONTH: return new MonthHolder(c, adapter);
            case MONTH_CARD: case YEAR_CARD: return new CardHolder(c, viewType == YEAR_CARD);
            default: return new TileHolder(c, adapter);
        }
    }

    @Override
    public void bind(RecyclerView.ViewHolder holder, LibraryAdapter.Row row, LibraryAdapter adapter) {
        if (holder instanceof TileHolder) ((TileHolder) holder).bind(row, adapter);
        else if (holder instanceof MonthHolder) ((MonthHolder) holder).bind(row, adapter);
        else if (holder instanceof CardHolder) ((CardHolder) holder).bind(row, adapter);
    }

    @Override
    public void refreshSelection(RecyclerView.ViewHolder holder, LibraryAdapter adapter) {
        if (holder instanceof TileHolder) ((TileHolder) holder).paint(adapter, true);
        else if (holder instanceof MonthHolder) ((MonthHolder) holder).paint(adapter);
    }

    // ---------------------------------------------------------------- every photo

    static final class TileHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView length, raw;
        final ImageView heart;
        final FrameLayout check;
        GalleryItem item;
        int index;

        TileHolder(Context c, LibraryAdapter adapter) {
            super(new LibraryAdapter.Square(c));
            FrameLayout cell = (FrameLayout) itemView;
            int half = Math.max(1, GalleryUi.dp(c, GAP / 2));
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(half, half, half, half);
            cell.setLayoutParams(lp);
            cell.setBackgroundColor(GalleryUi.CARD);
            RowViews.round(cell, TILE_RADIUS);
            image = new ImageView(c);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setTag("thumb");
            cell.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // the library's video length: plain white text with a soft shadow at the bottom right
            length = GalleryUi.text(c, "", 11, GalleryUi.TEXT);
            length.setTypeface(com.scamera.app.ui.style.Fonts.of(c, 600));
            length.setShadowLayer(GalleryUi.dp(c, 3), 0, 0, 0xB3000000);
            FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.END);
            dp.rightMargin = dp.bottomMargin = GalleryUi.dp(c, 5);
            cell.addView(length, dp);
            // 20 % ours: a RAW keeps a small mark (the grid's format badge, reduced to a word)
            raw = GalleryUi.text(c, "RAW", 8.5f, GalleryUi.TEXT);
            raw.setTypeface(com.scamera.app.ui.style.Fonts.of(c, 700));
            raw.setPadding(GalleryUi.dp(c, 4), GalleryUi.dp(c, 1), GalleryUi.dp(c, 4), GalleryUi.dp(c, 1));
            raw.setBackground(GalleryUi.round(c, GalleryUi.SCRIM, 0, 5));
            FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            rp.rightMargin = rp.topMargin = GalleryUi.dp(c, 4);
            cell.addView(raw, rp);
            heart = GalleryUi.icon(c, R.drawable.ui_ic_heart_on, GalleryUi.TEXT, 12);
            heart.setElevation(GalleryUi.dp(c, 2));
            FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(GalleryUi.dp(c, 12), GalleryUi.dp(c, 12), Gravity.BOTTOM | Gravity.START);
            hp.leftMargin = hp.bottomMargin = GalleryUi.dp(c, 5);
            cell.addView(heart, hp);
            check = RowViews.check(c);
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(GalleryUi.dp(c, 22), GalleryUi.dp(c, 22), Gravity.BOTTOM | Gravity.END);
            cp.rightMargin = cp.bottomMargin = GalleryUi.dp(c, 5);
            cell.addView(check, cp);
            cell.setClickable(true);
            cell.setFocusable(true);
            Motion.press(cell, .95f);
            cell.setOnClickListener(v -> {
                if (item != null) adapter.host().onPhotoClicked(index, v);
            });
        }

        void bind(LibraryAdapter.Row row, LibraryAdapter adapter) {
            final GalleryItem it = adapter.items().get(row.item);
            item = it;
            index = row.item;
            MediaFile f = it.mediaFile();
            boolean video = GalleryVideo.isVideo(f);
            String dur = video ? RowText.duration(GalleryVideo.durationMs(f)) : null;
            length.setText(dur != null ? dur : "");
            length.setVisibility(dur != null ? View.VISIBLE : View.GONE);
            GalleryFormat format = GalleryFormat.of(it, adapter.rawBases());
            raw.setVisibility(format == GalleryFormat.RAW ? View.VISIBLE : View.GONE);
            boolean fav = adapter.host().isFavourite(it);
            heart.setVisibility(fav ? View.VISIBLE : View.GONE);
            StringBuilder d = new StringBuilder(GalleryUi.dayTime(f.modifiedMs(), adapter.now));
            if (video) d.append(", ").append(Lang.t("видео", "video")).append(dur != null ? " " + dur : "");
            if (!format.label.isEmpty()) d.append(", ").append(format.label);
            if (fav) d.append(", ").append(Lang.t("избранное", "favourite"));
            itemView.setContentDescription(d.toString());
            RowViews.thumb(image, f, adapter.thumbPx(), () -> {
                if (item == it) RowViews.thumb(image, f, adapter.thumbPx(), null);
            });
            paint(adapter, false);
        }

        void paint(LibraryAdapter adapter, boolean animate) {
            if (item == null) return;
            boolean selecting = adapter.host().selecting(), on = selecting && adapter.host().isSelected(item);
            RowViews.paintCheck(check, selecting, on, animate);
            float a = on ? .62f : 1f;
            if (animate && Motion.enabled(image)) image.animate().alpha(a).setDuration(180).setInterpolator(Motion.EASE_OUT).start();
            else image.setAlpha(a);
            if (selecting) androidx.core.view.ViewCompat.setStateDescription(itemView, on ? Lang.t("выбрано", "selected") : Lang.t("не выбрано", "not selected"));
            else androidx.core.view.ViewCompat.setStateDescription(itemView, null);
        }
    }

    /** «Ноябрь 2026» (the year in grey) and the month's count; while selecting a tap selects the month. */
    static final class MonthHolder extends RecyclerView.ViewHolder {
        final TextView title, count;
        LibraryAdapter.Row row;

        MonthHolder(Context c, LibraryAdapter adapter) {
            super(new LinearLayout(c));
            LinearLayout r = (LinearLayout) itemView;
            r.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.BOTTOM);
            r.setPaddingRelative(GalleryUi.dp(c, 10), GalleryUi.dp(c, 20), GalleryUi.dp(c, 10), GalleryUi.dp(c, 8));
            title = GalleryUi.text(c, "", TITLE_SP, GalleryUi.TEXT);
            title.setTypeface(com.scamera.app.ui.style.Fonts.of(c, 700));
            title.setLetterSpacing(-0.01f);
            title.setMaxLines(2);
            title.setAccessibilityHeading(true);
            r.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            count = GalleryUi.text(c, "", 13, GalleryUi.SECTION);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.setMarginStart(GalleryUi.dp(c, 8));
            r.addView(count, cp);
            r.setOnClickListener(v -> {
                if (row != null && adapter.host().selecting()) adapter.host().onDayClicked(row.item, row.count);
            });
        }

        void bind(LibraryAdapter.Row r, LibraryAdapter adapter) {
            row = r;
            String label = r.label != null ? r.label : "";
            int space = label.lastIndexOf(' ');
            SpannableStringBuilder s = new SpannableStringBuilder(label);
            if (space > 0) s.setSpan(new ForegroundColorSpan(GalleryUi.MUTED), space + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            title.setText(s);
            int[] t = RowText.tally(adapter.items().subList(r.item, Math.min(adapter.items().size(), r.item + r.count)));
            count.setText(GalleryUi.dayCount(t[0], t[1]));
            itemView.setContentDescription(label + ", " + count.getText());
            paint(adapter);
        }

        void paint(LibraryAdapter adapter) {
            boolean selecting = adapter.host().selecting();
            itemView.setClickable(selecting);
            count.setTextColor(selecting ? GalleryUi.accent(itemView.getContext()) : GalleryUi.SECTION);
        }
    }

    // ---------------------------------------------------------------- months and years

    /** A month card (its newest photo, the title at the top on a fade) or a year card (three months' photos side by side). */
    final class CardHolder extends RecyclerView.ViewHolder {
        final boolean year;
        final ImageView[] images;
        final TextView title, sub;
        LibraryAdapter.Row row;

        CardHolder(Context c, boolean year) {
            super(new CardFrame(c, year ? .78f : .62f));
            this.year = year;
            FrameLayout card = (FrameLayout) itemView;
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int side = GalleryUi.dp(c, 10 - EDGE);
            lp.setMargins(side, GalleryUi.dp(c, 6), side, GalleryUi.dp(c, 6));
            card.setLayoutParams(lp);
            card.setBackgroundColor(GalleryUi.CARD);
            RowViews.round(card, CARD_RADIUS);
            if (year) {
                LinearLayout mosaic = new LinearLayout(c);
                mosaic.setOrientation(LinearLayout.HORIZONTAL);
                images = new ImageView[]{image(c), image(c), image(c)};
                mosaic.addView(images[0], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2));
                LinearLayout right = new LinearLayout(c);
                right.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
                a.bottomMargin = GalleryUi.dp(c, GAP / 2);
                right.addView(images[1], a);
                LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
                b.topMargin = GalleryUi.dp(c, GAP / 2);
                right.addView(images[2], b);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                rp.setMarginStart(GalleryUi.dp(c, GAP));
                mosaic.addView(right, rp);
                card.addView(mosaic, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                images = new ImageView[]{image(c)};
                card.addView(images[0], new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            View fade = new View(c);
            fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x99000000, 0x00000000}));
            card.addView(fade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, GalleryUi.dp(c, 110), Gravity.TOP));
            LinearLayout text = new LinearLayout(c);
            text.setOrientation(LinearLayout.VERTICAL);
            title = GalleryUi.text(c, "", year ? 34 : 26, GalleryUi.TEXT);
            title.setTypeface(com.scamera.app.ui.style.Fonts.of(c, 700));
            title.setLetterSpacing(-0.012f);
            title.setMaxLines(2);
            title.setShadowLayer(GalleryUi.dp(c, 6), 0, 0, 0x66000000);
            text.addView(title);
            sub = GalleryUi.text(c, "", 13, 0xE6FFFFFF);
            sub.setMaxLines(2);
            sub.setShadowLayer(GalleryUi.dp(c, 4), 0, 0, 0x66000000);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.topMargin = GalleryUi.dp(c, 3);
            text.addView(sub, sp);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
            tp.leftMargin = tp.rightMargin = tp.topMargin = GalleryUi.dp(c, 16);
            card.addView(text, tp);
            card.setClickable(true);
            card.setFocusable(true);
            Motion.press(card, .97f);
            card.setOnClickListener(v -> {
                if (row != null) nav.zoom(this.year ? GalleryLayout.Level.MONTHS : GalleryLayout.Level.ALL, row.item);
            });
        }

        private ImageView image(Context c) {
            ImageView i = new ImageView(c);
            i.setScaleType(ImageView.ScaleType.CENTER_CROP);
            i.setBackgroundColor(GalleryUi.CARD);
            i.setTag("thumb");
            return i;
        }

        void bind(LibraryAdapter.Row r, LibraryAdapter adapter) {
            row = r;
            Context c = itemView.getContext();
            title.setText(r.label);
            List<GalleryItem> run = adapter.items().subList(r.item, Math.min(adapter.items().size(), r.item + r.count));
            sub.setText(RowText.counts(run));
            itemView.setContentDescription(r.label + ", " + sub.getText() + ", "
                    + (year ? Lang.t("открыть месяцы", "open the months") : Lang.t("открыть фото месяца", "open the month's photos")));
            int width = c.getResources().getDisplayMetrics().widthPixels;
            int[] covers = year ? TimelineSections.monthCovers(adapter.items(), r.item, r.count, 3) : new int[]{r.item};
            for (int i = 0; i < images.length; i++) {
                if (i >= covers.length) {
                    com.bumptech.glide.Glide.with(images[i]).clear(images[i]);
                    images[i].setImageDrawable(null);
                    continue;
                }
                int px = RowViews.step(year && i > 0 ? width / 3 : year ? width * 2 / 3 : width);
                final MediaFile f = adapter.items().get(covers[i]).mediaFile();
                final ImageView view = images[i];
                final LibraryAdapter.Row bound = r;
                RowViews.thumb(view, f, px, () -> {
                    if (row == bound) RowViews.thumb(view, f, px, null);
                });
            }
        }
    }

    /** A card {@code ratio} as tall as it is wide. */
    static final class CardFrame extends FrameLayout {
        private final float ratio;

        CardFrame(Context c, float ratio) {
            super(c);
            this.ratio = ratio;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec);
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(Math.round(w * ratio), MeasureSpec.EXACTLY));
        }
    }
}
