// P662 (item 4): rows of tightly packed 4-byte pixels into rows of a bitmap (stride bytes apart) on up to `threads` threads - the
// final readback's ring (JpegliEncoder.copyRows). Every destination row gets exactly its source row; tools/check_row_copy.cpp.
#pragma once
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <exception>
#include <thread>
#include <vector>

namespace scamera_rowcopy {
inline void copyRows(const uint8_t* from, uint8_t* to, size_t row, size_t stride, uint32_t rows, int threads) {
    const int n = std::clamp(threads, 1, 8);
    const uint32_t per = (rows + uint32_t(n) - 1) / uint32_t(n);
    auto part = [&](uint32_t r0) {
        const uint32_t r1 = std::min(rows, r0 + per);
        if (r1 <= r0) return;
        if (stride == row) { std::memcpy(to + size_t(r0) * row, from + size_t(r0) * row, size_t(r1 - r0) * row); return; }
        for (uint32_t r = r0; r < r1; r++) std::memcpy(to + size_t(r) * stride, from + size_t(r) * row, row);
    };
    std::vector<std::thread> pool;
    try {
        for (int k = 1; k < n; k++) pool.emplace_back(part, uint32_t(k) * per);
    } catch (const std::exception&) {
        // fewer threads: this one copies the rest below
    }
    part(0);
    for (uint32_t k = uint32_t(pool.size()) + 1; k < uint32_t(n); k++) part(k * per);
    for (auto& t : pool) t.join();
}
}  // namespace scamera_rowcopy
