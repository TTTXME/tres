#include "parallel.h"
#include "tone_port.h"

#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <exception>
#include <mutex>
#include <thread>
#include <vector>

namespace tone {
namespace {

constexpr int max_threads = 16;
std::atomic<int> configured{-1};

int automatic() {
    const unsigned cores = std::thread::hardware_concurrency();
    return std::clamp(static_cast<int>(cores ? cores : 1), 1, 8);
}

int from_environment() {
    const char* text = std::getenv("TONE_PORT_THREADS");
    if (!text || !*text) return 1;
    const int value = std::atoi(text);
    return value <= 0 ? automatic() : std::min(value, max_threads);
}

}  // namespace

void set_threads(int count) {
    configured.store(count <= 0 ? automatic() : std::min(count, max_threads));
}

int threads() {
    return detail::thread_count();
}

int detail::thread_count() {
    int value = configured.load();
    if (value < 0) {
        value = from_environment();
        configured.store(value);
    }
    return value;
}

void detail::run_bands(std::ptrdiff_t begin, std::ptrdiff_t end, std::ptrdiff_t grain,
                       const std::function<void(std::ptrdiff_t, std::ptrdiff_t)>& body) {
    grain = std::max<std::ptrdiff_t>(1, grain);
    const std::ptrdiff_t bands = (end - begin + grain - 1) / grain;
    const int workers = static_cast<int>(std::min<std::ptrdiff_t>(thread_count(), bands));
    std::atomic<std::ptrdiff_t> next{begin};
    std::exception_ptr failure;
    std::mutex failure_lock;
    const auto work = [&] {
        try {
            for (;;) {
                const std::ptrdiff_t first = next.fetch_add(grain);
                if (first >= end) break;
                body(first, std::min(end, first + grain));
            }
        } catch (...) {
            std::lock_guard<std::mutex> hold(failure_lock);
            if (!failure) failure = std::current_exception();
            next.store(end);
        }
    };
    std::vector<std::thread> pool;
    pool.reserve(workers > 1 ? workers - 1 : 0);
    try {
        for (int i = 1; i < workers; ++i) pool.emplace_back(work);
    } catch (...) {
        // a thread that could not start: the rows go to the threads that did (and to this one)
    }
    work();
    for (auto& thread : pool) thread.join();
    if (failure) std::rethrow_exception(failure);
}

}  // namespace tone
