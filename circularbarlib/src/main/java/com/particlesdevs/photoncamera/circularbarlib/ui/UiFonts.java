package com.particlesdevs.photoncamera.circularbarlib.ui;

import android.content.Context;
import android.graphics.Typeface;

import androidx.core.content.res.ResourcesCompat;

import com.particlesdevs.photoncamera.circularbarlib.R;

/**
 * P582 (owner, 2026-11-09: «Убедись что масштаб самого смартфона и то какой у него в системе шрифт не влияет на наше
 * приложение»): the concept's font, Roboto Flex (res/font of this module, so the manual controls here and the app both
 * reach it), at the concept's weights. The phone's own system font never draws our text: code that used
 * Typeface.create("sans-serif-…") or Typeface.DEFAULT(_BOLD) takes {@link #of} instead; the app's
 * com.particlesdevs.photoncamera.ui.style.Fonts is the same helper.
 */
public final class UiFonts {
    private UiFonts() {}

    public static final int REGULAR = 400, MEDIUM = 500, SEMIBOLD = 600, BOLD = 700, HEAVY = 800;
    private static final Typeface[] CACHE = new Typeface[5];

    /** Roboto Flex at {@code weight} (400 .. 800, rounded to the nearest bundled instance). */
    public static Typeface of(Context c, int weight) {
        final int i = Math.max(0, Math.min(4, Math.round((weight - 400) / 100f)));
        Typeface t = CACHE[i];
        if (t == null) {
            final int[] ids = {R.font.roboto_flex_400, R.font.roboto_flex_500, R.font.roboto_flex_600, R.font.roboto_flex_700,
                    R.font.roboto_flex_800};
            try {
                Context app = c.getApplicationContext();
                t = ResourcesCompat.getFont(app != null ? app : c, ids[i]);
            } catch (RuntimeException e) {
                t = null;
            }
            if (t == null) return Typeface.create(Typeface.SANS_SERIF, weight >= BOLD ? Typeface.BOLD : Typeface.NORMAL);
            CACHE[i] = t;
        }
        return t;
    }

    /**
     * The Roboto Flex weight that stands for a system family name ({@code "sans-serif-medium"} and the like) and a
     * Typeface style; -1 when the family is not a sans-serif one (monospace, serif, a font resource), which stays as it is.
     */
    public static int weightOf(String family, int style) {
        boolean bold = (style & Typeface.BOLD) != 0;
        if (family == null || family.isEmpty() || "sans-serif".equals(family)) return bold ? BOLD : REGULAR;
        switch (family) {
            case "sans-serif-thin":
            case "sans-serif-light":
                return bold ? BOLD : REGULAR;
            case "sans-serif-medium":
            case "sans-serif-condensed-medium":
                return bold ? BOLD : MEDIUM;
            case "sans-serif-condensed":
            case "sans-serif-condensed-light":
                return bold ? BOLD : REGULAR;
            case "sans-serif-black":
                return HEAVY;
            default:
                return -1;
        }
    }

    /** Roboto Flex for a system family name and style ({@link #weightOf}); other families as the system has them. */
    public static Typeface forFamily(Context c, String family, int style) {
        int w = weightOf(family, style);
        if (w < 0) {
            Typeface t = Typeface.create(family, style);
            return t != null ? t : Typeface.SANS_SERIF;
        }
        return of(c, w);
    }

    /** {@code v} in the concept font at {@code weight}. */
    public static <T extends android.widget.TextView> T apply(T v, int weight) {
        v.setTypeface(of(v.getContext(), weight));
        return v;
    }
}
