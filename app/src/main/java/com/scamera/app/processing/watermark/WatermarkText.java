package com.scamera.app.processing.watermark;

import androidx.annotation.Nullable;

import com.scamera.app.util.Lang;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * P628: what the watermark says - three groups built from the fields that are on, each empty when none of its fields is: the
 * title (the custom text, the phone), the exposure (focal length, aperture, shutter, ISO) and the time (in the chosen format).
 * Nothing is forced: no «Shot on», no phone or app name unless its field is on; a field without a value (an unknown ISO) is left
 * out with its separator, so any subset - a single field included - reads without gaps.
 */
public final class WatermarkText {
    /** Between the title's parts and between the groups joined on one line. */
    public static final String JOIN = "  ·  ";
    /** Between the exposure's values («24mm  f/1.8  1/120s  ISO 100»). */
    public static final String EXIF_JOIN = "   ";

    public final String title, exif, time;
    public final boolean logo;

    WatermarkText(String title, String exif, String time, boolean logo) {
        this.title = title;
        this.exif = exif;
        this.time = time;
        this.logo = logo;
    }

    /** The shot's facts the watermark may show (each unknown: null / NaN / 0). */
    public static final class Info {
        @Nullable public String device;
        /** mm: the lens's own and its 35 mm equivalent (0: unknown; the equivalent is shown when known). */
        public double focalLength = Double.NaN;
        public int focal35;
        public double fNumber = Double.NaN;
        /** Seconds. */
        public double exposureTime = Double.NaN;
        public int iso;
        /** Wall clock ms (0: unknown) and its zone. */
        public long timeMs;
        @Nullable public TimeZone zone;

        /** The settings page's sample shot: the phone it runs on, 24 mm, f/1.8, 1/120 s, ISO 100, now. */
        public static Info sample(@Nullable String device, long now) {
            Info i = new Info();
            i.device = device;
            i.focalLength = 6.9;
            i.focal35 = 24;
            i.fNumber = 1.8;
            i.exposureTime = 1 / 120.0;
            i.iso = 100;
            i.timeMs = now;
            i.zone = TimeZone.getDefault();
            return i;
        }
    }

    /** The groups of {@code spec} for the shot {@code info}. */
    public static WatermarkText of(WatermarkSpec spec, Info info) {
        List<String> title = new ArrayList<>();
        if (spec.showText) add(title, clean(spec.text));
        if (spec.showDevice) add(title, clean(info.device));
        List<String> exif = new ArrayList<>();
        if (spec.showLens) add(exif, focal(info));
        if (spec.showAperture) add(exif, aperture(info.fNumber));
        if (spec.showShutter) add(exif, shutter(info.exposureTime));
        if (spec.showIso) add(exif, info.iso > 0 ? "ISO " + info.iso : "");
        String time = spec.showTime && info.timeMs > 0 ? time(info.timeMs, info.zone, spec.dateFormat, Lang.ru()) : "";
        return new WatermarkText(String.join(JOIN, title), String.join(EXIF_JOIN, exif), time, spec.logo);
    }

    /** Whether anything at all is drawn (a text group or the glyph). */
    public boolean any() {
        return logo || !title.isEmpty() || !exif.isEmpty() || !time.isEmpty();
    }

    /** The non-empty groups in order: title, exposure, time. */
    public List<String> groups() {
        List<String> out = new ArrayList<>(3);
        add(out, title);
        add(out, exif);
        add(out, time);
        return out;
    }

    /**
     * One column of at most two lines: the first group present is the main line (a lone date is the main line, not a small
     * second one), the others share the second line.
     */
    public List<String> column() {
        List<String> g = groups();
        if (g.size() <= 1) return g;
        List<String> out = new ArrayList<>(2);
        out.add(g.get(0));
        out.add(String.join(JOIN, g.subList(1, g.size())));
        return out;
    }

    /** The two sides of a band (bar, frame): the title over the time on one side, the exposure on the other. */
    public List<String> leading() {
        List<String> out = new ArrayList<>(2);
        add(out, title);
        add(out, time);
        if (out.isEmpty() && !exif.isEmpty()) out.add(exif); // a lone exposure moves to the leading side
        return out;
    }

    public List<String> trailing() {
        return title.isEmpty() && time.isEmpty() || exif.isEmpty() ? Collections.<String>emptyList() : Collections.singletonList(exif);
    }

    private static void add(List<String> out, @Nullable String s) {
        if (s != null && !s.isEmpty()) out.add(s);
    }

    /** Trimmed, control characters and line breaks as spaces, runs of spaces as one, at most {@link WatermarkSpec#MAX_TEXT}. */
    static String clean(@Nullable String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < ' ' || c == 0x7F || Character.isWhitespace(c)) { space = b.length() > 0; continue; }
            if (space) b.append(' ');
            space = false;
            b.append(c);
        }
        String out = b.toString();
        if (out.length() > WatermarkSpec.MAX_TEXT) {
            int end = WatermarkSpec.MAX_TEXT;
            if (Character.isHighSurrogate(out.charAt(end - 1))) end--;
            out = out.substring(0, end).trim();
        }
        return out;
    }

    /** «24mm» (the 35 mm equivalent), else the lens's own «6.9mm»; "" unknown. */
    static String focal(Info info) {
        if (info.focal35 > 0) return info.focal35 + "mm";
        if (!(info.focalLength > 0)) return "";
        return number(info.focalLength) + "mm";
    }

    /** «f/1.8»; "" unknown. */
    static String aperture(double f) {
        if (!(f > 0) || Double.isInfinite(f)) return "";
        return "f/" + String.format(Locale.US, "%.1f", f);
    }

    /** «1/120s», «0.5s», «2s», «1.5s»; "" unknown. */
    static String shutter(double t) {
        if (!(t > 0) || Double.isInfinite(t)) return "";
        if (t < .3) return "1/" + Math.max(1, Math.round(1 / t)) + "s";
        return number(t) + "s";
    }

    /** One decimal, a whole number without it. */
    static String number(double v) {
        double r = Math.round(v * 10) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.format(Locale.US, "%.1f", r);
    }

    /** The time of {@code ms} in {@code zone} (null: the phone's) in {@code format}, 24 hours; {@code ru}: the month word in Russian. */
    public static String time(long ms, @Nullable TimeZone zone, WatermarkSpec.DateFormat format, boolean ru) {
        final String pattern;
        Locale locale = Locale.US;
        switch (format) {
            case DATE: pattern = "dd.MM.yyyy"; break;
            case TIME: pattern = "HH:mm"; break;
            case ISO: pattern = "yyyy-MM-dd HH:mm"; break;
            case TEXT:
                pattern = "d MMMM yyyy, HH:mm";
                locale = ru ? new Locale("ru") : Locale.ENGLISH;
                break;
            default: pattern = "dd.MM.yyyy  HH:mm"; break;
        }
        SimpleDateFormat f = new SimpleDateFormat(pattern, locale);
        f.setTimeZone(zone != null ? zone : TimeZone.getDefault());
        return f.format(new Date(ms));
    }
}
