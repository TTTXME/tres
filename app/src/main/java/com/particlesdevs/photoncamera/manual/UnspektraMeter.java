package com.particlesdevs.photoncamera.manual;

import java.util.Arrays;

// Unspektrawesome 1.2.4 / 1.2.7 exposure statistic, before tone mapping or display gain.
public final class UnspektraMeter {
    public enum Method { CENTRE_WEIGHTED, MEDIAN }

    private UnspektraMeter() {}

    public static double errorEv(float[] linearRgb, int width, int height, int pixelStride,
            Method method, boolean spot, int focusX, int focusY, float[] luminanceRow) {
        if (width <= 0 || height <= 0 || pixelStride < 3
                || (long) width * height * pixelStride > linearRgb.length
                || luminanceRow.length != 3) throw new IllegalArgumentException("Invalid RGB meter input");
        int longest = Math.max(width, height);
        int w = longest <= 256 ? width : Math.max(1, (int) Math.floor(width * 256f / longest + .5f));
        int h = longest <= 256 ? height : Math.max(1, (int) Math.floor(height * 256f / longest + .5f));
        float[] y = new float[w * h];
        for (int row = 0; row < h; row++) {
            int sourceRow = (int) ((long) row * height / h);
            for (int col = 0; col < w; col++) {
                int sourceCol = (int) ((long) col * width / w);
                int i = (sourceRow * width + sourceCol) * pixelStride;
                y[row * w + col] = Math.fma(luminanceRow[2], linearRgb[i + 2],
                        Math.fma(luminanceRow[0], linearRgb[i], luminanceRow[1] * linearRgb[i + 1]));
            }
        }
        double global = statistic(y, w, 0, 0, w, h, method);
        double value = global;
        if (spot) {
            int rw = Math.max(1, (int) (w * .12f)), rh = Math.max(1, (int) (h * .12f));
            int cx = (int) Math.min(w - 1, Math.max(0, (long) focusX * w / width));
            int cy = (int) Math.min(h - 1, Math.max(0, (long) focusY * h / height));
            int left = Math.max(0, Math.min(w - rw, cx - rw / 2));
            int top = Math.max(0, Math.min(h - rh, cy - rh / 2));
            double roi = statistic(y, w, left, top, rw, rh, method);
            if (global > 0 && roi >= 0)
                value = Math.max(.5 * global, Math.min(2 * global, .5 * (global + roi)));
        }
        double product = value * 5.434782505035400390625;
        double ev = product > 0 ? -Math.log(product) / Math.log(2) : 0;
        return Double.isFinite(ev) ? ev : 0;
    }

    private static double statistic(float[] y, int stride, int left, int top,
            int width, int height, Method method) {
        if (method == Method.MEDIAN) {
            float[] values = new float[width * height];
            for (int row = 0; row < height; row++)
                System.arraycopy(y, (top + row) * stride + left, values, row * width, width);
            Arrays.sort(values);
            return .5 * ((double) values[values.length / 2] + values[(values.length - 1) / 2]);
        }
        double sum = 0, weights = 0;
        int longest = Math.max(width, height);
        for (int row = 0; row < height; row++) {
            double dy = ((double) row / height - .5) * height / longest;
            for (int col = 0; col < width; col++) {
                double dx = ((double) col / width - .5) * width / longest;
                double weight = Math.exp(-12.5 * (dx * dx + dy * dy));
                sum += y[(top + row) * stride + left + col] * weight;
                weights += weight;
            }
        }
        return sum / Math.max(weights, 1e-30);
    }
}
