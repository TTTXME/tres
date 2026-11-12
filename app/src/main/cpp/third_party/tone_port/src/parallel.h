#pragma once

// SCAMERA P636: the module's row loops on several threads. Every loop handed to parallel_rows writes rows that no other row
// of the same loop reads, in the loop's own arithmetic, so the result is the single-thread one byte for byte at any thread
// count (tests/regression.py under TONE_PORT_THREADS = 1, 2, 3, 4, 7). One thread (the default): the loop runs as it did.

#include <cstddef>
#include <functional>

namespace tone::detail {

// The threads a loop may use (>= 1): tone::set_threads, else the environment's TONE_PORT_THREADS, else 1.
int thread_count();

void run_bands(std::ptrdiff_t begin, std::ptrdiff_t end, std::ptrdiff_t grain,
               const std::function<void(std::ptrdiff_t, std::ptrdiff_t)>& body);

// body(first, last) over [begin, end) in bands of `grain` rows taken by the threads in turn; `row_cost` is the samples a row
// touches (a loop of little work stays on the caller's thread).
template <typename Body>
inline void parallel_rows(std::ptrdiff_t begin, std::ptrdiff_t end, std::ptrdiff_t row_cost, Body&& body,
                          std::ptrdiff_t grain = 16) {
    if (end <= begin) return;
    if (thread_count() <= 1 || end - begin <= grain || (end - begin) * row_cost < 262144) {
        body(begin, end);
        return;
    }
    run_bands(begin, end, grain, body);
}

}  // namespace tone::detail
