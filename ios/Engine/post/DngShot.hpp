// The facts of a shot the Android app takes from CameraCharacteristics / CaptureResult, read from the iPhone's own DNG of the
// burst's first frame (TIFF/EP + DNG 1.4 tags): the colour tags, AsShotNeutral, NoiseProfile, the lens shading of OpcodeList2
// (GainMap opcodes, as DngCreator writes Android's LENS_SHADING_MAP), levels, CFA, orientation and the EXIF exposure.
// shotParameters fills Parameters from them as Parameters.FillConstParameters / FillDynamicParameters / ReCalcColor do.
#pragma once

#include "Parameters.hpp"
#include "Settings.hpp"
#include "color/ShotColour.hpp"

#include <cstdint>
#include <cstring>
#include <functional>
#include <map>
#include <optional>
#include <string>
#include <vector>

namespace scam_post {

struct DngGainMap {
    int top = 0, left = 0, rowPitch = 1, colPitch = 1, pointsV = 0, pointsH = 0, mapPlanes = 1;
    double spacingV = 0, spacingH = 0, originV = 0, originH = 0;
    std::vector<float> gains;
};

struct DngShot {
    ShotColourTags colour;
    std::vector<double> noiseProfile;  // (S, O) pairs
    std::vector<DngGainMap> gainMaps;
    int orientation = 1;
    int width = 0, height = 0;
    int cfa = -1;                      // 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR
    std::vector<float> black;
    int white = 0;
    int iso = 0;
    double exposure = 0;
    float aperture = 0, focalLength = 0;
};

namespace DngRead {

struct Tiff {
    const uint8_t* d;
    size_t n;
    bool le;
    uint16_t u16(size_t o) const { if (o + 2 > n) throw std::runtime_error("DNG truncated"); return le ? uint16_t(d[o] | d[o + 1] << 8) : uint16_t(d[o] << 8 | d[o + 1]); }
    uint32_t u32(size_t o) const {
        if (o + 4 > n) throw std::runtime_error("DNG truncated");
        return le ? uint32_t(d[o]) | uint32_t(d[o + 1]) << 8 | uint32_t(d[o + 2]) << 16 | uint32_t(d[o + 3]) << 24
                  : uint32_t(d[o]) << 24 | uint32_t(d[o + 1]) << 16 | uint32_t(d[o + 2]) << 8 | uint32_t(d[o + 3]);
    }
};

struct Entry { uint16_t type = 0; uint32_t count = 0; size_t at = 0; };
using Ifd = std::map<uint16_t, Entry>;

inline size_t typeSize(uint16_t t) {
    switch (t) { case 1: case 2: case 6: case 7: return 1; case 3: case 8: return 2; case 4: case 9: case 11: case 13: return 4; default: return 8; }
}
inline Ifd readIfd(const Tiff& t, size_t off, size_t* next = nullptr) {
    Ifd ifd;
    const uint16_t count = t.u16(off);
    for (uint16_t i = 0; i < count; i++) {
        const size_t e = off + 2 + size_t(i) * 12;
        Entry en;
        en.type = t.u16(e + 2);
        en.count = t.u32(e + 4);
        const size_t bytes = typeSize(en.type) * en.count;
        en.at = bytes <= 4 ? e + 8 : t.u32(e + 8);
        if (en.at + bytes > t.n) continue;
        ifd[t.u16(e)] = en;
    }
    if (next) *next = t.u32(off + 2 + size_t(count) * 12);
    return ifd;
}
/// a tag's values as doubles; a rational as Android's Rational.floatValue reads it (float numerator / float denominator)
inline std::vector<double> values(const Tiff& t, const Ifd& ifd, uint16_t tag) {
    std::vector<double> out;
    auto it = ifd.find(tag);
    if (it == ifd.end()) return out;
    const Entry& e = it->second;
    for (uint32_t i = 0; i < e.count; i++) {
        const size_t o = e.at + size_t(i) * typeSize(e.type);
        switch (e.type) {
        case 1: case 7: out.push_back(t.d[o]); break;
        case 6: out.push_back(int8_t(t.d[o])); break;
        case 3: out.push_back(t.u16(o)); break;
        case 8: out.push_back(int16_t(t.u16(o))); break;
        case 4: out.push_back(t.u32(o)); break;
        case 9: out.push_back(int32_t(t.u32(o))); break;
        case 5: { const float nu = float(t.u32(o)), de = float(t.u32(o + 4)); out.push_back(double(nu / de)); break; }
        case 10: { const float nu = float(int32_t(t.u32(o))), de = float(int32_t(t.u32(o + 4))); out.push_back(double(nu / de)); break; }
        case 11: { uint32_t b = t.u32(o); float f; std::memcpy(&f, &b, 4); out.push_back(f); break; }
        case 12: {
            uint64_t b = t.le ? uint64_t(t.u32(o)) | uint64_t(t.u32(o + 4)) << 32 : uint64_t(t.u32(o)) << 32 | uint64_t(t.u32(o + 4));
            double v; std::memcpy(&v, &b, 8); out.push_back(v); break;
        }
        default: break;
        }
    }
    return out;
}
inline std::vector<float> floats(const Tiff& t, const Ifd& ifd, uint16_t tag) {
    std::vector<float> f;
    for (double v : values(t, ifd, tag)) f.push_back(float(v));
    return f;
}

/// DngRaw.parseGainMaps: the GainMap opcodes (id 9) of an opcode list (big endian, as the DNG specification writes it)
inline std::vector<DngGainMap> gainMaps(const uint8_t* list, size_t size) {
    std::vector<DngGainMap> maps;
    const Tiff b{list, size, false};
    try {
        size_t o = 0;
        const uint32_t n = b.u32(o); o += 4;
        for (uint32_t i = 0; i < n && size - o >= 16; i++) {
            const uint32_t id = b.u32(o);
            const uint32_t bytes = b.u32(o + 12);
            o += 16;
            const size_t start = o;
            if (start + bytes > size) break;
            if (id == 9 && bytes >= 76) {
                DngGainMap g;
                auto i32 = [&](size_t at) { return int32_t(b.u32(at)); };
                auto f64 = [&](size_t at) { uint64_t v = uint64_t(b.u32(at)) << 32 | b.u32(at + 4); double x; std::memcpy(&x, &v, 8); return x; };
                g.top = i32(o); g.left = i32(o + 4);
                g.rowPitch = std::max(1, i32(o + 24)); g.colPitch = std::max(1, i32(o + 28));
                g.pointsV = i32(o + 32); g.pointsH = i32(o + 36);
                g.spacingV = f64(o + 40); g.spacingH = f64(o + 48); g.originV = f64(o + 56); g.originH = f64(o + 64);
                g.mapPlanes = std::max(1, i32(o + 72));
                const int64_t count = int64_t(g.pointsV) * g.pointsH * g.mapPlanes;
                if (g.pointsV > 0 && g.pointsH > 0 && count > 0 && count * 4 <= int64_t(bytes) - 76) {
                    g.gains.resize(size_t(count));
                    for (int64_t k = 0; k < count; k++) { uint32_t v = b.u32(o + 76 + size_t(k) * 4); std::memcpy(&g.gains[size_t(k)], &v, 4); }
                    maps.push_back(std::move(g));
                }
            }
            o = start + bytes;
        }
    } catch (const std::exception&) {
    }
    return maps;
}

} // namespace DngRead

/// The DNG's tags: IFD 0 (colour), the raw IFD (NewSubfileType 0: size, CFA, levels, OpcodeList2) and the EXIF IFD.
inline DngShot readDng(const uint8_t* data, size_t size) {
    using namespace DngRead;
    if (size < 8 || !((data[0] == 'I' && data[1] == 'I') || (data[0] == 'M' && data[1] == 'M'))) throw std::runtime_error("not a TIFF / DNG");
    const Tiff t{data, size, data[0] == 'I'};
    const Ifd ifd0 = readIfd(t, t.u32(4));
    DngShot s;
    auto first = [&](const Ifd& ifd, uint16_t tag, double fallback) { auto v = values(t, ifd, tag); return v.empty() ? fallback : v[0]; };
    s.orientation = int(first(ifd0, 0x0112, 1));
    s.colour.colorMatrix1 = floats(t, ifd0, 0xC621);
    s.colour.colorMatrix2 = floats(t, ifd0, 0xC622);
    s.colour.calibration1 = floats(t, ifd0, 0xC623);
    s.colour.calibration2 = floats(t, ifd0, 0xC624);
    s.colour.forwardMatrix1 = floats(t, ifd0, 0xC714);
    s.colour.forwardMatrix2 = floats(t, ifd0, 0xC715);
    s.colour.neutral = floats(t, ifd0, 0xC628);
    s.colour.illuminant1 = int(first(ifd0, 0xC65A, -1));
    s.colour.illuminant2 = int(first(ifd0, 0xC65B, -1));
    s.noiseProfile = values(t, ifd0, 0xC761);
    // the raw image: IFD 0 itself or one of its SubIFDs with NewSubfileType 0
    Ifd raw = ifd0;
    if (first(ifd0, 0x00FE, 0) != 0)
        for (double off : values(t, ifd0, 0x014A)) {
            const Ifd sub = readIfd(t, size_t(off));
            if (first(sub, 0x00FE, 0) == 0) { raw = sub; break; }
        }
    if (s.noiseProfile.empty()) s.noiseProfile = values(t, raw, 0xC761);
    s.width = int(first(raw, 0x0100, 0));
    s.height = int(first(raw, 0x0101, 0));
    const auto cfa = values(t, raw, 0x828E);
    if (cfa.size() == 4) {
        const int code = int(cfa[0]) * 1000 + int(cfa[1]) * 100 + int(cfa[2]) * 10 + int(cfa[3]);
        s.cfa = code == 112 ? 0 : code == 1021 ? 1 : code == 1201 ? 2 : code == 2110 ? 3 : -1;
    }
    for (double b : values(t, raw, 0xC61A)) s.black.push_back(float(b));
    s.white = int(first(raw, 0xC61D, 0));
    const Entry* ops = raw.count(0xC740) ? &raw.at(0xC740) : ifd0.count(0xC740) ? &ifd0.at(0xC740) : nullptr;
    if (ops) s.gainMaps = gainMaps(data + ops->at, ops->count);
    if (auto e = ifd0.find(0x8769); e != ifd0.end()) {
        const Ifd exif = readIfd(t, t.u32(e->second.at));
        s.iso = int(first(exif, 0x8827, 0));
        s.exposure = first(exif, 0x829A, 0);
        s.aperture = float(first(exif, 0x829D, 0));
        s.focalLength = float(first(exif, 0x920A, 0));
    }
    return s;
}

/// Android's LENS_SHADING_MAP layout (mapSize, [r, g_even, g_odd, b] per point) from the four CFA-site GainMaps of the DNG
/// (the inverse of DngCreator's OpcodeList2); 1x1 neutral gains when the DNG has none.
inline void shadingMap(const DngShot& s, Parameters& p, const std::function<void(const std::string&)>& log) {
    p.mapSize = Point{1, 1};
    p.gainMap = std::vector<float>{1.f, 1.f, 1.f, 1.f};
    p.hasGainMap = false;
    if (s.gainMaps.empty() || s.cfa < 0) { log("lens shading: no GainMap in the DNG (neutral gains)"); return; }
    static const char* layouts[4] = {"RGGB", "GRBG", "GBRG", "BGGR"};
    const DngGainMap& g0 = s.gainMaps[0];
    const int w = g0.pointsH, h = g0.pointsV;
    std::vector<float> out(size_t(w) * size_t(h) * 4, 1.f);
    int filled = 0;
    for (const DngGainMap& g : s.gainMaps) {
        if (g.pointsH != w || g.pointsV != h) continue;
        if (g.rowPitch == 1 && g.colPitch == 1) { // one map for every site
            for (size_t i = 0; i < size_t(w) * h; i++) for (int c = 0; c < 4; c++) out[i * 4 + size_t(c)] = g.gains[i * size_t(g.mapPlanes)];
            filled = 4;
            break;
        }
        const char site = layouts[s.cfa][(g.top & 1) * 2 + (g.left & 1)];
        const int channel = site == 'R' ? 0 : site == 'B' ? 3 : (g.top & 1) == 0 ? 1 : 2;
        for (size_t i = 0; i < size_t(w) * h; i++) out[i * 4 + size_t(channel)] = g.gains[i * size_t(g.mapPlanes)];
        filled++;
    }
    if (filled < 4) { log("lens shading: the DNG GainMaps cover " + std::to_string(filled) + " of 4 sites (neutral gains)"); return; }
    p.mapSize = Point{w, h};
    p.gainMap = out;
    p.hasGainMap = true;
    log("lens shading: OpcodeList2 GainMap " + std::to_string(w) + "x" + std::to_string(h));
}

/// NoiseModeler of the DNG's NoiseProfile (one pair for all channels, three per channel, four R / Gr / Gb / B), base = compute
inline NoiseModel noiseModel(const DngShot& s, int white) {
    NoiseModel m;
    m.AnalogueISO = s.iso;
    m.SensivityISO = s.iso;
    std::vector<std::pair<double, double>> in;
    for (size_t i = 0; i + 1 < s.noiseProfile.size(); i += 2) {
        double o = s.noiseProfile[i + 1];
        if (white > 1 && o > 0.01 && !std::isinf(o)) o = o > 1.0 ? o / (double(white) * white) : o / white; // NoiseModeler.halOffset
        in.push_back({s.noiseProfile[i], o});
    }
    if (in.empty() || in[0].first == 0.0) {
        // NoiseModeler's built-in generators at the shot's ISO
        const double iso = s.iso, dGain = std::max(iso / std::max(1, s.iso), 1.0);
        const double S = 0.0000025720647 * iso + 0.000028855721;
        const double O = (0.000000000039798506 * iso * iso) + (0.000000046578279 * dGain * dGain);
        in = {{S, O}};
    }
    if (in.size() == 4) in = {in[0], {(in[1].first + in[2].first) / 2.0, (in[1].second + in[2].second) / 2.0}, in[3]};
    while (in.size() < 3) in.push_back(in.back());
    in.resize(3);
    m.baseModel = in;
    m.computeModel = in;
    return m;
}

/// The Parameters of an iPhone shot whose merge made an outW x outH RGB on the sensor grid.
inline Parameters shotParameters(const DngShot& s, int outW, int outH, const std::function<void(const std::string&)>& log) {
    Parameters p;
    p.rawSize = Point{outW, outH};
    p.hybridFinalSize = Point{outW, outH};
    p.scamHdrMode = true;
    p.cfaPattern = p.baseCfaPattern = s.cfa;
    p.whiteLevel = s.white;
    p.declaredWhite = s.white;
    if (s.black.size() >= 4) p.blackLevel = std::vector<float>(s.black.begin(), s.black.begin() + 4);
    else if (!s.black.empty()) p.blackLevel = std::vector<float>(4, s.black[0]);
    p.iso = p.analogIso = s.iso;
    p.exposureTime = s.exposure > 0 ? s.exposure : 1.0 / 30.0;
    p.aperture = s.aperture;
    p.focalLength = s.focalLength;
    switch (s.orientation) { // EXIF orientation of the sensor rows -> Android's clockwise rotation of the photo
    case 6: p.cameraRotation = 90; break;
    case 3: p.cameraRotation = 180; break;
    case 8: p.cameraRotation = 270; break;
    default: p.cameraRotation = 0; break;
    }
    p.noiseModeler = noiseModel(s, s.white);
    shadingMap(s, p, log);
    ShotColour::reCalcColor(p, s.colour, log);
    return p;
}

} // namespace scam_post
