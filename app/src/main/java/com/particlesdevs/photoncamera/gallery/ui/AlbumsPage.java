package com.particlesdevs.photoncamera.gallery.ui;

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
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;
import com.particlesdevs.photoncamera.ui.Motion;
import com.particlesdevs.photoncamera.util.Lang;

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

    private void render() {
        Context c = view.getContext();
        column.removeAllViews();
        if (!frequent.isEmpty()) section(c, "frequent", Lang.t("Часто используемые", "Frequently used"), frequent.size() > 6, open -> {
            LinearLayout grid = tiles(c, frequent, open ? frequent.size() : Math.min(6, frequent.size()), true);
            return grid;
        });
        if (!others.isEmpty()) section(c, "others", Lang.t("Другие альбомы", "Other albums"), others.size() > 9, open ->
                tiles(c, others, open ? others.size() : Math.min(9, others.size()), false));
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
        TextView t = GalleryUi.text(c, title, 17, GalleryUi.TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setAccessibilityHeading(true);
        head.addView(t);
        ImageView chev = GalleryUi.icon(c, R.drawable.ui_ic_chev, GalleryUi.MUTED, 18);
        chev.setRotation(collapsed[0] ? 90 : -90);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(GalleryUi.dp(c, 18), GalleryUi.dp(c, 18));
        cp.leftMargin = GalleryUi.dp(c, 6);
        head.addView(chev, cp);
        View fill = new View(c);
        head.addView(fill, new LinearLayout.LayoutParams(0, 1, 1));
        TextView more = GalleryUi.text(c, "", 14, GalleryUi.MUTED);
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
        float radius = GalleryUi.dp(c, 18);
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        cover.setClipToOutline(true);
        cover.setBackground(GalleryUi.card(c, GalleryUi.CARD, 18));
        ImageView image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Uri uri = a.cover();
        if (uri != null) Glide.with(image).load(uri).apply(new RequestOptions().diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .override(360, 360).centerCrop()).into(image);
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
            name.setTypeface(Typeface.DEFAULT_BOLD);
            text.addView(n);
            text.addView(name);
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM);
            tp.leftMargin = tp.rightMargin = tp.bottomMargin = GalleryUi.dp(c, 9);
            cover.addView(text, tp);
            box.addView(cover);
        } else {
            box.addView(cover);
            TextView name = GalleryUi.fitText(c, a.title, 13, GalleryUi.TEXT);
            name.setPadding(GalleryUi.dp(c, 2), GalleryUi.dp(c, 6), GalleryUi.dp(c, 2), 0);
            box.addView(name);
            TextView n = GalleryUi.text(c, count, 12, GalleryUi.MUTED);
            n.setPadding(GalleryUi.dp(c, 2), 0, GalleryUi.dp(c, 2), 0);
            box.addView(n);
        }
        box.setClickable(true);
        box.setFocusable(true);
        box.setContentDescription(a.title + ", " + GalleryUi.shots(a.count()));
        box.setOnClickListener(v -> host.openAlbum(a.folder, a.title));
        Motion.press(box);
        return box;
    }

    /** «Тип»: two rows a line, an icon, the name and the count, the shade's tonal tiles. */
    private View typeRows(Context c) {
        List<View> rows = new ArrayList<>();
        for (Album a : types) rows.add(row(c, a.icon, a.title, String.valueOf(a.count()), v -> host.openAlbum(a.folder, a.title)));
        return pairs(c, rows);
    }

    private View otherRows(Context c) {
        List<View> rows = new ArrayList<>();
        if (trashCount >= 0) rows.add(row(c, R.drawable.ui_ic_trash, Lang.t("Недавно удаленные", "Recently deleted"),
                String.valueOf(trashCount), v -> host.openTrash()));
        rows.add(row(c, R.drawable.ui_ic_folders, Lang.t("Папки во вкладке «Фото»", "Folders in «Photos»"), "", v -> host.openPhotoFolders()));
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        for (View r : rows) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = GalleryUi.dp(c, 8);
            list.addView(r, lp);
        }
        return list;
    }

    private static LinearLayout pairs(Context c, List<View> rows) {
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < rows.size(); i += 2) {
            LinearLayout line = new LinearLayout(c);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.addView(rows.get(i), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            second.leftMargin = GalleryUi.dp(c, 8);
            line.addView(i + 1 < rows.size() ? rows.get(i + 1) : new View(c), second);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = GalleryUi.dp(c, 8);
            list.addView(line, lp);
        }
        return list;
    }

    /** A tonal row (the shade's Material You tile): icon, name, count at the right. */
    static LinearLayout row(Context c, @DrawableRes int icon, String name, String count, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(GalleryUi.dp(c, 52));
        r.setPadding(GalleryUi.dp(c, 14), 0, GalleryUi.dp(c, 14), 0);
        r.setBackground(GalleryUi.pressable(GalleryUi.round(c, 0x17FFFFFF, 0x17FFFFFF, 18)));
        r.addView(GalleryUi.icon(c, icon, GalleryUi.TEXT, 20));
        TextView t = GalleryUi.fitText(c, name, 14, GalleryUi.TEXT);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tp.leftMargin = GalleryUi.dp(c, 12);
        r.addView(t, tp);
        if (!count.isEmpty()) {
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            np.leftMargin = GalleryUi.dp(c, 10);
            r.addView(GalleryUi.text(c, count, 13, GalleryUi.MUTED), np);
        }
        r.setClickable(true);
        r.setFocusable(true);
        r.setContentDescription(count.isEmpty() ? name : name + ", " + count);
        r.setOnClickListener(click);
        Motion.press(r);
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
