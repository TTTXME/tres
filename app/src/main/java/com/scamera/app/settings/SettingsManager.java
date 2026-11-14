/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.scamera.app.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Typed access to the app's preference files.
 *
 * <p>Values written here are strings ("0"/"1" for flags, decimal text for numbers); reads also accept the native types a
 * preference widget may have stored (a switch writes a Boolean, a seek bar an int or float), see {@link PreferenceValue}.
 *
 * <p>A scope names a preference file: {@link #SCOPE_GLOBAL} is the default preferences, any other scope is the private file
 * {@code <package><scope>}. Only one non-global file is held at a time, and the registered listeners follow it.
 */
public class SettingsManager {
    public static final String SCOPE_GLOBAL = "default_scope";

    /** The default preferences (SCOPE_GLOBAL) and the application context the scoped files are opened with. */
    private final SharedPreferences global;
    private final Context appContext;
    /** Watchers as registered, and the preference-level callbacks wrapping them (same order). */
    private final List<OnSettingChangedListener> watchers = new ArrayList<>();
    private final List<SharedPreferences.OnSharedPreferenceChangeListener> bridges = new ArrayList<>();
    private SharedPreferences scoped;
    private String scopedName;

    public SettingsManager(Context host) {
        appContext = host;
        // P662 («app onCreate 49 ms (settings + migrations 44)»): the rename of every stored file and the obsolete-key pass run once
        // per build, not at every process start - stored settings get old names or obsolete keys only from an older build;
        // a restored backup and an applied module profile still go through BrandMigration.migrate / SettingsMigration.removeObsolete
        // themselves (BackupRestoreUtil, ModuleProfiles)
        final long start = System.nanoTime();
        final SharedPreferences marker = appContext.getSharedPreferences(MIGRATED_FILE, Context.MODE_PRIVATE);
        final int build = com.scamera.app.BuildConfig.VERSION_CODE;
        final boolean due = migrationsDue(marker, build);
        // P55: settings written before the SCAM rename get their new names before anything reads them.
        if (due) BrandMigration.migrateAll(appContext);
        global = PreferenceManager.getDefaultSharedPreferences(appContext);
        runOneTimeMigrations(due);
        if (due) marker.edit().putInt(MIGRATED_KEY, build).apply();
        com.scamera.app.util.Log.i("PERF", "settings migrations " + (due ? "ran for build " + build : "skipped (done for build " + build + ")")
                + " in " + (System.nanoTime() - start) / 1_000_000 + " ms");
    }

    /** P662: the file and key of the build whose start last ran the rename and the obsolete-key pass. */
    static final String MIGRATED_FILE = "scam_migrations", MIGRATED_KEY = "migrated_build";

    /** P662: the rename and the obsolete-key pass are due when this build has not run them yet (a new install or update). */
    static boolean migrationsDue(SharedPreferences marker, int build) {
        return marker.getInt(MIGRATED_KEY, Integer.MIN_VALUE) != build;
    }

    /**
     * The one-time migrations and their markers, the device defaults included. MigrationManager runs them again after its reset
     * of the preferences (a fresh install, the full reset): that reset cleared the markers written here, so the next start ran
     * the migrations on the user's values (the flicker list's «Авто» became «Выкл.»).
     */
    void runOneTimeMigrations() {
        runOneTimeMigrations(true);
    }

    /** P662: {@code obsoletePass} - SettingsMigration.removeObsolete too (once per build, {@link #migrationsDue}). */
    void runOneTimeMigrations(boolean obsoletePass) {
        SharedPreferences p = global;
        SettingsMigration.migrateMultiFrame(p);
        SettingsMigration.migrateAntibanding(p);
        DeviceDefaults.applyOnce(appContext, p);
        DeviceDefaults.unlockRawViewfinderOnce(appContext, p); // P705
        // After the device defaults: the SCAM Hybrid's own section takes over the SCAM HDR engine choice.
        SettingsMigration.migrateScamHybrid(appContext, p);
        // The old quick buttons and favourites become the shade's tiles before their keys become obsolete.
        SettingsMigration.migrateShadeTiles(p);
        SettingsMigration.migrateSimpleUiTiles(p);
        SettingsMigration.migrateOutputTile(p);
        SettingsMigration.migrateHdrTile(p); // P494
        SettingsMigration.migrateIspPreview(p); // P266
        SettingsMigration.migrateHdrPhoto(p); // P266
        SettingsMigration.migrateWatermark(p); // P628
        // After the route migration, which reads the former route switches before they become obsolete.
        if (obsoletePass) SettingsMigration.removeObsolete(appContext, p);
    }

    public Context getContext() {
        return appContext;
    }

    public SharedPreferences getDefaultPreferences() {
        return global;
    }

    static String asText(int val) {
        return String.valueOf(val);
    }

    static String asText(boolean val) {
        return val ? "1" : "0";
    }

    /** The private file of a scope, with every registered watcher attached. */
    protected SharedPreferences openPreferences(String area) {
        SharedPreferences file = appContext.getSharedPreferences(appContext.getPackageName() + area, Context.MODE_PRIVATE);
        for (SharedPreferences.OnSharedPreferenceChangeListener b : bridges) file.registerOnSharedPreferenceChangeListener(b);
        return file;
    }

    /** Detaches the registered watchers from a scope's file (the file itself needs no closing). */
    protected void closePreferences(SharedPreferences file) {
        for (SharedPreferences.OnSharedPreferenceChangeListener b : bridges) file.unregisterOnSharedPreferenceChangeListener(b);
    }

    /** Calls the watcher for every change in the global file and in the scoped file currently held; a second add is ignored. */
    public void addListener(OnSettingChangedListener watcher) {
        if (watcher == null) throw new IllegalArgumentException("OnSettingChangedListener cannot be null.");
        if (watchers.contains(watcher)) return;
        SharedPreferences.OnSharedPreferenceChangeListener bridge = (file, id) -> watcher.onSettingChanged(this, id);
        watchers.add(watcher);
        bridges.add(bridge);
        global.registerOnSharedPreferenceChangeListener(bridge);
        if (scoped != null) scoped.registerOnSharedPreferenceChangeListener(bridge);
    }

    /**
     * The file behind a scope. The scoped file is kept while the same scope is asked for again (the watchers stay attached),
     * and swapped, watchers moved, when another scope is asked for.
     */
    private SharedPreferences file(String area) {
        if (SCOPE_GLOBAL.equals(area)) return global;
        if (scoped != null && area.equals(scopedName)) return scoped;
        if (scoped != null) closePreferences(scoped);
        scoped = openPreferences(area);
        scopedName = area;
        return scoped;
    }

    private static Object raw(SharedPreferences file, String id) {
        return PreferenceValue.get(file, id);
    }

    // ---- defaults -------------------------------------------------------------------------------------------------

    /**
     * Defaults given in code (PreferenceKeys.setDefaults, the first scanned camera) for keys without an XML row. Shared by
     * every manager of the process, as they always were.
     */
    private static final Map<String, String> CODE_DEFAULTS = new ConcurrentHashMap<>();

    /** Sets (or, with null, drops) the code default of a key. */
    void setDefault(PreferenceKeys.Key id, String val) {
        if (val == null) CODE_DEFAULTS.remove(id.mValue);
        else CODE_DEFAULTS.put(id.mValue, val);
    }

    /** The code default, else the android:defaultValue of the key's row in res/xml/preferences.xml ({@link XmlDefaults}), else null. */
    public String getStringDefault(PreferenceKeys.Key id) {
        String coded = CODE_DEFAULTS.get(id.mValue);
        return coded != null ? coded : XmlDefaults.get(appContext, id.mValue);
    }

    private boolean flagDefault(PreferenceKeys.Key id) {
        return PreferenceNumber.bool(getStringDefault(id), false);
    }

    private double numberDefault(PreferenceKeys.Key id) {
        return PreferenceNumber.read(getStringDefault(id), 0);
    }

    // ---- reads ----------------------------------------------------------------------------------------------------

    public String getString(String area, String id, String fallback) {
        Object v = raw(file(area), id);
        if (v == null) return fallback;
        return v instanceof Boolean ? asText((Boolean) v) : v.toString();
    }

    public String getString(String area, PreferenceKeys.Key id, String fallback) {
        return getString(area, id.mValue, fallback);
    }

    public String getString(String area, PreferenceKeys.Key id) {
        return getString(area, id.mValue, getStringDefault(id));
    }

    public Set<String> getStringSet(String area, String id, Set<String> fallback) {
        return file(area).getStringSet(id, fallback);
    }

    public Set<String> getStringSet(String area, PreferenceKeys.Key id, Set<String> fallback) {
        return getStringSet(area, id.mValue, fallback);
    }

    public Integer getInteger(String area, PreferenceKeys.Key id, Integer fallback) {
        // Numbers stored as text go through their decimal form, as they were written.
        return (int) PreferenceNumber.read(getString(area, id.mValue, String.valueOf(fallback)), fallback);
    }

    public Integer getInteger(String area, PreferenceKeys.Key id) {
        return getInteger(area, id, (int) Math.round(numberDefault(id)));
    }

    public Float getFloat(String area, PreferenceKeys.Key id, Float fallback) {
        return (float) PreferenceNumber.read(getString(area, id.mValue, String.valueOf(fallback)), fallback);
    }

    public Float getFloat(String area, PreferenceKeys.Key id) {
        return getFloat(area, id, (float) numberDefault(id));
    }

    /**
     * Accepts both the "0"/"1" text this class writes and the real Boolean a SwitchPreferenceCompat writes: reading such a
     * key as a string threw ClassCastException and aborted the capture.
     */
    public boolean getBoolean(String area, String id, boolean fallback) {
        return PreferenceNumber.bool(raw(file(area), id), fallback);
    }

    public boolean getBoolean(String area, PreferenceKeys.Key id, boolean fallback) {
        return getBoolean(area, id.mValue, fallback);
    }

    public boolean getBoolean(String area, PreferenceKeys.Key id) {
        return getBoolean(area, id.mValue, flagDefault(id));
    }

    public boolean isSet(String area, String id) {
        return file(area).contains(id);
    }

    public boolean isSet(String area, PreferenceKeys.Key id) {
        return isSet(area, id.mValue);
    }

    // ---- writes ---------------------------------------------------------------------------------------------------

    public void set(String area, String id, String val) {
        file(area).edit().putString(id, val).apply();
    }

    public void set(String area, PreferenceKeys.Key id, String val) {
        set(area, id.mValue, val);
    }

    public void set(String area, PreferenceKeys.Key id, int val) {
        set(area, id.mValue, asText(val));
    }

    public void set(String area, String id, boolean val) {
        set(area, id, asText(val));
    }

    public void set(String area, PreferenceKeys.Key id, boolean val) {
        set(area, id.mValue, asText(val));
    }

    public void set(String area, String id, Set<String> val) {
        file(area).edit().putStringSet(id, val).apply();
    }

    public void set(String area, PreferenceKeys.Key id, Set<String> val) {
        set(area, id.mValue, val);
    }

    public void set(String area, String id, ArrayList<String> val) {
        set(area, id, (Set<String>) new HashSet<>(val));
    }

    public void setInitial(String area, String id, String val) {
        if (!isSet(area, id)) set(area, id, val);
    }

    public void setInitial(String area, PreferenceKeys.Key id, String val) {
        setInitial(area, id.mValue, val);
    }

    public void setInitial(String area, PreferenceKeys.Key id, int val) {
        setInitial(area, id.mValue, asText(val));
    }

    public void setInitial(String area, PreferenceKeys.Key id, boolean val) {
        setInitial(area, id.mValue, asText(val));
    }

    public void remove(String area, PreferenceKeys.Key id) {
        file(area).edit().remove(id.mValue).apply();
    }

    /** Told the key of every change in the files this manager watches. */
    public interface OnSettingChangedListener {
        void onSettingChanged(SettingsManager source, String changedKey);
    }
}
