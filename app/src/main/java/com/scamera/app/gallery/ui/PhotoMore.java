package com.scamera.app.gallery.ui;

import androidx.annotation.Nullable;

import com.scamera.app.util.Lang;

import java.util.Locale;

/**
 * P492 (owner, 2026-11-03: «сделать более детальные свойства у файлов»): the details sheet's «Подробнее» rows - what the file's
 * EXIF holds beyond the «Как снято» cells, in plain words; a tag the file lacks gives no row. P706: the words of EXIF values
 * only; the rows are PhotoProperties' sections.
 */
public final class PhotoMore {
    private PhotoMore() {}

    /** «2026:11:03 11:51:19» as «03.11.2026 11:51:19». */
    @Nullable
    static String taken(@Nullable String v) {
        if (v == null || v.length() < 19 || v.charAt(4) != ':') return v;
        return v.substring(8, 10) + "." + v.substring(5, 7) + "." + v.substring(0, 4) + v.substring(10, 19);
    }

    @Nullable
    static String bias(double ev) {
        if (Double.isNaN(ev)) return null;
        if (Math.abs(ev) < 0.005) return "0 EV";
        return String.format(Locale.ROOT, "%+.1f EV", ev).replace('.', Lang.t(",", ".").charAt(0));
    }

    @Nullable
    static String whiteBalance(int v) {
        return v == 0 ? Lang.t("Авто", "Auto") : v == 1 ? Lang.t("Ручной", "Manual") : null;
    }

    @Nullable
    static String flash(int v) {
        if (v < 0) return null;
        return (v & 1) != 0 ? Lang.t("Сработала", "Fired") : Lang.t("Не сработала", "Did not fire");
    }

    @Nullable
    static String metering(int v) {
        switch (v) {
            case 1: return Lang.t("Средний", "Average");
            case 2: return Lang.t("Центровзвешенный", "Centre-weighted");
            case 3: return Lang.t("Точечный", "Spot");
            case 4: return Lang.t("Многоточечный", "Multi-spot");
            case 5: return Lang.t("Матричный", "Matrix");
            case 6: return Lang.t("Частичный", "Partial");
            default: return null;
        }
    }

    @Nullable
    static String zoom(double r) {
        if (Double.isNaN(r) || r <= 1.001) return null;
        return String.format(Locale.ROOT, "%.1f×", r).replace('.', Lang.t(",", ".").charAt(0));
    }

    @Nullable
    static String colourSpace(int v) {
        return v == 1 ? "sRGB" : v == 0xFFFF ? Lang.t("Широкий (Display P3 / другой)", "Wide (Display P3 / other)") : null;
    }

    @Nullable
    static String rotation(int degrees) {
        return degrees == 0 ? null : degrees + "°";
    }
}
