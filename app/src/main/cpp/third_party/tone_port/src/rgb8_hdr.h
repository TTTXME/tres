#pragma once

#include "tone_port.h"

namespace tone::detail {

uint8_t bright_weight(Version version, uint8_t r, uint8_t g, uint8_t b);
std::vector<uint8_t> tone_virtual_rgb8(Version version, const Image& bright, const Image& dark);

}  // namespace tone::detail
