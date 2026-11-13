package com.scamera.app.processing.parameters;

import java.util.Locale;

/** Exposure times: Camera2's nanoseconds and seconds, and the photographer's notation of a time. */
public class ExposureIndex {
    /** Nanoseconds per second. */
    public static final long sec = 1_000_000_000L;

    private ExposureIndex() {
    }

    /** A Camera2 exposure time (ns) in seconds. */
    public static double time2sec(long nanoseconds) {
        return nanoseconds / (double) sec;
    }

    /** "2.50" for times above a second, else the reciprocal: "1/250". */
    public static String sec2string(double seconds) {
        return seconds > 1.0 ? String.format(Locale.US, "%.2f", seconds) : "1/" + String.format(Locale.US, "%.0f", 1.0 / seconds);
    }
}
