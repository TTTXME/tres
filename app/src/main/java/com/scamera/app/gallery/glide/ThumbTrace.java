package com.scamera.app.gallery.glide;

import androidx.annotation.NonNull;

import com.scamera.app.util.Log;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * P620: the thumbnail decodes in the log (tag «GalleryThumb»), counted from the gallery's open (GalleryOpenTrace.opened calls
 * {@link #reset}): the first decode of each format and source («first jpeg thumbnail: decoded 1/16 182 ms (8160x6144 -> 480x360)»),
 * every {@link #EVERY} decodes the count and mean time per format / source, and for the open's first screen the sources of its
 * decoded thumbnails ({@link #sources}). Called on Glide's decode threads.
 */
public final class ThumbTrace {
    private ThumbTrace() {}

    private static final String TAG = "GalleryThumb";
    static final int EVERY = 50;

    private static final class Stat {
        int n;
        long ms, max;
    }

    private static final Map<String, Stat> STATS = new LinkedHashMap<>();
    private static int decodes;

    /** A new gallery open: the counts start again. */
    public static synchronized void reset() {
        STATS.clear();
        decodes = 0;
    }

    /** A thumbnail decoded: {@code kind} (jpeg, dng...), {@code source} (exif, decoded 1/8, dng-raw...), the picture's size and the result's. */
    public static void decoded(@NonNull String kind, @NonNull String source, long ms, int srcW, int srcH, int outW, int outH) {
        String first = null, every = null;
        synchronized (ThumbTrace.class) {
            String key = kind + "/" + family(source);
            Stat s = STATS.get(key);
            if (s == null) {
                STATS.put(key, s = new Stat());
                first = "first " + kind + " thumbnail: " + source + " " + ms + " ms (" + srcW + "x" + srcH + " -> " + outW + "x" + outH + ")";
            }
            s.n++;
            s.ms += ms;
            s.max = Math.max(s.max, ms);
            if (++decodes % EVERY == 0) every = "thumbnails decoded since the open: " + decodes + " (" + table() + ")";
        }
        if (first != null) Log.i(TAG, first);
        if (every != null) Log.i(TAG, every);
    }

    /** A thumbnail whose decoder failed (Glide's own decoders then try the uri). */
    public static void failed(@NonNull String kind, @NonNull String name, @NonNull Throwable e) {
        Log.w(TAG, kind + " thumbnail of " + name + " failed: " + e + " - Glide's decoders try it");
    }

    /** «, decoded: jpeg/decoded 8, dng/dng-raw 2» for the first screen's line, empty before any decode. */
    public static synchronized String sources() {
        if (STATS.isEmpty()) return "";
        StringBuilder b = new StringBuilder(", decoded: ");
        boolean comma = false;
        for (Map.Entry<String, Stat> e : STATS.entrySet()) {
            if (comma) b.append(", ");
            b.append(e.getKey()).append(' ').append(e.getValue().n);
            comma = true;
        }
        return b.toString();
    }

    private static String table() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Stat> e : STATS.entrySet()) {
            if (b.length() > 0) b.append(", ");
            Stat s = e.getValue();
            b.append(String.format(Locale.ROOT, "%s %d x %d ms, max %d", e.getKey(), s.n, s.n > 0 ? s.ms / s.n : 0, s.max));
        }
        return b.toString();
    }

    /** «decoded 1/16» and «decoded 1/8» count as one source. */
    static String family(String source) {
        int space = source.indexOf(' ');
        return space < 0 ? source : source.substring(0, space);
    }
}
