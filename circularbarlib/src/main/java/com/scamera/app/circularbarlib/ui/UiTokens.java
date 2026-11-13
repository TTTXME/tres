package com.scamera.app.circularbarlib.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;

/**
 * The card style's colour tokens and helpers, shared by the settings screens, the quick-settings shade and the manual
 * controls. The single source of truth: the app's SettingsStyle and ShadeStyle refer to these values; the app's
 * res/values/settings_style.xml mirrors them for XML layouts. The accent is never a token: it comes from
 * {@link AccentPalette} (the user's theme).
 */
public final class UiTokens {
    private UiTokens() {}

    public static final int BG = 0xFF101416, CARD = 0xFF1B2023, TEXT = 0xFFF4F3F7, MUTED = 0xFFB2BAC9, LINE = 0xFF30363C;
    public static final int FIELD = 0xFF121417, SHEET = 0xFF202428, INK = 0xFF17141F;
    /**
     * P139: a row's description, a step dimmer than {@link #MUTED}; with the title in {@link #title medium weight} the two
     * never read as one paragraph (owner: «заголовки отличались от описания чтобы они не сливались вместе»).
     */
    public static final int SUMMARY = 0xFF959EAD;

    /** P139: a setting's title: medium weight (P582: Roboto Flex 500, UiFonts), the colour stays the caller's. */
    public static void title(android.widget.TextView view) {
        view.setTypeface(UiFonts.of(view.getContext(), UiFonts.MEDIUM));
    }

    /** P139: a setting's description under its title. */
    public static void summary(android.widget.TextView view) {
        view.setTextColor(SUMMARY);
    }
    /** Alpha of an unavailable row, tile or chip. */
    public static final float DIMMED = .45f;

    // The manual controls' extra tokens (concept: scale inset, muted pill, ink on accent fills).
    /** Text and icons on an accent fill (the «Авто» button while the parameter is in auto). */
    public static final int ACCENT_INK = INK;
    /** The darker inset behind the ruler's scale. */
    public static final int SCALE_INSET = 0xFF121519;
    /** The value pill of a parameter in auto (muted value on this fill). */
    public static final int MUTED_PILL = 0xFF24282D;
    /** Alpha of the accent over {@link #CARD} for a chip / pill fill in manual ({@link #chipBg}). */
    public static final float CHIP_BG_ALPHA = .16f;

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /**
     * The most the viewfinder controls' text grows with the phone's font size (P43): text inside a fixed or tightly packed
     * box (the manual chips, the ruler, the lens strip, the zoom ruler) follows the font scale up to this factor, so a
     * large system font enlarges it a little but never doubles the strip or pushes text out of its box. Smaller font
     * scales apply in full.
     */
    public static final float FONT_SCALE_CAP = 1.15f;

    /** {@code sp} in pixels as the system scales it (font scale, non-linear on Android 14+), at most {@link #FONT_SCALE_CAP}. */
    public static float spPx(android.content.res.Resources r, float sp) {
        android.util.DisplayMetrics m = r.getDisplayMetrics();
        float system = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sp, m);
        return Math.min(system, sp * m.density * FONT_SCALE_CAP);
    }

    public static float spPx(Context c, float sp) {
        return spPx(c.getResources(), sp);
    }

    /** Sets a text size of {@code sp} with the growth capped as in {@link #spPx}. */
    public static void setTextSp(android.widget.TextView view, float sp) {
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, spPx(view.getResources(), sp));
    }

    /** The camera accent: the user's accent, or the camera yellow with the default theme. */
    public static int cameraAccent(Context c) {
        return AccentPalette.camera(c);
    }

    /** The accent at a given alpha over the card colour (chips, pills). */
    public static int tint(int accent, float alpha) {
        int a = Math.round(alpha * 255);
        int r = (((accent >> 16) & 255) * a + ((CARD >> 16) & 255) * (255 - a)) / 255;
        int g = (((accent >> 8) & 255) * a + ((CARD >> 8) & 255) * (255 - a)) / 255;
        int b = ((accent & 255) * a + (CARD & 255) * (255 - a)) / 255;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** The fill of a chip or pill whose parameter is manual: the accent at {@link #CHIP_BG_ALPHA} over the card. */
    public static int chipBg(int accent) {
        return tint(accent, CHIP_BG_ALPHA);
    }

    /** A rounded card: fill, optional 1dp stroke. */
    public static GradientDrawable shape(Context c, int color, int stroke, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radius));
        if (stroke != 0) d.setStroke(dp(c, 1), stroke);
        return d;
    }

    /** P509: the Material You tonal fill of a tile at rest (white at 9 % over the glass, the shade's ShadeStyle.TONAL). */
    public static final int TONAL = 0x17FFFFFF;

    /**
     * P509 (owner, 2026-11-05: the Pro panel «под стиль нашей новой шторки»): the shade's glass plate (its ShadeStyle.sheet) - the
     * sheet colour at the glass alpha («Прозрачность шторки» + 10 points, as the shade), a hairline light edge, {@code radiusDp}
     * corners.
     */
    public static GradientDrawable glass(Context c, float radiusDp) {
        int t = 40;
        try {
            Object v = android.preference.PreferenceManager.getDefaultSharedPreferences(c.getApplicationContext()).getAll().get("pref_shade_transparency");
            if (v instanceof Number) t = ((Number) v).intValue();
            else if (v != null) t = Math.round(Float.parseFloat(v.toString()));
        } catch (RuntimeException ignored) {
        }
        t = Math.max(0, Math.min(80, t));
        final float a = Math.max(.1f, 1f - t / 100f - .10f);
        GradientDrawable d = new GradientDrawable();
        d.setColor((Math.round(255 * a) << 24) | 0x15191C);
        d.setCornerRadius(dp(c, radiusDp));
        d.setStroke(Math.max(1, Math.round(c.getResources().getDisplayMetrics().density * .5f)), 0x29FFFFFF);
        return d;
    }

    /** {@code color} with its alpha multiplied by {@code alpha}. */
    public static int alpha(int color, float alpha) {
        int a = Math.round(((color >>> 24) & 255) * Math.max(0f, Math.min(1f, alpha)));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /** P583: the Pro panel's cards (the concept's rgba(20, 20, 22, .9)). */
    public static final int PRO_CARD = 0xE6141416;

    /** P583: Roboto Flex at {@code weight} ({@link UiFonts#of}). */
    public static android.graphics.Typeface font(Context c, int weight) {
        return UiFonts.of(c, weight);
    }
}
