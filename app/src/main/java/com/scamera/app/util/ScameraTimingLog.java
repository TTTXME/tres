package com.scamera.app.util;

import android.content.Context;

/** Compatibility API: timing records now belong to the single opt-in session log. */
public final class ScameraTimingLog {
    private ScameraTimingLog() {}

    public static void shot(Context context, boolean profile, String header) {
        ScameraFileLog.attach(context);
        if (ScameraFileLog.isEnabled()) Log.i("ShotTimeline", "=== " + header + " ===");
    }

    public static void summary(String line) {
        if (line != null && ScameraFileLog.isEnabled()) Log.i("ShotTimeline", line);
    }

    static boolean isTiming(String message) {
        return message != null && (message.contains(" ms") || message.contains("ms=") || message.contains("ms:")
                || message.contains("elapsed") || message.contains("TIMELINE") || message.contains("timing"));
    }

    static void mirror(String level, String tag, String message) { /* Captured from logcat once. */ }
}
