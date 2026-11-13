import Foundation
import ImageIO
import Metal
import simd

/// A Bayer burst through the Android pipeline as ported: NCH transport → the SHDR merge (Android C++, ScamGL on the GPU) →
/// white balance and colour matrix (the camera's DNG) → «Why тон» V2 (Metal) → HEIC.
enum BurstProcessor {
    struct Result {
        let heic: Data
        let log: [String]
    }

    /// The last burst is kept as an NCH file (Documents/Bursts, Files app) to replay it against the Android merge.
    static var lastBurstURL: URL {
        let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Bursts", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder.appendingPathComponent("last.nch")
    }

    static func process(_ burst: BayerBurst, metadata: [String: Any]) throws -> Result {
        guard let engine = MetalEngine.shared else { throw EngineError.noDevice }
        guard burst.frames.count >= 1, burst.width >= 64, burst.height >= 64 else { throw EngineError.decode("empty burst") }
        var log = burst.report
        let started = Date()
        let info = DngInfo(dng: burst.dng)
        log += info.notes.map { "dng: " + $0 }
        var frames = burst.frames
        let blackMean = info.black.reduce(0, +) / 4
        var source = "DNG NoiseProfile"
        var noise = info.noise
        if noise == nil, frames.count >= 2,
           let estimate = NoiseEstimate.fromPair(frames[0].raw, frames[1].raw, width: burst.width, height: burst.height, cfa: burst.cfa,
                                                 black: blackMean, white: info.white) {
            noise = estimate
            source = "estimated from frames 0 / 1"
        }
        let (slope, offset) = noise ?? (1e-4, 1e-6)
        if noise == nil { source = "fallback (no profile, one frame)" }
        let iso0 = Float(max(frames[0].iso, 1))
        for i in frames.indices {
            let k = Float(frames[i].iso) / iso0
            frames[i].noiseSlope = slope * k
            frames[i].noiseOffset = offset * k * k
            frames[i].exposure = min(max(frames[i].exposure, 1 / 512), 512)
        }
        frames[0].exposure = 1
        log.append("burst \(burst.width)x\(burst.height) cfa=\(burst.cfa) frames=\(frames.count) black=\(info.black) white=\(info.white)"
                   + " noise \(source): S=\(slope) O=\(offset) neutral=\(info.neutral) orientation=\(info.orientation)")

        let nch = lastBurstURL
        let writeStarted = Date()
        try NchWriter.write(to: nch, width: burst.width, height: burst.height, cfa: burst.cfa, white: info.white, black: info.black,
                            frames: frames)
        log.append("burst written to \(nch.lastPathComponent) in \(ms(since: writeStarted)) ms")
        guard let tuningURL = Bundle.main.url(forResource: "replay_tuning", withExtension: "txt") else {
            throw EngineError.decode("merge tuning missing from the app")
        }
        let tuning = try String(contentsOf: tuningURL, encoding: .utf8)
        let merged = try SCAMMerge.mergeBurst(atPath: nch.path, tuning: tuning)
        log += merged.log.map { "merge: " + $0 }
        log.append("merge \(merged.width)x\(merged.height) in \(Int(merged.milliseconds)) ms, \(memoryFootprint())")

        // camera RGB → white-balanced linear sRGB (rgba16Float)
        let w = merged.width, h = merged.height
        guard let rgb = merged.rgb.withUnsafeBytes({ engine.device.makeBuffer(bytes: $0.baseAddress!, length: $0.count, options: .storageModeShared) })
        else { throw EngineError.texture("merged buffer") }
        let linear = try engine.texture(w, h, .rgba16Float)
        var params = CameraToLinearParams(cameraToSRGB: info.cameraToSRGB, neutralInverse: SIMD3<Float>(1, 1, 1) / info.neutral,
                                          width: Int32(w), height: Int32(h))
        try engine.run("camera-to-linear") { buffer in
            guard let enc = buffer.makeComputeCommandEncoder() else { throw EngineError.noDevice }
            enc.setBuffer(rgb, offset: 0, index: 0)
            enc.setBytes(&params, length: MemoryLayout<CameraToLinearParams>.stride, index: 1)
            enc.setTexture(linear, index: 0)
            try engine.dispatch(enc, engine.pipeline("scam_camera_to_linear"), width: w, height: h)
            enc.endEncoding()
        }
        let toned = try WhyV2Renderer(engine: engine).render(linear, noise: (slope, offset))
        log += toned.log
        let heic = try ShotProcessor.encode(toned.texture, metadata: metadata, orientation: info.orientation)
        log.append("burst processed in \(ms(since: started)) ms, HEIC \(heic.count) bytes, \(memoryFootprint())")
        return Result(heic: heic, log: log)
    }
}
