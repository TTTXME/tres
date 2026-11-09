package com.particlesdevs.photoncamera.ui.settings;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

import java.util.WeakHashMap;

/**
 * P574 (owner, 2026-11-09: the settings in the camera sheets' look, «Отлично, реализуем»; the coloured icon tiles stay): a row's
 * icon tile in Material You tones - the tile in the dark tone of the icon's hue, the icon drawn in its light tone. The hue is the
 * one the colour icon is drawn in (tools/icons/build.mjs: one system colour per icon), read once from the drawable itself and
 * matched to the nearest of the palette; an icon without a colour (grey, or nothing drawn) takes the grey pair.
 */
public final class SettingsTiles {
    private SettingsTiles() {}

    /** The icon colours of tools/icons/build.mjs and their tonal pairs {tile, icon}: blue, indigo, purple, pink, red, ... */
    private static final int[] HUES = {0xFF0A84FF, 0xFF5E5CE6, 0xFFBF5AF2, 0xFFFF375F, 0xFFFF453A, 0xFFFF9F0A, 0xFFFFC800,
            0xFF30D158, 0xFF00C7BE, 0xFF40C8E0, 0xFF64D2FF, 0xFFAC8E68};
    private static final int[][] PAIRS = {
            {0xFF004786, 0xFFA4C9FF}, {0xFF3237A9, 0xFFBFC2FF}, {0xFF6E238D, 0xFFE9B3FF}, {0xFF87083A, 0xFFFFB1C0},
            {0xFF8C1D18, 0xFFFFB4AB}, {0xFF6A3B00, 0xFFFFB77C}, {0xFF574500, 0xFFEBC248}, {0xFF105225, 0xFF9AD49F},
            {0xFF00504B, 0xFF7DD8CF}, {0xFF004E5B, 0xFF86D2E3}, {0xFF004C6D, 0xFF89CEFF}, {0xFF5A4329, 0xFFE2C2A0}};
    /** A grey (or colourless) icon. */
    public static final int[] GREY = {0xFF45464B, 0xFFC6C6CB};
    /** The warning rows (the resets): the red pair. */
    public static final int[] RED = PAIRS[4];

    private static final WeakHashMap<Drawable.ConstantState, int[]> CACHE = new WeakHashMap<>();

    /** {tile, icon} for {@code icon} (null: the grey pair). */
    public static int[] pair(@Nullable Drawable icon) {
        if (icon == null) return GREY;
        final Drawable.ConstantState state = icon.getConstantState();
        if (state != null) {
            final int[] known = CACHE.get(state);
            if (known != null) return known;
        }
        int[] found = GREY;
        try {
            // a copy without the filter a bound row put on it (its own colours, not the tone drawn last time)
            final Drawable copy = (state != null ? state.newDrawable() : icon).mutate();
            copy.setColorFilter(null);
            found = forColour(colourOf(copy));
        } catch (RuntimeException ignored) {
            // a drawable that cannot be drawn off screen: grey
        }
        if (state != null) CACHE.put(state, found);
        return found;
    }

    /**
     * The pair of the palette colour nearest to {@code colour} (0: none drawn, or a grey: the grey pair). P584: the concept's own
     * row icons (res/drawable/cs_ic_*, tools/icons/concept_icons.py --tones) are drawn in their hue's light tone: that tone gives
     * its pair as it is (the concept's ICONS / HUES of research/concept/Hybrid.dc.html).
     */
    static int[] forColour(int colour) {
        if (Color.alpha(colour) == 0) return GREY;
        if (near(colour, GREY[1])) return GREY;
        for (int[] pair : PAIRS) if (near(colour, pair[1])) return pair;
        final float[] hsv = new float[3];
        Color.colorToHSV(colour, hsv);
        if (hsv[1] < .2f) return GREY;
        int best = -1;
        long bestD = Long.MAX_VALUE;
        for (int i = 0; i < HUES.length; i++) {
            final long dr = Color.red(colour) - Color.red(HUES[i]), dg = Color.green(colour) - Color.green(HUES[i]),
                    db = Color.blue(colour) - Color.blue(HUES[i]);
            final long d = 2 * dr * dr + 4 * dg * dg + 3 * db * db;
            if (d < bestD) { bestD = d; best = i; }
        }
        return best < 0 ? GREY : PAIRS[best];
    }

    /** {@code a} and {@code b} the same colour but for the rounding of an edge pixel. */
    private static boolean near(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b)) <= 6 && Math.abs(Color.green(a) - Color.green(b)) <= 6
                && Math.abs(Color.blue(a) - Color.blue(b)) <= 6;
    }

    private static final WeakHashMap<Drawable.ConstantState, Boolean> FLAT = new WeakHashMap<>();

    /**
     * P584: whether {@code icon} is drawn in one colour (the concept's line glyphs, cs_*): those take the tone as it is (SRC_IN);
     * the older colour icons take the tonal mono of Icons.mono (their light pieces fainter).
     */
    public static boolean flat(@Nullable Drawable icon) {
        if (icon == null) return false;
        final Drawable.ConstantState state = icon.getConstantState();
        if (state != null) {
            final Boolean known = FLAT.get(state);
            if (known != null) return known;
        }
        boolean flat = false;
        try {
            final Drawable copy = (state != null ? state.newDrawable() : icon).mutate();
            copy.setColorFilter(null);
            final int size = 24;
            final Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            copy.setBounds(0, 0, size, size);
            copy.draw(new Canvas(b));
            final int[] px = new int[size * size];
            b.getPixels(px, 0, size, 0, 0, size, size);
            b.recycle();
            int first = 0;
            flat = true;
            for (int p : px) {
                if (Color.alpha(p) < 160) continue;
                if (first == 0) first = p;
                else if (!near(p, first)) { flat = false; break; }
            }
            if (first == 0) flat = false;
        } catch (RuntimeException ignored) {
            // not drawable off screen: the tonal mono
        }
        if (state != null) FLAT.put(state, flat);
        return flat;
    }

    /** {@code v}'s icon in {@code ink}: a one-colour glyph as it is, a colour icon in tonal mono. */
    public static void ink(android.widget.ImageView v, int ink) {
        if (flat(v.getDrawable())) v.setColorFilter(new android.graphics.PorterDuffColorFilter(ink, android.graphics.PorterDuff.Mode.SRC_IN));
        else v.setColorFilter(com.particlesdevs.photoncamera.ui.Icons.mono(ink));
    }

    /** The most saturated opaque colour the drawable paints (0 when it paints nothing). */
    private static int colourOf(Drawable d) {
        final int size = 24;
        final Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, size, size);
        d.draw(new Canvas(b));
        final int[] px = new int[size * size];
        b.getPixels(px, 0, size, 0, 0, size, size);
        b.recycle();
        final float[] hsv = new float[3];
        int best = 0;
        float bestS = -1f;
        for (int p : px) {
            if (Color.alpha(p) < 160) continue;
            Color.colorToHSV(p, hsv);
            final float score = hsv[1] * hsv[2];
            if (score > bestS) { bestS = score; best = p | 0xFF000000; }
        }
        return best;
    }
}
