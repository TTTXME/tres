import AVFoundation
import CoreMotion
import SwiftUI
import Vision

/// The analysis of the viewfinder's frames (the live look's AVCaptureVideoDataOutput, Viewfinder/ViewfinderRenderer): the HUD's live
/// RGB histogram (ViewfinderHud.binHistogram: 64 bins a channel over every other sampled pixel, square-rooted, at most every 150 ms)
/// and the subject tracking of «Следить за объектом» (SubjectFocus: Android's ZNCC PatchTracker on quarter-size frames; here
/// Vision's VNTrackObjectRequest, at most 15 frames a second, the focus and exposure point following at most 10 times a second).
/// While either is wanted the output's frames run even with no look to draw (the plain preview layer still shows the picture).
final class FrameAnalysis: @unchecked Sendable {
    static let shared = FrameAnalysis()
    static let bins = 64
    private let lock = NSLock()
    private var histogramOn = false
    private var lastHistogram = Date.distantPast
    // tracking (on the renderer's queue, the requests under the lock)
    private var pendingStart: (point: CGPoint, view: CGSize)?
    private var request: VNTrackObjectRequest?
    private var handler = VNSequenceRequestHandler()
    private var lastTrack = Date.distantPast
    private var lastFocus = Date.distantPast
    private var lowFrames = 0
    private var viewSize = CGSize.zero
    /// Told on the main actor: the tracked box (0..1 of the view) and its centre in view points; nil when the subject is lost
    var onTrack: (@MainActor (CGRect?, CGPoint?) -> Void)?

    var wanted: Bool {
        lock.lock(); defer { lock.unlock() }
        return histogramOn || request != nil || pendingStart != nil
    }

    var tracking: Bool {
        lock.lock(); defer { lock.unlock() }
        return request != nil || pendingStart != nil
    }

    func setHistogram(_ on: Bool) {
        lock.lock()
        let changed = histogramOn != on
        histogramOn = on
        lock.unlock()
        if changed { ViewfinderRenderer.shared.analysisChanged() }
    }

    /// A long press (or a tap, by «Следить за объектом») at `point` of a viewfinder `view` wide: the subject there is tracked
    func startTracking(at point: CGPoint, view: CGSize) {
        lock.lock()
        pendingStart = (point, view)
        request = nil
        lowFrames = 0
        lock.unlock()
        ViewfinderRenderer.shared.analysisChanged()
        SessionLog.i("Subject", "tracking starts at \(Int(point.x)),\(Int(point.y))")
    }

    func stopTracking() {
        lock.lock()
        let was = request != nil || pendingStart != nil
        request = nil
        pendingStart = nil
        lock.unlock()
        if was {
            ViewfinderRenderer.shared.analysisChanged()
            let report = onTrack
            Task { @MainActor in report?(nil, nil) }
        }
    }

    func setViewSize(_ size: CGSize) {
        lock.lock()
        viewSize = size
        lock.unlock()
    }

    /// One frame of the output (the renderer's queue): upright as the preview, mirrored for the front lens
    func frame(_ pixels: CVPixelBuffer) {
        lock.lock()
        let wantsHistogram = histogramOn && Date().timeIntervalSince(lastHistogram) >= 0.15
        if wantsHistogram { lastHistogram = Date() }
        let start = pendingStart
        let tracking = request != nil || start != nil
        let view = viewSize
        lock.unlock()
        if wantsHistogram { histogram(pixels) }
        if tracking { track(pixels, start: start, view: view) }
    }

    /// The aspect-fit rect of a w x h frame in the view (the preview layer's .resizeAspect)
    private static func content(_ w: Int, _ h: Int, in view: CGSize) -> CGRect {
        guard w > 0, h > 0, view.width > 0, view.height > 0 else { return CGRect(origin: .zero, size: view) }
        let s = min(view.width / CGFloat(w), view.height / CGFloat(h))
        let cw = CGFloat(w) * s, ch = CGFloat(h) * s
        return CGRect(x: (view.width - cw) / 2, y: (view.height - ch) / 2, width: cw, height: ch)
    }

    private func track(_ pixels: CVPixelBuffer, start: (point: CGPoint, view: CGSize)?, view: CGSize) {
        guard Date().timeIntervalSince(lastTrack) >= 1.0 / 15 || start != nil else { return }
        lastTrack = Date()
        let w = CVPixelBufferGetWidth(pixels), h = CVPixelBufferGetHeight(pixels)
        if let start {
            let rect = Self.content(w, h, in: start.view)
            let u = (start.point.x - rect.minX) / max(1, rect.width), v = (start.point.y - rect.minY) / max(1, rect.height)
            guard u >= 0, u <= 1, v >= 0, v <= 1 else { stopTracking(); return }
            let side = 0.12 * min(1, CGFloat(h) / CGFloat(w))
            let sideY = side * CGFloat(w) / CGFloat(h)
            let box = CGRect(x: min(max(u - side / 2, 0), 1 - side), y: min(max(1 - v - sideY / 2, 0), 1 - sideY), width: side, height: sideY)
            let observation = VNDetectedObjectObservation(boundingBox: box)
            let r = VNTrackObjectRequest(detectedObjectObservation: observation)
            r.trackingLevel = .accurate
            handler = VNSequenceRequestHandler()
            lock.lock()
            if pendingStart?.point == start.point { pendingStart = nil; request = r }
            lock.unlock()
        }
        lock.lock()
        let r = request
        lock.unlock()
        guard let r else { return }
        do {
            try handler.perform([r], on: pixels)
        } catch {
            SessionLog.w("Subject", "tracking: \(error.localizedDescription)")
            stopTracking()
            return
        }
        guard let result = r.results?.first as? VNDetectedObjectObservation else { return }
        if result.confidence < 0.3 {
            lowFrames += 1
            if lowFrames >= 8 {
                SessionLog.i("Subject", "target lost")
                stopTracking()
            }
            return
        }
        lowFrames = 0
        r.inputObservation = result
        let rect = Self.content(w, h, in: view)
        let b = result.boundingBox
        let box = CGRect(x: rect.minX + b.minX * rect.width, y: rect.minY + (1 - b.maxY) * rect.height, width: b.width * rect.width,
                         height: b.height * rect.height)
        let normalized = view.width > 0 && view.height > 0
            ? CGRect(x: box.minX / view.width, y: box.minY / view.height, width: box.width / view.width, height: box.height / view.height) : .zero
        let focusNow = Date().timeIntervalSince(lastFocus) >= 0.1
        if focusNow { lastFocus = Date() }
        let centre = CGPoint(x: box.midX, y: box.midY)
        let report = onTrack
        Task { @MainActor in report?(normalized, focusNow ? centre : nil) }
    }

    private func histogram(_ pixels: CVPixelBuffer) {
        guard CVPixelBufferGetPixelFormatType(pixels) == kCVPixelFormatType_32BGRA else { return }
        CVPixelBufferLockBaseAddress(pixels, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixels, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(pixels) else { return }
        let w = CVPixelBufferGetWidth(pixels), h = CVPixelBufferGetHeight(pixels), row = CVPixelBufferGetBytesPerRow(pixels)
        let p = base.assumingMemoryBound(to: UInt8.self)
        var r = [Int](repeating: 0, count: Self.bins), g = r, b = r
        let stepX = max(1, w / 128), stepY = max(1, h / 96)
        var y = 0
        while y < h {
            var x = 0
            let line = p + y * row
            while x < w {
                let px = line + x * 4
                b[Int(px[0]) * Self.bins / 256] += 1
                g[Int(px[1]) * Self.bins / 256] += 1
                r[Int(px[2]) * Self.bins / 256] += 1
                x += stepX * 2
            }
            y += stepY
        }
        var peak = 1
        var channels = [r, g, b]
        for c in 0..<3 {
            for i in 0..<Self.bins {
                channels[c][i] = Int(Double(channels[c][i]).squareRoot())
                peak = max(peak, channels[c][i])
            }
        }
        let top = peak, out = channels
        Task { @MainActor in CameraHud.shared.setHistogram(out, peak: top) }
    }
}

/// Face detection (pref_face_detect_mode; AVCaptureMetadataOutput's faces) for the viewfinder's boxes: the faces of the open lens in
/// the preview layer's coordinates, the largest one the primary (the camera's own face-driven focus and exposure use it, as
/// Android's primary face drives the AF / AE regions).
final class FaceBoxes: NSObject, AVCaptureMetadataOutputObjectsDelegate, @unchecked Sendable {
    static let shared = FaceBoxes()
    let output = AVCaptureMetadataOutput()
    let queue = DispatchQueue(label: "scamera.faces", qos: .userInitiated)
    private weak var layer: AVCaptureVideoPreviewLayer?

    override init() {
        super.init()
        output.setMetadataObjectsDelegate(self, queue: queue)
    }

    /// After the output joined the session: faces only
    func configure() {
        if output.availableMetadataObjectTypes.contains(.face) { output.metadataObjectTypes = [.face] }
    }

    func attach(_ layer: AVCaptureVideoPreviewLayer?) {
        queue.async { [self] in self.layer = layer }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        let faces = metadataObjects.compactMap { $0 as? AVMetadataFaceObject }
        let layer = self.layer
        DispatchQueue.main.async {
            guard let layer else { return }
            let size = layer.bounds.size
            guard size.width > 0, size.height > 0 else { return }
            let rects = faces.compactMap { layer.transformedMetadataObject(for: $0)?.bounds }
                .map { CGRect(x: $0.minX / size.width, y: $0.minY / size.height, width: $0.width / size.width, height: $0.height / size.height) }
            MainActor.assumeIsolated { SubjectOverlayModel.shared.setFaces(rects) }
        }
    }
}

/// SubjectOverlayView (P42): face boxes and the tracking box as rounded corner brackets (no text) that glide to their new place
/// and fade in and out; the primary face and the tracked subject in the accent, other faces in translucent white
@MainActor
@Observable
final class SubjectOverlayModel {
    static let shared = SubjectOverlayModel()
    struct Box: Identifiable { let id: Int; var rect: CGRect; var primary: Bool }
    private(set) var faces: [Box] = []
    private(set) var track: CGRect?
    @ObservationIgnored private var nextId = 0
    @ObservationIgnored private var missing: [Int: Int] = [:]

    static var facesOn: Bool { SettingsStore.shared.string("pref_face_detect_mode", "auto") != "off" }

    func setFaces(_ rects: [CGRect]) {
        guard Self.facesOn else {
            if !faces.isEmpty { withAnimation(.easeOut(duration: 0.2)) { faces = [] } }
            return
        }
        // match the new rects to the boxes by their centres (within a quarter of the view), new ones fade in, lost ones fade out
        var next: [Box] = []
        var used = Set<Int>()
        let primary = rects.indices.max { rects[$0].width * rects[$0].height < rects[$1].width * rects[$1].height }
        for (j, r) in rects.prefix(10).enumerated() {
            var best: Box?
            var bestD = 0.25
            for b in faces where !used.contains(b.id) {
                let d = hypot(b.rect.midX - r.midX, b.rect.midY - r.midY)
                if d < bestD { bestD = d; best = b }
            }
            if let best {
                used.insert(best.id)
                next.append(Box(id: best.id, rect: r, primary: j == primary))
            } else {
                nextId += 1
                next.append(Box(id: nextId, rect: r, primary: j == primary))
            }
        }
        // a face missing in one update stays (it fades after two)
        for b in faces where !used.contains(b.id) {
            let m = (missing[b.id] ?? 0) + 1
            missing[b.id] = m
            if m < 2 { next.append(b) } else { missing[b.id] = nil }
        }
        withAnimation(.easeOut(duration: 0.18)) { faces = next }
    }

    func setTrack(_ rect: CGRect?) {
        withAnimation(.easeOut(duration: 0.15)) { track = rect }
    }
}

struct SubjectOverlay: View {
    private let model = SubjectOverlayModel.shared

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .topLeading) {
                ForEach(model.faces) { b in
                    Brackets(stroke: 1.6).stroke(b.primary ? CameraStyle.accent : Color.white.opacity(0.6), style: StrokeStyle(lineWidth: 1.6, lineCap: .round, lineJoin: .round))
                        .frame(width: b.rect.width * g.size.width, height: b.rect.height * g.size.height)
                        .offset(x: b.rect.minX * g.size.width, y: b.rect.minY * g.size.height)
                        .transition(.opacity)
                }
                if let t = model.track {
                    Brackets(stroke: 2.2).stroke(CameraStyle.accent, style: StrokeStyle(lineWidth: 2.2, lineCap: .round, lineJoin: .round))
                        .frame(width: t.width * g.size.width, height: t.height * g.size.height)
                        .offset(x: t.minX * g.size.width, y: t.minY * g.size.height)
                        .transition(.opacity)
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    /// Rounded corner brackets: a fifth of the shorter side, 10..24 pt, 4 pt corners; nothing under 16 pt
    struct Brackets: Shape {
        let stroke: CGFloat
        func path(in r: CGRect) -> Path {
            var p = Path()
            guard r.width >= 16, r.height >= 16 else { return p }
            let len = max(min(min(r.width, r.height) * 0.2, 24), 10), c: CGFloat = 4
            p.move(to: CGPoint(x: r.minX, y: r.minY + len)); p.addLine(to: CGPoint(x: r.minX, y: r.minY + c))
            p.addQuadCurve(to: CGPoint(x: r.minX + c, y: r.minY), control: CGPoint(x: r.minX, y: r.minY)); p.addLine(to: CGPoint(x: r.minX + len, y: r.minY))
            p.move(to: CGPoint(x: r.maxX - len, y: r.minY)); p.addLine(to: CGPoint(x: r.maxX - c, y: r.minY))
            p.addQuadCurve(to: CGPoint(x: r.maxX, y: r.minY + c), control: CGPoint(x: r.maxX, y: r.minY)); p.addLine(to: CGPoint(x: r.maxX, y: r.minY + len))
            p.move(to: CGPoint(x: r.maxX, y: r.maxY - len)); p.addLine(to: CGPoint(x: r.maxX, y: r.maxY - c))
            p.addQuadCurve(to: CGPoint(x: r.maxX - c, y: r.maxY), control: CGPoint(x: r.maxX, y: r.maxY)); p.addLine(to: CGPoint(x: r.maxX - len, y: r.maxY))
            p.move(to: CGPoint(x: r.minX + len, y: r.maxY)); p.addLine(to: CGPoint(x: r.minX + c, y: r.maxY))
            p.addQuadCurve(to: CGPoint(x: r.minX, y: r.maxY - c), control: CGPoint(x: r.minX, y: r.maxY)); p.addLine(to: CGPoint(x: r.minX, y: r.maxY - len))
            return p
        }
    }
}

/// ExposureSquareView (P142, «Раздельные фокус и экспозиция»): a rounded square (6 pt corners, 8 pt in from its 76 pt frame) with a
/// small sun at its top right corner, drawn around the focus ring after a tap and dragged on its own - the exposure point follows it
struct ExposureSquare: View {
    static let side: CGFloat = 76
    private let colour = Color(red: 1, green: 0xD5 / 255, blue: 0x4F / 255)

    var body: some View {
        Canvas { context, size in
            let box = CGRect(x: 8, y: 8, width: size.width - 16, height: size.height - 16)
            context.stroke(Path(roundedRect: box, cornerRadius: 6), with: .color(colour), lineWidth: 1.5)
            let cx = box.maxX, cy = box.minY, r: CGFloat = 2.4
            context.fill(Path(ellipseIn: CGRect(x: cx - r, y: cy - r, width: 2 * r, height: 2 * r)), with: .color(colour))
            for i in 0..<8 {
                let a = Double.pi / 4 * Double(i)
                let c = CGFloat(cos(a)), s = CGFloat(sin(a))
                var p = Path()
                p.move(to: CGPoint(x: cx + c * (r + 1.6), y: cy + s * (r + 1.6)))
                p.addLine(to: CGPoint(x: cx + c * (r + 3.6), y: cy + s * (r + 3.6)))
                context.stroke(p, with: .color(colour), style: StrokeStyle(lineWidth: 1.2, lineCap: .round))
            }
        }
        .frame(width: Self.side, height: Self.side)
        .accessibilityLabel(L("ev.square"))
    }
}

/// Gyro.getTripod: the phone is on a tripod when the peak of the smoothed shake (the gyroscope's rates summed, mrad/s) over the last
/// detection window stays under 25 - the HUD's «[TRIPOD]» line
@MainActor
@Observable
final class TripodDetector {
    static let shared = TripodDetector()
    private(set) var onTripod = false
    @ObservationIgnored private let manager = CMMotionManager()
    @ObservationIgnored private var smoothed = -1.0
    @ObservationIgnored private var peak = 0.0
    @ObservationIgnored private var count = 0

    func setActive(_ on: Bool) {
        if on {
            guard manager.isGyroAvailable, !manager.isGyroActive else { return }
            manager.gyroUpdateInterval = 1.0 / 60
            manager.startGyroUpdates(to: .main) { [weak self] data, _ in
                guard let r = data?.rotationRate else { return }
                MainActor.assumeIsolated { self?.sample(abs(r.x) + abs(r.y) + abs(r.z)) }
            }
        } else {
            manager.stopGyroUpdates()
            onTripod = false
        }
    }

    private func sample(_ rate: Double) {
        let raw = rate * 1000
        smoothed = smoothed < 0 ? raw : raw * 0.2 + smoothed * 0.8
        peak = max(peak, smoothed)
        count += 1
        if count >= 180 {
            let now = peak < 25
            if now != onTripod { onTripod = now }
            peak = 0
            count = 0
        }
    }
}
