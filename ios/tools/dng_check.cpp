// P165 / P620 / P665 host check of the merged DNG: the iPhone's writer (Engine/post/DngWriter.hpp + DngWriter.cpp, DngPreview.hpp)
// against the DNG the Android code writes for the same merge (ios/tools/android-host/run.sh HostPostRender ... DNG=1: the worker's
// mosaic.u16, report.txt, android.dng / android_lj92.dng and the facts it used, dng_facts.txt), byte for byte and tag by tag; the
// preview's append; then the gallery developer on that DNG (Engine/post/DevelopRun.hpp, Editor/EditDevelop.hpp: the record of the
// shot, the DNG decoded, demosaiced, developed on Mesa) against the shot's photo (photo.rgba of the same run).
//   g++ -std=c++17 -O2 -ffp-contract=off -Iios/ScamGL/include -Iapp/src/main/cpp -Iapp/src/main/cpp/third_party/tone_port/include
//       ios/tools/dng_check.cpp ios/Engine/post/DngWriter.cpp -lEGL -lGLESv2 -pthread -o dng_check
//   dng_check <shaders> <shot dir> <reference dir> [--no-develop] [--save DIR]
#include "../Editor/EditDevelop.hpp"
#include "../Engine/post/DevelopRun.hpp"
#include "../Engine/post/DngPreview.hpp"
#include "../Engine/post/DngWriter.hpp"

#if __has_include("tone_port.h") && __has_include("oldstyle-tone-core.h")
#include "../Engine/post/oldstyle/ToneUnity.hpp"
#else
namespace scam_post::ToneNative {
int setThreads(int) { return 1; }
void render(int, const int16_t*, int, int, const float*, float, float, bool, bool, float, const float*, int, const float*, int, int, float*,
            uint8_t*) {
    throw std::runtime_error("the tone-port module is not in this build");
}
} // namespace scam_post::ToneNative
#endif

#include <EGL/egl.h>

#include <cstdio>
#include <fstream>
#include <map>
#include <sstream>

using namespace scam_post;

static std::vector<uint8_t> readAll(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) throw std::runtime_error("cannot read " + path);
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
}

static void writeAll(const std::string& path, const std::vector<uint8_t>& b) {
    std::ofstream f(path, std::ios::binary);
    f.write(reinterpret_cast<const char*>(b.data()), std::streamsize(b.size()));
}

/// The tags of a little-endian TIFF's IFD at `at` (tag -> type, count, value bytes), and the SubIFDs / Exif pointers followed.
struct TiffTags {
    std::map<std::string, std::string> tags;
    static uint32_t u16(const std::vector<uint8_t>& d, size_t o) { return uint32_t(d[o] | d[o + 1] << 8); }
    static uint32_t u32(const std::vector<uint8_t>& d, size_t o) { return uint32_t(d[o]) | uint32_t(d[o + 1]) << 8 | uint32_t(d[o + 2]) << 16 | uint32_t(d[o + 3]) << 24; }
    void read(const std::vector<uint8_t>& d, size_t at, const std::string& name, bool skipImageData) {
        const uint32_t n = u16(d, at);
        static const int sizes[13] = {0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8};
        for (uint32_t i = 0; i < n; i++) {
            const size_t e = at + 2 + 12 * size_t(i);
            const uint32_t tag = u16(d, e), type = u16(d, e + 2), count = u32(d, e + 4);
            const size_t bytes = size_t(type < 13 ? sizes[type] : 1) * count;
            const size_t src = bytes <= 4 ? e + 8 : u32(d, e + 8);
            std::string value;
            if ((tag == 273 || tag == 279) && skipImageData) value = "(strip)";
            else if (tag == 34665 || tag == 330) value = "(pointer)";
            else if (src + bytes <= d.size()) value.assign(d.begin() + long(src), d.begin() + long(src + bytes));
            tags[name + " " + std::to_string(tag)] = std::to_string(type) + "x" + std::to_string(count) + ":" + value;
            if (tag == 34665) read(d, u32(d, e + 8), "exif", skipImageData);
        }
    }
};

static std::map<std::string, std::string> facts(const std::string& path) {
    std::map<std::string, std::string> out;
    std::istringstream in(std::string(reinterpret_cast<const char*>(readAll(path).data()), readAll(path).size()));
    std::string line;
    while (std::getline(in, line)) {
        const size_t eq = line.find('=');
        if (eq != std::string::npos) out[line.substr(0, eq)] = line.substr(eq + 1);
    }
    return out;
}

static int compareDng(const std::string& what, const std::vector<uint8_t>& ios, const std::vector<uint8_t>& android) {
    if (ios == android) {
        std::printf("%s: byte for byte the Android file (%zu bytes)\n", what.c_str(), ios.size());
        return 0;
    }
    size_t first = 0;
    while (first < ios.size() && first < android.size() && ios[first] == android[first]) first++;
    std::printf("%s: %zu vs %zu bytes, first difference at %zu\n", what.c_str(), ios.size(), android.size(), first);
    TiffTags a, b;
    a.read(ios, TiffTags::u32(ios, 4), "ifd0", true);
    b.read(android, TiffTags::u32(android, 4), "ifd0", true);
    for (const auto& [k, v] : b.tags) {
        auto it = a.tags.find(k);
        if (it == a.tags.end()) std::printf("  %s: only in Android\n", k.c_str());
        else if (it->second != v) std::printf("  %s differs\n", k.c_str());
    }
    for (const auto& [k, v] : a.tags) if (!b.tags.count(k)) std::printf("  %s: only on the iPhone\n", k.c_str());
    return 1;
}

int main(int argc, char** argv) {
    if (argc < 4) {
        std::fprintf(stderr, "usage: dng_check <shaders> <shot dir> <reference dir> [--no-develop] [--save DIR]\n");
        return 2;
    }
    const std::string shaders = argv[1], shot = argv[2], ref = argv[3];
    bool develop = true;
    std::string save;
    for (int i = 4; i < argc; i++) {
        if (std::string(argv[i]) == "--no-develop") develop = false;
        else if (std::string(argv[i]) == "--save" && i + 1 < argc) save = argv[++i];
    }
    int failures = 0;
    try {
        const auto metaText = readAll(shot + "/meta.json");
        const Json meta = Json::parse(std::string(metaText.begin(), metaText.end()));
        Settings settings = Settings::fromJson(meta);
        Settings::Scope scope(settings);
        std::vector<uint8_t> head(128);
        { std::ifstream b(shot + "/burst.nch", std::ios::binary); b.read(reinterpret_cast<char*>(head.data()), 128); }
        int32_t hw, hh, flags, grid, sabreW, sabreH;
        std::memcpy(&hw, head.data() + 8, 4); std::memcpy(&hh, head.data() + 12, 4);
        std::memcpy(&flags, head.data() + 44, 4); std::memcpy(&grid, head.data() + 52, 4);
        std::memcpy(&sabreW, head.data() + 60, 4); std::memcpy(&sabreH, head.data() + 64, 4);
        grid = std::max(1, grid);
        const int outW = sabreW > 0 ? sabreW : hw * grid, outH = sabreW > 0 ? sabreH : hh * grid;
        const auto reportBytes = readAll(ref + "/report.txt");
        const std::string report(reportBytes.begin(), reportBytes.end());
        const auto mosaicBytes = readAll(ref + "/mosaic.u16");
        if (mosaicBytes.size() != size_t(hw) * hh * 2) throw std::runtime_error("mosaic.u16 is not the burst's grid");
        std::vector<uint16_t> mosaic(size_t(hw) * hh);
        std::memcpy(mosaic.data(), mosaicBytes.data(), mosaicBytes.size());
        MergeInfo m;
        mergeInfoFromReport(m, report, false);
        const double mergeFrames = dng::DngMetadata::mergeFrames(report);
        const auto f = facts(ref + "/dng_facts.txt");
        std::printf("merge: headroom %.6g (Android %s), frames %.17g (Android %s), noise x%.6g (Android %s)\n", double(m.lastMergedDngHeadroom),
                    f.at("headroom").c_str(), mergeFrames, f.at("mergeFrames").c_str(), double(m.lastNoiseFactor), f.at("noiseFactor").c_str());

        Parameters p = Parameters::fromJson(meta["params"]);
        p.rawSize = Point{hw, hh};
        dng::DngFacts s;
        s.make = f.at("make");
        s.model = f.at("model");
        s.device = f.at("device");
        s.buildModel = f.at("buildModel");
        s.software = f.at("software");
        s.dateTime = f.at("dateTime");
        s.subSec = f.at("subSec");
        s.offset = f.at("offset");
        s.lossless = f.at("lossless") == "true";
        auto log = [](const std::string& line) { std::printf("  %s\n", line.c_str()); };
        dng::dngLog() = log;
        std::vector<uint8_t> file = dng::writeMergedDng(p, s, mosaic, m.lastMergedDngHeadroom, mergeFrames, m.lastNoiseFactor, settings, log);
        failures += compareDng("merged DNG", file, readAll(ref + "/android.dng"));
        dng::DngFacts lj = s;
        lj.lossless = true;
        std::vector<uint16_t> again = mosaic;
        const std::vector<uint8_t> lj92 = dng::writeMergedDng(p, lj, again, m.lastMergedDngHeadroom, mergeFrames, m.lastNoiseFactor, settings, log);
        failures += compareDng("merged DNG, lossless JPEG", lj92, readAll(ref + "/android_lj92.dng"));
        if (!save.empty()) {
            writeAll(save + "/ios.dng", file);
            writeAll(save + "/ios_lj92.dng", lj92);
        }
        // the decode of our file gives the mosaic back (the gallery's reader, the DNG's own levels)
        {
            const scam_edit::DngRaw::Image img = scam_edit::DngRaw::decodeLinear(lj92.data(), lj92.size(), 1, false);
            const scam_edit::DngRaw::Image plain = scam_edit::DngRaw::decodeLinear(file.data(), file.size(), 1, false);
            double maxd = 0;
            for (size_t i = 0; i < img.linear.size() && i < plain.linear.size(); i++) maxd = std::max(maxd, double(std::fabs(img.linear[i] - plain.linear[i])));
            std::printf("decode: %dx%d, lossless vs uncompressed max |d| %.3g\n", img.width, img.height, maxd);
            if (maxd != 0) failures++;
        }

        // P620: the photo as the DNG's preview
        const auto photoBytes = readAll(ref + "/photo.rgba");
        int pw, ph;
        std::memcpy(&pw, photoBytes.data() + 4, 4);
        std::memcpy(&ph, photoBytes.data() + 8, 4);
        {
            int tw = 0, th = 0;
            std::vector<uint8_t> small = DngPreview::scaled(photoBytes.data() + 16, pw, ph, DngPreview::DNG_LONG_SIDE, tw, th);
            small = DngPreview::dngSource(small, tw, th, p.cameraRotation);
            const std::vector<uint8_t> jpeg = {0xFF, 0xD8, 0xFF, 0xD9};
            std::vector<uint8_t> withPreview = file;
            const bool ok = DngPreview::append(withPreview, jpeg, tw, th, DngPreview::COLOUR_SRGB, s.software);
            TiffTags t;
            t.read(withPreview, TiffTags::u32(withPreview, 4), "ifd0", true);
            const bool same = std::equal(file.begin() + 8, file.end(), withPreview.begin() + 8);
            std::printf("preview: %dx%d (sensor orientation), appended %s, IFD 0 copy with SubIFDs %s, the file's bytes before it %s\n", tw, th,
                        ok ? "yes" : "NO", t.tags.count("ifd0 330") ? "yes" : "NO", same ? "unchanged" : "CHANGED");
            if (!ok || !t.tags.count("ifd0 330") || !same) failures++;
            const scam_edit::DngRaw::Image again2 = scam_edit::DngRaw::decodeLinear(withPreview.data(), withPreview.size(), 1, false);
            if (again2.width != hw) { std::printf("preview: the raw IFD no longer read\n"); failures++; }
        }

        if (develop) {
            EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
            auto makeContext = [&] {
                eglInitialize(display, nullptr, nullptr);
                eglBindAPI(EGL_OPENGL_ES_API);
                const EGLint configAttrs[] = {EGL_RENDERABLE_TYPE, 0x40, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
                EGLConfig config;
                EGLint n = 0;
                eglChooseConfig(display, configAttrs, &config, 1, &n);
                const EGLint ctxAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
                EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, ctxAttrs);
                const EGLint size[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
                EGLSurface surface = eglCreatePbufferSurface(display, config, size);
                if (!eglMakeCurrent(display, surface, surface, context)) throw std::runtime_error("no GL context");
            };
            // the record as ShotRun takes it: the shot's Parameters, settings, the merge's numbers and maps (the worker's output
            // without its merged-RAW trailer, read as ScamNeuralClient reads it)
            auto worker = readAll(ref + "/worker.out");
            const bool rgbHalf = settings.scamDevSwitch("hybrid_rgb_half", true) || int64_t(outW) * outH > 2147483647LL / 12;
            const size_t rgbBytes = size_t(outW) * outH * (rgbHalf ? 6 : 12);
            worker.erase(worker.begin() + long(rgbBytes), worker.begin() + long(rgbBytes + mosaicBytes.size()));
            const bool effectiveMap = (flags & 16) == 0, clipFlags = (flags & 4) != 0;
            const bool highlightMask = settings.scamDevSwitch("why_hl_dezipper", true);
            MergeInfo merge;
            readWorkerOutput(merge, worker, report, outW, outH, hw, hh, rgbHalf, effectiveMap, clipFlags, highlightMask);
            merge.lastNormals = meta["normals"].isNull() ? 1 : meta["normals"].integer();
            DevelopRecord::Worker w;
            w.bentoApplied = merge.lastBentoApplied;
            w.bentoFactor = merge.lastBentoFactor;
            w.bentoUsClipped = merge.lastBentoUsClipped;
            w.effectiveMedian = merge.lastEffectiveMedian;
            w.noiseFactor = merge.lastNoiseFactor;
            w.normals = merge.lastNormals;
            w.report = merge.lastReport;
            Parameters shotParams = Parameters::fromJson(meta["params"]);
            DevelopRecord r = DevelopRecord::make(shotParams.toJson(), settings, w, merge.lastMergedDngHeadroom, hw, hh, 1.f, false, 0);
            if (merge.lastClipFlags) { r.clipFlags = *merge.lastClipFlags; r.clipW = outW; r.clipH = outH; }
            if (merge.lastEffectiveFrames) { r.frames = *merge.lastEffectiveFrames; r.framesW = outW; r.framesH = outH; }
            if (merge.lastHlMask) {
                r.hlMask.resize(merge.lastHlMask->halves.size() * 2);
                std::memcpy(r.hlMask.data(), merge.lastHlMask->halves.data(), r.hlMask.size());
                r.hlW = merge.lastHlMask->width;
                r.hlH = merge.lastHlMask->height;
            }
            for (int gainMaps = 0; gainMaps < 2; gainMaps++) {
                const scam_edit::DngRaw::Image img = scam_edit::DngRaw::decodeLinear(file.data(), file.size(), 1, gainMaps == 1);
                const bool half = (long long)img.width * img.height > scam_edit::develop::HALF_ABOVE;
                std::vector<uint8_t> rgb = scam_edit::develop::mhc(img.linear, img.width, img.height, img.phase, r.headroom(), half);
                const auto t0 = std::chrono::steady_clock::now();
                const PostOutput out = Develop::develop(r, std::move(rgb), img.width, img.height, half, {}, shaders, makeContext,
                                                        [](const std::string& l) { std::printf("  %s\n", l.c_str()); });
                const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
                if (out.width != pw || out.height != ph) {
                    std::printf("develop: %dx%d, the photo %dx%d\n", out.width, out.height, pw, ph);
                    failures++;
                    continue;
                }
                double se = 0;
                size_t n = 0, over8 = 0;
                int maxd = 0;
                for (size_t i = 0; i < out.rgba.size(); i++) {
                    if ((i & 3) == 3) continue;
                    const int d = std::abs(int(out.rgba[i]) - int(photoBytes[16 + i]));
                    se += double(d) * d;
                    maxd = std::max(maxd, d);
                    over8 += d > 8;
                    n++;
                }
                const double rmse = std::sqrt(se / double(n));
                std::printf("develop of the DNG (%s) %dx%d in %.0f ms against the shot's photo: PSNR %.2f dB, mean |d|^2 %.3f, max %d, %.3f %% "
                            "of the values off by more than 8\n",
                            gainMaps ? "the DNG's GainMap applied in the decode, as Android's EditDevelop" : "lens shading left to the pipeline",
                            out.width, out.height, ms, rmse > 0 ? 20 * std::log10(255.0 / rmse) : 999.0, se / double(n), maxd,
                            100.0 * double(over8) / double(n));
                if (!save.empty()) {
                    std::vector<uint8_t> o(16);
                    const int32_t h4[4] = {0x38524353, out.width, out.height, 4};
                    std::memcpy(o.data(), h4, 16);
                    o.insert(o.end(), out.rgba.begin(), out.rgba.end());
                    writeAll(save + (gainMaps ? "/develop_gainmap.rgba" : "/develop.rgba"), o);
                }
            }
        }
    } catch (const std::exception& e) {
        std::fprintf(stderr, "dng_check: %s\n", e.what());
        return 1;
    }
    std::printf(failures ? "dng_check: %d failure(s)\n" : "dng_check: all passed\n", failures);
    return failures ? 1 : 0;
}
