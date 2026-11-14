// P701: host replay of a kept YUV series (processing/yuv/YuvBurstFile: "SCAMYUV1", LE int32 header length, the JSON header, the
// packed planar 4:2:0 frames) through the app's merge (app/src/main/cpp/scam-yuv-merge.h) with YuvBurst's default settings and
// capture curve (YuvMergeRoute.curvePairs: 64 sRGB points at evenly spaced inputs). Prints the merge's report and writes the linear
// RGB the post pipeline would take, box-downscaled, as a PFM (for comparing the decode with the camera's own photo).
// Build: g++ -std=c++17 -O2 -pthread -Iapp/src/main/cpp tools/yuv_burst_replay.cpp -o /tmp/yuv-burst-replay
// Run:   /tmp/yuv-burst-replay series.bin [--inner auto|0|1] [--chroma 0.8] [--toe 0.6] [--toe-cap 2] [--scale 4] [--out linear.pfm] [--threads 4]
//        --inner: the prior of the capture curve's model (YuvBurst.curvePrior: 1 the camera applies the points to its sRGB signal,
//        0 to linear light; auto = 1, the MediaTek prior of the series' camera); --chroma: YuvBurst.chroma (scaleChroma);
//        --toe / --toe-cap: YuvBurst's shadow toe (shadowToe; 1 / 1: none).
#include "scam-yuv-merge.h"

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

using namespace scam_yuv;

namespace {

/** The number after "key": at or after {@code from} in the flat JSON (no nesting needed for these fields). */
double number(const std::string& json, const std::string& key, std::size_t from, std::size_t to, double fallback) {
    const std::size_t k = json.find("\"" + key + "\":", from);
    if (k == std::string::npos || k >= to) return fallback;
    return std::strtod(json.c_str() + k + key.size() + 3, nullptr);
}

std::string text(const std::string& json, const std::string& key, std::size_t from, std::size_t to) {
    const std::size_t k = json.find("\"" + key + "\":\"", from);
    if (k == std::string::npos || k >= to) return "";
    const std::size_t a = k + key.size() + 4, b = json.find('"', a);
    return json.substr(a, b - a);
}

float srgb(float x) { return x <= 0.0031308f ? 12.92f * x : 1.055f * std::pow(x, 1.f / 2.4f) - 0.055f; }

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s series.bin [--inner auto|0|1] [--scale N] [--out file.pfm] [--threads N]\n", argv[0]);
        return 2;
    }
    std::string path = argv[1], outPath, inner = "auto";
    int scale = 4, threads = 4;
    float chroma = 0.8f, toe = 0.6f, toeCap = 2.f;
    for (int i = 2; i + 1 < argc; i += 2) {
        const std::string a = argv[i];
        if (a == "--inner") inner = argv[i + 1];
        else if (a == "--scale") scale = std::max(1, std::atoi(argv[i + 1]));
        else if (a == "--out") outPath = argv[i + 1];
        else if (a == "--threads") threads = std::max(1, std::atoi(argv[i + 1]));
        else if (a == "--chroma") chroma = float(std::atof(argv[i + 1]));
        else if (a == "--toe") toe = float(std::atof(argv[i + 1]));
        else if (a == "--toe-cap") toeCap = float(std::atof(argv[i + 1]));
    }
    std::ifstream in(path, std::ios::binary);
    char magic[8];
    int32_t len = 0;
    if (!in.read(magic, 8) || std::string(magic, 8) != "SCAMYUV1" || !in.read(reinterpret_cast<char*>(&len), 4) || len <= 0) {
        std::fprintf(stderr, "not a SCAMYUV1 series\n");
        return 1;
    }
    std::string json(std::size_t(len), '\0');
    in.read(json.data(), len);
    const std::size_t framesAt = json.find("\"frames\":");
    const int w = int(number(json, "width", 0, framesAt, 0)), h = int(number(json, "height", 0, framesAt, 0));
    const int bits = int(number(json, "bits", 0, framesAt, 8));
    if (bits != 8 || w < 64 || h < 64) {
        std::fprintf(stderr, "only 8-bit series (bits %d, %dx%d)\n", bits, w, h);
        return 1;
    }
    const std::size_t samples = std::size_t(w) * h + 2 * std::size_t((w + 1) / 2) * ((h + 1) / 2);
    std::vector<FrameInfo> info;
    std::vector<std::vector<uint8_t>> data;
    double base = 0;
    float noiseS = 0, noiseO = 0;
    for (std::size_t at = json.find('{', framesAt); at != std::string::npos; at = json.find('{', at + 1)) {
        const std::size_t end = json.find('}', at);
        const std::string role = text(json, "role", at, end);
        const double exposure = number(json, "exposureNs", at, end, 0) * number(json, "iso", at, end, 0);
        if (data.empty()) {
            base = exposure;
            noiseS = float(number(json, "noiseSlope", at, end, 0));
            noiseO = float(number(json, "noiseOffset", at, end, 0));
        }
        FrameInfo f;
        f.role = role == "LONG" ? Role::Long : role == "SHORT" || role == "EXTRA_SHORT" ? Role::Short : Role::Normal;
        f.ratio = base > 0 ? float(exposure / base) : 1.f;
        info.push_back(f);
        data.emplace_back(samples);
        if (!in.read(reinterpret_cast<char*>(data.back().data()), std::streamsize(samples))) {
            std::fprintf(stderr, "series cut short at frame %zu\n", data.size() - 1);
            return 1;
        }
    }
    std::vector<Frame420<uint8_t>> frames;
    const int cw = (w + 1) / 2, ch = (h + 1) / 2;
    for (const auto& d : data) {
        Frame420<uint8_t> f;
        f.y = {d.data(), w, 1};
        f.c0 = {d.data() + std::size_t(w) * h, cw, 1};
        f.c1 = {d.data() + std::size_t(w) * h + std::size_t(cw) * ch, cw, 1};
        f.width = w;
        f.height = h;
        f.bits = 8;
        frames.push_back(f);
    }
    Settings set;  // YuvBurst's defaults (profile 3.1, search 48, local 6, ...)
    set.globalRadius = 48;
    set.localRadius = 6;
    set.threads = threads;
    Transfer t;
    t.kind = Transfer::POINTS;
    for (int i = 0; i < 64; ++i) {
        const float x = float(i) / 63.f;
        t.in.push_back(x);
        t.out.push_back(srgb(x));
    }
    t.innerSrgb = inner != "0";
    t.build();
    Decoder d;  // JFIF BT.601 full range (8-bit YUV_420_888)
    const NoiseFallback fb{noiseS, noiseO};
    std::vector<float> rgb(std::size_t(w) * h * 3);
    std::vector<uint8_t> counts(std::size_t(w) * h);
    const Report rep = mergeBurst(frames, info, set, t, d, fb, rgb.data(), counts.data());
    scaleChroma(rgb.data(), std::size_t(w) * h, chroma, threads);
    float geo[2];
    shadowToe(rgb.data(), std::size_t(w) * h, toe, toeCap, threads, geo);
    std::printf("%s\n", rep.text().c_str());
    std::printf("linear noise S %.4g O %.4g, curve model %s, chroma x%.2f, shadow toe %.2f cap %.2f: scene geo %.4f -> %.4f (WHY AE target x%.3f)\n",
                rep.linS, rep.linO, rep.curveModel.c_str(), chroma, toe, toeCap, geo[0], geo[1], geo[1] / geo[0]);
    if (!outPath.empty()) {
        const int ow = w / scale, oh = h / scale;
        std::vector<float> small(std::size_t(ow) * oh * 3);
        for (int y = 0; y < oh; ++y)
            for (int x = 0; x < ow; ++x)
                for (int c = 0; c < 3; ++c) {
                    double s = 0;
                    for (int j = 0; j < scale; ++j)
                        for (int i = 0; i < scale; ++i) s += rgb[(std::size_t(y * scale + j) * w + x * scale + i) * 3 + c];
                    small[(std::size_t(oh - 1 - y) * ow + x) * 3 + c] = float(s / (scale * scale));  // PFM rows bottom-up
                }
        std::ofstream o(outPath, std::ios::binary);
        o << "PF\n" << ow << " " << oh << "\n-1.0\n";
        o.write(reinterpret_cast<const char*>(small.data()), std::streamsize(small.size() * sizeof(float)));
    }
    return 0;
}
