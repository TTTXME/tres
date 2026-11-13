package com.scamera.app.circularbarlib.ui;

import android.content.Context;
import android.content.res.Configuration;

/**
 * SIMPLE_UI step 0 (owner: «одинаково на любом экране»): the camera chrome keeps the concept's proportions on every phone,
 * display size and font size. The concept is drawn for a {@link #REFERENCE_DP} window; every dp / sp of the camera screen
 * and of what lies over it (top bar, bottom rows, Pro panel, quick sheet, catalog, format / size sheets, focus ring, lock
 * pill) is multiplied by {@code k = clamp(short side / 390, 0.85, 1.35)}: 360dp gives 0.92, 411dp 1.05, 480dp 1.23.
 * Settings, gallery lists and sheets with running text are not scaled. The window's short side comes from the current
 * {@link Configuration} (multi-window included), not the physical display; the activity is recreated on a density change, so
 * {@code k} is cached per configuration only.
 *
 * <p>P582: the app's activities are laid out at the concept's density (app UiFixedScale: the display's short side is always
 * 390dp and the font scale 1, whatever the phone's display and font size), so on a phone {@code k} is 1 and the camera's dp
 * are the concept's as written; {@code k} still differs in a split-screen window and on a large screen (sw600dp+, which keeps
 * the system density). The font scale is 1 there too, so {@link UiTokens#FONT_SCALE_CAP} no longer applies on top.
 */
public final class UiScale {
    private UiScale() {}

    /** The concept's phone width (research/concept, 390 CSS px; 392 before P582), at which every camera dp holds as written. */
    public static final float REFERENCE_DP = 390f;
    public static final float MIN = 0.85f, MAX = 1.35f;

    private static int cachedSeq = Integer.MIN_VALUE;
    private static Configuration cachedFor;
    private static float cached = 1f;

    /** {@code k} for a window whose short side is {@code shortSideDp} (1 when unknown). */
    public static float k(float shortSideDp) {
        if (!(shortSideDp > 0f)) return 1f;
        return Math.max(MIN, Math.min(MAX, shortSideDp / REFERENCE_DP));
    }

    /** {@code k} of this context's window (short side of the current configuration, in dp). */
    public static float k(Context c) {
        Configuration cfg = c.getResources().getConfiguration();
        if (cfg == cachedFor && cachedSeq == cfg.hashCode()) return cached;
        int w = cfg.screenWidthDp, h = cfg.screenHeightDp;
        float shortSide;
        if (w > 0 && h > 0 && w != Configuration.SCREEN_WIDTH_DP_UNDEFINED && h != Configuration.SCREEN_HEIGHT_DP_UNDEFINED) {
            shortSide = Math.min(w, h);
        } else {
            android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
            shortSide = Math.min(m.widthPixels, m.heightPixels) / m.density;
        }
        cached = k(shortSide);
        cachedFor = cfg;
        cachedSeq = cfg.hashCode();
        return cached;
    }

    /** {@code v} dp of the camera chrome in pixels: {@code v * k * density}. */
    public static int dp(Context c, float v) {
        return Math.round(dpF(c, v));
    }

    /** {@link #dp} without rounding (radii, stroke widths). */
    public static float dpF(Context c, float v) {
        return v * k(c) * c.getResources().getDisplayMetrics().density;
    }

    /** {@code sp} of packed camera text: {@code sp * k}, with the font scale applied up to {@link UiTokens#FONT_SCALE_CAP}. */
    public static float spPx(Context c, float sp) {
        return UiTokens.spPx(c.getResources(), sp * k(c));
    }

    /** Sets a packed label's size ({@link #spPx}) and an autosize guard down to 8sp, so it never ellipsizes. */
    public static void text(android.widget.TextView view, float sp) {
        float px = spPx(view.getContext(), sp);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px);
        float min = UiTokens.spPx(view.getResources(), 8f);
        if (px > min + 1f) {
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(view,
                    Math.round(min), Math.round(px), 1, android.util.TypedValue.COMPLEX_UNIT_PX);
        }
    }

    /**
     * Sets a label's size ({@link #spPx}) with no autosize: for wrap_content labels (autosize there shrinks unpredictably),
     * whose container steps down by itself when it does not fit (the top bar's levels).
     */
    public static void textFixed(android.widget.TextView view, float sp) {
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeWithDefaults(view, androidx.core.widget.TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, spPx(view.getContext(), sp));
    }
}
