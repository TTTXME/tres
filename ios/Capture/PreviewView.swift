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

    final class Coordinator: NSObject {
        var parent: CameraPreview
        init(_ parent: CameraPreview) { self.parent = parent }

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
        view.addGestureRecognizer(UIPinchGestureRecognizer(target: c, action: #selector(Coordinator.pinch(_:))))
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
