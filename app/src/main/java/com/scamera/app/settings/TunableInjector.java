package com.scamera.app.settings;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.util.Log;

import java.util.List;
import java.util.Locale;

/** Fills an object's {@code @Tunable} fields from the settings ({@code pref_tunable_<class>_<field>}). */
public final class TunableInjector {
    private static final String TAG = "TunableInjector";

    private TunableInjector() {}

    /** The settings key of a field of a class (both by simple name, lower case). */
    static String key(String className, String fieldName) {
        return "pref_tunable_" + className.toLowerCase(Locale.ROOT) + "_" + fieldName.toLowerCase(Locale.ROOT);
    }

    /**
     * Writes the stored values into the fields {@code target}'s own class declares. Called by every GL node's constructor:
     * a class without such fields returns at once from the cached (empty) field list.
     */
    public static void inject(Object target) {
        if (target == null) return;
        Class<?> type = target.getClass();
        List<FieldSpec> fields = FieldSpec.tunables(type);
        if (fields.isEmpty()) return;
        SettingsManager m = ScameraApp.getSettingsManagerStatic();
        if (m == null) {
            Log.w(TAG, "no settings yet, " + type.getSimpleName() + " keeps its values");
            return;
        }
        StringBuilder done = new StringBuilder(type.getSimpleName()).append(':');
        for (FieldSpec s : fields) {
            String key = key(type.getSimpleName(), s.name);
            try {
                Object v = FieldInjection.write(m, target, s, key, false);
                if (v == null) Log.w(TAG, "unsupported field " + s.name + " (" + s.type + ")");
                else done.append(' ').append(key).append('=').append(v);
            } catch (Exception e) {
                Log.e(TAG, "could not set " + s.name, e);
            }
        }
        Log.d(TAG, done.toString());
    }
}
