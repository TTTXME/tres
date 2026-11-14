package com.scamera.app.processing.yuv;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.util.Log;

import java.util.Locale;

/**
 * P701 (owner, 2026-11-14: «Вот тебе бурсты, логи и фото с YUV, займись исправлением»): where the camera applies the points of our
 * capture curve (YuvMergeRoute.curvePairs) - to linear light, as Camera2 defines TONEMAP_CURVE, or to its own sRGB-encoded signal.
 * The vivo X200 Pro front camera (MediaTek) does the second: its series' ES frames against the N frames fit exposure^1.00 +- 0.02
 * at every quantile decoded through the points and then sRGB's inverse, exposure^0.47 .. 0.63 through the points alone; decoded
 * as asked, a series without short frames came out with its linear median at 0.62 (a washed-out photo, the wall nearly white),
 * one with them was rescued by P691's power (2.11) with crushed shadows.
 * <p>
 * The merge measures the model from the bracket (scam-yuv-merge.h, mergeBurst's curve check); a series without a bracket decodes
 * through {@link #prior}: the model the camera's last bracket measured on this firmware, else MediaTek's (over sRGB), else as
 * Camera2 defines it. scam_dev.txt "yuv_curve_model 1 / 2" sets the prior (1 over linear light, 2 over the camera's sRGB).
 */
public final class YuvCurveModel {
    private YuvCurveModel() {}

    static final String TAG = "SCAM_HDR";
    /** The models (the merge's stats[10] codes): not known, the points over linear light, the points over the camera's sRGB. */
    public static final int UNKNOWN = 0, LINEAR = 1, OVER_SRGB = 2;
    static final String PREFS = "scam_yuv_curve";

    /** The stored value of a measured model: "model|fingerprint". */
    static String stamp(int model, String fingerprint) {
        return model + "|" + (fingerprint == null ? "" : fingerprint);
    }

    /** The model of a stored value measured on {@code fingerprint}, UNKNOWN for another firmware, a missing or a bad value. */
    static int stored(@Nullable String value, String fingerprint) {
        if (value == null) return UNKNOWN;
        final int bar = value.indexOf('|');
        if (bar <= 0 || !value.substring(bar + 1).equals(fingerprint == null ? "" : fingerprint)) return UNKNOWN;
        try {
            final int m = Integer.parseInt(value.substring(0, bar));
            return m == LINEAR || m == OVER_SRGB ? m : UNKNOWN;
        } catch (NumberFormatException e) {
            return UNKNOWN;
        }
    }

    /** A MediaTek SoC (Build.SOC_MANUFACTURER, else Build.HARDWARE "mt..."). */
    static boolean mediatek(@Nullable String hardware, @Nullable String soc) {
        if (soc != null && soc.toLowerCase(Locale.ROOT).contains("mediatek")) return true;
        return hardware != null && hardware.toLowerCase(Locale.ROOT).startsWith("mt");
    }

    /** The prior: {@code forced} (scam_dev, 1 / 2), else the {@code stored} model, else MediaTek's over sRGB, else linear light. */
    static int prior(int forced, int stored, boolean mediatek) {
        if (forced == LINEAR || forced == OVER_SRGB) return forced;
        if (stored == LINEAR || stored == OVER_SRGB) return stored;
        return mediatek ? OVER_SRGB : LINEAR;
    }

    static String name(int model) {
        return model == OVER_SRGB ? "over the camera's sRGB" : model == LINEAR ? "over linear light" : "unknown";
    }

    /** The prior of {@code camera}'s series (never throws: linear light / MediaTek's on any failure), with its reason. */
    public static int prior(@Nullable String camera, StringBuilder why) {
        final int forced = Math.round(PreferenceKeys.scamDevNumber("yuv_curve_model", 0f));
        int stored = UNKNOWN;
        try {
            final SharedPreferences p = prefs();
            if (p != null && camera != null) stored = stored(p.getString(key(camera), null), android.os.Build.FINGERPRINT);
        } catch (RuntimeException e) {
            stored = UNKNOWN;
        }
        final boolean mtk = mediatek(android.os.Build.HARDWARE,
                android.os.Build.VERSION.SDK_INT >= 31 ? android.os.Build.SOC_MANUFACTURER : null);
        final int model = prior(forced, stored, mtk);
        why.append(forced == LINEAR || forced == OVER_SRGB ? "scam_dev yuv_curve_model"
                : stored != UNKNOWN ? "measured by this camera's last bracket" : mtk ? "MediaTek default" : "Camera2 default");
        return model;
    }

    /** The bracket measured {@code model} on {@code camera}: kept for its series without a bracket (logged when it changes). */
    public static void store(@Nullable String camera, int model) {
        if (camera == null || (model != LINEAR && model != OVER_SRGB)) return;
        try {
            final SharedPreferences p = prefs();
            if (p == null) return;
            final String value = stamp(model, android.os.Build.FINGERPRINT);
            if (value.equals(p.getString(key(camera), null))) return;
            p.edit().putString(key(camera), value).apply();
            Log.i(TAG, "YUV MERGE camera " + camera + ": the capture curve applies " + name(model) + " (measured, kept for this firmware)");
        } catch (RuntimeException e) {
            Log.w(TAG, "YUV MERGE curve model not kept: " + e);
        }
    }

    static String key(String camera) {
        return "model_" + camera;
    }

    @Nullable
    private static SharedPreferences prefs() {
        final Context c = com.scamera.app.app.ScameraApp.getAppContext();
        return c == null ? null : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
