#include "rgb8_hdr.h"
#include "parallel.h"

#include <algorithm>
#include <cstring>
#include <limits>
#include <memory>
#include <stdexcept>
#include <utility>

namespace tone {
namespace {

constexpr int padding = 2;
using GaussianPixel = std::array<uint8_t, 7>;
using ReconstructedPixel = std::array<int16_t, 3>;

int floor_shift(int value, unsigned bits) {
    const int divisor = 1 << bits;
    return value / divisor - (value % divisor < 0);
}

int border(int index, int length, bool mirror) {
    const int end = length - 1;
    if (mirror) index = std::max(std::min(index, 2 * end - index), -index);
    return std::clamp(index, 0, end);
}

int avg(int a, int b) {
    return (a + b + 1) / 2;
}

int mix(int a, int b, int weight) {
    return floor_shift(a * weight + b * (128 - weight) + 64, 7);
}

// SCAMERA P636: a level's storage; the row path writes every pixel before it reads one and takes it not zeroed.
template <typename Pixel>
struct LevelData {
    std::unique_ptr<Pixel[]> pixels;
    size_t count;

    LevelData(size_t n, bool zeroed) : pixels(zeroed ? new Pixel[n]() : new Pixel[n]), count(n) {}
    size_t size() const { return count; }
    Pixel& operator[](size_t i) { return pixels[i]; }
    const Pixel& operator[](size_t i) const { return pixels[i]; }
};

template <typename Pixel>
struct Level {
    int width;
    int height;
    LevelData<Pixel> data;

    Level(int w, int h, bool zeroed = true)
        : width(w), height(h), data(size_t(w + 2 * padding) * (h + 2 * padding), zeroed) {}

    Pixel& pixel(int x, int y) {
        return data[size_t(y + padding) * (width + 2 * padding) + size_t(x + padding)];
    }

    const Pixel& pixel(int x, int y) const {
        return data[size_t(y + padding) * (width + 2 * padding) + size_t(x + padding)];
    }
};

template <typename ReadPixel>
Level<GaussianPixel> downsample(int width, int height, bool mirror, ReadPixel read) {
    Level<GaussianPixel> next((width + 1) / 2, (height + 1) / 2);
    detail::parallel_rows(-padding, next.height + padding, 112 * std::ptrdiff_t(next.width + 2 * padding),
                          [&](std::ptrdiff_t first, std::ptrdiff_t last) {
    for (int y = static_cast<int>(first); y < static_cast<int>(last); ++y) {
        std::array<int, 4> iy{};
        for (int j = 0; j < 4; ++j) iy[j] = border(2 * y + j - 1, height, mirror);
        for (int x = -padding; x < next.width + padding; ++x) {
            std::array<int, 4> ix{};
            for (int i = 0; i < 4; ++i) ix[i] = border(2 * x + i - 1, width, mirror);
            GaussianPixel tile[4][4];
            for (int j = 0; j < 4; ++j) {
                for (int i = 0; i < 4; ++i) tile[j][i] = read(ix[i], iy[j]);
            }

            auto& pixel = next.pixel(x, y);
            for (size_t c = 0; c < pixel.size(); ++c) {
                const int center = avg(avg(tile[1][1][c], tile[1][2][c]), avg(tile[2][1][c], tile[2][2][c]));
                const int corner = avg(avg(tile[0][0][c], tile[3][0][c]), avg(tile[0][3][c], tile[3][3][c]));
                const int top = avg(tile[0][1][c], tile[0][2][c]);
                const int right = avg(tile[1][3][c], tile[2][3][c]);
                const int bottom = avg(tile[3][1][c], tile[3][2][c]);
                const int left = avg(tile[1][0][c], tile[2][0][c]);
                pixel[c] = static_cast<uint8_t>(avg(avg(center, avg(center, corner)), avg(avg(top, right), avg(bottom, left))));
            }
        }
    }
    });
    return next;
}

template <typename Pixel>
int upsample(const Level<Pixel>& level, int x, int y, size_t channel) {
    const int ix = floor_shift(x, 1), iy = floor_shift(y, 1);
    const int nx = ix + (x % 2 != 0 ? 1 : -1);
    const int ny = iy + (y % 2 != 0 ? 1 : -1);
    return floor_shift(9 * level.pixel(ix, iy)[channel] + 3 * level.pixel(nx, iy)[channel] +
                       3 * level.pixel(ix, ny)[channel] + level.pixel(nx, ny)[channel] + 8, 4);
}

int reconstruct(const GaussianPixel& fine, const Level<GaussianPixel>& parent,
                const Level<ReconstructedPixel>& combined, int x, int y, size_t c) {
    const int a = fine[c] - upsample(parent, x, y, c);
    const int b = fine[c + 3] - upsample(parent, x, y, c + 3);
    return mix(a, b, fine[6]) + upsample(combined, x, y, c);
}

// ---- SCAMERA P636: the same pyramid in rows (more than one thread; one thread runs the loops above as they were) ----
// downsample: avg(a, b) = (a + b + 1) / 2 of two bytes is (a | b) - ((a ^ b) >> 1), so the seven channels of a pixel go
// through the tree of averages in one 64-bit word (no carry leaves a byte); level 0 reads rows of pixels packed once a row.
// reconstruct: upsample's 9 a + 3 b + 3 c + d of the two parent rows is 3 (3 a + c) + (3 b + d): the rows are blended once
// (v = 3 * near + far, exact integers), then each pixel takes 3 * v[near] + v[far]; the rounding and the shift are upsample's.

#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
constexpr bool rows_supported = true;
#else
constexpr bool rows_supported = false;
#endif

uint64_t load7(const uint8_t* p) {
    uint32_t low, high;
    std::memcpy(&low, p, 4);
    std::memcpy(&high, p + 3, 4);
    return low | (uint64_t(high) << 24);
}

void store7(uint8_t* p, uint64_t value) {
    const auto low = static_cast<uint32_t>(value), high = static_cast<uint32_t>(value >> 24);
    std::memcpy(p, &low, 4);
    std::memcpy(p + 3, &high, 4);
}

uint64_t avg7(uint64_t a, uint64_t b) {
    return (a | b) - (((a ^ b) >> 1) & 0x007f7f7f7f7f7f7full);
}

// One row of the next level from four source rows of 7-byte pixels (pixel 0 at each pointer), columns by border().
void downsample_row(const uint8_t* const rows[4], int width, bool mirror, Level<GaussianPixel>& next, int y) {
    const int interior_last = (width - 3) / 2;  // 2 x - 1 >= 0 and 2 x + 2 <= width - 1
    for (int x = -padding; x < next.width + padding; ++x) {
        int ix[4];
        if (x >= 1 && x <= interior_last) {
            for (int i = 0; i < 4; ++i) ix[i] = 2 * x + i - 1;
        } else {
            for (int i = 0; i < 4; ++i) ix[i] = border(2 * x + i - 1, width, mirror);
        }
        uint64_t tile[4][4];
        for (int j = 0; j < 4; ++j) {
            for (int i = 0; i < 4; ++i) tile[j][i] = load7(rows[j] + 7 * size_t(ix[i]));
        }
        const uint64_t center = avg7(avg7(tile[1][1], tile[1][2]), avg7(tile[2][1], tile[2][2]));
        const uint64_t corner = avg7(avg7(tile[0][0], tile[3][0]), avg7(tile[0][3], tile[3][3]));
        const uint64_t top = avg7(tile[0][1], tile[0][2]);
        const uint64_t right = avg7(tile[1][3], tile[2][3]);
        const uint64_t bottom = avg7(tile[3][1], tile[3][2]);
        const uint64_t left = avg7(tile[1][0], tile[2][0]);
        store7(next.pixel(x, y).data(),
               avg7(avg7(center, avg7(center, corner)), avg7(avg7(top, right), avg7(bottom, left))));
    }
}

// The two exposures and the weight of image row y as 7-byte pixels (+ one byte of slack for load7's second read).
void pack_row(const Image& bright, const Image& dark, const uint8_t* weights, int y, int width, uint8_t* out) {
    const auto* a = bright.data + size_t(y) * bright.row_stride;
    const auto* b = dark.data + size_t(y) * dark.row_stride;
    for (int x = 0; x < width; ++x, a += 3, b += 3, out += 7) {
        out[0] = a[0];
        out[1] = a[1];
        out[2] = a[2];
        out[3] = b[0];
        out[4] = b[1];
        out[5] = b[2];
        out[6] = weights[std::max({a[0], a[1], a[2]})];
    }
}

Level<GaussianPixel> downsample_images(const Image& bright, const Image& dark, const uint8_t* weights, int width, int height) {
    Level<GaussianPixel> next((width + 1) / 2, (height + 1) / 2, false);
    detail::parallel_rows(-padding, next.height + padding, 112 * std::ptrdiff_t(next.width + 2 * padding),
                          [&](std::ptrdiff_t first, std::ptrdiff_t last) {
        const size_t row_bytes = size_t(width) * 7 + 1;
        std::vector<uint8_t> storage(4 * row_bytes);
        int held[4] = {-1, -1, -1, -1};
        for (int y = static_cast<int>(first); y < static_cast<int>(last); ++y) {
            int iy[4];
            for (int j = 0; j < 4; ++j) iy[j] = border(2 * y + j - 1, height, false);
            const uint8_t* rows[4];
            for (int j = 0; j < 4; ++j) {
                int slot = -1;
                for (int k = 0; k < 4; ++k) if (held[k] == iy[j]) slot = k;
                if (slot < 0) {
                    for (int k = 0; k < 4 && slot < 0; ++k) {
                        if (held[k] != iy[0] && held[k] != iy[1] && held[k] != iy[2] && held[k] != iy[3]) slot = k;
                    }
                    pack_row(bright, dark, weights, iy[j], width, storage.data() + slot * row_bytes);
                    held[slot] = iy[j];
                }
                rows[j] = storage.data() + slot * row_bytes;
            }
            downsample_row(rows, width, false, next, y);
        }
    });
    return next;
}

Level<GaussianPixel> downsample_level(const Level<GaussianPixel>& parent, bool mirror) {
    const int width = parent.width, height = parent.height;
    Level<GaussianPixel> next((width + 1) / 2, (height + 1) / 2, false);
    detail::parallel_rows(-padding, next.height + padding, 112 * std::ptrdiff_t(next.width + 2 * padding),
                          [&](std::ptrdiff_t first, std::ptrdiff_t last) {
        for (int y = static_cast<int>(first); y < static_cast<int>(last); ++y) {
            const uint8_t* rows[4];
            for (int j = 0; j < 4; ++j) rows[j] = parent.pixel(0, border(2 * y + j - 1, height, mirror)).data();
            downsample_row(rows, width, mirror, next, y);
        }
    });
    return next;
}

// upsample() of `channels` channels of a level's rows iy = floor(y / 2) and its neighbour for the fine columns
// [x_first, x_last): out[(x - x_first) * channels + c]. near / far: pixel 0 of the two rows, `step` values a pixel.
// x_first and x_last are even (a fine column 2 k takes the columns k and k - 1, 2 k + 1 the columns k and k + 1).
// Value: what the sums are held in (the 8-bit parent's fit 16 bits: 16 * 255 + 8).
template <typename Sample, typename Value>
void upsample_row(const Sample* near, const Sample* far, int step, int channels, int x_first, int x_last,
                  std::vector<Value>& blend, Value* out) {
    const int k_first = floor_shift(x_first, 1) - 1, k_last = floor_shift(x_last - 1, 1) + 1;  // the columns read
    blend.resize(size_t(k_last - k_first + 1) * channels);
    for (int k = k_first; k <= k_last; ++k) {
        const Sample* a = near + std::ptrdiff_t(k) * step;
        const Sample* b = far + std::ptrdiff_t(k) * step;
        Value* v = blend.data() + size_t(k - k_first) * channels;
        for (int c = 0; c < channels; ++c) v[c] = static_cast<Value>(3 * a[c] + b[c]);
    }
    for (int x = x_first; x < x_last; x += 2) {
        const Value* mid = blend.data() + size_t(floor_shift(x, 1) - k_first) * channels;
        const Value* left = mid - channels;
        const Value* right = mid + channels;
        Value* o = out + size_t(x - x_first) * channels;
        for (int c = 0; c < channels; ++c) {
            o[c] = static_cast<Value>(floor_shift(3 * mid[c] + left[c] + 8, 4));
            o[channels + c] = static_cast<Value>(floor_shift(3 * mid[c] + right[c] + 8, 4));
        }
    }
}

// The upsampled parent (6 channels) and combined level (3) under fine row y, columns [x_first, x_last).
struct RowsUnder {
    std::vector<int16_t> blend16, parent;
    std::vector<int32_t> blend, combined;

    void fill(const Level<GaussianPixel>& parent_level, const Level<ReconstructedPixel>& combined_level, int y,
              int x_first, int x_last) {
        const int iy = floor_shift(y, 1), ny = iy + (y % 2 != 0 ? 1 : -1);
        x_last += x_last & 1;  // whole pairs: the column past an odd end is computed (inside the level's padding), not used
        parent.resize(size_t(x_last - x_first) * 6);
        combined.resize(size_t(x_last - x_first) * 3);
        upsample_row(parent_level.pixel(0, iy).data(), parent_level.pixel(0, ny).data(), 7, 6, x_first, x_last, blend16,
                     parent.data());
        upsample_row(combined_level.pixel(0, iy).data(), combined_level.pixel(0, ny).data(), 3, 3, x_first, x_last, blend,
                     combined.data());
    }
};

// `output` (width x height x 3, dense) may be the bright exposure's own pixels when that is dense: an output pixel is
// written after the last read of the exposures' pixel of the same place, and no other place reads it (the pyramid is built).
void tone_virtual_rows(Version version, const Image& bright, const Image& dark, uint8_t* output) {
    const auto w = static_cast<int>(bright.width), h = static_cast<int>(bright.height);
    uint8_t weights[256];
    for (int i = 0; i < 256; ++i) weights[i] = detail::bright_weight(version, static_cast<uint8_t>(i), 0, 0);

    std::vector<Level<GaussianPixel>> levels;
    levels.push_back(downsample_images(bright, dark, weights, w, h));
    for (int depth = 1; depth < 9 && w > (6 << depth); ++depth) {
        auto next = downsample_level(levels.back(), depth >= 3);
        levels.push_back(std::move(next));
    }

    const auto& coarse = levels.back();
    Level<ReconstructedPixel> combined(coarse.width, coarse.height, false);
    for (size_t i = 0; i < coarse.data.size(); ++i) {
        const auto& p = coarse.data[i];
        for (size_t c = 0; c < 3; ++c) combined.data[i][c] = static_cast<int16_t>(mix(p[c], p[c + 3], p[6]));
    }
    for (size_t i = levels.size() - 1; i > 0; --i) {
        const auto& fine = levels[i - 1];
        const auto& parent = levels[i];
        Level<ReconstructedPixel> next(fine.width, fine.height, false);
        detail::parallel_rows(-padding, fine.height + padding, 36 * std::ptrdiff_t(fine.width + 2 * padding),
                              [&](std::ptrdiff_t first, std::ptrdiff_t last) {
            RowsUnder under;
            for (int y = static_cast<int>(first); y < static_cast<int>(last); ++y) {
                under.fill(parent, combined, y, -padding, fine.width + padding);
                const GaussianPixel* pixel = &fine.pixel(-padding, y);
                ReconstructedPixel* out = &next.pixel(-padding, y);
                const int16_t* up = under.parent.data();
                const int32_t* low = under.combined.data();
                for (int x = 0; x < fine.width + 2 * padding; ++x, ++pixel, ++out, up += 6, low += 3) {
                    const int weight = (*pixel)[6];
                    for (size_t c = 0; c < 3; ++c) {
                        (*out)[c] = static_cast<int16_t>(
                            mix((*pixel)[c] - up[c], (*pixel)[c + 3] - up[c + 3], weight) + low[c]);
                    }
                }
            }
        });
        combined = std::move(next);
    }

    detail::parallel_rows(0, h, 36 * std::ptrdiff_t(w), [&](std::ptrdiff_t first, std::ptrdiff_t last) {
        RowsUnder under;
        for (int y = static_cast<int>(first); y < static_cast<int>(last); ++y) {
            under.fill(levels.front(), combined, y, 0, w);
            const auto* a = bright.data + size_t(y) * bright.row_stride;
            const auto* b = dark.data + size_t(y) * dark.row_stride;
            uint8_t* out = output + size_t(y) * w * 3;
            const int16_t* up = under.parent.data();
            const int32_t* low = under.combined.data();
            for (int x = 0; x < w; ++x, a += 3, b += 3, out += 3, up += 6, low += 3) {
                const int weight = weights[std::max({a[0], a[1], a[2]})];
                const int values[3] = {mix(a[0] - up[0], b[0] - up[3], weight) + low[0],
                                       mix(a[1] - up[1], b[1] - up[4], weight) + low[1],
                                       mix(a[2] - up[2], b[2] - up[5], weight) + low[2]};
                for (size_t c = 0; c < 3; ++c) out[c] = static_cast<uint8_t>(std::clamp(values[c], 0, 255));
            }
        }
    });
}

void validate(const Image& image) {
    constexpr auto max_dimension = std::numeric_limits<int>::max() / 8;
    if (!image.data || !image.width || !image.height || image.width % 32 || image.height % 32 ||
        image.width > max_dimension || image.height > max_dimension ||
        image.width * 3 > image.row_stride || image.width * 3 > image.bytes ||
        image.height - 1 > (image.bytes - image.width * 3) / image.row_stride ||
        image.width > std::numeric_limits<size_t>::max() / image.height / 3) {
        throw std::invalid_argument("Invalid interleaved RGB8 image for HDR tone mapping");
    }
}

}  // namespace

std::vector<uint8_t> detail::tone_virtual_rgb8(Version version, const Image& bright, const Image& dark) {
    validate(bright);
    validate(dark);
    if (bright.width != dark.width || bright.height != dark.height) {
        throw std::invalid_argument("Virtual exposures must have equal dimensions");
    }
    if (rows_supported && detail::thread_count() > 1) {
        std::vector<uint8_t> output(bright.width * bright.height * 3);
        tone_virtual_rows(version, bright, dark, output.data());
        return output;
    }
    const auto w = static_cast<int>(bright.width), h = static_cast<int>(bright.height);
    const auto input_pixel = [&](int x, int y) {
        const auto* a = bright.data + size_t(y) * bright.row_stride + 3 * size_t(x);
        const auto* b = dark.data + size_t(y) * dark.row_stride + 3 * size_t(x);
        return GaussianPixel{a[0], a[1], a[2], b[0], b[1], b[2], bright_weight(version, a[0], a[1], a[2])};
    };

    std::vector<Level<GaussianPixel>> levels;
    levels.push_back(downsample(w, h, false, input_pixel));
    for (int depth = 1; depth < 9 && w > (6 << depth); ++depth) {
        const auto& parent = levels.back();
        auto next = downsample(parent.width, parent.height, depth >= 3,
                               [&](int x, int y) { return parent.pixel(x, y); });
        levels.push_back(std::move(next));
    }

    const auto& coarse = levels.back();
    Level<ReconstructedPixel> combined(coarse.width, coarse.height);
    for (size_t i = 0; i < coarse.data.size(); ++i) {
        const auto& p = coarse.data[i];
        for (size_t c = 0; c < 3; ++c) combined.data[i][c] = static_cast<int16_t>(mix(p[c], p[c + 3], p[6]));
    }
    for (size_t i = levels.size() - 1; i > 0; --i) {
        const auto& fine = levels[i - 1];
        const auto& parent = levels[i];
        Level<ReconstructedPixel> next(fine.width, fine.height);
        detail::parallel_rows(-padding, fine.height + padding, 36 * std::ptrdiff_t(fine.width + 2 * padding),
                              [&](std::ptrdiff_t first, std::ptrdiff_t last) {
        for (int y = static_cast<int>(first); y < static_cast<int>(last); ++y) {
            for (int x = -padding; x < fine.width + padding; ++x) {
                const auto& pixel = fine.pixel(x, y);
                for (size_t c = 0; c < 3; ++c) next.pixel(x, y)[c] =
                    static_cast<int16_t>(reconstruct(pixel, parent, combined, x, y, c));
            }
        }
        });
        combined = std::move(next);
    }

    std::vector<uint8_t> output(bright.width * bright.height * 3);
    detail::parallel_rows(0, h, 36 * std::ptrdiff_t(w), [&](std::ptrdiff_t first, std::ptrdiff_t last) {
    for (int y = static_cast<int>(first); y < static_cast<int>(last); ++y) {
        for (int x = 0; x < w; ++x) {
            const auto pixel = input_pixel(x, y);
            for (size_t c = 0; c < 3; ++c) {
                output[(size_t(y) * w + x) * 3 + c] = static_cast<uint8_t>(
                    std::clamp(reconstruct(pixel, levels.front(), combined, x, y, c), 0, 255));
            }
        }
    }
    });
    return output;
}

// SCAMERA P636: tone_virtual_rgb8 of two dense exposures of the module's own (tone_pair), the result in the bright one's
// storage (no third frame); one thread: tone_virtual_rgb8 as it was.
std::vector<uint8_t> detail::tone_virtual_owned(Version version, size_t width, size_t height, std::vector<uint8_t>& bright,
                                                const uint8_t* dark, size_t dark_bytes) {
    const Image a{bright.data(), bright.size(), width, height, width * 3, 0};
    const Image b{dark, dark_bytes, width, height, width * 3, 0};
    if (!(rows_supported && thread_count() > 1)) return tone_virtual_rgb8(version, a, b);
    validate(a);
    validate(b);
    tone_virtual_rows(version, a, b, bright.data());
    return std::move(bright);
}

}  // namespace tone
