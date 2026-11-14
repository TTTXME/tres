// The merged DNG of a shot as the Android app writes it (P165): processing/DngCreator.java's setParameters / writeColour over the
// native writer (DngFile: dngCreator.cpp's class, DngWriter.cpp), ShotExif.java's DNG tags (P618: the time, the exposure, the
// lens, ImageDescription, the 4:3 DefaultCrop), DngMetadata.java (Software, N_eff of the merge, the NoiseProfile of a merged file,
// P214c), color/DngColour.java (the JPEG's colour as the DNG's single profile, P243) and ScamProcessor's P165 block (16 bit over
// the headroom, the levels scaled to 65535, BaselineExposure log2(headroom)). Java float / double semantics throughout.
#pragma once

#include "Parameters.hpp"
#include "SabreCrop.hpp"
#include "Settings.hpp"
#include "color/ShotColour.hpp"

#include <array>
#include <cmath>
#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <regex>
#include <string>
#include <vector>

namespace scam_post {
namespace dng {

inline std::function<void(const std::string&)>& dngLog() {
    static std::function<void(const std::string&)> f = [](const std::string&) {};
    return f;
}

constexpr unsigned short TIFF_ASCII = 2, TIFF_SHORT = 3, TIFF_LONG = 4, TIFF_RATIONAL = 5;

/// dngCreator.cpp's scamdng::DngFile (DngWriter.cpp): the setters store as the JNI natives do; build lays the file out.
class DngFile {
public:
    DngFile();
    ~DngFile();
    DngFile(const DngFile&) = delete;
    DngFile& operator=(const DngFile&) = delete;
    void setUniqueCameraModel(const std::string& s);
    void setDescription(const std::string& s);
    void setSoftware(const std::string& s);
    void setMake(const std::string& s);
    void setModel(const std::string& s);
    void setDateTime(const std::string& s);
    void setNoiseProfile(const double* pairs, int count);
    bool setTag(int ifd, unsigned short tag, unsigned short type, unsigned int count, const void* values);
    void setOrientation(int v);
    void setWhiteLevel(double v);
    void setBlackLevel(const unsigned short* v);
    void setCompression(bool lossless);
    void setBaselineExposure(double ev);
    void setCalibrationIlluminant1(unsigned short v);
    void setCalibrationIlluminant2(unsigned short v);
    void setColorMatrix1(const double* m);
    void setColorMatrix2(const double* m);
    void setForwardMatrix1(const double* m);
    void setForwardMatrix2(const double* m);
    void setCameraCalibration1(const double* m);
    void setCameraCalibration2(const double* m);
    void setAsShotNeutral(const double* rgb);
    void setCFAPattern(int arrangement);
    void setMosaicBlock(int block);
    void setDefaultCrop(int x, int y, int w, int h);
    void setGainMap(const float* interleaved, int xmin, int ymin, int xmax, int ymax, int w, int h);
    /// the whole file for a w x h 16-bit mosaic (empty on failure); the samples are read only
    std::vector<uint8_t> build(uint16_t* mosaic, int w, int h);

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

// ---------------------------------------------------------------- ShotExif (P618)

namespace ShotExif {

inline double jmin(const std::optional<std::vector<float>>& v) {
    double m = NAN;
    if (v) for (float x : *v) if (x > 0 && !std::isinf(x) && !(m <= x)) m = x;
    return m;
}
inline double jmax(const std::optional<std::vector<float>>& v) {
    double m = NAN;
    if (v) for (float x : *v) if (x > 0 && !std::isinf(x) && !(m >= x)) m = x;
    return m;
}

inline int iso16(int iso) { return iso <= 0 ? 0 : std::min(65535, iso); }

/// an unsigned rational {n, d} of v (Math.rint), none for a negative / non-finite value or one past 2^32
inline std::optional<std::array<long long, 2>> rational(double v, long long denominator) {
    if (!(v >= 0) || std::isinf(v) || denominator <= 0) return std::nullopt;
    const double n = std::nearbyint(v * double(denominator));
    if (n > 4294967295.0) return std::nullopt;
    return std::array<long long, 2>{(long long)n, denominator};
}

inline std::optional<std::array<long long, 2>> exposureRational(double seconds) {
    if (!(seconds > 0) || std::isinf(seconds)) return std::nullopt;
    if (seconds < 1) {
        const long long n = (long long)std::floor(1 / seconds + 0.5);
        if (n > 0 && std::fabs(1.0 / double(n) - seconds) <= 0.005 * seconds) return std::array<long long, 2>{1, n};
    }
    auto r = rational(seconds, 1000000);
    if (r && (*r)[0] % 1000000 == 0) return std::array<long long, 2>{(*r)[0] / 1000000, 1};
    return r;
}

inline double apex(double fNumber) { return fNumber > 0 ? 2 * std::log(fNumber) / std::log(2.0) : NAN; }

inline int focal35(double focalMm, double sensorW, double sensorH, double zoom) {
    if (!(focalMm > 0) || !(sensorW > 0) || !(sensorH > 0) || !(zoom > 0) || std::isinf(focalMm) || std::isinf(sensorW)
        || std::isinf(sensorH) || std::isinf(zoom))
        return 0;
    const long long eq = (long long)std::floor(focalMm * std::hypot(36.0, 24.0) / std::hypot(sensorW, sensorH) * zoom + 0.5);
    return eq < 1 || eq > 65535 ? 0 : int(eq);
}

inline std::string ascii(const std::string& s) {
    std::string out;
    // UTF-8 read as Java chars: every non-ASCII code point becomes one '?' (a surrogate pair two)
    for (size_t i = 0; i < s.size();) {
        const unsigned char c = (unsigned char)s[i];
        if (c < 0x80) {
            out += c >= 0x20 && c < 0x7F ? char(c) : '?';
            i++;
            continue;
        }
        const int len = c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : c >= 0xC0 ? 2 : 1;
        out += len == 4 ? "??" : "?";
        i += size_t(len);
    }
    return out;
}

/// "9.12" / "23" / "1.7": String.format("%.2f") without trailing zeros
inline std::string trim(double v) {
    std::string s = javaFixed(v, 2);
    if (s.find('.') != std::string::npos) {
        while (!s.empty() && s.back() == '0') s.pop_back();
        if (!s.empty() && s.back() == '.') s.pop_back();
    }
    return s;
}

inline std::string strip(const std::string& s) { return Settings::trim(s); }

inline std::string lensModel(const std::string& device, const std::string& facing, double focalMm, double fNumber) {
    std::string b;
    if (!strip(device).empty()) b += strip(device) + " ";
    b += (facing.empty() ? std::string() : facing + " ") + "camera";
    if (focalMm > 0 && !std::isinf(focalMm)) b += " " + trim(focalMm) + "mm";
    if (fNumber > 0 && !std::isinf(fNumber)) b += " f/" + trim(fNumber);
    return ascii(b);
}

inline std::string uniqueCameraModel(const std::string& make, const std::string& buildModel, int physicalId) {
    std::string b;
    if (!strip(make).empty()) b += strip(make) + " ";
    b += strip(buildModel).empty() ? "Android" : strip(buildModel);
    if (physicalId >= 0) b += " camera " + std::to_string(physicalId);
    return ascii(b);
}

inline std::string facing(const std::optional<int>& lensFacing) {
    if (!lensFacing) return "";
    switch (*lensFacing) {
        case 0: return "front";
        case 1: return "back";
        case 2: return "external";
        default: return "";
    }
}

/// ShotExif.Facts: the time as text (DateTime, SubSecTime, OffsetTime of the shot's moment and zone), the make and the phone
struct Facts {
    std::string dateTime, subSec, offset, make, device, facing;
    int iso = 0;
    double exposureTime = NAN, fNumber = NAN, focalLength = NAN;
    std::optional<std::vector<float>> focalLengths, apertures;
    int focal35 = 0;
};

struct Tag {
    static constexpr int IFD0 = 0, EXIF = 1;
    int ifd = 0, tag = 0;
    std::optional<std::string> ascii;
    std::vector<long long> shorts, longs, rationals;
};

constexpr int EXPOSURE_TIME = 33434, F_NUMBER = 33437, ISO = 34855, SENSITIVITY_TYPE = 34864, ISO_SPEED = 34867,
              DATE_TIME_ORIGINAL = 36867, DATE_TIME_DIGITIZED = 36868, OFFSET_TIME = 36880, OFFSET_TIME_ORIGINAL = 36881,
              OFFSET_TIME_DIGITIZED = 36882, APERTURE_VALUE = 37378, FOCAL_LENGTH = 37386, SUBSEC_TIME = 37520,
              SUBSEC_TIME_ORIGINAL = 37521, SUBSEC_TIME_DIGITIZED = 37522, FOCAL_LENGTH_35MM = 41989, LENS_SPECIFICATION = 42034,
              LENS_MAKE = 42035, LENS_MODEL = 42036, LENS_INFO = 50736;

/// ShotExif.dngTags: the time, exposure and lens tags (unknown values give no tag)
inline std::vector<Tag> dngTags(const Facts& f) {
    std::vector<Tag> t;
    auto rationals = [&](int ifd, int tag, const std::vector<long long>& r) { Tag x; x.ifd = ifd; x.tag = tag; x.rationals = r; t.push_back(x); };
    auto text = [&](int tag, const std::string& s) { Tag x; x.ifd = Tag::EXIF; x.tag = tag; x.ascii = s; t.push_back(x); };
    if (auto e = exposureRational(f.exposureTime)) rationals(Tag::EXIF, EXPOSURE_TIME, {(*e)[0], (*e)[1]});
    if (f.fNumber > 0) if (auto fn = rational(f.fNumber, 100)) rationals(Tag::EXIF, F_NUMBER, {(*fn)[0], (*fn)[1]});
    if (f.iso > 0) {
        Tag a; a.ifd = Tag::EXIF; a.tag = ISO; a.shorts = {iso16(f.iso)}; t.push_back(a);
        Tag b; b.ifd = Tag::EXIF; b.tag = SENSITIVITY_TYPE; b.shorts = {3}; t.push_back(b);
        Tag c; c.ifd = Tag::EXIF; c.tag = ISO_SPEED; c.longs = {f.iso}; t.push_back(c);
    }
    for (int tag : {DATE_TIME_ORIGINAL, DATE_TIME_DIGITIZED}) text(tag, f.dateTime);
    for (int tag : {OFFSET_TIME, OFFSET_TIME_ORIGINAL, OFFSET_TIME_DIGITIZED}) text(tag, f.offset);
    for (int tag : {SUBSEC_TIME, SUBSEC_TIME_ORIGINAL, SUBSEC_TIME_DIGITIZED}) text(tag, f.subSec);
    const double av = apex(f.fNumber);
    if (av >= 0) if (auto r = rational(av, 1000)) rationals(Tag::EXIF, APERTURE_VALUE, {(*r)[0], (*r)[1]});
    if (f.focalLength > 0) if (auto r = rational(f.focalLength, 100)) rationals(Tag::EXIF, FOCAL_LENGTH, {(*r)[0], (*r)[1]});
    if (f.focal35 > 0) { Tag x; x.ifd = Tag::EXIF; x.tag = FOCAL_LENGTH_35MM; x.shorts = {f.focal35}; t.push_back(x); }
    // LensSpecification / LensInfo: the shortest and longest declared focal length, the smallest f-number at both ends
    const double minF = jmin(f.focalLengths), maxF = jmax(f.focalLengths), minN = jmin(f.apertures);
    if (minF > 0 && maxF > 0 && minN > 0) {
        std::vector<long long> spec;
        bool ok = true;
        for (double v : {minF, maxF, minN, minN}) {
            auto r = rational(v, 100);
            if (!r) { ok = false; break; }
            spec.push_back((*r)[0]);
            spec.push_back((*r)[1]);
        }
        if (ok) {
            rationals(Tag::EXIF, LENS_SPECIFICATION, spec);
            rationals(Tag::IFD0, LENS_INFO, spec);
        }
    }
    if (!strip(f.make).empty()) text(LENS_MAKE, ascii(strip(f.make)));
    text(LENS_MODEL, lensModel(f.device, f.facing, f.focalLength, f.fNumber));
    return t;
}

/// ShotExif.aspectCrop: the photo's 4:3 crop of a DNG (IszAspectCrop.box), none when it keeps it all
inline std::optional<std::array<int, 4>> aspectCrop(int width, int height) {
    const int unit = std::min(std::max(width, height) / 4, std::min(width, height) / 3) & ~1;
    if (unit < 2) return std::nullopt;
    const int w = width >= height ? 4 * unit : 3 * unit, h = width >= height ? 3 * unit : 4 * unit;
    const int x = (width - w) / 2, y = (height - h) / 2;
    if (x == 0 && y == 0 && w == width && h == height) return std::nullopt;
    return std::array<int, 4>{x, y, w, h};
}

} // namespace ShotExif

// ---------------------------------------------------------------- DngMetadata (P214c)

namespace DngMetadata {

/// N_eff of the last merge from "HYBRID MERGE FACTORS: 1:0.32 ... average=0.386570" (the base plus every donor's share; the
/// sub-frame split's line divided by b^2); NaN without the line
inline double mergeFrames(const std::string& s) {
    static const std::string FACTORS = "HYBRID MERGE FACTORS:", SPLIT = "HYBRID MOSAIC: binned alignment";
    auto lastIndexOf = [&](const std::string& what, size_t from) -> long long {
        if (from == std::string::npos) from = s.size();
        const size_t at = s.rfind(what, from);
        return at == std::string::npos ? -1 : (long long)at;
    };
    const long long retry = lastIndexOf("\nRETRY:\n", std::string::npos);
    const long long at = lastIndexOf(FACTORS, std::string::npos);
    if (at < 0 || at < retry) return NAN;
    const size_t end = s.find('\n', size_t(at));
    const std::string line = s.substr(size_t(at) + FACTORS.size(), (end == std::string::npos ? s.size() : end) - size_t(at) - FACTORS.size());
    const size_t avgAt = line.find("average=");
    const std::string shares = avgAt == std::string::npos ? line : line.substr(0, avgAt);
    static const std::regex share("(^|[^0-9.])[0-9]+:(-?[0-9]+(?:\\.[0-9]+)?)");
    int donors = 0;
    double sum = 0;
    for (auto it = std::sregex_iterator(shares.begin(), shares.end(), share); it != std::sregex_iterator(); ++it) {
        const double v = std::strtod((*it)[2].str().c_str(), nullptr);
        if (!std::isfinite(v)) continue;
        sum += std::max(0.0, std::min(1.0, v));
        ++donors;
    }
    if (donors == 0) return 1;
    static const std::regex average("average=(-?[0-9]+(?:\\.[0-9]+)?)");
    std::smatch a;
    if (std::regex_search(line, a, average)) {
        const double avg = std::strtod(a[1].str().c_str(), nullptr);
        if (std::isfinite(avg)) sum = std::max(0.0, std::min(1.0, avg)) * donors;
    }
    const long long previous = at > 0 ? lastIndexOf(FACTORS, size_t(at - 1)) : -1;
    const long long split = lastIndexOf(SPLIT, size_t(at));
    int perFrame = 1;
    if (split > std::max(retry, previous)) {
        const size_t splitEnd = s.find('\n', size_t(split));
        const size_t stop = splitEnd == std::string::npos || (long long)splitEnd > at ? size_t(at) : splitEnd;
        const std::string part = s.substr(size_t(split), stop - size_t(split));
        static const std::regex grid("sub-frames on their ([0-9]+)x grid");
        std::smatch g;
        if (std::regex_search(part, g, grid)) {
            int b = std::atoi(g[1].str().c_str());
            b = std::max(1, std::min(8, b));
            perFrame = b * b;
        }
    }
    return std::max(1.0, (1 + sum) / perFrame);
}

inline double atLeastOne(double v) { return std::isfinite(v) && v > 1 ? v : 1; }

/// NoiseProfile of a merged DNG: S' = k S / (H N A), O' = k O / (H^2 N A) per plane of the one-frame model
inline std::array<double, 6> noiseProfile(const std::array<double, 6>& single, double noiseFactor, double headroom, double frames,
                                          double averagedSites) {
    const double k = std::isfinite(noiseFactor) && noiseFactor >= 1.0 / 16 && noiseFactor <= 4 ? noiseFactor : 1;
    const double h = atLeastOne(headroom), n = atLeastOne(frames), a = atLeastOne(averagedSites);
    std::array<double, 6> out{};
    for (int c = 0; c < 3; c++) {
        out[size_t(2 * c)] = k * single[size_t(2 * c)] / (h * n * a);
        out[size_t(2 * c + 1)] = k * single[size_t(2 * c + 1)] / (h * h * n * a);
    }
    return out;
}

inline std::optional<std::array<double, 6>> planes(const std::vector<std::pair<double, double>>& model) {
    if (model.size() < 3) return std::nullopt;
    std::array<double, 6> out{};
    for (int c = 0; c < 3; c++) {
        out[size_t(2 * c)] = model[size_t(c)].first;
        out[size_t(2 * c + 1)] = model[size_t(c)].second;
    }
    return out;
}

} // namespace DngMetadata

// ---------------------------------------------------------------- DngColour (P243)

namespace DngColour {
using D9 = std::array<double, 9>;
using D3 = std::array<double, 3>;

struct Profile {
    std::array<float, 9> colorMatrix1{}, forwardMatrix1{};
    int illuminant = 21;
    double whiteX = 0, whiteY = 0;
};

inline D9 diag(double a, double b, double c) { return {a, 0, 0, 0, b, 0, 0, 0, c}; }
inline D9 mul(const D9& a, const D9& b) {
    D9 o{};
    for (int r = 0; r < 3; r++)
        for (int c = 0; c < 3; c++) o[size_t(r * 3 + c)] = a[size_t(r * 3)] * b[size_t(c)] + a[size_t(r * 3 + 1)] * b[size_t(3 + c)] + a[size_t(r * 3 + 2)] * b[size_t(6 + c)];
    return o;
}
inline D3 map(const D9& m, const D3& v) {
    return {m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2], m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
}
inline std::optional<D9> invert(const D9& m) {
    const double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
    const double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
    const double det = a * A + b * B + c * C;
    if (!(std::fabs(det) > 1e-12) || !std::isfinite(det)) return std::nullopt;
    return D9{A / det, -(b * i - c * h) / det, (b * f - c * e) / det, B / det, (a * i - c * g) / det, -(a * f - c * d) / det,
              C / det, -(a * h - b * g) / det, (a * e - b * d) / det};
}

inline const D9& xyzToSrgbD50Inv() {
    static const D9 inv = *invert(D9{3.1338561, -1.6168667, -0.4906146, -0.9787684, 1.9161415, 0.0334540, 0.0719453, -0.2289914, 1.4052427});
    return inv;
}

inline D3 xyzOfCct(double t) {
    double x;
    if (t >= 4000) {
        x = t <= 7000 ? -4.6070e9 / (t * t * t) + 2.9678e6 / (t * t) + 0.09911e3 / t + 0.244063
                      : -2.0064e9 / (t * t * t) + 1.9018e6 / (t * t) + 0.24748e3 / t + 0.237040;
        const double y = -3.0 * x * x + 2.870 * x - 0.275;
        return {x / y, 1.0, (1 - x - y) / y};
    }
    x = -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910;
    const double y = t <= 2222 ? -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
                               : -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867;
    return {x / y, 1.0, (1 - x - y) / y};
}

inline D9 bradford(const D3& src, const D3& dst) {
    static const D9 B{0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296};
    const D3 s = map(B, src), d = map(B, dst);
    return mul(*invert(B), mul(diag(d[0] / s[0], d[1] / s[1], d[2] / s[2]), B));
}

inline bool finite(const std::optional<std::vector<float>>& v, size_t n) {
    if (!v || v->size() < n) return false;
    for (size_t i = 0; i < n; i++) if (!std::isfinite((*v)[i])) return false;
    return true;
}

/// DngColour.jpegProfile: the JPEG's colour as one ColorMatrix / ForwardMatrix, none when a matrix is not usable
inline std::optional<Profile> jpegProfile(const std::optional<std::vector<float>>& sensorToProPhoto,
                                          const std::optional<std::vector<float>>& intermediateToSRGB,
                                          const std::optional<std::vector<float>>& neutral, float sceneCct) {
    if (!finite(sensorToProPhoto, 9) || !finite(intermediateToSRGB, 9) || !finite(neutral, 3)) return std::nullopt;
    for (size_t i = 0; i < 3; i++) if (!((*neutral)[i] > 0.f)) return std::nullopt;
    D9 a{}, b{};
    for (size_t i = 0; i < 9; i++) { a[i] = (*intermediateToSRGB)[i]; b[i] = (*sensorToProPhoto)[i]; }
    const D9 m = mul(a, b);
    const D9 c2x = mul(xyzToSrgbD50Inv(), m);
    const double n0 = (*neutral)[0], n1 = (*neutral)[1], n2 = (*neutral)[2];
    D9 fm = mul(c2x, diag(n0, n1, n2));
    const D3 white = map(fm, {1, 1, 1});
    if (!(white[1] > 1e-9)) return std::nullopt;
    for (size_t i = 0; i < 9; i++) fm[i] /= white[1];
    const std::optional<D9> fmInv = invert(fm);
    if (!fmInv) return std::nullopt;
    const D3 scene = sceneCct >= 1667.f && sceneCct <= 25000.f ? xyzOfCct(sceneCct) : D3{0.9642, 1.0, 0.8249};
    const D3 fmWhite = map(fm, {1, 1, 1});
    const D9 cm = mul(mul(diag(n0, n1, n2), *fmInv), bradford(scene, fmWhite));
    const double sum = white[0] + white[1] + white[2];
    Profile p;
    for (size_t i = 0; i < 9; i++) { p.colorMatrix1[i] = float(cm[i]); p.forwardMatrix1[i] = float(fm[i]); }
    p.whiteX = white[0] / sum;
    p.whiteY = white[1] / sum;
    return p;
}

} // namespace DngColour

// ---------------------------------------------------------------- DngCreator.setParameters

/// What Android's Parameters carries for the DNG beside the lab's fields (dngColour, dngNoiseProfile, dngBaselineExposure) and the
/// facts of the shot's files (ShotExif.of's make / device, DeviceName.model, Build.MODEL, the APK's Software, the time as text).
struct DngFacts {
    std::optional<DngColour::Profile> colour;
    std::optional<std::array<double, 6>> noiseProfile;
    float baselineExposure = 0.f;
    std::string make, model, device, buildModel, software, dateTime, subSec, offset;
    /// the iPhone's values where its Parameters lack the camera's (lensFacing, lens tables, sensor size): "" / NaN / 0 none
    std::string facing;
    double fNumber = NAN, focalLength = NAN;
    int focal35 = 0;
    bool lossless = false;
};

/// Parameters.exifAperture / exifFocalLength
inline double exifAperture(const Parameters& p) {
    if (p.apertureOfShot && p.aperture > 0) return p.aperture;
    return p.lensApertures && !p.lensApertures->empty() && (*p.lensApertures)[0] > 0 ? (*p.lensApertures)[0] : NAN;
}
inline double exifFocalLength(const Parameters& p) {
    if (p.focalLengthOfShot && p.focalLength > 0) return p.focalLength;
    return p.lensFocalLengths && !p.lensFocalLengths->empty() && (*p.lensFocalLengths)[0] > 0 ? (*p.lensFocalLengths)[0] : NAN;
}

/// Parameters.description + ShotExif.dngDescription + ParseExif.routeLine
inline std::string dngDescription(const Parameters& p) {
    std::string b = "SCAMERA";
    if (p.multiFrameCount > 0) b += "\n Frames=" + std::to_string(p.multiFrameCount);
    b += "\n Camera=" + p.cameraID;
    if (!p.colourSource.empty()) b += "\n Colour=" + p.colourSource;
    if (!p.filmRecipe.empty()) b += "\n Film=" + p.filmRecipe;
    return b + (p.dngMerged ? "\n RAW=merged burst" : "\n RAW=one frame") + "\n Route=SHDR";
}

/// ShotExif.of: the facts of the shot for the DNG's field of view (Parameters.dngFieldZoom)
inline ShotExif::Facts facts(const Parameters& p, const DngFacts& s) {
    ShotExif::Facts f;
    f.dateTime = s.dateTime;
    f.subSec = s.subSec;
    f.offset = s.offset;
    f.make = s.make;
    f.device = s.device;
    f.iso = p.iso;
    f.exposureTime = p.exposureTime;
    f.fNumber = exifAperture(p);
    if (!(f.fNumber > 0) && s.fNumber > 0) f.fNumber = s.fNumber;
    f.focalLength = exifFocalLength(p);
    if (!(f.focalLength > 0) && s.focalLength > 0) f.focalLength = s.focalLength;
    f.focalLengths = p.lensFocalLengths;
    f.apertures = p.lensApertures;
    f.facing = p.lensFacing ? ShotExif::facing(p.lensFacing) : s.facing;
    f.focal35 = p.sensorSize ? ShotExif::focal35(f.focalLength, p.sensorSize->width, p.sensorSize->height, p.dngFieldZoom) : s.focal35;
    return f;
}

/// P215 / P370: a matrix DngCreator writes (finite; a ColorMatrix also invertible, Parameters.dngColourMatrixUsable)
inline bool finiteMatrix(const std::optional<std::vector<float>>& v) { return v && DngColour::finite(v, v->size()); }
inline bool colourMatrixUsable(const std::optional<std::vector<float>>& cm) {
    if (!finiteMatrix(cm) || cm->size() < 9) return false;
    const std::vector<float>& m = *cm;
    const double det = double(m[0]) * (double(m[4]) * m[8] - double(m[5]) * m[7]) - double(m[1]) * (double(m[3]) * m[8] - double(m[5]) * m[6])
                       + double(m[2]) * (double(m[3]) * m[7] - double(m[4]) * m[6]);
    return std::fabs(det) > 1e-9 && std::isfinite(det);
}

inline ShotColour::F9 f9(const std::optional<std::vector<float>>& v) {
    ShotColour::F9 out{};
    if (v) for (size_t i = 0; i < 9 && i < v->size(); i++) out[i] = (*v)[i];
    return out;
}

/// Parameters.dngForwardMatrices: not when they are the sRGB -> XYZ placeholder and the colour matrices are real
inline bool dngForwardMatrices(const Parameters& p) {
    auto placeholderCm = [](const std::optional<std::vector<float>>& cm, int illuminant) {
        if (!cm || cm->size() < 9) return true;
        return ShotColour::placeholderColorMatrix(f9(cm), illuminant);
    };
    auto placeholderFm = [](const std::optional<std::vector<float>>& fm) { return fm && fm->size() >= 9 && ShotColour::placeholderForward(f9(fm)); };
    return !(placeholderFm(p.ForwardTransform1) && placeholderFm(p.ForwardTransform2) && !placeholderCm(p.ColorMatrix1, p.calibrationIlluminant1)
             && !placeholderCm(p.ColorMatrix2, p.calibrationIlluminant2));
}

inline std::array<double, 9> wide(const std::vector<float>& v) {
    std::array<double, 9> o{};
    for (size_t i = 0; i < 9 && i < v.size(); i++) o[i] = v[i];
    return o;
}

/// DngCreator.setParameters + writeColour, in their order; then the compression («DNG без потерь») and the file
inline std::vector<uint8_t> writeDng(const Parameters& p, const DngFacts& s, std::vector<uint16_t>& mosaic, int w, int h) {
    DngFile f;
    f.setMosaicBlock(p.dngMosaicBlock);
    f.setDescription(dngDescription(p));
    f.setSoftware(s.software);
    const ShotExif::Facts fx = facts(p, s);
    f.setDateTime(fx.dateTime);
    for (const ShotExif::Tag& t : ShotExif::dngTags(fx)) {
        auto words = [&](const std::vector<long long>& v, long long max, bool rational, std::vector<unsigned int>& out) {
            for (size_t i = 0; i < v.size(); ++i) {
                if (v[i] < 0 || v[i] > max || (rational && (i & 1) && v[i] == 0)) return false;
                out.push_back(unsigned(v[i]));
            }
            return true;
        };
        if (t.ascii) {
            if (!t.ascii->empty()) f.setTag(t.ifd, (unsigned short)t.tag, TIFF_ASCII, unsigned(t.ascii->size() + 1), t.ascii->c_str());
            continue;
        }
        std::vector<unsigned int> out;
        if (!t.shorts.empty()) {
            if (!words(t.shorts, 65535, false, out)) continue;
            const std::vector<unsigned short> shorts(out.begin(), out.end());
            f.setTag(t.ifd, (unsigned short)t.tag, TIFF_SHORT, unsigned(shorts.size()), shorts.data());
        } else if (!t.longs.empty()) {
            if (!words(t.longs, 0xFFFFFFFFLL, false, out)) continue;
            f.setTag(t.ifd, (unsigned short)t.tag, TIFF_LONG, unsigned(out.size()), out.data());
        } else if (!t.rationals.empty()) {
            if (t.rationals.size() % 2 != 0 || !words(t.rationals, 0xFFFFFFFFLL, true, out)) continue;
            f.setTag(t.ifd, (unsigned short)t.tag, TIFF_RATIONAL, unsigned(out.size() / 2), out.data());
        }
    }
    if (p.photoAspect4x3 && p.rawSize)
        if (auto crop = ShotExif::aspectCrop(p.rawSize->x, p.rawSize->y)) f.setDefaultCrop((*crop)[0], (*crop)[1], (*crop)[2], (*crop)[3]);
    // DngCreator.noiseProfile: the writer's own, else the noise model's compute pairs
    std::optional<std::array<double, 6>> noise = s.noiseProfile;
    if (!noise && p.noiseModeler && !p.noiseModeler->baseModel.empty() && p.noiseModeler->computeModel.size() >= 3) noise = DngMetadata::planes(p.noiseModeler->computeModel);
    if (noise) f.setNoiseProfile(noise->data(), 6);
    f.setMake(fx.make);
    f.setModel(s.model);
    f.setUniqueCameraModel(ShotExif::uniqueCameraModel(fx.make, s.buildModel, p.physicalID));
    unsigned short black[4] = {0, 0, 0, 0};
    for (size_t i = 0; i < 4 && p.blackLevel && i < p.blackLevel->size(); i++) black[i] = (unsigned short)(short)(int)(*p.blackLevel)[i];
    f.setBlackLevel(black);
    f.setWhiteLevel(p.whiteLevel);
    if (s.baselineExposure != 0.f) f.setBaselineExposure(s.baselineExposure);
    // writeColour
    const auto& jpeg = s.colour;
    f.setCalibrationIlluminant1((unsigned short)(short)(jpeg ? jpeg->illuminant : p.calibrationIlluminant1));
    f.setCalibrationIlluminant2((unsigned short)(short)(jpeg ? jpeg->illuminant : p.calibrationIlluminant2));
    const bool forward = !jpeg && dngForwardMatrices(p);
    if (jpeg) {
        std::array<double, 9> cm{}, fm{};
        for (size_t i = 0; i < 9; i++) { cm[i] = jpeg->colorMatrix1[i]; fm[i] = jpeg->forwardMatrix1[i]; }
        f.setColorMatrix1(cm.data());
        f.setForwardMatrix1(fm.data());
    } else {
        if (colourMatrixUsable(p.ColorMatrix1)) f.setColorMatrix1(wide(*p.ColorMatrix1).data());
        if (colourMatrixUsable(p.ColorMatrix2)) f.setColorMatrix2(wide(*p.ColorMatrix2).data());
        if (forward && finiteMatrix(p.ForwardTransform1)) f.setForwardMatrix1(wide(*p.ForwardTransform1).data());
        if (forward && finiteMatrix(p.ForwardTransform2)) f.setForwardMatrix2(wide(*p.ForwardTransform2).data());
        if (finiteMatrix(p.calibrationTransform1)) f.setCameraCalibration1(wide(*p.calibrationTransform1).data());
        if (finiteMatrix(p.calibrationTransform2)) f.setCameraCalibration2(wide(*p.calibrationTransform2).data());
    }
    if (finiteMatrix(p.whitePoint) && p.whitePoint->size() >= 3 && (*p.whitePoint)[0] > 0 && (*p.whitePoint)[1] > 0 && (*p.whitePoint)[2] > 0) {
        const double n[3] = {(*p.whitePoint)[0], (*p.whitePoint)[1], (*p.whitePoint)[2]};
        f.setAsShotNeutral(n);
    }
    f.setCFAPattern(p.baseCfaPattern);
    const int quarter = p.cameraRotation / 90;
    static const int ORIENTATION_OF_QUARTER[4] = {1, 6, 3, 8};
    if (quarter >= 0 && quarter <= 8) f.setOrientation(quarter < 4 ? ORIENTATION_OF_QUARTER[quarter] : 0);
    if (p.gainMap && p.mapSize && p.rawSize && p.gainMap->size() >= size_t(4) * p.mapSize->x * p.mapSize->y && p.mapSize->x > 0 && p.mapSize->y > 0)
        f.setGainMap(p.gainMap->data(), 0, 0, p.rawSize->x, p.rawSize->y, p.mapSize->x, p.mapSize->y);
    f.setCompression(s.lossless);
    return f.build(mosaic.data(), w, h);
}

/// ScamProcessor's P165 block for the merge's mosaic (16 bit over its headroom): the levels scaled to 65535, BaselineExposure
/// log2(headroom), the NoiseProfile of the merged level (P214c), the JPEG's colour (P243, scam_dev dng_colour_jpeg), RAW=merged burst.
/// p: the shot's Parameters as the DNG is written (rawSize = the mosaic's grid); mergeFrames NaN: the worker gave none.
inline std::vector<uint8_t> writeMergedDng(Parameters p, DngFacts s, std::vector<uint16_t>& mosaic, float mergedHeadroom, double mergeFrames,
                                           float noiseFactor, const Settings& settings, const std::function<void(const std::string&)>& log) {
    if (!p.rawSize || size_t(p.rawSize->x) * size_t(p.rawSize->y) != mosaic.size()) throw std::runtime_error("merged DNG: the mosaic is not the DNG's grid");
    if ((settings.scamDevSwitch("dng_colour_jpeg", true) || !colourMatrixUsable(p.ColorMatrix1)) && p.CCT) {
        s.colour = DngColour::jpegProfile(p.sensorToProPhoto, std::optional<std::vector<float>>(p.CCT->matrix), p.whitePoint, p.sceneCct);
        if (!s.colour) log("DNG colour: the JPEG's matrices are not usable, the camera's profile is written");
    }
    const std::vector<float> black = p.blackLevel ? *p.blackLevel : std::vector<float>(4, 0.f);
    const int white = p.whiteLevel;
    const float k = 65535.f / float(white);
    const float headroom = std::max(1.f, mergedHeadroom);
    std::vector<float> scaled(4, 0.f);
    for (size_t i = 0; i < 4 && i < black.size(); i++) scaled[i] = float((long long)std::floor(double(black[i] * k) + 0.5));
    p.blackLevel = scaled;
    p.whiteLevel = 65535;
    s.baselineExposure = headroom > 1.0001f ? float(std::log(double(headroom)) / std::log(2.0)) : 0.f;
    const bool framesKnown = std::isfinite(mergeFrames) && mergeFrames >= 1;
    if (p.noiseModeler) {
        const auto camera = DngMetadata::planes(framesKnown ? p.noiseModeler->baseModel : p.noiseModeler->computeModel);
        if (camera) s.noiseProfile = DngMetadata::noiseProfile(*camera, noiseFactor, headroom, framesKnown ? mergeFrames : 1, 1);
    }
    p.dngMosaicBlock = 1;
    p.dngMerged = true;
    std::vector<uint8_t> out = writeDng(p, s, mosaic, p.rawSize->x, p.rawSize->y);
    char line[200];
    std::snprintf(line, sizeof line, "DNG: merged burst RAW (N, bracketed and ultrashort frames), 16-bit, headroom x%.3f, BaselineExposure %+.2f EV",
                  double(headroom), double(s.baselineExposure));
    log(line);
    if (s.noiseProfile) {
        std::snprintf(line, sizeof line, "DNG: NoiseProfile G %.4g / %.4g (frames %s, headroom x%.3f, noise x%.3f)", (*s.noiseProfile)[2],
                      (*s.noiseProfile)[3], framesKnown ? javaFixed(mergeFrames, 2).c_str() : "unknown: legacy model", double(headroom),
                      double(noiseFactor));
        log(line);
    }
    return out;
}

} // namespace dng
} // namespace scam_post
