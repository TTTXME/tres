import Foundation

/// The Android migrations a config goes through when it is imported (BackupRestoreUtil.migrate): BrandMigration (the names before the
/// SCAM rename and the tone's «Why» rename), SettingsMigration's multi-frame, antibanding, SCAM Hybrid (the route, the legacy keys and
/// every defaults revision), shade tiles and watermark passes, the obsolete-key pass, then SettingsMigration.prepare (each row's value
/// in the type its row stores). The rules, keys, markers and values are the Java ones, so a config of any Android build reads as that
/// build's settings would after its update.
enum SettingsMigration {
    typealias Values = [String: Any]

    // ───── BrandMigration ─────

    private static func r(_ s: String) -> String { String(s.reversed()) }

    private static let token = try! NSRegularExpression(pattern: "[A-Za-z0-9_]*(?:[Ll][Mm][Cc]|[Vv][Ii][Vv][Oo]|[Nn][Ii][Cc][Ee]|[Aa][Rr][Kk])[A-Za-z0-9_]*")
    private static let whySegment = try! NSRegularExpression(pattern: "(?<![A-Za-z0-9])" + r("kra") + "(?![A-Za-z0-9])")
    private static let prefixes: [(String, String)] = [
        (r("_dirbyh_cml_ferp"), "pref_scam_hybrid_"), (r("_ecin_oviv_ferp"), "pref_scamhdr_"), (r("_ecin_ferp"), "pref_scamold_"),
        (r("_rdh_oviv_ferp"), "pref_scamroute_"),
    ]
    private static let order: [(String, String)] = [
        (r("eciNeciNoviV"), "ScamScam"),
        (r("ECIN_OVIV"), "SCAM"), (r("eciNoviV"), "Scam"), (r("eciNoviv"), "scam"), (r("ecin_oviv"), "scam"), (r("ecinoviv"), "scam"),
        (r("LARUEN_OVIV"), "SCAM_NEURAL"), (r("larueNoviV"), "ScamNeural"), (r("larueNoviv"), "scamNeural"), (r("laruen_oviv"), "scam_neural"),
        (r("DIRBYH_CML"), "SCAM_HYBRID"), (r("dirbyHcmL"), "ScamHybrid"), (r("dirbyHcml"), "scamHybrid"), (r("dirbyh_cml"), "scam_hybrid"),
        (r("oviV"), "Scam"), (r("oviv"), "scam"), (r("OVIV"), "SCAM"),
        (r("eciN"), "Scam"), (r("ecin"), "scam"), (r("ECIN"), "SCAM"),
        (r("cmL"), "Scam"), (r("cml"), "scam"), (r("CML"), "SCAM"),
    ]

    private static func replace(_ re: NSRegularExpression, _ s: String, _ template: String) -> String {
        re.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s), withTemplate: template)
    }

    /// One word with an old name -> its new name
    static func word(_ t: String) -> String {
        var out = t
        for (old, new) in prefixes where out.hasPrefix(old) {
            out = new + out.dropFirst(old.count)
            break
        }
        for (old, new) in order { out = out.replacingOccurrences(of: old, with: new) }
        out = out.replacingOccurrences(of: "(Scam)(Scam)+", with: "$1", options: .regularExpression)
        out = out.replacingOccurrences(of: "(scam)(_?scam)+", with: "$1", options: .regularExpression)
        out = out.replacingOccurrences(of: "(SCAM)(_?SCAM)+", with: "$1", options: .regularExpression)
        return replace(whySegment, out, "why")
    }

    /// Every old word of a key or a string value renamed; a word next to «.» or «/» stays (vendor tags, paths)
    static func text(_ s: String) -> String {
        let ns = s as NSString
        var out = ""
        var last = 0
        for m in token.matches(in: s, range: NSRange(location: 0, length: ns.length)) {
            let start = m.range.location, end = start + m.range.length
            let before = start > 0 ? ns.substring(with: NSRange(location: start - 1, length: 1)) : ""
            let after = end < ns.length ? ns.substring(with: NSRange(location: end, length: 1)) : ""
            let kept = before == "." || before == "/" || after == "." || after == "/"
            let w = ns.substring(with: m.range)
            let n = kept ? w : word(w)
            if n == w { continue }
            out += ns.substring(with: NSRange(location: last, length: start - last)) + n
            last = end
        }
        if last == 0 && out.isEmpty { return s }
        return out + ns.substring(from: last)
    }

    /// P319: only the tone word's rename
    static func why(_ key: String) -> String { replace(whySegment, key, "why") }

    static func value(_ v: Any) -> Any {
        if let s = v as? String { return text(s) }
        if let a = v as? [String] { return a.map(text) }
        return v
    }

    /// BrandMigration.map: the new names; a new key already present wins over its old twin
    static func brand(_ values: Values) -> Values {
        var out: Values = [:]
        for (k, v) in values {
            let n = text(k)
            if n != k && values[n] != nil { continue }
            out[n] = value(v)
        }
        return out
    }

    // ───── value helpers (PreferenceNumber, SettingsMigration.isNumber) ─────

    private static func isNumber(_ v: Any?, _ expected: Float) -> Bool {
        guard let v, !PrefValue.isBool(v) else { return false }
        let f: Float?
        if let n = v as? NSNumber { f = n.floatValue } else { f = Float(PrefValue.text(v).trimmingCharacters(in: .whitespaces)) }
        guard let f else { return false }
        return abs(f - expected) < 1e-4
    }

    private static func isTrue(_ v: Any?) -> Bool { PrefValue.isBool(v) && (v as! NSNumber).boolValue }
    private static func isFalse(_ v: Any?) -> Bool { PrefValue.isBool(v) && !(v as! NSNumber).boolValue }

    /// PreferenceNumber.format
    private static func format(_ v: Float, _ floating: Bool) -> String { SliderGrid.exact(Double(v), decimal: floating) }

    /// A number written back in the stored value's own form (a String of a row, else a float)
    private static func number(_ stored: Any?, _ v: Float, _ floating: Bool = true) -> Any {
        stored is String ? format(v, floating) : NSNumber(value: v)
    }

    /// `text` when the stored value is a String (a row's), else the float `n`
    private static func textOr(_ stored: Any, _ text: String, _ n: Float) -> Any { stored is String ? text : NSNumber(value: n) }

    private static func sameList(_ stored: Any?, _ expected: [Float]) -> Bool {
        guard let stored else { return false }
        let parts = PrefValue.text(stored).components(separatedBy: ",")
        guard parts.count == expected.count else { return false }
        return zip(parts, expected).allSatisfy { isNumber($0.0, $0.1) }
    }

    // ───── SettingsMigration ─────

    static func migrateAntibanding(_ p: inout Values) {
        if p["settings_antibanding_list_v1"] != nil { return }
        p["settings_antibanding_list_v1"] = true
        if let stored = p["pref_antibanding_hz_key"] {
            var hz = PrefValue.number(stored, 120)
            let entry: String
            if !hz.isFinite || hz == 120 || hz < 0 { entry = "-1" } else if hz <= 0 { entry = "0" } else {
                if hz <= 70 { hz *= 2 }
                entry = hz < 110 ? "100" : "120"
            }
            p["pref_antibanding_hz_key"] = entry
        }
    }

    static func migrateMultiFrame(_ p: inout Values) {
        if p["settings_zsl_capacity_v2"] != nil { return }
        p["pref_zsl_buffer_count_key"] = "50"
        p["settings_zsl_capacity_v2"] = true
    }

    static let shadeTilesKey = "ui_shade_tiles"
    static let defaultTiles = ["pref_ae_mode_key", "pref_countdown_timer_key", "pref_show_grid_key", "pref_save_raw_key", "pref_scam_hybrid_output",
                               "pref_hdr_frames", "pref_ultrahdr_key", "pref_horizon", "pref_motion_mode", "pref_camera_sounds_key",
                               "ui_photo_style", "pref_face_detect_mode", "pref_antibanding_hz_key", "pref_tunable_parameters_disablemirror",
                               "pref_wide169_key", "pref_tracking_af_mode", "pref_peak_method_key", "pref_scam_hybrid_bento",
                               "pref_show_afdata_key"]
    private static let quickKeys: [(String, String)] = [
        ("FLASH", "pref_ae_mode_key"), ("TIMER", "pref_countdown_timer_key"), ("RAW", "pref_save_raw_key"), ("GRID", "pref_show_grid_key"),
        ("AE_METERING_STD", "pref_ae_metering_std_mode_key"), ("HYBRID_OUTPUT", "pref_scam_hybrid_output"),
        ("HYBRID_DOWNSAMPLER", "pref_scam_hybrid_downsampler"),
    ]

    private static func splitTiles(_ v: String) -> [String] {
        v.components(separatedBy: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }

    /// ShadeTiles.keyOf: a lens pin («lens:<profile>:<key>») by its key
    private static func tileKey(_ tile: String) -> String {
        guard tile.hasPrefix("lens:"), let colon = tile.dropFirst(5).firstIndex(of: ":"), colon < tile.index(before: tile.endIndex) else { return tile }
        return String(tile[tile.index(after: colon)...])
    }

    static func migrateShadeTiles(_ p: inout Values) {
        let legacyQuick = "ui_sheet_quick", legacyFavourites = "settings_favorite_keys"
        guard p[legacyQuick] != nil || p[legacyFavourites] != nil else { return }
        if p[shadeTilesKey] == nil {
            var tiles: [String] = []
            if let quick = p[legacyQuick] {
                for name in PrefValue.text(quick).components(separatedBy: ",") {
                    if let key = quickKeys.first(where: { $0.0 == name.trimmingCharacters(in: .whitespaces) })?.1, !tiles.contains(key) { tiles.append(key) }
                }
            }
            if let fav = p[legacyFavourites], let data = PrefValue.text(fav).data(using: .utf8),
               let keys = try? JSONSerialization.jsonObject(with: data) as? [String] {
                for key in keys where !isObsolete(key) && !tiles.contains(key) { tiles.append(key) }
            }
            for key in defaultTiles where !tiles.contains(key) { tiles.append(key) }
            p[shadeTilesKey] = tiles.joined(separator: ",")
        }
        p[legacyQuick] = nil
        p[legacyFavourites] = nil
    }

    static func migrateWatermark(_ p: inout Values) {
        let line1 = "pref_watermark_line1", line2 = "pref_watermark_line2", rev = "pref_watermark_text_rev", textKey = "pref_watermark_text"
        guard p[line1] != nil || p[line2] != nil else {
            if p[rev] == nil { p[rev] = NSNumber(value: 1) }
            return
        }
        var first = (p[line1].map(PrefValue.text) ?? "SHOT ON").trimmingCharacters(in: .whitespaces)
        let second = (p[line2].map(PrefValue.text) ?? "SCAMERA").trimmingCharacters(in: .whitespaces)
        if first.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression).caseInsensitiveCompare("SHOT ON") == .orderedSame { first = "" }
        let text = first.isEmpty ? second : second.isEmpty ? first : first + " " + second
        p[line1] = nil
        p[line2] = nil
        p[rev] = NSNumber(value: 1)
        if p[textKey] == nil && text != "SCAMERA" { p[textKey] = text }
    }

    // OBSOLETE_KEYS / OBSOLETE_PREFIXES / OBSOLETE_SENSOR_FIELDS (settings cleanup, October 2026 onward)
    static let obsoleteKeys: Set<String> = [
        "pref_scam_hybrid_denoise_engine", "pref_scam_hybrid_despeckle", "pref_scam_hybrid_why_dn_dark_chroma", "pref_scam_hybrid_why_ms_motion",
        "pref_scam_hybrid_dn_luma_mult", "pref_scam_hybrid_dn_sabre_luma_mult", "pref_scam_hybrid_dn_luma_gid14_mult",
        "pref_scam_hybrid_dn_chroma_mult", "pref_scam_hybrid_dn_chroma_floor", "pref_scam_hybrid_dn_revert_mult", "pref_scam_hybrid_dn_revert_max",
        "pref_scam_hybrid_dn_coarse_stock", "pref_scam_hybrid_dn_luma_grain", "pref_scam_hybrid_dn_dark_tier", "pref_scam_hybrid_dn_dark_chroma_hi",
        "pref_scam_hybrid_dn_dark_chroma_lo", "pref_scam_hybrid_dn_dark_chroma_noise", "pref_scam_hybrid_dn_dark_fade",
        "pref_scam_hybrid_dn_chroma_day", "pref_scam_hybrid_dn_chroma_2x_keep", "pref_scam_hybrid_dn_strength_map", "pref_scam_hybrid_dn_huge_map",
        "pref_scam_hybrid_dn_huge_fine",
        "pref_scam_upscale_backend_key", "pref_scam_downscale_kernel_key", "pref_scam_downscale_size_key",
        "pref_tunable_imagesaversettings_croptype",
        "pref_scam_hybrid_enabled", "pref_scamroute_enabled", "pref_scamhdr_enabled",
        "pref_frame_count_key", "pref_short_frame_count_key", "pref_short_exposure_ev_key", "pref_long_frame_count_key",
        "pref_long_exposure_ev_key", "pref_highlight_suppression_key", "pref_zsl_quality_selection_key", "pref_max_hdr_ratio_key",
        "pref_tet_model_enabled_key", "pref_long_frame_shutter_cap_key", "pref_zsl_merge_algorithm_key", "pref_night_merge_algorithm_key",
        "pref_processing_backend_key", "pref_merge_robustness_key", "pref_merge_clip_level_key", "pref_merge_max_exposure_ratio_key",
        "pref_merge_floor_sigmas_key", "pref_merge_tiling_tolerance_key", "pref_merge_seekbar_key", "pref_highlight_recovery_key",
        "pref_highlight_recovery_min_ok_key", "pref_highlight_protection_key", "pref_highlight_protection_knee_key",
        "pref_highlight_protection_strength_key", "pref_binning_key", "pref_energy_safe_key", "pref_raw_mfsr_enabled_key",
        "pref_remosaic_enabled_key", "pref_remosaic_backend_key", "pref_hexquad_frames", "pref_quad_frames", "hexquad_exposure_ev",
        "hexquad_full_resolution", "hexquad_post_denoise", "quad2x2_exposure_ev", "quad2x2_post_denoise", "pref_scamroute_luma",
        "pref_scamroute_chroma", "pref_scamroute_sharpen", "pref_ai_denoise_enabled_key", "pref_ai_denoise_strength_key",
        "pref_ai_denoise_luma_key", "pref_ai_denoise_chroma_key", "pref_ai_denoise_model_key", "pref_remosaic_dump_key",
        "pref_noise_dynamic_enabled_key",
        "pref_rt_denoise_backend", "pref_capture_one_enabled_key", "pref_false_color_enabled_key", "pref_saliency_protection_key",
        "pref_false_color_strength_key", "pref_defringe_purple_key", "pref_defringe_green_key", "pref_ca_red_key", "pref_ca_blue_key",
        "pref_saturation_seekbar_key", "pref_contrast_seekbar_key", "pref_shadows_seekbar_key", "pref_compressor_seekbar_key",
        "pref_sensor_sharpening_enabled", "pref_noise_disable_digital_gain_key", "pref_noise_model_coefficient_key", "pref_noise_iso_curve_key",
        "pref_noise_iso_min_key", "pref_noise_iso_max_key", "pref_noise_iso_manual_key", "pref_noise_seekbar_key", "pref_agx_local_highlights",
        "pref_agx_local_start",
        "pref_expocompensation_seekbar_key", "pref_scamold_ae_mid", "pref_scamold_ae_high", "pref_scamold_ae_gain_max", "pref_scamhdr_soft_tone",
        "pref_scamhdr_tone_key", "pref_scamhdr_sharp_amount", "pref_scamhdr_texture", "pref_scamhdr_warm_retention", "pref_scam_hybrid_ae_mid",
        "pref_scam_hybrid_ae_high", "pref_scam_hybrid_ae_gain_max", "pref_scam_hybrid_bento_fusion", "pref_scam_hybrid_texture",
        "pref_scam_hybrid_why_tone", "pref_scam_hybrid_soft_tone", "pref_scam_hybrid_tone_key", "pref_scam_hybrid_warm_retention",
        "pref_scamhdr_route", "pref_rawvideo_downscale_4x_key", "pref_rawvideo_write_zip_key", "pref_rawvideo_crop_169_key",
        "pref_scam_hybrid_boost",
        "pref_scam_tone_curve", "pref_scam_tone_curve_strength", "pref_scam_gamma_curve", "pref_scam_gamma_curve_strength",
        "pref_tunable_camerauiviewimpl_enablequadres", "ui_sheet_quick", "settings_favorite_keys",
        "pref_preview_format_key",
        "pref_enable_system_nr_key", "pref_disable_aligning_key", "pref_enhanced_processing_key", "pref_hdrx_nr_key", "pref_chroma_nr_seekbar_key",
        "pref_luma_nr_seekbar_key", "pref_gain_seekbar_key", "pref_sharpness_seekbar_key", "pref_nr_luma_enabled_key", "pref_nr_chroma_enabled_key",
        "pref_live_viewfinder_look_key", "pref_align_method_key",
        "pref_hdrx_key",
        "pref_quad_bayer_key", "scamera_quad_bayer_enabled", "scamera_quad_bayer_mode", "scamera_quad_dng_metadata",
        "pref_show_gradient_key",
        "pref_scam_hybrid_why_face_priority", "pref_scam_hybrid_why_hl_blend",
        "pref_scam_hybrid_sharp_mode", "pref_scam_hybrid_why_post_sharp", "pref_scam_hybrid_why_sharp_guard", "pref_scam_hybrid_sharp_strength",
        "pref_scam_hybrid_sharp_amount", "pref_scam_hybrid_why_detail_gain",
        "pref_scam_hybrid_neural_remosaic", "pref_scam_hybrid_neural_remosaic_rev", "pref_tetra_response_key", "pref_remosaic_block_key",
        "pref_remosaic_profile_key", "pref_remosaic_steered_key", "pref_remosaic_clamp_key", "pref_remosaic_flatfield_key",
        "pref_remosaic_phase_x_key", "pref_remosaic_phase_y_key",
        "pref_scam_hybrid_noise_source", "pref_scam_hybrid_bento_weight",
        "pref_scam_hybrid_why_gf_radius", "pref_scam_hybrid_why_gf_eps_e3",
        "pref_scam_hybrid_static_sr", "pref_scam_hybrid_static_iters",
    ]
    static let obsoletePrefixes = [
        "pref_raisr_", "pref_softpqe_", "pref_snr_", "pref_mfsr_", "scamera_mosaic_sr_", "pref_hdrplus_", "pref_tunable_esd4d_",
        "pref_tunable_pyramidalignment_", "rt512_", "pref_rt_nr_", "pref_aces_", "pref_c1_", "scamera_darktable_", "pref_tunable_postpipeline_",
        "pref_tunable_esd3d2_", "pref_tunable_ablc_", "pref_tunable_initial_", "pref_tunable_autoexposurecurve_", "pref_tunable_opendrt_",
        "pref_tunable_locallaplacian_", "pref_tunable_linearexposure_", "pref_tunable_headroomrender_", "pref_tunable_bayer2float_",
        "pref_tunable_amaze_", "pref_agx_", "pref_scamroute_", "pref_scamhdr_fusion_", "pref_scam_hybrid_hdr_", "pref_scam_hybrid_fusion_",
        "pref_scam_hybrid_agx_", "pref_scam_hybrid_rawca_", "pref_scam_hybrid_jsr_", "pref_sharp_", "pref_scamhdr_sharp_", "pref_scamhdr_",
        "pref_scamold_", "hexquad_", "quad2x2_",
    ]
    static let obsoleteSensorFields = ["exposurebalanceisolimit", "exposurebalanceshutterlimit", "exposurebalancemultiplier"]

    static func isObsolete(_ key: String) -> Bool {
        if obsoleteKeys.contains(key) || obsoletePrefixes.contains(where: { key.hasPrefix($0) }) { return true }
        return key.hasPrefix("pref_sensorconfig_") && obsoleteSensorFields.contains { key.hasSuffix("_" + $0) }
    }

    private static let wholeNumberKeys = ["pref_scam_hybrid_why_metering", "pref_scam_hybrid_why_agx_look", "pref_scam_hybrid_why_sharp_domain",
                                          "pref_scam_hybrid_why_sharp_rl1_kernel", "pref_scam_hybrid_why_sharp_rl2_kernel",
                                          "pref_scam_hybrid_why_sharp_rl3_kernel", "pref_scamhdr_luma_radius", "pref_scamhdr_chroma_radius"]

    static func removeObsolete(_ p: inout Values) {
        for key in Array(p.keys) where isObsolete(key) { p[key] = nil }
        if let tiles = p[shadeTilesKey] as? String {
            let input = splitTiles(tiles), kept = input.filter { !isObsolete(tileKey($0)) }
            if kept.count != input.count { p[shadeTilesKey] = kept.joined(separator: ",") }
        }
        if let cfa = p["pref_cfa_key"], [4.0, -2.0].contains(PrefValue.number(cfa, -1)) { p["pref_cfa_key"] = "-1" }
        if let processing = p["pref_scam_hybrid_processing"], PrefValue.text(processing).trimmingCharacters(in: .whitespaces) == "static" {
            p["pref_scam_hybrid_processing"] = "quality"
        }
        if let window = p["pref_scam_hybrid_mosaic_window"], PrefValue.number(window, 0) > 4 { p["pref_scam_hybrid_mosaic_window"] = "4" }
        if let edge = p["pref_scam_hybrid_bento_chroma_sigma"] {
            let sigma = PrefValue.number(edge, 2)
            if !(sigma > 0) { p["pref_scam_hybrid_bento_chroma_sigma"] = nil } else if sigma < 0.5 { p["pref_scam_hybrid_bento_chroma_sigma"] = "0.5" }
        }
        for key in wholeNumberKeys {
            guard let value = p[key] else { continue }
            var v = PrefValue.number(value, .nan)
            guard v.isFinite else {
                p[key] = nil
                continue
            }
            // SettingsNumericRules.bounds: the row's own range
            if let node = PageReset.find(key), let lo = node.min, let hi = node.max { v = max(lo, min(hi, v)) }
            p[key] = String(Int(v.rounded(.toNearestOrAwayFromZero)))
        }
    }

    // SettingsMigration.migrateScamHybrid (freshInstall false: an imported config) and its defaults revisions
    private static let pre = "pref_scam_hybrid_"
    private static let sharedScamKeys: Set<String> = ["zsl_frames", "highlight_recovery", "isp_ccm", "noise_photon", "noise_readout",
                                                      "noise_source", "cre_source", "diagnostics"]
    private static let sharedScamPrefixes = ["fusion_", "tone_", "look", "warm_", "texture", "grain_"]
    private static let unsharedHdrKeys: Set<String> = ["enabled", "luma", "chroma", "sharpen", "reset_tone"]

    static func sharedWithHybrid(_ key: String) -> Bool {
        if key.hasPrefix("pref_scamhdr_hybrid_") || key == "pref_scamhdr_hybrid" { return false }
        if key.hasPrefix("pref_scamhdr_") {
            let k = String(key.dropFirst("pref_scamhdr_".count))
            if k == "soft_tone" { return false }
            return sharedScamKeys.contains(k) || sharedScamPrefixes.contains { k.hasPrefix($0) }
        }
        if key.hasPrefix("pref_scamold_") {
            let k = String(key.dropFirst("pref_scamold_".count))
            return k.hasPrefix("ae_") || k == "fast_capture"
        }
        if key.hasPrefix("pref_agx_scam_") { return true }
        if key.hasPrefix("pref_scamroute_") { return !unsharedHdrKeys.contains(String(key.dropFirst("pref_scamroute_".count))) }
        return false
    }

    static func copyKey(_ key: String) -> String {
        if key.hasPrefix("pref_scamhdr_hybrid_") { return pre + key.dropFirst("pref_scamhdr_hybrid_".count) }
        if key.hasPrefix("pref_scamhdr_") { return pre + key.dropFirst("pref_scamhdr_".count) }
        if key.hasPrefix("pref_agx_scam_") { return pre + "agx_" + key.dropFirst("pref_agx_scam_".count) }
        if key.hasPrefix("pref_scamold_") { return pre + key.dropFirst("pref_scamold_".count) }
        if key.hasPrefix("pref_scamroute_") { return pre + "hdr_" + key.dropFirst("pref_scamroute_".count) }
        return key
    }

    private static func legacyHybridShots(_ v: Values) -> Bool {
        guard PrefValue.bool(v["pref_scamroute_enabled"], false), PrefValue.bool(v["pref_scamhdr_enabled"], false) else { return false }
        let engine = v["pref_scamhdr_engine"]
        if engine == nil && v["pref_scamhdr_hybrid"] != nil { return PrefValue.bool(v["pref_scamhdr_hybrid"], true) }
        let e = engine.map(PrefValue.text) ?? "auto"
        return e == "hybrid" || e != "scam"   // an iPhone is no SM8750 (ScamHybridKeys.scamNetSoc)
    }

    static let toneDefaultPoints = "0.0000:0.0000,0.2500:0.1600,0.5000:0.5000,0.7500:0.8400,1.0000:1.0000"
    static let toneSettingPoints = "0.0000:0.0000,0.0625:0.0314,0.1250:0.0599,0.1875:0.0881,0.2500:0.1282,0.3125:0.1971,0.3750:0.2867,0.4375:0.3915,0.5000:0.5100,0.5625:0.6347,0.6250:0.7542,0.6875:0.8577,0.7500:0.9350,0.8125:0.9757,0.8750:0.9937,0.9375:1.0000,1.0000:1.0000"

    /// CONFIG_DEFAULTS: (key, the former default, today's) - a Bool, a String or a Float
    private static let configDefaults: [(String, Any, Any)] = [
        ("why_bright_thresh", Float(0.8), Float(1)), ("why_hl_boost", Float(1), Float(1)), ("why_hl_blend", Float(0.33), Float(0.5)),
        ("why_dark_thresh", Float(15), Float(10)), ("why_dark_pixel_thresh", Float(10), Float(22.5)), ("why_contrast_boost", Float(0.5), Float(0.3)),
        ("why_agx_slope", Float(2.7), Float(2.45)), ("why_agx_tp", Float(1.6), Float(2)), ("why_agx_ev", Float(0.3), Float(0)),
        ("why_agx_inset", Float(100), Float(50)), ("why_uchi_post_gain", Float(0.9), Float(1)), ("why_curve_on", false, true),
        ("why_curve_points", toneDefaultPoints, toneSettingPoints), ("why_curve_boost", Float(0), Float(1)), ("why_film_toe", Float(0.1), Float(0)),
        ("why_highlight_str", Float(2), Float(2)), ("why_fusion_layout", "0", "1"), ("why_classic_weight_hl", Float(0.8), Float(1)),
        ("why_classic_weight_mid", Float(1), Float(1)), ("why_classic_weight_shadow", Float(0.5), Float(1.5)),
        ("why_classic_weight_center", Float(0.5), Float(1)), ("why_blend_smoothness", Float(0.25), Float(1)), ("why_bracket_denoise", Float(1), Float(0)),
        ("why_ms_denoise", false, true), ("why_ms_luma", Float(1), Float(0.2)), ("why_ms_luma_l0", Float(1), Float(0.4)),
        ("why_ms_luma_l1", Float(1), Float(0.1)), ("why_ms_luma_l2", Float(1), Float(0.35)), ("why_ms_chroma", Float(2), Float(0.5)),
        ("why_ms_chroma_l0", Float(1), Float(0.2)), ("why_ms_chroma_l1", Float(1), Float(0.4)), ("why_ms_chroma_l2", Float(1), Float(0.6)),
        ("why_ms_model_scale", Float(1), Float(1.5)), ("why_hr_strength", Float(1), Float(0.6)), ("why_hr_knee", Float(0.5), Float(0.6)),
        ("why_hr_room", Float(0.4), Float(0.35)), ("why_vibrance_sky", Float(0.4), Float(0.25)), ("why_vibrance_green", Float(0.2), Float(0.05)),
        ("why_cct_sat", Float(1), Float(1.1)), ("why_sharp_usm_radius", Float(0.5), Float(0.1)), ("why_sharp_usm_amount", Float(1), Float(0.5)),
        ("why_sharp_usm_thresh", Float(50), Float(0)), ("why_sharp_gf_lc", Float(0.25), Float(0)), ("why_sharp_rl3_amount", Float(1), Float(1.5)),
        ("dezipper", Float(0), Float(4.5)),
    ]
    private static let configV33: [(String, Any, Any)] = [
        ("why_ae_target", Float(0.18), Float(0.15)), ("why_bright_thresh", Float(1), Float(0.9)), ("why_dark_thresh", Float(10), Float(12.5)),
        ("why_dark_pixel_thresh", Float(22.5), Float(22)), ("why_hl_boost", Float(1), Float(1.1)), ("why_highlight_str", Float(2), Float(2.1)),
        ("why_blend_smoothness", Float(1), Float(0.45)), ("why_macro_contrast", Float(1), Float(1.1)), ("why_classic_weight_ext_hl", Float(1), Float(0.7)),
        ("why_classic_weight_mid", Float(1), Float(2)), ("why_classic_weight_shadow", Float(1.5), Float(1)),
        ("why_portrait_ae", Float(0.7), Float(1)), ("why_portrait_awb", Float(0.5), Float(1)), ("why_portrait_curve_on", false, true),
    ]
    /// V2_MERGED: (V2's key, V1's key, V2's former default, V1's default)
    private static let v2Merged: [(String, String, Float, Float)] = [
        ("ae_target", "ae_target", 0.14999999, 0.15), ("max_boost", "ae_max_boost", 5, 5), ("min_limit", "ae_min_limit", 0.5, 0.5),
        ("bright_thresh", "bright_thresh", 0.90000004, 0.9), ("hl_boost", "hl_boost", 1.1, 1.1), ("hl_overflow", "hl_overflow", 2, 2),
        ("hl_blend", "hl_blend", 0.5, 0.5), ("dark_thresh", "dark_thresh", 12.5, 12.5), ("dark_pixel_thresh", "dark_pixel_thresh", 22, 22),
        ("contrast_boost", "contrast_boost", 0.3, 0.3), ("night_dim", "night_thresh", 0.049999997, 0.05), ("highlight_str", "highlight_str", 2.1000001, 2.1),
        ("shadow_str", "shadow_str", 2, 2), ("weight_ext_hl", "classic_weight_ext_hl", 0.7, 0.7), ("weight_hl", "classic_weight_hl", 1, 1),
        ("weight_mid", "classic_weight_mid", 2, 2), ("weight_shadow", "classic_weight_shadow", 1, 1), ("weight_center", "classic_weight_center", 1, 1),
        ("blend_smoothness", "blend_smoothness", 0.45000002, 0.45), ("macro_contrast", "macro_contrast", 1.1, 1.1), ("gamma_type", "gamma_type", 0, 0),
        ("gamma", "gamma", 2.2, 2.2), ("film_toe", "film_toe", 0, 0), ("agx_slope", "agx_slope", 2.45, 2.45),
        ("agx_sp", "agx_sp", 1.35, 1.35), ("agx_tp", "agx_tp", 2, 2), ("agx_min_ev", "agx_min_ev", -8.5, -8.5),
        ("agx_max_ev", "agx_max_ev", 3.5, 3.5), ("agx_sat", "agx_sat", 1, 1), ("agx_ev", "agx_ev", 0, 0),
        ("agx_inset", "agx_inset", 50, 50), ("aces_toe", "aces_toe", 0.05, 0.05), ("aces_pre_gain", "aces_pre_gain", 1, 1),
        ("aces_a", "aces_a", 2.61, 2.61), ("aces_d", "aces_d", 0.59, 0.59), ("uchi_pre_gain", "uchi_pre_gain", 1, 1),
        ("uchi_post_gain", "uchi_post_gain", 1, 1), ("uchi_p", "uchi_p", 1, 1), ("uchi_a", "uchi_a", 1, 1),
        ("uchi_m", "uchi_m", 0.22, 0.22), ("uchi_l", "uchi_l", 0.4, 0.4), ("uchi_c", "uchi_c", 1.33, 1.33),
        ("uchi_b", "uchi_b", 0, 0), ("curve_lift", "curve_lift", 0, 0), ("curve_contrast", "curve_contrast", 0, 0),
        ("curve_shadow_contrast", "curve_shadow_contrast", 0, 0), ("curve_highlight_contrast", "curve_highlight_contrast", 0, 0),
        ("curve_deep_shadows", "curve_deep_shadows", 0, 0), ("curve_extreme_highlights", "curve_extreme_highlights", 0, 0),
        ("curve_offset", "curve_offset", 0, 0), ("vibrance", "vibrance", 0, 0), ("vibrance_saturated", "vibrance_saturated", 0, 0),
        ("vibrance_sky", "vibrance_sky", 0.25, 0.25), ("vibrance_green", "vibrance_green", 0.05, 0.05), ("vibrance_hl", "vibrance_hl_str", 0.25, 0.25),
        ("vibrance_mid", "vibrance_mid", 0, 0), ("vibrance_shadow", "vibrance_shadows", 0, 0), ("skin_protect", "skin_protect", 1, 1),
        ("skin_hue_shift", "skin_hue_shift", 0, 0), ("skin_sat_shift", "skin_sat_shift", 0, 0), ("skin_luma_min", "skin_luma_min", 0.16, 0.16),
        ("shoulder_strength", "hr_strength", 0.6, 0.6), ("shoulder_knee", "hr_knee", 0.6, 0.6), ("shoulder_headroom", "hr_room", 0.35, 0.35),
        ("bracket_denoise", "bracket_denoise", 0, 0),
    ]
    private static let toneGroupPages = ["pref_scam_hybrid_why_ae_target", "pref_scam_hybrid_why_macro_contrast", "pref_scam_hybrid_why_agx_sat",
                                         "scam_hybrid_why_ae_screen", "scam_hybrid_why_curves_screen", "scam_hybrid_why_tone_screen"]

    /// One migration of a «marker once» revision: `body` only when the marker is not stored yet; the marker set either way
    private static func once(_ p: inout Values, _ values: Values, _ marker: String, _ body: (inout Values) -> Void) {
        guard values[marker] == nil else { return }
        body(&p)
        p[marker] = NSNumber(value: 1)
    }

    static func migrateScamHybrid(_ p: inout Values) {
        let values = p
        var written = Set<String>()
        let firstRun = values["pref_scam_hybrid_enabled"] == nil && values["pref_merge_route"] == nil
        let hybridShots = firstRun && legacyHybridShots(values)
        for (key, value) in values where key.hasPrefix("pref_scamhdr_hybrid_") {
            let k = String(key.dropFirst("pref_scamhdr_hybrid_".count)), target = pre + k
            if k != "soft_tone" && k != "bento_factor" && values[target] == nil {
                p[target] = value
                written.insert(target)
            }
            p[key] = nil
        }
        if hybridShots {
            for (key, value) in values where sharedWithHybrid(key) {
                if key == "pref_scamhdr_noise_source" && PrefValue.text(value) != "settings" { continue }
                let target = copyKey(key)
                if target == key || values[target] != nil || written.contains(target) { continue }
                written.insert(target)
                p[target] = value
            }
        }
        if values["pref_merge_route"] == nil {
            let hybrid = firstRun ? hybridShots : PrefValue.bool(values["pref_scam_hybrid_enabled"], false)
            let scam = PrefValue.bool(values["pref_scamroute_enabled"], false) && PrefValue.bool(values["pref_scamhdr_enabled"], false)
            p["pref_merge_route"] = hybrid || !scam ? "hybrid" : "scamhdr"
        }
        for old in ["pref_scam_hybrid_enabled", "pref_scamroute_enabled", "pref_scamhdr_enabled", "pref_scamhdr_engine", "pref_scamhdr_hybrid"] {
            p[old] = nil
        }
        let revision = (values["pref_scam_hybrid_defaults_rev"] as? NSNumber).map { PrefValue.isBool($0) ? 0 : $0.intValue } ?? 0
        let defaultsRev = "pref_scam_hybrid_defaults_rev"
        if revision < 3 {
            var touched = false
            let rows3: [(String, Float, Float)] = [("dn_revert_max", 2, 9), ("dn_coarse_stock", 0.5, 0), ("dn_chroma_floor", 2.75, 0)]
            for (k, former, now) in rows3 {
                guard let v = values[pre + k] else { continue }
                if isNumber(v, former) { p[pre + k] = number(v, now) }
                touched = true
            }
            if touched { p[defaultsRev] = NSNumber(value: 3) }
        }
        if revision < 4 {
            var touched = false
            let rows4: [(String, Float, Float)] = [("shasta_frames", 2, 5), ("shasta_ev", 2, 1)]
            for (i, (k, former, now)) in rows4.enumerated() {
                guard let v = values[pre + k] else { continue }
                if isNumber(v, former) { p[pre + k] = number(v, now, i == 1) }
                touched = true
            }
            if touched { p[defaultsRev] = NSNumber(value: 4) }
        }
        if revision < 5, let cdm = values[pre + "cdm"] {
            if isNumber(cdm, 0.07) { p[pre + "cdm"] = number(cdm, 0.2) }
            p[defaultsRev] = NSNumber(value: 5)
        }
        if revision < 6 {
            var touched = false
            if let path = values[pre + "mosaic_path"] {
                if isNumber(path, 0) { p[pre + "mosaic_path"] = textOr(path, "1", 1) }
                touched = true
            }
            if let scale = values[pre + "mosaic_kernel_scale"] {
                if isNumber(scale, 1) { p[pre + "mosaic_kernel_scale"] = number(scale, 0.7) }
                touched = true
            }
            if touched || written.isEmpty { p[defaultsRev] = NSNumber(value: 6) }
        }
        if revision < 7 {
            if let tetra = values[pre + "mosaic_tetra"] {
                if isNumber(tetra, 0) { p[pre + "mosaic_tetra"] = textOr(tetra, "1", 1) }
                p[defaultsRev] = NSNumber(value: 7)
            } else if written.isEmpty {
                p[defaultsRev] = NSNumber(value: 7)
            }
        }
        var zslMovedTo30 = false
        once(&p, values, "pref_scam_hybrid_zsl_frames_rev") { p in
            if let f = values[pre + "zsl_frames"], isNumber(f, 20) {
                p[pre + "zsl_frames"] = textOr(f, "30", 30)
                zslMovedTo30 = true
            }
        }
        once(&p, values, "pref_scam_hybrid_mosaic_frames_rev") { p in
            if let f = values[pre + "mosaic_frames"], isNumber(f, 24) { p[pre + "mosaic_frames"] = textOr(f, "30", 30) }
        }
        once(&p, values, "pref_scam_hybrid_p68_defaults_rev") { p in
            let rows: [(String, Float, Float)] = [("zsl_frames", 30, 20), ("sabre61", 2, 1), ("mochi", 0, 2), ("bento", 1, 2), ("bento_frames", 2, 3),
                                                  ("shasta_frames", 5, 3)]
            for (i, (k, former, now)) in rows.enumerated() {
                guard let v = values[pre + k], isNumber(v, former) || (i == 0 && zslMovedTo30) else { continue }
                p[pre + k] = number(v, now, false)
            }
        }
        once(&p, values, "pref_scam_hybrid_p497_us_rev") { p in
            if let v = values[pre + "bento_frames"], isNumber(v, 3) { p[pre + "bento_frames"] = number(v, 2, false) }
        }
        once(&p, values, "pref_scam_hybrid_precision_rev") { p in
            if values[pre + "bento_trigger"] as? String == "0.00" { p[pre + "bento_trigger"] = "0.0005" }
            if values[pre + "lut_sigma"] as? String == "1.41" { p[pre + "lut_sigma"] = "1.414" }
        }
        once(&p, values, "pref_scamhdr_defaults_rev") { p in
            if let d = values["pref_scamhdr_diagnostics"], PrefValue.bool(d, false) { p["pref_scamhdr_diagnostics"] = false }
            if let planner = values["pref_scamhdr_planner"], PrefValue.text(planner).trimmingCharacters(in: .whitespaces) == "stock" {
                p["pref_scamhdr_planner"] = "scamera"
            }
        }
        once(&p, values, "pref_scam_hybrid_night_thresh_rev") { p in
            if let night = values[pre + "why_night_thresh"], let f = Float(PrefValue.text(night).trimmingCharacters(in: .whitespaces)), f >= 1 {
                p[pre + "why_night_thresh"] = textOr(night, "0.05", 0.05)
            }
        }
        once(&p, values, "pref_scam_hybrid_low_light_dn_rev") { p in
            if sameList(values[pre + "dn_luma_t1_strength"], [1, 3, 0, 0, 0]) { p[pre + "dn_luma_t1_strength"] = "1.2,1.1,1.2,1.1,1" }
            if sameList(values[pre + "dn_luma_t1_revert"], [9, 9, 0.08, 0.12, 0]) { p[pre + "dn_luma_t1_revert"] = "0.6,0.5,0.08,0.12,0" }
            if let m = values[pre + "dn_revert_max"], isNumber(m, 9) || isNumber(m, 2) && revision < 3 {
                p[pre + "dn_revert_max"] = textOr(m, "1", 1)
            }
        }
        once(&p, values, "pref_scam_hybrid_mosaic_kernel_rev") { p in
            if let s = values[pre + "mosaic_kernel_scale"], isNumber(s, 0.7) { p[pre + "mosaic_kernel_scale"] = number(s, 1) }
        }
        once(&p, values, "pref_scam_hybrid_fullres50_frames_rev") { p in
            if let f = values[pre + "fullres50_frames"], isNumber(f, 0) { p[pre + "fullres50_frames"] = number(f, 12, false) }
        }
        once(&p, values, "pref_scam_hybrid_detail_defaults_rev") { p in
            if let v = values[pre + "dn_model_read"], isNumber(v, 6) { p[pre + "dn_model_read"] = number(v, 2) }
        }
        once(&p, values, "pref_scam_hybrid_glow_defaults_rev") { p in
            if let v = values[pre + "why_weight_dark2"], isNumber(v, 0.4) { p[pre + "why_weight_dark2"] = number(v, 0.1) }
        }
        once(&p, values, "pref_scam_hybrid_cell_clip_rev") { p in
            if isTrue(values[pre + "cell_clip"]) { p[pre + "cell_clip"] = false }
        }
        once(&p, values, "pref_scam_hybrid_config_defaults_rev") { p in
            for (k, former, now) in configDefaults {
                guard let v = values[pre + k] else { continue }
                if let b = former as? Bool {
                    if PrefValue.isBool(v) && (v as! NSNumber).boolValue == b { p[pre + k] = now }
                } else if let s = former as? String {
                    if v as? String == s { p[pre + k] = now }
                } else if let f = former as? Float, isNumber(v, f), let to = now as? Float {
                    p[pre + k] = number(v, to)
                }
            }
        }
        once(&p, values, "pref_scam_hybrid_config_v33_rev") { p in
            for (k, former, now) in configV33 {
                guard let stored = values[pre + k] else { continue }
                var v: Any = stored
                if values["pref_scam_hybrid_config_defaults_rev"] == nil {
                    for (ok, of, on) in configDefaults where ok == k { if let f = of as? Float, isNumber(v, f) { v = on } }
                }
                if let b = former as? Bool {
                    if (PrefValue.isBool(v) && (v as! NSNumber).boolValue == b) || (v as? Bool) == b { p[pre + k] = now }
                } else if let f = former as? Float, isNumber(v, f), let to = now as? Float {
                    p[pre + k] = number(stored, to)
                }
            }
        }
        once(&p, values, "pref_scam_hybrid_config_stable_rev") { p in
            if values["pref_scam_hybrid_config_v33_rev"] != nil, let v = values[pre + "why_vibrance_hl_str"], isNumber(v, 0) {
                p[pre + "why_vibrance_hl_str"] = number(v, 0.25)
            }
        }
        once(&p, values, "pref_scam_hybrid_cct_sat_rev") { p in
            if let v = values[pre + "why_cct_sat"], isNumber(v, 1.25) { p[pre + "why_cct_sat"] = number(v, 1.1) }
        }
        once(&p, values, "pref_scam_hybrid_v2_merge_rev") { p in
            for (v2, v1, v2Default, v1Default) in v2Merged {
                let from = pre + "why_v2_" + v2, to = pre + "why_" + v1
                guard values[from] != nil else { continue }
                if let v = values[from], !isNumber(v, v2Default), values[to] == nil || isNumber(values[to], v1Default) { p[to] = v }
                p[from] = nil
            }
            let points = pre + "why_v2_curve_points"
            if let v = values[points] {
                let own = toneSettingPoints, text = PrefValue.text(v).trimmingCharacters(in: .whitespaces)
                let current = values[pre + "why_curve_points"].map { PrefValue.text($0).trimmingCharacters(in: .whitespaces) }
                if own != text && (current == nil || current == own) { p[pre + "why_curve_points"] = text }
                p[points] = nil
            }
            let operatorKey = pre + "why_v2_tone_operator"
            if values[operatorKey] != nil {
                let op = Int(PrefValue.number(values[operatorKey], 3).rounded())
                let atDefault = PrefValue.bool(values[pre + "why_curve_on"], true) && !PrefValue.bool(values[pre + "why_uchi_on"], false)
                    && !PrefValue.bool(values[pre + "why_aces_on"], false)
                if op >= 0 && op < 3 && atDefault {
                    p[pre + "why_curve_on"] = false
                    p[pre + "why_uchi_on"] = op == 2
                    p[pre + "why_aces_on"] = op == 1
                }
                p[operatorKey] = nil
            }
        }
        once(&p, values, "pref_scam_hybrid_vibrance_rev") { p in
            let sh = PrefValue.number(values[pre + "colour_vib_shadows"], 0), hl = PrefValue.number(values[pre + "colour_vib_highlights"], 0)
            if sh != 0 {
                let v = Float(max(-1, min(1, PrefValue.number(values[pre + "why_vibrance_shadows"], 0) + sh)))
                p[pre + "why_vibrance_shadows"] = textOr(values[pre + "why_vibrance_shadows"] as Any, format(v, true), v)
            }
            if hl != 0 { p[pre + "why_vibrance_hl_str"] = format(Float(max(0, min(1.5, 0.25 + hl))), true) }
            for k in ["colour_vib_shadows", "colour_vib_highlights", "colour_skin_protect"] { p[pre + k] = nil }
        }
        once(&p, values, "pref_scam_hybrid_fringe_light_rev") { p in
            if let v = values[pre + "fringe_net_light"], isNumber(v, 0.12) { p[pre + "fringe_net_light"] = number(v, 0.05) }
        }
        once(&p, values, "pref_scam_hybrid_yuv_10bit_rev") { p in
            if let v = values[pre + "yuv_10bit"], PrefValue.bool(v, false) { p[pre + "yuv_10bit"] = v is String ? "false" as Any : NSNumber(value: false) as Any }
        }
        once(&p, values, "pref_scam_hybrid_dnmerge_rev") { p in
            if let layout = values[SettingsLayout.key] as? String {
                p[SettingsLayout.key] = SettingsLayout.joinPages(layout, into: "scam_hybrid_dnmerge_screen",
                                                                 pages: ["scam_hybrid_photo_screen", "scam_hybrid_merge_screen"])
            }
        }
        once(&p, values, "pref_scam_hybrid_tone_group_rev") { p in
            if let layout = values[SettingsLayout.key] as? String {
                p[SettingsLayout.key] = SettingsLayout.carry(layout, into: "tone_settings_screen", from: "root_colour", moved: toneGroupPages)
            }
        }
        for k in ["dn_engine", "post_luma", "post_chroma", "dn_snr", "dn_strength_map"] { p[pre + k] = nil }
    }

    // ───── SettingsMigration.prepare: each row's value in the type its row stores ─────

    static func prepare(_ p: inout Values) {
        if let out = p["pref_scam_hybrid_output"] as? String {
            // P501: «Разрешение» as the Sabre scales (PreferenceKeys.outputMode: 16 / 20 MP -> 1.5x, anything else unknown -> sensor)
            let mode = out == "2x" ? "2x" : (out == "1.5x" || out == "16" || out == "20") ? "1.5x" : "sensor"
            if out != mode && out != "sensor" { p["pref_scam_hybrid_output"] = mode }
        }
        guard let tree = SettingsCatalog.shared.tree else { return }
        func walk(_ n: SettingsNode) {
            if let key = n.key, let value = p[key] {
                switch n.type {
                case "switch":
                    if !PrefValue.isBool(value) { p[key] = PrefValue.bool(value, n.defaultValue?.bool ?? false) }
                case "list", "text", "slider":
                    var text = PrefValue.text(value)
                    let entries = SettingsCatalog.shared.array(n.values)
                    if !entries.contains(text) {
                        let wanted = PrefValue.number(text, .nan)
                        if wanted.isFinite, let e = entries.first(where: { PrefValue.number($0, .nan) == wanted }) { text = e }
                    }
                    if !(value is String) || (value as? String) != text { p[key] = text }
                default:
                    break
                }
            }
            for c in n.children ?? [] { walk(c) }
        }
        walk(tree)
    }

    /// The one-time markers an Android install writes on its first start (its settings are today's): the iPhone's settings are today's
    /// too, so a config saved here carries them and no Android build moves its values as former defaults
    static let markers: Values = {
        var m: Values = ["settings_antibanding_list_v1": true, "settings_zsl_capacity_v2": true, "pref_merge_route": "hybrid",
                         "pref_scam_hybrid_defaults_rev": NSNumber(value: 7), "pref_watermark_text_rev": NSNumber(value: 1),
                         "pref_scamhdr_defaults_rev": NSNumber(value: 1)]
        for rev in ["zsl_frames", "mosaic_frames", "p68_defaults", "p497_us", "precision", "night_thresh", "low_light_dn", "mosaic_kernel",
                    "fullres50_frames", "detail_defaults", "glow_defaults", "cell_clip", "config_defaults", "config_v33", "config_stable",
                    "cct_sat", "v2_merge", "vibrance", "fringe_light", "yuv_10bit", "dnmerge", "tone_group"] {
            m["pref_scam_hybrid_" + rev + "_rev"] = NSNumber(value: 1)
        }
        return m
    }()

    /// The settings with the markers they lack (a config saved on the iPhone)
    static func withMarkers(_ values: Values) -> Values { values.merging(markers) { own, _ in own } }

    /// BackupRestoreUtil.migrate then SettingsMigration.prepare: an imported config's main settings as this build reads them. A config
    /// the iPhone saved before it carried the markers holds today's settings: it gets them first (no value is taken for a former default).
    static func importing(_ values: Values, fromIPhone: Bool = false) -> Values {
        var p = brand(fromIPhone ? withMarkers(values) : values)
        migrateMultiFrame(&p)
        migrateAntibanding(&p)
        migrateScamHybrid(&p)
        migrateShadeTiles(&p)
        migrateWatermark(&p)
        removeObsolete(&p)
        prepare(&p)
        return p
    }
}
