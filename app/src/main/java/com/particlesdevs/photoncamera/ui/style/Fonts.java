package com.particlesdevs.photoncamera.ui.style;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.TextView;

import com.particlesdevs.photoncamera.circularbarlib.ui.UiFonts;

/**
 * The concept's font (owner, 2026-11-09: «шрифты так же не совпадают с концептом»): Roboto Flex bundled in res/font (of the
 * circularbarlib module since P582, so its manual controls reach it too), so the phone's own system font never changes the
 * interface. {@link #of} gives the weight the concept uses (400 regular, 500 medium, 600 semibold, 700 bold, 800 heavy);
 * views made in code take it instead of Typeface.create("sans-serif-…"). The themes give it to every inflated TextView
 * (styles.xml), and {@link ConceptViewInflater} to layouts that still name a system family. Same helper as {@link UiFonts}.
 */
public final class Fonts {
    private Fonts() {}

    public static final int REGULAR = UiFonts.REGULAR, MEDIUM = UiFonts.MEDIUM, SEMIBOLD = UiFonts.SEMIBOLD, BOLD = UiFonts.BOLD,
            HEAVY = UiFonts.HEAVY;

    public static Typeface of(Context c, int weight) {
        return UiFonts.of(c, weight);
    }

    /** Roboto Flex for a system family name ("sans-serif-medium" …) and a Typeface style; monospace / serif stay. */
    public static Typeface forFamily(Context c, String family, int style) {
        return UiFonts.forFamily(c, family, style);
    }

    /** {@code v} in the concept font at {@code weight}. */
    public static <T extends TextView> T apply(T v, int weight) {
        v.setTypeface(of(v.getContext(), weight));
        return v;
    }
}
