// A small JSON reader for the editor's state text (EditState.toJson / fromJson, org.json on Android): objects, arrays, numbers
// (parsed as double, as JSONObject.getDouble), strings, true / false / null. Writing goes through EditState::toJson.
#pragma once

#include <cstdio>
#include <cstdlib>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace scam_edit {

struct JValue {
    enum Kind { NUL, BOOL, NUM, STR, ARR, OBJ } kind = NUL;
    bool b = false;
    double n = 0;
    std::string s;
    std::vector<JValue> a;
    std::vector<std::pair<std::string, JValue>> o;

    const JValue* get(const std::string& key) const {
        if (kind != OBJ) return nullptr;
        for (const auto& kv : o) if (kv.first == key) return &kv.second;
        return nullptr;
    }
    const JValue* obj(const std::string& key) const { const JValue* v = get(key); return v && v->kind == OBJ ? v : nullptr; }
    const JValue* arr(const std::string& key) const { const JValue* v = get(key); return v && v->kind == ARR ? v : nullptr; }
    /// optInt / optDouble / optBoolean / optString of org.json: the value when it is one (a number for the numbers), else the fallback
    double optDouble(const std::string& key, double fallback) const {
        const JValue* v = get(key);
        if (!v) return fallback;
        if (v->kind == NUM) return v->n;
        if (v->kind == STR) { char* end = nullptr; const double d = std::strtod(v->s.c_str(), &end); return end && *end == 0 && !v->s.empty() ? d : fallback; }
        return fallback;
    }
    int optInt(const std::string& key, int fallback) const {
        const JValue* v = get(key);
        if (!v || (v->kind != NUM && v->kind != STR)) return fallback;
        const double d = optDouble(key, double(fallback));
        return int(d); // JSONObject.optInt: (int) of the double
    }
    bool optBool(const std::string& key, bool fallback) const {
        const JValue* v = get(key);
        if (!v) return fallback;
        if (v->kind == BOOL) return v->b;
        if (v->kind == STR) { if (v->s == "true") return true; if (v->s == "false") return false; }
        return fallback;
    }
    std::string optString(const std::string& key, const std::string& fallback) const {
        const JValue* v = get(key);
        if (!v || v->kind == NUL) return fallback;
        if (v->kind == STR) return v->s;
        return fallback;
    }
};

class JParser {
public:
    explicit JParser(const std::string& t) : t_(t) {}
    JValue parse() {
        JValue v = value();
        ws();
        if (i_ != t_.size()) throw std::runtime_error("json: trailing text");
        return v;
    }

private:
    const std::string& t_;
    size_t i_ = 0;

    void ws() { while (i_ < t_.size() && (t_[i_] == ' ' || t_[i_] == '\n' || t_[i_] == '\r' || t_[i_] == '\t')) i_++; }
    char peek() { ws(); if (i_ >= t_.size()) throw std::runtime_error("json: end"); return t_[i_]; }
    void expect(char c) { if (peek() != c) throw std::runtime_error(std::string("json: expected ") + c); i_++; }

    static void utf8(std::string& out, unsigned cp) {
        if (cp < 0x80) out += char(cp);
        else if (cp < 0x800) { out += char(0xC0 | (cp >> 6)); out += char(0x80 | (cp & 0x3F)); }
        else if (cp < 0x10000) { out += char(0xE0 | (cp >> 12)); out += char(0x80 | ((cp >> 6) & 0x3F)); out += char(0x80 | (cp & 0x3F)); }
        else { out += char(0xF0 | (cp >> 18)); out += char(0x80 | ((cp >> 12) & 0x3F)); out += char(0x80 | ((cp >> 6) & 0x3F)); out += char(0x80 | (cp & 0x3F)); }
    }

    std::string str() {
        expect('"');
        std::string out;
        while (true) {
            if (i_ >= t_.size()) throw std::runtime_error("json: string end");
            char c = t_[i_++];
            if (c == '"') break;
            if (c != '\\') { out += c; continue; }
            if (i_ >= t_.size()) throw std::runtime_error("json: escape");
            char e = t_[i_++];
            switch (e) {
            case 'n': out += '\n'; break;
            case 't': out += '\t'; break;
            case 'r': out += '\r'; break;
            case 'b': out += '\b'; break;
            case 'f': out += '\f'; break;
            case 'u': {
                if (i_ + 4 > t_.size()) throw std::runtime_error("json: \\u");
                unsigned cp = unsigned(std::strtoul(t_.substr(i_, 4).c_str(), nullptr, 16));
                i_ += 4;
                if (cp >= 0xD800 && cp < 0xDC00 && i_ + 6 <= t_.size() && t_[i_] == '\\' && t_[i_ + 1] == 'u') {
                    unsigned lo = unsigned(std::strtoul(t_.substr(i_ + 2, 4).c_str(), nullptr, 16));
                    if (lo >= 0xDC00 && lo < 0xE000) { cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00); i_ += 6; }
                }
                utf8(out, cp);
                break;
            }
            default: out += e; break;
            }
        }
        return out;
    }

    JValue value() {
        const char c = peek();
        JValue v;
        if (c == '{') {
            i_++;
            v.kind = JValue::OBJ;
            if (peek() == '}') { i_++; return v; }
            while (true) {
                std::string k = str();
                expect(':');
                JValue x = value();
                bool replaced = false;
                for (auto& kv : v.o) if (kv.first == k) { kv.second = x; replaced = true; }
                if (!replaced) v.o.emplace_back(k, x);
                const char d = peek();
                i_++;
                if (d == '}') break;
                if (d != ',') throw std::runtime_error("json: object");
            }
        } else if (c == '[') {
            i_++;
            v.kind = JValue::ARR;
            if (peek() == ']') { i_++; return v; }
            while (true) {
                v.a.push_back(value());
                const char d = peek();
                i_++;
                if (d == ']') break;
                if (d != ',') throw std::runtime_error("json: array");
            }
        } else if (c == '"') {
            v.kind = JValue::STR;
            v.s = str();
        } else if (t_.compare(i_, 4, "true") == 0) { i_ += 4; v.kind = JValue::BOOL; v.b = true; }
        else if (t_.compare(i_, 5, "false") == 0) { i_ += 5; v.kind = JValue::BOOL; v.b = false; }
        else if (t_.compare(i_, 4, "null") == 0) { i_ += 4; v.kind = JValue::NUL; }
        else {
            const char* start = t_.c_str() + i_;
            char* end = nullptr;
            v.n = std::strtod(start, &end);
            if (end == start) throw std::runtime_error("json: value");
            i_ += size_t(end - start);
            v.kind = JValue::NUM;
        }
        return v;
    }
};

inline JValue parseJson(const std::string& text) { return JParser(text).parse(); }

/// a JSON string literal of s (the characters org.json escapes)
inline std::string quoteJson(const std::string& s) {
    std::string out = "\"";
    for (unsigned char c : s) {
        switch (c) {
        case '"': out += "\\\""; break;
        case '\\': out += "\\\\"; break;
        case '/': out += "\\/"; break;
        case '\n': out += "\\n"; break;
        case '\r': out += "\\r"; break;
        case '\t': out += "\\t"; break;
        case '\b': out += "\\b"; break;
        case '\f': out += "\\f"; break;
        default:
            if (c < 0x20) { char b[8]; std::snprintf(b, sizeof b, "\\u%04x", c); out += b; }
            else out += char(c);
        }
    }
    return out + "\"";
}

} // namespace scam_edit
