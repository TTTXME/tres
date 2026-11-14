import CoreMotion
import SwiftUI

/// HorizonIndicatorView (P174 / P245): a thin line with round ends and a gap in its middle that turns with the phone, two short
/// fixed marks beyond its ends, a small ring in the centre and the tilt in degrees above it, white with a soft shadow; level
/// (within half a degree of the frame's horizontal in the controls' orientation, left beyond one degree) everything turns the
/// accent, the line closes and one soft haptic pulse marks the moment. A 420 pt square centred on the preview.
struct HorizonLevel: View {
    static let levelIn = 0.5, levelOut = 1.0
    @State private var motion = HorizonMotion()

    var body: some View {
        let turn = DeviceTurn.shared.degrees
        Canvas { context, size in
            draw(&context, size: size, roll: motion.roll, turn: turn, level: motion.level)
        }
        .frame(width: 420, height: 420)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .onAppear { motion.start() }
        .onDisappear { motion.stop() }
        .onChange(of: turn) { motion.turn = turn }
    }

    /// The line's tilt against the frame's horizontal in this orientation, degrees in (-90, 90]
    static func tilt(_ roll: Double, _ turn: Double) -> Double {
        var t = (-roll - turn).truncatingRemainder(dividingBy: 180)
        if t > 90 { t -= 180 }
        if t <= -90 { t += 180 }
        return t
    }

    static func levelNow(_ was: Bool, _ t: Double) -> Bool { was ? abs(t) <= levelOut : abs(t) <= levelIn }

    private func draw(_ context: inout GraphicsContext, size: CGSize, roll: Double, turn: Double, level: Bool) {
        let cx = size.width / 2, cy = size.height / 2
        let colour: Color = level ? CameraStyle.accent : Color.white.opacity(0.95)
        let marks: Color = level ? CameraStyle.accent : Color.white.opacity(0.6)
        let half: CGFloat = 70, gap: CGFloat = 12
        context.addFilter(.shadow(color: .black.opacity(0.4), radius: 2.5, x: 0, y: 0.5))
        let style = StrokeStyle(lineWidth: 2, lineCap: .round)
        // the line, turning with the phone; closed through the centre when level
        var line = context
        line.translateBy(x: cx, y: cy)
        line.rotate(by: .degrees(-roll))
        var p = Path()
        if level {
            p.move(to: CGPoint(x: -half, y: 0)); p.addLine(to: CGPoint(x: half, y: 0))
        } else {
            p.move(to: CGPoint(x: -half, y: 0)); p.addLine(to: CGPoint(x: -gap, y: 0))
            p.move(to: CGPoint(x: gap, y: 0)); p.addLine(to: CGPoint(x: half, y: 0))
        }
        line.stroke(p, with: .color(colour), style: style)
        // the frame's horizontal: two fixed marks beyond the line's ends, the tilt above the centre
        var frame = context
        frame.translateBy(x: cx, y: cy)
        frame.rotate(by: .degrees(turn))
        let m0 = half + 8, m1 = half + 26
        var m = Path()
        m.move(to: CGPoint(x: -m1, y: 0)); m.addLine(to: CGPoint(x: -m0, y: 0))
        m.move(to: CGPoint(x: m0, y: 0)); m.addLine(to: CGPoint(x: m1, y: 0))
        frame.stroke(m, with: .color(marks), style: style)
        if !level {
            let t = Self.tilt(roll, turn)
            frame.draw(Text("\(Int(abs(t).rounded()))°").font(.system(size: 11)).foregroundColor(.white.opacity(0.9)),
                       at: CGPoint(x: 0, y: -16), anchor: .bottom)
        }
        context.stroke(Path(ellipseIn: CGRect(x: cx - 4.5, y: cy - 4.5, width: 9, height: 9)), with: .color(colour), lineWidth: 1.5)
    }
}

/// The phone's roll from CoreMotion's gravity (Gyro.getRoll: the rotation vector's roll averaged over its last 10 readings), read at
/// 30 Hz while the level shows; redrawn only when the line moved more than 0.1° (no redraw at the display's rate with the phone
/// still). Level keeps its hysteresis here so the soft pulse fires once on the way in.
@MainActor
@Observable
final class HorizonMotion {
    private(set) var roll: Double = 0
    private(set) var level = false
    @ObservationIgnored var turn: Double = DeviceTurn.shared.degrees
    @ObservationIgnored private let manager = CMMotionManager()
    @ObservationIgnored private var gx: [Double] = [], gy: [Double] = []

    func start() {
        guard manager.isDeviceMotionAvailable, !manager.isDeviceMotionActive else { return }
        manager.deviceMotionUpdateInterval = 1.0 / 30
        manager.startDeviceMotionUpdates(to: .main) { [weak self] data, _ in
            guard let g = data?.gravity else { return }
            MainActor.assumeIsolated { self?.read(g.x, g.y) }
        }
    }

    func stop() { manager.stopDeviceMotionUpdates() }

    private func read(_ x: Double, _ y: Double) {
        gx.append(x); gy.append(y)
        if gx.count > 10 { gx.removeFirst(); gy.removeFirst() }
        let ax = gx.reduce(0, +) / Double(gx.count), ay = gy.reduce(0, +) / Double(gy.count)
        let r = atan2(ax, -ay) * 180 / .pi
        guard abs(r - roll) > 0.1 else { return }
        let now = HorizonLevel.levelNow(level, HorizonLevel.tilt(r, turn))
        if now && !level { Haptics.soft() }
        roll = r
        if now != level { level = now }
    }
}
