import SwiftUI
import UIKit

/// P360 EditMotion: the editor's motion - one easing family (Material's emphasized decelerate for things arriving, standard for
/// things moving, the exit curve), short durations; Reduce Motion turns it off as the system's animation scale does on Android.
enum EditMotion {
    static let short = 0.12, medium = 0.24, long = 0.36

    static func enter(_ duration: Double = medium) -> Animation { .timingCurve(0.05, 0.7, 0.1, 1, duration: duration) }
    static func move(_ duration: Double = medium) -> Animation { .timingCurve(0.2, 0, 0, 1, duration: duration) }
    static func exit(_ duration: Double = short + 0.04) -> Animation { .timingCurve(0.3, 0, 0.8, 0.15, duration: duration) }
    /// OvershootInterpolator(2.5) over LONG: the new tab's icon from 0.8
    static let bounce = Animation.spring(response: long, dampingFraction: 0.45)

    static var on: Bool { !UIAccessibility.isReduceMotionEnabled }

    /// EditMotion.show: fades in from `dy` below (out to it), MEDIUM in / SHORT + 40 ms out.
    static func show(dy: CGFloat) -> AnyTransition {
        .asymmetric(insertion: .offset(y: dy).combined(with: .opacity).animation(enter()),
                    removal: .offset(y: dy).combined(with: .opacity).animation(exit()))
    }

    /// PhotoEditorFragment.showPanel: the old panel slips out against `direction`, the new one slides in from it (28pt).
    static func panel(_ direction: Int) -> AnyTransition {
        let shift = 28 * CGFloat(direction)
        return .asymmetric(insertion: .offset(x: shift).combined(with: .opacity).animation(enter()),
                           removal: .offset(x: -shift * 0.6).combined(with: .opacity).animation(exit(short - 0.03)))
    }
}

/// EditMotion.stagger's clock: the rows of a panel come in one after another (25 ms apart, at most 12), counted from the panel's
/// arrival (read and moved on the main thread only).
final class StaggerClock: @unchecked Sendable {
    var next = 0
    func reset() { next = 0 }
}

private struct StaggerKey: EnvironmentKey {
    static let defaultValue: StaggerClock? = nil
}

extension EnvironmentValues {
    var editStagger: StaggerClock? {
        get { self[StaggerKey.self] }
        set { self[StaggerKey.self] = newValue }
    }
}

/// A row of a panel: a short rise (10pt) and fade, delayed by its place in the panel (EditMotion.stagger).
struct StaggerIn: ViewModifier {
    @Environment(\.editStagger) private var clock
    @State private var shown = false

    func body(content: Content) -> some View {
        let visible = clock == nil || shown
        content
            .opacity(visible ? 1 : 0)
            .offset(y: visible ? 0 : 10)
            .onAppear {
                guard let clock, !shown else { return }
                guard EditMotion.on else {
                    shown = true
                    return
                }
                let i = min(clock.next, 11)
                clock.next += 1
                withAnimation(EditMotion.enter().delay(0.025 * Double(i))) { shown = true }
            }
    }
}

/// EditMotion.enter: the editor's arrival - a cover over the picture fades away (LONG + 80 ms), the bars rise in (24pt, LONG,
/// 60 ms then 40 ms apart).
struct EditorArrival: ViewModifier {
    let arrived: Bool
    let order: Int
    func body(content: Content) -> some View {
        content
            .opacity(arrived ? 1 : 0)
            .offset(y: arrived ? 0 : 24)
            .animation(EditMotion.on ? EditMotion.enter(EditMotion.long).delay(0.06 + 0.04 * Double(order)) : nil, value: arrived)
    }
}
