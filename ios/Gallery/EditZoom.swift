import Foundation

/// P367 EditZoom (gallery/editor/EditZoom.java): the editor's view zoom - the picture pinched / double-tapped from the fit up to
/// 400 % of the original's pixels and panned; the zoom is a view of the displayed crop in its own frame (the saved copy does not
/// change). `scale` view px per original px (1 = 100 %), the displayed crop's top-left corner (`left`, `top`) in view px. On an axis
/// where the zoomed picture is narrower than the view it is centred, where it is wider it never leaves a gap at its edges. Past the
/// preview decode's resolution the visible part comes from the original (EditorModel's full-resolution window).
struct EditZoom: Equatable {
    /// The largest magnification: 400 % of the original's pixels (a fit already larger is not zoomed further).
    static let maxFull = 4.0
    /// A scale this close above the fit counts as the fit; the model keeps it, so a slow pinch adds up.
    static let fitEps = 1e-3
    /// Full resolution is wanted once a preview pixel covers more than this many screen pixels.
    static let previewLimit = 1.02

    private(set) var viewW = 1.0, viewH = 1.0
    private(set) var fitL = 0.0, fitT = 0.0, fitW = 1.0, fitH = 1.0
    /// The displayed crop in the original's pixels.
    private(set) var cropW = 1.0, cropH = 1.0
    /// The copy's size (EditExporter.outputSize; 0: unknown) and whether it is the crop's own pixels.
    private var copyW = 0, copyH = 0
    private var copyGrid = false
    var scale = 1.0, left = 0.0, top = 0.0

    /// A new frame: the view, the fit rect of the crop in it, the crop in original pixels. With `keep` a zoomed view keeps its scale
    /// and the crop point at the view's centre (a resized view); else it goes back to the fit.
    mutating func frame(viewW vw: Double, viewH vh: Double, fitL fl: Double, fitT ft: Double, fitW fw: Double, fitH fh: Double,
                        cropW cw: Double, cropH ch: Double, keep: Bool) {
        let was = keep && isZoomed
        var cu = 0.5, cv = 0.5
        let s = scale
        if was {
            cu = (viewW / 2 - left) / displayedW
            cv = (viewH / 2 - top) / displayedH
        }
        viewW = max(1, vw)
        viewH = max(1, vh)
        fitL = fl
        fitT = ft
        fitW = max(1e-6, fw)
        fitH = max(1e-6, fh)
        cropW = max(1e-6, cw)
        cropH = max(1e-6, ch)
        if was {
            scale = clampScale(s)
            left = viewW / 2 - cu * displayedW
            top = viewH / 2 - cv * displayedH
            clamp()
        } else {
            reset()
        }
    }

    /// The copy «Сохранить копию» writes of this crop: when that is the crop's own pixels, «100 %» is the copy's 1:1.
    mutating func copySize(_ w: Int, _ h: Int) {
        copyW = w
        copyH = h
        copyGrid = w > 0 && h > 0 && abs(Double(w) - cropW) <= 1 && abs(Double(h) - cropH) <= 1
    }

    /// The scale of «100 %»: the copy's 1:1 when it has the crop's pixels, else one original px a screen px.
    var hundred: Double { copyGrid ? Double(copyW) / cropW : 1 }
    var fitScale: Double { fitW / cropW }
    var maxScale: Double { max(fitScale, Self.maxFull) }
    var isZoomed: Bool { scale > fitScale * (1 + Self.fitEps) }
    var displayedW: Double { cropW * scale }
    var displayedH: Double { cropH * scale }
    /// The zoom in % of the original's pixels.
    var percent: Double { scale * 100 }

    func clampScale(_ s: Double) -> Double { min(maxScale, max(fitScale, s)) }

    mutating func reset() {
        scale = fitScale
        left = fitL
        top = fitT
    }

    /// Pinch: the scale times `factor` (in range), the crop point under (fx, fy) stays there; false when nothing moved.
    @discardableResult
    mutating func zoomAbout(_ factor: Double, _ fx: Double, _ fy: Double) -> Bool {
        guard factor > 0, factor.isFinite else { return false }
        let s2 = clampScale(scale * factor)
        if s2 == scale { return false }
        left = fx - (fx - left) * s2 / scale
        top = fy - (fy - top) * s2 / scale
        scale = s2
        clamp()
        return true
    }

    /// One finger (or the fingers' centre) moved by dx, dy view px.
    mutating func panBy(_ dx: Double, _ dy: Double) {
        guard isZoomed else { return }
        left += dx
        top += dy
        clamp()
    }

    mutating func zoomTo(_ s: Double, _ cu: Double, _ cv: Double) {
        scale = clampScale(s)
        left = viewW / 2 - Self.clamp01(cu) * displayedW
        top = viewH / 2 - Self.clamp01(cv) * displayedH
        clamp()
    }

    /// Scale `s`, the crop point under (fx, fy) moved to the view's centre (clamped).
    mutating func centreOn(_ s: Double, _ fx: Double, _ fy: Double) {
        zoomTo(s, Self.clamp01((fx - left) / displayedW), Self.clamp01((fy - top) / displayedH))
    }

    /// Where a double tap at (fx, fy) goes: zoomed -> the fit; at the fit -> 100 % (2x the fit for a small photo) there.
    func toggleTarget(_ fx: Double, _ fy: Double) -> EditZoom {
        var t = self
        if isZoomed {
            t.reset()
        } else {
            let h = hundred
            let target = h > fitScale * 1.05 ? h : min(maxScale, fitScale * 2)
            if target > fitScale * (1 + Self.fitEps) { t.centreOn(target, fx, fy) } else { t.reset() }
        }
        return t
    }

    /// Centred where the picture is narrower than the view, no gap at the edges where it is wider; at (or under) the fit exactly
    /// the fit.
    mutating func clamp() {
        if scale <= fitScale * (1 + 1e-12) {
            reset()
            return
        }
        let dw = displayedW, dh = displayedH
        left = dw <= viewW ? (viewW - dw) / 2 : min(0, max(viewW - dw, left))
        top = dh <= viewH ? (viewH - dh) / 2 : min(0, max(viewH - dh, top))
    }

    /// Where the displayed crop goes on the screen (left, top, dw, dh): at «100 %» of a copy with the crop's pixels it is exactly the
    /// copy's W x H on whole view px (every screen pixel an output pixel of the copy).
    var placement: (Double, Double, Double, Double) {
        guard copyGrid, abs(scale - hundred) <= 1e-9 * scale else { return (left, top, displayedW, displayedH) }
        return (Self.snap(left, viewW, displayedW, copyW), Self.snap(top, viewH, displayedH, copyH), Double(copyW), Double(copyH))
    }

    private static func snap(_ pos: Double, _ view: Double, _ model: Double, _ copy: Int) -> Double {
        if Double(copy) <= view { return ((view - Double(copy)) / 2).rounded(.toNearestOrEven) }
        let travel = view - model, f = travel < -1e-9 ? clamp01(pos / travel) : 0
        return (f * (view - Double(copy))).rounded(.toNearestOrEven)
    }

    /// Whether the screen shows more than the preview decode has: the preview has fewer pixels and one covers > ~1 screen px.
    static func fullResWanted(_ scale: Double, _ previewPerOriginal: Double) -> Bool {
        previewPerOriginal < 0.98 && scale / max(1e-9, previewPerOriginal) > previewLimit
    }

    static func clamp01(_ v: Double) -> Double { min(1, max(0, v)) }
}
