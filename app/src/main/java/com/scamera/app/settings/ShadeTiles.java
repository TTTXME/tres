package com.scamera.app.settings;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * The shade's pinned tiles: an ordered list of preference keys, comma separated, in {@link #KEY} of the main settings.
 * A "ui_" key on purpose: every "pref_" key is per module when per-lens settings are on (ModuleProfiles.isLocal), and the
 * tiles must not change with the lens. Being in the main settings, the list travels in the config file's main part.
 * <p>
 * Nothing stored yet: {@link ShadeCatalog#defaultTiles()}. An empty string is a valid choice (every tile removed). Keys the
 * catalog does not know on this phone and repeats are dropped on read (P167, owner 2026-10-16: «убери лимит на 12 плиток в
 * шторке и дай возможность закреплять столько сколько я хочу»: no limit);
 * keys of removed settings are also dropped from the stored value by SettingsMigration.removeObsolete.
 * <p>
 * P606 (owner, 2026-11-10: «дай возможность добавлять настройки из линзы в шторку, в шторке можно менять их только на линзе для
 * которой ты добавил»): a setting of a lens page is pinned as {@link #lensToken} - «lens:&lt;profile&gt;:&lt;key&gt;», the module
 * profile (ModuleRegistry slot, or its FULLRES profile) whose page pinned it and the key that page writes. The lens's name is kept
 * beside the list ({@link #LENS_NAME} + profile) for a lens deleted since.
 */
public final class ShadeTiles {
    public static final String KEY = "ui_shade_tiles";
    /** P606: the start of a lens setting's pin. */
    public static final String LENS_PREFIX = "lens:";
    /** P606: the last name a pinned lens had (+ its profile id), shown while that lens is gone. */
    public static final String LENS_NAME = "ui_shade_lens_name_";

    private ShadeTiles() {}

    /** The stored list as written, or null when nothing was ever stored. */
    @Nullable
    public static List<String> stored(SharedPreferences prefs) {
        Object raw = PreferenceValue.get(prefs, KEY);
        return raw == null ? null : split(raw.toString());
    }

    /** The tiles to show: the stored keys (or the defaults) that {@code known} accepts, without repeats. */
    public static List<String> load(SharedPreferences prefs, Predicate<String> known) {
        List<String> stored = stored(prefs);
        return clean(stored == null ? ShadeCatalog.defaultTiles() : stored, known);
    }

    /** Known keys in order, without repeats. */
    public static List<String> clean(Collection<String> keys, Predicate<String> known) {
        List<String> out = new ArrayList<>();
        for (String key : keys) {
            if (key == null || key.isEmpty() || out.contains(key) || !known.test(key)) continue;
            out.add(key);
        }
        return out;
    }

    /** P137: whether the setting is in the shade (the stored list, or the default tiles before anything was stored). */
    public static boolean isPinned(SharedPreferences prefs, String key) {
        List<String> keys = stored(prefs);
        return (keys == null ? ShadeCatalog.defaultTiles() : keys).contains(key);
    }

    /**
     * P137: «В шторку» of a value editor in the settings: pins the setting at the end of the shade or takes it out. True when
     * it is pinned now, false when it was taken out (P167: the shade has no limit).
     */
    public static boolean toggle(SharedPreferences prefs, String key) {
        List<String> stored = stored(prefs);
        List<String> keys = new ArrayList<>(stored == null ? ShadeCatalog.defaultTiles() : stored);
        if (keys.remove(key)) {
            save(prefs, keys);
            return false;
        }
        keys.add(key);
        save(prefs, keys);
        return true;
    }

    /** P606: the pin of {@code key} of the lens page of module profile {@code profile}. */
    public static String lensToken(String profile, String key) {
        return LENS_PREFIX + profile + ":" + key;
    }

    /** P606: {@code tile} is a lens setting's pin. */
    public static boolean isLensToken(@Nullable String tile) {
        return tile != null && tile.startsWith(LENS_PREFIX) && tile.indexOf(':', LENS_PREFIX.length()) > LENS_PREFIX.length()
                && tile.indexOf(':', LENS_PREFIX.length()) < tile.length() - 1;
    }

    /** P606: the module profile of a lens pin; null for a plain tile. */
    @Nullable
    public static String lensOf(@Nullable String tile) {
        if (!isLensToken(tile)) return null;
        return tile.substring(LENS_PREFIX.length(), tile.indexOf(':', LENS_PREFIX.length()));
    }

    /** P606: the preference key a tile reads and writes (a plain tile is its key). */
    public static String keyOf(String tile) {
        return isLensToken(tile) ? tile.substring(tile.indexOf(':', LENS_PREFIX.length()) + 1) : tile;
    }

    /** P606: remembers the lens's name for its pins (shown when the lens is gone). */
    public static void rememberLens(SharedPreferences prefs, String profile, String name) {
        if (name == null || name.isEmpty() || name.equals(prefs.getString(LENS_NAME + profile, null))) return;
        prefs.edit().putString(LENS_NAME + profile, name).apply();
    }

    /** P606: the lens's last known name, else its profile id. */
    public static String lensName(SharedPreferences prefs, String profile) {
        String name = prefs.getString(LENS_NAME + profile, null);
        return name == null || name.isEmpty() ? profile : name;
    }

    public static void save(SharedPreferences prefs, List<String> keys) {
        prefs.edit().putString(KEY, join(keys)).apply();
    }

    static String join(List<String> keys) {
        return String.join(",", keys);
    }

    static List<String> split(String value) {
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String key = part.trim();
            if (!key.isEmpty()) out.add(key);
        }
        return out;
    }
}
