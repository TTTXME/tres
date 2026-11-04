package com.particlesdevs.photoncamera.gallery.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * P502: the header of a gallery tab, as the reference video's «Фото» / «Альбомы» / «Поиск»: an optional back disc, the big title
 * (28sp bold) and the page's discs at the right (search, select, «⋮»). The title and the back disc change in place (an album opened
 * from «Альбомы» shows its name and a back arrow on the «Фото» page).
 */
public final class GalleryHeader {
    public final LinearLayout view;
    public final TextView title;
    @Nullable private final View back;

    public GalleryHeader(Context c, String text, @Nullable View back, View... rights) {
        this.back = back;
        view = new LinearLayout(c);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(GalleryUi.dp(c, 16), GalleryUi.dp(c, 14), GalleryUi.dp(c, 12), GalleryUi.dp(c, 8));
        if (back != null) {
            GalleryUi.disc(back);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(GalleryUi.dp(c, 44), GalleryUi.dp(c, 44));
            bp.rightMargin = GalleryUi.dp(c, 10);
            view.addView(back, bp);
        }
        title = GalleryUi.fitText(c, text, 28, GalleryUi.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setAccessibilityHeading(true);
        view.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        for (int i = 0; i < rights.length; i++) {
            GalleryUi.disc(rights[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(GalleryUi.dp(c, 44), GalleryUi.dp(c, 44));
            lp.leftMargin = GalleryUi.dp(c, 8);
            view.addView(rights[i], lp);
        }
    }

    /** The title and whether the back disc shows. */
    public void set(String text, boolean withBack) {
        title.setText(text);
        if (back != null) back.setVisibility(withBack ? View.VISIBLE : View.GONE);
    }
}
