import SwiftUI

/// OldstyleStyle (P630c): the mode's style - the palette's «Тон» / «Цвет» and twelve advanced parameters, each -100..100 (0 none)
/// or, for the discrete ones (aura, halo, grain), 0..3 (off, low, medium, high); stored as pref_oldstyle_style_<key>, which the
/// photo's OldstyleStyleNode reads at the press (Engine/post/oldstyle/OldstyleAe.hpp style()).
enum OldstyleParam: String, CaseIterable {
    case tone, color, exposure, aura, halo, grain, warmth, hue, highlights, shadows, warmBoost = "warm_boost", coolBoost = "cool_boost",
         sharpness, vignette

    static let advanced: [OldstyleParam] = [.exposure, .aura, .halo, .grain, .warmth, .hue, .highlights, .shadows, .warmBoost, .coolBoost,
                                            .sharpness, .vignette]
    var discrete: Bool { self == .aura || self == .halo || self == .grain }
    var minValue: Int { discrete ? 0 : -100 }
    var maxValue: Int { discrete ? 3 : 100 }
    var key: String { "pref_oldstyle_style_" + rawValue }

    var value: Int {
        let v = Int(LutTrayModel.number(SettingsStore.shared.string(key, "0"), 0).rounded())
        return max(minValue, min(maxValue, v))
    }

    func set(_ v: Int) { SettingsStore.shared.set(key, String(max(minValue, min(maxValue, v)))) }

    var name: String { L("os.p." + rawValue) }
    var help: String { L("os.h." + rawValue) }

    var icon: String {
        switch self {
        case .exposure: return "cv_ev"
        case .aura: return "cv_os_aura"
        case .halo: return "cv_os_halo"
        case .grain: return "cv_os_grain"
        case .warmth: return "cv_thermo"
        case .hue: return "cv_os_hue"
        case .highlights: return "cv_sun"
        case .shadows: return "cv_os_contrast"
        case .warmBoost: return "cv_os_warm"
        case .coolBoost: return "cv_os_cool"
        case .sharpness: return "cv_sharp"
        default: return "cv_os_vignette"
        }
    }

    /// A value's text: a discrete step's word («Выкл.», «Низк.», «Средн.», «Высок.»), else the signed number
    func text(_ v: Int) -> String {
        if discrete { return L(["os.step0", "os.step1", "os.step2", "os.step3"][max(0, min(3, v))]) }
        return v > 0 ? "+\(v)" : "\(v)"
    }

    static var neutral: Bool { allCases.allSatisfy { $0.value == 0 } }
}

/// OldstyleControls (P630 / P630c / P630g): the «Oldstyle» mode's own controls. The «Про» manual row stays open in this mode (and
/// «Про» steps aside); a round style button above the row's right end opens the style panel, which takes the row's place: a header
/// (back, the segments «Палитра | Расширенные», reset - shown once anything is changed - and the menu) over «Палитра» (a 2D pad: left
/// a cool grey, right a warm salmon colour = «Цвет»; up lighter, down darker = «Тон»; a dot grid that grows under the finger, a dotted
/// cross at the point at rest; the HUD line «База: Natural · Тон: 81 · Цвет: -100» at the preview's top) or «Расширенные» (the
/// chosen parameter's value over a centred tick ruler, a tick of haptics per step, a double tap back to 0, and the row of the twelve
/// parameters' chips). Reset asks first. The photo takes the style at the press.
@MainActor
@Observable
final class OldstyleModel {
    static let shared = OldstyleModel()
    var open = false
    var tab = 0
    var chosen = OldstyleParam.exposure
    private(set) var revision = 0
    func touch() { revision += 1 }
}

struct OldstyleControls: View {
    let camera: CameraModel
    var shown: Bool
    @State private var askReset = false
    @State private var menu = false
    private var model: OldstyleModel { OldstyleModel.shared }

    var body: some View {
        let _ = model.revision
        VStack(alignment: .trailing, spacing: 8) {
            if model.open {
                panel.transition(.opacity.combined(with: .scale(scale: 0.96, anchor: .bottomTrailing)))
            } else {
                Button {
                    Haptics.tick()
                    withAnimation(CameraMotion.open) { model.open = true }
                } label: {
                    Image("cv_os_style").renderingMode(.template).resizable().frame(width: 22, height: 22)
                        .foregroundStyle(OldstyleParam.neutral ? CameraStyle.text : CameraStyle.accent)
                        .frame(width: 44, height: 44)
                        .background(CameraStyle.control, in: Circle())
                        .overlay(Circle().stroke(CameraStyle.hairline, lineWidth: 0.5))
                        .turnsWithPhone()
                }
                .buttonStyle(PressScale())
                .accessibilityLabel(L("os.button"))
                .simultaneousGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in MessageCenter.shared.info(L("os.button_help")) })
            }
        }
        .opacity(shown ? 1 : 0)
        .allowsHitTesting(shown)
        .animation(shown ? CameraMotion.sheet : .easeOut(duration: 0.18), value: shown)
        .confirmationDialog(L("os.reset_title"), isPresented: $askReset, titleVisibility: .visible) {
            Button(L("os.reset"), role: .destructive) {
                for p in OldstyleParam.allCases { p.set(0) }
                model.touch()
                MessageCenter.shared.info(L("os.reset_done"))
            }
        } message: { Text(L("os.reset_message")) }
        .confirmationDialog(L("os.menu"), isPresented: $menu) {
            Button(L("os.reset_style")) { askReset = true }
            let v2 = SettingsStore.shared.string("pref_scam_hybrid_oldstyle_version", "v1").trimmingCharacters(in: .whitespaces) == "v2"
            Button(L("os.tone") + "ScamMaster " + (v2 ? "V2 → V1" : "V1 → V2")) {
                SettingsStore.shared.set("pref_scam_hybrid_oldstyle_version", v2 ? "v1" : "v2")
                MessageCenter.shared.info("ScamMaster " + (v2 ? "V1" : "V2"))
            }
            Button(L("os.help")) { MessageCenter.shared.info(model.tab == 0 ? L("os.h.tone") : model.chosen.help) }
        }
    }

    private var panel: some View {
        VStack(spacing: 8) {
            ZStack {
                if model.tab == 0 {
                    PalettePad().frame(height: 150).transition(.opacity.combined(with: .offset(x: -24)))
                } else {
                    advanced.transition(.opacity.combined(with: .offset(x: 24)))
                }
            }
            .animation(.easeOut(duration: 0.16), value: model.tab)
            HStack(spacing: 0) {
                round("cv_chev_left", L("os.close")) { withAnimation(CameraMotion.exit) { model.open = false } }
                Spacer(minLength: 4)
                HStack(spacing: 0) {
                    ForEach(0..<2, id: \.self) { i in
                        Text(i == 0 ? L("os.palette") : L("os.advanced")).font(.app(13, 600))
                            .foregroundStyle(i == model.tab ? CameraStyle.accent : CameraStyle.text)
                            .padding(.horizontal, 14).frame(height: 32)
                            .background(i == model.tab ? CameraStyle.chosen : Color.clear, in: RoundedRectangle(cornerRadius: 16))
                            .contentShape(Rectangle())
                            .onTapGesture { Haptics.tick(); withAnimation(CameraMotion.glide) { model.tab = i } }
                    }
                }
                .padding(3)
                .background(CameraStyle.control, in: RoundedRectangle(cornerRadius: 20))
                .overlay(RoundedRectangle(cornerRadius: 20).stroke(CameraStyle.hairline, lineWidth: 0.5))
                Spacer(minLength: 4)
                if !OldstyleParam.neutral {
                    round("cs_page_reset", L("os.reset_style")) { askReset = true }
                }
                round("cv_os_more", L("os.menu")) { menu = true }
                    .padding(.leading, 6)
            }
        }
    }

    private func round(_ icon: String, _ label: String, action: @escaping () -> Void) -> some View {
        Button(action: { Haptics.tick(); action() }) {
            Image(icon).renderingMode(.template).resizable().frame(width: 20, height: 20).foregroundStyle(CameraStyle.text)
                .frame(width: 40, height: 40)
                .background(CameraStyle.control, in: Circle())
                .overlay(Circle().stroke(CameraStyle.hairline, lineWidth: 0.5))
                .turnsWithPhone()
        }
        .buttonStyle(PressScale())
        .accessibilityLabel(label)
    }

    private var advanced: some View {
        let p = model.chosen
        return VStack(spacing: 6) {
            Text(p.name + "  " + p.text(p.value)).font(.app(15, 700)).foregroundStyle(CameraStyle.text)
                .shadow(color: .black.opacity(0.6), radius: 3)
            OldstyleRuler(param: p).frame(height: 38)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(OldstyleParam.advanced, id: \.self) { q in
                        let on = q == p
                        VStack(spacing: 3) {
                            Image(q.icon).renderingMode(.template).resizable().frame(width: 20, height: 20)
                                .foregroundStyle(on || q.value != 0 ? CameraStyle.accent : CameraStyle.text)
                            Text(q.value == 0 ? q.name : q.text(q.value)).font(.app(9.5, 600)).lineLimit(1)
                                .minimumScaleFactor(0.7).foregroundStyle(on ? CameraStyle.accent : CameraStyle.text)
                        }
                        .padding(4)
                        .frame(width: 64, height: 64)
                        .background(on ? CameraStyle.row : CameraStyle.control, in: RoundedRectangle(cornerRadius: 18))
                        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(on ? CameraStyle.accent : CameraStyle.hairline, lineWidth: on ? 1.5 : 0.5))
                        .contentShape(Rectangle())
                        .onTapGesture { Haptics.tick(); withAnimation(CameraMotion.glide) { model.chosen = q } }
                        .onLongPressGesture(minimumDuration: 0.5) { MessageCenter.shared.info(q.help) }
                        .accessibilityLabel(q.name + ", " + q.text(q.value))
                        .accessibilityAddTraits(on ? [.isButton, .isSelected] : .isButton)
                    }
                }
                .padding(.horizontal, 4)
            }
        }
    }
}

/// The «Палитра» pad: the cool-grey -> warm-salmon gradient (left to right) with the light above and the shade below, a dot grid
/// (11 pt) whose dots grow under the finger, a dotted cross and a ring at the point at rest, a filled disc while dragged; a tick per
/// ten of either axis
struct PalettePad: View {
    @State private var touch: CGPoint?
    @State private var lastTick: (Int, Int)?
    private var model: OldstyleModel { OldstyleModel.shared }

    var body: some View {
        let _ = model.revision
        let colour = OldstyleParam.color.value, tone = OldstyleParam.tone.value
        let touchNow = touch
        GeometryReader { g in
            let w = g.size.width, h = g.size.height, inset: CGFloat = 14
            let px = inset + CGFloat(colour + 100) / 200 * (w - 2 * inset)
            let py = inset + CGFloat(100 - tone) / 200 * (h - 2 * inset)
            Canvas { context, size in
                let rect = CGRect(origin: .zero, size: size)
                context.fill(Path(rect), with: .linearGradient(Gradient(colors: [Color(red: 0x8A / 255, green: 0x93 / 255, blue: 0x9B / 255).opacity(0.88),
                                                                              Color(red: 0xB3 / 255, green: 0xA8 / 255, blue: 0xA6 / 255).opacity(0.88),
                                                                              Color(red: 0xF2 / 255, green: 0x90 / 255, blue: 0x7F / 255).opacity(0.88)]),
                                                             startPoint: .zero, endPoint: CGPoint(x: size.width, y: 0)))
                context.fill(Path(rect), with: .linearGradient(Gradient(colors: [.white.opacity(0.25), .clear, .black.opacity(0.4)]),
                                                             startPoint: .zero, endPoint: CGPoint(x: 0, y: size.height)))
                let step: CGFloat = 11, reach: CGFloat = 48
                var y = step / 2
                while y < size.height {
                    var x = step / 2
                    while x < size.width {
                        var k: CGFloat = 0
                        if let t = touchNow { let d = hypot(x - t.x, y - t.y); k = d < reach ? 1 - d / reach : 0 }
                        let r = 1.1 * (1 + 1.8 * k * k)
                        context.fill(Path(ellipseIn: CGRect(x: x - r, y: y - r, width: 2 * r, height: 2 * r)), with: .color(.white.opacity(0.42 + 0.58 * k)))
                        x += step
                    }
                    y += step
                }
                if touchNow == nil {
                    var cross = Path()
                    cross.move(to: CGPoint(x: 0, y: py)); cross.addLine(to: CGPoint(x: size.width, y: py))
                    cross.move(to: CGPoint(x: px, y: 0)); cross.addLine(to: CGPoint(x: px, y: size.height))
                    context.stroke(cross, with: .color(.white.opacity(0.8)), style: StrokeStyle(lineWidth: 1.2, dash: [2, 3]))
                    context.stroke(Path(ellipseIn: CGRect(x: px - 6, y: py - 6, width: 12, height: 12)), with: .color(.white), lineWidth: 1.6)
                    context.fill(Path(ellipseIn: CGRect(x: px - 2.2, y: py - 2.2, width: 4.4, height: 4.4)), with: .color(.white))
                } else {
                    context.fill(Path(ellipseIn: CGRect(x: px - 7, y: py - 7, width: 14, height: 14)), with: .color(.white))
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 22))
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { d in
                    let x = min(max(d.location.x, 0), w), y = min(max(d.location.y, 0), h)
                    withAnimation(.easeOut(duration: 0.16)) { touch = CGPoint(x: x, y: y) }
                    let u = min(max((x - inset) / max(1, w - 2 * inset), 0), 1), v = min(max((y - inset) / max(1, h - 2 * inset), 0), 1)
                    let c = Int((u * 200 - 100).rounded()), t = Int((100 - v * 200).rounded())
                    if let last = lastTick, last != (c / 10, t / 10) { Haptics.tick() }
                    lastTick = (c / 10, t / 10)
                    OldstyleParam.color.set(c)
                    OldstyleParam.tone.set(t)
                    model.touch()
                }
                .onEnded { _ in
                    withAnimation(.easeOut(duration: 0.26)) { touch = nil }
                    lastTick = nil
                })
        }
        .accessibilityElement()
        .accessibilityLabel(L("os.pad"))
        .accessibilityValue(L("os.p.tone") + " \(tone), " + L("os.p.color") + " \(colour)")
    }
}

/// The advanced tab's ruler: ticks every 5 (every 25 taller, 0 tallest) for -100..100, four stops for a discrete parameter, the
/// scale sliding under the fixed accent needle; a tick of haptics per step (per 5 for -100..100); a double tap back to 0
struct OldstyleRuler: View {
    let param: OldstyleParam
    @State private var start: Int?
    private var model: OldstyleModel { OldstyleModel.shared }
    private var unit: CGFloat { param.discrete ? 40 : 3 }

    var body: some View {
        let _ = model.revision
        let v = param.value
        GeometryReader { g in
            let w = g.size.width, h = g.size.height, cx = w / 2
            Canvas { context, size in
                let every = param.discrete ? 1 : 5
                var x0 = param.minValue
                while x0 <= param.maxValue {
                    let x = cx + CGFloat(x0 - v) * unit
                    if x >= -2 && x <= size.width + 2 {
                        let major = param.discrete || x0 % 25 == 0
                        let len: CGFloat = x0 == 0 ? 18 : major ? 13 : 7
                        var p = Path()
                        p.move(to: CGPoint(x: x, y: h - 4)); p.addLine(to: CGPoint(x: x, y: h - 4 - len))
                        context.stroke(p, with: .color(.white.opacity(major ? 0.75 : 0.4)), lineWidth: x0 == 0 ? 2 : 1.5)
                    }
                    x0 += every
                }
                var needle = Path()
                needle.move(to: CGPoint(x: cx, y: h - 2)); needle.addLine(to: CGPoint(x: cx, y: h - 26))
                context.stroke(needle, with: .color(CameraStyle.accent), lineWidth: 2)
            }
            .mask(LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.15),
                                         .init(color: .black, location: 0.85), .init(color: .clear, location: 1)],
                                 startPoint: .leading, endPoint: .trailing))
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { d in
                    if start == nil { start = v }
                    let next = max(param.minValue, min(param.maxValue, (start ?? v) - Int((d.translation.width / unit).rounded())))
                    if next != v {
                        let step = param.discrete ? 1 : 5
                        if next / step != v / step || param.discrete { Haptics.tick() }
                        param.set(next)
                        model.touch()
                    }
                }
                .onEnded { _ in start = nil })
            .simultaneousGesture(TapGesture(count: 2).onEnded { Haptics.soft(); param.set(0); model.touch() })
        }
        .accessibilityElement()
        .accessibilityLabel(param.name)
        .accessibilityValue(param.text(v))
        .accessibilityAdjustableAction { dir in
            switch dir {
            case .increment: param.set(v + (param.discrete ? 1 : 5))
            case .decrement: param.set(v - (param.discrete ? 1 : 5))
            @unknown default: break
            }
            model.touch()
        }
    }
}

/// The palette's HUD line at the preview's top while the style panel is open: «База: Natural  ·  Тон: 81  ·  Цвет: -100»
struct OldstyleHud: View {
    var body: some View {
        let _ = OldstyleModel.shared.revision
        Text(L("os.base") + ": Natural  ·  " + L("os.p.tone") + ": \(OldstyleParam.tone.value)  ·  " + L("os.p.color") + ": \(OldstyleParam.color.value)")
            .font(.app(12, 600)).foregroundStyle(CameraStyle.text)
            .padding(.horizontal, 10).frame(height: 24)
            .background(Color.black.opacity(0.5), in: Capsule())
            .allowsHitTesting(false)
    }
}
