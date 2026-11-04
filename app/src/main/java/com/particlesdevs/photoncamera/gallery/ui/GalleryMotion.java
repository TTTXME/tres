package com.particlesdevs.photoncamera.gallery.ui;

import android.graphics.Rect;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.ui.Motion;

/**
 * P498 (owner, 2026-11-04: «добавь красивые анимации»): the gallery's motion on the camera's shared curves (ui/Motion: the iOS sheet
 * curve, its springs) - the viewer's bars sliding off and back, the selection bar rising from below, a selected thumbnail sinking
 * into its frame, a photo opening out of its thumbnail and folding back into it. Every one is an Animator / Animation, so the
 * system's animation scale applies, and without animations each lands at its end at once.
 */
public final class GalleryMotion {
    private GalleryMotion() {}

    /** The viewer's top and bottom bars: slid off past their edge and faded (220 ms), back on the sheet curve (300 ms). */
    public static void bars(@Nullable View top, @Nullable View bottom, boolean shown) {
        slide(top, shown, -1);
        slide(bottom, shown, 1);
    }

    private static void slide(@Nullable View v, boolean shown, int dir) {
        if (v == null) return;
        v.animate().cancel();
        if (!Motion.enabled(v)) {
            v.setVisibility(shown ? View.VISIBLE : View.GONE);
            v.setAlpha(1f);
            v.setTranslationY(0f);
            return;
        }
        final float off = dir * Math.max(v.getHeight(), GalleryUi.dp(v.getContext(), 60)) * .6f;
        if (shown) {
            if (v.getVisibility() != View.VISIBLE) {
                v.setAlpha(0f);
                v.setTranslationY(off);
                v.setVisibility(View.VISIBLE);
            }
            v.animate().alpha(1f).translationY(0f).setDuration(300).setInterpolator(Motion.SHEET).withEndAction(null).start();
        } else if (v.getVisibility() == View.VISIBLE) {
            v.animate().alpha(0f).translationY(off).setDuration(220).setInterpolator(Motion.EASE_OUT).withEndAction(() -> {
                v.setVisibility(View.GONE);
                v.setAlpha(1f);
                v.setTranslationY(0f);
            }).start();
        }
    }

    /** A floating bar (selection, undo): rises from below on the entry spring, sinks and fades out. */
    public static void rise(@Nullable View v, boolean shown) {
        if (v == null) return;
        v.animate().cancel();
        if (!Motion.enabled(v)) {
            v.setVisibility(shown ? View.VISIBLE : View.GONE);
            Motion.rest(v);
            return;
        }
        final float off = GalleryUi.dp(v.getContext(), 72);
        if (shown) {
            if (v.getVisibility() != View.VISIBLE) {
                v.setAlpha(0f);
                v.setTranslationY(off);
                v.setScaleX(.94f);
                v.setScaleY(.94f);
                v.setVisibility(View.VISIBLE);
            }
            v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(Motion.ENTER.durationMs).setInterpolator(Motion.ENTER)
                    .withEndAction(null).start();
        } else if (v.getVisibility() == View.VISIBLE) {
            v.animate().alpha(0f).translationY(off * .6f).scaleX(.96f).scaleY(.96f).setDuration(200).setInterpolator(Motion.EASE_OUT)
                    .withEndAction(() -> {
                        v.setVisibility(View.GONE);
                        Motion.rest(v);
                    }).start();
        }
    }

    /** A thumbnail selected (sinks to .88 inside its frame) or not, on the release spring. */
    public static void select(View image, boolean on, boolean animate) {
        final float s = on ? .88f : 1f;
        image.animate().cancel();
        if (!animate || !Motion.enabled(image)) {
            image.setScaleX(s);
            image.setScaleY(s);
            return;
        }
        image.animate().scaleX(s).scaleY(s).setDuration(Motion.RELEASE.durationMs).setInterpolator(Motion.RELEASE).start();
    }

    /** A switch's thumb sliding to its side (the gallery sheets' switches). */
    public static void thumb(View thumb, float x) {
        thumb.animate().cancel();
        if (!Motion.enabled(thumb) || !thumb.isLaidOut()) {
            thumb.setTranslationX(x);
            return;
        }
        thumb.animate().translationX(x).setDuration(Motion.RELEASE.durationMs).setInterpolator(Motion.RELEASE).start();
    }

    /**
     * The viewer opening out of the thumbnail at {@code cell} (window coordinates) over {@code root}: scaled from the cell's size and
     * moved from its centre, faded in quickly; or, {@code reverse}, folding back into it. Null without a usable cell.
     */
    @Nullable
    public static Animation zoom(@Nullable Rect cell, int rootW, int rootH, int rootX, int rootY, boolean reverse) {
        if (cell == null || cell.isEmpty() || rootW <= 0 || rootH <= 0) return null;
        float s = Math.max(cell.width() / (float) rootW, cell.height() / (float) rootH);
        s = Math.max(.08f, Math.min(1f, s));
        float dx = cell.exactCenterX() - (rootX + rootW / 2f), dy = cell.exactCenterY() - (rootY + rootH / 2f);
        AnimationSet set = new AnimationSet(true);
        set.setInterpolator(new android.view.animation.PathInterpolator(0.32f, 0.72f, 0f, 1f));
        ScaleAnimation scale = reverse ? new ScaleAnimation(1f, s, 1f, s, Animation.RELATIVE_TO_SELF, .5f, Animation.RELATIVE_TO_SELF, .5f)
                : new ScaleAnimation(s, 1f, s, 1f, Animation.RELATIVE_TO_SELF, .5f, Animation.RELATIVE_TO_SELF, .5f);
        TranslateAnimation move = reverse ? new TranslateAnimation(0, dx, 0, dy) : new TranslateAnimation(dx, 0, dy, 0);
        final long d = reverse ? 320 : 360;
        scale.setDuration(d);
        move.setDuration(d);
        set.addAnimation(scale);
        set.addAnimation(move);
        AlphaAnimation fade = reverse ? new AlphaAnimation(1f, 0f) : new AlphaAnimation(0f, 1f);
        fade.setDuration(reverse ? 240 : 160); // (the set's duration would be pushed down to every child: each has its own)
        if (reverse) fade.setStartOffset(80);
        set.addAnimation(fade);
        return set;
    }
}
