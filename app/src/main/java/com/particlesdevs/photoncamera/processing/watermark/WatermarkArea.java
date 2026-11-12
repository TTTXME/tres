package com.particlesdevs.photoncamera.processing.watermark;

import android.graphics.Rect;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/**
 * P633: the part of a photo a watermark's drawing may touch, as the rectangles whose original pixels a shot keeps (WatermarkPatch)
 * so the gallery's editor can take the watermark off again exactly.
 * <p>
 * The parts are the plan's own boxes - each line of text, the glyph, the badge's pill, the bar's band, the frame's four border
 * strips (outside its window, the window's rounded corners included) - grown by what a draw spreads past its box (the text's
 * shadow blur and offset, a glyph's side bearings, antialiasing). They go onto a grid of {@link #BLOCK} px blocks from the photo's
 * corner - the JPEG's 4:2:0 MCU, so a block holding ink is a whole MCU - and every block next to one is taken too ({@link
 * #DILATE}): a lossy codec spreads a change past its own block (the chroma upsampling of the next MCU, HEVC deblocking and SAO,
 * AV1's loop filters), and those pixels are restored as well. Runs of blocks merge into rectangles: row runs first, then equal
 * runs of the rows under them.
 */
public final class WatermarkArea {
    /** The grid (px): the JPEG MCU of 4:2:0 and the HEVC coding block the encoders start from. */
    public static final int BLOCK = 16;
    /** Blocks taken on every side of a touched one. */
    public static final int DILATE = 1;
    /** Extra px around every part: antialiasing and the glyphs' overhang of their measured boxes. */
    static final float EDGE_PX = 2f;

    private WatermarkArea() {}

    /** The boxes the plan draws into (photo px, as viewed), grown by the draw's spread; empty for an empty plan. */
    public static List<RectF> parts(WatermarkPainter.Plan plan) {
        List<RectF> out = new ArrayList<>();
        if (plan.empty()) return out;
        final float u = plan.unit;
        // the text's shadow: Skia blurs about 3 sigma = 1.73 x radius + 1.5 px, offset .03 u down; a glyph may overhang its advance
        final float shadow = plan.layout == WatermarkSpec.Layout.TEXT ? 1.8f * WatermarkPainter.shadowRadius(plan) + 1.5f + .03f * u : 0f;
        final float spread = shadow + .12f * u + EDGE_PX;
        if (plan.window != null) {
            final float r = plan.windowRadius + EDGE_PX;
            RectF w = plan.window;
            out.add(new RectF(0, 0, plan.width, w.top + r));
            out.add(new RectF(0, w.bottom - r, plan.width, plan.height));
            out.add(new RectF(0, w.top, w.left + r, w.bottom));
            out.add(new RectF(w.right - r, w.top, plan.width, w.bottom));
        }
        if (plan.plate != null) out.add(grown(plan.plate, EDGE_PX));
        if (plan.hairline != null) out.add(grown(plan.hairline, EDGE_PX));
        if (plan.logo != null) out.add(grown(plan.logo, spread));
        for (WatermarkPainter.Run r : plan.runs) out.add(grown(r.box, spread));
        return out;
    }

    private static RectF grown(RectF r, float by) {
        RectF g = new RectF(r);
        g.inset(-by, -by);
        return g;
    }

    /**
     * The rectangles kept for {@code parts} on a {@code width x height} photo: the touched blocks and their neighbours, as row runs
     * merged down; inside the photo, in raster order (top to bottom, left to right), not overlapping.
     */
    public static List<Rect> rects(List<RectF> parts, int width, int height) {
        List<Rect> out = new ArrayList<>();
        if (width <= 0 || height <= 0 || parts.isEmpty()) return out;
        final int bw = (width + BLOCK - 1) / BLOCK, bh = (height + BLOCK - 1) / BLOCK;
        boolean[] hit = new boolean[bw * bh];
        for (RectF p : parts) {
            if (!(p.right > 0 && p.bottom > 0 && p.left < width && p.top < height)) continue;
            int x0 = clamp((int) Math.floor(Math.max(0, p.left) / BLOCK) - DILATE, bw);
            int y0 = clamp((int) Math.floor(Math.max(0, p.top) / BLOCK) - DILATE, bh);
            int x1 = clamp((int) Math.ceil(Math.min(width, p.right) / BLOCK) - 1 + DILATE, bw);
            int y1 = clamp((int) Math.ceil(Math.min(height, p.bottom) / BLOCK) - 1 + DILATE, bh);
            for (int by = y0; by <= y1; by++) for (int bx = x0; bx <= x1; bx++) hit[by * bw + bx] = true;
        }
        // row runs, a run continuing the same run of the row above grows that rectangle
        List<int[]> open = new ArrayList<>(); // {bx0, bx1, by0, index in out}
        for (int by = 0; by < bh; by++) {
            List<int[]> next = new ArrayList<>();
            for (int bx = 0; bx < bw; ) {
                if (!hit[by * bw + bx]) { bx++; continue; }
                int start = bx;
                while (bx < bw && hit[by * bw + bx]) bx++;
                int[] grown = null;
                for (int[] o : open) if (o[0] == start && o[1] == bx) { grown = o; break; }
                if (grown != null) {
                    Rect r = out.get(grown[3]);
                    r.bottom = Math.min(height, (by + 1) * BLOCK);
                    next.add(grown);
                } else {
                    out.add(new Rect(start * BLOCK, by * BLOCK, Math.min(width, bx * BLOCK), Math.min(height, (by + 1) * BLOCK)));
                    next.add(new int[]{start, bx, by, out.size() - 1});
                }
            }
            open = next;
        }
        return out;
    }

    private static int clamp(int v, int n) {
        return Math.max(0, Math.min(n - 1, v));
    }

    /** The rectangles of {@code plan}'s watermark (see {@link #rects}). */
    public static List<Rect> of(WatermarkPainter.Plan plan) {
        return rects(parts(plan), plan.width, plan.height);
    }

    /** Pixels in {@code rects}. */
    public static long pixels(List<Rect> rects) {
        long n = 0;
        for (Rect r : rects) n += (long) r.width() * r.height();
        return n;
    }
}
