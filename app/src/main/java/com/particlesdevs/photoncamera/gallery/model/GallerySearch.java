package com.particlesdevs.photoncamera.gallery.model;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.gallery.files.MediaFile;
import com.particlesdevs.photoncamera.gallery.ui.GalleryFormat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * P502 (owner, 2026-11-05: the reference video's «Поиск»): the gallery's search without an on-device recogniser - every word of the
 * query must appear in what is known of a file: its name, its folder, its kind (видео / video, raw / dng, heic, jpeg / jpg,
 * скриншот / screenshot, избранное / favourite) and its date (the year, the month in ru / en, «сегодня» / «вчера», the day
 * «31»). Case and «ё» do not matter. Pure: the page and the tests call it.
 */
public final class GallerySearch {
    private GallerySearch() {}

    private static final String[] RU_MONTHS = {"январь января", "февраль февраля", "март марта", "апрель апреля", "май мая",
            "июнь июня", "июль июля", "август августа", "сентябрь сентября", "октябрь октября", "ноябрь ноября", "декабрь декабря"};
    private static final String[] EN_MONTHS = {"january jan", "february feb", "march mar", "april apr", "may", "june jun",
            "july jul", "august aug", "september sep sept", "october oct", "november nov", "december dec"};

    /** The words of a query: lower case, «ё» as «е», split on spaces and punctuation. */
    public static List<String> words(@Nullable String query) {
        List<String> out = new ArrayList<>();
        if (query == null) return out;
        for (String w : normalise(query).split("[\\s,;]+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    static String normalise(String s) {
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    /** What is known of {@code f} as searchable text. */
    public static String haystack(MediaFile f, boolean favourite, long nowMs) {
        StringBuilder b = new StringBuilder();
        String name = f.getDisplayName() != null ? f.getDisplayName() : "";
        b.append(name).append(' ');
        String path = f.getAbsolutePath();
        if (path != null) {
            int slash = path.lastIndexOf('/');
            int prev = slash > 0 ? path.lastIndexOf('/', slash - 1) : -1;
            if (slash > 0) b.append(path, prev + 1, slash).append(' ');
            if (normalise(path).contains("screenshot")) b.append("скриншот скриншоты screenshot ");
            if (normalise(path).contains("screenrecord") || normalise(path).contains("screen record")) b.append("запись экрана screen recording ");
        }
        GalleryFormat format = GalleryFormat.of(name, false);
        switch (format) {
            case VIDEO: b.append("видео video ролик "); break;
            case RAW: b.append("raw dng рав "); break;
            case HEIC: b.append("heic heif "); break;
            case JPEG: b.append("jpeg jpg "); break;
            default: break;
        }
        if (format != GalleryFormat.VIDEO) b.append("фото photo снимок ");
        if (favourite) b.append("избранное избранные favourite favorite ");
        long t = f.getLastModified();
        if (t > 0) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(t);
            int m = c.get(Calendar.MONTH);
            b.append(c.get(Calendar.YEAR)).append(' ').append(RU_MONTHS[m]).append(' ').append(EN_MONTHS[m]).append(' ')
                    .append(c.get(Calendar.DAY_OF_MONTH)).append(' ');
            Calendar now = Calendar.getInstance();
            now.setTimeInMillis(nowMs);
            if (sameDay(c, now)) b.append("сегодня today ");
            now.add(Calendar.DAY_OF_YEAR, -1);
            if (sameDay(c, now)) b.append("вчера yesterday ");
        }
        return normalise(b.toString());
    }

    static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    /** The items of {@code all} whose text has every word of {@code query} (all of them for an empty query), in their order. */
    public static List<GalleryItem> find(List<GalleryItem> all, @Nullable String query, Set<String> favourites, long nowMs,
                                         @Nullable Map<GalleryItem, String> cache) {
        List<String> words = words(query);
        List<GalleryItem> out = new ArrayList<>();
        Map<GalleryItem, String> texts = cache != null ? cache : new IdentityHashMap<>();
        for (GalleryItem i : all) {
            if (i == null || i.getFile() == null) continue;
            if (words.isEmpty()) {
                out.add(i);
                continue;
            }
            String text = texts.get(i);
            if (text == null) {
                text = haystack(i.getFile(), favourites.contains(GalleryView.key(i.getFile())), nowMs);
                // P504: the text read on the photo (OcrIndex; SearchPage drops this cache when the index grows)
                String onPhoto = com.particlesdevs.photoncamera.gallery.ocr.OcrIndex.searchText(i.getFile());
                if (onPhoto != null && !onPhoto.isEmpty()) text = text + " " + onPhoto;
                texts.put(i, text);
            }
            boolean all_ = true;
            for (String w : words) if (!text.contains(w)) { all_ = false; break; }
            if (all_) out.add(i);
        }
        return out;
    }
}
