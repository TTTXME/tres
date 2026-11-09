package com.particlesdevs.photoncamera.gallery.ui;

import android.content.Context;
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
import com.particlesdevs.photoncamera.util.Lang;

import java.util.function.IntConsumer;

/**
 * P502 (owner, 2026-11-05: the gallery «аналогичной тому что на этом видео, но в стиле нашей шторки»): the bottom tabs «Фото» /
 * «Альбомы» / «Поиск» as the shade's floating glass panel (its fill at the shade's transparency, a hairline light border, radius 24)
 * holding three Material You tabs: the chosen one sits on an accent tonal pill (icon and label in the accent), the others muted.
 * A tap on the chosen tab again scrolls its page to the top (the stock galleries' habit).
 * P579 (owner, 2026-11-09: «Не забудь что у нас есть галерея и в ней надо стиль также обновить»): the camera's bar look - a dark,
 * nearly solid capsule (rgba(20, 20, 22, .9), a 0.5dp light edge; less glass), the chosen tab's icon and label in the accent on a
 * grey thumb that glides to it, the others white at 60 %.
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
        thumb = new Thumb();
        view.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{GalleryUi.bar(c, 26), thumb}));
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
            tab.setBackground(GalleryUi.pressable(GalleryUi.round(c, 0, 0, 22)));
            tab.setPadding(GalleryUi.dp(c, 4), GalleryUi.dp(c, 4), GalleryUi.dp(c, 4), GalleryUi.dp(c, 4));
            ImageView icon = GalleryUi.icon(c, res[i], GalleryUi.MUTED, 22);
            tab.addView(icon, new LinearLayout.LayoutParams(GalleryUi.dp(c, 22), GalleryUi.dp(c, 22)));
            TextView label = GalleryUi.text(c, names[i], 11, GalleryUi.MUTED);
            label.setTypeface(GalleryUi.semibold());
            label.setMaxLines(1);
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

    /** Marks tab {@code index} as the chosen one (no callback); P579: the grey thumb glides there from the old tab. */
    public void select(int index) {
        if (index == current) return;
        final int from = current;
        current = index;
        for (int i = 0; i < 3; ++i) {
            boolean on = i == index;
            com.particlesdevs.photoncamera.ui.Icons.state(icons[i], on, accent, GalleryUi.MUTED); // P570: on in its colours, off tonal mono
            if (on && com.particlesdevs.photoncamera.ui.Icons.colour(icons[i])) icons[i].setColorFilter(com.particlesdevs.photoncamera.ui.Icons.mono(accent));
            labels[i].setTextColor(on ? accent : GalleryUi.MUTED);
            tabs[i].setSelected(on);
            if (on && Motion.enabled(icons[i])) Motion.pop(icons[i]);
        }
        thumb.moveTo(from, index);
    }

    /** P579: the chosen tab's grey capsule (the shared segmented control's thumb colour), sliding between the tabs on a spring. */
    private final Thumb thumb;

    private final class Thumb extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF box = new android.graphics.RectF();
        private float at = -1f;
        @androidx.annotation.Nullable private android.animation.ValueAnimator anim;

        Thumb() {
            paint.setColor(0x52767680);
        }

        void moveTo(int from, int to) {
            if (anim != null) anim.cancel();
            if (from < 0 || !com.particlesdevs.photoncamera.ui.settings.SettingsMotion.enabled(view) || !view.isLaidOut()) {
                at = to;
                invalidateSelf();
                return;
            }
            anim = android.animation.ValueAnimator.ofFloat(at < 0 ? from : at, to);
            anim.setDuration(GLIDE.durationMs);
            anim.setInterpolator(GLIDE);
            anim.addUpdateListener(a -> { at = (float) a.getAnimatedValue(); invalidateSelf(); });
            anim.start();
        }

        @Override public void draw(@androidx.annotation.NonNull android.graphics.Canvas canvas) {
            if (at < 0) return;
            int lo = (int) Math.floor(at), hi = Math.min(2, lo + 1);
            float f = at - lo;
            View a = tabs[Math.max(0, Math.min(2, lo))], b = tabs[hi];
            if (a.getWidth() == 0) return;
            float left = a.getLeft() + (b.getLeft() - a.getLeft()) * f, right = a.getRight() + (b.getRight() - a.getRight()) * f;
            box.set(left, a.getTop(), right, a.getBottom());
            float r = box.height() / 2f;
            canvas.drawRoundRect(box, r, r, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }

        @Override public void setColorFilter(@androidx.annotation.Nullable android.graphics.ColorFilter cf) { paint.setColorFilter(cf); }

        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private static final com.particlesdevs.photoncamera.ui.settings.SettingsMotion.Spring GLIDE =
            new com.particlesdevs.photoncamera.ui.settings.SettingsMotion.Spring(0.4f, 0.72f);

    public int current() {
        return current;
    }

    /** The floating bars' backing; P579: the dark, nearly solid bar with its light edge (GalleryUi.bar; it was the shade's glass). */
    public static GradientDrawable glass(Context c, float radiusDp) {
        return GalleryUi.bar(c, radiusDp);
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
