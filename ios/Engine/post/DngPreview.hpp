// P620: the developed photo as the DNG's preview (Android processing/DngPreview.java + PhotoThumbnail.scaled / dngSource), on the
// file's bytes: the photo at most DNG_LONG_SIDE px (each pixel the mean of up to 4 x 4 samples of its box), turned back to the
// sensor's orientation; its baseline JPEG (the platform's encoder) appended with its IFD (NewSubFileType 1, Compression 7, YCbCr,
// PreviewColorSpace) and a copy of IFD 0 with a SubIFDs tag pointing at it, then the header's IFD 0 offset moved to the copy.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace scam_post {
namespace DngPreview {

constexpr int DNG_LONG_SIDE = 1024;
constexpr int COLOUR_SRGB = 2, COLOUR_UNKNOWN = 0;
constexpr int TAG_SUB_IFDS = 330;

/// PhotoThumbnail.size
inline void size(int width, int height, int longSide, int& tw, int& th) {
    if (width <= 0 || height <= 0) { tw = th = 0; return; }
    if (std::max(width, height) <= longSide) { tw = width; th = height; return; }
    const double s = longSide / double(std::max(width, height));
    tw = std::max(1, int(std::floor(width * s + 0.5)));
    th = std::max(1, int(std::floor(height * s + 0.5)));
}

/// PhotoThumbnail.sample of RGBA8 rows: each pixel the rounded mean of up to 4 x 4 samples spread over its box (alpha 255)
inline std::vector<uint8_t> scaled(const uint8_t* rgba, int width, int height, int longSide, int& tw, int& th) {
    size(width, height, longSide, tw, th);
    std::vector<uint8_t> out(size_t(tw) * th * 4);
    std::vector<int> xs(size_t(4) * size_t(tw)), nxs(static_cast<size_t>(tw));
    for (int tx = 0; tx < tw; tx++) {
        const int x0 = int((long long)tx * width / tw), x1 = std::max(x0 + 1, int((long long)(tx + 1) * width / tw));
        const int nx = std::min(4, x1 - x0);
        nxs[size_t(tx)] = nx;
        for (int i = 0; i < nx; i++) xs[size_t(4 * tx + i)] = x0 + int((2LL * i + 1) * (x1 - x0) / (2LL * nx));
    }
    for (int ty = 0; ty < th; ty++) {
        const int y0 = int((long long)ty * height / th), y1 = std::max(y0 + 1, int((long long)(ty + 1) * height / th));
        const int ny = std::min(4, y1 - y0);
        int rows[4];
        for (int j = 0; j < ny; j++) rows[j] = y0 + int((2LL * j + 1) * (y1 - y0) / (2LL * ny));
        for (int tx = 0; tx < tw; tx++) {
            const int nx = nxs[size_t(tx)];
            int r = 0, g = 0, b = 0;
            for (int j = 0; j < ny; j++)
                for (int i = 0; i < nx; i++) {
                    const uint8_t* c = rgba + (size_t(rows[j]) * width + xs[size_t(4 * tx + i)]) * 4;
                    r += c[0];
                    g += c[1];
                    b += c[2];
                }
            const int n = nx * ny;
            uint8_t* o = out.data() + (size_t(ty) * tw + tx) * 4;
            o[0] = uint8_t((r + n / 2) / n);
            o[1] = uint8_t((g + n / 2) / n);
            o[2] = uint8_t((b + n / 2) / n);
            o[3] = 255;
        }
    }
    return out;
}

/// PhotoThumbnail.dngSource: the upright photo turned back by cameraRotation degrees (counter-clockwise), as the DNG's
/// Orientation turns its previews as its raw
inline std::vector<uint8_t> dngSource(const std::vector<uint8_t>& rgba, int& w, int& h, int cameraRotation) {
    const int r = ((cameraRotation % 360) + 360) % 360;
    if (r != 90 && r != 180 && r != 270) return rgba;
    const int ow = r == 180 ? w : h, oh = r == 180 ? h : w;
    std::vector<uint8_t> out(rgba.size());
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
            int ox, oy;
            if (r == 90) { ox = y; oy = w - 1 - x; }
            else if (r == 180) { ox = w - 1 - x; oy = h - 1 - y; }
            else { ox = h - 1 - y; oy = x; }
            std::memcpy(out.data() + (size_t(oy) * ow + ox) * 4, rgba.data() + (size_t(y) * w + x) * 4, 4);
        }
    w = ow;
    h = oh;
    return out;
}

/// DngPreview.append on the file's bytes: false (the bytes untouched) for a file it does not make sense of
inline bool append(std::vector<uint8_t>& dng, const std::vector<uint8_t>& jpeg, int width, int height, int colourSpace, const std::string& software) {
    if (jpeg.empty() || width <= 0 || height <= 0 || dng.size() < 8) return false;
    const bool le = dng[0] == 'I' && dng[1] == 'I', be = dng[0] == 'M' && dng[1] == 'M';
    if (!le && !be) return false;
    auto get16 = [&](size_t o) { return le ? uint32_t(dng[o] | dng[o + 1] << 8) : uint32_t(dng[o] << 8 | dng[o + 1]); };
    auto get32 = [&](size_t o) {
        return le ? uint32_t(dng[o]) | uint32_t(dng[o + 1]) << 8 | uint32_t(dng[o + 2]) << 16 | uint32_t(dng[o + 3]) << 24
                  : uint32_t(dng[o]) << 24 | uint32_t(dng[o + 1]) << 16 | uint32_t(dng[o + 2]) << 8 | uint32_t(dng[o + 3]);
    };
    if (get16(2) != 42) return false;
    const uint64_t ifd0 = get32(4), length = dng.size();
    if (ifd0 < 8 || ifd0 + 2 > length) return false;
    const uint32_t count = get16(size_t(ifd0));
    if (ifd0 + 2 + 12ull * count + 4 > length) return false;
    std::vector<std::vector<uint8_t>> kept;
    for (uint32_t i = 0; i < count; i++) {
        const size_t e = size_t(ifd0 + 2 + 12ull * i);
        if (get16(e) == uint32_t(TAG_SUB_IFDS)) return false;
        kept.emplace_back(dng.begin() + long(e), dng.begin() + long(e + 12));
    }
    const uint32_t nextIfd = get32(size_t(ifd0 + 2 + 12ull * count));
    std::vector<uint8_t> app;
    if (!software.empty()) { app.assign(software.begin(), software.end()); app.push_back(0); }
    auto even = [](uint64_t at) { return (at + 1) & ~uint64_t(1); };
    const uint64_t jpegAt = even(length), appAt = even(jpegAt + jpeg.size()), bitsAt = even(appAt + app.size());
    const uint64_t previewAt = even(bitsAt + 6);
    const int previewEntries = 13 + (app.empty() ? 0 : 1);
    const uint64_t ifd0At = even(previewAt + 2 + 12ull * previewEntries + 4);
    const uint64_t end = ifd0At + 2 + 12ull * (count + 1) + 4;
    if (end > 0xFFFFFFFFull) return false;
    std::vector<uint8_t> out(size_t(end - length), 0);
    size_t at = 0;
    auto seek = [&](uint64_t abs) { at = size_t(abs - length); };
    auto put16 = [&](uint32_t v) {
        if (le) { out[at] = uint8_t(v); out[at + 1] = uint8_t(v >> 8); }
        else { out[at] = uint8_t(v >> 8); out[at + 1] = uint8_t(v); }
        at += 2;
    };
    auto put32 = [&](uint32_t v) {
        if (le) for (int k = 0; k < 4; k++) out[at + size_t(k)] = uint8_t(v >> (8 * k));
        else for (int k = 0; k < 4; k++) out[at + size_t(k)] = uint8_t(v >> (24 - 8 * k));
        at += 4;
    };
    auto putBytes = [&](const uint8_t* p, size_t n) { std::memcpy(out.data() + at, p, n); at += n; };
    auto entry = [&](uint32_t tag, uint32_t type, uint32_t n, uint32_t value) { put16(tag); put16(type); put32(n); put32(value); };
    auto entryShort = [&](uint32_t tag, uint32_t value) { put16(tag); put16(3); put32(1); put16(value); put16(0); };
    seek(jpegAt);
    putBytes(jpeg.data(), jpeg.size());
    seek(appAt);
    if (!app.empty()) putBytes(app.data(), app.size());
    seek(bitsAt);
    put16(8); put16(8); put16(8);
    seek(previewAt);
    put16(uint32_t(previewEntries));
    entry(254, 4, 1, 1);
    entry(256, 4, 1, uint32_t(width));
    entry(257, 4, 1, uint32_t(height));
    entry(258, 3, 3, uint32_t(bitsAt));
    entryShort(259, 7);
    entryShort(262, 6);
    entry(273, 4, 1, uint32_t(jpegAt));
    entryShort(277, 3);
    entry(278, 4, 1, uint32_t(height));
    entry(279, 4, 1, uint32_t(jpeg.size()));
    entryShort(284, 1);
    put16(530); put16(3); put32(2); put16(2); put16(2);
    if (app.size() > 4) entry(50966, 2, uint32_t(app.size()), uint32_t(appAt));
    else if (!app.empty()) {
        put16(50966); put16(2); put32(uint32_t(app.size()));
        uint8_t four[4] = {0, 0, 0, 0};
        std::memcpy(four, app.data(), app.size());
        putBytes(four, 4);
    }
    entry(50970, 4, 1, uint32_t(colourSpace));
    put32(0);
    seek(ifd0At);
    put16(count + 1);
    bool placed = false;
    for (const auto& one : kept) {
        const uint32_t tag = le ? uint32_t(one[0] | one[1] << 8) : uint32_t(one[0] << 8 | one[1]);
        if (!placed && tag > uint32_t(TAG_SUB_IFDS)) {
            entry(TAG_SUB_IFDS, 4, 1, uint32_t(previewAt));
            placed = true;
        }
        putBytes(one.data(), 12);
    }
    if (!placed) entry(TAG_SUB_IFDS, 4, 1, uint32_t(previewAt));
    put32(nextIfd);
    dng.insert(dng.end(), out.begin(), out.end());
    const uint32_t o = uint32_t(ifd0At);
    if (le) for (int k = 0; k < 4; k++) dng[size_t(4 + k)] = uint8_t(o >> (8 * k));
    else for (int k = 0; k < 4; k++) dng[size_t(4 + k)] = uint8_t(o >> (24 - 8 * k));
    return true;
}

} // namespace DngPreview
} // namespace scam_post
