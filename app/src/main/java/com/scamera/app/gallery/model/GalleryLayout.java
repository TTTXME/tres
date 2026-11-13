package com.scamera.app.gallery.model;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.scamera.app.util.Lang;

import java.util.List;

/**
 * P622 (owner, 2026-11-11: «Добавь в настройки галереи возможность выбора такого вида просмотра, но сделай в нашем стиле с красивыми
 * анимациями. Так же придумай свой третий вид для нашей галереи в стиле IOS, но на 20% ближе к нашему»): how the gallery lays its
 * photos and albums out - {@link Mode#GRID} (the P59b / P502 grid, the default), {@link Mode#LIST} (one row per album and per photo:
 * a thumbnail, the name, a secondary line and tags) and {@link Mode#TIMELINE} («Лента»: an edge-to-edge square grid under month
 * titles with a sticky month capsule, zoomed by pinch or the floating control between «Годы», «Месяцы» and «Все фото»). Kept in the
 * gallery's own preferences («gallery_ui») with the timeline's level and columns, the last photo opened (the list highlights it) and,
 * per album, the newest file seen (the list's badge counts the files added since).
 */
public final class GalleryLayout {
    private GalleryLayout() {}

    /** The gallery's view. */
    public enum Mode {
        GRID, LIST, TIMELINE;

        /** «Сетка» / «Список» / «Лента». */
        public String label() {
            switch (this) {
                case LIST: return Lang.t("Список", "List");
                case TIMELINE: return Lang.t("Лента", "Timeline");
                default: return Lang.t("Сетка", "Grid");
            }
        }
    }

    /** The timeline's zoom: whole years, months, every photo. */
    public enum Level {
        YEARS, MONTHS, ALL;

        public String label() {
            switch (this) {
                case YEARS: return Lang.t("Годы", "Years");
                case MONTHS: return Lang.t("Месяцы", "Months");
                default: return Lang.t("Все фото", "All photos");
            }
        }
    }

    static final String PREFS = "gallery_ui";
    static final String KEY_MODE = "view_mode", KEY_LEVEL = "timeline_level", KEY_TL_COLUMNS = "timeline_columns",
            KEY_LAST = "last_opened", KEY_SEEN = "album_seen_";
    /** The timeline's columns in portrait (times the screen's width factor), as the stock library's zoom steps. */
    public static final int TL_MIN_COLUMNS = 3, TL_MAX_COLUMNS = 7, TL_DEFAULT_COLUMNS = 4;

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Mode mode(Context c) {
        return parse(prefs(c).getString(KEY_MODE, null));
    }

    /** A stored name back to a mode; anything unknown (or nothing) is the grid. */
    public static Mode parse(@Nullable String name) {
        if (name == null) return Mode.GRID;
        try {
            return Mode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Mode.GRID;
        }
    }

    public static void setMode(Context c, Mode m) {
        prefs(c).edit().putString(KEY_MODE, (m != null ? m : Mode.GRID).name()).apply();
    }

    public static Level level(Context c) {
        try {
            return Level.valueOf(prefs(c).getString(KEY_LEVEL, Level.ALL.name()));
        } catch (IllegalArgumentException e) {
            return Level.ALL;
        }
    }

    public static void setLevel(Context c, Level l) {
        prefs(c).edit().putString(KEY_LEVEL, (l != null ? l : Level.ALL).name()).apply();
    }

    public static int timelineColumns(Context c) {
        return clampTimeline(prefs(c).getInt(KEY_TL_COLUMNS, TL_DEFAULT_COLUMNS));
    }

    public static void setTimelineColumns(Context c, int n) {
        prefs(c).edit().putInt(KEY_TL_COLUMNS, clampTimeline(n)).apply();
    }

    public static int clampTimeline(int n) {
        return Math.max(TL_MIN_COLUMNS, Math.min(TL_MAX_COLUMNS, n));
    }

    /**
     * A pinch on the timeline: {@code spread} (fingers apart) zooms in - fewer columns, then from months to every photo, from years to
     * months; a pinch zooms out the other way. Returns {level, columns} (the level's ordinal) after one step.
     */
    public static int[] zoomStep(Level level, int columns, boolean spread) {
        if (spread) {
            if (level == Level.YEARS) return new int[]{Level.MONTHS.ordinal(), columns};
            if (level == Level.MONTHS) return new int[]{Level.ALL.ordinal(), TL_MAX_COLUMNS};
            return new int[]{Level.ALL.ordinal(), clampTimeline(columns - 1)};
        }
        if (level == Level.ALL) {
            if (columns >= TL_MAX_COLUMNS) return new int[]{Level.MONTHS.ordinal(), columns};
            return new int[]{Level.ALL.ordinal(), columns + 1};
        }
        return new int[]{Level.YEARS.ordinal(), columns};
    }

    // ---------------------------------------------------------------- the last photo opened

    public static void setLastOpened(Context c, @Nullable String key) {
        prefs(c).edit().putString(KEY_LAST, key != null ? key : "").apply();
    }

    public static String lastOpened(Context c) {
        return prefs(c).getString(KEY_LAST, "");
    }

    // ---------------------------------------------------------------- new in an album since the last visit

    /**
     * The files of an album newer than the newest one seen when it was last opened (the list's badge). An album seen for the first
     * time records its newest file and has no badge: «new» starts counting from the first visit of this version.
     */
    public static int newCount(Context c, String album, List<GalleryItem> files) {
        long newest = newest(files);
        SharedPreferences p = prefs(c);
        String key = KEY_SEEN + album;
        if (!p.contains(key)) {
            if (newest > 0) p.edit().putLong(key, newest).apply();
            return 0;
        }
        return countNewer(files, p.getLong(key, Long.MAX_VALUE));
    }

    /** The album opened: everything in it now counts as seen. */
    public static void markSeen(Context c, String album, List<GalleryItem> files) {
        long newest = newest(files);
        if (newest > 0) prefs(c).edit().putLong(KEY_SEEN + album, newest).apply();
    }

    static long newest(List<GalleryItem> files) {
        long t = 0;
        if (files != null) for (GalleryItem i : files) if (i != null && i.mediaFile() != null) t = Math.max(t, i.mediaFile().modifiedMs());
        return t;
    }

    /** How many of {@code files} are newer than {@code seen}. */
    public static int countNewer(List<GalleryItem> files, long seen) {
        int n = 0;
        if (files != null) for (GalleryItem i : files) if (i != null && i.mediaFile() != null && i.mediaFile().modifiedMs() > seen) ++n;
        return n;
    }

    /** The badge's text: the count, «99+» above 99, null for none. */
    @Nullable
    public static String badge(int n) {
        if (n <= 0) return null;
        return n > 99 ? "99+" : String.valueOf(n);
    }
}
