// A small JSON reader for the burst metadata (.json beside the burst: params, prefs, dev): the values org.json gives the
// Android lab (ScamLabParams / ScamLabRun). Numbers keep their text (Java parses "NaN" / "Infinity" strings as numbers).
#pragma once

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace scam_post {

struct Json {
    enum class Kind { Null, Bool, Number, String, Array, Object };
    Kind kind = Kind::Null;
    bool b = false;
    double n = 0;
    std::string s;
    std::vector<Json> a;
    std::map<std::string, Json> o;

    bool isNull() const { return kind == Kind::Null; }
    bool has(const std::string& key) const { return kind == Kind::Object && o.count(key) != 0; }
    const Json& operator[](const std::string& key) const {
        static const Json none;
        auto it = o.find(key);
        return it == o.end() ? none : it->second;
    }
    const Json& operator[](size_t i) const { return a.at(i); }
    size_t size() const { return kind == Kind::Array ? a.size() : o.size(); }

    // org.json JSONObject.getDouble / number(): a number, or a string Double.parseDouble reads ("NaN", "Infinity")
    double number() const {
        if (kind == Kind::Number) return n;
        if (kind == Kind::Bool) throw std::runtime_error("json: boolean is not a number");
        if (kind == Kind::String) {
            if (s == "NaN") return NAN;
            if (s == "Infinity") return INFINITY;
            if (s == "-Infinity") return -INFINITY;
            char* end = nullptr;
            const double v = std::strtod(s.c_str(), &end);
            if (end == s.c_str()) throw std::runtime_error("json: not a number: " + s);
            return v;
        }
        throw std::runtime_error("json: not a number");
    }
    int integer() const { return int(number()); }
    bool boolean() const {
        if (kind == Kind::Bool) return b;
        if (kind == Kind::String && (s == "true" || s == "false")) return s == "true";
        throw std::runtime_error("json: not a boolean");
    }
    // org.json toString() of a value: the text of a string, the number as Java prints it otherwise
    std::string text() const {
        if (kind == Kind::String) return s;
        if (kind == Kind::Bool) return b ? "true" : "false";
        if (kind == Kind::Number) {
            if (n == std::floor(n) && std::fabs(n) < 1e15) return std::to_string(int64_t(n));
            char buf[32];
            std::snprintf(buf, sizeof buf, "%.17g", n);
            return buf;
        }
        return "null";
    }

    static Json parse(const std::string& text) {
        size_t i = 0;
        Json v = value(text, i);
        skip(text, i);
        if (i != text.size()) throw std::runtime_error("json: trailing characters at " + std::to_string(i));
        return v;
    }

private:
    static void skip(const std::string& t, size_t& i) {
        while (i < t.size() && (t[i] == ' ' || t[i] == '\n' || t[i] == '\r' || t[i] == '\t')) ++i;
    }
    static void expect(const std::string& t, size_t& i, char c) {
        skip(t, i);
        if (i >= t.size() || t[i] != c) throw std::runtime_error(std::string("json: expected '") + c + "' at " + std::to_string(i));
        ++i;
    }
    static void utf8(std::string& out, uint32_t c) {
        if (c < 0x80) out += char(c);
        else if (c < 0x800) { out += char(0xC0 | (c >> 6)); out += char(0x80 | (c & 63)); }
        else if (c < 0x10000) { out += char(0xE0 | (c >> 12)); out += char(0x80 | ((c >> 6) & 63)); out += char(0x80 | (c & 63)); }
        else {
            out += char(0xF0 | (c >> 18)); out += char(0x80 | ((c >> 12) & 63));
            out += char(0x80 | ((c >> 6) & 63)); out += char(0x80 | (c & 63));
        }
    }
    static uint32_t hex4(const std::string& t, size_t& i) {
        if (i + 4 > t.size()) throw std::runtime_error("json: bad \\u escape");
        const uint32_t c = uint32_t(std::strtoul(t.substr(i, 4).c_str(), nullptr, 16));
        i += 4;
        return c;
    }
    static std::string string(const std::string& t, size_t& i) {
        expect(t, i, '"');
        std::string out;
        while (i < t.size() && t[i] != '"') {
            char c = t[i++];
            if (c != '\\') { out += c; continue; }
            if (i >= t.size()) break;
            c = t[i++];
            switch (c) {
                case 'n': out += '\n'; break;
                case 't': out += '\t'; break;
                case 'r': out += '\r'; break;
                case 'b': out += '\b'; break;
                case 'f': out += '\f'; break;
                case 'u': {
                    uint32_t u = hex4(t, i);
                    if (u >= 0xD800 && u < 0xDC00 && i + 1 < t.size() && t[i] == '\\' && t[i + 1] == 'u') {
                        i += 2;
                        const uint32_t lo = hex4(t, i);
                        u = 0x10000 + ((u - 0xD800) << 10) + (lo - 0xDC00);
                    }
                    utf8(out, u);
                    break;
                }
                default: out += c;
            }
        }
        expect(t, i, '"');
        return out;
    }
    static Json value(const std::string& t, size_t& i) {
        skip(t, i);
        if (i >= t.size()) throw std::runtime_error("json: unexpected end");
        Json v;
        const char c = t[i];
        if (c == '{') {
            v.kind = Kind::Object;
            ++i;
            skip(t, i);
            if (i < t.size() && t[i] == '}') { ++i; return v; }
            for (;;) {
                std::string key = string(t, i);
                expect(t, i, ':');
                v.o[key] = value(t, i);
                skip(t, i);
                if (i < t.size() && t[i] == ',') { ++i; continue; }
                expect(t, i, '}');
                return v;
            }
        }
        if (c == '[') {
            v.kind = Kind::Array;
            ++i;
            skip(t, i);
            if (i < t.size() && t[i] == ']') { ++i; return v; }
            for (;;) {
                v.a.push_back(value(t, i));
                skip(t, i);
                if (i < t.size() && t[i] == ',') { ++i; continue; }
                expect(t, i, ']');
                return v;
            }
        }
        if (c == '"') { v.kind = Kind::String; v.s = string(t, i); return v; }
        if (t.compare(i, 4, "true") == 0) { i += 4; v.kind = Kind::Bool; v.b = true; return v; }
        if (t.compare(i, 5, "false") == 0) { i += 5; v.kind = Kind::Bool; return v; }
        if (t.compare(i, 4, "null") == 0) { i += 4; return v; }
        const char* start = t.c_str() + i;
        char* end = nullptr;
        v.n = std::strtod(start, &end);
        if (end == start) throw std::runtime_error("json: bad value at " + std::to_string(i));
        v.kind = Kind::Number;
        v.s.assign(start, size_t(end - start));
        i += size_t(end - start);
        return v;
    }
};

} // namespace scam_post
