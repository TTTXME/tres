import Accelerate
import Metal
import simd

/// «Why тон» V2 (Android PostPipeline: WhyV2Stats → luma → WhyV2Fusion → WhyV2Tone) on Metal.
/// Input: white-balanced linear Rec.709 RGB (rgba16Float). Output: display RGBA8 (sRGB-tagged).
/// Sharpening (WhyLumaSharpen) is not ported yet: the detail transfer reads the unsharpened reference luma.
final class WhyV2Renderer {
    struct Output {
        let texture: MTLTexture
        let ae: WhyV2Ae.Result
        let log: [String]
    }

    let engine: MetalEngine
    var params = WhyV2Ae.Params()

    init(engine: MetalEngine) {
        self.engine = engine
    }

    func render(_ input: MTLTexture, clipCeiling: Float = 1, noise: (Float, Float) = (0, 0)) throws -> Output {
        let w = input.width, h = input.height
        var log: [String] = []
        let started = Date()
        let clean = try engine.texture(w, h, .rgba16Float)
        let luma = try engine.texture(w, h, .r16Float)
        let step = WhyV2Ae.stride(width: w, height: h)
        let sw = WhyV2Ae.samples(w, stride: step), sh = WhyV2Ae.samples(h, stride: step)
        guard let samples = engine.device.makeBuffer(length: sw * sh * MemoryLayout<SIMD4<Float>>.stride, options: .storageModeShared) else {
            throw EngineError.texture("samples")
        }

        // WhyV2Stats: clean RGB, the histogram samples; the reference luma for the detail transfer
        try engine.run("why-v2-stats") { buffer in
            guard let enc = buffer.makeComputeCommandEncoder() else { throw EngineError.noDevice }
            var cp = WhyV2CleanParams(sensorToIntermediate: matrix_identity_float3x3, intermediateToSRGB: matrix_identity_float3x3,
                                      neutral: SIMD3<Float>(1, 1, 1), scale: 1)
            enc.setTexture(input, index: 0)
            enc.setTexture(clean, index: 1)
            enc.setBytes(&cp, length: MemoryLayout<WhyV2CleanParams>.stride, index: 0)
            try engine.dispatch(enc, engine.pipeline("why_v2_clean"), width: w, height: h)
            var dims = SIMD2<Int32>(Int32(sw), Int32(sh))
            var s = Int32(step)
            enc.setTexture(clean, index: 0)
            enc.setBuffer(samples, offset: 0, index: 0)
            enc.setBytes(&dims, length: MemoryLayout<SIMD2<Int32>>.stride, index: 1)
            enc.setBytes(&s, length: MemoryLayout<Int32>.stride, index: 2)
            try engine.dispatch(enc, engine.pipeline("why_v2_stride"), width: sw, height: sh)
            enc.setTexture(clean, index: 0)
            enc.setTexture(luma, index: 1)
            try engine.dispatch(enc, engine.pipeline("why_v2_luma"), width: w, height: h)
            enc.endEncoding()
        }

        let clip = WhyV2Ae.clipCeiling(clipCeiling)
        var hist = WhyV2Ae.Hist()
        var maxValue: Float = 0
        let pointer = samples.contents().bindMemory(to: SIMD4<Float>.self, capacity: sw * sh)
        for i in 0..<(sw * sh) {
            let v = pointer[i]
            for c in 0..<3 where v[c].isFinite && v[c] > maxValue { maxValue = v[c] }
            WhyV2Ae.add(&hist, v.x, v.y, v.z, clip: clip)
        }
        let r = WhyV2Ae.finish(hist, params, clipCeiling: clipCeiling, noiseS: noise.0, noiseO: noise.1)
        log.append("WHY V2 stats \(r.describe) stride=\(step) samples=\(sw)x\(sh) max=\(maxValue) noise=\(noise.0)/\(noise.1)"
                   + " ms=\(ms(since: started))")

        // WhyV2Fusion
        let fusionStarted = Date()
        let p = params
        let lv = WhyV2Ae.levels(width: w, height: h)
        let n = lv.count
        var g: [MTLTexture] = [], wt: [MTLTexture] = [], fu: [MTLTexture] = []
        for (lw, lh) in lv {
            g.append(try engine.texture(lw, lh, .rgba16Float))
            wt.append(try engine.texture(lw, lh, .rgba16Float))
            fu.append(try engine.texture(lw, lh, .r16Float))
        }
        let em = WhyV2Ae.exposures(r), lwts = WhyV2Ae.layerWeights(p)
        try engine.run("why-v2-fusion") { buffer in
            guard let enc = buffer.makeComputeCommandEncoder() else { throw EngineError.noDevice }
            var pp = WhyV2PrepParams(expMultipliers: em, layerWeights: lwts, width: Int32(lv[0].0), height: Int32(lv[0].1), scale: 1,
                                     gammaCurveType: p.gammaType, weightCenter: p.weightCenter, autoExposure: r.ae,
                                     blendSmoothness: p.blendSmoothness, gammaInv: 1 / max(p.gamma, 1), acesToe: p.acesToe)
            enc.setTexture(clean, index: 0)
            enc.setTexture(g[0], index: 1)
            enc.setTexture(wt[0], index: 2)
            enc.setBytes(&pp, length: MemoryLayout<WhyV2PrepParams>.stride, index: 0)
            try engine.dispatch(enc, engine.pipeline("why_v2_ef_prep"), width: lv[0].0, height: lv[0].1)
            let down = try engine.pipeline("why_v2_pyr_down")
            for l in 0..<(n - 1) {
                for pyr in [g, wt] {
                    var lp = WhyV2LevelParams(texelSize: SIMD2(1 / Float(lv[l].0), 1 / Float(lv[l].1)),
                                              width: Int32(lv[l + 1].0), height: Int32(lv[l + 1].1), clarity: 0)
                    enc.setTexture(pyr[l], index: 0)
                    enc.setTexture(pyr[l + 1], index: 1)
                    enc.setBytes(&lp, length: MemoryLayout<WhyV2LevelParams>.stride, index: 0)
                    engine.dispatch(enc, down, width: lv[l + 1].0, height: lv[l + 1].1)
                }
            }
            var cp = WhyV2LevelParams(texelSize: .zero, width: Int32(lv[n - 1].0), height: Int32(lv[n - 1].1), clarity: p.clarity)
            enc.setTexture(g[n - 1], index: 0)
            enc.setTexture(wt[n - 1], index: 1)
            enc.setTexture(fu[n - 1], index: 2)
            enc.setBytes(&cp, length: MemoryLayout<WhyV2LevelParams>.stride, index: 0)
            try engine.dispatch(enc, engine.pipeline("why_v2_ef_coarsest"), width: lv[n - 1].0, height: lv[n - 1].1)
            let collapse = try engine.pipeline("why_v2_ef_collapse")
            for l in stride(from: n - 2, through: 0, by: -1) {
                var lp = WhyV2LevelParams(texelSize: .zero, width: Int32(lv[l].0), height: Int32(lv[l].1), clarity: p.clarity)
                enc.setTexture(g[l], index: 0)
                enc.setTexture(g[l + 1], index: 1)
                enc.setTexture(wt[l], index: 2)
                enc.setTexture(fu[l + 1], index: 3)
                enc.setTexture(fu[l], index: 4)
                enc.setBytes(&lp, length: MemoryLayout<WhyV2LevelParams>.stride, index: 0)
                engine.dispatch(enc, collapse, width: lv[l].0, height: lv[l].1)
            }
            enc.endEncoding()
        }
        let fused = fu[0]
        g.removeAll(); wt.removeAll(); fu.removeAll()
        log.append("WHY V2 fusion levels=\(n) base=\(lv[0].0)x\(lv[0].1) coarsest=\(lv[n - 1].0)x\(lv[n - 1].1)"
                   + " em=\(em.x),\(em.y),\(em.z),\(em.w) ms=\(ms(since: fusionStarted))")

        // WhyV2Tone
        let toneStarted = Date()
        let curve = try curveTexture(p)
        let output = try engine.texture(w, h, .rgba8Unorm, shared: true)
        let inset = WhyV2Ae.insetMatrix(p.agxInset), outset = WhyV2Ae.outsetMatrix(p.agxInset)
        var tp = WhyV2ToneParams(
            agxInset: Self.matrix(inset), agxOutset: Self.matrix(outset), width: Int32(w), height: Int32(h), efScaled: 0, quant8: 1,
            tonemapOperator: p.toneOperator, agxLook: p.agxLook, agxHueLock: p.agxHueLock ? 1 : 0, gammaCurveType: p.gammaType,
            autoExposure: r.ae, clipCeiling: r.clip, acesToe: p.acesToe, agxSlope: r.agxSlope, agxSp: p.agxSp, agxTp: p.agxTp,
            agxMinEv: p.agxMinEv, agxMaxEv: p.agxMaxEv, agxSatBoost: p.agxSat, agxEv: p.agxEv, acesPreGain: p.acesPreGain,
            acesA: p.acesA, acesD: r.acesD, uchiPreGain: p.uchiPreGain, uchiPostGain: p.uchiPostGain, uchiP: p.uchiP, uchiA: r.uchiA,
            uchiM: p.uchiM, uchiL: p.uchiL, uchiC: p.uchiC, uchiB: p.uchiB, macroContrast: r.macroContrast,
            contrastBoost: r.contrastBoost, vibStrength: p.vibStrength, vibSaturated: p.vibSaturated, skinProtect: p.skinProtect,
            vibSky: p.vibSky, vibGreen: p.vibGreen, vibHl: p.vibHl, vibMid: p.vibMid, vibShadow: p.vibShadow,
            skinHueShift: p.skinHueShift, skinSatShift: p.skinSatShift, skinHueCenter: p.skinHueCenterRad,
            skinLumaMin: p.skinLumaMin, gamma: p.gamma, gammaInv: 1 / max(p.gamma, 1), filmToe: p.filmToe,
            shoulderStrength: p.shoulderStrength, shoulderKnee: p.shoulderKnee, shoulderHeadroom: p.shoulderHeadroom)
        try engine.run("why-v2-tone") { buffer in
            guard let enc = buffer.makeComputeCommandEncoder() else { throw EngineError.noDevice }
            enc.setTexture(clean, index: 0)
            enc.setTexture(fused, index: 1)
            enc.setTexture(luma, index: 2)
            enc.setTexture(curve, index: 3)
            enc.setTexture(output, index: 4)
            enc.setBytes(&tp, length: MemoryLayout<WhyV2ToneParams>.stride, index: 0)
            try engine.dispatch(enc, engine.pipeline("why_v2_tonemap"), width: w, height: h)
            enc.endEncoding()
        }
        log.append("WHY V2 tone ae=\(r.ae) clip=\(r.clip) op=\(p.toneOperator) cb=\(r.contrastBoost) macro=\(r.macroContrast)"
                   + " grid=\(w)x\(h) quant8 sharpen=off ms=\(ms(since: toneStarted))")
        return Output(texture: output, ae: r, log: log)
    }

    /// The own curve as the reference's R16F 1024×1 table (linear filtering).
    private func curveTexture(_ p: WhyV2Ae.Params) throws -> MTLTexture {
        let values = WhyV2Curve.samples(points: p.curvePoints, mods: p.curveMods)
        var halves = [UInt16](repeating: 0, count: values.count)
        var source = values
        source.withUnsafeMutableBufferPointer { src in
            halves.withUnsafeMutableBufferPointer { dst in
                var s = vImage_Buffer(data: src.baseAddress, height: 1, width: vImagePixelCount(values.count), rowBytes: values.count * 4)
                var d = vImage_Buffer(data: dst.baseAddress, height: 1, width: vImagePixelCount(values.count), rowBytes: values.count * 2)
                _ = vImageConvert_PlanarFtoPlanar16F(&s, &d, vImage_Flags(kvImageNoFlags))
            }
        }
        let texture = try engine.texture(values.count, 1, .r16Float, shared: true, usage: [.shaderRead])
        halves.withUnsafeBytes { raw in
            texture.replace(region: MTLRegionMake2D(0, 0, values.count, 1), mipmapLevel: 0, withBytes: raw.baseAddress!,
                            bytesPerRow: values.count * 2)
        }
        return texture
    }

    static func matrix(_ columnMajor: [Float]) -> simd_float3x3 {
        simd_float3x3(columns: (SIMD3(columnMajor[0], columnMajor[1], columnMajor[2]),
                                SIMD3(columnMajor[3], columnMajor[4], columnMajor[5]),
                                SIMD3(columnMajor[6], columnMajor[7], columnMajor[8])))
    }
}

func ms(since start: Date) -> Int { Int(Date().timeIntervalSince(start) * 1000) }
