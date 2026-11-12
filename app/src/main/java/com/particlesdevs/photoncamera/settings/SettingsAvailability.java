package com.particlesdevs.photoncamera.settings;

import com.particlesdevs.photoncamera.util.Lang;

import java.util.Map;

/**
 * Conditions mirror the capture/post-pipeline branches. Pages stay open to explain inactive rows. The facts of the phone
 * (they need Android; tools/check_settings_model.py compiles this class alone) are set by DeviceAvailability.
 */
public final class SettingsAvailability {
    private final Map<String, ?> values;
    /** Why this phone cannot write the 10-bit HEIC (heif.Heic10Support.unavailableReason), null when it can or is not known. */
    private String heic10Unavailable;
    /** Why «HDR в HEIC / AVIF» cannot act on this phone (processing.color.HdrOutput.unavailableReason: Android 13), or null. */
    private String hdrUnavailable;
    /** The vivo stock AE observer runs on this phone (capture.ScamStockAe.supportedDevice: the vivo X200 Ultra). */
    /** Why «Цветовой метод» does not act here (a tuned / ISP matrix replaces it), null when it does. */
    private String colorMethodOverride;
    /** The tele's smooth optical zoom exists here (capture.XiaomiTeleZoom.phone: the Xiaomi 17 Ultra); assumed unless told otherwise. */
    private boolean xiaomiSmoothZoom = true;
    /** P128: the OPPO tuned ISP matrix renders this phone's photos (OppoTunedColor.applies); assumed unless told otherwise. */
    private boolean oppoTunedColor = true;
    /** P182: «Аппаратный ремозаик» of FULLRES acts here (VivoSdkFullRes.hardwareRemosaicOffered); assumed unless told otherwise. */
    private boolean fullResHwRemosaic = true;
    /** P128: a module of this phone has streamed a Quad / Tetra mosaic (MosaicBlockStore); assumed unless told otherwise. */
    private boolean mosaicStreams = true;
    /** P190: a module here has FULLRES 50 / 200 MP shots (FullResProfiles.sizeOffered); assumed unless told otherwise. */
    private boolean fullRes50 = true, fullRes200 = true;
    /**
     * P310: the highlight frames are blended after the merge (worker bentoBlend; scam_dev.txt "hybrid_bento_blend 0" mixes them
     * inside the merge as before); assumed unless told otherwise.
     */
    private boolean bentoBlend = true;
    /** P320: the camera running now takes DCG frames (its shots take no shadow frames: HybridPlan). */
    private boolean dcg;
    private boolean rawViewfinder = true;
    public SettingsAvailability(Map<String, ?> values) { this.values = values; }
    public SettingsAvailability rawViewfinder(boolean supported) { rawViewfinder = supported; return this; }
    /** The device fact behind «HEIC 10 бит»: the reason it is unavailable here (Android version, no Main10 / P010 encoder) or null. */
    public SettingsAvailability heic10Unavailable(String reason) { heic10Unavailable = reason; return this; }
    /** The device fact behind «HDR в HEIC / AVIF»: the reason it cannot act here (Android version) or null. */
    public SettingsAvailability hdrUnavailable(String reason) { hdrUnavailable = reason; return this; }
    /** The device fact behind «Стоковый AE vivo»: the stock AE observer exists on this phone. */
    /** The device fact behind «Цветовой метод»: the matrix that replaces the choice on this phone, or null. */
    public SettingsAvailability colorMethodOverride(String reason) { colorMethodOverride = reason; return this; }
    /** The device fact behind «Плавный оптический зум»: hidden on any other phone than the Xiaomi 17 Ultra. */
    public SettingsAvailability xiaomiSmoothZoom(boolean supported) { xiaomiSmoothZoom = supported; return this; }
    /** The device fact behind «Насыщенность матрицы OPPO»: hidden where the OPPO tuned matrix never renders a photo. */
    public SettingsAvailability oppoTunedColor(boolean applies) { oppoTunedColor = applies; return this; }
    /** The device fact behind «Аппаратный ремозаик»: hidden where FULLRES has no choice of remosaic. */
    public SettingsAvailability fullResHwRemosaic(boolean offered) { fullResHwRemosaic = offered; return this; }
    /** P190: the device facts behind «Кадров в снимке 50 / 200 Мп»: hidden where no module has FULLRES of that size. */
    public SettingsAvailability fullResSizes(boolean fifty, boolean twoHundred) { fullRes50 = fifty; fullRes200 = twoHundred; return this; }
    /** The device fact behind the mosaic merge rows: whether a module here has streamed a Quad / Tetra mosaic. */
    public SettingsAvailability mosaicStreams(boolean seen) { mosaicStreams = seen; return this; }
    /** The fact behind «Кадры светов: ширина ядра»: the highlight frames are blended after the merge (not with scam_dev bento_blend 0). */
    public SettingsAvailability bentoBlend(boolean on) { bentoBlend = on; return this; }
    public SettingsAvailability dcg(boolean on) { dcg = on; return this; }
    private String text(String key, String fallback) {
        Object value = values.get(key); return value == null ? fallback : value.toString();
    }
    private boolean on(String key, boolean fallback) { return PreferenceNumber.bool(values.get(key), fallback); }
    private boolean any(String key, String... alternatives) {
        for (String value : alternatives) if (key.equals(value)) return true;
        return false;
    }
    /** «Формат фото» as stored (pref_photo_format): jpeg (default), heic, webp or avif. */
    private String photoFormat() {
        String f = text("pref_photo_format", "jpeg").trim().toLowerCase(java.util.Locale.ROOT);
        return f.equals("heic") || f.equals("webp") || f.equals("avif") ? f : "jpeg";
    }
    /** Whether a shot writes a JPEG: the JPEG format, or HEIC / WebP / AVIF with «Также сохранять JPEG». */
    private boolean writesJpeg() {
        return photoFormat().equals("jpeg") || on("pref_photo_also_jpeg", false);
    }

    /**
     * P369: a film simulation is set (FilmSim.current: a stock and a strength above 0, its default 100 %); {@code alone}: at
     * 100 %, where the film's print replaces ours entirely.
     */
    private boolean film(boolean alone) {
        if (text("pref_film_sim_stock", "").trim().isEmpty()) return false;
        final double strength = PreferenceNumber.read(values.get("pref_film_sim_strength"), 100);
        return alone ? strength >= 100 : strength > 0;
    }

    private static final String WHY = "pref_scam_hybrid_why_", COLOUR = "pref_scam_hybrid_colour_";

    /**
     * P369: rows of our tone and colour the film never sees (why/combine FILM_SIM: the film is fed the scene light x ae ahead of
     * the fusion's gain, the shoulders, the OKLab grading, AgX / ACES / Uchimura / the own curve and the display chain; WhyLumaSharpen
     * takes the guided filter's local contrast and its grain on ours only). The colour matrix's rows act on the film too: warmth,
     * tint, the kept scene light and the calibration (WhyTone.colourMatrix), as do the exposure rows (ae x the overflow's cut).
     */
    static boolean filmBypassed(String key) {
        if (key.startsWith(COLOUR)) {
            final String k = key.substring(COLOUR.length());
            return !(k.equals("warmth") || k.equals("tint") || k.equals("keep_warm") || k.equals("keep_cool") || k.startsWith("cal_"));
        }
        if (!key.startsWith(WHY)) return false;
        final String k = key.substring(WHY.length());
        if (k.startsWith("agx_") || k.startsWith("aces_") || k.startsWith("uchi_") || k.startsWith("curve_")
                || k.startsWith("sharp_") || k.startsWith("v2_")) return true;   // P614: V2's own tone rows
        switch (k) {
            case "macro_contrast": case "gamma": case "film_toe": case "contrast_boost": case "hl_soft": case "hl_white": case "ccm_sat":
            case "vibrance": case "vibrance_sky": case "vibrance_green":
            case "bright_thresh": case "dark_thresh": case "dark_pixel_thresh": case "hl_boost": case "shadow_str": case "highlight_str":
            case "weight_dark2": case "weight_hl": case "weight_mid": case "weight_bright": case "weight_center": case "blend_smoothness":
            case "ef_coarse":
            case "fusion_layout": case "classic_weight_ext_hl": case "classic_weight_hl": case "classic_weight_mid": case "classic_weight_shadow":
            case "classic_weight_center": case "hr_strength": case "hr_knee": case "hr_room":   // P402
            case "gamma_type": case "vibrance_saturated": case "vibrance_mid": case "vibrance_shadows": case "skin_protect":
            case "skin_hue_center": case "skin_luma_min": case "skin_sat_shift":   // P404
            case "vibrance_hl_str":   // P419
            case "clarity": case "flat_protect": case "edge_hold":
            case "cct_sat": case "cct_gain_r": case "cct_gain_g": case "cct_gain_b":
            case "dynamic_cct": case "dynamic_cct_strength":
                return true;
            default:
                return false;
        }
    }

    /**
     * Rows that only belong to another choice and are hidden instead of explained: the settings of the photo formats that
     * are not chosen (HEIC quality / 10 bit, WebP quality / lossless, the AVIF rows, «HDR в HEIC / AVIF» outside HEIC / AVIF)
     * and «Также сохранять JPEG» while the format is JPEG («Цветовое пространство» serves every format); and the rows of what this phone does not have (the Xiaomi
     * smooth zoom, the OPPO tuned matrix's saturation).
     */
    public boolean hidden(String key) {
        // Rows of what this phone does not have (the settings screen also hides them when it builds the tree, for the search).
        if (!xiaomiSmoothZoom && key.equals("pref_xiaomi_smooth_zoom")) return true;
        if (!fullResHwRemosaic && key.equals("pref_fullres_hw_remosaic")) return true;
        if (!fullRes50 && (key.equals("pref_scam_hybrid_fullres50_frames") || key.equals("pref_scam_hybrid_fullres50_zsl"))) return true;
        if (!fullRes200 && key.equals("pref_scam_hybrid_fullres200_frames")) return true;
        // P128: the AgX saturation of the OPPO tuned matrix acts only on the phones that matrix renders (WhyTone.ccmSatComp)
        if (!oppoTunedColor && key.equals("pref_scam_hybrid_why_ccm_sat")) return true;
        // P493: «Настройки нейросети» belongs to «Подавление аберраций: Нейросеть» (CaNetP.method; «Классическое» by default)
        if (key.equals("fringe_net_screen")) return !text("pref_scam_hybrid_fringe_method", "classic").trim().equals("neural");
        String format = photoFormat();
        switch (key) {
            case "pref_heic_quality": case "pref_heic_10bit": return !format.equals("heic");
            case "pref_webp_quality": case "pref_webp_lossless": return !format.equals("webp");
            case "pref_avif_quality": case "pref_avif_lossless": case "pref_avif_depth": case "pref_avif_chroma":
            case "pref_avif_speed": return !format.equals("avif");
            case "pref_photo_also_jpeg": return format.equals("jpeg");
            default: return false;
        }
    }

    /** P614: «Why тон» V2 is chosen (pref_scam_hybrid_tone_version; V1 by default). */
    private boolean toneV2() {
        return text("pref_scam_hybrid_tone_version", "v1").trim().equals("v2");
    }

    /**
     * P614 (owner, 2026-11-11: «когда выбирается например V1, то недоступные для него настройки были не активными и наоборот»): the
     * rows and groups of «Настройки изображения» only V1 reads - its statistics, fusion and combine (WhyStats / WhyFusion /
     * WhyCombine: the metering, the AE power, its looks, layouts and the detail delta, the portrait, the colour look's skin, mono,
     * mixer and grading, the highlight softness). V2 (WhyV2Stats.settings) reads the merged rows (WhyV2Stats.MERGED) and its own
     * pref_scam_hybrid_why_v2_*; every other row of the page (the merge, the denoise, the sharpening, the white balance, the film)
     * comes before or after the tone and serves both.
     */
    static final java.util.Set<String> V1_ONLY = new java.util.HashSet<>(java.util.Arrays.asList(
            WHY + "sharp_gain", WHY + "metering", WHY + "night_dim", WHY + "agx_look", COLOUR + "hue_lock", WHY + "curve_boost",
            WHY + "fusion_layout", WHY + "weight_hl", WHY + "weight_dark2", WHY + "weight_mid", WHY + "weight_bright",
            WHY + "weight_center", WHY + "ef_coarse", WHY + "clarity", WHY + "sharp_domain", WHY + "ccm_sat", COLOUR + "gamut",
            COLOUR + "mono", COLOUR + "skin_hue", WHY + "skin_hue_center", COLOUR + "skin_even",
            WHY + "portrait_ae", WHY + "portrait_awb", WHY + "portrait_curve_on", WHY + "portrait_curve_points",
            WHY + "hl_soft", WHY + "hl_white", WHY + "delta_chroma", WHY + "flat_protect",
            // the groups and pages whose rows are all V1's
            "scam_hybrid_why_weights_category", "scam_hybrid_why_artifacts_category", "portrait_face_category",
            "portrait_curve_category", "portrait_mask_screen", "colour_mixer_screen", "colour_grading_screen"));

    /** P614: {@code key} is read by «Why тон» V1 only ({@link #V1_ONLY}, the portrait curve, the face mask, the mixer, the grading). */
    static boolean v1Only(String key) {
        return V1_ONLY.contains(key) || key.startsWith(WHY + "portrait_curve_") || key.startsWith(WHY + "face_")
                || key.startsWith(COLOUR + "mix_") || key.startsWith(COLOUR + "grade_");
    }

    /** P614: {@code key} is one of V2's own rows (WhyV2Stats.OWN). */
    static boolean v2Only(String key) {
        return key.startsWith(WHY + "v2_");
    }

    /** P630: «Oldstyle V2» (3.1) is the mode's tone (pref_scam_hybrid_oldstyle_version; V1, 2.7, by default). */
    private boolean oldstyleV2() {
        return text("pref_scam_hybrid_oldstyle_version", "v1").trim().equals("v2");
    }

    /** P630: the rows of «Oldstyle Тон» only V1 (2.7) reads (OldstyleMode.params: its skip / max ratios). */
    static boolean oldstyleV1Only(String key) {
        return key.startsWith("pref_scam_hybrid_oldstyle_v1_");
    }

    /** P630: the rows only V2 (3.1) reads (maxHdrRatio, its forgiveness, maxDigitalGain, the pyramid's 8 / 10 levels). */
    static boolean oldstyleV2Only(String key) {
        return key.startsWith("pref_scam_hybrid_oldstyle_v2_");
    }

    /** P630: why a row of the other «Oldstyle» tone version is inactive, null when it acts with the chosen one. */
    String oldstyleVersionReason(String key) {
        if (oldstyleV2() ? oldstyleV1Only(key) : oldstyleV2Only(key))
            return oldstyleV2() ? Lang.t("Только для Oldstyle V1: выбран V2.", "Oldstyle V1 only: V2 is chosen.")
                    : Lang.t("Только для Oldstyle V2: выбран V1.", "Oldstyle V2 only: V1 is chosen.");
        return null;
    }

    /** P614: why a row or group of the other «Why тон» version is inactive, null when it acts with the chosen one. */
    String toneVersionReason(String key) {
        final String old = oldstyleVersionReason(key);
        if (old != null) return old;
        if (toneV2() ? v1Only(key) : v2Only(key))
            return toneV2() ? Lang.t("Только для Why тона V1: выбран V2 (снимки 200 Мп по-прежнему идут через V1).",
                    "Why tone V1 only: V2 is chosen (200 MP shots still take V1).")
                    : Lang.t("Только для Why тона V2 (Настройки › Why тон).", "Why tone V2 only (Settings › Why tone).");
        return null;
    }

    /** P629: the merge routes of «Режим обработки» (pref_scam_hybrid_processing), as bits of {@link #routes}. */
    static final int FAST = 1, QUALITY = 2, STATIC = 4, ALL = FAST | QUALITY | STATIC;
    private static final String HYB = "pref_scam_hybrid_";

    /**
     * P629 (owner, 2026-11-11: «Группу шумоподавление и склейка перемести под выбор склейки Быстро/Качество/Статика как мы сделали с
     * настройками тонмапинга»): the rows of «Шумоподавление и склейка» that only the SCAM merge reads - «Качество» (worker
     * hybridReconstruct after its two route branches: HybridTuning cdm, filterVariance, dilate*, boost*, varianceThreshold,
     * motionThreshold, chromaDiff*, dezipper, hot*, cellClip, kernelScale, snrScale, rawNoise, widenBelow, weightCap, fwe,
     * sabre61, s61*, localAlign, laGpu, mochi; the CRE source: the alignment only that merge takes). The fast route
     * (scam-fast-merge.h) and the static route (scam-static-merge.h) read none of them. The Quad / Tetra rows are not here: the fast
     * and static routes read the mosaic merge's tuning themselves (P627: every mode merges its own Quad / Tetra), so they act in all three.
     */
    static final java.util.Set<String> QUALITY_ONLY = new java.util.HashSet<>(java.util.Arrays.asList(
            HYB + "cre_source", HYB + "local_align", HYB + "la_engine",
            HYB + "cdm", HYB + "filter_variance", HYB + "dilate", HYB + "dilate_floor", HYB + "motion_boost", HYB + "boost_value",
            HYB + "boost_threshold", HYB + "motion_threshold", HYB + "chroma_diff", HYB + "chroma_diff_clamp", HYB + "dezipper",
            HYB + "hot_sigma", HYB + "hot_base_sigma", HYB + "hot_max_key", HYB + "cell_clip",
            HYB + "sabre61", HYB + "s61_max_key", HYB + "s61_min_motion", HYB + "kernel", HYB + "snr_scale", HYB + "tensor_noise",
            HYB + "widen_below", HYB + "weight_cap", HYB + "fwe", HYB + "mochi",
            // «Спорт»'s own values of the rejection (MotionMode.DEFAULTS, the same tuning lines; the fast route's Sport rejection is
            // its fixed fastMergeSport set)
            HYB + "mm_motion_boost", HYB + "mm_boost_value", HYB + "mm_motion_threshold",
            // the groups whose rows are all «Качество»'s
            "scam_hybrid_merge_align_category", "scam_hybrid_merge_reject_category", "scam_hybrid_merge_final_category"));
    /** P629: the kernel's texture / bokeh / transition footprints: the fast route reads them too (hybridKernelFootprint). */
    static final java.util.Set<String> FAST_AND_QUALITY = new java.util.HashSet<>(java.util.Arrays.asList(
            HYB + "kernel_texture", HYB + "kernel_bokeh", HYB + "kernel_transition"));

    /**
     * P629: the routes {@code key} acts on ({@link #FAST} | {@link #QUALITY} | {@link #STATIC}): «Статика»'s own rows and group
     * (pref_scam_hybrid_static_*: PreferenceKeys.staticTuning / staticOutputMode; a row added to the group later takes its prefix),
     * {@link #QUALITY_ONLY}, {@link #FAST_AND_QUALITY}; every other row (the denoise after the merge, the burst's frames, «Спорт»'s
     * capture, Quad / Tetra) acts on all three.
     */
    static int routes(String key) {
        if (key.startsWith(HYB + "static_") || key.equals("scam_hybrid_static_category")) return STATIC;
        if (QUALITY_ONLY.contains(key)) return QUALITY;
        if (FAST_AND_QUALITY.contains(key)) return FAST | QUALITY;
        return ALL;
    }

    /** P629: the chosen «Режим обработки» as a route bit (PreferenceKeys.processingMode: any other value is «Быстрый»). */
    private int route() {
        final String mode = text(HYB + "processing", "fast").trim();
        return mode.equals("quality") ? QUALITY : mode.equals("static") ? STATIC : FAST;
    }

    /** P629: why a row or group of «Шумоподавление и склейка» does not act with the chosen processing mode, null when it does. */
    String routeReason(String key) {
        final int routes = routes(key), route = route();
        if ((routes & route) != 0) return null;
        if (routes == STATIC)
            return Lang.t("Только для режима «Статика» (Режим обработки).", "“Static” processing mode only (Processing mode).");
        // P627: no mode hands its shots to another mode's merge, so a «Качество» row does nothing in «Быстрый» / «Статика»
        if (routes == QUALITY)
            return Lang.t("Только для режима «Качество» (Режим обработки).", "“Quality” processing mode only (Processing mode).");
        return Lang.t("Только для режимов «Быстрый» и «Качество» (Режим обработки).",
                "“Fast” and “Quality” processing modes only (Processing mode).");
    }

    /** P614: why a whole group (a category of the page) is inactive: the tone version, P629: the processing mode. */
    public String groupReason(String key) {
        if (key == null) return null;
        final String version = toneVersionReason(key);
        return version != null ? version : routeReason(key);
    }

    public String reason(String key) {
        if (key.equals("pref_live_viewfinder_raw_key") && !rawViewfinder)
            return Lang.t("Отключён для процессоров уровня Snapdragon 8 Gen 3 и ниже. Используется обычное превью камеры.",
                    "Disabled on Snapdragon 8 Gen 3 tier and slower processors. Uses the standard camera preview.");
        // P614: a row of the other «Why тон» version first (whatever else holds, it does not act on this one)
        final String version = toneVersionReason(key);
        if (version != null) return version;
        // P629: a row of «Шумоподавление и склейка» another processing mode's merge reads (whatever else holds, it does not act here)
        final String route = routeReason(key);
        if (route != null) return route;
        // P369: the film gates of the shot (PostPipeline.gainMapWanted, wantsLocalContrast: off with any film) and the rows the
        // film's print replaces at 100 % (below it they act on the share without the film, so they stay open)
        if (film(false)) {
            if (film(true) && key.equals("pref_scam_hybrid_dn_luma_grain"))
                return Lang.t("С плёночной симуляцией на силе 100 % зерно задаёт плёнка.",
                        "With a film simulation at 100 %, the film sets the grain.");
            if (key.equals("pref_ultrahdr_key"))
                return Lang.t("С плёночной симуляцией фото сохраняется в SDR: HDR изменил бы отпечаток плёнки.",
                        "With a film simulation the photo is saved in SDR: HDR would change the film's print.");
            if (key.equals(WHY + "texture"))
                return Lang.t("С плёночной симуляцией выключена: фактуру даёт плёнка.", "Off with a film simulation: the film gives the texture.");
            if (film(true) && filmBypassed(key))
                return Lang.t("С плёночной симуляцией на силе 100 % не действует: тон, цвет и фактуру задаёт плёнка. При силе ниже 100 % влияние ослабляется.",
                        "No effect with a film simulation at 100 %: the film sets the tone, colour and texture. Below 100 % its influence is reduced.");
        }
        // The denoise and watermark switches sit on a parent page of these rows (P6), so the rule replaces android:dependency.
        if ((key.startsWith("pref_scam_hybrid_dn_") || key.startsWith("pref_scam_hybrid_why_dn_")
                || key.startsWith("pref_scam_hybrid_why_ms_") || key.startsWith("pref_scam_hybrid_resolve_speckle_")
                || any(key, "pref_scam_hybrid_why_bracket_denoise", "pref_scam_hybrid_why_chroma_denoise"))
                && !on("pref_scam_hybrid_denoise", true)) return Lang.t("Включите «Все шумодавы» (Шумоподавление и склейка).",
                "Turn on “All denoisers” (Noise reduction and merge).");
        // Output size (pref_scam_hybrid_output, P501): the Sabre scales 1.0x / 1.5x / 2x; the merge returns that size itself, so the
        // downsampler only brings a cropped shot to its photo size.
        final String stored = text("pref_scam_hybrid_output", "sensor").trim();
        final String output = "2x".equals(stored) ? "2x" : any(stored, "1.5x", "16", "20") ? "1.5x" : "sensor"; // (PreferenceKeys.outputMode)
        // P503: «Уменьшить до» scales the photo down after the pipeline; its own values act with their mode only
        final String resize = text("pref_scam_hybrid_resize", "off").trim();
        if (key.equals("pref_scam_hybrid_downsampler") && resize.equals("off"))
            return Lang.t("Только при «Уменьшить до» (Размер фото): без него фото остаётся в размере склейки.",
                    "Only with “Resize to” (Photo size): without it the photo keeps the merged size.");
        if (key.equals("pref_scam_hybrid_resize_mp") && !resize.equals("mp"))
            return Lang.t("Только при «Уменьшить до» › «Свои мегапиксели».", "Only with “Resize to” › “Own megapixels”.");
        if (key.equals("pref_scam_hybrid_resize_px") && !resize.equals("px"))
            return Lang.t("Только при «Уменьшить до» › «Своё разрешение».", "Only with “Resize to” › “Own resolution”.");
        if (key.equals("pref_scam_hybrid_auto2x") && output.equals("sensor"))
            return Lang.t("Только при «Разрешение» 1.5× или 2× (Файл и размер › Размер фото).",
                    "Only with “Resolution” 1.5× or 2× (File and size › Photo size).");
        // P306: a 200 MP shot is denoised on its 2x2 means, the 2x grid path, whatever the resolution (ScamDenoise: s = 2)
        if (key.equals("pref_scam_hybrid_dn_chroma_2x_keep") && output.equals("sensor") && !fullRes200)
            return Lang.t("Только при «Разрешение» 1.5× или 2× и в снимках 200 Мп.",
                    "Only with “Resolution” 1.5× or 2× and in 200 MP shots.");
        if (key.equals("pref_scam_hybrid_auto2x") && output.equals("2x"))
            return Lang.t("В «Разрешение 2×» всегда используется настоящая сетка склейки 2×; автоматическая замена на 1× выключена.",
                    "“Resolution 2×” always uses the native 2× merge grid; automatic 1× replacement is disabled.");
        // P128: the «Движение» rows replace the normal ones only while the mode is on (MotionMode, ParamController)
        final boolean motion = on("pref_motion_mode", false);
        if (key.startsWith("pref_scam_hybrid_mm_") && !motion)
            return Lang.t("Действует только в режиме «Спорт» (кнопка в верхней панели).", "Acts only in “Sport” mode (the button in the top bar).");
        // ...and while it is on, the merge reads the mode's value of a merge row it replaces (MotionMode.DEFAULTS, read by
        // PreferenceKeys.hybridValue / hybridSwitch); it has no row for the base widening: always 0 there (P111)
        if (motion && any(key, "pref_scam_hybrid_motion_boost", "pref_scam_hybrid_boost_value", "pref_scam_hybrid_motion_threshold"))
            return Lang.t("В режиме «Спорт» действует его значение (страница «Спорт»: Шумоподавление и склейка › Кадры серии).",
                    "In “Sport” mode its own value applies (the “Sport” page: Noise reduction and merge › Burst frames).");
        if (motion && key.equals("pref_scam_hybrid_why_dn_motion_revert")) // P471 (MotionMode.DEFAULTS)
            return Lang.t("В режиме «Спорт» действует 0.5: половина резкости движущихся участков, без лишнего зерна.",
                    "In “Sport” mode 0.5 applies: moving areas keep half their luma detail, without extra grain.");
        if (motion && key.equals("pref_scam_hybrid_widen_below"))
            return Lang.t("В режиме «Спорт» не действует: база там не расширяется.", "Not used in “Sport” mode: the base is never widened there.");
        // «Порог дисперсии для усиления» is not replaced by the mode: it acts with the boost switch in effect (the mode's own in the mode) and F6
        if (key.equals("pref_scam_hybrid_boost_threshold")
                && !(motion ? on("pref_scam_hybrid_mm_motion_boost", true) : on("pref_scam_hybrid_motion_boost", true)))
            return motion ? Lang.t("Включите «Против двоения» на странице «Спорт» (Шумоподавление и склейка › Кадры серии).",
                    "Turn on “Anti-ghosting” on the “Sport” page (Noise reduction and merge › Burst frames).")
                    : Lang.t("Включите «Против двоения».", "Turn on “Anti-ghosting”.");
        // Settings audit 2026-10-21 («Кадры светов и теней»): the mode replaces the photo rows of the ring's frame count and of the
        // highlight / shadow switches too (MotionMode.DEFAULTS), and the frames' rows act through the switch the shot reads - the
        // mode's own while it is on (the plan and the merge read it: PreferenceKeys.hybridValue / hybridSwitch)
        if (motion && any(key, "pref_scam_hybrid_zsl_frames", "pref_scam_hybrid_bento", "pref_scam_hybrid_shasta"))
            return Lang.t("В режиме «Спорт» действует его значение (страница «Спорт»: Шумоподавление и склейка › Кадры серии).",
                    "In “Sport” mode its own value applies (the “Sport” page: Noise reduction and merge › Burst frames).");
        // P320: a DCG shot takes no shadow frames (its high-gain read holds the shadows; HybridPlan, the worker's DCG tuning)
        if (dcg && !motion && any(key, "pref_scam_hybrid_shasta", "pref_scam_hybrid_shasta_mode", "pref_scam_hybrid_shasta_motion_max",
                "pref_scam_hybrid_shasta_frames", "pref_scam_hybrid_shasta_ev", "pref_scam_hybrid_shasta_sharpness",
                "pref_scam_hybrid_shasta_max_ratio", "pref_scam_hybrid_lut_sigma"))
            return Lang.t("С DCG кадры теней не снимаются: один DCG-кадр держит тени сам. Кадры светов — только если он клипует.",
                    "With DCG no shadow frames are taken: one DCG frame holds the shadows itself. Highlight frames only where it clips.");
        if (motion && any(key, "pref_scam_hybrid_mm_bento", "pref_scam_hybrid_mm_shasta"))
            return Lang.t("В режиме «Спорт» брекетинг отключён.", "Exposure bracketing is disabled in Sport mode.");
        final String bento = motion ? "0" : text("pref_scam_hybrid_bento", "2").trim();
        final boolean shasta = !motion && on("pref_scam_hybrid_shasta", true);
        // «Кадры светов» off: no ultrashort frame is planned (HybridPlan) and the worker runs none of their passes
        if (bento.equals("0") && any(key, "pref_scam_hybrid_bento_frames", "pref_scam_hybrid_bento_validate",
                "pref_scam_hybrid_bento_motion_max", "pref_scam_hybrid_bento_factor", "pref_scam_hybrid_bento_trigger",
                "pref_scam_hybrid_bento_sigma", "pref_scam_hybrid_bento_ramp", "pref_scam_hybrid_bento_chroma_sigma", "pref_scam_hybrid_bento_scam"))
            return motion ? Lang.t("В режиме «Спорт» кадры светов не снимаются.", "Sport mode takes no highlight brackets.")
                    : Lang.t("Включите «Кадры светов».", "Turn on “Highlight frames”.");
        // «Кадры теней» off: no long frame is planned. The whole category follows the switch in effect here, not by
        // android:dependency: in «Движение» the photo switch is greyed (the mode's own rules), which would grey its dependants too
        if (!shasta && any(key, "pref_scam_hybrid_shasta_mode", "pref_scam_hybrid_shasta_motion_max", "pref_scam_hybrid_shasta_frames",
                "pref_scam_hybrid_shasta_ev", "pref_scam_hybrid_shasta_deep", "pref_scam_hybrid_shasta_auto_count", "pref_scam_hybrid_shasta_sharpness", "pref_scam_hybrid_shasta_max_ratio", "pref_scam_hybrid_lut_sigma"))
            return motion ? Lang.t("В режиме «Спорт» кадры теней не снимаются.", "Sport mode takes no shadow brackets.")
                    : Lang.t("Включите «Кадры теней».", "Turn on “Shadow frames”.");
        // the worker reads the motion limit of the long frames under shastaForce only («Всегда»)
        if (key.equals("pref_scam_hybrid_shasta_motion_max") && !text("pref_scam_hybrid_shasta_mode", "2").trim().equals("2"))
            return Lang.t("Только при «Кадры теней: режим» = «Всегда».", "Only with “Shadow frames: mode” = “Always”.");
        // worker lutSigma: a frame's kernel widens only above a merge weight of 10 (lutLo). A shadow frame's weight is
        // min(cap, ((TET ratio)^2 readB / readF)^exponent); the plan raises the gain first, so it is at most (4^EV)^exponent
        if (key.equals("pref_scam_hybrid_lut_sigma")) {
            final double ev = Math.max(1, Math.min(4, PreferenceNumber.read(values.get("pref_scam_hybrid_shasta_ev"), 1)));
            final double fwe = PreferenceNumber.read(values.get("pref_scam_hybrid_fwe"), 1);
            final double cap = PreferenceNumber.read(values.get("pref_scam_hybrid_weight_cap"), 50);
            if (Math.min(cap, Math.pow(Math.pow(4, ev), fwe)) <= 10)
                return Lang.t("Не действует: вес кадра теней в склейке не выходит за 10. Нужно «Кадры теней: EV над N» больше 1,66 "
                                + "(при «Показатель веса кадра» 1) и «Предел веса кадра» больше 10.",
                        "No effect: a shadow frame's merge weight stays at 10 or below. Needs “Shadow frames: EV above N” above 1.66 "
                                + "(with “Frame weight exponent” 1) and “Frame weight limit” above 10.");
        }
        // bentoUsSigma is read by the native Quad / Tetra merge alone (its ultrashort fill). Elsewhere - plain Bayer, a mosaic merged
        // as plain Bayer (the split, «Наш ремозаик» off) - the highlight frames are blended after the merge (P310 bentoBlend) with
        // their own demosaic, without this kernel
        if (key.equals("pref_scam_hybrid_bento_sigma") && bentoBlend && (!mosaicStreams
                || text("pref_scam_hybrid_mosaic_path", "1").trim().equals("0") || !on("pref_scam_hybrid_mosaic_remosaic", true)))
            return Lang.t("Только для нативной склейки Quad / Tetra («Наш ремозаик», «Склейка мозаики» = «Нативная мозаика»): "
                            + "на обычном Байере кадры светов накладываются после склейки, без этого ядра.",
                    "Native Quad / Tetra merge only (“Our remosaic”, “Mosaic merge” = “Native mosaic”): on plain Bayer the highlight "
                            + "frames are blended after the merge, without this kernel.");
        // «Мерцание ламп» is never greyed out: besides the long frames' shutter (HybridPlan) the native FULLRES AE (OPPO, vivo)
        // rounds its shutter to whole flicker periods with or without them (P310 settings cleanup; P128 greyed it with Shasta off)
        // P128: the mosaic merge acts on Quad / Tetra streams (ISZ modules without the sensor's remosaic) only
        if (key.startsWith("pref_scam_hybrid_mosaic_") && !mosaicStreams)
            return Lang.t("Только для модулей с мозаикой Quad / Tetra (ISZ без ремозаика); на этом телефоне её ещё не было.",
                    "Only for modules streaming a Quad / Tetra mosaic (ISZ without remosaic); none has on this phone yet.");
        // P29 mosaic merge: the native rows follow the merge list, the split's edge kernel only acts on the split (P35: Tetra takes
        // the native merge too with "Tetra path" at its default, so the row is live only with the split for Quad or for Tetra).
        if (key.startsWith("pref_scam_hybrid_mosaic_")) {
            // P267: without our remosaic a Quad / Tetra shot is merged as always, its merged mosaic is the DNG alone: every mosaic
            // merge row still acts (the RGB the DNG is sampled from is that merge)
            boolean nativeMosaic = text("pref_scam_hybrid_mosaic_path", "1").equals("1"); // P34: the native merge is the default
            if (key.equals("pref_scam_hybrid_mosaic_edge_scale") && nativeMosaic && !text("pref_scam_hybrid_mosaic_tetra", "1").equals("0")) // P35: T1 default
                return Lang.t("Только для разбиения на подкадры: Quad и Tetra идут нативной склейкой со своим «Ядром поперёк краёв».", "Subframe split only: Quad and Tetra both take the native merge with its own “Kernel across edges”.");
            if (any(key, "pref_scam_hybrid_mosaic_window", "pref_scam_hybrid_mosaic_window_full", "pref_scam_hybrid_mosaic_kernel_scale",
                    "pref_scam_hybrid_mosaic_native_edge_scale", "pref_scam_hybrid_mosaic_kernel_g", "pref_scam_hybrid_mosaic_kernel_rb",
                    "pref_scam_hybrid_mosaic_chroma_fill", "pref_scam_hybrid_mosaic_fill_support", "pref_scam_hybrid_mosaic_tetra",
                    "pref_scam_hybrid_mosaic_native_flat_scale", "pref_scam_hybrid_mosaic_native_clamp") && !nativeMosaic)
                return Lang.t("Только для «Нативная мозаика» в «Склейка мозаики».", "Only with “Native mosaic” in “Mosaic merge”.");
            if (key.equals("pref_scam_hybrid_mosaic_fill_support") && text("pref_scam_hybrid_mosaic_chroma_fill", "0").equals("0"))
                return Lang.t("Только с добором «Средние цветовых блоков».", "Only with the “Colour-block means” fill.");
            // the edge kernels of the split and of the native merge and the native flat-area kernel are set inside the new
            // kernel only (worker hybridReconstruct: `if(sabre61)`); the previous kernel never reads them
            if (any(key, "pref_scam_hybrid_mosaic_edge_scale", "pref_scam_hybrid_mosaic_native_edge_scale", "pref_scam_hybrid_mosaic_native_flat_scale")
                    && text("pref_scam_hybrid_sabre61", "1").equals("0"))
                return Lang.t("Только с «Новое ядро склейки» («Всегда» или «Авто»): прежнее ядро это значение не использует.",
                        "Only with “New merge kernel” (“Always” or “Auto”): the previous kernel does not use this value.");
        }
        // P134 «Цвет»: the AgX looks but «Custom» set their own saturation (WhyAe.LOOKS); a grading zone's hue needs its strength
        // P614: V2's AgX reads its own rows whatever the look (its look only warms Golden)
        if (key.equals("pref_scam_hybrid_why_agx_sat") && !toneV2() && !text("pref_scam_hybrid_why_agx_look", "4").equals("4"))
            return Lang.t("Только при «Стиль AgX» = «Свой» (Настройки тона › Глобальный тонмап): другие стили задают насыщенность сами.",
                    "Only with “AgX look” = “Custom” (Tone settings › Global tone mapping): the other looks set their own saturation.");
        for (String zone : new String[]{"shadows", "mids", "highlights"})
            if (key.equals("pref_scam_hybrid_colour_grade_" + zone + "_hue") && PreferenceNumber.read(values.get("pref_scam_hybrid_colour_grade_" + zone + "_str"), 0) <= 0)
                return Lang.t("Сначала задайте силу этой зоны.", "Set this zone's strength first.");
        // P118: rows that act only with another setting off its default (found while the descriptions were rewritten)
        final String sabre61 = text("pref_scam_hybrid_sabre61", "1");
        if (key.equals("pref_scam_hybrid_s61_min_motion") && !sabre61.equals("2"))
            return Lang.t("Только при «Новое ядро склейки» = «Авто (ночью)».", "Only with “New merge kernel” = “Auto (at night)”.");
        if (any(key, "pref_scam_hybrid_snr_scale", "pref_scam_hybrid_tensor_noise") && sabre61.equals("1"))
            return Lang.t("Только для прежнего ядра: «Новое ядро склейки» выключено или «Авто».", "Previous kernel only: “New merge kernel” off or “Auto”.");
        if (any(key, "pref_scam_hybrid_shasta_sharpness", "pref_scam_hybrid_shasta_max_ratio") && !text("pref_scam_hybrid_shasta_mode", "2").equals("1"))
            return Lang.t("Только при «Кадры теней: режим» = «Авто».", "Only with “Shadow frames: mode” = “Auto”.");
        if (key.equals("pref_scam_hybrid_bento_trigger") && !bento.equals("1"))
            return Lang.t("Только при «Кадры светов» = «Авто».", "Only with “Highlight frames” = “Auto”.");
        // the boost and its texture threshold need the F6 motion (worker boostMode); so does Auto's daylight rule (handheld61)
        if (any(key, "pref_scam_hybrid_motion_boost", "pref_scam_hybrid_mm_motion_boost", "pref_scam_hybrid_boost_threshold",
                "pref_scam_hybrid_la_engine", "pref_scam_hybrid_s61_min_motion") && text("pref_scam_hybrid_local_align", "1").equals("0"))
            return Lang.t("Нужно «Локальное выравнивание F6».", "Needs “Local alignment F6”.");
        // the clamp is a uniform of the colour-difference pass, which runs only with its strength above 0
        if (key.equals("pref_scam_hybrid_chroma_diff_clamp") && PreferenceNumber.read(values.get("pref_scam_hybrid_chroma_diff"), 1) <= 0)
            return Lang.t("Нужно «Цвет базы по цветоразности» больше 0.", "Needs “Base colour from colour difference” above 0.");
        // P144: AgX and its look give way to the tone operator switched on in «Кривая AgX и ACES»; so does the OPPO matrix's
        // saturation, a factor of AgX's (WhyCombine agxAU.w: read by the AgX branch of why/combine only)
        if (key.startsWith("pref_scam_hybrid_why_agx_") || key.equals("pref_scam_hybrid_why_ccm_sat")
                || key.equals("pref_scam_hybrid_why_v2_agx_look")) {   // P614: V2's look acts on its AgX only
            String other = on("pref_scam_hybrid_why_curve_on", false) ? Lang.t("своя кривая", "the own curve")
                    : on("pref_scam_hybrid_why_uchi_on", false) ? "Uchimura" : on("pref_scam_hybrid_why_aces_on", false) ? "ACES" : null;
            if (other != null)
                return Lang.t("Сейчас вместо AgX работает " + other + " (Настройки тона › Глобальный тонмап).",
                        "Now " + other + " works instead of AgX (Tone settings › Global tone mapping).");
        }
        if (key.startsWith("pref_scam_hybrid_why_agx_") && !key.equals("pref_scam_hybrid_why_agx_look") && !toneV2()
                && !text("pref_scam_hybrid_why_agx_look", "4").equals("4"))
            return Lang.t("Только в стиле AgX «Свой» (Настройки тона › Глобальный тонмап): другие стили задают эти значения сами.",
                    "AgX look “Custom” only (Tone settings › Global tone mapping): the other looks set these values themselves.");
        // ScamRgb: the colour prior, the defringe and the clip band act only inside the per-channel recovery (perChannel needs a
        // strength above 0; the merge sends the band's clip flags only then)
        if (any(key, "pref_scam_hybrid_highlight_chroma", "pref_scam_hybrid_highlight_defringe", "pref_scam_hybrid_highlight_band",
                "pref_scam_hybrid_highlight_glow")
                && PreferenceNumber.read(values.get("pref_scam_hybrid_highlight_recovery"), 100) <= 0)
            return Lang.t("Включите «Восстановление светов по каналам».", "Turn on “Per-channel highlight recovery”.");
        // why/combine: clarity and the flat-area protection scale the WHY detail delta, which is off at a detail strength of 0
        if (any(key, "pref_scam_hybrid_why_clarity", "pref_scam_hybrid_why_flat_protect")
                && PreferenceNumber.read(values.get("pref_scam_hybrid_why_sharp_gain"), 1) == 0)
            return Lang.t("Не действует при «Резкость» = 0 (группа «Снимок»): работает только с деталью Why.",
                    "No effect with “Sharpness” = 0 (the “Picture” group): acts on the Why detail only.");
        if (key.startsWith("pref_watermark_") && !on("pref_show_watermark_key", true)) return Lang.t("Включите водяной знак.", "Turn on the watermark.");
        // P628: the custom text and the date format only act with their fields on
        if (key.equals("pref_watermark_text") && !on("pref_watermark_show_text", true))
            return Lang.t("Включите «Свой текст».", "Turn on “Own text”.");
        if (key.equals("pref_watermark_date_format") && !on("pref_watermark_show_time", false))
            return Lang.t("Включите «Дата и время».", "Turn on “Date and time”.");
        // «Формат фото»: Ultra HDR and the JPEG quality act on a JPEG, which a HEIC / WebP / AVIF shot writes only with «Также сохранять JPEG».
        if (!writesJpeg()) {
            if (key.equals("pref_jpeg_quality") || key.equals("pref_jpeg_fast_encoder"))
                return Lang.t("Для JPEG: выберите формат JPEG или включите «Копия в JPEG».",
                        "For JPEG: choose the JPEG format or turn on “JPEG copy”.");
        }
        // «HEIC 10 бит» needs Android 13 and an HEVC Main10 encoder with P010 input; without them the shot is an 8-bit HEIC.
        if (key.equals("pref_heic_10bit") && heic10Unavailable != null) return heic10Unavailable;
        // P266 «HDR-фото» (one switch: the JPEG's Ultra HDR, P46 / P125 the HEIC / AVIF gain map): a JPEG of the shot holds it
        // always; the HEIC needs the app's 10-bit HEIC writer, AVIF holds the map at any depth, WebP none.
        if (key.equals("pref_ultrahdr_key") && !writesJpeg()) {
            final String format = photoFormat();
            if (format.equals("webp"))
                return Lang.t("В WebP HDR не хранится: включите «Копия в JPEG» — HDR будет в нём.",
                        "WebP holds no HDR: turn on “JPEG copy” to get it in that JPEG.");
            if (hdrUnavailable != null) return hdrUnavailable;
            if (format.equals("heic")) {
                if (!on("pref_heic_10bit", false))
                    return Lang.t("Включите «HEIC 10 бит» или «Копия в JPEG»: HDR хранится в 10-битном HEIC или в JPEG.",
                            "Turn on “10-bit HEIC” or “JPEG copy”: HDR needs the 10-bit HEIC or a JPEG.");
                if (heic10Unavailable != null)
                    return Lang.t("HDR хранится только в 10-битном HEIC, а он здесь недоступен. ", "HDR needs the 10-bit HEIC, which is unavailable here. ")
                            + heic10Unavailable;
            }
        }
        if (key.equals("pref_webp_quality") && on("pref_webp_lossless", false))
            return Lang.t("Не используется в WebP без потерь.", "Not used by lossless WebP.");
        // AVIF «Без потерь» stores the exact pixels: identity matrix, 4:4:4 and the photo's own bit depth.
        if (on("pref_avif_lossless", false)) {
            if (key.equals("pref_avif_quality")) return Lang.t("Не используется в AVIF без потерь.", "Not used by lossless AVIF.");
            if (key.equals("pref_avif_depth"))
                return Lang.t("Без потерь — глубина самого снимка: 10 бит с Android 13, иначе 8.",
                        "Lossless keeps the photo's own depth: 10 bit from Android 13, else 8.");
            if (key.equals("pref_avif_chroma")) return Lang.t("Без потерь — всегда 4:4:4.", "Lossless is always 4:4:4.");
        }
        if (key.equals("pref_color_method_key") && colorMethodOverride != null) return colorMethodOverride;
        // a DCP profile replaces the matrix the colour method picks (Parameters: sensorToProPhoto from the DCP)
        if (key.equals("pref_color_method_key") && !text("pref_dcp_profile_key", "").trim().isEmpty())
            return Lang.t("Выбран DCP-профиль: цвет задаёт его матрица, выбор не действует.",
                    "A DCP profile is chosen: its matrix sets the colour, the choice has no effect.");
        // the worker keeps one copy of a shot's burst: with the lab switch on it goes to the lab's folder (ScamNeuralClient)
        if (key.equals("pref_keep_raw_burst") && on("pref_scam_lab_keep", false))
            return Lang.t("Включено «Сохранять снимки для лаборатории»: RAW-серия уже сохраняется в папку снимка лаборатории.",
                    "“Keep shots for the lab” is on: the RAW burst is already kept in the lab shot's folder.");
        return null;
    }
}
