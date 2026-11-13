import AVFoundation
import SwiftUI
import UIKit

/// The player's picture fits (VideoPlayerActivity.Fit) and VideoLayout.size: the picture's size in the box for a fit, then the
/// pinch zoom on top.
enum VideoFit: Int, CaseIterable {
    case fit, crop, stretch, original, r16x9, r4x3

    var title: String {
        switch self {
        case .fit: return L("video.fit.fit")
        case .crop: return L("video.fit.crop")
        case .stretch: return L("video.fit.stretch")
        case .original: return "100 %"
        case .r16x9: return "16:9"
        case .r4x3: return "4:3"
        }
    }

    /// The picture's width and height in a `box` (VideoLayout.size)
    func size(video: CGSize, box: CGSize, zoom: CGFloat, scale: CGFloat = 1) -> CGSize {
        let vw = max(1, video.width), vh = max(1, video.height), bw = max(1, box.width), bh = max(1, box.height)
        var w: CGFloat, h: CGFloat
        switch self {
        case .crop:
            let s = max(bw / vw, bh / vh)
            w = vw * s
            h = vh * s
        case .stretch:
            w = bw
            h = bh
        case .original:
            // the video's pixels at the screen's pixels
            w = vw / max(1, scale)
            h = vh / max(1, scale)
        case .r16x9, .r4x3:
            var a: CGFloat = self == .r16x9 ? 16.0 / 9.0 : 4.0 / 3.0
            if vh > vw { a = 1 / a }   // a portrait video keeps its orientation
            if bw / bh > a {
                h = bh
                w = bh * a
            } else {
                w = bw
                h = bw / a
            }
        case .fit:
            let s = min(bw / vw, bh / vh)
            w = vw * s
            h = vh * s
        }
        return CGSize(width: max(1, (w * zoom).rounded()), height: max(1, (h * zoom).rounded()))
    }
}

/// The player's touch surface and picture (VideoPlayerActivity.installGestures + layoutVideo): an AVPlayerLayer framed by the fit,
/// the zoom and the pan; a tap toggles the controls, a double tap at the sides seeks back / forward (in the middle: pause), a
/// horizontal drag seeks, a vertical drag sets the brightness (left half) or the volume (right half), a pinch zooms and pans, a
/// long press plays at 2x while held.
struct VideoSurface: UIViewRepresentable {
    let player: AVPlayer
    let videoSize: CGSize
    let fit: VideoFit
    let zoom: CGFloat
    let pan: CGSize
    var onTap: () -> Void
    var onDoubleTap: (CGFloat) -> Void
    var onDrag: (DragKind, CGFloat, CGFloat, Bool) -> Void
    var onPinch: (CGFloat, CGSize, Bool) -> Void
    var onHold: (Bool) -> Void

    enum DragKind { case seek, brightness, volume }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> SurfaceView {
        let v = SurfaceView()
        v.playerLayer.player = player
        let c = context.coordinator
        let double = UITapGestureRecognizer(target: c, action: #selector(Coordinator.doubleTap(_:)))
        double.numberOfTapsRequired = 2
        v.addGestureRecognizer(double)
        let single = UITapGestureRecognizer(target: c, action: #selector(Coordinator.tap(_:)))
        single.require(toFail: double)
        v.addGestureRecognizer(single)
        let pan = UIPanGestureRecognizer(target: c, action: #selector(Coordinator.pan(_:)))
        pan.maximumNumberOfTouches = 1
        v.addGestureRecognizer(pan)
        let pinch = UIPinchGestureRecognizer(target: c, action: #selector(Coordinator.pinch(_:)))
        v.addGestureRecognizer(pinch)
        let hold = UILongPressGestureRecognizer(target: c, action: #selector(Coordinator.hold(_:)))
        hold.minimumPressDuration = 0.5
        v.addGestureRecognizer(hold)
        return v
    }

    func updateUIView(_ v: SurfaceView, context: Context) {
        context.coordinator.parent = self
        if v.playerLayer.player !== player { v.playerLayer.player = player }
        v.layout = (videoSize, fit, zoom, pan)
        v.setNeedsLayout()
    }

    final class SurfaceView: UIView {
        let playerLayer = AVPlayerLayer()
        var layout: (CGSize, VideoFit, CGFloat, CGSize) = (.zero, .fit, 1, .zero)

        override init(frame: CGRect) {
            super.init(frame: frame)
            backgroundColor = .black
            playerLayer.videoGravity = .resize
            layer.addSublayer(playerLayer)
        }

        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

        override func layoutSubviews() {
            super.layoutSubviews()
            let (video, fit, zoom, pan) = layout
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            if video.width <= 0 || video.height <= 0 {
                playerLayer.videoGravity = .resizeAspect
                playerLayer.frame = bounds
            } else {
                playerLayer.videoGravity = .resize
                let size = fit.size(video: video, box: bounds.size, zoom: zoom, scale: traitCollection.displayScale)
                let maxX = max(0, (size.width - bounds.width) / 2), maxY = max(0, (size.height - bounds.height) / 2)
                let px = min(max(pan.width, -maxX), maxX), py = min(max(pan.height, -maxY), maxY)
                playerLayer.frame = CGRect(x: (bounds.width - size.width) / 2 + px, y: (bounds.height - size.height) / 2 + py,
                                           width: size.width, height: size.height)
            }
            CATransaction.commit()
        }
    }

    final class Coordinator: NSObject {
        var parent: VideoSurface
        private var kind: DragKind?
        private var pinchStart = CGPoint.zero
        init(_ parent: VideoSurface) { self.parent = parent }

        @objc func tap(_ g: UITapGestureRecognizer) { parent.onTap() }

        @objc func doubleTap(_ g: UITapGestureRecognizer) {
            guard let v = g.view else { return }
            parent.onDoubleTap(g.location(in: v).x / max(1, v.bounds.width))
        }

        /// The drag's kind from its first 14 pt (sideways: seek; up / down: the half it began in); the system's 44 pt edges left alone
        @objc func pan(_ g: UIPanGestureRecognizer) {
            guard let v = g.view else { return }
            let t = g.translation(in: v)
            let w = max(1, v.bounds.width), h = max(1, v.bounds.height)
            switch g.state {
            case .changed:
                if kind == nil {
                    let start = g.location(in: v)
                    let origin = CGPoint(x: start.x - t.x, y: start.y - t.y)
                    if origin.y < 44 || origin.y > h - 44 { return }
                    if abs(t.x) < 14 && abs(t.y) < 14 { return }
                    kind = abs(t.x) > abs(t.y) ? .seek : origin.x < w / 2 ? .brightness : .volume
                }
                if let kind { parent.onDrag(kind, t.x / w, t.y / h, false) }
            case .ended, .cancelled, .failed:
                if let kind { parent.onDrag(kind, t.x / w, t.y / h, true) }
                kind = nil
            default:
                break
            }
        }

        @objc func pinch(_ g: UIPinchGestureRecognizer) {
            guard let v = g.view else { return }
            let p = g.location(in: v)
            switch g.state {
            case .began:
                pinchStart = p
                parent.onPinch(g.scale, .zero, false)
                g.scale = 1
            case .changed:
                parent.onPinch(g.scale, CGSize(width: p.x - pinchStart.x, height: p.y - pinchStart.y), false)
                pinchStart = p
                g.scale = 1
            case .ended, .cancelled, .failed:
                parent.onPinch(1, .zero, true)
            default:
                break
            }
        }

        @objc func hold(_ g: UILongPressGestureRecognizer) {
            switch g.state {
            case .began:
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                parent.onHold(true)
            case .ended, .cancelled, .failed:
                parent.onHold(false)
            default:
                break
            }
        }
    }
}
