package com.scamera.app.ui.camera;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;

import com.scamera.app.circularbarlib.ui.UiScale;
import com.scamera.app.ui.style.Fonts;

/**
 * P583 (owner, 2026-11-09: «Убедись что всё выглядит один в один как на концептах»): the camera screen's concept
 * (research/concept/Viewfinder.dc.html) in numbers - its greys, its text in Roboto Flex at the concept's px as dp (times the camera
 * chrome's {@link UiScale}, never the system font size), its line icons (res/drawable/cv_*, tools/icons/concept_icons.py) tinted
 * plain, and the concept's yellow replaced by the camera accent: where the concept fills with #4A3D00 (the yellow's dark tone),
 * {@link #dark}; where it lays rgba(yellow, .16) under an item, {@link #tint16}.
 */
public final class ConceptStyle {
    private ConceptStyle() {}

    /** The concept's greys: the controls (#1C1C1E), the rows and discs (#2C2C2E), the chosen disc (#3A3A3C), a pressed grey (#48484A). */
    public static final int CONTROL = 0xFF1C1C1E, ROW = 0xFF2C2C2E, CHOSEN = 0xFF3A3A3C, GREY = 0xFF48484A, THUMB = 0xFF636366,
            BADGE = 0xFF8E8E93;
    /** The controls' hairline: inset 0 0 0 .5px rgba(255, 255, 255, .1). */
    public static final int HAIRLINE = 0x1AFFFFFF;
    /** The segmented track: rgba(118, 118, 128, .24). */
    public static final int SEG_TRACK = 0x3D767680;
    /** The grabbers: rgba(255, 255, 255, .35) on the screen, .3 on a sheet. */
    public static final int GRIP = 0x59FFFFFF, SHEET_GRIP = 0x4DFFFFFF;

    /** The accent's dark tone (the concept's #4A3D00 for #FFD60A: the yellow at 29 %). */
    public static int dark(int accent) {
        final float k = 0.29f;
        return 0xFF000000 | Math.round(((accent >> 16) & 255) * k) << 16 | Math.round(((accent >> 8) & 255) * k) << 8
                | Math.round((accent & 255) * k);
    }

    /** {@code px} of the concept in pixels (dp times the camera chrome's scale). */
    public static float px(Context c, float px) {
        return UiScale.dpF(c, px);
    }

    /** The concept's text: {@code px} (CSS px = dp, times the chrome's scale; the font size of the system does not count), Roboto Flex at {@code weight}. */
    public static <T extends TextView> T text(T v, float px, int weight) {
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiScale.dpF(v.getContext(), px));
        Fonts.apply(v, weight);
        return v;
    }

    /** A concept line icon ({@code cv_*}, white strokes) in {@code ink} - a plain tint, the strokes stay opaque. */
    public static void icon(ImageView v, @DrawableRes int res, int ink) {
        v.setImageResource(res);
        v.clearColorFilter();
        v.setImageTintList(ColorStateList.valueOf(ink));
        v.setTag(com.scamera.app.R.id.kb_colour, false);
    }

    /** A concept line icon's drawable in {@code ink}, sized {@code sizePx}. */
    public static Drawable icon(Context c, @DrawableRes int res, int ink, int sizePx) {
        Drawable d = c.getDrawable(res).mutate();
        d.setTint(ink);
        d.setBounds(0, 0, sizePx, sizePx);
        return d;
    }

    /** A rounded fill of {@code radius} concept px, with the controls' .5px hairline when {@code line}. */
    public static GradientDrawable shape(Context c, int fill, float radius, boolean line) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(UiScale.dpF(c, radius));
        if (line) g.setStroke(Math.max(1, Math.round(c.getResources().getDisplayMetrics().density * .5f)), HAIRLINE);
        return g;
    }

    /** {@link #shape} that ripples white in its own outline. */
    public static Drawable pressable(Context c, int fill, float radius, boolean line) {
        GradientDrawable mask = shape(c, 0xFFFFFFFF, radius, false);
        return new RippleDrawable(ColorStateList.valueOf(0x29FFFFFF), shape(c, fill, radius, line), mask);
    }
}
