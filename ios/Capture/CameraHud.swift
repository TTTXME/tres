import AVFoundation
import SwiftUI

/// «Информация на экране» (pref_show_afdata_key: 0 off, 1 HUD, 2 HUD + histogram, 3 AF / AE boxes and frame data) - Android's
/// CameraFragment.updateViewfinderHud / SurfaceViewOverViewfinder. Android reads the preview's CaptureResult; the iPhone reads
/// the lens (AVCaptureDevice) by key-value observing: exposure time, ISO, the lens position, the focus / exposure / white balance
/// modes and whether they are adjusting, the EV bias and the metering offset, the white balance gains, the points of interest.
/// Updates at most every 150 ms (HUD_UPDATE_INTERVAL_MS); the lens is read whatever the mode (the Pro panel's chips show the metered
/// values). Mode 2's live RGB histogram comes from the viewfinder's frames (App/Camera/SubjectTracking FrameAnalysis), the
/// «[TRIPOD]» row from the gyroscope (TripodDetector). Not on the iPhone: the OIS row (no public OIS state), the frame number, the
/// LMT flags of IsoExpoSelector.
struct CameraHudSample: Sendable {
    var exposure: Double = 0
    var iso: Float = 0
    var aperture: Float = 0
    var focal35 = 0
    var focusMode = AVCaptureDevice.FocusMode.continuousAutoFocus
    var adjustingFocus = false
    var lensPosition: Float = 0
    var exposureMode = AVCaptureDevice.ExposureMode.continuousAutoExposure
    var adjustingExposure = false
    var bias: Float = 0
    var offset: Float = 0
    var whiteBalanceMode = AVCaptureDevice.WhiteBalanceMode.continuousAutoWhiteBalance
    var gains = AVCaptureDevice.WhiteBalanceGains(redGain: 1, greenGain: 1, blueGain: 1)
    var kelvin: Float = 0
    var torch = false
    var zoom: Double = 1
    var focusPoint = CGPoint(x: 0.5, y: 0.5)
    var exposurePoint = CGPoint(x: 0.5, y: 0.5)
    /// The points on the viewfinder (the preview layer's coordinates), nil when not known
    var focusOnScreen: CGPoint?
    var exposureOnScreen: CGPoint?

    /// ExposureIndex.sec2string + "s": «1/120s», «1.50s»
    var exposureText: String {
        guard exposure > 0 else { return "-" }
        if exposure > 1 { return String(format: "%.2fs", exposure) }
        return "1/" + String(format: "%.0f", 1 / exposure) + "s"
    }

    var isoText: String { "ISO \(Int(iso.rounded()))" }

    var lensText: String { "\(focal35)mm · f/" + String(format: "%.1f", aperture) }

    /// MF with the lens position (the iPhone gives no distance), else AF-S / AF-C with SCAN while it moves, LOCK when held
    var focusText: String {
        if focusMode == .locked && !adjustingFocus { return "MF · " + String(format: "%.2f", lensPosition) }
        let prefix = focusMode == .autoFocus ? "AF-S" : "AF-C"
        return prefix + (adjustingFocus ? " · SCAN" : " · LOCK")
    }

    /// calculateWhitebalanceString: AWB / MWB · the temperature rounded to 100 K, 2000..10000
    var whiteBalanceText: String {
        let prefix = whiteBalanceMode == .locked ? "MWB" : "AWB"
        guard kelvin > 0 else { return prefix }
        let k = min(10000, max(2000, Int((kelvin / 100).rounded()) * 100))
        return prefix + " · \(k)K"
    }

    /// Mode 3: the frame data as Android lists it, under the iPhone's names
    var debugLines: [String] {
        func name(_ f: AVCaptureDevice.FocusMode) -> String {
            switch f { case .locked: return "LOCKED"; case .autoFocus: return "AUTO"; case .continuousAutoFocus: return "CONTINUOUS"; @unknown default: return "?" }
        }
        func name(_ e: AVCaptureDevice.ExposureMode) -> String {
            switch e {
            case .locked: return "LOCKED"; case .autoExpose: return "AUTO"; case .continuousAutoExposure: return "CONTINUOUS"
            case .custom: return "CUSTOM"; @unknown default: return "?"
            }
        }
        var lines = [
            "AF_MODE: " + name(focusMode),
            "AF_STATE: " + (adjustingFocus ? "SCANNING" : "IDLE"),
            "AE_MODE: " + name(exposureMode),
            "AE_STATE: " + (adjustingExposure ? "SEARCHING" : "CONVERGED"),
            "FLASH_MODE: " + (torch ? "TORCH" : "OFF"),
            "LENS_POSITION: " + String(format: "%.3f", lensPosition),
            "EXPOSURE_TIME: " + exposureText,
            "ISO: \(Int(iso.rounded()))",
            "EV_BIAS: " + String(format: "%+.2f", bias),
            "EV_OFFSET: " + String(format: "%+.2f", offset),
            "ZOOM: " + String(format: "%.2f", zoom),
            "White Point: " + String(format: "%.3f %.3f %.3f", gains.redGain, gains.greenGain, gains.blueGain),
            "AF_RECT: " + String(format: "(%.3f, %.3f)", focusPoint.x, focusPoint.y),
        ]
        if let p = focusOnScreen { lines.append("AF_RECT(px): " + String(format: "(%.0f, %.0f)", p.x, p.y)) }
        lines.append("AE_RECT: " + String(format: "(%.3f, %.3f)", exposurePoint.x, exposurePoint.y))
        if let p = exposureOnScreen { lines.append("AE_RECT(px): " + String(format: "(%.0f, %.0f)", p.x, p.y)) }
        return lines
    }
}

/// What the viewfinder's HUD shows now (main actor)
@MainActor
@Observable
final class CameraHud {
    static let shared = CameraHud()
    private(set) var sample: CameraHudSample?
    /// Mode 2's histogram: 64 bins of R, G and B (square-rooted) and their peak
    private(set) var histogram: [[Int]]?
    private(set) var histogramPeak = 1

    func setHistogram(_ channels: [[Int]], peak: Int) {
        histogram = channels
        histogramPeak = max(1, peak)
    }

    func update(_ sample: CameraHudSample, layer: AVCaptureVideoPreviewLayer?) {
        var s = sample
        if let layer {
            s.focusOnScreen = layer.layerPointConverted(fromCaptureDevicePoint: sample.focusPoint)
            s.exposureOnScreen = layer.layerPointConverted(fromCaptureDevicePoint: sample.exposurePoint)
        }
        self.sample = s
    }

    func clear() { sample = nil; histogram = nil }
}

/// The key-value observer of the open lens (CaptureService gives it each lens it opens, with the preview layer)
final class DeviceHudMonitor: @unchecked Sendable {
    private let queue = DispatchQueue(label: "scamera.hud", qos: .utility)
    private var observations: [NSKeyValueObservation] = []
    private weak var device: AVCaptureDevice?
    private weak var layer: AVCaptureVideoPreviewLayer?
    private var scheduled = false
    private var last = Date.distantPast
    static let interval = 0.15

    static var mode: Int { min(max(Int(StoredPrefs.string("pref_show_afdata_key", "0")) ?? 0, 0), 3) }

    func observe(_ device: AVCaptureDevice, layer: AVCaptureVideoPreviewLayer?) {
        queue.async { [self] in
            observations.forEach { $0.invalidate() }
            self.device = device
            self.layer = layer
            let poke: @Sendable () -> Void = { [weak self] in self?.poke() }
            observations = [
                device.observe(\.iso, options: []) { _, _ in poke() },
                device.observe(\.exposureDuration, options: []) { _, _ in poke() },
                device.observe(\.lensPosition, options: []) { _, _ in poke() },
                device.observe(\.isAdjustingFocus, options: []) { _, _ in poke() },
                device.observe(\.isAdjustingExposure, options: []) { _, _ in poke() },
                device.observe(\.exposureTargetOffset, options: []) { _, _ in poke() },
                device.observe(\.exposureTargetBias, options: []) { _, _ in poke() },
                device.observe(\.focusMode, options: []) { _, _ in poke() },
                device.observe(\.exposureMode, options: []) { _, _ in poke() },
                device.observe(\.whiteBalanceMode, options: []) { _, _ in poke() },
                device.observe(\.deviceWhiteBalanceGains, options: []) { _, _ in poke() },
                device.observe(\.videoZoomFactor, options: []) { _, _ in poke() },
                device.observe(\.focusPointOfInterest, options: []) { _, _ in poke() },
                device.observe(\.exposurePointOfInterest, options: []) { _, _ in poke() },
                device.observe(\.torchMode, options: []) { _, _ in poke() },
            ]
            poke()
        }
    }

    /// A change: published after the interval at most once
    private func poke() {
        queue.async { [self] in
            guard !scheduled else { return }
            scheduled = true
            let wait = max(0, Self.interval - Date().timeIntervalSince(last))
            queue.asyncAfter(deadline: .now() + wait) { [self] in
                scheduled = false
                last = Date()
                publish()
            }
        }
    }

    private func publish() {
        guard let d = device else {
            Task { @MainActor in CameraHud.shared.clear() }
            return
        }
        var s = CameraHudSample()
        s.exposure = d.exposureDuration.seconds.isFinite ? d.exposureDuration.seconds : 0
        s.iso = d.iso
        s.aperture = d.lensAperture
        s.zoom = Double(d.videoZoomFactor)
        s.focal35 = ShotExif.focal35(horizontalFOV: Double(d.activeFormat.videoFieldOfView), aspect: 4.0 / 3.0, zoom: s.zoom)
        s.focusMode = d.focusMode
        s.adjustingFocus = d.isAdjustingFocus
        s.lensPosition = d.lensPosition
        s.exposureMode = d.exposureMode
        s.adjustingExposure = d.isAdjustingExposure
        s.bias = d.exposureTargetBias
        s.offset = d.exposureTargetOffset
        s.whiteBalanceMode = d.whiteBalanceMode
        var g = d.deviceWhiteBalanceGains
        s.gains = g
        let top = max(1, d.maxWhiteBalanceGain)
        g.redGain = min(max(g.redGain, 1), top)
        g.greenGain = min(max(g.greenGain, 1), top)
        g.blueGain = min(max(g.blueGain, 1), top)
        s.kelvin = d.temperatureAndTintValues(for: g).temperature
        s.torch = d.hasTorch && d.torchMode == .on
        s.focusPoint = d.focusPointOfInterest
        s.exposurePoint = d.exposurePointOfInterest
        let layer = self.layer
        let sample = s
        Task { @MainActor in CameraHud.shared.update(sample, layer: layer) }
    }
}

/// SurfaceViewOverViewfinder.drawHUD (13 pt semibold white with a shadow, 16 pt lines, 14 pt from the left, 12 pt from the top:
/// shutter, ISO, focal length and aperture, focus, white balance) and, in mode 3, drawAFRect / drawAERect / drawAFDebugText (the
/// focus point's box green, the metering point's yellow; the iPhone gives points, so the boxes are the tap ring's size).
struct CameraHudView: View {
    let mode: Int
    let sample: CameraHudSample?

    var body: some View {
        GeometryReader { g in
            if let s = sample {
                ZStack(alignment: .topLeading) {
                    if mode == 1 || mode == 2 {
                        VStack(alignment: .leading, spacing: 0) {
                            row(s.exposureText)
                            row(s.isoText)
                            row(s.lensText)
                            row(s.focusText)
                            row(s.whiteBalanceText)
                            if TripodDetector.shared.onTripod { row("[TRIPOD]") }
                        }
                        .rotationEffect(.degrees(DeviceTurn.shared.degrees))
                        .padding(.leading, 14).padding(.top, 12)
                        if mode == 2, let bins = CameraHud.shared.histogram {
                            HudHistogram(bins: bins, peak: CameraHud.shared.histogramPeak)
                                .rotationEffect(.degrees(DeviceTurn.shared.degrees))
                                .position(x: g.size.width - 14 - 36, y: 12 + 18)
                        }
                    } else if mode == 3 {
                        box(s.focusOnScreen, color: .green, in: g.size)
                        box(s.exposureOnScreen, color: .yellow, in: g.size)
                        VStack(alignment: .leading, spacing: 1) {
                            ForEach(Array(s.debugLines.enumerated()), id: \.offset) { _, line in
                                Text(line).font(.system(size: 10, design: .monospaced))
                                    .foregroundStyle(line.hasPrefix("AF_RECT") ? Color.green : line.hasPrefix("AE_RECT") ? Color.yellow : Color.white)
                                    .shadow(color: .black, radius: 1, x: 0.5, y: 0.5)
                            }
                        }
                        .padding(.leading, 14).padding(.top, 50)
                    }
                }
                .frame(width: g.size.width, height: g.size.height, alignment: .topLeading)
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private func row(_ text: String) -> some View {
        Text(text).font(.system(size: 13, weight: .semibold)).foregroundStyle(.white)
            .shadow(color: .black, radius: 1.3, x: 0.4, y: 0.4)
            .lineLimit(1).fixedSize()
            .frame(height: 16, alignment: .leading)
    }

    @ViewBuilder private func box(_ point: CGPoint?, color: Color, in size: CGSize) -> some View {
        if let p = point, p.x.isFinite, p.y.isFinite, p.x >= 0, p.y >= 0, p.x <= size.width, p.y <= size.height {
            Rectangle().stroke(color, lineWidth: 1.5).frame(width: 70, height: 70).position(p)
        }
    }
}

/// SurfaceViewOverViewfinder.drawHistogram: a 72 x 36 dark box (black 40 %, a white 35 % frame) with thirds, the R, G and B curves
/// added over each other
struct HudHistogram: View {
    let bins: [[Int]]
    let peak: Int
    private static let colours: [Color] = [Color(red: 1, green: 0x1A / 255, blue: 0x1A / 255), Color(red: 0x1A / 255, green: 0xD4 / 255, blue: 0x2A / 255),
                                           Color(red: 0x2A / 255, green: 0x55 / 255, blue: 1)]

    var body: some View {
        Canvas { context, size in
            let w = size.width, h = size.height
            let frame = Path(CGRect(origin: .zero, size: size))
            context.fill(frame, with: .color(.black.opacity(0.4)))
            var thirds = Path()
            thirds.move(to: CGPoint(x: w / 3, y: 0)); thirds.addLine(to: CGPoint(x: w / 3, y: h))
            thirds.move(to: CGPoint(x: 2 * w / 3, y: 0)); thirds.addLine(to: CGPoint(x: 2 * w / 3, y: h))
            context.stroke(frame, with: .color(.white.opacity(0.35)), lineWidth: 1)
            context.stroke(thirds, with: .color(.white.opacity(0.35)), lineWidth: 1)
            var layer = context
            layer.blendMode = .plusLighter
            for c in 0..<min(3, bins.count) {
                let count = bins[c].count
                guard count > 0 else { continue }
                let step = w / CGFloat(count + 1), scale = h / CGFloat(max(1, peak))
                var p = Path()
                p.move(to: CGPoint(x: 0, y: h))
                for (i, v) in bins[c].enumerated() { p.addLine(to: CGPoint(x: CGFloat(i) * step, y: h - min(CGFloat(v) * scale, h))) }
                p.addLine(to: CGPoint(x: CGFloat(count) * step, y: h))
                p.closeSubpath()
                layer.fill(p, with: .color(Self.colours[c].opacity(0.87)))
            }
        }
        .frame(width: 72, height: 36)
    }
}
