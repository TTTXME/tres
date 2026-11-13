import Foundation

/// ScamHybridBurst's order of the N frames of a burst: the base is the sharpest (RawFrameQuality) of the newest BASE_CANDIDATES
/// within BASE_WINDOW_NS of the newest frame; the donors follow newest first, the blurred ones (below sharp_select x the median,
/// P331) left out; the exposures become ratios to the base.
enum BurstOrder {
    static let baseWindowMs: Float = 205
    static let baseCandidates = 4
    static let sharpSelect: Float = 0.75, sharpKeep = 8, sharpMaxShare: Float = 1 / 3

    /// RawFrameQuality.score (block 1): edge energy above the gradients' noise floor over the mean signal, on a 64 x 48 grid
    static func score(_ raw: Data, width: Int, height: Int) -> Double {
        guard width >= 32, height >= 32, raw.count >= width * height * 2 else { return .nan }
        let period = 2
        var gradients: [Double] = []
        gradients.reserveCapacity(64 * 48)
        var signal = 0.0
        raw.withUnsafeBytes { (p: UnsafeRawBufferPointer) in
            let px = p.bindMemory(to: UInt16.self)
            for gy in 0..<48 {
                for gx in 0..<64 {
                    let x = (width / 10 + gx * (width * 4 / 5) / 64) / period * period + (gx % 2)
                    let y = (height / 10 + gy * (height * 4 / 5) / 48) / period * period + (gy % 2)
                    if x < period || y < period || x + period >= width || y + period >= height { continue }
                    let at = y * width + x
                    let left = Int(UInt16(littleEndian: px[at - period])), right = Int(UInt16(littleEndian: px[at + period]))
                    let up = Int(UInt16(littleEndian: px[at - period * width])), down = Int(UInt16(littleEndian: px[at + period * width]))
                    gradients.append(hypot(Double(right - left), Double(down - up)))
                    signal += Double(left + right + up + down) * 0.25
                }
            }
        }
        let count = gradients.count
        if count < 64 || signal <= 0 { return .nan }
        gradients.sort()
        let floor = gradients[count / 2] * 2.5
        var edges = 0.0
        for i in (count * 4 / 5)..<count { edges += max(0, gradients[i] - floor) }
        return edges / Double(count - count * 4 / 5) / (signal / Double(count))
    }

    static func medianFinite(_ v: [Float]) -> Float {
        let f = v.filter { $0.isFinite }.sorted()
        if f.count < 4 { return .nan }
        return f.count & 1 == 1 ? f[f.count / 2] : 0.5 * (f[f.count / 2 - 1] + f[f.count / 2])
    }

    /// ScamHybridBurst.blurredDonors
    static func blurredDonors(_ scores: [Float], base: Int, ratio: Float) -> [Int] {
        let median = medianFinite(scores)
        guard ratio > 0, median > 0 else { return [] }
        let limit = ratio * median
        let soft = scores.indices.filter { $0 != base && scores[$0].isFinite && scores[$0] < limit }.sorted { scores[$0] < scores[$1] }
        let donors = scores.count - (base >= 0 ? 1 : 0)
        let most = min(Int((sharpMaxShare * Float(donors)).rounded(.down)), scores.count - sharpKeep)
        return Array(soft.prefix(max(0, most)))
    }

    static func arrange(_ input: [NchFrame], width: Int, height: Int, log: inout [String]) -> [NchFrame] {
        guard !input.isEmpty else { return input }
        let normal = input.sorted { $0.orderMs > $1.orderMs } // newest first
        var scores = [Float](repeating: .nan, count: normal.count)
        let anchor = normal[0].orderMs
        var best = 0
        var bestScore = -1.0
        for i in 0..<min(baseCandidates, normal.count) {
            if anchor - normal[i].orderMs > baseWindowMs { break }
            let s = score(normal[i].raw, width: width, height: height)
            scores[i] = Float(s)
            let v = s.isNaN ? 0 : s
            if v > bestScore { bestScore = v; best = i }
        }
        var blurred = Set<Int>()
        if normal.count > sharpKeep {
            for i in normal.indices where scores[i].isNaN { scores[i] = Float(score(normal[i].raw, width: width, height: height)) }
            blurred = Set(blurredDonors(scores, base: best, ratio: min(sharpSelect, 1)))
            if !blurred.isEmpty { log.append("hybrid sharpness: median \(medianFinite(scores)), below x\(sharpSelect) left out: \(blurred.sorted())") }
        }
        let base = normal[best]
        let ref = Double(base.exposure) // exposures are ratios to one frame: their ratio to the base is product / product
        var out: [NchFrame] = []
        for (i, f) in ([(best, base)] + normal.enumerated().filter { $0.offset != best }.map { ($0.offset, $0.element) }) {
            if i != best && blurred.contains(i) { continue }
            var g = f
            let ratio = Double(f.exposure) / ref
            if !(ratio > 1.0 / 512) || !(ratio < 512) {
                log.append("hybrid: frame dropped, exposure x\(ratio) of the base is outside the merge range")
                continue
            }
            g.exposure = Float(ratio)
            out.append(g)
        }
        log.append(String(format: "base frame: %.1f ms before the newest, score %.4f, %d of %d frames", -base.orderMs, bestScore, out.count, input.count))
        return out
    }
}
