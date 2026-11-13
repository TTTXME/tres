package com.scamera.app.ui.settings;

import android.content.Context;
import android.text.InputType;

import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.scamera.app.settings.DevKeys;
import com.scamera.app.settings.ScamDevFile;
import com.scamera.app.util.Lang;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The «Для разработчика» page (owner, 2026-10-13): one row per hidden scam_dev.txt line of {@link DevKeys}, grouped. Rows
 * are not stored as preferences: a switch writes "key 1" / "key 0", a number "key value" (empty = the line is removed and
 * the code's default applies) straight into scam_dev.txt; the summary says whether the file sets the line.
 */
final class DeveloperPreferences {
    static final String SCREEN = "developer_settings_screen";

    private DeveloperPreferences() {}

    static void populate(PreferenceScreen root, Context context) {
        populate(root, context, null);
    }

    /**
     * P475 (owner, 2026-11-03: «в дев настройках у нас невозможно поменять ни один параметр»): a scam_dev.txt the app did not create
     * (a PC, adb or the package before com.scamera.app made it: on Android 11+ the app can neither replace nor delete it) is only
     * written through the folder grant, and without one every row silently stayed as it was. A refused write now says so and
     * {@code askGrant} opens the folder picker (Download/SCAMERA); null: the message only.
     */
    static void populate(PreferenceScreen root, Context context, Runnable askGrant) {
        PreferenceScreen screen = root == null ? null : root.findPreference(SCREEN);
        if (screen == null || context == null) return;
        screen.removeAll();
        final Context app = context.getApplicationContext();
        // P191: the file is Download/SCAMERA/scam_dev.txt (no longer Android/data); the short path the owner opens
        String path = ScamDevFile.SHOWN;
        Preference note = new Preference(context);
        note.setKey("dev_note");
        note.setSelectable(false);
        note.setIconSpaceReserved(false);
        note.setSummary(Lang.t(context,
                "Скрытые переключатели для опытов и отладки. Значения пишутся в файл " + path
                        + ". «по умолчанию» — строки в файле нет, работает значение из кода.",
                "Hidden switches for experiments and debugging. Values are written to " + path
                        + ". «default»: no line in the file, the code's value applies."));
        screen.addPreference(note);
        // P592: the note has no title, so it is no row any more (SettingsStyle.emptyNote); its text is the page's long press
        SettingHelp.describe(screen, note.getSummary());
        Preference reset = new Preference(context);
        reset.setKey("dev_reset");
        reset.setIconSpaceReserved(false);
        reset.setTitle(Lang.t(context, "Сбросить всё по умолчанию", "Reset everything to default"));
        reset.setSummary(Lang.t(context, "Удаляет из scam_dev.txt строки этой страницы", "Removes this page's lines from scam_dev.txt"));
        reset.setOnPreferenceClickListener(p -> {
            if (!ScamDevFile.removeAll(app, DevKeys.keys())) refused(context, askGrant);
            populate(root, context, askGrant);
            return true;
        });
        screen.addPreference(reset);
        Map<String, PreferenceCategory> groups = new LinkedHashMap<>();
        // P303: the file read once for the page (it was once per key)
        final Map<String, String> values = ScamDevFile.snapshot(app);
        for (DevKeys.Key k : DevKeys.all()) {
            PreferenceCategory group = groups.get(k.group);
            if (group == null) {
                group = new PreferenceCategory(context);
                group.setKey("dev_group_" + groups.size());
                group.setTitle(k.group);
                group.setIconSpaceReserved(false);
                screen.addPreference(group);
                groups.put(k.group, group);
            }
            group.addPreference(k.kind == DevKeys.Kind.SWITCH ? switchRow(context, app, k, values.get(k.key), askGrant)
                    : numberRow(context, app, k, values.get(k.key), askGrant));
        }
    }

    /**
     * The page reset of this page (audit 2026-10-27: its rows are not stored as preferences, so it changed nothing): every line of
     * this page out of scam_dev.txt, as its own «Сбросить всё по умолчанию» row does, and the rows rebuilt; the lines removed.
     */
    static int resetAll(PreferenceScreen root, Context context) {
        return resetAll(root, context, null);
    }

    static int resetAll(PreferenceScreen root, Context context, Runnable askGrant) {
        if (root == null || context == null) return 0;
        final Context app = context.getApplicationContext();
        final Map<String, String> values = ScamDevFile.snapshot(app);
        int set = 0;
        for (String key : DevKeys.keys()) if (values.containsKey(key)) set++;
        if (set > 0 && !ScamDevFile.removeAll(app, DevKeys.keys())) {
            set = 0;
            refused(context, askGrant);
        }
        populate(root, context, askGrant);
        return set;
    }

    /** P475: the write was refused (see populate): the message and the folder picker. */
    private static void refused(Context context, Runnable askGrant) {
        android.widget.Toast.makeText(context, Lang.t(context,
                "Нет доступа к " + ScamDevFile.SHOWN + ": файл создан не приложением (ПК, adb или прежняя установка). Разрешите «Доступ ко всем файлам»",
                "No access to " + ScamDevFile.SHOWN + ": the file was not made by the app (a PC, adb or an earlier install). Allow “All files access”"),
                android.widget.Toast.LENGTH_LONG).show();
        if (askGrant != null) askGrant.run();
    }

    private static Preference switchRow(Context context, Context app, DevKeys.Key k, String cur, Runnable askGrant) {
        SwitchPreferenceCompat p = new SwitchPreferenceCompat(context);
        p.setKey("dev_" + k.key);
        p.setPersistent(false);
        p.setIconSpaceReserved(false);
        p.setTitle(k.key);
        p.setChecked(cur != null ? number(cur) != 0f : k.def != null && k.switchDefault());
        texts(context, p, k, cur);
        p.setOnPreferenceChangeListener((pref, value) -> {
            String v = Boolean.TRUE.equals(value) ? "1" : "0";
            boolean ok = ScamDevFile.set(app, k.key, v);
            if (ok) texts(context, pref, k, v);
            else refused(context, askGrant);
            return ok;
        });
        return p;
    }

    private static Preference numberRow(Context context, Context app, DevKeys.Key k, String cur, Runnable askGrant) {
        EditTextPreference p = new EditTextPreference(context);
        p.setKey("dev_" + k.key);
        p.setPersistent(false);
        p.setIconSpaceReserved(false);
        p.setTitle(k.key);
        p.setDialogTitle(k.key);
        p.setDialogMessage(k.text + "\n" + Lang.t(context, "Пусто — по умолчанию", "Empty: default")
                + (k.def != null ? " (" + k.def + ")" : ""));
        p.setText(cur == null ? "" : cur);
        texts(context, p, k, cur);
        p.setOnBindEditTextListener(input -> input.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL));
        p.setOnPreferenceChangeListener((pref, value) -> {
            String text = value == null ? "" : value.toString().trim().replace(',', '.');
            String v = null;
            if (!text.isEmpty()) {
                try {
                    v = ScamDevFile.number(Float.parseFloat(text));
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            boolean ok = ScamDevFile.set(app, k.key, v);
            if (ok) {
                ((EditTextPreference) pref).setText(v == null ? "" : v);
                texts(context, pref, k, v);
            } else refused(context, askGrant);
            return false; // the text is set above (not stored as a preference)
        });
        return p;
    }

    /**
     * P592: a line's row - under its name only its state («задано: 1», «по умолчанию: 0»: whether the file sets the line); what
     * the line does ({@code k.text}) is the row's description, the long press.
     */
    private static void texts(Context context, Preference p, DevKeys.Key k, String cur) {
        SettingHelp.state(p, state(context, k, cur));
        p.setSummary(summary(context, k, cur));
    }

    private static String state(Context context, DevKeys.Key k, String cur) {
        if (cur != null) return Lang.t(context, "задано: ", "set: ") + cur;
        if (k.def != null) return Lang.t(context, "по умолчанию: ", "default: ") + k.def;
        return Lang.t(context, "по умолчанию (решает приложение)", "default (chosen by the app)");
    }

    private static String summary(Context context, DevKeys.Key k, String cur) {
        return k.text + "\n" + state(context, k, cur);
    }

    private static float number(String v) {
        try {
            return Float.parseFloat(v);
        } catch (NumberFormatException e) {
            return 0f;
        }
    }
}
