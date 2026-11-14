package com.scamera.app.settings;

import android.content.SharedPreferences;

import com.scamera.app.processing.opengl.postpipeline.WhyV2Ae;

/**
 * SIMPLE_UI §4 «Стиль снимка» (the wizard's «Вид снимков → Стиль», the settings' style page and the shade's «Стиль» tile). P718
 * (owner, 2026-11-14: «Чб и плёнка замени на два пресета Vivid и Neutral … Во всех трёх пресетах тонмапинг оставь V2»): a tone
 * preset - Насыщенный (Vivid), Обычный (Normal) or Нейтральный (Neutral), in that order on every card row - stored in
 * {@link #KEY}; the photo's V2 tone takes it as factors on the user's own tone rows (WhyV2Ae.applyStyle), which keep their
 * values. Choosing a preset puts «Why тон» on V2. Black and white and the film colours are no styles any more: their own rows
 * (the black-and-white filter, the film LUT, the film simulation, the LUT tray) hold them, untouched by the style; a user who had
 * one (the style P369 derived from them) keeps it and reads Обычный here, as nothing was stored.
 */
public final class PhotoStyle {
    public static final int NORMAL = WhyV2Ae.STYLE_NORMAL, VIVID = WhyV2Ae.STYLE_VIVID, NEUTRAL = WhyV2Ae.STYLE_NEUTRAL;
    /** The stored preset: "normal" (also unset), "vivid" or "neutral". */
    public static final String KEY = "pref_photo_style";
    /** «Why тон» (PreferenceKeys.whyToneV2) and its V2 value. */
    static final String TONE_VERSION = "pref_scam_hybrid_tone_version", V2 = "v2";
    /** The cards' order (owner, 2026-11-14: «Порядок плиток сделай Насыщенный/Обычный/Нейтральный»). */
    private static final int[] ORDER = {VIVID, NORMAL, NEUTRAL};

    private PhotoStyle() {}

    /** The presets in the cards' order. */
    public static int[] order() {
        return ORDER.clone();
    }

    /** The stored preset (anything else, or nothing: Normal). */
    public static int current(SharedPreferences prefs) {
        return of(prefs.getString(KEY, ""));
    }

    /** The preset of a stored value. */
    public static int of(String value) {
        if (value == null) return NORMAL;
        switch (value.trim()) {
            case "vivid": return VIVID;
            case "neutral": return NEUTRAL;
            default: return NORMAL;
        }
    }

    /** The stored value (and the cards' tag suffix) of a preset. */
    public static String id(int style) {
        switch (style) {
            case VIVID: return "vivid";
            case NEUTRAL: return "neutral";
            default: return "normal";
        }
    }

    /** Stores {@code style} and puts «Why тон» on V2 (every preset is a V2 tone); the other settings stay as they are. */
    public static void apply(SharedPreferences prefs, int style) {
        prefs.edit().putString(KEY, id(style)).putString(TONE_VERSION, V2).apply();
    }

    /** The next style in the cards' order (the shade's «Стиль» tile cycles). */
    public static int next(int style) {
        for (int i = 0; i < ORDER.length; i++) if (ORDER[i] == style) return ORDER[(i + 1) % ORDER.length];
        return NORMAL;
    }

    public static String name(android.content.Context c, int style) {
        switch (style) {
            case VIVID: return com.scamera.app.util.Lang.t(c, "Насыщенный", "Vivid");
            case NEUTRAL: return com.scamera.app.util.Lang.t(c, "Нейтральный", "Neutral");
            default: return com.scamera.app.util.Lang.t(c, "Обычный", "Normal");
        }
    }

    /** What a preset does, in a line (the settings' long press, the cards' description). */
    public static String about(android.content.Context c, int style) {
        switch (style) {
            case VIVID: return com.scamera.app.util.Lang.t(c,
                    "Больше HDR: светлее тени, мягче яркие места, сочнее приглушённые цвета.",
                    "More HDR: brighter shadows, softer bright areas, richer muted colours.");
            case NEUTRAL: return com.scamera.app.util.Lang.t(c,
                    "Спокойнее: мягче контраст, меньше подъём теней, сдержаннее цвета.",
                    "Calmer: gentler contrast, less shadow lift, quieter colours.");
            default: return com.scamera.app.util.Lang.t(c,
                    "Тон камеры как есть: ваши настройки тона без изменений.",
                    "The camera's tone as it is: your tone settings unchanged.");
        }
    }
}
