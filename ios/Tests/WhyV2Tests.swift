import Accelerate
import Metal
import XCTest
@testable import SCAMERA

/// The Android WhyV2ToneTest cases for the Swift port of WhyV2Ae / WhyV2Curve, and the Metal pipeline on a synthetic image.
final class WhyV2AeTests: XCTestCase {
    private func hist(_ values: [(Float, Float, Float)], _ counts: [Int], clip: Float) -> WhyV2Ae.Hist {
        var h = WhyV2Ae.Hist()
        for (v, n) in zip(values, counts) { for _ in 0..<n { WhyV2Ae.add(&h, v.0, v.1, v.2, clip: clip) } }
        return h
    }

    func testStrideAndLevels() {
        XCTAssertEqual(WhyV2Ae.stride(width: 4096, height: 3072), 4)
        XCTAssertEqual(WhyV2Ae.stride(width: 4000, height: 3000), 3)
        XCTAssertEqual(WhyV2Ae.stride(width: 800, height: 600), 1)
        XCTAssertEqual(WhyV2Ae.stride(width: 8192, height: 6144), 8)
        XCTAssertEqual(WhyV2Ae.samples(4000, stride: 4), 1000)
        XCTAssertEqual(WhyV2Ae.samples(4000, stride: 3), 1334)
        let lv = WhyV2Ae.levels(width: 4096, height: 3072)
        XCTAssertEqual(lv.count, 7)
        XCTAssertEqual(lv[1].0, 2048); XCTAssertEqual(lv[1].1, 1536)
        XCTAssertEqual(lv[6].0, 64); XCTAssertEqual(lv[6].1, 48)
        let odd = WhyV2Ae.levels(width: 4001, height: 3001)
        XCTAssertEqual(odd[1].0, 2000); XCTAssertEqual(odd[1].1, 1500)
        XCTAssertEqual(odd.last!.0, 62); XCTAssertEqual(odd.last!.1, 46)
        XCTAssertEqual(WhyV2Ae.levels(width: 48, height: 400).count, 1)
        XCTAssertEqual(WhyV2Ae.levels(width: 1 << 20, height: 1 << 20).count, 12)
    }

    func testFlatGreyScene() {
        let p = WhyV2Ae.Params()
        let r = WhyV2Ae.finish(hist([(0.2, 0.2, 0.2)], [1000], clip: 1), p, clipCeiling: 1, noiseS: 0, noiseO: 0)
        let v: Float = 819 / 4096
        XCTAssertEqual(r.p10, v, accuracy: 1e-6)
        XCTAssertEqual(r.p50, v, accuracy: 1e-6)
        XCTAssertEqual(r.p98, v, accuracy: 1e-6)
        XCTAssertEqual(r.p995, v, accuracy: 1e-6)
        XCTAssertEqual(r.geo, v + 0.0001220703125, accuracy: 1e-5)
        XCTAssertEqual(r.ae, 0.15 / r.geo, accuracy: 1e-5)
        XCTAssertEqual(r.deficit, 1)
        XCTAssertEqual(r.effS, 0)
        XCTAssertEqual(r.effH, 0)
        XCTAssertEqual(r.flat, 1, accuracy: 1e-6)
        XCTAssertEqual(r.contrastBoost, 0.3, accuracy: 1e-6)
        XCTAssertEqual(r.agxSlope, 2.45 + 0.3, accuracy: 1e-6)
        XCTAssertEqual(r.acesD, 0.59 - 0.24, accuracy: 1e-6)
        XCTAssertEqual(r.uchiA, 1 + 0.225, accuracy: 1e-6)
        XCTAssertEqual(r.macroContrast, 1.1 + 0.09, accuracy: 1e-6)
        XCTAssertEqual(WhyV2Ae.exposures(r), SIMD4<Float>(1, 1, 1, 1))
    }

    func testZeroBinQuirksAndHighlights() {
        let p = WhyV2Ae.Params()
        let r = WhyV2Ae.finish(hist([(0, 0, 0), (0.1, 0.1, 0.1), (1.5, 1.5, 1.5)], [300, 600, 100], clip: 1), p,
                               clipCeiling: 1, noiseS: 0, noiseO: 0)
        XCTAssertEqual(r.p10, 1 / 4096, accuracy: 1e-9)
        XCTAssertEqual(r.p50, 409 / 4096, accuracy: 1e-6)
        XCTAssertEqual(r.p98, 4095 / 4096, accuracy: 1e-6)
        XCTAssertEqual(r.p995, 4095 / 4096, accuracy: 1e-6)
        XCTAssertEqual(r.ovShare, 0.1, accuracy: 1e-6)
        XCTAssertTrue(r.effH > 0.1 && r.effH <= p.highlightStr)
        XCTAssertTrue(r.effS > 0)
        let em = WhyV2Ae.exposures(r)
        XCTAssertEqual(Double(em.x), pow(2, -2.5 * Double(r.effH)), accuracy: 1e-6)
        XCTAssertEqual(Double(em.y), pow(2, -1.2 * Double(r.effH)), accuracy: 1e-6)
        XCTAssertEqual(em.z, 1)
        XCTAssertEqual(Double(em.w), min(pow(2, 1.8 * Double(r.effS)), 16), accuracy: 1e-5)
    }

    func testOverflowBlendAndNoiseCap() {
        let p = WhyV2Ae.Params()
        let h = hist([(0.01, 0.01, 0.01), (0.9, 0.9, 0.9)], [990, 10], clip: 1)
        let r = WhyV2Ae.finish(h, p, clipCeiling: 1, noiseS: 0, noiseO: 0)
        let aeC = min(p.maxBoost, 0.15 / r.geo)
        let lim = p.hlOverflow / r.p995
        XCTAssertTrue(aeC * r.p995 > p.hlOverflow)
        XCTAssertEqual(r.ae, lim + 0.5 * (aeC - lim), accuracy: 1e-4)
        XCTAssertEqual(r.deficit, aeC / r.ae, accuracy: 1e-4)
        let n = WhyV2Ae.finish(h, p, clipCeiling: 1, noiseS: 0.004, noiseO: 1e-5)
        let sigma = Float(sqrt(Double(0.5 * Float(0.004) + Float(1e-5))))
        let d = min(max(0.5 * sigma, 0.026), sigma)
        XCTAssertEqual(n.noiseCap, 0.05 / d, accuracy: 1e-4)
        XCTAssertEqual(n.ae, max(min(r.ae, n.noiseCap), p.minLimit), accuracy: 1e-5)
        let loud = WhyV2Ae.finish(h, p, clipCeiling: 1, noiseS: 0.5, noiseO: 0.1)
        XCTAssertEqual(loud.noiseCap, 1)
        XCTAssertEqual(loud.ae, 1, accuracy: 1e-6)
    }

    func testClipCeilingScalesTheHistograms() {
        XCTAssertEqual(WhyV2Ae.clipCeiling(1.05), 1)
        var h = WhyV2Ae.Hist()
        WhyV2Ae.add(&h, 2, 2, 2, clip: 4)
        XCTAssertEqual(h.luma[Int(Float(0.5) * 4095.99)], 1)
    }

    func testOwnerFloats() {
        let p = WhyV2Ae.Params()
        XCTAssertEqual(p.aeTarget.bitPattern, Float(0.14999999).bitPattern)
        XCTAssertEqual(p.skinHueCenterDeg.bitPattern, 0x4225_0000)
        XCTAssertEqual(p.skinHueCenterRad.bitPattern, (Float(41.25) * Float(0.017453292)).bitPattern)
        XCTAssertNotEqual(WhyV2Ae.fma(0.1, 3, -0.3), 0)
    }

    func testInsetOutsetInverse() {
        for s: Float in [0, 0.5, 50, 100] {
            let a = WhyV2Ae.insetMatrix(s), b = WhyV2Ae.outsetMatrix(s)
            for i in 0..<3 {
                for j in 0..<3 {
                    var v = 0.0
                    for k in 0..<3 { v += Double(a[k * 3 + i]) * Double(b[j * 3 + k]) }
                    XCTAssertEqual(v, i == j ? 1 : 0, accuracy: 1e-5)
                }
            }
        }
    }

    func testCurveTable() {
        let lut = WhyV2Curve.samples(points: WhyV2Ae.ownerPoints, mods: [Float](repeating: 0, count: 7))
        XCTAssertEqual(lut.count, 1024)
        XCTAssertEqual(lut.first, 0)
        XCTAssertEqual(lut.last!, 1, accuracy: 1e-6)
        XCTAssertEqual(lut[512], 0.51, accuracy: 0.01)
        for i in 1..<lut.count { XCTAssertGreaterThanOrEqual(lut[i], lut[i - 1]) }
        XCTAssertEqual(WhyV2Curve.parse("broken").count, WhyV2Curve.parse(WhyV2Curve.defaultPoints).count)
    }
}

final class WhyV2MetalTests: XCTestCase {
    /// A horizontal ramp through the GPU tone: finite, monotone in brightness, the same bytes on a second run.
    func testRampRendersDeterministically() throws {
        let engine = try XCTUnwrap(MetalEngine.shared)
        let w = 320, h = 240
        var pixels = [UInt16](repeating: 0, count: w * h * 4)
        var floats = [Float](repeating: 0, count: w * h * 4)
        for y in 0..<h {
            for x in 0..<w {
                let v = Float(x) / Float(w - 1) * 1.2
                let o = (y * w + x) * 4
                floats[o] = v; floats[o + 1] = v * 0.9; floats[o + 2] = v * 0.8; floats[o + 3] = 1
            }
        }
        floats.withUnsafeMutableBufferPointer { src in
            pixels.withUnsafeMutableBufferPointer { dst in
                var s = vImage_Buffer(data: src.baseAddress, height: 1, width: vImagePixelCount(src.count), rowBytes: src.count * 4)
                var d = vImage_Buffer(data: dst.baseAddress, height: 1, width: vImagePixelCount(src.count), rowBytes: src.count * 2)
                _ = vImageConvert_PlanarFtoPlanar16F(&s, &d, vImage_Flags(kvImageNoFlags))
            }
        }
        let input = try engine.texture(w, h, .rgba16Float, shared: true)
        pixels.withUnsafeBytes { raw in
            input.replace(region: MTLRegionMake2D(0, 0, w, h), mipmapLevel: 0, withBytes: raw.baseAddress!, bytesPerRow: w * 8)
        }
        func run() throws -> [UInt8] {
            let out = try WhyV2Renderer(engine: engine).render(input, noise: WhyV2Ae.referenceNoiseFallback)
            var bytes = [UInt8](repeating: 0, count: w * h * 4)
            out.texture.getBytes(&bytes, bytesPerRow: w * 4, from: MTLRegionMake2D(0, 0, w, h), mipmapLevel: 0)
            return bytes
        }
        let a = try run(), b = try run()
        XCTAssertEqual(a, b)
        let row = h / 2
        let left = Int(a[(row * w + 4) * 4 + 1]), mid = Int(a[(row * w + w / 2) * 4 + 1]), right = Int(a[(row * w + w - 5) * 4 + 1])
        XCTAssertLessThan(left, mid)
        XCTAssertLessThan(mid, right)
    }
}
