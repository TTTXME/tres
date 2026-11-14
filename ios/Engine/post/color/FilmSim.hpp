// The film simulation's settings and colour model one to one: FilmSim.java (the stocks, prints and formats, FilmSim.Settings and
// FilmSim.current, our black-and-white model, the display LUT FilmSim.lut, the scene LUT FilmSim.lutScene, the grain helpers and
// FilmSim.grainLut), FilmAdvanced.java (the page's advanced controls, as far as the photo reads them: the halation amount / size and
// the LUT key's token) and FilmSpectral.java (spektrafilm's per-pixel colour on the arrays of assets/film/*.bin). Java semantics:
// float maths in float, the Math.* calls in double, (float) casts where the Java casts, String.format("%.2f") half up.
// The arrays are read from filmAssetsRoot() (the app's assets/film: on iOS the bundle's "film" folder, on the host
// app/src/main/assets/film); PostPipelineRun / the editor derive it from the shaders' folder when it is not set.
#pragma once

#include "../Settings.hpp"

#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

namespace scam_post {

namespace jfmt {
/// String.format(Locale.ROOT, "%.<places>f", double): the double's shortest decimal digits (FloatingDecimal) rounded half up
inline std::string fixed(double d, int places) {
    if (std::isnan(d)) return "NaN";
    if (std::isinf(d)) return d > 0 ? "Infinity" : "-Infinity";
    const bool neg = std::signbit(d);
    const double a = std::fabs(d);
    std::string zero = "0" + (places > 0 ? "." + std::string(size_t(places), '0') : std::string());
    if (a == 0.0) return (neg ? "-" : "") + zero;
    char buf[64];
    int prec = 1;
    for (; prec <= 17; prec++) {
        std::snprintf(buf, sizeof buf, "%.*e", prec - 1, a);
        if (std::strtod(buf, nullptr) == a) break;
    }
    std::snprintf(buf, sizeof buf, "%.*e", std::min(prec, 17) - 1, a);
    const std::string s = buf;
    const size_t e = s.find('e');
    std::string digits;
    for (size_t i = 0; i < e; i++) if (s[i] != '.') digits += s[i];
    const int exp = std::atoi(s.c_str() + e + 1);
    const int keep = exp + 1 + places;
    std::string kept;
    bool up = false;
    if (keep < 0) up = false;
    else if (keep == 0) up = digits[0] >= '5';
    else {
        for (int i = 0; i < keep; i++) kept += i < int(digits.size()) ? digits[size_t(i)] : '0';
        up = keep < int(digits.size()) && digits[size_t(keep)] >= '5';
    }
    std::string n = kept.empty() ? "0" : kept;
    if (up) {
        int i = int(n.size()) - 1;
        while (i >= 0 && n[size_t(i)] == '9') { n[size_t(i)] = '0'; i--; }
        if (i < 0) n.insert(n.begin(), '1');
        else n[size_t(i)]++;
    }
    while (int(n.size()) < places + 1) n.insert(n.begin(), '0');
    size_t lead = 0;
    while (int(lead) + places + 1 < int(n.size()) && n[lead] == '0') lead++;
    n = n.substr(lead);
    const std::string intPart = n.substr(0, n.size() - size_t(places));
    return std::string(neg ? "-" : "") + intPart + (places > 0 ? "." + n.substr(n.size() - size_t(places)) : std::string());
}
/// String.hashCode
inline int32_t hashCode(const std::string& s) {
    uint32_t h = 0;
    for (unsigned char c : s) h = 31u * h + c;
    return int32_t(h);
}
/// Math.abs(int) (MIN_VALUE stays)
inline int32_t absInt(int32_t v) { return v == INT32_MIN ? v : (v < 0 ? -v : v); }
/// String.trim
inline std::string trim(const std::string& s) {
    size_t a = 0, b = s.size();
    while (a < b && static_cast<unsigned char>(s[a]) <= ' ') a++;
    while (b > a && static_cast<unsigned char>(s[b - 1]) <= ' ') b--;
    return s.substr(a, b - a);
}
/// Float.parseFloat of a decimal text (an optional sign, digits, '.', an exponent, a trailing f / F / d / D; "NaN", "Infinity")
inline bool parseFloat(const std::string& in, float& out) {
    std::string s = trim(in);
    if (s.empty()) return false;
    const size_t sign = (s[0] == '+' || s[0] == '-') ? 1 : 0;
    const std::string rest = s.substr(sign);
    if (rest == "NaN") { out = NAN; return true; }
    if (rest == "Infinity") { out = s[0] == '-' ? -INFINITY : INFINITY; return true; }
    std::string body = s;
    const char last = body.back();
    if (last == 'f' || last == 'F' || last == 'd' || last == 'D') body.pop_back();
    if (body.size() <= sign) return false;
    bool digit = false;
    for (size_t i = sign; i < body.size(); i++) {
        const char c = body[i];
        if (std::isdigit(static_cast<unsigned char>(c))) digit = true;
        else if (!(c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-')) return false;
    }
    if (!digit) return false;
    char* end = nullptr;
    const float v = std::strtof(body.c_str(), &end);
    if (end != body.c_str() + body.size()) return false;
    out = v;
    return true;
}
/// String.split(regex) of a literal: trailing empty strings dropped (limit 0) or kept (keepEmpty, limit -1)
inline std::vector<std::string> split(const std::string& s, char sep, bool keepEmpty = false) {
    std::vector<std::string> out;
    std::string cur;
    for (char c : s) {
        if (c == sep) { out.push_back(cur); cur.clear(); }
        else cur += c;
    }
    out.push_back(cur);
    if (!keepEmpty) while (out.size() > 1 && out.back().empty()) out.pop_back();
    if (!keepEmpty && out.size() == 1 && out[0].empty() && !s.empty()) out.clear();
    return out;
}
} // namespace jfmt

/// Where FilmSpectral reads its arrays (the app's assets/film): set by the app / the host tool, else beside the shaders' folder.
inline std::string& filmAssetsRoot() { static std::string root; return root; }

namespace FilmSim {

constexpr const char* STOCK_KEY = "pref_film_sim_stock";
constexpr const char* PRINT_KEY = "pref_film_sim_print";
constexpr const char* FORMAT_KEY = "pref_film_sim_format";
constexpr const char* GRAIN_KEY = "pref_film_sim_grain";
constexpr const char* HALATION_KEY = "pref_film_sim_halation";
constexpr const char* SOFTNESS_KEY = "pref_film_sim_softness";
constexpr const char* EXPOSURE_KEY = "pref_film_sim_exposure";
constexpr const char* PRINT_EV_KEY = "pref_film_sim_print_ev";
constexpr const char* TONE_KEY = "pref_film_sim_tone";
constexpr const char* STRENGTH_KEY = "pref_film_sim_strength";
constexpr const char* PRINT_M_KEY = "pref_film_sim_print_m";
constexpr const char* PRINT_Y_KEY = "pref_film_sim_print_y";
constexpr const char* DIFFUSION_KEY = "pref_film_sim_diffusion";
constexpr const char* DIFFUSION_STRENGTH_KEY = "pref_film_sim_diffusion_strength";
constexpr const char* DIR_KEY = "pref_film_sim_dir";
constexpr float DIFFUSION_DEFAULT = .5f, DIR_DEFAULT = 0.f, LEGACY_DIR_DEFAULT = 1.f;
constexpr const char* DEFAULT_PRINT = "kodak_supra_endura";
constexpr const char* DEFAULT_FORMAT = "135";
enum { NEGATIVE = 0, CINE = 1, SLIDE = 2, BW_NEGATIVE = 3, BW_REVERSAL = 4 };

/// FilmSim.number: Float.parseFloat(v.trim().replace(',', '.')) when finite, else the fallback
inline float number(const std::optional<std::string>& v, float fallback) {
    if (!v) return fallback;
    std::string t = jfmt::trim(*v);
    for (char& c : t) if (c == ',') c = '.';
    float f;
    if (!jfmt::parseFloat(t, f)) return fallback;
    return std::isfinite(f) ? f : fallback;
}
inline float clamp(float v, float lo, float hi) { return std::max(lo, std::min(hi, v)); }

/// Math.round(float) as an int
inline int round(float v) { return int(Settings::javaRound(v)); }

} // namespace FilmSim

// ───── FilmAdvanced (P487): the film page's DIR / halation / development / spectral-model controls

struct FilmAdvanced {
    struct Param { const char* key; float min, max, step, def; bool lut; const char* en; };
    static constexpr int SLIDER_COUNT = 16;
    enum Index { DIR_SPREAD, DIR_TAIL, DIR_TAIL_WEIGHT, DIR_SAME, DIR_INTER, SCATTER, SCATTER_SIZE, HALO, HALO_SIZE, BOOST, BOOST_RANGE,
                 PROTECT, PUSH, DEV_GAMMA, BLEACH, LEUCO };
    static const std::array<Param, SLIDER_COUNT>& sliders() {
        static const std::array<Param, SLIDER_COUNT> s = {{
            {"pref_film_sim_dir_spread", 0, 100, 1, 20, false, "Inhibitor spread"},
            {"pref_film_sim_dir_tail", 0, 1000, 10, 200, false, "Spread tail"},
            {"pref_film_sim_dir_tail_weight", 0, 1, .01f, .06f, false, "Tail weight"},
            {"pref_film_sim_dir_same", 0, 2, .05f, 1, true, "Same-layer inhibition"},
            {"pref_film_sim_dir_inter", 0, 2, .05f, 1, true, "Interlayer inhibition"},
            {"pref_film_sim_scatter", 0, 2, .05f, 1, false, "Scatter amount"},
            {"pref_film_sim_scatter_size", 0, 2, .05f, 1, false, "Scatter size"},
            {"pref_film_sim_halation_amount", 0, 2, .05f, 1, false, "Halation amount"},
            {"pref_film_sim_halation_size", 0, 2, .05f, 1, false, "Halation size"},
            {"pref_film_sim_halation_boost", 0, 3, .1f, 0, false, "Highlight boost"},
            {"pref_film_sim_halation_boost_range", 0, 1, .01f, .3f, false, "Boost range"},
            {"pref_film_sim_halation_protect", 0, 8, .1f, 4, false, "Boost protect"},
            {"pref_film_sim_push", -2, 2, .1f, 0, true, "Push / pull"},
            {"pref_film_sim_dev_gamma", .5f, 2, .05f, 1, true, "Development gamma"},
            {"pref_film_sim_bleach", 0, 1, .05f, 0, true, "Negative bleach bypass"},
            {"pref_film_sim_leuco", 0, 2, .05f, 1, true, "Leuco cyan coupling"},
        }};
        return s;
    }
    static constexpr const char* DIR_GAMMA_KEYS[9] = {"pref_film_sim_dir_gamma_rr", "pref_film_sim_dir_gamma_gg", "pref_film_sim_dir_gamma_bb",
        "pref_film_sim_dir_gamma_rg", "pref_film_sim_dir_gamma_rb", "pref_film_sim_dir_gamma_gr", "pref_film_sim_dir_gamma_gb",
        "pref_film_sim_dir_gamma_br", "pref_film_sim_dir_gamma_bg"};
    static constexpr float DIR_GAMMA_MAX = 1.f;
    static constexpr const char* PUSH_MODE_KEY = "pref_film_sim_push_mode";
    static constexpr const char* SPECTRAL_KEY = "pref_film_sim_spectral";
    static constexpr const char* PUSH_MODES[2] = {"standard", "experimental"};
    static constexpr const char* SPECTRAL_MODELS[3] = {"hanatos2026", "mallett2019", "hanatos2025"};

    std::array<float, SLIDER_COUNT> v{};
    std::array<float, 9> gamma{};
    int pushMode = 0, spectral = 0;

    /// Param.read: the stored value clamped to the range (the default when unset or unreadable)
    static float read(const Param& p, const std::optional<std::string>& stored) {
        return FilmSim::clamp(FilmSim::number(stored, p.def), p.min, p.max);
    }
    FilmAdvanced() : FilmAdvanced(defaults(), nullptr, 0, 0) {}
    FilmAdvanced(const std::array<float, SLIDER_COUNT>& values, const std::array<float, 9>* g, int push, int spec) : v(values) {
        for (size_t i = 0; i < gamma.size(); i++)
            gamma[i] = !g || !std::isfinite((*g)[i]) ? NAN : FilmSim::clamp((*g)[i], 0.f, DIR_GAMMA_MAX);
        pushMode = push == 1 ? 1 : 0;
        spectral = spec >= 0 && spec < 3 ? spec : 0;
    }
    static std::array<float, SLIDER_COUNT> defaults() {
        std::array<float, SLIDER_COUNT> d{};
        for (size_t i = 0; i < d.size(); i++) d[i] = sliders()[i].def;
        return d;
    }
    float get(Index i) const { return v[size_t(i)]; }
    int rgbToRawMethod() const { static const int m[3] = {2, 1, 0}; return m[spectral]; }

    static int choice(const char* const* ids, int n, const std::optional<std::string>& stored) {
        for (int i = 0; i < n; i++) if (stored && *stored == ids[i]) return i;
        return 0;
    }
    /// FilmAdvanced.read of the stored settings (getString(key, null): nullopt when absent)
    template <class Stored>
    static FilmAdvanced read(Stored stored) {
        std::array<float, SLIDER_COUNT> values{};
        for (size_t i = 0; i < values.size(); i++) values[i] = read(sliders()[i], stored(sliders()[i].key));
        std::array<float, 9> g{};
        for (size_t i = 0; i < g.size(); i++) {
            const std::optional<std::string> s = stored(DIR_GAMMA_KEYS[i]);
            g[i] = !s || jfmt::trim(*s).empty() ? NAN : FilmSim::number(s, NAN);
        }
        std::optional<std::string> push = stored(PUSH_MODE_KEY), spec = stored(SPECTRAL_KEY);
        if (!push) push = std::string(PUSH_MODES[0]);
        if (!spec) spec = std::string(SPECTRAL_MODELS[0]);
        return FilmAdvanced(values, &g, choice(PUSH_MODES, 2, push), choice(SPECTRAL_MODELS, 3, spec));
    }
    static std::string fmt(float x) { return jfmt::fixed(double(x), 3); }
    /// the colour LUT's token ("" at the defaults)
    std::string lutToken() const {
        bool any = pushMode != 0 || spectral != 0;
        for (size_t i = 0; i < v.size(); i++) if (sliders()[i].lut && v[i] != sliders()[i].def) any = true;
        for (float g : gamma) if (std::isfinite(g)) any = true;
        if (!any) return "";
        std::string b = "x" + std::to_string(spectral) + "," + std::to_string(pushMode);
        for (size_t i = 0; i < v.size(); i++) if (sliders()[i].lut) b += "," + fmt(v[i]);
        for (float g : gamma) b += "," + (std::isfinite(g) ? fmt(g) : std::string("-"));
        return b;
    }
    static bool isToken(const std::string& field) { return field.rfind("x", 0) == 0 && field.find(',') != std::string::npos && field.find(',') > 0; }
    static FilmAdvanced fromToken(const std::string& token) {
        if (!isToken(token)) return FilmAdvanced();
        const std::vector<std::string> f = jfmt::split(token.substr(1), ',', true);
        int lut = 0;
        for (const Param& p : sliders()) if (p.lut) lut++;
        if (int(f.size()) != 2 + lut + 9) return FilmAdvanced();
        std::array<float, SLIDER_COUNT> values{};
        size_t k = 2;
        for (size_t i = 0; i < values.size(); i++) values[i] = sliders()[i].lut ? read(sliders()[i], f[k++]) : sliders()[i].def;
        std::array<float, 9> g{};
        for (size_t i = 0; i < 9; i++) g[i] = f[k + i] == "-" ? NAN : FilmSim::number(f[k + i], NAN);
        // (int) FilmSim.number(f[1], 0), (int) FilmSim.number(f[0], 0)
        auto toInt = [](float x) { return std::isnan(x) ? 0 : int(x); };
        return FilmAdvanced(values, &g, toInt(FilmSim::number(f[1], 0)), toInt(FilmSim::number(f[0], 0)));
    }
    bool isDefault() const {
        if (!lutToken().empty()) return false;
        for (size_t i = 0; i < v.size(); i++) if (!sliders()[i].lut && v[i] != sliders()[i].def) return false;
        return true;
    }
};

// ───── the stocks, prints and formats

struct FilmStock {
    std::string id, brand, name;
    int type = FilmSim::NEGATIVE, iso = 0;
    std::array<float, 3> peak{}, width{};
    float gamma = 0;
    std::array<float, 3> tilt{};
    float range = 0, toe = 0, shoulder = 0, dir = 0;
    std::array<float, 3> dyePeak{}, dyeWidth{};
    float impurity = 0, warmth = 0;
    float rms = 0, grainUm = 0, mtfUm = 0;
    std::array<float, 3> halation{};
    float halationUm = 0;
    std::string spectral;     // "" = null: our own (black-and-white) model
    std::string targetPrint;  // "" = null
    bool mono() const { return type == FilmSim::BW_NEGATIVE || type == FilmSim::BW_REVERSAL; }
    bool reversal() const { return type == FilmSim::SLIDE || type == FilmSim::BW_REVERSAL; }
};

struct FilmPrint {
    std::string id, brand, name;
    bool film = false;
    std::array<float, 3> sensPeak{}, sensWidth{};
    float gamma = 0, dmax = 0, mid = 0, toe = 0, shoulder = 0;
    std::array<float, 3> dyePeak{}, dyeWidth{};
    float impurity = 0;
};

struct FilmFormat {
    std::string id, name;
    float longMm = 0;
};

namespace FilmSim {

inline FilmStock reference(const char* id, const char* brand, const char* name, int type, int iso, const char* target, float rms,
                           float grainUm, float mtfUm, std::array<float, 3> halation, float halationUm) {
    FilmStock s;
    s.id = s.spectral = id;
    s.targetPrint = target ? target : "";
    s.brand = brand; s.name = name; s.type = type; s.iso = iso; s.rms = rms; s.grainUm = grainUm; s.mtfUm = mtfUm;
    s.halation = halation; s.halationUm = halationUm;
    return s;
}
inline FilmStock classic(const char* id, const char* brand, const char* name, int type, int iso, std::array<float, 3> peak,
                         std::array<float, 3> width, float gamma, std::array<float, 3> tilt, float range, float toe, float shoulder, float dir,
                         std::array<float, 3> dyePeak, std::array<float, 3> dyeWidth, float impurity, float warmth, float rms, float grainUm,
                         float mtfUm, std::array<float, 3> halation, float halationUm) {
    FilmStock s;
    s.id = id; s.brand = brand; s.name = name; s.type = type; s.iso = iso; s.peak = peak; s.width = width; s.gamma = gamma; s.tilt = tilt;
    s.range = range; s.toe = toe; s.shoulder = shoulder; s.dir = dir; s.dyePeak = dyePeak; s.dyeWidth = dyeWidth; s.impurity = impurity;
    s.warmth = warmth; s.rms = rms; s.grainUm = grainUm; s.mtfUm = mtfUm; s.halation = halation; s.halationUm = halationUm;
    return s;
}

inline const std::vector<FilmStock>& STOCKS() {
    static const std::array<float, 3> WEAK_AH = {.08f, .02f, 0.f}, STRONG_AH = {.015f, .005f, 0.f}, BW_AH = {.05f, .05f, .05f};
    static const std::vector<FilmStock> s = {
        reference("kodak_portra_160", "Kodak", "Portra 160", NEGATIVE, 160, "kodak_portra_endura", 4.0f, 2.6f, 4.2f, STRONG_AH, 65),
        reference("kodak_portra_400", "Kodak", "Portra 400", NEGATIVE, 400, "kodak_portra_endura", 5.0f, 3.0f, 4.8f, STRONG_AH, 65),
        reference("kodak_portra_800", "Kodak", "Portra 800", NEGATIVE, 800, "kodak_portra_endura", 7.0f, 3.6f, 5.6f, STRONG_AH, 65),
        reference("kodak_portra_800_push1", "Kodak", "Portra 800 (Push 1)", NEGATIVE, 1600, "kodak_portra_endura", 8.5f, 4.0f, 6.0f, STRONG_AH, 65),
        reference("kodak_portra_800_push2", "Kodak", "Portra 800 (Push 2)", NEGATIVE, 3200, "kodak_portra_endura", 10.0f, 4.4f, 6.4f, STRONG_AH, 65),
        reference("kodak_gold_200", "Kodak", "Gold 200", NEGATIVE, 200, "kodak_portra_endura", 5.0f, 3.0f, 4.8f, WEAK_AH, 65),
        reference("kodak_ultramax_400", "Kodak", "Ultramax 400", NEGATIVE, 400, "kodak_portra_endura", 6.0f, 3.4f, 5.2f, WEAK_AH, 65),
        reference("kodak_ektar_100", "Kodak", "Ektar 100", NEGATIVE, 100, "kodak_portra_endura", 3.0f, 2.2f, 3.6f, STRONG_AH, 65),
        reference("fujifilm_c200", "Fujifilm", "C200", NEGATIVE, 200, "fujifilm_crystal_archive_typeii", 5.0f, 3.0f, 4.8f, WEAK_AH, 65),
        reference("fujifilm_xtra_400", "Fujifilm", "X-Tra 400", NEGATIVE, 400, "fujifilm_crystal_archive_typeii", 6.0f, 3.4f, 5.2f, WEAK_AH, 65),
        reference("fujifilm_pro_400h", "Fujifilm", "Pro 400H", NEGATIVE, 400, "fujifilm_crystal_archive_typeii", 5.0f, 3.2f, 5.0f, STRONG_AH, 65),
        reference("kodak_vision3_50d", "Kodak", "Vision3 50D", CINE, 50, "kodak_2383", 4.0f, 2.4f, 3.8f, STRONG_AH, 50),
        reference("kodak_vision3_200t", "Kodak", "Vision3 200T", CINE, 200, "kodak_2383", 5.0f, 2.8f, 4.4f, STRONG_AH, 50),
        reference("kodak_vision3_250d", "Kodak", "Vision3 250D", CINE, 250, "kodak_2383", 5.0f, 2.8f, 4.4f, STRONG_AH, 50),
        reference("kodak_vision3_500t", "Kodak", "Vision3 500T", CINE, 500, "kodak_2383", 7.0f, 3.4f, 5.2f, STRONG_AH, 50),
        reference("kodak_verita_200d", "Kodak", "Verita 200D", CINE, 200, "kodak_2383", 5.0f, 2.8f, 4.4f, STRONG_AH, 50),
        reference("kodak_ektachrome_100", "Kodak", "Ektachrome 100", SLIDE, 100, nullptr, 8.0f, 2.4f, 4.0f, STRONG_AH, 65),
        reference("fujifilm_provia_100f", "Fujifilm", "Provia 100F", SLIDE, 100, nullptr, 8.0f, 2.3f, 3.8f, STRONG_AH, 65),
        reference("fujifilm_velvia_100", "Fujifilm", "Velvia 100", SLIDE, 100, nullptr, 9.0f, 2.2f, 3.6f, STRONG_AH, 65),
        reference("kodak_kodachrome_64", "Kodak", "Kodachrome 64", SLIDE, 64, nullptr, 9.0f, 2.4f, 3.8f, WEAK_AH, 65),
        classic("trix400", "Kodak", "Tri-X 400", BW_NEGATIVE, 400, {560, 560, 560}, {110, 110, 110}, .65f, {1, 1, 1}, 2.3f, .30f, .40f, 0.f,
                {0, 0, 0}, {0, 0, 0}, 0.f, 0.f, 17.0f, 6.0f, 6.0f, BW_AH, 65),
        classic("tmax100", "Kodak", "T-Max 100", BW_NEGATIVE, 100, {555, 555, 555}, {105, 105, 105}, .62f, {1, 1, 1}, 2.4f, .26f, .40f, 0.f,
                {0, 0, 0}, {0, 0, 0}, 0.f, 0.f, 8.0f, 3.0f, 3.6f, BW_AH, 65),
        classic("hp5", "Ilford", "HP5 Plus", BW_NEGATIVE, 400, {565, 565, 565}, {115, 115, 115}, .62f, {1, 1, 1}, 2.3f, .32f, .42f, 0.f,
                {0, 0, 0}, {0, 0, 0}, 0.f, 0.f, 16.0f, 5.6f, 5.8f, BW_AH, 65),
        classic("trix7266", "Kodak", "Tri-X 7266", BW_REVERSAL, 200, {560, 560, 560}, {110, 110, 110}, 1.75f, {1, 1, 1}, 3.0f, .24f, .30f, 0.f,
                {0, 0, 0}, {0, 0, 0}, 0.f, 0.f, 14.0f, 5.0f, 5.2f, BW_AH, 50),
    };
    return s;
}

inline FilmPrint referencePrint(const char* id, const char* brand, const char* name, bool film) {
    FilmPrint p;
    p.id = id; p.brand = brand; p.name = name; p.film = film;
    return p;
}
inline const std::vector<FilmPrint>& PRINTS() {
    static const std::vector<FilmPrint> p = {
        referencePrint("kodak_portra_endura", "Kodak", "Portra Endura", false),
        referencePrint("kodak_endura_premier", "Kodak", "Endura Premier", false),
        referencePrint("kodak_ultra_endura", "Kodak", "Ultra Endura", false),
        referencePrint("kodak_supra_endura", "Kodak", "Supra Endura", false),
        referencePrint("kodak_ektacolor_edge", "Kodak", "Ektacolor Edge", false),
        referencePrint("fujifilm_crystal_archive_typeii", "Fujifilm", "Crystal Archive Type II", false),
        referencePrint("kodak_2383", "Kodak", "Vision 2383", true),
        referencePrint("kodak_2393", "Kodak", "Vision Premier 2393", true),
    };
    return p;
}
/// The paper our black-and-white model prints on
inline const FilmPrint& MONO_PAPER() {
    static const FilmPrint p = [] {
        FilmPrint q;
        q.id = "endura_premier"; q.brand = "Kodak"; q.name = "Endura Premier"; q.film = false;
        q.sensPeak = {692, 545, 450}; q.sensWidth = {18, 20, 22};
        q.gamma = 2.75f; q.dmax = 2.35f; q.mid = .80f; q.toe = .22f; q.shoulder = .30f;
        q.dyePeak = {655, 546, 446}; q.dyeWidth = {42, 34, 29}; q.impurity = 1.f;
        return q;
    }();
    return p;
}
inline const std::vector<FilmFormat>& FORMATS() {
    static const std::vector<FilmFormat> f = {
        {"off", "", 0.f}, {"8mm", "8 mm", 4.8f}, {"super8", "Super 8", 5.79f}, {"16mm", "16 mm", 10.26f}, {"super16", "Super 16", 12.52f},
        {"super35", "Super 35", 24.89f}, {"135", "35 mm", 36.f}, {"65mm", "65 mm", 52.48f}, {"645", "645", 56.f},
        {"67", "6\xC3\x97" "7", 70.f}, {"imax70", "IMAX 70", 70.41f},
    };
    return f;
}
/// the film's diffusions offered, in spektrafilm's order ("" none; Pro-Mist is stored as "pearl")
inline const std::vector<std::string>& DIFFUSIONS() {
    static const std::vector<std::string> d = {"", "glimmer", "bpm", "pearl", "cinebloom"};
    return d;
}

inline std::string currentId(const std::string& id) {
    static const char* OLD[][2] = {{"portra160", "kodak_portra_160"}, {"portra400", "kodak_portra_400"}, {"portra800", "kodak_portra_800"},
        {"gold200", "kodak_gold_200"}, {"ultramax400", "kodak_ultramax_400"}, {"ektar100", "kodak_ektar_100"}, {"c200", "fujifilm_c200"},
        {"xtra400", "fujifilm_xtra_400"}, {"pro400h", "fujifilm_pro_400h"}, {"vision50d", "kodak_vision3_50d"},
        {"vision250d", "kodak_vision3_250d"}, {"vision500t", "kodak_vision3_500t"}, {"ektachrome100", "kodak_ektachrome_100"},
        {"velvia50", "fujifilm_velvia_100"}, {"provia100f", "fujifilm_provia_100f"}, {"kodachrome64", "kodak_kodachrome_64"},
        {"endura_premier", "kodak_endura_premier"}, {"ultra_endura", "kodak_ultra_endura"}, {"ektacolor_edge", "kodak_ektacolor_edge"},
        {"portra_endura", "kodak_portra_endura"}, {"crystal_archive", "fujifilm_crystal_archive_typeii"}, {"vision2383", "kodak_2383"},
        {"vision2393", "kodak_2393"}};
    for (const auto& o : OLD) if (id == o[0]) return o[1];
    return id;
}
inline const FilmStock* stock(const std::optional<std::string>& in) {
    if (!in) return nullptr;
    const std::string id = currentId(*in);
    for (const FilmStock& s : STOCKS()) if (s.id == id) return &s;
    return nullptr;
}
inline const FilmPrint& print(const std::optional<std::string>& in) {
    if (in) {
        const std::string id = currentId(*in);
        for (const FilmPrint& p : PRINTS()) if (p.id == id) return p;
    }
    return PRINTS()[0];
}
/// An explicit paper is preserved; an unset measured recipe starts with the reference app's common paper
inline const FilmPrint& print(const std::optional<std::string>& id, const FilmStock* s) {
    if ((!id || id->empty()) && s && !s->spectral.empty()) return print(std::string(DEFAULT_PRINT));
    if ((!id || id->empty()) && s && !s->targetPrint.empty()) return print(s->targetPrint);
    return print(id);
}
inline const FilmFormat& format(const std::optional<std::string>& id) {
    if (id) for (const FilmFormat& f : FORMATS()) if (f.id == *id) return f;
    for (const FilmFormat& f : FORMATS()) if (f.id == DEFAULT_FORMAT) return f;
    throw std::runtime_error("no default format");
}

constexpr float GRAIN_BLUR_UM = 11.8f; // FilmSpectral.GRAIN_BLUR_UM
inline float grainBlurScale(float longMm) { return float(std::pow(std::max(double(longMm) / 35.0, 1e-6), 0.62)); }
inline float grainBlurUm(const FilmFormat& f) { return GRAIN_BLUR_UM * grainBlurScale(f.longMm); }

inline int defaultPercent(const std::string& key) {
    if (key == GRAIN_KEY || key == HALATION_KEY || key == SOFTNESS_KEY) return 0;
    if (key == DIR_KEY) return round(100 * DIR_DEFAULT);
    throw std::invalid_argument("Not a film effect percentage");
}
inline float effect(const std::string& key, const std::optional<std::string>& stored, float max) {
    return clamp(number(stored, float(defaultPercent(key))) / 100.f, 0.f, max);
}
inline float couplers(const std::optional<std::string>& stored) { return effect(DIR_KEY, stored, 1.f); }
inline float diffusionGrade(const std::optional<std::string>& stored) { return clamp(number(stored, DIFFUSION_DEFAULT * 100.f) / 100.f, 0.f, 2.f); }

} // namespace FilmSim

/// LensFilters.index for the film's diffusion ids (the offered filters; the lens filters' own table is color/LensFilters.hpp)
inline int filmDiffusionIndex(const std::string& id) {
    if (id.empty()) return 0;
    static const char* offered[] = {"glimmer", "bpm", "pearl", "cinebloom"};
    static const char* numbering[] = {"bpm", "cinebloom", "glimmer", "pearl", "fog", "dfog", "gnd", "lightpol", "streak", "prism", "kaleido", "ir"};
    bool ok = false;
    for (const char* o : offered) ok |= id == o;
    if (!ok) return -1;
    for (int i = 0; i < 12; i++) if (id == numbering[i]) return i + 1;
    return -1;
}

/// FilmSim.Settings: the simulation of a shot (or of the editor's state)
struct FilmSettings {
    const FilmStock* stock = nullptr;
    const FilmPrint* print = nullptr;
    const FilmFormat* format = nullptr;
    float grain = 0, halation = 0, softness = 0, exposure = 0, printEv = 0, tone = 0, strength = 1;
    float printM = 0, printY = 0;
    std::string diffusion;
    float diffusionGrade = FilmSim::DIFFUSION_DEFAULT, couplers = FilmSim::LEGACY_DIR_DEFAULT;
    FilmAdvanced advanced;

    FilmSettings() = default;
    FilmSettings(const FilmStock& s, const FilmPrint& p, const FilmFormat& f, float grain_, float halation_, float softness_, float exposure_,
                 float printEv_, float tone_, float printM_, float printY_, float strength_, const std::string& diffusion_, float grade,
                 float couplers_, const FilmAdvanced& adv = FilmAdvanced())
        : stock(&s), print(&p), format(&f), grain(grain_), halation(halation_), softness(softness_), exposure(exposure_), printEv(printEv_),
          tone(tone_), strength(strength_), printM(printM_), printY(printY_),
          diffusion(filmDiffusionIndex(diffusion_) <= 0 ? std::string() : diffusion_), diffusionGrade(grade), couplers(couplers_), advanced(adv) {}

    static std::string fmt(float v) { return jfmt::fixed(double(v), 2); }
    /// What the LUT depends on
    std::string lutKey() const {
        const std::string token = advanced.lutToken();
        return stock->id + "|" + (stock->reversal() ? std::string("-") : print->id) + "|" + fmt(exposure) + "|" + fmt(printEv) + "|" + fmt(tone)
               + "|" + fmt(printM) + "|" + fmt(printY) + "|" + fmt(couplers) + (token.empty() ? "" : "|" + token);
    }
    /// «Сила симуляции» on each layer's halation amount
    std::array<float, 3> halationAmounts() const {
        std::array<float, 3> a{};
        for (size_t c = 0; c < 3; c++) a[c] = stock->halation[c] * halation * advanced.get(FilmAdvanced::HALO) * strength;
        return a;
    }
    float halationUm() const { return stock->halationUm * advanced.get(FilmAdvanced::HALO_SIZE); }
    float softnessApplied() const { return softness * strength; }
    float grainApplied() const { return grain * strength; }
    float pixelUm(int longPx) const { return format->longMm <= 0 || longPx <= 0 ? 0.f : format->longMm * 1000.f / float(longPx); }
    /// the frame (um) the film's diffusion is sized on (DiffusionModel.FRAME_UM without a format)
    double diffusionFrameUm() const { return format->longMm > 0 ? double(format->longMm) * 1000.0 : 36000.0; }
    std::string recipe() const {
        char buf[512];
        std::snprintf(buf, sizeof buf, "%s %s; print %s; format %s; diffusion %s; grain %d %%; halation %d %%; softness %d %%; DIR couplers %d %%;",
                      stock->brand.c_str(), stock->name.c_str(), stock->reversal() ? "none (projected)" : (print->brand + " " + print->name).c_str(),
                      format->longMm <= 0 ? "off" : format->name.c_str(), diffusion.empty() ? "none" : (diffusion + " " + jfmt::fixed(diffusionGrade, 2)).c_str(),
                      FilmSim::round(grain * 100), FilmSim::round(halation * 100), FilmSim::round(softness * 100), FilmSim::round(couplers * 100));
        return std::string(buf) + " film exposure " + (exposure >= 0 ? "+" : "") + jfmt::fixed(exposure, 1) + " EV; print exposure "
               + (printEv >= 0 ? "+" : "") + jfmt::fixed(printEv, 2) + " EV; contrast " + std::to_string(FilmSim::round(tone * 100))
               + "; enlarger M " + std::to_string(FilmSim::round(printM)) + " Y " + std::to_string(FilmSim::round(printY)) + " CC; strength "
               + std::to_string(FilmSim::round(strength * 100)) + " %";
    }
    /// FilmSim.Settings.forLut: the settings a lutKey stands for at `strength` (null for a key of no known stock)
    static std::optional<FilmSettings> forLut(const std::string& key, float strength) {
        const std::vector<std::string> p = jfmt::split(key, '|', true);
        if (p.size() < 7) return std::nullopt;
        const FilmStock* s = FilmSim::stock(p[0]);
        if (!s) return std::nullopt;
        const float couplers = p.size() >= 8 ? FilmSim::clamp(FilmSim::number(p[7], FilmSim::LEGACY_DIR_DEFAULT), 0.f, 1.f) : FilmSim::LEGACY_DIR_DEFAULT;
        const FilmAdvanced advanced = p.size() >= 9 ? FilmAdvanced::fromToken(p[8]) : FilmAdvanced();
        return FilmSettings(*s, FilmSim::print(p[1] == "-" ? std::string() : p[1], s), FilmSim::FORMATS()[0], 0.f, 0.f, 0.f,
                            FilmSim::number(p[2], 0.f), FilmSim::number(p[3], 0.f), FilmSim::number(p[4], 0.f), FilmSim::number(p[5], 0.f),
                            FilmSim::number(p[6], 0.f), strength, "", FilmSim::DIFFUSION_DEFAULT, couplers, advanced);
    }
};

namespace FilmSim {

/// FilmSim.current: the settings now (null when no stock is chosen or at strength 0)
inline std::shared_ptr<const FilmSettings> current(const Settings& m) {
    try {
        auto str = [&](const char* key) -> std::optional<std::string> {
            if (!m.contains(key)) return std::nullopt;
            return m.getString(key, "");
        };
        auto strOr = [&](const char* key, const char* fallback) { return std::optional<std::string>(m.getString(key, fallback)); };
        const FilmStock* s = stock(strOr(STOCK_KEY, ""));
        if (!s) return nullptr;
        const float strength = clamp(number(strOr(STRENGTH_KEY, "100"), 100.f) / 100.f, 0.f, 1.f);
        if (strength <= 0.f) return nullptr;
        return std::make_shared<FilmSettings>(*s, print(strOr(PRINT_KEY, ""), s), format(strOr(FORMAT_KEY, DEFAULT_FORMAT)),
            effect(GRAIN_KEY, strOr(GRAIN_KEY, ""), 2.f), effect(HALATION_KEY, strOr(HALATION_KEY, ""), 4.f),
            effect(SOFTNESS_KEY, strOr(SOFTNESS_KEY, ""), 3.f), clamp(number(strOr(EXPOSURE_KEY, "0"), 0.f), -3.f, 3.f),
            clamp(number(strOr(PRINT_EV_KEY, "0"), 0.f), -2.f, 2.f), clamp(number(strOr(TONE_KEY, "0"), 0.f) / 100.f, -1.f, 1.f),
            clamp(number(strOr(PRINT_M_KEY, "0"), 0.f), -50.f, 50.f), clamp(number(strOr(PRINT_Y_KEY, "0"), 0.f), -50.f, 50.f), strength,
            m.getString(DIFFUSION_KEY, ""), diffusionGrade(strOr(DIFFUSION_STRENGTH_KEY, "50")), couplers(strOr(DIR_KEY, "")),
            FilmAdvanced::read([&](const char* k) { return str(k); }));
    } catch (const std::exception&) {
        return nullptr;
    }
}

// ───── our black-and-white model (FilmSim.Model)

constexpr int BINS = 31;
constexpr double SEPARATION = .35, NEGATIVE_MID = 1.0, PAPER_DMIN = .08, SHADOW_STOPS = .62;

inline double lobe(double l, double mu, double s1, double s2) {
    const double t = (l - mu) / (l < mu ? s1 : s2);
    return std::exp(-.5 * t * t);
}
inline double gauss(double l, double mu, double s) {
    const double t = (l - mu) / s;
    return std::exp(-.5 * t * t);
}
inline double planck(double nm, double k) {
    const double m = nm * 1e-9;
    return 1.0 / (std::pow(m, 5) * (std::exp(1.4387769e-2 / (m * k)) - 1)) * 1e-30;
}
inline double softplus10(double u) { return u > 30 ? u : std::log10(1 + std::pow(10, u)); }

struct Spectra {
    float LAMBDA[BINS], PRIMARY[3][BINS], VIEW[3][BINS], LAMP[BINS];
    Spectra() {
        for (int i = 0; i < BINS; i++) LAMBDA[i] = float(400 + 10 * i);
        const double xyzToRgb[3][3] = {{3.2404542, -1.5371385, -0.4985314}, {-0.9692660, 1.8760108, 0.0415560}, {0.0556434, -0.2040259, 1.0572252}};
        for (int i = 0; i < BINS; i++) {
            const double l = LAMBDA[i];
            const double b = 1 / (1 + std::exp((l - 492) / 11.0)), r = 1 / (1 + std::exp(-(l - 584) / 11.0));
            PRIMARY[0][i] = float(r);
            PRIMARY[2][i] = float(b);
            PRIMARY[1][i] = float(std::max(0.0, 1 - r - b));
            const double ill = planck(l, 6504);
            const double x = 1.056 * lobe(l, 599.8, 37.9, 31.0) + 0.362 * lobe(l, 442.0, 16.0, 26.7) - 0.065 * lobe(l, 501.1, 20.4, 26.2);
            const double y = 0.821 * lobe(l, 568.8, 46.9, 40.5) + 0.286 * lobe(l, 530.9, 16.3, 31.1);
            const double z = 1.217 * lobe(l, 437.0, 11.8, 36.0) + 0.681 * lobe(l, 459.0, 26.0, 13.8);
            for (int c = 0; c < 3; c++) VIEW[c][i] = float(ill * (xyzToRgb[c][0] * x + xyzToRgb[c][1] * y + xyzToRgb[c][2] * z));
            LAMP[i] = float(planck(l, 3200) / (1 + std::exp((l - 680) / 14.0)));
        }
    }
};
inline const Spectra& spectra() { static const Spectra s; return s; }

struct Curve {
    double g = 0, range = 0, toe = 0, shoulder = 0, x0 = 0;
    Curve() = default;
    Curve(double g_, double range_, double toe_, double shoulder_, double mid) : g(g_), range(range_), toe(toe_), shoulder(shoulder_) {
        double lo = -6, hi = 6;
        for (int i = 0; i < 60; i++) {
            const double m = (lo + hi) / 2;
            if (raw(0, m) > mid) lo = m; else hi = m;
        }
        x0 = (lo + hi) / 2;
    }
    double raw(double x, double origin) const {
        const double r = range / g;
        return g * toe * softplus10((x - origin) / toe) - g * shoulder * softplus10((x - origin - r) / shoulder);
    }
    double at(double x) const { return std::max(0.0, std::min(range, raw(x, x0))); }
};

struct Model {
    static constexpr int GREY = 48;
    static constexpr double GREY_LO = -8, GREY_HI = 5;
    const FilmStock& s;
    const FilmPrint& p;
    bool mono, reversal;
    float crosstalk[3][3] = {};
    Curve film[3];
    float dye[3][BINS] = {}, base[BINS] = {}, paperSens[3][BINS] = {}, paperDye[3][BINS] = {};
    Curve paper[3];
    float balance[3] = {}, white[3] = {};
    double exposure;
    float grey[GREY][3] = {};

    static float luma(const float* o) { return .2126f * o[0] + .7152f * o[1] + .0722f * o[2]; }

    Model(const FilmStock& st, const FilmPrint& pr, float exposureEv, float printEv, float tone) : s(st), p(pr) {
        const Spectra& S = spectra();
        mono = s.mono();
        reversal = s.reversal();
        exposure = double(exposureEv) * std::log10(2);
        for (int i = 0; i < 3; i++) {
            double sum = 0;
            double acc[3] = {0, 0, 0};
            for (int k = 0; k < BINS; k++) {
                const double sens = gauss(S.LAMBDA[k], s.peak[size_t(i)], s.width[size_t(i)]);
                sum += sens;
                for (int j = 0; j < 3; j++) acc[j] += sens * S.PRIMARY[j][k];
            }
            for (int j = 0; j < 3; j++) crosstalk[i][j] = float(SEPARATION * acc[j] / sum + (i == j ? 1 - SEPARATION : 0));
        }
        const double mid = reversal ? double(s.range) - .75 : NEGATIVE_MID;
        for (int i = 0; i < 3; i++) film[i] = Curve(double(s.gamma * s.tilt[size_t(i)]), s.range, s.toe, s.shoulder, mid);
        for (int k = 0; k < BINS; k++) {
            const double l = S.LAMBDA[k];
            if (mono) {
                dye[0][k] = 1.f;
                base[k] = .18f;
                continue;
            }
            const double imp = s.impurity;
            dye[0][k] = float(gauss(l, s.dyePeak[0], s.dyeWidth[0]) + imp * (.07 * gauss(l, 545, 40) + .04 * gauss(l, 450, 35)));
            dye[1][k] = float(gauss(l, s.dyePeak[1], s.dyeWidth[1]) + imp * (.12 * gauss(l, 440, 30) + .03 * gauss(l, 630, 40)));
            dye[2][k] = float(gauss(l, s.dyePeak[2], s.dyeWidth[2]) + imp * .03 * gauss(l, 545, 40));
            base[k] = float(s.type == NEGATIVE || s.type == CINE ? .55 * gauss(l, 440, 45) + .28 * gauss(l, 520, 40) + .12 : .06);
        }
        for (int k = 0; k < BINS; k++) {
            const double l = S.LAMBDA[k];
            for (int c = 0; c < 3; c++) paperSens[c][k] = float(gauss(l, p.sensPeak[size_t(c)], p.sensWidth[size_t(c)]));
            if (mono) {
                paperDye[0][k] = 1.f;
                continue;
            }
            paperDye[0][k] = float(gauss(l, p.dyePeak[0], p.dyeWidth[0]) + double(p.impurity) * .06 * gauss(l, 550, 40));
            paperDye[1][k] = float(gauss(l, p.dyePeak[1], p.dyeWidth[1]) + double(p.impurity) * (.10 * gauss(l, 445, 30) + .02 * gauss(l, 640, 40)));
            paperDye[2][k] = float(gauss(l, p.dyePeak[2], p.dyeWidth[2]) + double(p.impurity) * .02 * gauss(l, 545, 40));
        }
        for (int c = 0; c < 3; c++) paper[c] = Curve(p.gamma, double(p.dmax) - PAPER_DMIN, p.toe, p.shoulder, double(p.mid) - PAPER_DMIN);
        const double neutral[3] = {.18, .18, .18};
        std::array<float, BINS> t = transmittance(filmDensity(neutral, reversal ? exposure : 0));
        for (int c = 0; c < 3; c++) {
            double q = 0;
            for (int k = 0; k < BINS; k++) q += S.LAMP[k] * t[size_t(k)] * (mono ? paperSens[0][k] + paperSens[1][k] + paperSens[2][k] : paperSens[c][k]);
            balance[c] = float(1 / std::max(q, 1e-30));
        }
        const double ev = std::pow(2, double(printEv));
        balance[0] *= float(ev * std::pow(10, mono ? 0 : -.035 * double(tone)));
        balance[1] *= float(ev * std::pow(10, mono ? 0 : .010 * double(tone)));
        balance[2] *= float(ev * std::pow(10, mono ? 0 : .045 * double(tone)));
        const double zero[3] = {0, 0, 0}, dmin[3] = {PAPER_DMIN, PAPER_DMIN, PAPER_DMIN};
        const std::array<float, 3> w = reversal ? light(transmittance(std::array<double, 3>{zero[0], zero[1], zero[2]}))
                                                : light(reflectance(std::array<double, 3>{dmin[0], dmin[1], dmin[2]}));
        for (int c = 0; c < 3; c++) white[c] = std::max(1e-9f, w[size_t(c)]);
        for (int i = 0; i < GREY; i++) {
            const double e = .18 * std::pow(2, GREY_LO + (GREY_HI - GREY_LO) * i / (GREY - 1));
            const double rgb[3] = {e, e, e};
            const std::array<float, 3> o = raw(rgb);
            const float y = luma(o.data());
            for (int c = 0; c < 3; c++) grey[i][c] = o[size_t(c)] > 1e-7f ? y / o[size_t(c)] : 1.f;
        }
        if (!mono && tone != 0) {
            const std::array<float, 3> o = raw(neutral);
            const float y = luma(o.data());
            for (int i = 0; i < GREY; i++) for (int c = 0; c < 3; c++) grey[i][c] *= o[size_t(c)] > 1e-7f ? o[size_t(c)] / y : 1.f;
        }
    }

    std::array<double, 3> filmDensity(const double* rgb) const { return filmDensity(rgb, exposure); }
    std::array<double, 3> filmDensity(const double* rgb, double ev) const {
        if (mono) {
            double e = 0;
            for (int j = 0; j < 3; j++) e += (j == 0 ? .30 : j == 1 ? .55 : .15) * rgb[j];
            const double d = film[0].at(std::log10(std::max(e, 1e-7) / .18) + ev);
            return {reversal ? double(s.range) - d : d, 0, 0};
        }
        const double warm = double(s.warmth) * std::log10(2) * .5;
        std::array<double, 3> d{};
        for (int i = 0; i < 3; i++) {
            double e = 0;
            for (int j = 0; j < 3; j++) e += crosstalk[i][j] * rgb[j];
            d[size_t(i)] = film[i].at(std::log10(std::max(e, 1e-7) / .18) + ev + (i == 0 ? warm : i == 2 ? -warm : 0));
        }
        double n[3];
        for (int i = 0; i < 3; i++) n[i] = d[size_t(i)] / film[i].g;
        for (int i = 0; i < 3; i++) {
            double push = 0;
            for (int k = 0; k < 3; k++) if (k != i) push += (n[i] - n[k]) * .5;
            d[size_t(i)] = std::max(0.0, std::min(double(s.range) + .2, (n[i] + double(s.dir) * push) * film[i].g));
        }
        if (reversal) for (int i = 0; i < 3; i++) d[size_t(i)] = std::max(0.0, double(s.range) - d[size_t(i)]);
        return d;
    }
    std::array<float, BINS> transmittance(const std::array<double, 3>& d) const {
        std::array<float, BINS> t{};
        for (int k = 0; k < BINS; k++) {
            double dl = base[k];
            for (int c = 0; c < 3; c++) dl += d[size_t(c)] * dye[c][k];
            t[size_t(k)] = float(std::pow(10, -dl));
        }
        return t;
    }
    std::array<float, BINS> reflectance(const std::array<double, 3>& dp) const {
        std::array<float, BINS> r{};
        for (int k = 0; k < BINS; k++) {
            double dl = .03;
            for (int c = 0; c < 3; c++) dl += dp[size_t(c)] * paperDye[c][k];
            r[size_t(k)] = float(std::pow(10, -dl));
        }
        return r;
    }
    static std::array<float, 3> light(const std::array<float, BINS>& spectrum) {
        const Spectra& S = spectra();
        std::array<float, 3> o{};
        for (int c = 0; c < 3; c++) {
            double v = 0;
            for (int k = 0; k < BINS; k++) v += spectrum[size_t(k)] * S.VIEW[c][k];
            o[size_t(c)] = float(v);
        }
        return o;
    }
    std::array<float, 3> raw(const double* rgb) const {
        const Spectra& S = spectra();
        const std::array<double, 3> d = filmDensity(rgb);
        const std::array<float, BINS> t = transmittance(d);
        std::array<float, 3> seen;
        if (reversal) seen = light(t);
        else {
            std::array<double, 3> dp{0, 0, 0};
            for (int c = 0; c < 3; c++) {
                if (mono && c > 0) break;
                double q = 0;
                for (int k = 0; k < BINS; k++) q += S.LAMP[k] * t[size_t(k)] * (mono ? paperSens[0][k] + paperSens[1][k] + paperSens[2][k] : paperSens[c][k]);
                dp[size_t(c)] = paper[c].at(std::log10(std::max(q * double(balance[c]), 1e-12))) + PAPER_DMIN;
            }
            seen = light(reflectance(dp));
        }
        std::array<float, 3> o{};
        for (int c = 0; c < 3; c++) o[size_t(c)] = std::max(0.f, seen[size_t(c)] / white[c] * .97f);
        if (mono) o[0] = o[1] = o[2] = luma(o.data());
        return o;
    }
    std::array<float, 3> render(const double* rgb) const {
        std::array<float, 3> o = raw(rgb);
        const double e = std::max(1e-9, .2126 * rgb[0] + .7152 * rgb[1] + .0722 * rgb[2]);
        const double pos = (std::log(e / .18) / std::log(2) - GREY_LO) / (GREY_HI - GREY_LO) * (GREY - 1);
        const int i = int(std::max(0.0, std::min(double(GREY - 2), std::floor(pos))));
        const float f = float(std::max(0.0, std::min(1.0, pos - i)));
        for (int c = 0; c < 3; c++) o[size_t(c)] *= grey[i][c] * (1 - f) + grey[i + 1][c] * f;
        return o;
    }
};

/// display colour back to scene light
inline double scene(double v) {
    const double y = v <= 0.04045 ? v / 12.92 : std::pow((v + 0.055) / 1.055, 2.4);
    if (y < .18) return y <= 0 ? 0 : .18 * std::pow(y / .18, SHADOW_STOPS);
    const double c = .9;
    return y * (1 - c * .18) / std::max(1e-4, 1 - c * std::min(y, 1.0));
}
inline float encode(double y) {
    y = std::max(0.0, std::min(1.0, y));
    return float(y <= 0.0031308 ? 12.92 * y : 1.055 * std::pow(y, 1 / 2.4) - 0.055);
}
inline double gamma(float tone) { return std::pow(2, double(tone) * .5); }

constexpr float FILM_GREY = .184f;
constexpr float LOG_LO = -10.f, LOG_SPAN = 20.f;
constexpr int SCENE_SIZE = 41;
constexpr float METER_TO_FILM = .184f / .15f;
constexpr float SELWYN = 42.5f, GRAIN_SCALE = .35f, CLASSIC_BLUR_MIN_PX = .33f;

inline float grainSigma(float rms, float grainUm, float pixelUm) { return rms / 1000.f * SELWYN / std::max(1.f, std::max(pixelUm, grainUm)); }
inline float grainThrough(const FilmSettings& set) { return set.stock->reversal() ? 1.f : set.print->film ? 3.0f : 2.7f; }
inline float classicGrainSigma(const FilmSettings& set, float um) {
    return grainSigma(set.stock->rms, set.stock->grainUm, um) * grainThrough(set) * GRAIN_SCALE * set.grainApplied();
}
inline float classicGrainCell(const FilmSettings& set, float um) { return std::max(1.f, set.stock->grainUm / um); }
inline float classicBlurPx(const FilmSettings& set, float um) { return set.stock->mtfUm / um * set.softnessApplied(); }

} // namespace FilmSim

// ───── FilmSpectral: spektrafilm's per-pixel colour on the exported arrays

namespace FilmSpectral {

struct Arr { std::vector<int> dims; std::vector<double> v; };
using Arrays = std::map<std::string, Arr>;

inline const std::vector<std::string>& FILMS() {
    static const std::vector<std::string> f = {"fujifilm_c200", "fujifilm_pro_400h", "fujifilm_provia_100f", "fujifilm_velvia_100",
        "fujifilm_xtra_400", "kodak_ektachrome_100", "kodak_ektar_100", "kodak_gold_200", "kodak_kodachrome_64", "kodak_portra_160",
        "kodak_portra_400", "kodak_portra_800", "kodak_portra_800_push1", "kodak_portra_800_push2", "kodak_ultramax_400",
        "kodak_verita_200d", "kodak_vision3_200t", "kodak_vision3_250d", "kodak_vision3_500t", "kodak_vision3_50d"};
    return f;
}
inline const std::vector<std::string>& PAPERS() {
    static const std::vector<std::string> p = {"fujifilm_crystal_archive_typeii", "kodak_2383", "kodak_2393", "kodak_ektacolor_edge",
        "kodak_endura_premier", "kodak_portra_endura", "kodak_supra_endura", "kodak_ultra_endura"};
    return p;
}
inline int indexOf(const std::vector<std::string>& a, const std::string& s) {
    for (size_t i = 0; i < a.size(); i++) if (a[i] == s) return int(i);
    return -1;
}

inline float half(uint16_t hbits) {
    const int s = (hbits >> 15) & 1, e = (hbits >> 10) & 0x1f, f = hbits & 0x3ff;
    float v;
    if (e == 0) v = float(f) * (1.f / float(1 << 24));
    else if (e == 31) v = f == 0 ? INFINITY : NAN;
    else {
        const uint32_t bits = (uint32_t(e + 112) << 23) | (uint32_t(f) << 13);
        std::memcpy(&v, &bits, 4);
    }
    return s == 1 ? -v : v;
}

/// 'SFD1', count, then per array: name, dtype (0 f32, 1 f16), dims, little-endian data
inline Arrays parse(const std::vector<uint8_t>& b) {
    size_t at = 0;
    auto need = [&](size_t n) { if (at + n > b.size()) throw std::runtime_error("SFD1 truncated"); };
    auto i32 = [&] { need(4); int32_t v; std::memcpy(&v, b.data() + at, 4); at += 4; return v; };
    if (b.size() < 8 || b[0] != 'S' || b[1] != 'F' || b[2] != 'D' || b[3] != '1') throw std::runtime_error("not SFD1");
    at = 4;
    const int count = i32();
    Arrays m;
    for (int a = 0; a < count; a++) {
        need(1);
        const size_t nl = b[at++];
        need(nl);
        const std::string name(reinterpret_cast<const char*>(b.data() + at), nl);
        at += nl;
        need(2);
        const int dt = int8_t(b[at++]), nd = int8_t(b[at++]);
        Arr arr;
        size_t n = 1;
        for (int i = 0; i < nd; i++) { arr.dims.push_back(i32()); n *= size_t(arr.dims.back()); }
        arr.v.resize(n);
        for (size_t i = 0; i < n; i++) {
            if (dt == 1) { need(2); uint16_t h; std::memcpy(&h, b.data() + at, 2); at += 2; arr.v[i] = half(h); }
            else { need(4); float f; std::memcpy(&f, b.data() + at, 4); at += 4; arr.v[i] = f; }
        }
        m[name] = std::move(arr);
    }
    return m;
}

inline std::mutex& cacheLock() { static std::mutex m; return m; }

inline std::shared_ptr<const Arrays> arrays(const std::string& file) {
    static std::map<std::string, std::shared_ptr<const Arrays>> cache;
    {
        std::lock_guard<std::mutex> lock(cacheLock());
        auto it = cache.find(file);
        if (it != cache.end()) return it->second;
    }
    const std::string path = filmAssetsRoot() + "/" + file;
    std::ifstream in(path, std::ios::binary);
    if (!in) throw std::runtime_error("film data " + path + " cannot be read");
    std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    auto m = std::make_shared<const Arrays>(parse(bytes));
    std::lock_guard<std::mutex> lock(cacheLock());
    cache[file] = m;
    return m;
}
inline const Arr& get(const Arrays& m, const std::string& k) {
    auto it = m.find(k);
    if (it == m.end()) throw std::runtime_error("missing " + k);
    return it->second;
}

constexpr int N_LE = 256, N_WL = 81;
constexpr double GRAIN_AREA = 0.1;
constexpr double GRAIN_SCALE[3] = {1.2, 1.0, 2.5}, GRAIN_LAYER_SCALE[3] = {6.0, 1.0, 0.4}, GRAIN_DMIN[3] = {.04, .05, .06},
                 GRAIN_UNIFORMITY[3] = {.99, .97, .98};

inline double clamp01(double v) { return v <= 0 ? 0 : v >= 1 ? 1 : v; }

/// interp of x over column xc of a stride-3 table to column yc of a stride-9 table
inline double interp(double x, const double* xp3, int xc, const double* fp9, int yc) {
    const int n = N_LE;
    if (!(x > xp3[xc])) return fp9[yc];
    if (x >= xp3[(n - 1) * 3 + xc]) return fp9[(n - 1) * 9 + yc];
    int lo = 0, hi = n - 1;
    while (hi - lo > 1) {
        const int mid = int(unsigned(lo + hi) >> 1);
        if (xp3[mid * 3 + xc] <= x) lo = mid; else hi = mid;
    }
    const double dx = xp3[hi * 3 + xc] - xp3[lo * 3 + xc];
    const double t = dx > 0 ? (x - xp3[lo * 3 + xc]) / dx : 0;
    return fp9[lo * 9 + yc] + (fp9[hi * 9 + yc] - fp9[lo * 9 + yc]) * t;
}
inline double interpDesc(double x, const double* xp3, int xc, const double* fp9, int yc) {
    std::vector<double> neg(size_t(N_LE) * 3, 0.0);
    for (int i = 0; i < N_LE; i++) neg[size_t(i * 3 + xc)] = -xp3[i * 3 + xc];
    return interp(-x, neg.data(), xc, fp9, yc);
}
/// np.interp of x over (xp, column col of fp with stride): clamped ends, xp ascending
inline double interp(double x, const std::vector<double>& xp, const double* fp, int stride, int col) {
    const int n = int(xp.size());
    if (!(x > xp[0])) return fp[col];
    if (x >= xp[size_t(n - 1)]) return fp[(n - 1) * stride + col];
    int lo = 0, hi = n - 1;
    while (hi - lo > 1) {
        const int mid = int(unsigned(lo + hi) >> 1);
        if (xp[size_t(mid)] <= x) lo = mid; else hi = mid;
    }
    const double dx = xp[size_t(hi)] - xp[size_t(lo)];
    const double t = dx > 0 ? (x - xp[size_t(lo)]) / dx : 0;
    return fp[lo * stride + col] + (fp[hi * stride + col] - fp[lo * stride + col]) * t;
}
inline double mitchell(double t) {
    const double b = 1 / 3.0, c = 1 / 3.0, x = std::fabs(t);
    if (x < 1) return ((12 - 9 * b - 6 * c) * x * x * x + (-18 + 12 * b + 6 * c) * x * x + (6 - 2 * b)) / 6;
    if (x < 2) return ((-b - 6 * c) * x * x * x + (6 * b + 30 * c) * x * x + (-12 * b - 48 * c) * x + (8 * b + 24 * c)) / 6;
    return 0;
}
inline int reflect(int i, int n) { return i < 0 ? -i : i >= n ? 2 * (n - 1) - i : i; }
inline void cubic2d(const std::vector<double>& lut, int n, double x, double y, double* out) {
    const double cx = std::min(std::max(x * (n - 1), 0.0), double(n - 1)), cy = std::min(std::max(y * (n - 1), 0.0), double(n - 1));
    int xb, yb;
    double xf, yf;
    if (cx >= n - 1) { xb = n - 2; xf = 1; } else { xb = int(std::floor(cx)); xf = cx - xb; }
    if (cy >= n - 1) { yb = n - 2; yf = 1; } else { yb = int(std::floor(cy)); yf = cy - yb; }
    const double wx[4] = {mitchell(xf + 1), mitchell(xf), mitchell(xf - 1), mitchell(xf - 2)};
    const double wy[4] = {mitchell(yf + 1), mitchell(yf), mitchell(yf - 1), mitchell(yf - 2)};
    out[0] = out[1] = out[2] = 0;
    double ws = 0;
    for (int i = 0; i < 4; i++) {
        const int xi = reflect(xb - 1 + i, n);
        for (int j = 0; j < 4; j++) {
            const int yj = reflect(yb - 1 + j, n);
            const double w = wx[i] * wy[j];
            ws += w;
            const size_t idx = size_t(xi * n + yj) * 3;
            for (int c = 0; c < 3; c++) out[c] += w * lut[idx + size_t(c)];
        }
    }
    if (ws != 0) for (int c = 0; c < 3; c++) out[c] /= ws;
}
inline std::vector<double> spectral(const std::vector<double>& cd, const std::vector<double>& bd, const double* d) {
    std::vector<double> s(N_WL);
    for (int w = 0; w < N_WL; w++) s[size_t(w)] = cd[size_t(w * 3)] * d[0] + cd[size_t(w * 3 + 1)] * d[1] + cd[size_t(w * 3 + 2)] * d[2] + bd[size_t(w)];
    return s;
}

struct Scan {
    std::vector<double> lut, lo, hi;
    int n = 0;
    explicit Scan(const Arrays& a) {
        lut = get(a, "scan").v;
        n = get(a, "scan").dims[0];
        lo = get(a, "scanmin").v;
        hi = get(a, "scanmax").v;
    }
    void apply(const double* d, double* out) const {
        int i0[3];
        double t[3];
        for (int c = 0; c < 3; c++) {
            const double p = clamp01((d[c] - lo[size_t(c)]) / (hi[size_t(c)] - lo[size_t(c)])) * (n - 1);
            i0[c] = std::min(int(std::floor(p)), n - 2);
            t[c] = p - i0[c];
        }
        out[0] = out[1] = out[2] = 0;
        for (int dz = 0; dz < 2; dz++) for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
            const double w = (dx == 1 ? t[0] : 1 - t[0]) * (dy == 1 ? t[1] : 1 - t[1]) * (dz == 1 ? t[2] : 1 - t[2]);
            const size_t idx = size_t(((i0[2] + dz) * n + (i0[1] + dy)) * n + (i0[0] + dx)) * 3;
            for (int c = 0; c < 3; c++) out[c] += w * lut[idx + size_t(c)];
        }
    }
};

struct Film {
    std::string id;
    bool positive = false;
    std::vector<double> m, tc, le, dc, cd, bd, dir, dcl;
    int tcSize = 0;
    std::vector<double> dcN = std::vector<double>(size_t(N_LE) * 3), dc0, dmaxN = std::vector<double>(3);
    std::map<uint64_t, std::vector<double>> dcAt;
    std::mutex dcLock;
    std::unique_ptr<Scan> scan;

    explicit Film(const std::string& id_) : id(id_) {
        const auto a = arrays(id + ".bin");
        m = get(*a, "M").v;
        tc = get(*a, "tc").v;
        tcSize = get(*a, "tc").dims[0];
        le = get(*a, "le").v;
        dc = get(*a, "dc").v;
        cd = get(*a, "cd").v;
        bd = get(*a, "bd").v;
        dir = get(*a, "dir").v;
        dcl = get(*a, "dcl").v;
        if (a->count("scan")) scan = std::make_unique<Scan>(*a);
        positive = scan != nullptr;
        for (int c = 0; c < 3; c++) {
            double lo = std::numeric_limits<double>::max();
            for (int i = 0; i < N_LE; i++) lo = std::min(lo, dc[size_t(i * 3 + c)]);
            double hi = -std::numeric_limits<double>::max();
            for (int i = 0; i < N_LE; i++) hi = std::max(hi, dcN[size_t(i * 3 + c)] = dc[size_t(i * 3 + c)] - lo);
            dmaxN[size_t(c)] = hi;
        }
        dc0 = curvesBefore(1.0);
    }
    std::vector<double> curvesBefore(double couplers) const {
        std::vector<double> out(size_t(N_LE) * 3);
        std::vector<double> le0(N_LE), col(N_LE);
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < N_LE; i++) {
                double amount = 0;
                for (int k = 0; k < 3; k++)
                    amount += (positive ? dmaxN[size_t(k)] - dcN[size_t(i * 3 + k)] : dcN[size_t(i * 3 + k)]) * (dir[size_t(k * 3 + c)] * couplers);
                le0[size_t(i)] = le[size_t(i)] - amount;
                col[size_t(i)] = positive ? -dcN[size_t(i * 3 + c)] : dcN[size_t(i * 3 + c)];
            }
            for (int i = 0; i < N_LE; i++) {
                const double v = interp(le[size_t(i)], le0, col.data(), 1, 0);
                out[size_t(i * 3 + c)] = positive ? -v : v;
            }
        }
        return out;
    }
    const std::vector<double>& curvesAt(double couplers) {
        if (couplers == 1.0) return dc0;
        uint64_t key;
        std::memcpy(&key, &couplers, 8);
        std::lock_guard<std::mutex> lock(dcLock);
        auto it = dcAt.find(key);
        if (it == dcAt.end()) it = dcAt.emplace(key, curvesBefore(couplers)).first;
        return it->second;
    }
    void raw(double r, double g, double b, double* out) const {
        const double x = m[0] * r + m[1] * g + m[2] * b, y = m[3] * r + m[4] * g + m[5] * b, z = m[6] * r + m[7] * g + m[8] * b;
        double s = x + y + z;
        const double d = std::max(s, 1e-10), tx = x / d, ty = y / d;
        const double qy = clamp01(ty / std::max(1 - tx, 1e-10)), qx = clamp01((1 - tx) * (1 - tx));
        cubic2d(tc, tcSize, qx, qy, out);
        if (!std::isfinite(s)) s = 0;
        for (int c = 0; c < 3; c++) out[c] *= s;
    }
    void develop(const double* logRaw, double gamma, double couplers, double* out) {
        double d[3];
        for (int c = 0; c < 3; c++) d[c] = interp(logRaw[c] * gamma, le, dcN.data(), 3, c);
        const std::vector<double>& before = curvesAt(couplers);
        for (int c = 0; c < 3; c++) {
            double corr = 0;
            for (int k = 0; k < 3; k++) corr += (positive ? dmaxN[size_t(k)] - d[k] : d[k]) * (dir[size_t(k * 3 + c)] * couplers);
            out[c] = interp((logRaw[c] - corr) * gamma, le, before.data(), 3, c);
        }
    }
    void grainSigma(const double* d, double* out) const {
        for (int c = 0; c < 3; c++) {
            double tot = 0;
            double dml[3];
            for (int l = 0; l < 3; l++) {
                double hi = -std::numeric_limits<double>::max();
                for (int i = 0; i < N_LE; i++) hi = std::max(hi, dcl[size_t(i * 9 + l * 3 + c)]);
                dml[l] = hi;
                tot += hi;
            }
            double var = 0;
            for (int l = 0; l < 3; l++) {
                const double frac = dml[l] / tot, dmin = frac * GRAIN_DMIN[c], dmax = dml[l] + dmin;
                const double layer = positive ? interpDesc(d[c], dcN.data(), c, dcl.data(), l * 3 + c) : interp(d[c], dcN.data(), c, dcl.data(), l * 3 + c);
                const double x = layer + dmin;
                const double pr = std::min(std::max(x / dmax, 1e-6), 1 - 1e-6);
                const double n = frac / (GRAIN_AREA * GRAIN_SCALE[c] * GRAIN_LAYER_SCALE[l]);
                var += x * (dmax / n) * (1 - pr * GRAIN_UNIFORMITY[c] * (1 - 1e-6));
            }
            out[c] = std::sqrt(std::max(var, 0.0));
        }
    }
    std::vector<double> midSpectral(double ev, double gamma) const {
        double r[3];
        const double v = 0.184 * std::pow(2, ev);
        raw(v, v, v, r);
        double d[3];
        for (int c = 0; c < 3; c++) d[c] = interp(std::log10(r[c] + 1e-10) * gamma, le, dc.data(), 3, c);
        return spectral(cd, bd, d);
    }
};

struct Paper {
    std::string id;
    std::vector<double> sens, le, dcm, cd, bd;
    std::unique_ptr<Scan> scan;
    explicit Paper(const std::string& id_) : id(id_) {
        const auto a = arrays(id + ".bin");
        sens = get(*a, "sens").v;
        le = get(*a, "le").v;
        dcm = get(*a, "dcm").v;
        cd = get(*a, "cd").v;
        bd = get(*a, "bd").v;
        scan = std::make_unique<Scan>(*a);
    }
};

inline std::shared_ptr<Film> film(const std::string& id) {
    static std::map<std::string, std::shared_ptr<Film>> cache;
    static std::mutex lock;
    std::lock_guard<std::mutex> g(lock);
    auto it = cache.find(id);
    if (it != cache.end()) return it->second;
    auto f = std::make_shared<Film>(id);
    cache[id] = f;
    return f;
}
inline std::shared_ptr<Paper> paper(const std::string& id) {
    static std::map<std::string, std::shared_ptr<Paper>> cache;
    static std::mutex lock;
    std::lock_guard<std::mutex> g(lock);
    auto it = cache.find(id);
    if (it != cache.end()) return it->second;
    auto p = std::make_shared<Paper>(id);
    cache[id] = p;
    return p;
}

/// FilmSpectral.Engine: one film (and paper) with the user's settings
struct Engine {
    std::shared_ptr<Film> filmP;
    std::shared_ptr<Paper> paperP;
    double ev, printExposure, gamma, factor = 1, couplers;
    std::vector<double> light;

    Engine(std::shared_ptr<Film> f, std::shared_ptr<Paper> p, double ev_, double printExposure_, double mShift, double yShift, double gamma_,
           double couplers_)
        : filmP(std::move(f)), paperP(filmP->positive ? nullptr : std::move(p)), ev(ev_), printExposure(printExposure_), gamma(gamma_),
          couplers(couplers_) {
        if (!paperP) return;
        const auto sh = arrays("shared.bin");
        const std::vector<double>& lamp = get(*sh, "thkg3").v;
        const std::vector<double>& dich = get(*sh, "dichroic").v;
        const std::vector<double>& neutral = get(*sh, "neutral").v;
        const int pi = indexOf(PAPERS(), paperP->id), fi = indexOf(FILMS(), filmP->id);
        double cc[3] = {0, 65, 55};
        if (pi >= 0 && fi >= 0) for (int c = 0; c < 3; c++) cc[c] = neutral[size_t((pi * int(FILMS().size()) + fi) * 3 + c)];
        cc[1] += mShift;
        cc[2] += yShift;
        light.resize(N_WL);
        for (int w = 0; w < N_WL; w++) {
            double total = 1;
            for (int c = 0; c < 3; c++) total *= 1 - (1 - dich[size_t(w * 3 + c)]) * (1 - std::pow(10, -cc[c] / 100.0));
            light[size_t(w)] = lamp[size_t(w)] * total;
        }
        const std::vector<double> mid = expose(filmP->midSpectral(0, gamma));
        double lg = 0;
        for (int c = 0; c < 3; c++) lg += std::log(std::max(mid[size_t(c)], 1e-10));
        factor = 1 / std::exp(lg / 3);
    }
    std::vector<double> expose(const std::vector<double>& spec) const {
        std::vector<double> raw(3, 0.0);
        for (int w = 0; w < N_WL; w++) {
            const double t = std::pow(10, -spec[size_t(w)]) * light[size_t(w)];
            if (!std::isfinite(t)) continue;
            for (int c = 0; c < 3; c++) raw[size_t(c)] += t * paperP->sens[size_t(w * 3 + c)];
        }
        return raw;
    }
    void render(double r, double g, double b, double* out) {
        double d[3];
        densities(r, g, b, d);
        fromDensities(d, out);
    }
    void densities(double r, double g, double b, double* d) {
        double raw[3], lr[3];
        filmP->raw(r, g, b, raw);
        const double k = std::pow(2, ev);
        for (int c = 0; c < 3; c++) lr[c] = std::log10(std::max(raw[c] * k, 0.0) + 1e-10);
        filmP->develop(lr, gamma, couplers, d);
    }
    void grainSigma(const double* d, double* out) const { filmP->grainSigma(d, out); }
    void fromDensities(const double* d, double* out) const {
        if (!paperP) { filmP->scan->apply(d, out); return; }
        const std::vector<double> rp = expose(spectral(filmP->cd, filmP->bd, d));
        double dp[3];
        for (int c = 0; c < 3; c++) {
            const double lrp = std::log10(std::max(rp[size_t(c)] * factor, 0.0) + 1e-10);
            const double lrp2 = std::log10(std::max(std::pow(10, lrp) * printExposure, 0.0) + 1e-10);
            dp[c] = interp(lrp2, paperP->le, paperP->dcm.data(), 3, c);
        }
        paperP->scan->apply(dp, out);
    }
};

/// FilmSpectral.engine with the couplers' amount
inline std::unique_ptr<Engine> engine(const std::string& filmId, const std::string& paperId, double ev, double printEv, double mShift,
                                      double yShift, double gamma, double couplers) {
    std::shared_ptr<Film> f = film(filmId);
    std::shared_ptr<Paper> p = f->positive ? nullptr : paper(!paperId.empty() && indexOf(PAPERS(), paperId) >= 0 ? paperId : "kodak_portra_endura");
    return std::make_unique<Engine>(f, p, ev, std::pow(2, printEv), mShift, yShift, gamma, couplers);
}

} // namespace FilmSpectral

} // namespace scam_post
