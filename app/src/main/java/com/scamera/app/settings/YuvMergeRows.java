package com.scamera.app.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;

import com.scamera.app.capture.YuvMergeRoute;
import com.scamera.app.ui.settings.SettingHelp;
import com.scamera.app.util.Lang;

/**
 * P646 «Склейка YUV» (owner, 2026-11-12: «переключатель должен быть в настройках линзы»): the lens page's switch of the YUV merge
 * (pref_sensorconfig_&lt;slot&gt;_yuvmerge, capture/YuvMergeRoute). Its default is the route's (on for the vivo X200 Pro's front
 * camera, which has no RAW); its line says which route the lens takes. The merge's own rows are in «Шумоподавление и склейка».
 */
public final class YuvMergeRows {
    private YuvMergeRows() {}

    static final int ORDER = 8990;

    public static String title(Context c) {
        return Lang.t(c, "Склейка YUV", "YUV merge");
    }

    public static String help(Context c) {
        return Lang.t(c, "Снимок этого объектива склеивается из серии кадров YUV, а не RAW: камера снимает серию после спуска со своей"
                        + " тоновой кривой SCAM (её шумодав на минимуме, резкость выключена), кадры выравниваются плитками 32×32 и"
                        + " складываются с порогами по шуму, измеренному на самой серии; короткие и длинный кадры брекетинга"
                        + " добавляют света и тени. Затем фото получает наш тон, цвет и резкость, как RAW-снимок. Нужен для камер без"
                        + " RAW (на vivo X200 Pro фронтальная камера включена по умолчанию); на объективе с RAW заменяет RAW-склейку"
                        + " только когда включён. Настройки склейки — «Шумоподавление и склейка» › «Склейка YUV». DNG у такого снимка нет.",
                "This lens's photo is merged from a series of YUV frames instead of RAW: the camera shoots the series after the shutter"
                        + " with a SCAM tone curve (its noise reduction at minimum, its sharpening off), the frames are aligned in 32×32"
                        + " tiles and added with thresholds from the noise measured on the series itself; short and long bracket frames"
                        + " add highlights and shadows. The photo then gets our tone, colour and sharpening, as a RAW shot. Meant for"
                        + " cameras without RAW (on the vivo X200 Pro the front camera has it on by default); on a lens with RAW it"
                        + " replaces the RAW merge only when switched on. The merge's settings: «Noise reduction and merge» › «YUV merge»."
                        + " Such a shot has no DNG.");
    }

    /** The switch's line: the route this lens takes. */
    public static String state(Context c, boolean on, boolean byDefault) {
        return on ? Lang.t(c, "Кадры YUV", "YUV frames") + (byDefault ? Lang.t(c, " (по умолчанию для этой камеры)", " (this camera's default)") : "")
                : Lang.t(c, "Кадры RAW (если камера их даёт)", "RAW frames (where the camera gives them)");
    }

    /** The switch of {@code slot} under {@code category} (its camera: {@code camera}). */
    static void add(Context context, PreferenceCategory category, String slot, @Nullable String camera) {
        final SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        final boolean x200Pro = YuvMergeRoute.x200Pro();
        final boolean byDefault = YuvMergeRoute.defaultOn(x200Pro, camera);
        final boolean on = YuvMergeRoute.on(prefs.getAll(), slot, camera, x200Pro);
        final SwitchPreferenceCompat toggle = new SwitchPreferenceCompat(context);
        toggle.setKey(YuvMergeRoute.key(slot));
        toggle.setTitle(title(context));
        toggle.setDefaultValue(byDefault);
        toggle.setChecked(on);
        toggle.setIcon(com.scamera.app.R.drawable.settings_ic_sensor);
        toggle.setOrder(ORDER);
        SettingHelp.describe(toggle, help(context));
        SettingHelp.summaryIsState(toggle);
        toggle.setSummary(state(context, on, byDefault && !prefs.contains(YuvMergeRoute.key(slot))));
        toggle.setOnPreferenceChangeListener((p, value) -> {
            p.setSummary(state(context, Boolean.TRUE.equals(value), false));
            return true;
        });
        com.scamera.app.ui.settings.SettingsStyle.style(toggle, category);
        category.addPreference(toggle);
    }
}
