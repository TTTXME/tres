package com.scamera.app.gallery.ui;

import com.scamera.app.gallery.model.GalleryItem;
import com.scamera.app.util.Lang;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * P622: the sections of the «Лента» view - every photo under its month («Ноябрь 2026» header rows), one card per month, one card per
 * year. The items keep their order (newest or oldest first): a section is a run of items of the same month / year.
 */
public final class TimelineSections {
    private TimelineSections() {}

    /** yyyymm of a timestamp (local time). */
    public static int monthKey(long millis) {
        Calendar c = com.scamera.app.gallery.model.LocalDay.at(millis); // P706: no new Calendar a photo
        return c.get(Calendar.YEAR) * 100 + c.get(Calendar.MONTH);
    }

    public static int yearKey(long millis) {
        return monthKey(millis) / 100;
    }

    /** «Ноябрь 2026» / "November 2026". */
    public static String monthTitle(long millis) {
        int k = monthKey(millis);
        return monthName(k % 100) + " " + (k / 100);
    }

    /** The month in the nominative: «Ноябрь» / "November". */
    public static String monthName(int month) {
        switch (month) {
            case 0: return Lang.t("Январь", "January");
            case 1: return Lang.t("Февраль", "February");
            case 2: return Lang.t("Март", "March");
            case 3: return Lang.t("Апрель", "April");
            case 4: return Lang.t("Май", "May");
            case 5: return Lang.t("Июнь", "June");
            case 6: return Lang.t("Июль", "July");
            case 7: return Lang.t("Август", "August");
            case 8: return Lang.t("Сентябрь", "September");
            case 9: return Lang.t("Октябрь", "October");
            case 10: return Lang.t("Ноябрь", "November");
            default: return Lang.t("Декабрь", "December");
        }
    }

    private static long when(List<GalleryItem> items, int i) {
        return items.get(i).mediaFile().modifiedMs();
    }

    /** Every photo: a month header (label «Ноябрь 2026», its count) and the month's photos. */
    public static List<LibraryAdapter.Row> all(List<GalleryItem> items) {
        List<LibraryAdapter.Row> out = new ArrayList<>();
        int i = 0;
        while (i < items.size()) {
            int key = monthKey(when(items, i));
            int j = i;
            while (j < items.size() && monthKey(when(items, j)) == key) j++;
            out.add(new LibraryAdapter.Row(LibraryAdapter.HEADER, i, monthTitle(when(items, i)), j - i));
            for (int k = i; k < j; ++k) out.add(new LibraryAdapter.Row(LibraryAdapter.PHOTO, k, null, 0));
            i = j;
        }
        return out;
    }

    /** One card per month (label «Ноябрь 2026»). */
    public static List<LibraryAdapter.Row> months(List<GalleryItem> items) {
        return groups(items, false);
    }

    /** One card per year (label «2026»). */
    public static List<LibraryAdapter.Row> years(List<GalleryItem> items) {
        return groups(items, true);
    }

    private static List<LibraryAdapter.Row> groups(List<GalleryItem> items, boolean years) {
        List<LibraryAdapter.Row> out = new ArrayList<>();
        int i = 0;
        while (i < items.size()) {
            int key = years ? yearKey(when(items, i)) : monthKey(when(items, i));
            int j = i;
            while (j < items.size() && (years ? yearKey(when(items, j)) : monthKey(when(items, j))) == key) j++;
            out.add(new LibraryAdapter.Row(RowStyle.GROUP, i, years ? String.valueOf(key) : monthTitle(when(items, i)), j - i));
            i = j;
        }
        return out;
    }

    /** The first item of up to {@code max} different months inside items [from, from + count) (a year card's covers). */
    public static int[] monthCovers(List<GalleryItem> items, int from, int count, int max) {
        int[] out = new int[max];
        int n = 0, last = Integer.MIN_VALUE;
        for (int i = from; i < Math.min(items.size(), from + count) && n < max; ++i) {
            int k = monthKey(when(items, i));
            if (k == last) continue;
            last = k;
            out[n++] = i;
        }
        int[] trimmed = new int[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    /** The adapter position showing item {@code item} in {@code rows}: its photo row, else the group holding it; -1 when none. */
    public static int positionOf(List<LibraryAdapter.Row> rows, int item) {
        for (int p = 0; p < rows.size(); ++p) {
            LibraryAdapter.Row r = rows.get(p);
            if (r.type == LibraryAdapter.PHOTO && r.item == item) return p;
            if (r.type == RowStyle.GROUP && item >= r.item && item < r.item + r.count) return p;
        }
        return -1;
    }
}
