import Foundation

/// The editor's sliders (gallery/editor/EditState.Param): JSON key, range, default, step - the same table as Editor/EditState.hpp.
enum EditParam: Int, CaseIterable {
    case exposure, contrast, highlights, shadows, whites, blacks, temperature, tint, vibrance, saturation, texture, clarity, dehaze
    case vignette, vignetteMid, grain, grainSize, sharpen, sharpenRadius, lumaNr, colorNr
    case hue0, hue1, hue2, hue3, hue4, hue5, hue6, hue7
    case sat0, sat1, sat2, sat3, sat4, sat5, sat6, sat7
    case lum0, lum1, lum2, lum3, lum4, lum5, lum6, lum7
    case curveShadows, curveDarks, curveLights, curveHighlights, curveSplit1, curveSplit2, curveSplit3
    case gradeShHue, gradeShSat, gradeShLum, gradeMidHue, gradeMidSat, gradeMidLum, gradeHiHue, gradeHiSat, gradeHiLum
    case gradeAllHue, gradeAllSat, gradeAllLum, gradeBlend, gradeBalance
    case mono, bw0, bw1, bw2, bw3, bw4, bw5, bw6, bw7
    case lutAmount, filmAmount, filmGrain, filmExposure, filmContrast, filmDir, filmPrintEv

    struct Info { let key: String; let min: Float; let max: Float; let def: Float }

    private static let table: [Info] = {
        var t: [Info] = [
            Info(key: "exposure", min: -4, max: 4, def: 0), Info(key: "contrast", min: -100, max: 100, def: 0),
            Info(key: "highlights", min: -100, max: 100, def: 0), Info(key: "shadows", min: -100, max: 100, def: 0),
            Info(key: "whites", min: -100, max: 100, def: 0), Info(key: "blacks", min: -100, max: 100, def: 0),
            Info(key: "temp", min: -100, max: 100, def: 0), Info(key: "tint", min: -100, max: 100, def: 0),
            Info(key: "vibrance", min: -100, max: 100, def: 0), Info(key: "saturation", min: -100, max: 100, def: 0),
            Info(key: "texture", min: -100, max: 100, def: 0), Info(key: "clarity", min: -100, max: 100, def: 0),
            Info(key: "dehaze", min: -100, max: 100, def: 0), Info(key: "vignette", min: -100, max: 100, def: 0),
            Info(key: "vignetteMid", min: 0, max: 100, def: 50), Info(key: "grain", min: 0, max: 100, def: 0),
            Info(key: "grainSize", min: 0, max: 100, def: 25), Info(key: "sharpen", min: 0, max: 150, def: 0),
            Info(key: "sharpenRadius", min: 0.5, max: 3, def: 1), Info(key: "lumaNr", min: 0, max: 100, def: 0),
            Info(key: "colorNr", min: 0, max: 100, def: 0),
        ]
        for prefix in ["h", "s", "l"] { for b in 0..<8 { t.append(Info(key: "\(prefix)\(b)", min: -100, max: 100, def: 0)) } }
        t += [Info(key: "cShadows", min: -100, max: 100, def: 0), Info(key: "cDarks", min: -100, max: 100, def: 0),
              Info(key: "cLights", min: -100, max: 100, def: 0), Info(key: "cHighlights", min: -100, max: 100, def: 0),
              Info(key: "cSplit1", min: 5, max: 95, def: 25), Info(key: "cSplit2", min: 5, max: 95, def: 50),
              Info(key: "cSplit3", min: 5, max: 95, def: 75)]
        for w in ["Sh", "Mi", "Hi", "Al"] {
            t += [Info(key: "g\(w)H", min: 0, max: 360, def: 0), Info(key: "g\(w)S", min: 0, max: 100, def: 0),
                  Info(key: "g\(w)L", min: -100, max: 100, def: 0)]
        }
        t += [Info(key: "gBlend", min: 0, max: 100, def: 50), Info(key: "gBalance", min: -100, max: 100, def: 0),
              Info(key: "mono", min: 0, max: 1, def: 0)]
        for b in 0..<8 { t.append(Info(key: "bw\(b)", min: -100, max: 100, def: 0)) }
        t += [Info(key: "lutAmt", min: 0, max: 100, def: 100), Info(key: "filmAmt", min: 0, max: 100, def: 100),
              Info(key: "filmGrain", min: 0, max: 200, def: 0), Info(key: "filmEv", min: -2, max: 2, def: 0),
              Info(key: "filmTone", min: -100, max: 100, def: 0), Info(key: "filmDir", min: 0, max: 100, def: 0),
              Info(key: "filmPrintEv", min: -1, max: 1, def: 0)]
        return t
    }()

    var info: Info { Self.table[rawValue] }
    var key: String { info.key }
    var min: Float { info.min }
    var max: Float { info.max }
    var def: Float { info.def }
    var bipolar: Bool { min < 0 }

    var step: Float {
        switch self {
        case .exposure: return 0.01
        case .sharpenRadius, .filmExposure: return 0.1
        case .filmPrintEv: return 0.05
        default: return 1
        }
    }

    /// Param.snap: clamped to the range, rounded to the step (Math.round: halves up)
    func snap(_ v: Float) -> Float {
        if v.isNaN { return def }
        let s = step
        let r = Float(Self.javaRound(Swift.max(min, Swift.min(max, v)) / s)) * s
        return Swift.max(min, Swift.min(max, r))
    }

    static func javaRound(_ x: Float) -> Int {
        if x.isNaN { return 0 }
        return Int((Double(x) + 0.5).rounded(.down))
    }

    static func byKey(_ key: String) -> EditParam? { allCases.first { $0.key == key } }
    static func hue(_ band: Int) -> EditParam { EditParam(rawValue: EditParam.hue0.rawValue + band)! }
    static func sat(_ band: Int) -> EditParam { EditParam(rawValue: EditParam.sat0.rawValue + band)! }
    static func lum(_ band: Int) -> EditParam { EditParam(rawValue: EditParam.lum0.rawValue + band)! }
    static func bw(_ band: Int) -> EditParam { EditParam(rawValue: EditParam.bw0.rawValue + band)! }
    /// wheel 0 shadows, 1 midtones, 2 highlights, 3 global; part 0 hue, 1 saturation, 2 luminance
    static func grade(_ wheel: Int, _ part: Int) -> EditParam { EditParam(rawValue: EditParam.gradeShHue.rawValue + wheel * 3 + part)! }

    static let lookParams: [EditParam] = [.lutAmount, .filmAmount, .filmGrain, .filmExposure, .filmContrast, .filmDir, .filmPrintEv]
}

/// EditState (gallery/editor/EditState.java): every setting of one edit; the JSON is Android's ({"v":1,...}, defaults left out),
/// the one the renderer (SCAMEditSession) reads.
struct EditState: Equatable {
    static let aspectFree: Float = 0, aspectOriginal: Float = -1, maxStraighten: Float = 45
    static let curveMaster = 0, curveRed = 1, curveGreen = 2, curveBlue = 3

    var values: [Float] = EditParam.allCases.map(\.def)
    var curves: [[Float]?] = [nil, nil, nil, nil]
    var quarterTurns = 0
    var flipH = false
    var straighten: Float = 0
    var cropL: Float = 0, cropT: Float = 0, cropR: Float = 1, cropB: Float = 1
    var aspect: Float = EditState.aspectFree
    // P619 / P633 parts the iPhone does not apply yet (kept so a state round-trips)
    var lut = "", film = "", filmPrint = "", filmFormat = "135"
    var wmRemove = false
    var wm = ""

    subscript(p: EditParam) -> Float {
        get { values[p.rawValue] }
        set { values[p.rawValue] = p.snap(newValue) }
    }

    var anyCurve: Bool { curves.contains { $0 != nil } }
    var mono: Bool { self[.mono] >= 0.5 }

    var isDefaultGeometry: Bool {
        quarterTurns == 0 && !flipH && straighten == 0 && cropL == 0 && cropT == 0 && cropR == 1 && cropB == 1
    }
    var isDefaultTone: Bool {
        EditParam.allCases.allSatisfy { values[$0.rawValue] == $0.def } && !anyCurve && lut.isEmpty && film.isEmpty
    }
    var isDefault: Bool { isDefaultTone && isDefaultGeometry && !wmRemove && wm.isEmpty }

    mutating func resetGeometry() {
        quarterTurns = 0
        flipH = false
        straighten = 0
        cropL = 0; cropT = 0; cropR = 1; cropB = 1
        aspect = Self.aspectFree
    }

    /// copyTone: every setting but the geometry (paste, styles)
    mutating func copyTone(_ o: EditState) {
        values = o.values
        curves = o.curves
        lut = o.lut; film = o.film; filmPrint = o.filmPrint; filmFormat = o.filmFormat
        wm = o.wm
    }

    // ---------------------------------------------------------------- JSON

    var json: String {
        var o: [String: Any] = ["v": 1]
        var p: [String: Any] = [:]
        for q in EditParam.allCases where values[q.rawValue] != q.def { p[q.key] = Double(values[q.rawValue]) }
        if !p.isEmpty { o["p"] = p }
        if anyCurve { o["k"] = curves.map { ($0 ?? []).map { Double($0) } } }
        if !lut.isEmpty || !film.isEmpty || !filmPrint.isEmpty || filmFormat != "135" {
            var l: [String: Any] = [:]
            if !lut.isEmpty { l["l"] = lut }
            if !film.isEmpty { l["f"] = film }
            if !filmPrint.isEmpty { l["p"] = filmPrint }
            if filmFormat != "135" { l["m"] = filmFormat }
            o["lk"] = l
        }
        if wmRemove || !wm.isEmpty {
            var w: [String: Any] = [:]
            if wmRemove { w["rm"] = true }
            if !wm.isEmpty, let d = wm.data(using: .utf8), let spec = try? JSONSerialization.jsonObject(with: d) { w["s"] = spec }
            o["wm"] = w
        }
        if !isDefaultGeometry || aspect != Self.aspectFree {
            var g: [String: Any] = [:]
            if quarterTurns != 0 { g["q"] = quarterTurns }
            if flipH { g["f"] = true }
            if straighten != 0 { g["a"] = Double(straighten) }
            if cropL != 0 || cropT != 0 || cropR != 1 || cropB != 1 { g["c"] = [cropL, cropT, cropR, cropB].map { Double($0) } }
            if aspect != Self.aspectFree { g["r"] = Double(aspect) }
            o["g"] = g
        }
        guard let data = try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys]) else { return "{\"v\":1}" }
        return String(data: data, encoding: .utf8) ?? "{\"v\":1}"
    }

    static func fromJson(_ text: String?) -> EditState? {
        guard let text, let data = text.data(using: .utf8),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any], (o["v"] as? NSNumber)?.intValue == 1 else { return nil }
        var s = EditState()
        if let p = o["p"] as? [String: Any] {
            for (k, v) in p { if let q = EditParam.byKey(k), let n = v as? NSNumber { s[q] = n.floatValue } }
        }
        if let k = o["k"] as? [Any] {
            for (i, a) in k.prefix(4).enumerated() {
                guard let a = a as? [NSNumber], a.count >= 4, a.count % 2 == 0 else { continue }
                s.curves[i] = a.map { Swift.max(0, Swift.min(1, $0.floatValue)) }
            }
        }
        if let l = o["lk"] as? [String: Any] {
            s.lut = l["l"] as? String ?? ""
            s.film = l["f"] as? String ?? ""
            s.filmPrint = l["p"] as? String ?? ""
            let m = l["m"] as? String ?? ""
            s.filmFormat = m.isEmpty ? "135" : m
        }
        if let w = o["wm"] as? [String: Any] {
            s.wmRemove = (w["rm"] as? NSNumber)?.boolValue ?? false
            if let spec = w["s"], let d = try? JSONSerialization.data(withJSONObject: spec), let t = String(data: d, encoding: .utf8) { s.wm = t }
        }
        if let g = o["g"] as? [String: Any] {
            s.quarterTurns = (((g["q"] as? NSNumber)?.intValue ?? 0) % 4 + 4) % 4
            s.flipH = (g["f"] as? NSNumber)?.boolValue ?? false
            s.straighten = Swift.max(-maxStraighten, Swift.min(maxStraighten, (g["a"] as? NSNumber)?.floatValue ?? 0))
            if let c = g["c"] as? [NSNumber], c.count == 4 {
                let v = c.map { Swift.max(0, Swift.min(1, $0.floatValue)) }
                if v[2] - v[0] > 1e-4, v[3] - v[1] > 1e-4 { s.cropL = v[0]; s.cropT = v[1]; s.cropR = v[2]; s.cropB = v[3] }
            }
            let r = (g["r"] as? NSNumber)?.floatValue ?? aspectFree
            s.aspect = r > 0 || r == aspectOriginal ? r : aspectFree
        }
        return s
    }
}

/// EditHistory: the committed states and the current one; a commit after an undo drops the redo branch; at most 100.
struct EditHistory {
    private(set) var states: [EditState]
    private(set) var index = 0
    init(_ initial: EditState) { states = [initial] }
    var canUndo: Bool { index > 0 }
    var canRedo: Bool { index < states.count - 1 }
    var changed: Bool { states[index] != states[0] }
    @discardableResult mutating func commit(_ s: EditState) -> Bool {
        if states[index] == s { return false }
        states.removeSubrange((index + 1)...)
        states.append(s)
        if states.count > 100 { states.removeFirst() }
        index = states.count - 1
        return true
    }
    mutating func undo() -> EditState { if index > 0 { index -= 1 }; return states[index] }
    mutating func redo() -> EditState { if index < states.count - 1 { index += 1 }; return states[index] }
}

/// EditPresets: «Стили» - a style moves each of its sliders from the photo's own value by amount x (style value - default).
enum EditPresets {
    static let styles: [[(EditParam, Float)]] = [
        [(.contrast, 25), (.vibrance, 30), (.clarity, 15), (.shadows, 15), (.highlights, -20)],
        [(.contrast, -20), (.highlights, -25), (.shadows, 25), (.clarity, -15), (.saturation, -10)],
        [(.temperature, 18), (.tint, 4), (.vibrance, 12), (.gradeHiHue, 45), (.gradeHiSat, 20)],
        [(.temperature, -18), (.vibrance, 8), (.gradeShHue, 220), (.gradeShSat, 20)],
        [(.contrast, -10), (.blacks, 25), (.saturation, -15), (.grain, 25), (.grainSize, 35), (.gradeShHue, 200), (.gradeShSat, 18),
         (.gradeHiHue, 40), (.gradeHiSat, 15), (.curveShadows, 20)],
        [(.mono, 1), (.contrast, 35), (.clarity, 20), (.bw0, 20), (.bw5, -30)],
        [(.mono, 1), (.contrast, -10), (.shadows, 20), (.grain, 20)],
        [(.dehaze, 20), (.vibrance, 20), (.texture, 15), (.highlights, -30)],
    ]

    static func name(_ i: Int) -> String {
        switch i {
        case 0: return L("editor.style.vivid")
        case 1: return L("editor.style.soft")
        case 2: return L("editor.style.warm")
        case 3: return L("editor.style.cool")
        case 4: return L("editor.style.film")
        case 5: return L("editor.style.bw_contrast")
        case 6: return L("editor.style.bw_soft")
        default: return L("editor.style.landscape")
        }
    }

    static func apply(_ base: EditState, _ i: Int, _ amount: Float) -> EditState {
        var s = base
        for (p, v) in styles[i] {
            if p == .mono {
                s[p] = amount > 0.5 ? 1 : base[p]
                continue
            }
            s[p] = base[p] + (v - p.def) * amount
        }
        return s
    }
}

/// CropMath (gallery/editor/CropMath.java): the crop's fitting in the straightened photo, the aspect presets, turns and flips.
enum CropMath {
    static let presets: [Float] = [EditState.aspectFree, EditState.aspectOriginal, 1, 4.0 / 5.0, 3.0 / 4.0, 4.0 / 3.0, 16.0 / 9.0, 9.0 / 16.0]
    static let presetRatios = ["", "", "1:1", "4:5", "3:4", "4:3", "16:9", "9:16"]

    static func swapsSides(_ orientation: Int) -> Bool { orientation >= 5 && orientation <= 8 }

    static func baseSize(_ w: Double, _ h: Double, _ quarterTurns: Int) -> (Double, Double) {
        quarterTurns & 1 == 1 ? (h, w) : (w, h)
    }

    static func cropPixels(_ s: EditState, _ w: Double, _ h: Double) -> (Double, Double) {
        let b = baseSize(w, h, s.quarterTurns)
        return (Double(s.cropR - s.cropL) * b.0, Double(s.cropB - s.cropT) * b.1)
    }

    static func radians(_ deg: Double) -> Double { deg * 0.017453292519943295 }

    static func maxScale(_ cx: Double, _ cy: Double, _ a0: Double, _ b0: Double, _ wb: Double, _ hb: Double, _ degrees: Double) -> Double {
        let t = radians(degrees), c = cos(t), s = sin(t)
        let ac = abs(c), as_ = abs(s)
        let k1 = (wb / 2 - abs(c * cx + s * cy)) / Swift.max(1e-12, a0 * ac + b0 * as_)
        let k2 = (hb / 2 - abs(-s * cx + c * cy)) / Swift.max(1e-12, a0 * as_ + b0 * ac)
        return Swift.min(k1, k2)
    }

    static func fits(_ r: [Float], _ wb: Double, _ hb: Double, _ degrees: Double) -> Bool {
        let l = Double(r[0]), t = Double(r[1]), rr = Double(r[2]), b = Double(r[3])
        let cx = ((l + rr) / 2 - 0.5) * wb, cy = ((t + b) / 2 - 0.5) * hb
        let a0 = (rr - l) / 2 * wb, b0 = (b - t) / 2 * hb
        return l >= -1e-6 && t >= -1e-6 && rr <= 1 + 1e-6 && b <= 1 + 1e-6 && maxScale(cx, cy, a0, b0, wb, hb, degrees) >= 1 - 1e-6
    }

    static func clampRect(_ l: Float, _ t: Float, _ r: Float, _ b: Float) -> [Float] {
        [Swift.max(0, l), Swift.max(0, t), Swift.min(1, r), Swift.min(1, b)]
    }

    static func inset(_ c: [Float], _ wb: Double, _ hb: Double, _ degrees: Double) -> [Float] {
        let (l, t, r, b) = (c[0], c[1], c[2], c[3])
        var cx = (Double(l + r) / 2 - 0.5) * wb, cy = (Double(t + b) / 2 - 0.5) * hb
        let a0 = Double(r - l) / 2 * wb, b0 = Double(b - t) / 2 * hb
        var k = maxScale(cx, cy, a0, b0, wb, hb, degrees)
        if k >= 1 { return clampRect(l, t, r, b) }
        let k0 = maxScale(0, 0, a0, b0, wb, hb, degrees)
        if k < 0.5 * Swift.min(1, k0) { cx = 0; cy = 0; k = k0 }
        k = Swift.min(1, Swift.max(1e-4, k)) * (1 - 1e-6)
        let hw = a0 * k / wb, hh = b0 * k / hb, ux = cx / wb + 0.5, uy = cy / hb + 0.5
        return clampRect(Float(ux - hw), Float(uy - hh), Float(ux + hw), Float(uy + hh))
    }

    static func ratioOf(_ aspect: Float, _ wb: Double, _ hb: Double) -> Double {
        if aspect == EditState.aspectOriginal { return wb / hb }
        return aspect > 0 ? Double(aspect) : 0
    }

    static func presetIndex(_ aspect: Float) -> Int { presets.firstIndex { abs($0 - aspect) < 1e-4 } ?? -1 }

    static func applyAspect(_ s: inout EditState, _ w: Double, _ h: Double) {
        let (wb, hb) = baseSize(w, h, s.quarterTurns)
        let ratio = ratioOf(s.aspect, wb, hb)
        if ratio <= 0 { return }
        var a0: Double, b0: Double
        if wb / hb > ratio { b0 = hb / 2; a0 = b0 * ratio } else { a0 = wb / 2; b0 = a0 / ratio }
        var cx = (Double(s.cropL + s.cropR) / 2 - 0.5) * wb, cy = (Double(s.cropT + s.cropB) / 2 - 0.5) * hb
        var k = Swift.min(1, maxScale(cx, cy, a0, b0, wb, hb, Double(s.straighten)))
        let k0 = Swift.min(1, maxScale(0, 0, a0, b0, wb, hb, Double(s.straighten)))
        if k < 0.8 * k0 { cx = 0; cy = 0; k = k0 }
        k = Swift.max(1e-4, k) * (1 - 1e-6)
        let hw = a0 * k / wb, hh = b0 * k / hb, ux = cx / wb + 0.5, uy = cy / hb + 0.5
        let c = clampRect(Float(ux - hw), Float(uy - hh), Float(ux + hw), Float(uy + hh))
        s.cropL = c[0]; s.cropT = c[1]; s.cropR = c[2]; s.cropB = c[3]
    }

    static func rotateClockwise(_ s: inout EditState) {
        let (l, t, r, b) = (s.cropL, s.cropT, s.cropR, s.cropB)
        s.cropL = 1 - b; s.cropT = l; s.cropR = 1 - t; s.cropB = r
        s.quarterTurns = (s.quarterTurns + (s.flipH ? 3 : 1)) % 4
        if s.aspect > 0 { s.aspect = 1 / s.aspect }
    }

    static func flipHorizontal(_ s: inout EditState) {
        let (l, r) = (s.cropL, s.cropR)
        s.cropL = 1 - r
        s.cropR = 1 - l
        s.flipH.toggle()
        s.straighten = -s.straighten
    }

    static func constrain(_ from: [Float], _ to: [Float], _ wb: Double, _ hb: Double, _ degrees: Double) -> [Float] {
        if fits(to, wb, hb, degrees) { return to }
        if !fits(from, wb, hb, degrees) { return from }
        func lerp(_ a: Float, _ b: Float, _ t: Double) -> Double { Double(a) + (Double(b) - Double(a)) * t }
        var lo = 0.0, hi = 1.0
        for _ in 0..<24 {
            let m = (lo + hi) / 2
            let c = (0..<4).map { Float(lerp(from[$0], to[$0], m)) }
            if fits(c, wb, hb, degrees) { lo = m } else { hi = m }
        }
        return (0..<4).map { Float(lerp(from[$0], to[$0], lo)) }
    }

    /// EditorView.canvas: the view rect the picture takes (the crop tab: the whole turned frame), fitted with the margin.
    static func canvas(_ w: Double, _ h: Double, _ margin: Double, _ s: EditState, _ ow: Double, _ oh: Double, _ crop: Bool) -> CGRect {
        let b = baseSize(ow, oh, s.quarterTurns)
        let aw = crop ? b.0 : b.0 * Double(s.cropR - s.cropL), ah = crop ? b.1 : b.1 * Double(s.cropB - s.cropT)
        let aw2 = w - 2 * margin, ah2 = h - 2 * margin
        let k = Swift.min(aw2 / Swift.max(1e-6, aw), ah2 / Swift.max(1e-6, ah))
        let fw = aw * k, fh = ah * k
        return CGRect(x: (w - fw) / 2, y: (h - fh) / 2, width: fw, height: fh)
    }
}
