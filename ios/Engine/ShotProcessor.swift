import CoreImage
import CoreVideo
import ImageIO
import Metal
import UniformTypeIdentifiers

/// One shot through the GPU pipeline: DNG → linear white-balanced RGB (Core Image RAW decode on the GPU, Apple's
/// demosaic until the RAW merge is on Metal) → «Why тон» V2 (Metal) → HEIC with the camera's EXIF.
enum ShotProcessor {
    struct Result {
        let heic: Data
        let log: [String]
    }

    static func process(dng: URL, metadata: [String: Any]) throws -> Result {
        guard let engine = MetalEngine.shared else { throw EngineError.noDevice }
        var log: [String] = []
        let started = Date()
        let linear = try decode(dng, engine: engine, log: &log)
        let input = try engine.texture(of: linear, format: .rgba16Float)
        let renderer = WhyV2Renderer(engine: engine)
        let toned = try withExtendedLifetime(input.owner) { try renderer.render(input.texture, noise: WhyV2Ae.referenceNoiseFallback) }
        log += toned.log
        let encodeStarted = Date()
        let heic = try encode(toned.texture, metadata: metadata)
        log.append("encode HEIC \(heic.count) bytes ms=\(ms(since: encodeStarted)); total ms=\(ms(since: started)), \(memoryFootprint())")
        return Result(heic: heic, log: log)
    }

    /// Linear extended sRGB (Rec.709 primaries), white balanced, without Apple's tone curve, in a 64RGBAHalf buffer.
    static func decode(_ url: URL, engine: MetalEngine, log: inout [String]) throws -> CVPixelBuffer {
        let started = Date()
        guard let filter = CIRAWFilter(imageURL: url) else { throw EngineError.decode("CIRAWFilter refused the DNG") }
        filter.boostAmount = 0
        filter.isGamutMappingEnabled = false
        if filter.isLocalToneMapSupported { filter.localToneMapAmount = 0 }
        guard let image = filter.outputImage else { throw EngineError.decode("no output image") }
        let extent = image.extent.integral
        let w = Int(extent.width), h = Int(extent.height)
        var buffer: CVPixelBuffer?
        let attributes: [String: Any] = [kCVPixelBufferIOSurfacePropertiesKey as String: [:] as [String: Any],
                                         kCVPixelBufferMetalCompatibilityKey as String: true]
        let status = CVPixelBufferCreate(nil, w, h, kCVPixelFormatType_64RGBAHalf, attributes as CFDictionary, &buffer)
        guard status == kCVReturnSuccess, let buffer else { throw EngineError.texture("decode buffer \(status)") }
        guard let space = CGColorSpace(name: CGColorSpace.extendedLinearSRGB) else { throw EngineError.decode("colour space") }
        engine.ciContext.render(image.transformed(by: CGAffineTransform(translationX: -extent.minX, y: -extent.minY)),
                                to: buffer, bounds: CGRect(x: 0, y: 0, width: w, height: h), colorSpace: space)
        log.append("decode \(w)x\(h) neutral=\(filter.neutralTemperature)K/\(filter.neutralTint) baselineEV=\(filter.baselineExposure)"
                   + " lensCorrection=\(filter.isLensCorrectionEnabled) ms=\(ms(since: started))")
        return buffer
    }

    static func encode(_ texture: MTLTexture, metadata: [String: Any], orientation: Int = 1) throws -> Data {
        let w = texture.width, h = texture.height
        var bytes = Data(count: w * 4 * h)
        bytes.withUnsafeMutableBytes { raw in
            texture.getBytes(raw.baseAddress!, bytesPerRow: w * 4, from: MTLRegionMake2D(0, 0, w, h), mipmapLevel: 0)
        }
        return try encode(rgba: bytes, width: w, height: h, metadata: metadata, orientation: orientation)
    }

    /// RGBA8 sRGB rows top-down (the pipeline's photo) → HEIC with the camera's EXIF.
    static func encode(rgba bytes: Data, width w: Int, height h: Int, metadata: [String: Any], orientation: Int = 1) throws -> Data {
        let rowBytes = w * 4
        guard let provider = CGDataProvider(data: bytes as CFData),
              let space = CGColorSpace(name: CGColorSpace.sRGB),
              let image = CGImage(width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: rowBytes, space: space,
                                  bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue), provider: provider,
                                  decode: nil, shouldInterpolate: false, intent: .defaultIntent) else {
            throw EngineError.encode("CGImage")
        }
        let out = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(out, UTType.heic.identifier as CFString, 1, nil) else {
            throw EngineError.encode("HEIC destination")
        }
        var properties = metadata
        for key in [kCGImagePropertyPixelWidth, kCGImagePropertyPixelHeight] { properties[key as String] = nil }
        if var exif = properties[kCGImagePropertyExifDictionary as String] as? [String: Any] {
            exif[kCGImagePropertyExifPixelXDimension as String] = w
            exif[kCGImagePropertyExifPixelYDimension as String] = h
            properties[kCGImagePropertyExifDictionary as String] = exif
        }
        // CIRAWFilter's pixels are upright (1); the merge's are the sensor's (the DNG's orientation).
        properties[kCGImagePropertyOrientation as String] = orientation
        var tiff = properties[kCGImagePropertyTIFFDictionary as String] as? [String: Any] ?? [:]
        tiff[kCGImagePropertyTIFFOrientation as String] = orientation
        tiff[kCGImagePropertyTIFFSoftware as String] = "SCAMERA iOS \(BuildInfo.version)"
        properties[kCGImagePropertyTIFFDictionary as String] = tiff
        properties[kCGImageDestinationLossyCompressionQuality as String] = 0.92
        CGImageDestinationAddImage(destination, image, properties as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { throw EngineError.encode("HEIC finalize") }
        return out as Data
    }
}
