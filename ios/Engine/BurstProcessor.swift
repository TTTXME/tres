import Foundation
import ImageIO

import simd

/// A Bayer burst through the Android photo route as ported (Engine/post/ShotRun.hpp, ScamGL on the GPU): NCH transport → the SHDR
/// merge → the post pipeline («Why тон», the Android nodes and shaders) with the shot's parameters from its DNG → HEIC.
enum BurstProcessor {
    struct Result {
        let heic: Data          // the photo in «Формат фото» (JPEG or HEIC)
        let uti: String
        let log: [String]
    }

    /// The last burst is kept as an NCH file (Documents/Bursts, Files app) to replay it against the Android merge.
    static var lastBurstURL: URL {
        let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Bursts", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder.appendingPathComponent("last.nch")
    }

    static func process(_ burst: BayerBurst, metadata: [String: Any]) throws -> Result {
        guard MetalEngine.shared != nil else { throw EngineError.noDevice }
        guard burst.frames.count >= 1, burst.width >= 64, burst.height >= 64 else { throw EngineError.decode("empty burst") }
        var log = burst.report
        let started = Date()
        let info = DngInfo(dng: burst.dng)
        log += info.notes.map { "dng: " + $0 }
        var frames = BurstOrder.arrange(burst.frames, width: burst.width, height: burst.height, log: &log)
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
        }
        log.append("burst \(burst.width)x\(burst.height) cfa=\(burst.cfa) frames=\(frames.count) black=\(info.black) white=\(info.white)"
                   + " noise \(source): S=\(slope) O=\(offset) neutral=\(info.neutral) orientation=\(info.orientation)")

        let nch = lastBurstURL
        let writeStarted = Date()
        try NchWriter.write(to: nch, width: burst.width, height: burst.height, cfa: burst.cfa, white: info.white, black: info.black,
                            frames: frames)
        log.append("burst written to \(nch.lastPathComponent) in \(ms(since: writeStarted)) ms")
        guard let shaders = Bundle.main.url(forResource: "shaders", withExtension: nil) else {
            throw EngineError.decode("the pipeline's shaders are missing from the app")
        }
        // «Портрет»: the faces for the post pipeline (Android: Camera2's face detection, Parameters.portraitFaces)
        var faces = PortraitFaces.Faces()
        if PortraitFaces.enabled {
            let faceStarted = Date()
            faces = PortraitFaces.detect(burst, dngOrientation: info.orientation, black: blackMean, white: info.white)
            log.append("portrait: \(faces.note) in \(ms(since: faceStarted)) ms")
        }
        let photo: SCAMPhoto
        do {
            photo = try SCAMShot.processBurst(atPath: nch.path, dng: burst.dng, settings: PostSettings.json(),
                                              faces: faces.boxes.map { NSNumber(value: $0) }, facesDetected: faces.detected,
                                              shaders: shaders.path)
        } catch let error as NSError {
            log += (error.userInfo["log"] as? [String]) ?? []
            throw error
        }
        log += photo.log
        log.append("merge \(Int(photo.mergeMilliseconds)) ms, post \(Int(photo.postMilliseconds)) ms, photo \(photo.width)x\(photo.height), \(memoryFootprint())")
        let format = ShotProcessor.photoFormat
        // P628: the watermark on the finished photo (upright, after every crop and resize) before its encoding; the Ultra HDR gain
        // map is left as the pipeline made it, as ScamProcessor does
        var rgba = photo.rgba
        var watermarkRecord: SCAMWatermarkRecord?
        if PhotoWatermark.on() {
            // P633: the pixels under it kept (WatermarkPatch) for the gallery's editor to take it off
            watermarkRecord = PhotoWatermark.applyRecorded(&rgba, width: photo.width, height: photo.height, metadata: metadata, dng: burst.dng,
                                                           shotTime: started, log: { log.append($0) }).record
        }
        var encodeLog: [String] = []
        var heic = try ShotProcessor.encode(rgba: rgba, width: photo.width, height: photo.height, metadata: metadata, format: format,
                                            gainMap: ShotProcessor.GainMap(photo: photo), log: { encodeLog.append($0) })
        log += encodeLog
        // P633: the record at the very end of the file (WatermarkStore), as PhotoOutput appends it to Android's files
        if let watermarkRecord { heic = PhotoWatermark.appendRecord(watermarkRecord, to: heic, log: { log.append($0) }) }
        log.append("burst processed in \(ms(since: started)) ms, HEIC \(heic.count) bytes, \(memoryFootprint())")
        return Result(heic: heic, uti: format.type.identifier, log: log)
    }
}
