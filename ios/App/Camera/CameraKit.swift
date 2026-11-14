import SwiftUI
import UIKit

/// The camera screen's concept colours (ConceptStyle, UiTokens, ShadeStyle, BottomChrome) as the Android code names them.
extension CameraStyle {
    /// ConceptStyle: the controls (#1C1C1E), the rows and discs (#2C2C2E), the chosen disc (#3A3A3C), a pressed grey (#48484A)
    static let control = Color(red: 0x1C / 255, green: 0x1C / 255, blue: 0x1E / 255)
    static let row = Color(red: 0x2C / 255, green: 0x2C / 255, blue: 0x2E / 255)
    static let chosen = Color(red: 0x3A / 255, green: 0x3A / 255, blue: 0x3C / 255)
    static let grey = Color(red: 0x48 / 255, green: 0x48 / 255, blue: 0x4A / 255)
    static let badge = Color(red: 0x8E / 255, green: 0x8E / 255, blue: 0x93 / 255)
    /// The controls' hairline: rgba(255, 255, 255, .1)
    static let hairline = Color.white.opacity(0.1)
    /// The segmented track: rgba(118, 118, 128, .24); its thumb #636366
    static let segTrack = Color(red: 118 / 255, green: 118 / 255, blue: 128 / 255).opacity(0.24)
    static let segThumb = Color(red: 0x63 / 255, green: 0x63 / 255, blue: 0x66 / 255)
    /// SettingsStyle: text #F4F3F7, muted #B2BAC9, summary white 60 %, card #1B2023, line #30363C, warn #FFB4A8
    static let text = Color(red: 0xF4 / 255, green: 0xF3 / 255, blue: 0xF7 / 255)
    static let muted = Color(red: 0xB2 / 255, green: 0xBA / 255, blue: 0xC9 / 255)
    static let summary = Color.white.opacity(0.6)
    static let card = Color(red: 0x1B / 255, green: 0x20 / 255, blue: 0x23 / 255)
    static let line = Color(red: 0x30 / 255, green: 0x36 / 255, blue: 0x3C / 255)
    static let warn = Color(red: 1, green: 0xB4 / 255, blue: 0xA8 / 255)
    static let red = Color(red: 1, green: 0x45 / 255, blue: 0x3A / 255)
    /// UiTokens.PRO_CARD: rgba(20, 20, 22, .9); the LUT tray's rgba(20, 20, 22, .92)
    static let proCard = Color(red: 20 / 255, green: 20 / 255, blue: 22 / 255).opacity(0.9)
    static let tray = Color(red: 20 / 255, green: 20 / 255, blue: 22 / 255).opacity(0.92)
    /// The accent's dark tone (the concept's #4A3D00: the accent at 29 %)
    static let accentDark = Color(red: 1 * 0.29, green: 0.839 * 0.29, blue: 0.039 * 0.29)
    /// The grabbers: white 35 % on the screen, 30 % on a sheet
    static let grip = Color.white.opacity(0.35)
    static let sheetGrip = Color.white.opacity(0.3)
    /// ShadeStyle.INK: the text on the accent
    static let ink = Color.black
}

/// The haptics the Android code asks for (performHapticFeedback / Vibration): a selection tick (CLOCK_TICK, VIRTUAL_KEY), the soft
/// pulse (Vibration.Soft), a long press.
enum Haptics {
    @MainActor static func tick() { UISelectionFeedbackGenerator().selectionChanged() }
    @MainActor static func soft() { UIImpactFeedbackGenerator(style: .soft).impactOccurred() }
    @MainActor static func light() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    @MainActor static func longPress() { UIImpactFeedbackGenerator(style: .medium).impactOccurred() }
}

/// UIKit's damped spring the Android panels use (ExpandingManualPanel.Spring / TrayMotion): response and damping as SwiftUI's
enum CameraMotion {
    /// ExpandingManualPanel.OPEN / the LUT tray's opening (response .42, damping .8)
    static let open = Animation.spring(response: 0.42, dampingFraction: 0.8)
    /// The settings' chip pill glide (SettingsMotion.GLIDE: .34, .78)
    static let glide = Animation.spring(response: 0.34, dampingFraction: 0.78)
    /// ExpandingManualPanel.EXIT: PathInterpolator(.3, 0, .8, .15), 220 ms
    static let exit = Animation.timingCurve(0.3, 0, 0.8, 0.15, duration: 0.22)
    /// Motion.SHEET: UIKit's sheet curve (.32, .72, 0, 1), 240 ms
    static let sheet = Animation.timingCurve(0.32, 0.72, 0, 1, duration: 0.24)
    static let easeOut = Animation.easeOut(duration: 0.18)
}

/// The phone's orientation for the controls (CustomOrientationEventListener + CameraFragmentViewModel.controlsRotation, P32): the
/// screen stays portrait, the icons and labels turn upright with the phone in 350 ms - left side up -90, right side up 90,
/// upside down 180. The system's rotation lock keeps them as they are, as Android's auto-rotate switch does (UIDevice reports
/// no new orientation then).
@MainActor
@Observable
final class DeviceTurn {
    static let shared = DeviceTurn()
    /// The controls' rotation in degrees, clockwise (SwiftUI's rotationEffect)
    private(set) var degrees: Double = 0
    @ObservationIgnored private var observer: NSObjectProtocol?

    func start() {
        guard observer == nil else { return }
        UIDevice.current.beginGeneratingDeviceOrientationNotifications()
        observer = NotificationCenter.default.addObserver(forName: UIDevice.orientationDidChangeNotification, object: nil,
                                                          queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.update() }
        }
        update()
    }

    private func update() {
        let next: Double
        switch UIDevice.current.orientation {
        case .portrait: next = 0
        case .landscapeLeft: next = 90       // the right side up
        case .landscapeRight: next = -90     // the left side up
        case .portraitUpsideDown: next = 180
        default: return                      // face up / down, unknown: as it was
        }
        guard next != degrees else { return }
        SessionLog.i("Camera", "controls turn to \(Int(next))")
        withAnimation(.easeInOut(duration: 0.35)) { degrees = next }
    }
}

extension View {
    /// bindRotate: the view turns with the phone (P32)
    func turnsWithPhone() -> some View { modifier(TurnsWithPhone()) }
}

private struct TurnsWithPhone: ViewModifier {
    func body(content: Content) -> some View {
        content.rotationEffect(.degrees(DeviceTurn.shared.degrees))
    }
}

/// The shared segmented control of the camera's sheets (IosSegmented: a capsule track, the grey thumb gliding to the chosen
/// segment, its label in the accent); 14 pt semibold, 36 pt high.
struct CameraSegmented: View {
    let labels: [String]
    let selected: Int
    var disabled = false
    var dots: [Bool] = []
    var onPick: (Int) -> Void
    var onDoubleTap: ((Int) -> Void)?
    @Namespace private var thumb

    var body: some View {
        HStack(spacing: 0) {
            ForEach(labels.indices, id: \.self) { i in
                ZStack {
                    if i == selected {
                        Capsule().fill(CameraStyle.segThumb).matchedGeometryEffect(id: "thumb", in: thumb).padding(2)
                    }
                    HStack(spacing: 4) {
                        Text(labels[i]).font(.app(14, 600)).lineLimit(1).minimumScaleFactor(0.7)
                            .foregroundStyle(i == selected ? CameraStyle.accent : CameraStyle.text)
                        if i < dots.count, dots[i] { Circle().fill(CameraStyle.accent).frame(width: 5, height: 5) }
                    }
                    .padding(.horizontal, 6)
                }
                .frame(maxWidth: .infinity, minHeight: 36)
                .contentShape(Rectangle())
                .onTapGesture(count: 2) {
                    if let onDoubleTap { onDoubleTap(i) } else if i != selected { pick(i) }
                }
                .simultaneousGesture(TapGesture().onEnded { if i != selected { pick(i) } })
                .accessibilityAddTraits(i == selected ? [.isButton, .isSelected] : .isButton)
            }
        }
        .background(CameraStyle.segTrack, in: Capsule())
        .opacity(disabled ? 0.35 : 1)
        .allowsHitTesting(!disabled)
    }

    private func pick(_ i: Int) {
        Haptics.tick()
        withAnimation(CameraMotion.glide) { onPick(i) }
    }
}

/// The settings' grabber of a sheet: 36 x 5 at 30 % (a 44 x 12 target)
struct SheetGrab: View {
    var body: some View {
        RoundedRectangle(cornerRadius: 2.5).fill(CameraStyle.sheetGrip).frame(width: 36, height: 5)
            .frame(width: 44, height: 12)
    }
}
