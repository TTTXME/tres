import AVFoundation
import ImageIO

/// A series of Bayer RAW frames for the SHDR merge (Android: the N frames of the burst, then the plan's ultrashort and bracketed
/// frames, HybridPlan), in capture order with each frame's capture role and measured exposure (the merge's frame rules pick from
/// them: Engine/post/HybridSelect.hpp).
struct BayerBurst {
    var width = 0, height = 0, cfa = 0
    var frames: [NchFrame] = []
    var dng = Data()          // the first frame's DNG: levels, noise profile, colour, orientation (the whole frame)
    var processed: Data?      // the camera's own HEIC of the first request, for its EXIF
    var report: [String] = []
    var front = false         // the front camera: the photo is mirrored as Android's Parameters.mirror
    /// ZoomController's residual at the press (the digital crop over the module's own zoom): SabreCrop's window before the merge and
    /// the photo's crop after it (P576 / P226). 1: no crop.
    var zoom = 1.0
    /// «16:9» (Wide169, P118): the frames' rows before the cut (0: whole frames) and its first row
    var fullHeight = 0, firstRow = 0
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
    /// The SHDR series: bracket requests of up to `maxBracketedCapturePhotoCount` frames at exposure bias 0 until `count` N frames
    /// arrived, then in the same series the plan's ultrashort (Bento) and bracketed (Shasta) frames as manual exposure brackets
    /// (HybridPlan from the newest N frame's measured exposure and its clipping / shadows; ultrashort first, as Android takes them).
    func captureBayerBurst(count: Int, device: AVCaptureDevice) async throws -> BayerBurst {
        guard let bayer = output.availableRawPhotoPixelFormatTypes.first(where: { AVCapturePhotoOutput.isBayerRAWPixelFormat($0) }) else {
            throw CameraError.captureException("Bayer RAW is not available on this lens / zoom")
        }
        var burst = BayerBurst()
        burst.front = device.position == .front
        let wide = Wide169.on
        let started = Date()
        let perRequest = max(1, min(count, output.maxBracketedCapturePhotoCount))
        var clock = BurstClock()
        while burst.frames.count < count {
            let n = min(perRequest, count - burst.frames.count)
            let bracket = (0..<n).map { _ in AVCaptureAutoExposureBracketedStillImageSettings.autoExposureSettings(exposureTargetBias: 0) }
            let first = burst.frames.isEmpty
            func request(withProcessed: Bool) -> AVCapturePhotoBracketSettings {
                let processed: [String: Any]? = withProcessed ? [AVVideoCodecKey: AVVideoCodecType.hevc] : nil
                let settings = AVCapturePhotoBracketSettings(rawPixelFormatType: bayer, processedFormat: processed, bracketedSettings: bracket)
                settings.maxPhotoDimensions = output.maxPhotoDimensions
                // one shutter sound for the whole series (CameraSounds), not the system's per request
                silence(settings)
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
            for photo in photos { try append(photo, to: &burst, clock: &clock, role: NchWriter.roleNormal, wide: wide) }
            if photos.isEmpty { break }
        }
        let normals = burst.frames.count
        if normals > 0, let plan = extrasPlan(for: burst, device: device) {
            burst.report.append(plan.description)
            if !plan.requests.isEmpty {
                await captureExtras(plan, bayer: bayer, device: device, perRequest: max(1, output.maxBracketedCapturePhotoCount),
                                    burst: &burst, clock: &clock, wide: wide)
            }
        }
        finish(&burst)
        let extras = burst.frames.count - normals
        burst.report.append("burst \(burst.frames.count) frames (\(normals) N, \(extras) ultrashort / bracketed) in \(ms(since: started)) ms, per request \(perRequest)"
                            + (burst.fullHeight > 0 ? ", 16:9 rows \(burst.firstRow)..<\(burst.firstRow + burst.height) of \(burst.fullHeight)" : ""))
        return burst
    }

    /// HybridPlan.build from the newest N frame (CaptureController: the newest ring frame's exposure, zslClipFraction's shares), the
    /// lens's ranges as SENSOR_INFO_EXPOSURE_TIME_RANGE / SENSITIVITY_RANGE; nil in «Движение», with «HDR кадры» off or «Oldstyle».
    private func extrasPlan(for burst: BayerBurst, device: AVCaptureDevice) -> HybridPlan.Plan? {
        guard !HybridPlan.oneExposure, let newest = burst.frames.max(by: { $0.timestampNs < $1.timestampNs }) else { return nil }
        let levels = DngInfo(dng: burst.dng)
        let measure = FrameMeasure.shares(newest.raw, width: burst.width, height: burst.height, black: levels.black, white: levels.white)
        let format = device.activeFormat
        let tLow = Int64((format.minExposureDuration.seconds * 1e9).rounded(.up)), tHigh = Int64((format.maxExposureDuration.seconds * 1e9).rounded(.down))
        let iLow = Int(format.minISO.rounded(.up)), iHigh = Int(format.maxISO.rounded(.down))
        guard tLow > 0, tHigh >= tLow, iLow > 0, iHigh >= iLow else { return nil }
        return HybridPlan.build(nShutterNs: newest.exposureNs, nIso: Int(newest.iso), clip: measure.clip, shadow: measure.shadow,
                                deepShadow: measure.deep, times: tLow...tHigh, isos: iLow...iHigh)
    }

    /// The plan's frames as manual exposure brackets (AVCaptureManualExposureBracketedStillImageSettings: the plan's shutter and ISO),
    /// RAW only, at most `perRequest` a request. Each delivered frame keeps the role its measured exposure gives (HybridPlan.classify);
    /// a refused request never costs the shot (P27): its N frames stay.
    private func captureExtras(_ plan: HybridPlan.Plan, bayer: OSType, device: AVCaptureDevice, perRequest: Int, burst: inout BayerBurst,
                               clock: inout BurstClock, wide: Bool) async {
        let format = device.activeFormat
        var pending = plan.requests[...]
        while !pending.isEmpty {
            let chunk = Array(pending.prefix(perRequest))
            pending = pending.dropFirst(chunk.count)
            let bracket = chunk.map { r in
                AVCaptureManualExposureBracketedStillImageSettings.manualExposureSettings(
                    exposureDuration: CMTime(value: r.shutterNs, timescale: 1_000_000_000),
                    iso: min(max(Float(r.iso), format.minISO), format.maxISO))
            }
            let settings = AVCapturePhotoBracketSettings(rawPixelFormatType: bayer, processedFormat: nil, bracketedSettings: bracket)
            settings.maxPhotoDimensions = output.maxPhotoDimensions
            silence(settings)
            let requestStarted = Date()
            let photos: [AVCapturePhoto]
            do {
                photos = try await capturePhotos(settings)
            } catch {
                burst.report.append("hybrid: \(chunk.count) ultrashort / bracketed frame(s) refused (\(error.localizedDescription)), the N frames merged alone")
                return
            }
            burst.report.append("burst request \(chunk.count) ultrashort / bracketed frames in \(ms(since: requestStarted)) ms")
            for (k, photo) in photos.enumerated() where photo.isRawPhoto {
                let at = photo.sequenceCount >= 1 && photo.sequenceCount <= chunk.count ? photo.sequenceCount - 1 : min(k, chunk.count - 1)
                let request = chunk[at]
                let (exposureNs, iso) = Self.measured(photo)
                var report = burst.report
                guard let role = HybridPlan.classify(request, exposureNs: exposureNs, iso: iso, plan: plan, log: { report.append($0) }) else {
                    burst.report = report
                    continue
                }
                burst.report = report
                do {
                    try append(photo, to: &burst, clock: &clock, role: role.rawValue, wide: wide)
                } catch {
                    burst.report.append("hybrid: \(request.role.name) frame left out (\(error.localizedDescription))")
                }
            }
        }
    }

    /// FlashMode.Shot (P590): a flash shot is never a series of frames exposed before the press - one fresh Bayer frame with the
    /// flash firing (the camera meters with its pre-flash), merged as a one-frame shot. Nil when this lens / zoom refuses a RAW
    /// frame with the flash (the caller takes the camera's own flash photo then).
    func captureFlashFrame(device: AVCaptureDevice, flash: AVCaptureDevice.FlashMode) async throws -> BayerBurst? {
        guard let bayer = output.availableRawPhotoPixelFormatTypes.first(where: { AVCapturePhotoOutput.isBayerRAWPixelFormat($0) }),
              output.supportedFlashModes.contains(flash) else { return nil }
        let settings = AVCapturePhotoSettings(rawPixelFormatType: bayer, processedFormat: [AVVideoCodecKey: AVVideoCodecType.hevc])
        settings.maxPhotoDimensions = output.maxPhotoDimensions
        settings.flashMode = flash
        silence(settings)
        var burst = BayerBurst()
        burst.front = device.position == .front
        let started = Date()
        let photos: [AVCapturePhoto]
        do {
            photos = try await capturePhotos(settings)
        } catch CameraError.captureException(let reason) {
            SessionLog.w("PhotoCapture", "flash RAW frame refused: \(reason)")
            return nil
        }
        var clock = BurstClock()
        for photo in photos { try append(photo, to: &burst, clock: &clock, role: NchWriter.roleNormal, wide: Wide169.on) }
        guard !burst.frames.isEmpty else { return nil }
        finish(&burst)
        burst.report.append("flash shot: 1 frame (flash \(flash.rawValue)) in \(ms(since: started)) ms")
        return burst
    }

    /// The first frame's exposure and time the others are relative to
    struct BurstClock {
        var t0: Double?
        var exposure0: Double = 0
    }

    /// The photo's measured exposure (EXIF ExposureTime in ns, ISOSpeedRatings) as Camera2's SENSOR_EXPOSURE_TIME / SENSITIVITY
    static func measured(_ photo: AVCapturePhoto) -> (exposureNs: Int64, iso: Int) {
        let exif = photo.metadata[kCGImagePropertyExifDictionary as String] as? [String: Any]
        var iso = (exif?[kCGImagePropertyExifISOSpeedRatings as String] as? [NSNumber])?.first?.doubleValue ?? 0
        var time = (exif?[kCGImagePropertyExifExposureTime as String] as? NSNumber)?.doubleValue ?? 0
        // a manual bracket's own request when the metadata has none
        if let manual = photo.bracketSettings as? AVCaptureManualExposureBracketedStillImageSettings {
            if time <= 0, manual.exposureDuration.isNumeric { time = manual.exposureDuration.seconds }
            if iso <= 0 { iso = Double(manual.iso) }
        }
        if iso <= 0 { iso = 100 }
        if time <= 0 { time = 0.01 }
        return (Int64((time * 1e9).rounded()), Int(iso.rounded()))
    }

    /// A delivered photo into the burst: the processed one's bytes kept for the EXIF, a Bayer frame copied from its 14-bit buffer
    /// (with «16:9» its centred rows, Wide169: every frame of the series the same cut), with its role, measured exposure and time.
    private func append(_ photo: AVCapturePhoto, to burst: inout BayerBurst, clock: inout BurstClock, role: Int, wide: Bool) throws {
        if !photo.isRawPhoto {
            burst.processed = photo.fileDataRepresentation()
            return
        }
        guard let buffer = photo.pixelBuffer else { return }
        let format = CVPixelBufferGetPixelFormatType(buffer)
        guard let cfa = format.bayerCFA else { throw CameraError.captureException("RAW format \(format.fourCC) is not 14-bit Bayer") }
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        let w = CVPixelBufferGetWidth(buffer), h = CVPixelBufferGetHeight(buffer), row = CVPixelBufferGetBytesPerRow(buffer)
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return }
        let fullW = w & ~1, fullH = h & ~1
        let rows = wide && fullW > fullH ? Wide169.rows(fullW, fullH) : fullH
        let first = rows < fullH ? Wide169.firstRow(fullW, fullH) : 0
        if burst.frames.isEmpty {
            burst.width = fullW
            burst.height = rows
            burst.cfa = cfa
            if rows < fullH {
                burst.fullHeight = fullH
                burst.firstRow = first
            }
            burst.dng = photo.fileDataRepresentation() ?? Data()
            burst.report.append("burst frame 0: \(w)x\(h) \(format.fourCC) row=\(row) dng=\(burst.dng.count) bytes")
        }
        // the cut keeps whole 2x2 colour sites: the first row is a multiple of 8, the CFA order is the frame's
        guard fullW == burst.width, rows == burst.height, first == burst.firstRow else { return }
        var raw = Data(count: burst.width * burst.height * 2)
        raw.withUnsafeMutableBytes { dst in
            for y in 0..<burst.height {
                memcpy(dst.baseAddress! + y * burst.width * 2, base + (first + y) * row, burst.width * 2)
            }
        }
        let (exposureNs, iso) = Self.measured(photo)
        let product = Double(exposureNs) * Double(iso)
        if clock.exposure0 == 0 { clock.exposure0 = product }
        let stampNs = photo.timestamp.convertScale(1_000_000_000, method: .default).value
        let stamp = Double(stampNs) / 1e6
        if clock.t0 == nil { clock.t0 = stamp }
        burst.frames.append(NchFrame(raw: raw, exposure: Float(clock.exposure0 > 0 ? product / clock.exposure0 : 1), iso: UInt32(iso),
                                     noiseSlope: 0, noiseOffset: 0, orderMs: Float(stamp - (clock.t0 ?? stamp)),
                                     role: role, exposureNs: exposureNs, timestampNs: stampNs))
    }

    /// The newest frame is 0 ms, earlier ones negative (as Android's orderMs; the merge's own orderMs come from HybridSelect)
    private func finish(_ burst: inout BayerBurst) {
        if let newest = burst.frames.map(\.orderMs).max() {
            for i in burst.frames.indices { burst.frames[i].orderMs -= newest }
        }
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
