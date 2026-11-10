#pragma once
// P610 (speed and memory, 200 MP): a photo's crop packed to the start of its own pixel buffer (ScamPostDownscale.cropInPlace: the
// bitmap is then reconfigured to the crop's size) instead of a copy into a second bitmap - the OnePlus 16's 200 MP ISZ 4:3 crop
// (12288 x 16320 -> 12240 x 16320, 800 MB) took 1.15 s and held both bitmaps. tools/check_bitmap_crop.cpp checks it on the host.
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace scamera_bitmap {
// Rows [y, y + h), columns [x, x + w) of a width x height image (stride bytes a row, bpp bytes a pixel, bytes of buffer) moved to
// rows of w * bpp bytes from the buffer's start. Row r lands at r * w * bpp, never after its source ((y + r) * stride + x * bpp),
// and before every later row's source: in row order nothing is read after it was overwritten. False (nothing moved): a bad region.
inline bool cropRowsInPlace(uint8_t* base, size_t bytes, size_t stride, int width, int height, int x, int y, int w, int h, int bpp) {
    if (!base || bpp <= 0 || width <= 0 || height <= 0 || x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > width || y + h > height
            || stride < size_t(width) * size_t(bpp) || stride * size_t(height - 1) + size_t(width) * size_t(bpp) > bytes) return false;
    const size_t row = size_t(w) * size_t(bpp);
    for (int r = 0; r < h; ++r) {
        uint8_t* dst = base + size_t(r) * row;
        const uint8_t* src = base + size_t(y + r) * stride + size_t(x) * size_t(bpp);
        if (dst != src) std::memmove(dst, src, row);
    }
    return true;
}
} // namespace scamera_bitmap
