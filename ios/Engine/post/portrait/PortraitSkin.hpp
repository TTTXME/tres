// processing/portrait/PortraitSkin.java and PortraitFaces.crop one to one (pure maths, Java float semantics: float maths in
// float, the OKLab and the sums in double, Math.min / max with NaN as Java's, Math.toDegrees / toRadians as the JDK's constant
// products): the faces' skin measure of «Портрет» on the 192 px sample (PortraitGpu), the scene-confirmed shift, the skin's
// luminance for «Приоритет лиц в AE» and Face AWB. Face boxes: normalised left, top, right, bottom in the unrotated RAW buffer.
#pragma once

#include <array>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

namespace scam_post {

/// PortraitSkin.Result
struct PortraitSkinResult {
    static constexpr int MAX_FACES = 5;
    int count = 0;
    std::vector<float> faces = std::vector<float>(MAX_FACES * 4, 0.f);
    /// per face: x = hue turn outside the broad skin band (radians), y = the scene-confirmed b / L shift, z / w unused
    std::vector<float> adjust = std::vector<float>(MAX_FACES * 4, 0.f);
    std::array<int, MAX_FACES> samples{};
    /// per face: the median skin hue (OKLab degrees), the mean skin a / L, b / L, its mean linear luminance, the spread; NaN unmeasured
    std::array<float, MAX_FACES> hue{}, a{}, b{}, luma{}, spread{};
    float sceneB = NAN;
    int sceneSamples = 0;

    bool corrected() const {
        for (int i = 0; i < count; i++) if (adjust[size_t(i) * 4] != 0.f || adjust[size_t(i) * 4 + 1] != 0.f) return true;
        return false;
    }

    /// the log line (every gate's evidence)
    std::string describe() const {
        char buf[256];
        std::snprintf(buf, sizeof buf, "sceneB=%+.4f (%d)", double(sceneB), sceneSamples);
        std::string s = buf;
        for (int i = 0; i < count; i++) {
            std::snprintf(buf, sizeof buf, " | f%d n=%d hue=%.1f a=%.4f b=%.4f turn=%.2f shift=%+.4f", i, samples[size_t(i)], double(hue[size_t(i)]),
                          double(a[size_t(i)]), double(b[size_t(i)]), double(adjust[size_t(i) * 4]) * 57.29577951308232, double(adjust[size_t(i) * 4 + 1]));
            s += buf;
        }
        return s;
    }
};

namespace PortraitSkin {

constexpr int MAX_FACES = PortraitSkinResult::MAX_FACES;
namespace detail {
/// Math.toRadians / Math.toDegrees (JDK 9+: a product with the constant)
inline double toRadians(double deg) { return deg * 0.017453292519943295; }
inline double toDegrees(double rad) { return rad * 57.29577951308232; }
/// Math.max / Math.min of floats (NaN wins, +0 over -0)
inline float jmax(float a, float b) {
    if (a != a) return a;
    if (a == 0.f && b == 0.f) return std::signbit(a) ? b : a;
    return a >= b ? a : b;
}
inline float jmin(float a, float b) {
    if (a != a) return a;
    if (a == 0.f && b == 0.f) return std::signbit(a) ? a : b;
    return a <= b ? a : b;
}
inline float clamp(float v, float lo, float hi) { return jmax(lo, jmin(hi, v)); }
inline float smooth(float lo, float hi, float v) {
    const float t = clamp((v - lo) / (hi - lo), 0.f, 1.f);
    return t * t * (3.f - 2.f * t);
}
/// Linear RGB at p to OKLab in lab; false for non-finite, clipped (>= 0.98), black or very dark pixels.
inline bool oklab(const float* pixels, size_t p, double* lab) {
    const double rr = pixels[p], gg = pixels[p + 1], bb = pixels[p + 2];
    if (!std::isfinite(rr) || !std::isfinite(gg) || !std::isfinite(bb) || std::min(rr, std::min(gg, bb)) <= 0.0001
        || std::max(rr, std::max(gg, bb)) >= 0.98)
        return false;
    if (0.2126 * rr + 0.7152 * gg + 0.0722 * bb < 0.008) return false;
    const double ll = std::cbrt(0.4122214708 * rr + 0.5363325363 * gg + 0.0514459929 * bb);
    const double mm = std::cbrt(0.2119034982 * rr + 0.6806995451 * gg + 0.1073969566 * bb);
    const double ss = std::cbrt(0.0883024619 * rr + 0.2817188376 * gg + 0.6299787005 * bb);
    lab[0] = 0.2104542553 * ll + 0.7936177850 * mm - 0.0040720468 * ss;
    lab[1] = 1.9779984951 * ll - 2.4285922050 * mm + 0.4505937099 * ss;
    lab[2] = 0.0259040371 * ll + 0.7827717662 * mm - 0.8086757660 * ss;
    return true;
}
} // namespace detail

/// MAX_TURN: (float) Math.toRadians(10.0); MAX_SHIFT: the largest yellow-blue shift of the skin (OKLab b / L)
inline float maxTurn() { return float(detail::toRadians(10.0)); }
constexpr float MAX_SHIFT = 0.035f;
constexpr float HUE_FROM = -35.f;
constexpr int HUE_BINS = 150;
constexpr float B_FROM = -0.12f;
constexpr int B_BINS = 240;
/// the natural skin hue Face AWB aims at (OKLab degrees) and its largest shift of the whole frame
constexpr float SKIN_HUE = 50.f;
constexpr float MAX_AWB = 0.03f;

/// the skin's b / L shift from the background cast and the skin hue (both must agree)
inline float shift(float sceneB, float hue, float a, float b) {
    using namespace detail;
    if (!std::isfinite(sceneB) || !std::isfinite(hue) || !std::isfinite(a) || !std::isfinite(b)) return 0.f;
    const float warm = smooth(0.030f, 0.050f, sceneB) * smooth(56.f, 64.f, hue);
    const float cold = (1.f - smooth(-0.015f, 0.005f, sceneB)) * (1.f - smooth(36.f, 44.f, hue));
    float s = -warm * 0.7f * jmax(sceneB - 0.01f, 0.f) + cold * 0.7f * jmax(0.015f - sceneB, 0.f);
    if (s < 0.f) s = jmax(s, jmin(0.f, a * float(std::tan(toRadians(52.0))) - b));
    else if (s > 0.f) s = jmin(s, jmax(0.f, a * float(std::tan(toRadians(48.0))) - b));
    return clamp(s, -MAX_SHIFT, MAX_SHIFT);
}

/// the weighted median b / L of near-neutral pixels (C / L under 0.10) outside every face box widened 1.3 times
inline void sceneNeutral(const float* pixels, int channels, int width, int height, PortraitSkinResult& out, double* lab) {
    using namespace detail;
    const int sx = std::max(1, (width + 127) / 128), sy = std::max(1, (height + 127) / 128);
    std::vector<double> hist(B_BINS, 0.0);
    double weight = 0;
    int tested = 0, neutral = 0;
    for (int y = 0; y < height; y += sy) {
        const float v = (float(y) + 0.5f) / float(height);
        for (int x = 0; x < width; x += sx) {
            const float u = (float(x) + 0.5f) / float(width);
            bool face = false;
            for (int i = 0; i < out.count && !face; i++) {
                const float l = out.faces[size_t(i) * 4], t = out.faces[size_t(i) * 4 + 1], r = out.faces[size_t(i) * 4 + 2], b = out.faces[size_t(i) * 4 + 3];
                face = std::fabs(u - (l + r) * 0.5f) <= (r - l) * 0.65f && std::fabs(v - (t + b) * 0.5f) <= (b - t) * 0.65f;
            }
            if (face || !oklab(pixels, (size_t(y) * size_t(width) + size_t(x)) * size_t(channels), lab) || lab[0] < 0.2) continue;
            tested++;
            const float ratio = float(std::hypot(lab[1], lab[2]) / lab[0]);
            const float w = 1.f - smooth(0.05f, 0.10f, ratio);
            if (w <= 0.f) continue;
            hist[size_t(std::max(0, std::min(B_BINS - 1, int(std::floor((lab[2] / lab[0] - double(B_FROM)) * 1000.0)))))] += double(w);
            weight += double(w);
            neutral++;
        }
    }
    out.sceneSamples = neutral;
    if (neutral < 64 || float(neutral) < float(tested) * 0.10f) return;
    double cumulative = 0;
    int median = 0;
    while (median < B_BINS - 1 && cumulative + hist[size_t(median)] < weight * 0.5) cumulative += hist[size_t(median++)];
    out.sceneB = B_FROM + (float(median) + 0.5f) / 1000.f;
}

/// PortraitSkin.measure: pixels linear RGB (channels a pixel, rows from y 0), boxes the faces
inline PortraitSkinResult measure(const float* pixels, size_t length, int channels, int width, int height, const std::vector<float>& boxes, float strength) {
    using namespace detail;
    PortraitSkinResult out;
    out.hue.fill(NAN);
    out.a.fill(NAN);
    out.b.fill(NAN);
    out.luma.fill(NAN);
    out.spread.fill(NAN);
    if (!pixels || channels < 3 || width <= 0 || height <= 0 || int64_t(width) * height * channels > int64_t(length) || !std::isfinite(strength)
        || strength <= 0.f)
        return out;
    strength = jmin(strength, 1.f);
    std::array<float, MAX_FACES> confidence{};
    double lab[3];
    const float turnMax = maxTurn();
    for (size_t at = 0; at + 3 < boxes.size() && out.count < MAX_FACES; at += 4) {
        if (!std::isfinite(boxes[at]) || !std::isfinite(boxes[at + 1]) || !std::isfinite(boxes[at + 2]) || !std::isfinite(boxes[at + 3])) continue;
        const float l = clamp(boxes[at], 0.f, 1.f), t = clamp(boxes[at + 1], 0.f, 1.f);
        const float r = clamp(boxes[at + 2], 0.f, 1.f), b = clamp(boxes[at + 3], 0.f, 1.f);
        if (r <= l || b <= t) continue;
        const int face = out.count++;
        const size_t to = size_t(face) * 4;
        out.faces[to] = l; out.faces[to + 1] = t; out.faces[to + 2] = r; out.faces[to + 3] = b;
        const int x0 = std::max(0, int(std::ceil(double(l * float(width) - 0.5f))));
        const int y0 = std::max(0, int(std::ceil(double(t * float(height) - 0.5f))));
        const int x1 = std::min(width, int(std::ceil(double(r * float(width) - 0.5f))));
        const int y1 = std::min(height, int(std::ceil(double(b * float(height) - 0.5f))));
        const int sx = std::max(1, (x1 - x0 + 63) / 64), sy = std::max(1, (y1 - y0 + 63) / 64);
        std::vector<double> hist(HUE_BINS, 0.0);
        double weight = 0, sumA = 0, sumB = 0, sumY = 0, sumAA = 0, sumBB = 0;
        int tested = 0, accepted = 0;
        for (int y = y0; y < y1; y += sy) {
            for (int x = x0; x < x1; x += sx) {
                const float ex = ((float(x) + 0.5f) / float(width) - (l + r) * 0.5f) / ((r - l) * 0.46f);
                const float ey = ((float(y) + 0.5f) / float(height) - (t + b) * 0.5f) / ((b - t) * 0.48f);
                const float ellipse = 1.f - smooth(0.65f, 1.f, ex * ex + ey * ey);
                if (ellipse <= 0.f) continue;
                tested++;
                if (!oklab(pixels, (size_t(y) * size_t(width) + size_t(x)) * size_t(channels), lab)) continue;
                const double light = lab[0], aa = lab[1], ab = lab[2];
                const float hue = float(toDegrees(std::atan2(ab, aa)));
                const float ratio = float(std::hypot(aa, ab) / std::max(light, 0.0001));
                const float skin = smooth(-35.f, -15.f, hue) * (1.f - smooth(95.f, 115.f, hue)) * smooth(0.03f, 0.05f, ratio)
                                   * (1.f - smooth(0.30f, 0.40f, ratio)) * smooth(0.12f, 0.20f, float(light));
                if (skin <= 0.05f) continue;
                const double w = double(ellipse * skin);
                const int bin = std::max(0, std::min(HUE_BINS - 1, int(hue - HUE_FROM)));
                hist[size_t(bin)] += w;
                weight += w;
                sumA += w * aa / light;
                sumB += w * ab / light;
                sumY += w * light * light * light;
                sumAA += w * (aa / light) * (aa / light);
                sumBB += w * (ab / light) * (ab / light);
                accepted++;
            }
        }
        out.samples[size_t(face)] = accepted;
        if (accepted < 16 || float(accepted) < float(tested) * 0.10f || weight < 8.0) continue;
        double cumulative = 0;
        int median = 0;
        while (median < HUE_BINS - 1 && cumulative + hist[size_t(median)] < weight * 0.5) cumulative += hist[size_t(median++)];
        const float hue = HUE_FROM + float(median) + 0.5f;
        out.hue[size_t(face)] = hue;
        out.a[size_t(face)] = float(sumA / weight);
        out.b[size_t(face)] = float(sumB / weight);
        out.luma[size_t(face)] = float(sumY / weight);
        out.spread[size_t(face)] = float(std::sqrt(std::max(0.0, sumAA / weight - double(out.a[size_t(face)]) * double(out.a[size_t(face)]) + sumBB / weight
                                                                   - double(out.b[size_t(face)]) * double(out.b[size_t(face)]))));
        confidence[size_t(face)] = strength * float(std::min(1.0, weight / 24.0));
        const float delta = clamp(hue, 25.f, 70.f) - hue;
        const float turn = clamp(float(toRadians(double(delta))), -turnMax, turnMax);
        out.adjust[to] = turn * strength * float(std::min(1.0, weight / 24.0));
    }
    sceneNeutral(pixels, channels, width, height, out, lab);
    for (int face = 0; face < out.count; face++)
        if (std::isfinite(out.hue[size_t(face)]))
            out.adjust[size_t(face) * 4 + 1] = shift(out.sceneB, out.hue[size_t(face)], out.a[size_t(face)], out.b[size_t(face)]) * confidence[size_t(face)];
    return out;
}

/// the measured faces' skin luminance, weighted by their samples; NaN without a measured face
inline float faceLuma(const PortraitSkinResult* r) {
    double sum = 0, weight = 0;
    for (int i = 0; r && i < r->count; i++)
        if (std::isfinite(r->luma[size_t(i)]) && r->luma[size_t(i)] > 0.f) {
            sum += r->samples[size_t(i)] * double(r->luma[size_t(i)]);
            weight += r->samples[size_t(i)];
        }
    return weight > 0 ? float(sum / weight) : NAN;
}

/// Face AWB: the whole frame's OKLab a / L, b / L shift that moves the measured skin strength of the way to SKIN_HUE
inline std::array<float, 2> faceAwb(const PortraitSkinResult* r, float strength) {
    double a = 0, b = 0, weight = 0;
    for (int i = 0; r && i < r->count; i++)
        if (std::isfinite(r->a[size_t(i)]) && std::isfinite(r->b[size_t(i)])) {
            a += r->samples[size_t(i)] * double(r->a[size_t(i)]);
            b += r->samples[size_t(i)] * double(r->b[size_t(i)]);
            weight += r->samples[size_t(i)];
        }
    if (weight <= 0 || !(strength > 0.f)) return {0.f, 0.f};
    a /= weight;
    b /= weight;
    const double c = std::hypot(a, b), hue = detail::toDegrees(std::atan2(b, a));
    const double off = hue - double(SKIN_HUE), dead = 6.0;
    if (std::fabs(off) <= dead) return {0.f, 0.f};
    const double sign = off > 0 ? 1.0 : off < 0 ? -1.0 : off;   // Math.signum
    const double aim = detail::toRadians(double(SKIN_HUE) + sign * dead);
    const float k = detail::jmin(strength, 1.f);
    double da = (c * std::cos(aim) - a) * double(k), db = (c * std::sin(aim) - b) * double(k);
    const double n = std::hypot(da, db);
    if (n > double(MAX_AWB)) { da *= double(MAX_AWB) / n; db *= double(MAX_AWB) / n; }
    return {float(da), float(db)};
}

} // namespace PortraitSkin

namespace PortraitFaces {

constexpr int MAX_FACES = 5;

/// PortraitFaces.append: clamped to the frame; false for a non-finite or empty box
inline bool append(std::vector<float>& out, size_t at, float l, float t, float r, float b) {
    using namespace PortraitSkin::detail;
    if (!std::isfinite(l) || !std::isfinite(t) || !std::isfinite(r) || !std::isfinite(b)) return false;
    l = jmax(0.f, l); t = jmax(0.f, t); r = jmin(1.f, r); b = jmin(1.f, b);
    if (r <= l || b <= t) return false;
    out[at] = l; out[at + 1] = t; out[at + 2] = r; out[at + 3] = b;
    return true;
}

/// PortraitFaces.crop: the boxes in the window (left, top, width, height of the frame, normalised), at most MAX_FACES
inline std::vector<float> crop(const std::vector<float>& boxes, float left, float top, float width, float height) {
    if (!std::isfinite(left) || !std::isfinite(top) || !std::isfinite(width) || !std::isfinite(height) || width <= 0.f || height <= 0.f) return {};
    std::vector<float> out(size_t(std::min(int(boxes.size() / 4), MAX_FACES)) * 4);
    size_t n = 0;
    for (size_t i = 0; i + 3 < boxes.size() && n < out.size(); i += 4)
        if (append(out, n, (boxes[i] - left) / width, (boxes[i + 1] - top) / height, (boxes[i + 2] - left) / width, (boxes[i + 3] - top) / height)) n += 4;
    out.resize(n);
    return out;
}

} // namespace PortraitFaces

} // namespace scam_post
