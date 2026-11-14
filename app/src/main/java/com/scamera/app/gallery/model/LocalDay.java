package com.scamera.app.gallery.model;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * P706: the local day, month and year of the gallery's timestamps without a new Calendar a call (the grid's rows, the timeline's
 * months and the year filter asked for two or three a photo: tens of thousands for a large library on the main thread at every
 * list). The zone is read again with every list ({@link #refresh}; TimeZone.getDefault() is a copy a call).
 */
public final class LocalDay {
    private LocalDay() {}

    private static final long DAY_MS = 86_400_000L;
    private static volatile TimeZone zone = TimeZone.getDefault();
    private static final ThreadLocal<Calendar> CALENDAR = ThreadLocal.withInitial(Calendar::getInstance);

    /** The phone's zone now (a list is about to be laid out). */
    public static void refresh() {
        zone = TimeZone.getDefault();
    }

    /** Days since the epoch, in the phone's zone, of {@code millis}: equal for two moments of the same local date. */
    public static long day(long millis) {
        return Math.floorDiv(millis + zone.getOffset(millis), DAY_MS);
    }

    /** This thread's calendar at {@code millis} in the phone's zone: read its fields at once (the next call moves it). */
    public static Calendar at(long millis) {
        final Calendar c = CALENDAR.get();
        final TimeZone z = zone;
        if (c.getTimeZone() != z) c.setTimeZone(z);
        c.setTimeInMillis(millis);
        return c;
    }
}
