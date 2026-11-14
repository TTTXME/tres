// CropMath (gallery/editor/CropMath.java) one to one (double math): the editor's frames - source uv -> oriented (EXIF) ->
// base (quarter turns, mirror) -> output (crop, after the straighten rotation) - as affine maps, the crop fitting, the aspect
// presets, turns and flips, the drag constraint.
#pragma once

#include "EditState.hpp"

#include <array>
#include <cmath>
#include <stdexcept>

namespace scam_edit {
namespace CropMath {

/// x' = a x + b y + c, y' = d x + e y + f
struct Affine {
    double a = 1, b = 0, c = 0, d = 0, e = 1, f = 0;
    Affine() = default;
    Affine(double a_, double b_, double c_, double d_, double e_, double f_) : a(a_), b(b_), c(c_), d(d_), e(e_), f(f_) {}
    static Affine identity() { return Affine(); }
    static Affine scale(double sx, double sy) { return Affine(sx, 0, 0, 0, sy, 0); }
    static Affine translate(double tx, double ty) { return Affine(1, 0, tx, 0, 1, ty); }
    /// this one first, then n
    Affine then(const Affine& n) const {
        return Affine(n.a * a + n.b * d, n.a * b + n.b * e, n.a * c + n.b * f + n.c, n.d * a + n.e * d, n.d * b + n.e * e, n.d * c + n.e * f + n.f);
    }
    Affine inverse() const {
        const double det = a * e - b * d;
        if (std::fabs(det) < 1e-300) throw std::runtime_error("singular affine");
        const double ia = e / det, ib = -b / det, id = -d / det, ie = a / det;
        return Affine(ia, ib, -(ia * c + ib * f), id, ie, -(id * c + ie * f));
    }
    std::array<double, 2> apply(double x, double y) const { return {a * x + b * y + c, d * x + e * y + f}; }
    /// column-major mat3 for glUniformMatrix3fv (transpose false)
    std::array<float, 9> mat3() const { return {float(a), float(d), 0.f, float(b), float(e), 0.f, float(c), float(f), 1.f}; }
};

/// Math.toRadians (JDK 9+: degrees x DEGREES_TO_RADIANS)
inline double toRadians(double deg) { return deg * 0.017453292519943295; }

inline Affine orientationToDisplay(int orientation) {
    switch (orientation) {
    case 2: return Affine(-1, 0, 1, 0, 1, 0);
    case 3: return Affine(-1, 0, 1, 0, -1, 1);
    case 4: return Affine(1, 0, 0, 0, -1, 1);
    case 5: return Affine(0, 1, 0, 1, 0, 0);
    case 6: return Affine(0, -1, 1, 1, 0, 0);
    case 7: return Affine(0, -1, 1, -1, 0, 1);
    case 8: return Affine(0, 1, 0, -1, 0, 1);
    default: return Affine();
    }
}

inline bool swapsSides(int orientation) { return orientation >= 5 && orientation <= 8; }

inline std::array<double, 2> baseSize(double w, double h, int quarterTurns) {
    return (quarterTurns & 1) == 1 ? std::array<double, 2>{h, w} : std::array<double, 2>{w, h};
}

inline Affine baseToOrientedUnrotated(int quarterTurns, bool flipH) {
    const Affine m = flipH ? Affine(-1, 0, 1, 0, 1, 0) : Affine();
    Affine turn;
    switch (((quarterTurns % 4) + 4) % 4) {
    case 1: turn = Affine(0, 1, 0, -1, 0, 1); break;
    case 2: turn = Affine(-1, 0, 1, 0, -1, 1); break;
    case 3: turn = Affine(0, -1, 1, 1, 0, 0); break;
    default: turn = Affine();
    }
    return m.then(turn);
}

inline Affine unstraighten(double wb, double hb, double degrees) {
    const double t = toRadians(degrees), c = std::cos(t), s = std::sin(t);
    const Affine toPx = Affine::translate(-0.5, -0.5).then(Affine::scale(wb, hb));
    const Affine rot(c, s, 0, -s, c, 0);
    const Affine back = Affine::scale(1 / wb, 1 / hb).then(Affine::translate(0.5, 0.5));
    return toPx.then(rot).then(back);
}

inline Affine baseToOriented(const EditState& s, double w, double h) {
    const auto b = baseSize(w, h, s.quarterTurns);
    return unstraighten(b[0], b[1], s.straighten).then(baseToOrientedUnrotated(s.quarterTurns, s.flipH));
}

inline Affine cropToBase(const EditState& s) {
    return Affine(double(s.cropR - s.cropL), 0, s.cropL, 0, double(s.cropB - s.cropT), s.cropT);
}

inline Affine outputToOriented(const EditState& s, double w, double h) { return cropToBase(s).then(baseToOriented(s, w, h)); }

inline std::array<double, 2> cropPixels(const EditState& s, double w, double h) {
    const auto b = baseSize(w, h, s.quarterTurns);
    return {double(s.cropR - s.cropL) * b[0], double(s.cropB - s.cropT) * b[1]};
}

// ---------------------------------------------------------------- fitting

inline double maxScale(double cx, double cy, double a0, double b0, double wb, double hb, double degrees) {
    const double t = toRadians(degrees), c = std::cos(t), s = std::sin(t);
    const double ac = std::fabs(c), as = std::fabs(s);
    const double k1 = (wb / 2 - std::fabs(c * cx + s * cy)) / std::max(1e-12, a0 * ac + b0 * as);
    const double k2 = (hb / 2 - std::fabs(-s * cx + c * cy)) / std::max(1e-12, a0 * as + b0 * ac);
    return std::min(k1, k2);
}

inline bool fits(double l, double t, double r, double b, double wb, double hb, double degrees) {
    const double cx = ((l + r) / 2 - 0.5) * wb, cy = ((t + b) / 2 - 0.5) * hb;
    const double a0 = (r - l) / 2 * wb, b0 = (b - t) / 2 * hb;
    return l >= -1e-6 && t >= -1e-6 && r <= 1 + 1e-6 && b <= 1 + 1e-6 && maxScale(cx, cy, a0, b0, wb, hb, degrees) >= 1 - 1e-6;
}

inline std::array<float, 4> clampRect(float l, float t, float r, float b) {
    return {jmax(0.f, l), jmax(0.f, t), jmin(1.f, r), jmin(1.f, b)};
}

inline std::array<float, 4> inset(float l, float t, float r, float b, double wb, double hb, double degrees) {
    double cx = (double(l + r) / 2.0 - 0.5) * wb, cy = (double(t + b) / 2.0 - 0.5) * hb;
    const double a0 = double(r - l) / 2.0 * wb, b0 = double(b - t) / 2.0 * hb;
    double k = maxScale(cx, cy, a0, b0, wb, hb, degrees);
    if (k >= 1) return clampRect(l, t, r, b);
    const double k0 = maxScale(0, 0, a0, b0, wb, hb, degrees);
    if (k < 0.5 * std::min(1.0, k0)) {
        cx = 0;
        cy = 0;
        k = k0;
    }
    k = std::min(1.0, std::max(1e-4, k)) * (1 - 1e-6);
    const double hw = a0 * k / wb, hh = b0 * k / hb;
    const double ux = cx / wb + 0.5, uy = cy / hb + 0.5;
    return clampRect(float(ux - hw), float(uy - hh), float(ux + hw), float(uy + hh));
}

inline void insetState(EditState& s, double w, double h) {
    const auto bs = baseSize(w, h, s.quarterTurns);
    const auto c = inset(s.cropL, s.cropT, s.cropR, s.cropB, bs[0], bs[1], s.straighten);
    s.cropL = c[0]; s.cropT = c[1]; s.cropR = c[2]; s.cropB = c[3];
}

inline double ratioOf(float aspect, double wb, double hb) {
    if (aspect == EditState::ASPECT_ORIGINAL) return wb / hb;
    return aspect > 0 ? aspect : 0;
}

inline void applyAspect(EditState& s, double w, double h) {
    const auto bs = baseSize(w, h, s.quarterTurns);
    const double ratio = ratioOf(s.aspect, bs[0], bs[1]);
    if (ratio <= 0) return;
    const double wb = bs[0], hb = bs[1];
    double a0, b0;
    if (wb / hb > ratio) {
        b0 = hb / 2;
        a0 = b0 * ratio;
    } else {
        a0 = wb / 2;
        b0 = a0 / ratio;
    }
    double cx = (double(s.cropL + s.cropR) / 2.0 - 0.5) * wb, cy = (double(s.cropT + s.cropB) / 2.0 - 0.5) * hb;
    double k = std::min(1.0, maxScale(cx, cy, a0, b0, wb, hb, s.straighten));
    const double k0 = std::min(1.0, maxScale(0, 0, a0, b0, wb, hb, s.straighten));
    if (k < 0.8 * k0) {
        cx = 0;
        cy = 0;
        k = k0;
    }
    k = std::max(1e-4, k) * (1 - 1e-6);
    const double hw = a0 * k / wb, hh = b0 * k / hb, ux = cx / wb + 0.5, uy = cy / hb + 0.5;
    const auto c = clampRect(float(ux - hw), float(uy - hh), float(ux + hw), float(uy + hh));
    s.cropL = c[0]; s.cropT = c[1]; s.cropR = c[2]; s.cropB = c[3];
}

inline void rotateClockwise(EditState& s) {
    const float l = s.cropL, t = s.cropT, r = s.cropR, b = s.cropB;
    s.cropL = 1 - b;
    s.cropT = l;
    s.cropR = 1 - t;
    s.cropB = r;
    s.quarterTurns = (s.quarterTurns + (s.flipH ? 3 : 1)) % 4;
    if (s.aspect > 0) s.aspect = 1.f / s.aspect;
}

inline void flipHorizontal(EditState& s) {
    const float l = s.cropL, r = s.cropR;
    s.cropL = 1 - r;
    s.cropR = 1 - l;
    s.flipH = !s.flipH;
    s.straighten = -s.straighten;
}

inline double lerp(double a, double b, double t) { return a + (b - a) * t; }

inline std::array<float, 4> constrain(const std::array<float, 4>& from, const std::array<float, 4>& to, double wb, double hb, double degrees) {
    if (fits(to[0], to[1], to[2], to[3], wb, hb, degrees)) return to;
    if (!fits(from[0], from[1], from[2], from[3], wb, hb, degrees)) return from;
    double lo = 0, hi = 1;
    for (int i = 0; i < 24; i++) {
        const double m = (lo + hi) / 2;
        if (fits(lerp(from[0], to[0], m), lerp(from[1], to[1], m), lerp(from[2], to[2], m), lerp(from[3], to[3], m), wb, hb, degrees)) lo = m;
        else hi = m;
    }
    return {float(lerp(from[0], to[0], lo)), float(lerp(from[1], to[1], lo)), float(lerp(from[2], to[2], lo)), float(lerp(from[3], to[3], lo))};
}

} // namespace CropMath
} // namespace scam_edit
