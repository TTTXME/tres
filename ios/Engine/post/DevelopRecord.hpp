// P665: the gallery developer's record (Android processing/DevelopRecord.java + DevelopCapture.record), header-only C++: what the
// developer needs to run the camera's post pipeline again on a DNG of ours, appended at the DNG's very end (TIFF readers never look
// past their offsets). The same bytes as Android: «SCDV», version (int), CRC-32 of the sections (int), then sections {tag (4 ASCII),
// w, h, raw length, deflated length, zlib-wrapped deflate}; after the record its length (int) and «SCDVEND1»; every int big-endian.
// Sections: JSON (the meta: "v", "build", "params" (ScamLabParams.toJson), "prefs" (ScamLabParams.prefs), "dev" (scam_dev lines),
// "worker" (the merge's numbers), "dng" {"headroom", "w", "h"}, "crop" {"zoom", "isz4x3"}), CLIP / EFFR (one byte a pixel) and HLMK
// (IEEE halves, little-endian, half the sensor grid). The platform supplies raw deflate / inflate (Codec): Foundation's on the
// iPhone (Bridge/ScamShot.mm, Bridge/ScamDevelop.mm), zlib in tools/develop_check.cpp.
#pragma once

#include "Json.hpp"
#include "JsonOut.hpp"
#include "Settings.hpp"

#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <functional>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

namespace scam_post {

struct DevelopRecord {
    static constexpr int VERSION = 1;
    static constexpr uint8_t MAGIC[4] = {'S', 'C', 'D', 'V'};
    static constexpr uint8_t END[8] = {'S', 'C', 'D', 'V', 'E', 'N', 'D', '1'};
    static constexpr size_t FOOTER = 4 + 8;
    /// the largest record read back (bytes)
    static constexpr uint64_t MAX_RECORD = 256ull << 20;
    /// the largest output (pixels) whose per-pixel maps are kept
    static constexpr long long MAP_MAX_PIXELS = 24000000LL;

    /// raw DEFLATE both ways (RFC 1951, no zlib header)
    struct Codec {
        std::function<std::vector<uint8_t>(const uint8_t*, size_t)> deflateRaw;
        /// exactly outLen bytes, false when they are not there
        std::function<bool(const uint8_t*, size_t, uint8_t*, size_t)> inflateRaw;
    };

    /// the meta as stored and as read
    std::string metaText = "{}";
    Json meta;
    std::vector<uint8_t> clipFlags, frames, hlMask;
    int clipW = 0, clipH = 0, framesW = 0, framesH = 0, hlW = 0, hlH = 0;

    /// The merge's numbers the post pipeline's nodes read (ScamHybridBurst's statics of the shot).
    struct Worker {
        bool bentoApplied = false;
        float bentoFactor = 1.f, bentoUsClipped = 0.f, effectiveMedian = NAN, noiseFactor = 1.f;
        int normals = 1;
        std::string report;

        std::string toJson() const {
            return JsonOut().put("bento", bentoApplied).put("bentoFactor", bentoFactor).put("bentoUs", bentoUsClipped)
                .put("effMedian", effectiveMedian).put("noiseFactor", noiseFactor).put("normals", normals).put("report", report).text();
        }

        static Worker of(const Json& o) {
            Worker w;
            if (o.kind != Json::Kind::Object) return w;
            w.bentoApplied = boolOf(o["bento"], false);
            w.bentoFactor = numberOf(o["bentoFactor"], 1.f);
            w.bentoUsClipped = numberOf(o["bentoUs"], 0.f);
            w.effectiveMedian = numberOf(o["effMedian"], NAN);
            w.noiseFactor = numberOf(o["noiseFactor"], 1.f);
            w.normals = o["normals"].isNull() ? 1 : int(numberOf(o["normals"], 1.f));
            w.report = o["report"].kind == Json::Kind::String ? o["report"].s : "";
            return w;
        }
    };

    Worker worker() const { return Worker::of(meta["worker"]); }

    /// the DNG's headroom: its 16-bit white is this many times the N frames' white (1 when nothing was above it)
    float headroom() const { return std::max(1.f, numberOf(meta["dng"]["headroom"], 1.f)); }

    /// DevelopRecord.number: a number, or a text that reads as one; otherwise the fallback
    static float numberOf(const Json& v, float otherwise) {
        if (v.isNull() || v.kind == Json::Kind::Bool || v.kind == Json::Kind::Array || v.kind == Json::Kind::Object) return otherwise;
        try {
            return float(v.number());
        } catch (const std::exception&) {
            return otherwise;
        }
    }
    static bool boolOf(const Json& v, bool otherwise) {
        if (v.kind == Json::Kind::Bool) return v.b;
        if (v.kind == Json::Kind::String && (v.s == "true" || v.s == "false")) return v.s == "true";
        return otherwise;
    }

    // ---------------------------------------------------------------- the meta (DevelopCapture.record)

    /// ScamLabParams.prefs: every stored preference with its type ({"s"|"b"|"i"|"f"|"l"|"set", value})
    static std::string prefsJson(const Settings& s) {
        JsonOut o;
        for (const auto& [key, p] : s.prefs) {
            JsonOut e(true);
            switch (p.type) {
                case Pref::Type::Bool: e.add("\"b\"").add(p.b ? "true" : "false"); break;
                case Pref::Type::Int: e.add("\"i\"").add(std::to_string(int64_t(p.n))); break;
                case Pref::Type::Long: e.add("\"l\"").add(std::to_string(int64_t(p.n))); break;
                case Pref::Type::Float: e.add("\"f\"").add(JsonText::number(float(p.n))); break;
                case Pref::Type::Set: e.add("\"set\"").add(JsonOut::arrayText(p.set)); break;
                case Pref::Type::String: e.add("\"s\"").add(JsonText::quoted(p.s)); break;
            }
            o.raw(key, e.text());
        }
        return o.text();
    }

    /// the scam_dev.txt lines in force (finite numbers only)
    static std::string devJson(const Settings& s) {
        JsonOut o;
        for (const auto& [key, v] : s.dev) if (std::isfinite(v)) o.put(key, double(v));
        return o.text();
    }

    /// DevelopCapture.record: params (the pipeline's Parameters as JSON), the settings and dev lines, the merge's numbers, the DNG's
    /// headroom and size, the crops made after the pipeline
    static DevelopRecord make(const std::string& paramsJson, const Settings& s, const Worker& w, float headroom, int dngW, int dngH,
                              float zoomCrop, bool isz4x3, long long build) {
        DevelopRecord r;
        JsonOut dng;
        dng.put("headroom", double(std::max(1.f, headroom))).put("w", dngW).put("h", dngH);
        const std::string dngText = dng.text();
        JsonOut crop;
        crop.put("zoom", zoomCrop > 1.005f && std::isfinite(zoomCrop) ? double(zoomCrop) : 1.0).put("isz4x3", isz4x3);
        JsonOut m;
        m.put("v", VERSION).put("build", int64_t(build)).raw("params", paramsJson).raw("prefs", prefsJson(s)).raw("dev", devJson(s))
            .raw("worker", w.toJson()).raw("dng", dngText).raw("crop", crop.text());
        r.metaText = m.text();
        r.meta = Json::parse(r.metaText);
        return r;
    }

    // ---------------------------------------------------------------- bytes

    /// java.util.zip.CRC32
    static uint32_t crc32(const uint8_t* p, size_t n) {
        static const std::array<uint32_t, 256> table = [] {
            std::array<uint32_t, 256> t{};
            for (uint32_t i = 0; i < 256; i++) {
                uint32_t c = i;
                for (int k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320u ^ (c >> 1) : c >> 1;
                t[i] = c;
            }
            return t;
        }();
        uint32_t c = 0xFFFFFFFFu;
        for (size_t i = 0; i < n; i++) c = table[(c ^ p[i]) & 0xFF] ^ (c >> 8);
        return c ^ 0xFFFFFFFFu;
    }

    static uint32_t adler32(const uint8_t* p, size_t n) {
        uint32_t a = 1, b = 0;
        for (size_t i = 0; i < n; i++) {
            a = (a + p[i]) % 65521u;
            b = (b + a) % 65521u;
        }
        return (b << 16) | a;
    }

    static void putInt(std::vector<uint8_t>& out, uint32_t v) {
        for (int s = 24; s >= 0; s -= 8) out.push_back(uint8_t(v >> s));
    }
    static uint32_t getInt(const uint8_t* p) { return uint32_t(p[0]) << 24 | uint32_t(p[1]) << 16 | uint32_t(p[2]) << 8 | p[3]; }

    /// Deflater(BEST_SPEED)'s stream: the zlib header, the data, Adler-32
    static std::vector<uint8_t> deflate(const Codec& c, const uint8_t* raw, size_t n) {
        if (!c.deflateRaw) throw std::runtime_error("develop record: no deflate");
        std::vector<uint8_t> out{0x78, 0x01};
        const std::vector<uint8_t> d = c.deflateRaw(raw, n);
        out.insert(out.end(), d.begin(), d.end());
        putInt(out, adler32(raw, n));
        return out;
    }

    /// Inflater's read of exactly rawLength bytes (the stream's Adler-32 checked)
    static bool inflate(const Codec& c, const uint8_t* packed, size_t len, size_t rawLength, std::vector<uint8_t>& out) {
        if (!c.inflateRaw || len < 6 || (packed[0] & 0x0F) != 8 || (unsigned(packed[0]) * 256u + packed[1]) % 31u != 0 || (packed[1] & 0x20))
            return false;
        out.assign(rawLength, 0);
        if (rawLength > 0 && !c.inflateRaw(packed + 2, len - 6, out.data(), rawLength)) return false;
        return getInt(packed + len - 4) == adler32(out.data(), out.size());
    }

    static void section(std::vector<uint8_t>& out, const Codec& c, const char* tag, int w, int h, const uint8_t* raw, size_t n) {
        const std::vector<uint8_t> packed = deflate(c, raw, n);
        out.insert(out.end(), tag, tag + 4);
        putInt(out, uint32_t(w));
        putInt(out, uint32_t(h));
        putInt(out, uint32_t(n));
        putInt(out, uint32_t(packed.size()));
        out.insert(out.end(), packed.begin(), packed.end());
    }

    /// DevelopRecord.toBytes: the record as appended (without the footer)
    std::vector<uint8_t> toBytes(const Codec& c) const {
        std::vector<uint8_t> body;
        section(body, c, "JSON", 0, 0, reinterpret_cast<const uint8_t*>(metaText.data()), metaText.size());
        if (!clipFlags.empty()) section(body, c, "CLIP", clipW, clipH, clipFlags.data(), clipFlags.size());
        if (!frames.empty()) section(body, c, "EFFR", framesW, framesH, frames.data(), frames.size());
        if (!hlMask.empty()) section(body, c, "HLMK", hlW, hlH, hlMask.data(), hlMask.size());
        std::vector<uint8_t> out(MAGIC, MAGIC + 4);
        putInt(out, uint32_t(VERSION));
        putInt(out, crc32(body.data(), body.size()));
        out.insert(out.end(), body.begin(), body.end());
        return out;
    }

    /// DevelopRecord.append's bytes: the record, its length and «SCDVEND1» (what goes after the DNG's last byte)
    std::vector<uint8_t> appendix(const Codec& c) const {
        std::vector<uint8_t> out = toBytes(c);
        const uint32_t len = uint32_t(out.size());
        putInt(out, len);
        out.insert(out.end(), END, END + 8);
        return out;
    }

    /// DevelopRecord.fromBytes: none when it is not one (wrong magic, version, CRC, damaged sections)
    static std::optional<DevelopRecord> fromBytes(const uint8_t* b, size_t n, const Codec& c) {
        try {
            if (n < 12 || std::memcmp(b, MAGIC, 4) != 0 || getInt(b + 4) != uint32_t(VERSION)) return std::nullopt;
            if (crc32(b + 12, n - 12) != getInt(b + 8)) return std::nullopt;
            DevelopRecord r;
            bool json = false;
            size_t at = 12;
            while (n - at >= 20) {
                const std::string tag(reinterpret_cast<const char*>(b + at), 4);
                const int w = int(getInt(b + at + 4)), h = int(getInt(b + at + 8)), raw = int(getInt(b + at + 12)), packed = int(getInt(b + at + 16));
                at += 20;
                if (raw < 0 || packed < 0 || size_t(packed) > n - at || uint64_t(raw) > MAX_RECORD * 4) return std::nullopt;
                std::vector<uint8_t> data;
                if (!inflate(c, b + at, size_t(packed), size_t(raw), data)) return std::nullopt;
                at += size_t(packed);
                if (tag == "JSON") {
                    r.metaText.assign(data.begin(), data.end());
                    r.meta = Json::parse(r.metaText);
                    json = r.meta.kind == Json::Kind::Object;
                } else if (tag == "CLIP") {
                    if ((long long)w * h != raw) return std::nullopt;
                    r.clipFlags = std::move(data);
                    r.clipW = w;
                    r.clipH = h;
                } else if (tag == "EFFR") {
                    if ((long long)w * h != raw) return std::nullopt;
                    r.frames = std::move(data);
                    r.framesW = w;
                    r.framesH = h;
                } else if (tag == "HLMK") {
                    if ((long long)w * h * 2 != raw) return std::nullopt;
                    r.hlMask = std::move(data);
                    r.hlW = w;
                    r.hlH = h;
                }
            }
            if (!json) return std::nullopt;
            const Json& v = r.meta["v"];
            if (v.kind != Json::Kind::Number || v.integer() != VERSION) return std::nullopt;
            return r;
        } catch (const std::exception&) {
            return std::nullopt;
        }
    }

    /// DevelopRecord.read: the record at the end of a whole file, none when there is none or it does not read
    static std::optional<DevelopRecord> read(const uint8_t* file, size_t size, const Codec& c) {
        if (size < FOOTER + 16) return std::nullopt;
        const uint8_t* footer = file + size - FOOTER;
        if (std::memcmp(footer + 4, END, 8) != 0) return std::nullopt;
        const uint64_t len = getInt(footer);
        if (len < 12 || len > MAX_RECORD || len > size - FOOTER - 8) return std::nullopt;
        return fromBytes(file + size - FOOTER - len, size_t(len), c);
    }
};

} // namespace scam_post
