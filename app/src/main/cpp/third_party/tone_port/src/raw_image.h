#pragma once

#include "tone_port.h"

#include <limits>
#include <stdexcept>

namespace tone::detail {

template<typename PlanarImage>
inline void validate_raw_planar(const PlanarImage& image, size_t planes) {
    constexpr auto max_dimension = std::numeric_limits<int>::max() / 8;
    if (!image.data || !image.width || !image.height ||
        image.width > max_dimension || image.height > max_dimension ||
        image.row_stride < image.width || image.width > image.samples ||
        image.height - 1 > (image.samples - image.width) / image.row_stride ||
        image.width > std::numeric_limits<size_t>::max() / image.height / planes) {
        throw std::invalid_argument("Invalid planar 16-bit image");
    }
    const auto plane = (image.height - 1) * image.row_stride + image.width;
    const auto stride = image.plane_stride;
    if (stride < plane || stride > (image.samples - plane) / (planes - 1)) {
        throw std::invalid_argument("Invalid 16-bit plane stride");
    }
}

}  // namespace tone::detail
