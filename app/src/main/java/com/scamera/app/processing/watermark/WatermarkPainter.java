package com.scamera.app.processing.watermark;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * P628: the watermark's layout and drawing, in the photo's own pixels as it is viewed (the saved photo is upright in every format:
 * no Orientation tag, ExifBlock), after every crop and resize of the shot, so it lands in the chosen corner the right way up at any
 * size (12 / 50 / 200 MP) and orientation. Everything is measured on the short side: a portrait and a landscape photo get the same
 * watermark. All drawing is vector (text, the glyph, the plates) at the photo's resolution.
 * <p>
 * Four layouts in the app's current look (the shade's glass, Roboto Flex, the camera's yellow):
 * <ul>
 * <li>{@link WatermarkSpec.Layout#TEXT} «Подпись»: the glyph and up to two lines on the photo with a soft shadow;</li>
 * <li>{@link WatermarkSpec.Layout#BADGE} «Плашка»: the same in a glass pill with the shade's hairline;</li>
 * <li>{@link WatermarkSpec.Layout#BAR} «Полоса»: a glass band across the bottom (top) edge - the title and time on one side,
 * the exposure on the other;</li>
 * <li>{@link WatermarkSpec.Layout#FRAME} «Рамка»: a white / black border over the photo's edges with a rounded window and a wider
 * band holding the text (the photo keeps its size, so its gain map, thumbnails and EXIF stay matched).</li>
 * </ul>
 * Fields that are off leave nothing behind: the lines, the sides and the glyph are built only from what is there ({@link
 * WatermarkText}).
 */
public final class WatermarkPainter {
    private WatermarkPainter() {}

    /** The camera's yellow (AccentPalette's default) on a dark watermark, a deeper amber on a light one: the glyph's LED. */
    public static final int ACCENT = 0xFFFFD447, ACCENT_ON_LIGHT = 0xFFE0A100;
    static final int INK_WHITE = 0xFFFFFFFF, INK_BLACK = 0xFF141517;
    /** The shade's sheet (#1C1C1E) and the settings' light text (#F4F3F7) as glass; the hairlines of the shade's tiles. */
    static final int GLASS_DARK = 0x1C1C1E, GLASS_LIGHT = 0xF4F3F7, LINE_DARK = 0x29FFFFFF, LINE_LIGHT = 0x1F000000;
    static final int FRAME_DARK = 0xFF101214, FRAME_LIGHT = 0xFFFFFFFF;
    /** The main line's weight and the others' (Roboto Flex 600 / 500). */
    static final int MAIN_WEIGHT = 600, SECOND_WEIGHT = 500;
    /** The second line's size per main size. */
    static final float SECOND = .74f;
    /** The glyph's box: the launcher icon's silhouette (scamera_launch_mono: x 33..75, y 36.78..70.8). */
    static final float LOGO_W = 42f, LOGO_H = 34.02f;
    /** Brighter than this mean (0..1, sRGB) under the watermark: black text (a light plate / white frame) in «Авто». */
    static final float BRIGHT = .58f;

    /** The typeface of a font at a weight (400..800). */
    public interface Fonts {
        Typeface of(WatermarkSpec.Font font, int weight);
    }

    /** One line of text as placed: its baseline's start, its size and weight, its box (font ascent to descent). */
    public static final class Run {
        public final String text;
        public final float x, baseline, size;
        public final int weight;
        public final boolean main;
        public final RectF box;

        Run(String text, float x, float baseline, float size, int weight, boolean main, RectF box) {
            this.text = text;
            this.x = x;
            this.baseline = baseline;
            this.size = size;
            this.weight = weight;
            this.main = main;
            this.box = box;
        }
    }

    /** Everything a watermark draws on a {@link #width} x {@link #height} photo. */
    public static final class Plan {
        public final int width, height;
        public final WatermarkSpec.Layout layout;
        public final List<Run> runs = new ArrayList<>();
        /** The glyph's box (null: none). */
        @Nullable public RectF logo;
        /** The badge's pill or the bar's band (null: none), its corner radius. */
        @Nullable public RectF plate;
        public float radius;
        /** The frame's window onto the photo and its corner radius (null: no frame). */
        @Nullable public RectF window;
        public float windowRadius;
        /** The bar's hairline on its inner edge (null: none). */
        @Nullable public RectF hairline;
        /** The plate's stroke width (px). */
        public float line;
        /** The main text size (px). */
        public float unit;
        /** The part of the photo «Авто» reads. */
        public final RectF sample = new RectF();
        /** Everything drawn, the text's shadow included. */
        public final RectF bounds = new RectF();
        /** White text (a dark plate / frame) or black text (a light one): {@link #chooseInk}. */
        public boolean whiteInk = true;

        Plan(int width, int height, WatermarkSpec.Layout layout) {
            this.width = width;
            this.height = height;
            this.layout = layout;
        }

        /** Whether the plan draws anything. */
        public boolean empty() {
            return runs.isEmpty() && logo == null && plate == null && window == null;
        }
    }

    // ───── Layout ─────

    /** Lines of one column, measured at a main size. */
    private static final class Column {
        final List<String> lines = new ArrayList<>();
        final List<Float> sizes = new ArrayList<>();
        final List<Integer> weights = new ArrayList<>();
        final List<Float> widths = new ArrayList<>();
        final List<Float> ascents = new ArrayList<>(), descents = new ArrayList<>();
        float width, height, gap;

        boolean empty() { return lines.isEmpty(); }
    }

    /** A column with the glyph beside it (before it, or after it on the right edge). */
    private static final class Group {
        Column column;
        boolean logo, logoAfter;
        float logoW, logoH, logoGap, width, height;
        /** 0 left, 1 centre, 2 right: the lines inside the column. */
        int align;
    }

    private static Paint paint(Fonts fonts, WatermarkSpec.Font font, int weight, float size) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
        p.setTypeface(fonts.of(font, weight));
        p.setTextSize(size);
        if (font != WatermarkSpec.Font.MONO) p.setLetterSpacing(weight >= MAIN_WEIGHT ? .01f : .02f);
        return p;
    }

    /** {@code lines} at main size {@code u}: the first line main ({@code mainWeight}), the rest second; each cut to {@code max}. */
    private static Column column(List<String> lines, float u, int mainWeight, float max, WatermarkSpec.Font font, Fonts fonts) {
        Column c = new Column();
        c.gap = .16f * u;
        for (int i = 0; i < lines.size(); i++) {
            final boolean main = i == 0;
            final float size = main ? u : u * SECOND;
            final int weight = main ? mainWeight : SECOND_WEIGHT;
            Paint p = paint(fonts, font, weight, size);
            String text = ellipsize(lines.get(i), p, max);
            if (text.isEmpty()) continue;
            Paint.FontMetrics fm = p.getFontMetrics();
            c.lines.add(text);
            c.sizes.add(size);
            c.weights.add(weight);
            float w = p.measureText(text);
            c.widths.add(w);
            c.ascents.add(fm.ascent);
            c.descents.add(fm.descent);
            c.width = Math.max(c.width, w);
            c.height += (fm.descent - fm.ascent) + (c.lines.size() > 1 ? c.gap : 0);
        }
        return c;
    }

    /** {@code text} cut with «…» to {@code max} px; "" when not even «…» fits. */
    static String ellipsize(String text, Paint p, float max) {
        if (text == null || text.isEmpty()) return "";
        if (p.measureText(text) <= max) return text;
        final String dots = "…";
        if (p.measureText(dots) > max) return "";
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            String cut = text.substring(0, safeEnd(text, mid)).trim() + dots;
            if (p.measureText(cut) <= max) lo = mid;
            else hi = mid - 1;
        }
        String head = text.substring(0, safeEnd(text, lo)).trim();
        return head.isEmpty() ? "" : head + dots;
    }

    private static int safeEnd(String s, int end) {
        return end > 0 && end < s.length() && Character.isHighSurrogate(s.charAt(end - 1)) ? end - 1 : end;
    }

    private static Group group(List<String> lines, boolean logo, int align, float u, int mainWeight, float maxWidth,
                               WatermarkSpec.Font font, Fonts fonts) {
        Group g = new Group();
        g.align = align;
        g.logo = logo;
        g.logoAfter = align == 2;
        g.logoGap = .5f * u;
        // the glyph first at the height of one main line (its final height follows the column)
        float logoH = 1.15f * u, logoW = logoH * LOGO_W / LOGO_H;
        float textMax = maxWidth - (logo ? logoW + g.logoGap : 0);
        g.column = column(lines, u, mainWeight, Math.max(0, textMax), font, fonts);
        if (logo) {
            g.logoH = g.column.empty() ? 1.5f * u : Math.max(1.05f * u, Math.min(1.9f * u, g.column.height * .82f));
            g.logoW = g.logoH * LOGO_W / LOGO_H;
            if (g.logoW > maxWidth) { // a photo narrower than the glyph: no glyph
                g.logo = false;
            } else if (!g.column.empty() && g.logoW + g.logoGap + g.column.width > maxWidth) {
                g.column = column(lines, u, mainWeight, Math.max(0, maxWidth - g.logoW - g.logoGap), font, fonts);
            }
        }
        g.width = (g.logo ? g.logoW + (g.column.empty() ? 0 : g.logoGap) : 0) + g.column.width;
        g.height = Math.max(g.logo ? g.logoH : 0, g.column.height);
        return g;
    }

    /** Places {@code g} with its top left at x, y. */
    private static void place(Plan plan, Group g, float x, float y) {
        float textX = x;
        if (g.logo) {
            float lx = g.logoAfter ? x + g.width - g.logoW : x;
            float ly = y + (g.height - g.logoH) / 2f;
            plan.logo = new RectF(lx, ly, lx + g.logoW, ly + g.logoH);
            if (!g.logoAfter && !g.column.empty()) textX = x + g.logoW + g.logoGap;
        }
        Column c = g.column;
        float top = y + (g.height - c.height) / 2f;
        for (int i = 0; i < c.lines.size(); i++) {
            float w = c.widths.get(i);
            float lx = g.align == 0 ? textX : g.align == 1 ? textX + (c.width - w) / 2f : textX + c.width - w;
            float baseline = top - c.ascents.get(i);
            RectF box = new RectF(lx, top, lx + w, top + c.descents.get(i) - c.ascents.get(i));
            plan.runs.add(new Run(c.lines.get(i), lx, baseline, c.sizes.get(i), c.weights.get(i), i == 0, box));
            top = box.bottom + c.gap;
        }
    }

    /**
     * The plan of {@code text} by {@code spec} on a {@code width} x {@code height} photo (as viewed). A plan that would draw nothing
     * (no field, no glyph; a frame always draws) is empty.
     */
    public static Plan plan(WatermarkSpec spec, WatermarkText text, int width, int height, Fonts fonts) {
        Plan plan = new Plan(width, height, spec.layout);
        if (width <= 0 || height <= 0) return plan;
        final float shortSide = Math.min(width, height);
        float u = Math.max(4f, shortSide * spec.size / 100f * WatermarkSpec.TITLE_SHARE);
        final float m = shortSide * Math.max(0, spec.margin) / 100f;
        // a watermark taller than a third of the photo is scaled down (tiny pictures, the largest size)
        for (int attempt = 0; attempt < 4; attempt++) {
            plan = new Plan(width, height, spec.layout);
            plan.unit = u;
            plan.line = Math.max(1f, u * .045f);
            switch (spec.layout) {
                case BADGE: badge(plan, spec, text, u, m, fonts); break;
                case BAR: band(plan, spec, text, u, m, fonts, false); break;
                case FRAME: band(plan, spec, text, u, m, fonts, true); break;
                default: textLayout(plan, spec, text, u, m, fonts); break;
            }
            float tall = contentHeight(plan);
            if (tall <= height / 3f || spec.layout == WatermarkSpec.Layout.FRAME && tall <= height / 2f) break;
            u *= .75f;
        }
        bounds(plan, spec);
        return plan;
    }

    private static float contentHeight(Plan p) {
        RectF r = new RectF();
        boolean any = false;
        if (p.plate != null) { r.set(p.plate); any = true; }
        if (p.logo != null) { if (any) r.union(p.logo); else r.set(p.logo); any = true; }
        for (Run run : p.runs) { if (any) r.union(run.box); else r.set(run.box); any = true; }
        if (p.window != null) return p.height - p.window.height();
        return any ? r.height() : 0;
    }

    private static int align(WatermarkSpec.Position pos) {
        return pos.center() ? 1 : pos.right() ? 2 : 0;
    }

    private static void textLayout(Plan plan, WatermarkSpec spec, WatermarkText text, float u, float m, Fonts fonts) {
        Group g = group(text.column(), text.logo, align(spec.position), u, MAIN_WEIGHT, plan.width - 2 * m, spec.font, fonts);
        if (g.width <= 0 || g.height <= 0) return;
        float x = corner(spec.position, plan.width, g.width, m), y = spec.position.top() ? m : plan.height - m - g.height;
        place(plan, g, x, y);
        plan.sample.set(x, y, x + g.width, y + g.height);
    }

    private static float corner(WatermarkSpec.Position pos, float width, float w, float m) {
        return pos.center() ? (width - w) / 2f : pos.right() ? width - m - w : m;
    }

    private static void badge(Plan plan, WatermarkSpec spec, WatermarkText text, float u, float m, Fonts fonts) {
        final float ph = .72f * u, pv = .5f * u;
        Group g = group(text.column(), text.logo, align(spec.position), u, MAIN_WEIGHT, plan.width - 2 * m - 2 * ph, spec.font, fonts);
        if (g.width <= 0 || g.height <= 0) return;
        float pw = g.width + 2 * ph, phh = g.height + 2 * pv;
        float x = corner(spec.position, plan.width, pw, m), y = spec.position.top() ? m : plan.height - m - phh;
        plan.plate = new RectF(x, y, x + pw, y + phh);
        plan.radius = Math.min(phh / 2f, 1.1f * u);
        place(plan, g, x + ph, y + pv);
        plan.sample.set(plan.plate);
    }

    /** The bar ({@code frame} false) or the frame: a band across the bottom (top) edge holding the text. */
    private static void band(Plan plan, WatermarkSpec spec, WatermarkText text, float u, float m, Fonts fonts, boolean frame) {
        final float shortSide = Math.min(plan.width, plan.height);
        final float border = frame ? shortSide * Math.max(.6f, spec.margin) / 100f : 0;
        final float padX = frame ? border : Math.max(m, u);
        final float inner = plan.width - 2 * padX;
        final boolean top = spec.position.top();
        Group lead = null, trail = null;
        if (spec.position.center()) {
            lead = group(text.column(), text.logo, 1, u, MAIN_WEIGHT, inner, spec.font, fonts);
        } else {
            final boolean right = spec.position.right();
            List<String> leading = text.leading(), trailing = text.trailing();
            final float gapMin = 1.6f * u;
            lead = group(leading, text.logo, right ? 2 : 0, u, MAIN_WEIGHT, inner, spec.font, fonts);
            if (!trailing.isEmpty()) {
                trail = group(trailing, false, right ? 0 : 2, u, SECOND_WEIGHT, inner, spec.font, fonts);
                if (lead.width + gapMin + trail.width > inner) {
                    // the exposure keeps up to 45 % of the width, the title and time the rest
                    float tMax = Math.min(trail.width, .45f * inner);
                    trail = group(trailing, false, right ? 0 : 2, u, SECOND_WEIGHT, tMax, spec.font, fonts);
                    lead = group(leading, text.logo, right ? 2 : 0, u, MAIN_WEIGHT, Math.max(0, inner - gapMin - trail.width),
                            spec.font, fonts);
                }
            }
        }
        float content = Math.max(lead.height, trail != null ? trail.height : 0);
        final float pv = frame ? Math.max(.75f * u, .6f * border) : .8f * u;
        float bandH = content > 0 ? content + 2 * pv : 0;
        if (frame) bandH = Math.max(border, bandH);
        if (!frame && bandH <= 0) return;
        final float bandTop = top ? 0 : plan.height - bandH;
        if (frame) {
            plan.window = top ? new RectF(border, bandH, plan.width - border, plan.height - border)
                    : new RectF(border, border, plan.width - border, plan.height - bandH);
            plan.windowRadius = Math.min(border * .6f, Math.min(plan.window.width(), plan.window.height()) / 4f);
            plan.sample.set(0, 0, plan.width, plan.height);
        } else {
            plan.plate = new RectF(0, bandTop, plan.width, bandTop + bandH);
            plan.radius = 0;
            plan.hairline = top ? new RectF(0, bandH - plan.line, plan.width, bandH) : new RectF(0, bandTop, plan.width, bandTop + plan.line);
            plan.sample.set(plan.plate);
        }
        if (content <= 0) return;
        final float cy = bandTop + bandH / 2f;
        if (spec.position.center()) {
            place(plan, lead, (plan.width - lead.width) / 2f, cy - lead.height / 2f);
            return;
        }
        final boolean right = spec.position.right();
        if (lead.width > 0 || lead.height > 0)
            place(plan, lead, right ? plan.width - padX - lead.width : padX, cy - lead.height / 2f);
        if (trail != null && trail.height > 0)
            place(plan, trail, right ? padX : plan.width - padX - trail.width, cy - trail.height / 2f);
    }

    private static void bounds(Plan plan, WatermarkSpec spec) {
        RectF b = plan.bounds;
        b.setEmpty();
        if (plan.window != null) { b.set(0, 0, plan.width, plan.height); return; }
        boolean any = false;
        List<RectF> parts = new ArrayList<>();
        if (plan.plate != null) parts.add(plan.plate);
        if (plan.logo != null) parts.add(plan.logo);
        for (Run r : plan.runs) parts.add(r.box);
        for (RectF r : parts) {
            if (any) b.union(r); else b.set(r);
            any = true;
        }
        if (any && spec.layout == WatermarkSpec.Layout.TEXT) b.inset(-shadowRadius(plan), -shadowRadius(plan));
        if (any) b.intersect(0, 0, plan.width, plan.height);
    }

    static float shadowRadius(Plan plan) {
        return .1f * plan.unit;
    }

    // ───── Colour ─────

    /** «Авто»: the mean brightness under the watermark picks its ink; white / black as chosen. */
    public static void chooseInk(Plan plan, WatermarkSpec spec, @Nullable Bitmap photo) {
        switch (spec.colour) {
            case WHITE: plan.whiteInk = true; return;
            case BLACK: plan.whiteInk = false; return;
            default: plan.whiteInk = !(photo != null && meanLuma(photo, plan.sample) > BRIGHT);
        }
    }

    /**
     * P633: «Авто» read from {@code small}, a smaller picture of the whole photo (the gallery editor's preview of the crop): the plan's
     * sample scaled to it. White / black as chosen; without a picture «Авто» keeps white.
     */
    public static void chooseInkScaled(Plan plan, WatermarkSpec spec, @Nullable Bitmap small) {
        if (spec.colour != WatermarkSpec.Colour.AUTO || small == null || plan.width <= 0 || plan.height <= 0) {
            chooseInk(plan, spec, null);
            return;
        }
        final float kx = small.getWidth() / (float) plan.width, ky = small.getHeight() / (float) plan.height;
        RectF r = new RectF(plan.sample.left * kx, plan.sample.top * ky, plan.sample.right * kx, plan.sample.bottom * ky);
        plan.whiteInk = !(meanLuma(small, r) > BRIGHT);
    }

    /** P642: the photo pixels {@link #chooseInk}'s «Авто» reads for {@code plan} (its {@link #meanLuma} corners); null: none. */
    @Nullable
    public static android.graphics.Rect sampled(Plan plan) {
        RectF r = plan.sample;
        int x0 = Math.max(0, (int) r.left), y0 = Math.max(0, (int) r.top);
        int x1 = Math.min(plan.width - 1, (int) r.right), y1 = Math.min(plan.height - 1, (int) r.bottom);
        return x1 < x0 || y1 < y0 ? null : new android.graphics.Rect(x0, y0, x1 + 1, y1 + 1);
    }

    /** The mean sRGB luma (0..1) of {@code photo} in {@code r}, on a grid of up to 16 x 8 points; 0 for an empty region. */
    static float meanLuma(Bitmap photo, RectF r) {
        int x0 = Math.max(0, (int) r.left), y0 = Math.max(0, (int) r.top);
        int x1 = Math.min(photo.getWidth() - 1, (int) r.right), y1 = Math.min(photo.getHeight() - 1, (int) r.bottom);
        if (x1 < x0 || y1 < y0) return 0;
        double sum = 0;
        int n = 0;
        for (int j = 0; j < 8; j++) {
            int y = y0 + (int) ((y1 - y0) * (j + .5f) / 8f);
            for (int i = 0; i < 16; i++) {
                int x = x0 + (int) ((x1 - x0) * (i + .5f) / 16f);
                int c = photo.getPixel(x, y);
                sum += (.2126 * ((c >> 16) & 255) + .7152 * ((c >> 8) & 255) + .0722 * (c & 255)) / 255.0;
                n++;
            }
        }
        return n == 0 ? 0 : (float) (sum / n);
    }

    // ───── Drawing ─────

    private static int alpha(int colour, float a) {
        int al = Math.round(((colour >>> 24) & 255) * Math.max(0, Math.min(1, a)));
        return (al << 24) | (colour & 0x00FFFFFF);
    }

    /** Draws {@code plan} on {@code canvas} (the photo's own pixels). */
    public static void draw(Canvas canvas, Plan plan, WatermarkSpec spec, Fonts fonts) {
        if (plan.empty()) return;
        final float op = spec.opacity;
        final boolean white = plan.whiteInk;
        final int ink = white ? INK_WHITE : INK_BLACK;
        final int muted = alpha(ink, white ? .82f : .72f);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        if (plan.window != null) {
            Path frame = new Path();
            frame.setFillType(Path.FillType.EVEN_ODD);
            frame.addRect(0, 0, plan.width, plan.height, Path.Direction.CW);
            frame.addRoundRect(plan.window, plan.windowRadius, plan.windowRadius, Path.Direction.CW);
            fill.setColor(alpha(white ? FRAME_DARK : FRAME_LIGHT, op));
            canvas.drawPath(frame, fill);
        }
        if (plan.plate != null) {
            final boolean bar = plan.layout == WatermarkSpec.Layout.BAR;
            fill.setColor(alpha((white ? GLASS_DARK : GLASS_LIGHT) | 0xFF000000, op * (white ? (bar ? .74f : .70f) : (bar ? .86f : .84f))));
            canvas.drawRoundRect(plan.plate, plan.radius, plan.radius, fill);
            if (bar && plan.hairline != null) {
                fill.setColor(alpha(white ? LINE_DARK : LINE_LIGHT, op));
                canvas.drawRect(plan.hairline, fill);
            } else if (!bar) {
                Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(plan.line);
                stroke.setColor(alpha(white ? LINE_DARK : LINE_LIGHT, op));
                RectF edge = new RectF(plan.plate);
                edge.inset(plan.line / 2f, plan.line / 2f);
                canvas.drawRoundRect(edge, Math.max(0, plan.radius - plan.line / 2f), Math.max(0, plan.radius - plan.line / 2f), stroke);
            }
        }
        final boolean shadow = plan.layout == WatermarkSpec.Layout.TEXT;
        final float sr = shadowRadius(plan);
        if (plan.logo != null) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(alpha(ink, op));
            if (shadow) shadow(p, white, sr, plan.unit, op);
            drawLogo(canvas, plan.logo, p, alpha(white ? ACCENT : ACCENT_ON_LIGHT, op));
        }
        for (Run r : plan.runs) {
            Paint p = paint(fonts, spec.font, r.weight, r.size);
            p.setColor(alpha(r.main ? ink : muted, op));
            if (shadow) shadow(p, white, sr, plan.unit, op);
            canvas.drawText(r.text, r.x, r.baseline, p);
        }
    }

    private static void shadow(Paint p, boolean white, float radius, float unit, float op) {
        if (white) p.setShadowLayer(radius, 0, .03f * unit, alpha(0x73000000, op));
        else p.setShadowLayer(radius * .8f, 0, 0, alpha(0x40FFFFFF, op));
    }

    /**
     * The SCAMERA glyph in {@code box}: the launcher icon's silhouette (the body with the viewfinder tab, the lens ring cut out and
     * its centre, scamera_launch_mono) in {@code body}'s paint, the LED in {@code led}.
     */
    public static void drawLogo(Canvas canvas, RectF box, Paint body, int led) {
        final float s = box.width() / LOGO_W, ox = box.left - 33f * s, oy = box.top - 36.78f * s;
        Path shape = new Path();
        shape.addRoundRect(new RectF(ox + 33f * s, oy + 41.4f * s, ox + 75f * s, oy + 70.8f * s), 8.4f * s, 8.4f * s, Path.Direction.CW);
        Path tab = new Path();
        tab.addRoundRect(new RectF(ox + 38.88f * s, oy + 36.78f * s, ox + 49.8f * s, oy + 44.34f * s), 2.52f * s, 2.52f * s, Path.Direction.CW);
        shape.op(tab, Path.Op.UNION);
        Path ring = new Path();
        ring.addCircle(ox + 54f * s, oy + 56.52f * s, 9.408f * s, Path.Direction.CW);
        shape.op(ring, Path.Op.DIFFERENCE);
        Path dot = new Path();
        dot.addCircle(ox + 68.28f * s, oy + 47.7f * s, 2.016f * s, Path.Direction.CW);
        shape.op(dot, Path.Op.DIFFERENCE);
        Path lens = new Path();
        lens.addCircle(ox + 54f * s, oy + 56.52f * s, 5.806f * s, Path.Direction.CW);
        shape.op(lens, Path.Op.UNION);
        canvas.drawPath(shape, body);
        Paint p = new Paint(body);
        p.setColor(led);
        canvas.drawPath(dot, p);
    }

    /**
     * Draws the watermark of {@code spec} / {@code text} into {@code photo} (mutable) and returns the plan; null when nothing was
     * drawn.
     */
    @Nullable
    public static Plan drawOn(Bitmap photo, WatermarkSpec spec, WatermarkText text, Fonts fonts) {
        if (!text.any() && spec.layout != WatermarkSpec.Layout.FRAME) return null;
        Plan plan = plan(spec, text, photo.getWidth(), photo.getHeight(), fonts);
        if (plan.empty()) return null;
        chooseInk(plan, spec, photo);
        draw(new Canvas(photo), plan, spec, fonts);
        return plan;
    }

    /** The lines of a plan, in order (tests, logs). */
    public static List<String> texts(Plan plan) {
        if (plan.runs.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (Run r : plan.runs) out.add(r.text);
        return out;
    }
}
