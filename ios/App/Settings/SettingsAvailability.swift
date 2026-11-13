import Foundation

/// Lang.t (util/Lang.java): the Russian or the English text of a line the Android code writes inline, in the language the app's
/// Localizable.strings are shown in.
enum Lang {
    static let russian: Bool = (Bundle.main.preferredLocalizations.first ?? "en").hasPrefix("ru")
    static func t(_ ru: String, _ en: String) -> String { russian ? ru : en }
}

/// PreferenceNumber.read / bool / text: a stored value as the Android code reads it (a String of a slider or list, a number, a
/// switch's Bool).
enum PrefValue {
    static func isBool(_ v: Any?) -> Bool {
        guard let n = v as? NSNumber else { return false }
        return CFGetTypeID(n) == CFBooleanGetTypeID()
    }

    static func number(_ v: Any?, _ fallback: Double) -> Double {
        guard let v else { return fallback }
        if isBool(v) { return (v as! NSNumber).boolValue ? 1 : 0 }
        if let n = v as? NSNumber { return n.doubleValue.isFinite ? n.doubleValue : fallback }
        let s = text(v).trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        guard let d = Double(s), d.isFinite else { return fallback }
        return d
    }

    static func bool(_ v: Any?, _ fallback: Bool) -> Bool {
        if isBool(v) { return (v as! NSNumber).boolValue }
        if let v {
            let s = text(v).lowercased()
            if s == "true" { return true }
            if s == "false" { return false }
        }
        return number(v, fallback ? 1 : 0) != 0
    }

    /// Java's toString of a stored value
    static func text(_ v: Any) -> String {
        if isBool(v) { return (v as! NSNumber).boolValue ? "true" : "false" }
        if let s = v as? String { return s }
        if let n = v as? NSNumber { return n.stringValue }
        if let a = v as? [String] { return "[" + a.joined(separator: ", ") + "]" }
        return "\(v)"
    }
}

/// SettingsAvailability (settings/SettingsAvailability.java): why a row does not act with the other settings as they are (a
/// greyed row with its reason), and which rows belong to another choice or to what this phone lacks (hidden). The Android rules
/// that depend on the stored values only are ported as they are; the device facts are the iPhone's (no Xiaomi tele zoom, no OPPO
/// tuned matrix, no FULLRES remosaic choice or 50 / 200 MP profiles, no Quad / Tetra stream, no DCG, no YUV merge route, no RAW
/// viewfinder). `iPhoneReason` adds the rows the iPhone cannot serve at all (root, Android camera packages, vendor sensor modes,
/// the NPU / neural parts the iOS pipeline lacks).
struct SettingsAvailability {
    let values: [String: Any]

    // the device facts of SettingsAvailability's setters, as an iPhone has them
    private let xiaomiSmoothZoom = false
    private let oppoTunedColor = false
    private let fullResHwRemosaic = false
    private let mosaicStreams = false
    private let fullRes50 = false, fullRes200 = false
    private let bentoBlend = true
    private let dcg = false
    private let yuvMerge = false
    private let rawViewfinder = false
    private let heic10Unavailable: String? = nil
    private let hdrUnavailable: String? = nil
    private let colorMethodOverride: String? = nil

    init(_ values: [String: Any]) { self.values = values }

    private func text(_ key: String, _ fallback: String) -> String {
        guard let v = values[key] else { return fallback }
        return PrefValue.text(v)
    }
    private func on(_ key: String, _ fallback: Bool) -> Bool { PrefValue.bool(values[key], fallback) }
    private func num(_ key: String, _ fallback: Double) -> Double { PrefValue.number(values[key], fallback) }
    private func any(_ key: String, _ alternatives: String...) -> Bool { alternatives.contains(key) }

    private var photoFormat: String {
        let f = text("pref_photo_format", "jpeg").trimmingCharacters(in: .whitespaces).lowercased()
        return ["heic", "webp", "avif"].contains(f) ? f : "jpeg"
    }
    private var writesJpeg: Bool { photoFormat == "jpeg" || on("pref_photo_also_jpeg", false) }

    private func film(_ alone: Bool) -> Bool {
        if text("pref_film_sim_stock", "").trimmingCharacters(in: .whitespaces).isEmpty { return false }
        let strength = num("pref_film_sim_strength", 100)
        return alone ? strength >= 100 : strength > 0
    }

    private static let WHY = "pref_scam_hybrid_why_", COLOUR = "pref_scam_hybrid_colour_", HYB = "pref_scam_hybrid_"

    static func filmBypassed(_ key: String) -> Bool {
        if key.hasPrefix(COLOUR) {
            let k = String(key.dropFirst(COLOUR.count))
            return !(k == "warmth" || k == "tint" || k == "keep_warm" || k == "keep_cool" || k.hasPrefix("cal_"))
        }
        guard key.hasPrefix(WHY) else { return false }
        let k = String(key.dropFirst(WHY.count))
        if k.hasPrefix("agx_") || k.hasPrefix("aces_") || k.hasPrefix("uchi_") || k.hasPrefix("curve_") || k.hasPrefix("sharp_") || k.hasPrefix("v2_") {
            return true
        }
        return [
            "macro_contrast", "gamma", "film_toe", "contrast_boost", "hl_soft", "hl_white", "ccm_sat",
            "vibrance", "vibrance_sky", "vibrance_green",
            "bright_thresh", "dark_thresh", "dark_pixel_thresh", "hl_boost", "shadow_str", "highlight_str",
            "weight_dark2", "weight_hl", "weight_mid", "weight_bright", "weight_center", "blend_smoothness",
            "ef_coarse",
            "fusion_layout", "classic_weight_ext_hl", "classic_weight_hl", "classic_weight_mid", "classic_weight_shadow",
            "classic_weight_center", "hr_strength", "hr_knee", "hr_room",
            "gamma_type", "vibrance_saturated", "vibrance_mid", "vibrance_shadows", "skin_protect",
            "skin_hue_center", "skin_luma_min", "skin_sat_shift",
            "vibrance_hl_str",
            "clarity", "flat_protect", "edge_hold",
            "cct_sat", "cct_gain_r", "cct_gain_g", "cct_gain_b",
            "dynamic_cct", "dynamic_cct_strength",
        ].contains(k)
    }

    /// Rows of another choice or of what this phone lacks: hidden (SettingsAvailability.hidden).
    func hidden(_ key: String) -> Bool {
        if !xiaomiSmoothZoom && key == "pref_xiaomi_smooth_zoom" { return true }
        if !fullResHwRemosaic && key == "pref_fullres_hw_remosaic" { return true }
        if !fullRes50 && (key == "pref_scam_hybrid_fullres50_frames" || key == "pref_scam_hybrid_fullres50_zsl") { return true }
        if !fullRes200 && key == "pref_scam_hybrid_fullres200_frames" { return true }
        if !oppoTunedColor && key == "pref_scam_hybrid_why_ccm_sat" { return true }
        if key == "fringe_net_screen" {
            return text("pref_scam_hybrid_fringe_method", "classic").trimmingCharacters(in: .whitespaces) != "neural"
        }
        let format = photoFormat
        switch key {
        case "pref_heic_quality", "pref_heic_10bit": return format != "heic"
        case "pref_webp_quality", "pref_webp_lossless": return format != "webp"
        case "pref_avif_quality", "pref_avif_lossless", "pref_avif_depth", "pref_avif_chroma", "pref_avif_speed": return format != "avif"
        case "pref_photo_also_jpeg": return format == "jpeg"
        default: return false
        }
    }

    private var toneV2: Bool { text("pref_scam_hybrid_tone_version", "v1").trimmingCharacters(in: .whitespaces) == "v2" }

    private static let V1_ONLY: Set<String> = [
        WHY + "sharp_gain", WHY + "metering", WHY + "night_dim", WHY + "agx_look", COLOUR + "hue_lock", WHY + "curve_boost",
        WHY + "fusion_layout", WHY + "weight_hl", WHY + "weight_dark2", WHY + "weight_mid", WHY + "weight_bright",
        WHY + "weight_center", WHY + "ef_coarse", WHY + "clarity", WHY + "sharp_domain", WHY + "ccm_sat", COLOUR + "gamut",
        COLOUR + "mono", COLOUR + "skin_hue", WHY + "skin_hue_center", COLOUR + "skin_even",
        WHY + "portrait_ae", WHY + "portrait_awb", WHY + "portrait_curve_on", WHY + "portrait_curve_points",
        WHY + "hl_soft", WHY + "hl_white", WHY + "delta_chroma", WHY + "flat_protect",
        "scam_hybrid_why_weights_category", "scam_hybrid_why_artifacts_category", "portrait_face_category",
        "portrait_curve_category", "portrait_mask_screen", "colour_mixer_screen", "colour_grading_screen",
    ]

    static func v1Only(_ key: String) -> Bool {
        V1_ONLY.contains(key) || key.hasPrefix(WHY + "portrait_curve_") || key.hasPrefix(WHY + "face_")
            || key.hasPrefix(COLOUR + "mix_") || key.hasPrefix(COLOUR + "grade_")
    }
    static func v2Only(_ key: String) -> Bool { key.hasPrefix(WHY + "v2_") }

    private var oldstyleV2: Bool { text("pref_scam_hybrid_oldstyle_version", "v1").trimmingCharacters(in: .whitespaces) == "v2" }

    func oldstyleVersionReason(_ key: String) -> String? {
        let v1Only = key.hasPrefix("pref_scam_hybrid_oldstyle_v1_"), v2Only = key.hasPrefix("pref_scam_hybrid_oldstyle_v2_")
        if oldstyleV2 ? v1Only : v2Only {
            return oldstyleV2 ? Lang.t("Только для ScamMaster V1: выбран V2.", "ScamMaster V1 only: V2 is chosen.")
                : Lang.t("Только для ScamMaster V2: выбран V1.", "ScamMaster V2 only: V1 is chosen.")
        }
        return nil
    }

    private static let OLD = "pref_scam_hybrid_oldstyle_"

    func oldstyleRowReason(_ key: String) -> String? {
        guard key.hasPrefix(Self.OLD) else { return nil }
        let k = String(key.dropFirst(Self.OLD.count)), hdr = text(Self.OLD + "hdr", "auto").trimmingCharacters(in: .whitespaces)
        if hdr != "auto" && any(k, "v1_hdr_min", "v1_hdr_max", "v2_hdr_min", "v2_hdr_forgive") {
            return Lang.t("Только при HDR «Авто»: сейчас решение HDR задано вручную.", "Only with HDR “Auto”: the HDR decision is set by hand now.")
        }
        if hdr == "off" && any(k, "v1_hdr_cap", "v2_hdr_max", "fusion_base", "highlight_target", "highlight_percentile", "shadow_ev", "center") {
            return Lang.t("HDR выключен: снимок — кадр как есть.", "HDR is off: the photo is the frame as it is.")
        }
        if k.hasPrefix("dehaze_") && !on(Self.OLD + "dehaze", true) {
            return Lang.t("Включите «Убирать дымку».", "Turn on “Remove haze”.")
        }
        if k.hasPrefix("v1_lowfreq_") && !on(Self.OLD + "v1_lowfreq", true) {
            return Lang.t("Включите «V1: цвет теней из склейки».", "Turn on “V1: shadow colour from the merge”.")
        }
        if any(k, "vf_ev", "vf_match", "vf_smooth") && !on(Self.OLD + "vf_tone", true) {
            return Lang.t("Включите «Тон в видоискателе».", "Turn on “Tone in the viewfinder”.")
        }
        return nil
    }

    func toneVersionReason(_ key: String) -> String? {
        if let old = oldstyleVersionReason(key) { return old }
        if toneV2 ? Self.v1Only(key) : Self.v2Only(key) {
            return toneV2 ? Lang.t("Только для Why тона V1: выбран V2 (снимки 200 Мп по-прежнему идут через V1).",
                                   "Why tone V1 only: V2 is chosen (200 MP shots still take V1).")
                : Lang.t("Только для Why тона V2 (Настройки › Why тон).", "Why tone V2 only (Settings › Why tone).")
        }
        return nil
    }

    private static let QUALITY_ONLY: Set<String> = [
        HYB + "cre_source", HYB + "local_align", HYB + "la_engine",
        HYB + "cdm", HYB + "filter_variance", HYB + "dilate", HYB + "dilate_floor", HYB + "motion_boost", HYB + "boost_value",
        HYB + "boost_threshold", HYB + "motion_threshold", HYB + "chroma_diff", HYB + "chroma_diff_clamp", HYB + "dezipper",
        HYB + "hot_sigma", HYB + "hot_base_sigma", HYB + "hot_max_key", HYB + "cell_clip",
        HYB + "sabre61", HYB + "s61_max_key", HYB + "s61_min_motion", HYB + "kernel", HYB + "snr_scale", HYB + "tensor_noise",
        HYB + "widen_below", HYB + "weight_cap", HYB + "fwe", HYB + "mochi",
        HYB + "mm_motion_boost", HYB + "mm_boost_value", HYB + "mm_motion_threshold",
        "scam_hybrid_merge_align_category", "scam_hybrid_merge_reject_category", "scam_hybrid_merge_final_category",
    ]

    func routeReason(_ key: String) -> String? {
        let quality = text(Self.HYB + "processing", "fast").trimmingCharacters(in: .whitespaces) == "quality"
        if Self.QUALITY_ONLY.contains(key) && !quality {
            return Lang.t("Только для режима «Качество» (Режим обработки).", "“Quality” processing mode only (Processing mode).")
        }
        return nil
    }

    private static let RAW_MERGE_ONLY: Set<String> = [
        HYB + "fullres50_frames", HYB + "fullres50_zsl", HYB + "fullres200_frames",
        HYB + "kernel_texture", HYB + "kernel_bokeh", HYB + "kernel_transition", HYB + "kernel_snr",
        "scam_hybrid_motion_screen", "scam_hybrid_merge_align_category",
        "scam_hybrid_merge_reject_category", "scam_hybrid_merge_kernel_category", "scam_hybrid_merge_quad_category",
        "scam_hybrid_mosaic_screen", "scam_hybrid_mosaic_native_category", "scam_hybrid_merge_final_category",
    ]

    static func rawMergeOnly(_ key: String) -> Bool {
        RAW_MERGE_ONLY.contains(key) || QUALITY_ONLY.contains(key) || key.hasPrefix(HYB + "mm_") || key.hasPrefix(HYB + "mosaic_")
    }

    func yuvReason(_ key: String) -> String? {
        let yuv = Self.HYB + "yuv_"
        if key.hasPrefix(yuv) || key == "scam_hybrid_yuv_category" {
            if !yuvMerge {
                return Lang.t("Только при «Склейке YUV» (настройки объектива текущего модуля).",
                              "Only with “YUV merge” on (the current module's lens settings).")
            }
            let zsl = text(yuv + "capture", "after").trimmingCharacters(in: .whitespaces) == "zsl"
            if zsl && key == yuv + "bracket" {
                return Lang.t("Брекетинг — только с кадрами после спуска (YUV: кадры серии).",
                              "Brackets need the frames after the shutter (YUV: series frames).")
            }
            if any(key, yuv + "es_ev", yuv + "long_ev", yuv + "deghost") && (zsl || !on(yuv + "bracket", true)) {
                return Lang.t("Включите «YUV: брекетинг экспозиции» (кадры после спуска).",
                              "Turn on “YUV: exposure brackets” (frames after the shutter).")
            }
            return nil
        }
        if yuvMerge && Self.rawMergeOnly(key) {
            return Lang.t("Не действует при «Склейке YUV» (настройки объектива): этот модуль склеивает кадры YUV.",
                          "No effect with “YUV merge” (lens settings): this module merges YUV frames.")
        }
        return nil
    }

    /// Why a whole group (a category or a page) is inactive.
    func groupReason(_ key: String) -> String? {
        if let r = iPhoneReason(key) { return r }
        if let r = toneVersionReason(key) { return r }
        if let r = yuvReason(key) { return r }
        return routeReason(key)
    }

    /// Why a row does not act now (nil: it does): the iPhone's own reasons first, then SettingsAvailability.reason.
    func reason(_ key: String) -> String? {
        if let r = iPhoneReason(key) { return r }
        if key == "pref_live_viewfinder_raw_key" && !rawViewfinder {
            return L("set.na.raw_viewfinder")
        }
        if let r = toneVersionReason(key) { return r }
        if let r = oldstyleRowReason(key) { return r }
        if let r = yuvReason(key) { return r }
        if let r = routeReason(key) { return r }
        if film(false) {
            if film(true) && key == "pref_scam_hybrid_dn_luma_grain" {
                return Lang.t("С плёночной симуляцией на силе 100 % зерно задаёт плёнка.", "With a film simulation at 100 %, the film sets the grain.")
            }
            if key == "pref_ultrahdr_key" {
                return Lang.t("С плёночной симуляцией фото сохраняется в SDR: HDR изменил бы отпечаток плёнки.",
                              "With a film simulation the photo is saved in SDR: HDR would change the film's print.")
            }
            if key == Self.WHY + "texture" {
                return Lang.t("С плёночной симуляцией выключена: фактуру даёт плёнка.", "Off with a film simulation: the film gives the texture.")
            }
            if film(true) && Self.filmBypassed(key) {
                return Lang.t("С плёночной симуляцией на силе 100 % не действует: тон, цвет и фактуру задаёт плёнка. При силе ниже 100 % влияние ослабляется.",
                              "No effect with a film simulation at 100 %: the film sets the tone, colour and texture. Below 100 % its influence is reduced.")
            }
        }
        if (key.hasPrefix("pref_scam_hybrid_dn_") || key.hasPrefix("pref_scam_hybrid_why_dn_") || key.hasPrefix("pref_scam_hybrid_why_ms_")
            || key.hasPrefix("pref_scam_hybrid_resolve_speckle_")
            || any(key, "pref_scam_hybrid_why_bracket_denoise", "pref_scam_hybrid_why_chroma_denoise"))
            && !on("pref_scam_hybrid_denoise", true) {
            return Lang.t("Включите «Все шумодавы» (Шумоподавление и склейка).", "Turn on “All denoisers” (Noise reduction and merge).")
        }
        let stored = text("pref_scam_hybrid_output", "sensor").trimmingCharacters(in: .whitespaces)
        let output = stored == "2x" ? "2x" : (["1.5x", "16", "20"].contains(stored) ? "1.5x" : "sensor")
        let resize = text("pref_scam_hybrid_resize", "off").trimmingCharacters(in: .whitespaces)
        if key == "pref_scam_hybrid_downsampler" && resize == "off" {
            return Lang.t("Только при «Уменьшить до» (Размер фото): без него фото остаётся в размере склейки.",
                          "Only with “Resize to” (Photo size): without it the photo keeps the merged size.")
        }
        if key == "pref_scam_hybrid_resize_mp" && resize != "mp" {
            return Lang.t("Только при «Уменьшить до» › «Свои мегапиксели».", "Only with “Resize to” › “Own megapixels”.")
        }
        if key == "pref_scam_hybrid_resize_px" && resize != "px" {
            return Lang.t("Только при «Уменьшить до» › «Своё разрешение».", "Only with “Resize to” › “Own resolution”.")
        }
        if key == "pref_scam_hybrid_auto2x" && output == "sensor" {
            return Lang.t("Только при «Разрешение» 1.5× или 2× (Файл и размер › Размер фото).",
                          "Only with “Resolution” 1.5× or 2× (File and size › Photo size).")
        }
        if key == "pref_scam_hybrid_dn_chroma_2x_keep" && output == "sensor" && !fullRes200 {
            return Lang.t("Только при «Разрешение» 1.5× или 2× и в снимках 200 Мп.", "Only with “Resolution” 1.5× or 2× and in 200 MP shots.")
        }
        if key == "pref_scam_hybrid_auto2x" && output == "2x" {
            return Lang.t("В «Разрешение 2×» всегда используется настоящая сетка склейки 2×; автоматическая замена на 1× выключена.",
                          "“Resolution 2×” always uses the native 2× merge grid; automatic 1× replacement is disabled.")
        }
        let motion = on("pref_motion_mode", false)
        if key.hasPrefix("pref_scam_hybrid_mm_") && !motion {
            return Lang.t("Действует только в режиме «Спорт» (кнопка в верхней панели).", "Acts only in “Sport” mode (the button in the top bar).")
        }
        if motion && any(key, "pref_scam_hybrid_motion_boost", "pref_scam_hybrid_boost_value", "pref_scam_hybrid_motion_threshold") {
            return Lang.t("В режиме «Спорт» действует его значение (страница «Спорт»: Шумоподавление и склейка › Кадры серии).",
                          "In “Sport” mode its own value applies (the “Sport” page: Noise reduction and merge › Burst frames).")
        }
        if motion && key == "pref_scam_hybrid_why_dn_motion_revert" {
            return Lang.t("В режиме «Спорт» действует 0.5: половина резкости движущихся участков, без лишнего зерна.",
                          "In “Sport” mode 0.5 applies: moving areas keep half their luma detail, without extra grain.")
        }
        if motion && key == "pref_scam_hybrid_widen_below" {
            return Lang.t("В режиме «Спорт» не действует: база там не расширяется.", "Not used in “Sport” mode: the base is never widened there.")
        }
        if key == "pref_scam_hybrid_boost_threshold"
            && !(motion ? on("pref_scam_hybrid_mm_motion_boost", true) : on("pref_scam_hybrid_motion_boost", true)) {
            return motion ? Lang.t("Включите «Против двоения» на странице «Спорт» (Шумоподавление и склейка › Кадры серии).",
                                   "Turn on “Anti-ghosting” on the “Sport” page (Noise reduction and merge › Burst frames).")
                : Lang.t("Включите «Против двоения».", "Turn on “Anti-ghosting”.")
        }
        if motion && any(key, "pref_scam_hybrid_zsl_frames", "pref_scam_hybrid_bento", "pref_scam_hybrid_shasta") {
            return Lang.t("В режиме «Спорт» действует его значение (страница «Спорт»: Шумоподавление и склейка › Кадры серии).",
                          "In “Sport” mode its own value applies (the “Sport” page: Noise reduction and merge › Burst frames).")
        }
        if dcg && !motion && any(key, "pref_scam_hybrid_shasta", "pref_scam_hybrid_shasta_mode", "pref_scam_hybrid_shasta_motion_max",
                                 "pref_scam_hybrid_shasta_frames", "pref_scam_hybrid_shasta_ev", "pref_scam_hybrid_shasta_sharpness",
                                 "pref_scam_hybrid_shasta_max_ratio", "pref_scam_hybrid_lut_sigma") {
            return Lang.t("С DCG кадры теней не снимаются: один DCG-кадр держит тени сам. Кадры светов — только если он клипует.",
                          "With DCG no shadow frames are taken: one DCG frame holds the shadows itself. Highlight frames only where it clips.")
        }
        if motion && any(key, "pref_scam_hybrid_mm_bento", "pref_scam_hybrid_mm_shasta") {
            return Lang.t("В режиме «Спорт» брекетинг отключён.", "Exposure bracketing is disabled in Sport mode.")
        }
        let bento = motion ? "0" : text("pref_scam_hybrid_bento", "2").trimmingCharacters(in: .whitespaces)
        let shasta = !motion && on("pref_scam_hybrid_shasta", true)
        if bento == "0" && any(key, "pref_scam_hybrid_bento_frames", "pref_scam_hybrid_bento_validate",
                               "pref_scam_hybrid_bento_motion_max", "pref_scam_hybrid_bento_factor", "pref_scam_hybrid_bento_trigger",
                               "pref_scam_hybrid_bento_sigma", "pref_scam_hybrid_bento_ramp", "pref_scam_hybrid_bento_chroma_sigma",
                               "pref_scam_hybrid_bento_scam") {
            return motion ? Lang.t("В режиме «Спорт» кадры светов не снимаются.", "Sport mode takes no highlight brackets.")
                : Lang.t("Включите «Кадры светов».", "Turn on “Highlight frames”.")
        }
        if !shasta && any(key, "pref_scam_hybrid_shasta_mode", "pref_scam_hybrid_shasta_motion_max", "pref_scam_hybrid_shasta_frames",
                          "pref_scam_hybrid_shasta_ev", "pref_scam_hybrid_shasta_deep", "pref_scam_hybrid_shasta_auto_count",
                          "pref_scam_hybrid_shasta_sharpness", "pref_scam_hybrid_shasta_max_ratio", "pref_scam_hybrid_lut_sigma") {
            return motion ? Lang.t("В режиме «Спорт» кадры теней не снимаются.", "Sport mode takes no shadow brackets.")
                : Lang.t("Включите «Кадры теней».", "Turn on “Shadow frames”.")
        }
        if key == "pref_scam_hybrid_shasta_motion_max" && text("pref_scam_hybrid_shasta_mode", "2").trimmingCharacters(in: .whitespaces) != "2" {
            return Lang.t("Только при «Кадры теней: режим» = «Всегда».", "Only with “Shadow frames: mode” = “Always”.")
        }
        if key == "pref_scam_hybrid_lut_sigma" {
            let ev = Swift.max(1, Swift.min(4, num("pref_scam_hybrid_shasta_ev", 1)))
            let fwe = num("pref_scam_hybrid_fwe", 1), cap = num("pref_scam_hybrid_weight_cap", 50)
            if Swift.min(cap, pow(pow(4, ev), fwe)) <= 10 {
                return Lang.t("Не действует: вес кадра теней в склейке не выходит за 10. Нужно «Кадры теней: EV над N» больше 1,66 "
                              + "(при «Показатель веса кадра» 1) и «Предел веса кадра» больше 10.",
                              "No effect: a shadow frame's merge weight stays at 10 or below. Needs “Shadow frames: EV above N” above 1.66 "
                              + "(with “Frame weight exponent” 1) and “Frame weight limit” above 10.")
            }
        }
        if key == "pref_scam_hybrid_bento_sigma" && bentoBlend && (!mosaicStreams
            || text("pref_scam_hybrid_mosaic_path", "1").trimmingCharacters(in: .whitespaces) == "0" || !on("pref_scam_hybrid_mosaic_remosaic", true)) {
            return Lang.t("Только для нативной склейки Quad / Tetra («Наш ремозаик», «Склейка мозаики» = «Нативная мозаика»): "
                          + "на обычном Байере кадры светов накладываются после склейки, без этого ядра.",
                          "Native Quad / Tetra merge only (“Our remosaic”, “Mosaic merge” = “Native mosaic”): on plain Bayer the highlight "
                          + "frames are blended after the merge, without this kernel.")
        }
        if key.hasPrefix("pref_scam_hybrid_mosaic_") && !mosaicStreams {
            return Lang.t("Только для модулей с мозаикой Quad / Tetra (ISZ без ремозаика); на этом телефоне её ещё не было.",
                          "Only for modules streaming a Quad / Tetra mosaic (ISZ without remosaic); none has on this phone yet.")
        }
        if key == "pref_scam_hybrid_why_agx_sat" && !toneV2 && text("pref_scam_hybrid_why_agx_look", "4") != "4" {
            return Lang.t("Только при «Стиль AgX» = «Свой» (Настройки тона › Глобальный тонмап): другие стили задают насыщенность сами.",
                          "Only with “AgX look” = “Custom” (Tone settings › Global tone mapping): the other looks set their own saturation.")
        }
        for zone in ["shadows", "mids", "highlights"]
        where key == "pref_scam_hybrid_colour_grade_\(zone)_hue" && num("pref_scam_hybrid_colour_grade_\(zone)_str", 0) <= 0 {
            return Lang.t("Сначала задайте силу этой зоны.", "Set this zone's strength first.")
        }
        let sabre61 = text("pref_scam_hybrid_sabre61", "1")
        if key == "pref_scam_hybrid_s61_min_motion" && sabre61 != "2" {
            return Lang.t("Только при «Новое ядро склейки» = «Авто (ночью)».", "Only with “New merge kernel” = “Auto (at night)”.")
        }
        if any(key, "pref_scam_hybrid_snr_scale", "pref_scam_hybrid_tensor_noise") && sabre61 == "1" {
            return Lang.t("Только для прежнего ядра: «Новое ядро склейки» выключено или «Авто».", "Previous kernel only: “New merge kernel” off or “Auto”.")
        }
        if any(key, "pref_scam_hybrid_shasta_sharpness", "pref_scam_hybrid_shasta_max_ratio") && text("pref_scam_hybrid_shasta_mode", "2") != "1" {
            return Lang.t("Только при «Кадры теней: режим» = «Авто».", "Only with “Shadow frames: mode” = “Auto”.")
        }
        if key == "pref_scam_hybrid_bento_trigger" && bento != "1" {
            return Lang.t("Только при «Кадры светов» = «Авто».", "Only with “Highlight frames” = “Auto”.")
        }
        if any(key, "pref_scam_hybrid_motion_boost", "pref_scam_hybrid_mm_motion_boost", "pref_scam_hybrid_boost_threshold",
               "pref_scam_hybrid_la_engine", "pref_scam_hybrid_s61_min_motion") && text("pref_scam_hybrid_local_align", "1") == "0" {
            return Lang.t("Нужно «Локальное выравнивание F6».", "Needs “Local alignment F6”.")
        }
        if key == "pref_scam_hybrid_chroma_diff_clamp" && num("pref_scam_hybrid_chroma_diff", 1) <= 0 {
            return Lang.t("Нужно «Цвет базы по цветоразности» больше 0.", "Needs “Base colour from colour difference” above 0.")
        }
        if key.hasPrefix("pref_scam_hybrid_why_agx_") || key == "pref_scam_hybrid_why_ccm_sat" || key == "pref_scam_hybrid_why_v2_agx_look" {
            let other: String? = on("pref_scam_hybrid_why_curve_on", false) ? Lang.t("своя кривая", "the own curve")
                : on("pref_scam_hybrid_why_uchi_on", false) ? "Uchimura" : on("pref_scam_hybrid_why_aces_on", false) ? "ACES" : nil
            if let other {
                return Lang.t("Сейчас вместо AgX работает " + other + " (Настройки тона › Глобальный тонмап).",
                              "Now " + other + " works instead of AgX (Tone settings › Global tone mapping).")
            }
        }
        if key.hasPrefix("pref_scam_hybrid_why_agx_") && key != "pref_scam_hybrid_why_agx_look" && !toneV2
            && text("pref_scam_hybrid_why_agx_look", "4") != "4" {
            return Lang.t("Только в стиле AgX «Свой» (Настройки тона › Глобальный тонмап): другие стили задают эти значения сами.",
                          "AgX look “Custom” only (Tone settings › Global tone mapping): the other looks set these values themselves.")
        }
        if any(key, "pref_scam_hybrid_highlight_chroma", "pref_scam_hybrid_highlight_defringe", "pref_scam_hybrid_highlight_band",
               "pref_scam_hybrid_highlight_glow") && num("pref_scam_hybrid_highlight_recovery", 100) <= 0 {
            return Lang.t("Включите «Восстановление светов по каналам».", "Turn on “Per-channel highlight recovery”.")
        }
        if any(key, "pref_scam_hybrid_why_clarity", "pref_scam_hybrid_why_flat_protect") && num("pref_scam_hybrid_why_sharp_gain", 1) == 0 {
            return Lang.t("Не действует при «Сила резкости» = 0 («Настройки изображения»): работает только с деталью Why.",
                          "No effect with “Sharpening strength” = 0 (Image settings): acts on the Why detail only.")
        }
        if key.hasPrefix("pref_watermark_") && !on("pref_show_watermark_key", true) {
            return Lang.t("Включите водяной знак.", "Turn on the watermark.")
        }
        if key == "pref_watermark_text" && !on("pref_watermark_show_text", true) {
            return Lang.t("Включите «Свой текст».", "Turn on “Own text”.")
        }
        if key == "pref_watermark_date_format" && !on("pref_watermark_show_time", false) {
            return Lang.t("Включите «Дата и время».", "Turn on “Date and time”.")
        }
        if !writesJpeg && (key == "pref_jpeg_quality" || key == "pref_jpeg_fast_encoder") {
            return Lang.t("Для JPEG: выберите формат JPEG или включите «Копия в JPEG».", "For JPEG: choose the JPEG format or turn on “JPEG copy”.")
        }
        if key == "pref_heic_10bit", let heic10Unavailable { return heic10Unavailable }
        if key == "pref_ultrahdr_key" && !writesJpeg {
            if photoFormat == "webp" {
                return Lang.t("В WebP HDR не хранится: включите «Копия в JPEG» — HDR будет в нём.",
                              "WebP holds no HDR: turn on “JPEG copy” to get it in that JPEG.")
            }
            if let hdrUnavailable { return hdrUnavailable }
            if photoFormat == "heic" && !on("pref_heic_10bit", false) {
                return Lang.t("Включите «HEIC 10 бит» или «Копия в JPEG»: HDR хранится в 10-битном HEIC или в JPEG.",
                              "Turn on “10-bit HEIC” or “JPEG copy”: HDR needs the 10-bit HEIC or a JPEG.")
            }
        }
        if key == "pref_webp_quality" && on("pref_webp_lossless", false) {
            return Lang.t("Не используется в WebP без потерь.", "Not used by lossless WebP.")
        }
        if on("pref_avif_lossless", false) {
            if key == "pref_avif_quality" { return Lang.t("Не используется в AVIF без потерь.", "Not used by lossless AVIF.") }
            if key == "pref_avif_depth" {
                return Lang.t("Без потерь — глубина самого снимка: 10 бит с Android 13, иначе 8.", "Lossless keeps the photo's own depth: 10 bit from Android 13, else 8.")
            }
            if key == "pref_avif_chroma" { return Lang.t("Без потерь — всегда 4:4:4.", "Lossless is always 4:4:4.") }
        }
        if key == "pref_color_method_key", let colorMethodOverride { return colorMethodOverride }
        if key == "pref_color_method_key" && !text("pref_dcp_profile_key", "").trimmingCharacters(in: .whitespaces).isEmpty {
            return Lang.t("Выбран DCP-профиль: цвет задаёт его матрица, выбор не действует.",
                          "A DCP profile is chosen: its matrix sets the colour, the choice has no effect.")
        }
        if key == "pref_keep_raw_burst" && on("pref_scam_lab_keep", false) {
            return Lang.t("Включено «Сохранять снимки для лаборатории»: RAW-серия уже сохраняется в папку снимка лаборатории.",
                          "“Keep shots for the lab” is on: the RAW burst is already kept in the lab shot's folder.")
        }
        return nil
    }

    /// Rows and pages an iPhone cannot serve (they stay visible, greyed, with this reason): root, the Android camera packages,
    /// the vendor sensor keys and ISP controls, the Android-only tools (the lab's worker, scam_dev.txt, the noise model files,
    /// DCP profiles), and the neural parts the iOS pipeline does not have.
    func iPhoneReason(_ key: String) -> String? {
        switch key {
        case "pref_root_enabled": return L("set.na.root")
        case "camera_package_spoof_screen", "pref_camera_package_spoof_enabled", "pref_oplus_spoof_package_key",
             "pref_generic_spoof_package_key", "pref_binder_spoof_package_key", "pref_camera_package_spoof_help":
            return L("set.na.spoof")
        case "fringe_net_screen": return L("set.na.fringe_net")
        case "expert_module_sensor", "lens_isp_category", "expert_sensor_screen": return L("set.na.vendor_keys")
        case "scam_lab_screen": return L("set.na.lab")
        case "developer_settings_screen", "pref_scam_hybrid_dev_note": return L("set.na.dev")
        case "pref_dcp_profile_key": return L("set.na.dcp")
        case "pref_noise_model_import_key", "pref_noise_model_export_key", "pref_noise_model_profile_key": return L("set.na.noise_model")
        case "pref_file_name_pattern": return L("set.na.file_name")
        case "pref_theme_accent_key": return L("set.na.accent")
        case "settings_layout_editor": return L("set.na.layout")
        case "scam_recent_messages": return L("set.na.messages")
        case "pref_raw_crop_auto", "pref_raw_crop_left", "pref_raw_crop_top", "pref_raw_crop_right", "pref_raw_crop_bottom", "raw_crop_preview":
            return L("set.na.raw_crop")
        default: return nil
        }
    }

    /// A line under a row that still acts (not greyed): the fringe net's choice, which the iPhone cannot run.
    func note(_ key: String) -> String? {
        if key == "pref_scam_hybrid_fringe_method" && text(key, "classic").trimmingCharacters(in: .whitespaces) == "neural" {
            return L("set.note.fringe_neural")
        }
        return nil
    }
}
