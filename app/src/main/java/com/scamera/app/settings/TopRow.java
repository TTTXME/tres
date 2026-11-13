package com.scamera.app.settings;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * P574 (the approved concept, owner 2026-11-09: «Отлично, реализуем»): the camera screen's top row - its items in order, a comma
 * list of item ids in {@link #KEY} of the main settings (the concept's «Верхняя строка» sheet edits it). A "ui_" key, as the
 * shade's tiles (ShadeTiles): not per module (ModuleProfiles.isLocal), so the row does not change with the lens; in the main
 * settings it travels in the config file's main part and goes with «Сбросить настройки».
 * <p>
 * Nothing stored yet: {@link #DEFAULT} (the row before it could be edited). {@link #REQUIRED} items (the format and the LUT: their
 * sheet and tray have no other way in) are always in the row: one missing from a stored list comes back on read (the format
 * first, the LUT last). Unknown ids and repeats are dropped on read, and at most {@link #MAX} items are kept. An item the open camera lacks (DCG
 * on a module without it) stays in the list; the row only hides it there.
 */
public final class TopRow {
    public static final String KEY = "ui_top_row";

    public static final String FORMAT = "format", TORCH = "torch", TIMER = "timer", LUT = "lut", DCG = "dcg", GRID = "grid",
            HORIZON = "horizon", UHDR = "uhdr";
    /** Every item the row can hold, in the sheet's «ДОБАВИТЬ» order. */
    public static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(FORMAT, TORCH, TIMER, LUT, DCG, GRID, HORIZON, UHDR));
    /** The row as it was before it could be edited («Вернуть основные»). */
    public static final List<String> DEFAULT = Collections.unmodifiableList(Arrays.asList(FORMAT, TORCH, TIMER, LUT, DCG));
    /** The most items the row holds (the concept's six: the 40dp items fit a 320dp screen). */
    public static final int MAX = 6;
    /** The items the row always holds: the format sheet and the LUT tray open only from them. */
    public static final List<String> REQUIRED = Collections.unmodifiableList(Arrays.asList(FORMAT, LUT));

    /** {@code id} cannot be taken out of the row. */
    public static boolean required(String id) { return REQUIRED.contains(id); }

    private TopRow() {}

    /** The stored list as written (cleaned), or null when nothing was ever stored. */
    @Nullable
    public static List<String> stored(SharedPreferences prefs) {
        Object raw = prefs.getAll().get(KEY);
        return raw == null ? null : clean(split(raw.toString()));
    }

    /** The row's items: the stored list, or the defaults. */
    public static List<String> load(SharedPreferences prefs) {
        List<String> stored = stored(prefs);
        return stored == null ? new ArrayList<>(DEFAULT) : stored;
    }

    public static void save(SharedPreferences prefs, List<String> items) {
        prefs.edit().putString(KEY, String.join(",", clean(items))).apply();
    }

    /**
     * Known ids in order, without repeats, at most {@link #MAX}, every {@link #REQUIRED} one in (a missing format first, a missing
     * LUT last; the other items give way to them past MAX).
     */
    public static List<String> clean(Collection<String> items) {
        List<String> out = new ArrayList<>();
        int others = 0;
        for (String id : items) {
            if (id == null || !ALL.contains(id) || out.contains(id)) continue;
            if (required(id)) { out.add(id); continue; }
            if (others >= MAX - REQUIRED.size()) continue;
            out.add(id);
            others++;
        }
        if (!out.contains(FORMAT)) out.add(0, FORMAT);
        if (!out.contains(LUT)) out.add(LUT);
        return out;
    }

    static List<String> split(String value) {
        List<String> out = new ArrayList<>();
        for (String s : value.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
