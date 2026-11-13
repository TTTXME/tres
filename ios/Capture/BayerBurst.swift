import AVFoundation
import ImageIO

/// A series of Bayer RAW frames of one exposure for the SHDR merge (Android: the N normal frames of the burst).
struct BayerBurst {
    var width = 0, height = 0, cfa = 0
    var frames: [NchFrame] = []
    var dng = Data()          // the first frame's DNG: levels, noise profile, colour, orientation
    var processed: Data?      // the camera's own HEIC of the first request, for its EXIF
    var report: [String] = []
}

extension FourCharCode {
    /// Camera2 / DNG CFA order of a 14-bit Bayer pixel format: 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR.
    var bayerCFA: Int? {
        switch self {
        case kCVPixelFormatType_14Bayer_RGGB: return 0
        case kCVPixelFormatType_14Bayer_GRBG: return 1
        case kCVPixelFormatType_14Bayer_GBRG: return 2
        case kCVPixelFormatType_14Bayer_BGGR: return 3
        default: return nil
        }
    }
}

extension PhotoCapture {
    /// Bracket requests of up to `maxBracketedCapturePhotoCount` frames at exposure bias 0 until `count` frames arrived.
    func captureBayerBurst(count: Int, device: AVCaptureDevice) async throws -> BayerBurst {
        guard let bayer = output.availableRawPhotoPixelFormatTypes.first(where: { AVCapturePhotoOutput.isBayerRAWPixelFormat($0) }) else {
            throw CameraError.captureException("Bayer RAW is not available on this lens / zoom")
        }
        var burst = BayerBurst()
        let started = Date()
        let perRequest = max(1, min(count, output.maxBracketedCapturePhotoCount))
        var t0: Double?
        var exposure0: Double = 0
        while burst.frames.count < count {
            let n = min(perRequest, count - burst.frames.count)
            let bracket = (0..<n).map { _ in AVCaptureAutoExposureBracketedStillImageSettings.autoExposureSettings(exposureTargetBias: 0) }
            let first = burst.frames.isEmpty
            func request(withProcessed: Bool) -> AVCapturePhotoBracketSettings {
                let processed: [String: Any]? = withProcessed ? [AVVideoCodecKey: AVVideoCodecType.hevc] : nil
                let settings = AVCapturePhotoBracketSettings(rawPixelFormatType: bayer, processedFormat: processed, bracketedSettings: bracket)
                settings.maxPhotoDimensions = output.maxPhotoDimensions
                return settings
            }
            let requestStarted = Date()
            var photos: [AVCapturePhoto]
            do {
                photos = try await capturePhotos(request(withProcessed: first))
            } catch CameraError.captureException(let reason) where first {
                // a RAW + HEIC bracket refused: RAW only (the EXIF then comes from the DNG)
                burst.report.append("burst request with HEIC refused (\(reason)), RAW only")
                photos = try await capturePhotos(request(withProcessed: false))
            }
            burst.report.append("burst request \(n) frames in \(ms(since: requestStarted)) ms")
            for photo in photos {
                if !photo.isRawPhoto {
                    burst.processed = photo.fileDataRepresentation()
                    continue
                }
                guard let buffer = photo.pixelBuffer else { continue }
                let format = CVPixelBufferGetPixelFormatType(buffer)
                guard let cfa = format.bayerCFA else { throw CameraError.captureException("RAW format \(format.fourCC) is not 14-bit Bayer") }
                CVPixelBufferLockBaseAddress(buffer, .readOnly)
                defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
                let w = CVPixelBufferGetWidth(buffer), h = CVPixelBufferGetHeight(buffer), row = CVPixelBufferGetBytesPerRow(buffer)
                guard let base = CVPixelBufferGetBaseAddress(buffer) else { continue }
                if burst.frames.isEmpty {
                    burst.width = w & ~1
                    burst.height = h & ~1
                    burst.cfa = cfa
                    burst.dng = photo.fileDataRepresentation() ?? Data()
                    burst.report.append("burst frame 0: \(w)x\(h) \(format.fourCC) row=\(row) dng=\(burst.dng.count) bytes")
                }
                guard w & ~1 == burst.width, h & ~1 == burst.height else { continue }
                var raw = Data(count: burst.width * burst.height * 2)
                raw.withUnsafeMutableBytes { dst in
                    for y in 0..<burst.height {
                        memcpy(dst.baseAddress! + y * burst.width * 2, base + y * row, burst.width * 2)
                    }
                }
                let exif = photo.metadata[kCGImagePropertyExifDictionary as String] as? [String: Any]
                let iso = (exif?[kCGImagePropertyExifISOSpeedRatings as String] as? [NSNumber])?.first?.doubleValue ?? 100
                let time = (exif?[kCGImagePropertyExifExposureTime as String] as? NSNumber)?.doubleValue ?? 0.01
                if exposure0 == 0 { exposure0 = iso * time }
                let stamp = CMTimeGetSeconds(photo.timestamp) * 1000
                if t0 == nil { t0 = stamp }
                burst.frames.append(NchFrame(raw: raw, exposure: Float(exposure0 > 0 ? iso * time / exposure0 : 1), iso: UInt32(iso),
                                             noiseSlope: 0, noiseOffset: 0, orderMs: Float(stamp - (t0 ?? stamp))))
            }
            if photos.isEmpty { break }
        }
        // the newest frame is 0 ms, earlier ones negative (as Android's orderMs)
        if let newest = burst.frames.map(\.orderMs).max() {
            for i in burst.frames.indices { burst.frames[i].orderMs -= newest }
        }
        burst.report.append("burst \(burst.frames.count) frames in \(ms(since: started)) ms, per request \(perRequest)")
        return burst
    }
}

enum NoiseEstimate {
    /// Variance = slope · x + offset of the normalised signal, from the difference of two frames of one exposure on the green
    /// sites (32×32 tiles; least squares over the tiles). The fallback when the DNG has no NoiseProfile.
    static func fromPair(_ a: Data, _ b: Data, width: Int, height: Int, cfa: Int, black: Float, white: Float) -> (Float, Float)? {
        let range = max(white - black, 1)
        // green sites: RGGB / BGGR at (x + y) odd, GRBG / GBRG at (x + y) even
        let greenOdd = cfa == 0 || cfa == 3
        var xs: [Double] = [], ys: [Double] = []
        a.withUnsafeBytes { pa in
            b.withUnsafeBytes { pb in
                let va = pa.bindMemory(to: UInt16.self), vb = pb.bindMemory(to: UInt16.self)
                var ty = 0
                while ty + 32 <= height {
                    var tx = 0
                    while tx + 32 <= width {
                        var sum = 0.0, sumD = 0.0, sumD2 = 0.0, n = 0.0
                        for y in ty..<(ty + 32) {
                            for x in tx..<(tx + 32) where ((x + y) & 1 == 1) == greenOdd {
                                let i = y * width + x
                                let u = (Double(va[i]) - Double(black)) / Double(range)
                                let v = (Double(vb[i]) - Double(black)) / Double(range)
                                sum += (u + v) * 0.5
                                let d = (u - v) / 2.0.squareRoot()
                                sumD += d
                                sumD2 += d * d
                                n += 1
                            }
                        }
                        let mean = sum / n
                        let variance = sumD2 / n - (sumD / n) * (sumD / n)
                        if mean > 0.002 && mean < 0.8 { xs.append(mean); ys.append(variance) }
                        tx += 32
                    }
                    ty += 32
                }
            }
        }
        guard xs.count >= 16 else { return nil }
        // tiles with texture inflate the variance: the lower half of the variance at each brightness counts (median split)
        let order = ys.indices.sorted { ys[$0] < ys[$1] }.prefix(max(16, ys.count / 2))
        let n = Double(order.count)
        let mx = order.map { xs[$0] }.reduce(0, +) / n, my = order.map { ys[$0] }.reduce(0, +) / n
        var sxx = 0.0, sxy = 0.0
        for i in order { sxx += (xs[i] - mx) * (xs[i] - mx); sxy += (xs[i] - mx) * (ys[i] - my) }
        let slope = sxx > 0 ? max(sxy / sxx, 1e-7) : 1e-5
        let offset = max(my - slope * mx, 1e-9)
        return (Float(slope), Float(offset))
    }
}
