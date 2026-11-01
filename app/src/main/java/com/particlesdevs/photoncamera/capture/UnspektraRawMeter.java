package com.particlesdevs.photoncamera.capture;

import com.particlesdevs.photoncamera.manual.UnspektraMeter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

final class UnspektraRawMeter {
    private static final int[][] ORDER = {{0,1,2,3},{1,0,3,2},{2,3,0,1},{3,2,1,0}};
    private static final float[] Y = {.2126f,.7152f,.0722f};

    static double measure(ByteBuffer buffer, int width, int height, int rowBytes, int cfa, int block,
            float[] black, float white, float[] wb, float[] ccm, NativeRawExposure.MeterSetup setup,
            UnspektraMeter.Method method) {
        if (buffer == null || setup == null || width <= 0 || height <= 0 || cfa < 0 || cfa >= 4 || block < 1 || block > 4
                || rowBytes < (long)width * 2 || (rowBytes & 1) != 0 || black == null || wb == null || ccm == null
                || black.length != 4 || wb.length != 3 || ccm.length != 9 || !(white > 0) || !Float.isFinite(white)) return Double.NaN;
        for (float level : black) if (!Float.isFinite(level) || level < 0 || level >= white) return Double.NaN;
        for (float gain : wb) if (!(gain > 0) || !Float.isFinite(gain)) return Double.NaN;
        for (float coefficient : ccm) if (!Float.isFinite(coefficient)) return Double.NaN;
        int period = block * 2;
        int left = Math.max(0, (int)(setup.left * width) / period * period);
        int top = Math.max(0, (int)(setup.top * height) / period * period);
        int right = Math.min(width, (int)(setup.right * width));
        int bottom = Math.min(height, (int)(setup.bottom * height));
        int cw = right - left, ch = bottom - top;
        if (cw < period || ch < period) return Double.NaN;
        int longest = Math.max(cw, ch);
        int w = Math.max(1, Math.round(cw * Math.min(1f, 256f / longest)));
        int h = Math.max(1, Math.round(ch * Math.min(1f, 256f / longest)));
        float[] rgb = new float[w * h * 3];
        float[] sites = new float[4];
        ShortBuffer raw = buffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer();
        int stride = rowBytes / 2;
        if ((long)(height - 1) * stride + width > raw.remaining()) return Double.NaN;
        int[] order = ORDER[cfa];
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
            int x = Math.min(right - period, left + i * cw / w) / period * period;
            int y = Math.min(bottom - period, top + j * ch / h) / period * period;
            for (int channel = 0; channel < 4; channel++) {
                int site = order[channel], sx = x + site % 2 * block, sy = y + site / 2 * block;
                float sum = 0;
                for (int dy = 0; dy < block; dy++) for (int dx = 0; dx < block; dx++)
                    sum += raw.get((sy + dy) * stride + sx + dx) & 65535;
                float level = Math.max(0, (sum / (block * block) - black[site]) / Math.max(1, white - black[site]));
                sites[channel] = level * shading(setup, channel, (x + period * .5f) / width, (y + period * .5f) / height);
            }
            float r = sites[0] * wb[0], g = (sites[1] + sites[2]) * .5f * wb[1], b = sites[3] * wb[2];
            int at = (j * w + i) * 3;
            for (int c = 0; c < 3; c++) rgb[at + c] = Math.fma(ccm[c * 3 + 2], b,
                    Math.fma(ccm[c * 3], r, ccm[c * 3 + 1] * g));
        }
        boolean spot = setup.regions != null && setup.regions.length > 0;
        int fx = w / 2, fy = h / 2;
        if (spot) {
            float[] region = setup.regions[0];
            fx = Math.round(((region[0] + region[2]) * .5f * width - left) * w / cw);
            fy = Math.round(((region[1] + region[3]) * .5f * height - top) * h / ch);
        }
        return UnspektraMeter.errorEv(rgb, w, h, 3, method, spot, fx, fy, Y);
    }

    private static float shading(NativeRawExposure.MeterSetup s, int channel, float x, float y) {
        if (s.gains == null || s.gainCols <= 0 || s.gainRows <= 0
                || (long)s.gainCols * s.gainRows * 4 > s.gains.length) return 1;
        float px = Math.max(0, Math.min(s.gainCols - 1, x * (s.gainCols - 1)));
        float py = Math.max(0, Math.min(s.gainRows - 1, y * (s.gainRows - 1)));
        int x0 = (int)px, y0 = (int)py, x1 = Math.min(x0 + 1, s.gainCols - 1), y1 = Math.min(y0 + 1, s.gainRows - 1);
        float a = s.gains[(y0 * s.gainCols + x0) * 4 + channel], b = s.gains[(y0 * s.gainCols + x1) * 4 + channel];
        float c = s.gains[(y1 * s.gainCols + x0) * 4 + channel], d = s.gains[(y1 * s.gainCols + x1) * 4 + channel];
        float result = (a + (b - a) * (px - x0)) * (1 - (py - y0)) + (c + (d - c) * (px - x0)) * (py - y0);
        return Float.isFinite(result) && result > 0 ? result : 1;
    }
}
