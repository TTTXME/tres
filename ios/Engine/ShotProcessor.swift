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
        let heic = try encode(toned.texture, metadata: metadata, watermark: { log.append($0) })
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

    /// `watermark` (nil: none): the photo is upright (orientation 1) and gets the stored settings' watermark (PhotoWatermark),
    /// its log line given to the closure.
    static func encode(_ texture: MTLTexture, metadata: [String: Any], orientation: Int = 1,
                       watermark: ((String) -> Void)? = nil) throws -> Data {
        let w = texture.width, h = texture.height
        var bytes = Data(count: w * 4 * h)
        bytes.withUnsafeMutableBytes { raw in
            texture.getBytes(raw.baseAddress!, bytesPerRow: w * 4, from: MTLRegionMake2D(0, 0, w, h), mipmapLevel: 0)
        }
        if let watermark, orientation == 1, PhotoWatermark.on() {
            PhotoWatermark.apply(&bytes, width: w, height: h, metadata: metadata, dng: nil, shotTime: Date(), log: watermark)
        }
        return try encode(rgba: bytes, width: w, height: h, metadata: metadata, orientation: orientation)
    }

    /// RGBA8 sRGB rows top-down (the pipeline's photo) → HEIC with the camera's EXIF.
    /// «Формат фото» (pref_photo_format, Android default jpeg) and its quality (pref_jpeg_quality 98, pref_heic_quality 90).
    static var photoFormat: (type: UTType, quality: Double) {
        let store = SettingsStore.shared
        if store.string("pref_photo_format", "jpeg") == "heic" {
            return (.heic, Double(Int(store.string("pref_heic_quality", "90")) ?? 90) / 100)
        }
        return (.jpeg, Double(Int(store.string("pref_jpeg_quality", "98")) ?? 98) / 100)
    }

    /// The photo's Ultra HDR gain map (SCAMPhoto.gainMap, Engine/post/UltraHdr.hpp): one byte a pixel, rows top-down, pixel-aligned
    /// with the photo; the codes linear in log2 gain from `min` to `max` (GainMapComputer.Result). Its ISO 21496-1 metadata is
    /// IsoGainMap.java's: gamma 1, base and alternate offsets GainMapComputer.DECODE_OFFSET, base headroom 0, alternate headroom
    /// HDRCapacityMax (`capacity`), one channel in the base image's colour space.
    struct GainMap {
        let data: Data
        let width: Int
        let height: Int
        let min: Float
        let max: Float
        let capacity: Float

        /// GainMapComputer.DECODE_OFFSET (OffsetSDR / OffsetHDR)
        static let decodeOffset: Float = 1.0 / 64.0

        init?(photo: SCAMPhoto) {
            guard let data = photo.gainMap, photo.gainMapWidth > 0, photo.gainMapHeight > 0,
                  data.count >= photo.gainMapWidth * photo.gainMapHeight else { return nil }
            self.data = data
            width = photo.gainMapWidth
            height = photo.gainMapHeight
            min = photo.gainMapMin
            max = photo.gainMapMax
            capacity = photo.hdrCapacityMax
        }
    }

    /// The gain map as the image's ISO 21496-1 gain map auxiliary image (ImageIO, iOS 18): the map's bytes with their description
    /// (one 8-bit component a pixel) and its metadata as the gain map XMP schema (hdrgm, the values of UltraHdrContainer's XMP and
    /// IsoGainMap). Returns false when the metadata could not be built (nothing added).
    @available(iOS 18.0, *)
    static func addGainMap(_ gain: GainMap, to destination: CGImageDestination) -> Bool {
        let metadata = CGImageMetadataCreateMutable()
        guard CGImageMetadataRegisterNamespaceForPrefix(metadata, "http://ns.adobe.com/hdr-gain-map/1.0/" as CFString,
                                                        "hdrgm" as CFString, nil) else { return false }
        let number = { (v: Float) in String(format: "%.6f", Double(v)) }
        let values: [(String, String)] = [
            ("Version", "1.0"),
            ("GainMapMin", number(gain.min)),
            ("GainMapMax", number(gain.max)),
            ("Gamma", "1"),
            ("OffsetSDR", number(GainMap.decodeOffset)),
            ("OffsetHDR", number(GainMap.decodeOffset)),
            ("HDRCapacityMin", "0"),
            ("HDRCapacityMax", number(gain.capacity)),
            ("BaseRenditionIsHDR", "False"),
        ]
        for (key, value) in values {
            guard CGImageMetadataSetValueWithPath(metadata, nil, "hdrgm:\(key)" as CFString, value as CFString) else { return false }
        }
        let description: [String: Any] = [
            kCGImagePropertyWidth as String: gain.width,
            kCGImagePropertyHeight as String: gain.height,
            kCGImagePropertyBytesPerRow as String: gain.width,
            kCGImagePropertyPixelFormat as String: NSNumber(value: kCVPixelFormatType_OneComponent8),
        ]
        let info: [String: Any] = [
            kCGImageAuxiliaryDataInfoData as String: gain.data as CFData,
            kCGImageAuxiliaryDataInfoDataDescription as String: description,
            kCGImageAuxiliaryDataInfoMetadata as String: metadata,
        ]
        CGImageDestinationAddAuxiliaryDataInfo(destination, kCGImageAuxiliaryDataTypeISOGainMap, info as CFDictionary)
        return true
    }

    /// `gainMap` (Ultra HDR): written as the ISO gain map on iOS 18 and later, left out before (the photo stays SDR); `log` says which.
    static func encode(rgba bytes: Data, width w: Int, height h: Int, metadata: [String: Any], orientation: Int = 1,
                       format: (type: UTType, quality: Double) = (.heic, 0.92), gainMap: GainMap? = nil,
                       log: ((String) -> Void)? = nil) throws -> Data {
        let rowBytes = w * 4
        guard let provider = CGDataProvider(data: bytes as CFData),
              let space = CGColorSpace(name: CGColorSpace.sRGB),
              let image = CGImage(width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: rowBytes, space: space,
                                  bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue), provider: provider,
                                  decode: nil, shouldInterpolate: false, intent: .defaultIntent) else {
            throw EngineError.encode("CGImage")
        }
        let out = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(out, format.type.identifier as CFString, 1, nil) else {
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
        properties[kCGImageDestinationLossyCompressionQuality as String] = format.quality
        CGImageDestinationAddImage(destination, image, properties as CFDictionary)
        if let gainMap {
            if gainMap.width != w || gainMap.height != h {
                log?("Ultra HDR: gain map \(gainMap.width)x\(gainMap.height) for a \(w)x\(h) photo, not written (SDR)")
            } else if #available(iOS 18.0, *) {
                let added = addGainMap(gainMap, to: destination)
                log?(added ? "Ultra HDR: ISO gain map \(gainMap.width)x\(gainMap.height) min=\(gainMap.min) max=\(gainMap.max)"
                             + " capacity=\(gainMap.capacity)"
                           : "Ultra HDR: the gain map's metadata could not be built, not written (SDR)")
            } else {
                log?("Ultra HDR: the ISO gain map needs iOS 18, the photo is SDR")
            }
        }
        guard CGImageDestinationFinalize(destination) else { throw EngineError.encode("HEIC finalize") }
        return out as Data
    }
}
