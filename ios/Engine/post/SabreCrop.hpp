// The crop of a shot one to one: SabreCrop (P576: the burst cut to the digital zoom's window before the merge, the window merged on
// the Sabre grid the photo needs), Wide169 (P118 «16:9»: the frames' centred 16:9 rows; the iPhone cuts them at capture,
// BayerBurst), RawCrop.cropGainMap / Wide169.cropGainMap (the lens shading map of the kept part), ZoomController.crop (the photo's
// centre crop when no window is cut), ScamProcessor.photoSize and FilmHalation.filmFrameScale. Java float / double semantics.
#pragma once

#include "GLCore.hpp"
#include "Settings.hpp"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstdio>
#include <optional>
#include <string>
#include <vector>

namespace scam_post {

/// RawCrop.Area
struct CropArea {
    int x = 0, y = 0, width = 0, height = 0;
    std::string str() const {
        return std::to_string(width) + "x" + std::to_string(height) + " at " + std::to_string(x) + "," + std::to_string(y);
    }
};

/// android.graphics.Rect (left, top, right, bottom)
struct CropRect {
    int left = 0, top = 0, right = 0, bottom = 0;
    int width() const { return right - left; }
    int height() const { return bottom - top; }
};

namespace Wide169 {

/// Rows of the crop of a width x height frame (a portrait frame, or one already as wide, stays whole)
inline int rows(int width, int height) {
    if (width <= height) return height;
    const int r = (width * 9 / 16) & ~7;
    return r > 0 && r < height ? r : height;
}

/// First row of the crop
inline int firstRow(int width, int height) { return ((height - rows(width, height)) / 2) & ~7; }

/// The lens shading map of the whole frame cut to the crop's rows [firstRow, firstRow + cropRows) of a frame fullHeight rows tall
inline std::vector<float> cropGainMap(const std::vector<float>& map, int cols, int mapRows, int fullHeight, int first, int cropRows) {
    if (mapRows < 2 || cols < 1 || map.size() < size_t(cols) * mapRows * 4 || fullHeight <= 1 || cropRows >= fullHeight) return map;
    std::vector<float> out(size_t(cols) * mapRows * 4);
    for (int j = 0; j < mapRows; j++) {
        const double y = (first + double(j) / (mapRows - 1) * (cropRows - 1)) / (fullHeight - 1) * (mapRows - 1);
        const int y0 = std::max(0, std::min(mapRows - 1, int(std::floor(y))));
        const int y1 = std::min(mapRows - 1, y0 + 1);
        const float t = float(std::max(0.0, std::min(1.0, y - y0)));
        for (int i = 0; i < cols * 4; i++) {
            const float a = map[size_t(y0) * cols * 4 + i] * (1 - t);
            const float b = map[size_t(y1) * cols * 4 + i] * t;
            out[size_t(j) * cols * 4 + i] = a + b;
        }
    }
    return out;
}

} // namespace Wide169

namespace RawCrop {

/// RawCrop.cropGainMap: the map of the whole fullWidth x fullHeight frame (cols x rows cells of 4 channels, corners on the frame's
/// corner pixels) resampled for area of it
inline std::vector<float> cropGainMap(const std::vector<float>& map, int cols, int rows, int fullWidth, int fullHeight, const CropArea& area) {
    if (cols < 2 || rows < 2 || map.size() < size_t(cols) * rows * 4 || fullWidth < 2 || fullHeight < 2) return map;
    auto clamp = [](int v, int max) { return std::max(0, std::min(max, v)); };
    std::vector<float> out(size_t(cols) * rows * 4);
    for (int j = 0; j < rows; j++) {
        const double fy = (area.y + double(j) / (rows - 1) * (area.height - 1)) / (fullHeight - 1) * (rows - 1);
        const int y0 = clamp(int(std::floor(fy)), rows - 1), y1 = std::min(rows - 1, y0 + 1);
        const float ty = float(std::max(0.0, std::min(1.0, fy - y0)));
        for (int i = 0; i < cols; i++) {
            const double fx = (area.x + double(i) / (cols - 1) * (area.width - 1)) / (fullWidth - 1) * (cols - 1);
            const int x0 = clamp(int(std::floor(fx)), cols - 1), x1 = std::min(cols - 1, x0 + 1);
            const float tx = float(std::max(0.0, std::min(1.0, fx - x0)));
            for (int c = 0; c < 4; c++) {
                const float a0 = map[(size_t(y0) * cols + x0) * 4 + c] * (1 - tx), a1 = map[(size_t(y0) * cols + x1) * 4 + c] * tx;
                const float a = a0 + a1;
                const float b0 = map[(size_t(y1) * cols + x0) * 4 + c] * (1 - tx), b1 = map[(size_t(y1) * cols + x1) * 4 + c] * tx;
                const float b = b0 + b1;
                const float o0 = a * (1 - ty), o1 = b * ty;
                out[(size_t(j) * cols + i) * 4 + c] = o0 + o1;
            }
        }
    }
    return out;
}

} // namespace RawCrop

/// ScamProcessor.photoSize: w x h kept to the aspect at hybridFinal's pixels when that is smaller, else w x h on even sides
inline Point photoSize(int w, int h, const std::optional<Point>& hybridFinal) {
    if (!hybridFinal || (long long)hybridFinal->x * hybridFinal->y >= (long long)w * h) return {w & ~1, h & ~1};
    const double s = std::sqrt(double(hybridFinal->x) * hybridFinal->y / (double(w) * h));
    auto r = [](double v) { return (long long)std::floor(v + 0.5); };
    return {int(std::max(2LL, r(w * s)) & ~1LL), int(std::max(2LL, r(h * s)) & ~1LL)};
}

/// ZoomController.crop's rectangle: the centre w / factor x h / factor on even sides; none for factor <= 1.005
inline std::optional<CropRect> zoomCropRect(int width, int height, float factor) {
    if (!(factor > 1.005f)) return std::nullopt;
    const int w = std::max(2, int(Settings::javaRound(float(width) / factor)) & ~1);
    const int h = std::max(2, int(Settings::javaRound(float(height) / factor)) & ~1);
    const int x = (width - w) / 2, y = (height - h) / 2;
    return CropRect{x, y, x + w, y + h};
}

/// FilmHalation.filmFrameScale (no ISZ 4:3 crop on the iPhone)
inline float filmFrameScale(float zoomCrop) { return zoomCrop > 1.005f ? 1.f / zoomCrop : 1.f; }

/// String.format(Locale.ROOT, "%.<digits>f", v): Java rounds the double's shortest decimal form (FloatingDecimal) half up; C's
/// printf rounds the exact binary value half even (1.5325 -> "1.533" in Java, "1.532" in C)
inline std::string javaFixed(double v, int digits) {
    if (!std::isfinite(v)) return std::isnan(v) ? "NaN" : v > 0 ? "Infinity" : "-Infinity";
    char buf[64];
    for (int p = 1; p <= 17; p++) { // the shortest digits that read back as v
        std::snprintf(buf, sizeof buf, "%.*e", p - 1, v);
        if (std::strtod(buf, nullptr) == v) break;
    }
    // buf: [-]d.ddde[+-]xx -> the plain digits and the decimal point's place
    std::string t(buf);
    const bool negative = t[0] == '-';
    if (negative) t.erase(0, 1);
    const size_t e = t.find('e');
    const int exponent = std::atoi(t.c_str() + e + 1);
    std::string mant = t.substr(0, e);
    mant.erase(std::remove(mant.begin(), mant.end(), '.'), mant.end());
    int point = exponent + 1; // digits before the point
    if (point <= 0) { mant.insert(0, size_t(1 - point), '0'); point = 1; }
    if (int(mant.size()) < point + digits + 1) mant.append(size_t(point + digits + 1 - int(mant.size())), '0');
    std::string keep = mant.substr(0, size_t(point + digits));
    if (mant[size_t(point + digits)] >= '5') {
        int i = int(keep.size()) - 1;
        for (; i >= 0 && keep[size_t(i)] == '9'; i--) keep[size_t(i)] = '0';
        if (i < 0) { keep.insert(0, "1"); point++; }
        else keep[size_t(i)]++;
    }
    std::string out = keep.substr(0, size_t(point));
    if (digits > 0) out += "." + keep.substr(size_t(point));
    bool zero = true;
    for (char c : keep) if (c != '0') zero = false;
    return (negative && !zero ? "-" : "") + out;
}

namespace SabreCrop {

constexpr float MIN_RESIDUAL = 1.005f;
constexpr int MIN_SIDE = 64;
constexpr long long MAX_GRID_PIXELS = 52000000LL;
constexpr int SNAP_PX = 2;

inline int period(int block) { return block == 1 ? 2 : block == 2 ? 4 : 8; }

inline int origin(int side, double residual, int p) {
    const double corner = (side - side / residual) / 2.0;
    return std::max(0, int(std::floor(corner / p + 1e-9)) * p);
}

/// The window of a w x h frame for a centred crop of residual; none for no crop, the whole frame or a side below MIN_SIDE
inline std::optional<CropArea> window(int w, int h, double residual, int p) {
    if (w <= 0 || h <= 0 || !(residual > MIN_RESIDUAL) || p < 2 || (p & 1) != 0) return std::nullopt;
    const int x = origin(w, residual, p), y = origin(h, residual, p);
    if (x == 0 && y == 0) return std::nullopt;
    const int ww = w - 2 * x, wh = h - 2 * y;
    if (ww < MIN_SIDE || wh < MIN_SIDE || (ww & 1) != 0 || (wh & 1) != 0) return std::nullopt;
    return CropArea{x, y, ww, wh};
}

/// The photo an uncropped merge of a w x h frame gives (P226: the size the crop is brought back to)
inline Point uncroppedPhoto(int w, int h, const std::string& mode, bool twoXFits, const Point& merged, const std::optional<Point>& fin) {
    const Point out = mode != "sensor" && twoXFits ? merged : Point{w, h};
    return photoSize(out.x, out.y, fin && (long long)fin->x * fin->y < (long long)out.x * out.y ? fin : std::nullopt);
}

/// The grid the window (ww x wh) is merged on for a photo of target from a crop of cw x ch sensor px
inline Point grid(int ww, int wh, double cw, double ch, const std::optional<Point>& target) {
    if (!target) return {ww, wh};
    const double sx = target->x / cw, sy = target->y / ch;
    if (sx <= 1.0005 && sy <= 1.0005) return {ww, wh};
    if (sx >= 1.9995 && sy >= 1.9995) return {2 * ww, 2 * wh};
    int ow = int(std::ceil(std::min(2.0, std::max(1.0, sx)) * ww / 4.0 - 1e-9)) * 4;
    int oh = int(std::ceil(std::min(2.0, std::max(1.0, sy)) * wh / 2.0 - 1e-9)) * 2;
    ow = std::max((ww + 3) & ~3, std::min(2 * ww, ow));
    oh = std::max(wh, std::min(2 * wh, oh));
    if (ow > 2 * ww) ow = 2 * ww;
    return {ow, oh};
}

/// SabreCrop.Plan
struct Plan {
    CropArea area;
    int frameWidth = 0, frameHeight = 0;
    float residual = 1.f;
    int period = 2;
    int outWidth = 0, outHeight = 0;
    std::optional<Point> target;
    double rx = 1, ry = 1;

    Plan() = default;
    Plan(CropArea a, int fw, int fh, float r, int p, int ow, int oh, std::optional<Point> t)
        : area(a), frameWidth(fw), frameHeight(fh), residual(r), period(p), outWidth(ow), outHeight(oh), target(t) {
        rx = area.width / (frameWidth / double(residual));
        ry = area.height / (frameHeight / double(residual));
    }

    bool sabre() const { return outWidth != area.width || outHeight != area.height; }
    float remaining() const { return float(std::max(rx, ry)); }

    std::string str() const {
        return "zoom x" + javaFixed(residual, 3) + " on " + std::to_string(frameWidth) + "x" + std::to_string(frameHeight) + " (period "
               + std::to_string(period) + "): window " + area.str() + ", merged " + std::to_string(outWidth) + "x" + std::to_string(outHeight)
               + " (scale " + javaFixed(outWidth / double(area.width), 3) + " x " + javaFixed(outHeight / double(area.height), 3) + "), photo "
               + (target ? std::to_string(target->x) + "x" + std::to_string(target->y) : std::string("the crop's own pixels (no upscale)"))
               + ", remaining crop x" + javaFixed(rx, 4) + " x " + javaFixed(ry, 4);
    }
};

struct Limits {
    int gpuMaxSide = 16384;
    bool fastRoute = true;
    long long available = -1;
};

struct Decision {
    std::optional<Plan> plan;
    std::string why;
};

/// SabreCrop.plan
inline Decision plan(int w, int h, float residual, int block, bool upscale, const std::optional<Point>& target, const Limits& limits) {
    if (!(residual > MIN_RESIDUAL)) return {std::nullopt, "no crop"};
    const int p = period(block);
    const std::optional<CropArea> area = window(w, h, residual, p);
    if (!area) {
        return {std::nullopt, "zoom x" + javaFixed(residual, 3) + " leaves no window of whole " + std::to_string(p) + " px periods"};
    }
    if (block >= 2 && (area->width / block < MIN_SIDE || area->height / block < MIN_SIDE))
        return {std::nullopt, "window " + area->str() + " below 64 binned px a side"};
    const double cw = w / double(residual), ch = h / double(residual);
    const Point out = grid(area->width, area->height, cw, ch, upscale ? target : std::nullopt);
    const Plan pl(*area, w, h, residual, p, out.x, out.y, upscale ? target : std::nullopt);
    if (!pl.sabre()) return {pl, ""};
    const bool exact2x = out.x == 2 * area->width && out.y == 2 * area->height;
    const long long gridPixels = limits.fastRoute || exact2x ? (long long)out.x * out.y : 4LL * area->width * area->height;
    std::string why;
    if (2LL * std::max(area->width, area->height) > limits.gpuMaxSide) why = "GPU side " + std::to_string(limits.gpuMaxSide);
    else if ((long long)area->width * area->height > 2147483647LL / 24LL) why = "one FP16 RGB buffer";
    else if (gridPixels > MAX_GRID_PIXELS) {
        why = javaFixed(gridPixels / 1e6, 1) + " MP grid above " + javaFixed(MAX_GRID_PIXELS / 1e6, 0) + " MP";
    } else if (limits.available > 0 && 32LL * out.x * out.y > limits.available)
        why = "memory available " + std::to_string(limits.available >> 20) + " MB";
    if (!why.empty()) {
        return {std::nullopt, "window " + area->str() + " on the " + std::to_string(out.x) + "x" + std::to_string(out.y) + " grid does not fit (" + why + ")"};
    }
    return {pl, ""};
}

/// Whether a bitmap of imgW x imgH is the ow x oh merge turned by 90 degrees (closer aspect)
inline bool turned(int imgW, int imgH, int ow, int oh) {
    const double a = std::log(imgW / double(imgH)), straight = std::log(ow / double(oh));
    return std::fabs(a + straight) + 1e-9 < std::fabs(a - straight);
}

/// SabreCrop.cropRect: the crop still to make of the merged window's image; none when nothing is cut
inline std::optional<CropRect> cropRect(int imgW, int imgH, const Plan& plan) {
    if (imgW <= 0 || imgH <= 0) return std::nullopt;
    const bool t = turned(imgW, imgH, plan.outWidth, plan.outHeight);
    const double fx = t ? plan.ry : plan.rx, fy = t ? plan.rx : plan.ry;
    auto r = [](double v) { return (long long)std::floor(v + 0.5); };
    int w = std::max(2, int(r(imgW / fx)) & ~1), h = std::max(2, int(r(imgH / fy)) & ~1);
    if (plan.target) {
        const int tx = t ? plan.target->y : plan.target->x, ty = t ? plan.target->x : plan.target->y;
        if (std::abs(w - tx) <= SNAP_PX && std::abs(h - ty) <= SNAP_PX && tx <= imgW && ty <= imgH) { w = tx; h = ty; }
    }
    w = std::min(w, imgW);
    h = std::min(h, imgH);
    if (w == imgW && h == imgH) return std::nullopt;
    const int x = (imgW - w) / 2, y = (imgH - h) / 2;
    return CropRect{x, y, x + w, y + h};
}

/// SabreCrop.target: the photo's target size in the bitmap's orientation
inline std::optional<Point> target(int imgW, int imgH, const Plan& plan) {
    if (!plan.target) return std::nullopt;
    return turned(imgW, imgH, plan.outWidth, plan.outHeight) ? Point{plan.target->y, plan.target->x} : *plan.target;
}

} // namespace SabreCrop

} // namespace scam_post
