package com.particlesdevs.photoncamera.ui.camera.views.settingsbar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.widget.TextView;

import com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;

/**
 * The card language of the settings screens (SettingsStyle, P6b) on the camera screen (P25): CARD tiles with a LINE
 * stroke, the camera accent (AccentPalette.camera, owner's answer 5) with INK on accent fills, MUTED secondary text.
 */
public final class ShadeStyle {
    public static final int BG = SettingsStyle.BG, CARD = SettingsStyle.CARD, LINE = SettingsStyle.LINE, TEXT = SettingsStyle.TEXT,
            MUTED = SettingsStyle.MUTED, INK = SettingsStyle.INK, FIELD = SettingsStyle.FIELD;
    public static final float DIMMED = SettingsStyle.DIMMED;
    private static final int RIPPLE = 0x29FFFFFF;

    private ShadeStyle() {}

    public static int dp(Context c, float v) {
        return SettingsStyle.dp(c, v);
    }

    /** The camera accent: the user's accent, or the camera yellow with the default theme. */
    public static int accent(Context c) {
        return AccentPalette.camera(c);
    }

    /** The accent at a given alpha over the card colour (chips, pills). */
    public static int tint(int accent, float alpha) {
        return SettingsStyle.tint(accent, alpha);
    }

    /** A rounded card: fill, optional 1dp stroke. */
    public static GradientDrawable card(Context c, int fill, int stroke, float radiusDp) {
        return SettingsStyle.shape(c, fill, stroke, radiusDp);
    }

    /** A card with a ripple bounded by its own shape. */
    public static Drawable pressable(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable mask = card(c, 0xFFFFFFFF, 0, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), card(c, fill, stroke, radiusDp), mask);
    }

    /** SIMPLE_UI: a transparent button with a ripple bounded by a rounded shape of {@code radiusDp} (times the camera's k). */
    public static Drawable ripple(Context c, float radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dpF(c, radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, mask);
    }

    /** The sheet: BG with a LINE stroke and 26dp top corners; the stroke has no bottom edge. */
    /**
     * SIMPLE_UI §2: the quick sheet, see-through (the concept's 40 % transparency: its colour at {@link #sheetAlpha} of
     * «Прозрачность шторки»), 28dp top corners x the screen's scale.
     */
    public static Drawable sheet(Context c) {
        // P495 (concept G): a floating glass panel - all corners round (18dp), a hairline light edge, the glass alpha
        return new GlassPlates((SHEET_FILL & 0x00FFFFFF) | (Math.round(255 * glassAlpha(c)) << 24),
                com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dpF(c, 18),
                Math.max(1, Math.round(c.getResources().getDisplayMetrics().density * .5f)), dp(c, 10));
    }

    /**
     * P495 (owner, 2026-11-03: «Для опытных» and «Настройки камеры» outside the shade's border, brought up by a swipe): the glass
     * as one plate, or - with a {@link #setSplit split} - two plates with a see-through gap above the split row (the lower one the
     * FULL level's rows, rising with the sheet from under the panel).
     */
    static final class GlassPlates extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float radius;
        private final int gap;
        private int split = -1;

        GlassPlates(int color, float radius, int stroke, int gap) {
            fill.setColor(color);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(stroke);
            line.setColor(0x29FFFFFF);
            this.radius = radius;
            this.gap = gap;
        }

        /** The row (in the drawable's bounds) where the lower plate starts; -1: one plate. */
        void setSplit(int y) {
            if (split != y) { split = y; invalidateSelf(); }
        }

        @Override public void draw(Canvas canvas) {
            final android.graphics.Rect b = getBounds();
            if (split <= 0 || split >= b.height()) { plate(canvas, b.top, b.bottom); return; }
            if (split - gap > 2 * radius) plate(canvas, b.top, b.top + split - gap);
            plate(canvas, b.top + split, b.bottom);
        }

        private void plate(Canvas canvas, float top, float bottom) {
            final android.graphics.Rect b = getBounds();
            canvas.drawRoundRect(b.left, top, b.right, bottom, radius, radius, fill);
            final float h = line.getStrokeWidth() / 2f;
            canvas.drawRoundRect(b.left + h, top + h, b.right - h, bottom - h, radius, radius, line);
        }

        @Override public void getOutline(android.graphics.Outline outline) {
            outline.setRoundRect(getBounds(), radius);
        }

        @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { fill.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** The concept's sheet colour (--sheet). */
    public static final int SHEET_FILL = 0xFF15191C;
    /** «Прозрачность шторки» (0..80 %, default 40 %): the key of the setting. */
    public static final String TRANSPARENCY_KEY = "pref_shade_transparency";
    public static final int TRANSPARENCY_DEFAULT = 40;

    /**
     * P495 (owner, 2026-11-03, concept G): the shade's glass panel is 10 points more see-through than «Прозрачность шторки» says
     * (the default 40 % shows as 50 %; owner: first +30, then «на 20% менее прозрачной»), still following the setting, at most 90 %.
     */
    public static float glassAlpha(Context c) {
        return Math.max(.1f, sheetAlpha(c) - GLASS_EXTRA / 100f);
    }

    /** The sheet's and its cards' alpha: 1 - the transparency. */
    public static float sheetAlpha(Context c) {
        int t = TRANSPARENCY_DEFAULT;
        try {
            // P551: the one key read (getAll copied every setting per call; the sheet and its cards ask on each open)
            android.content.SharedPreferences p = android.preference.PreferenceManager.getDefaultSharedPreferences(c.getApplicationContext());
            if (p.contains(TRANSPARENCY_KEY)) {
                try {
                    t = p.getInt(TRANSPARENCY_KEY, TRANSPARENCY_DEFAULT);
                } catch (ClassCastException notInt) {
                    try {
                        t = Math.round(p.getFloat(TRANSPARENCY_KEY, TRANSPARENCY_DEFAULT));
                    } catch (ClassCastException notFloat) {
                        t = Math.round(Float.parseFloat(p.getString(TRANSPARENCY_KEY, String.valueOf(TRANSPARENCY_DEFAULT))));
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        t = Math.max(0, Math.min(80, t));
        return 1f - t / 100f;
    }

    /** P495: how much more see-through the glass panel is than «Прозрачность шторки» (points). */
    static final int GLASS_EXTRA = 10;
    /** P495: the Material You tonal fill of a tile at rest (white at 9 % over the glass). */
    public static final int TONAL = 0x17FFFFFF;

    /** The ink on an accent fill: dark on a light accent, white on a dark one. */
    public static int onAccent(int accent) {
        float l = (0.299f * ((accent >> 16) & 255) + 0.587f * ((accent >> 8) & 255) + 0.114f * (accent & 255)) / 255f;
        return l > .6f ? INK : 0xFFFFFFFF;
    }

    /** Small uppercase accent label of a section («ЗАКРЕПЛЕНО», «HYBRID»). */
    public static void label(TextView t, int accent) {
        t.setAllCaps(true);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setLetterSpacing(.14f);
        t.setTextColor((accent & 0x00FFFFFF) | 0xD9000000);
        t.setIncludeFontPadding(false);
    }

    /**
     * Never an ellipsis (SHADE_TASK.md §2): one line, shrinking from {@code maxSp} down to {@code oneLineMinSp} (0.5sp
     * steps); when that does not fit, the largest size down to {@code minSp} at which the text takes at most
     * {@code maxLines} lines of {@code widthPx} without splitting a word.
     */
    public static void fit(TextView view, int widthPx, float maxSp, float oneLineMinSp, float minSp, int maxLines) {
        CharSequence text = view.getText();
        view.setMaxLines(maxLines);
        view.setEllipsize(null);
        if (text == null || text.length() == 0 || widthPx <= 0) {
            setSp(view, maxSp);
            return;
        }
        TextPaint paint = new TextPaint(view.getPaint());
        float scaled = view.getResources().getDisplayMetrics().scaledDensity;
        for (float sp = maxSp; sp >= oneLineMinSp - 1e-3f; sp -= .5f) {
            paint.setTextSize(sp * scaled);
            if (fits(text, paint, widthPx, 1)) {
                setSp(view, sp);
                return;
            }
        }
        float sp = maxSp;
        for (; sp > minSp; sp -= .5f) {
            paint.setTextSize(sp * scaled);
            if (fits(text, paint, widthPx, maxLines)) break;
        }
        sp = Math.max(minSp, sp);
        setSp(view, sp);
        // P517 (owner, 2026-11-05: «убедись что названия не вылазят за пределы»): still too long at the smallest size - an ellipsis
        // inside the view instead of letters cut by its edge
        paint.setTextSize(sp * scaled);
        if (!fits(text, paint, widthPx, maxLines)) view.setEllipsize(android.text.TextUtils.TruncateAt.END);
    }

    private static void setSp(TextView view, float sp) {
        float px = sp * view.getResources().getDisplayMetrics().scaledDensity;
        if (Math.abs(view.getTextSize() - px) > .01f) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
    }

    static boolean fits(CharSequence text, TextPaint paint, int width, int maxLines) {
        for (String word : text.toString().split("[\\s­]+"))
            if (paint.measureText(word) > width) return false;
        StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build();
        return layout.getLineCount() <= maxLines;
    }
}
