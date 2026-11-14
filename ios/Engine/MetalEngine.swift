import CoreImage
import CoreVideo
import Metal

enum EngineError: LocalizedError {
    case noDevice
    case kernel(String)
    case texture(String)
    case decode(String)
    case encode(String)

    var errorDescription: String? {
        switch self {
        case .noDevice: return "Metal device unavailable"
        case .kernel(let name): return "Metal kernel \(name) unavailable"
        case .texture(let what): return "Metal texture \(what) could not be created"
        case .decode(let what): return "RAW decode failed: \(what)"
        case .encode(let what): return "Image encode failed: \(what)"
        }
    }
}

/// Metal device, queue, the app's kernels (Engine/*.metal) and a Core Image context on the same device.
final class MetalEngine: @unchecked Sendable {
    static let shared: MetalEngine? = MetalEngine()

    let device: MTLDevice
    let queue: MTLCommandQueue
    let library: MTLLibrary
    let ciContext: CIContext
    private var textureCache: CVMetalTextureCache?
    private var pipelines: [String: MTLComputePipelineState] = [:]
    private let lock = NSLock()

    private init?() {
        guard let device = MTLCreateSystemDefaultDevice(), let queue = device.makeCommandQueue(),
              let library = try? device.makeDefaultLibrary(bundle: Bundle(for: MetalEngine.self)) else { return nil }
        self.device = device
        self.queue = queue
        self.library = library
        ciContext = CIContext(mtlDevice: device, options: [.cacheIntermediates: false,
                                                            .workingFormat: NSNumber(value: CIFormat.RGBAh.rawValue)])
        CVMetalTextureCacheCreate(nil, nil, device, nil, &textureCache)
    }

    func pipeline(_ name: String) throws -> MTLComputePipelineState {
        lock.lock()
        defer { lock.unlock() }
        if let cached = pipelines[name] { return cached }
        guard let function = library.makeFunction(name: name) else { throw EngineError.kernel(name) }
        let state = try device.makeComputePipelineState(function: function)
        pipelines[name] = state
        return state
    }

    func texture(_ width: Int, _ height: Int, _ format: MTLPixelFormat, shared: Bool = false,
                 usage: MTLTextureUsage = [.shaderRead, .shaderWrite]) throws -> MTLTexture {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: format, width: width, height: height, mipmapped: false)
        descriptor.usage = usage
        descriptor.storageMode = shared ? .shared : .private
        guard let texture = device.makeTexture(descriptor: descriptor) else { throw EngineError.texture("\(width)x\(height) \(format.rawValue)") }
        return texture
    }

    /// Zero-copy Metal view of an IOSurface-backed pixel buffer (64RGBAHalf → rgba16Float). The CVMetalTexture must
    /// stay alive while the GPU uses the texture.
    func texture(of buffer: CVPixelBuffer, format: MTLPixelFormat) throws -> (texture: MTLTexture, owner: CVMetalTexture) {
        guard let cache = textureCache else { throw EngineError.texture("cache") }
        var cvTexture: CVMetalTexture?
        let status = CVMetalTextureCacheCreateTextureFromImage(nil, cache, buffer, nil, format,
                                                               CVPixelBufferGetWidth(buffer), CVPixelBufferGetHeight(buffer), 0, &cvTexture)
        guard status == kCVReturnSuccess, let cvTexture, let texture = CVMetalTextureGetTexture(cvTexture) else {
            throw EngineError.texture("pixel buffer view \(status)")
        }
        return (texture, cvTexture)
    }

    func dispatch(_ encoder: MTLComputeCommandEncoder, _ state: MTLComputePipelineState, width: Int, height: Int) {
        encoder.setComputePipelineState(state)
        let w = state.threadExecutionWidth
        let h = max(1, state.maxTotalThreadsPerThreadgroup / w)
        encoder.dispatchThreads(MTLSize(width: width, height: height, depth: 1),
                                threadsPerThreadgroup: MTLSize(width: w, height: min(h, 16), depth: 1))
    }

    /// Runs `encode` in one command buffer and waits; GPU errors are thrown.
    func run(_ label: String, _ encode: (MTLCommandBuffer) throws -> Void) throws {
        guard let buffer = queue.makeCommandBuffer() else { throw EngineError.noDevice }
        buffer.label = label
        try encode(buffer)
        buffer.commit()
        buffer.waitUntilCompleted()
        if let error = buffer.error { throw error }
    }
}
