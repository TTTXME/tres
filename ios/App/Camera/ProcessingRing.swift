import SwiftUI

/// ProcessingRing (P158): the shot's processing as an accent line running clockwise round the gallery card's edge from the top
/// centre over a faint track. It moves on by time (an ease-out over the last shot's processing time) and never lags behind the
/// pipeline's milestones (`stage`); it stops short of the end (94 %) until the shot is done, then closes the ring and fades. A shot
/// queued behind the running one starts the ring again when the first is done. The iPhone's pipeline reports no merge heartbeat
/// (P220's measured progress), so the ring runs on time and milestones only.
@MainActor
@Observable
final class ProcessingRingModel {
    static let shared = ProcessingRingModel()
    static let timeCap = 0.94
    private(set) var running = false
    @ObservationIgnored private var estimate: TimeInterval = 3.5
    @ObservationIgnored private(set) var shown = 0.0
    @ObservationIgnored private var stage = 0.0
    @ObservationIgnored private(set) var fade = 1.0
    @ObservationIgnored private var started = Date()
    @ObservationIgnored private var finishing = false
    @ObservationIgnored private var jobs = 0
    @ObservationIgnored private var ended = false

    func start() {
        jobs += 1
        if running { return }   // the last shot's ring closes first, then starts over for this one
        restart()
    }

    /// The pipeline reached `fraction` (0..1): the ring is at least there
    func setStage(_ fraction: Double) {
        guard running, !finishing else { return }
        stage = max(stage, max(0, min(Self.timeCap, fraction)))
    }

    /// A shot's processing is done (or failed): the ring closes and fades, or starts over for the next queued shot
    func finish() {
        if jobs > 0 { jobs -= 1 }
        guard running, !finishing else { return }
        estimate = max(0.8, min(20, Date().timeIntervalSince(started)))
        finishing = true
    }

    private func restart() {
        if !running { running = true }
        ended = false
        finishing = false
        shown = 0
        stage = 0
        fade = 1
        started = Date()
    }

    /// byTime: about 0.85 of the ring at the last shot's time, at most timeCap
    static func byTime(_ elapsed: TimeInterval, _ estimate: TimeInterval) -> Double {
        timeCap * (1 - exp(-2.2 * max(0, elapsed) / max(0.001, estimate)))
    }

    /// One display frame: the eased step towards the target (never back), then the close and the fade
    func step(_ now: Date) {
        guard running else { return }
        let target = finishing ? 1 : max(stage, Self.byTime(now.timeIntervalSince(started), estimate))
        if target > shown { shown += max((target - shown) * 0.16, finishing ? 0.02 : 0) }
        if finishing && shown > target { shown = target }
        if finishing && shown >= 0.999 { fade -= 0.08 }
        if finishing && fade <= 0 && !ended {
            if jobs > 0 {
                restart()
            } else {
                // not while the frame draws: the ring's view goes on the next turn of the main loop
                ended = true
                Task { [weak self] in
                    if let self, self.ended { self.running = false }
                }
            }
        }
    }
}

/// The ring drawn round a rounded square of `radius` (BottomChrome.SIDE_RADIUS_DP 18), a 2.6 pt line in the accent over the accent
/// at 22 %.
struct ProcessingRingView: View {
    let radius: CGFloat
    private let model = ProcessingRingModel.shared

    var body: some View {
        if model.running {
            TimelineView(.animation) { timeline in
                Canvas { context, size in
                    model.step(timeline.date)
                    let stroke: CGFloat = 2.6
                    let path = Self.outline(CGRect(origin: .zero, size: size).insetBy(dx: stroke / 2, dy: stroke / 2), radius: radius - stroke / 2)
                    let style = StrokeStyle(lineWidth: stroke, lineCap: .round)
                    context.stroke(path, with: .color(CameraStyle.accent.opacity(0.22 * max(0, model.fade))), style: style)
                    context.stroke(path.trimmedPath(from: 0, to: model.shown), with: .color(CameraStyle.accent.opacity(max(0, model.fade))), style: style)
                }
            }
            .allowsHitTesting(false)
            .accessibilityHidden(true)
        }
    }

    /// From the top centre clockwise round a rounded rectangle
    static func outline(_ r: CGRect, radius: CGFloat) -> Path {
        let rad = min(radius, min(r.width, r.height) / 2)
        var p = Path()
        p.move(to: CGPoint(x: r.midX, y: r.minY))
        p.addLine(to: CGPoint(x: r.maxX - rad, y: r.minY))
        p.addArc(center: CGPoint(x: r.maxX - rad, y: r.minY + rad), radius: rad, startAngle: .degrees(-90), endAngle: .degrees(0), clockwise: false)
        p.addLine(to: CGPoint(x: r.maxX, y: r.maxY - rad))
        p.addArc(center: CGPoint(x: r.maxX - rad, y: r.maxY - rad), radius: rad, startAngle: .degrees(0), endAngle: .degrees(90), clockwise: false)
        p.addLine(to: CGPoint(x: r.minX + rad, y: r.maxY))
        p.addArc(center: CGPoint(x: r.minX + rad, y: r.maxY - rad), radius: rad, startAngle: .degrees(90), endAngle: .degrees(180), clockwise: false)
        p.addLine(to: CGPoint(x: r.minX, y: r.minY + rad))
        p.addArc(center: CGPoint(x: r.minX + rad, y: r.minY + rad), radius: rad, startAngle: .degrees(180), endAngle: .degrees(270), clockwise: false)
        p.closeSubpath()
        return p
    }
}
