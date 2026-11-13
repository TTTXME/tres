package com.scamera.app.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.scamera.app.util.Log;

import java.io.File;
import java.util.Map;

/** The {@code @Tunable} classes of {@link TunableRegistry} and the import of their values from an old JSON backup. */
public final class TunableSettingsManager {
    private static final String TAG = "TunableSettings";
    private static volatile boolean ready;

    private TunableSettingsManager() {}

    /** Kept for the callers that make sure the classes are known before reading or writing their settings. */
    public static void ensureTunableClassesRegistered() {
        ready = true;
    }

    private static Class<?> classNamed(String simpleName) {
        if (!ready) return null; // as before: an import without the registration imports nothing
        for (Class<?> c : TunableRegistry.OWNERS) if (c.getSimpleName().equals(simpleName)) return c;
        return null;
    }

    private static FieldSpec fieldOf(Class<?> c, String name) {
        for (FieldSpec s : FieldSpec.tunables(c)) if (s.name.equals(name)) return s;
        return null;
    }

    /**
     * Writes backup entries {@code "Class.field" -> value} under the fields' settings keys in their native types: text for
     * File and String fields (empty values are skipped), a float for float/double fields, an int otherwise. Entries of an
     * unknown class or of a field without {@code @Tunable} are left out.
     */
    public static void importTunableSettings(Context context, Map<String, Object> values) {
        if (values == null || values.isEmpty()) return;
        SharedPreferences.Editor edit = PreferenceManager.getDefaultSharedPreferences(context).edit();
        int imported = 0;
        for (Map.Entry<String, Object> e : values.entrySet()) {
            String[] classAndField = e.getKey().split("\\.");
            if (classAndField.length != 2) {
                Log.w(TAG, "not a Class.field entry: " + e.getKey());
                continue;
            }
            Class<?> owner = classNamed(classAndField[0]);
            FieldSpec s = owner == null ? null : fieldOf(owner, classAndField[1]);
            if (s == null) continue;
            String key = TunableInjector.key(classAndField[0], classAndField[1]);
            Object v = e.getValue();
            if (s.type == File.class || s.type == String.class) {
                String text = v == null ? "" : v.toString();
                if (!text.isEmpty()) edit.putString(key, text);
            } else {
                // an unreadable number takes the declared default; without one it is not imported (it was stored as -999999)
                final double n = PreferenceNumber.read(v, s.declared);
                if (Double.isNaN(n)) continue;
                if (s.isFloating()) edit.putFloat(key, (float) n);
                else edit.putInt(key, (int) n);
            }
            imported++;
        }
        edit.apply();
        Log.d(TAG, "imported " + imported + " tunable values");
    }
}
