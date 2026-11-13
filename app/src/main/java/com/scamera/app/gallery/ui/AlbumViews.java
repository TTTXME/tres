package com.scamera.app.gallery.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.scamera.app.R;
import com.scamera.app.gallery.model.GalleryItem;
import com.scamera.app.gallery.model.GalleryLayout;
import com.scamera.app.ui.Motion;
import com.scamera.app.util.Lang;

import java.util.ArrayList;
import java.util.List;

/**
 * P622: the albums page's sections in the «Список» and «Лента» views. The list: one card of rows (the settings' grouped look) - the
 * cover (the album's newest file, rounded), the name, «128 фото · 3 видео · 12 RAW», an accent badge with the files added since the
 * album was last opened, a chevron. The timeline: two big rounded covers a row, the name and the count under each (the phone
 * library's album shelf, its corners 20 % of the way to this app's). Rows and covers come in one after another and give under the
 * finger on the shared springs.
 */
public final class AlbumViews {
    private AlbumViews() {}

    public interface Open {
        void open(AlbumsPage.Album album);
    }

    /** The first {@code n} of {@code albums} in {@code mode} (not the grid: AlbumsPage draws that itself). */
    public static View body(Context c, GalleryLayout.Mode mode, List<AlbumsPage.Album> albums, int n, Open open) {
        return mode == GalleryLayout.Mode.TIMELINE ? covers(c, albums, n, open) : rows(c, albums, n, open);
    }

    /** The cover size of a list row (dp). */
    static final int COVER = 56;

    static LinearLayout rows(Context c, List<AlbumsPage.Album> albums, int n, Open open) {
        LinearLayout group = GalleryUi.group(c, 16 + COVER + 14);
        List<View> cells = new ArrayList<>();
        for (int i = 0; i < Math.min(n, albums.size()); i++) {
            View r = row(c, albums.get(i), open);
            group.addView(r);
            cells.add(r);
        }
        if (Motion.enabled(group)) Motion.enter(cells, 30, 16);
        return group;
    }

    static View row(Context c, AlbumsPage.Album a, Open open) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(GalleryUi.dp(c, 76));
        r.setPaddingRelative(GalleryUi.dp(c, 16), GalleryUi.dp(c, 10), GalleryUi.dp(c, 14), GalleryUi.dp(c, 10));
        r.setBackground(GalleryUi.pressable(GalleryUi.round(c, 0, 0, 0)));
        FrameLayout holder = new FrameLayout(c);
        holder.setClipChildren(false);
        FrameLayout cover = cover(c, a, GalleryUi.dp(c, COVER), 14);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(GalleryUi.dp(c, COVER), GalleryUi.dp(c, COVER), Gravity.BOTTOM | Gravity.START);
        holder.addView(cover, cp);
        String badge = GalleryLayout.badge(GalleryLayout.newCount(c, a.title, a.folder.groupedItems()));
        if (badge != null) {
            TextView b = badge(c, badge);
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            holder.addView(b, bp);
        }
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(GalleryUi.dp(c, COVER + 7), GalleryUi.dp(c, COVER + 7));
        r.addView(holder, hp);
        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = GalleryUi.text(c, a.title, 16, GalleryUi.TEXT);
        name.setTypeface(com.scamera.app.ui.style.Fonts.of(c, 600));
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(name);
        String counts = RowText.counts(a.folder.groupedItems());
        TextView n = GalleryUi.text(c, counts, 13, GalleryUi.MUTED);
        n.setMaxLines(2);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = GalleryUi.dp(c, 4);
        text.addView(n, np);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tp.setMarginStart(GalleryUi.dp(c, 7));
        r.addView(text, tp);
        ImageView chev = GalleryUi.icon(c, R.drawable.cs_chevron, GalleryUi.SECTION, 16);
        chev.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (chev.getDrawable() != null) chev.getDrawable().mutate().setAutoMirrored(true);
        LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(GalleryUi.dp(c, 16), GalleryUi.dp(c, 16));
        chp.setMarginStart(GalleryUi.dp(c, 8));
        r.addView(chev, chp);
        r.setClickable(true);
        r.setFocusable(true);
        r.setContentDescription(a.title + ", " + counts + (badge != null ? ", " + Lang.t("новых: ", "new: ") + badge : ""));
        r.setOnClickListener(v -> open.open(a));
        Motion.press(r, .98f);
        return r;
    }

    static LinearLayout covers(Context c, List<AlbumsPage.Album> albums, int n, Open open) {
        final int per = 2 * GalleryUi.widthFactor(c);
        LinearLayout grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = null;
        List<View> cells = new ArrayList<>();
        int count = Math.min(n, albums.size());
        int width = c.getResources().getDisplayMetrics().widthPixels / per;
        for (int i = 0; i < count; i++) {
            if (i % per == 0) {
                line = new LinearLayout(c);
                line.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = GalleryUi.dp(c, 16);
                grid.addView(line, lp);
            }
            View cell = shelfCover(c, albums.get(i), width, open);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i % per != 0) lp.setMarginStart(GalleryUi.dp(c, 12));
            line.addView(cell, lp);
            cells.add(cell);
        }
        if (line != null) for (int k = count % per; k > 0 && k < per; ++k) {
            View pad = new View(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1);
            lp.setMarginStart(GalleryUi.dp(c, 12));
            line.addView(pad, lp);
        }
        if (Motion.enabled(grid)) Motion.enter(cells, 30, 20);
        return grid;
    }

    static View shelfCover(Context c, AlbumsPage.Album a, int px, Open open) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        FrameLayout square = new AlbumsPage.SquareBox(c);
        FrameLayout cover = cover(c, a, px, TimelineRows.CARD_RADIUS);
        square.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        String badge = GalleryLayout.badge(GalleryLayout.newCount(c, a.title, a.folder.groupedItems()));
        if (badge != null) {
            TextView b = badge(c, badge);
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            bp.topMargin = bp.rightMargin = GalleryUi.dp(c, 8);
            square.addView(b, bp);
        }
        box.addView(square);
        TextView name = GalleryUi.text(c, a.title, 15, GalleryUi.TEXT);
        name.setTypeface(com.scamera.app.ui.style.Fonts.of(c, 600));
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(GalleryUi.dp(c, 2), GalleryUi.dp(c, 8), GalleryUi.dp(c, 2), 0);
        box.addView(name);
        String counts = RowText.counts(a.folder.groupedItems());
        TextView n = GalleryUi.text(c, counts, 13, GalleryUi.MUTED);
        n.setMaxLines(2);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        n.setPadding(GalleryUi.dp(c, 2), GalleryUi.dp(c, 3), GalleryUi.dp(c, 2), 0);
        box.addView(n);
        box.setClickable(true);
        box.setFocusable(true);
        box.setContentDescription(a.title + ", " + counts + (badge != null ? ", " + Lang.t("новых: ", "new: ") + badge : ""));
        box.setOnClickListener(v -> open.open(a));
        Motion.press(box, .96f);
        return box;
    }

    /** The album's cover: its newest file's thumbnail at {@code px}, rounded; an album icon when it has none. */
    static FrameLayout cover(Context c, AlbumsPage.Album a, int px, float radiusDp) {
        FrameLayout cover = new FrameLayout(c);
        cover.setBackground(GalleryUi.card(c, 0xFF3A3A3C, radiusDp));
        RowViews.round(cover, radiusDp);
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        List<GalleryItem> files = a.folder.groupedItems();
        if (!files.isEmpty() && files.get(0).mediaFile() != null) {
            ImageView image = new ImageView(c);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setTag("thumb");
            cover.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            RowViews.thumb(image, files.get(0).mediaFile(), RowViews.step(px), null);
        } else {
            ImageView none = GalleryUi.icon(c, R.drawable.ui_ic_albums, GalleryUi.MUTED, 24);
            cover.addView(none, new FrameLayout.LayoutParams(GalleryUi.dp(c, 24), GalleryUi.dp(c, 24), Gravity.CENTER));
        }
        return cover;
    }

    /** The new files' count: the accent capsule with dark ink (11sp bold, at least a circle). */
    static TextView badge(Context c, String text) {
        TextView b = GalleryUi.text(c, text, 11, GalleryUi.INK);
        b.setTypeface(com.scamera.app.ui.style.Fonts.of(c, 700));
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(GalleryUi.dp(c, 20));
        b.setMinHeight(GalleryUi.dp(c, 20));
        b.setPadding(GalleryUi.dp(c, 5), GalleryUi.dp(c, 2), GalleryUi.dp(c, 5), GalleryUi.dp(c, 2));
        b.setBackground(GalleryUi.round(c, GalleryUi.accent(c), GalleryUi.BG, 999));
        b.setElevation(GalleryUi.dp(c, 2));
        b.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return b;
    }
}
