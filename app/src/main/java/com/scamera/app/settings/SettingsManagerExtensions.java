package com.scamera.app.settings;

import android.content.SharedPreferences;

/**
 * Native-typed reads and writes for keys built at run time (sensor and tunable rows). Only the global scope holds such
 * keys; any other scope reads the fallback and ignores writes.
 */
public final class SettingsManagerExtensions {
    private SettingsManagerExtensions() {}

    /** The global file, or null for any other scope. */
    private static SharedPreferences global(SettingsManager manager, String area) {
        return SettingsManager.SCOPE_GLOBAL.equals(area) ? manager.getDefaultPreferences() : null;
    }

    private static Object stored(SettingsManager manager, String area, String id) {
        SharedPreferences p = global(manager, area);
        return p == null ? null : PreferenceValue.get(p, id);
    }

    public static Float getFloat(SettingsManager manager, String area, String id, Float fallback) {
        return global(manager, area) == null ? fallback : (float) PreferenceNumber.read(stored(manager, area, id), fallback);
    }

    public static Integer getInteger(SettingsManager manager, String area, String id, Integer fallback) {
        return global(manager, area) == null ? fallback : (int) PreferenceNumber.read(stored(manager, area, id), fallback);
    }

    public static boolean getBoolean(SettingsManager manager, String area, String id, boolean fallback) {
        return global(manager, area) == null ? fallback : PreferenceNumber.bool(stored(manager, area, id), fallback);
    }

    /** Text of a key; a number or flag a seek bar or switch stored natively reads as its text form. */
    public static String getString(SettingsManager manager, String area, String id, String fallback) {
        Object v = stored(manager, area, id);
        return v instanceof String || v instanceof Number || v instanceof Boolean ? String.valueOf(v) : fallback;
    }

    public static void setFloat(SettingsManager manager, String area, String id, float v) {
        SharedPreferences p = global(manager, area);
        if (p != null) p.edit().putFloat(id, v).apply();
    }

    public static void setInt(SettingsManager manager, String area, String id, int v) {
        SharedPreferences p = global(manager, area);
        if (p != null) p.edit().putInt(id, v).apply();
    }

    public static void setBoolean(SettingsManager manager, String area, String id, boolean v) {
        SharedPreferences p = global(manager, area);
        if (p != null) p.edit().putBoolean(id, v).apply();
    }
}
