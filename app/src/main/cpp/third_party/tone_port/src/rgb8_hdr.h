#pragma once

#include "tone_port.h"

namespace tone::detail {

uint8_t bright_weight(Version version, uint8_t r, uint8_t g, uint8_t b);
std::vector<uint8_t> tone_virtual_rgb8(Version version, const Image& bright, const Image& dark);
// SCAMERA P636: the same of two dense exposures; with more than one thread the result takes the bright one's storage.
std::vector<uint8_t> tone_virtual_owned(Version version, size_t width, size_t height, std::vector<uint8_t>& bright,
                                        const uint8_t* dark, size_t dark_bytes);

}  // namespace tone::detail
