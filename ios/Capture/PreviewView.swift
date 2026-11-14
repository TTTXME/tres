import SwiftUI
import AVKit
@preconcurrency import AVFoundation

/// Viewfinder (AVCam CameraPreview): a UIView backed by AVCaptureVideoPreviewLayer, the live look's Metal layer over it
/// (Viewfinder/ViewfinderRenderer: shown while the settings give the frames a film / LUT / lens filter / peaking), and the
/// viewfinder's gestures as Android's Swipe: a tap focuses (TouchFocus), a long press holds focus and exposure, a pinch zooms
/// (ZoomController); the volume buttons press the shutter (CameraActivity.dispatchKeyEvent; AVCaptureEventInteraction, iOS 17.2).
struct CameraPreview: UIViewRepresentable {
    let source: PreviewSource
    var onTap: ((CGPoint) -> Void)?
    var onLongPress: ((CGPoint) -> Void)?
    /// The pinch's scale since it began, and whether it began now
    var onPinch: ((CGFloat, Bool) -> Void)?
    var onHardwareShutter: (() -> Void)?
    /// A one-finger fling (Swipe.Router): the translation, the velocity and where it started, in the view's points
    var onFling: ((CGPoint, CGPoint, CGPoint, PreviewView) -> Void)?

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var parent: CameraPreview
        /// A second finger came down during the gesture (a pinch): no fling acts then
        private var pinched = false
        init(_ parent: CameraPreview) { self.parent = parent }

        @objc func pan(_ g: UIPanGestureRecognizer) {
            guard let view = g.view as? PreviewView else { return }
            switch g.state {
            case .began: pinched = false
            case .ended:
                guard !pinched else { return }
                let t = g.translation(in: view), v = g.velocity(in: view), end = g.location(in: view)
                parent.onFling?(t, v, CGPoint(x: end.x - t.x, y: end.y - t.y), view)
            default: break
            }
        }

        func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
            (g is UIPanGestureRecognizer && other is UIPinchGestureRecognizer) || (g is UIPinchGestureRecognizer && other is UIPanGestureRecognizer)
        }

        @objc func tap(_ g: UITapGestureRecognizer) {
            guard g.state == .ended, let view = g.view else { return }
            parent.onTap?(g.location(in: view))
        }

        @objc func longPress(_ g: UILongPressGestureRecognizer) {
            guard g.state == .began, let view = g.view else { return }
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            parent.onLongPress?(g.location(in: view))
        }

        @objc func pinch(_ g: UIPinchGestureRecognizer) {
            pinched = true
            switch g.state {
            case .began: parent.onPinch?(g.scale, true)
            case .changed: parent.onPinch?(g.scale, false)
            default: break
            }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        source.connect(to: view)
        let c = context.coordinator
        view.addGestureRecognizer(UITapGestureRecognizer(target: c, action: #selector(Coordinator.tap(_:))))
        let long = UILongPressGestureRecognizer(target: c, action: #selector(Coordinator.longPress(_:)))
        long.minimumPressDuration = 0.5
        view.addGestureRecognizer(long)
        let pinch = UIPinchGestureRecognizer(target: c, action: #selector(Coordinator.pinch(_:)))
        pinch.delegate = c
        view.addGestureRecognizer(pinch)
        let pan = UIPanGestureRecognizer(target: c, action: #selector(Coordinator.pan(_:)))
        pan.maximumNumberOfTouches = 1
        pan.delegate = c
        view.addGestureRecognizer(pan)
        if #available(iOS 17.2, *) {
            // the volume buttons press the shutter while the camera runs (Android: KEYCODE_VOLUME_UP / DOWN, ACTION_DOWN)
            let interaction = AVCaptureEventInteraction { [weak c] event in
                if event.phase == .began { c?.parent.onHardwareShutter?() }
            }
            view.addInteraction(interaction)
        }
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) {
        context.coordinator.parent = self
    }

    final class PreviewView: UIView, PreviewTarget {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
        /// The live look (hidden: the preview layer's own picture)
        let look = ViewfinderLayerView()

        init() {
            super.init(frame: .zero)
            backgroundColor = .black
            previewLayer.videoGravity = .resizeAspect
            look.frame = bounds
            look.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            addSubview(look)
        }

        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

        nonisolated func setSession(_ session: AVCaptureSession) {
            Task { @MainActor in previewLayer.session = session }
        }

        /// LutWipeView (P319): the viewfinder's picture now (the old look) over the preview, the LUT changed under it, the old picture
        /// cut away from the side the finger came from in 320 ms (after a short settle for the new look's first frame), a thin bright
        /// edge on the border. Without a picture the LUT just changes.
        func wipe(direction: Int, change: () -> Void) {
            let w = bounds.width, h = bounds.height
            guard w > 0, h > 0, let snapshot = snapshotView(afterScreenUpdates: false) else { change(); return }
            snapshot.frame = bounds
            snapshot.isUserInteractionEnabled = false
            let mask = UIView(frame: bounds)
            mask.backgroundColor = .black
            snapshot.mask = mask
            let edge = UIView(frame: CGRect(x: direction > 0 ? w - 1 : -1, y: 0, width: 2, height: h))
            edge.backgroundColor = UIColor(white: 1, alpha: 0.9)
            edge.layer.shadowColor = UIColor.black.cgColor
            edge.layer.shadowOpacity = 0.6
            edge.layer.shadowRadius = 6
            edge.isUserInteractionEnabled = false
            addSubview(snapshot)
            addSubview(edge)
            change()
            UIView.animate(withDuration: 0.32, delay: 0.12, options: [.curveEaseOut], animations: {
                mask.frame = direction > 0 ? CGRect(x: 0, y: 0, width: 0, height: h) : CGRect(x: w, y: 0, width: 0, height: h)
                edge.frame.origin.x = direction > 0 ? -1 : w - 1
            }, completion: { _ in
                snapshot.removeFromSuperview()
                edge.removeFromSuperview()
            })
        }
    }
}

protocol PreviewSource: Sendable {
    func connect(to target: PreviewTarget)
}

protocol PreviewTarget {
    func setSession(_ session: AVCaptureSession)
}

struct DefaultPreviewSource: PreviewSource, @unchecked Sendable {
    private let session: AVCaptureSession
    init(session: AVCaptureSession) { self.session = session }
    func connect(to target: PreviewTarget) { target.setSession(session) }
}
