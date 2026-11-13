// The shot's capture facts beside its NCH (the sidecar "<burst>.json" the iPhone writes: Engine/BurstProcessor.swift) and what the
// Android app does with the frames between the capture and the merge: the ScamHybridBurst frame table (HybridSelect) written over the
// capture's, the SabreCrop window cut (P576, RawCrop.apply: every frame's window rows), and the photo's crop after the pipeline
// (ScamProcessor: the window's remaining crop and the photo size, else the digital zoom's centre crop brought back to the photo
// size). Shared by the app (ShotRun.hpp) and the host check (tools/shot_run_check.cpp).
//
// Sidecar (all optional): {"zoom": the digital zoom's residual at the press (ZoomController.processingResidual), "frames": [{"role": 1
// N | 3 bracketed (LONG) | 5 ultrashort (EXTRA_SHORT), "exposureNs", "iso", "timestampNs", "slope", "offset"}, ...] in the NCH's
// plane order, "fullHeight": the frames' rows before the 16:9 cut (0: not cut), "firstRow": its first row}.
#pragma once

#include "HybridSelect.hpp"
#include "Json.hpp"
#include "SabreCrop.hpp"
#include "lanczos-downscale.h"

#include <array>
#include <cstring>
#include <fcntl.h>
#include <fstream>
#include <sstream>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace scam_post {

struct ShotFacts {
    bool present = false;
    float zoom = 1.f;
    std::vector<HybridSelect::Frame> frames;
    int fullHeight = 0, firstRow = 0;
};

inline ShotFacts readShotFacts(const std::string& path) {
    ShotFacts f;
    std::ifstream in(path, std::ios::binary);
    if (!in) return f;
    std::stringstream text;
    text << in.rdbuf();
    const Json j = Json::parse(text.str());
    f.present = true;
    if (j.has("zoom") && !j["zoom"].isNull()) f.zoom = float(j["zoom"].number());
    if (j.has("fullHeight") && !j["fullHeight"].isNull()) f.fullHeight = int(j["fullHeight"].number());
    if (j.has("firstRow") && !j["firstRow"].isNull()) f.firstRow = int(j["firstRow"].number());
    if (j.has("frames")) {
        const Json& a = j["frames"];
        for (size_t i = 0; i < a.size(); i++) {
            const Json& e = a[i];
            HybridSelect::Frame fr;
            fr.index = int(i);
            fr.number = int(i);
            const int role = int(e["role"].number());
            fr.role = role == HybridSelect::ROLE_BRACKETED ? HybridSelect::CaptureRole::Long
                      : role == HybridSelect::ROLE_ULTRASHORT ? HybridSelect::CaptureRole::ExtraShort
                                                               : HybridSelect::CaptureRole::Normal;
            fr.exposureNs = (long long)e["exposureNs"].number();
            fr.iso = int(e["iso"].number());
            fr.timestampNs = (long long)e["timestampNs"].number();
            if (e.has("slope") && !e["slope"].isNull()) fr.noiseSlope = float(e["slope"].number());
            if (e.has("offset") && !e["offset"].isNull()) fr.noiseOffset = float(e["offset"].number());
            f.frames.push_back(fr);
        }
    }
    return f;
}

/// An NCH v11 / v12 burst mapped read-only (the planes are read only where touched)
struct NchFile {
    std::array<uint32_t, 32> h{};
    std::vector<uint8_t> table;
    void* address = MAP_FAILED;
    size_t length = 0;

    explicit NchFile(const std::string& path) {
        const int fd = ::open(path.c_str(), O_RDONLY);
        if (fd < 0) throw std::runtime_error("no burst " + path);
        struct stat st{};
        if (fstat(fd, &st) || st.st_size < 128) { ::close(fd); throw std::runtime_error("bad burst " + path); }
        length = size_t(st.st_size);
        address = mmap(nullptr, length, PROT_READ, MAP_PRIVATE, fd, 0);
        ::close(fd);
        if (address == MAP_FAILED) throw std::runtime_error("cannot map " + path);
        std::memcpy(h.data(), address, 128);
        if (h[0] != 0x3143484eu || (h[1] != 11 && h[1] != 12)) throw std::runtime_error("not an NCH v11 / v12 burst " + path);
        if (128 + 32 * size_t(frames()) > length) throw std::runtime_error("truncated burst " + path);
        const auto* t = static_cast<const uint8_t*>(address) + 128;
        table.assign(t, t + 32 * size_t(frames()));
    }
    NchFile(const NchFile&) = delete;
    ~NchFile() { if (address != MAP_FAILED) munmap(address, length); }

    int width() const { return int(h[2]); }
    int height() const { return int(h[3]); }
    int frames() const { return int(h[5]); }
    uint32_t word(int i, int k) const { uint32_t v; std::memcpy(&v, table.data() + 32 * size_t(i) + 4 * size_t(k), 4); return v; }
    size_t planeOffset(int i) const {
        if (h[1] == 12) return size_t(word(i, 7)) * 4096;
        return 128 + 32 * size_t(frames()) + size_t(i) * width() * height() * 2;
    }
    const uint16_t* plane(int i) const {
        const size_t at = planeOffset(i);
        if (at + size_t(width()) * height() * 2 > length) throw std::runtime_error("burst frame " + std::to_string(i) + " outside the file");
        return reinterpret_cast<const uint16_t*>(static_cast<const uint8_t*>(address) + at);
    }
};

inline void putTable(std::vector<uint8_t>& table, size_t at, const HybridSelect::Entry& e, uint32_t page) {
    auto put = [&](size_t o, const void* v) { std::memcpy(table.data() + at + o, v, 4); };
    const uint32_t zero = 0;
    put(0, &e.role);
    put(4, &e.exposure);
    put(8, &e.iso);
    put(12, &e.slope);
    put(16, &e.offset);
    put(20, &e.orderMs);
    put(24, &zero);
    put(28, &page);
}

/// The burst the worker gets: the selected frames in their order (selection: none keeps the file's table) cut to window (none:
/// the whole frames). A v12 burst whose frames are only re-picked gets its header rewritten in place (its planes stay where they
/// are); otherwise a v11 burst is written to out. Returns the path to merge.
inline std::string writeTransport(const std::string& path, const std::vector<HybridSelect::Entry>* selection, const std::optional<CropArea>& window,
                                  const std::string& out) {
    if (!selection && !window) return path;
    std::vector<HybridSelect::Entry> entries;
    std::vector<int> source;
    {
        NchFile nch(path);
        if (selection) {
            entries = *selection;
            for (const auto& e : entries) source.push_back(e.index);
        } else {
            for (int i = 0; i < nch.frames(); i++) {
                HybridSelect::Entry e;
                e.index = i;
                std::memcpy(&e.role, nch.table.data() + 32 * size_t(i), 4);
                std::memcpy(&e.exposure, nch.table.data() + 32 * size_t(i) + 4, 4);
                std::memcpy(&e.iso, nch.table.data() + 32 * size_t(i) + 8, 4);
                std::memcpy(&e.slope, nch.table.data() + 32 * size_t(i) + 12, 4);
                std::memcpy(&e.offset, nch.table.data() + 32 * size_t(i) + 16, 4);
                std::memcpy(&e.orderMs, nch.table.data() + 32 * size_t(i) + 20, 4);
                entries.push_back(e);
                source.push_back(i);
            }
        }
        if (!window && nch.h[1] == 12) {
            // in place: the header and the table of the picked frames, each at its own page
            std::vector<uint32_t> pages;
            for (int i : source) pages.push_back(nch.word(i, 7));
            std::vector<uint8_t> head(128 + 32 * entries.size());
            std::array<uint32_t, 32> h = nch.h;
            h[5] = uint32_t(entries.size());
            std::memcpy(head.data(), h.data(), 128);
            for (size_t k = 0; k < entries.size(); k++) putTable(head, 128 + 32 * k, entries[k], pages[k]);
            const size_t firstPlane = *std::min_element(pages.begin(), pages.end()) * size_t(4096);
            if (head.size() > firstPlane) throw std::runtime_error("the burst's header has no room for its frame table");
            std::fstream f(path, std::ios::in | std::ios::out | std::ios::binary);
            f.write(reinterpret_cast<const char*>(head.data()), std::streamsize(head.size()));
            if (!f) throw std::runtime_error("cannot write the burst header " + path);
            return path;
        }
        const int w = nch.width(), hgt = nch.height();
        const CropArea a = window ? *window : CropArea{0, 0, w, hgt};
        std::array<uint32_t, 32> h = nch.h;
        h[1] = 11;
        h[2] = uint32_t(a.width);
        h[3] = uint32_t(a.height);
        h[5] = uint32_t(entries.size());
        std::vector<uint8_t> head(128 + 32 * entries.size());
        std::memcpy(head.data(), h.data(), 128);
        for (size_t k = 0; k < entries.size(); k++) putTable(head, 128 + 32 * k, entries[k], 0);
        std::ofstream o(out, std::ios::binary | std::ios::trunc);
        o.write(reinterpret_cast<const char*>(head.data()), std::streamsize(head.size()));
        for (int i : source) {
            const uint16_t* p = nch.plane(i);
            for (int y = 0; y < a.height; y++)
                o.write(reinterpret_cast<const char*>(p + size_t(a.y + y) * w + a.x), std::streamsize(size_t(a.width) * 2));
        }
        if (!o) throw std::runtime_error("cannot write the burst " + out);
    }
    return out;
}

/// The photo's centre crop (RGBA8 rows without padding)
inline void cropRgba(std::vector<uint8_t>& px, int& w, int& h, const CropRect& r) {
    std::vector<uint8_t> out(size_t(r.width()) * r.height() * 4);
    for (int y = 0; y < r.height(); y++)
        std::memcpy(out.data() + size_t(y) * r.width() * 4, px.data() + (size_t(r.top + y) * w + r.left) * 4, size_t(r.width()) * 4);
    px.swap(out);
    w = r.width();
    h = r.height();
}

/// ScamPostDownscale.resizeTo: down only (the source when not smaller); Lanczos-3 or bicubic (Lanczos-2) as the native filter.
/// "area" / "bilinear" are Skia's createScaledBitmap on Android: here the bicubic kernel (close, not bit for bit).
inline void resizeTo(std::vector<uint8_t>& px, int& w, int& h, int tw, int th, const std::string& kernel) {
    if (tw >= w && th >= h) return;
    const int lobes = kernel == "bicubic" || kernel == "area" || kernel == "bilinear" ? 2 : 3;
    std::vector<uint8_t> out(size_t(tw) * th * 4);
    scamera_lanczos::resample(px.data(), w, h, size_t(w) * 4, out.data(), tw, th, size_t(tw) * 4, lobes, false);
    px.swap(out);
    w = tw;
    h = th;
}

/// ScamPostDownscale.scaleTo: down by resizeTo "bicubic", up (at most 1:4) by the Lanczos-2 resample (nativeResample)
inline void scaleTo(std::vector<uint8_t>& px, int& w, int& h, int tw, int th) {
    if (tw == w && th == h) return;
    if (tw <= w && th <= h) { resizeTo(px, w, h, tw, th, "bicubic"); return; }
    std::vector<uint8_t> out(size_t(tw) * th * 4);
    scamera_lanczos::resample(px.data(), w, h, size_t(w) * 4, out.data(), tw, th, size_t(tw) * 4, 2, true);
    px.swap(out);
    w = tw;
    h = th;
}

/// The Ultra HDR map along (PostPipeline.GainMapRaw croppedTo / croppedBy / resizedFor are Skia's filtered draws on Android; here
/// the same crop and a bilinear resample of the map's bytes)
inline void resizeMap(std::vector<uint8_t>& px, int& w, int& h, int tw, int th) {
    if (tw == w && th == h) return;
    std::vector<uint8_t> out(size_t(tw) * th * 4);
    for (int y = 0; y < th; y++) {
        const double sy = std::max(0.0, (y + 0.5) * h / th - 0.5);
        const int y0 = std::min(h - 1, int(sy)), y1 = std::min(h - 1, y0 + 1);
        const double fy = sy - y0;
        for (int x = 0; x < tw; x++) {
            const double sx = std::max(0.0, (x + 0.5) * w / tw - 0.5);
            const int x0 = std::min(w - 1, int(sx)), x1 = std::min(w - 1, x0 + 1);
            const double fx = sx - x0;
            for (int c = 0; c < 4; c++) {
                auto at = [&](int xx, int yy) { return double(px[(size_t(yy) * w + xx) * 4 + c]); };
                const double v = (at(x0, y0) * (1 - fx) + at(x1, y0) * fx) * (1 - fy) + (at(x0, y1) * (1 - fx) + at(x1, y1) * fx) * fy;
                out[(size_t(y) * tw + x) * 4 + c] = uint8_t(std::lround(std::max(0.0, std::min(255.0, v))));
            }
        }
    }
    px.swap(out);
    w = tw;
    h = th;
}

} // namespace scam_post
