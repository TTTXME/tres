import SwiftUI
@preconcurrency import AVFoundation

/// Viewfinder (AVCam CameraPreview): a UIView backed by AVCaptureVideoPreviewLayer.
struct CameraPreview: UIViewRepresentable {
    let source: PreviewSource

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        source.connect(to: view)
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) {}

    final class PreviewView: UIView, PreviewTarget {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }

        init() {
            super.init(frame: .zero)
            backgroundColor = .black
            previewLayer.videoGravity = .resizeAspect
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
