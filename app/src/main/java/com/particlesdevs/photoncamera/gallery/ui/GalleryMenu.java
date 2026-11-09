package com.particlesdevs.photoncamera.gallery.ui;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.DrawableRes;

import com.particlesdevs.photoncamera.ui.Motion;

import java.util.List;

/**
 * P502: the «⋮» menu of the gallery's pages (the reference video's «Фильтр / Увеличить / Уменьшить / Порядок просмотра / …») as the
 * shade's glass card: rows of an icon and a label, a disabled row dimmed, opening from the button's corner on the shade's curve.
 * P579 (owner, 2026-11-09: «Не забудь что у нас есть галерея и в ней надо стиль также обновить»): the shared pull-down menu's look
 * (IosMenu) - a rounded #2C2C2E card with a light edge under the button, 16sp white labels with the line icon at the end, hairlines
 * between the rows, grown from the button's corner on a spring and shrunk back on close, the screen behind dimmed a little.
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

    private static final com.particlesdevs.photoncamera.ui.settings.SettingsMotion.Spring OPEN =
            new com.particlesdevs.photoncamera.ui.settings.SettingsMotion.Spring(0.42f, 0.72f);

    /** Shows {@code items} under {@code anchor} (above it when there is no room), its end aligned with the anchor's. */
    public static PopupWindow show(View anchor, List<Item> items) {
        Context c = anchor.getContext();
        // the card (its shape and edge), a scroller for a short window (landscape), the rows
        android.widget.FrameLayout card = new android.widget.FrameLayout(c);
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView scroll = new android.widget.ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.addView(list);
        card.addView(scroll);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF22C2C2E);
        bg.setCornerRadius(GalleryUi.dp(c, 26));
        bg.setStroke(Math.max(1, GalleryUi.dp(c, .5f)), 0x24FFFFFF);
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setElevation(GalleryUi.dp(c, 24));
        list.setPadding(0, GalleryUi.dp(c, 4), 0, GalleryUi.dp(c, 4));
        list.setDividerDrawable(GalleryUi.hairline(c, 18));
        list.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        final int width = GalleryUi.dp(c, 262);
        final PopupWindow w = new PopupWindow(card, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        final boolean[] closing = {false};
        for (Item it : items) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(GalleryUi.dp(c, 46));
            row.setPadding(GalleryUi.dp(c, 18), 0, GalleryUi.dp(c, 18), 0);
            row.setBackground(pressed());
            TextView t = GalleryUi.text(c, it.label, 16, GalleryUi.TEXT);
            t.setMaxLines(2);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            android.widget.ImageView icon = new android.widget.ImageView(c);
            icon.setImageResource(it.icon);
            com.particlesdevs.photoncamera.ui.Icons.ink(icon, it.icon, GalleryUi.TEXT); // P579: a white line icon at the row's end
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(GalleryUi.dp(c, 20), GalleryUi.dp(c, 20));
            ip.setMarginStart(GalleryUi.dp(c, 12));
            row.addView(icon, ip);
            row.setClickable(it.enabled);
            row.setFocusable(it.enabled);
            row.setContentDescription(it.label);
            GalleryUi.setEnabledLook(row, it.enabled);
            if (it.enabled) row.setOnClickListener(v -> {
                if (closing[0]) return;
                close(w, card, closing);
                it.run.run();
            });
            list.addView(row);
        }
        w.setBackgroundDrawable(new ColorDrawable(0));
        w.setOutsideTouchable(true);
        w.setElevation(GalleryUi.dp(c, 24));
        w.setAnimationStyle(0);
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        // where: under the anchor, its end at the anchor's end (the start in right to left); above it when there is no room
        Rect frame = new Rect();
        anchor.getWindowVisibleDisplayFrame(frame);
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int margin = GalleryUi.dp(c, 12), h = Math.min(card.getMeasuredHeight(), Math.max(GalleryUi.dp(c, 100), frame.height() - 2 * margin));
        w.setHeight(h);
        boolean rtl = anchor.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int x = rtl ? at[0] : at[0] + anchor.getWidth() - width;
        x = Math.max(frame.left + margin, Math.min(frame.right - margin - width, x));
        int y = at[1] + anchor.getHeight() + GalleryUi.dp(c, 6);
        boolean below = true;
        if (y + h > frame.bottom - margin) {
            y = Math.max(frame.top + margin, at[1] - h - GalleryUi.dp(c, 6));
            below = false;
        }
        card.setPivotX(rtl ? 0 : width);
        card.setPivotY(below ? 0 : h);
        w.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
        dim(w, .18f);
        if (Motion.enabled(card)) {
            card.setScaleX(.25f);
            card.setScaleY(.25f);
            card.setAlpha(0f);
            card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(OPEN.durationMs).setInterpolator(OPEN).start();
        }
        return w;
    }

    /** The close: the card shrinks back to the button's corner, then the window goes (P579, as the shared menu). */
    private static void close(PopupWindow w, View card, boolean[] closing) {
        closing[0] = true;
        if (!Motion.enabled(card)) {
            dismiss(w);
            return;
        }
        card.animate().cancel();
        card.animate().scaleX(.6f).scaleY(.6f).alpha(0f).setDuration(170).setInterpolator(com.particlesdevs.photoncamera.ui.settings.SettingsMotion.EXIT)
                .setListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a) { dismiss(w); }
                }).start();
    }

    private static void dismiss(PopupWindow w) {
        try {
            w.dismiss();
        } catch (RuntimeException ignored) { /* the window went with its activity */ }
    }

    /** The screen behind the menu dimmed by {@code amount} (the popup window's own flag). */
    private static void dim(PopupWindow w, float amount) {
        try {
            View root = w.getContentView().getRootView();
            android.view.WindowManager wm = (android.view.WindowManager) root.getContext().getSystemService(Context.WINDOW_SERVICE);
            ViewGroup.LayoutParams p = root.getLayoutParams();
            if (wm == null || !(p instanceof android.view.WindowManager.LayoutParams)) return;
            android.view.WindowManager.LayoutParams lp = (android.view.WindowManager.LayoutParams) p;
            lp.flags |= android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            lp.dimAmount = amount;
            wm.updateViewLayout(root, lp);
        } catch (RuntimeException ignored) { /* no dim: the menu still works */ }
    }

    private static android.graphics.drawable.Drawable pressed() {
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(0x1FFFFFFF));
        s.addState(new int[0], new ColorDrawable(0));
        return s;
    }
}
