package com.scamera.app.settings;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

/**
 * P680 (performance audit: the viewfinder's result callback read settings from SharedPreferences on every frame): the settings a
 * running session reads per frame, read once and kept until any setting of the main preferences changes (a listener counts the
 * changes) - so a snapshot always answers what the settings say now, without a preferences read per frame.
 */
public final class SessionPrefs {
    private static final java.util.concurrent.atomic.AtomicInteger CHANGES = new java.util.concurrent.atomic.AtomicInteger();
    /** Held here: SharedPreferences keeps its listeners weakly. */
    private static final SharedPreferences.OnSharedPreferenceChangeListener COUNT = (prefs, key) -> CHANGES.incrementAndGet();
    private static SharedPreferences watched;

    /** «Автофокус» (PreferenceKeys.getAfMode). */
    public final int afMode;
    /** The change count the snapshot was read at. */
    private final int changes;

    SessionPrefs(int afMode, int changes) {
        this.afMode = afMode;
        this.changes = changes;
    }

    /** The settings now (and the main preferences watched from now on). */
    public static SessionPrefs read() {
        final int at = watch();
        return new SessionPrefs(PreferenceKeys.getAfMode(), at);
    }

    /** No setting changed since the snapshot was read. */
    public boolean current() {
        return changes == watch();
    }

    /** {@code kept} while it is current, else a new snapshot. */
    public static SessionPrefs refresh(@Nullable SessionPrefs kept) {
        return kept != null && kept.current() ? kept : read();
    }

    /** The change count; the listener is registered on the main preferences the settings use now (a new file counts as a change). */
    private static int watch() {
        final SettingsManager manager = PreferenceKeys.settingsStore();
        final SharedPreferences prefs = manager == null ? null : manager.getDefaultPreferences();
        synchronized (SessionPrefs.class) {
            if (prefs != watched) {
                if (watched != null) watched.unregisterOnSharedPreferenceChangeListener(COUNT);
                if (prefs != null) prefs.registerOnSharedPreferenceChangeListener(COUNT);
                watched = prefs;
                CHANGES.incrementAndGet();
            }
        }
        return CHANGES.get();
    }

    /** Tests: a change of the preferences (as the listener counts one). */
    static void changedForTest() {
        CHANGES.incrementAndGet();
    }

    @Override public boolean equals(Object o) {
        return o instanceof SessionPrefs && ((SessionPrefs) o).afMode == afMode;
    }

    @Override public int hashCode() {
        return afMode;
    }
}
