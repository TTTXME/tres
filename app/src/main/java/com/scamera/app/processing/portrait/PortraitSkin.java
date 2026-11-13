package com.scamera.app.processing.portrait;

import java.util.Locale;

public final class PortraitSkin {
    private PortraitSkin() {}

    public static final int MAX_FACES = 5;
    public static final float MAX_TURN = (float) Math.toRadians(10.0);
    /** P473: the largest yellow-blue shift of the skin (OKLab b / L); a B x0.7 cast adds ~0.045 to the five scenes' skin. */
    public static final float MAX_SHIFT = 0.035f;
    private static final float HUE_FROM = -35f;
    private static final int HUE_BINS = 150;
    private static final float B_FROM = -0.12f;
    private static final int B_BINS = 240;

    public static final class Result {
        public int count;
        public final float[] faces = new float[MAX_FACES * 4];
        /** Per face: x = hue turn outside the broad skin band (radians), y = P473 scene-confirmed b / L shift, z / w unused. */
        public final float[] adjust = new float[MAX_FACES * 4];
        public final int[] samples = new int[MAX_FACES];
        /** P473: per face, the median skin hue (OKLab degrees) and the mean skin a / L, b / L; NaN when the face was not measured. */
        public final float[] hue = new float[MAX_FACES], a = new float[MAX_FACES], b = new float[MAX_FACES];
        /** P512: per face, the skin's mean linear luminance (OKLab L cubed) in the sample's units; NaN when not measured. */
        public final float[] luma = new float[MAX_FACES];
        /** P512: per face, the spread of the skin's a / L, b / L round its mean (its standard distance); NaN when not measured. */
        public final float[] spread = new float[MAX_FACES];
        /** P473: the median b / L of the near-neutral background (yellow +, blue -); NaN with too few neutral pixels. */
        public float sceneB = Float.NaN;
        public int sceneSamples;

        public boolean corrected() {
            for (int i = 0; i < count; i++) if (adjust[i * 4] != 0f || adjust[i * 4 + 1] != 0f) return true;
            return false;
        }

        /** P473: one log line - the evidence of every gate (samples, hue, skin b, background b, turn, shift). */
        public String describe() {
            StringBuilder s = new StringBuilder(String.format(Locale.US, "sceneB=%+.4f (%d)", sceneB, sceneSamples));
            for (int i = 0; i < count; i++)
                s.append(String.format(Locale.US, " | f%d n=%d hue=%.1f a=%.4f b=%.4f turn=%.2f shift=%+.4f", i, samples[i], hue[i],
                        a[i], b[i], Math.toDegrees(adjust[i * 4]), adjust[i * 4 + 1]));
            return s.toString();
        }
    }

    // shortcut: skin colour alone cannot identify the illuminant; only clear casts outside a broad hue dead zone move, by at most 10 degrees.
    // P473 (owner, 2026-11-03: the five face scenes unchanged): their skin sat at hue 45-54 (natural, 0 is right), but a yellow cast
    // (B x0.8 / x0.7) only took it to 61-71 deg and a cold one (R x0.85, B x1.25) to -11-49: the turn moved 1 of 10 / 1 of 5, at the
    // band edge only (host replay of the saved 192 px samples). So a second, scene-confirmed path:
    // the background's near-neutral pixels carry the same cast; when they and the skin hue agree, the skin moves back along OKLab b
    // (a local white balance, part of the cast, never past hue 52 / 48), by at most MAX_SHIFT. Uniform skin without a background: as before.
    public static Result measure(float[] pixels, int channels, int width, int height, float[] boxes, float strength) {
        Result out = new Result();
        java.util.Arrays.fill(out.hue, Float.NaN);
        java.util.Arrays.fill(out.a, Float.NaN);
        java.util.Arrays.fill(out.b, Float.NaN);
        java.util.Arrays.fill(out.luma, Float.NaN);
        java.util.Arrays.fill(out.spread, Float.NaN);
        if (pixels == null || boxes == null || channels < 3 || width <= 0 || height <= 0
                || (long) width * height * channels > pixels.length || !Float.isFinite(strength) || strength <= 0f) return out;
        strength = Math.min(strength, 1f);
        final float[] confidence = new float[MAX_FACES];
        final double[] lab = new double[3];
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
            double weight = 0, sumA = 0, sumB = 0, sumY = 0, sumAA = 0, sumBB = 0;
            int tested = 0, accepted = 0;
            for (int y = y0; y < y1; y += sy) {
                for (int x = x0; x < x1; x += sx) {
                    final float ex = ((x + 0.5f) / width - (l + r) * 0.5f) / ((r - l) * 0.46f);
                    final float ey = ((y + 0.5f) / height - (t + b) * 0.5f) / ((b - t) * 0.48f);
                    final float ellipse = 1f - smooth(0.65f, 1f, ex * ex + ey * ey);
                    if (ellipse <= 0f) continue;
                    tested++;
                    if (!oklab(pixels, (y * width + x) * channels, lab)) continue;
                    final double light = lab[0], aa = lab[1], ab = lab[2];
                    final float hue = (float) Math.toDegrees(Math.atan2(ab, aa));
                    final float ratio = (float) (Math.hypot(aa, ab) / Math.max(light, 0.0001));
                    final float skin = smooth(-35f, -15f, hue) * (1f - smooth(95f, 115f, hue))
                            * smooth(0.03f, 0.05f, ratio) * (1f - smooth(0.30f, 0.40f, ratio))
                            * smooth(0.12f, 0.20f, (float) light);
                    if (skin <= 0.05f) continue;
                    final double w = ellipse * skin;
                    final int bin = Math.max(0, Math.min(HUE_BINS - 1, (int) (hue - HUE_FROM)));
                    hist[bin] += w;
                    weight += w;
                    sumA += w * aa / light;
                    sumB += w * ab / light;
                    sumY += w * light * light * light;
                    sumAA += w * (aa / light) * (aa / light);
                    sumBB += w * (ab / light) * (ab / light);
                    accepted++;
                }
            }
            out.samples[face] = accepted;
            if (accepted < 16 || accepted < tested * 0.10f || weight < 8.0) continue;
            double cumulative = 0;
            int median = 0;
            while (median < HUE_BINS - 1 && cumulative + hist[median] < weight * 0.5) cumulative += hist[median++];
            final float hue = HUE_FROM + median + 0.5f;
            out.hue[face] = hue;
            out.a[face] = (float) (sumA / weight);
            out.b[face] = (float) (sumB / weight);
            out.luma[face] = (float) (sumY / weight);
            out.spread[face] = (float) Math.sqrt(Math.max(0.0, sumAA / weight - out.a[face] * (double) out.a[face]
                    + sumBB / weight - out.b[face] * (double) out.b[face]));
            confidence[face] = strength * (float) Math.min(1.0, weight / 24.0);
            final float delta = clamp(hue, 25f, 70f) - hue;
            final float turn = clamp((float) Math.toRadians(delta), -MAX_TURN, MAX_TURN);
            out.adjust[to] = turn * strength * (float) Math.min(1.0, weight / 24.0);
        }
        sceneNeutral(pixels, channels, width, height, out, lab);
        for (int face = 0; face < out.count; face++)
            if (Float.isFinite(out.hue[face]))
                out.adjust[face * 4 + 1] = shift(out.sceneB, out.hue[face], out.a[face], out.b[face]) * confidence[face];
        return out;
    }

    /**
     * P473: the skin's b / L shift from the background cast and the skin hue, both must agree: warm/yellow when the neutrals pass
     * b / L 0.03 .. 0.05 (the five indoor scenes: 0.008 .. 0.033) and the skin hue 56 .. 64 (theirs 45 .. 54); cold below b / L 0.005
     * .. -0.015 and hue 44 .. 36. 0.7 of the neutrals' excess, the mean skin stopped at hue 52 (cooling) / 48 (warming), MAX_SHIFT.
     */
    static float shift(float sceneB, float hue, float a, float b) {
        if (!Float.isFinite(sceneB) || !Float.isFinite(hue) || !Float.isFinite(a) || !Float.isFinite(b)) return 0f;
        final float warm = smooth(0.030f, 0.050f, sceneB) * smooth(56f, 64f, hue);
        final float cold = (1f - smooth(-0.015f, 0.005f, sceneB)) * (1f - smooth(36f, 44f, hue));
        float shift = -warm * 0.7f * Math.max(sceneB - 0.01f, 0f) + cold * 0.7f * Math.max(0.015f - sceneB, 0f);
        if (shift < 0f) shift = Math.max(shift, Math.min(0f, a * (float) Math.tan(Math.toRadians(52.0)) - b));
        else if (shift > 0f) shift = Math.min(shift, Math.max(0f, a * (float) Math.tan(Math.toRadians(48.0)) - b));
        return clamp(shift, -MAX_SHIFT, MAX_SHIFT);
    }

    /** P512: the measured faces' skin luminance, weighted by their samples; NaN without a measured face. */
    public static float faceLuma(Result r) {
        double sum = 0, weight = 0;
        for (int i = 0; r != null && i < r.count; i++)
            if (Float.isFinite(r.luma[i]) && r.luma[i] > 0f) { sum += r.samples[i] * (double) r.luma[i]; weight += r.samples[i]; }
        return weight > 0 ? (float) (sum / weight) : Float.NaN;
    }

    /** P512: the natural skin hue Face AWB aims at (OKLab degrees; the five face scenes' skin sat at 44.6 - 54.4). */
    public static final float SKIN_HUE = 50f;
    /** P512: the largest Face AWB shift of the whole frame (OKLab a / L, b / L). */
    public static final float MAX_AWB = 0.03f;

    /**
     * P512 «Нейтрализация скинтона (Face AWB)»: the whole frame's colour shift (OKLab a / L, b / L) that moves the measured skin
     * {@code strength} of the way to the natural hue {@link #SKIN_HUE} at its own chroma - a white balance led by the faces, for
     * the whole frame when a face is found (not the face mask). Nothing within 6 degrees of it (natural skin varies), at most
     * {@link #MAX_AWB}; {0, 0} without a measured face.
     */
    public static float[] faceAwb(Result r, float strength) {
        double a = 0, b = 0, weight = 0;
        for (int i = 0; r != null && i < r.count; i++)
            if (Float.isFinite(r.a[i]) && Float.isFinite(r.b[i])) { a += r.samples[i] * (double) r.a[i]; b += r.samples[i] * (double) r.b[i]; weight += r.samples[i]; }
        if (weight <= 0 || !(strength > 0f)) return new float[]{0f, 0f};
        a /= weight;
        b /= weight;
        final double c = Math.hypot(a, b), hue = Math.toDegrees(Math.atan2(b, a));
        final double off = hue - SKIN_HUE, dead = 6.0;
        if (Math.abs(off) <= dead) return new float[]{0f, 0f};
        final double aim = Math.toRadians(SKIN_HUE + Math.signum(off) * dead);
        double da = (c * Math.cos(aim) - a) * Math.min(strength, 1f), db = (c * Math.sin(aim) - b) * Math.min(strength, 1f);
        final double n = Math.hypot(da, db);
        if (n > MAX_AWB) { da *= MAX_AWB / n; db *= MAX_AWB / n; }
        return new float[]{(float) da, (float) db};
    }

    /** P473: the weighted median b / L of near-neutral pixels (C / L under 0.10) outside every face box widened 1.3 times. */
    private static void sceneNeutral(float[] pixels, int channels, int width, int height, Result out, double[] lab) {
        final int sx = Math.max(1, (width + 127) / 128), sy = Math.max(1, (height + 127) / 128);
        final double[] hist = new double[B_BINS];
        double weight = 0;
        int tested = 0, neutral = 0;
        for (int y = 0; y < height; y += sy) {
            final float v = (y + 0.5f) / height;
            for (int x = 0; x < width; x += sx) {
                final float u = (x + 0.5f) / width;
                boolean face = false;
                for (int i = 0; i < out.count && !face; i++) {
                    final float l = out.faces[i * 4], t = out.faces[i * 4 + 1], r = out.faces[i * 4 + 2], b = out.faces[i * 4 + 3];
                    face = Math.abs(u - (l + r) * 0.5f) <= (r - l) * 0.65f && Math.abs(v - (t + b) * 0.5f) <= (b - t) * 0.65f;
                }
                if (face || !oklab(pixels, (y * width + x) * channels, lab) || lab[0] < 0.2) continue;
                tested++;
                final float ratio = (float) (Math.hypot(lab[1], lab[2]) / lab[0]);
                final float w = 1f - smooth(0.05f, 0.10f, ratio);
                if (w <= 0f) continue;
                hist[Math.max(0, Math.min(B_BINS - 1, (int) Math.floor((lab[2] / lab[0] - B_FROM) * 1000.0)))] += w;
                weight += w;
                neutral++;
            }
        }
        out.sceneSamples = neutral;
        if (neutral < 64 || neutral < tested * 0.10f) return;
        double cumulative = 0;
        int median = 0;
        while (median < B_BINS - 1 && cumulative + hist[median] < weight * 0.5) cumulative += hist[median++];
        out.sceneB = B_FROM + (median + 0.5f) / 1000f;
    }

    /** Linear RGB at {@code p} to OKLab in {@code lab}; false for non-finite, clipped (>= 0.98), black or very dark pixels. */
    private static boolean oklab(float[] pixels, int p, double[] lab) {
        final double rr = pixels[p], gg = pixels[p + 1], bb = pixels[p + 2];
        if (!Double.isFinite(rr) || !Double.isFinite(gg) || !Double.isFinite(bb)
                || Math.min(rr, Math.min(gg, bb)) <= 0.0001 || Math.max(rr, Math.max(gg, bb)) >= 0.98) return false;
        if (0.2126 * rr + 0.7152 * gg + 0.0722 * bb < 0.008) return false;
        final double ll = Math.cbrt(0.4122214708 * rr + 0.5363325363 * gg + 0.0514459929 * bb);
        final double mm = Math.cbrt(0.2119034982 * rr + 0.6806995451 * gg + 0.1073969566 * bb);
        final double ss = Math.cbrt(0.0883024619 * rr + 0.2817188376 * gg + 0.6299787005 * bb);
        lab[0] = 0.2104542553 * ll + 0.7936177850 * mm - 0.0040720468 * ss;
        lab[1] = 1.9779984951 * ll - 2.4285922050 * mm + 0.4505937099 * ss;
        lab[2] = 0.0259040371 * ll + 0.7827717662 * mm - 0.8086757660 * ss;
        return true;
    }

    private static float smooth(float lo, float hi, float v) {
        float t = clamp((v - lo) / (hi - lo), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
