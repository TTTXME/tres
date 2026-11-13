import AVFoundation
import CoreMotion
import SwiftUI

/// The camera screen's own settings under the Android keys (CameraUIController): the grid (pref_show_grid_key), the self-timer
/// (pref_countdown_timer_key, an index of countdowntimer_entryvalues 0 / 3 / 10 s), the level (pref_horizon), Ultra HDR
/// (pref_ultrahdr_key) and the camera mode (OldstyleMode.select: pref_motion_mode «Спорт», pref_oldstyle_mode).
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

    var grid: Bool {
        get { (Int(store.string("pref_show_grid_key", "0")) ?? 0) > 0 }
        set { store.set("pref_show_grid_key", newValue ? 1 : 0) }
    }
    var horizon: Bool {
        get { store.bool("pref_horizon", false) }
        set { store.set("pref_horizon", newValue) }
    }
    var ultraHdr: Bool {
        get { store.bool("pref_ultrahdr_key", false) }
        set { store.set("pref_ultrahdr_key", newValue) }
    }
    var timerSeconds: Int {
        let i = Int(store.string("pref_countdown_timer_key", "0")) ?? 0
        return Self.timerValues[min(max(i, 0), Self.timerValues.count - 1)]
    }
    func cycleTimer() {
        let i = Int(store.string("pref_countdown_timer_key", "0")) ?? 0
        store.set("pref_countdown_timer_key", (i + 1) % Self.timerValues.count)
    }
    var mode: Mode {
        get { store.bool("pref_oldstyle_mode", false) ? .oldstyle : store.bool("pref_motion_mode", false) ? .sport : .photo }
        set {
            store.set("pref_oldstyle_mode", newValue == .oldstyle)
            store.set("pref_motion_mode", newValue == .sport)
        }
    }

    /// The shutter: the self-timer's countdown first (CameraUIController.startTimer), then the shot.
    func shoot(_ capture: @escaping () async -> Void) {
        let seconds = timerSeconds
        guard seconds > 0 else { Task { await capture() }; return }
        countdown = seconds
        Task {
            while countdown > 0 {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                countdown -= 1
            }
            await capture()
        }
    }
}

/// The viewfinder's 3 x 3 grid and the level line (the device's roll from CoreMotion; green when level within 1°).
struct ViewfinderOverlay: View {
    let grid: Bool
    let horizon: Bool
    @State private var roll: Double = 0
    @State private var motion = CMMotionManager()

    var body: some View {
        GeometryReader { g in
            ZStack {
                if grid {
                    Path { p in
                        for i in 1...2 {
                            let x = g.size.width * CGFloat(i) / 3, y = g.size.height * CGFloat(i) / 3
                            p.move(to: CGPoint(x: x, y: 0)); p.addLine(to: CGPoint(x: x, y: g.size.height))
                            p.move(to: CGPoint(x: 0, y: y)); p.addLine(to: CGPoint(x: g.size.width, y: y))
                        }
                    }
                    .stroke(Color.white.opacity(0.35), lineWidth: 1)
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

    private func startMotion() {
        guard horizon, motion.isDeviceMotionAvailable, !motion.isDeviceMotionActive else { return }
        motion.deviceMotionUpdateInterval = 1.0 / 30
        motion.startDeviceMotionUpdates(to: .main) { data, _ in
            guard let g = data?.gravity else { return }
            roll = atan2(g.x, -g.y) * 180 / .pi
        }
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

/// «Про»: the manual exposure of the open lens (ISO, shutter, exposure bias, focus, white balance); Auto by default.
struct ProSheet: View {
    let camera: CameraModel
    @State private var manual = ManualSettings.load()

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle(L("pro.manual_exposure"), isOn: $manual.exposureOn)
                    if manual.exposureOn {
                        LabeledContent("ISO", value: "\(Int(manual.iso))")
                        Slider(value: $manual.iso, in: 25...3200, step: 1)
                        LabeledContent(L("pro.shutter"), value: ManualSettings.shutterText(manual.shutter))
                        Slider(value: $manual.shutter, in: -13...0, step: 0.25)
                    } else {
                        LabeledContent(L("pro.ev"), value: String(format: "%+.1f", manual.bias))
                        Slider(value: $manual.bias, in: -3...3, step: 0.1)
                    }
                }
                Section {
                    Toggle(L("pro.manual_focus"), isOn: $manual.focusOn)
                    if manual.focusOn { Slider(value: $manual.focus, in: 0...1) }
                    Toggle(L("pro.manual_wb"), isOn: $manual.wbOn)
                    if manual.wbOn {
                        LabeledContent(L("pro.wb"), value: "\(Int(manual.kelvin)) K")
                        Slider(value: $manual.kelvin, in: 2500...8000, step: 50)
                    }
                }
                Section { Button(L("pro.auto")) { manual = ManualSettings() } }
            }
            .navigationTitle(L("bottom.pro"))
            .navigationBarTitleDisplayMode(.inline)
        }
        .preferredColorScheme(.dark)
        .onChange(of: manual) { manual.save(); Task { await camera.applyManual(manual) } }
    }
}

struct ManualSettings: Codable, Equatable, Sendable {
    var exposureOn = false, iso: Double = 100, shutter: Double = -6, bias: Double = 0
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
