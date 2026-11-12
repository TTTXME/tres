package com.particlesdevs.photoncamera.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.InputType;

import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;

import com.particlesdevs.photoncamera.capture.FullResRecipe;
import com.particlesdevs.photoncamera.capture.FullResRecipeStore;
import com.particlesdevs.photoncamera.ui.settings.SettingHelp;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.List;

/**
 * P635 (owner, 2026-11-12: «Реализуй как переключатель в настройках линзы»): the lens page's rows of the full-resolution recipe -
 * the switch «Полное разрешение (рецепт)» and, while it is on, the profile's fields (the preset of this phone and camera where
 * there is one, each field editable) with «Сбросить к пресету». The switch's line under its title is its state: the preset's
 * name, why the profile cannot run here (the row greyed below Android 9), or why it failed on this lens. Every row's long press
 * says what its field does. Keys: pref_sensorconfig_&lt;slot&gt;_recipe… (the module's copy and reset take them; a lens pin as
 * any sensor row).
 */
public final class FullResRecipeRows {
    private FullResRecipeRows() {}

    static final int ORDER = 9000;
    /** The row of «Сбросить к пресету» (no stored value). */
    public static final String RESET = "recipereset";

    public static String key(String slot, String field) {
        return FullResRecipeStore.key(slot, field);
    }

    /** A row of this section (the switch, a field, the reset) by its key. */
    public static boolean ours(@Nullable String key, String slot) {
        return key != null && key.startsWith(ModuleSensorSettings.prefix(slot) + FullResRecipe.ON);
    }

    public static String title(Context c) {
        return Lang.t(c, "Полное разрешение (рецепт)", "Full resolution (recipe)");
    }

    public static String help(Context c) {
        return Lang.t(c, "Снимок в полном разрешении сенсора по рецепту для этого объектива: отдельный RAW-поток полного размера только на"
                        + " снимок, рядом с ним маленький поток-спутник, свои ключи камеры и шаблон запроса. Видоискатель при этом работает"
                        + " как обычно, на своём потоке. Кадр проверяется: если камера отдала увеличенный кадр 12 Мп вместо настоящего"
                        + " полного, снимок сохранится обычным и об этом будет сказано. Поля ниже заполнены пресетом для этого телефона,"
                        + " если он есть; каждое можно изменить. Если камера откажет, вернётся обычный режим, а причина будет здесь.",
                "A photo at the sensor's full resolution by a recipe for this lens: a full-size RAW stream of its own for the shot only,"
                        + " a small companion stream beside it, camera keys and a request template of its own. The viewfinder keeps"
                        + " running as usual on its own stream. The frame is checked: if the camera delivered an upscaled 12 MP frame"
                        + " instead of the real full one, the photo is saved as a normal one and you are told. The fields below are filled"
                        + " from the preset for this phone where there is one; each can be changed. If the camera refuses, the normal"
                        + " mode comes back and the reason is shown here.");
    }

    public static String fieldTitle(Context c, String field) {
        switch (field) {
            case FullResRecipe.SIZE: return Lang.t(c, "Размер RAW снимка", "Capture RAW size");
            case FullResRecipe.USAGE: return Lang.t(c, "Usage RAW-ридера", "RAW reader usage");
            case FullResRecipe.USE_CASE: return Lang.t(c, "Stream use case RAW", "RAW stream use case");
            case FullResRecipe.COMPANION: return Lang.t(c, "Поток-спутник", "Companion stream");
            case FullResRecipe.TAGS: return Lang.t(c, "Ключи камеры (vendor tags)", "Camera keys (vendor tags)");
            case FullResRecipe.TEMPLATE: return Lang.t(c, "Шаблон снимка", "Still template");
            case FullResRecipe.SESSION: return Lang.t(c, "Шаблон параметров сессии", "Session parameters template");
            case FullResRecipe.FPS: return Lang.t(c, "Частота кадров", "Frame rate");
            case FullResRecipe.PIXEL_MODE: return Lang.t(c, "Sensor pixel mode", "Sensor pixel mode");
            case FullResRecipe.PREVIEW: return Lang.t(c, "Видоискатель", "Viewfinder");
            case FullResRecipe.FRAMES: return Lang.t(c, "Кадров на снимок", "Frames a shot");
            case FullResRecipe.OP_MODE: return Lang.t(c, "Режим сессии", "Session operation mode");
            default: return field;
        }
    }

    public static String fieldHelp(Context c, String field) {
        switch (field) {
            case FullResRecipe.SIZE: return Lang.t(c, "Ширина x высота RAW-потока для снимка, например 8192x6144. Может быть размером, которого нет в списке камеры.",
                    "Width x height of the shot's RAW stream, for example 8192x6144. It may be a size the camera does not list.");
            case FullResRecipe.USAGE: return Lang.t(c, "Биты usage ридера RAW (число или 0x…). 0 — как обычно.",
                    "The RAW reader's usage bits (a number or 0x…). 0: the usual ones.");
            case FullResRecipe.USE_CASE: return Lang.t(c, "Значение setStreamUseCase только для RAW-потока снимка (число или 0x…; значения производителя от 0x80000). 0 — не задавать. Нужен Android 13.",
                    "The setStreamUseCase value of the shot's RAW stream alone (a number or 0x…; vendor values start at 0x80000). 0: not set. Needs Android 13.");
            case FullResRecipe.COMPANION: return Lang.t(c, "Второй поток в том же запросе, что и RAW снимка; без него камера может отдать увеличенный кадр. off — нет; 640x480 — свой поток YUV этого размера; auto — самый маленький YUV из списка камеры; viewfinder — поток ISP видоискателя.",
                    "A second stream in the same request as the shot's RAW; without it the camera may deliver an upscaled frame. off: none; 640x480: a YUV stream of that size; auto: the smallest YUV the camera lists; viewfinder: the viewfinder's ISP stream.");
            case FullResRecipe.TAGS: return Lang.t(c, "По одному на строку: ключ=тип:значение@куда. Типы: i32, i32[], u8, u8[], i64, f32. Куда: session — параметры сессии, request — каждый запрос, both — и то и другое, still — только снимок. Пример: xiaomi.remosaic.enabled=u8[]:1@both",
                    "One a line: key=type:value@where. Types: i32, i32[], u8, u8[], i64, f32. Where: session - the session parameters, request - every request, both, still - the shot only. Example: xiaomi.remosaic.enabled=u8[]:1@both");
            case FullResRecipe.TEMPLATE: return Lang.t(c, "Шаблон Camera2 для запроса снимка. Обычно still.", "The Camera2 template of the shot's request. Usually still.");
            case FullResRecipe.SESSION: return Lang.t(c, "Шаблон Camera2 для параметров сессии.", "The Camera2 template of the session parameters.");
            case FullResRecipe.FPS: return Lang.t(c, "Диапазон частоты кадров для сессии и снимка, например 30-30. Пусто — не менять.",
                    "The frame rate range of the session and the shot, for example 30-30. Empty: unchanged.");
            case FullResRecipe.PIXEL_MODE: return Lang.t(c, "never — режим пикселей не задаётся совсем; annotate — RAW снимка и спутник помечаются MAXIMUM_RESOLUTION, снимок его запрашивает (нужен Android 12).",
                    "never: no pixel mode is said at all; annotate: the shot's RAW and the companion are marked MAXIMUM_RESOLUTION and the shot asks for it (needs Android 12).");
            case FullResRecipe.PREVIEW: return Lang.t(c, "Поток видоискателя рядом с RAW снимка: isp — обычный поток ISP; raw — обычный RAW-поток камеры (видоискатель из RAW); both — оба.",
                    "The viewfinder's stream beside the shot's RAW: isp - the usual ISP stream; raw - the camera's usual RAW stream (the RAW viewfinder); both.");
            case FullResRecipe.FRAMES: return Lang.t(c, "Сколько кадров полного размера снимается после нажатия (1–8). Больше — меньше шума, но дольше и горячее.",
                    "How many full-size frames a press takes (1-8). More: less noise, but longer and hotter.");
            case FullResRecipe.OP_MODE: return Lang.t(c, "Режим сессии камеры (число или 0x…). 0 — обычный.", "The camera session's operation mode (a number or 0x…). 0: regular.");
            default: return "";
        }
    }

    /** The reason a profile cannot run or failed, in the owner's words. */
    public static String blockedText(Context c, String blocked) {
        if (blocked.startsWith("failed:"))
            return Lang.t(c, "Не запустился: ", "Did not run: ") + blocked.substring(7)
                    + Lang.t(c, ". Измените поле или сбросьте к пресету, чтобы попробовать снова.", ". Change a field or reset to the preset to try again.");
        switch (blocked) {
            case "size": return Lang.t(c, "Для этого объектива нет пресета: задайте размер RAW и остальные поля.", "No preset for this lens: set the RAW size and the other fields.");
            case "usecase:33": return Lang.t(c, "Stream use case требует Android 13.", "The stream use case needs Android 13.");
            case "pixelmode:31": return Lang.t(c, "Sensor pixel mode требует Android 12.", "The sensor pixel mode needs Android 12.");
            case "session:28": return Lang.t(c, "Недоступно: нужен Android 9.", "Not available: needs Android 9.");
            case "companion:viewfinder": return Lang.t(c, "Спутник «viewfinder» требует поток ISP в видоискателе (isp или both).", "The “viewfinder” companion needs the ISP stream in the viewfinder (isp or both).");
            case "companion:pixelmode": return Lang.t(c, "Спутник «viewfinder» не работает с annotate.", "The “viewfinder” companion does not work with annotate.");
            default: return blocked;
        }
    }

    /** The switch's line: the profile's name and what keeps it from running (null: nothing to say while it is off without a preset). */
    @Nullable
    public static String state(Context c, boolean on, @Nullable FullResRecipe preset, FullResRecipe recipe, @Nullable String blocked) {
        final String name = recipe.id.isEmpty() ? null : recipe.id + (recipe.experimental ? Lang.t(c, " · не проверен на телефоне", " · not proven on a phone") : "");
        if (!on) return preset == null ? null : Lang.t(c, "Пресет: ", "Preset: ") + name;
        if (blocked != null) return (name == null ? "" : name + ". ") + blockedText(c, blocked);
        return name == null ? Lang.t(c, "Свой профиль", "Own profile") : name;
    }

    /** The section's rows of {@code slot} under {@code category}; {@code preset}: its camera's (null: none). */
    static void add(Context context, PreferenceCategory category, String slot, @Nullable FullResRecipe preset, @Nullable Runnable refresh) {
        final SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        final boolean on = FullResRecipeStore.on(prefs, slot);
        final List<String> problems = new ArrayList<>();
        final FullResRecipe recipe = FullResRecipeStore.profile(prefs, slot, preset, problems);
        final String blocked = FullResRecipeStore.blocked(prefs, slot, recipe, Build.VERSION.SDK_INT, String.valueOf(Build.FINGERPRINT));

        final SwitchPreferenceCompat toggle = new SwitchPreferenceCompat(context);
        toggle.setKey(key(slot, FullResRecipe.ON));
        toggle.setTitle(title(context));
        toggle.setDefaultValue(false);
        toggle.setIcon(com.particlesdevs.photoncamera.R.drawable.settings_ic_sensor);
        toggle.setOrder(ORDER);
        final String state = state(context, on, preset, recipe, blocked);
        SettingHelp.describe(toggle, help(context));
        SettingHelp.summaryIsState(toggle);
        toggle.setSummary(state);
        // greyed where no session of this kind can be made at all (the reason is its line); a switched-on one can still be turned off
        toggle.setEnabled(on || Build.VERSION.SDK_INT >= 28);
        toggle.setOnPreferenceChangeListener((p, value) -> {
            if (refresh != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(refresh);
            return true;
        });
        com.particlesdevs.photoncamera.ui.settings.SettingsStyle.style(toggle, category); // a row built later takes its layout before the list sees it
        category.addPreference(toggle);
        if (!on) return;

        final java.util.Map<String, String> fields = recipe.fields();
        int order = ORDER;
        for (String field : FullResRecipe.FIELDS) {
            final String shown = fields.get(field);
            final Preference row = lists(field) ? listRow(context, slot, field, shown) : textRow(context, slot, field, shown);
            row.setOrder(++order);
            row.setIconSpaceReserved(true);
            SettingHelp.describe(row, fieldHelp(context, field));
            row.setOnPreferenceChangeListener((p, value) -> {
                final boolean valid = FullResRecipe.valid(field, String.valueOf(value));
                if (!valid) android.widget.Toast.makeText(context, Lang.t(context, "Не подходит: ", "Not valid: ") + fieldHelp(context, field),
                        android.widget.Toast.LENGTH_LONG).show();
                else if (refresh != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(refresh);
                return valid;
            });
            com.particlesdevs.photoncamera.ui.settings.SettingsStyle.style(row, category); // a row built later takes its layout before the list sees it
            category.addPreference(row);
        }

        final Preference reset = new Preference(context);
        reset.setKey(key(slot, RESET));
        reset.setPersistent(false);
        reset.setLayoutResource(com.particlesdevs.photoncamera.R.layout.preference_card);
        reset.setTitle(Lang.t(context, "Сбросить к пресету", "Reset to the preset"));
        reset.setSummary(preset == null ? Lang.t(context, "Для этого объектива пресета нет: поля очистятся", "This lens has no preset: the fields are cleared")
                : Lang.t(context, "Вернуть поля пресета «", "Bring back the fields of the preset “") + preset.id + Lang.t(context, "»", "”"));
        SettingHelp.describe(reset, Lang.t(context, "Убирает ваши правки полей рецепта и память о неудачном запуске; переключатель остаётся как есть.",
                "Removes your edits of the recipe's fields and the memory of a failed run; the switch stays as it is."));
        reset.setIcon(com.particlesdevs.photoncamera.R.drawable.settings_ic_reset);
        reset.setOrder(++order);
        reset.setOnPreferenceClickListener(p -> {
            FullResRecipeStore.resetToPreset(prefs, slot);
            if (refresh != null) refresh.run();
            return true;
        });
        com.particlesdevs.photoncamera.ui.settings.SettingsStyle.style(reset, category); // a row built later takes its layout before the list sees it
        category.addPreference(reset);
    }

    static boolean lists(String field) {
        return FullResRecipe.TEMPLATE.equals(field) || FullResRecipe.SESSION.equals(field) || FullResRecipe.PIXEL_MODE.equals(field)
                || FullResRecipe.PREVIEW.equals(field);
    }

    static String[] values(String field) {
        if (FullResRecipe.PIXEL_MODE.equals(field)) return new String[]{"never", "annotate"};
        if (FullResRecipe.PREVIEW.equals(field)) return new String[]{"isp", "raw", "both"};
        return FullResRecipe.TEMPLATES.clone();
    }

    /** A field with a fixed set of values: the stored one, else the profile's ({@code shown}). */
    private static Preference listRow(Context context, String slot, String field, String shown) {
        final ListPreference list = new ListPreference(context) {
            @Override public String getValue() {
                final String v = super.getValue();
                return v == null ? shown : v;
            }
        };
        list.setKey(key(slot, field));
        list.setTitle(fieldTitle(context, field));
        list.setDialogTitle(fieldTitle(context, field));
        list.setEntries(values(field));
        list.setEntryValues(values(field));
        list.setSummaryProvider(p -> list.getValue());
        SettingHelp.summaryIsState(list);
        return list;
    }

    /** A field typed as text: the stored one, else the profile's ({@code shown}). */
    private static Preference textRow(Context context, String slot, String field, String shown) {
        final EditTextPreference text = new EditTextPreference(context) {
            @Override public String getText() {
                final String v = super.getText();
                return v == null ? shown : v;
            }
        };
        text.setKey(key(slot, field));
        text.setTitle(fieldTitle(context, field));
        text.setDialogTitle(fieldTitle(context, field));
        final boolean tags = FullResRecipe.TAGS.equals(field);
        text.setOnBindEditTextListener(edit -> {
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | (tags ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
            edit.setSingleLine(!tags);
        });
        text.setSummaryProvider(p -> {
            final String v = text.getText();
            return v == null || v.isEmpty() ? Lang.t(context, "не задано", "not set") : v.replace("\n", "\n");
        });
        SettingHelp.summaryIsState(text);
        return text;
    }
}
