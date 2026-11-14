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
