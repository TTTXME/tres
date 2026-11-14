import Foundation

/// The SHDR frames exposed after the N frames (Android capture/HybridPlan.java, its build as buildNormalBack takes it: the iPhone has
/// no ZSL ring, so every N frame comes after the press and the plan adds only its ultrashort and bracketed frames):
/// - Bento: `bento_frames` ultrashort frames (ROLE_ULTRASHORT) at N / `bento_factor` (8), the gain lowered first, then the shutter;
///   «Всегда» (bento 2, the default) or «Авто» (1: only when the newest N frame clips above `bento_trigger`);
/// - Shasta: 1–3 bracketed frames (ROLE_BRACKETED) at N x 2^`shasta_ev`, the gain first at the N shutter, the shutter longer only
///   where the gain range ends (handheld cap 1/8 s); +2 EV with the shutter first up to 1/60 s under deep shadows (P409); their count
///   by the shadows of the newest N frame (P410); skipped below x1.2 or above `shasta_max_ratio` («Всегда» takes them above it).
/// None in «Движение», with «HDR кадры» off or in «Oldstyle» (oneExposure). The exposures are relative to N's measured exposure x ISO.
enum HybridPlan {
    enum Role: Int { case normal = 1, long = 3, extraShort = 5 }

    struct Request: CustomStringConvertible {
        let role: Role
        let shutterNs: Int64
        let iso: Int
        let ratio: Double
        var description: String { String(format: "%@ %.3fms*ISO%ld (x%.3f)", role.name, Double(shutterNs) / 1e6, iso, ratio) }
    }

    struct Plan {
        var requests: [Request] = []
        var nShutterNs: Int64 = 0
        var nIso = 0
        var description = ""
    }

    static let handheldShutterCapNs: Int64 = 125_000_000
    static let bracketSafeShutterNs: Int64 = 16_666_667
    static let shadowTwo: Float = 0.10, shadowThree: Float = 0.30, deepShadowShare: Float = 0.15
    static let verifyToleranceEv = 0.4
    static let normalDarkerRatio = pow(2.0, -0.5), normalBrighterRatio = 1.035

    // MARK: the settings (PreferenceKeys.hybridValue / hybridSwitch: scam_dev "hybrid_<key>" first, then pref_scam_hybrid_<key>)

    private static var prefs: [String: Any] { UserDefaults.standard.dictionary(forKey: PostSettings.prefsKey) ?? [:] }
    private static var dev: [String: Double] { UserDefaults.standard.dictionary(forKey: PostSettings.devKey) as? [String: Double] ?? [:] }

    static var motionMode: Bool { StoredPrefs.bool("pref_motion_mode", false) }
    /// PreferenceKeys.hdrFrames (scam_dev "hdr_frames" overrides «HDR кадры»)
    static var hdrFrames: Bool { dev["hdr_frames"].map { $0 > 0 } ?? StoredPrefs.bool("pref_hdr_frames", true) }
    /// HybridPlan.oneExposure: «Движение», «HDR кадры» off, «Oldstyle»
    static var oneExposure: Bool { motionMode || !hdrFrames || StoredPrefs.bool("pref_oldstyle_mode", false) }

    static func value(_ key: String, _ fallback: Float) -> Float {
        if oneExposure && (key == "bento" || key == "shasta") { return 0 }
        if let d = dev["hybrid_" + key] { return Float(d) }
        switch prefs["pref_scam_hybrid_" + key] {
        case let s as String: return Float(s.trimmingCharacters(in: .whitespaces)) ?? fallback
        case let n as NSNumber: return n.floatValue
        default: return fallback
        }
    }

    static func on(_ key: String, _ fallback: Bool) -> Bool {
        if oneExposure && (key == "bento" || key == "shasta") { return false }
        if let d = dev["hybrid_" + key] { return d > 0 }
        switch prefs["pref_scam_hybrid_" + key] {
        case let n as NSNumber: return n.boolValue
        case let s as String: return s == "true" || s == "1"
        default: return fallback
        }
    }

    /// Math.round(float) / Math.round(double)
    static func round(_ v: Float) -> Int { v.isNaN ? 0 : Int((Double(v) + 0.5).rounded(.down)) }
    static func round(_ v: Double) -> Int64 { v.isNaN ? 0 : Int64((v + 0.5).rounded(.down)) }

    static var shastaEnabled: Bool { !oneExposure && on("shasta", true) }
    static var bracketCount: Int { max(0, min(3, round(value("shasta_frames", 3)))) }
    static var shastaMode: Int { motionMode || !on("shasta", true) ? 0 : max(1, min(2, round(value("shasta_mode", 2)))) }
    static var shastaForced: Bool { shastaMode == 2 }
    static var bracketEv: Double { Double(max(1, min(4, value("shasta_ev", 1)))) }
    static var bentoMode: Int { oneExposure ? 0 : max(0, min(2, round(value("bento", 2)))) }
    static var ultrashortFactor: Double { Double(max(2, min(16, value("bento_factor", 8)))) }
    static var bentoFrames: Int { max(1, min(3, round(value("bento_frames", 2)))) }
    static var bentoTriggerClip: Float { max(0, min(0.1, value("bento_trigger", 0.0005))) }
    static var maxBracketRatio: Double { Double(max(2, min(100, value("shasta_max_ratio", 32)))) }
    static var shastaAutoCount: Bool { on("shasta_auto_count", true) }
    static var shastaDeep: Bool { on("shasta_deep", true) }

    /// PreferenceKeys.getAntibandingHz: «Мерцание ламп» as set (100 / 120, 0 off); «Авто» (-1) is the Android camera's measured mains
    /// (MainsFlicker), which the iPhone does not report: no snapping then
    static var antibandingHz: Int {
        let hz = Int(StoredPrefs.string("pref_antibanding_hz_key", "-1")) ?? -1
        return max(0, hz)
    }

    /// P410: 1 .. set bracketed frames by the shadow share (NaN: the set count)
    static func bracketCountFor(_ set: Int, shadow: Float, deepShadow: Float) -> Int {
        if set <= 1 || !shastaAutoCount || shadow.isNaN { return set }
        let n = shadow >= shadowThree || (shastaDeep && deepShadow >= deepShadowShare) ? 3 : shadow >= shadowTwo ? 2 : 1
        return max(1, min(set, n))
    }

    static func handheldCap(_ nShutterNs: Int64) -> Int64 { max(nShutterNs, handheldShutterCapNs) }

    static func flickerSafe(_ ns: Int64, _ nShutterNs: Int64) -> Int64 {
        let hz = antibandingHz
        if hz <= 0 || ns <= nShutterNs { return ns }
        let period = round(1e9 / Double(hz))
        if nShutterNs < period { return ns }
        return max(nShutterNs, (ns / period) * period)
    }

    static func snapAntibanding(_ ns: Int64, _ nShutterNs: Int64) -> Int64 {
        let hz = antibandingHz
        if hz <= 0 { return ns }
        let period = round(1e9 / Double(hz))
        if ns < 2 * period { return ns }
        let snapped = (ns / period) * period
        return snapped >= nShutterNs ? snapped : ns
    }

    /// HybridPlan.build without the Android-only cases (DCG, the HAL's motion cap, learned exposure limits): the ultrashort and the
    /// bracketed requests for N at `nShutterNs` x `nIso`; `times` / `isos` the lens's exposure and ISO ranges; `clip`, `shadow` and
    /// `deepShadow` the newest N frame's shares (FrameMeasure; shadow NaN: unknown).
    static func build(nShutterNs: Int64, nIso: Int, clip: Float, shadow: Float, deepShadow: Float,
                      times: ClosedRange<Int64>, isos: ClosedRange<Int>) -> Plan {
        var plan = Plan(nShutterNs: nShutterNs, nIso: nIso)
        guard nShutterNs > 0, nIso > 0 else {
            plan.description = "hybrid plan: no N exposure, no Bento / Shasta"
            return plan
        }
        let n = Double(nShutterNs) * Double(nIso)
        var out: [Request] = []
        var why = ""
        let bento = bentoMode
        if bento == 2 || (bento == 1 && clip > bentoTriggerClip) {
            let target = n / ultrashortFactor
            let iso = Int(max(Int64(isos.lowerBound), min(Int64(nIso), round(target / Double(nShutterNs)))))
            let ns = max(times.lowerBound, min(nShutterNs, round(target / Double(iso))))
            let ratio = Double(ns) * Double(iso) / n
            if ratio < 0.5 {
                for _ in 0..<bentoFrames { out.append(Request(role: .extraShort, shutterNs: ns, iso: iso, ratio: ratio)) }
            } else {
                why += " us skipped (sensor floor)"
            }
        } else {
            why += bento == 0 ? " bento off" : String(format: " no clipping (%.4f)", clip)
        }
        let count = shastaEnabled ? bracketCountFor(bracketCount, shadow: shadow, deepShadow: deepShadow) : 0
        if count > 0 {
            let deep = shastaDeep && deepShadow >= deepShadowShare
            let ev = deep ? max(bracketEv, 2.0) : bracketEv
            let target = n * pow(2, ev)
            let cap = min(times.upperBound, handheldCap(nShutterNs))
            var ns = nShutterNs
            if deep {
                ns = max(nShutterNs, min(round(Double(nShutterNs) * pow(2, ev)), min(cap, max(nShutterNs, bracketSafeShutterNs))))
                ns = flickerSafe(max(nShutterNs, min(ns, times.upperBound)), nShutterNs)
            }
            let gainWanted = target / (Double(ns) * Double(nIso))
            let gain = deep ? min(gainWanted, 2.0) : gainWanted
            let iso = Int(max(Int64(isos.lowerBound), min(Int64(isos.upperBound), round(Double(nIso) * gain))))
            if Double(ns) * Double(iso) < target * 0.95 && iso >= isos.upperBound {
                ns = snapAntibanding(max(ns, min(cap, round(target / Double(iso)))), nShutterNs)
            }
            if deep { why += String(format: " deep shadows %.0f%%: +%.0f EV", 100 * deepShadow, ev) }
            if count != bracketCount { why += String(format: " shadows %.0f%%: %ld of %ld long frames", 100 * shadow, count, bracketCount) }
            let ratio = Double(ns) * Double(iso) / n
            if ratio > maxBracketRatio && !shastaForced {
                why += String(format: " brackets skipped (ratio %.1f)", ratio)
            } else if ratio < 1.2 {
                why += String(format: " brackets skipped (ratio %.2f)", ratio)
            } else {
                for _ in 0..<count { out.append(Request(role: .long, shutterNs: ns, iso: iso, ratio: ratio)) }
            }
        } else {
            why += " shasta off"
        }
        plan.requests = out
        plan.description = String(format: "hybrid normal-back: N=%.3fms*ISO%ld clip=%.4f requests=", Double(nShutterNs) / 1e6, nIso, clip)
            + "[" + out.map(\.description).joined(separator: ", ") + "]" + why
        return plan
    }

    /// HybridPlan.classify: the role a delivered frame of `request` really has. Within 0.4 EV of its plan the planned role; otherwise
    /// by its measured exposure against N (x1.5 or more bracketed, x0.5 or less ultrashort, the N band an N frame, else dropped: nil).
    static func classify(_ request: Request, exposureNs: Int64, iso: Int, plan: Plan, log: (String) -> Void) -> Role? {
        guard exposureNs > 0, iso > 0 else { return nil }
        let planEv = abs(log2(Double(exposureNs) * Double(iso) / (Double(request.shutterNs) * Double(request.iso))))
        if planEv <= verifyToleranceEv { return request.role }
        let ratio = Double(exposureNs) * Double(iso) / (Double(plan.nShutterNs) * Double(plan.nIso))
        let role: Role? = ratio >= 1.5 ? .long : ratio <= 0.5 ? .extraShort
            : ratio >= normalDarkerRatio && ratio <= normalBrighterRatio ? .normal : nil
        log(String(format: "hybrid: %@ delivered x%.3f of N (plan x%.3f; ISO %ld/%ld, shutter %lld/%lld, %.2f EV off) -> %@", request.role.name,
                   ratio, request.ratio, iso, request.iso, exposureNs, request.shutterNs, planEv, role.map { "used as " + $0.name } ?? "dropped"))
        return role
    }
}

extension HybridPlan.Role {
    var name: String {
        switch self {
        case .normal: return "NORMAL"
        case .long: return "LONG"
        case .extraShort: return "EXTRA_SHORT"
        }
    }
}

/// CaptureController.zslClipFraction on the newest N frame (14-bit RAW16): the share of samples at 95 % of white (Bento «Авто»), and
/// of those within 1/16 (P410 shadows) and 1/64 (P409 deep shadows) of the range above black, every 8th row, every 4th column.
enum FrameMeasure {
    static func shares(_ raw: Data, width: Int, height: Int, black: [Float], white: Float) -> (clip: Float, shadow: Float, deep: Float) {
        guard width > 0, height > 0, raw.count >= width * height * 2 else { return (0, .nan, 0) }
        let limit = Int(white * 0.95)
        let blackMean = black.reduce(0, +) / Float(max(1, black.count))
        let deepBelow = HybridPlan.round(blackMean + (white - blackMean) / 64)
        let shadowBelow = HybridPlan.round(blackMean + (white - blackMean) / 16)
        var clipped = 0, deep = 0, shadow = 0, total = 0
        raw.withUnsafeBytes { (p: UnsafeRawBufferPointer) in
            let px = p.bindMemory(to: UInt16.self)
            var y = 0
            while y < height {
                var x = 0
                while x < width {
                    let v = Int(UInt16(littleEndian: px[y * width + x]))
                    if v >= limit {
                        clipped += 1
                    } else if v <= shadowBelow {
                        shadow += 1
                        if v <= deepBelow { deep += 1 }
                    }
                    total += 1
                    x += 4
                }
                y += 8
            }
        }
        guard total > 0 else { return (0, .nan, 0) }
        return (Float(clipped) / Float(total), Float(shadow) / Float(total), Float(deep) / Float(total))
    }
}

/// «16:9» (Wide169, P118): the one centred crop of a landscape RAW frame - width x 9 / 16 rows (whole 8-row colour blocks), the
/// first row on a multiple of 8; a portrait frame, or one already as wide, stays whole.
enum Wide169 {
    static var on: Bool { StoredPrefs.bool("pref_wide169_key", false) }

    static func rows(_ width: Int, _ height: Int) -> Int {
        if width <= height { return height }
        let r = (width * 9 / 16) & ~7
        return r > 0 && r < height ? r : height
    }

    static func firstRow(_ width: Int, _ height: Int) -> Int { ((height - rows(width, height)) / 2) & ~7 }
}
