package com.scamera.app.util;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;

import androidx.core.content.res.ResourcesCompat;

import java.util.Locale;

public class Utilities {
    /** A shutter time for the screen: 1/n below a second, else whole seconds ('') and the rest as 1/n (locale digits). */
    public static String formatExposureTime(final double seconds) {
        final Locale here = Locale.getDefault();
        if (seconds < 1.0f) return String.format(here, "%d/%d", 1, reciprocal(seconds));
        final int whole = (int) seconds;
        final double rest = seconds - whole;
        final String text = String.format(here, "%d''", whole);
        return rest > 0.0001f ? text + String.format(here, " %d/%d", 1, reciprocal(rest)) : text;
    }

    private static int reciprocal(double part) {
        return (int) (0.5f + 1 / part);
    }

    /** The drawable a theme attribute names (the attribute's array is recycled). */
    public static Drawable resolveDrawable(Context context, int attr) {
        final TypedArray values = context.getTheme().obtainStyledAttributes(new int[]{attr});
        final int id;
        try {
            id = values.getResourceId(0, 0);
        } finally {
            values.recycle();
        }
        return ResourcesCompat.getDrawable(context.getResources(), id, context.getTheme());
    }
}
