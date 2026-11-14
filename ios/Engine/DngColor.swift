import Foundation
import ImageIO
import simd

/// Sensor facts of a Bayer shot from the camera's own DNG ({DNG} of ImageIO): levels, noise profile, white balance and the
/// camera → linear sRGB (D65) matrix by the DNG colour model (ForwardMatrix when present, else the inverted ColorMatrix,
/// both interpolated between the two calibration illuminants by the shot's colour temperature).
struct DngInfo {
    var black: [Float] = [0, 0, 0, 0]
    var white: Float = 16383
    var neutral = SIMD3<Float>(1, 1, 1)          // AsShotNeutral (camera RGB of white)
    var noise: (slope: Float, offset: Float)?      // NoiseProfile (mean of the planes)
    var cameraToSRGB = matrix_identity_float3x3    // applied to white-balanced camera RGB
    var cct: Float = 0
    var orientation: Int = 1
    var notes: [String] = []

    init(dng data: Data) {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any] else {
            notes.append("DNG properties unavailable")
            return
        }
        orientation = (props[kCGImagePropertyOrientation as String] as? Int) ?? 1
        let dng = props["{DNG}"] as? [String: Any] ?? [:]
        func floats(_ key: String) -> [Float]? {
            if let a = dng[key] as? [NSNumber] { return a.map(\.floatValue) }
            if let n = dng[key] as? NSNumber { return [n.floatValue] }
            return nil
        }
        if let b = floats("BlackLevel") { black = b.count >= 4 ? Array(b.prefix(4)) : [Float](repeating: b.first ?? 0, count: 4) }
        if let w = floats("WhiteLevel")?.first { white = w }
        if let n = floats("AsShotNeutral"), n.count == 3, n.allSatisfy({ $0 > 0 }) { neutral = SIMD3(n[0], n[1], n[2]) }
        if let p = floats("NoiseProfile"), p.count >= 2 {
            var s: Float = 0, o: Float = 0
            let pairs = p.count / 2
            for i in 0..<pairs { s += p[2 * i]; o += p[2 * i + 1] }
            noise = (s / Float(pairs), o / Float(pairs))
        }
        let cm1 = floats("ColorMatrix1").flatMap(Self.matrix), cm2 = floats("ColorMatrix2").flatMap(Self.matrix)
        let fm1 = floats("ForwardMatrix1").flatMap(Self.matrix), fm2 = floats("ForwardMatrix2").flatMap(Self.matrix)
        let il1 = Self.temperature(Int(floats("CalibrationIlluminant1")?.first ?? 17))
        let il2 = Self.temperature(Int(floats("CalibrationIlluminant2")?.first ?? 21))
        guard let c1 = cm1 else {
            notes.append("no ColorMatrix1: camera colours kept")
            return
        }
        let c2 = cm2 ?? c1
        // The shot's colour temperature: the neutral through the interpolated ColorMatrix, a few rounds (DNG SDK).
        var weight: Float = 0.5
        for _ in 0..<4 {
            let cm = Self.mix(c1, c2, weight)
            let xyz = cm.inverse * neutral
            let sum = xyz.x + xyz.y + xyz.z
            guard sum > 0 else { break }
            let x = xyz.x / sum, y = xyz.y / sum
            let n = (x - 0.3320) / (0.1858 - y)
            let t = 449 * n * n * n + 3525 * n * n + 6823.3 * n + 5520.33 // McCamy
            cct = t
            weight = Self.weight(t, il1, il2)
        }
        let xyzD50ToSRGB = simd_float3x3(rows: [SIMD3(3.1338561, -1.6168667, -0.4906146),
                                                SIMD3(-0.9787684, 1.9161415, 0.0334540),
                                                SIMD3(0.0719453, -0.2289914, 1.4052427)])
        if let f1 = fm1 {
            // white-balanced camera RGB → XYZ (D50): ForwardMatrix (DNG 1.2)
            cameraToSRGB = xyzD50ToSRGB * Self.mix(f1, fm2 ?? f1, weight)
            notes.append(String(format: "ForwardMatrix, CCT %.0f K, weight %.2f", cct, weight))
        } else {
            // XYZ (D50, Bradford from the neutral's white) → camera is ColorMatrix: inverted, white balanced after
            let cameraToXYZ = Self.mix(c1, c2, weight).inverse
            let white = cameraToXYZ * neutral
            let d50 = SIMD3<Float>(0.9642, 1, 0.8249)
            let adapt = Self.bradford(from: white / max(white.y, 1e-6), to: d50)
            cameraToSRGB = xyzD50ToSRGB * adapt * cameraToXYZ * simd_float3x3(diagonal: neutral)
            notes.append(String(format: "ColorMatrix (inverted), CCT %.0f K, weight %.2f", cct, weight))
        }
    }

    /// White-balance gains of the camera channels (green 1).
    var gains: SIMD3<Float> { SIMD3(neutral.y / neutral.x, 1, neutral.y / neutral.z) }

    static func matrix(_ v: [Float]) -> simd_float3x3? {
        guard v.count >= 9 else { return nil }
        return simd_float3x3(rows: [SIMD3(v[0], v[1], v[2]), SIMD3(v[3], v[4], v[5]), SIMD3(v[6], v[7], v[8])])
    }

    static func mix(_ a: simd_float3x3, _ b: simd_float3x3, _ w: Float) -> simd_float3x3 {
        simd_float3x3(columns: (a.columns.0 * w + b.columns.0 * (1 - w), a.columns.1 * w + b.columns.1 * (1 - w),
                                a.columns.2 * w + b.columns.2 * (1 - w)))
    }

    /// Weight of illuminant 1 at temperature t (interpolation in 1/T, as the DNG SDK).
    static func weight(_ t: Float, _ t1: Float, _ t2: Float) -> Float {
        guard t1 != t2, t > 0 else { return 1 }
        let (lo, hi) = t1 < t2 ? (t1, t2) : (t2, t1)
        let tc = min(max(t, lo), hi)
        let w = (1 / tc - 1 / t2) / (1 / t1 - 1 / t2)
        return min(max(w, 0), 1)
    }

    /// EXIF LightSource codes of the calibration illuminants → colour temperature.
    static func temperature(_ code: Int) -> Float {
        switch code {
        case 17, 3: return 2856      // standard light A, tungsten
        case 18: return 4874         // B
        case 19: return 6774         // C
        case 20: return 5503         // D55
        case 21: return 6504         // D65
        case 22: return 7504         // D75
        case 23: return 5003         // D50
        case 24: return 3200         // ISO studio tungsten
        case 2, 14: return 4230      // fluorescent, cool white
        case 12: return 6430         // daylight fluorescent
        case 1, 9: return 5500
        default: return 5000
        }
    }

    static func bradford(from src: SIMD3<Float>, to dst: SIMD3<Float>) -> simd_float3x3 {
        let m = simd_float3x3(rows: [SIMD3(0.8951, 0.2664, -0.1614), SIMD3(-0.7502, 1.7135, 0.0367), SIMD3(0.0389, -0.0685, 1.0296)])
        let s = m * src, d = m * dst
        let scale = simd_float3x3(diagonal: SIMD3(d.x / s.x, d.y / s.y, d.z / s.z))
        return m.inverse * scale * m
    }
}
