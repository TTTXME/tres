package com.particlesdevs.photoncamera.gallery.ui;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.DrawableRes;

import com.particlesdevs.photoncamera.ui.Motion;

import java.util.ArrayList;
import java.util.List;

/**
 * P502: the «⋮» menu of the gallery's pages (the reference video's «Фильтр / Увеличить / Уменьшить / Порядок просмотра / …») as the
 * shade's glass card: rows of an icon and a label, a disabled row dimmed, opening from the button's corner on the shade's curve.
 */
public final class GalleryMenu {
    private GalleryMenu() {}

    public static final class Item {
        final @DrawableRes int icon;
        final String label;
        final boolean enabled;
        final Runnable run;

        public Item(@DrawableRes int icon, String label, boolean enabled, Runnable run) {
            this.icon = icon;
            this.label = label;
            this.enabled = enabled;
            this.run = run;
        }
    }

    /** Shows {@code items} under {@code anchor}, right-aligned with it. */
    public static PopupWindow show(View anchor, List<Item> items) {
        Context c = anchor.getContext();
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(GalleryTabBar.glass(c, 18));
        card.setElevation(GalleryUi.dp(c, 12));
        int pad = GalleryUi.dp(c, 6);
        card.setPadding(pad, pad, pad, pad);
        final PopupWindow w = new PopupWindow(card, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        List<View> rows = new ArrayList<>();
        for (Item it : items) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(GalleryUi.dp(c, 46));
            row.setMinimumWidth(GalleryUi.dp(c, 216));
            row.setPadding(GalleryUi.dp(c, 12), 0, GalleryUi.dp(c, 16), 0);
            row.setBackground(GalleryUi.pressable(GalleryUi.round(c, 0, 0, 13)));
            row.addView(GalleryUi.icon(c, it.icon, GalleryUi.TEXT, 20));
            TextView t = GalleryUi.text(c, it.label, 15, GalleryUi.TEXT);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            tp.leftMargin = GalleryUi.dp(c, 12);
            row.addView(t, tp);
            row.setClickable(it.enabled);
            row.setFocusable(it.enabled);
            row.setContentDescription(it.label);
            GalleryUi.setEnabledLook(row, it.enabled);
            if (it.enabled) row.setOnClickListener(v -> {
                w.dismiss();
                it.run.run();
            });
            card.addView(row);
            rows.add(row);
        }
        w.setBackgroundDrawable(new ColorDrawable(0));
        w.setOutsideTouchable(true);
        w.setElevation(GalleryUi.dp(c, 12));
        card.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int xoff = anchor.getWidth() - card.getMeasuredWidth();
        w.showAsDropDown(anchor, xoff, GalleryUi.dp(c, 6));
        if (Motion.enabled(card)) {
            // from the button's corner on the shade's sheet curve (its profile menu), the rows after it
            card.setPivotX(card.getMeasuredWidth());
            card.setPivotY(0);
            card.setScaleX(.86f);
            card.setScaleY(.86f);
            card.setAlpha(0f);
            card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260).setInterpolator(Motion.SHEET).start();
            Motion.enter(rows, 40, 18);
        }
        return w;
    }
}
