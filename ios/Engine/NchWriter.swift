import Foundation

/// An NCH v11 burst file — the transport Android's ScamHybridBurst writes for the merge worker
/// (header 128 bytes, a 32-byte entry per frame, then the frames' 16-bit Bayer planes). The same file can be merged on
/// the phone (SCAMMerge) and replayed on a computer against the Android merge (tools/replay_hybrid_ab.py).
struct NchFrame {
    var raw: Data            // width × height UInt16, little endian, rows top-down
    var exposure: Float      // relative to frame 0 (1 for the same exposure)
    var iso: UInt32
    var noiseSlope: Float    // noise variance = slope · x + offset, x the normalised signal (DNG NoiseProfile)
    var noiseOffset: Float
    var orderMs: Float       // capture time relative to the newest frame
}

enum NchWriter {
    static let magic: UInt32 = 0x3143_484E
    static let roleNormal: UInt32 = 1

    /// cfa: 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR (Camera2 / DNG order); black per CFA site in that order.
    static func write(to url: URL, width: Int, height: Int, cfa: Int, white: Float, black: [Float], frames: [NchFrame]) throws {
        precondition(black.count == 4 && !frames.isEmpty)
        var header = Data(count: 128 + 32 * frames.count)
        func put<T>(_ value: T, at offset: Int) {
            withUnsafeBytes(of: value) { header.replaceSubrange(offset..<offset + $0.count, with: $0) }
        }
        put(magic.littleEndian, at: 0)
        put(UInt32(11).littleEndian, at: 4)
        put(UInt32(width).littleEndian, at: 8)
        put(UInt32(height).littleEndian, at: 12)
        put(UInt32(cfa).littleEndian, at: 16)
        put(UInt32(frames.count).littleEndian, at: 20)
        put(white.bitPattern.littleEndian, at: 24)
        for i in 0..<4 { put(black[i].bitPattern.littleEndian, at: 28 + 4 * i) }
        put(UInt32(16).littleEndian, at: 44)   // flags: no effective-frame map
        put(UInt32(0).littleEndian, at: 48)    // base index
        put(UInt32(1).littleEndian, at: 52)    // output grid 1
        put(UInt32(1).littleEndian, at: 56)    // colour block 1 (Bayer)
        for (i, f) in frames.enumerated() {
            let o = 128 + 32 * i
            put(roleNormal.littleEndian, at: o)
            put(f.exposure.bitPattern.littleEndian, at: o + 4)
            put(f.iso.littleEndian, at: o + 8)
            put(f.noiseSlope.bitPattern.littleEndian, at: o + 12)
            put(f.noiseOffset.bitPattern.littleEndian, at: o + 16)
            put(f.orderMs.bitPattern.littleEndian, at: o + 20)
        }
        FileManager.default.createFile(atPath: url.path, contents: nil)
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }
        try handle.write(contentsOf: header)
        let plane = width * height * 2
        for f in frames {
            guard f.raw.count == plane else { throw CocoaError(.fileWriteInvalidFileName) }
            try handle.write(contentsOf: f.raw)
        }
    }
}
