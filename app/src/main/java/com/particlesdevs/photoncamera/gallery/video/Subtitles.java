package com.particlesdevs.photoncamera.gallery.video;

import android.content.Context;
import android.media.MediaExtractor;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P499: the player's subtitles - SubRip (.srt) and WebVTT (.vtt) files beside the video or picked by hand, and the text tracks inside
 * the file (MP4 tx3g / WebVTT, Matroska SubRip / WebVTT). Cues are kept sorted; {@link #at(List, long)} gives the text at a time.
 */
public final class Subtitles {
    private Subtitles() {}

    public static final class Cue {
        public final long startMs, endMs;
        public final String text;

        public Cue(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    private static final Pattern TIMING = Pattern.compile(
            "((?:\\d+:)?\\d{1,2}:\\d{1,2}[,.]\\d{1,3})\\s*-->\\s*((?:\\d+:)?\\d{1,2}:\\d{1,2}[,.]\\d{1,3})");
    private static final long LAST_CUE_MS = 5000, LONGEST_MS = 10_000;

    static boolean isTextMime(String mime) {
        return mime.startsWith("text/") || "application/x-subrip".equals(mime) || "application/x-quicktime-tx3g".equals(mime);
    }

    /** «01:02:03,456», «02:03.456», «2:03.4» in ms; -1 when unreadable. */
    static long time(String t) {
        try {
            String[] main = t.trim().replace(',', '.').split("\\.");
            String[] parts = main[0].split(":");
            long ms = 0;
            for (String p : parts) ms = ms * 60 + Long.parseLong(p.trim());
            ms *= 1000;
            if (main.length > 1) {
                String frac = (main[1] + "00").substring(0, 3);
                ms += Long.parseLong(frac);
            }
            return ms;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Drops the markup (<i>, <font ...>, {\an8}) the player does not draw. */
    static String clean(String s) {
        return s.replaceAll("<[^>]*>", "").replaceAll("\\{\\\\[^}]*\\}", "").replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").trim();
    }

    /** SubRip or WebVTT text: every «start --> end» line and the text lines under it up to an empty line. */
    public static List<Cue> parse(String source) {
        List<Cue> cues = new ArrayList<>();
        String[] lines = source.replace("﻿", "").replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher m = TIMING.matcher(lines[i]);
            if (!m.find()) continue;
            long a = time(m.group(1)), b = time(m.group(2));
            StringBuilder text = new StringBuilder();
            int k = i + 1;
            for (; k < lines.length && !lines[k].trim().isEmpty(); k++) {
                if (TIMING.matcher(lines[k]).find()) break;
                if (text.length() > 0) text.append('\n');
                text.append(lines[k]);
            }
            i = k - 1;
            String t = clean(text.toString());
            if (a >= 0 && b > a && !t.isEmpty()) cues.add(new Cue(a, b, t));
        }
        Collections.sort(cues, (x, y) -> Long.compare(x.startMs, y.startMs));
        return cues;
    }

    /** The text shown at {@code ms} (overlapping cues joined by a line), or null. */
    @Nullable
    public static String at(List<Cue> cues, long ms) {
        int lo = 0, hi = cues.size() - 1, last = -1;
        while (lo <= hi) { // the last cue starting at or before ms
            int mid = (lo + hi) >>> 1;
            if (cues.get(mid).startMs <= ms) {
                last = mid;
                lo = mid + 1;
            } else hi = mid - 1;
        }
        StringBuilder out = null;
        for (int i = last; i >= 0 && i > last - 8; i--) {
            Cue c = cues.get(i);
            if (ms >= c.startMs && ms < c.endMs) {
                if (out == null) out = new StringBuilder(c.text);
                else out.insert(0, c.text + "\n");
            }
        }
        return out == null ? null : out.toString();
    }

    /** Cues of in-file samples: each lasts until the next sample (an empty one clears), at most 10 s. */
    static List<Cue> fromSamples(List<Long> startsMs, List<String> texts) {
        List<Cue> cues = new ArrayList<>();
        for (int i = 0; i < startsMs.size(); i++) {
            String t = clean(texts.get(i));
            if (t.isEmpty()) continue;
            long a = startsMs.get(i);
            long b = i + 1 < startsMs.size() ? startsMs.get(i + 1) : a + LAST_CUE_MS;
            b = Math.min(b, a + LONGEST_MS);
            if (b > a) cues.add(new Cue(a, b, t));
        }
        return cues;
    }

    /** One in-file sample as text, by the track's MIME type. */
    static String sampleText(String mime, byte[] data, int n) {
        if (n <= 0) return "";
        if ("text/3gpp-tt".equals(mime) || "application/x-quicktime-tx3g".equals(mime)) {
            if (n < 2) return "";
            int len = Math.min(n - 2, ((data[0] & 0xFF) << 8) | (data[1] & 0xFF));
            if (len >= 2 && (data[2] & 0xFF) == 0xFE && (data[3] & 0xFF) == 0xFF)
                return new String(data, 4, len - 2, StandardCharsets.UTF_16BE);
            return new String(data, 2, Math.max(0, len), StandardCharsets.UTF_8);
        }
        if ("text/vtt".equals(mime)) {
            String payl = box(data, 0, n, "payl");
            if (payl != null) return payl;
            if (n >= 8 && "vtte".equals(new String(data, 4, 4, StandardCharsets.ISO_8859_1))) return "";
        }
        return new String(data, 0, n, StandardCharsets.UTF_8);
    }

    /** The text of the first {@code type} box (an MP4 WebVTT sample: vttc > payl), searching inside vttc boxes. */
    @Nullable
    private static String box(byte[] d, int from, int to, String type) {
        int p = from;
        while (p + 8 <= to) {
            int size = ((d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
            if (size < 8 || p + size > to) return null;
            String t = new String(d, p + 4, 4, StandardCharsets.ISO_8859_1);
            if (t.equals(type)) return new String(d, p + 8, size - 8, StandardCharsets.UTF_8);
            if (t.equals("vttc")) {
                String inner = box(d, p + 8, p + size, type);
                if (inner != null) return inner;
            }
            p += size;
        }
        return null;
    }

    /** Reads every sample of an in-file text track (off the main thread). */
    static List<Cue> readTrack(Context c, Uri video, VideoEngine.Track track) throws java.io.IOException {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(c, video, null);
            ex.selectTrack(track.index);
            ByteBuffer buf = ByteBuffer.allocate(1 << 20);
            byte[] arr = new byte[1 << 20];
            List<Long> starts = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            while (true) {
                buf.clear();
                int n = ex.readSampleData(buf, 0);
                if (n < 0) break;
                buf.get(arr, 0, n);
                starts.add(ex.getSampleTime() / 1000);
                texts.add(sampleText(track.mime, arr, n));
                if (!ex.advance()) break;
            }
            return fromSamples(starts, texts);
        } finally {
            ex.release();
        }
    }

    /** A subtitle file (UTF-8, or Windows-1251 when it is not valid UTF-8) from a content / file URI. */
    static List<Cue> readFile(Context c, Uri uri) throws java.io.IOException {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.FileNotFoundException(uri.toString());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0 && out.size() < 8 << 20) out.write(b, 0, n);
            return parse(decode(out.toByteArray()));
        }
    }

    static String decode(byte[] bytes) {
        java.nio.charset.CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder();
        try {
            return utf8.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            try {
                return new String(bytes, "windows-1251"); // old Russian .srt files
            } catch (java.io.UnsupportedEncodingException x) {
                return new String(bytes, StandardCharsets.ISO_8859_1);
            }
        }
    }

    /** «name.srt» / «name.vtt» / «name.ru.srt»... beside {@code videoPath} (a file path), or null. */
    @Nullable
    static java.io.File besideVideo(@Nullable String videoPath) {
        if (videoPath == null) return null;
        java.io.File v = new java.io.File(videoPath);
        java.io.File dir = v.getParentFile();
        if (dir == null) return null;
        String name = v.getName();
        int dot = name.lastIndexOf('.');
        String stem = (dot > 0 ? name.substring(0, dot) : name).toLowerCase(Locale.ROOT);
        java.io.File[] files;
        try {
            files = dir.listFiles();
        } catch (SecurityException e) {
            return null;
        }
        if (files == null) return null;
        for (java.io.File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.startsWith(stem + ".") && (n.endsWith(".srt") || n.endsWith(".vtt")) && f.canRead()) return f;
        }
        return null;
    }
}
