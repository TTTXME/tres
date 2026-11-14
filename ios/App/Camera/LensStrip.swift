import SwiftUI

/// The lens strip (AuxButtonsLayout + LensButton, P185 / P583 / P589): one capsule (#1C1C1E with the faint hairline, 3 pt inside)
/// of 48 x 44 pt stops 2 pt apart in zoom order; the active stop a #3A3A3C pill with its label in the accent (15 pt semibold, «1×»
/// or the live zoom «2,3×»), the others white 14 pt without the sign («0,5», «2», «5»), decimal commas in Russian. A tap selects a
/// lens (a key tick, at most every 0.5 s; the pill glides over); a horizontal drag zooms continuously (x2 per 70 pt, eased in log
/// every frame, a tick when the module changes). The stops shrink together (to half at most) when they would not fit; only the
/// labels turn with the phone (P32). One lens is no choice: the strip keeps its place and shows nothing.
struct LensStrip: View {
    let camera: CameraModel
    var onZoomChange: (Double) -> Void
    @Namespace private var pill
    @State private var lastSwitch = Date.distantPast
    @State private var drag = StripZoomDrag()
    @State private var lastX: CGFloat?

    static let height: CGFloat = 44, width: CGFloat = 48, corner: CGFloat = 22, minFit: CGFloat = 0.5

    var body: some View {
        let lenses = camera.sideModules
        let _ = (camera.activeModuleID, camera.zoom)
        GeometryReader { g in
            let need = CGFloat(lenses.count) * (Self.width + 2) + 6
            let fit = need > g.size.width ? max(Self.minFit, (g.size.width - 2) / need) : 1
            HStack(spacing: 2 * fit) {
                ForEach(lenses) { lens in tile(lens, fit: fit) }
            }
            .animation(CameraMotion.glide, value: camera.activeModuleID)
            .padding(3)
            .background(Capsule().fill(CameraStyle.control))
            .overlay(Capsule().stroke(CameraStyle.hairline, lineWidth: 0.5))
            .contentShape(Capsule())
            .gesture(DragGesture(minimumDistance: 8)
                .onChanged { d in
                    guard abs(d.translation.width) > abs(d.translation.height) || lastX != nil else { return }
                    let dx = d.location.x - (lastX ?? d.location.x)
                    lastX = d.location.x
                    if dx != 0 { drag.drag(dx, camera: camera, onZoom: onZoomChange) }
                }
                .onEnded { _ in lastX = nil })
            .frame(width: g.size.width, height: g.size.height)
        }
        .frame(height: Self.height + 6)
        .opacity(lenses.count > 1 ? 1 : 0)
        .allowsHitTesting(lenses.count > 1 && camera.activity == .idle)
    }

    private func tile(_ lens: LensModule, fit: CGFloat) -> some View {
        let active = lens.id == camera.activeModuleID
        let live = active && abs(camera.zoom - lens.zoom) >= 0.05
        let label = live ? CameraStyle.zoomText(camera.zoom) + "×" : Self.display(lens.label, sign: active)
        return ZStack {
            if active {
                RoundedRectangle(cornerRadius: Self.corner * fit).fill(CameraStyle.chosen)
                    .matchedGeometryEffect(id: "pill", in: pill)
            }
            Text(label)
                .font(.system(size: (active ? 15 : 14) * fit, weight: .semibold))
                .monospacedDigit()
                .foregroundStyle(active ? CameraStyle.accent : Color.white)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .padding(.horizontal, 4 * fit)
                .turnsWithPhone()
        }
        .frame(minWidth: Self.width * fit, minHeight: Self.height, maxHeight: Self.height)
        .contentShape(Rectangle())
        .onTapGesture { tap(lens) }
        .accessibilityLabel(L("lens.prefix") + Self.display(lens.label, sign: true))
        .accessibilityAddTraits(active ? [.isButton, .isSelected] : .isButton)
    }

    private func tap(_ lens: LensModule) {
        guard lens.id != camera.activeModuleID, Date().timeIntervalSince(lastSwitch) >= 0.5 else { return }
        lastSwitch = Date()
        Haptics.tick()
        Task { await camera.select(lens) }
    }

    /// The decimal comma in Russian; the sign only on the active lens
    static func display(_ label: String, sign: Bool) -> String {
        var s = CameraStyle.decimal(label.replacingOccurrences(of: " F", with: ""))
        if !sign, s.hasSuffix("×") { s.removeLast() }
        return s
    }
}

/// CameraFragment.dragZoom: the drag sets a target zoom on a log scale (one stop per 70 pt, right zooms in); the shown zoom moves
/// 22 % of the way (in log) to it every display frame; crossing a module's mark switches the module (CLOCK_TICK).
@MainActor
final class StripZoomDrag {
    static let stopPt: CGFloat = 70, ease = 0.22
    private var target = 1.0, shown = 1.0
    private var easing: Task<Void, Never>?

    func drag(_ dx: CGFloat, camera: CameraModel, onZoom: @escaping (Double) -> Void) {
        if easing == nil { target = camera.zoom; shown = camera.zoom }
        let range = camera.zoomRange
        target = min(max(target * pow(2, Double(dx / Self.stopPt)), range.lowerBound), range.upperBound)
        guard easing == nil else { return }
        easing = Task { @MainActor [weak self] in
            while let self, !Task.isCancelled {
                let a = log(self.shown), b = log(self.target)
                self.shown = abs(b - a) < 0.002 ? self.target : exp(a + (b - a) * Self.ease)
                let before = camera.activeModuleID
                await camera.zoom(to: self.shown)
                if before != camera.activeModuleID { Haptics.tick() }
                onZoom(camera.zoom)
                if abs(log(self.shown) - log(self.target)) <= 0.0005 { break }
                try? await Task.sleep(nanoseconds: 16_000_000)
            }
            self?.easing = nil
        }
    }
}

/// ZoomReadoutView (SIMPLE_UI §1, the concept's .zhud): while the zoom changes, a 26 pt pill in the message band - the value on a
/// tonal accent chip («2,5×», 12 pt bold) and a 150 pt mini log scale from the lowest to the highest zoom (third-stop ticks fading
/// towards the ends, brighter marks at the modules, the accent needle ringed in the glass colour). It fades out 1.3 s after the
/// last change and takes the message band's place meanwhile.
@MainActor
@Observable
final class ZoomReadoutModel {
    static let shared = ZoomReadoutModel()
    static let hideAfter = 1.3
    private(set) var visible = false
    private(set) var zoom = 1.0
    @ObservationIgnored private var hide: Task<Void, Never>?

    func show(_ zoom: Double) {
        self.zoom = zoom
        if !visible { withAnimation(.easeOut(duration: 0.12)) { visible = true } }
        hide?.cancel()
        hide = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.hideAfter * 1e9))
            guard !Task.isCancelled else { return }
            withAnimation(.easeIn(duration: 0.2)) { self?.visible = false }
        }
    }
}

struct ZoomReadout: View {
    let range: ClosedRange<Double>
    let modules: [Double]
    let zoom: Double
    static let height: CGFloat = 26, scale: CGFloat = 150, pad: CGFloat = 3, gap: CGFloat = 10, value: CGFloat = 40, end: CGFloat = 12

    var body: some View {
        Canvas { context, size in
            let h = size.height
            let chip = CGRect(x: Self.pad, y: Self.pad, width: Self.value, height: h - 2 * Self.pad)
            context.fill(Path(roundedRect: chip, cornerRadius: chip.height / 2), with: .color(CameraStyle.accent.opacity(0.18)))
            context.draw(Text(Self.label(zoom)).font(.app(12, 700)).monospacedDigit().foregroundColor(CameraStyle.accent),
                         at: CGPoint(x: chip.midX, y: chip.midY))
            let x0 = Self.pad + Self.value + Self.gap, cy = h / 2
            var z = range.lowerBound
            while z <= range.upperBound * 1.0001 {
                let t = pos(z)
                let units = min(t, 1 - t) * Self.scale / Self.end
                let alpha = 0.25 + 0.75 * max(0, min(1, units))
                let x = x0 + t * Self.scale
                context.fill(Path(CGRect(x: x - 0.5, y: cy - 2.5, width: 1, height: 5)),
                             with: .color(Color(red: 0x8B / 255, green: 0x90 / 255, blue: 0x96 / 255).opacity(alpha)))
                z *= pow(2, 1.0 / 3)
            }
            for m in modules {
                let x = x0 + pos(m) * Self.scale
                context.fill(Path(roundedRect: CGRect(x: x - 0.6, y: cy - 5, width: 1.2, height: 10), cornerRadius: 1), with: .color(CameraStyle.text))
            }
            let nx = x0 + pos(zoom) * Self.scale
            context.fill(Path(roundedRect: CGRect(x: nx - 3.5, y: cy - 9, width: 7, height: 18), cornerRadius: 3.5), with: .color(CameraStyle.control))
            context.fill(Path(roundedRect: CGRect(x: nx - 2, y: cy - 7.5, width: 4, height: 15), cornerRadius: 2), with: .color(CameraStyle.accent))
        }
        .frame(width: Self.pad * 2 + Self.value + Self.gap + Self.scale + 6, height: Self.height)
        .background(Capsule().fill(CameraStyle.control))
        .overlay(Capsule().stroke(CameraStyle.hairline, lineWidth: 0.5))
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    /// «2,5×»
    static func label(_ zoom: Double) -> String {
        let r = (zoom * 10).rounded() / 10
        let s = abs(r - r.rounded()) < 1e-3 ? String(Int(r.rounded())) : String(format: "%.1f", r)
        return CameraStyle.decimal(s) + "×"
    }

    /// Position of z on the scale, 0..1 (log)
    private func pos(_ z: Double) -> CGFloat {
        let lo = log(range.lowerBound), hi = log(max(range.upperBound, range.lowerBound * 1.0001))
        return CGFloat(max(0, min(1, (log(max(range.lowerBound, min(range.upperBound, z))) - lo) / (hi - lo))))
    }
}
