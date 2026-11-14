import Foundation

/// A frame of the series as captured (Android's ImageFrame for the merge): its Bayer plane and what its capture result says.
struct NchFrame {
    var raw: Data            // width × height UInt16, little endian, rows top-down
    var exposure: Float      // exposure x ISO relative to the series' first frame (the table's provisional value)
    var iso: UInt32
    var noiseSlope: Float    // noise variance = slope · x + offset, x the normalised signal (the DNG NoiseProfile at this ISO)
    var noiseOffset: Float
    var orderMs: Float       // capture time relative to the newest frame
    /// ImageFrame.CaptureRole: 1 N, 3 bracketed (LONG, Shasta), 5 ultrashort (EXTRA_SHORT, Bento) - as the plan asked and the
    /// delivered exposure confirmed (HybridPlan.classify)
    var role: Int = NchWriter.roleNormal
    var exposureNs: Int64 = 0  // measuredExposure
    var timestampNs: Int64 = 0 // the photo's timestamp
}

/// An NCH burst file - the transport Android's ScamHybridBurst writes for the merge worker (header 128 bytes, a 32-byte entry per
/// frame, the frames' 16-bit Bayer planes) - written as v12: every plane on its own 4096-byte page in capture order, so the frame
/// table the merge gets (Engine/post/HybridSelect.hpp: ScamHybridBurst's base, roles, ratios, noise, orderMs) is written over the
/// capture's in place without moving a plane. Beside it the capture facts (`<file>.json`, Engine/post/ShotTransport.hpp). The same
/// file is merged on the phone (SCAMShot) and replays on a computer against the Android merge (tools/hybrid_replay, the lab).
enum NchWriter {
    static let magic: UInt32 = 0x3143_484E
    static let roleNormal = 1, roleBracketed = 3, roleUltrashort = 5
    static let page = 4096

    /// cfa: 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR (Camera2 / DNG order); black per CFA site in that order.
    static func write(to url: URL, width: Int, height: Int, cfa: Int, white: Float, black: [Float], frames: [NchFrame]) throws {
        precondition(black.count == 4 && !frames.isEmpty)
        let plane = width * height * 2
        let headerPages = (128 + 32 * frames.count + page - 1) / page
        let planePages = (plane + page - 1) / page
        var header = Data(count: headerPages * page)
        func put<T>(_ value: T, at offset: Int) {
            withUnsafeBytes(of: value) { header.replaceSubrange(offset..<offset + $0.count, with: $0) }
        }
        put(magic.littleEndian, at: 0)
        put(UInt32(12).littleEndian, at: 4)
        put(UInt32(width).littleEndian, at: 8)
        put(UInt32(height).littleEndian, at: 12)
        put(UInt32(cfa).littleEndian, at: 16)
        put(UInt32(frames.count).littleEndian, at: 20)
        put(white.bitPattern.littleEndian, at: 24)
        for i in 0..<4 { put(black[i].bitPattern.littleEndian, at: 28 + 4 * i) }
        put(UInt32(16).littleEndian, at: 44)   // flags: no effective-frame map (ShotRun sets the shot's)
        put(UInt32(0).littleEndian, at: 48)    // base index
        put(UInt32(1).littleEndian, at: 52)    // output grid 1
        put(UInt32(1).littleEndian, at: 56)    // colour block 1 (Bayer)
        for (i, f) in frames.enumerated() {
            let o = 128 + 32 * i
            put(UInt32(f.role).littleEndian, at: o)
            put(f.exposure.bitPattern.littleEndian, at: o + 4)
            put(f.iso.littleEndian, at: o + 8)
            put(f.noiseSlope.bitPattern.littleEndian, at: o + 12)
            put(f.noiseOffset.bitPattern.littleEndian, at: o + 16)
            put(f.orderMs.bitPattern.littleEndian, at: o + 20)
            put(UInt32(headerPages + i * planePages).littleEndian, at: o + 28) // the plane's page
        }
        FileManager.default.createFile(atPath: url.path, contents: nil)
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }
        try handle.write(contentsOf: header)
        let padding = Data(count: planePages * page - plane)
        for f in frames {
            guard f.raw.count == plane else { throw CocoaError(.fileWriteInvalidFileName) }
            try handle.write(contentsOf: f.raw)
            if !padding.isEmpty { try handle.write(contentsOf: padding) }
        }
    }

    /// The capture facts the merge's frame rules and the crop read (ShotTransport.hpp's sidecar): the digital zoom's residual, each
    /// frame's capture role, measured exposure, ISO, timestamp and noise profile in the file's order, and the «16:9» cut.
    static func writeFacts(to url: URL, frames: [NchFrame], zoom: Double, fullHeight: Int, firstRow: Int) throws {
        let list: [[String: Any]] = frames.map { f in
            ["role": f.role, "exposureNs": f.exposureNs, "iso": Int(f.iso), "timestampNs": f.timestampNs,
             "slope": Double(f.noiseSlope), "offset": Double(f.noiseOffset)]
        }
        var facts: [String: Any] = ["zoom": zoom, "frames": list]
        if fullHeight > 0 {
            facts["fullHeight"] = fullHeight
            facts["firstRow"] = firstRow
        }
        let data = try JSONSerialization.data(withJSONObject: facts, options: [.sortedKeys])
        try data.write(to: url, options: .atomic)
    }
}
