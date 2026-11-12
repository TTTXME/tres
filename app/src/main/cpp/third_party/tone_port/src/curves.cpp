#include "tone_port.h"

#include <algorithm>
#include <stdexcept>

namespace tone {
namespace {
#include "lut_data.inc"

template <typename T>
Curve10 smooth(const T& data, int radius) {
    Curve10 out{};
    const int size = static_cast<int>(data.size());
    const float reciprocal = 1.f / (2 * radius + 1);
    for (int i = 0; i < size; ++i) {
        const int r = std::min({i, size - 1 - i, radius});
        unsigned total = 0;
        for (int j = i - r; j <= i + r; ++j) {
            total += data[j];
        }
        const float mean = r == radius ? total * reciprocal : float(total) / (2 * r + 1);
        out[i] = std::min(1023, static_cast<int>(mean + .5f));
    }
    return out;
}
}  // namespace

const Curve8& yuv_curve() { return kYuv; }
const Curve12& raw_curve_31() { return kRaw31; }

Inverse invert(const Curve8& curve) {
    if (!std::is_sorted(curve.begin(), curve.end())) {
        throw std::invalid_argument("Input curve must be monotonic");
    }
    Inverse out{};
    for (int first = 0; first < 1024;) {
        int next = first + 1;
        while (next < 1024 && curve[next] == curve[first]) {
            ++next;
        }
        out[curve[first]] = (first + next) / 2;
        const int upper = next < 1024 ? curve[next] : 256;
        std::fill(out.begin() + curve[first] + 1, out.begin() + upper,
                  std::min(next, 1023));
        first = next;
    }
    return out;
}

Curve10 expand_inverse(const Inverse& input) {
    Curve10 expanded{};
    for (size_t i = 0; i < input.size(); ++i) {
        std::fill_n(expanded.begin() + i * 4, 4, input[i]);
    }
    return smooth(expanded, 8);
}

Curve10 expand_forward(const Curve8& curve) {
    Curve10 expanded{};
    std::transform(curve.begin(), curve.end(), expanded.begin(), [](uint8_t x) { return x << 2; });
    return smooth(expanded, 8);
}

Curve10 retone(const Inverse& input, const Curve8& output, uint32_t gain_q8) {
    Curve10 expanded{};
    for (size_t i = 0; i < input.size(); ++i) {
        const uint64_t index = (uint64_t(input[i]) * gain_q8 + 128) >> 8;
        std::fill_n(expanded.begin() + i * 4, 4, output[std::min<uint64_t>(index, 1023)] << 2);
    }
    return smooth(expanded, 12);
}
}  // namespace tone
