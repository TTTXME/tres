package com.scamera.app.util;

import android.content.Context;

/** Android logcat is the single source for the optional file recorder. */
public final class Log {
    private static volatile boolean logEnabled = true;
    private Log() {}

    /** Kept for older callers; permanent DCIM logging is retired. */
    public static void setLogFolder(Context context) { ScameraFileLog.attach(context); }

    private static void write(String level, String tag, String message, Throwable error) {
        if (!logEnabled) return;
        String text = error == null ? message : message + "\n" + android.util.Log.getStackTraceString(error);
        switch (level) {
            case "E": android.util.Log.e(tag, text); break;
            case "W": android.util.Log.w(tag, text); break;
            case "I": android.util.Log.i(tag, text); break;
            case "V": android.util.Log.v(tag, text); break;
            default: android.util.Log.d(tag, text);
        }
        ScameraFileLog.fallback(level, tag, text);
    }

    public static void d(String tag, String message) { write("D", tag, message, null); }
    public static void w(String tag, String message) { write("W", tag, message, null); }
    public static void w(String tag, String message, Throwable error) { write("W", tag, message, error); }
    public static void e(String tag, String message) { write("E", tag, message, null); }
    public static void e(String tag, String message, Throwable error) { write("E", tag, message, error); }
    public static void i(String tag, String message) { write("I", tag, message, null); }
    public static void v(String tag, String message) { write("V", tag, message, null); }
    public static String getStackTraceString(Exception error) {
        String trace = android.util.Log.getStackTraceString(error);
        e("Exception", trace);
        return trace;
    }
    public static void setLogEnabled(boolean enabled) { logEnabled = enabled; }
    public static void shutdown() { ScameraDebugLog.setEnabled(false); }
}
