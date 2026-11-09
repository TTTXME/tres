package com.particlesdevs.photoncamera.capture;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * P586: the OEM's custom RAW sizes of an OPPO-family phone - the [OemSupportedCustomRAWFullSizes] section of
 * /odm/etc/camera/CameraHWConfiguration.config, a world-readable file (the OnePlus 16's SCAM TEST report read it without root,
 * lines 22986-22990: pokemonmain 8160x6144 / 8160x4590 / 6144x6144 / 8160x3745, pokemontele and pokemonultrawide 8192x6144 /
 * 8192x4608 / 6144x6144 / 8192x3760; the 200 MP 16320x12288 is not listed). OppoNativeFullRes puts the listed sizes first and
 * logs a candidate that is not listed. Read once per process; none when the file is not there or not readable.
 */
public final class OplusRawSizes {
    private OplusRawSizes() { }

    static final String FILE = "/odm/etc/camera/CameraHWConfiguration.config";
    static final String SECTION = "[OemSupportedCustomRAWFullSizes]";
    private static volatile List<int[]> sizes;

    /** The listed {width, height} pairs (empty: none known). */
    static List<int[]> custom() {
        List<int[]> s = sizes;
        if (s == null) sizes = s = read();
        return s;
    }

    private static List<int[]> read() {
        if (!OppoNativeFullRes.family()) return Collections.emptyList();
        final java.io.File f = new java.io.File(FILE);
        if (!f.canRead() || f.length() <= 0 || f.length() > 4_000_000L) return Collections.emptyList();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            final StringBuilder b = new StringBuilder();
            String line;
            boolean in = false;
            while ((line = r.readLine()) != null) {
                final String t = line.trim();
                if (t.startsWith("[")) {
                    if (in) break;
                    in = t.startsWith(SECTION);
                    continue;
                }
                if (in) b.append(line).append('\n');
            }
            final List<int[]> out = parse(SECTION + "\n" + b);
            com.particlesdevs.photoncamera.util.Log.i("OppoNativeFullRes", FILE + " " + SECTION + ": " + text(out));
            return out;
        } catch (java.io.IOException | RuntimeException e) {
            com.particlesdevs.photoncamera.util.Log.w("OppoNativeFullRes", FILE + " not read: " + e);
            return Collections.emptyList();
        }
    }

    /**
     * The sizes of the section in {@code config}: each «Size[name] = w; h; w; h ...» line after the section header, until the next
     * section; comments (#) left out, each size once.
     */
    static List<int[]> parse(@Nullable String config) {
        final List<int[]> out = new ArrayList<>();
        if (config == null) return out;
        boolean in = false;
        for (String raw : config.split("\n")) {
            String t = raw.trim();
            if (t.startsWith("[")) {
                if (in) break;
                in = t.startsWith(SECTION);
                continue;
            }
            if (!in) continue;
            final int hash = t.indexOf('#');
            if (hash >= 0) t = t.substring(0, hash);
            final int eq = t.indexOf('=');
            if (eq < 0 || !t.substring(0, eq).contains("Size")) continue;
            final List<Integer> v = new ArrayList<>();
            for (String part : t.substring(eq + 1).split("[;,\\s]+")) {
                try {
                    if (!part.isEmpty()) v.add(Integer.parseInt(part));
                } catch (NumberFormatException ignored) {
                }
            }
            for (int i = 0; i + 1 < v.size(); i += 2) {
                final int w = v.get(i), h = v.get(i + 1);
                if (w > 0 && h > 0 && !OppoNativeFullRes.listed(out, w, h)) out.add(new int[]{w, h});
            }
        }
        return out;
    }

    static String text(List<int[]> sizes) {
        final StringBuilder b = new StringBuilder();
        for (int[] s : sizes) b.append(b.length() == 0 ? "" : ", ").append(s[0]).append('x').append(s[1]);
        return b.length() == 0 ? "none" : b.toString();
    }

    /** Tests: the list as if read. */
    static void setForTest(@Nullable List<int[]> list) {
        sizes = list;
    }
}
