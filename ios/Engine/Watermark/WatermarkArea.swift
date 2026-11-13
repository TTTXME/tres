import Foundation

/// The part of a photo a watermark's drawing may touch (Android WatermarkArea.java, P633): the plan's own boxes - each line of
/// text, the glyph, the badge's pill, the bar's band, the frame's four border strips - grown by what a draw spreads past its box
/// (the shadow, a glyph's side bearings, antialiasing), on a grid of 16 px blocks (the JPEG 4:2:0 MCU) with every neighbouring
/// block taken too (a lossy codec spreads a change past its block), merged into rectangles. Android keeps the original pixels
/// of these rectangles in the photo's file so the gallery's editor can take the watermark off; the iOS shot uses them for its log
/// (the record itself is not ported yet: ios/PLAN.md «Watermark»).
enum WatermarkArea {
    /// The grid (px).
    static let block = 16
    /// Blocks taken on every side of a touched one.
    static let dilate = 1
    /// Extra px around every part: antialiasing and the glyphs' overhang of their measured boxes.
    static let edgePx: Float = 2

    /// android.graphics.Rect: integer pixels, right / bottom exclusive.
    struct PixelRect: Equatable {
        var left: Int, top: Int, right: Int, bottom: Int
        var width: Int { right - left }
        var height: Int { bottom - top }
    }

    /// The boxes the plan draws into (photo px, as viewed), grown by the draw's spread; empty for an empty plan.
    static func parts(_ plan: WatermarkPainter.Plan) -> [WatermarkRect] {
        var out: [WatermarkRect] = []
        if plan.empty { return out }
        let u = plan.unit
        // the text's shadow: about 3 sigma = 1.73 x radius + 1.5 px, offset .03 u down; a glyph may overhang its advance
        let shadow: Float = plan.layout == .text ? 1.8 * WatermarkPainter.shadowRadius(plan) + 1.5 + 0.03 * u : 0
        let spread = shadow + 0.12 * u + edgePx
        let width = Float(plan.width), height = Float(plan.height)
        if let w = plan.window {
            let r = plan.windowRadius + edgePx
            out.append(WatermarkRect(0, 0, width, w.top + r))
            out.append(WatermarkRect(0, w.bottom - r, width, height))
            out.append(WatermarkRect(0, w.top, w.left + r, w.bottom))
            out.append(WatermarkRect(w.right - r, w.top, width, w.bottom))
        }
        if let plate = plan.plate { out.append(grown(plate, edgePx)) }
        if let hairline = plan.hairline { out.append(grown(hairline, edgePx)) }
        if let logo = plan.logo { out.append(grown(logo, spread)) }
        for r in plan.runs { out.append(grown(r.box, spread)) }
        return out
    }

    private static func grown(_ r: WatermarkRect, _ by: Float) -> WatermarkRect {
        var g = r
        g.inset(-by, -by)
        return g
    }

    /// The rectangles kept for `parts` on a `width` x `height` photo: the touched blocks and their neighbours, as row runs merged
    /// down; inside the photo, in raster order, not overlapping.
    static func rects(_ parts: [WatermarkRect], width: Int, height: Int) -> [PixelRect] {
        var out: [PixelRect] = []
        if width <= 0 || height <= 0 || parts.isEmpty { return out }
        let bw = (width + block - 1) / block, bh = (height + block - 1) / block
        var hit = [Bool](repeating: false, count: bw * bh)
        let fb = Float(block)
        for p in parts {
            if !(p.right > 0 && p.bottom > 0 && p.left < Float(width) && p.top < Float(height)) { continue }
            let x0 = clamp(floorInt(Swift.max(0, p.left) / fb) - dilate, bw)
            let y0 = clamp(floorInt(Swift.max(0, p.top) / fb) - dilate, bh)
            let x1 = clamp(ceilInt(Swift.min(Float(width), p.right) / fb) - 1 + dilate, bw)
            let y1 = clamp(ceilInt(Swift.min(Float(height), p.bottom) / fb) - 1 + dilate, bh)
            if y0 > y1 || x0 > x1 { continue }
            for by in y0...y1 { for bx in x0...x1 { hit[by * bw + bx] = true } }
        }
        // row runs; a run continuing the same run of the row above grows that rectangle
        var open: [(bx0: Int, bx1: Int, index: Int)] = []
        for by in 0..<bh {
            var next: [(bx0: Int, bx1: Int, index: Int)] = []
            var bx = 0
            while bx < bw {
                if !hit[by * bw + bx] { bx += 1; continue }
                let start = bx
                while bx < bw && hit[by * bw + bx] { bx += 1 }
                if let grown = open.first(where: { $0.bx0 == start && $0.bx1 == bx }) {
                    out[grown.index].bottom = Swift.min(height, (by + 1) * block)
                    next.append(grown)
                } else {
                    out.append(PixelRect(left: start * block, top: by * block, right: Swift.min(width, bx * block),
                                         bottom: Swift.min(height, (by + 1) * block)))
                    next.append((start, bx, out.count - 1))
                }
            }
            open = next
        }
        return out
    }

    /// Math.floor / Math.ceil of a float division as Java's (int) of the double.
    private static func floorInt(_ v: Float) -> Int { WatermarkPainter.javaInt(v.rounded(.down)) }
    private static func ceilInt(_ v: Float) -> Int { WatermarkPainter.javaInt(v.rounded(.up)) }

    private static func clamp(_ v: Int, _ n: Int) -> Int {
        Swift.max(0, Swift.min(n - 1, v))
    }

    /// The rectangles of `plan`'s watermark.
    static func of(_ plan: WatermarkPainter.Plan) -> [PixelRect] {
        rects(parts(plan), width: plan.width, height: plan.height)
    }

    /// Pixels in `rects`.
    static func pixels(_ rects: [PixelRect]) -> Int {
        rects.reduce(0) { $0 + $1.width * $1.height }
    }
}
