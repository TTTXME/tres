package android.util;

/** The host tests' android.util.Log: every line to standard output with its level, nothing kept, nothing loggable. */
public class Log {
    private static int print(String level, String tag, String msg) {
        System.out.println(level + ": " + tag + ": " + msg);
        return 0;
    }

    public static int d(String tag, String msg) { return print("DEBUG", tag, msg); }

    public static int i(String tag, String msg) { return print("INFO", tag, msg); }

    public static int w(String tag, String msg) { return print("WARN", tag, msg); }

    public static int e(String tag, String msg) { return print("ERROR", tag, msg); }

    public static int d(String tag, String msg, Throwable ignored) { return print("DEBUG", tag, msg); }

    public static int i(String tag, String msg, Throwable ignored) { return print("INFO", tag, msg); }

    public static int w(String tag, String msg, Throwable ignored) { return print("WARN", tag, msg); }

    public static int e(String tag, String msg, Throwable ignored) { return print("ERROR", tag, msg); }

    public static String getStackTraceString(Throwable t) {
        return t != null ? t.toString() : "";
    }

    public static boolean isLoggable(String tag, int level) {
        return false;
    }
}
