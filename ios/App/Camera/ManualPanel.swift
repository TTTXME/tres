import SwiftUI

/// «Про» as Android's inline ExpandingManualPanel (manual_palette, SIMPLE_UI §3 / P583): the ruler card 6 pt above the chips card,
/// both rgba(20, 20, 22, .9) with 22 pt corners, over the bottom bar. The chips card: six equal chips 2 pt apart - ISO, Выдержка,
/// Яркость, Фокус, Цвет and «Всё на авто» - each the parameter's glyph (18 pt), its value (13 pt heavy, shrunk rather than cut)
/// and its name (9.5 pt). A tap opens that parameter's ruler (the chip gets a #2C2C2E fill and a 1.5 pt accent edge), a second tap
/// closes it. A value set by hand keeps the accent on its number; in auto the chip shows the camera's metered value. EV with ISO
/// and shutter both manual is locked (40 %, «—»; a tap says why). The panel grows out of «Про» on a damped spring with its tiles
/// rising one after another, and goes back into it on the exit curve; it is shown only while the quick settings are down and no
/// other panel takes its place (P630g).
struct ManualPanel: View {
    let camera: CameraModel
    @Binding var expanded: Bool
    var shown: Bool
    @State private var manual = ManualSettings.load()
    @State private var selected: ManualParam?
    @State private var risen = false

    var body: some View {
        let hud = CameraHud.shared.sample
        VStack(spacing: 6) {
            if let p = selected, expanded {
                let model = RulerModel(param: p, lens: camera.lens)
                ProRuler(model: model, index: model.index(of: p.isManual(manual) ? p.value(manual) : (p.metered(hud) ?? p.value(manual))),
                         auto: !p.isManual(manual),
                         onIndex: { set(p, model.stops[$0]) },
                         onAuto: { setAuto(p) })
                    .padding(5)
                    .background(CameraStyle.proCard, in: RoundedRectangle(cornerRadius: 22))
                    .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
            HStack(spacing: 2) {
                ForEach(Array(ManualParam.allCases.enumerated()), id: \.element) { i, p in
                    chip(p, hud: hud).riseIn(risen, delay: 0.04 + 0.018 * Double(i))
                }
                allAuto.riseIn(risen, delay: 0.04 + 0.018 * 5)
            }
            .padding(4)
            .background(CameraStyle.proCard, in: RoundedRectangle(cornerRadius: 22))
        }
        .scaleEffect(expanded ? 1 : 0.3, anchor: .bottomTrailing)
        .opacity(expanded && shown ? 1 : 0)
        .offset(y: shown ? 0 : 16)
        .allowsHitTesting(expanded && shown)
        .animation(expanded ? CameraMotion.open : CameraMotion.exit, value: expanded)
        .animation(shown ? CameraMotion.sheet : .easeOut(duration: 0.18), value: shown)
        .onChange(of: expanded) {
            if expanded {
                manual = ManualSettings.load()
                risen = false
                withAnimation(.timingCurve(0.05, 0.7, 0.1, 1, duration: 0.32)) { risen = true }
            } else {
                selected = nil   // collapsing closes the ruler (the owner's rule)
            }
        }
        .accessibilityHidden(!expanded)
    }

    /// Any applied parameter is manual: «Про» carries the accent dot
    static func anyManual(_ m: ManualSettings) -> Bool { m.isoOn || m.shutterOn || m.focusOn || m.wbOn || abs(m.bias) > 1e-3 }

    private func chip(_ p: ManualParam, hud: CameraHudSample?) -> some View {
        let locked = p == .ev && manual.isoOn && manual.shutterOn
        let isManual = p.isManual(manual)
        let chosen = selected == p
        let value = locked ? "—" : isManual ? p.text(p.value(manual)) : (p.metered(hud).map { p.text($0) } ?? L("pro.auto_short"))
        return VStack(spacing: 1) {
            Image(p.icon).renderingMode(.template).resizable().frame(width: 18, height: 18)
                .foregroundStyle(chosen ? CameraStyle.accent : CameraStyle.text)
            Text(value).font(.system(size: 13, weight: .heavy)).monospacedDigit().lineLimit(1).minimumScaleFactor(9 / 13)
                .foregroundStyle(chosen || isManual ? CameraStyle.accent : CameraStyle.text)
                .frame(height: 15.6)
            Text(p.name).font(.system(size: 9.5)).lineLimit(1).minimumScaleFactor(7 / 9.5)
                .foregroundStyle(chosen ? CameraStyle.accent.opacity(0.75) : Color.white.opacity(0.75))
                .frame(height: 11.4)
        }
        .padding(.top, 6).padding(.bottom, 5).padding(.horizontal, 2)
        .frame(maxWidth: .infinity, minHeight: 66)
        .background(RoundedRectangle(cornerRadius: 18).fill(chosen ? CameraStyle.row : Color.clear))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(chosen ? CameraStyle.accent : Color.clear, lineWidth: 1.5))
        .opacity(locked ? 0.4 : 1)
        .contentShape(Rectangle())
        .onTapGesture {
            if locked {
                MessageCenter.shared.info(L("pro.ev_locked"))
                return
            }
            Haptics.tick()
            withAnimation(CameraMotion.glide) { selected = chosen ? nil : p }
        }
        .accessibilityLabel(locked ? String(format: L("pro.a11y_locked"), p.name)
                            : isManual ? p.name + ": " + value : String(format: L("pro.a11y_auto"), p.name, value))
        .accessibilityAddTraits(chosen ? [.isButton, .isSelected] : .isButton)
    }

    private var allAuto: some View {
        let any = Self.anyManual(manual)
        return VStack(spacing: 1) {
            Image("pro_ic_auto").renderingMode(.template).resizable().frame(width: 18, height: 18)
                .foregroundStyle(any ? CameraStyle.accent : CameraStyle.text)
            Text(L("pro.all_value")).font(.system(size: 13, weight: .heavy)).lineLimit(1).minimumScaleFactor(9 / 13)
                .foregroundStyle(CameraStyle.text).frame(height: 15.6)
            Text(L("pro.all_name")).font(.system(size: 9.5)).lineLimit(1).minimumScaleFactor(7 / 9.5)
                .foregroundStyle(Color.white.opacity(0.75)).frame(height: 11.4)
        }
        .padding(.top, 6).padding(.bottom, 5).padding(.horizontal, 2)
        .frame(maxWidth: .infinity, minHeight: 66)
        .opacity(any ? 1 : 0.4)
        .contentShape(Rectangle())
        .onTapGesture {
            guard any else { return }
            Haptics.tick()
            manual = ManualSettings()
            apply()
            withAnimation(CameraMotion.glide) { selected = nil }
            MessageCenter.shared.info(L("pro.all_auto_done"))
        }
        .accessibilityLabel(L("pro.all_auto"))
        .accessibilityAddTraits(.isButton)
    }

    private func set(_ p: ManualParam, _ v: Double) {
        p.set(&manual, v)
        apply()
    }

    private func setAuto(_ p: ManualParam) {
        p.setAuto(&manual)
        apply()
        MessageCenter.shared.info(String(format: L("pro.toast_auto"), p.name))
    }

    private func apply() {
        manual.save()
        let m = manual
        Task { await camera.applyManual(m) }
    }
}

/// The tiles rise into the opening panel one after another (P509: 12 pt up, 18 ms apart)
private struct RiseIn: ViewModifier {
    let risen: Bool
    let delay: Double
    func body(content: Content) -> some View {
        content.opacity(risen ? 1 : 0).offset(y: risen ? 0 : 12)
            .animation(.timingCurve(0.05, 0.7, 0.1, 1, duration: 0.32).delay(delay), value: risen)
    }
}

private extension View {
    func riseIn(_ risen: Bool, delay: Double) -> some View { modifier(RiseIn(risen: risen, delay: delay)) }
}

/// The five parameters of the panel over ManualSettings (the iPhone's values: ISO, the shutter in seconds, EV, the lens position
/// 0..1 - the iPhone gives no focus distance - and white balance in kelvin)
enum ManualParam: Int, CaseIterable, Hashable {
    case iso, shutter, ev, focus, wb

    var name: String {
        switch self {
        case .iso: return "ISO"
        case .shutter: return L("pro.name_shutter")
        case .ev: return L("pro.name_ev")
        case .focus: return L("pro.name_focus")
        case .wb: return L("pro.name_wb")
        }
    }

    var icon: String {
        switch self {
        case .iso: return "pro_ic_iso"
        case .shutter: return "pro_ic_shutter"
        case .ev: return "pro_ic_ev"
        case .focus: return "pro_ic_focus"
        case .wb: return "pro_ic_wb"
        }
    }

    func isManual(_ m: ManualSettings) -> Bool {
        switch self {
        case .iso: return m.isoOn
        case .shutter: return m.shutterOn
        case .ev: return abs(m.bias) > 1e-3
        case .focus: return m.focusOn
        case .wb: return m.wbOn
        }
    }

    /// The value in the ruler's units (the shutter in seconds)
    func value(_ m: ManualSettings) -> Double {
        switch self {
        case .iso: return m.iso
        case .shutter: return pow(2, m.shutter)
        case .ev: return m.bias
        case .focus: return m.focus
        case .wb: return m.kelvin
        }
    }

    func set(_ m: inout ManualSettings, _ v: Double) {
        switch self {
        case .iso: m.isoOn = true; m.iso = v
        case .shutter: m.shutterOn = true; m.shutter = log2(max(v, 1e-6))
        case .ev: m.bias = v
        case .focus: m.focusOn = true; m.focus = v
        case .wb: m.wbOn = true; m.kelvin = v
        }
    }

    func setAuto(_ m: inout ManualSettings) {
        switch self {
        case .iso: m.isoOn = false
        case .shutter: m.shutterOn = false
        case .ev: m.bias = 0
        case .focus: m.focusOn = false
        case .wb: m.wbOn = false
        }
    }

    /// The camera's own value now (CameraHud's lens reading), nil when unknown
    func metered(_ s: CameraHudSample?) -> Double? {
        guard let s else { return nil }
        switch self {
        case .iso: return s.iso > 0 ? Double(s.iso) : nil
        case .shutter: return s.exposure > 0 ? s.exposure : nil
        case .ev: return Double(s.bias)
        case .focus: return Double(s.lensPosition)
        case .wb: return s.kelvin > 0 ? Double(s.kelvin) : nil
        }
    }

    /// ManualFormat: «2560», «1/8000» / «1,3 s», «+1 1/3», the lens position «0,62», «5200K»
    func text(_ v: Double) -> String {
        switch self {
        case .iso: return String(Int(v.rounded()))
        case .shutter: return v < 0.5 ? "1/\(Int((1 / max(v, 1e-6)).rounded()))" : Self.oneDecimal(v) + " " + L("pro.unit_s")
        case .ev: return Self.ev(v)
        case .focus: return v >= 0.995 ? "∞" : CameraStyle.decimal(String(format: "%.2f", v))
        case .wb: return "\(Int(v.rounded()))K"
        }
    }

    /// The ruler's short label (the shutter without its unit)
    func short(_ v: Double) -> String {
        if self == .shutter { return v < 0.5 ? "1/\(Int((1 / max(v, 1e-6)).rounded()))" : Self.oneDecimal(v) }
        return text(v)
    }

    static func oneDecimal(_ v: Double) -> String {
        let tenths = Int((v * 10).rounded())
        if tenths % 10 == 0 { return String(tenths / 10) }
        return CameraStyle.decimal("\(tenths / 10).\(tenths % 10)")
    }

    /// ManualFormat.ev: «0», else a sign (+ or U+2212) with the whole part and a fraction in thirds (halves, quarters, sixths)
    static func ev(_ ev: Double) -> String {
        if abs(ev) < 1e-3 { return "0" }
        let sign = ev > 0 ? "+" : "\u{2212}"
        let a = abs(ev)
        for den in [1, 3, 2, 4, 6] {
            let n = Int((a * Double(den)).rounded())
            if abs(a * Double(den) - Double(n)) < 0.02 {
                let whole = n / den, rest = n % den
                if rest == 0 { return sign + String(whole) }
                var g = rest, b = den
                while b != 0 { (g, b) = (b, g % b) }
                let frac = "\(rest / g)/\(den / g)"
                return whole == 0 ? sign + frac : sign + "\(whole) " + frac
            }
        }
        return sign + String(format: "%.1f", a).replacingOccurrences(of: ".", with: ",")
    }
}

/// The ruler's stops of a parameter on the open lens (ManualStops: the photographic third stops within the lens's range; EV in
/// thirds of the lens's ±4; the lens position in twentieths; white balance 2000-10000 K by 100)
struct RulerModel {
    let param: ManualParam
    let stops: [Double]

    init(param: ManualParam, lens: CaptureService.State) {
        self.param = param
        switch param {
        case .iso: stops = Self.iso(Double(lens.isoRange.lowerBound), Double(lens.isoRange.upperBound))
        case .shutter: stops = Self.shutter(lens.durationRange.lowerBound, lens.durationRange.upperBound)
        case .ev:
            let lo = max(-4, Double(lens.evRange.lowerBound)), hi = min(4, Double(lens.evRange.upperBound))
            stops = stride(from: (lo * 3).rounded(.up), through: (hi * 3).rounded(.down), by: 1).map { $0 / 3 }
        case .focus: stops = (0...20).map { Double($0) / 20 }
        case .wb: stops = stride(from: 2000.0, through: 10000, by: 100).map { $0 }
        }
    }

    static func iso(_ lo: Double, _ hi: Double) -> [Double] {
        var v = Set([lo.rounded(), hi.rounded()])
        for octave in -4..<16 {
            for base in [100.0, 125, 160] {
                let x = (base * pow(2, Double(octave))).rounded()
                if x >= lo && x <= hi { v.insert(x) }
            }
        }
        return v.sorted()
    }

    static func shutter(_ lo: Double, _ hi: Double) -> [Double] {
        var v = Set([lo, hi])
        let den: [Double] = [32000, 25000, 20000, 16000, 12800, 10000, 8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640,
                             500, 400, 320, 250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3.2, 2.5, 2]
        for d in den where 1 / d >= lo && 1 / d <= hi { v.insert(1 / d) }
        for s in [0.6, 0.8, 1, 1.3, 1.6, 2, 2.5, 3.2, 4, 5, 6, 8, 10, 13, 16, 20, 25, 32] where s >= lo && s <= hi { v.insert(s) }
        return v.sorted()
    }

    /// LinearScaleView.isMajor: ISO octaves, the whole shutter stops, whole EV, every 3rd focus stop, every 1000 K
    func major(_ j: Int) -> Bool {
        let x = stops[j]
        switch param {
        case .iso:
            let o = log2(x / 100)
            return abs(o - o.rounded()) < 0.001
        case .shutter:
            for d in [32000.0, 16000, 8000, 4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4, 2] where abs(x - 1 / d) <= 1e-9 * 2 { return true }
            let o = log2(x)
            return x >= 1 && abs(o - o.rounded()) < 0.001
        case .ev: return abs(x - x.rounded()) < 1e-3
        case .focus: return j % 3 == 0
        case .wb: return Int(x.rounded()) % 1000 == 0
        }
    }

    /// The stop nearest the value (in log for ISO and the shutter); the metered one while in auto
    func index(of value: Double) -> Int {
        guard !stops.isEmpty else { return 0 }
        let v = value
        let logScale = param == .iso || param == .shutter
        var best = 0, bestD = Double.infinity
        for (i, s) in stops.enumerated() {
            let d = logScale ? abs(log(max(s, 1e-9)) - log(max(v, 1e-9))) : abs(s - v)
            if d < bestD { bestD = d; best = i }
        }
        return best
    }
}

/// LinearScaleView (SIMPLE_UI §3, the concept's one-row ruler): the «Авто» button (64 x 50, 16 pt corners: the accent's dark tone
/// with the accent text while the parameter is in auto, #2C2C2E with white text otherwise) and the scale on the right, 54 pt high:
/// stops 16 pt apart, major ticks 14 pt (white 70 %) with their labels on one row at the top, minor 8 pt (35 %), the fixed accent
/// needle in the centre, the current value above it (the accent; the text colour in auto), the ends fading over 40 pt. A drag moves
/// the scale and commits every stop it crosses (a tick each); a release flings on and snaps to a stop.
struct ProRuler: View {
    let model: RulerModel
    let index: Int
    let auto: Bool
    var onIndex: (Int) -> Void
    var onAuto: () -> Void
    @State private var dragStart: Int?
    @State private var residual: CGFloat = 0
    static let step: CGFloat = 16, fade: CGFloat = 40

    var body: some View {
        HStack(spacing: 10) {
            Button(action: { Haptics.tick(); onAuto() }) {
                Text(L("pro.auto_short")).font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(auto ? CameraStyle.accent : CameraStyle.text)
                    .frame(minWidth: 64, minHeight: 50)
                    .padding(.horizontal, 4)
                    .background(auto ? CameraStyle.accentDark : CameraStyle.row, in: RoundedRectangle(cornerRadius: 16))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L("pro.auto_short") + " " + model.param.name)
            GeometryReader { g in
                let w = g.size.width, h = g.size.height, cx = w / 2
                ZStack(alignment: .topLeading) {
                    Canvas { context, size in
                        let base = size.height - 4
                        let shift = cx - CGFloat(index) * Self.step + residual
                        let from = max(0, index - Int((cx / Self.step).rounded(.up)) - 2)
                        let to = min(model.stops.count - 1, index + Int(((w - cx) / Self.step).rounded(.up)) + 2)
                        guard from <= to else { return }
                        for j in from...to {
                            let x = shift + CGFloat(j) * Self.step
                            let major = model.major(j)
                            var p = Path()
                            p.move(to: CGPoint(x: x, y: base))
                            p.addLine(to: CGPoint(x: x, y: base - (major ? 14 : 8)))
                            context.stroke(p, with: .color(.white.opacity(major ? 0.7 : 0.35)), lineWidth: 2)
                            if major, abs(x - cx) > 34 {
                                context.draw(Text(model.param.short(model.stops[j])).font(.system(size: 10)).foregroundColor(.white.opacity(0.6)),
                                             at: CGPoint(x: x, y: 6), anchor: .top)
                            }
                        }
                    }
                    LinearGradient(colors: [CameraStyle.proCard, .clear], startPoint: .leading, endPoint: .trailing)
                        .frame(width: min(Self.fade, w / 3), height: h)
                    LinearGradient(colors: [.clear, CameraStyle.proCard], startPoint: .leading, endPoint: .trailing)
                        .frame(width: min(Self.fade, w / 3), height: h)
                        .offset(x: w - min(Self.fade, w / 3))
                    Rectangle().fill(CameraStyle.accent).frame(width: 2, height: 25).offset(x: cx - 1, y: h - 26)
                    if index < model.stops.count {
                        Text(model.param.short(model.stops[index]))
                            .font(.system(size: 16, weight: .bold)).monospacedDigit()
                            .foregroundStyle(auto ? CameraStyle.text : CameraStyle.accent)
                            .fixedSize()
                            .frame(width: w, alignment: .center)
                            .offset(y: 4)
                    }
                }
                .frame(width: w, height: h)
                .clipped()
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { d in
                        let start = dragStart ?? index
                        if dragStart == nil { dragStart = index }
                        let virtual = Double(start) - Double(d.translation.width / Self.step)
                        let clamped = min(max(virtual, 0), Double(model.stops.count - 1))
                        let next = Int(clamped.rounded())
                        residual = CGFloat(Double(next) - clamped) * Self.step
                        if next != index || auto && abs(d.translation.width) > 2 {
                            Haptics.tick()
                            onIndex(next)
                        }
                    }
                    .onEnded { d in
                        let start = dragStart ?? index
                        dragStart = nil
                        // the fling: the predicted end, at most 24 stops on, then the snap
                        let flung = Double(d.predictedEndTranslation.width - d.translation.width) / Double(Self.step)
                        let virtual = Double(start) - Double(d.translation.width / Self.step) - max(-24, min(24, flung))
                        let target = Int(min(max(virtual, 0), Double(model.stops.count - 1)).rounded())
                        withAnimation(.easeOut(duration: 0.3)) {
                            residual = 0
                            if target != index { onIndex(target) }
                        }
                        if target != index { Haptics.tick() }
                    })
            }
            .frame(height: 54)
            .accessibilityElement()
            .accessibilityLabel(model.param.name)
            .accessibilityValue(index < model.stops.count ? model.param.text(model.stops[index]) : "")
            .accessibilityAdjustableAction { direction in
                switch direction {
                case .increment: if index + 1 < model.stops.count { onIndex(index + 1) }
                case .decrement: if index > 0 { onIndex(index - 1) }
                @unknown default: break
                }
            }
        }
    }
}
