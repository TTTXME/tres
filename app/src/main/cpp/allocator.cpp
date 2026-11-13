//
// Created by eszdman on 03.06.2025.
//

#include <jni.h>
#include <malloc.h>
#include <string.h>
#include <algorithm>
#include <cmath>
#include <thread>
#include <unordered_map>
#include <vector>
#include "android/log.h"

#define ALLOC_LOG(...) __android_log_print(ANDROID_LOG_DEBUG, "Allocator", __VA_ARGS__)
// The JNI entry points of util/Allocator.java (and RawUnpack.java)
#define ALLOC_JNI(type, name) extern "C" JNIEXPORT type JNICALL Java_com_scamera_app_util_Allocator_##name
#define UNPACK_JNI(type, name) extern "C" JNIEXPORT type JNICALL Java_com_scamera_app_util_RawUnpack_##name

#include <atomic>
#include <mutex>
#include <sys/mman.h>
#include <fcntl.h>
#include <linux/falloc.h>
#include <sys/syscall.h>
#include <unistd.h>
#if defined(__aarch64__)
#include <arm_neon.h>
#endif
std::atomic<long> memoryCount{0};

// Pixel 10 Pro XL (owner's RAW10 photo, 2026-10-12: green shadows): its main camera declares 12-bit levels (black 256, white
// 4095) and the RAW10 stream carries 10-bit samples. Packed RAW10 / RAW12 samples are shifted left by these bits when the
// camera's declared white is wider than the format (set per session from Java, RawUnpack.setPackedShift), so every level of
// the metadata (black, white, dynamic levels, noise profile) fits the unpacked data. 0 = as stored.
static std::atomic<int> gRaw10Shift{0}, gRaw12Shift{0};
ALLOC_JNI(void, setPackedShift)(JNIEnv*, jclass, jint raw10, jint raw12) {
    gRaw10Shift.store(raw10 < 0 || raw10 > 6 ? 0 : raw10);
    gRaw12Shift.store(raw12 < 0 || raw12 > 4 ? 0 : raw12);
}

// P30: shot arenas. The RAW frames of a Hybrid shot are copied straight into one memfd (the worker's transport) instead of
// malloc buffers that were copied into the memfd again before the merge (~680 MB, ~250 ms). A frame buffer inside an arena is
// a view: free() drops a reference instead of freeing it, the arena is unmapped once released by the shot and unreferenced.
namespace {
// P91: slots, the views of each byte offset: the ZSL ring (one long-lived arena) reuses a slot only when no view of it is left
// (a shot's frame stays pinned until the merge frees it).
// P200: bytes, the length of the views of each offset (the slot's extent, for the holes punched when the arena is released).
// P371: adopted, a memfd another process laid out (the merge worker's result, arenaAdopt): no transport header, no slots of
// frames - its release punches nothing (its trailers are read after its views were made), only arenaPunch's explicit ranges.
struct Arena { int id; int fd; uint8_t* base; size_t size; long refs; bool released; std::unordered_map<size_t, int> slots;
               std::unordered_map<size_t, size_t> bytes; bool adopted = false; };
std::mutex arenaLock;
// P371 (review m1): the slow system calls of an arena change - punching holes into its memfd (11 ms for 72 MB of mapped shmem,
// 44 ms for 302 MB), munmap and close - made once arenaLock is released (declared before the lock guard: destroyed after it), so
// the ZSL ring's arenaSlotRefs / arenaView on the camera thread do not wait for them. The holes go through a duplicate of the
// arena's fd (the arena itself may be unmapped and closed by another thread meanwhile); an arena unmapped here was erased from
// `arenas` under the lock, so nothing else reaches its mapping or fd.
struct ArenaWork {
    int fd = -1; std::vector<std::pair<off_t, off_t>> holes;
    std::vector<std::pair<void*, size_t>> maps; std::vector<int> closes;
    void hole(int arenaFd, size_t offset, size_t bytes) {
        if (fd < 0) fd = fcntl(arenaFd, F_DUPFD_CLOEXEC, 3);
        if (fd >= 0) holes.emplace_back(off_t(offset), off_t(bytes));
    }
    ~ArenaWork() {
        for (const auto& h : holes) fallocate(fd, FALLOC_FL_PUNCH_HOLE | FALLOC_FL_KEEP_SIZE, h.first, h.second);
        if (fd >= 0) close(fd);
        for (const auto& m : maps) munmap(m.first, m.second);
        for (int c : closes) close(c);
    }
};
std::vector<Arena> arenas;
int nextArenaId = 1;
// True when the last view of the slot went.
bool slotUnrefLocked(size_t index, size_t offset) {
    auto it = arenas[index].slots.find(offset);
    if (it != arenas[index].slots.end() && --it->second <= 0) {
        arenas[index].slots.erase(it);
        arenas[index].bytes.erase(offset);
        return true;
    }
    return false;
}
// P179: the pages of a large slot whose last view went go back to the system at once (a hole punched into the memfd), not
// only when the whole arena is unmapped: a 200 MP burst's frames the merge leaves out are 401 MB each. The slot reads as
// zeros afterwards and is written again before any new view of it (the ZSL ring); small slots are left as they were.
constexpr size_t kPunchBytes = size_t(64) << 20;
// P200: only once the arena is released (the shot's frames, a ZSL ring that was closed): a live ring keeps its slots' pages, which
// it writes again at once - a punched 96 MB slot of the 50 MP ring took every page fault again on its next copy (38-75 ms on the
// camera thread per frame, the X300 / X7 Ultra viewfinder stuttered).
void punchLocked(size_t index, size_t offset, size_t bytes, ArenaWork& work) {
    if (!arenas[index].released || bytes < kPunchBytes || offset + bytes > arenas[index].size) return;
    work.hole(arenas[index].fd, offset, bytes);
}
// P200: a released arena gives back at once the pages of its large slots no view holds (a closed 50 MP ring: up to 1.5 GB that
// stayed resident while a shot's frames pinned the memfd). The first 64 KB (the transport header) are kept; holes of at least
// kPunchBytes, page aligned.
void punchFreeLocked(size_t index, ArenaWork& work) {
    Arena& a = arenas[index];
    if (a.adopted) return; // P371 (review B1): not a shot's frames
    std::vector<std::pair<size_t, size_t>> held;
    for (const auto& s : a.slots) {
        auto b = a.bytes.find(s.first);
        held.emplace_back(s.first, b == a.bytes.end() ? size_t(0) : b->second);
    }
    std::sort(held.begin(), held.end());
    constexpr size_t kKeep = size_t(64) << 10;
    size_t from = kKeep;
    auto punch = [&](size_t lo, size_t hi) {
        lo = (lo + 4095) & ~size_t(4095);
        hi &= ~size_t(4095);
        if (hi > lo && hi - lo >= kPunchBytes) work.hole(a.fd, lo, hi - lo);
    };
    for (const auto& h : held) {
        if (h.first > from) punch(from, h.first);
        from = std::max(from, h.first + (h.second ? h.second : size_t(4096)));
    }
    if (a.size > from) punch(from, a.size);
}
// P200: large copies on 4 threads (a 96 MB 50 MP RAW into the ring: one core's copy is bound by its own load / store rate).
void copyBytes(uint8_t* dst, const uint8_t* src, size_t n) {
    constexpr size_t kParallel = size_t(32) << 20;
    if (n < kParallel) { memcpy(dst, src, n); return; }
    constexpr int kThreads = 4;
    const size_t part = ((n / kThreads + (n % kThreads != 0)) + 4095) & ~size_t(4095);
    std::thread pool[kThreads - 1];
    for (int t = 1; t < kThreads; ++t) {
        const size_t lo = std::min(n, part * t), hi = std::min(n, part * (t + 1));
        if (hi <= lo) break;
        try { pool[t - 1] = std::thread([=] { memcpy(dst + lo, src + lo, hi - lo); }); }
        catch (...) { memcpy(dst + lo, src + lo, hi - lo); }
    }
    memcpy(dst, src, std::min(n, part));
    for (auto& helper : pool) if (helper.joinable()) helper.join();
}
void unmapLocked(size_t index, ArenaWork& work) {
    work.maps.emplace_back(arenas[index].base, arenas[index].size);
    work.closes.push_back(arenas[index].fd);
    arenas.erase(arenas.begin() + long(index));
}
long findLocked(int id) {
    for (size_t i = 0; i < arenas.size(); ++i) if (arenas[i].id == id) return long(i);
    return -1;
}
long containingLocked(const void* p) {
    const auto* q = static_cast<const uint8_t*>(p);
    for (size_t i = 0; i < arenas.size(); ++i) if (q >= arenas[i].base && q < arenas[i].base + arenas[i].size) return long(i);
    return -1;
}
}

ALLOC_JNI(jint, arenaCreate)(JNIEnv*, jclass, jlong bytes) {
    if (bytes <= 0) return 0;
    const int fd = int(syscall(__NR_memfd_create, "scamera-shot", 1 /* MFD_CLOEXEC */));
    if (fd < 0) return 0;
    if (ftruncate(fd, off_t(bytes)) != 0) { close(fd); return 0; }
    void* base = mmap(nullptr, size_t(bytes), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (base == MAP_FAILED) { close(fd); return 0; }
    std::lock_guard<std::mutex> lock(arenaLock);
    const int id = nextArenaId++;
    arenas.push_back({id, fd, static_cast<uint8_t*>(base), size_t(bytes), 0, false, {}, {}});
    return id;
}

ALLOC_JNI(jobject, arenaCopy)(JNIEnv* env, jclass, jint id, jlong offset, jobject origin,
                                                              jint originOffset, jint bytes) {
    void* src = origin ? env->GetDirectBufferAddress(origin) : nullptr;
    const jlong srcCapacity = origin ? env->GetDirectBufferCapacity(origin) : 0;
    if (!src || bytes <= 0 || originOffset < 0 || jlong(originOffset) + bytes > srcCapacity) return nullptr;
    uint8_t* dst;
    {
        std::lock_guard<std::mutex> lock(arenaLock);
        const long i = findLocked(id);
        if (i < 0 || arenas[size_t(i)].released || offset < 0 || size_t(offset) + size_t(bytes) > arenas[size_t(i)].size) return nullptr;
        dst = arenas[size_t(i)].base + offset;
        arenas[size_t(i)].refs++;
        arenas[size_t(i)].slots[size_t(offset)]++;
        arenas[size_t(i)].bytes[size_t(offset)] = size_t(bytes);
    }
    copyBytes(dst, static_cast<uint8_t*>(src) + originOffset, size_t(bytes));
    jobject view = env->NewDirectByteBuffer(dst, bytes);
    if (!view) {
        ArenaWork work;
        std::lock_guard<std::mutex> lock(arenaLock);
        const long i = containingLocked(dst);
        if (i >= 0) slotUnrefLocked(size_t(i), size_t(dst - arenas[size_t(i)].base));
        if (i >= 0 && --arenas[size_t(i)].refs == 0 && arenas[size_t(i)].released) unmapLocked(size_t(i), work);
    }
    return view;
}

ALLOC_JNI(jboolean, arenaWrite)(JNIEnv* env, jclass, jint id, jlong offset, jobject origin) {
    void* src = origin ? env->GetDirectBufferAddress(origin) : nullptr;
    const jlong bytes = origin ? env->GetDirectBufferCapacity(origin) : 0;
    if (!src || bytes <= 0) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(arenaLock);
    const long i = findLocked(id);
    if (i < 0 || offset < 0 || size_t(offset) + size_t(bytes) > arenas[size_t(i)].size) return JNI_FALSE;
    memcpy(arenas[size_t(i)].base + offset, src, size_t(bytes));
    return JNI_TRUE;
}

// {arena id, byte offset} of a buffer that is an arena view, or null.
ALLOC_JNI(jlongArray, arenaOf)(JNIEnv* env, jclass, jobject buffer) {
    void* p = buffer ? env->GetDirectBufferAddress(buffer) : nullptr;
    if (!p) return nullptr;
    jlong out[2];
    {
        std::lock_guard<std::mutex> lock(arenaLock);
        const long i = containingLocked(p);
        if (i < 0) return nullptr;
        out[0] = arenas[size_t(i)].id;
        out[1] = jlong(static_cast<uint8_t*>(p) - arenas[size_t(i)].base);
    }
    jlongArray result = env->NewLongArray(2);
    if (result) env->SetLongArrayRegion(result, 0, 2, out);
    return result;
}

// P371 (speed, zero_copy_read): a memfd another process filled (the merge worker's result) adopted as an arena of `bytes`: its
// views (arenaView) read the pages in place - the client preads 85 MB into fresh malloc pages per 12 MP shot (36-54 ms on the
// host, the first-touch faults included) - mapped with MAP_POPULATE, so the GL upload thread takes no faults either. The caller
// keeps its fd (a duplicate is held); arenaRelease, then every view's free, unmaps it, as a shot's frames. Adopted: its release
// punches no gaps (review B1: the trailers between its views are read after it); arenaPunch gives back what was read. 0 on any
// failure.
ALLOC_JNI(jint, arenaAdopt)(JNIEnv*, jclass, jint fd, jlong bytes) {
    if (fd < 0 || bytes <= 0) return 0;
    const int own = fcntl(fd, F_DUPFD_CLOEXEC, 3);
    if (own < 0) return 0;
    void* base = mmap(nullptr, size_t(bytes), PROT_READ | PROT_WRITE, MAP_SHARED | MAP_POPULATE, own, 0);
    if (base == MAP_FAILED) { close(own); return 0; }
    std::lock_guard<std::mutex> lock(arenaLock);
    const int id = nextArenaId++;
    arenas.push_back({id, own, static_cast<uint8_t*>(base), size_t(bytes), 0, false, {}, {}, true});
    return id;
}

// A duplicate of the arena's memfd (the caller owns it), -1 when the arena is gone.
ALLOC_JNI(jint, arenaFd)(JNIEnv*, jclass, jint id) {
    std::lock_guard<std::mutex> lock(arenaLock);
    const long i = findLocked(id);
    return i < 0 ? -1 : fcntl(arenas[size_t(i)].fd, F_DUPFD_CLOEXEC, 3);
}

// P91: one more view of {offset, bytes} of the arena (a ring frame handed to a shot): its slot stays pinned until it is freed.
ALLOC_JNI(jobject, arenaView)(JNIEnv* env, jclass, jint id, jlong offset, jint bytes) {
    uint8_t* p;
    {
        std::lock_guard<std::mutex> lock(arenaLock);
        const long i = findLocked(id);
        if (i < 0 || arenas[size_t(i)].released || offset < 0 || bytes <= 0 || size_t(offset) + size_t(bytes) > arenas[size_t(i)].size)
            return nullptr;
        p = arenas[size_t(i)].base + offset;
        arenas[size_t(i)].refs++;
        arenas[size_t(i)].slots[size_t(offset)]++;
        size_t& extent = arenas[size_t(i)].bytes[size_t(offset)];
        extent = std::max(extent, size_t(bytes));
    }
    jobject view = env->NewDirectByteBuffer(p, bytes);
    if (!view) {
        ArenaWork work;
        std::lock_guard<std::mutex> lock(arenaLock);
        const long i = containingLocked(p);
        if (i >= 0) slotUnrefLocked(size_t(i), size_t(p - arenas[size_t(i)].base));
        if (i >= 0 && --arenas[size_t(i)].refs == 0 && arenas[size_t(i)].released) unmapLocked(size_t(i), work);
    }
    return view;
}

// P91: the views left of the slot at {offset} (0: the ring may write it again).
ALLOC_JNI(jint, arenaSlotRefs)(JNIEnv*, jclass, jint id, jlong offset) {
    std::lock_guard<std::mutex> lock(arenaLock);
    const long i = findLocked(id);
    if (i < 0 || offset < 0) return -1;
    auto it = arenas[size_t(i)].slots.find(size_t(offset));
    return it == arenas[size_t(i)].slots.end() ? 0 : it->second;
}

// The shot hands out no more views; the arena goes once its views are freed.
ALLOC_JNI(void, arenaRelease)(JNIEnv*, jclass, jint id) {
    ArenaWork work;
    std::lock_guard<std::mutex> lock(arenaLock);
    const long i = findLocked(id);
    if (i < 0) return;
    arenas[size_t(i)].released = true;
    if (arenas[size_t(i)].refs <= 0) unmapLocked(size_t(i), work);
    else punchFreeLocked(size_t(i), work);
}

// P662: the pages of {offset, bytes} of a live arena allocated and mapped now (a fresh ZSL ring's part, on a thread of its own),
// so the camera thread's copies into it take no first-touch faults (2-4 s of 9-21 ms ring copies after every open, 3.2-3.5 ms
// once the slots were touched). fallocate gives the memfd its pages (zeroed by the kernel), MADV_POPULATE_WRITE (Linux 5.14)
// maps them writable without writing them; an older kernel gets a read of each page instead. Nothing is ever written, so a
// slot the camera is filling meanwhile keeps its bytes. The arena is pinned (one more reference) while it runs: a release
// meanwhile unmaps it after this returns. false: no such arena, released, out of bounds.
#ifndef MADV_POPULATE_WRITE
#define MADV_POPULATE_WRITE 23
#endif
ALLOC_JNI(jboolean, arenaPrefault)(JNIEnv*, jclass, jint id, jlong offset, jlong bytes) {
    int fd;
    uint8_t* p;
    size_t size;
    {
        std::lock_guard<std::mutex> lock(arenaLock);
        const long i = findLocked(id);
        if (i < 0 || offset < 0 || bytes <= 0) return JNI_FALSE;
        Arena& a = arenas[size_t(i)];
        if (a.released || a.adopted || size_t(offset) + size_t(bytes) > a.size) return JNI_FALSE;
        a.refs++;
        fd = a.fd; // closed only by unmapLocked, which waits for this reference
        p = a.base + offset;
        size = a.size;
    }
    const size_t lo = size_t(offset) & ~size_t(4095), hi = std::min(size, (size_t(offset) + size_t(bytes) + 4095) & ~size_t(4095));
    uint8_t* const from = p - (size_t(offset) - lo);
    fallocate(fd, 0, off_t(lo), off_t(hi - lo));
    if (madvise(from, hi - lo, MADV_POPULATE_WRITE) != 0) {
        volatile uint8_t sink = 0;
        for (size_t o = 0; o < hi - lo; o += 4096) sink = sink + static_cast<volatile uint8_t*>(from)[o];
        (void) sink;
    }
    ArenaWork work;
    std::lock_guard<std::mutex> lock(arenaLock);
    const long i = containingLocked(p);
    if (i >= 0 && --arenas[size_t(i)].refs == 0 && arenas[size_t(i)].released) unmapLocked(size_t(i), work);
    return JNI_TRUE;
}

// P371 (review B1): the pages of {offset, bytes} of an adopted arena go back (a hole in the memfd: the client read those trailers
// into buffers of their own), whole pages inside the range only, never a page a view holds. false: not an adopted arena, out of
// its bounds, or a view overlaps the range.
ALLOC_JNI(jboolean, arenaPunch)(JNIEnv*, jclass, jint id, jlong offset, jlong bytes) {
    ArenaWork work;
    std::lock_guard<std::mutex> lock(arenaLock);
    const long i = findLocked(id);
    if (i < 0 || offset < 0 || bytes <= 0) return JNI_FALSE;
    const Arena& a = arenas[size_t(i)];
    if (!a.adopted || size_t(offset) + size_t(bytes) > a.size) return JNI_FALSE;
    const size_t lo = (size_t(offset) + 4095) & ~size_t(4095), hi = (size_t(offset) + size_t(bytes)) & ~size_t(4095);
    for (const auto& v : a.slots) {
        const auto b = a.bytes.find(v.first);
        const size_t end = v.first + (b == a.bytes.end() ? size_t(1) : b->second);
        if (v.first < size_t(offset) + size_t(bytes) && end > size_t(offset)) return JNI_FALSE;
    }
    if (hi > lo) work.hole(a.fd, lo, hi - lo);
    return JNI_TRUE;
}

static float mosaicSrKernel(float x, int kernel) {
    x = std::fabs(x);
    if (kernel == 0) return x < 1.0f ? 1.0f - x : 0.0f;
    if (kernel == 1) {
        // Catmull-Rom bicubic: sharp but still well behaved on a CFA plane.
        if (x < 1.0f) return 1.5f*x*x*x - 2.5f*x*x + 1.0f;
        if (x < 2.0f) return -0.5f*x*x*x + 2.5f*x*x - 4.0f*x + 2.0f;
        return 0.0f;
    }
    // Windowed sinc, radius 2. Negative lobes recover more high-frequency
    // detail; the final sample is clamped to the 16-bit DNG domain.
    if (x >= 2.0f) return 0.0f;
    if (x < 1.0e-5f) return 1.0f;
    constexpr float pi = 3.14159265358979323846f;
    return (std::sin(pi*x) / (pi*x)) * (std::sin(pi*x/2.0f) / (pi*x/2.0f));
}

static inline int clampMosaicIndex(int value, int count) {
    return std::max(0, std::min(value, count - 1));
}

ALLOC_JNI(jobject, reconstructMosaicSr)(
        JNIEnv *env, jclass, jobject originBuffer, jint inputWidth, jint inputHeight,
        jint outputWidth, jint outputHeight, jint kernel) {
    auto *input = static_cast<const uint16_t*>(env->GetDirectBufferAddress(originBuffer));
    jlong inputCapacity = env->GetDirectBufferCapacity(originBuffer);
    if (input == nullptr || inputWidth < 2 || inputHeight < 2 || outputWidth < 2 || outputHeight < 2
            || inputCapacity < static_cast<jlong>(inputWidth) * inputHeight * 2) {
        ALLOC_LOG("Invalid RAW SR mosaic input");
        return nullptr;
    }
    outputWidth &= ~1;
    outputHeight &= ~1;
    size_t pixelCount = static_cast<size_t>(outputWidth) * outputHeight;
    auto *output = static_cast<uint16_t*>(malloc(pixelCount * sizeof(uint16_t)));
    if (output == nullptr) return nullptr;

    const float scaleX = static_cast<float>(outputWidth) / inputWidth;
    const float scaleY = static_cast<float>(outputHeight) / inputHeight;
    const int radius = kernel == 0 ? 1 : 2;
    unsigned workerCount = std::max(1u, std::min(4u, std::thread::hardware_concurrency()));
    std::vector<std::thread> workers;
    workers.reserve(workerCount);
    for (unsigned worker = 0; worker < workerCount; ++worker) {
        workers.emplace_back([=]() {
            for (int y = static_cast<int>(worker); y < outputHeight; y += static_cast<int>(workerCount)) {
                int phaseY = y & 1;
                float sourceY = (y + 0.5f) / scaleY - 0.5f;
                float gridY = (sourceY - phaseY) * 0.5f;
                int baseY = static_cast<int>(std::floor(gridY));
                int gridHeight = (inputHeight - phaseY + 1) / 2;
                for (int x = 0; x < outputWidth; ++x) {
                    int phaseX = x & 1;
                    float sourceX = (x + 0.5f) / scaleX - 0.5f;
                    float gridX = (sourceX - phaseX) * 0.5f;
                    int baseX = static_cast<int>(std::floor(gridX));
                    int gridWidth = (inputWidth - phaseX + 1) / 2;
                    float sum = 0.0f;
                    float weightSum = 0.0f;
                    int first = kernel == 0 ? 0 : -1;
                    int last = radius;
                    for (int ky = first; ky <= last; ++ky) {
                        int gy = clampMosaicIndex(baseY + ky, gridHeight);
                        float wy = mosaicSrKernel(gridY - (baseY + ky), kernel);
                        for (int kx = first; kx <= last; ++kx) {
                            int gx = clampMosaicIndex(baseX + kx, gridWidth);
                            float wx = mosaicSrKernel(gridX - (baseX + kx), kernel);
                            float w = wx * wy;
                            int sx = phaseX + 2 * gx;
                            int sy = phaseY + 2 * gy;
                            sum += static_cast<float>(input[static_cast<size_t>(sy) * inputWidth + sx]) * w;
                            weightSum += w;
                        }
                    }
                    float value = std::fabs(weightSum) > 1.0e-8f ? sum / weightSum : 0.0f;
                    output[static_cast<size_t>(y) * outputWidth + x] = static_cast<uint16_t>(
                            std::lround(std::max(0.0f, std::min(65535.0f, value))));
                }
            }
        });
    }
    for (auto &worker : workers) worker.join();
    size_t byteCount = pixelCount * sizeof(uint16_t);
    jobject result = env->NewDirectByteBuffer(output, static_cast<jlong>(byteCount));
    if (result == nullptr) {
        free(output);
        return nullptr;
    }
    memoryCount += static_cast<long>(byteCount);
    ALLOC_LOG("RAW SR mosaic reconstructed: %dx%d -> %dx%d, kernel=%d",
         inputWidth, inputHeight, outputWidth, outputHeight, kernel);
    return result;
}

// --- plain native buffers and the RAW unpack / bin copies (util/Allocator.java) ------------------------------------------

namespace {

// A direct buffer over `bytes` of fresh malloc memory (counted in memoryCount), or null - logged as `what` - when the
// size is negative or above what a direct buffer holds (INT32_MAX), malloc fails or the VM refuses the buffer (it would
// abort on a null address or a negative capacity, and the memory would leak).
jobject freshBuffer(JNIEnv* env, long long bytes, const char* what, void** memory) {
    *memory = nullptr;
    if (bytes < 0 || bytes > INT32_MAX) {
        ALLOC_LOG("%s: %lld bytes refused", what, bytes);
        return nullptr;
    }
    void* m = malloc(size_t(bytes));
    if (m == nullptr && bytes > 0) {
        ALLOC_LOG("%s: malloc of %lld bytes failed", what, bytes);
        return nullptr;
    }
    jobject buffer = env->NewDirectByteBuffer(m, jlong(bytes));
    if (buffer == nullptr) {
        free(m);
        ALLOC_LOG("%s: no direct buffer over %lld bytes", what, bytes);
        return nullptr;
    }
    memoryCount += long(bytes);
    *memory = m;
    return buffer;
}

long long pixelBytes(int width, int height) { return (long long)std::max(width, 0) * std::max(height, 0) * 2; }

// Camera2 packed RAW10 / RAW12 (ImageFormat 0x25 / 0x26) and the MIPI RAW14 codes of RawUnpack (0x7E04 as stored, 0x7E14
// shifted down into the 10-bit range the metadata declares).
constexpr int kRaw10 = 0x25, kRaw12 = 0x26, kRaw14 = 0x7E04, kRaw14To10 = 0x7E14;

}  // namespace

static bool isRaw14(int format) { return format == kRaw14 || format == kRaw14To10; }

// RAW10: 4 samples in 5 bytes - their high 8 bits, then one byte of the four low pairs (sample k in bits 2k+1..2k). The
// whole groups of the row (4 * floor(width / 4) samples), each shifted by the session's RAW10 shift; the count written.
// P662: the scalar form (the NEON path's tail and its reference: tools/check_raw_unpack.cpp)
static int decodeRaw10RowScalar(const uint8_t* row, uint16_t* out, int width, int shift) {
    const int groups = std::max(width, 0) / 4;
    for (int g = 0; g < groups; ++g, row += 5, out += 4) {
        const unsigned low = row[4];
        for (int k = 0; k < 4; ++k) out[k] = uint16_t(((row[k] << 2) | ((low >> (2 * k)) & 3u)) << shift);
    }
    return groups * 4;
}

// RAW12: 2 samples in 3 bytes - their high 8 bits, then the low nibbles (the first sample's in the low half).
static int decodeRaw12RowScalar(const uint8_t* row, uint16_t* out, int width, int shift) {
    const int pairs = std::max(width, 0) / 2;
    for (int p = 0; p < pairs; ++p, row += 3, out += 2) {
        out[0] = uint16_t(((row[0] << 4) | (row[2] & 0x0F)) << shift);
        out[1] = uint16_t(((row[1] << 4) | (row[2] >> 4)) << shift);
    }
    return pairs * 2;
}

#if defined(__aarch64__)
// P662 (speed: the ZSL ring's unpack of every RAW10 / RAW12 frame on the camera thread): 16 samples a step with NEON - the 20
// (RAW10) / 24 (RAW12) bytes of 4 groups / 8 pairs read as two overlapping 16-byte loads (never past those bytes), the high bytes
// and each sample's low byte gathered by a table lookup, the low bits moved down by a per-lane shift: exactly the integers of the
// scalar form (its tail finishes the row)
static int decodeRaw10RowNeon(const uint8_t* row, uint16_t* out, int width, int shift) {
    const int groups = std::max(width, 0) / 4;
    // table {row[0..15], row[4..19]}: byte p < 16 at p, byte p >= 16 at p + 12
    static const uint8_t kHi[16] = {0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12, 13, 15, 28, 29, 30};
    static const uint8_t kLo[16] = {4, 4, 4, 4, 9, 9, 9, 9, 14, 14, 14, 14, 31, 31, 31, 31};
    static const int16_t kDown[8] = {0, -2, -4, -6, 0, -2, -4, -6};
    const uint8x16_t hiIdx = vld1q_u8(kHi), loIdx = vld1q_u8(kLo);
    const int16x8_t down = vld1q_s16(kDown), up = vdupq_n_s16(int16_t(shift));
    const uint16x8_t three = vdupq_n_u16(3);
    int g = 0;
    for (; g + 4 <= groups; g += 4, row += 20, out += 16) {
        uint8x16x2_t t;
        t.val[0] = vld1q_u8(row);
        t.val[1] = vld1q_u8(row + 4);
        const uint8x16_t hi = vqtbl2q_u8(t, hiIdx), lo = vqtbl2q_u8(t, loIdx);
        const uint16x8_t h0 = vshlq_n_u16(vmovl_u8(vget_low_u8(hi)), 2), h1 = vshlq_n_u16(vmovl_u8(vget_high_u8(hi)), 2);
        const uint16x8_t l0 = vandq_u16(vshlq_u16(vmovl_u8(vget_low_u8(lo)), down), three);
        const uint16x8_t l1 = vandq_u16(vshlq_u16(vmovl_u8(vget_high_u8(lo)), down), three);
        vst1q_u16(out, vshlq_u16(vorrq_u16(h0, l0), up));
        vst1q_u16(out + 8, vshlq_u16(vorrq_u16(h1, l1), up));
    }
    return g * 4 + decodeRaw10RowScalar(row, out, (groups - g) * 4, shift);
}

static int decodeRaw12RowNeon(const uint8_t* row, uint16_t* out, int width, int shift) {
    const int pairs = std::max(width, 0) / 2;
    // table {row[0..15], row[8..23]}: byte p < 16 at p, byte p >= 16 at p + 8
    static const uint8_t kHi[16] = {0, 1, 3, 4, 6, 7, 9, 10, 12, 13, 15, 24, 26, 27, 29, 30};
    static const uint8_t kLo[16] = {2, 2, 5, 5, 8, 8, 11, 11, 14, 14, 25, 25, 28, 28, 31, 31};
    static const int16_t kDown[8] = {0, -4, 0, -4, 0, -4, 0, -4};
    const uint8x16_t hiIdx = vld1q_u8(kHi), loIdx = vld1q_u8(kLo);
    const int16x8_t down = vld1q_s16(kDown), up = vdupq_n_s16(int16_t(shift));
    const uint16x8_t nibble = vdupq_n_u16(0x0F);
    int p = 0;
    for (; p + 8 <= pairs; p += 8, row += 24, out += 16) {
        uint8x16x2_t t;
        t.val[0] = vld1q_u8(row);
        t.val[1] = vld1q_u8(row + 8);
        const uint8x16_t hi = vqtbl2q_u8(t, hiIdx), lo = vqtbl2q_u8(t, loIdx);
        const uint16x8_t h0 = vshlq_n_u16(vmovl_u8(vget_low_u8(hi)), 4), h1 = vshlq_n_u16(vmovl_u8(vget_high_u8(hi)), 4);
        const uint16x8_t l0 = vandq_u16(vshlq_u16(vmovl_u8(vget_low_u8(lo)), down), nibble);
        const uint16x8_t l1 = vandq_u16(vshlq_u16(vmovl_u8(vget_high_u8(lo)), down), nibble);
        vst1q_u16(out, vshlq_u16(vorrq_u16(h0, l0), up));
        vst1q_u16(out + 8, vshlq_u16(vorrq_u16(h1, l1), up));
    }
    return p * 2 + decodeRaw12RowScalar(row, out, (pairs - p) * 2, shift);
}
#endif

static int decodeRaw10Row(const uint8_t* row, uint16_t* out, int width) {
    const int shift = gRaw10Shift.load(std::memory_order_relaxed);
#if defined(__aarch64__)
    return decodeRaw10RowNeon(row, out, width, shift);
#else
    return decodeRaw10RowScalar(row, out, width, shift);
#endif
}

static int decodeRaw12Row(const uint8_t* row, uint16_t* out, int width) {
    const int shift = gRaw12Shift.load(std::memory_order_relaxed);
#if defined(__aarch64__)
    return decodeRaw12RowNeon(row, out, width, shift);
#else
    return decodeRaw12RowScalar(row, out, width, shift);
#endif
}

// P72: packed MIPI RAW14 (Xiaomi 17 Ultra tele in in-sensor zoom, mode 9: 7140 bytes per 4080-wide row behind RAW_SENSOR):
// 4 samples in 7 bytes - their high 8 bits, then 24 bits of the four low 6-bit parts in sample order, least significant
// bits first.
static void decodeRaw14Row(const uint8_t* row, uint16_t* out, int width, int shift) {
    for (int px = 0; px + 3 < width; px += 4, row += 7) {
        const uint32_t low = uint32_t(row[4]) | uint32_t(row[5]) << 8 | uint32_t(row[6]) << 16;
        for (int k = 0; k < 4; ++k) out[px + k] = uint16_t(((row[k] << 6) | ((low >> (6 * k)) & 0x3Fu)) >> shift);
    }
}

static long packedRowBytes(int format, int width) {
    const long bits = format == kRaw10 ? 10 : format == kRaw12 ? 12 : isRaw14(format) ? 14 : 0;
    return long(width) * bits / 8;
}

static void decodePackedRow(int format, const uint8_t* in, uint16_t* out, int width) {
    if (format == kRaw10) decodeRaw10Row(in, out, width);
    else if (format == kRaw12) decodeRaw12Row(in, out, width);
    else decodeRaw14Row(in, out, width, format == kRaw14To10 ? 4 : 0);
}

namespace {

// Bayer-aware 2x2 sum binning: output site (x, y) sums the four input sites of its colour around it - rows r and r + 2
// with r = 4 * (y / 2) + y % 2 (the same for columns), the second one clamped to the last row / column - saturated at
// 65535. `rowOf(y)` gives input row y; output rows go in pairs, so rowOf is asked for rows 4j .. 4j + 3 of pair j only.
template <class RowOf>
void binSums(RowOf&& rowOf, int srcWidth, int srcHeight, uint16_t* out, int outWidth, int outHeight) {
    for (int oy = 0; oy < outHeight; ++oy) {
        const int r0 = (oy / 2) * 4 + oy % 2, r1 = std::min(r0 + 2, srcHeight - 1);
        const uint16_t* a = rowOf(r0);
        const uint16_t* b = rowOf(r1);
        uint16_t* o = out + size_t(oy) * size_t(outWidth);
        for (int ox = 0; ox < outWidth; ++ox) {
            const int c0 = (ox / 2) * 4 + ox % 2, c1 = std::min(c0 + 2, srcWidth - 1);
            const uint32_t sum = uint32_t(a[c0]) + a[c1] + b[c0] + b[c1];
            o[ox] = uint16_t(std::min(sum, 65535u));
        }
    }
}

// P659: the packed frame is unpacked four rows at a time into a small window (a pair of output rows reads input rows
// 4j .. 4j + 3 only) instead of one whole-frame uint16 copy - 100 MB less native memory at 50 MP. Samples a row does not
// decode (a width that is no whole group) read as 0.
jobject unpackAndBin(JNIEnv* env, jint capacity, jobject origin, jint width, jint rowStride, jint offset, bool raw12,
                     const char* what) {
    const int height = rowStride != 0 ? capacity / rowStride : 0;
    const int outWidth = width / 2, outHeight = height / 2;
    auto* src = origin != nullptr ? static_cast<const uint8_t*>(env->GetDirectBufferAddress(origin)) : nullptr;
    void* memory = nullptr;
    jobject buffer = src != nullptr ? freshBuffer(env, pixelBytes(outWidth, outHeight), what, &memory) : nullptr;
    if (buffer == nullptr) return nullptr;
    src += offset;
    std::vector<uint16_t> window(size_t(4) * size_t(std::max(width, 0)), 0);
    int windowStart = -1;
    auto rowOf = [&](int y) -> const uint16_t* {
        const int start = y & ~3;
        if (start != windowStart) {
            windowStart = start;
            for (int k = 0; k < 4 && start + k < height; ++k) {
                const uint8_t* packed = src + size_t(start + k) * size_t(rowStride);
                uint16_t* row = window.data() + size_t(k) * size_t(width);
                if (raw12) decodeRaw12Row(packed, row, width);
                else decodeRaw10Row(packed, row, width);
            }
        }
        return window.data() + size_t(y - start) * size_t(width);
    };
    binSums(rowOf, width, height, static_cast<uint16_t*>(memory), outWidth, outHeight);
    ALLOC_LOG("%s: %dx%d -> %dx%d, %ld MB counted", what, width, height, outWidth, outHeight, long(memoryCount / (1 << 20)));
    return buffer;
}

// The rows of a packed frame unpacked one after another, `pitch` samples apart.
jobject unpackRows(JNIEnv* env, jint capacity, jobject origin, jint width, jint rowStride, jint offset, bool raw12,
                   const char* what) {
    const int height = rowStride != 0 ? capacity / rowStride : 0;
    auto* src = origin != nullptr ? static_cast<const uint8_t*>(env->GetDirectBufferAddress(origin)) : nullptr;
    void* memory = nullptr;
    jobject buffer = src != nullptr ? freshBuffer(env, pixelBytes(width, height), what, &memory) : nullptr;
    if (buffer == nullptr) return nullptr;
    src += offset;
    auto* out = static_cast<uint16_t*>(memory);
    for (int y = 0; y < height; ++y) {
        const uint8_t* packed = src + size_t(y) * size_t(rowStride);
        // RAW10 rows follow each other without gaps (a width that is no whole group leaves the tail unwritten); RAW12 rows
        // keep their width apart
        if (raw12) decodeRaw12Row(packed, out + size_t(y) * size_t(width), width);
        else out += decodeRaw10Row(packed, out, width);
    }
    ALLOC_LOG("%s: %dx%d, %ld MB counted", what, width, height, long(memoryCount / (1 << 20)));
    return buffer;
}

}  // namespace

ALLOC_JNI(jobject, allocate)(JNIEnv* env, jclass, jint capacity) {
    void* memory = nullptr;
    return freshBuffer(env, capacity, "allocate", &memory);
}

ALLOC_JNI(jobject, allocateAndCopy)(JNIEnv* env, jclass, jint capacity, jobject origin, jint offset) {
    void* memory = nullptr;
    jobject buffer = freshBuffer(env, capacity, "allocateAndCopy", &memory);
    if (buffer == nullptr) return nullptr;
    // P610 (speed): on 4 threads from 32 MB (copyBytes, P200) - the reference RAW out of the shot arena before the post
    // pipeline (382 MB at 200 MP). Without an origin the buffer comes back as allocated.
    auto* src = origin != nullptr ? static_cast<const uint8_t*>(env->GetDirectBufferAddress(origin)) : nullptr;
    if (src != nullptr) copyBytes(static_cast<uint8_t*>(memory), src + offset, size_t(capacity));
    else ALLOC_LOG("allocateAndCopy: origin is no direct buffer, nothing copied");
    return buffer;
}

ALLOC_JNI(jobject, allocateAndCopyConvert)(JNIEnv* env, jclass, jint capacity, jobject origin, jint width, jint rowStride,
                                           jint offset) {
    return unpackRows(env, capacity, origin, width, rowStride, offset, false, "allocateAndCopyConvert");
}

ALLOC_JNI(jobject, allocateAndCopyConvert12)(JNIEnv* env, jclass, jint capacity, jobject origin, jint width, jint rowStride,
                                             jint offset) {
    return unpackRows(env, capacity, origin, width, rowStride, offset, true, "allocateAndCopyConvert12");
}

ALLOC_JNI(jobject, allocateAndCopyConvertBinning)(JNIEnv* env, jclass, jint capacity, jobject origin, jint width,
                                                  jint rowStride, jint offset) {
    return unpackAndBin(env, capacity, origin, width, rowStride, offset, false, "allocateAndCopyConvertBinning");
}

ALLOC_JNI(jobject, allocateAndCopyConvert12Binning)(JNIEnv* env, jclass, jint capacity, jobject origin, jint width,
                                                    jint rowStride, jint offset) {
    return unpackAndBin(env, capacity, origin, width, rowStride, offset, true, "allocateAndCopyConvert12Binning");
}

// RAW16 binned straight from its padded rows (P659: no whole-frame de-strided copy first).
ALLOC_JNI(jobject, allocateAndCopyBinning)(JNIEnv* env, jclass, jint, jobject origin, jint width, jint height,
                                           jint rowStride) {
    auto* src = origin != nullptr ? static_cast<const uint8_t*>(env->GetDirectBufferAddress(origin)) : nullptr;
    void* memory = nullptr;
    jobject buffer = src != nullptr ? freshBuffer(env, pixelBytes(width / 2, height / 2), "allocateAndCopyBinning", &memory)
                                    : nullptr;
    if (buffer == nullptr) return nullptr;
    auto rowOf = [&](int y) { return reinterpret_cast<const uint16_t*>(src + size_t(y) * size_t(rowStride)); };
    binSums(rowOf, width, height, static_cast<uint16_t*>(memory), width / 2, height / 2);
    return buffer;
}

bool scamAhbFreeView(const void* ptr); // P482: scam-ahb.cpp (the merge RGB in the app's hardware buffer)

// Frees a buffer of this library: a hardware-buffer view is unlocked (P482), an arena view drops one reference of its slot
// and arena (P30; the slot's pages go once its last view does, P179), anything else was malloc'ed here.
ALLOC_JNI(void, free)(JNIEnv* env, jclass, jobject buffer) {
    void* p = buffer != nullptr ? env->GetDirectBufferAddress(buffer) : nullptr;
    if (p == nullptr) {
        ALLOC_LOG("free: no direct buffer");
        return;
    }
    const long capacity = long(env->GetDirectBufferCapacity(buffer));
    if (scamAhbFreeView(p)) return;
    {
        ArenaWork work;
        std::lock_guard<std::mutex> lock(arenaLock);
        const long i = containingLocked(p);
        if (i >= 0) {
            Arena& a = arenas[size_t(i)];
            const size_t offset = size_t(static_cast<uint8_t*>(p) - a.base);
            if (slotUnrefLocked(size_t(i), offset) && capacity > 0) punchLocked(size_t(i), offset, size_t(capacity), work);
            if (--a.refs <= 0 && a.released) unmapLocked(size_t(i), work);
            return;
        }
    }
    ::free(p);
    memoryCount -= capacity;
}

ALLOC_JNI(jlong, getMemoryCount)(JNIEnv*, jclass) { return jlong(memoryCount.load()); }

// RawUnpack: packed RAW10 / RAW12 rows to uint16 into the caller's buffer (RAW viewfinder and mosaic measurement on RAW10 /
// RAW12 streams). Rows split over up to four threads; the bit layout of decodeRaw10Row / decodeRaw12Row.
UNPACK_JNI(jboolean, nativeUnpack)(JNIEnv* env, jclass, jobject src, jint format, jint width,
                                                                jint height, jint rowStride, jobject dst) {
    auto* in = static_cast<const uint8_t*>(src ? env->GetDirectBufferAddress(src) : nullptr);
    auto* out = static_cast<uint16_t*>(dst ? env->GetDirectBufferAddress(dst) : nullptr);
    if (!in || !out || width < 4 || height < 1 || (format != 0x25 && format != 0x26 && !isRaw14(format))) return JNI_FALSE;
    if (isRaw14(format) && (width & 3) != 0) return JNI_FALSE;
    const long rowBytes = packedRowBytes(format, width);
    if (rowStride < rowBytes || env->GetDirectBufferCapacity(src) < long(height - 1) * rowStride + rowBytes
            || env->GetDirectBufferCapacity(dst) < long(width) * height * 2) return JNI_FALSE;
    const int threads = std::max(1, std::min(4, int(std::thread::hardware_concurrency())));
    auto rows = [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            decodePackedRow(format, in + size_t(y) * rowStride, out + size_t(y) * width, width);
        }
    };
    std::vector<std::thread> pool;
    for (int t = 1; t < threads; ++t) pool.emplace_back(rows, int(int64_t(height) * t / threads), int(int64_t(height) * (t + 1) / threads));
    rows(0, height / threads);
    for (auto& t : pool) t.join();
    return JNI_TRUE;
}

// P30: a packed RAW10 / RAW12 frame unpacked straight into its arena slot (uint16, width per row), so a RAW10 stream
// (OPPO Find X8 Ultra default) also reaches the worker without the transport copy.
ALLOC_JNI(jobject, arenaCopyUnpack)(JNIEnv* env, jclass, jint id, jlong offset, jobject origin,
                                                                    jint originOffset, jint format, jint width, jint rowStride,
                                                                    jint height) {
    auto* src = origin ? static_cast<const uint8_t*>(env->GetDirectBufferAddress(origin)) : nullptr;
    const jlong srcCapacity = origin ? env->GetDirectBufferCapacity(origin) : 0;
    if (!src || (format != 0x25 && format != 0x26 && !isRaw14(format)) || width < 4 || height < 1 || originOffset < 0) return nullptr;
    if (isRaw14(format) && (width & 3) != 0) return nullptr;
    const long rowBytes = packedRowBytes(format, width);
    if (rowStride < rowBytes || long(originOffset) + long(height - 1) * rowStride + rowBytes > srcCapacity) return nullptr;
    const size_t bytes = size_t(width) * size_t(height) * 2;
    if (bytes > size_t(INT32_MAX)) return nullptr; // a direct buffer view holds at most 2 GiB (ART aborts above it)
    uint8_t* dst;
    {
        std::lock_guard<std::mutex> lock(arenaLock);
        const long i = findLocked(id);
        if (i < 0 || arenas[size_t(i)].released || offset < 0 || size_t(offset) + bytes > arenas[size_t(i)].size) return nullptr;
        dst = arenas[size_t(i)].base + offset;
        arenas[size_t(i)].refs++;
        arenas[size_t(i)].slots[size_t(offset)]++;
        arenas[size_t(i)].bytes[size_t(offset)] = bytes;
    }
    auto* out = reinterpret_cast<uint16_t*>(dst);
    const uint8_t* in = src + originOffset;
    const int threads = std::max(1, std::min(4, int(std::thread::hardware_concurrency())));
    auto rows = [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            decodePackedRow(format, in + size_t(y) * rowStride, out + size_t(y) * width, width);
        }
    };
    std::vector<std::thread> pool;
    for (int t = 1; t < threads; ++t) pool.emplace_back(rows, int(int64_t(height) * t / threads), int(int64_t(height) * (t + 1) / threads));
    rows(0, height / threads);
    for (auto& t : pool) t.join();
    jobject view = env->NewDirectByteBuffer(dst, jlong(bytes));
    if (!view) {
        ArenaWork work;
        std::lock_guard<std::mutex> lock(arenaLock);
        const long i = containingLocked(dst);
        if (i >= 0) slotUnrefLocked(size_t(i), size_t(dst - arenas[size_t(i)].base));
        if (i >= 0 && --arenas[size_t(i)].refs == 0 && arenas[size_t(i)].released) unmapLocked(size_t(i), work);
    }
    return view;
}

// P609 (processing/TetraBin, the OnePlus 16 main's 50 MP from its 200 MP row): each 2x2 box of the source rows averaged into one
// site ((sum + 2) >> 2: the levels stay) - a Tetra frame (4x4 sites of one colour) becomes the sensor's own Quad of half its sides,
// in the same colour order; whole 8x8 colour periods only (out = side / 8 * 4). RAW16 rows are read in place, packed RAW10 / RAW12
// rows are unpacked two at a time first. Rows split over up to four threads. The caller's buffers; false when either is too small.
static bool binBlocks2Rows(uint16_t* out, size_t outCapacity, const uint8_t* in, size_t inCapacity, int format, int width, int height,
                           int rowStride) {
    if (!out || !in || width < 8 || height < 8 || rowStride <= 0) return false;
    const bool packed = format == 0x25 || format == 0x26;
    if (!packed && format != 0x20) return false;
    const int ow = width / 8 * 4, oh = height / 8 * 4;
    const long rowBytes = packed ? packedRowBytes(format, width) : long(width) * 2;
    if (rowStride < rowBytes) return false;
    if (size_t(2 * oh - 1) * size_t(rowStride) + size_t(rowBytes) > inCapacity) return false;
    if (size_t(ow) * size_t(oh) * 2 > outCapacity) return false;
    const int threads = std::max(1, std::min(4, int(std::thread::hardware_concurrency())));
    auto rows = [&](int y0, int y1) {
        std::vector<uint16_t> a(packed ? size_t(width) : 0), b(packed ? size_t(width) : 0);
        for (int y = y0; y < y1; ++y) {
            const uint8_t* p0 = in + size_t(2 * y) * size_t(rowStride);
            const uint8_t* p1 = p0 + rowStride;
            uint16_t* o = out + size_t(y) * size_t(ow);
            if (packed) {
                decodePackedRow(format, p0, a.data(), width);
                decodePackedRow(format, p1, b.data(), width);
                const uint16_t *r0 = a.data(), *r1 = b.data();
                for (int x = 0; x < ow; ++x)
                    o[x] = uint16_t((uint32_t(r0[2 * x]) + r0[2 * x + 1] + r1[2 * x] + r1[2 * x + 1] + 2u) >> 2);
                continue;
            }
            // RAW16: the samples read bytewise (a buffer or stride need not be 2-byte aligned)
            uint16_t s0[2], s1[2];
            for (int x = 0; x < ow; ++x) {
                memcpy(s0, p0 + 4 * size_t(x), 4); memcpy(s1, p1 + 4 * size_t(x), 4);
                o[x] = uint16_t((uint32_t(s0[0]) + s0[1] + s1[0] + s1[1] + 2u) >> 2);
            }
        }
    };
    std::vector<std::thread> pool;
    for (int t = 1; t < threads; ++t) pool.emplace_back(rows, int(int64_t(oh) * t / threads), int(int64_t(oh) * (t + 1) / threads));
    rows(0, oh / threads);
    for (auto& t : pool) t.join();
    return true;
}

ALLOC_JNI(jboolean, binBlocks2)(JNIEnv* env, jclass, jobject dst, jobject origin, jint offset, jint format,
                                                             jint width, jint height, jint rowStride) {
    auto* out = dst ? static_cast<uint16_t*>(env->GetDirectBufferAddress(dst)) : nullptr;
    auto* in = origin ? static_cast<const uint8_t*>(env->GetDirectBufferAddress(origin)) : nullptr;
    if (!out || !in || offset < 0) return JNI_FALSE;
    const jlong outCapacity = env->GetDirectBufferCapacity(dst), inCapacity = env->GetDirectBufferCapacity(origin);
    if (outCapacity <= 0 || inCapacity <= offset) return JNI_FALSE;
    return binBlocks2Rows(out, size_t(outCapacity), in + offset, size_t(inCapacity - offset), format, width, height, rowStride)
            ? JNI_TRUE : JNI_FALSE;
}
