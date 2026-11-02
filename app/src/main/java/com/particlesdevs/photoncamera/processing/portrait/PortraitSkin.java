package com.particlesdevs.photoncamera.processing.portrait;

public final class PortraitSkin {
    private PortraitSkin() {}

    public static final int MAX_FACES = 5;
    public static final float MAX_TURN = (float) Math.toRadians(10.0);
    private static final float HUE_FROM = -35f;
    private static final int HUE_BINS = 150;

    public static final class Result {
        public int count;
        public final float[] faces = new float[MAX_FACES * 4];
        public final float[] adjust = new float[MAX_FACES * 4];
        public final int[] samples = new int[MAX_FACES];

        public boolean corrected() {
            for (int i = 0; i < count; i++) if (adjust[i * 4] != 0f) return true;
            return false;
        }
    }

    // shortcut: skin colour alone cannot identify the illuminant; only clear casts outside a broad hue dead zone move, by at most 10 degrees.
    public static Result measure(float[] pixels, int channels, int width, int height, float[] boxes, float strength) {
        Result out = new Result();
        if (pixels == null || boxes == null || channels < 3 || width <= 0 || height <= 0
                || (long) width * height * channels > pixels.length || !Float.isFinite(strength) || strength <= 0f) return out;
        strength = Math.min(strength, 1f);
        for (int at = 0; at + 3 < boxes.length && out.count < MAX_FACES; at += 4) {
            if (!Float.isFinite(boxes[at]) || !Float.isFinite(boxes[at + 1])
                    || !Float.isFinite(boxes[at + 2]) || !Float.isFinite(boxes[at + 3])) continue;
            final float l = clamp(boxes[at], 0f, 1f), t = clamp(boxes[at + 1], 0f, 1f);
            final float r = clamp(boxes[at + 2], 0f, 1f), b = clamp(boxes[at + 3], 0f, 1f);
            if (r <= l || b <= t) continue;
            final int face = out.count++, to = face * 4;
            out.faces[to] = l; out.faces[to + 1] = t; out.faces[to + 2] = r; out.faces[to + 3] = b;
            final int x0 = Math.max(0, (int) Math.ceil(l * width - 0.5f));
            final int y0 = Math.max(0, (int) Math.ceil(t * height - 0.5f));
            final int x1 = Math.min(width, (int) Math.ceil(r * width - 0.5f));
            final int y1 = Math.min(height, (int) Math.ceil(b * height - 0.5f));
            final int sx = Math.max(1, (x1 - x0 + 63) / 64), sy = Math.max(1, (y1 - y0 + 63) / 64);
            final double[] hist = new double[HUE_BINS];
            double weight = 0;
            int tested = 0, accepted = 0;
            for (int y = y0; y < y1; y += sy) {
                for (int x = x0; x < x1; x += sx) {
                    final float ex = ((x + 0.5f) / width - (l + r) * 0.5f) / ((r - l) * 0.46f);
                    final float ey = ((y + 0.5f) / height - (t + b) * 0.5f) / ((b - t) * 0.48f);
                    final float ellipse = 1f - smooth(0.65f, 1f, ex * ex + ey * ey);
                    if (ellipse <= 0f) continue;
                    tested++;
                    final int p = (y * width + x) * channels;
                    final double rr = pixels[p], gg = pixels[p + 1], bb = pixels[p + 2];
                    if (!Double.isFinite(rr) || !Double.isFinite(gg) || !Double.isFinite(bb)
                            || Math.min(rr, Math.min(gg, bb)) <= 0.0001 || Math.max(rr, Math.max(gg, bb)) >= 0.98) continue;
                    final double yy = 0.2126 * rr + 0.7152 * gg + 0.0722 * bb;
                    if (yy < 0.008) continue;
                    final double ll = Math.cbrt(0.4122214708 * rr + 0.5363325363 * gg + 0.0514459929 * bb);
                    final double mm = Math.cbrt(0.2119034982 * rr + 0.6806995451 * gg + 0.1073969566 * bb);
                    final double ss = Math.cbrt(0.0883024619 * rr + 0.2817188376 * gg + 0.6299787005 * bb);
                    final double light = 0.2104542553 * ll + 0.7936177850 * mm - 0.0040720468 * ss;
                    final double aa = 1.9779984951 * ll - 2.4285922050 * mm + 0.4505937099 * ss;
                    final double ab = 0.0259040371 * ll + 0.7827717662 * mm - 0.8086757660 * ss;
                    final float hue = (float) Math.toDegrees(Math.atan2(ab, aa));
                    final float ratio = (float) (Math.hypot(aa, ab) / Math.max(light, 0.0001));
                    final float confidence = smooth(-35f, -15f, hue) * (1f - smooth(95f, 115f, hue))
                            * smooth(0.03f, 0.05f, ratio) * (1f - smooth(0.30f, 0.40f, ratio))
                            * smooth(0.12f, 0.20f, (float) light);
                    if (confidence <= 0.05f) continue;
                    final double w = ellipse * confidence;
                    final int bin = Math.max(0, Math.min(HUE_BINS - 1, (int) (hue - HUE_FROM)));
                    hist[bin] += w;
                    weight += w;
                    accepted++;
                }
            }
            out.samples[face] = accepted;
            if (accepted < 16 || accepted < tested * 0.10f || weight < 8.0) continue;
            double cumulative = 0;
            int median = 0;
            while (median < HUE_BINS - 1 && cumulative + hist[median] < weight * 0.5) cumulative += hist[median++];
            final float hue = HUE_FROM + median + 0.5f;
            final float delta = clamp(hue, 25f, 70f) - hue;
            final float turn = clamp((float) Math.toRadians(delta), -MAX_TURN, MAX_TURN);
            out.adjust[to] = turn * strength * (float) Math.min(1.0, weight / 24.0);
        }
        return out;
    }

    private static float smooth(float lo, float hi, float v) {
        float t = clamp((v - lo) / (hi - lo), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
