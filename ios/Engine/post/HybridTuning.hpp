// The merge worker's hybrid_tuning.txt of a shot: PreferenceKeys.hybridTuningText (the settings' lines) and
// ScamHybridBurst.tuningOverride (the burst's own lines after them) one to one, for an iPhone burst: plain Bayer (no mosaic DNG),
// no conservative retry, no avoided steps, no DCG, no FULLRES series.
#pragma once

#include "GLCore.hpp"
#include "Settings.hpp"

#include <cmath>
#include <optional>
#include <string>

namespace scam_post {

namespace HybridTuning {

constexpr float US_DAYLIGHT_EV = 10.f, US_INCOHERENT = 0.5f;
inline const char* FAST_TUNING = "rejPool 0\nrejVarExact 0\nrejMatchBlur 0\nrejPerChannel 0\nrejRelFloor 0\nrejMeanDiff 0\n"
                                 "rejMeanFloor 0\nrejKneeLo 0\nrejKneeHi 0\nlaStride 16\nlaIters 0\nfastMerge 1\n";

inline std::string f(float v) { return javaFloatString(v); }
inline float clamp01(float v) { return std::isnan(v) ? 0.f : std::max(0.f, std::min(1.f, v)); }
inline long round(float v) { return Settings::javaRound(v); }

inline bool fastProcessing(const Settings& s) { return s.hybridString("processing", "fast") != "quality"; }
inline int shastaMode(const Settings& s) {
    if (s.isMotionMode()) return 0;
    if (!s.hybridSwitch("shasta", true)) return 0;
    return int(std::max(1L, std::min(2L, round(s.hybridValue("shasta_mode", 2.f)))));
}

/// PreferenceKeys.storedTuningValue
inline std::string storedTuningValue(const Settings& s, const std::string& key) {
    const std::string raw = s.hybridString(key, "");
    if (raw.empty()) return "";
    double d = readNumber(raw, NAN);
    if (!std::isfinite(d)) return "";
    if (auto b = Settings::bounds(Settings::HYBRID_PREFIX + key)) { // SettingsNumericRules.value(key, d, d)
        d = std::max((*b)[0], std::min((*b)[1], d));
        if ((*b)[2] == 1) d = std::nearbyint(d);
    }
    return f(float(d));
}

/// Float.parseFloat of a trimmed value (the Java number grammar the stored strings use)
inline std::optional<float> parseFloat(const std::string& v) {
    const std::string t = Settings::trim(v);
    if (t.empty()) return std::nullopt;
    char* end = nullptr;
    const float x = std::strtof(t.c_str(), &end);
    if (end == t.c_str()) return std::nullopt;
    std::string rest(end);
    if (!rest.empty() && rest != "f" && rest != "F" && rest != "d" && rest != "D") return std::nullopt;
    return x;
}

/// PreferenceKeys.hybridTuningText
inline std::string text(const Settings& s) {
    std::string out;
    const bool motion = s.isMotionMode(), noHdr = s.noHdrFrames();
    for (const TuningKeyRow& k : kTuningKeys) {
        const std::string key = k.key;
        const auto dev = s.scamDevValue("hybrid_" + key);
        std::string v = noHdr && (key == "bento" || key == "shasta") ? "0.0"
                        : dev ? f(*dev)
                        : motion && Settings::motionDefault(key) ? f(s.hybridValue(key, 0.f))
                        : storedTuningValue(s, key);
        if (v.empty()) if (auto d = Settings::hybridDefaultOf(key)) v = f(*d);
        if (v.empty()) continue;
        if (auto x = parseFloat(v)) out += std::string(k.name) + " " + f(*x) + "\n";
    }
    if (!s.scamDevValue("hybrid_profile") && s.hybridSwitch("profile", false)) out += "profile 1\n";
    const float s61Fine = s.hybridValue("s61_fine", 0.f);
    if (s61Fine > 0.f) out += "s61Fine " + f(s61Fine) + "\n";
    const float nightScale = s.hybridValue("s61_night_scale", 0.8f);
    if (nightScale > 0.f && nightScale != 1.f) out += "s61NightScale " + f(std::max(0.25f, std::min(2.f, nightScale))) + "\n";
    if (s.hybridValue("mosaic_nat_tex", 0.f) != 0.f) out += "mosaicNatTex 1\n";
    if (s.hybridValue("fast_mosaic_chroma", 1.f) == 0.f) out += "fastMosaicChroma 0\n";
    if (s.hybridValue("fast_site_gain", 1.f) == 0.f) out += "fastSiteGain 0\n";
    const float quadLattice = s.hybridValue("fast_quad_lattice", 2.f);
    if (quadLattice != 2.f) out += "fastQuadLattice " + std::to_string(round(std::max(0.f, std::min(2.f, quadLattice)))) + "\n";
    if (s.hybridValue("quad_detail", 1.f) == 0.f)
        out += "fastQuadFlatNoise 0\nfastQuadLineFrames 0\nfastQuadCone 0\nmosaicQuadFine 0\nbaseDemosaicQuadRadius 2.2\nmosaicQuadTexture 1\n";
    const float tetraSmooth = s.hybridValue("tetra_motion_smooth", 1.f);
    if (tetraSmooth != 1.f) out += "mosaicTetraMotionSmooth " + f(std::max(0.f, std::min(1.f, tetraSmooth))) + "\n";
    if (s.hybridValue("tetra3", 1.f) == 0.f) out += "fastTetraPost 0\nfastTetraClipNotch 0\nnormalWeakHom 0\n";
    const float flowPick = s.hybridValue("fast_flow_pick", 2.f);
    if (flowPick != 2.f) out += "fastFlowPick " + std::to_string(round(std::max(0.f, std::min(2.f, flowPick)))) + "\n";
    const float lightFill = s.hybridValue("fast_light_fill", 1.f);
    if (lightFill != 1.f) out += "fastLightFill " + f(std::max(0.f, std::min(1.f, lightFill))) + "\n";
    if (s.hybridValue("us_weak_hom", 1.f) == 0.f) out += "usWeakHom 0\n";
    if (s.hybridValue("us_clip_vote", 1.f) == 0.f) out += "usClipVote 0\n";
    if (s.hybridValue("strip_guard", 1.f) == 0.f) out += "stripGuard 0\n";
    const float natTile = s.hybridValue("mosaic_nat_tile", 2.f);
    if (natTile != 2.f) out += "mosaicNatTile " + std::to_string(round(std::max(0.f, std::min(2.f, natTile)))) + "\n";
    const float dezipper = s.hybridValue("dezipper", 4.5f);
    if (dezipper > 0.f) out += "dezipper " + f(std::max(0.5f, std::min(15.f, dezipper))) + "\n";
    if (s.hybridValue("rej_prior", 1.f) > 0.f) out += "rejPrior 1\n";
    if (s.hybridValue("rej_set", 1.f) > 0.f) {
        const bool motionMode = s.isMotionMode();
        out += "rejPool 3\nrejVarExact 1\nrejMatchBlur 1\nrejPerChannel 1\nrejRelFloor 0.004\nrejMeanDiff 0.2\n";
        out += "rejMeanFloor 0.015\nrejPoolT 1.2\n";
        out += motionMode ? "rejKneeLo 0.1\nrejKneeHi 0.6\ncdm 0.3\n" : "rejKneeLo 0.05\nrejKneeHi 0.8\n";
        out += "rejSkipUs 1\n";
        if (!motionMode && std::fabs(s.hybridValue("dilate_floor", 0.15f) - 0.15f) < 1e-4f) out += "dilateFloor 0.35\n";
    } else if (s.hybridValue("rej_pool", 1.f) > 0.f) out += "rejPool 1\n";
    if (s.hybridValue("clip_reject", 1.f) > 0.f) out += "clipReject 1\n";
    if (s.hybridValue("rim_lift", 1.f) > 0.f) out += "rimLift 1\n";
    if (s.hybridValue("us_need_clip", 1.f) > 0.f) out += "usNeedClip 1\n";
    const float alias = clamp01(s.hybridValue("base_demosaic_alias", 1.f));
    if (alias > 0.f) out += "baseDemosaicAlias " + f(alias) + "\n";
    if (auto hand = s.scamDevValue("hybrid_plain_chroma_hand")) out += "plainChromaHand " + f(clamp01(*hand)) + "\n";
    if (s.hybridSwitch("auto2x", true)) out += "auto2x 1\n";
    const int shasta = shastaMode(s);
    if (shasta == 0) out += "shastaEnable 0\n";
    else if (shasta == 2) out += "shastaForce 1\n";
    const auto boostDev = s.scamDevValue("hybrid_motion_boost");
    out += "boostEnable " + f(boostDev ? *boostDev : s.hybridSwitch("motion_boost", true) ? 1.f : 0.f) + "\n";
    if (!s.hybridSwitch("cell_clip", false)) out += "cellClip 0\n";
    if (!s.hybridSwitch("chroma_diff_clamp", true)) out += "chromaDiffClamp 0\n";
    if (!s.hybridSwitch("bento_scam", true)) out += "bentoScam 0\n";
    if (s.hybridSwitch("bento_blend", true))
        out += "bentoBlend 1\nbentoBlendSigma " + f(std::max(0.25f, 0.25f * s.hybridValue("bento_ramp", *Settings::hybridDefaultOf("bento_ramp")))) + "\n";
    if (s.hybridValue("us_set", 1.f) > 0.f) {
        out += "bentoUsRegister 2\nlaUltrashort 1\nbentoBlendGateOwnLo 0.3\nbentoBlendGateOwnHi 0.6\nbentoRefuseScope 1\n";
        out += "bentoMotionMax 25\nbentoValidZ 3\nbentoValidZRange 1\nbentoBlendGainMin 0.5\nbentoBlendGainLevel 0.1\n";
        out += "bentoBlendCellMode 2\nbentoBlendScale 2\n";
    }
    if (s.hybridValue("long_set", 1.f) > 0.f) out += "noiseRoles 1\nshastaNoiseWeight 0.005\n";
    if (s.hybridValue("dark_read", 1.f) > 0.f) out += "noiseDarkRead 1\n";
    if (s.hybridValue("us_partial", 1.f) > 0.f) out += "bentoPartial 1\nbentoAliasDamp 1\n";
    if (s.hybridValue("us_edges", 1.f) > 0.f) out += "bentoBlendUsBox 1\nbentoBlendFieldBilinear 1\nbentoBlendPixelMark 1\n";
    if (s.hybridValue("us_cache", 1.f) > 0.f) out += "bentoUsCache 1\n";
    if (s.hybridValue("bayer_split", 1.f) > 0.f) out += "bayerSplit 1\n";
    out += std::string("bayerQuads ") + (s.hybridValue("bayer_quads", 1.f) > 0.f ? "1" : "0") + "\n";
    out += "texMaps " + std::to_string(std::max(0L, std::min(31L, round(s.hybridValue("tex_maps", 19.f))))) + "\n";
    if (s.hybridValue("long_scope", 1.f) > 0.f) out += "shastaMotionScope 1\nshastaScopeAll 1\nshastaScopeLone 1\n";
    if (s.hybridSwitch("us_fill", true)) out += "mosaicUsFill 1\n";
    if (!s.hybridSwitch("mosaic_window_full", true)) out += "mosaicWindowFull 0\n";
    const float daylightEv = s.hybridValue("us_daylight_ev", US_DAYLIGHT_EV), incoherent = s.hybridValue("us_incoherent", US_INCOHERENT);
    if (daylightEv != US_DAYLIGHT_EV) out += "usDaylightEv " + f(std::max(0.f, daylightEv)) + "\n";
    if (incoherent != US_INCOHERENT) out += "usIncoherent " + f(std::max(0.f, incoherent)) + "\n";
    if (motion && s.scamDevSwitch("motion_sharp_priority", true)) out += "widenBelow 0\nbaseDemosaicAniso 1\n";
    const float pixDiff = s.hybridValue("rej_pix_diff", 0.f), shiftMax = s.hybridValue("donor_shift_max", motion ? 120.f : 0.f);
    const long fastSport = round(s.hybridValue("fast_merge_sport", motion ? 3.f : 0.f));
    if (pixDiff > 0.f) out += "rejPixDiff " + f(std::min(1.f, pixDiff)) + "\n";
    if (shiftMax > 0.f) out += "donorShiftMax " + f(shiftMax) + "\n";
    if (fastSport > 0 && fastProcessing(s)) out += "fastMergeSport " + std::to_string(fastSport & 3) + "\n";
    if (fastProcessing(s)) out += FAST_TUNING;
    if (!s.hybridSwitch("denoise", true)) out += "denoise 0\n"; // DenoiseStages.workerTuning
    return out;
}

/// ScamHybridBurst.sceneEv100
inline float sceneEv100(double exposureSeconds, int iso, float aperture) {
    if (!(exposureSeconds > 0) || iso <= 0 || !(aperture > 0.f) || !std::isfinite(aperture)) return NAN;
    const double t = exposureSeconds, n = aperture;
    return float(std::log(n * n / t / (iso / 100.0)) / std::log(2.0));
}

/// ScamHybridBurst.tuningOverride for a plain Bayer burst: the remosaic line, the base frame's EV100 and the neutral
inline std::string burstOverride(float sceneEv, const std::vector<float>& whitePoint, bool strictTwoX) {
    char buf[96];
    std::string out = "mosaicRemosaic 1\n";
    if (std::isfinite(sceneEv)) { std::snprintf(buf, sizeof buf, "usSceneEv %.2f\n", double(sceneEv)); out += buf; }
    if (whitePoint.size() >= 3 && whitePoint[1] > 0.f && std::isfinite(whitePoint[1])) {
        const float r = whitePoint[0] / whitePoint[1], b = whitePoint[2] / whitePoint[1];
        if (r >= 0.1f && r <= 10.f && b >= 0.1f && b <= 10.f) { std::snprintf(buf, sizeof buf, "wbR %.5f\nwbB %.5f\n", double(r), double(b)); out += buf; }
    }
    if (strictTwoX) out += "grid 2\nauto2x 0\n";
    return out;
}

} // namespace HybridTuning
} // namespace scam_post
