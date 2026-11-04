package com.particlesdevs.photoncamera.gallery.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.ui.Motion;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.function.IntConsumer;

/**
 * P502 (owner, 2026-11-05: the gallery «аналогичной тому что на этом видео, но в стиле нашей шторки»): the bottom tabs «Фото» /
 * «Альбомы» / «Поиск» as the shade's floating glass panel (its fill at the shade's transparency, a hairline light border, radius 24)
 * holding three Material You tabs: the chosen one sits on an accent tonal pill (icon and label in the accent), the others muted.
 * A tap on the chosen tab again scrolls its page to the top (the stock galleries' habit).
 */
public final class GalleryTabBar {
    public static final int PHOTOS = 0, ALBUMS = 1, SEARCH = 2;

    public final LinearLayout view;
    private final LinearLayout[] tabs = new LinearLayout[3];
    private final ImageView[] icons = new ImageView[3];
    private final TextView[] labels = new TextView[3];
    private final int accent;
    private int current = -1;

    public GalleryTabBar(Context c, IntConsumer onTab, IntConsumer onReselect) {
        accent = GalleryUi.accent(c);
        view = new LinearLayout(c);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setBackground(glass(c, 22));
        view.setElevation(GalleryUi.dp(c, 10));
        int pad = GalleryUi.dp(c, 4);
        view.setPadding(pad, pad, pad, pad);
        String[] names = {Lang.t("Фото", "Photos"), Lang.t("Альбомы", "Albums"), Lang.t("Поиск", "Search")};
        @DrawableRes int[] res = {R.drawable.ui_ic_photos, R.drawable.ui_ic_albums, R.drawable.ui_ic_search};
        for (int i = 0; i < 3; ++i) {
            final int index = i;
            LinearLayout tab = new LinearLayout(c);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setMinimumHeight(GalleryUi.dp(c, 48));
            tab.setPadding(GalleryUi.dp(c, 4), GalleryUi.dp(c, 4), GalleryUi.dp(c, 4), GalleryUi.dp(c, 4));
            ImageView icon = GalleryUi.icon(c, res[i], GalleryUi.MUTED, 22);
            tab.addView(icon, new LinearLayout.LayoutParams(GalleryUi.dp(c, 22), GalleryUi.dp(c, 22)));
            TextView label = GalleryUi.text(c, names[i], 11, GalleryUi.MUTED);
            label.setSingleLine(true);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = GalleryUi.dp(c, 2);
            tab.addView(label, lp);
            tab.setClickable(true);
            tab.setFocusable(true);
            tab.setContentDescription(names[i]);
            tab.setOnClickListener(v -> {
                if (index == current) onReselect.accept(index);
                else {
                    select(index);
                    onTab.accept(index);
                }
            });
            Motion.press(tab);
            tabs[i] = tab;
            icons[i] = icon;
            labels[i] = label;
            // (owner, 2026-11-05: «гораздо более компактными» in both orientations - a fixed narrow tab, not a third of the screen)
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(GalleryUi.dp(c, 72), ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) tp.leftMargin = GalleryUi.dp(c, 2);
            view.addView(tab, tp);
        }
        select(PHOTOS);
    }

    /** Marks tab {@code index} as the chosen one (no callback). */
    public void select(int index) {
        if (index == current) return;
        current = index;
        Context c = view.getContext();
        for (int i = 0; i < 3; ++i) {
            boolean on = i == index;
            final int pill = ShadeStyle.tint(accent, .18f);
            tabs[i].setBackground(on ? GalleryUi.round(c, pill, pill, 18) : GalleryUi.pressable(GalleryUi.round(c, 0, 0, 18)));
            icons[i].setImageTintList(android.content.res.ColorStateList.valueOf(on ? accent : GalleryUi.MUTED));
            labels[i].setTextColor(on ? accent : GalleryUi.MUTED);
            labels[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            tabs[i].setSelected(on);
            if (on && Motion.enabled(icons[i])) Motion.pop(icons[i]);
        }
    }

    public int current() {
        return current;
    }

    /** The shade's glass: its sheet fill at the shade's transparency, a hairline light border. */
    public static GradientDrawable glass(Context c, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        // (denser than the shade's panel: the grid scrolls under it, a camera's viewfinder does not)
        int alpha = Math.round(255 * Math.max(.88f, Math.min(1f, ShadeStyle.glassAlpha(c) + .45f)));
        g.setColor((alpha << 24) | (ShadeStyle.SHEET_FILL & 0xFFFFFF));
        g.setStroke(Math.max(1, GalleryUi.dp(c, .5f)), 0x2EFFFFFF);
        g.setCornerRadius(GalleryUi.dp(c, radiusDp));
        return g;
    }

    /** The tabs' (and the selection's, the trash's) compact capsule: centred at the bottom, 14dp from the edge, as wide as its three tabs. */
    public static FrameLayout.LayoutParams tabParams(Context c) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = GalleryUi.dp(c, 14);
        return lp;
    }

    /** Hides the bar while a selection's bar takes its place. */
    public void shown(boolean on) {
        GalleryMotion.rise(view, on);
    }

    static View spacer(Context c, int dp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, GalleryUi.dp(c, dp)));
        return v;
    }
}
