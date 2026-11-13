// The Android settings as the post pipeline reads them (SettingsManager / PreferenceKeys / SettingsNumericRules /
// PreferenceNumber / MotionMode): the stored preferences of the shot (the burst json's "prefs", or the iOS app's) and the
// scam_dev.txt lines ("dev"). One Settings is current per render, as Android's static PreferenceKeys.
#pragma once

#include "Json.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <map>
#include <optional>
#include <regex>
#include <set>
#include <string>
#include <vector>

namespace scam_post {

struct BoundRow { const char* key; double min, max; int integer; };
struct ListBoundRow { const char* key; int count; double min, max; int integer; };
struct DefaultRow { const char* key; float value; };
#include "SettingsTables.inc"

// A stored preference as SharedPreferences returns it: Boolean, Integer, Float, Long, String or a String set.
struct Pref {
    enum class Type { Bool, Int, Float, Long, String, Set } type = Type::String;
    bool b = false;
    double n = 0;
    std::string s;
    std::vector<std::string> set;

    static Pref string(std::string v) { Pref p; p.s = std::move(v); return p; }
    static Pref boolean(bool v) { Pref p; p.type = Type::Bool; p.b = v; return p; }
    static Pref integer(int v) { Pref p; p.type = Type::Int; p.n = v; return p; }
    static Pref real(float v) { Pref p; p.type = Type::Float; p.n = v; return p; }

    // Object.toString() of the value
    std::string text() const {
        switch (type) {
            case Type::Bool: return b ? "true" : "false";
            case Type::Int: case Type::Long: return std::to_string(int64_t(n));
            case Type::Float: return javaFloat(float(n));
            case Type::String: return s;
            case Type::Set: {
                std::string out = "[";
                for (size_t i = 0; i < set.size(); ++i) out += (i ? ", " : "") + set[i];
                return out + "]";
            }
        }
        return s;
    }

    // Float.toString: the shortest decimal that reads back as the same float, at least one fraction digit
    static std::string javaFloat(float v) {
        if (std::isnan(v)) return "NaN";
        if (std::isinf(v)) return v > 0 ? "Infinity" : "-Infinity";
        char buf[48];
        for (int digits = 1; digits <= 9; ++digits) {
            std::snprintf(buf, sizeof buf, "%.*g", digits, double(v));
            if (std::strtof(buf, nullptr) == v) break;
        }
        std::string out = buf;
        if (out.find_first_of(".e") == std::string::npos) out += ".0";
        return out;
    }
};

// PreferenceNumber.read: a number of a Boolean (1 / 0), a Number or a text ("," as the decimal point); fallback otherwise
inline double readNumber(const std::optional<Pref>& value, double fallback) {
    if (!value) return fallback;
    if (value->type == Pref::Type::Bool) return value->b ? 1 : 0;
    if (value->type == Pref::Type::Int || value->type == Pref::Type::Long || value->type == Pref::Type::Float)
        return std::isfinite(value->n) ? value->n : fallback;
    std::string t = value->text();
    const size_t a = t.find_first_not_of(" \t\n\r"), b = t.find_last_not_of(" \t\n\r");
    if (a == std::string::npos) return fallback;
    t = t.substr(a, b - a + 1);
    for (char& c : t) if (c == ',') c = '.';
    // Double.parseDouble: the whole text, optionally with a trailing d / f type suffix
    if (!t.empty() && (t.back() == 'd' || t.back() == 'D' || t.back() == 'f' || t.back() == 'F')) t.pop_back();
    if (t == "NaN" || t == "+NaN" || t == "-NaN") return fallback;
    if (t == "Infinity" || t == "+Infinity" || t == "-Infinity") return fallback;
    char* end = nullptr;
    const double v = std::strtod(t.c_str(), &end);
    if (end == t.c_str() || *end != 0) return fallback;
    // strtod takes hex and "inf" / "nan", parseDouble does not (except hex with a p exponent: not in the settings)
    if (t.find_first_of("xXiInN") != std::string::npos) return fallback;
    return std::isfinite(v) ? v : fallback;
}
inline double readNumber(const std::string& text, double fallback) { return readNumber(std::optional<Pref>(Pref::string(text)), fallback); }

// PreferenceNumber.bool
inline bool readBool(const std::optional<Pref>& value, bool fallback) {
    if (value && value->type == Pref::Type::Bool) return value->b;
    if (value) {
        std::string t = value->text();
        for (char& c : t) c = char(std::tolower(static_cast<unsigned char>(c)));
        if (t == "true") return true;
        if (t == "false") return false;
    }
    return readNumber(value, fallback ? 1 : 0) != 0;
}

class Settings {
public:
    std::map<std::string, Pref> prefs;
    std::map<std::string, float> dev;
    // MotionMode.Snapshot of the shot (null: the stored switch)
    std::optional<bool> motionShot;

    // ScamLabRun: the shot's prefs (ScamLabParams.prefs, BrandMigration.whyMap) and dev lines (devOf) of the burst json
    static Settings fromJson(const Json& meta) {
        Settings s;
        const Json& p = meta["prefs"];
        std::map<std::string, Pref> raw;
        for (const auto& [key, e] : p.o) {
            const std::string t = e[0].text();
            const Json& v = e[1];
            Pref pref;
            if (t == "b") pref = Pref::boolean(v.boolean());
            else if (t == "i") pref = Pref::integer(v.integer());
            else if (t == "f") pref = Pref::real(float(v.number()));
            else if (t == "l") { pref.type = Pref::Type::Long; pref.n = v.number(); }
            else if (t == "set") { pref.type = Pref::Type::Set; for (const Json& x : v.a) pref.set.push_back(x.text()); }
            else pref = Pref::string(v.text());
            raw[key] = pref;
        }
        for (const auto& [key, value] : raw) {
            const std::string k = why(key);
            if (k != key && raw.count(k)) continue;
            s.prefs[k] = value;
        }
        for (const auto& [key, v] : meta["dev"].o) {
            if (v.kind == Json::Kind::Number || v.kind == Json::Kind::String) {
                try { const double d = v.number(); if (!std::isnan(d)) s.dev[why(key)] = float(d); } catch (...) {}
            }
        }
        // ScamLabRun.basePrefs: never the diagnostics, the kept bursts, the lab's own switch or the merge stamp
        s.prefs["pref_scam_hybrid_diagnostics"] = Pref::boolean(false);
        s.prefs["pref_keep_raw_burst"] = Pref::boolean(false);
        return s;
    }

    // BrandMigration.why: the old name segment of a key under the new one
    static std::string why(const std::string& key) {
        static const std::regex segment(std::string("(^|[^A-Za-z0-9])") + reversed("kra") + "(?![A-Za-z0-9])");
        return std::regex_replace(key, segment, "$1why");
    }

    static Settings& current() { return *slot(); }
    // the settings of one render (the previous ones back at the end of the scope)
    struct Scope {
        Settings* previous;
        explicit Scope(Settings& s) : previous(slot()) { slot() = &s; }
        ~Scope() { slot() = previous; }
    };

    std::optional<Pref> get(const std::string& key) const {
        auto it = prefs.find(key);
        if (it == prefs.end()) return std::nullopt;
        return it->second;
    }
    bool contains(const std::string& key) const { return prefs.count(key) != 0; }

    // SettingsManager.getString: a Boolean as "1" / "0", anything else its text
    std::string getString(const std::string& key, const std::string& fallback) const {
        auto v = get(key);
        if (!v) return fallback;
        if (v->type == Pref::Type::Bool) return v->b ? "1" : "0";
        return v->text();
    }
    bool getBoolean(const std::string& key, bool fallback) const { return readBool(get(key), fallback); }
    int getInteger(const std::string& key, int fallback) const {
        return int(readNumber(getString(key, std::to_string(fallback)), fallback));
    }
    float getFloat(const std::string& key, float fallback) const {
        return float(readNumber(getString(key, Pref::javaFloat(fallback)), fallback));
    }

    // scam_dev.txt
    std::optional<float> scamDevValue(const std::string& key) const {
        auto it = dev.find(key);
        if (it == dev.end()) return std::nullopt;
        return it->second;
    }
    bool scamDevSwitch(const std::string& key, bool fallback) const {
        auto v = scamDevValue(key);
        return v ? *v != 0.f : fallback;
    }
    bool scamDevOverrides(const std::string& key) const { return scamDevValue(key).has_value(); }
    float scamDevNumber(const std::string& key, float fallback) const {
        auto v = scamDevValue(key);
        return v ? *v : fallback;
    }

    // SettingsNumericRules.bounds: {min, max, integer}
    static std::optional<std::array<double, 3>> bounds(const std::string& key) {
        for (const BoundRow& r : kBounds) if (key == r.key) return std::array<double, 3>{r.min, r.max, double(r.integer)};
        static const std::string hybrid = "pref_scam_hybrid_";
        if (key.rfind(hybrid + "colour_", 0) == 0) {
            const std::string k = key.substr(hybrid.size() + 7);
            auto starts = [&](const char* p) { return k.rfind(p, 0) == 0; };
            auto ends = [&](const std::string& p) { return k.size() >= p.size() && k.compare(k.size() - p.size(), p.size(), p) == 0; };
            if (starts("mix_") || starts("cal_") || k == "warmth" || k == "tint" || k == "skin_hue" || k == "grade_balance")
                return std::array<double, 3>{-100, 100, 1};
            if (starts("vib_")) return std::array<double, 3>{-1, 1, 0};
            if (ends("_hue")) return std::array<double, 3>{0, 360, 1};
            if (k == "keep_warm" || k == "keep_cool" || k == "skin_protect" || k == "skin_even" || k == "gamut" || ends("_str")
                || k == "grade_blend") return std::array<double, 3>{0, 100, 1};
            return std::nullopt;
        }
        static const std::regex snr("pref_scam_hybrid_dn_(luma_t[1-5]|chroma_t[1-4])_snr");
        if (std::regex_match(key, snr)) return std::array<double, 3>{0.1, 500, 0};
        if (key.rfind(hybrid, 0) == 0) {
            const std::string k = key.substr(hybrid.size());
            if (k.rfind("agx_", 0) == 0 || k.rfind("ae_", 0) == 0 || k == "enabled") return std::nullopt;
            return bounds("pref_scamhdr_" + k);
        }
        for (const BoundRow& r : kBoundsLate) if (key == r.key) return std::array<double, 3>{r.min, r.max, double(r.integer)};
        return std::nullopt;
    }
    // SettingsNumericRules.value
    static double value(const std::string& key, const std::optional<Pref>& input, double fallback) {
        double v = readNumber(input, fallback);
        if (auto b = bounds(key)) {
            v = std::max((*b)[0], std::min((*b)[1], v));
            if ((*b)[2] == 1) v = std::nearbyint(v); // Math.rint: half to even (the default rounding mode)
        }
        return v;
    }
    // SettingsNumericRules.listValue
    static std::optional<std::vector<float>> listValue(const std::string& key, const std::string& input,
                                                       const std::optional<std::vector<float>>& fallback) {
        // String.split("[;,\\s]+") of the trimmed text: a leading separator gives an empty first part, trailing ones none
        const std::string text = trim(input);
        if (text.empty()) return fallback;
        auto separator = [](char c) { return c == ';' || c == ',' || std::isspace(static_cast<unsigned char>(c)); };
        std::vector<std::string> parts(1);
        for (size_t i = 0; i < text.size(); ++i) {
            if (!separator(text[i])) { parts.back() += text[i]; continue; }
            while (i + 1 < text.size() && separator(text[i + 1])) ++i;
            parts.emplace_back();
        }
        while (parts.size() > 1 && parts.back().empty()) parts.pop_back();
        const ListBoundRow* b = nullptr;
        for (const ListBoundRow& r : kListBounds) if (key == r.key) b = &r;
        const size_t count = b ? size_t(b->count) : fallback ? fallback->size() : parts.size();
        if (parts.size() != count) return fallback;
        std::vector<float> out(count);
        for (size_t i = 0; i < count; ++i) {
            double v = readNumber(parts[i], NAN);
            if (!std::isfinite(v)) return fallback;
            if (b) { v = std::max(b->min, std::min(b->max, v)); if (b->integer == 1) v = std::nearbyint(v); }
            out[i] = float(v);
        }
        return out;
    }

    // ───── PreferenceKeys ─────
    static constexpr const char* HYBRID_PREFIX = "pref_scam_hybrid_";

    bool hdrFrames() const { return scamDevSwitch("hdr_frames", getBoolean("pref_hdr_frames", true)); }
    bool isMotionMode() const { return motionShot ? *motionShot : getBoolean("pref_motion_mode", false); }
    bool oldstyleMode() const { return getBoolean("pref_oldstyle_mode", false); }
    bool noHdrFrames() const { return isMotionMode() || !hdrFrames() || oldstyleMode(); }

    static std::optional<float> hybridDefaultOf(const std::string& key) {
        for (const DefaultRow& r : kHybridDefaults) if (key == r.key) return r.value;
        return std::nullopt;
    }
    static std::optional<float> motionDefault(const std::string& key) {
        for (const DefaultRow& r : kMotionDefaults) if (key == r.key) return r.value;
        return std::nullopt;
    }

    float hybridValue(const std::string& key, float fallback) const {
        if (noHdrFrames() && (key == "bento" || key == "shasta")) return 0.f;
        if (auto o = scamDevValue("hybrid_" + key)) return *o;
        if (auto m = motionDefault(key); m && isMotionMode()) return hybridStored("mm_" + key, *m);
        return hybridStored(key, fallback);
    }
    bool hybridSwitch(const std::string& key, bool fallback) const {
        if (noHdrFrames() && (key == "bento" || key == "shasta")) return false;
        if (auto o = scamDevValue("hybrid_" + key)) return *o > 0.f;
        if (auto m = motionDefault(key); m && isMotionMode()) return hybridStoredSwitch("mm_" + key, *m > 0.f);
        return hybridStoredSwitch(key, fallback);
    }
    std::string hybridString(const std::string& key, const std::string& fallback) const {
        static const std::map<std::string, std::vector<std::string>> devLists = {
            {"dn_strength_map", {"auto", "frames", "uniform"}},
            {"fringe_method", {"neural", "classic"}},
            {"tone_version", {"v1", "v2"}},
        };
        auto list = devLists.find(key);
        if (list != devLists.end()) {
            if (auto d = scamDevValue("hybrid_" + key)) {
                const long i = javaRound(*d);
                if (i >= 0 && i < long(list->second.size())) return list->second[size_t(i)];
            }
        }
        const std::string v = trim(getString(HYBRID_PREFIX + key, fallback));
        return v.empty() ? fallback : v;
    }
    std::optional<std::vector<float>> hybridList(const std::string& key, const std::optional<std::vector<float>>& fallback) const {
        const std::string full = HYBRID_PREFIX + key;
        return listValue(full, getString(full, ""), fallback);
    }

    static std::string trim(const std::string& s) {
        const size_t a = s.find_first_not_of(" \t\n\r"), b = s.find_last_not_of(" \t\n\r");
        return a == std::string::npos ? std::string() : s.substr(a, b - a + 1);
    }
    // Math.round(float): floor(x + 0.5)
    static long javaRound(float v) { return std::isnan(v) ? 0 : long(std::floor(double(v) + 0.5)); }

private:
    float hybridStored(const std::string& key, float fallback) const {
        const float def = hybridDefaultOf(key).value_or(fallback);
        const std::string full = HYBRID_PREFIX + key;
        const std::string v = trim(getString(full, ""));
        return v.empty() ? def : float(value(full, Pref::string(v), def));
    }
    bool hybridStoredSwitch(const std::string& key, bool fallback) const {
        const bool def = hybridDefaultOf(key).value_or(fallback ? 1.f : 0.f) > 0.f;
        return getBoolean(HYBRID_PREFIX + key, def);
    }
    static std::string reversed(std::string s) { return std::string(s.rbegin(), s.rend()); }
    static Settings*& slot() {
        static Settings empty;
        static thread_local Settings* current = &empty;
        return current;
    }
};

} // namespace scam_post
