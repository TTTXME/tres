// EditState (gallery/editor/EditState.java) one to one: every setting of one edit - the sliders by Param (key, range, default,
// step) and the geometry (quarter turns, flip, straighten, crop rect in the turned frame, aspect) - read from the Android JSON
// ({"v":1,"p":{key:value},"k":[curves],"lk":{look},"wm":{watermark},"g":{geometry}}, defaults left out). The Swift editor keeps
// the state in the same JSON; the renderer reads it here.
#pragma once

#include "EditJson.hpp"

#include <array>
#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

namespace scam_edit {

/// Java's Math.round(float): floor(x + 1/2) computed exactly, NaN 0, saturated to int.
inline int jround(float x) {
    if (std::isnan(x)) return 0;
    const double r = std::floor(double(x) + 0.5);
    if (r >= 2147483647.0) return 2147483647;
    if (r <= -2147483648.0) return -2147483647 - 1;
    return int(r);
}
/// Java's Math.round(double) (long): the nearest, halves up, exactly.
inline long long jroundD(double x) {
    if (std::isnan(x)) return 0;
    const double f = std::floor(x);
    const double r = x - f >= 0.5 ? f + 1 : f;
    if (r >= 9.2233720368547758e18) return 9223372036854775807LL;
    if (r <= -9.2233720368547758e18) return -9223372036854775807LL - 1;
    return (long long)r;
}
/// Java's (int) cast of a double / float: truncation, NaN 0, saturated.
inline int jint(double x) {
    if (std::isnan(x)) return 0;
    if (x >= 2147483647.0) return 2147483647;
    if (x <= -2147483648.0) return -2147483647 - 1;
    return int(x);
}
/// Java's Math.max / Math.min of floats (NaN wins, -0 below +0).
inline float jmax(float a, float b) {
    if (a != a) return a;
    if (a == 0.0f && b == 0.0f) return std::signbit(a) ? b : a;
    return a >= b ? a : b;
}
inline float jmin(float a, float b) {
    if (a != a) return a;
    if (a == 0.0f && b == 0.0f) return std::signbit(a) ? a : b;
    return a <= b ? a : b;
}

enum class Param : int {
    EXPOSURE, CONTRAST, HIGHLIGHTS, SHADOWS, WHITES, BLACKS, TEMPERATURE, TINT, VIBRANCE, SATURATION, TEXTURE, CLARITY, DEHAZE,
    VIGNETTE, VIGNETTE_MID, GRAIN, GRAIN_SIZE, SHARPEN, SHARPEN_RADIUS, LUMA_NR, COLOR_NR,
    HUE_0, HUE_1, HUE_2, HUE_3, HUE_4, HUE_5, HUE_6, HUE_7,
    SAT_0, SAT_1, SAT_2, SAT_3, SAT_4, SAT_5, SAT_6, SAT_7,
    LUM_0, LUM_1, LUM_2, LUM_3, LUM_4, LUM_5, LUM_6, LUM_7,
    CURVE_SHADOWS, CURVE_DARKS, CURVE_LIGHTS, CURVE_HIGHLIGHTS, CURVE_SPLIT_1, CURVE_SPLIT_2, CURVE_SPLIT_3,
    GRADE_SH_HUE, GRADE_SH_SAT, GRADE_SH_LUM, GRADE_MID_HUE, GRADE_MID_SAT, GRADE_MID_LUM, GRADE_HI_HUE, GRADE_HI_SAT, GRADE_HI_LUM,
    GRADE_ALL_HUE, GRADE_ALL_SAT, GRADE_ALL_LUM, GRADE_BLEND, GRADE_BALANCE,
    MONO, BW_0, BW_1, BW_2, BW_3, BW_4, BW_5, BW_6, BW_7,
    LUT_AMOUNT, FILM_AMOUNT, FILM_GRAIN, FILM_EXPOSURE, FILM_CONTRAST, FILM_DIR, FILM_PRINT_EV,
    COUNT
};

struct ParamInfo {
    const char* key;
    float min, max, def;
};

inline const ParamInfo& paramInfo(Param p) {
    static const ParamInfo table[] = {
        {"exposure", -4.f, 4.f, 0.f}, {"contrast", -100, 100, 0}, {"highlights", -100, 100, 0}, {"shadows", -100, 100, 0},
        {"whites", -100, 100, 0}, {"blacks", -100, 100, 0}, {"temp", -100, 100, 0}, {"tint", -100, 100, 0},
        {"vibrance", -100, 100, 0}, {"saturation", -100, 100, 0}, {"texture", -100, 100, 0}, {"clarity", -100, 100, 0},
        {"dehaze", -100, 100, 0}, {"vignette", -100, 100, 0}, {"vignetteMid", 0, 100, 50}, {"grain", 0, 100, 0},
        {"grainSize", 0, 100, 25}, {"sharpen", 0, 150, 0}, {"sharpenRadius", 0.5f, 3.f, 1.f}, {"lumaNr", 0, 100, 0},
        {"colorNr", 0, 100, 0},
        {"h0", -100, 100, 0}, {"h1", -100, 100, 0}, {"h2", -100, 100, 0}, {"h3", -100, 100, 0},
        {"h4", -100, 100, 0}, {"h5", -100, 100, 0}, {"h6", -100, 100, 0}, {"h7", -100, 100, 0},
        {"s0", -100, 100, 0}, {"s1", -100, 100, 0}, {"s2", -100, 100, 0}, {"s3", -100, 100, 0},
        {"s4", -100, 100, 0}, {"s5", -100, 100, 0}, {"s6", -100, 100, 0}, {"s7", -100, 100, 0},
        {"l0", -100, 100, 0}, {"l1", -100, 100, 0}, {"l2", -100, 100, 0}, {"l3", -100, 100, 0},
        {"l4", -100, 100, 0}, {"l5", -100, 100, 0}, {"l6", -100, 100, 0}, {"l7", -100, 100, 0},
        {"cShadows", -100, 100, 0}, {"cDarks", -100, 100, 0}, {"cLights", -100, 100, 0}, {"cHighlights", -100, 100, 0},
        {"cSplit1", 5, 95, 25}, {"cSplit2", 5, 95, 50}, {"cSplit3", 5, 95, 75},
        {"gShH", 0, 360, 0}, {"gShS", 0, 100, 0}, {"gShL", -100, 100, 0},
        {"gMiH", 0, 360, 0}, {"gMiS", 0, 100, 0}, {"gMiL", -100, 100, 0},
        {"gHiH", 0, 360, 0}, {"gHiS", 0, 100, 0}, {"gHiL", -100, 100, 0},
        {"gAlH", 0, 360, 0}, {"gAlS", 0, 100, 0}, {"gAlL", -100, 100, 0},
        {"gBlend", 0, 100, 50}, {"gBalance", -100, 100, 0},
        {"mono", 0, 1, 0},
        {"bw0", -100, 100, 0}, {"bw1", -100, 100, 0}, {"bw2", -100, 100, 0}, {"bw3", -100, 100, 0},
        {"bw4", -100, 100, 0}, {"bw5", -100, 100, 0}, {"bw6", -100, 100, 0}, {"bw7", -100, 100, 0},
        {"lutAmt", 0, 100, 100}, {"filmAmt", 0, 100, 100}, {"filmGrain", 0, 200, 0}, {"filmEv", -2, 2, 0},
        {"filmTone", -100, 100, 0}, {"filmDir", 0, 100, 0}, {"filmPrintEv", -1, 1, 0},
    };
    static_assert(sizeof(table) / sizeof(table[0]) == size_t(Param::COUNT), "the Param table");
    return table[int(p)];
}

/// Param.step: 0.01 EV, 0.1 for the sharpening radius and the film's exposure, 0.05 EV for its print, whole units otherwise.
inline float paramStep(Param p) {
    return p == Param::EXPOSURE ? 0.01f : p == Param::SHARPEN_RADIUS || p == Param::FILM_EXPOSURE ? 0.1f : p == Param::FILM_PRINT_EV ? 0.05f : 1.f;
}

/// Param.snap: clamped to the range, rounded to the step.
inline float paramSnap(Param p, float v) {
    const ParamInfo& i = paramInfo(p);
    if (std::isnan(v)) return i.def;
    const float s = paramStep(p);
    const float r = float(jround(jmax(i.min, jmin(i.max, v)) / s)) * s;
    return jmax(i.min, jmin(i.max, r));
}

inline Param paramAt(int ordinal) { return Param(ordinal); }
inline Param hueParam(int band) { return paramAt(int(Param::HUE_0) + band); }
inline Param satParam(int band) { return paramAt(int(Param::SAT_0) + band); }
inline Param lumParam(int band) { return paramAt(int(Param::LUM_0) + band); }
inline Param bwParam(int band) { return paramAt(int(Param::BW_0) + band); }
inline Param gradeParam(int wheel, int part) { return paramAt(int(Param::GRADE_SH_HUE) + wheel * 3 + part); }

struct EditState {
    static constexpr float ASPECT_FREE = 0.f, ASPECT_ORIGINAL = -1.f, MAX_STRAIGHTEN = 45.f;
    static constexpr int CURVE_MASTER = 0, CURVE_RED = 1, CURVE_GREEN = 2, CURVE_BLUE = 3;

    std::array<float, size_t(Param::COUNT)> values{};
    /// master, red, green, blue: x0, y0, x1, y1... (empty = straight)
    std::array<std::vector<float>, 4> curves;
    int quarterTurns = 0;
    bool flipH = false;
    float straighten = 0.f;
    float cropL = 0.f, cropT = 0.f, cropR = 1.f, cropB = 1.f;
    float aspect = ASPECT_FREE;
    std::string lut, film, filmPrint, filmFormat = "135";
    bool wmRemove = false;
    std::string wm;

    EditState() { for (int i = 0; i < int(Param::COUNT); i++) values[size_t(i)] = paramInfo(Param(i)).def; }

    float get(Param p) const { return values[size_t(p)]; }
    void set(Param p, float v) { values[size_t(p)] = paramSnap(p, v); }

    bool anyCurve() const { for (const auto& c : curves) if (!c.empty()) return true; return false; }
    bool anyGrade() const {
        for (int w = 0; w < 4; w++) if (get(gradeParam(w, 1)) != 0.f || get(gradeParam(w, 2)) != 0.f) return true;
        return false;
    }
    bool mono() const { return get(Param::MONO) >= 0.5f; }
    bool anyHsl() const {
        for (int i = int(Param::HUE_0); i <= int(Param::LUM_7); i++) if (values[size_t(i)] != 0.f) return true;
        return false;
    }
    bool isDefaultGeometry() const {
        return quarterTurns == 0 && !flipH && straighten == 0.f && cropL == 0.f && cropT == 0.f && cropR == 1.f && cropB == 1.f;
    }

    /// A state with the default tone and this one's geometry (the «before» view).
    EditState withNeutralTone() const {
        EditState s;
        s.quarterTurns = quarterTurns;
        s.flipH = flipH;
        s.straighten = straighten;
        s.cropL = cropL; s.cropT = cropT; s.cropR = cropR; s.cropB = cropB;
        s.aspect = aspect;
        return s;
    }

    static float clampF(float v, float lo, float hi) { return std::isnan(v) ? lo : jmax(lo, jmin(hi, v)); }

    static std::string name(const std::string& v) {
        if (v.size() > 255 || v.find('/') != std::string::npos || v.find('\\') != std::string::npos || v.find('\0') != std::string::npos) return "";
        return v;
    }

    /// EditState.fromJson: unknown keys skipped, out-of-range values clamped; false for text that is not a state.
    static bool fromJson(const std::string& json, EditState& out) {
        if (json.empty()) return false;
        try {
            const JValue o = parseJson(json);
            if (o.kind != JValue::OBJ || o.optInt("v", 0) != 1) return false;
            EditState s;
            if (const JValue* p = o.obj("p")) {
                for (const auto& kv : p->o) {
                    for (int i = 0; i < int(Param::COUNT); i++) {
                        if (kv.first != paramInfo(Param(i)).key) continue;
                        if (kv.second.kind != JValue::NUM && kv.second.kind != JValue::STR) return false; // getDouble throws
                        s.set(Param(i), float(p->optDouble(kv.first, 0)));
                    }
                }
            }
            if (const JValue* k = o.arr("k")) {
                for (size_t i = 0; i < k->a.size() && i < 4; i++) {
                    const JValue& a = k->a[i];
                    if (a.kind != JValue::ARR || a.a.size() < 4 || (a.a.size() & 1) != 0) continue;
                    std::vector<float> c(a.a.size());
                    for (size_t j = 0; j < c.size(); j++) {
                        if (a.a[j].kind != JValue::NUM) return false;
                        c[j] = clampF(float(a.a[j].n), 0.f, 1.f);
                    }
                    s.curves[i] = c;
                }
            }
            if (const JValue* lk = o.obj("lk")) {
                s.lut = name(lk->optString("l", ""));
                s.film = name(lk->optString("f", ""));
                s.filmPrint = name(lk->optString("p", ""));
                const std::string m = name(lk->optString("m", ""));
                s.filmFormat = m.empty() ? "135" : m;
            }
            if (const JValue* w = o.obj("wm")) s.wmRemove = w->optBool("rm", false);
            if (const JValue* g = o.obj("g")) {
                s.quarterTurns = ((g->optInt("q", 0) % 4) + 4) % 4;
                s.flipH = g->optBool("f", false);
                s.straighten = clampF(float(g->optDouble("a", 0)), -MAX_STRAIGHTEN, MAX_STRAIGHTEN);
                if (const JValue* c = g->arr("c")) {
                    if (c->a.size() == 4) {
                        float v[4];
                        for (int i = 0; i < 4; i++) {
                            if (c->a[size_t(i)].kind != JValue::NUM) return false;
                            v[i] = clampF(float(c->a[size_t(i)].n), 0.f, 1.f);
                        }
                        if (v[2] - v[0] > 1e-4f && v[3] - v[1] > 1e-4f) { s.cropL = v[0]; s.cropT = v[1]; s.cropR = v[2]; s.cropB = v[3]; }
                    }
                }
                const float r = float(g->optDouble("r", ASPECT_FREE));
                s.aspect = r > 0.f || r == ASPECT_ORIGINAL ? r : ASPECT_FREE;
            }
            out = s;
            return true;
        } catch (const std::exception&) {
            return false;
        }
    }
};

} // namespace scam_edit
