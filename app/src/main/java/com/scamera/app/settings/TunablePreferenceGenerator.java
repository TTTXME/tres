package com.scamera.app.settings;

import android.content.Context;

import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;

import com.scamera.app.settings.annotations.Tunable;
import com.scamera.app.ui.settings.SettingHelp;
import com.scamera.app.ui.settings.custompreferences.TunableCheckBoxPreference;
import com.scamera.app.ui.settings.custompreferences.TunablePngPreference;
import com.scamera.app.ui.settings.custompreferences.TunableSeekBarPreference;
import com.scamera.app.util.Lang;
import com.scamera.app.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the settings rows of the registered {@link Tunable} fields, one category per annotation category on the class's
 * expert screen: a switch for a 0..1 whole-step number, a slider for other numbers, a text field for step 0, a list for a
 * String with entries, a PNG picker for a File.
 */
public final class TunablePreferenceGenerator {
    private static final String TAG = "TunableRows";
    /** Classes whose fields have their own XML rows. */
    private static final String HAND_MADE = "PostPipeline";
    private static final List<Class<?>> classes = new ArrayList<>();

    private TunablePreferenceGenerator() {}

    public static void registerTunableClass(Class<?> type) {
        if (!classes.contains(type)) classes.add(type);
    }

    /** One field with the class it belongs to. */
    private static final class Row {
        final String owner;
        final FieldSpec spec;

        Row(String owner, FieldSpec spec) {
            this.owner = owner;
            this.spec = spec;
        }
    }

    /** Adds the rows to their expert_* screens of the full settings tree (see {@link #screenOf}). */
    public static void generatePreferences(Context context, PreferenceScreen tree) {
        if (classes.isEmpty()) {
            Log.w(TAG, "no tunable classes registered, no rows");
            return;
        }
        try {
            for (Map.Entry<String, List<Row>> group : byCategory().entrySet()) {
                List<Row> rows = group.getValue();
                String screenKey = screenOf(rows.get(0).owner);
                PreferenceGroup screen = screenKey == null ? null : tree.findPreference(screenKey);
                if (screen == null) {
                    Log.e(TAG, "no screen for category " + group.getKey() + ", its rows are left out");
                    continue;
                }
                PreferenceCategory category = category(context, screen, group.getKey());
                for (Row r : rows) addRow(context, category, r);
            }
            // SIMPLE_UI §4 «Съёмка»: «Не зеркалить селфи» moved up from the RAW-metadata rows
            moveTo(tree, ShadeCatalog.MIRROR, "root_shoot");
        } catch (Exception e) {
            Log.e(TAG, "tunable rows failed", e);
        }
    }

    /** The fields by annotation category, categories in the order first met (classes in registration order). */
    private static Map<String, List<Row>> byCategory() {
        Map<String, List<Row>> out = new LinkedHashMap<>();
        for (Class<?> c : classes) {
            String owner = c.getSimpleName();
            if (HAND_MADE.equals(owner)) continue;
            for (FieldSpec s : FieldSpec.tunables(c))
                out.computeIfAbsent(s.category, k -> new ArrayList<>()).add(new Row(owner, s));
        }
        return out;
    }

    private static String screenOf(String owner) {
        return "Parameters".equals(owner) ? "expert_sensor_screen" : null;
    }

    /** A generated row moved to another group (its category left behind goes when it is empty). */
    static void moveTo(PreferenceScreen tree, String key, String groupKey) {
        Preference row = tree.findPreference(key);
        PreferenceGroup to = tree.findPreference(groupKey);
        if (row == null || to == null) return;
        PreferenceGroup from = row.getParent();
        if (from == to) return;
        if (from != null) from.removePreference(row);
        row.setOrder(Preference.DEFAULT_ORDER);
        to.addPreference(row);
        boolean emptyCategory = from instanceof PreferenceCategory && from.getPreferenceCount() == 0;
        if (emptyCategory && from.getParent() != null) from.getParent().removePreference(from);
    }

    /** The screen's category {@code pref_category_tunable_<name>} (created when missing). */
    private static PreferenceCategory category(Context context, PreferenceGroup screen, String name) {
        String key = "pref_category_tunable_" + name.toLowerCase(Locale.ROOT).replace(' ', '_');
        for (int i = 0, n = screen.getPreferenceCount(); i < n; i++) {
            Preference p = screen.getPreference(i);
            if (p instanceof PreferenceCategory && key.equals(p.getKey())) return (PreferenceCategory) p;
        }
        PreferenceCategory made = new PreferenceCategory(context);
        made.setKey(key);
        made.setTitle(name.isEmpty() ? Lang.t(context, "Параметры", "Parameters") : title(name));
        screen.addPreference(made);
        return made;
    }

    private static void addRow(Context context, PreferenceCategory category, Row r) {
        FieldSpec s = r.spec;
        String key = TunableInjector.key(r.owner, s.name);
        String title = title(s.title), text = description(s.description);
        if (s.type == String.class && s.isList()) {
            AnnotatedRows.addList(context, category, key, title, text, s, AnnotatedRows.listDefault(s, true), null);
        } else if (s.isText()) {
            category.addPreference(textRow(context, key, title, text, s));
        } else if (s.isFloating() || s.isFlag() || s.type == int.class || s.type == Integer.class) {
            category.addPreference(s.isSwitch() ? switchRow(context, key, title, text, s) : slider(context, key, title, text, s));
        } else if (s.type == File.class) {
            category.addPreference(pngRow(context, key, title, text, s));
        }
    }

    private static Preference switchRow(Context context, String key, String title, String text, FieldSpec s) {
        TunableCheckBoxPreference row = new TunableCheckBoxPreference(context);
        row.setKey(key);
        row.setTitle(title);
        if (!text.isEmpty()) row.setSummary(text);
        row.setDefaultValue(s.fallback() != 0f ? 1 : 0);
        return row;
    }

    private static Preference slider(Context context, String key, String title, String text, FieldSpec s) {
        TunableSeekBarPreference row = new TunableSeekBarPreference(context);
        row.setKey(key);
        row.setTitle(title);
        if (!text.isEmpty()) row.setSummary(text);
        row.setMinValue(s.min);
        row.setMaxValue(s.max);
        // The kind and the step first: the default's precision follows them.
        row.setIsFloat(s.isFloating());
        row.setStepPerUnit(1f / s.step);
        row.setDefaultValue(s.fallback());
        return row;
    }

    private static Preference textRow(Context context, String key, String title, String text, FieldSpec s) {
        EditTextPreference row = new EditTextPreference(context);
        AnnotatedRows.setUpText(context, row, key, title, s);
        if (!text.isEmpty()) SettingHelp.describe(row, text); // P592
        String shown = AnnotatedRows.defaultText(s);
        row.setSummaryProvider(p -> {
            String v = row.getText() == null ? shown : row.getText();
            return text.isEmpty() ? v : text + "\n" + v;
        });
        return row;
    }

    private static Preference pngRow(Context context, String key, String title, String text, FieldSpec s) {
        TunablePngPreference row = new TunablePngPreference(context);
        row.setKey(key);
        row.setTitle(title);
        row.setAllowedPngSizes(s.pngEdges);
        // P592: the row's line is its state (the chosen file), its description is the long press
        if (!text.isEmpty()) SettingHelp.describe(row, text);
        SettingHelp.summaryIsState(row);
        row.setSummary(Lang.t(context, "Не выбран", "None selected"));
        return row;
    }

    /**
     * A @Tunable title or category (the annotations hold English) in the UI language, as ModuleSensorSettings.title does for
     * the sensor rows; other texts as they are.
     */
    static String title(String text) {
        switch (text) {
            case "Parameters": return Lang.t("Параметры", "Parameters");
            case "Use Dynamic Black Level": return Lang.t("Динамический уровень чёрного", "Dynamic black level");
            case "Use Dynamic White Level": return Lang.t("Динамический уровень белого", "Dynamic white level");
            case "Disable front mirror": return Lang.t("Не зеркалить селфи", "Don’t mirror selfies");
            default: return text;
        }
    }

    /** English text of a @Tunable description (the annotations hold the Russian text); other texts as they are. */
    static String description(String text) {
        switch (text) {
            case "Уровень чёрного из метаданных каждого кадра (что сообщает камера), а не из её паспорта. Оценка по пикселям — «Уровень чёрного по кадру». На части телефонов работает нестабильно.": return Lang.t(text, "The black level from each frame's metadata (what the camera reports), not from its static data. The estimate from the pixels is «Black level from the frame». Unstable on some phones.");
            case "Уровень белого из метаданных каждого кадра (что сообщает камера), а не из её паспорта. На части телефонов работает нестабильно.": return Lang.t(text, "The white level from each frame's metadata (what the camera reports), not from its static data. Unstable on some phones.");
            case "Фото с фронтальной камеры сохраняются без зеркального отражения, как видит камера.": return Lang.t(text, "Front camera photos are saved without mirroring, as the camera sees them.");
            default: return text;
        }
    }
}
