import Foundation

/// «Why тон» V2 statistics and exposure, ported one to one from Android WhyV2Ae.java (P614): float arithmetic as there,
/// the log sum in Double, `fma` as its (a·b + c in Double, then Float).
enum WhyV2Ae {
    static let ownerPoints = "0.0000:0.0000,0.0625:0.0314,0.1250:0.0599,0.1875:0.0881,0.2500:0.1282,0.3125:0.1971,"
        + "0.3750:0.2867,0.4375:0.3915,0.5000:0.5100,0.5625:0.6347,0.6250:0.7542,0.6875:0.8577,0.7500:0.9350,0.8125:0.9757,"
        + "0.8750:0.9937,0.9375:1.0000,1.0000:1.0000"
    static let bins = 4096
    /// The reference's AE noise without a camera noise profile (NoiseModeler.reportedMean(null): S 0.00015, O 1e-5).
    static let referenceNoiseFallback: (Float, Float) = (0.00015, 1e-5)

    struct Params {
        var aeTarget: Float = 0.14999999, maxBoost: Float = 5.0, minLimit: Float = 0.5, facePriority: Float = 0.7
        var nightDim: Float = 0.049999997, hlOverflow: Float = 2.0, hlBlend: Float = 0.5
        var brightThresh: Float = 0.90000004, darkThresh: Float = 12.5, darkPixelThresh: Float = 22, hlBoost: Float = 1.1
        var contrastBoost: Float = 0.3
        var expEv: Float = 0
        var highlightStr: Float = 2.1000001, shadowStr: Float = 2.0
        var weightExtHl: Float = 0.7, weightHl: Float = 1.0, weightMid: Float = 2.0, weightShadow: Float = 1.0
        var weightCenter: Float = 1.0, blendSmoothness: Float = 0.45000002, macroContrast: Float = 1.1, clarity: Float = 0
        var toneOperator: Int32 = 3, agxLook: Int32 = 4, gammaType: Int32 = 0
        var gamma: Float = 2.2, filmToe: Float = 0
        var agxSlope: Float = 2.45, agxSp: Float = 1.35, agxTp: Float = 2.0, agxMinEv: Float = -8.5, agxMaxEv: Float = 3.5
        var agxSat: Float = 1.0, agxEv: Float = 0
        var agxHueLock = true
        var agxInset: Float = 50
        var acesToe: Float = 0.05, acesPreGain: Float = 1.0, acesA: Float = 2.61, acesD: Float = 0.59
        var uchiPreGain: Float = 1.0, uchiPostGain: Float = 1.0, uchiP: Float = 1.0, uchiA: Float = 1.0, uchiM: Float = 0.22
        var uchiL: Float = 0.4, uchiC: Float = 1.33, uchiB: Float = 0
        var vibStrength: Float = 0, vibSaturated: Float = 0, skinProtect: Float = 1.0, vibSky: Float = 0.25, vibGreen: Float = 0.05
        var vibHl: Float = 0.25, vibMid: Float = 0, vibShadow: Float = 0, skinHueShift: Float = 0, skinSatShift: Float = 0
        var skinHueCenterDeg: Float = 41.25, skinLumaMin: Float = 0.16
        var shoulderStrength: Float = 0.6, shoulderKnee: Float = 0.6, shoulderHeadroom: Float = 0.35
        var curvePoints = WhyV2Ae.ownerPoints
        var curveMods = [Float](repeating: 0, count: 7)

        var skinHueCenterRad: Float { skinHueCenterDeg * 0.017453292 }
    }

    struct Hist {
        var luma = [Int32](repeating: 0, count: WhyV2Ae.bins)
        var hl = [Int32](repeating: 0, count: WhyV2Ae.bins)
        var total = 0
    }

    struct Result {
        var ae: Float = 1, clip: Float = 1, deficit: Float = 1, geo: Float = 0, meter: Float = 0
        var p10: Float = 0, p50: Float = 0, p98: Float = 0, p995: Float = 0, ovShare: Float = 0
        var spread: Float = 0, rr: Float = 0, m: Float = 0, area: Float = 0, realPeak: Float = 0, flat: Float = 0
        var effS: Float = 0, effH: Float = 0, contrastBoost: Float = 0, noiseCap: Float = 0
        var agxSlope: Float = 0, acesD: Float = 0, uchiA: Float = 0, macroContrast: Float = 0

        var describe: String {
            String(format: "ae=%.4f clip=%.3f geo=%.5f meter=%.5f p10=%.5f p50=%.5f p98=%.5f p995=%.5f deficit=%.4f noiseCap=%.3f"
                   + " ov=%.4f spread=%.4f rr=%.3f m=%.3f area=%.3f peak=%.4f flat=%.3f effS=%.4f effH=%.4f cb=%.4f",
                   ae, clip, geo, meter, p10, p50, p98, p995, deficit, noiseCap, ovShare, spread, rr, m, area, realPeak, flat,
                   effS, effH, contrastBoost)
        }
    }

    static func stride(width: Int, height: Int) -> Int { max(1, max(width / 1024, height / 768)) }
    static func samples(_ n: Int, stride: Int) -> Int { (n + stride - 1) / stride }
    static func clipCeiling(_ ceiling: Float) -> Float { ceiling > 1.05 ? ceiling : 1 }

    static func add(_ h: inout Hist, _ r: Float, _ g: Float, _ b: Float, clip: Float) {
        let vLuma: Float = 0.2126 * r + 0.7152 * g + 0.0722 * b
        let vHl = max(vLuma, max(r, max(g, b)))
        let inv: Float = 1.0 / max(clip, 0.0001)
        h.luma[bin(vLuma * inv)] += 1
        h.hl[bin(vHl * inv)] += 1
        h.total += 1
    }

    static func bin(_ v: Float) -> Int {
        if v.isNaN { return 0 }
        let c = max(0, min(1, v))
        return max(0, min(bins - 1, Int(c * 4095.99)))
    }

    static func fma(_ a: Float, _ b: Float, _ c: Float) -> Float { Float(Double(a) * Double(b) + Double(c)) }

    static func finish(_ h: Hist, _ p: Params, clipCeiling ceiling: Float, noiseS: Float, noiseO: Float) -> Result {
        var r = Result()
        let clip = clipCeiling(ceiling)
        r.clip = clip
        var logSum = 0.0
        for i in 0..<bins {
            let n = h.luma[i]
            if n == 0 { continue }
            var v: Float = (Float(i) / 4096 + 0.0001220703125) * clip
            if v < 1e-4 { v = 1e-4 }
            logSum += Double(Float(log(Double(v)))) * Double(n)
        }
        let total = max(h.total, 1)
        let totalF = Float(total), invTotal: Float = 1 / totalF
        let step: Float = clip * 0.000244140625
        var p10: Float = 0, p50: Float = 0
        var cum: Int32 = 0
        var half = false
        for i in 0..<bins {
            cum += h.luma[i]
            let share = Float(cum) * invTotal
            let v = step * Float(i)
            if share >= 0.1 && p10 == 0 { p10 = v }
            if share >= 0.5 { p50 = v; half = true; break }
        }
        if !half { p50 = 0 }
        var p98 = clip, p995: Float = 0
        cum = 0
        for i in 0..<bins {
            cum += h.hl[i]
            let share = Float(cum) * invTotal
            let v = step * Float(i)
            if share >= 0.98 && p98 == clip { p98 = v }
            if share >= 0.995 && p995 == 0 { p995 = v }
        }
        if p98 == clip { p98 = clip * 0.99975586 }
        if p995 == 0 { p995 = p98 }
        r.p10 = p10; r.p50 = p50; r.p98 = p98; r.p995 = p995
        let geo = Float(exp(logSum / Double(total)))
        r.geo = geo
        let meter = geo // no face on the photo path (as Android: u_has_face 0)
        r.meter = meter
        let ev = Float(pow(2.0, Double(p.expEv)))
        let maxB = max(ev, 1) * p.maxBoost, minL = min(ev, 1) * p.minLimit
        var deficit: Float = 1, capped: Float
        var noiseCap: Float = 0
        if meter > 1e-4 {
            let a = p.aeTarget * ev / meter
            var aeC = maxB < a ? maxB : a
            if aeC < minL { aeC = minL }
            var aeF = aeC
            if p.hlOverflow < 9.99 && p995 > 0.01 && aeC * p995 > p.hlOverflow {
                let lim = p.hlOverflow / p995
                let blend = fma(0, 1 - p.hlBlend, p.hlBlend)
                aeF = fma(blend, aeC - lim, lim)
            }
            if aeF < minL { aeF = minL }
            if aeF > 0.001 && aeC > 0.001 && aeF < aeC { deficit = aeC / aeF }
            capped = aeF
            if noiseS > 0 || noiseO > 0 {
                var v = fma(noiseS, 0.5, noiseO)
                if v < 1e-10 { v = 1e-10 }
                let sigma = Float(sqrt(Double(v)))
                var d = max(sigma * 0.5, 0.026)
                if !(d < sigma) { d = sigma }
                var nd: Float = p.nightDim > 0.2 ? 0.2 : p.nightDim
                if p.nightDim < 0.01 { nd = 0.01 }
                var cap = nd / d
                if cap < 1 { cap = 1 }
                noiseCap = cap
                if aeF > cap { capped = cap }
            }
        } else {
            capped = 1
        }
        var ae = maxB < capped ? maxB : capped
        if ae < minL { ae = minL }
        r.ae = ae
        r.deficit = deficit
        r.noiseCap = noiseCap
        var t = p.brightThresh / (max(ae, 0.001) * clip)
        t = max(min(t, 1), 0)
        let j = Int(t * 4095.99)
        var ov: Int64 = 0
        for i in max(0, j)..<bins { ov += Int64(h.hl[i]) }
        let f = Float(ov) / totalF
        r.ovShare = f
        let sp10 = Float(sqrt(Double(max(ae * p10, 0))))
        let a98 = max(ae * p98, 0)
        let sp98 = Float(sqrt(Double(a98 > ae ? ae : a98)))
        let spread = sp98 - sp10
        let rr = spread / max(p.darkThresh * 0.15, 0.001)
        let sp50 = Float(sqrt(Double(max(ae * p50, 0))))
        let m = max(min(fma(sp50, 4.5454545, -0.9090909), 1), 0)
        let rrHalf = fma(rr, 0.5, 1)
        var effS = p.shadowStr * fma(m * m, -0.35, 1) * rr * rrHalf / (rrHalf + rr)
        effS = fma(Float(log(Double(max(deficit, 1))) / log(2.0)), 0.5, effS)
        effS = max(effS, 0)
        let s20 = f * 20
        var area: Float = s20 > 1 ? 1 : s20
        if s20 < 0 { area = 0 }
        area = area * area * (3 - 2 * area)
        var tail = max(ae * p995 - a98, 0)
        let lim = 1.5 * max(p.brightThresh, 0.5)
        if lim < tail { tail = lim }
        let realPeak = fma(area, 0.25 * tail, a98)
        let bt85 = p.brightThresh * 0.85
        var soft: Float = 0
        if realPeak > bt85 {
            let ex = Float(log(Double(realPeak / bt85)) / log(2.0))
            let knee = Float(log(Double(p.brightThresh / bt85)) / log(2.0))
            if ex < knee {
                let u = ex / knee
                soft = u * u * (knee * 0.5)
            } else {
                soft = fma(knee, -0.5, ex)
            }
            soft *= 0.8333333
        }
        let bigArea = fma(f, 5, -0.25)
        let gate: Float = bigArea < 0 ? 1 : fma(bigArea > 1 ? 1 : bigArea, 0.35, 1)
        var q = 1 - spread / max(p.darkPixelThresh * 0.05, 0.001)
        q = max(q, 0)
        var effH = fma(area, 0.8, 0.2) * (gate * soft * max(p.hlBoost, 0))
        if !(effH < p.highlightStr) { effH = p.highlightStr }
        let flat = q * q * (3 - 2 * q)
        let cb = q * q * p.contrastBoost * (3 - 2 * q)
        r.spread = spread; r.rr = rr; r.m = m; r.area = area; r.realPeak = realPeak; r.flat = flat
        r.effS = effS
        r.effH = effH
        r.contrastBoost = cb
        r.agxSlope = p.agxSlope; r.acesD = p.acesD; r.uchiA = p.uchiA; r.macroContrast = p.macroContrast
        if cb > 1e-4 {
            r.acesD = max(fma(cb, -0.8, p.acesD), 0.1)
            r.agxSlope = p.agxSlope + cb
            r.uchiA = fma(cb, 0.75, p.uchiA)
            r.macroContrast = fma(cb, 0.3, p.macroContrast)
        }
        return r
    }

    static func exposures(_ r: Result) -> SIMD4<Float> {
        SIMD4(Float(pow(2.0, Double(-2.5 * r.effH))), Float(pow(2.0, Double(-1.2 * r.effH))), 1,
              Float(min(pow(2.0, Double(1.8 * r.effS)), 16.0)))
    }

    static func layerWeights(_ p: Params) -> SIMD4<Float> {
        SIMD4(p.weightExtHl, p.weightHl, p.weightMid, p.weightShadow)
    }

    static func levels(width: Int, height: Int) -> [(Int, Int)] {
        var out = [(width, height)]
        var w = width, h = height
        while w > 48 && h > 48 && out.count <= 11 {
            w >>= 1
            h = h > 3 ? h / 2 : 1
            out.append((w, h))
        }
        return out
    }

    /// Column-major 3x3 (as glUniformMatrix3fv without transpose; Metal float3x3 is column-major too).
    static func insetMatrix(_ strength: Float) -> [Float] {
        var t = strength > 1 ? strength * 0.01 : strength
        t = max(0, min(1, t))
        let m: [Double] = [0.842479062253094, 0.0423282422610123, 0.0423756549057051,
                           0.0784335999999992, 0.878468636469772, 0.0784336,
                           0.0792237451477643, 0.0791661274605434, 0.879142973793104]
        return (0..<9).map { i in
            let id: Double = i % 4 == 0 ? 1 : 0
            return Float((1 - Double(t)) * id + Double(t) * m[i])
        }
    }

    static func outsetMatrix(_ strength: Float) -> [Float] {
        let a = insetMatrix(strength).map(Double.init)
        let a00 = a[0], a10 = a[1], a20 = a[2], a01 = a[3], a11 = a[4], a21 = a[5], a02 = a[6], a12 = a[7], a22 = a[8]
        let r: [Double] = [
            a11 * a22 - a12 * a21, a02 * a21 - a01 * a22, a01 * a12 - a02 * a11,
            a12 * a20 - a10 * a22, a00 * a22 - a02 * a20, a02 * a10 - a00 * a12,
            a10 * a21 - a11 * a20, a01 * a20 - a00 * a21, a00 * a11 - a01 * a10]
        let det = a00 * r[0] + a01 * r[3] + a02 * r[6]
        let inv = abs(det) > 1e-12 ? 1 / det : 1
        var out = [Float](repeating: 0, count: 9)
        for i in 0..<3 { for j in 0..<3 { out[j * 3 + i] = Float(r[i * 3 + j] * inv) } }
        return out
    }
}

/// The own curve's 1024-sample table (Android WhyV2Curve.java, ToneCurveHelper of the reference app).
enum WhyV2Curve {
    static let defaultPoints = "0.0000:0.0000,0.1250:0.0688,0.2500:0.1600,0.3750:0.3188,0.5000:0.5000,0.6250:0.6812,"
        + "0.7500:0.8400,0.8750:0.9312,1.0000:1.0000"

    static func parse(_ text: String) -> [Float] {
        var out: [(Float, Float)] = []
        for pair in text.split(separator: ",") {
            let xy = pair.trimmingCharacters(in: .whitespaces).split(separator: ":", omittingEmptySubsequences: false)
            guard xy.count == 2, let x = Float(xy[0].trimmingCharacters(in: .whitespaces)),
                  let y = Float(xy[1].trimmingCharacters(in: .whitespaces)) else { continue }
            out.append((max(0, min(1, x)), max(0, min(1, y))))
        }
        if out.count < 2 { return parse(defaultPoints) }
        // Java's List.sort is stable, as Swift's sort on arrays.
        out.sort { $0.0 < $1.0 }
        return out.flatMap { [$0.0, $0.1] }
    }

    static func applyModifiers(_ p: [Float], _ m: [Float]) -> [Float] {
        if m.allSatisfy({ abs($0) < 1e-4 }) { return p }
        var out = p
        var i = 0
        while i + 1 < p.count {
            out[i + 1] = max(0, min(1, p[i + 1] + deltaY(p[i], m)))
            i += 2
        }
        return out
    }

    static func deltaY(_ x: Float, _ m: [Float]) -> Float {
        var v = coeff(curveB, x) * m[0]
        v -= coeff(curveC, x) * m[1]
        v -= coeff(curveE, x) * m[2]
        v -= coeff(curveF, x) * m[3]
        v += coeff(curveG, x) * m[4]
        v -= coeff(curveH, x) * m[5]
        return m[6] / 200 + v * 2.5e-6
    }

    static let curveB: [Float] = [0, 0, 4, 50, 178, 401, 674, 906, 1000, 906, 674, 401, 178, 50, 4, 0, 0]
    static let curveC: [Float] = [0, 349, 659, 889, 1000, 964, 769, 431, 0, -431, -769, -964, -1000, -889, -659, -349, 0]
    static let curveE: [Float] = [0, 349, 659, 889, 1000, 962, 769, 471, 206, 51, 6, 0, 0, 0, 0, 0, 0]
    static let curveF: [Float] = [0, 0, 0, 0, 0, 0, -6, -51, -206, -471, -769, -962, -1000, -889, -659, -349, 0]
    static let curveG: [Float] = [0, 667, 1000, 956, 697, 402, 180, 61, 14, 1, 0, 0, 0, 0, 0, 0, 0]
    static let curveH: [Float] = [0, 0, 0, 0, 0, 0, 0, -1, -14, -61, -180, -402, -697, -956, -1000, -667, 0]

    static func coeff(_ t: [Float], _ x: Float) -> Float {
        let idx = x * 16
        let i = Int(idx)
        if i < 0 { return t[0] }
        if i >= 16 { return t[16] }
        let f = idx - Float(i)
        return t[i] + f * f * (3 - f * 2) * (t[i + 1] - t[i])
    }

    static func stored(_ p: [Float]) -> [Float] {
        var parts: [String] = []
        var i = 0
        while i + 1 < p.count {
            parts.append(String(format: "%.4f:%.4f", locale: Locale(identifier: "en_US_POSIX"), p[i], p[i + 1]))
            i += 2
        }
        return parse(parts.joined(separator: ","))
    }

    static func lut(_ pts: [Float], _ n: Int) -> [Float] {
        var out = [Float](repeating: 0, count: n)
        let k = pts.count / 2
        if k < 2 {
            for i in 0..<n { out[i] = Float(i) / Float(n - 1) }
            return out
        }
        var x = (0..<k).map { pts[2 * $0] }, y = (0..<k).map { pts[2 * $0 + 1] }
        x[0] = 0
        x[k - 1] = 1
        for i in 1..<k where !(x[i] > x[i - 1]) { x[i] = min(1, x[i - 1] + 0.001) }
        var h = [Float](repeating: 0, count: k - 1), d = h, m = [Float](repeating: 0, count: k)
        for i in 0..<(k - 1) {
            h[i] = x[i + 1] - x[i]
            d[i] = (y[i + 1] - y[i]) / max(1e-6, h[i])
        }
        m[0] = d[0]
        m[k - 1] = d[k - 2]
        if k > 2 { for i in 1..<(k - 1) { m[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) * 0.5 } }
        for i in 0..<(k - 1) {
            if abs(d[i]) < 1e-7 { m[i] = 0; m[i + 1] = 0; continue }
            let a = m[i] / d[i], b = m[i + 1] / d[i]
            if a < 0 { m[i] = 0 }
            if b < 0 { m[i + 1] = 0 }
            let s = a * a + b * b
            if s > 9 {
                let t = 3 / Float(sqrt(Double(s)))
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
            }
        }
        var seg = 0
        for j in 0..<n {
            let t = Float(j) / Float(n - 1)
            while seg < k - 2 && t > x[seg + 1] { seg += 1 }
            let hh = max(1e-6, x[seg + 1] - x[seg])
            let u = max(0, min(1, (t - x[seg]) / hh))
            let u2 = u * u, u3 = u2 * u
            let h00 = u3 * 2 - u2 * 3 + 1, h10 = u3 - 2 * u2 + u, h01 = -2 * u3 + u2 * 3, h11 = u3 - u2
            let v = h00 * y[seg] + h10 * hh * m[seg] + h01 * y[seg + 1] + h11 * hh * m[seg + 1]
            out[j] = max(0, min(1, v))
        }
        return out
    }

    static func samples(points: String, mods: [Float], n: Int = 1024) -> [Float] {
        lut(stored(applyModifiers(parse(points), mods)), n)
    }
}
