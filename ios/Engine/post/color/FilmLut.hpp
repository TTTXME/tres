// The 3D LUTs of the film simulation and «Плёночный LUT» one to one: FilmLut.java (.cube parsing, the 3D RGB16F texture, the
// trilinear sample), FilmLooks.java (our five built-in looks), FilmSim's tables (FilmSim.lut of the display colour, FilmSim.lutScene
// of the scene light, FilmSim.applyScene, FilmSim.grainLut), FilmChain.java (the chosen LUT, the display chain of a film and the
// scene table of the photo with the LUT on the film's print) and FinishedLook.java (the editor's display table). Java float
// semantics as FilmSim.hpp. A .cube file is read from filmLutFolder() (Android: Download/SCAMERA/LUT; iOS: Documents/LUT).
#pragma once

#include "FilmSim.hpp"

#include <GLES3/gl31.h>

#include <dirent.h>

#include <algorithm>
#include <functional>
#include <list>
#include <sstream>

#ifndef GL_TEXTURE_WRAP_R
#define GL_TEXTURE_WRAP_R 0x8072
#endif

namespace scam_post {

/// The folder of the imported .cube files (ScameraFolder.LUTS)
inline std::string& filmLutFolder() { static std::string folder; return folder; }

struct FilmLut {
    static constexpr const char* KEY = "pref_film_lut";
    static constexpr const char* STRENGTH_KEY = "pref_film_lut_strength";
    static constexpr int MIN_SIZE = 2, MAX_SIZE = 65;
    static constexpr long MAX_BYTES = 24L << 20;

    int size = 0;
    std::vector<float> rgb;
    std::array<float, 3> domainMin{0, 0, 0}, domainMax{1, 1, 1};
    std::string title;

    static std::shared_ptr<const FilmLut> of(int n, std::vector<float> v, const std::string& title) {
        auto l = std::make_shared<FilmLut>();
        l->size = n;
        l->rgb = std::move(v);
        l->title = title;
        return l;
    }
    static std::shared_ptr<const FilmLut> identity(int n) {
        std::vector<float> v(size_t(n) * n * n * 3);
        size_t k = 0;
        for (int b = 0; b < n; b++) for (int g = 0; g < n; g++) for (int r = 0; r < n; r++) {
            v[k++] = float(r) / float(n - 1);
            v[k++] = float(g) / float(n - 1);
            v[k++] = float(b) / float(n - 1);
        }
        return of(n, std::move(v), "identity");
    }

    /// FilmLut.parse: null for a 1D LUT, a size outside 2..65, a wrong row count or a value that is not a number
    static std::shared_ptr<const FilmLut> parse(const std::string& text) {
        int size = 0;
        size_t k = 0;
        std::vector<float> values;
        bool have = false;
        std::array<float, 3> mn{0, 0, 0}, mx{1, 1, 1};
        std::string title;
        long read = 0;
        std::istringstream in(text);
        std::string raw;
        while (std::getline(in, raw)) {
            // BufferedReader.readLine: "\n", "\r" or "\r\n" end a line
            if (!raw.empty() && raw.back() == '\r') raw.pop_back();
            read += long(raw.size()) + 1;
            if (read > MAX_BYTES) return nullptr;
            const std::string line = jfmt::trim(raw);
            if (line.empty() || line[0] == '#') continue;
            std::vector<std::string> t;
            {
                std::string cur;
                for (char c : line) {
                    if (std::isspace(static_cast<unsigned char>(c))) { if (!cur.empty()) { t.push_back(cur); cur.clear(); } }
                    else cur += c;
                }
                if (!cur.empty()) t.push_back(cur);
            }
            std::string head = t[0];
            for (char& c : head) c = char(std::toupper(static_cast<unsigned char>(c)));
            auto num = [&](size_t i, float& out) { return i < t.size() && jfmt::parseFloat(t[i], out); };
            if (head == "TITLE") {
                std::string rest = jfmt::trim(line.substr(5));
                std::string clean;
                for (char c : rest) if (c != '"') clean += c;
                title = clean;
                continue;
            }
            if (head == "LUT_1D_SIZE") return nullptr;
            if (head == "LUT_3D_SIZE") {
                if (t.size() < 2) return nullptr;
                char* end = nullptr;
                const long v = std::strtol(t[1].c_str(), &end, 10);
                if (end == t[1].c_str() || *end) return nullptr;
                size = int(v);
                if (size < MIN_SIZE || size > MAX_SIZE) return nullptr;
                values.assign(size_t(size) * size * size * 3, 0.f);
                have = true;
                continue;
            }
            if (head == "DOMAIN_MIN" || head == "DOMAIN_MAX") {
                std::array<float, 3>& d = head == "DOMAIN_MIN" ? mn : mx;
                for (size_t c = 0; c < 3; c++) if (!num(1 + c, d[c])) return nullptr;
                continue;
            }
            if (head == "LUT_3D_INPUT_RANGE") {
                float lo, hi;
                if (!num(1, lo) || !num(2, hi)) return nullptr;
                mn = {lo, lo, lo};
                mx = {hi, hi, hi};
                continue;
            }
            const char h0 = head[0];
            if (!std::isdigit(static_cast<unsigned char>(h0)) && h0 != '-' && h0 != '.' && h0 != '+') continue;
            if (!have || t.size() < 3 || k + 3 > values.size()) return nullptr;
            for (size_t c = 0; c < 3; c++) {
                float v;
                if (!jfmt::parseFloat(t[c], v)) return nullptr;
                if (!std::isfinite(v)) return nullptr;
                values[k++] = v;
            }
        }
        if (!have || k != values.size()) return nullptr;
        for (size_t c = 0; c < 3; c++) if (!(mx[c] > mn[c])) return nullptr;
        auto l = std::make_shared<FilmLut>();
        l->size = size;
        l->rgb = std::move(values);
        l->domainMin = mn;
        l->domainMax = mx;
        l->title = title;
        return l;
    }

    /// A 3D RGB16F texture of the LUT (linear filtering, clamped) bound on the active unit's 3D target, then unbound; the caller
    /// deletes it (glDeleteTextures)
    GLuint upload() const {
        GLuint id = 0;
        glGenTextures(1, &id);
        glBindTexture(GL_TEXTURE_3D, id);
        glTexImage3D(GL_TEXTURE_3D, 0, GL_RGB16F, size, size, size, 0, GL_RGB, GL_FLOAT, rgb.data());
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_3D, 0);
        return id;
    }

    /// FilmLut.sampleInto (the CPU twin of the shader's trilinear lookup)
    void sampleInto(float r, float g, float b, float* out) const {
        auto axis = [&](float v, int c, int& i, float& p) {
            const float x = std::max(0.f, std::min(1.f, (std::max(0.f, std::min(1.f, v)) - domainMin[size_t(c)]) / (domainMax[size_t(c)] - domainMin[size_t(c)])))
                            * float(size - 1);
            i = std::min(size - 2, int(x));
            p = x - float(i);
        };
        int ir, ig, ib;
        float pr, pg, pb;
        axis(r, 0, ir, pr);
        axis(g, 1, ig, pg);
        axis(b, 2, ib, pb);
        out[0] = out[1] = out[2] = 0.f;
        for (int corner = 0; corner < 8; corner++) {
            const int dr = corner & 1, dg = (corner >> 1) & 1, db = corner >> 2;
            const float w = (dr == 1 ? pr : 1 - pr) * (dg == 1 ? pg : 1 - pg) * (db == 1 ? pb : 1 - pb);
            const size_t idx = size_t(((ib + db) * size + (ig + dg)) * size + (ir + dr)) * 3;
            out[0] += w * rgb[idx];
            out[1] += w * rgb[idx + 1];
            out[2] += w * rgb[idx + 2];
        }
    }
};
using FilmLutP = std::shared_ptr<const FilmLut>;

// ───── FilmLooks (P170): five looks of our own

namespace FilmLooks {
constexpr const char* PREFIX = "scam:";
constexpr int SIZE = 33;
inline const std::array<const char*, 5>& IDS() { static const std::array<const char*, 5> a = {"warm400", "cool160", "slide100", "cine500", "mono400"}; return a; }
inline const std::array<const char*, 5>& TITLES() { static const std::array<const char*, 5> a = {"Warm 400", "Cool 160", "Slide 100", "Cine 500", "B&W 400"}; return a; }

inline int index(const std::string& value) {
    if (value.rfind(PREFIX, 0) != 0) return -1;
    const std::string id = value.substr(std::strlen(PREFIX));
    for (size_t i = 0; i < IDS().size(); i++) if (id == IDS()[i]) return int(i);
    return -1;
}
inline bool isBuiltIn(const std::string& value) { return index(value) >= 0; }
inline std::vector<std::string> values() {
    std::vector<std::string> out;
    for (const char* id : IDS()) out.push_back(std::string(PREFIX) + id);
    return out;
}

inline float clamp(float v) { return v < 0.f ? 0.f : v > 1.f ? 1.f : v; }
inline float luma(const float* c) { return 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]; }
inline float logistic(float x, float k, float pivot) { return 1.f / (1.f + float(std::exp(double(-k * (x - pivot))))); }
inline void film(float* c, float k, float pivot, float amount, float lift, float white) {
    const float lo = logistic(0.f, k, pivot), hi = logistic(1.f, k, pivot);
    for (int i = 0; i < 3; i++) {
        const float x = clamp(c[i]);
        const float s = (logistic(x, k, pivot) - lo) / (hi - lo);
        const float y = x + (s - x) * amount;
        c[i] = lift + (white - lift) * y;
    }
}
inline void split(float* c, float sr, float sg, float sb, float hr, float hg, float hb) {
    const float l = clamp(luma(c));
    const float ws = (1.f - l) * (1.f - l), wh = l * l;
    const float o[3] = {sr * ws + hr * wh, sg * ws + hg * wh, sb * ws + hb * wh};
    const float lo = luma(o);
    for (int i = 0; i < 3; i++) c[i] += o[i] - lo;
}
inline void saturate(float* c, float s) {
    const float l = luma(c);
    for (int i = 0; i < 3; i++) c[i] = l + (c[i] - l) * s;
}
inline void bands(float* c, const std::vector<std::array<float, 5>>& bands) {
    const float r = clamp(c[0]), g = clamp(c[1]), b = clamp(c[2]);
    const float max = std::max(r, std::max(g, b)), min = std::min(r, std::min(g, b));
    float v = max;
    const float d = max - min;
    if (d <= 1e-6f || max <= 1e-6f) return;
    float s = d / max;
    float h;
    if (max == r) h = 60.f * std::fmod((g - b) / d, 6.f);
    else if (max == g) h = 60.f * ((b - r) / d + 2.f);
    else h = 60.f * ((r - g) / d + 4.f);
    if (h < 0.f) h += 360.f;
    float dh = 0.f, ds = 0.f, dv = 0.f;
    for (const auto& band : bands) {
        float dist = std::fabs(h - band[0]);
        if (dist > 180.f) dist = 360.f - dist;
        const float t = dist / band[1];
        if (t >= 1.f) continue;
        const float w = (1.f - t * t) * (1.f - t * t);
        dh += band[2] * w;
        ds += band[3] * w;
        dv += band[4] * w;
    }
    h = std::fmod(h + dh + 360.f, 360.f);
    s = clamp(s * (1.f + ds));
    v = clamp(v * (1.f + dv * s));
    const float cc = v * s, x = cc * (1.f - std::fabs(std::fmod(h / 60.f, 2.f) - 1.f)), m = v - cc;
    float rr, gg, bb;
    const int sector = int(h / 60.f) % 6;
    switch (sector) {
    case 0: rr = cc; gg = x; bb = 0; break;
    case 1: rr = x; gg = cc; bb = 0; break;
    case 2: rr = 0; gg = cc; bb = x; break;
    case 3: rr = 0; gg = x; bb = cc; break;
    case 4: rr = x; gg = 0; bb = cc; break;
    default: rr = cc; gg = 0; bb = x; break;
    }
    c[0] = rr + m;
    c[1] = gg + m;
    c[2] = bb + m;
}
inline void warm400(float* c) {
    film(c, 4.0f, 0.42f, 0.55f, 0.030f, 0.975f);
    bands(c, {{110, 50, -14, -0.25f, 0}, {215, 40, -5, -0.15f, -0.05f}, {28, 25, 0, 0.06f, 0.01f}});
    split(c, 0.020f, 0.005f, -0.020f, 0.025f, 0.010f, -0.030f);
    saturate(c, 0.92f);
}
inline void cool160(float* c) {
    film(c, 3.6f, 0.45f, 0.45f, 0.045f, 0.970f);
    bands(c, {{110, 50, 10, -0.15f, 0}, {10, 30, -4, -0.05f, 0}, {215, 40, 4, -0.08f, 0.02f}});
    split(c, -0.015f, 0.010f, 0.015f, 0.008f, -0.004f, 0.006f);
    saturate(c, 0.85f);
}
inline void slide100(float* c) {
    film(c, 5.5f, 0.47f, 0.80f, 0.008f, 0.985f);
    bands(c, {{225, 45, 0, 0.10f, -0.06f}, {0, 30, 0, 0.05f, 0}, {120, 45, 4, 0.08f, 0}, {30, 22, 0, -0.10f, 0}});
    split(c, -0.010f, 0.0f, 0.015f, 0.005f, 0.004f, -0.006f);
    saturate(c, 1.08f);
}
inline void cine500(float* c) {
    film(c, 5.0f, 0.45f, 0.75f, 0.025f, 0.965f);
    bands(c, {{120, 55, 18, -0.30f, 0}, {215, 40, -5, -0.10f, -0.05f}, {25, 25, 0, 0.05f, 0}});
    split(c, -0.030f, 0.005f, 0.030f, 0.030f, 0.008f, -0.030f);
    saturate(c, 0.90f);
}
inline void mono400(float* c) {
    const float y = 0.30f * c[0] + 0.59f * c[1] + 0.11f * c[2];
    c[0] = c[1] = c[2] = y;
    film(c, 5.5f, 0.45f, 0.85f, 0.025f, 0.985f);
}
inline void apply(int i, float* c) {
    switch (i) {
    case 0: warm400(c); break;
    case 1: cool160(c); break;
    case 2: slide100(c); break;
    case 3: cine500(c); break;
    default: mono400(c); break;
    }
}
/// The LUT of a look, or null for anything else
inline FilmLutP lut(const std::string& value) {
    const int i = index(value);
    if (i < 0) return nullptr;
    const int n = SIZE;
    std::vector<float> v(size_t(n) * n * n * 3);
    size_t k = 0;
    float c[3];
    for (int b = 0; b < n; b++) for (int g = 0; g < n; g++) for (int r = 0; r < n; r++) {
        c[0] = float(r) / float(n - 1);
        c[1] = float(g) / float(n - 1);
        c[2] = float(b) / float(n - 1);
        apply(i, c);
        for (int ch = 0; ch < 3; ch++) v[k++] = clamp(c[ch]);
    }
    return FilmLut::of(n, std::move(v), TITLES()[size_t(i)]);
}
} // namespace FilmLooks

namespace FilmLutFiles {
/// FilmLut.load / chosen's file read: a .cube of filmLutFolder() (null when missing or unreadable)
inline FilmLutP read(const std::string& name) {
    if (filmLutFolder().empty() || name.find('/') != std::string::npos) return nullptr;
    std::ifstream in(filmLutFolder() + "/" + name, std::ios::binary);
    if (!in) return nullptr;
    std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    return FilmLut::parse(text);
}
/// FilmLut.load: a LUT by its stored value (one of our looks or a file), outside the pipeline's cache
inline FilmLutP load(const std::string& name) {
    if (name.empty()) return nullptr;
    if (FilmLooks::isBuiltIn(name)) return FilmLooks::lut(name);
    return read(name);
}
/// FilmLut.chosen: parsed once per name (the pipeline asks per shot)
inline FilmLutP chosen(const std::string& name) {
    static std::mutex lock;
    static std::string cachedName;
    static FilmLutP cached;
    static bool have = false;
    if (name.empty()) return nullptr;
    std::lock_guard<std::mutex> g(lock);
    if (have && name == cachedName) return cached;
    FilmLutP lut = FilmLooks::isBuiltIn(name) ? FilmLooks::lut(name) : read(name);
    cachedName = name;
    cached = lut;
    have = true;
    return lut;
}
/// FilmLut.forget (a new import of the same name)
inline void forget() { chosen(""); }
/// The .cube files of the folder, sorted (the settings' picker; ScameraFolder.LUTS lists *.cube)
inline std::vector<std::string> list() {
    std::vector<std::string> out;
    if (filmLutFolder().empty()) return out;
    if (DIR* d = opendir(filmLutFolder().c_str())) {
        while (dirent* e = readdir(d)) {
            const std::string n = e->d_name;
            if (n.size() > 5) {
                std::string ext = n.substr(n.size() - 5);
                for (char& c : ext) c = char(std::tolower(static_cast<unsigned char>(c)));
                if (ext == ".cube") out.push_back(n);
            }
        }
        closedir(d);
    }
    std::sort(out.begin(), out.end());
    return out;
}
} // namespace FilmLutFiles

// ───── FilmSim's tables

namespace FilmSim {

/// FilmSim.engine: the reference model for a colour stock (null for our black-and-white model, or when its data cannot be read)
inline std::unique_ptr<FilmSpectral::Engine> engine(const FilmSettings& set, std::string* error = nullptr) {
    if (set.stock->spectral.empty()) return nullptr;
    try {
        return FilmSpectral::engine(set.stock->spectral, set.print->id, set.exposure, set.printEv, set.printM, set.printY, gamma(set.tone), set.couplers);
    } catch (const std::exception& e) {
        if (error) *error = e.what();
        return nullptr;
    }
}

/// FilmSim.lut: the simulation as a LUT of the display-encoded colour, n points a side (red fastest)
inline FilmLutP lut(const FilmSettings& set, int n) {
    const std::string title = set.stock->brand + " " + set.stock->name;
    std::vector<float> v(size_t(n) * n * n * 3);
    std::vector<double> sc{std::vector<double>(size_t(n))};
    for (int i = 0; i < n; i++) sc[size_t(i)] = scene(i / double(n - 1));
    size_t k = 0;
    if (!set.stock->spectral.empty()) {
        auto e = engine(set);
        double o[3];
        for (int b = 0; b < n; b++) for (int g = 0; g < n; g++) for (int r = 0; r < n; r++) {
            if (!e) {
                v[k++] = float(r) / float(n - 1);
                v[k++] = float(g) / float(n - 1);
                v[k++] = float(b) / float(n - 1);
                continue;
            }
            e->render(sc[size_t(r)], sc[size_t(g)], sc[size_t(b)], o);
            for (int c = 0; c < 3; c++) v[k++] = float(std::max(0.0, std::min(1.0, o[c])));
        }
        return FilmLut::of(n, std::move(v), title);
    }
    const Model m(*set.stock, MONO_PAPER(), set.exposure, set.printEv, set.tone);
    double rgb[3];
    for (int b = 0; b < n; b++) for (int g = 0; g < n; g++) for (int r = 0; r < n; r++) {
        rgb[0] = sc[size_t(r)];
        rgb[1] = sc[size_t(g)];
        rgb[2] = sc[size_t(b)];
        const std::array<float, 3> o = m.render(rgb);
        v[k++] = encode(o[0]);
        v[k++] = encode(o[1]);
        v[k++] = encode(o[2]);
    }
    return FilmLut::of(n, std::move(v), title);
}

/// FilmSim.lutScene: the simulation as a LUT of scene light on the per-channel log grid (null when the data cannot be read)
inline FilmLutP lutScene(const FilmSettings& set, int n) {
    std::vector<double> sc{std::vector<double>(size_t(n))};
    for (int i = 0; i < n; i++) sc[size_t(i)] = double(FILM_GREY) * std::pow(2, double(LOG_LO) + double(LOG_SPAN * float(i)) / double(n - 1));
    std::vector<float> v(size_t(n) * n * n * 3);
    size_t k = 0;
    const std::string title = set.stock->brand + " " + set.stock->name;
    if (!set.stock->spectral.empty()) {
        auto e = engine(set);
        if (!e) return nullptr;
        double o[3];
        for (int b = 0; b < n; b++) for (int g = 0; g < n; g++) for (int r = 0; r < n; r++) {
            e->render(sc[size_t(r)], sc[size_t(g)], sc[size_t(b)], o);
            for (int c = 0; c < 3; c++) v[k++] = float(std::max(0.0, std::min(1.0, o[c])));
        }
        return FilmLut::of(n, std::move(v), title);
    }
    const Model m(*set.stock, MONO_PAPER(), set.exposure, set.printEv, set.tone);
    const double toModel = .18 / double(FILM_GREY);
    double rgb[3];
    for (int b = 0; b < n; b++) for (int g = 0; g < n; g++) for (int r = 0; r < n; r++) {
        rgb[0] = sc[size_t(r)] * toModel;
        rgb[1] = sc[size_t(g)] * toModel;
        rgb[2] = sc[size_t(b)] * toModel;
        const std::array<float, 3> o = m.render(rgb);
        v[k++] = encode(o[0]);
        v[k++] = encode(o[1]);
        v[k++] = encode(o[2]);
    }
    return FilmLut::of(n, std::move(v), title);
}

inline int cellIndex(double v, int n) {
    const double x = std::floor(v * (n - 1) + 0.5); // Math.round(double): NaN 0
    if (x != x) return 0;
    return int(std::max(0.0, std::min(double(n - 1), x)));
}

/// FilmSim.grainLut: the reference model's grain as a LUT over the picture's colour (null for our black-and-white model)
inline FilmLutP grainLut(const FilmSettings& set, int n) {
    if (set.stock->spectral.empty()) return nullptr;
    static std::mutex lock;
    static std::string cachedKey;
    static FilmLutP cached;
    std::lock_guard<std::mutex> g(lock);
    const std::string key = set.lutKey() + "|" + std::to_string(n);
    if (key == cachedKey) return cached;
    auto e = engine(set);
    if (!e) return nullptr;
    const int cells = n * n * n, side = 21;
    std::vector<double> sum(size_t(cells) * 3, 0.0);
    std::vector<int> count(size_t(cells), 0);
    double d[3], sig[3], o[3], hi[3], lo[3];
    const double step = .02;
    for (int b = 0; b < side; b++) for (int gg = 0; gg < side; gg++) for (int r = 0; r < side; r++) {
        e->densities(scene(r / (side - 1.0)), scene(gg / (side - 1.0)), scene(b / (side - 1.0)), d);
        e->fromDensities(d, o);
        e->grainSigma(d, sig);
        const int cell = (cellIndex(o[2], n) * n + cellIndex(o[1], n)) * n + cellIndex(o[0], n);
        for (int c = 0; c < 3; c++) {
            const double keep = d[c];
            d[c] = keep + step;
            e->fromDensities(d, hi);
            d[c] = keep - step;
            e->fromDensities(d, lo);
            d[c] = keep;
            sum[size_t(cell * 3 + c)] += std::fabs(hi[c] - lo[c]) / (2 * step) * sig[c];
        }
        count[size_t(cell)]++;
    }
    std::vector<float> v(size_t(cells) * 3, 0.f);
    std::vector<char> known(size_t(cells), 0);
    for (int i = 0; i < cells; i++) if (count[size_t(i)] > 0) {
        known[size_t(i)] = 1;
        for (int c = 0; c < 3; c++) v[size_t(i * 3 + c)] = float(sum[size_t(i * 3 + c)] / count[size_t(i)]);
    }
    for (int pass = 0; pass < 3 * n; pass++) {
        std::vector<char> next = known;
        bool grew = false;
        for (int z = 0; z < n; z++) for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            const int i = (z * n + y) * n + x;
            if (known[size_t(i)]) continue;
            float acc[3] = {0, 0, 0};
            int kk = 0;
            for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                const int xx = x + dx, yy = y + dy, zz = z + dz;
                if (xx < 0 || yy < 0 || zz < 0 || xx >= n || yy >= n || zz >= n) continue;
                const int j = (zz * n + yy) * n + xx;
                if (!known[size_t(j)]) continue;
                for (int c = 0; c < 3; c++) acc[c] += v[size_t(j * 3 + c)];
                kk++;
            }
            if (kk == 0) continue;
            for (int c = 0; c < 3; c++) v[size_t(i * 3 + c)] = acc[c] / float(kk);
            next[size_t(i)] = 1;
            grew = true;
        }
        known = next;
        if (!grew) break;
    }
    cachedKey = key;
    cached = FilmLut::of(n, std::move(v), set.stock->name + " grain");
    return cached;
}

} // namespace FilmSim

// ───── FilmChain: the chosen LUT, a film's display chain, the photo's scene table

namespace FilmChain {
constexpr const char* PREFIX = "chain:";

struct State { std::string key; float strength = 0; };

/// FilmChain.current of a film snapshot (null: none) and the chosen LUT ("" none) at its strength
inline State current(const FilmSettings* sim, const std::string& lut, float lutStrength) {
    if (!sim) return {lut, lutStrength};
    return {std::string(PREFIX) + sim->lutKey() + "|" + jfmt::fixed(sim->strength, 2) + "|" + lut + "|"
                + jfmt::fixed(lut.empty() ? 0.f : lutStrength, 2), 1.f};
}

/// FilmChain.sample: a LUT at a colour (its domain, trilinear)
inline std::array<float, 3> sample(const FilmLut& l, const float* rgb) {
    const int n = l.size;
    float p[3], t[3];
    int i0[3];
    for (int ch = 0; ch < 3; ch++) {
        const float range = l.domainMax[size_t(ch)] - l.domainMin[size_t(ch)];
        p[ch] = std::max(0.f, std::min(1.f, range > 0 ? (rgb[ch] - l.domainMin[size_t(ch)]) / range : 0.f)) * float(n - 1);
        i0[ch] = std::min(n - 2, int(std::floor(double(p[ch]))));
        t[ch] = p[ch] - float(i0[ch]);
    }
    std::array<float, 3> out{0, 0, 0};
    for (int dz = 0; dz < 2; dz++) for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
        const float w = (dx == 1 ? t[0] : 1 - t[0]) * (dy == 1 ? t[1] : 1 - t[1]) * (dz == 1 ? t[2] : 1 - t[2]);
        const size_t idx = size_t((((i0[2] + dz) * n + (i0[1] + dy)) * n + (i0[0] + dx)) * 3);
        for (int ch = 0; ch < 3; ch++) out[size_t(ch)] += w * l.rgb[idx + size_t(ch)];
    }
    return out;
}

/// FilmChain.compose: x -> mix(x, film(x), filmStrength) -> mix(y, user(y), userStrength) on the film LUT's grid
inline FilmLutP compose(const FilmLut& film, float filmStrength, const FilmLut* user, float userStrength) {
    const int n = film.size;
    std::vector<float> v(film.rgb.size());
    float c[3];
    size_t k = 0;
    for (int b = 0; b < n; b++) for (int g = 0; g < n; g++) for (int r = 0; r < n; r++) {
        const float in[3] = {float(r) / float(n - 1), float(g) / float(n - 1), float(b) / float(n - 1)};
        for (int ch = 0; ch < 3; ch++) c[ch] = in[ch] + (film.rgb[k + size_t(ch)] - in[ch]) * filmStrength;
        if (user && userStrength > 0.f) {
            const std::array<float, 3> u = sample(*user, c);
            for (int ch = 0; ch < 3; ch++) c[ch] += (u[size_t(ch)] - c[ch]) * userStrength;
        }
        for (int ch = 0; ch < 3; ch++) v[k + size_t(ch)] = std::max(0.f, std::min(1.f, c[ch]));
        k += 3;
    }
    return FilmLut::of(n, std::move(v), film.title + (user && userStrength > 0.f ? " + " + user->title : ""));
}

inline size_t lutFields(const std::vector<std::string>& parts) { return parts.size() >= 12 && FilmAdvanced::isToken(parts[8]) ? 9 : 8; }

/// FilmChain.lut: the LUT of a key of current (a chain built once per key); null: none
inline FilmLutP lut(const std::string& key) {
    if (key.empty()) return nullptr;
    if (key.rfind(PREFIX, 0) != 0) return FilmLutFiles::chosen(key);
    static std::mutex lock;
    static std::string cachedKey;
    static FilmLutP cached;
    std::lock_guard<std::mutex> g(lock);
    if (key == cachedKey) return cached;
    const std::vector<std::string> parts = jfmt::split(key.substr(std::strlen(PREFIX)), '|', true);
    const size_t fields = lutFields(parts);
    std::optional<FilmSettings> sim;
    if (parts.size() >= fields + 3) {
        std::string head;
        for (size_t i = 0; i < fields; i++) head += (i ? "|" : "") + parts[i];
        sim = FilmSettings::forLut(head, FilmSim::number(parts[fields], 1.f));
    }
    FilmLutP out;
    if (sim) {
        const FilmLutP film = FilmSim::lut(*sim, FilmLooks::SIZE);
        const std::string lutName = parts[parts.size() - 2];
        const float lutStrength = lutName.empty() ? 0.f : FilmSim::number(parts[parts.size() - 1], 0.f);
        const FilmLutP user = lutName.empty() ? nullptr : FilmLutFiles::chosen(lutName);
        out = compose(*film, sim->strength, user.get(), lutStrength);
    }
    cachedKey = key;
    cached = out;
    return out;
}

/// The scene table of the photo: the table, the shader's film strength, its key
struct Scene { FilmLutP lut; float strength = 1; std::string key; };

inline std::string sceneKey(const FilmSettings& sim, const std::string& user, float userStrength) {
    return "scene:" + sim.lutKey() + "|" + user + "|" + jfmt::fixed(user.empty() ? 0.f : userStrength, 2) + "|"
           + Pref::javaFloat(FilmSim::LOG_LO) + "|" + Pref::javaFloat(FilmSim::LOG_SPAN);
}

/// FilmChain.scene: the scene table of a film snapshot with the chosen LUT on its print; null when the film's data cannot be read
inline std::optional<Scene> scene(const FilmSettings& sim, const std::string& user, float userStrength) {
    static std::mutex lock;
    static std::string sceneCachedKey, filmCachedKey;
    static FilmLutP sceneCached, filmCached;
    std::lock_guard<std::mutex> g(lock);
    const std::string key = sceneKey(sim, user, userStrength);
    if (key != sceneCachedKey) {
        const std::string filmKey = "scene:" + sim.lutKey() + "|" + Pref::javaFloat(FilmSim::LOG_LO) + "|" + Pref::javaFloat(FilmSim::LOG_SPAN);
        if (filmKey != filmCachedKey) {
            filmCached = FilmSim::lutScene(sim, FilmSim::SCENE_SIZE);
            filmCachedKey = filmKey;
        }
        FilmLutP table;
        if (filmCached) {
            const FilmLutP userLut = user.empty() || !(userStrength > 0.f) ? nullptr : FilmLutFiles::chosen(user);
            table = !userLut ? filmCached : compose(*filmCached, 1.f, userLut.get(), userStrength);
        }
        sceneCachedKey = key;
        sceneCached = table;
    }
    if (!sceneCached) return std::nullopt;
    return Scene{sceneCached, sim.strength, key};
}
} // namespace FilmChain

// ───── FinishedLook (P619): the camera's LUT and film colour on a finished picture (the editor's «LUT» tab)

namespace FinishedLook {
struct Table { std::string key; FilmLutP lut; bool reference = false; };

inline std::string key(const FilmSettings* film, const std::string& lut, float lutStrength) {
    const bool user = !lut.empty() && lutStrength > 0.f;
    if (!film) return user ? "lut:" + lut : "";
    return "film:" + film->lutKey() + "|" + (user ? lut : "") + "|" + jfmt::fixed(user ? lutStrength : 0.f, 2);
}
inline float shaderStrength(const FilmSettings* film, float lutStrength) {
    return film ? std::max(0.f, std::min(1.f, film->strength)) : std::max(0.f, std::min(1.f, lutStrength));
}

/// FinishedLook.user: the camera's table of a stored LUT value (FilmLut.load), parsed once (a small LRU)
inline FilmLutP user(const std::string& name) {
    if (name.empty()) return nullptr;
    static std::mutex lock;
    static std::list<std::pair<std::string, FilmLutP>> cache;
    {
        std::lock_guard<std::mutex> g(lock);
        for (auto it = cache.begin(); it != cache.end(); ++it)
            if (it->first == name) { auto hit = *it; cache.erase(it); cache.push_back(hit); return hit.second; }
    }
    const FilmLutP l = FilmLutFiles::load(name);
    std::lock_guard<std::mutex> g(lock);
    cache.emplace_back(name, l);
    while (cache.size() > 6) cache.pop_front();
    return l;
}
inline void forget(const std::string& name) { (void)name; }

/// FinishedLook.legacy: the first phase's film table (FilmSim.lut of the display colour, FilmLooks.SIZE a side), once per lutKey
inline FilmLutP legacy(const FilmSettings& film) {
    static std::mutex lock;
    static std::list<std::pair<std::string, FilmLutP>> cache;
    const std::string k = film.lutKey();
    {
        std::lock_guard<std::mutex> g(lock);
        for (auto it = cache.begin(); it != cache.end(); ++it)
            if (it->first == k) { auto hit = *it; cache.erase(it); cache.push_back(hit); return hit.second; }
    }
    const FilmLutP l = FilmSim::lut(film, FilmLooks::SIZE);
    std::lock_guard<std::mutex> g(lock);
    cache.emplace_back(k, l);
    while (cache.size() > 6) cache.pop_front();
    return l;
}
inline FilmLutP printed(const FilmLutP& film, const FilmLutP& userLut, float lutStrength) {
    return userLut && lutStrength > 0.f ? FilmChain::compose(*film, 1.f, userLut.get(), lutStrength) : film;
}

/// FinishedLook.load / blocking without the reference model's DISPLAY table (FilmPreviewLut renders it with the Vulkan film engine,
/// which this build does not have: Android without the engine ends at the same first-phase table). Null: nothing to apply, or a
/// LUT that cannot be read.
inline std::optional<Table> blocking(const FilmSettings* film, const std::string& lut, float lutStrength) {
    const std::string k = key(film, lut, lutStrength);
    if (k.empty()) return std::nullopt;
    const bool withUser = !lut.empty() && lutStrength > 0.f;
    const FilmLutP u = withUser ? user(lut) : nullptr;
    if (!film) {
        if (u) return Table{k, u, false};
        return std::nullopt;
    }
    if (withUser && !u) return std::nullopt;
    const FilmLutP first = legacy(*film);
    if (!first) return std::nullopt;
    return Table{k, printed(first, u, lutStrength), false};
}
} // namespace FinishedLook

} // namespace scam_post
