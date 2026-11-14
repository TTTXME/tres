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
        return Lang.t(c, "Склейка YUV 8 бит", "YUV merge 8-bit");
    }

    /** P692: the lens's second switch, the 10-bit series (P010 / HLG10). */
    public static String title10(Context c) {
        return Lang.t(c, "Склейка YUV 10 бит", "YUV merge 10-bit");
    }

    public static String help10(Context c) {
        return Lang.t(c, "То же, что «Склейка YUV 8 бит», но серия снимается в 10 битах (P010) с HDR-кривой HLG камеры, если камера"
                        + " даёт такой поток в размере фото рядом с обычным видоискателем; иначе — 8 бит. Больше градаций в тенях,"
                        + " но где камера ставит белый в HLG, решает она сама, и кадр вдвое тяжелее (меньше кадров в серии)."
                        + " Включение одного переключателя выключает другой.",
                "As «YUV merge 8-bit», but the series is taken at 10 bits (P010) with the camera's HLG HDR curve where the camera"
                        + " gives such a stream at the photo size beside the normal viewfinder; 8 bits otherwise. More steps in the"
                        + " shadows, but where HLG puts the white is the camera's choice, and a frame is twice as heavy (fewer frames"
                        + " in the series). Turning one switch on turns the other off.");
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

    /** The two switches of {@code slot} under {@code category} (its camera: {@code camera}); one of them is on at most (P692). */
    static void add(Context context, PreferenceCategory category, String slot, @Nullable String camera) {
        final SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        final boolean x200Pro = YuvMergeRoute.x200Pro();
        final boolean byDefault = YuvMergeRoute.defaultOn(x200Pro, camera);
        final java.util.Map<String, ?> values = prefs.getAll();
        final boolean ten = YuvMergeRoute.tenOn(values, slot, camera, x200Pro);
        final boolean eight = !ten && YuvMergeRoute.eightOn(values, slot, camera, x200Pro);
        // the value and default are set before the row joins the page: a switch set after it would store its state (the module
        // then counts as customised)
        final SwitchPreferenceCompat toggle8 = row(context, YuvMergeRoute.key(slot), title(context), help(context), ORDER, byDefault, eight);
        final SwitchPreferenceCompat toggle10 = row(context, YuvMergeRoute.key10(slot), title10(context), help10(context), ORDER + 1, false, ten);
        toggle8.setSummary(state(context, eight, byDefault && !prefs.contains(YuvMergeRoute.key(slot))));
        toggle10.setSummary(state10(context, ten));
        com.scamera.app.ui.settings.SettingsStyle.style(toggle8, category);
        category.addPreference(toggle8);
        com.scamera.app.ui.settings.SettingsStyle.style(toggle10, category);
        category.addPreference(toggle10);
        toggle8.setOnPreferenceChangeListener((p, value) -> {
            final boolean on = Boolean.TRUE.equals(value);
            if (on && toggle10.isChecked()) {
                toggle10.setChecked(false);
                toggle10.setSummary(state10(context, false));
            }
            p.setSummary(state(context, on, false));
            return true;
        });
        toggle10.setOnPreferenceChangeListener((p, value) -> {
            final boolean on = Boolean.TRUE.equals(value);
            if (on && toggle8.isChecked()) {
                toggle8.setChecked(false);
                toggle8.setSummary(state(context, false, false));
            }
            // the 8-bit default (the X200 Pro front) must not come back under the 10-bit switch: stored explicitly
            if (on) prefs.edit().putBoolean(YuvMergeRoute.key(slot), false).apply();
            p.setSummary(state10(context, on));
            return true;
        });
    }

    /** P692: the 10-bit switch's line. */
    public static String state10(Context c, boolean on) {
        return on ? Lang.t(c, "Кадры YUV 10 бит (8 бит, если камера не даёт 10)", "10-bit YUV frames (8-bit where the camera has no 10)")
                : Lang.t(c, "Выключено", "Off");
    }

    private static SwitchPreferenceCompat row(Context context, String key, String title, String help, int order, boolean byDefault, boolean on) {
        final SwitchPreferenceCompat toggle = new SwitchPreferenceCompat(context);
        toggle.setKey(key);
        toggle.setTitle(title);
        toggle.setDefaultValue(byDefault);
        toggle.setChecked(on);
        toggle.setIcon(com.scamera.app.R.drawable.settings_ic_sensor);
        toggle.setOrder(order);
        SettingHelp.describe(toggle, help);
        SettingHelp.summaryIsState(toggle);
        return toggle;
    }
}
