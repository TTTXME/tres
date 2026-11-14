package com.scamera.app.processing.watermark;

import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.scamera.app.util.Lang;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * The Android watermark's own code (WatermarkSpec, WatermarkText, WatermarkPainter.plan, WatermarkArea) run in a plain JVM on
 * stubs (ios/tools/watermark_host/stubs: text measured with fixed advances) and written out as Swift: ios/Tests/WatermarkGolden.swift,
 * which ios/Tests/WatermarkTests.swift checks the Swift port against (the same stored values, the same fixed measures).
 * Run by ios/tools/watermark_host/run.sh.
 */
public final class Golden {
    static final long NOW = at(2026, 11, 11, 14, 5, "UTC");
    static final StringBuilder out = new StringBuilder();

    static long at(int y, int mo, int d, int h, int mi, String zone) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone(zone), Locale.US);
        c.clear();
        c.set(y, mo - 1, d, h, mi);
        return c.getTimeInMillis();
    }

    /** SharedPreferences over a map: a value of another type throws ClassCastException, as the framework's does. */
    static final class MapPrefs implements SharedPreferences {
        final Map<String, Object> m;
        MapPrefs(Map<String, Object> m) { this.m = m; }
        public boolean contains(String k) { return m.containsKey(k); }
        public String getString(String k, String f) { return m.containsKey(k) ? (String) m.get(k) : f; }
        public boolean getBoolean(String k, boolean f) { return m.containsKey(k) ? (Boolean) m.get(k) : f; }
        public int getInt(String k, int f) { return m.containsKey(k) ? (Integer) m.get(k) : f; }
        public float getFloat(String k, float f) { return m.containsKey(k) ? (Float) m.get(k) : f; }
        public long getLong(String k, long f) { return m.containsKey(k) ? (Long) m.get(k) : f; }
        @SuppressWarnings("unchecked")
        public Set<String> getStringSet(String k, Set<String> f) { return m.containsKey(k) ? (Set<String>) m.get(k) : f; }
    }

    static final WatermarkPainter.Fonts FONTS = (font, weight) -> new Typeface(WatermarkSpec.code(font), weight);

    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20 || c == 0x7F || c >= 0xD800 && c <= 0xDFFF) {
                int cp = s.codePointAt(i);
                b.append("\\u{").append(Integer.toHexString(cp)).append('}');
                if (Character.isSupplementaryCodePoint(cp)) i++;
            } else b.append(c);
        }
        return b.append('"').toString();
    }

    static String f(float v) { return Float.toString(v); }

    static String r(RectF r) {
        return r == null ? "nil" : "[" + f(r.left) + ", " + f(r.top) + ", " + f(r.right) + ", " + f(r.bottom) + "]";
    }

    static String list(List<String> l) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) b.append(i > 0 ? ", " : "").append(q(l.get(i)));
        return b.append(']').toString();
    }

    static String prefs(Map<String, Object> m) {
        if (m.isEmpty()) return "[:]";
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            b.append(first ? "" : ", ").append(q(e.getKey())).append(": ");
            Object v = e.getValue();
            b.append(v instanceof String ? q((String) v) : v.toString());
            first = false;
        }
        return b.append(']').toString();
    }

    static Map<String, Object> p(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static WatermarkText.Info info() {
        WatermarkText.Info i = WatermarkText.Info.sample("vivo X200 Ultra", NOW);
        i.zone = TimeZone.getTimeZone("UTC");
        return i;
    }

    static final Object[] ALL = {"pref_watermark_show_device", true, "pref_watermark_show_lens", true, "pref_watermark_show_aperture", true,
            "pref_watermark_show_shutter", true, "pref_watermark_show_iso", true, "pref_watermark_show_time", true};

    static Object[] with(Object[] base, Object... more) {
        Object[] o = new Object[base.length + more.length];
        System.arraycopy(base, 0, o, 0, base.length);
        System.arraycopy(more, 0, o, base.length, more.length);
        return o;
    }

    public static void main(String[] args) {
        Lang.RU = false;
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        out.append("// Generated by ios/tools/watermark_host/run.sh from the Android watermark's Java (do not edit): the values\n");
        out.append("// WatermarkTests checks the Swift port against. Text measured with FixedFonts' advances.\n\n");
        out.append("enum WatermarkGolden {\n");
        out.append("    static let now: Int64 = ").append(NOW).append("\n\n");

        // ───── the stored values as WatermarkSpec.from reads them ─────
        List<Map<String, Object>> stored = new ArrayList<>();
        stored.add(p());
        stored.add(p("pref_watermark_layout", "BAR ", "pref_watermark_position", "tr", "pref_watermark_size", "2", "pref_watermark_opacity", "55.5",
                "pref_watermark_margin", "2.5", "pref_watermark_font", "mono", "pref_watermark_color", "black", "pref_watermark_date_format", "text",
                "pref_watermark_show_text", false, "pref_watermark_show_iso", 1, "pref_watermark_logo", "false", "pref_watermark_text", "  Hello  "));
        stored.add(p("pref_watermark_layout", "frame", "pref_watermark_position", "nowhere", "pref_watermark_size", "7,5", "pref_watermark_opacity", "5",
                "pref_watermark_margin", "3.5", "pref_watermark_font", "serif", "pref_watermark_color", "WHITE", "pref_watermark_date_format", "iso",
                "pref_watermark_show_time", "true", "pref_watermark_show_lens", 0));
        stored.add(p("pref_watermark_size", "25", "pref_watermark_opacity", "100", "pref_watermark_margin", "11", "pref_watermark_layout", "badge",
                "pref_watermark_position", "bc", "pref_watermark_font", "condensed", "pref_watermark_date_format", "nonsense"));
        out.append("    /// stored values → layout, position, font, colour, dateFormat, text, size, opacity, margin, the eight switches\n");
        out.append("    static let stored: [(prefs: [String: Any], spec: [String], numbers: [Float], fields: [Bool])] = [\n");
        for (Map<String, Object> m : stored) {
            WatermarkSpec s = WatermarkSpec.from(new MapPrefs(m));
            out.append("        (").append(prefs(m)).append(",\n         ").append(list(java.util.Arrays.asList(WatermarkSpec.code(s.layout),
                    WatermarkSpec.code(s.position), WatermarkSpec.code(s.font), WatermarkSpec.code(s.colour), WatermarkSpec.code(s.dateFormat), s.text)))
                    .append(", [").append(f(s.size)).append(", ").append(f(s.opacity)).append(", ").append(f(s.margin)).append("], [")
                    .append(s.showText).append(", ").append(s.showDevice).append(", ").append(s.showLens).append(", ").append(s.showAperture)
                    .append(", ").append(s.showShutter).append(", ").append(s.showIso).append(", ").append(s.showTime).append(", ").append(s.logo)
                    .append("]),\n");
        }
        out.append("    ]\n\n");

        // ───── the text of every subset of the eight fields ─────
        out.append("    /// mask (text, device, lens, aperture, shutter, iso, time, logo: bits 0..7) → title, exif, time, column, leading, trailing\n");
        out.append("    static let subsets: [(mask: Int, title: String, exif: String, time: String, column: [String], leading: [String], trailing: [String])] = [\n");
        for (int mask = 0; mask < 256; mask++) {
            WatermarkSpec s = new WatermarkSpec().only((mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0, (mask & 16) != 0,
                    (mask & 32) != 0, (mask & 64) != 0, (mask & 128) != 0);
            WatermarkText t = WatermarkText.of(s, info());
            out.append("        (").append(mask).append(", ").append(q(t.title)).append(", ").append(q(t.exif)).append(", ").append(q(t.time))
                    .append(", ").append(list(t.column())).append(", ").append(list(t.leading())).append(", ").append(list(t.trailing())).append("),\n");
        }
        out.append("    ]\n\n");

        // ───── the time in every format, English and Russian ─────
        long[] times = {NOW, at(2026, 1, 5, 9, 7, "UTC"), at(2025, 5, 31, 23, 59, "UTC")};
        String[] zones = {"UTC", "GMT+03:00", "GMT-05:00"};
        out.append("    /// ms, zone offset (s), format, ru → text\n");
        out.append("    static let times: [(ms: Int64, offset: Int, format: String, ru: Bool, text: String)] = [\n");
        for (long t : times)
            for (String z : zones)
                for (WatermarkSpec.DateFormat d : WatermarkSpec.DateFormat.values())
                    for (boolean ru : new boolean[]{false, true}) {
                        TimeZone zone = TimeZone.getTimeZone(z);
                        out.append("        (").append(t).append(", ").append(zone.getRawOffset() / 1000).append(", ").append(q(WatermarkSpec.code(d)))
                                .append(", ").append(ru).append(", ").append(q(WatermarkText.time(t, zone, d, ru))).append("),\n");
                    }
        out.append("    ]\n\n");

        // ───── values ─────
        double[] shutters = {1 / 120.0, .25, .5, 2, 1.5, 1 / 8000.0, 1 / 3.0, .3, .29, 30, .0333, 1 / 50.0, .125, .0166666, 0, -1, Double.NaN, 0.45, 1.25, 1.05};
        out.append("    static let shutters: [(Double, String)] = [\n");
        for (double v : shutters) out.append("        (").append(Double.isNaN(v) ? ".nan" : Double.toString(v)).append(", ").append(q(WatermarkText.shutter(v))).append("),\n");
        out.append("    ]\n");
        double[] apertures = {1.8, 2, 1.78, 2.2, 2.25, .95, 16, 1.4999, 2.05, 2.15, 1.7799999713897705, 0, Double.NaN, 0.15, 0.25};
        out.append("    static let apertures: [(Double, String)] = [\n");
        for (double v : apertures) out.append("        (").append(Double.isNaN(v) ? ".nan" : Double.toString(v)).append(", ").append(q(WatermarkText.aperture(v))).append("),\n");
        out.append("    ]\n");
        double[] numbers = {6.9, 6.81, 24.0, 2.25, .15, 13.049999, 4.45, 6.86, 0.05, 100.0, 2.35};
        out.append("    static let numbers: [(Double, String)] = [\n");
        for (double v : numbers) out.append("        (").append(Double.toString(v)).append(", ").append(q(WatermarkText.number(v))).append("),\n");
        out.append("    ]\n");
        StringBuilder emoji = new StringBuilder("x");
        for (int i = 0; i < 40; i++) emoji.append("📷");
        String[] cleans = {"  A\n\t B  ", new String(new char[200]).replace('\0', 'W'), "a b", "x  y", emoji.toString(), "\u0000\u0007ok\u007F", "",
                "SCAMERA  ·  ", new String(new char[63]).replace('\0', 'Q') + " tail"};
        out.append("    static let cleans: [(String, String)] = [\n");
        for (String s : cleans) out.append("        (").append(q(s)).append(", ").append(q(WatermarkText.clean(s))).append("),\n");
        out.append("    ]\n\n");

        // ───── layouts ─────
        List<Object[]> cases = new ArrayList<>();
        int[][] sizes = {{4000, 3000}, {3000, 4000}, {1600, 900}, {900, 1600}, {640, 480}, {120, 90}};
        Object[][] specs = {
                {"default"},
                {"all", with(ALL)},
                {"time", "pref_watermark_show_text", false, "pref_watermark_logo", false, "pref_watermark_show_time", true},
                {"badge_exif_br", with(ALL, "pref_watermark_show_text", false, "pref_watermark_show_time", false, "pref_watermark_layout", "badge", "pref_watermark_position", "br")},
                {"badge_all_tc", with(ALL, "pref_watermark_layout", "badge", "pref_watermark_position", "tc", "pref_watermark_size", "12")},
                {"bar_all_bl", with(ALL, "pref_watermark_layout", "bar", "pref_watermark_size", "6")},
                {"bar_all_tr", with(ALL, "pref_watermark_layout", "bar", "pref_watermark_position", "tr", "pref_watermark_font", "condensed")},
                {"bar_exif_only", with(ALL, "pref_watermark_layout", "bar", "pref_watermark_show_text", false, "pref_watermark_show_device", false, "pref_watermark_show_time", false, "pref_watermark_logo", false)},
                {"frame_all_bc", with(ALL, "pref_watermark_layout", "frame", "pref_watermark_position", "bc", "pref_watermark_size", "6")},
                {"frame_default_tl", "pref_watermark_layout", "frame", "pref_watermark_position", "tl", "pref_watermark_margin", "0"},
                {"frame_nothing", "pref_watermark_layout", "frame", "pref_watermark_show_text", false, "pref_watermark_logo", false, "pref_watermark_margin", "10"},
                {"text_long_20", "pref_watermark_text", new String(new char[200]).replace('\0', 'W'), "pref_watermark_size", "20", "pref_watermark_position", "br"},
                {"text_mono_all_tr", with(ALL, "pref_watermark_font", "mono", "pref_watermark_position", "tr", "pref_watermark_size", "3", "pref_watermark_margin", "10")},
                {"logo_only_tc", "pref_watermark_show_text", false, "pref_watermark_position", "tc", "pref_watermark_size", "20"},
                {"bar_long_br", with(ALL, "pref_watermark_layout", "bar", "pref_watermark_position", "br", "pref_watermark_size", "20",
                        "pref_watermark_text", "A long custom caption that will not fit")},
        };
        out.append("    struct Run { let text: String; let x, baseline, size: Float; let weight: Int; let main: Bool; let box: [Float] }\n");
        out.append("    struct Layout {\n        let name: String; let width, height: Int; let prefs: [String: Any]\n");
        out.append("        let unit, line, radius, windowRadius: Float; let runs: [Run]; let logo, plate, window, hairline: [Float]?\n");
        out.append("        let sample, bounds: [Float]; let area: [Int]\n    }\n\n");
        out.append("    static let layouts: [Layout] = [\n");
        for (int[] size : sizes)
            for (Object[] spec : specs) {
                Object[] kv = spec.length == 2 && spec[1] instanceof Object[] ? (Object[]) spec[1] : java.util.Arrays.copyOfRange(spec, 1, spec.length);
                Map<String, Object> m = p(kv);
                WatermarkSpec s = WatermarkSpec.from(new MapPrefs(m));
                WatermarkText t = WatermarkText.of(s, info());
                WatermarkPainter.Plan plan = WatermarkPainter.plan(s, t, size[0], size[1], FONTS);
                out.append("        Layout(name: ").append(q(spec[0] + " " + size[0] + "x" + size[1])).append(", width: ").append(size[0])
                        .append(", height: ").append(size[1]).append(", prefs: ").append(prefs(m)).append(",\n");
                out.append("               unit: ").append(f(plan.unit)).append(", line: ").append(f(plan.line)).append(", radius: ").append(f(plan.radius))
                        .append(", windowRadius: ").append(f(plan.windowRadius)).append(", runs: [");
                for (WatermarkPainter.Run run : plan.runs)
                    out.append("\n                   Run(text: ").append(q(run.text)).append(", x: ").append(f(run.x)).append(", baseline: ").append(f(run.baseline))
                            .append(", size: ").append(f(run.size)).append(", weight: ").append(run.weight).append(", main: ").append(run.main)
                            .append(", box: ").append(r(run.box)).append("),");
                out.append("],\n               logo: ").append(r(plan.logo)).append(", plate: ").append(r(plan.plate)).append(", window: ").append(r(plan.window))
                        .append(", hairline: ").append(r(plan.hairline)).append(",\n               sample: ").append(r(plan.sample)).append(", bounds: ")
                        .append(r(plan.bounds)).append(", area: [");
                boolean first = true;
                for (Rect a : WatermarkArea.of(plan)) {
                    out.append(first ? "" : ", ").append(a.left).append(", ").append(a.top).append(", ").append(a.right).append(", ").append(a.bottom);
                    first = false;
                }
                out.append("]),\n");
            }
        out.append("    ]\n}\n");
        java.io.PrintStream utf8 = new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                java.nio.charset.StandardCharsets.UTF_8);
        utf8.print(out);
        utf8.flush();
    }
}
