package com.scamera.app.processing.ultrahdr;

import android.graphics.Bitmap;

/**
 * The CPU end of the Ultra HDR gain map: PostPipeline's scene-anchored pass stores per pixel {@code v = log2(gain) / SCALE}
 * clamped to [0, 1] (gain: the anchored scene luminance over the linear SDR luminance, both plus {@link #DECODE_OFFSET}).
 * Here the map is box-filtered when asked ({@code down} pixels per axis, averaged in the log domain), its real log2 range is
 * found and it is requantised to fill [0, 255]; that range becomes GainMapMin / GainMapMax of the hdrgm XMP, from which any
 * ISO 21496-1 decoder rebuilds the HDR rendition for its display's headroom.
 */
public final class GainMapComputer {
    /**
     * The log2 range of the encoding (6 stops: up to a 64x boost; the uScale of ultrahdr/gainmap.glsl) and the hdrgm
     * OffsetSDR / OffsetHDR (1/64), which the shader adds to both luminances before their ratio so the decode is exact.
     */
    public static final float SCALE = 6f, DECODE_OFFSET = 1f / 64f;
    /** Downsample per axis of the map PostPipeline renders (1: the base's resolution). */
    public static final int SCALE_DOWN = 1;
    /** The top of the range sits this far above the largest boost (at least {@link #MIN_RANGE}): no pixel on the endpoint. */
    private static final float RANGE_PAD_FRACTION = 0.02f;
    private static final float MIN_RANGE = 1e-3f;
    private static final int OPAQUE = 0xFF << 24;

    private GainMapComputer() {
    }

    /** The finished map with its hdrgm range (log2 units). */
    public static class Result {
        /** Single-channel gain map, packed as R=G=B=value, A=255 (ARGB_8888). */
        public final Bitmap gainMap;
        public final float gainMapMin, gainMapMax;
        /**
         * HDRCapacityMax: never at or below HDRCapacityMin (0), where a viewer's headroom weight divides by zero or less
         * and every viewer clamps it its own way.
         */
        public final float hdrCapacityMax;
        public final int gainW, gainH;

        Result(Bitmap map, float min, float max) {
            gainMap = map;
            gainW = map.getWidth();
            gainH = map.getHeight();
            gainMapMin = min;
            gainMapMax = max;
            hdrCapacityMax = Math.max(max, 1e-3f);
        }
    }

    /**
     * The requantised map of the GPU's encoded map {@code src} (R=G=B in [0, 1]): {@code down} x {@code down} boxes averaged in
     * the log domain (a geometric mean of the gains; 1 keeps the resolution), the range [0, its largest boost padded].
     * {@code scale}: the encode's log2 range ({@link #SCALE}).
     *
     * <p>One band of {@code down} rows at a time, twice (the range, then the bytes): a whole-frame copy of a 50 MP map was
     * ~400 MB of Java heap and failed the encode, leaving the photo SDR.
     */
    public static Result compute(Bitmap src, int down, float scale) {
        final int w = src.getWidth(), h = src.getHeight();
        if (w <= 0 || h <= 0) throw new IllegalArgumentException("Empty gain map: " + w + "x" + h);
        final int box = Math.max(1, down);
        final MapReader reader = new MapReader(src, box, scale);
        final float gMax = rangeMax(box == 1 && scale > 0f && Float.isFinite(scale) ? boost(reader.largestByte(), scale) : reader.largestBoost());
        final Bitmap out = Bitmap.createBitmap(reader.outW, reader.outH, Bitmap.Config.ARGB_8888);
        final int[] packed = new int[reader.outW];
        if (box == 1) {
            // one byte in, one byte out: the requantisation table of exactly this arithmetic
            final int[] table = packedTable(requantisation(gMax, scale));
            for (int y = 0; y < reader.outH; y++) {
                final int[] in = reader.band(y);
                for (int x = 0; x < reader.outW; x++) packed[x] = table[(in[x] >> 16) & 0xFF];
                out.setPixels(packed, 0, reader.outW, 0, y, reader.outW, 1);
            }
        } else {
            final float range = gMax - 0f;
            for (int y = 0; y < reader.outH; y++) {
                final float[] boosts = reader.boosts(y);
                for (int x = 0; x < reader.outW; x++) packed[x] = grey(toByte(boosts[x], range));
                out.setPixels(packed, 0, reader.outW, 0, y, reader.outW, 1);
            }
        }
        return new Result(out, 0f, gMax);
    }

    /** Rows of the encoded map, a band of {@code box} rows at a time, as bytes or as box-averaged log2 boosts. */
    private static final class MapReader {
        final Bitmap src;
        final int box, w, h, outW, outH;
        final float scale;
        final int[] band;
        final float[] boosts;

        MapReader(Bitmap src, int box, float scale) {
            this.src = src;
            this.box = box;
            this.scale = scale;
            w = src.getWidth();
            h = src.getHeight();
            outW = Math.max(1, w / box);
            outH = Math.max(1, h / box);
            band = new int[w * box];
            boosts = new float[outW];
        }

        /** The ARGB pixels of output row {@code y}'s band (its last band may be shorter). */
        int[] band(int y) {
            final int top = y * box;
            src.getPixels(band, 0, w, 0, top, w, Math.min(box, h - top));
            return band;
        }

        /** Output row {@code y}'s log2 boosts: each box's mean byte over the encode range. */
        float[] boosts(int y) {
            final int top = y * box, rows = Math.min(box, h - top);
            band(y);
            for (int x = 0; x < outW; x++) {
                final int left = x * box, right = Math.min(left + box, w);
                float sum = 0f;
                int n = 0;
                for (int r = 0; r < rows; r++) {
                    for (int i = r * w + left, end = r * w + right; i < end; i++, n++) sum += (band[i] >> 16) & 0xFF;
                }
                boosts[x] = (sum / (n * 255f)) * scale;
            }
            return boosts;
        }

        /** The largest boost of the box-averaged map (negative infinity for none). */
        float largestBoost() {
            float top = Float.NEGATIVE_INFINITY;
            for (int y = 0; y < outH; y++) {
                for (float b : boosts(y)) if (b > top) top = b;
            }
            return top;
        }

        /**
         * The largest byte of the map's {@code outW} x {@code outH} corner (the pixels a box of 1 reads): with a positive
         * scale, its boost is the largest boost, as boost() rises with the byte.
         */
        int largestByte() {
            int top = 0;
            for (int y = 0; y < outH; y++) {
                final int[] in = band(y);
                for (int x = 0; x < outW; x++) top = Math.max(top, (in[x] >> 16) & 0xFF);
            }
            return top;
        }
    }

    /**
     * GainMapMax of a map whose largest log2 boost is {@code maxBoost}. Gains are non-negative by construction (headroom
     * >= 1), so the metadata range is anchored at exactly 0: identity gain for everything below white, matching the
     * midtone-exact behaviour of the shader; the top is padded so extreme pixels don't sit on the endpoint.
     */
    public static float rangeMax(float maxBoost) {
        final float top = Math.max(Float.isFinite(maxBoost) && maxBoost >= 0f ? maxBoost : 0f, MIN_RANGE);
        return top + Math.max(top * RANGE_PAD_FRACTION, MIN_RANGE);
    }

    /** The log2 boost of an encoded byte of the GPU map at full resolution (compute's box of 1). */
    public static float boost(int encoded, float scale) {
        return (encoded / 255f) * scale;
    }

    /** A boost as a byte of a map whose range is [0, {@code range}]: rounded, clamped (NaN: 0). */
    private static int toByte(float boost, float range) {
        float v = (boost - 0f) / range;
        if (v < 0f) v = 0f;
        else if (v > 1f) v = 1f;
        return Math.round(v * 255.0f);
    }

    private static int grey(int b) {
        return OPAQUE | b << 16 | b << 8 | b;
    }

    private static int[] packedTable(int[] bytes) {
        final int[] packed = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) packed[i] = grey(bytes[i]);
        return packed;
    }

    /**
     * P145: compute's requantisation at full resolution as a table - for every encoded byte of the GPU map, the byte it
     * becomes in a map whose range is [0, gMax] (ultrahdr/gainquant applies it on the GPU; the same arithmetic as compute).
     */
    public static int[] requantisation(float gMax, float scale) {
        final float range = gMax - 0f;
        final int[] table = new int[256];
        for (int v = 0; v < table.length; v++) table[v] = toByte(boost(v, scale), range);
        return table;
    }

    /** P145: a map the GPU already requantised to [0, gMax] (PostPipeline.RunHDRGainMap). */
    public static Result normalised(Bitmap map, float gMax) {
        return new Result(map, 0f, gMax);
    }
}
