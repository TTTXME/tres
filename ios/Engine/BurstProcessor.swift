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

    /// The facts beside the last burst (NchWriter.writeFacts: the frames' capture roles and exposures, the zoom, the 16:9 cut)
    static var lastFactsURL: URL { lastBurstURL.appendingPathExtension("json") }

    /// zoom: the press's digital zoom over the module's own (ZoomController.processingResidual; nil: the burst's, BayerBurst.zoom) -
    /// SabreCrop's window of the frames before the merge and the photo's crop after the pipeline (Engine/post/ShotRun.hpp).
    static func process(_ burst: BayerBurst, metadata: [String: Any], zoom: Double? = nil) throws -> Result {
        guard MetalEngine.shared != nil else { throw EngineError.noDevice }
        guard burst.frames.count >= 1, burst.width >= 64, burst.height >= 64 else { throw EngineError.decode("empty burst") }
        var log = burst.report
        let started = Date()
        let info = DngInfo(dng: burst.dng)
        log += info.notes.map { "dng: " + $0 }
        var frames = burst.frames
        let blackMean = info.black.reduce(0, +) / 4
        // the frame the DNG is (the series' first); the noise of the others is its profile at their ISO (Camera2 gives each frame its
        // own SENSOR_NOISE_PROFILE; the iPhone's DNG profile is the first frame's)
        let iso0 = Float(max(frames[0].iso, 1))
        var source = "DNG NoiseProfile at each frame's ISO"
        var noise = info.noise
        let normals = frames.indices.filter { frames[$0].role == NchWriter.roleNormal }
        if noise == nil, normals.count >= 2,
           let estimate = NoiseEstimate.fromPair(frames[normals[0]].raw, frames[normals[1]].raw, width: burst.width, height: burst.height,
                                                 cfa: burst.cfa, black: blackMean, white: info.white) {
            // the pair's ISO is the first N frame's: scaled to the DNG frame's
            let k = iso0 / Float(max(frames[normals[0]].iso, 1))
            noise = (estimate.0 * k, estimate.1 * k * k)
            source = "estimated from N frames \(normals[0]) / \(normals[1])"
        }
        let (slope, offset) = noise ?? (1e-4, 1e-6)
        if noise == nil { source = "fallback (no profile, one frame)" }
        for i in frames.indices {
            let k = Float(frames[i].iso) / iso0
            frames[i].noiseSlope = slope * k
            frames[i].noiseOffset = offset * k * k
        }
        let shotZoom = max(1, zoom ?? burst.zoom)
        let roles = frames.map(\.role)
        log.append("burst \(burst.width)x\(burst.height) cfa=\(burst.cfa) frames=\(frames.count) (N \(roles.filter { $0 == NchWriter.roleNormal }.count),"
                   + " bracketed \(roles.filter { $0 == NchWriter.roleBracketed }.count), ultrashort \(roles.filter { $0 == NchWriter.roleUltrashort }.count))"
                   + " black=\(info.black) white=\(info.white) noise \(source): S=\(slope) O=\(offset) neutral=\(info.neutral)"
                   + " orientation=\(info.orientation) zoom=\(String(format: "%.3f", shotZoom))")

        let nch = lastBurstURL
        let writeStarted = Date()
        // the frames in capture order; the merge's frame table (the base, roles, ratios, noise, orderMs: ScamHybridBurst's rules) and
        // the zoom's window are ShotRun's (Engine/post/HybridSelect.hpp, SabreCrop.hpp), from these facts
        try NchWriter.write(to: nch, width: burst.width, height: burst.height, cfa: burst.cfa, white: info.white, black: info.black,
                            frames: frames)
        try NchWriter.writeFacts(to: lastFactsURL, frames: frames, zoom: shotZoom, fullHeight: burst.fullHeight, firstRow: burst.firstRow)
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
                                              faces: faces.boxes.map { NSNumber(value: $0) }, facesDetected: faces.detected, front: burst.front,
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
