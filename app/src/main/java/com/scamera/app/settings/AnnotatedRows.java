package com.scamera.app.settings;

import android.content.Context;
import android.text.InputType;
import android.widget.Toast;

import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceGroup;

import com.scamera.app.ui.settings.SettingHelp;
import com.scamera.app.util.Lang;

/** The pieces the {@code @Tunable} and {@code @SensorConfig} settings rows share. */
final class AnnotatedRows {
    private AnnotatedRows() {}

    /** The keyboard of a text row for the field's type. */
    static int keyboard(FieldSpec s) {
        if (s.isFloating()) return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        if (s.isWholeNumber()) return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED;
        return InputType.TYPE_CLASS_TEXT;
    }

    /** The default as a text row shows it: a decimal for float fields, whole otherwise. */
    static String defaultText(FieldSpec s) {
        float d = s.fallback();
        return s.isFloating() ? String.valueOf(d) : String.valueOf((int) d);
    }

    /**
     * The common part of a text row: titles, keyboard, the default in an empty field, and the number check (in the range,
     * whole for whole-number fields; a String field takes any text). The caller sets its summary.
     */
    static void setUpText(Context context, EditTextPreference row, String key, String title, FieldSpec s) {
        row.setKey(key);
        row.setTitle(title);
        row.setDialogTitle(title);
        row.setIconSpaceReserved(false);
        final int keys = keyboard(s);
        final String shown = defaultText(s);
        row.setOnBindEditTextListener(edit -> {
            edit.setInputType(keys);
            if (row.getText() == null) edit.setText(shown);
        });
        row.setOnPreferenceChangeListener((p, input) -> {
            if (s.type == String.class) return true;
            double n = PreferenceNumber.read(input, Double.NaN);
            boolean ok = Double.isFinite(n) && n >= s.min && n <= s.max && (s.isFloating() || n == Math.rint(n));
            if (!ok) Toast.makeText(context, Lang.t(context, "Число от " + s.min + " до " + s.max,
                    "Number from " + s.min + " to " + s.max), Toast.LENGTH_LONG).show();
            return ok;
        });
    }

    /**
     * The stored value a list row starts with: the entry value equal to the declared default as a number; with
     * {@code byIndex}, else the entry at that index; else the first entry.
     */
    static String listDefault(FieldSpec s, boolean byIndex) {
        String[] values = s.entryValues;
        if (s.hasDeclaredDefault()) {
            for (String v : values) {
                try {
                    if (Float.parseFloat(v) == s.declared) return v;
                } catch (NumberFormatException notANumber) {
                    // a text value never equals a number
                }
            }
            if (byIndex && s.declared >= 0 && s.declared < values.length) return values[(int) s.declared];
        }
        return values[0];
    }

    /**
     * Adds a list row; its line is the chosen entry, the description is its long press. A key new to an installed app gets
     * its value written here: addPreference() stores a default only on the first run, and a row without a value crashed
     * SimpleSummaryProvider when bound.
     */
    static ListPreference addList(Context context, PreferenceGroup group, String key, String title, String description,
                                  FieldSpec s, String start, Integer icon) {
        ListPreference row = new ListPreference(context);
        row.setKey(key);
        row.setTitle(title);
        row.setDialogTitle(title);
        row.setEntries(s.entries);
        row.setEntryValues(s.entryValues);
        row.setDefaultValue(start);
        row.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        if (!description.isEmpty()) SettingHelp.describe(row, description); // P592
        if (icon != null) row.setIcon(icon);
        group.addPreference(row);
        if (row.getValue() == null) row.setValue(start);
        return row;
    }
}
