import SwiftUI

/// ToneCurveCompare (ui/settings/custompreferences/ToneCurveCompare.java, P177): the other tone operators next to the own curve -
/// chips «AgX» / «ACES» / «Uchimura» that draw each one (with today's settings) dashed on the graph, remembered for every editor of the
/// curve (a viewer's convenience, not a setting), and the templates (S-curve, straight, AgX, ACES, Uchimura) to load one as an own
/// curve of nine points and edit it. The operators are ToneCurve.agxAt / acesAt / uchimuraAt in the own curve's domain (x = 0.5 + EV
/// / 10 around the 0.18 grey, y the display value) with WhyTone's uniforms (pref_scam_hybrid_why_*, scam_dev hybrid_why_* over them).
enum ToneCurveCompare {
    struct Reference: Identifiable {
        let name: String
        let color: Color
        let samples: [Float]
        var id: String { name }
    }

    static let names = ["AgX", "ACES", "Uchimura"]
    static let colours: [UInt32] = [0x8FD3FF, 0xFFB86B, 0xC3A6FF]
    static let templatePoints = 9
    private static let samplesCount = 96
    private static let key = "scam_ui.tone_curve_compare"

    static func color(_ i: Int) -> Color { AccentPalette.color(colours[i]) }

    static func shown(_ i: Int) -> Bool { (UserDefaults.standard.string(forKey: key) ?? "").contains(names[i]) }

    static func setShown(_ i: Int, _ on: Bool) {
        let list = names.indices.filter { $0 == i ? on : shown($0) }.map { names[$0] }
        UserDefaults.standard.set(list.joined(separator: ","), forKey: key)
    }

    // ───── WhyTone.reference(op) ─────

    /// WhyTone.value: pref_scam_hybrid_why_<key>, scam_dev hybrid_why_<key> over it
    private static func value(_ k: String, _ fallback: Float) -> Float {
        if let dev = ScamDevFile.cachedValues()["hybrid_why_" + k] { return Float(dev) }
        return Float(PrefValue.number(SettingsStore.shared.values["pref_scam_hybrid_why_" + k], Double(fallback)))
    }

    private static func clamp01(_ v: Float) -> Float { max(0, min(1, v)) }
    private static func sceneOf(_ x: Float) -> Float { 0.18 * powf(2, (x - 0.5) * 10) }
    private static func smoothstep(_ e0: Float, _ e1: Float, _ x: Float) -> Float {
        let t = clamp01((x - e0) / (e1 - e0))
        return t * t * (3 - 2 * t)
    }

    static func agxTonescale(_ x: Float, _ slope: Float, _ sp: Float, _ tp: Float, _ px: Float) -> Float {
        let py: Float = 0.5
        if x >= px {
            let sBase = slope * (1 - px) / (1 - py)
            let sDenom = powf(slope * (1 - px), -sp)
            let ss = powf(max((powf(sBase, sp) - 1) * sDenom, 0.00001), -1 / sp)
            let ms = slope * (x - px) / max(ss, 0.00001)
            let fs = ms / powf(1 + powf(max(ms, 0), sp), 1 / sp)
            return ss * fs + py
        }
        let tBase = slope * px / py
        let tDenom = powf(slope * px, -tp)
        let ts = powf(max((powf(tBase, tp) - 1) * tDenom, 0.00001), -1 / tp)
        let mr = slope * (px - x) / max(ts, 0.00001)
        let ft = mr / powf(1 + powf(max(mr, 0), tp), 1 / tp)
        return py - ts * ft
    }

    static func agxAt(_ x: Float, slope: Float, sp: Float, tp: Float, minEv: Float, maxEv: Float, ev: Float) -> Float {
        let range = max(maxEv + max(ev, 0) - minEv, 1)
        let px = max(0.05, min(0.95, -minEv / range))
        let lg = clamp01(((x - 0.5) * 10 + ev - minEv) / range)
        return clamp01(agxTonescale(lg, slope, sp, tp, px))
    }

    static func aces(_ v: Float, toe: Float, preGain: Float, a: Float, d: Float) -> Float {
        let x = max(v, 0) * preGain
        let b = max(toe * 0.75, 0.005)
        let e = max(0.14 - (toe - 0.04) * 0.8, 0.03)
        return x * (a * x + b) / (x * (2.43 * x + d) + e)
    }

    static func uchimura(_ x: Float, _ P: Float, _ a: Float, _ m: Float, _ l: Float, _ c: Float, _ bOffset: Float) -> Float {
        let l0 = (P - m) * l / a
        let s0 = m + l0, s1 = m + a * l0
        let c2 = a * P / (P - s1), cp = -c2 / P
        let w0 = 1 - smoothstep(0, m, x)
        let w2: Float = x >= m + l0 ? 1 : 0
        let w1 = 1 - w0 - w2
        let t = m * powf(max(x / m, 0), c) + bOffset
        let s = P - (P - s1) * expf(cp * (x - s0))
        let lin = m + a * (x - m)
        return t * w0 + lin * w1 + s * w2
    }

    /// The operator `i` (0 AgX, 1 ACES, 2 Uchimura) with today's settings
    static func reference(_ i: Int) -> (Float) -> Float {
        switch i {
        case 1:
            let u = (value("aces_pre_gain", 1), value("aces_a", 2.61), value("aces_d", 0.59), value("aces_toe", 0.05))
            return { x in powf(clamp01(aces(sceneOf(x), toe: u.3, preGain: u.0, a: u.1, d: u.2)), 1 / 2.4) }
        case 2:
            let m = max(value("uchi_m", 0.22), 0.01), a = max(value("uchi_a", 1), 0.05)
            let P = max(value("uchi_p", 1), m + 0.05), l = min(max(value("uchi_l", 0.4), 0), 0.95)
            let pre = value("uchi_pre_gain", 1), post = value("uchi_post_gain", 1), c = value("uchi_c", 1.33), b = value("uchi_b", 0)
            return { x in powf(clamp01(uchimura(sceneOf(x) * pre, P, a, m, l, c, b) * post), 1 / 2.4) }
        default:
            let slope = value("agx_slope", 2.45), sp = value("agx_sp", 1.35), tp = value("agx_tp", 2), minEv = value("agx_min_ev", -8.5)
            let maxEv = value("agx_max_ev", 3.5), ev = value("agx_ev", 0)
            return { x in agxAt(x, slope: slope, sp: sp, tp: tp, minEv: minEv, maxEv: maxEv, ev: ev) }
        }
    }

    static func samples(_ i: Int) -> [Float] {
        let f = reference(i)
        return (0..<samplesCount).map { f(Float($0) / Float(samplesCount - 1)) }
    }

    /// The operators drawn on the graph now
    static func references() -> [Reference] {
        names.indices.filter(shown).map { Reference(name: names[$0], color: color($0), samples: samples($0)) }
    }

    static var templateNames: [String] {
        [Lang.t("S-кривая", "S-curve"), Lang.t("Прямая", "Straight"), "AgX", "ACES", Lang.t("Uchimura (Gran Turismo)", "Uchimura (Gran Turismo)")]
    }

    /// The i-th template as an own curve to edit (ToneCurve.following: never falling, the ends at x 0 and 1)
    static func template(_ i: Int) -> ToneCurve {
        if i == 0 { return ToneCurve.defaults() }
        if i == 1 { return ToneCurve.identity() }
        let f = reference(i - 2)
        var last: Float = 0
        let points = (0..<templatePoints).map { k -> String in
            let x = Float(k) / Float(templatePoints - 1)
            let y = max(last, clamp01(f(x)))
            last = y
            return String(format: "%.4f:%.4f", locale: Locale(identifier: "en_US_POSIX"), x, y)
        }
        return ToneCurve.parse(points.joined(separator: ","))
    }
}

/// «Сравнить:» and a chip per operator: a tap draws it on the graph or takes it off
struct ToneCurveCompareChips: View {
    @Binding var references: [ToneCurveCompare.Reference]
    @State private var on: [Bool] = ToneCurveCompare.names.indices.map(ToneCurveCompare.shown)

    var body: some View {
        HStack(spacing: 4) {
            Text(Lang.t("Сравнить:", "Compare:")).font(.app(13)).foregroundStyle(.secondary).padding(.trailing, 2)
            ForEach(ToneCurveCompare.names.indices, id: \.self) { i in
                Button {
                    on[i].toggle()
                    ToneCurveCompare.setShown(i, on[i])
                    references = ToneCurveCompare.references()
                } label: {
                    Text(ToneCurveCompare.names[i]).font(.app(13)).foregroundStyle(ToneCurveCompare.color(i))
                        .frame(maxWidth: .infinity, minHeight: 34)
                        .background(on[i] ? ToneCurveCompare.color(i).opacity(0.2) : Color.clear, in: Capsule())
                        .overlay(Capsule().strokeBorder(on[i] ? ToneCurveCompare.color(i) : Color.clear, lineWidth: 1))
                }
                .accessibilityAddTraits(on[i] ? .isSelected : [])
            }
        }
        .onAppear { references = ToneCurveCompare.references() }
    }
}
