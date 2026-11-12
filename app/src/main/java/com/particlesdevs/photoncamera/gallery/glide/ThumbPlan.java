package com.particlesdevs.photoncamera.gallery.glide;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * P620: the pure part of the gallery's thumbnail decode ({@link ThumbDecoder}): what a file is (by its first bytes, then its
 * name), the size a thumbnail needs, the power-of-two sample a decode takes, whether an embedded thumbnail is large enough, and
 * the JPEG header (frame size, progressive, EXIF orientation, the EXIF thumbnail's place and size). No Android classes.
 */
public final class ThumbPlan {
    private ThumbPlan() {}

    public enum Kind { JPEG, HEIF, AVIF, PNG, WEBP, GIF, BMP, DNG, RAW, VIDEO, OTHER }

    /** An embedded thumbnail is used when it has at least this share of the size the request needs (a little upscale). */
    static final double ENOUGH = 0.8;
    /** ... and the aspect of the main picture within this (a 4:3 thumbnail of a 16:9 photo has bars). */
    static final double ASPECT_TOLERANCE = 0.02;

    // ---------------------------------------------------------------- kind

    /** The kind of a file from its first {@code n} bytes, else (a video, an unknown header) from its name. */
    public static Kind kindOf(@Nullable String name, byte[] head, int n) {
        final String ext = extension(name);
        if (n >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) return Kind.JPEG;
        if (n >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') return Kind.PNG;
        if (n >= 12 && ascii(head, 0, "RIFF") && ascii(head, 8, "WEBP")) return Kind.WEBP;
        if (n >= 6 && ascii(head, 0, "GIF8")) return Kind.GIF;
        if (n >= 2 && head[0] == 'B' && head[1] == 'M') return Kind.BMP;
        if (com.particlesdevs.photoncamera.gallery.dng.DngPreview.isTiff(head, n)) {
            if (com.particlesdevs.photoncamera.gallery.raw.RawFormats.looksDng(head, n) || "dng".equals(ext)) return Kind.DNG;
            return Kind.RAW;
        }
        if (com.particlesdevs.photoncamera.gallery.files.GalleryVideo.isVideoName(name)) return Kind.VIDEO;
        if (n >= 12 && ascii(head, 4, "ftyp")) {
            if (AvifGlideDecoder.isAvif(head, n)) return Kind.AVIF;
            if (heifBrand(head, n)) return Kind.HEIF;
            return Kind.VIDEO; // mp4 / 3gp / mov brands
        }
        if (com.particlesdevs.photoncamera.gallery.raw.RawFormats.looksCameraRaw(head, n)) return Kind.RAW; // CR3, RAF, X3F...
        switch (ext) {
            case "heic": case "heif": return Kind.HEIF;
            case "avif": return Kind.AVIF;
            default: return com.particlesdevs.photoncamera.gallery.raw.RawFormats.isRawName(name) ? Kind.RAW : Kind.OTHER;
        }
    }

    private static boolean heifBrand(byte[] h, int n) {
        int box = (int) u32(h, 0, false);
        int limit = box > 0 && box < n ? box : n;
        for (int i = 8; i + 4 <= limit; i += 4) {
            if (i == 12) continue; // minor_version
            String b = new String(h, i, 4, java.nio.charset.StandardCharsets.US_ASCII);
            switch (b) {
                case "heic": case "heix": case "hevc": case "hevx": case "heim": case "heis": case "hevm": case "hevs": case "mif1": case "msf1":
                    return true;
                default: break;
            }
        }
        return false;
    }

    static String extension(@Nullable String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- size math

    /**
     * The size of the thumbnail of a {@code w x h} picture for a {@code reqW x reqH} request, never larger than the picture:
     * {@code cover} - the smallest size whose sides both reach the request (a grid cell is cropped from it), else the largest that
     * fits inside the request. {w, h}.
     */
    public static int[] targetSize(int w, int h, int reqW, int reqH, boolean cover) {
        if (w <= 0 || h <= 0 || reqW <= 0 || reqH <= 0) return new int[]{Math.max(w, 0), Math.max(h, 0)};
        double sx = (double) reqW / w, sy = (double) reqH / h;
        double s = Math.min(1.0, cover ? Math.max(sx, sy) : Math.min(sx, sy));
        return new int[]{Math.max(1, (int) Math.round(w * s)), Math.max(1, (int) Math.round(h * s))};
    }

    /**
     * The largest power-of-two sample of a {@code w x h} decode that still gives at least {@link #targetSize} (the decoder then
     * scales the rest down exactly). 1 when the picture is not larger than the target.
     */
    public static int sampleSize(int w, int h, int reqW, int reqH, boolean cover) {
        int[] t = targetSize(w, h, reqW, reqH, cover);
        int s = 1;
        while (s < (1 << 20) && w / (s * 2) >= t[0] && h / (s * 2) >= t[1]) s *= 2;
        return s;
    }

    /**
     * Whether an embedded {@code tw x th} thumbnail of a {@code w x h} picture (both as stored, before the EXIF orientation) can
     * stand for it at this request: the same aspect and at least {@link #ENOUGH} of the target size on both sides.
     */
    public static boolean enough(int tw, int th, int w, int h, int reqW, int reqH, boolean cover) {
        if (tw <= 0 || th <= 0 || w <= 0 || h <= 0) return false;
        if (Math.abs((double) tw / th - (double) w / h) > ASPECT_TOLERANCE * ((double) w / h)) return false;
        int[] t = targetSize(w, h, reqW, reqH, cover);
        return tw >= ENOUGH * t[0] && th >= ENOUGH * t[1];
    }

    /** Whether a decoded {@code bw x bh} bitmap is worth an exact downscale to {@code t} (more than 25 % more pixels on a side). */
    public static boolean worthScaling(int bw, int bh, int[] t) {
        return bw > 1.25 * t[0] || bh > 1.25 * t[1];
    }

    // ---------------------------------------------------------------- JPEG header

    /** A JPEG's header: the frame, the EXIF orientation and the EXIF (IFD1) thumbnail. Offsets are into the parsed buffer. */
    public static final class Jpeg {
        public int width, height;
        public boolean progressive;
        public int orientation = 1;
        public int thumbOffset = -1, thumbLength;
        public int thumbWidth, thumbHeight;

        public boolean hasThumb() { return thumbOffset >= 0 && thumbLength > 0; }
    }

    /**
     * The header of the JPEG in {@code b} (absolute reads from 0 to its limit): the markers up to the first frame (SOFn), the
     * EXIF APP1 on the way. Null when it is not a JPEG or no frame is found.
     */
    @Nullable
    public static Jpeg parseJpeg(ByteBuffer b) {
        final int limit = b.limit();
        if (limit < 4 || (b.get(0) & 0xFF) != 0xFF || (b.get(1) & 0xFF) != 0xD8) return null;
        Jpeg j = new Jpeg();
        int p = 2;
        while (p + 4 <= limit) {
            if ((b.get(p) & 0xFF) != 0xFF) return null;
            int marker = b.get(p + 1) & 0xFF;
            if (marker == 0xFF) { p++; continue; } // fill
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { p += 2; continue; }
            if (marker == 0xD9 || marker == 0xDA) return null; // no frame before the scan
            int len = ((b.get(p + 2) & 0xFF) << 8) | (b.get(p + 3) & 0xFF);
            if (len < 2 || p + 2 + len > limit) return null;
            int seg = p + 4, segEnd = p + 2 + len;
            if (marker == 0xE1 && len >= 8 && j.thumbOffset < 0 && b.get(seg) == 'E' && b.get(seg + 1) == 'x' && b.get(seg + 2) == 'i'
                    && b.get(seg + 3) == 'f' && b.get(seg + 4) == 0 && b.get(seg + 5) == 0) {
                exif(b, seg + 6, segEnd, j);
            } else if (isSof(marker)) {
                if (len < 8) return null;
                j.height = ((b.get(seg + 1) & 0xFF) << 8) | (b.get(seg + 2) & 0xFF);
                j.width = ((b.get(seg + 3) & 0xFF) << 8) | (b.get(seg + 4) & 0xFF);
                j.progressive = marker == 0xC2 || marker == 0xC6 || marker == 0xCA || marker == 0xCE;
                return j.width > 0 && j.height > 0 ? j : null;
            }
            p = segEnd;
        }
        return null;
    }

    /** SOF0..SOF15 except DHT (C4), JPG (C8) and DAC (CC). */
    static boolean isSof(int m) {
        return m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC;
    }

    /** The TIFF structure of an EXIF APP1 at [{@code t}, {@code end}): IFD0's orientation, IFD1's JPEG thumbnail. */
    private static void exif(ByteBuffer b, int t, int end, Jpeg j) {
        try {
            if (t + 8 > end) return;
            boolean le;
            if (b.get(t) == 'I' && b.get(t + 1) == 'I') le = true;
            else if (b.get(t) == 'M' && b.get(t + 1) == 'M') le = false;
            else return;
            if (u16(b, t + 2, le) != 42) return;
            long ifd0 = u32(b, t + 4, le);
            if (ifd0 < 8 || t + ifd0 + 2 > end) return;
            int at = (int) (t + ifd0);
            int n = u16(b, at, le);
            if (at + 2 + 12L * n + 4 > end) return;
            for (int i = 0; i < n; i++) {
                int e = at + 2 + 12 * i;
                if (u16(b, e, le) == 0x112) j.orientation = u16(b, e + 8, le);
            }
            long ifd1 = u32(b, at + 2 + 12 * n, le);
            if (ifd1 < 8 || t + ifd1 + 2 > end) return;
            at = (int) (t + ifd1);
            n = u16(b, at, le);
            if (at + 2 + 12L * n > end) return;
            long off = -1, len = 0;
            for (int i = 0; i < n; i++) {
                int e = at + 2 + 12 * i;
                int tag = u16(b, e, le), type = u16(b, e + 2, le);
                long v = type == 3 ? u16(b, e + 8, le) : u32(b, e + 8, le);
                if (tag == 0x201) off = v;
                else if (tag == 0x202) len = v;
            }
            if (off <= 0 || len <= 0 || t + off + len > end) return;
            ByteBuffer thumb = b.duplicate();
            thumb.position((int) (t + off));
            thumb.limit((int) (t + off + len));
            Jpeg th = parseJpeg(thumb.slice());
            if (th == null) return;
            j.thumbOffset = (int) (t + off);
            j.thumbLength = (int) len;
            j.thumbWidth = th.width;
            j.thumbHeight = th.height;
        } catch (IndexOutOfBoundsException ignored) {
            // a damaged EXIF: no thumbnail, the orientation read so far
        }
    }

    // ---------------------------------------------------------------- orientation

    /** The EXIF orientation's rotation (0, 90, 180, 270) when it only rotates; -1 for a mirrored one (2, 4, 5, 7). */
    public static int rotationOf(int orientation) {
        switch (orientation) {
            case 3: return 180;
            case 6: return 90;
            case 8: return 270;
            case 2: case 4: case 5: case 7: return -1;
            default: return 0;
        }
    }

    private static boolean ascii(byte[] b, int at, String s) {
        if (at + s.length() > b.length) return false;
        for (int i = 0; i < s.length(); i++) if (b[at + i] != s.charAt(i)) return false;
        return true;
    }

    private static int u16(ByteBuffer b, int at, boolean le) {
        int a = b.get(at) & 0xFF, c = b.get(at + 1) & 0xFF;
        return le ? a | (c << 8) : (a << 8) | c;
    }

    private static long u32(ByteBuffer b, int at, boolean le) {
        return b.duplicate().order(le ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN).getInt(at) & 0xFFFFFFFFL;
    }

    private static long u32(byte[] h, int at, boolean le) {
        if (at + 4 > h.length) return 0;
        return le ? (h[at] & 0xFFL) | (h[at + 1] & 0xFFL) << 8 | (h[at + 2] & 0xFFL) << 16 | (h[at + 3] & 0xFFL) << 24
                : (h[at] & 0xFFL) << 24 | (h[at + 1] & 0xFFL) << 16 | (h[at + 2] & 0xFFL) << 8 | (h[at + 3] & 0xFFL);
    }
}
