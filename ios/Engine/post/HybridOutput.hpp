// The merged and final photo sizes of a hybrid shot one to one: ScamHybridBurst.sabreSize / resizeTarget / megapixelSize /
// sabreWords / sabre2xFits / transportHalf and PreferenceKeys.hybridSabreSize / hybridFinalSize (P501 Sabre 1.5x / 2x, P503
// «Уменьшить до»), and the output decisions of the ScamHybridBurst constructor for a plain Bayer burst (no crop window, no
// conservative retry, no mosaic DNG).
#pragma once

#include "GLCore.hpp"
#include "Settings.hpp"

#include <array>
#include <cmath>
#include <optional>
#include <regex>
#include <stdexcept>
#include <string>
#include <vector>

namespace scam_post {

namespace HybridOutput {

/// Math.round(double): floor(x + 0.5) as a long
inline long long roundD(double v) { return std::isnan(v) ? 0 : (long long)std::floor(v + 0.5); }

/// sabreSize: the merged size of a w x h stream in output mode ("2x", "1.5x": the width on 4 px, the height on 2 px, else w x h)
inline Point sabreSize(int w, int h, const std::string& mode) {
    if (mode == "2x") return {2 * w, 2 * h};
    if (mode == "1.5x")
        return {std::min(2 * w, int(Settings::javaRound(float(w) * 1.5f / 4.f)) * 4), std::min(2 * h, int(Settings::javaRound(float(h) * 1.5f / 2.f)) * 2)};
    return {w, h};
}

/// megapixelSize: megapixels in the aspect of w x h (4:3 widths on a 64 px step, else 16)
inline Point megapixelSize(double megapixels, int w, int h) {
    const double aspect = double(w) / h, pixels = megapixels * 1000000.0;
    const int step = std::fabs(aspect - 4.0 / 3.0) < 0.01 ? 64 : 16;
    const int tw = int(roundD(std::sqrt(pixels * aspect) / step)) * step;
    const int th = int(roundD(tw / aspect / 16)) * 16;
    return {std::max(64, tw), std::max(64, th)};
}

/// firstNumbers: up to max positive numbers of text ("4000x3000", "24", "12,5")
inline std::vector<double> firstNumbers(const std::optional<std::string>& text, size_t max) {
    std::vector<double> out;
    if (!text) return out;
    static const std::regex number("[0-9]+(?:[.,][0-9]+)?");
    for (auto it = std::sregex_iterator(text->begin(), text->end(), number); it != std::sregex_iterator() && out.size() < max; ++it) {
        std::string m = it->str();
        for (char& c : m) if (c == ',') c = '.';
        out.push_back(std::strtod(m.c_str(), nullptr));
    }
    return out;
}

/// resizeTarget: the photo size a w x h frame is scaled down to after the pipeline (pref_scam_hybrid_resize), none when off
inline std::optional<Point> resizeTarget(const std::optional<std::string>& mode, const std::optional<std::string>& custom, int w, int h) {
    if (!mode || w <= 0 || h <= 0) return std::nullopt;
    if (*mode == "12" || *mode == "16" || *mode == "20") {
        const Point t = megapixelSize(std::stoi(*mode), w, h);
        return std::llabs((long long)t.x * t.y - (long long)w * h) < 0.1 * w * h ? Point{w, h} : t;
    }
    if (*mode == "mp") {
        const std::vector<double> n = firstNumbers(custom, 1);
        const double mp = n.size() == 1 ? n[0] : -1;
        if (mp >= 0.3 && mp <= 1000) return megapixelSize(mp, w, h);
        return std::nullopt;
    }
    if (*mode == "px") {
        const std::vector<double> n = firstNumbers(custom, 2);
        if (n.empty() || n[0] < 64) return std::nullopt;
        const double longSide = std::max(w, h), shortSide = std::min(w, h);
        double s = n[0] / longSide;
        if (n.size() == 2 && n[1] >= 64) s = std::min(std::max(n[0], n[1]) / longSide, std::min(n[0], n[1]) / shortSide);
        return Point{std::max(64, int(roundD(w * s / 2)) * 2), std::max(64, int(roundD(h * s / 2)) * 2)};
    }
    return std::nullopt;
}

/// sabreWords: header words 15 / 16, ow x oh unless it is the sensor grid or the 2x grid of w x h
inline std::array<int, 2> sabreWords(int w, int h, int ow, int oh) {
    const bool grid = (ow == w && oh == h) || (ow == 2 * w && oh == 2 * h);
    return grid ? std::array<int, 2>{0, 0} : std::array<int, 2>{ow, oh};
}

inline bool sabre2xFits(int w, int h, int gpuMaxSide) {
    const long long px = (long long)w * h;
    return w > 0 && h > 0 && 2LL * std::max(w, h) <= gpuMaxSide && px <= 2147483647LL / 24LL;
}

inline bool transportHalf(int w, int h, bool preferredHalf) { return preferredHalf || (long long)w * h > 2147483647LL / 12LL; }

/// ScamHdrDenoise.huge: above 100 MP
inline bool huge(int w, int h) { return (long long)w * h > 100000000LL; }

/// PreferenceKeys.hybridSabreSize
inline Point hybridSabreSize(int w, int h, const std::string& mode) { return sabreSize(w, h, mode); }

/// PreferenceKeys.hybridFinalSize: the Sabre scale's merged size, scaled down to «Уменьшить до» when that is smaller
inline Point hybridFinalSize(const Settings& s, int w, int h) {
    const Point merged = hybridSabreSize(w, h, s.hybridOutputMode());
    const std::optional<Point> t = resizeTarget(s.hybridResizeMode(), s.hybridResizeCustom(), w, h);
    return t && t->x < merged.x && t->y < merged.y ? *t : merged;
}

/// The output of a hybrid burst as the ScamHybridBurst constructor decides it (outWidth / outHeight, finalWidth / finalHeight,
/// strictTwoX, rgbHalf) and the header words it writes from them (flags 8 / 16, word 13 grid, words 15 / 16).
struct Plan {
    int width = 0, height = 0;       // the stream (sensor grid)
    int outWidth = 0, outHeight = 0; // the merged RGB
    int finalWidth = 0, finalHeight = 0;
    bool strictTwoX = false, twoX = false, rgbHalf = true;
    std::string outputMode = "sensor";

    int grid() const { return outWidth == width && outHeight == height ? 1 : 2; }
    std::array<int, 2> words() const { return sabreWords(width, height, outWidth, outHeight); }
    bool effectiveMap() const { return !huge(outWidth, outHeight); }
    /// outputScale: the returned RGB against the sensor grid
    float outputScale() const { return float(outWidth) / float(width); }
    /// ScamProcessor: Parameters.hybridFinalSize when the final size has fewer pixels than the merged RGB, else none
    std::optional<Point> hybridFinalSize() const {
        if ((long long)finalWidth * finalHeight < (long long)outWidth * outHeight) return Point{finalWidth, finalHeight};
        return std::nullopt;
    }
};

/// mosaicBlock: the stream's colour block (1 Bayer); keepCrop: «Увеличивать кадр с кропом» off on a crop shot (iPhone: false)
inline Plan plan(const Settings& s, int width, int height, int gpuMaxSide, int mosaicBlock = 1, bool keepCrop = false) {
    Plan p;
    p.width = width;
    p.height = height;
    const Point fin = keepCrop ? Point{width, height} : hybridFinalSize(s, width, height);
    p.outputMode = s.hybridOutputMode();
    const int dngMosaic = 1; // a Bayer stream (dngOnlyBlock: a Quad / Tetra stream without the remosaic is no iPhone burst)
    p.strictTwoX = !keepCrop && dngMosaic == 1 && p.outputMode == "2x";
    const bool outputMode2x = !keepCrop && dngMosaic == 1 && p.outputMode != "sensor";
    const bool wants2x = outputMode2x && mosaicBlock <= 4;
    if (p.strictTwoX && !wants2x) throw std::runtime_error("SHDR: Sabre 2x is unavailable for colour block " + std::to_string(mosaicBlock));
    p.twoX = wants2x && sabre2xFits(width, height, gpuMaxSide);
    if (p.strictTwoX && !p.twoX)
        throw std::runtime_error("SHDR: Sabre 2x " + std::to_string(2LL * width) + "x" + std::to_string(2LL * height) + " exceeds GPU side "
                                 + std::to_string(gpuMaxSide) + " or one FP16 RGB buffer (2 GB)");
    const Point sabre = sabreSize(width, height, p.outputMode);
    p.outWidth = p.twoX ? sabre.x : width;
    p.outHeight = p.twoX ? sabre.y : height;
    p.rgbHalf = transportHalf(p.outWidth, p.outHeight, s.scamDevSwitch("hybrid_rgb_half", true));
    p.finalWidth = fin.x;
    p.finalHeight = fin.y;
    return p;
}

} // namespace HybridOutput

} // namespace scam_post
