package com.scamera.app.processing.opengl.postpipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * P614 «Why тон» V2: the own curve's table as the reference app builds it - its points ("x:y,...", parsePoints: each pair clamped
 * to 0..1, sorted by x, fewer than two: its default points), its seven curve modifiers on the points (applyModifiersToPoints:
 * calcDeltaY of its six tables, y clamped; all |m| < 1e-4: the points as they are), the points as it stores them ("%.4f"), and
 * generateLut's monotone cubic at 1024 samples (Java float). Pure Java (the unit tests and the host check run it as is).
 */
public final class WhyV2Curve {
    private WhyV2Curve() {}

    /** The reference's default points (ToneCurveHelper.getDefaultPoints) for an empty or broken string. */
    public static final String DEFAULT_POINTS =
            "0.0000:0.0000,0.1250:0.0688,0.2500:0.1600,0.3750:0.3188,0.5000:0.5000,0.6250:0.6812,0.7500:0.8400,0.8750:0.9312,1.0000:1.0000";

    /** ToneCurveHelper.parsePoints: [x0, y0, x1, y1, ...] sorted by x. */
    public static float[] parse(String text) {
        final List<float[]> out = new ArrayList<>();
        if (text != null && !text.trim().isEmpty()) {
            for (String pair : text.split(",")) {
                final String[] xy = pair.trim().split(":");
                if (xy.length != 2) continue;
                try {
                    final float x = Float.parseFloat(xy[0].trim()), y = Float.parseFloat(xy[1].trim());
                    out.add(new float[]{Math.max(0f, Math.min(1f, x)), Math.max(0f, Math.min(1f, y))});
                } catch (NumberFormatException e) {
                    // a broken pair is skipped
                }
            }
        }
        if (out.size() < 2) return parse(DEFAULT_POINTS);
        out.sort((a, b) -> Double.compare(a[0], b[0]));
        final float[] p = new float[out.size() * 2];
        for (int i = 0; i < out.size(); i++) {
            p[2 * i] = out.get(i)[0];
            p[2 * i + 1] = out.get(i)[1];
        }
        return p;
    }

    /**
     * ToneCurveHelper.applyModifiersToPoints: each point's y + calcDeltaY(x), within 0..1. m: lift centre, global contrast, shadow
     * contrast, highlight contrast, deep shadows, extreme highlights (-100..100), offset (-50..50).
     */
    public static float[] applyModifiers(float[] p, float[] m) {
        boolean none = true;
        for (float v : m) none &= Math.abs(v) < 1e-4f;
        if (none) return p.clone();
        final float[] out = p.clone();
        for (int i = 0; i + 1 < p.length; i += 2) out[i + 1] = Math.max(0f, Math.min(1f, p[i + 1] + deltaY(p[i], m)));
        return out;
    }

    /** ToneCurveHelper.calcDeltaY in its operation order. */
    static float deltaY(float x, float[] m) {
        float v = coeff(CURVE_B, x) * m[0];
        v -= coeff(CURVE_C, x) * m[1];
        v -= coeff(CURVE_E, x) * m[2];
        v -= coeff(CURVE_F, x) * m[3];
        v += coeff(CURVE_G, x) * m[4];
        v -= coeff(CURVE_H, x) * m[5];
        return m[6] / 200f + v * 2.5e-6f;
    }

    /** ToneCurveHelper's COEFF_B / C / E / F / G / H (classes.dex fill-array-data; the same tables as WhyTone's V1 port). */
    static final float[] CURVE_B = {0, 0, 4, 50, 178, 401, 674, 906, 1000, 906, 674, 401, 178, 50, 4, 0, 0},
            CURVE_C = {0, 349, 659, 889, 1000, 964, 769, 431, 0, -431, -769, -964, -1000, -889, -659, -349, 0},
            CURVE_E = {0, 349, 659, 889, 1000, 962, 769, 471, 206, 51, 6, 0, 0, 0, 0, 0, 0},
            CURVE_F = {0, 0, 0, 0, 0, 0, -6, -51, -206, -471, -769, -962, -1000, -889, -659, -349, 0},
            CURVE_G = {0, 667, 1000, 956, 697, 402, 180, 61, 14, 1, 0, 0, 0, 0, 0, 0, 0},
            CURVE_H = {0, 0, 0, 0, 0, 0, 0, -1, -14, -61, -180, -402, -697, -956, -1000, -667, 0};

    /** ToneCurveHelper.sampleCoeff: idx = x 16, (int) idx, the end nodes outside, (f f)(3 - 2 f) between two nodes. */
    static float coeff(float[] t, float x) {
        final float idx = x * 16f;
        final int i = (int) idx;
        if (i < 0) return t[0];
        if (i >= 16) return t[16];
        final float f = idx - i;
        return t[i] + f * f * (3f - f * 2f) * (t[i + 1] - t[i]);
    }

    /** serializePoints ("%.4f:%.4f", Locale.US) then parsePoints: the points as the engine reads them back. */
    public static float[] stored(float[] p) {
        final StringBuilder s = new StringBuilder();
        for (int i = 0; i + 1 < p.length; i += 2) {
            if (i > 0) s.append(',');
            s.append(String.format(Locale.US, "%.4f:%.4f", p[i], p[i + 1]));
        }
        return parse(s.toString());
    }

    /** ToneCurveHelper.generateLut(points, n): Fritsch-Carlson tangents and the Hermite cubic, Java float. */
    public static float[] lut(float[] pts, int n) {
        final float[] out = new float[n];
        final int k = pts.length / 2;
        if (k < 2) {
            for (int i = 0; i < n; i++) out[i] = i / (float) (n - 1);
            return out;
        }
        final float[] x = new float[k], y = new float[k];
        for (int i = 0; i < k; i++) {
            x[i] = pts[2 * i];
            y[i] = pts[2 * i + 1];
        }
        x[0] = 0f;
        x[k - 1] = 1f;
        for (int i = 1; i < k; i++) if (!(x[i] > x[i - 1])) x[i] = Math.min(1f, x[i - 1] + 0.001f);
        final float[] h = new float[k - 1], d = new float[k - 1], m = new float[k];
        for (int i = 0; i < k - 1; i++) {
            h[i] = x[i + 1] - x[i];
            d[i] = (y[i + 1] - y[i]) / Math.max(1e-6f, h[i]);
        }
        m[0] = d[0];
        m[k - 1] = d[k - 2];
        for (int i = 1; i < k - 1; i++) m[i] = d[i - 1] * d[i] <= 0f ? 0f : (d[i - 1] + d[i]) * 0.5f;
        for (int i = 0; i < k - 1; i++) {
            if (Math.abs(d[i]) < 1e-7f) {
                m[i] = 0f;
                m[i + 1] = 0f;
                continue;
            }
            final float a = m[i] / d[i], b = m[i + 1] / d[i];
            if (a < 0f) m[i] = 0f;
            if (b < 0f) m[i + 1] = 0f;
            final float s = a * a + b * b;
            if (s > 9f) {
                final float t = 3f / (float) Math.sqrt(s);
                m[i] = t * a * d[i];
                m[i + 1] = t * b * d[i];
            }
        }
        int seg = 0;
        for (int j = 0; j < n; j++) {
            final float t = j / (float) (n - 1);
            while (seg < k - 2 && t > x[seg + 1]) seg++;
            final float hh = Math.max(1e-6f, x[seg + 1] - x[seg]);
            final float u = Math.max(0f, Math.min(1f, (t - x[seg]) / hh));
            final float u2 = u * u, u3 = u2 * u;
            final float h00 = u3 * 2f - u2 * 3f + 1f, h10 = u3 - 2f * u2 + u, h01 = -2f * u3 + u2 * 3f, h11 = u3 - u2;
            final float v = h00 * y[seg] + h10 * hh * m[seg] + h01 * y[seg + 1] + h11 * hh * m[seg + 1];
            out[j] = Math.max(0f, Math.min(1f, v));
        }
        return out;
    }

    /** The table the engine gets: lut(stored(applyModifiers(parse(points), m)), n). */
    public static float[] samples(String points, float[] m, int n) {
        return lut(stored(applyModifiers(parse(points), m)), n);
    }
}
