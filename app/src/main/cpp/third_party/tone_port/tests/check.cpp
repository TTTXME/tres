#include "tone_port.h"

#include <algorithm>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <string>

namespace {

const std::array<float, 9> test_ccm{1.1f, -.09f, .05f, -.04f, 1.2f, -.06f, .12f, -.14f, 1.09f};

template <typename T>
void write(std::ofstream& output, const T& data) {
    output.write(reinterpret_cast<const char*>(data.data()), data.size() * sizeof(data[0]));
}

int run(int argc, char** argv) {
    if (argc != 8) {
        throw std::runtime_error("tone_check <27|31> <mode> <w> <h> <variant> <input> <out>");
    }
    const std::string version = argv[1], mode = argv[2];
    if (version != "27" && version != "31") throw std::runtime_error("Unknown profile");
    const size_t w = std::stoul(argv[3]), h = std::stoul(argv[4]), variant = std::stoul(argv[5]);
    std::ifstream input(argv[6], std::ios::binary);
    std::ofstream output(argv[7], std::ios::binary);
    if (!input || !output) throw std::runtime_error("Cannot open test files");
    const std::vector<uint8_t> bytes{std::istreambuf_iterator<char>(input), {}};
    const auto& curve = tone::yuv_curve();
    const auto inverse = tone::invert(curve);

    const auto profile = version == "27" ? tone::Version::v27 : tone::Version::v31;
    if (mode == "curves") {
        write(output, curve);
        write(output, inverse);
        write(output, tone::expand_inverse(inverse));
        write(output, tone::retone(inverse, curve, 401));
        if (version == "31") write(output, tone::raw_curve_31());
    } else if (mode == "raw-toe") {
        const size_t count = w * h;
        if (bytes.size() != count * 6) throw std::runtime_error("Invalid RAW tone input");
        std::vector<uint16_t> pixels(count * 3);
        for (size_t i = 0; i < pixels.size(); ++i) {
            pixels[i] = static_cast<uint16_t>(bytes[2 * i] + 256 * bytes[2 * i + 1]);
        }
        write(output, tone::tone_raw31_opponent({pixels.data(), pixels.size(), w, h, w, count}));
    } else if (mode == "raw8" || mode == "raw10" || mode == "raw-rgb" || mode == "raw-pack" ||
               mode == "raw-render") {
        const size_t count = w * h;
        if ((mode != "raw-rgb" && version != "31") || bytes.size() != count * 6 || variant > 7) {
            throw std::runtime_error("Invalid RAW test input");
        }
        std::vector<int16_t> pixels(count * 3);
        for (size_t i = 0; i < pixels.size(); ++i) {
            const int value = bytes[2 * i] + 256 * bytes[2 * i + 1];
            pixels[i] = static_cast<int16_t>(value > 32767 ? value - 65536 : value);
        }
        const tone::RawRgbImage src{pixels.data(), pixels.size(), w, h, w, count};
        const std::array<std::array<float, 9>, 3> matrices{{
            {1.f, 0.f, 0.f, 0.f, 1.f, 0.f, 0.f, 0.f, 1.f},
            test_ccm,
            {1.56f, -.37f, -.16f, -.29f, 1.27f, .04f, .18f, -.13f, 1.29f}
        }};
        if (mode == "raw-render") {
            const auto& lut = tone::raw_curve_31();
            const std::array<float, 4> saturation{0.f, .2f, -.25f, .45f};
            if (variant == 0 || variant == 3) {
                write(output, tone::render_raw31_sdr(src, lut, saturation.at(variant)));
            } else {
                const auto gains = variant == 2 ? tone::raw_hdr_gains_31(1.15f, 1.35f, 3.17f, 16.f)
                                                : tone::raw_hdr_gains_31(1.f, 1.f, 4.f, 16.f);
                write(output, tone::render_raw31_hdr(src, gains, variant == 2, lut, saturation.at(variant)));
            }
            return output ? 0 : 1;
        }
        if (mode == "raw-pack") {
            const std::array<float, 4> saturation{0.f, 1.f, -.45f, 2.35f};
            write(output, tone::pack_raw31_rgb8(src, saturation.at(variant)));
            return output ? 0 : 1;
        }
        if (mode == "raw-rgb" && version == "27") {
            auto lut = tone::yuv_curve();
            if (variant == 2) {
                for (size_t i = 0; i < lut.size(); ++i) lut[i] = static_cast<uint8_t>(i / 4);
            }
            write(output, tone::finish_raw27_rgb(src, matrices.at(variant), lut));
            return output ? 0 : 1;
        }
        const std::array<float, 4> short_gains{1.f, .75f, 1.f, .97f};
        const std::array<float, 4> long_gains{4.f, 2.f, 1.f, 2.87f};
        auto lut = tone::raw_curve_31();
        if (variant >= 4) {
            for (size_t i = 0; i < lut.size(); ++i) lut[i] = static_cast<uint16_t>(i);
        }
        const auto result = mode == "raw-rgb" ? tone::tone_raw31_ldr(src, lut)
            : tone::tone_raw31_hdr(src, short_gains[variant % 4], long_gains[variant % 4],
                               mode == "raw8", lut);
        for (uint16_t value : result) {
            output.put(static_cast<char>(value & 255));
            output.put(static_cast<char>(value >> 8));
        }
    } else if (mode == "retone") {
        const tone::WhiteBalance neutral{
            5000, {512, 512, 512, 512}, {1.f, 0.f, 0.f, 0.f, 1.f, 0.f, 0.f, 0.f, 1.f}
        };
        auto adjusted = neutral;
        if (variant >= 3 && variant <= 6) {
            adjusted.color_temperature = 5600;
            adjusted.gains_q9 = {573, 512, 512, 471};
        }
        if (variant == 5 || variant == 6) {
            adjusted.rgb_to_rgb = {1.04f, .02f, -.01f, -.01f, .96f, .03f, .02f, .02f, 1.05f};
        }
        tone::Curve8 target = curve;
        if (variant == 2 || variant >= 4) {
            for (size_t i = 0; i < target.size(); ++i) target[i] = static_cast<uint8_t>(i / 4);
        }
        const std::array<float, 8> gains{1.f, 1.45f, 1.45f, 1.f, 1.f, 1.45f, 1.45f, .65f};
        const tone::Image src{bytes.data(), bytes.size(), w, h, w, w * h};
        write(output, tone::retone_yuv(src, neutral, adjusted, gains.at(variant), curve,
                                       inverse, target, variant == 6));
    } else if (mode == "rgb-luts-hdr" || mode == "yuv-luts-hdr" ||
               mode == "rgb-mat-hdr" || mode == "yuv-mat-hdr") {
        const bool yuv = mode.front() == 'y';
        const tone::Image src{bytes.data(), bytes.size(), w, h, yuv ? w : w * 3, yuv ? w * h : 0};
        const auto color = yuv ? tone::ColorInput::yuv : tone::ColorInput::rgb;
        if (mode.find("luts") != std::string::npos) {
            std::array<tone::Curve10, 3> a, b;
            for (unsigned i = 0; i < a.size(); ++i) {
                a[i] = tone::retone(inverse, curve, 600 + i * 25);
                b[i] = tone::retone(inverse, curve, 240 + i * 7);
            }
            write(output, tone::tone_rgb8_hdr_luts(profile, color, src, a, b));
        } else {
            tone::Curve10 forward{};
            std::transform(curve.begin(), curve.end(), forward.begin(), [](uint8_t v) { return v << 2; });
            const tone::Matrix a{2000, 21, -11, 17, 2200, 6, 9, 11, 1800};
            const tone::Matrix b{1024, 0, 0, 0, 1024, 0, 0, 0, 1024};
            write(output, tone::tone_rgb8_hdr_matrices(
                profile, color, src, forward, tone::expand_inverse(inverse), a, 10, b, 10));
        }
    } else {
        throw std::runtime_error("Unknown test mode");
    }
    return output ? 0 : 1;
}
}

int main(int argc, char** argv) {
    try {
        return run(argc, argv);
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
