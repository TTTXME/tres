#include "rgb8_hdr.h"

#include <algorithm>
#include <limits>
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

template <typename Pixel>
struct Level {
    int width;
    int height;
    std::vector<Pixel> data;

    Level(int w, int h) : width(w), height(h), data(size_t(w + 2 * padding) * (h + 2 * padding)) {}

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
    for (int y = -padding; y < next.height + padding; ++y) {
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
        for (int y = -padding; y < fine.height + padding; ++y) {
            for (int x = -padding; x < fine.width + padding; ++x) {
                const auto& pixel = fine.pixel(x, y);
                for (size_t c = 0; c < 3; ++c) next.pixel(x, y)[c] =
                    static_cast<int16_t>(reconstruct(pixel, parent, combined, x, y, c));
            }
        }
        combined = std::move(next);
    }

    std::vector<uint8_t> output(bright.width * bright.height * 3);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const auto pixel = input_pixel(x, y);
            for (size_t c = 0; c < 3; ++c) {
                output[(size_t(y) * w + x) * 3 + c] = static_cast<uint8_t>(
                    std::clamp(reconstruct(pixel, levels.front(), combined, x, y, c), 0, 255));
            }
        }
    }
    return output;
}

}  // namespace tone
