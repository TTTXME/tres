#pragma once
// P24: every JPEG SCAMERA saves (the plain photo, the Ultra HDR base image and its gain map) is encoded by jpegli with
// full-resolution chroma (4:4:4). Android's Bitmap.compress always subsamples chroma 4:2:0, which halved the colour
// resolution of the merge's detail. jpegli: third_party/README.md.
#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <stdexcept>
#include <string>
#include <vector>

#include "lib/jpegli/encode.h"

namespace scamera_jpeg {

// Receives the encoded bytes in order; returns false to abort the encode.
using Writer = std::function<bool(const uint8_t* data, size_t size)>;

// P443 (the night port 230628 against the reference app's photo: the shadows' fine detail): jpegli's adaptive quantisation,
// on in jpegli_set_defaults, raises the zero bias of the AC coefficients in flat and dark blocks to ~0.85 of a quantisation
// step (0.59 without it): at q98 it zeroed 16 % more AC coefficients of the fenders and the quay wall than the denoise did,
// and halved the grain of a dark sky. Off unless the app turns it on (JpegliEncoder: scam_dev "jpeg_aq 1" = before); the
// quantisation tables are the same either way. Both encoders (one thread, strips) read it.
inline std::atomic<bool>& adaptiveQuantisation() {
    static std::atomic<bool> on{false};
    return on;
}

namespace detail {
struct Destination {
    jpeg_destination_mgr pub{};
    const Writer* writer = nullptr;
    std::vector<JOCTET> buffer = std::vector<JOCTET>(1 << 20);
};

[[noreturn]] inline void errorExit(j_common_ptr cinfo) {
    char message[JMSG_LENGTH_MAX]{};
    (*cinfo->err->format_message)(cinfo, message);
    throw std::runtime_error(std::string("jpegli: ") + message);
}

inline void initDestination(j_compress_ptr cinfo) {
    auto* d = reinterpret_cast<Destination*>(cinfo->dest);
    d->pub.next_output_byte = d->buffer.data();
    d->pub.free_in_buffer = d->buffer.size();
}

inline boolean emptyBuffer(j_compress_ptr cinfo) {
    auto* d = reinterpret_cast<Destination*>(cinfo->dest);
    if (!(*d->writer)(d->buffer.data(), d->buffer.size())) throw std::runtime_error("JPEG output stream failed");
    d->pub.next_output_byte = d->buffer.data();
    d->pub.free_in_buffer = d->buffer.size();
    return TRUE;
}

inline void termDestination(j_compress_ptr cinfo) {
    auto* d = reinterpret_cast<Destination*>(cinfo->dest);
    const size_t used = d->buffer.size() - d->pub.free_in_buffer;
    if (used > 0 && !(*d->writer)(d->buffer.data(), used)) throw std::runtime_error("JPEG output stream failed");
}
}  // namespace detail

// Encodes RGBA rows (alpha ignored; Android ARGB_8888 memory order) as a baseline-compatible progressive JPEG,
// YCbCr 4:4:4, quality 1-100 on the libjpeg scale. Throws std::runtime_error on any failure.
inline void encodeRgba(const uint8_t* pixels, int width, int height, size_t stride, int quality, const Writer& writer, bool aq = adaptiveQuantisation().load()) {
    if (pixels == nullptr || width <= 0 || height <= 0 || stride < size_t(width) * 4)
        throw std::runtime_error("JPEG: bad image geometry");
    jpeg_compress_struct cinfo{};
    jpeg_error_mgr jerr{};
    cinfo.err = jpegli_std_error(&jerr);
    jerr.error_exit = detail::errorExit;
    detail::Destination destination;
    destination.writer = &writer;
    destination.pub.init_destination = detail::initDestination;
    destination.pub.empty_output_buffer = detail::emptyBuffer;
    destination.pub.term_destination = detail::termDestination;
    try {
        jpegli_create_compress(&cinfo);
        cinfo.dest = &destination.pub;
        cinfo.image_width = JDIMENSION(width);
        cinfo.image_height = JDIMENSION(height);
        cinfo.input_components = 4;
        cinfo.in_color_space = JCS_EXT_RGBA;
        jpegli_set_defaults(&cinfo);
        jpegli_set_quality(&cinfo, quality < 1 ? 1 : quality > 100 ? 100 : quality, TRUE);
        jpegli_enable_adaptive_quantization(&cinfo, aq ? TRUE : FALSE);   // P443
        for (int c = 0; c < cinfo.num_components; ++c) {
            cinfo.comp_info[c].h_samp_factor = 1;
            cinfo.comp_info[c].v_samp_factor = 1;
        }
        jpegli_start_compress(&cinfo, TRUE);
        constexpr int kRows = 16;
        JSAMPROW rows[kRows];
        while (cinfo.next_scanline < cinfo.image_height) {
            const int n = int(std::min<JDIMENSION>(kRows, cinfo.image_height - cinfo.next_scanline));
            for (int i = 0; i < n; ++i)
                rows[i] = const_cast<JSAMPROW>(pixels + size_t(cinfo.next_scanline + JDIMENSION(i)) * stride);
            jpegli_write_scanlines(&cinfo, rows, JDIMENSION(n));
        }
        jpegli_finish_compress(&cinfo);
    } catch (...) {
        jpegli_destroy_compress(&cinfo);
        throw;
    }
    jpegli_destroy_compress(&cinfo);
}

}  // namespace scamera_jpeg
