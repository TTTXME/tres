package com.particlesdevs.photoncamera.gallery.glide;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * P620 (owner, 2026-11-11: «200мп в галерее тоже ускорь»): a baseline JPEG whose restart interval is a whole number of MCU rows
 * (the camera's own 50 / 200 MP JPEGs: strips encoded in parallel, joined with RSTn, P147) split into one small standalone JPEG
 * per strip - the shared header with the strip's height in SOF and no DRI, the strip's entropy-coded segment, EOI - so the
 * strips decode on several cores; one BitmapFactory decode entropy-decodes the whole 60-100 MB of a 200 MP file on one core.
 * Pure Java over a ByteBuffer; the Android side is {@link ThumbDecoder}.
 */
public final class JpegStrips {
    /** The header bytes before SOF's height, the bytes after it up to and with SOS, without DRI and APP1 / MPF segments. */
    final byte[] head, tail;
    public final int width, height, stripRows;
    /** The entropy-coded segments: [offset, length] per strip, in the parsed buffer. */
    final List<int[]> segments;

    private JpegStrips(byte[] head, byte[] tail, int width, int height, int stripRows, List<int[]> segments) {
        this.head = head;
        this.tail = tail;
        this.width = width;
        this.height = height;
        this.stripRows = stripRows;
        this.segments = segments;
    }

    public int strips() {
        return segments.size();
    }

    /** The first picture row of strip {@code i}, and its height. */
    public int y0(int i) {
        return i * stripRows;
    }

    public int rows(int i) {
        return Math.min(stripRows, height - i * stripRows);
    }

    /** Strip {@code i} as a standalone baseline JPEG. */
    public byte[] strip(ByteBuffer b, int i) {
        int[] s = segments.get(i);
        int h = rows(i);
        byte[] out = new byte[head.length + 2 + tail.length + s[1] + 2];
        int p = 0;
        System.arraycopy(head, 0, out, p, head.length);
        p += head.length;
        out[p++] = (byte) (h >> 8);
        out[p++] = (byte) h;
        System.arraycopy(tail, 0, out, p, tail.length);
        p += tail.length;
        ByteBuffer d = b.duplicate();
        d.position(s[0]);
        d.get(out, p, s[1]);
        p += s[1];
        out[p++] = (byte) 0xFF;
        out[p] = (byte) 0xD9;
        return out;
    }

    /**
     * The strips of the JPEG in {@code b}, or null when it is not one this can split: not baseline (SOF0 / SOF1), not one
     * interleaved scan, no restart interval, an interval that is not whole MCU rows, fewer than {@code minStrips} strips, or RST
     * markers that do not match the interval.
     */
    @Nullable
    public static JpegStrips parse(ByteBuffer b, int minStrips) {
        final int limit = b.limit();
        if (limit < 4 || (b.get(0) & 0xFF) != 0xFF || (b.get(1) & 0xFF) != 0xD8) return null;
        ByteArrayOutputStream head = new ByteArrayOutputStream(), tail = new ByteArrayOutputStream();
        head.write(0xFF);
        head.write(0xD8);
        int width = 0, height = 0, interval = 0, mcuW = 8, mcuH = 8, comps = 0;
        boolean sof = false;
        int p = 2, scan = -1;
        while (p + 4 <= limit) {
            if ((b.get(p) & 0xFF) != 0xFF) return null;
            int m = b.get(p + 1) & 0xFF;
            if (m == 0xFF) { p++; continue; }
            if (m == 0xD9 || (m >= 0xD0 && m <= 0xD7) || m == 0x01) return null;
            int len = ((b.get(p + 2) & 0xFF) << 8) | (b.get(p + 3) & 0xFF);
            if (len < 2 || p + 2 + len > limit) return null;
            int seg = p + 4;
            ByteArrayOutputStream to = sof ? tail : head;
            if (m == 0xC0 || m == 0xC1) {
                if (sof || len < 8) return null;
                height = u16(b, seg + 1);
                width = u16(b, seg + 3);
                comps = b.get(seg + 5) & 0xFF;
                if (len < 8 + 3 * comps || comps < 1) return null;
                int hmax = 1, vmax = 1;
                for (int c = 0; c < comps; c++) {
                    int hv = b.get(seg + 6 + 3 * c + 1) & 0xFF;
                    hmax = Math.max(hmax, hv >> 4);
                    vmax = Math.max(vmax, hv & 15);
                }
                mcuW = 8 * hmax;
                mcuH = 8 * vmax;
                // SOF up to the height (marker, length, precision), then the rest after it
                for (int i = p; i < seg + 1; i++) head.write(b.get(i));
                for (int i = seg + 3; i < p + 2 + len; i++) tail.write(b.get(i));
                sof = true;
                p += 2 + len;
                continue;
            }
            if (m == 0xC2 || m == 0xC3 || (m >= 0xC5 && m <= 0xCF && m != 0xC8 && m != 0xCC)) return null; // progressive, lossless...
            if (m == 0xDD) {
                if (len != 4) return null;
                interval = u16(b, seg);
                p += 2 + len;
                continue;
            }
            boolean drop = m == 0xE1 || (m == 0xE2 && len >= 6 && b.get(seg) == 'M' && b.get(seg + 1) == 'P' && b.get(seg + 2) == 'F');
            if (!drop) for (int i = p; i < p + 2 + len; i++) to.write(b.get(i));
            if (m == 0xDA) {
                if (!sof || (b.get(seg) & 0xFF) != comps) return null; // one scan of every component
                scan = p + 2 + len;
                break;
            }
            p += 2 + len;
        }
        if (!sof || scan < 0 || interval <= 0 || width <= 0 || height <= 0) return null;
        final int mcusPerRow = (width + mcuW - 1) / mcuW;
        if (interval % mcusPerRow != 0) return null;
        final int stripRows = interval / mcusPerRow * mcuH;
        final int strips = (height + stripRows - 1) / stripRows;
        if (strips < Math.max(2, minStrips)) return null;
        // the RSTn markers (FF D0..D7; entropy data stuffs FF with 00) and the end of the scan, found in parallel chunks: a
        // marker is an FF followed by neither 00 nor FF (a fill byte), wherever the chunk starts
        final int from = scan, chunks = Math.max(1, Math.min(16, (limit - from) >> 22));
        final int per = (limit - from + chunks - 1) / chunks;
        final List<List<int[]>> found = new ArrayList<>();
        for (int c = 0; c < chunks; c++) found.add(new ArrayList<>());
        java.util.stream.IntStream.range(0, chunks).parallel().forEach(c -> {
            int s0 = from + c * per, e0 = Math.min(limit - 1, s0 + per);
            byte[] chunk = new byte[Math.max(0, e0 - s0 + 1)];
            ByteBuffer d = b.duplicate();
            d.position(s0);
            d.get(chunk, 0, chunk.length);
            List<int[]> out = found.get(c);
            for (int i = 0; i + 1 < chunk.length; i++) {
                if (chunk[i] != (byte) 0xFF) continue;
                int v = chunk[i + 1] & 0xFF;
                if (v == 0x00 || v == 0xFF) continue;
                out.add(new int[]{s0 + i, v});
                if (v < 0xD0 || v > 0xD7) break; // the end of the scan
            }
        });
        List<int[]> segments = new ArrayList<>(strips);
        int start = scan, expect = 0;
        boolean ended = false;
        for (List<int[]> l : found) {
            for (int[] mk : l) {
                int v = mk[1];
                if (v >= 0xD0 && v <= 0xD7) {
                    if (v - 0xD0 != (expect & 7)) return null;
                    segments.add(new int[]{start, mk[0] - start});
                    start = mk[0] + 2;
                    expect++;
                } else {
                    segments.add(new int[]{start, mk[0] - start}); // EOI or any other marker ends the scan
                    ended = true;
                    break;
                }
            }
            if (ended) break;
        }
        if (!ended) return null;
        if (segments.size() != strips) return null;
        return new JpegStrips(head.toByteArray(), tail.toByteArray(), width, height, stripRows, segments);
    }

    private static int u16(ByteBuffer b, int at) {
        return ((b.get(at) & 0xFF) << 8) | (b.get(at + 1) & 0xFF);
    }
}
