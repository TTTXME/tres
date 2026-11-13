import CoreGraphics
import CoreText
import Foundation

/// android.graphics.RectF in the watermark's single-precision geometry (left, top, right, bottom; y down, the photo as viewed).
struct WatermarkRect: Equatable {
    var left: Float, top: Float, right: Float, bottom: Float

    init(_ left: Float, _ top: Float, _ right: Float, _ bottom: Float) {
        self.left = left; self.top = top; self.right = right; self.bottom = bottom
    }

    static let zero = WatermarkRect(0, 0, 0, 0)

    var width: Float { right - left }
    var height: Float { bottom - top }
    var centerX: Float { (left + right) * 0.5 }
    var centerY: Float { (top + bottom) * 0.5 }
    var isEmpty: Bool { left >= right || top >= bottom }
    var cg: CGRect { CGRect(x: CGFloat(left), y: CGFloat(top), width: CGFloat(width), height: CGFloat(height)) }

    /// RectF.union: an empty `r` changes nothing, an empty self becomes `r`.
    mutating func union(_ r: WatermarkRect) {
        guard r.left < r.right && r.top < r.bottom else { return }
        if left < right && top < bottom {
            left = Swift.min(left, r.left); top = Swift.min(top, r.top)
            right = Swift.max(right, r.right); bottom = Swift.max(bottom, r.bottom)
        } else {
            self = r
        }
    }

    mutating func inset(_ dx: Float, _ dy: Float) {
        left += dx; top += dy; right -= dx; bottom -= dy
    }

    /// RectF.intersect: false (self unchanged) when they do not overlap.
    @discardableResult
    mutating func intersect(_ l: Float, _ t: Float, _ r: Float, _ b: Float) -> Bool {
        guard left < r && l < right && top < b && t < bottom else { return false }
        left = Swift.max(left, l); top = Swift.max(top, t); right = Swift.min(right, r); bottom = Swift.min(bottom, b)
        return true
    }

    func contains(_ r: WatermarkRect) -> Bool {
        left < right && top < bottom && left <= r.left && top <= r.top && right >= r.right && bottom >= r.bottom
    }

    func intersects(_ r: WatermarkRect) -> Bool {
        left < r.right && r.left < right && top < r.bottom && r.top < bottom
    }
}

/// The watermark's text measures, as Android's Paint gives them: `metrics` FontMetrics.ascent (negative) and descent, `measure`
/// measureText with the letter spacing (em) applied. WatermarkFonts measures with CoreText; the tests with fixed advances.
protocol WatermarkFontSource {
    func metrics(_ font: WatermarkSpec.Font, weight: Int, size: Float) -> (ascent: Float, descent: Float)
    func measure(_ text: String, font: WatermarkSpec.Font, weight: Int, size: Float, spacing: Float) -> Float
}

/// The watermark's layout and drawing (Android WatermarkPainter.java, P628), in the photo's own pixels as it is viewed (upright),
/// after every crop and resize of the shot. Everything is measured on the short side. Four layouts: TEXT «Подпись» (glyph and up to
/// two lines with a soft shadow), BADGE «Плашка» (the same in a glass pill with a hairline), BAR «Полоса» (a glass band across the
/// bottom / top edge: title and time on one side, exposure on the other), FRAME «Рамка» (a border over the photo's edges with a
/// rounded window and a wider band holding the text; the photo keeps its size). The geometry is Android's, in Float as Java's
/// float; the text is CoreText's (not Skia's glyphs: pixel identity with Android's Canvas is not possible).
enum WatermarkPainter {
    /// The camera's yellow on a dark watermark, a deeper amber on a light one: the glyph's LED (ARGB).
    static let accent: UInt32 = 0xFFFF_D447, accentOnLight: UInt32 = 0xFFE0_A100
    static let inkWhite: UInt32 = 0xFFFF_FFFF, inkBlack: UInt32 = 0xFF14_1517
    /// The shade's sheet and the settings' light text as glass (RGB); the hairlines of the shade's tiles (ARGB).
    static let glassDark: UInt32 = 0x1C_1C1E, glassLight: UInt32 = 0xF4_F3F7, lineDark: UInt32 = 0x29FF_FFFF, lineLight: UInt32 = 0x1F00_0000
    static let frameDark: UInt32 = 0xFF10_1214, frameLight: UInt32 = 0xFFFF_FFFF
    /// The main line's weight and the others'.
    static let mainWeight = 600, secondWeight = 500
    /// The second line's size per main size.
    static let second: Float = 0.74
    /// The glyph's box: the launcher icon's silhouette (x 33..75, y 36.78..70.8).
    static let logoW: Float = 42, logoH: Float = 34.02
    /// Brighter than this mean (0..1, sRGB) under the watermark: black text (a light plate / white frame) in «Авто».
    static let bright: Float = 0.58

    /// One line of text as placed: its baseline's start, its size and weight, its box (font ascent to descent).
    struct Run: Equatable {
        let text: String
        let x: Float, baseline: Float, size: Float
        let weight: Int
        let main: Bool
        let box: WatermarkRect
    }

    /// Everything a watermark draws on a `width` x `height` photo.
    struct Plan {
        let width: Int, height: Int
        let layout: WatermarkSpec.Layout
        var runs: [Run] = []
        /// The glyph's box (nil: none).
        var logo: WatermarkRect?
        /// The badge's pill or the bar's band (nil: none), its corner radius.
        var plate: WatermarkRect?
        var radius: Float = 0
        /// The frame's window onto the photo and its corner radius (nil: no frame).
        var window: WatermarkRect?
        var windowRadius: Float = 0
        /// The bar's hairline on its inner edge (nil: none).
        var hairline: WatermarkRect?
        /// The plate's stroke width (px).
        var line: Float = 0
        /// The main text size (px).
        var unit: Float = 0
        /// The part of the photo «Авто» reads.
        var sample = WatermarkRect.zero
        /// Everything drawn, the text's shadow included.
        var bounds = WatermarkRect.zero
        /// White text (a dark plate / frame) or black text (a light one): chooseInk.
        var whiteInk = true

        init(width: Int, height: Int, layout: WatermarkSpec.Layout) {
            self.width = width; self.height = height; self.layout = layout
        }

        /// Whether the plan draws anything.
        var empty: Bool { runs.isEmpty && logo == nil && plate == nil && window == nil }

        /// The lines in order (tests, logs).
        var texts: [String] { runs.map(\.text) }
    }

    // ───── Layout ─────

    /// Lines of one column, measured at a main size.
    private struct Column {
        var lines: [String] = [], sizes: [Float] = [], weights: [Int] = [], widths: [Float] = [], ascents: [Float] = [], descents: [Float] = []
        var width: Float = 0, height: Float = 0, gap: Float = 0
        var empty: Bool { lines.isEmpty }
    }

    /// A column with the glyph beside it (before it, or after it on the right edge).
    private struct Group {
        var column = Column()
        var logo = false, logoAfter = false
        var logoW: Float = 0, logoH: Float = 0, logoGap: Float = 0, width: Float = 0, height: Float = 0
        /// 0 left, 1 centre, 2 right: the lines inside the column.
        var align = 0
    }

    /// Paint.setLetterSpacing of the watermark's text (em): none for the monospace.
    static func letterSpacing(_ font: WatermarkSpec.Font, weight: Int) -> Float {
        font == .mono ? 0 : weight >= mainWeight ? 0.01 : 0.02
    }

    /// `lines` at main size `u`: the first line main (`mainWeight`), the rest second; each cut to `max`.
    private static func column(_ lines: [String], _ u: Float, _ mainWeight: Int, _ max: Float, _ font: WatermarkSpec.Font,
                               _ fonts: WatermarkFontSource) -> Column {
        var c = Column()
        c.gap = 0.16 * u
        for i in lines.indices {
            let main = i == 0
            let size = main ? u : u * second
            let weight = main ? mainWeight : secondWeight
            let spacing = letterSpacing(font, weight: weight)
            let measure = { (s: String) in fonts.measure(s, font: font, weight: weight, size: size, spacing: spacing) }
            let text = ellipsize(lines[i], max, measure)
            if text.isEmpty { continue }
            let fm = fonts.metrics(font, weight: weight, size: size)
            c.lines.append(text)
            c.sizes.append(size)
            c.weights.append(weight)
            let w = measure(text)
            c.widths.append(w)
            c.ascents.append(fm.ascent)
            c.descents.append(fm.descent)
            c.width = Swift.max(c.width, w)
            c.height += (fm.descent - fm.ascent) + (c.lines.count > 1 ? c.gap : 0)
        }
        return c
    }

    /// `text` cut with «…» to `max` px; "" when not even «…» fits (Java's binary search over UTF-16 units, surrogates kept whole).
    static func ellipsize(_ text: String, _ max: Float, _ measure: (String) -> Float) -> String {
        if text.isEmpty { return "" }
        if measure(text) <= max { return text }
        let dots = "…"
        if measure(dots) > max { return "" }
        let units = Array(text.utf16)
        func head(_ end: Int) -> String {
            String(decoding: WatermarkText.javaTrim(Array(units[0..<safeEnd(units, end)])), as: UTF16.self)
        }
        var lo = 0, hi = units.count
        while lo < hi {
            let mid = (lo + hi + 1) >> 1
            if measure(head(mid) + dots) <= max { lo = mid } else { hi = mid - 1 }
        }
        let h = head(lo)
        return h.isEmpty ? "" : h + dots
    }

    private static func safeEnd(_ s: [UInt16], _ end: Int) -> Int {
        end > 0 && end < s.count && UTF16.isLeadSurrogate(s[end - 1]) ? end - 1 : end
    }

    private static func group(_ lines: [String], _ logo: Bool, _ align: Int, _ u: Float, _ mainWeight: Int, _ maxWidth: Float,
                              _ font: WatermarkSpec.Font, _ fonts: WatermarkFontSource) -> Group {
        var g = Group()
        g.align = align
        g.logo = logo
        g.logoAfter = align == 2
        g.logoGap = 0.5 * u
        // the glyph first at the height of one main line (its final height follows the column)
        let logoH0 = 1.15 * u, logoW0 = logoH0 * logoW / logoH
        let textMax = maxWidth - (logo ? logoW0 + g.logoGap : 0)
        g.column = column(lines, u, mainWeight, Swift.max(0, textMax), font, fonts)
        if logo {
            g.logoH = g.column.empty ? 1.5 * u : Swift.max(1.05 * u, Swift.min(1.9 * u, g.column.height * 0.82))
            g.logoW = g.logoH * logoW / logoH
            if g.logoW > maxWidth { // a photo narrower than the glyph: no glyph
                g.logo = false
            } else if !g.column.empty && g.logoW + g.logoGap + g.column.width > maxWidth {
                g.column = column(lines, u, mainWeight, Swift.max(0, maxWidth - g.logoW - g.logoGap), font, fonts)
            }
        }
        g.width = (g.logo ? g.logoW + (g.column.empty ? 0 : g.logoGap) : 0) + g.column.width
        g.height = Swift.max(g.logo ? g.logoH : 0, g.column.height)
        return g
    }

    /// Places `g` with its top left at x, y.
    private static func place(_ plan: inout Plan, _ g: Group, _ x: Float, _ y: Float) {
        var textX = x
        if g.logo {
            let lx = g.logoAfter ? x + g.width - g.logoW : x
            let ly = y + (g.height - g.logoH) / 2
            plan.logo = WatermarkRect(lx, ly, lx + g.logoW, ly + g.logoH)
            if !g.logoAfter && !g.column.empty { textX = x + g.logoW + g.logoGap }
        }
        let c = g.column
        var top = y + (g.height - c.height) / 2
        for i in c.lines.indices {
            let w = c.widths[i]
            let lx = g.align == 0 ? textX : g.align == 1 ? textX + (c.width - w) / 2 : textX + c.width - w
            let baseline = top - c.ascents[i]
            let box = WatermarkRect(lx, top, lx + w, top + c.descents[i] - c.ascents[i])
            plan.runs.append(Run(text: c.lines[i], x: lx, baseline: baseline, size: c.sizes[i], weight: c.weights[i], main: i == 0, box: box))
            top = box.bottom + c.gap
        }
    }

    /// The plan of `text` by `spec` on a `width` x `height` photo (as viewed). A plan that would draw nothing (no field, no glyph;
    /// a frame always draws) is empty.
    static func plan(_ spec: WatermarkSpec, _ text: WatermarkText, width: Int, height: Int, fonts: WatermarkFontSource) -> Plan {
        var plan = Plan(width: width, height: height, layout: spec.layout)
        if width <= 0 || height <= 0 { return plan }
        let shortSide = Float(Swift.min(width, height))
        var u = Swift.max(4, shortSide * spec.size / 100 * WatermarkSpec.titleShare)
        let m = shortSide * Swift.max(0, spec.margin) / 100
        // a watermark taller than a third of the photo is scaled down (tiny pictures, the largest size)
        for _ in 0..<4 {
            plan = Plan(width: width, height: height, layout: spec.layout)
            plan.unit = u
            plan.line = Swift.max(1, u * 0.045)
            switch spec.layout {
            case .badge: badge(&plan, spec, text, u, m, fonts)
            case .bar: band(&plan, spec, text, u, m, fonts, frame: false)
            case .frame: band(&plan, spec, text, u, m, fonts, frame: true)
            case .text: textLayout(&plan, spec, text, u, m, fonts)
            }
            let tall = contentHeight(plan)
            if tall <= Float(height) / 3 || spec.layout == .frame && tall <= Float(height) / 2 { break }
            u *= 0.75
        }
        bounds(&plan, spec)
        return plan
    }

    private static func contentHeight(_ p: Plan) -> Float {
        var r = WatermarkRect.zero
        var any = false
        if let plate = p.plate { r = plate; any = true }
        if let logo = p.logo { if any { r.union(logo) } else { r = logo }; any = true }
        for run in p.runs { if any { r.union(run.box) } else { r = run.box }; any = true }
        if let window = p.window { return Float(p.height) - window.height }
        return any ? r.height : 0
    }

    private static func align(_ pos: WatermarkSpec.Position) -> Int {
        pos.center ? 1 : pos.right ? 2 : 0
    }

    private static func textLayout(_ plan: inout Plan, _ spec: WatermarkSpec, _ text: WatermarkText, _ u: Float, _ m: Float,
                                   _ fonts: WatermarkFontSource) {
        let g = group(text.column, text.logo, align(spec.position), u, mainWeight, Float(plan.width) - 2 * m, spec.font, fonts)
        if g.width <= 0 || g.height <= 0 { return }
        let x = corner(spec.position, Float(plan.width), g.width, m), y = spec.position.top ? m : Float(plan.height) - m - g.height
        place(&plan, g, x, y)
        plan.sample = WatermarkRect(x, y, x + g.width, y + g.height)
    }

    private static func corner(_ pos: WatermarkSpec.Position, _ width: Float, _ w: Float, _ m: Float) -> Float {
        pos.center ? (width - w) / 2 : pos.right ? width - m - w : m
    }

    private static func badge(_ plan: inout Plan, _ spec: WatermarkSpec, _ text: WatermarkText, _ u: Float, _ m: Float,
                              _ fonts: WatermarkFontSource) {
        let ph = 0.72 * u, pv = 0.5 * u
        let g = group(text.column, text.logo, align(spec.position), u, mainWeight, Float(plan.width) - 2 * m - 2 * ph, spec.font, fonts)
        if g.width <= 0 || g.height <= 0 { return }
        let pw = g.width + 2 * ph, phh = g.height + 2 * pv
        let x = corner(spec.position, Float(plan.width), pw, m), y = spec.position.top ? m : Float(plan.height) - m - phh
        let plate = WatermarkRect(x, y, x + pw, y + phh)
        plan.plate = plate
        plan.radius = Swift.min(phh / 2, 1.1 * u)
        place(&plan, g, x + ph, y + pv)
        plan.sample = plate
    }

    /// The bar (`frame` false) or the frame: a band across the bottom (top) edge holding the text.
    private static func band(_ plan: inout Plan, _ spec: WatermarkSpec, _ text: WatermarkText, _ u: Float, _ m: Float,
                             _ fonts: WatermarkFontSource, frame: Bool) {
        let width = Float(plan.width), height = Float(plan.height)
        let shortSide = Swift.min(width, height)
        let border: Float = frame ? shortSide * Swift.max(0.6, spec.margin) / 100 : 0
        let padX = frame ? border : Swift.max(m, u)
        let inner = width - 2 * padX
        let top = spec.position.top
        var lead: Group
        var trail: Group?
        if spec.position.center {
            lead = group(text.column, text.logo, 1, u, mainWeight, inner, spec.font, fonts)
        } else {
            let right = spec.position.right
            let leading = text.leading, trailing = text.trailing
            let gapMin = 1.6 * u
            lead = group(leading, text.logo, right ? 2 : 0, u, mainWeight, inner, spec.font, fonts)
            if !trailing.isEmpty {
                var t = group(trailing, false, right ? 0 : 2, u, secondWeight, inner, spec.font, fonts)
                if lead.width + gapMin + t.width > inner {
                    // the exposure keeps up to 45 % of the width, the title and time the rest
                    let tMax = Swift.min(t.width, 0.45 * inner)
                    t = group(trailing, false, right ? 0 : 2, u, secondWeight, tMax, spec.font, fonts)
                    lead = group(leading, text.logo, right ? 2 : 0, u, mainWeight, Swift.max(0, inner - gapMin - t.width), spec.font, fonts)
                }
                trail = t
            }
        }
        let content = Swift.max(lead.height, trail?.height ?? 0)
        let pv: Float = frame ? Swift.max(0.75 * u, 0.6 * border) : 0.8 * u
        var bandH: Float = content > 0 ? content + 2 * pv : 0
        if frame { bandH = Swift.max(border, bandH) }
        if !frame && bandH <= 0 { return }
        let bandTop: Float = top ? 0 : height - bandH
        if frame {
            let window = top ? WatermarkRect(border, bandH, width - border, height - border)
                : WatermarkRect(border, border, width - border, height - bandH)
            plan.window = window
            plan.windowRadius = Swift.min(border * 0.6, Swift.min(window.width, window.height) / 4)
            plan.sample = WatermarkRect(0, 0, width, height)
        } else {
            let plate = WatermarkRect(0, bandTop, width, bandTop + bandH)
            plan.plate = plate
            plan.radius = 0
            plan.hairline = top ? WatermarkRect(0, bandH - plan.line, width, bandH) : WatermarkRect(0, bandTop, width, bandTop + plan.line)
            plan.sample = plate
        }
        if content <= 0 { return }
        let cy = bandTop + bandH / 2
        if spec.position.center {
            place(&plan, lead, (width - lead.width) / 2, cy - lead.height / 2)
            return
        }
        let right = spec.position.right
        if lead.width > 0 || lead.height > 0 {
            place(&plan, lead, right ? width - padX - lead.width : padX, cy - lead.height / 2)
        }
        if let trail, trail.height > 0 {
            place(&plan, trail, right ? padX : width - padX - trail.width, cy - trail.height / 2)
        }
    }

    private static func bounds(_ plan: inout Plan, _ spec: WatermarkSpec) {
        var b = WatermarkRect.zero
        defer { plan.bounds = b }
        if plan.window != nil { b = WatermarkRect(0, 0, Float(plan.width), Float(plan.height)); return }
        var any = false
        var parts: [WatermarkRect] = []
        if let plate = plan.plate { parts.append(plate) }
        if let logo = plan.logo { parts.append(logo) }
        parts += plan.runs.map(\.box)
        for r in parts {
            if any { b.union(r) } else { b = r }
            any = true
        }
        if any && spec.layout == .text { b.inset(-shadowRadius(plan), -shadowRadius(plan)) }
        if any { b.intersect(0, 0, Float(plan.width), Float(plan.height)) }
    }

    static func shadowRadius(_ plan: Plan) -> Float {
        0.1 * plan.unit
    }

    // ───── Colour ─────

    /// «Авто»: the mean brightness under the watermark picks its ink; white / black as chosen. `rows`: RGBA8 rows (nil: none).
    static func chooseInk(_ plan: inout Plan, _ spec: WatermarkSpec, rows: WatermarkRows?) {
        switch spec.colour {
        case .white: plan.whiteInk = true
        case .black: plan.whiteInk = false
        case .auto:
            let sample = plan.sample
            plan.whiteInk = !(rows.map { meanLuma($0, sample) > bright } ?? false)
        }
    }

    /// The mean sRGB luma (0..1) of the rows in `r`, on a grid of up to 16 x 8 points; 0 for an empty region.
    static func meanLuma(_ rows: WatermarkRows, _ r: WatermarkRect) -> Float {
        let x0 = Swift.max(0, javaInt(r.left)), y0 = Swift.max(0, javaInt(r.top))
        let x1 = Swift.min(rows.width - 1, javaInt(r.right)), y1 = Swift.min(rows.height - 1, javaInt(r.bottom))
        if x1 < x0 || y1 < y0 { return 0 }
        var sum = 0.0
        var n = 0
        for j in 0..<8 {
            let y = y0 + javaInt(Float(y1 - y0) * (Float(j) + 0.5) / 8)
            for i in 0..<16 {
                let x = x0 + javaInt(Float(x1 - x0) * (Float(i) + 0.5) / 16)
                let p = rows.base + y * rows.bytesPerRow + 4 * x
                let red = Double(p[0]), green = Double(p[1]), blue = Double(p[2])
                sum += (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255.0
                n += 1
            }
        }
        return n == 0 ? 0 : Float(sum / Double(n))
    }

    /// Java's (int) of a float: toward zero, NaN 0, saturated.
    static func javaInt(_ v: Float) -> Int {
        if v.isNaN { return 0 }
        if v >= Float(Int32.max) { return Int(Int32.max) }
        if v <= Float(Int32.min) { return Int(Int32.min) }
        return Int(v.rounded(.towardZero))
    }

    // ───── Drawing ─────

    /// `colour` (ARGB) with its alpha times `a` (Math.round of the float product).
    static func alpha(_ colour: UInt32, _ a: Float) -> UInt32 {
        let al = UInt32(Swift.max(0, (Float((colour >> 24) & 255) * Swift.max(0, Swift.min(1, a)) + 0.5).rounded(.down)))
        return (Swift.min(al, 255) << 24) | (colour & 0x00FF_FFFF)
    }

    static func cgColor(_ argb: UInt32) -> CGColor {
        let space = CGColorSpace(name: CGColorSpace.sRGB)!
        let c: [CGFloat] = [CGFloat((argb >> 16) & 255) / 255, CGFloat((argb >> 8) & 255) / 255, CGFloat(argb & 255) / 255,
                            CGFloat(argb >> 24) / 255]
        return CGColor(colorSpace: space, components: c)!
    }

    /// A rounded rectangle as Path.addRoundRect draws it (CoreGraphics needs the radius within half of each side).
    static func roundRect(_ r: WatermarkRect, _ radius: Float) -> CGPath {
        let rect = r.cg
        let k = Swift.max(0, Swift.min(CGFloat(radius), rect.width / 2, rect.height / 2))
        return k > 0 ? CGPath(roundedRect: rect, cornerWidth: k, cornerHeight: k, transform: nil) : CGPath(rect: rect, transform: nil)
    }

    /// Draws `plan` into `context`, whose user space is the photo's pixels with y down (WatermarkRows.context).
    static func draw(_ context: CGContext, _ plan: Plan, _ spec: WatermarkSpec, fonts: WatermarkFonts) {
        if plan.empty { return }
        let op = spec.opacity
        let white = plan.whiteInk
        let ink = white ? inkWhite : inkBlack
        let muted = alpha(ink, white ? 0.82 : 0.72)
        context.setShouldAntialias(true)
        if let window = plan.window {
            let frame = CGMutablePath()
            frame.addRect(CGRect(x: 0, y: 0, width: plan.width, height: plan.height))
            frame.addPath(roundRect(window, plan.windowRadius))
            context.setFillColor(cgColor(alpha(white ? frameDark : frameLight, op)))
            context.addPath(frame)
            context.fillPath(using: .evenOdd)
        }
        if let plate = plan.plate {
            let bar = plan.layout == .bar
            context.setFillColor(cgColor(alpha((white ? glassDark : glassLight) | 0xFF00_0000, op * (white ? (bar ? 0.74 : 0.70) : (bar ? 0.86 : 0.84)))))
            context.addPath(roundRect(plate, plan.radius))
            context.fillPath()
            if bar, let hairline = plan.hairline {
                context.setFillColor(cgColor(alpha(white ? lineDark : lineLight, op)))
                context.fill(hairline.cg)
            } else if !bar {
                var edge = plate
                edge.inset(plan.line / 2, plan.line / 2)
                context.setStrokeColor(cgColor(alpha(white ? lineDark : lineLight, op)))
                context.setLineWidth(CGFloat(plan.line))
                context.addPath(roundRect(edge, Swift.max(0, plan.radius - plan.line / 2)))
                context.strokePath()
            }
        }
        let shadowed = plan.layout == .text
        let sr = shadowRadius(plan)
        if let logo = plan.logo {
            context.saveGState()
            if shadowed { shadow(context, white, sr, plan.unit, op) }
            drawLogo(context, logo, body: cgColor(alpha(ink, op)), led: cgColor(alpha(white ? accent : accentOnLight, op)))
            context.restoreGState()
        }
        for r in plan.runs {
            context.saveGState()
            if shadowed { shadow(context, white, sr, plan.unit, op) }
            let line = fonts.line(r.text, font: spec.font, weight: r.weight, size: r.size,
                                  spacing: letterSpacing(spec.font, weight: r.weight), colour: cgColor(alpha(r.main ? ink : muted, op)))
            context.textMatrix = .identity
            context.translateBy(x: CGFloat(r.x), y: CGFloat(r.baseline))
            context.scaleBy(x: 1, y: -1) // CoreText draws y up
            context.textPosition = .zero
            CTLineDraw(line, context)
            context.restoreGState()
        }
    }

    /// Paint.setShadowLayer(radius, 0, dy, colour): Skia's blur sigma is 0.57735 x radius + 0.5; CoreGraphics' blur is taken as
    /// two sigma. The offset is in the context's base space (y up): the shadow falls down by .03 unit.
    private static func shadow(_ context: CGContext, _ white: Bool, _ radius: Float, _ unit: Float, _ op: Float) {
        let r = white ? radius : radius * 0.8
        let blur = r > 0 ? 2 * (0.57735 * r + 0.5) : 0
        if white {
            context.setShadow(offset: CGSize(width: 0, height: -CGFloat(0.03 * unit)), blur: CGFloat(blur), color: cgColor(alpha(0x7300_0000, op)))
        } else {
            context.setShadow(offset: .zero, blur: CGFloat(blur), color: cgColor(alpha(0x40FF_FFFF, op)))
        }
    }

    /// The SCAMERA glyph in `box`: the launcher icon's silhouette (the body with the viewfinder tab, the lens ring cut out and its
    /// centre) in `body`, the LED in `led`.
    static func drawLogo(_ context: CGContext, _ box: WatermarkRect, body: CGColor, led: CGColor) {
        let s = CGFloat(box.width / logoW), ox = CGFloat(box.left) - 33 * s, oy = CGFloat(box.top) - 36.78 * s
        func rect(_ l: CGFloat, _ t: CGFloat, _ r: CGFloat, _ b: CGFloat) -> CGRect {
            CGRect(x: ox + l * s, y: oy + t * s, width: (r - l) * s, height: (b - t) * s)
        }
        func circle(_ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat) -> CGPath {
            CGPath(ellipseIn: CGRect(x: ox + (cx - r) * s, y: oy + (cy - r) * s, width: 2 * r * s, height: 2 * r * s), transform: nil)
        }
        let main = CGPath(roundedRect: rect(33, 41.4, 75, 70.8), cornerWidth: 8.4 * s, cornerHeight: 8.4 * s, transform: nil)
        let tab = CGPath(roundedRect: rect(38.88, 36.78, 49.8, 44.34), cornerWidth: 2.52 * s, cornerHeight: 2.52 * s, transform: nil)
        let dot = circle(68.28, 47.7, 2.016)
        let shape = main.union(tab)
            .subtracting(circle(54, 56.52, 9.408))
            .subtracting(dot)
            .union(circle(54, 56.52, 5.806))
        context.setFillColor(body)
        context.addPath(shape)
        context.fillPath()
        context.setFillColor(led)
        context.addPath(dot)
        context.fillPath()
    }

    /// Draws the watermark of `spec` / `text` into `rows` and returns the plan; nil when nothing was drawn.
    @discardableResult
    static func drawOn(_ rows: WatermarkRows, _ spec: WatermarkSpec, _ text: WatermarkText, fonts: WatermarkFonts) -> Plan? {
        if !text.any && spec.layout != .frame { return nil }
        var plan = WatermarkPainter.plan(spec, text, width: rows.width, height: rows.height, fonts: fonts)
        if plan.empty { return nil }
        chooseInk(&plan, spec, rows: rows)
        guard let context = rows.context() else { return nil }
        draw(context, plan, spec, fonts: fonts)
        return plan
    }
}

/// A photo as RGBA8 rows top-down (the pipeline's output: R, G, B, then an ignored byte), `bytesPerRow` apart, drawn in place.
struct WatermarkRows {
    let base: UnsafeMutablePointer<UInt8>
    let width: Int, height: Int, bytesPerRow: Int

    /// A CoreGraphics context over the rows (sRGB, the fourth byte skipped) whose user space is the photo's pixels with y down.
    func context() -> CGContext? {
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(data: base, width: width, height: height, bitsPerComponent: 8, bytesPerRow: bytesPerRow,
                                      space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        context.translateBy(x: 0, y: CGFloat(height))
        context.scaleBy(x: 1, y: -1)
        return context
    }
}
