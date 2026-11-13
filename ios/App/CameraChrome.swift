import AVFoundation
import CoreMotion
import SwiftUI

/// The camera screen's own settings under the Android keys (CameraUIController): the grid (pref_show_grid_key: 0 off, 1 3x3, 2 4x4,
/// 3 golden ratio, 4 diagonals; the top row toggles off <-> 3x3), the self-timer (pref_countdown_timer_key, an index of
/// countdowntimer_entryvalues 0 / 3 / 10 s), the level (pref_horizon), Ultra HDR (pref_ultrahdr_key) and the camera mode
/// (OldstyleMode.select: pref_motion_mode «Спорт», pref_oldstyle_mode).
@MainActor
@Observable
final class CameraChrome {
    enum Mode { case sport, photo, oldstyle
        var title: String {
            switch self { case .sport: return L("mode.sport"); case .photo: return L("mode.photo"); case .oldstyle: return "Oldstyle" }
        }
    }
    static let timerValues = [0, 3, 10]
    private let store = SettingsStore.shared
    private(set) var countdown = 0
    private var countdownTask: Task<Void, Never>?

    /// vf_grid_entryvalues: 0 off, 1 3x3, 2 4x4, 3 golden ratio, 4 diagonals
    var gridType: Int { min(max(Int(store.string("pref_show_grid_key", "0")) ?? 0, 0), 4) }
    var grid: Bool { gridType > 0 }
    /// P574: the top row's grid (the shade's «Сетка»: off <-> 3x3; a stored 4x4 / Φ goes off first); returns the new state
    func toggleGrid() -> Bool {
        let on = gridType <= 0
        store.set("pref_show_grid_key", on ? "1" : "0")
        return on
    }
    var horizon: Bool {
        get { store.bool("pref_horizon", false) }
        set { store.set("pref_horizon", newValue) }
    }
    var ultraHdr: Bool {
        get { store.bool("pref_ultrahdr_key", false) }
        set { store.set("pref_ultrahdr_key", newValue) }
    }
    /// «Закруглённые края» (pref_show_roundedge_key, default on): the viewfinder's 24 pt corners
    var roundEdges: Bool { store.bool("pref_show_roundedge_key", true) }
    var timerSeconds: Int {
        let i = Int(store.string("pref_countdown_timer_key", "0")) ?? 0
        return Self.timerValues[min(max(i, 0), Self.timerValues.count - 1)]
    }
    /// SIMPLE_UI §1: off -> 3 s -> 10 s -> off; returns the new seconds
    func cycleTimer() -> Int {
        let i = Int(store.string("pref_countdown_timer_key", "0")) ?? 0
        store.set("pref_countdown_timer_key", (i + 1) % Self.timerValues.count)
        return timerSeconds
    }
    var mode: Mode {
        get { store.bool("pref_oldstyle_mode", false) ? .oldstyle : store.bool("pref_motion_mode", false) ? .sport : .photo }
        set {
            store.set("pref_oldstyle_mode", newValue == .oldstyle)
            store.set("pref_motion_mode", newValue == .sport)
        }
    }

    /// A self-timer counts down (the shutter then stops it)
    var counting: Bool { countdownTask != nil }

    /// The shutter (CameraUIController.onClick): a press while the timer counts stops it (resetTimer), else the countdown with
    /// its sound first (startTimer: CameraSounds.timerStart, cut at the end), then the shot.
    func shoot(_ capture: @escaping () async -> Void) {
        if countdownTask != nil {
            cancelTimer()
            return
        }
        let seconds = timerSeconds
        guard seconds > 0 else { Task { await capture() }; return }
        CameraSounds.shared.timerStart()
        countdown = seconds
        countdownTask = Task {
            while countdown > 0 {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if Task.isCancelled { return }
                countdown -= 1
            }
            CameraSounds.shared.timerStop()
            countdownTask = nil
            await capture()
        }
    }

    /// resetTimer: no shot, the sound cut
    func cancelTimer() {
        countdownTask?.cancel()
        countdownTask = nil
        countdown = 0
        CameraSounds.shared.timerStop()
    }
}

/// The camera accent (the concept's #FFD60A) and the items' tint when on
enum CameraStyle {
    static let accent = Color(red: 1, green: 0.839, blue: 0.039)

    static var russian: Bool { (Locale.preferredLanguages.first ?? "en").hasPrefix("ru") }

    /// The decimal comma in the Russian interface (owner's answer 11), the point in English
    static func decimal(_ text: String) -> String { russian ? text.replacingOccurrences(of: ".", with: ",") : text }

    /// «2,5» / «1» (ZoomDialView.format)
    static func zoomText(_ v: Double) -> String {
        var text = String(format: "%.1f", v)
        if text.hasSuffix(".0") { text.removeLast(2) }
        return decimal(text)
    }
}

/// The viewfinder's grid (SurfaceViewOverViewfinder.drawGrid: 3x3, 4x4, golden ratio, the diagonals; rgba(255, 255, 255, .28), 1 pt)
/// and the level line (the device's roll from CoreMotion; green when level within 1°).
struct ViewfinderOverlay: View {
    let grid: Int
    let horizon: Bool
    @State private var roll: Double = 0
    @State private var motion = CMMotionManager()

    var body: some View {
        GeometryReader { g in
            ZStack {
                if grid > 0 {
                    Path { p in Self.lines(grid, size: g.size).forEach { p.move(to: $0.0); p.addLine(to: $0.1) } }
                        .stroke(Color.white.opacity(0.28), lineWidth: 1)
                }
                if horizon {
                    let level = abs(roll) < 1
                    Capsule().fill(level ? Color.green : Color.white).frame(width: g.size.width * 0.4, height: 2)
                        .rotationEffect(.degrees(-roll))
                        .position(x: g.size.width / 2, y: g.size.height / 2)
                }
            }
        }
        .allowsHitTesting(false)
        .onAppear { startMotion() }
        .onChange(of: horizon) { startMotion() }
        .onDisappear { motion.stopDeviceMotionUpdates() }
    }

    /// The segments of grid type `type` in a w x h view
    static func lines(_ type: Int, size: CGSize) -> [(CGPoint, CGPoint)] {
        let w = size.width, h = size.height
        let v = { (x: CGFloat) in (CGPoint(x: x, y: 0), CGPoint(x: x, y: h)) }
        let hz = { (y: CGFloat) in (CGPoint(x: 0, y: y), CGPoint(x: w, y: y)) }
        switch type {
        case 1: return [v(w / 3), v(2 * w / 3), hz(h / 3), hz(2 * h / 3)]
        case 2: return [v(w / 4), v(w / 2), v(3 * w / 4), hz(h / 4), hz(h / 2), hz(3 * h / 4)]
        case 3:
            let gr = (1 + 5.0.squareRoot()) / 2
            return [v(w / (1 + gr)), v(gr * w / (1 + gr)), hz(h / (1 + gr)), hz(gr * h / (1 + gr))]
        case 4:
            return [(CGPoint(x: 0, y: 0), CGPoint(x: w, y: h)), (CGPoint(x: w / 3, y: h / 3), CGPoint(x: w, y: 0)),
                    (CGPoint(x: 2 * w / 3, y: 2 * h / 3), CGPoint(x: 0, y: h))]
        default: return []
        }
    }

    private func startMotion() {
        guard horizon, motion.isDeviceMotionAvailable, !motion.isDeviceMotionActive else { return }
        motion.deviceMotionUpdateInterval = 1.0 / 30
        motion.startDeviceMotionUpdates(to: .main) { data, _ in
            guard let g = data?.gravity else { return }
            roll = atan2(g.x, -g.y) * 180 / .pi
        }
    }
}

/// SIMPLE_UI §1, the concept's brightness control beside the focus ring (ExposureSunView): a vertical line (1.5 pt, the accent at
/// 80 %) broken by the sun (20 pt) at the exposure compensation, its value («+0,7») beside it while it is not 0. Dragging up
/// brightens; thirds of a stop; the value is the Pro panel's EV. 96 pt high, 0 EV at ZERO of the height.
struct ExposureSun: View {
    let range: ClosedRange<Float>
    let ev: Double
    var onEv: (Double) -> Void
    var onDrag: (Bool) -> Void = { _ in }
    static let height: CGFloat = 96, width: CGFloat = 22, knob: CGFloat = 20, gap: CGFloat = 11, label: CGFloat = 48
    static let zero = (height - knob) / 2.4 / height + knob / 2 / height
    @State private var downEv: Double?

    /// pt per 1 EV: the room on the shorter side of 0 EV over its end of the range
    private var ptPerEv: CGFloat {
        let r = Self.knob / 2, zeroY = Self.height * Self.zero
        let up = (zeroY - r) / CGFloat(max(1e-3, range.upperBound)), down = (Self.height - r - zeroY) / CGFloat(max(1e-3, -range.lowerBound))
        return max(0, min(up, down))
    }

    var body: some View {
        let r = Self.knob / 2
        let y = Self.height * Self.zero - CGFloat(ev) * ptPerEv
        ZStack(alignment: .topLeading) {
            Path { p in
                if y - r > 0 { p.move(to: CGPoint(x: Self.width / 2, y: 0)); p.addLine(to: CGPoint(x: Self.width / 2, y: y - r)) }
                if y + r < Self.height { p.move(to: CGPoint(x: Self.width / 2, y: y + r)); p.addLine(to: CGPoint(x: Self.width / 2, y: Self.height)) }
            }
            .stroke(CameraStyle.accent.opacity(0.8), lineWidth: 1.5)
            Image("cv_sun").renderingMode(.template).resizable().frame(width: Self.knob, height: Self.knob)
                .foregroundStyle(CameraStyle.accent)
                .position(x: Self.width / 2, y: y)
            if abs(ev) >= 0.05 {
                Text(CameraStyle.decimal(String(format: "%+.1f", ev)))
                    .font(.system(size: 12, weight: .semibold)).foregroundStyle(.white).shadow(color: .black, radius: 3, y: 1)
                    .fixedSize()
                    .position(x: Self.width + 4 + 18, y: y)
            }
        }
        .frame(width: Self.width + Self.label, height: Self.height, alignment: .topLeading)
        .contentShape(Rectangle().size(width: Self.width + 6, height: Self.height))
        .gesture(DragGesture(minimumDistance: 0)
            .onChanged { g in
                if downEv == nil { downEv = ev; onDrag(true) }
                guard ptPerEv > 0 else { return }
                var next = (downEv ?? ev) - Double(g.translation.height / ptPerEv)
                next = min(max(next, Double(range.lowerBound)), Double(range.upperBound))
                next = (next * 3).rounded() / 3
                if abs(next - ev) > 1e-4 {
                    UISelectionFeedbackGenerator().selectionChanged()
                    onEv(next)
                }
            }
            .onEnded { _ in downEv = nil; onDrag(false) })
        .accessibilityLabel(L("ev.sun"))
    }
}

/// The zoom ruler (ZoomDialView): a logarithmic scale sliding under a fixed pointer, the module ratios as labelled marks; the value
/// in the accent with a decimal comma; shown while the zoom changes, faded out 1.6 s after. Dragging it zooms.
struct ZoomRuler: View {
    let zoom: Double
    let range: ClosedRange<Double>
    let marks: [(Double, String)]
    var onZoom: (Double) -> Void
    @State private var lastX: CGFloat?

    var body: some View {
        GeometryReader { g in
            let w = g.size.width, h = g.size.height, cx = w / 2, ppo = w * 0.36
            let logZoom = log2(max(zoom, 1e-3))
            ZStack {
                RoundedRectangle(cornerRadius: 16).fill(Color(white: 0.1, opacity: 0.92))
                RoundedRectangle(cornerRadius: 16).stroke(Color.white.opacity(0.12), lineWidth: 1)
                Canvas { context, size in
                    let baseline = size.height - 9
                    let q0 = Int((log2(range.lowerBound) * 4).rounded(.down)), q1 = Int((log2(range.upperBound) * 4).rounded(.up))
                    if q0 <= q1 {
                        for q in q0...q1 {
                            let x = cx + CGFloat(Double(q) / 4 - logZoom) * ppo
                            guard x >= 0, x <= size.width else { continue }
                            let octave = q % 4 == 0
                            var p = Path()
                            p.move(to: CGPoint(x: x, y: baseline - (octave ? 7 : 4)))
                            p.addLine(to: CGPoint(x: x, y: baseline))
                            context.stroke(p, with: .color(Color.white.opacity(octave ? 0.6 : 0.35)), lineWidth: 1.2)
                        }
                    }
                    for (ratio, label) in marks {
                        let x = cx + CGFloat(log2(ratio) - logZoom) * ppo
                        guard x >= -20, x <= size.width + 20 else { continue }
                        var p = Path()
                        p.move(to: CGPoint(x: x, y: baseline - 9))
                        p.addLine(to: CGPoint(x: x, y: baseline))
                        context.stroke(p, with: .color(.white), lineWidth: 1.2)
                        let current = abs(ratio - zoom) < 0.05
                        context.draw(Text(label).font(.system(size: 9)).foregroundColor(current ? .white : .gray),
                                     at: CGPoint(x: x, y: baseline - 15))
                    }
                    var tri = Path()
                    tri.move(to: CGPoint(x: cx, y: baseline + 1))
                    tri.addLine(to: CGPoint(x: cx - 4, y: baseline + 6))
                    tri.addLine(to: CGPoint(x: cx + 4, y: baseline + 6))
                    tri.closeSubpath()
                    context.fill(tri, with: .color(CameraStyle.accent))
                }
                Text(CameraStyle.zoomText(zoom) + "×").font(.system(size: 14, weight: .bold)).foregroundStyle(CameraStyle.accent)
                    .position(x: cx, y: 12)
            }
            .frame(width: w, height: h)
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { d in
                    let dx = d.location.x - (lastX ?? d.location.x)
                    lastX = d.location.x
                    guard dx != 0 else { return }
                    let next = min(max(zoom * pow(2, Double(-dx / ppo)), range.lowerBound), range.upperBound)
                    if next != zoom { onZoom(next) }
                }
                .onEnded { _ in lastX = nil })
        }
        .frame(height: 48)
        .accessibilityLabel(L("zoom.ruler"))
    }
}

/// «Формат | Размер» (CameraSheets.formatAndSize): the photo format, RAW (DNG) beside it, the full resolution and the SHDR route.
struct FormatSheet: View {
    let camera: CameraModel
    private var store: SettingsStore { SettingsStore.shared }

    var body: some View {
        NavigationStack {
            Form {
                Section(L("format.photo")) {
                    Picker(L("format.photo"), selection: Binding(get: { store.string("pref_photo_format", "jpeg") },
                                                                 set: { store.set("pref_photo_format", $0) })) {
                        Text("HEIC").tag("heic")
                        Text("JPEG").tag("jpeg")
                    }
                    .pickerStyle(.segmented)
                }
                Section(L("raw.title")) {
                    Picker(L("raw.title"), selection: Binding(get: { camera.raw }, set: { camera.setRaw($0) })) {
                        ForEach(RawChoice.allCases) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.segmented)
                }
                Section(L("format.size")) {
                    Toggle(L("fullres.title"), isOn: Binding(get: { camera.fullResolution }, set: { v in Task { await camera.setFullResolution(v) } }))
                        .disabled(!camera.supportsFullResolution)
                    Toggle("SHDR", isOn: Binding(get: { camera.shdr }, set: { camera.setShdr($0) }))
                }
            }
            .navigationTitle(L("top.format"))
            .navigationBarTitleDisplayMode(.inline)
        }
        .preferredColorScheme(.dark)
    }
}

/// «Про» (the manual panel, ExpandingManualPanel's five chips): ISO, shutter, focus, EV, white balance, each «Авто» on its own; ISO or
/// shutter alone keeps the scene's exposure with the other (the panel's software priority), both fix it (EV then locked, as
/// ManualModeConsole.evLocked). The ranges are the open lens's; EV in quarters of a stop (EvModel), white balance 2000-10000 K by 100.
struct ProSheet: View {
    let camera: CameraModel
    @State private var manual = ManualSettings.load()

    private var isoRange: ClosedRange<Double> { Double(camera.lens.isoRange.lowerBound)...Double(camera.lens.isoRange.upperBound) }
    private var shutterRange: ClosedRange<Double> { log2(camera.lens.durationRange.lowerBound)...log2(camera.lens.durationRange.upperBound) }
    private var evRange: ClosedRange<Double> {
        max(-4, Double(camera.lens.evRange.lowerBound))...min(4, Double(camera.lens.evRange.upperBound))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle(L("pro.manual_iso"), isOn: $manual.isoOn)
                    if manual.isoOn {
                        LabeledContent("ISO", value: "\(Int(manual.iso.rounded()))")
                        Slider(value: Binding(get: { log2(max(manual.iso, 1)) }, set: { manual.iso = pow(2, $0).rounded() }),
                               in: log2(isoRange.lowerBound)...log2(isoRange.upperBound))
                    }
                    Toggle(L("pro.manual_shutter"), isOn: $manual.shutterOn)
                    if manual.shutterOn {
                        LabeledContent(L("pro.shutter"), value: ManualSettings.shutterText(manual.shutter))
                        Slider(value: $manual.shutter, in: shutterRange, step: 1.0 / 3)
                    }
                    LabeledContent(L("pro.ev"), value: String(format: "%+.2f", manual.bias))
                    Slider(value: $manual.bias, in: evRange, step: 0.25).disabled(manual.isoOn && manual.shutterOn)
                }
                Section {
                    Toggle(L("pro.manual_focus"), isOn: $manual.focusOn)
                    if manual.focusOn { Slider(value: $manual.focus, in: 0...1) }
                    Toggle(L("pro.manual_wb"), isOn: $manual.wbOn)
                    if manual.wbOn {
                        LabeledContent(L("pro.wb"), value: "\(Int(manual.kelvin)) K")
                        Slider(value: $manual.kelvin, in: 2000...10000, step: 100)
                    }
                }
                Section { Button(L("pro.auto")) { manual = ManualSettings() } }
            }
            .navigationTitle(L("bottom.pro"))
            .navigationBarTitleDisplayMode(.inline)
        }
        .preferredColorScheme(.dark)
        .onChange(of: manual) {
            manual.iso = min(max(manual.iso, isoRange.lowerBound), isoRange.upperBound)
            manual.shutter = min(max(manual.shutter, shutterRange.lowerBound), shutterRange.upperBound)
            manual.save()
            Task { await camera.applyManual(manual) }
        }
    }
}

/// The Pro panel's values (ManualParamModel): each parameter manual or auto; stored as JSON ("scam.manual").
struct ManualSettings: Codable, Equatable, Sendable {
    var isoOn = false, iso: Double = 100
    var shutterOn = false, shutter: Double = -6
    var bias: Double = 0
    var focusOn = false, focus: Double = 0.5
    var wbOn = false, kelvin: Double = 5500

    static func shutterText(_ log2: Double) -> String {
        let t = pow(2, log2)
        return t >= 0.5 ? String(format: "%.1f s", t) : "1/\(Int((1 / t).rounded()))"
    }
    static func load() -> ManualSettings {
        guard let d = UserDefaults.standard.data(forKey: "scam.manual"), let m = try? JSONDecoder().decode(ManualSettings.self, from: d) else { return ManualSettings() }
        return m
    }
    func save() { UserDefaults.standard.set(try? JSONEncoder().encode(self), forKey: "scam.manual") }
}

extension ManualSettings {
    private enum Keys: String, CodingKey { case isoOn, iso, shutterOn, shutter, bias, focusOn, focus, wbOn, kelvin, exposureOn }

    /// Stored values of any earlier form read as far as they go (the old «Ручная экспозиция» set ISO and shutter together)
    init(from decoder: Decoder) throws {
        self.init()
        let c = try decoder.container(keyedBy: Keys.self)
        let both = try c.decodeIfPresent(Bool.self, forKey: .exposureOn) ?? false
        isoOn = try c.decodeIfPresent(Bool.self, forKey: .isoOn) ?? both
        iso = try c.decodeIfPresent(Double.self, forKey: .iso) ?? iso
        shutterOn = try c.decodeIfPresent(Bool.self, forKey: .shutterOn) ?? both
        shutter = try c.decodeIfPresent(Double.self, forKey: .shutter) ?? shutter
        bias = try c.decodeIfPresent(Double.self, forKey: .bias) ?? bias
        focusOn = try c.decodeIfPresent(Bool.self, forKey: .focusOn) ?? focusOn
        focus = try c.decodeIfPresent(Double.self, forKey: .focus) ?? focus
        wbOn = try c.decodeIfPresent(Bool.self, forKey: .wbOn) ?? wbOn
        kelvin = try c.decodeIfPresent(Double.self, forKey: .kelvin) ?? kelvin
    }
}
