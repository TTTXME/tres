package com.scamera.app.util.log;

import androidx.annotation.NonNull;

import com.scamera.app.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

/** Log helpers: a short warning with the place it came from, a key : value table, a stack trace as text. */
public final class Logger {
    private Logger() {
    }

    /** A warning with the exception's text and the method that called the warning's caller. */
    public static void warnShort(String tag, String message, @NonNull Exception e) {
        Log.w(tag, message + "\nError: " + e + "\n\tat " + getCallerClassMethodName());
    }

    /**
     * "class.method(file:line)" of the first frame past the calling class (Logger and java.lang.Thread frames skipped):
     * the method that called the method this is called from; null when the stack has none.
     */
    public static String getCallerClassMethodName() {
        final StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        String caller = null;
        for (int i = 1; i < stack.length; i++) {
            final String type = stack[i].getClassName();
            if (type.equals(Logger.class.getName()) || type.startsWith("java.lang.Thread")) continue;
            if (caller == null) caller = type;
            else if (!caller.equals(type))
                return type + "." + stack[i].getMethodName() + "(" + stack[i].getFileName() + ":" + stack[i].getLineNumber() + ")";
        }
        return null;
    }

    /** "key : value" lines in the map's order. */
    public static String createTextFrom(Map<String, String> rows) {
        final StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> row : rows.entrySet()) text.append(row.getKey()).append(" : ").append(row.getValue()).append('\n');
        return text.toString();
    }

    public static String getStackTrace(final Throwable throwable) {
        final StringWriter text = new StringWriter();
        throwable.printStackTrace(new PrintWriter(text, true));
        return text.toString();
    }
}
