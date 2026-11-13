package com.scamera.app.gallery.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.scamera.app.R;
import com.scamera.app.gallery.model.GalleryItem;
import com.scamera.app.ui.Motion;
import com.scamera.app.util.Lang;

import java.util.ArrayList;
import java.util.List;

/**
 * P502 (owner, 2026-11-05: the reference video's «Альбомы»): the albums page in the shade's style - sections with a title, a
 * collapse chevron and «Ещё», each remembered collapsed or open:
 * <ul>
 * <li>«Часто используемые»: Все, Камера, Избранное, Видео, RAW, Скриншоты, Записи экрана (those that have something) as big cards;</li>
 * <li>«Другие альбомы»: every other folder, 9 tiles until «Ещё»;</li>
 * <li>«Тип»: rows with a count (Видео, RAW, HEIC, JPEG, Ultra HDR is the grid's badge);</li>
 * <li>«Другое»: «Недавно удаленные» (Android 11's trash, 30 days) and the folders the «Фото» tab shows.</li>
 * </ul>
 * The reference's people, places and smart categories need an on-device recogniser this app does not ship: left out.
 * P579 (owner, 2026-11-09: «Не забудь что у нас есть галерея и в ней надо стиль также обновить»): the settings' look - section titles
 * 20sp bold with «Ещё» in the accent, the covers with 16dp corners, «Тип» and «Другое» rows of one #2C2C2E card (24dp corners,
 * hairlines, a chevron) instead of tonal tiles.
 */
public final class AlbumsPage {
    public interface Host {
        void openAlbum(GalleryItem folder, String title);

        void openTrash();

        void openPhotoFolders();
    }

    /** An album: its title, its files (newest first), an icon for the type rows. */
    public static final class Album {
        public final String title;
        public final GalleryItem folder;
        @DrawableRes public final int icon;

        public Album(String title, GalleryItem folder, @DrawableRes int icon) {
            this.title = title;
            this.folder = folder;
            this.icon = icon;
        }

        int count() {
            return folder.getFiles().size();
        }

        @Nullable
        Uri cover() {
            return folder.getFiles().isEmpty() ? null : folder.getFiles().get(0).getFile().getFileUri();
        }
    }

    public final ScrollView view;
    private final LinearLayout column;
    private final Host host;
    private final SharedPreferences prefs;
    private List<Album> frequent = new ArrayList<>(), others = new ArrayList<>(), types = new ArrayList<>();
    private int trashCount = -1;
    /** P622: the gallery's view (the list and the timeline draw the albums their own way: AlbumViews). */
    private com.scamera.app.gallery.model.GalleryLayout.Mode mode = com.scamera.app.gallery.model.GalleryLayout.Mode.GRID;

    public AlbumsPage(Context c, Host host) {
        this.host = host;
        prefs = c.getSharedPreferences("gallery_ui", Context.MODE_PRIVATE);
        view = new ScrollView(c);
        view.setFillViewport(true);
        view.setClipToPadding(false);
        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(GalleryUi.dp(c, 16), 0, GalleryUi.dp(c, 16), GalleryUi.dp(c, 120));
        view.addView(column);
    }

    /** The page's albums; {@code trashCount} < 0: no trash on this Android. */
    public void bind(List<Album> frequent, List<Album> others, List<Album> types, int trashCount) {
        this.frequent = frequent;
        this.others = others;
        this.types = types;
        this.trashCount = trashCount;
        render();
    }

    /** P622: the albums drawn in {@code m} (animated: the page cross-fades and its sections come in one after another). */
    public void setMode(com.scamera.app.gallery.model.GalleryLayout.Mode m) {
        if (m == null || m == mode) return;
        mode = m;
        if (frequent.isEmpty() && others.isEmpty()) return;
        if (Motion.enabled(column)) {
            android.transition.TransitionSet t = new android.transition.TransitionSet()
                    .addTransition(new android.transition.Fade()).addTransition(new android.transition.ChangeBounds());
            t.setDuration(280);
            t.setInterpolator(Motion.SHEET);
            android.transition.TransitionManager.beginDelayedTransition(column, t);
        }
        render();
    }

    private void render() {
        Context c = view.getContext();
        column.removeAllViews();
        final boolean grid = mode == com.scamera.app.gallery.model.GalleryLayout.Mode.GRID;
        // P622: the list shows more rows before «Ещё» (a row is smaller than a tile)
        final int firstFrequent = grid ? 6 : frequent.size(), firstOthers = grid ? 9 : 12;
        final AlbumViews.Open opener = a -> host.openAlbum(a.folder, a.title);
        if (!frequent.isEmpty()) section(c, "frequent", Lang.t("Часто используемые", "Frequently used"), frequent.size() > firstFrequent, open -> {
            int n = open ? frequent.size() : Math.min(firstFrequent, frequent.size());
            return grid ? tiles(c, frequent, n, true) : AlbumViews.body(c, mode, frequent, n, opener);
        });
        if (!others.isEmpty()) section(c, "others", Lang.t("Другие альбомы", "Other albums"), others.size() > firstOthers, open -> {
            int n = open ? others.size() : Math.min(firstOthers, others.size());
            return grid ? tiles(c, others, n, false) : AlbumViews.body(c, mode, others, n, opener);
        });
        if (!types.isEmpty()) section(c, "types", Lang.t("Тип", "Type"), false, open -> typeRows(c));
        section(c, "other", Lang.t("Другое", "Other"), false, open -> otherRows(c));
    }

    interface Body {
        View make(boolean expanded);
    }

    /** A section: «Title ⌃» (collapses it) and «Ещё ›» (shows every item) when there are more. */
    private void section(Context c, String key, String title, boolean hasMore, Body body) {
        final boolean[] collapsed = {prefs.getBoolean("albums_collapsed_" + key, false)};
        final boolean[] expanded = {prefs.getBoolean("albums_expanded_" + key, false)};
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(GalleryUi.dp(c, 48));
        head.setPadding(GalleryUi.dp(c, 2), GalleryUi.dp(c, 10), 0, GalleryUi.dp(c, 2));
        TextView t = GalleryUi.text(c, title, 20, GalleryUi.TEXT);
        t.setTypeface(com.scamera.app.ui.style.Fonts.of(t.getContext(), 700));
        t.setAccessibilityHeading(true);
        head.addView(t);
        ImageView chev = GalleryUi.icon(c, R.drawable.ui_ic_chev, GalleryUi.SECTION, 18);
        chev.setRotation(collapsed[0] ? 90 : -90);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(GalleryUi.dp(c, 18), GalleryUi.dp(c, 18));
        cp.setMarginStart(GalleryUi.dp(c, 6));
        head.addView(chev, cp);
        View fill = new View(c);
        head.addView(fill, new LinearLayout.LayoutParams(0, 1, 1));
        TextView more = GalleryUi.text(c, "", 15, GalleryUi.accent(c));
        more.setPadding(GalleryUi.dp(c, 10), GalleryUi.dp(c, 8), GalleryUi.dp(c, 2), GalleryUi.dp(c, 8));
        more.setBackground(GalleryUi.pressable(GalleryUi.round(c, 0, 0, 999)));
        head.addView(more);
        column.addView(head);
        FrameLayout holder = new FrameLayout(c);
        column.addView(holder);
        Runnable apply = () -> {
            holder.removeAllViews();
            if (!collapsed[0]) holder.addView(body.make(expanded[0]));
            holder.setVisibility(collapsed[0] ? View.GONE : View.VISIBLE);
            more.setVisibility(hasMore && !collapsed[0] ? View.VISIBLE : View.GONE);
            more.setText(expanded[0] ? Lang.t("Свернуть", "Less") : Lang.t("Ещё ›", "More ›"));
            chev.animate().rotation(collapsed[0] ? 90 : -90).setDuration(220).setInterpolator(Motion.SHEET).start();
        };
        head.setClickable(true);
        head.setContentDescription(title);
        head.setOnClickListener(v -> {
            collapsed[0] = !collapsed[0];
            prefs.edit().putBoolean("albums_collapsed_" + key, collapsed[0]).apply();
            if (Motion.enabled(column)) android.transition.TransitionManager.beginDelayedTransition(column);
            apply.run();
        });
        more.setOnClickListener(v -> {
            expanded[0] = !expanded[0];
            prefs.edit().putBoolean("albums_expanded_" + key, expanded[0]).apply();
            if (Motion.enabled(column)) android.transition.TransitionManager.beginDelayedTransition(column);
            apply.run();
        });
        apply.run();
    }

    /** {@code n} album tiles, 3 a row (times the screen's width factor); {@code big}: the frequent albums' cards. */
    private LinearLayout tiles(Context c, List<Album> albums, int n, boolean big) {
        final int per = 3 * GalleryUi.widthFactor(c);
        LinearLayout grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        List<View> cells = new ArrayList<>();
        for (int i = 0; i < n; ++i) {
            if (i % per == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = GalleryUi.dp(c, big ? 10 : 14);
                grid.addView(row, rp);
            }
            View tile = tile(c, albums.get(i), big);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i % per != 0) lp.leftMargin = GalleryUi.dp(c, 10);
            row.addView(tile, lp);
            cells.add(tile);
        }
        if (row != null) for (int k = n % per; k > 0 && k < per; ++k) {
            View pad = new View(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1);
            lp.leftMargin = GalleryUi.dp(c, 10);
            row.addView(pad, lp);
        }
        if (Motion.enabled(grid)) Motion.enter(cells, 30, 16);
        return grid;
    }

    private View tile(Context c, Album a, boolean big) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        FrameLayout cover = new SquareBox(c);
        float radius = GalleryUi.dp(c, 16);
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        cover.setClipToOutline(true);
        cover.setBackground(GalleryUi.card(c, GalleryUi.CARD, 16));
        ImageView image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Uri uri = a.cover();
        // P620: the thumbnail decoder (the cheapest large-enough source), uncropped (the view crops)
        if (uri != null) com.scamera.app.gallery.glide.GalleryThumb.request(Glide.with(image),
                com.scamera.app.gallery.glide.GalleryThumb.of(uri), 360).into(image);
        else {
            ImageView none = GalleryUi.icon(c, R.drawable.ui_ic_albums, GalleryUi.MUTED, 28);
            cover.addView(none, new FrameLayout.LayoutParams(GalleryUi.dp(c, 28), GalleryUi.dp(c, 28), Gravity.CENTER));
        }
        String count = String.valueOf(a.count());
        if (big) {
            // the frequent albums: the count and the name on a dark fade over the cover's bottom
            View fade = new View(c);
            fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xB0000000, 0x00000000}));
            cover.addView(fade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, GalleryUi.dp(c, 54), Gravity.BOTTOM));
            LinearLayout text = new LinearLayout(c);
            text.setOrientation(LinearLayout.VERTICAL);
            TextView n = GalleryUi.text(c, count, 11, 0xCCFFFFFF);
            TextView name = GalleryUi.fitText(c, a.title, 14, 0xFFFFFFFF);
            name.setTypeface(com.scamera.app.ui.style.Fonts.of(name.getContext(), 600));
            text.addView(n);
            text.addView(name);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM);
            tp.leftMargin = tp.rightMargin = tp.bottomMargin = GalleryUi.dp(c, 9);
            cover.addView(text, tp);
            box.addView(cover);
        } else {
            box.addView(cover);
            TextView name = GalleryUi.fitText(c, a.title, 14, GalleryUi.TEXT);
            name.setTypeface(com.scamera.app.ui.style.Fonts.of(name.getContext(), 600));
            name.setPadding(GalleryUi.dp(c, 2), GalleryUi.dp(c, 6), GalleryUi.dp(c, 2), 0);
            box.addView(name);
            TextView n = GalleryUi.text(c, count, 13, GalleryUi.MUTED);
            n.setPadding(GalleryUi.dp(c, 2), GalleryUi.dp(c, 2), GalleryUi.dp(c, 2), 0);
            box.addView(n);
        }
        box.setClickable(true);
        box.setFocusable(true);
        box.setContentDescription(a.title + ", " + GalleryUi.shots(a.count()));
        box.setOnClickListener(v -> host.openAlbum(a.folder, a.title));
        Motion.press(box);
        return box;
    }

    /** «Тип»: P579, one card of rows - an icon, the name, the count and a chevron. */
    private View typeRows(Context c) {
        LinearLayout list = GalleryUi.group(c, 52);
        for (Album a : types) list.addView(row(c, a.icon, a.title, String.valueOf(a.count()), v -> host.openAlbum(a.folder, a.title)));
        return list;
    }

    private View otherRows(Context c) {
        LinearLayout list = GalleryUi.group(c, 52);
        if (trashCount >= 0) list.addView(row(c, R.drawable.ui_ic_trash, Lang.t("Недавно удаленные", "Recently deleted"),
                String.valueOf(trashCount), v -> host.openTrash()));
        list.addView(row(c, R.drawable.ui_ic_folders, Lang.t("Папки во вкладке «Фото»", "Folders in «Photos»"), "", v -> host.openPhotoFolders()));
        return list;
    }

    /** A row of a group (P579): the colour icon, the name, the count at 55 % white and a chevron at the end. */
    static LinearLayout row(Context c, @DrawableRes int icon, String name, String count, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(GalleryUi.dp(c, GalleryUi.ROW_MIN));
        r.setPadding(GalleryUi.dp(c, 16), 0, GalleryUi.dp(c, 14), 0);
        r.setBackground(GalleryUi.pressable(GalleryUi.round(c, 0, 0, 0)));
        r.addView(GalleryUi.icon(c, icon, GalleryUi.TEXT, 22));
        TextView t = GalleryUi.fitText(c, name, 16, GalleryUi.TEXT);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tp.setMarginStart(GalleryUi.dp(c, 14));
        r.addView(t, tp);
        if (!count.isEmpty()) {
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            np.setMarginStart(GalleryUi.dp(c, 10));
            r.addView(GalleryUi.text(c, count, 16, GalleryUi.SECTION), np);
        }
        ImageView chev = GalleryUi.icon(c, R.drawable.cs_chevron, GalleryUi.SECTION, 16);
        chev.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (chev.getDrawable() != null) chev.getDrawable().mutate().setAutoMirrored(true); // points the reading way in right to left
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(GalleryUi.dp(c, 16), GalleryUi.dp(c, 16));
        cp.setMarginStart(GalleryUi.dp(c, 6));
        r.addView(chev, cp);
        r.setClickable(true);
        r.setFocusable(true);
        r.setContentDescription(count.isEmpty() ? name : name + ", " + count);
        r.setOnClickListener(click);
        return r;
    }

    /** A frame as tall as it is wide. */
    static final class SquareBox extends FrameLayout {
        SquareBox(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec);
        }
    }
}
