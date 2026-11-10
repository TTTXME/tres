package com.particlesdevs.photoncamera.settings;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceCategory;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.util.Lang;

public final class UnspektraAePreferences {
    public static final String ENGINE = "ae_engine", METHOD = "ae_method", MIN_ISO = "ae_min_iso",
            MAX_ISO = "ae_max_iso", FAST = "ae_fast_den", SLOW = "ae_slow_den";
    public static final String[] KEYS = {ENGINE, METHOD, MIN_ISO, MAX_ISO, FAST, SLOW};
    private UnspektraAePreferences() {}

    public static double value(SharedPreferences prefs, String prefix, String key, double fallback) {
        return PreferenceNumber.read(PreferenceValue.get(prefs, prefix + key), fallback);
    }
    public static String currentPrefix(String camera) {
        return ModuleSensorSettings.prefix(ModuleSensorSettings.runtimeScope(camera));
    }
    public static boolean selected(String camera) {
        if (camera == null) return false;
        SharedPreferences prefs = ScameraApp.getSettingsManagerStatic().getDefaultPreferences();
        return value(prefs, currentPrefix(camera), ENGINE, 0) == 1;
    }
    public static void addRows(Context context, PreferenceCategory category, String slot, Runnable changed) {
        String prefix = ModuleSensorSettings.prefix(slot);
        if (category.findPreference(prefix + ENGINE) != null) return;
        ListPreference engine = new ListPreference(context);
        engine.setKey(prefix + ENGINE); engine.setDefaultValue("0");
        engine.setTitle(Lang.t("Автоэкспозиция", "Auto exposure"));   // P592: the restart is said in its long press
        com.particlesdevs.photoncamera.ui.settings.SettingHelp.describe(engine, Lang.t("Кто выбирает выдержку и ISO для этого модуля. Android AE — автоэкспозиция камеры. Unspektra AE считает экспозицию в приложении: её замер и пределы ISO и выдержки — в пунктах ниже.",
                "Who picks the shutter and ISO for this module. Android AE is the camera's own auto exposure. Unspektra AE computes the exposure in the app: its metering and its ISO and shutter limits are in the rows below."));
        engine.setEntries(new String[]{"Android AE", "Unspektra AE"});
        engine.setEntryValues(new String[]{"0", "1"}); engine.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        engine.setOrder(50); category.addPreference(engine);
        ListPreference method = new ListPreference(context);
        method.setKey(prefix + METHOD); method.setDefaultValue("0");
        method.setTitle(Lang.t("Замер Unspektra AE", "Unspektra AE metering"));
        com.particlesdevs.photoncamera.ui.settings.SettingHelp.describe(method, Lang.t("По какой части кадра Unspektra AE меряет яркость. Центровзвешенный отдаёт приоритет середине кадра, медиана — середине всех яркостей кадра, без перевеса светлых и тёмных мест.",
                "Which part of the frame Unspektra AE measures the brightness on. Centre weighted favours the middle of the frame, median takes the middle of all the frame\'s brightness, without bright or dark places weighing more."));
        method.setEntries(new String[]{Lang.t("Центровзвешенный", "Centre weighted"), Lang.t("Медиана", "Median")});
        method.setEntryValues(new String[]{"0", "1"}); method.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance()); method.setOrder(51); category.addPreference(method);
        method.setEnabled("1".equals(engine.getValue()));
        EditTextPreference[] fields = new EditTextPreference[4];
        String[] keys = {MIN_ISO, MAX_ISO, FAST, SLOW};
        String[] titles = {Lang.t("Минимальный ISO AE", "AE minimum ISO"), Lang.t("Максимальный ISO AE", "AE maximum ISO"),
                Lang.t("Короткая выдержка AE, 1/с", "AE fastest shutter, 1/s"), Lang.t("Длинная выдержка AE, 1/с", "AE slowest shutter, 1/s")};
        for (int i = 0; i < fields.length; i++) {
            final int field = i;
            EditTextPreference row = new EditTextPreference(context); fields[i] = row;
            row.setOrder(52 + i);
            row.setKey(prefix + keys[i]); row.setTitle(titles[i]); row.setDefaultValue(i == 3 ? "30" : "0");
            com.particlesdevs.photoncamera.ui.settings.SettingHelp.describe(row, Lang.t("Предел, за который Unspektra AE не выходит, выбирая экспозицию. 0 — весь диапазон камеры. Полностью ручную съёмку эти пределы не ограничивают.",
                    "The limit Unspektra AE stays within when it picks the exposure. 0 is the camera\'s whole range. These limits do not restrict fully manual shooting."));
            row.setDialogMessage(Lang.t("0 — диапазон камеры. Ограничения не сужают полностью ручное управление.",
                    "0: camera range. These limits do not restrict fully manual control."));
            row.setSummaryProvider(EditTextPreference.SimpleSummaryProvider.getInstance());
            row.setOnBindEditTextListener(input -> input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL));
            row.setOnPreferenceChangeListener((pref, next) -> {
                try { double number = Double.parseDouble(String.valueOf(next));
                    if (!Double.isFinite(number) || number < 0) return false;
                    int paired = field ^ 1;
                    double other = PreferenceNumber.read(fields[paired].getText(), paired == 3 ? 30 : 0);
                    if (number > 0 && other > 0 && (field == 0 ? number > other : field == 1 ? number < other
                            : field == 2 ? number < other : number > other)) return false;
                } catch (NumberFormatException error) { return false; }
                if (changed != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(changed); return true;
            });
            category.addPreference(row); row.setEnabled("1".equals(engine.getValue()));
        }
        engine.setOnPreferenceChangeListener((pref, next) -> {
            boolean on = "1".equals(String.valueOf(next)); method.setEnabled(on);
            for (EditTextPreference row : fields) row.setEnabled(on);
            if (changed != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(changed); return true;
        });
    }
}
