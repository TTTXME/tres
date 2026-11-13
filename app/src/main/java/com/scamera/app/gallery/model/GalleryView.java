package com.scamera.app.gallery.model;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.scamera.app.gallery.files.MediaFile;
import com.scamera.app.gallery.ui.GalleryFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * P498 (owner, 2026-11-04: «сделай из нашей галереи полноценную галерею с полноценным функционалом»): what the gallery shows of a
 * folder - the favourites (a heart on a photo; the «Избранное» chip), a type filter (RAW / JPEG / HEIC / video), the order (newest or oldest
 * first), the grid's column count, and the photos deleted a moment ago that can still be brought back (hidden until the undo runs
 * out). Kept in the app's own preferences («gallery_ui», «gallery_favorites»): the MediaStore favourite needs a system dialog per
 * photo for another app's files. The grid and the viewer read the same filtered list (GalleryViewModel), so positions match.
 */
public final class GalleryView {
    private GalleryView() {}

    /** The «Избранное» chip's folder name (a folder of the favourites of every shown folder). */
    public static final String FAVOURITES = "FAV";
    static final String PREFS = "gallery_ui", FAV_PREFS = "gallery_favorites", KEY_FAV = "keys";
    static final String KEY_TYPE = "type_filter", KEY_OLDEST = "oldest_first", KEY_COLUMNS = "grid_columns";
    public static final int MIN_COLUMNS = 2, MAX_COLUMNS = 6, DEFAULT_COLUMNS = 3;

    /** The type filter. */
    public enum Type { ALL, RAW, JPEG, HEIC, VIDEO }

    /** P498 (owner, 2026-11-04: «отображать только фото / только видео / фото и видео»): what kind of media the gallery shows. */
    public enum Media { BOTH, PHOTOS, VIDEOS }

    static final String KEY_MEDIA = "media_kind";
    /** P502: the filter sheet's «Статус» (favourites only) and «Время» (the years shown; none = every year). */
    static final String KEY_FAV_ONLY = "filter_favourites", KEY_YEARS = "filter_years";

    public static boolean favouritesOnly(Context c) {
        return prefs(c).getBoolean(KEY_FAV_ONLY, false);
    }

    public static void setFavouritesOnly(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_FAV_ONLY, on).apply();
    }

    public static Set<Integer> years(Context c) {
        Set<Integer> out = new java.util.TreeSet<>();
        for (String y : prefs(c).getStringSet(KEY_YEARS, Collections.emptySet())) {
            try { out.add(Integer.parseInt(y)); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    public static void setYears(Context c, Set<Integer> years) {
        Set<String> s = new HashSet<>();
        for (Integer y : years) s.add(String.valueOf(y));
        prefs(c).edit().putStringSet(KEY_YEARS, s).apply();
    }

    /** The calendar year of a timestamp (ms, local time). */
    public static int yearOf(long millis) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(millis);
        return cal.get(java.util.Calendar.YEAR);
    }

    /** P502: whether the filter sheet narrows the list (its chip shows; «Сбросить» clears it). */
    public static boolean filtering(Context c) {
        return type(c) != Type.ALL || media(c) != Media.BOTH || favouritesOnly(c) || !years(c).isEmpty();
    }

    /** P502: the filter sheet back to everything. */
    public static void clearFilter(Context c) {
        prefs(c).edit().putString(KEY_TYPE, Type.ALL.name()).putString(KEY_MEDIA, Media.BOTH.name()).putBoolean(KEY_FAV_ONLY, false)
                .putStringSet(KEY_YEARS, Collections.emptySet()).apply();
    }

    public static Media media(Context c) {
        try {
            return Media.valueOf(prefs(c).getString(KEY_MEDIA, Media.BOTH.name()));
        } catch (IllegalArgumentException e) {
            return Media.BOTH;
        }
    }

    public static void setMedia(Context c, Media m) {
        prefs(c).edit().putString(KEY_MEDIA, m.name()).apply();
    }

    /** Whether a file named {@code name} is of the kind shown. */
    public static boolean matches(Media m, @Nullable String name) {
        if (m == Media.BOTH) return true;
        boolean video = GalleryFormat.of(name, false) == GalleryFormat.VIDEO;
        return m == Media.VIDEOS ? video : !video;
    }

    /** The photos deleted a moment ago (their keys), hidden while their undo runs. */
    private static final Set<String> PENDING = Collections.synchronizedSet(new HashSet<>());

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** A file's key: its MediaStore row id (the same row has two URI forms), else its URI. */
    public static String key(@Nullable MediaFile f) {
        if (f == null) return "";
        if (f.getId() >= 0) return "id:" + f.getId();
        return f.getFileUri() != null ? f.getFileUri().toString() : String.valueOf(f.getAbsolutePath());
    }

    // ---------------------------------------------------------------- favourites

    public static Set<String> favourites(Context c) {
        return new HashSet<>(c.getApplicationContext().getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_FAV, Collections.emptySet()));
    }

    public static boolean isFavourite(Context c, @Nullable MediaFile f) {
        return f != null && favourites(c).contains(key(f));
    }

    public static void setFavourite(Context c, List<MediaFile> files, boolean on) {
        Set<String> s = favourites(c);
        for (MediaFile f : files) {
            if (on) s.add(key(f));
            else s.remove(key(f));
        }
        c.getApplicationContext().getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_FAV, s).apply();
    }

    // ---------------------------------------------------------------- view options

    public static Type type(Context c) {
        try {
            return Type.valueOf(prefs(c).getString(KEY_TYPE, Type.ALL.name()));
        } catch (IllegalArgumentException e) {
            return Type.ALL;
        }
    }

    public static void setType(Context c, Type t) {
        prefs(c).edit().putString(KEY_TYPE, t.name()).apply();
    }

    public static boolean oldestFirst(Context c) {
        return prefs(c).getBoolean(KEY_OLDEST, false);
    }

    public static void setOldestFirst(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_OLDEST, on).apply();
    }

    public static int columns(Context c) {
        return clampColumns(prefs(c).getInt(KEY_COLUMNS, DEFAULT_COLUMNS));
    }

    public static void setColumns(Context c, int n) {
        prefs(c).edit().putInt(KEY_COLUMNS, clampColumns(n)).apply();
    }

    public static int clampColumns(int n) {
        return Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, n));
    }

    // ---------------------------------------------------------------- deletes with undo

    public static void hide(List<? extends MediaFile> files) {
        for (MediaFile f : files) PENDING.add(key(f));
    }

    public static void unhide(List<? extends MediaFile> files) {
        for (MediaFile f : files) PENDING.remove(key(f));
    }

    public static boolean hidden(@Nullable MediaFile f) {
        return f != null && PENDING.contains(key(f));
    }

    // ---------------------------------------------------------------- the list

    /** Whether {@code name} passes the type filter. */
    public static boolean matches(Type t, @Nullable String name) {
        if (t == Type.ALL) return true;
        GalleryFormat f = GalleryFormat.of(name, false);
        switch (t) {
            case RAW: return f == GalleryFormat.RAW;
            case JPEG: return f == GalleryFormat.JPEG;
            case HEIC: return f == GalleryFormat.HEIC;
            case VIDEO: return f == GalleryFormat.VIDEO;
            default: return true;
        }
    }

    /** Whether {@link #apply} changes anything (else the folder's own list is shown, as before P498). */
    public static boolean active(@Nullable Context c, boolean favouritesOnly) {
        return favouritesOnly || !PENDING.isEmpty() || (c != null && (filtering(c) || oldestFirst(c)));
    }

    /**
     * What the grid and the viewer show of {@code files}: without the photos pending deletion, of the chosen type, only the
     * favourites when {@code favouritesOnly}, newest first (the MediaStore order) or reversed.
     */
    public static ArrayList<GalleryItem> apply(@Nullable Context c, @Nullable List<GalleryItem> files, boolean favouritesOnly) {
        ArrayList<GalleryItem> out = new ArrayList<>();
        if (files == null) return out;
        Type t = c != null ? type(c) : Type.ALL;
        Media m = c != null ? media(c) : Media.BOTH;
        Set<String> fav = (favouritesOnly || c != null && favouritesOnly(c)) && c != null ? favourites(c) : null;
        Set<Integer> years = c != null ? years(c) : Collections.emptySet();
        for (GalleryItem i : files) {
            if (i == null || i.getFile() == null || hidden(i.getFile())) continue;
            if (!matches(t, i.getFile().getDisplayName()) || !matches(m, i.getFile().getDisplayName())) continue;
            if (fav != null && !fav.contains(key(i.getFile()))) continue;
            if (!years.isEmpty() && !years.contains(yearOf(i.getFile().getLastModified()))) continue;
            out.add(i);
        }
        if (c != null && oldestFirst(c)) Collections.reverse(out);
        return out;
    }
}
