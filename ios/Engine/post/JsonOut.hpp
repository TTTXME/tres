// A small JSON writer for what the iPhone stores in org.json's shapes (ScamLabParams.toJson, the develop record's meta): an object's
// members in the order they are put, numbers that read back as the same float / double, NaN and the infinities as the strings
// Java's Double.toString gives (ScamLabParams.finite), which Json::number() and Android's ScamLabParams.number read back.
#pragma once

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <optional>
#include <string>
#include <utility>
#include <vector>

namespace scam_post {

namespace JsonText {
inline std::string quoted(const std::string& s) {
    std::string out = "\"";
    for (unsigned char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof buf, "\\u%04x", c);
                    out += buf;
                } else {
                    out += char(c);
                }
        }
    }
    return out + "\"";
}

inline std::string nonFinite(double v) { return std::isnan(v) ? "\"NaN\"" : v > 0 ? "\"Infinity\"" : "\"-Infinity\""; }

/// the shortest decimal that reads back as the same double
inline std::string number(double v) {
    if (!std::isfinite(v)) return nonFinite(v);
    if (v == std::floor(v) && std::fabs(v) < 1e15) {
        char buf[32];
        std::snprintf(buf, sizeof buf, "%.1f", v);
        return buf;
    }
    char buf[40];
    for (int digits = 1; digits <= 17; ++digits) {
        std::snprintf(buf, sizeof buf, "%.*g", digits, v);
        if (std::strtod(buf, nullptr) == v) break;
    }
    return buf;
}

/// a float as its own shortest decimal (Java writes the float widened to double; both read back as the same float)
inline std::string number(float v) {
    if (!std::isfinite(v)) return nonFinite(v);
    if (v == std::floor(v) && std::fabs(v) < 1e7f) return number(double(v));
    char buf[40];
    for (int digits = 1; digits <= 9; ++digits) {
        std::snprintf(buf, sizeof buf, "%.*g", digits, double(v));
        if (std::strtof(buf, nullptr) == v) break;
    }
    return buf;
}
} // namespace JsonText

/// One JSON object or array being written: put(...) adds a member, text() closes it.
class JsonOut {
public:
    explicit JsonOut(bool array = false) : array_(array) { s_ = array ? "[" : "{"; }

    JsonOut& raw(const std::string& key, const std::string& json) {
        if (!first_) s_ += ',';
        first_ = false;
        if (!array_) s_ += JsonText::quoted(key) + ':';
        s_ += json;
        return *this;
    }
    JsonOut& add(const std::string& json) { return raw("", json); }

    JsonOut& put(const std::string& k, bool v) { return raw(k, v ? "true" : "false"); }
    JsonOut& put(const std::string& k, int v) { return raw(k, std::to_string(v)); }
    JsonOut& put(const std::string& k, int64_t v) { return raw(k, std::to_string(v)); }
    JsonOut& put(const std::string& k, float v) { return raw(k, JsonText::number(v)); }
    JsonOut& put(const std::string& k, double v) { return raw(k, JsonText::number(v)); }
    JsonOut& put(const std::string& k, const std::string& v) { return raw(k, JsonText::quoted(v)); }
    JsonOut& put(const std::string& k, const char* v) { return raw(k, JsonText::quoted(v)); }
    template <class T> JsonOut& put(const std::string& k, const std::vector<T>& v) { return raw(k, arrayText(v)); }
    template <class T> JsonOut& put(const std::string& k, const std::optional<T>& v) {
        if (v) put(k, *v);
        return *this;
    }

    std::string text() const { return s_ + (array_ ? "]" : "}"); }

    template <class T> static std::string arrayText(const std::vector<T>& v) {
        JsonOut a(true);
        for (const T& x : v) a.add(one(x));
        return a.text();
    }

private:
    static std::string one(float v) { return JsonText::number(v); }
    static std::string one(double v) { return JsonText::number(v); }
    static std::string one(int v) { return std::to_string(v); }
    static std::string one(const std::string& v) { return JsonText::quoted(v); }
    std::string s_;
    bool array_;
    bool first_ = true;
};

} // namespace scam_post
