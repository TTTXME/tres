package com.scamera.app.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import com.scamera.app.R;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * P570 (owner, 2026-11-09: «Стиль иконок сделай ближе к Material You», «Сделай иконки более минималистичными»): the app's colour
 * icons (tools/icons/build.mjs: one-colour lines, a filled "on" still; their names in res/values/kb_icons.xml) keep their own colours
 * where the old glyphs were tinted. Where a single colour is meant - an icon on an accent disc, a muted / off icon, an icon inline
 * with text - they are drawn tonal mono: the given colour, with an icon's light pieces (a filled icon's inner detail) more
 * transparent than its colour, so the detail stays; a plain tint would flatten a filled icon into a silhouette. Plain glyphs
 * (chevrons, back, grips) keep their tint.
 */
public final class Icons {
    private Icons() {}

    private static Set<String> names;

    /** {@code res} is one of the generated colour icons. */
    public static boolean colour(Context c, @DrawableRes int res) {
        if (res == 0) return false;
        if (names == null) names = new HashSet<>(Arrays.asList(c.getResources().getStringArray(R.array.kb_icons)));
        try {
            return names.contains(c.getResources().getResourceEntryName(res));
        } catch (Resources.NotFoundException e) {
            return false;
        }
    }

    /**
     * Tonal mono in {@code ink}: alpha = 1 + s (r - mean(rgb)), so the icon colours (mean .45-.68) stay opaque, their light tonal
     * pieces (about .8) at about 60 % and white details (1) at about a third; colour = ink.
     */
    public static ColorFilter mono(int ink) {
        final float s = 1.5f, r = 0.56f, a = ((ink >>> 24) & 255) / 255f, w = -s / 3f * a;
        // on unpremultiplied 0..255 channels; the last column is an offset in the same units
        return new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                0, 0, 0, 0, (ink >> 16) & 255,
                0, 0, 0, 0, (ink >> 8) & 255,
                0, 0, 0, 0, ink & 255,
                w, w, w, (1 + s * r) * a, 0}));
    }

    /** {@code v} (showing a colour icon) in its own colours. */
    public static void own(ImageView v) {
        v.setTag(R.id.kb_colour, true);
        v.setImageTintList(null);
        v.clearColorFilter();
    }

    /** {@code v} (showing a colour icon) tonal mono in {@code ink}. */
    public static void mono(ImageView v, int ink) {
        v.setTag(R.id.kb_colour, true);
        v.setImageTintList(null);
        v.setColorFilter(mono(ink));
    }

    /** The icon as drawn: its own colours for a colour icon, else the old tint. */
    public static void set(ImageView v, @DrawableRes int res, int tint) {
        v.setImageResource(res);
        tint(v, res, tint);
    }

    /** {@code v} was given a colour icon by {@link #set} / {@link #tint} / {@link #ink}. */
    public static boolean colour(ImageView v) {
        return Boolean.TRUE.equals(v.getTag(R.id.kb_colour));
    }

    /** {@code v} shows {@code res}: its own colours for a colour icon, else tinted {@code tint}. */
    public static void tint(ImageView v, @DrawableRes int res, int tint) {
        v.setTag(R.id.kb_colour, colour(v.getContext(), res));
        if (colour(v)) {
            v.setImageTintList(null);
            v.clearColorFilter();
        } else {
            v.clearColorFilter();
            v.setImageTintList(android.content.res.ColorStateList.valueOf(tint));
        }
    }

    /** {@code v} shows {@code res} in one colour: tonal mono for a colour icon, else tinted. */
    public static void ink(ImageView v, @DrawableRes int res, int ink) {
        v.setTag(R.id.kb_colour, colour(v.getContext(), res));
        if (colour(v)) {
            v.setImageTintList(null);
            v.setColorFilter(mono(ink));
        } else {
            v.clearColorFilter();
            v.setImageTintList(android.content.res.ColorStateList.valueOf(ink));
        }
    }

    /** An on / off icon: on in its own colours (and its pressed pose, the activated state), off tonal mono in {@code offInk}. */
    public static void state(ImageView v, @DrawableRes int res, boolean on, int onTint, int offInk) {
        v.setTag(R.id.kb_colour, colour(v.getContext(), res));
        state(v, on, onTint, offInk);
    }

    /** {@link #state(ImageView, int, boolean, int, int)} for the icon {@code v} was given by {@link #set} (or a tinted glyph). */
    public static void state(ImageView v, boolean on, int onTint, int offInk) {
        if (colour(v)) {
            v.setImageTintList(null);
            if (on) v.clearColorFilter(); else v.setColorFilter(mono(offInk));
        } else {
            v.clearColorFilter();
            v.setImageTintList(android.content.res.ColorStateList.valueOf(on ? onTint : offInk));
        }
        v.setActivated(on);
    }

    /** A drawable of {@code res} in one colour (inline with text): tonal mono for a colour icon, else tinted. */
    @Nullable
    public static Drawable inked(Context c, @DrawableRes int res, int ink) {
        final Drawable d = c.getDrawable(res);
        if (d == null) return null;
        final Drawable m = d.mutate();
        recolour(c, res, m, ink);
        return m;
    }

    /** A drawable of {@code res} (mutated) to {@code ink}, the same way as {@link #inked}. */
    public static void recolour(Context c, @DrawableRes int res, Drawable d, int ink) {
        if (colour(c, res)) {
            d.setTintList(null);
            d.setColorFilter(mono(ink));
        } else d.setTint(ink);
    }
}
