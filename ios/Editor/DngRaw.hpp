// The editor's RAW source (gallery/dng: DngPreview's TIFF reader, DngRaw, Lj92Decoder, DngColor) one to one: the largest raw IFD
// of a DNG (CFA or LinearRaw; uncompressed 8..16-bit strips / tiles or lossless JPEG) as 16-bit fixed-point linear samples
// ((value - black) / (white - black) x the OpcodeList2 gain maps, FIXED_ONE = white), a Quad CFA halved, binned bin x bin; the
// automatic lift; the DNG colour model (CCT / Duv white balance through the interpolated matrices, camera -> linear sRGB).
#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <functional>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace scam_edit {

// ---------------------------------------------------------------- TIFF (DngPreview's reader)

struct DngIfd {
    long long subType = 0;
    int width = 0, height = 0, compression = 1, photometric = -1, spp = 1, planar = 1, rowsPerStrip = 2147483647;
    std::vector<int> bps = {1};
    std::vector<long long> stripOffsets, stripCounts, tileOffsets, tileCounts;
    bool hasStrips = false, hasStripCounts = false, hasTiles = false, hasTileCounts = false;
    int tileW = 0, tileH = 0;
    int cfaRows = 2, cfaCols = 2;
    std::vector<int8_t> cfa;
    bool hasCfa = false;
    std::vector<int> linearization;
    bool hasLin = false;
    std::vector<double> black;
    bool hasBlack = false;
    int blackRows = 1, blackCols = 1;
    double white = -1;
    std::vector<int> activeArea;
    std::vector<double> cropOrigin, cropSize, blackDeltaH, blackDeltaV;
    bool hasActive = false, hasCropOrigin = false, hasCropSize = false, hasDeltaH = false, hasDeltaV = false;
    std::vector<uint8_t> opcodeList2;
    bool hasOpcodes = false;
};

class DngFile {
public:
    const uint8_t* d = nullptr;
    size_t limit = 0;
    bool le = true;
    std::vector<DngIfd> ifds;
    int orientation = 1;
    std::vector<double> colorMatrix1, forwardMatrix1, asShotNeutral, colorMatrix2, forwardMatrix2, cameraCalibration1,
        cameraCalibration2, analogBalance, asShotWhiteXY;
    double baselineExposure = 0;
    int illuminant1 = 0, illuminant2 = 0;
    bool dng = false;

    DngFile(const uint8_t* data, size_t size) : d(data), limit(size) {
        if (limit < 8) throw std::runtime_error("DNG: too short");
        if (d[0] == 'I' && d[1] == 'I') le = true;
        else if (d[0] == 'M' && d[1] == 'M') le = false;
        else throw std::runtime_error("DNG: not a TIFF");
        if (u16(2) != 42) throw std::runtime_error("DNG: not a TIFF");
        long long off = u32(4);
        int guard = 0;
        while (off != 0 && off < (long long)limit && guard++ < 16) off = readIfd(off, 0, true);
    }

    void need(long long at, long long n) const {
        if (at < 0 || at + n > (long long)limit) throw std::runtime_error("DNG: read outside the file");
    }
    uint32_t u16(long long o) const {
        need(o, 2);
        return le ? uint32_t(d[o]) | uint32_t(d[o + 1]) << 8 : uint32_t(d[o]) << 8 | uint32_t(d[o + 1]);
    }
    uint32_t u32(long long o) const {
        need(o, 4);
        return le ? uint32_t(d[o]) | uint32_t(d[o + 1]) << 8 | uint32_t(d[o + 2]) << 16 | uint32_t(d[o + 3]) << 24
                  : uint32_t(d[o]) << 24 | uint32_t(d[o + 1]) << 16 | uint32_t(d[o + 2]) << 8 | uint32_t(d[o + 3]);
    }
    int32_t i32(long long o) const { return int32_t(u32(o)); }
    uint8_t u8(long long o) const { need(o, 1); return d[o]; }

private:
    static int typeSize(int type) {
        switch (type) {
        case 1: case 2: case 6: case 7: return 1;
        case 3: case 8: return 2;
        case 4: case 9: case 11: case 13: return 4;
        case 5: case 10: case 12: return 8;
        default: return 0;
        }
    }

    double num(int type, long long vo, long long i) const {
        switch (type) {
        case 1: case 7: return u8(vo + i);
        case 6: return int8_t(u8(vo + i));
        case 3: return u16(vo + 2 * i);
        case 8: return int16_t(u16(vo + 2 * i));
        case 4: case 13: return double(u32(vo + 4 * i));
        case 9: return i32(vo + 4 * i);
        case 5: { const long long n = u32(vo + 8 * i), dd = u32(vo + 8 * i + 4); return dd == 0 ? 0 : double(n) / double(dd); }
        case 10: { const int n = i32(vo + 8 * i), dd = i32(vo + 8 * i + 4); return dd == 0 ? 0 : double(n) / double(dd); }
        case 11: { const uint32_t b = u32(vo + 4 * i); float f; std::memcpy(&f, &b, 4); return f; }
        case 12: {
            need(vo + 8 * i, 8);
            uint64_t b = 0;
            for (int k = 0; k < 8; k++) b = le ? b | uint64_t(d[vo + 8 * i + k]) << (8 * k) : b << 8 | uint64_t(d[vo + 8 * i + k]);
            double v;
            std::memcpy(&v, &b, 8);
            return v;
        }
        default: return 0;
        }
    }
    static int toInt(double v) {
        if (std::isnan(v)) return 0;
        if (v >= 2147483647.0) return 2147483647;
        if (v <= -2147483648.0) return -2147483647 - 1;
        return int(v);
    }
    static long long toLong(double v) {
        if (std::isnan(v)) return 0;
        if (v >= 9.2233720368547758e18) return std::numeric_limits<long long>::max();
        if (v <= -9.2233720368547758e18) return std::numeric_limits<long long>::min();
        return (long long)v;
    }
    std::vector<double> doubles(int type, long long vo, long long count) const {
        std::vector<double> v(static_cast<size_t>(count));
        for (long long i = 0; i < count; ++i) v[size_t(i)] = num(type, vo, i);
        return v;
    }
    std::vector<int> ints(int type, long long vo, long long count) const {
        std::vector<int> v(static_cast<size_t>(count));
        for (long long i = 0; i < count; ++i) v[size_t(i)] = toInt(num(type, vo, i));
        return v;
    }
    std::vector<long long> longs(int type, long long vo, long long count) const {
        std::vector<long long> v(static_cast<size_t>(count));
        for (long long i = 0; i < count; ++i) v[size_t(i)] = toLong(num(type, vo, i));
        return v;
    }

    long long readIfd(long long off, int depth, bool top) {
        if (off + 2 > (long long)limit) return 0;
        const int n = int(u16(off));
        if (n == 0 || off + 2 + 12LL * n + 4 > (long long)limit) return 0;
        DngIfd f;
        std::vector<long long> subs;
        for (int i = 0; i < n; ++i) {
            const long long e = off + 2 + 12LL * i;
            const int tag = int(u16(e)), type = int(u16(e + 2));
            const long long count = u32(e + 4);
            const int size = typeSize(type);
            if (size == 0 || count > (1 << 26)) continue;
            const long long bytes = size * count;
            const long long vo = bytes <= 4 ? e + 8 : (long long)i32(e + 8);
            if (vo < 0 || vo + bytes > (long long)limit) continue;
            switch (tag) {
            case 0xFE: f.subType = toLong(num(type, vo, 0)); break;
            case 0x100: f.width = toInt(num(type, vo, 0)); break;
            case 0x101: f.height = toInt(num(type, vo, 0)); break;
            case 0x102: f.bps = ints(type, vo, count); break;
            case 0x103: f.compression = toInt(num(type, vo, 0)); break;
            case 0x106: f.photometric = toInt(num(type, vo, 0)); break;
            case 0x111: f.stripOffsets = longs(type, vo, count); f.hasStrips = true; break;
            case 0x112: if (top && depth == 0) orientation = toInt(num(type, vo, 0)); break;
            case 0x115: f.spp = toInt(num(type, vo, 0)); break;
            case 0x116: f.rowsPerStrip = toInt(num(type, vo, 0)); break;
            case 0x117: f.stripCounts = longs(type, vo, count); f.hasStripCounts = true; break;
            case 0x11C: f.planar = toInt(num(type, vo, 0)); break;
            case 0x142: f.tileW = toInt(num(type, vo, 0)); break;
            case 0x143: f.tileH = toInt(num(type, vo, 0)); break;
            case 0x144: f.tileOffsets = longs(type, vo, count); f.hasTiles = true; break;
            case 0x145: f.tileCounts = longs(type, vo, count); f.hasTileCounts = true; break;
            case 0x14A: for (long long s : longs(type, vo, count)) subs.push_back(s); break;
            case 0x828D: { const auto dd = ints(type, vo, count); if (dd.size() >= 2) { f.cfaRows = dd[0]; f.cfaCols = dd[1]; } break; }
            case 0x828E: { f.cfa.resize(size_t(count)); for (long long k = 0; k < count; ++k) f.cfa[size_t(k)] = int8_t(u8(vo + k)); f.hasCfa = true; break; }
            case 0xC612: dng = true; break;
            case 0xC618: f.linearization = ints(type, vo, count); f.hasLin = true; break;
            case 0xC619: { const auto dd = ints(type, vo, count); if (dd.size() >= 2) { f.blackRows = dd[0]; f.blackCols = dd[1]; } break; }
            case 0xC61A: f.black = doubles(type, vo, count); f.hasBlack = true; break;
            case 0xC61D: f.white = num(type, vo, 0); break;
            case 0xC621: colorMatrix1 = doubles(type, vo, count); break;
            case 0xC628: asShotNeutral = doubles(type, vo, count); break;
            case 0xC62A: baselineExposure = num(type, vo, 0); break;
            case 0xC714: forwardMatrix1 = doubles(type, vo, count); break;
            case 0xC715: forwardMatrix2 = doubles(type, vo, count); break;
            case 0xC622: colorMatrix2 = doubles(type, vo, count); break;
            case 0xC623: cameraCalibration1 = doubles(type, vo, count); break;
            case 0xC624: cameraCalibration2 = doubles(type, vo, count); break;
            case 0xC627: analogBalance = doubles(type, vo, count); break;
            case 0xC629: asShotWhiteXY = doubles(type, vo, count); break;
            case 0xC65A: illuminant1 = toInt(num(type, vo, 0)); break;
            case 0xC65B: illuminant2 = toInt(num(type, vo, 0)); break;
            case 0xC68D: f.activeArea = ints(type, vo, count); f.hasActive = true; break;
            case 0xC61F: f.cropOrigin = doubles(type, vo, count); f.hasCropOrigin = true; break;
            case 0xC620: f.cropSize = doubles(type, vo, count); f.hasCropSize = true; break;
            case 0xC61B: f.blackDeltaH = doubles(type, vo, count); f.hasDeltaH = true; break;
            case 0xC61C: f.blackDeltaV = doubles(type, vo, count); f.hasDeltaV = true; break;
            case 0xC740: f.opcodeList2.assign(d + vo, d + vo + count); f.hasOpcodes = true; break;
            default: break;
            }
        }
        ifds.push_back(f);
        if (depth < 3) for (long long s : subs) if (s > 0 && s < (long long)limit) readIfd(s, depth + 1, false);
        return (long long)u32(off + 2 + 12LL * n);
    }
};

// ---------------------------------------------------------------- lossless JPEG (Lj92Decoder)

class Lj92Decoder {
public:
    using RowSink = std::function<void(int y, const uint16_t* row, int n)>;

    /// samples of the frame row by row into sink; dims = {X * Nc, Y}
    static void decode(const uint8_t* data, size_t limit, long long offset, long long length, int* dims, const RowSink& sink) {
        Lj92Decoder dec(data, limit, offset, length);
        dec.sink_ = sink;
        dec.run(dims);
    }

private:
    const uint8_t* d_;
    size_t limit_;
    long long pos_, end_;
    std::vector<int> maxCode_[4], valPtr_[4], minCode_[4], vals_[4];
    int precision_ = 0, width_ = 0, height_ = 0, comps_ = 0, predictor_ = 0, pointTransform_ = 0, restartInterval_ = 0;
    int compTable_[4] = {};
    RowSink sink_;
    uint64_t bitBuf_ = 0;
    int bitCnt_ = 0;
    bool markerHit_ = false;

    Lj92Decoder(const uint8_t* data, size_t limit, long long offset, long long length)
        : d_(data), limit_(limit), pos_(offset), end_(offset + length) {}

    uint8_t at(long long p) const {
        if (p < 0 || p >= (long long)limit_) throw std::runtime_error("LJ92: read outside the file");
        return d_[p];
    }
    int u8() {
        if (pos_ >= end_) throw std::runtime_error("LJ92: truncated");
        return at(pos_++);
    }
    int u16() { const int a = u8(); return (a << 8) | u8(); }

    void run(int* dims) {
        if (u8() != 0xFF || u8() != 0xD8) throw std::runtime_error("LJ92: no SOI");
        while (true) {
            const int m = u8();
            if (m != 0xFF) continue;
            int marker = u8();
            while (marker == 0xFF) marker = u8();
            if (marker == 0xD9) throw std::runtime_error("LJ92: no scan");
            const int len = u16();
            const long long segEnd = pos_ + len - 2;
            switch (marker) {
            case 0xC4:
                while (pos_ < segEnd) {
                    const int tcth = u8(), th = tcth & 3;
                    int counts[17] = {};
                    int total = 0;
                    for (int i = 1; i <= 16; ++i) { counts[i] = u8(); total += counts[i]; }
                    std::vector<int> v(static_cast<size_t>(total));
                    for (int i = 0; i < total; ++i) v[size_t(i)] = u8();
                    buildTable(th, counts, v);
                }
                break;
            case 0xC3:
                precision_ = u8();
                height_ = u16();
                width_ = u16();
                comps_ = u8();
                if (comps_ < 1 || comps_ > 4) throw std::runtime_error("LJ92: components");
                for (int i = 0; i < comps_; ++i) { u8(); u8(); u8(); }
                break;
            case 0xDD:
                restartInterval_ = u16();
                break;
            case 0xDA: {
                const int ns = u8();
                for (int i = 0; i < ns; ++i) { u8(); const int t = u8(); if (i < 4) compTable_[i] = (t >> 4) & 3; }
                predictor_ = u8();
                u8();
                pointTransform_ = u8() & 15;
                pos_ = segEnd;
                if (width_ <= 0 || height_ <= 0) throw std::runtime_error("LJ92: no frame");
                dims[0] = width_ * comps_;
                dims[1] = height_;
                scan();
                return;
            }
            default:
                if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC && marker != 0xC3)
                    throw std::runtime_error("LJ92: unsupported frame");
                break;
            }
            pos_ = segEnd;
        }
    }

    void buildTable(int th, const int* counts, const std::vector<int>& v) {
        std::vector<int> mx(18), mn(17), ptr(17);
        int code = 0, k = 0;
        for (int l = 1; l <= 16; ++l) {
            ptr[size_t(l)] = k;
            mn[size_t(l)] = code;
            code += counts[l];
            k += counts[l];
            mx[size_t(l)] = counts[l] > 0 ? code - 1 : -1;
            code <<= 1;
        }
        mx[17] = 2147483647;
        maxCode_[th] = mx;
        minCode_[th] = mn;
        valPtr_[th] = ptr;
        vals_[th] = v;
    }

    void fill() {
        while (bitCnt_ <= 56) {
            int b = 0;
            if (!markerHit_ && pos_ < end_) {
                b = at(pos_);
                if (b == 0xFF) {
                    const int n = pos_ + 1 < end_ ? at(pos_ + 1) : 0;
                    if (n == 0x00) pos_ += 2;
                    else { markerHit_ = true; b = 0; }
                } else pos_++;
            }
            bitBuf_ |= uint64_t(b) << (56 - bitCnt_);
            bitCnt_ += 8;
        }
    }

    int bits(int n) {
        if (n == 0) return 0;
        if (bitCnt_ < n) fill();
        const int v = int(bitBuf_ >> (64 - n));
        bitBuf_ <<= n;
        bitCnt_ -= n;
        return v;
    }

    int huff(int t) {
        if (bitCnt_ < 16) fill();
        const std::vector<int>& mx = maxCode_[t];
        if (mx.empty()) throw std::runtime_error("LJ92: missing Huffman table");
        int code = 0;
        for (int l = 1; l <= 16; ++l) {
            code = (code << 1) | bits(1);
            if (code <= mx[size_t(l)]) {
                const long long i = (long long)valPtr_[t][size_t(l)] + code - minCode_[t][size_t(l)];
                if (i < 0 || i >= (long long)vals_[t].size()) throw std::runtime_error("LJ92: bad Huffman value");
                return vals_[t][size_t(i)];
            }
        }
        throw std::runtime_error("LJ92: bad Huffman code");
    }

    void restart() {
        bitBuf_ = 0;
        bitCnt_ = 0;
        markerHit_ = false;
        while (pos_ + 1 < end_ && !(at(pos_) == 0xFF && at(pos_ + 1) >= 0xD0 && at(pos_ + 1) <= 0xD7)) pos_++;
        if (pos_ + 1 < end_) pos_ += 2;
    }

    void scan() {
        const int rowLen = width_ * comps_;
        std::vector<uint16_t> prevV(static_cast<size_t>(rowLen)), curV(static_cast<size_t>(rowLen)), emitV(static_cast<size_t>(rowLen));
        uint16_t* prev = prevV.data();
        uint16_t* cur = curV.data();
        const int mask = 0xFFFF;
        const int initial = 1 << (precision_ - pointTransform_ - 1);
        long long mcus = 0;
        bool resetRow = true;
        for (int y = 0; y < height_; ++y) {
            for (int x = 0; x < width_; ++x) {
                if (restartInterval_ > 0 && mcus > 0 && mcus % restartInterval_ == 0) {
                    restart();
                    resetRow = true;
                }
                for (int c = 0; c < comps_; ++c) {
                    const int s = huff(compTable_[c]);
                    int diff;
                    if (s == 0) diff = 0;
                    else if (s == 16) diff = 32768;
                    else {
                        diff = bits(s);
                        if (diff < (1 << (s - 1))) diff -= (1 << s) - 1;
                    }
                    const int i = x * comps_ + c;
                    int pred;
                    if (resetRow && x == 0) pred = initial;
                    else if (y == 0 || resetRow) pred = cur[i - comps_];
                    else if (x == 0) pred = prev[c];
                    else {
                        const int ra = cur[i - comps_], rb = prev[i], rc = prev[i - comps_];
                        switch (predictor_) {
                        case 1: pred = ra; break;
                        case 2: pred = rb; break;
                        case 3: pred = rc; break;
                        case 4: pred = ra + rb - rc; break;
                        case 5: pred = ra + ((rb - rc) >> 1); break;
                        case 6: pred = rb + ((ra - rc) >> 1); break;
                        case 7: pred = (ra + rb) >> 1; break;
                        default: pred = ra; break;
                        }
                    }
                    cur[i] = uint16_t((pred + diff) & mask);
                }
                ++mcus;
            }
            resetRow = false;
            const uint16_t* emit = cur;
            if (pointTransform_ > 0) {
                for (int i = 0; i < rowLen; ++i) emitV[size_t(i)] = uint16_t((cur[i] << pointTransform_) & mask);
                emit = emitV.data();
            }
            sink_(y, emit, rowLen);
            std::swap(prev, cur);
        }
    }
};

// ---------------------------------------------------------------- the DNG colour model (DngColor)

using M3 = std::vector<double>; // row-major 3x3, empty = absent

namespace DngMat {
inline M3 mul(const M3& a, const M3& b) {
    M3 r(9);
    for (int i = 0; i < 3; ++i) for (int j = 0; j < 3; ++j) r[size_t(i * 3 + j)] = a[size_t(i * 3)] * b[size_t(j)] + a[size_t(i * 3 + 1)] * b[size_t(3 + j)] + a[size_t(i * 3 + 2)] * b[size_t(6 + j)];
    return r;
}
inline double det(const M3& m) {
    return m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6]);
}
inline M3 inverse(const M3& m) {
    const double d = det(m);
    return {(m[4] * m[8] - m[5] * m[7]) / d, (m[2] * m[7] - m[1] * m[8]) / d, (m[1] * m[5] - m[2] * m[4]) / d,
            (m[5] * m[6] - m[3] * m[8]) / d, (m[0] * m[8] - m[2] * m[6]) / d, (m[2] * m[3] - m[0] * m[5]) / d,
            (m[3] * m[7] - m[4] * m[6]) / d, (m[1] * m[6] - m[0] * m[7]) / d, (m[0] * m[4] - m[1] * m[3]) / d};
}
inline std::vector<double> apply(const M3& m, const std::vector<double>& v) {
    return {m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2], m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
}
inline M3 identity() { return {1, 0, 0, 0, 1, 0, 0, 0, 1}; }
} // namespace DngMat

class DngColor {
public:
    M3 cm1, cm2, fm1, fm2, cc1, cc2, ab;
    double t1 = 2850, t2 = 6500;
    std::vector<double> asShotNeutral;

    DngColor() : asShotNeutral{1, 1, 1} {}

    DngColor(const std::vector<double>& cm1_, const std::vector<double>& cm2_, const std::vector<double>& fm1_, const std::vector<double>& fm2_,
             const std::vector<double>& cc1_, const std::vector<double>& cc2_, const std::vector<double>& ab_, int illuminant1, int illuminant2,
             const std::vector<double>& neutral, const std::vector<double>& whiteXY) {
        cm1 = m3(cm1_); cm2 = m3(cm2_); fm1 = m3(fm1_); fm2 = m3(fm2_); cc1 = m3(cc1_); cc2 = m3(cc2_);
        if (ab_.size() >= 3) ab = {ab_[0], 0, 0, 0, ab_[1], 0, 0, 0, ab_[2]};
        t1 = illuminantTemperature(illuminant1, 2850);
        t2 = illuminantTemperature(illuminant2, 6500);
        std::vector<double> n;
        if (neutral.size() >= 3 && neutral[1] > 0) n = normalise(neutral);
        else if (whiteXY.size() >= 2 && hasMatrices()) n = neutralForXy(whiteXY);
        asShotNeutral = !n.empty() ? n : std::vector<double>{1, 1, 1};
    }

    static DngColor of(const DngFile& p) {
        return DngColor(p.colorMatrix1, p.colorMatrix2, p.forwardMatrix1, p.forwardMatrix2, p.cameraCalibration1, p.cameraCalibration2,
                        p.analogBalance, p.illuminant1, p.illuminant2, p.asShotNeutral, p.asShotWhiteXY);
    }

    bool hasMatrices() const { return !cm1.empty(); }

    static double illuminantTemperature(int code, double fallback) {
        switch (code) {
        case 1: case 4: case 9: case 18: case 20: return 5500;
        case 2: case 14: return 4150;
        case 3: case 17: return 2850;
        case 10: case 19: case 21: return 6500;
        case 11: case 22: return 7500;
        case 12: return 6400;
        case 13: return 5050;
        case 15: return 3525;
        case 16: return 2925;
        case 23: return 5000;
        case 24: return 3200;
        default: return fallback;
        }
    }

    double weight1(double cct) const {
        if (cm2.empty() || t1 == t2) return 1;
        const double lo = std::min(t1, t2), hi = std::max(t1, t2);
        const double t = std::max(lo, std::min(hi, cct));
        const double g = (1 / t - 1 / hi) / (1 / lo - 1 / hi);
        return t1 <= t2 ? g : 1 - g;
    }

    static M3 mix(const M3& a, const M3& b, double wa) {
        if (b.empty()) return a;
        if (a.empty()) return b;
        M3 r(9);
        for (size_t i = 0; i < 9; i++) r[i] = a[i] * wa + b[i] * (1 - wa);
        return r;
    }

    M3 abcc(double cct) const {
        const double w = weight1(cct);
        const M3 cc = mix(cc1, cc2, w);
        const M3 m = !cc.empty() ? cc : DngMat::identity();
        return !ab.empty() ? DngMat::mul(ab, m) : m;
    }

    M3 xyzToCamera(double cct) const { return DngMat::mul(abcc(cct), mix(cm1, cm2, weight1(cct))); }

    std::vector<double> neutralForXy(const std::vector<double>& xy) const {
        const auto xyz = xyToXyz(xy);
        return normalise(DngMat::apply(xyzToCamera(cctDuv(xy)[0]), xyz));
    }

    std::vector<double> xyForNeutral(const std::vector<double>& neutral) const {
        std::vector<double> xy = estimateXy(neutral);
        return !xy.empty() ? xy : std::vector<double>{0.3457, 0.3585};
    }

    std::vector<double> estimateXy(const std::vector<double>& neutral) const {
        std::vector<double> xy = {0.3457, 0.3585};
        for (int i = 0; i < 40; i++) {
            const M3 m = xyzToCamera(cctDuv(xy)[0]);
            if (std::fabs(DngMat::det(m)) < 1e-12) return {};
            const auto xyz = DngMat::apply(DngMat::inverse(m), neutral);
            const double s = xyz[0] + xyz[1] + xyz[2];
            if (s <= 1e-12) return {};
            const std::vector<double> next = {xyz[0] / s, xyz[1] / s};
            if (std::fabs(next[0] - xy[0]) + std::fabs(next[1] - xy[1]) < 1e-9) return next;
            xy = next;
        }
        return xy;
    }

    M3 cameraToSrgb(const std::vector<double>& n) const {
        if (!hasMatrices()) return {1 / n[0], 0, 0, 0, 1 / n[1], 0, 0, 0, 1 / n[2]};
        static const M3 XYZ50_TO_SRGB = {3.1338561, -1.6168667, -0.4906146, -0.9787684, 1.9161415, 0.0334540, 0.0719453, -0.2289914, 1.4052427};
        M3 m = DngMat::mul(XYZ50_TO_SRGB, cameraToXyzD50(n));
        const auto out = DngMat::apply(m, n);
        for (int r = 0; r < 3; r++) {
            const double s = out[size_t(r)] > 1e-9 ? 1 / out[size_t(r)] : 1;
            for (int c = 0; c < 3; c++) m[size_t(r * 3 + c)] *= s;
        }
        return m;
    }

    M3 cameraToXyzD50(const std::vector<double>& n) const {
        const auto xy = xyForNeutral(n);
        const double cct = cctDuv(xy)[0];
        const double w = weight1(cct);
        const M3 fm = mix(fm1, fm2, w);
        const M3 abc = abcc(cct);
        if (!fm.empty()) {
            const M3 inv = DngMat::inverse(abc);
            const auto ref = DngMat::apply(inv, n);
            const M3 dd = {1 / std::max(ref[0], 1e-9), 0, 0, 0, 1 / std::max(ref[1], 1e-9), 0, 0, 0, 1 / std::max(ref[2], 1e-9)};
            return DngMat::mul(fm, DngMat::mul(dd, inv));
        }
        const M3 camToXyz = DngMat::inverse(xyzToCamera(cct));
        return DngMat::mul(adaptation(xyToXyz(xy), xyToXyz({0.3457, 0.3585})), camToXyz);
    }

    static std::vector<double> planckUv(double t) {
        const double u = (0.860117757 + 1.54118254e-4 * t + 1.28641212e-7 * t * t) / (1 + 8.42420235e-4 * t + 7.08145163e-7 * t * t);
        const double v = (0.317398726 + 4.22806245e-5 * t + 4.20481691e-8 * t * t) / (1 - 2.89741816e-5 * t + 1.61456053e-7 * t * t);
        return {u, v};
    }
    static std::vector<double> xyToUv(const std::vector<double>& xy) {
        const double dd = -2 * xy[0] + 12 * xy[1] + 3;
        return {4 * xy[0] / dd, 6 * xy[1] / dd};
    }
    static std::vector<double> uvToXy(const std::vector<double>& uv) {
        const double dd = 2 * uv[0] - 8 * uv[1] + 4;
        return {3 * uv[0] / dd, 2 * uv[1] / dd};
    }
    static double dist2(const std::vector<double>& uv, double t) {
        const auto p = planckUv(t);
        return (uv[0] - p[0]) * (uv[0] - p[0]) + (uv[1] - p[1]) * (uv[1] - p[1]);
    }
    /// {CCT K, Duv}
    static std::vector<double> cctDuv(const std::vector<double>& xy) {
        const auto uv = xyToUv(xy);
        double lo = 1e6 / 25000, hi = 1e6 / 1430;
        const double g = (std::sqrt(5.0) - 1) / 2;
        double a = hi - g * (hi - lo), b = lo + g * (hi - lo);
        double fa = dist2(uv, 1e6 / a), fb = dist2(uv, 1e6 / b);
        for (int i = 0; i < 80; i++) {
            if (fa < fb) {
                hi = b; b = a; fb = fa; a = hi - g * (hi - lo); fa = dist2(uv, 1e6 / a);
            } else {
                lo = a; a = b; fa = fb; b = lo + g * (hi - lo); fb = dist2(uv, 1e6 / b);
            }
        }
        const double t = 1e6 / ((lo + hi) / 2);
        const auto p = planckUv(t);
        const double dd = std::sqrt(dist2(uv, t));
        return {t, uv[1] >= p[1] ? dd : -dd};
    }
    static std::vector<double> xyFromCctDuv(double cct, double duv) {
        const auto p = planckUv(cct), q = planckUv(cct * 1.001);
        const double tu = q[0] - p[0], tv = q[1] - p[1], len = std::hypot(tu, tv);
        double nu = -tv / len, nv = tu / len;
        if (nv < 0) { nu = -nu; nv = -nv; }
        return uvToXy({p[0] + duv * nu, p[1] + duv * nv});
    }

    static std::vector<double> xyToXyz(const std::vector<double>& xy) { return {xy[0] / xy[1], 1, (1 - xy[0] - xy[1]) / xy[1]}; }
    static M3 adaptation(const std::vector<double>& src, const std::vector<double>& dst) {
        static const M3 BRADFORD = {0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296};
        const auto s = DngMat::apply(BRADFORD, src), dd = DngMat::apply(BRADFORD, dst);
        const M3 scale = {dd[0] / s[0], 0, 0, 0, dd[1] / s[1], 0, 0, 0, dd[2] / s[2]};
        return DngMat::mul(DngMat::inverse(BRADFORD), DngMat::mul(scale, BRADFORD));
    }
    static std::vector<double> normalise(const std::vector<double>& n) {
        const double g = n[1] > 1e-12 ? n[1] : std::max(n[0], std::max(n[1], n[2]));
        if (g <= 1e-12) return {1, 1, 1};
        return {n[0] / g, n[1] / g, n[2] / g};
    }

private:
    static M3 m3(const std::vector<double>& m) { return m.size() >= 9 ? M3(m.begin(), m.begin() + 9) : M3(); }
};

// ---------------------------------------------------------------- DngRaw

class DngRaw {
public:
    static constexpr float FIXED_ONE = 16384.f;
    static constexpr int KIND_BAYER = 0, KIND_RGB = 1;
    static constexpr int RGGB = 0, GRBG = 1, GBRG = 2, BGGR = 3;

    struct Info {
        int kind = KIND_BAYER;
        int cropWidth = 0, cropHeight = 0;
        int cell = 1;
        int orientation = 1;
        double baselineExposure = 0;
        DngColor colour;
        bool gainMap = false;
        int bits = 16;
        int compression = 1;
        std::array<int, 2> size(int bin) const {
            if (kind == KIND_RGB) return {std::max(1, cropWidth / bin), std::max(1, cropHeight / bin)};
            const int step = 2 * cell * bin;
            return {std::max(2, cropWidth / step * 2), std::max(2, cropHeight / step * 2)};
        }
    };

    struct Image {
        Info info;
        int width = 0, height = 0, bin = 1;
        int phase = 0;
        std::vector<uint16_t> data;
        float autoLiftStops = 0;
        float scale = 1.f / FIXED_ONE;
    };

    static Info probe(const uint8_t* data, size_t size) {
        DngFile p(data, size);
        DngRaw r(p, pick(p));
        r.setup();
        return r.info_;
    }

    static Image decode(const uint8_t* data, size_t size, int bin) {
        DngFile p(data, size);
        DngRaw r(p, pick(p));
        r.setup();
        return r.run(std::max(1, bin));
    }

    static float autoLift(const Image& img) {
        const int w = img.width, h = img.height;
        const int stride = std::max(1, int(std::sqrt(double(w) * h / 40000.0)));
        std::vector<float> g;
        for (int y = 0; y < h; y += stride)
            for (int x = 0; x < w; x += stride) {
                float v;
                if (img.info.kind == KIND_RGB) v = float(img.data[size_t((y * w + x) * 3 + 1)]) * img.scale;
                else {
                    const int gx = x & ~1, gy = y & ~1;
                    const int px = (img.phase == RGGB || img.phase == BGGR) ? 1 : 0;
                    v = float(img.data[size_t(gy * w + gx + px)]) * img.scale;
                }
                g.push_back(v);
            }
        if (g.empty()) return 0.f;
        std::sort(g.begin(), g.end());
        const double median = g[g.size() / 2], high = g[std::min(g.size() - 1, size_t(int(double(g.size()) * 0.99)))];
        if (median <= 1e-6) return 0.f;
        double lift = std::max(1.0, std::min(8.0, 0.18 / median));
        if (high * lift > 4) lift = std::max(1.0, 4 / std::max(high, 1e-6));
        return float(std::log(lift) / std::log(2.0));
    }

private:
    struct GainMap {
        int top = 0, left = 0, bottom = 0, right = 0, plane = 0, planes = 0, rowPitch = 1, colPitch = 1, pointsV = 0, pointsH = 0, mapPlanes = 1;
        double spacingV = 0, spacingH = 0, originV = 0, originH = 0;
        std::vector<float> gains;
        float at(double rv, double rh, int pl) const {
            double mv = (rv - originV) / std::max(spacingV, 1e-12), mh = (rh - originH) / std::max(spacingH, 1e-12);
            mv = std::max(0.0, std::min(double(pointsV - 1), mv));
            mh = std::max(0.0, std::min(double(pointsH - 1), mh));
            const int v0 = int(mv), h0 = int(mh), v1 = std::min(pointsV - 1, v0 + 1), h1 = std::min(pointsH - 1, h0 + 1);
            const double tv = mv - v0, th = mh - h0;
            const int p = std::min(pl, mapPlanes - 1);
            const double a = gains[size_t((v0 * pointsH + h0) * mapPlanes + p)], b = gains[size_t((v0 * pointsH + h1) * mapPlanes + p)];
            const double c = gains[size_t((v1 * pointsH + h0) * mapPlanes + p)], dd = gains[size_t((v1 * pointsH + h1) * mapPlanes + p)];
            return float((a + (b - a) * th) * (1 - tv) + (c + (dd - c) * th) * tv);
        }
    };

    const DngFile& p_;
    const DngIfd& f_;
    Info info_;
    int x0_ = 0, y0_ = 0, aaLeft_ = 0, aaTop_ = 0, aaW_ = 0, aaH_ = 0;
    int rr_ = 2, rc_ = 2;
    std::vector<int8_t> cfa_;
    const std::vector<int>* lin_ = nullptr;
    std::vector<double> black_;
    int bRows_ = 1, bCols_ = 1;
    const std::vector<double>* deltaH_ = nullptr;
    const std::vector<double>* deltaV_ = nullptr;
    double white_ = 0;
    std::vector<GainMap> gainMaps_;
    int outW_ = 0, outH_ = 0, bin_ = 1, spp_ = 1, step_ = 2;
    std::vector<uint16_t> out_;
    float inv_ = 0;

    DngRaw(const DngFile& p, const DngIfd& f) : p_(p), f_(f) {}

    static const DngIfd& pick(const DngFile& p) {
        const DngIfd* raw = nullptr;
        for (const DngIfd& f : p.ifds) {
            if ((f.photometric == 32803 || f.photometric == 34892) && (f.subType & 1) == 0
                && (raw == nullptr || (long long)f.width * f.height > (long long)raw->width * raw->height)) raw = &f;
        }
        if (!raw) throw std::runtime_error("DNG: no raw image");
        return *raw;
    }

    static int clampI(int v, int lo, int hi) { return std::max(lo, std::min(hi, v)); }
    static int floorMod(int a, int b) { const int m = a % b; return m < 0 ? m + b : m; }

    void setup() {
        const DngIfd& f = f_;
        info_.orientation = p_.orientation;
        info_.baselineExposure = p_.baselineExposure;
        info_.colour = DngColor::of(p_);
        info_.compression = f.compression;
        info_.bits = !f.bps.empty() ? f.bps[0] : 16;
        if (f.compression != 1 && f.compression != 7) throw std::runtime_error("DNG: raw compression " + std::to_string(f.compression));
        if (info_.bits < 8 || info_.bits > 16) throw std::runtime_error("DNG: raw of " + std::to_string(info_.bits) + " bits");
        if (f.planar != 1) throw std::runtime_error("DNG: planar raw");
        if (f.photometric == 34892) {
            if (f.spp != 3) throw std::runtime_error("DNG: LinearRaw samples");
            info_.kind = KIND_RGB;
            spp_ = 3;
        } else {
            if (f.spp != 1) throw std::runtime_error("DNG: CFA samples");
            info_.kind = KIND_BAYER;
            rr_ = std::max(1, f.cfaRows);
            rc_ = std::max(1, f.cfaCols);
            if (f.hasCfa && (long long)f.cfa.size() >= (long long)rr_ * rc_) cfa_ = f.cfa;
            else {
                rr_ = rc_ = 2;
                cfa_ = {0, 1, 1, 2};
            }
            if (rr_ == 2 && rc_ == 2) info_.cell = 1;
            else if (rr_ == 4 && rc_ == 4 && quadCells()) info_.cell = 2;
            else throw std::runtime_error("DNG: CFA is not Bayer or Quad");
        }
        const std::vector<int> aa = f.hasActive && f.activeArea.size() >= 4 ? f.activeArea : std::vector<int>{0, 0, f.height, f.width};
        aaTop_ = clampI(aa[0], 0, f.height - 1);
        aaLeft_ = clampI(aa[1], 0, f.width - 1);
        aaH_ = clampI(aa[2], aaTop_ + 1, f.height) - aaTop_;
        aaW_ = clampI(aa[3], aaLeft_ + 1, f.width) - aaLeft_;
        int cx = 0, cy = 0, cw = aaW_, ch = aaH_;
        if (f.hasCropOrigin && f.cropOrigin.size() >= 2 && f.hasCropSize && f.cropSize.size() >= 2) {
            cx = clampI(int(jroundL(f.cropOrigin[0])), 0, aaW_ - 1);
            cy = clampI(int(jroundL(f.cropOrigin[1])), 0, aaH_ - 1);
            cw = clampI(int(jroundL(f.cropSize[0])), 1, aaW_ - cx);
            ch = clampI(int(jroundL(f.cropSize[1])), 1, aaH_ - cy);
        }
        if (info_.cell == 2) {
            const int ax = cx & 1, ay = cy & 1;
            cx += ax;
            cy += ay;
            cw = std::max(2, cw - ax);
            ch = std::max(2, ch - ay);
        }
        x0_ = aaLeft_ + cx;
        y0_ = aaTop_ + cy;
        info_.cropWidth = cw;
        info_.cropHeight = ch;
        lin_ = f.hasLin ? &f.linearization : nullptr;
        black_ = f.hasBlack && !f.black.empty() ? f.black : std::vector<double>{0};
        bRows_ = std::max(1, f.blackRows);
        bCols_ = std::max(1, f.blackCols);
        if ((long long)black_.size() < (long long)bRows_ * bCols_ * spp_) {
            bRows_ = bCols_ = 1;
            if ((int)black_.size() < spp_) black_ = {black_[0], black_[0], black_[0]};
        }
        deltaH_ = f.hasDeltaH ? &f.blackDeltaH : nullptr;
        deltaV_ = f.hasDeltaV ? &f.blackDeltaV : nullptr;
        white_ = f.white > 0 ? f.white : (lin_ ? 65535 : (1 << info_.bits) - 1);
        if (f.hasOpcodes) parseGainMaps(f.opcodeList2);
        info_.gainMap = !gainMaps_.empty();
    }

    static long long jroundL(double x) {
        if (std::isnan(x)) return 0;
        const double fl = std::floor(x);
        return (long long)(x - fl >= 0.5 ? fl + 1 : fl);
    }

    bool quadCells() const {
        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 4; x++)
                if (cfa_[size_t(y * 4 + x)] != cfa_[size_t((y & ~1) * 4 + (x & ~1))]) return false;
        int r = 0, g = 0, b = 0;
        for (int y = 0; y < 4; y += 2)
            for (int x = 0; x < 4; x += 2) {
                const int c = cfa_[size_t(y * 4 + x)];
                if (c == 0) r++;
                else if (c == 1) g++;
                else if (c == 2) b++;
            }
        return r == 1 && g == 2 && b == 1;
    }

    int cfaAt(int x, int y) const { return cfa_[size_t(floorMod(y - aaTop_, rr_) * rc_ + floorMod(x - aaLeft_, rc_))]; }

    int phase() const {
        const int q = info_.cell;
        const int c00 = cfaAt(x0_, y0_), c10 = cfaAt(x0_ + q, y0_), c01 = cfaAt(x0_, y0_ + q), c11 = cfaAt(x0_ + q, y0_ + q);
        if (c00 == 0 && c10 == 1 && c01 == 1 && c11 == 2) return RGGB;
        if (c00 == 1 && c10 == 0 && c01 == 2 && c11 == 1) return GRBG;
        if (c00 == 1 && c10 == 2 && c01 == 0 && c11 == 1) return GBRG;
        if (c00 == 2 && c10 == 1 && c01 == 1 && c11 == 0) return BGGR;
        return -1;
    }

    void parseGainMaps(const std::vector<uint8_t>& list) {
        size_t o = 0;
        auto need = [&](size_t n) { if (o + n > list.size()) throw std::runtime_error("opcodes"); };
        auto gi = [&]() { need(4); const uint32_t v = uint32_t(list[o]) << 24 | uint32_t(list[o + 1]) << 16 | uint32_t(list[o + 2]) << 8 | list[o + 3]; o += 4; return int32_t(v); };
        auto gf = [&]() { const int32_t v = gi(); float f; std::memcpy(&f, &v, 4); return f; };
        auto gd = [&]() { need(8); uint64_t v = 0; for (int k = 0; k < 8; k++) v = v << 8 | list[o + size_t(k)]; o += 8; double x; std::memcpy(&x, &v, 8); return x; };
        std::vector<GainMap> maps;
        try {
            const int n = gi();
            for (int i = 0; i < n && list.size() - o >= 16; i++) {
                const int id = gi();
                gi();
                gi();
                const int bytes = gi();
                const size_t start = o;
                if (bytes < 0 || start + size_t(bytes) > list.size()) break;
                if (id == 9 && bytes >= 76) {
                    GainMap g;
                    g.top = gi(); g.left = gi(); g.bottom = gi(); g.right = gi(); g.plane = gi(); g.planes = gi();
                    g.rowPitch = std::max(1, gi());
                    g.colPitch = std::max(1, gi());
                    g.pointsV = gi(); g.pointsH = gi();
                    g.spacingV = gd(); g.spacingH = gd(); g.originV = gd(); g.originH = gd();
                    g.mapPlanes = std::max(1, gi());
                    const long long count = (long long)g.pointsV * g.pointsH * g.mapPlanes;
                    if (g.pointsV > 0 && g.pointsH > 0 && count > 0 && count * 4 <= bytes - 76) {
                        g.gains.resize(size_t(count));
                        for (long long k = 0; k < count; k++) g.gains[size_t(k)] = gf();
                        maps.push_back(g);
                    }
                }
                o = start + size_t(bytes);
            }
        } catch (const std::exception&) {
        }
        gainMaps_.insert(gainMaps_.end(), maps.begin(), maps.end());
    }

    Image run(int bin) {
        bin_ = bin;
        const auto size = info_.size(bin);
        outW_ = size[0];
        outH_ = size[1];
        step_ = info_.kind == KIND_RGB ? bin : 2 * info_.cell * bin;
        const int channels = info_.kind == KIND_RGB ? 3 : 1;
        const long long n = (long long)outW_ * outH_ * channels;
        if (n > 2147483647LL / 2) throw std::runtime_error("DNG: raw too large to decode");
        out_.assign(size_t(n), 0);
        const int perOut = info_.kind == KIND_RGB ? bin * bin : info_.cell * info_.cell * bin * bin;
        inv_ = FIXED_ONE / float(perOut);
        if (f_.compression == 1) uncompressed();
        else lossless();
        Image img;
        img.info = info_;
        img.width = outW_;
        img.height = outH_;
        img.bin = bin;
        img.phase = info_.kind == KIND_BAYER ? phase() : 0;
        if (info_.kind == KIND_BAYER && img.phase < 0) throw std::runtime_error("DNG: CFA phase is not a Bayer");
        img.data = std::move(out_);
        img.autoLiftStops = autoLift(img);
        return img;
    }

    void add(int x, int y, int ch, int v) {
        const int dx = x - x0_, dy = y - y0_;
        if (dx < 0 || dy < 0) return;
        int ox, oy;
        if (info_.kind == KIND_RGB) {
            ox = dx / bin_;
            oy = dy / bin_;
        } else {
            const int q = info_.cell;
            ox = 2 * (dx / step_) + (dx % (2 * q)) / q;
            oy = 2 * (dy / step_) + (dy % (2 * q)) / q;
        }
        if (ox >= outW_ || oy >= outH_) return;
        const int value = lin_ ? (*lin_)[size_t(std::min(v, int(lin_->size()) - 1))] : v;
        const int ax = x - aaLeft_, ay = y - aaTop_;
        double bl = black_[size_t((floorMod(ay, bRows_) * bCols_ + floorMod(ax, bCols_)) * spp_ + (spp_ == 3 ? ch : 0))];
        if (deltaH_ && ax >= 0 && ax < int(deltaH_->size())) bl += (*deltaH_)[size_t(ax)];
        if (deltaV_ && ay >= 0 && ay < int(deltaV_->size())) bl += (*deltaV_)[size_t(ay)];
        double s = (value - bl) / std::max(1.0, white_ - bl);
        if (!gainMaps_.empty()) {
            const int plane = spp_ == 3 ? ch : 0;
            for (const GainMap& g : gainMaps_) {
                if (ay < g.top || ay >= g.bottom || ax < g.left || ax >= g.right) continue;
                if ((ay - g.top) % g.rowPitch != 0 || (ax - g.left) % g.colPitch != 0) continue;
                if (plane < g.plane || plane >= g.plane + g.planes) continue;
                s *= g.at((ay + 0.5) / aaH_, (ax + 0.5) / aaW_, plane - g.plane);
            }
        }
        if (s <= 0) return;
        const size_t i = size_t((oy * outW_ + ox) * (info_.kind == KIND_RGB ? 3 : 1) + (info_.kind == KIND_RGB ? ch : 0));
        const int acc = int(out_[i]) + int(jroundL(s * double(inv_)));
        out_[i] = uint16_t(std::min(65535, acc));
    }

    void uncompressed() {
        const DngIfd& f = f_;
        const int bits = info_.bits;
        const int W = f.width, H = f.height;
        if (f.hasTiles && f.tileW > 0 && f.tileH > 0) {
            const int across = (W + f.tileW - 1) / f.tileW;
            const int tRowBytes = (f.tileW * spp_ * bits + 7) / 8;
            for (size_t t = 0; t < f.tileOffsets.size(); ++t) {
                const int tx = int(t % size_t(across)) * f.tileW, ty = int(t / size_t(across)) * f.tileH;
                if (ty + f.tileH <= y0_ || tx + f.tileW <= x0_) continue;
                for (int y = 0; y < f.tileH && ty + y < H; ++y)
                    readRow(f.tileOffsets[t] + (long long)y * tRowBytes, bits, std::min(f.tileW, W - tx), tx, ty + y);
            }
            return;
        }
        if (!f.hasStrips) throw std::runtime_error("DNG: raw without strips");
        const int rowBytes = (W * spp_ * bits + 7) / 8;
        const int rps = std::max(1, std::min(f.rowsPerStrip, H));
        const int yEnd = std::min(H, y0_ + info_.cropHeight);
        for (int y = std::max(0, y0_); y < yEnd; ++y) {
            const int s = y / rps;
            if (s >= int(f.stripOffsets.size())) break;
            readRow(f.stripOffsets[size_t(s)] + (long long)(y - s * rps) * rowBytes, bits, W, 0, y);
        }
    }

    void readRow(long long off, int bits, int n, int xStart, int y) {
        const long long lim = (long long)p_.limit;
        const int o = int(off);
        const int samples = n * spp_;
        const uint8_t* b = p_.d;
        if (bits == 16) {
            if (o + 2LL * samples > lim) return;
            for (int i = 0; i < samples; ++i) {
                const long long at = o + 2LL * i;
                const int v = p_.le ? b[at] | b[at + 1] << 8 : b[at] << 8 | b[at + 1];
                add(xStart + i / spp_, y, i % spp_, v);
            }
        } else if (bits == 8) {
            if (o + (long long)samples > lim) return;
            for (int i = 0; i < samples; ++i) add(xStart + i / spp_, y, i % spp_, b[o + i]);
        } else {
            long long bitPos = 0;
            for (int i = 0; i < samples; ++i) {
                int v = 0;
                for (int k = 0; k < bits; ++k, ++bitPos) {
                    const long long at = o + (bitPos >> 3);
                    if (at >= lim) return;
                    v = (v << 1) | ((b[at] >> (7 - int(bitPos & 7))) & 1);
                }
                add(xStart + i / spp_, y, i % spp_, v);
            }
        }
    }

    void lossless() {
        const DngIfd& f = f_;
        const int W = f.width, H = f.height;
        const bool tiled = f.hasTiles && f.tileW > 0 && f.tileH > 0;
        const bool have = tiled ? (f.hasTiles && f.hasTileCounts) : (f.hasStrips && f.hasStripCounts);
        if (!have) throw std::runtime_error("DNG: LJ92 raw without data");
        const std::vector<long long>& offs = tiled ? f.tileOffsets : f.stripOffsets;
        const std::vector<long long>& counts = tiled ? f.tileCounts : f.stripCounts;
        const int tw = tiled ? f.tileW : W, th = tiled ? f.tileH : std::min(f.rowsPerStrip, H);
        const int across = tiled ? (W + tw - 1) / tw : 1;
        for (size_t t = 0; t < offs.size(); ++t) {
            const int tx = int(t % size_t(across)) * tw, ty = int(t / size_t(across)) * th;
            if (ty >= y0_ + info_.cropHeight || tx >= x0_ + info_.cropWidth || ty + th <= y0_ || tx + tw <= x0_) continue;
            if (t >= counts.size()) throw std::runtime_error("DNG: LJ92 counts");
            long long cursor = 0;
            int dims[2] = {0, 0};
            Lj92Decoder::decode(p_.d, p_.limit, int(offs[t]), int(counts[t]), dims, [&](int, const uint16_t* data, int len) {
                long long c = cursor;
                for (int i = 0; i < len; ++i, ++c) {
                    const long long px = c / spp_;
                    const int x = tx + int(px % tw), y = ty + int(px / tw);
                    if (x < W && y < H) add(x, y, int(c % spp_), data[i]);
                }
                cursor = c;
            });
        }
    }
};

} // namespace scam_edit
