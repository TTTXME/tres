package com.scamera.app.settings;

import android.content.SharedPreferences;

/**
 * Wipes the stored settings once when their layout version is older than {@link #LAYOUT}; bump {@link #LAYOUT} only when a
 * release cannot read the old settings at all. The version lives in its own scope file (named after the key), as it always has.
 */
public final class MigrationManager {
    private static final int LAYOUT = 4;
    /** Set after a wipe: the activities then re-apply the XML defaults over the empty store. */
    public static boolean readAgain;

    private MigrationManager() {}

    public static void migrate(SettingsManager manager) {
        PreferenceKeys.Key versionKey = PreferenceKeys.Key.KEY_PREF_VERSION;
        String versionScope = versionKey.mValue;
        if (manager.getInteger(versionScope, versionKey, 1) >= LAYOUT) return;
        SharedPreferences[] files = {
                manager.getDefaultPreferences(),
                manager.openPreferences(PreferenceKeys.Key.PER_LENS_FILE_NAME.mValue),
                manager.openPreferences(PreferenceKeys.Key.CAMERAS_PREFERENCE_FILE_NAME.mValue),
        };
        for (SharedPreferences f : files) f.edit().clear().apply();
        manager.set(versionScope, versionKey, LAYOUT);
        readAgain = true;
        // A fresh install has no stored version either, and SettingsManager already wrote the one-time markers into the store
        // cleared above: write them again (on the empty store the migrations write only their markers and the device defaults).
        manager.runOneTimeMigrations();
    }
}
