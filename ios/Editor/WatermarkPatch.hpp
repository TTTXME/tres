// P633 / P642: the watermark's record (Android processing/watermark/WatermarkPatch.java + WatermarkStore.java), header-only C++.
// The original pixels under a photo's watermark and the watermark's spec, kept at the very end of the photo's file, so the
// gallery's editor can take the watermark off exactly (EditState.wmRemove) or draw another one. The same bytes as Android:
// «SCWM», version 1, size, format (ARGB_8888 / RGBA_1010102), colour space (sRGB / Display P3), the spec's JSON, then each piece
// (rect, codec, CRC-32 of its little-endian words, data); the predictive code (CODEC_MED: G, R-G, B-G, A planes, LOCO-I's median
// edge predictor, zigzag residuals, low bytes then high bytes, zlib-wrapped deflate) or Android's lossless WebP (CODEC_WEBP, read
// only: the iPhone has no WebP encoder, so it writes CODEC_MED, which Android reads). Stored as WatermarkStore appends it: after a
// JPEG's last EOI, a «SCWM» RIFF chunk of a WebP (the RIFF size grown), a top-level «uuid» box of a HEIC / AVIF, each ending
// with the record's length (big-endian) and «SCWMEND1».
// The platform supplies raw deflate / inflate (RFC 1951) and the WebP decoder (Codec): Foundation's NSData compression on the
// iPhone (Bridge/ScamEdit.mm), zlib in tools/edit_check.cpp. Checked against the Android classes by edit_check --wm.
#pragma once

#include "EditExport.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <functional>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

namespace scam_edit {
namespace wm {

constexpr int FORMAT_8888 = 0, FORMAT_1010102 = 1;
constexpr int SPACE_SRGB = 0, SPACE_P3 = 1;
constexpr int CODEC_MED = 0, CODEC_WEBP = 1;
/// Pixels a piece holds at most (16 MB of words).
constexpr int PIECE_PIXELS = 4 << 20;
constexpr int VERSION = 1;
constexpr uint8_t MAGIC[4] = {'S', 'C', 'W', 'M'};

/// android.graphics.Rect: integer pixels, right / bottom exclusive.
struct Rect {
    int left = 0, top = 0, right = 0, bottom = 0;
    int width() const { return right - left; }
    int height() const { return bottom - top; }
    bool empty() const { return left >= right || top >= bottom; }
    bool intersects(const Rect& o) const { return left < o.right && o.left < right && top < o.bottom && o.top < bottom; }
};

/// What the platform gives the record: raw DEFLATE both ways and the WebP decoder (empty: WebP pieces cannot be read).
struct Codec {
    /// RFC 1951 data of the bytes (no zlib header, no checksum).
    std::function<std::vector<uint8_t>(const uint8_t*, size_t)> deflateRaw;
    /// Exactly `outLen` bytes from RFC 1951 data; false when they are not there.
    std::function<bool(const uint8_t*, size_t, uint8_t*, size_t)> inflateRaw;
    /// A lossless WebP's words (R in the low byte, as Android's ARGB_8888 buffer), its size checked; false when not decoded.
    std::function<bool(const uint8_t*, size_t, int, int, std::vector<uint32_t>&)> decodeWebp;
};

// ───── checksums ─────

/// java.util.zip.CRC32 over the words' little-endian bytes.
inline int32_t crc(const uint32_t* words, size_t n) {
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
    for (size_t i = 0; i < n; i++) {
        const uint32_t v = words[i];
        for (int b = 0; b < 4; b++) c = table[(c ^ (v >> (8 * b))) & 0xFF] ^ (c >> 8);
    }
    return int32_t(c ^ 0xFFFFFFFFu);
}

inline uint32_t adler32(const uint8_t* p, size_t n) {
    uint32_t a = 1, b = 0;
    for (size_t i = 0; i < n; i++) {
        a = (a + p[i]) % 65521u;
        b = (b + a) % 65521u;
    }
    return (b << 16) | a;
}

/// DeflaterOutputStream's stream: the zlib header (deflate, 32 K window, the default level's bits), the data, Adler-32.
inline std::vector<uint8_t> zlibWrap(const Codec& codec, const std::vector<uint8_t>& plain) {
    if (!codec.deflateRaw) throw std::runtime_error("no deflate for the watermark record");
    std::vector<uint8_t> out{0x78, 0x9C};
    const std::vector<uint8_t> raw = codec.deflateRaw(plain.data(), plain.size());
    out.insert(out.end(), raw.begin(), raw.end());
    const uint32_t a = adler32(plain.data(), plain.size());
    for (int s = 24; s >= 0; s -= 8) out.push_back(uint8_t(a >> s));
    return out;
}

/// InflaterInputStream's read of exactly `n` bytes from a zlib stream.
inline std::vector<uint8_t> zlibUnwrap(const Codec& codec, const std::vector<uint8_t>& data, size_t n) {
    if (!codec.inflateRaw) throw std::runtime_error("no inflate for the watermark record");
    if (data.size() < 6 || (data[0] & 0x0F) != 8 || (unsigned(data[0]) * 256u + data[1]) % 31u != 0 || (data[1] & 0x20))
        throw std::runtime_error("watermark piece: not a zlib stream");
    std::vector<uint8_t> out(n);
    // the deflate data alone: the two header bytes and the Adler-32 at the end left out (the pieces' CRC checks the words)
    if (!codec.inflateRaw(data.data() + 2, data.size() - 6, out.data(), n)) throw std::runtime_error("watermark piece: short data");
    return out;
}

// ───── the predictive code ─────

inline std::array<int, 4> bitsOf(int format) {
    return format == FORMAT_1010102 ? std::array<int, 4>{10, 10, 10, 2} : std::array<int, 4>{8, 8, 8, 8};
}

inline std::array<int, 4> shiftsOf(int format) {
    return format == FORMAT_1010102 ? std::array<int, 4>{0, 10, 20, 30} : std::array<int, 4>{0, 8, 16, 24};
}

/// LOCO-I's median edge detector from left a, above b, above-left c; the first row takes the left, the first column above.
inline int predict(const std::vector<int>& v, size_t i, int x, int y, int w) {
    if (y == 0) return x == 0 ? 0 : v[i - 1];
    if (x == 0) return v[i - size_t(w)];
    const int a = v[i - 1], b = v[i - size_t(w)], c = v[i - size_t(w) - 1];
    const int mx = std::max(a, b), mn = std::min(a, b);
    return c >= mx ? mn : c <= mn ? mx : a + b - c;
}

/// The planes coded: G, R - G, B - G (mod 2^bits), A.
inline std::array<std::vector<int>, 4> planes(const std::vector<uint32_t>& words, int format) {
    const auto b = bitsOf(format), s = shiftsOf(format);
    const size_t n = words.size();
    const uint32_t mask = (1u << b[0]) - 1, am = (1u << b[3]) - 1;
    std::array<std::vector<int>, 4> p;
    for (auto& q : p) q.resize(n);
    for (size_t i = 0; i < n; i++) {
        const uint32_t w = words[i];
        const int R = int((w >> s[0]) & mask), G = int((w >> s[1]) & mask), B = int((w >> s[2]) & mask);
        p[0][i] = G;
        p[1][i] = int(uint32_t(R - G) & mask);
        p[2][i] = int(uint32_t(B - G) & mask);
        p[3][i] = int((w >> s[3]) & am);
    }
    return p;
}

inline std::vector<uint32_t> unplanes(const std::array<std::vector<int>, 4>& p, int format) {
    const auto b = bitsOf(format), s = shiftsOf(format);
    const size_t n = p[0].size();
    const uint32_t mask = (1u << b[0]) - 1;
    std::vector<uint32_t> out(n);
    for (size_t i = 0; i < n; i++) {
        const uint32_t G = uint32_t(p[0][i]), R = (uint32_t(p[1][i]) + G) & mask, B = (uint32_t(p[2][i]) + G) & mask;
        out[i] = (R << s[0]) | (G << s[1]) | (B << s[2]) | (uint32_t(p[3][i]) << s[3]);
    }
    return out;
}

/// WatermarkPatch.med: the residual planes as the bytes Android deflates (before the deflate).
inline std::vector<uint8_t> medPlain(const std::vector<uint32_t>& words, int w, int h, int format) {
    const auto bt = bitsOf(format);
    const auto pl = planes(words, format);
    std::vector<uint8_t> out;
    out.reserve(words.size() * 4);
    for (int c = 0; c < 4; c++) {
        const int bits = bt[c], m = (1 << bits) - 1, half = 1 << (bits - 1);
        const std::vector<int>& v = pl[c];
        for (int pass = 0; pass < (bits > 8 ? 2 : 1); pass++) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    const size_t i = size_t(y) * size_t(w) + size_t(x);
                    const int r = (v[i] - predict(v, i, x, y, w)) & m;
                    const int sgn = r >= half ? r - (m + 1) : r;
                    const int z = sgn >= 0 ? 2 * sgn : -2 * sgn - 1;
                    out.push_back(uint8_t(pass == 0 ? z : z >> 8));
                }
            }
        }
    }
    return out;
}

inline std::vector<uint8_t> med(const Codec& codec, const std::vector<uint32_t>& words, int w, int h, int format) {
    return zlibWrap(codec, medPlain(words, w, h, format));
}

inline std::vector<uint32_t> unmed(const Codec& codec, const std::vector<uint8_t>& data, int w, int h, int format) {
    const auto bt = bitsOf(format);
    const size_t n = size_t(w) * size_t(h);
    size_t total = 0;
    for (int c = 0; c < 4; c++) total += bt[c] > 8 ? 2 * n : n;
    const std::vector<uint8_t> plain = zlibUnwrap(codec, data, total);
    std::array<std::vector<int>, 4> pl;
    size_t at = 0;
    for (int c = 0; c < 4; c++) {
        const int bits = bt[c], m = (1 << bits) - 1;
        const uint8_t* lo = plain.data() + at;
        at += n;
        const uint8_t* hi = bits > 8 ? plain.data() + at : nullptr;
        if (bits > 8) at += n;
        std::vector<int> v(n);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                const size_t i = size_t(y) * size_t(w) + size_t(x);
                const int z = lo[i] | (hi ? hi[i] << 8 : 0);
                const int sgn = (z & 1) == 0 ? z >> 1 : -((z + 1) >> 1);
                v[i] = (predict(v, i, x, y, w) + sgn) & m;
            }
        }
        pl[c] = std::move(v);
    }
    return unplanes(pl, format);
}

// ───── the record ─────

struct Piece {
    Rect rect;
    int codec = CODEC_MED;
    int32_t crc = 0;
    std::vector<uint8_t> data;
};

/// `rects` cut in row strips of at most PIECE_PIXELS.
inline std::vector<Rect> split(const std::vector<Rect>& rects) {
    std::vector<Rect> out;
    for (const Rect& r : rects) {
        if (r.empty()) continue;
        const int rows = std::max(1, PIECE_PIXELS / std::max(1, r.width()));
        for (int y = r.top; y < r.bottom; y += rows) out.push_back(Rect{r.left, y, r.right, std::min(r.bottom, y + rows)});
    }
    return out;
}

struct Patch {
    int width = 0, height = 0, format = FORMAT_8888, colourSpace = SPACE_SRGB;
    /// The watermark's settings (WatermarkSpec.toJson).
    std::string spec;
    std::vector<Piece> pieces;

    long long pixels() const {
        long long n = 0;
        for (const Piece& p : pieces) n += (long long)p.rect.width() * p.rect.height();
        return n;
    }

    long long encodedBytes() const {
        long long n = 0;
        for (const Piece& p : pieces) n += (long long)p.data.size();
        return n;
    }

    /// WatermarkPatch.toString.
    std::string describe() const {
        return "WatermarkPatch{" + std::to_string(width) + "x" + std::to_string(height) + (format == FORMAT_1010102 ? " 10-bit" : " 8-bit")
               + (colourSpace == SPACE_P3 ? " P3" : "") + ", " + std::to_string(pieces.size()) + " pieces, " + std::to_string(pixels())
               + " px, " + std::to_string(encodedBytes()) + " bytes}";
    }

    /// The record as stored: «SCWM», version, size, format, colour space, spec, then each piece (rect, codec, CRC, data).
    std::vector<uint8_t> toBytes() const {
        std::vector<uint8_t> o;
        o.reserve(size_t(encodedBytes()) + 1024);
        auto i32 = [&o](int64_t v) {
            const uint32_t u = uint32_t(v);
            for (int s = 24; s >= 0; s -= 8) o.push_back(uint8_t(u >> s));
        };
        o.insert(o.end(), MAGIC, MAGIC + 4);
        o.push_back(uint8_t(VERSION));
        i32(width);
        i32(height);
        o.push_back(uint8_t(format));
        o.push_back(uint8_t(colourSpace));
        i32(int64_t(spec.size()));
        o.insert(o.end(), spec.begin(), spec.end());
        i32(int64_t(pieces.size()));
        for (const Piece& p : pieces) {
            i32(p.rect.left);
            i32(p.rect.top);
            i32(p.rect.width());
            i32(p.rect.height());
            o.push_back(uint8_t(p.codec));
            i32(p.crc);
            i32(int64_t(p.data.size()));
            o.insert(o.end(), p.data.begin(), p.data.end());
        }
        return o;
    }

    /// The record of toBytes; throws for bytes that are not one (or not whole).
    static Patch fromBytes(const uint8_t* b, size_t size) {
        size_t at = 0;
        auto need = [&](size_t n) {
            if (size - at < n) throw std::runtime_error("watermark record: short");
        };
        auto u8 = [&]() -> int {
            need(1);
            return b[at++];
        };
        auto i32 = [&]() -> int32_t {
            need(4);
            const uint32_t v = uint32_t(b[at]) << 24 | uint32_t(b[at + 1]) << 16 | uint32_t(b[at + 2]) << 8 | uint32_t(b[at + 3]);
            at += 4;
            return int32_t(v);
        };
        need(4);
        if (std::memcmp(b, MAGIC, 4) != 0) throw std::runtime_error("not a watermark record");
        at = 4;
        const int version = u8();
        if (version != VERSION) throw std::runtime_error("watermark record version " + std::to_string(version));
        Patch p;
        p.width = i32();
        p.height = i32();
        p.format = u8();
        p.colourSpace = u8();
        if (p.width <= 0 || p.height <= 0 || p.format > FORMAT_1010102 || p.colourSpace > SPACE_P3) throw std::runtime_error("bad watermark record");
        const int32_t sl = i32();
        if (sl < 0 || sl > 1 << 16) throw std::runtime_error("bad watermark spec");
        need(size_t(sl));
        p.spec.assign(reinterpret_cast<const char*>(b + at), size_t(sl));
        at += size_t(sl);
        const int32_t n = i32();
        if (n < 0 || n > 1 << 20) throw std::runtime_error("bad watermark pieces");
        p.pieces.reserve(size_t(n));
        for (int32_t i = 0; i < n; i++) {
            const int32_t x = i32(), y = i32(), w = i32(), h = i32();
            const int codec = u8();
            const int32_t c = i32(), len = i32();
            if (w <= 0 || h <= 0 || x < 0 || y < 0 || (long long)x + w > p.width || (long long)y + h > p.height
                || (long long)w * h > PIECE_PIXELS || codec > CODEC_WEBP || len < 0 || (size_t)len > size)
                throw std::runtime_error("bad watermark piece " + std::to_string(i));
            need(size_t(len));
            Piece q;
            q.rect = Rect{x, y, x + w, y + h};
            q.codec = codec;
            q.crc = c;
            q.data.assign(b + at, b + at + len);
            at += size_t(len);
            p.pieces.push_back(std::move(q));
        }
        return p;
    }

    /// The words of piece `i`, checked against its CRC.
    std::vector<uint32_t> wordsOf(size_t i, const Codec& codec) const {
        const Piece& p = pieces.at(i);
        const int w = p.rect.width(), h = p.rect.height();
        std::vector<uint32_t> words;
        if (p.codec == CODEC_WEBP) {
            if (!codec.decodeWebp || !codec.decodeWebp(p.data.data(), p.data.size(), w, h, words) || words.size() != size_t(w) * size_t(h))
                throw std::runtime_error("watermark piece " + std::to_string(i) + " not decoded");
        } else {
            words = unmed(codec, p.data, w, h, format);
        }
        if (crc(words.data(), words.size()) != p.crc) throw std::runtime_error("watermark piece " + std::to_string(i) + ": CRC mismatch");
        return words;
    }

    /// Whether every piece decodes and matches its CRC (the editor checks before it offers «Убрать»).
    bool verify(const Codec& codec) const {
        try {
            for (size_t i = 0; i < pieces.size(); i++) wordsOf(i, codec);
            return true;
        } catch (const std::exception&) {
            return false;
        }
    }

    /// Piece `i` as RGBA8 bytes (R, G, B, A): a 10-bit piece as WatermarkPatch.pieceBitmap's 8-bit fallback (each channel
    /// (v x 255 + 511) / 1023, opaque).
    std::vector<uint8_t> rgba8Of(size_t i, const Codec& codec) const {
        std::vector<uint32_t> words = wordsOf(i, codec);
        std::vector<uint8_t> out(words.size() * 4);
        for (size_t k = 0; k < words.size(); k++) {
            const uint32_t v = words[k];
            if (format == FORMAT_1010102) {
                out[4 * k] = uint8_t(((v & 1023) * 255 + 511) / 1023);
                out[4 * k + 1] = uint8_t((((v >> 10) & 1023) * 255 + 511) / 1023);
                out[4 * k + 2] = uint8_t((((v >> 20) & 1023) * 255 + 511) / 1023);
                out[4 * k + 3] = 255;
            } else {
                out[4 * k] = uint8_t(v);
                out[4 * k + 1] = uint8_t(v >> 8);
                out[4 * k + 2] = uint8_t(v >> 16);
                out[4 * k + 3] = uint8_t(v >> 24);
            }
        }
        return out;
    }

    /// WatermarkPatch.paint: the kept pixels into `target` (RGBA8 rows, tw x th, `stride` bytes apart), a picture of the photo's
    /// region `src` (stored px of the width x height photo) at the target's size: pixel for pixel when the target is that region
    /// at full size, else scaled (halved by 2 x 2 means while more than 2x smaller, then bilinear at the pixel centres - Android
    /// draws with Skia's filtering there: a preview or a reduced export, not bit-exact with it).
    void paint(uint8_t* target, int tw, int th, size_t stride, const Rect& src, const Codec& codec) const {
        if (src.empty() || tw <= 0 || th <= 0) throw std::runtime_error("not a picture of a region");
        const double sx = tw / double(src.width()), sy = th / double(src.height());
        const bool exact = tw == src.width() && th == src.height();
        for (size_t i = 0; i < pieces.size(); i++) {
            const Rect r = pieces[i].rect;
            if (!r.intersects(src)) continue;
            std::vector<uint8_t> px = rgba8Of(i, codec);
            int pw = r.width(), ph = r.height();
            if (exact) {
                for (int y = 0; y < ph; y++) {
                    const int ty = r.top - src.top + y;
                    if (ty < 0 || ty >= th) continue;
                    const int x0 = std::max(0, src.left - r.left), x1 = std::min(pw, src.right - r.left);
                    if (x1 <= x0) continue;
                    std::memcpy(target + size_t(ty) * stride + size_t(r.left - src.left + x0) * 4, px.data() + (size_t(y) * size_t(pw) + size_t(x0)) * 4,
                                size_t(x1 - x0) * 4);
                }
                continue;
            }
            const double dl = (r.left - src.left) * sx, dt = (r.top - src.top) * sy, dr = (r.right - src.left) * sx, db = (r.bottom - src.top) * sy;
            const double dw = dr - dl, dh = db - dt;
            while (pw > 2 * std::max(1.0, dw) && ph > 2 * std::max(1.0, dh)) {
                const int nw = std::max(1, pw / 2), nh = std::max(1, ph / 2);
                std::vector<uint8_t> half(size_t(nw) * size_t(nh) * 4);
                for (int y = 0; y < nh; y++) {
                    for (int x = 0; x < nw; x++) {
                        const int x0 = std::min(pw - 1, 2 * x), x1 = std::min(pw - 1, 2 * x + 1);
                        const int y0 = std::min(ph - 1, 2 * y), y1 = std::min(ph - 1, 2 * y + 1);
                        for (int c = 0; c < 4; c++) {
                            const int s = px[(size_t(y0) * pw + x0) * 4 + c] + px[(size_t(y0) * pw + x1) * 4 + c] + px[(size_t(y1) * pw + x0) * 4 + c]
                                          + px[(size_t(y1) * pw + x1) * 4 + c];
                            half[(size_t(y) * nw + x) * 4 + c] = uint8_t((s + 2) / 4);
                        }
                    }
                }
                px.swap(half);
                pw = nw;
                ph = nh;
            }
            // the target pixels whose centres fall in the destination rectangle
            const int x0 = std::max(0, int(std::ceil(dl - 0.5))), x1 = std::min(tw, int(std::ceil(dr - 0.5)));
            const int y0 = std::max(0, int(std::ceil(dt - 0.5))), y1 = std::min(th, int(std::ceil(db - 0.5)));
            for (int y = y0; y < y1; y++) {
                const double v = std::max(0.0, std::min(double(ph - 1), (y + 0.5 - dt) * ph / dh - 0.5));
                const int v0 = int(v), v1 = std::min(ph - 1, v0 + 1);
                const double fy = v - v0;
                uint8_t* row = target + size_t(y) * stride;
                for (int x = x0; x < x1; x++) {
                    const double u = std::max(0.0, std::min(double(pw - 1), (x + 0.5 - dl) * pw / dw - 0.5));
                    const int u0 = int(u), u1 = std::min(pw - 1, u0 + 1);
                    const double fx = u - u0;
                    for (int c = 0; c < 4; c++) {
                        const double a = px[(size_t(v0) * pw + u0) * 4 + c] * (1 - fx) + px[(size_t(v0) * pw + u1) * 4 + c] * fx;
                        const double b = px[(size_t(v1) * pw + u0) * 4 + c] * (1 - fx) + px[(size_t(v1) * pw + u1) * 4 + c] * fx;
                        row[size_t(x) * 4 + c] = uint8_t(std::min(255.0, std::max(0.0, a * (1 - fy) + b * fy + 0.5)));
                    }
                }
            }
        }
    }

    /// WatermarkPatch.restore: the kept pixels back into the whole photo at its own size.
    void restore(uint8_t* rgba, size_t stride, const Codec& codec) const {
        paint(rgba, width, height, stride, Rect{0, 0, width, height}, codec);
    }
};

/// WatermarkPatch.capture for RGBA8 rows (the pipeline's photo before its watermark): the pieces of `rects` (split), each coded
/// with the predictive code (no WebP encoder here) and its CRC; `opaque`: the fourth byte read as 255 (the iPhone's rows leave it
/// undefined, the encoder skips it - Android's ARGB_8888 photo is opaque).
inline Patch capture(const uint8_t* rgba, int width, int height, size_t stride, const std::vector<Rect>& rects, const std::string& spec,
                     int colourSpace, bool opaque, const Codec& codec) {
    Patch p;
    p.width = width;
    p.height = height;
    p.format = FORMAT_8888;
    p.colourSpace = colourSpace;
    p.spec = spec;
    for (const Rect& r0 : split(rects)) {
        const Rect r{std::max(0, r0.left), std::max(0, r0.top), std::min(width, r0.right), std::min(height, r0.bottom)};
        if (r.empty()) continue;
        const int w = r.width(), h = r.height();
        std::vector<uint32_t> words(size_t(w) * size_t(h));
        for (int y = 0; y < h; y++) {
            const uint8_t* s = rgba + size_t(r.top + y) * stride + size_t(r.left) * 4;
            for (int x = 0; x < w; x++, s += 4)
                words[size_t(y) * w + x] = uint32_t(s[0]) | uint32_t(s[1]) << 8 | uint32_t(s[2]) << 16 | uint32_t(opaque ? 255 : s[3]) << 24;
        }
        Piece q;
        q.rect = r;
        q.codec = CODEC_MED;
        q.crc = crc(words.data(), words.size());
        q.data = med(codec, words, w, h, FORMAT_8888);
        p.pieces.push_back(std::move(q));
    }
    return p;
}

/// EditSource.recordFits: a record is this photo's - the photo's stored size, stored upright (the camera's photos have no turn).
inline bool recordFits(const Patch& p, int fullW, int fullH, int orientation) {
    return p.width == fullW && p.height == fullH && orientation == 1;
}

// ───── WatermarkStore ─────

namespace store {

constexpr uint8_t END[8] = {'S', 'C', 'W', 'M', 'E', 'N', 'D', '1'};
constexpr size_t FOOTER = 4 + 8;
/// The record's uuid box type (ISO BMFF): a random version-4 UUID of SCAMERA's watermark record.
constexpr uint8_t UUID[16] = {0x5c, 0xa3, 0x7e, 0x1a, 0x9b, 0x0d, 0x4f, 0x83, 0xa6, 0x21, 0xe4, 0x58, 0x2f, 0xc7, 0x10, 0x9d};
/// The largest record read back (bytes).
constexpr uint64_t MAX_RECORD = 512ull << 20;

enum class Container { JPEG, WEBP, ISOBMFF, OTHER };

inline Container sniff(const uint8_t* h, size_t n) {
    if (n >= 3 && h[0] == 0xFF && h[1] == 0xD8 && h[2] == 0xFF) return Container::JPEG;
    if (n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P')
        return Container::WEBP;
    if (n >= 8 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') return Container::ISOBMFF;
    return Container::OTHER;
}

/// WatermarkStore.append: `record` appended to the file's bytes in its container's form; false (untouched) for a container it
/// does not know.
inline bool append(std::vector<uint8_t>& file, const std::vector<uint8_t>& record) {
    const Container c = sniff(file.data(), std::min<size_t>(16, file.size()));
    if (c == Container::OTHER) return false;
    auto be = [](std::vector<uint8_t>& o, uint32_t v) {
        for (int s = 24; s >= 0; s -= 8) o.push_back(uint8_t(v >> s));
    };
    auto le = [](std::vector<uint8_t>& o, uint32_t v) {
        for (int s = 0; s < 32; s += 8) o.push_back(uint8_t(v >> s));
    };
    std::vector<uint8_t> footer;
    be(footer, uint32_t(record.size()));
    footer.insert(footer.end(), END, END + 8);
    switch (c) {
        case Container::WEBP: {
            // [SCWM][size LE][pad][record][footer]: the chunk's data even, the footer last
            const size_t data = record.size() + FOOTER;
            const size_t pad = data & 1;
            std::vector<uint8_t> h{'S', 'C', 'W', 'M'};
            le(h, uint32_t(data + pad));
            if (pad) h.push_back(0);
            file.insert(file.end(), h.begin(), h.end());
            file.insert(file.end(), record.begin(), record.end());
            file.insert(file.end(), footer.begin(), footer.end());
            const uint32_t riff = uint32_t(file.size() - 8);
            for (int k = 0; k < 4; k++) file[4 + size_t(k)] = uint8_t(riff >> (8 * k));
            break;
        }
        case Container::ISOBMFF: {
            const uint64_t box = 8ull + 16 + record.size() + FOOTER;
            if (box > 0xFFFFFFFFull) return false;
            std::vector<uint8_t> h;
            be(h, uint32_t(box));
            h.insert(h.end(), {'u', 'u', 'i', 'd'});
            h.insert(h.end(), UUID, UUID + 16);
            file.insert(file.end(), h.begin(), h.end());
            file.insert(file.end(), record.begin(), record.end());
            file.insert(file.end(), footer.begin(), footer.end());
            break;
        }
        default:
            file.insert(file.end(), record.begin(), record.end());
            file.insert(file.end(), footer.begin(), footer.end());
    }
    return true;
}

/// WatermarkStore.read: the record at the end of a whole file, nullopt when there is none.
inline std::optional<std::vector<uint8_t>> read(const uint8_t* file, size_t size) {
    if (size < FOOTER + 16) return std::nullopt;
    const uint8_t* f = file + size - FOOTER;
    const uint64_t len = uint64_t(f[0]) << 24 | uint64_t(f[1]) << 16 | uint64_t(f[2]) << 8 | uint64_t(f[3]);
    if (std::memcmp(f + 4, END, 8) != 0 || len == 0 || len > MAX_RECORD || len > size - FOOTER - 16) return std::nullopt;
    const uint8_t* r = file + size - FOOTER - len;
    return std::vector<uint8_t>(r, r + len);
}

} // namespace store

// ───── the editor ─────

/// EditSource.readWatermark's clean preview: the record's pixels painted into the picture's preview (the whole photo's region).
inline std::vector<uint8_t> cleanPreview(const PreviewSource& s, const Patch& p, const Codec& codec) {
    std::vector<uint8_t> out = s.rgba;
    p.paint(out.data(), s.width, s.height, size_t(s.width) * 4, Rect{0, 0, p.width, p.height}, codec);
    return out;
}

/// The picture reader's restoreWatermark (EditSource.restored for the whole picture): the record painted into the export's
/// picture (`w` x `h`, the photo's stored pixels or a reduced decode of them).
inline void restorePicture(std::vector<uint8_t>& rgba, int w, int h, const Patch& p, const Codec& codec) {
    p.paint(rgba.data(), w, h, size_t(w) * 4, Rect{0, 0, p.width, p.height}, codec);
}

} // namespace wm
} // namespace scam_edit
