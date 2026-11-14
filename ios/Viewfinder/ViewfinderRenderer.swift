import AVFoundation
import CoreVideo
import Metal
import QuartzCore
import UIKit

/// The live viewfinder's look (Android MainRenderer's ISP path: shaders/preview/main_fs.glsl + LiveLensFilter) on the camera's
/// AVCaptureVideoDataOutput frames, drawn with Metal (Viewfinder.metal) into a CAMetalLayer over the AVCaptureVideoPreviewLayer:
/// the film / LUT table the photo gets (FilmChain.current, mixed by its strength), the lens filter's or the film's diffusion
/// (DiffusionModel's level weights on a half-size mip pyramid) and focus peaking (pref_peak_method_key: 1 on, 2 with manual focus).
/// The settings are read again twice a second (LiveLensFilter.REFRESH_MS). With nothing to add the frames stop (the output's
/// connection off) and the plain preview layer shows the camera's picture, as Android's pass draws the frame unchanged then.
/// Android's developed-RAW viewfinder (pref_live_viewfinder_raw_key, off by default) has no counterpart: the iPhone streams no RAW.
final class ViewfinderRenderer: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate, @unchecked Sendable {
    static let shared = ViewfinderRenderer()

    /// Added to the session by CaptureService; its frames come upright (the connection's rotation) and mirrored for the front lens.
    let output = AVCaptureVideoDataOutput()
    let queue = DispatchQueue(label: "scamera.viewfinder", qos: .userInteractive)
    private let lookQueue = DispatchQueue(label: "scamera.viewfinder-look", qos: .utility)
    static let refreshInterval = 0.5

    private let device = MTLCreateSystemDefaultDevice()
    private var commands: MTLCommandQueue?
    private var mainPipeline: MTLRenderPipelineState?
    private var prepPipeline: MTLRenderPipelineState?
    private var textureCache: CVMetalTextureCache?
    private var dummy2D: MTLTexture?
    private var dummy3D: MTLTexture?

    /// What `queue` draws with (set on `queue`)
    private struct Drawn {
        var lut: MTLTexture?
        var lutKey = ""
        var strength: Float = 0
        var domainMin = SIMD3<Float>(0, 0, 0)
        var domainMax = SIMD3<Float>(1, 1, 1)
        var filter = 0
        var filterStrength: Float = 0
        var weights: [SIMD3<Float>] = []
        var peaking = false
        var active: Bool { lut != nil && strength > 0 || filter > 0 && filterStrength > 0 && !weights.isEmpty || peaking }
    }
    private var drawn = Drawn()
    private var pyramid: MTLTexture?
    private var shownActivation = false

    /// Shared with the main thread (lock)
    private let lock = NSLock()
    private weak var metalLayer: CAMetalLayer?
    private weak var view: UIView?
    private var drawableSize = CGSize.zero

    /// On `lookQueue`: the settings the look was made of, the timer
    private var lookKey = ""
    private var timer: DispatchSourceTimer?
    private var loggedActive: Bool?

    override init() {
        super.init()
        output.alwaysDiscardsLateVideoFrames = true
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        // the preview's size, not the format's (a 12 / 48 MP photo format streams 4032-wide frames otherwise)
        output.automaticallyConfiguresOutputBufferDimensions = false
        output.deliversPreviewSizedOutputBuffers = true
        output.setSampleBufferDelegate(self, queue: queue)
        guard let device else {
            SessionLog.w("Viewfinder", "no Metal device: the plain preview")
            return
        }
        commands = device.makeCommandQueue()
        CVMetalTextureCacheCreate(nil, nil, device, nil, &textureCache)
        do {
            guard let library = device.makeDefaultLibrary() else { throw NSError(domain: "Viewfinder", code: 1) }
            let main = MTLRenderPipelineDescriptor()
            main.vertexFunction = library.makeFunction(name: "vf_vertex")
            main.fragmentFunction = library.makeFunction(name: "vf_main")
            main.colorAttachments[0].pixelFormat = .bgra8Unorm
            mainPipeline = try device.makeRenderPipelineState(descriptor: main)
            let prep = MTLRenderPipelineDescriptor()
            prep.vertexFunction = library.makeFunction(name: "vf_vertex")
            prep.fragmentFunction = library.makeFunction(name: "vf_prep")
            prep.colorAttachments[0].pixelFormat = .rgba16Float
            prepPipeline = try device.makeRenderPipelineState(descriptor: prep)
        } catch {
            SessionLog.e("Viewfinder", "pipelines: \(error.localizedDescription): the plain preview")
        }
        dummy2D = Self.texture2D(device, width: 1, height: 1, levels: 1, usage: .shaderRead)
        let d3 = MTLTextureDescriptor()
        d3.textureType = .type3D
        d3.pixelFormat = .rgba16Float
        d3.width = 1
        d3.height = 1
        d3.depth = 1
        d3.usage = .shaderRead
        dummy3D = device.makeTexture(descriptor: d3)
    }

    private static func texture2D(_ device: MTLDevice, width: Int, height: Int, levels: Int, usage: MTLTextureUsage) -> MTLTexture? {
        let d = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba16Float, width: width, height: height, mipmapped: levels > 1)
        d.mipmapLevelCount = levels
        d.usage = usage
        d.storageMode = .private
        return device.makeTexture(descriptor: d)
    }

    // MARK: the view (main thread)

    /// The CAMetalLayer view of the viewfinder came on screen: the look is read from now on.
    func attach(_ view: ViewfinderLayerView) {
        lock.lock()
        metalLayer = view.metalLayer
        self.view = view
        lock.unlock()
        view.metalLayer.device = device
        view.metalLayer.pixelFormat = .bgra8Unorm
        view.metalLayer.framebufferOnly = true
        lookQueue.async { [self] in
            guard timer == nil else { return }
            let t = DispatchSource.makeTimerSource(queue: lookQueue)
            t.schedule(deadline: .now(), repeating: Self.refreshInterval)
            t.setEventHandler { [weak self] in self?.refresh() }
            t.resume()
            timer = t
        }
    }

    func detach(_ view: ViewfinderLayerView) {
        lock.lock()
        if self.view === view {
            metalLayer = nil
            self.view = nil
        }
        lock.unlock()
        lookQueue.async { [self] in
            timer?.cancel()
            timer = nil
            lookKey = ""
            setFrames(false)
        }
    }

    func setDrawableSize(_ size: CGSize) {
        lock.lock()
        drawableSize = size
        lock.unlock()
    }

    // MARK: the settings (lookQueue)

    /// What the look depends on: the film / LUT / lens filter rows, the peaking row, manual focus and the viewport.
    private func settingsKey(size: CGSize) -> String {
        let prefs = UserDefaults.standard.dictionary(forKey: PostSettings.prefsKey) ?? [:]
        let look = prefs.filter { $0.key.hasPrefix("pref_film_") || $0.key.hasPrefix("pref_lens_filter") }
            .sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: ";")
        return look + "|" + "\(Int(size.width))x\(Int(size.height))"
    }

    /// MainRenderer.getPeakEnabled: 1 on; 2 «Авто» while the focus is manual («Про»); 0 off.
    private static var peaking: Bool {
        let prefs = UserDefaults.standard.dictionary(forKey: PostSettings.prefsKey) ?? [:]
        let value = (prefs["pref_peak_method_key"] as? String) ?? (prefs["pref_peak_method_key"] as? NSNumber)?.stringValue ?? "2"
        switch value {
        case "1": return true
        case "2": return ManualSettings.load().focusOn
        default: return false
        }
    }

    private func refresh() {
        lock.lock()
        let size = drawableSize
        let attached = metalLayer != nil
        lock.unlock()
        guard attached else { return }
        let peaking = Self.peaking
        let key = settingsKey(size: size)
        var look: ViewfinderNative.Look?
        if key != lookKey {
            lookKey = key
            // LiveLensFilter.prepass: a pyramid at half the viewport's size, its mip levels down to 1 x 1
            let half = (max(1, (Int(size.width) + 1) / 2), max(1, (Int(size.height) + 1) / 2))
            let levels = Int.bitWidth - max(1, max(half.0, half.1)).leadingZeroBitCount
            let longPx = Int(max(size.width, size.height))
            look = ViewfinderNative.look(settings: PostSettings.json(), longPx: longPx, levels: levels) ?? ViewfinderNative.Look()
        }
        let lutTexture: MTLTexture?? = look.map { $0.size >= 2 && $0.strength > 0 ? makeLut($0) : nil }
        queue.async { [self] in
            if let look {
                if let lutTexture { drawn.lut = lutTexture }
                drawn.lutKey = look.key
                drawn.strength = look.strength
                drawn.domainMin = look.domainMin
                drawn.domainMax = look.domainMax
                drawn.filter = look.filter
                drawn.filterStrength = look.filterStrength
                drawn.weights = look.weights
            }
            drawn.peaking = peaking
            let active = drawn.active && mainPipeline != nil
            lookQueue.async { [self] in setFrames(active) }
        }
        if let look {
            SessionLog.i("Viewfinder", "look: table \(look.key.isEmpty ? "none" : "\(look.size)^3 at \(look.strength)")"
                         + " filter \(look.filter) at \(look.filterStrength) levels \(look.weights.count) peaking \(peaking)")
        }
    }

    /// The settings changed (a LUT step of the viewfinder's swipe): the look read again now, not at the next refresh
    func refreshNow() {
        lookQueue.async { [self] in refresh() }
    }

    /// The look last decided on `lookQueue` (the frames may also run for FrameAnalysis without it)
    private var lookActive = false

    /// The HUD's histogram or the subject tracking started or stopped: the frames on or off again (lookQueue)
    func analysisChanged() {
        lookQueue.async { [self] in setFrames(lookActive) }
    }

    /// The frames on (a look to draw, or the frames' analysis: App/Camera/SubjectTracking) or off (the plain preview layer), and the
    /// Metal view shown only while a look draws.
    private func setFrames(_ active: Bool) {
        lookActive = active
        let on = active || FrameAnalysis.shared.wanted
        if let connection = output.connection(with: .video), connection.isEnabled != on { connection.isEnabled = on }
        guard loggedActive != active else { return }
        loggedActive = active
        SessionLog.i("Viewfinder", active ? "the look on the camera's frames" : "the plain preview")
        if !active {
            lock.lock()
            let view = self.view
            lock.unlock()
            queue.async { [self] in shownActivation = false }
            DispatchQueue.main.async { view?.isHidden = true }
        }
    }

    /// The output's connection of a newly opened lens (a new input makes a new connection): upright frames (the preview's angle),
    /// mirrored for the front lens (as Android mirrors the front viewfinder), on only while a look draws.
    func configure(rotation angle: CGFloat, mirrored: Bool) {
        lookQueue.async { [self] in
            guard let connection = output.connection(with: .video) else { return }
            if connection.isVideoRotationAngleSupported(angle) { connection.videoRotationAngle = angle }
            if connection.isVideoMirroringSupported {
                connection.automaticallyAdjustsVideoMirroring = false
                connection.isVideoMirrored = mirrored
            }
            connection.isEnabled = (loggedActive ?? false) || FrameAnalysis.shared.wanted
        }
    }

    /// IEEE half of a float (rounded to nearest; below the half range's normals to 0, above it infinity): no Float16, which the
    /// x86_64 simulator lacks
    static func half(_ value: Float) -> UInt16 {
        let bits = value.bitPattern
        let sign = UInt16((bits >> 16) & 0x8000)
        let exponent = Int((bits >> 23) & 0xff) - 127 + 15
        if value.isNaN { return 0x7e00 }
        if exponent <= 0 { return sign }
        if exponent >= 31 { return sign | 0x7c00 }
        let mantissa = Int(bits & 0x7fffff)
        return sign | UInt16(min(0x7c00, (exponent << 10) + ((mantissa + 0x1000) >> 13)))
    }

    /// FilmLut.upload: the table as a 3D RGBA half-float texture (x red, y green, z blue)
    private func makeLut(_ look: ViewfinderNative.Look) -> MTLTexture? {
        guard let device, let data = look.lut else { return nil }
        let n = look.size
        guard data.count >= n * n * n * 3 * MemoryLayout<Float>.size else { return nil }
        var rgba = [UInt16](repeating: Self.half(1), count: n * n * n * 4)
        data.withUnsafeBytes { raw in
            let f = raw.bindMemory(to: Float.self)
            for i in 0..<(n * n * n) {
                rgba[i * 4] = Self.half(f[i * 3])
                rgba[i * 4 + 1] = Self.half(f[i * 3 + 1])
                rgba[i * 4 + 2] = Self.half(f[i * 3 + 2])
            }
        }
        let d = MTLTextureDescriptor()
        d.textureType = .type3D
        d.pixelFormat = .rgba16Float
        d.width = n
        d.height = n
        d.depth = n
        d.usage = .shaderRead
        guard let texture = device.makeTexture(descriptor: d) else { return nil }
        rgba.withUnsafeBytes { raw in
            texture.replace(region: MTLRegionMake3D(0, 0, 0, n, n, n), mipmapLevel: 0, slice: 0, withBytes: raw.baseAddress!,
                            bytesPerRow: n * 8, bytesPerImage: n * n * 8)
        }
        return texture
    }

    // MARK: the frames (queue)

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let pixels = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        if FrameAnalysis.shared.wanted { FrameAnalysis.shared.frame(pixels) }
        draw(pixels)
    }

    private func draw(_ pixels: CVPixelBuffer) {
        guard drawn.active, let device, let commands, let mainPipeline, let textureCache else { return }
        lock.lock()
        let layer = metalLayer
        let size = drawableSize
        let view = self.view
        lock.unlock()
        guard let layer, size.width >= 2, size.height >= 2 else { return }
        if layer.drawableSize != size { layer.drawableSize = size }
        let w = CVPixelBufferGetWidth(pixels), h = CVPixelBufferGetHeight(pixels)
        var cvTexture: CVMetalTexture?
        CVMetalTextureCacheCreateTextureFromImage(nil, textureCache, pixels, nil, .bgra8Unorm, w, h, 0, &cvTexture)
        guard let cvTexture, let frame = CVMetalTextureGetTexture(cvTexture) else { return }
        guard let drawable = layer.nextDrawable(), let buffer = commands.makeCommandBuffer() else { return }

        // aspect fit about the centre, as the preview layer's .resizeAspect
        let frameAspect = Double(w) / Double(h), viewAspect = Double(size.width / size.height)
        let scale = frameAspect > viewAspect ? SIMD2<Float>(1, Float(frameAspect / viewAspect)) : SIMD2<Float>(Float(viewAspect / frameAspect), 1)
        let lutOn = drawn.lut != nil && drawn.strength > 0
        let p3 = (CVBufferCopyAttachment(pixels, kCVImageBufferColorPrimariesKey, nil) as? String) == (kCVImageBufferColorPrimaries_P3_D65 as String)
        var levels = 0
        var target: MTLTexture?
        if drawn.filter >= 1, drawn.filter <= 6, drawn.filterStrength > 0, drawn.weights.count >= 2, prepPipeline != nil {
            let half = (max(1, (Int(size.width) + 1) / 2), max(1, (Int(size.height) + 1) / 2))
            let mips = Int.bitWidth - max(half.0, half.1).leadingZeroBitCount
            if pyramid == nil || pyramid!.width != half.0 || pyramid!.height != half.1 {
                pyramid = Self.texture2D(device, width: half.0, height: half.1, levels: mips, usage: [.renderTarget, .shaderRead])
            }
            if let pyramid {
                levels = min(12, drawn.weights.count - 1, pyramid.mipmapLevelCount)
                target = pyramid
            }
        }
        let range = drawn.domainMax - drawn.domainMin
        let inverse = SIMD3<Float>(range.x > 0 ? 1 / range.x : 0, range.y > 0 ? 1 / range.y : 0, range.z > 0 ? 1 / range.z : 0)
        var u: [SIMD4<Float>] = [
            SIMD4(scale.x, scale.y, 0, 0),
            SIMD4(Float(size.width), Float(size.height), Float(drawn.lut?.width ?? 1), drawn.strength),
            SIMD4(drawn.domainMin, 0),
            SIMD4(inverse, 0),
            SIMD4(lutOn ? 1 : 0, Float(levels), drawn.peaking ? 1 : 0, levels > 0 ? 1 : 0),
            SIMD4(drawn.filterStrength, p3 ? 1 : 0, 0, 0),
        ]
        for k in 0...12 {
            let weight = k < drawn.weights.count ? drawn.weights[k] : SIMD3<Float>(k == 0 ? 1 : 0, k == 0 ? 1 : 0, k == 0 ? 1 : 0)
            u.append(SIMD4(weight, 0))
        }
        let length = MemoryLayout<SIMD4<Float>>.stride * u.count
        // LiveLensFilter.prepass: the linear frame at half size, then its mip levels (the blur pyramid)
        if let target, let prepPipeline {
            let pass = MTLRenderPassDescriptor()
            pass.colorAttachments[0].texture = target
            pass.colorAttachments[0].level = 0
            pass.colorAttachments[0].loadAction = .dontCare
            pass.colorAttachments[0].storeAction = .store
            if let encoder = buffer.makeRenderCommandEncoder(descriptor: pass) {
                encoder.setRenderPipelineState(prepPipeline)
                encoder.setVertexBytes(&u, length: length, index: 0)
                encoder.setFragmentBytes(&u, length: length, index: 0)
                encoder.setFragmentTexture(frame, index: 0)
                encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
                encoder.endEncoding()
            }
            if target.mipmapLevelCount > 1, let blit = buffer.makeBlitCommandEncoder() {
                blit.generateMipmaps(for: target)
                blit.endEncoding()
            }
        }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = drawable.texture
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        pass.colorAttachments[0].storeAction = .store
        guard let encoder = buffer.makeRenderCommandEncoder(descriptor: pass) else { return }
        encoder.setRenderPipelineState(mainPipeline)
        encoder.setVertexBytes(&u, length: length, index: 0)
        encoder.setFragmentBytes(&u, length: length, index: 0)
        encoder.setFragmentTexture(frame, index: 0)
        encoder.setFragmentTexture(lutOn ? drawn.lut : dummy3D, index: 1)
        encoder.setFragmentTexture(levels > 0 ? pyramid : dummy2D, index: 2)
        encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        encoder.endEncoding()
        buffer.present(drawable)
        // the camera's buffer stays with its texture until the GPU is done with it
        buffer.addCompletedHandler { _ in _ = cvTexture }
        buffer.commit()
        if !shownActivation {
            shownActivation = true
            DispatchQueue.main.async { view?.isHidden = false }
        }
    }
}

/// The CAMetalLayer the look is drawn into, over the AVCaptureVideoPreviewLayer (hidden while the plain preview shows).
final class ViewfinderLayerView: UIView {
    override class var layerClass: AnyClass { CAMetalLayer.self }
    var metalLayer: CAMetalLayer { layer as! CAMetalLayer }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        isOpaque = true
        isHidden = true
        backgroundColor = .black
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func layoutSubviews() {
        super.layoutSubviews()
        let scale = window?.screen.scale ?? traitCollection.displayScale
        contentScaleFactor = scale
        ViewfinderRenderer.shared.setDrawableSize(CGSize(width: (bounds.width * scale).rounded(), height: (bounds.height * scale).rounded()))
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil {
            ViewfinderRenderer.shared.attach(self)
            setNeedsLayout()
        } else {
            ViewfinderRenderer.shared.detach(self)
        }
    }
}
