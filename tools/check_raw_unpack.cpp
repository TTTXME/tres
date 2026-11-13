// P662 (speed): allocator.cpp unpacks every packed RAW10 / RAW12 row (the ZSL ring on RAW10 / RAW12 sessions, RawUnpack, the
// binning paths) with NEON on aarch64, 16 samples a step, the scalar loop finishing the row. Checked against the scalar
// unpack as it was (written out again here): random rows of every width 0..300 and a few long ones, every RAW10 shift 0..6
// and RAW12 shift 0..4, rows at every byte alignment, the samples past the decoded count untouched, the count returned; rows
// that end at an inaccessible page (no read past a row's bytes).
//
// Host build (CI, the scalar path; jni.h from the NDK as for check_allocator_adopt):
//   g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined -I/tmp/native-engine-host -Itools/host-include tools/check_raw_unpack.cpp
// The NEON path (aarch64; run under qemu-aarch64-static where there is one):
//   $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang++ -static -std=c++17 -O2 tools/check_raw_unpack.cpp
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <random>
#include <string>
#include <vector>
#include <sys/mman.h>
#include <unistd.h>

#include "../app/src/main/cpp/allocator.cpp"

// P482: scam-ahb.cpp (Android hardware buffers) is not built here; no pointer is ever a hardware buffer view
bool scamAhbFreeView(const void*) { return false; }
#if defined(__ANDROID__)
// a static aarch64 build has no liblog: the allocator's log lines go nowhere
extern "C" int __android_log_print(int, const char*, const char*, ...) { return 0; }
#endif

namespace {
int failures = 0;
void expect(bool ok, const std::string& what) {
    if (!ok) { if (failures < 20) std::printf("FAIL: %s\n", what.c_str()); failures++; }
}

int raw10Reference(const uint8_t* row, uint16_t* out, int width, int shift) {
    const int groups = std::max(width, 0) / 4;
    for (int g = 0; g < groups; ++g, row += 5, out += 4) {
        const unsigned low = row[4];
        for (int k = 0; k < 4; ++k) out[k] = uint16_t(((row[k] << 2) | ((low >> (2 * k)) & 3u)) << shift);
    }
    return groups * 4;
}

int raw12Reference(const uint8_t* row, uint16_t* out, int width, int shift) {
    const int pairs = std::max(width, 0) / 2;
    for (int p = 0; p < pairs; ++p, row += 3, out += 2) {
        out[0] = uint16_t(((row[0] << 4) | (row[2] & 0x0F)) << shift);
        out[1] = uint16_t(((row[1] << 4) | (row[2] >> 4)) << shift);
    }
    return pairs * 2;
}

void check(bool raw12, int width, int shift, int align, std::mt19937& rng) {
    const size_t bytes = raw12 ? size_t(width / 2) * 3 : size_t(width / 4) * 5;
    // the row's bytes exactly (ASan sees a read past them), at `align` bytes into its allocation
    std::vector<uint8_t> storage(bytes + size_t(align));
    for (auto& b : storage) b = uint8_t(rng());
    const uint8_t* row = storage.data() + align;
    std::vector<uint16_t> want(size_t(width) + 8, 0xBEEF), got(size_t(width) + 8, 0xBEEF);
    int n;
    if (raw12) {
        gRaw12Shift.store(shift);
        n = decodeRaw12Row(row, got.data(), width);
        expect(n == raw12Reference(row, want.data(), width, shift), "raw12 count");
    } else {
        gRaw10Shift.store(shift);
        n = decodeRaw10Row(row, got.data(), width);
        expect(n == raw10Reference(row, want.data(), width, shift), "raw10 count");
    }
    expect(got == want, std::string(raw12 ? "RAW12" : "RAW10") + " width " + std::to_string(width) + " shift " + std::to_string(shift)
            + " align " + std::to_string(align) + ": the samples (and nothing past them)");
}
// a row that ends where an inaccessible page starts (any read past its bytes faults; also without ASan, the aarch64 build)
void guarded(bool raw12, int width) {
    const size_t page = size_t(sysconf(_SC_PAGESIZE)), bytes = raw12 ? size_t(width / 2) * 3 : size_t(width / 4) * 5;
    const size_t span = (bytes + page - 1) / page * page;
    auto* m = static_cast<uint8_t*>(mmap(nullptr, span + page, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    if (m == MAP_FAILED) { expect(false, "guard mapping"); return; }
    mprotect(m + span, page, PROT_NONE);
    uint8_t* row = m + span - bytes;
    for (size_t i = 0; i < bytes; ++i) row[i] = uint8_t(i * 37 + 11);
    std::vector<uint16_t> want(static_cast<size_t>(width)), got(static_cast<size_t>(width));
    if (raw12) { decodeRaw12Row(row, got.data(), width); raw12Reference(row, want.data(), width, 0); }
    else { decodeRaw10Row(row, got.data(), width); raw10Reference(row, want.data(), width, 0); }
    expect(got == want, std::string(raw12 ? "RAW12" : "RAW10") + " width " + std::to_string(width) + " at a guard page");
    munmap(m, span + page);
}
}  // namespace

int main() {
    std::mt19937 rng(662);
    long rows = 0;
    for (int width = 0; width <= 300; ++width)
        for (int align = 0; align < 4; ++align) {
            for (int shift = 0; shift <= 6; ++shift, ++rows) check(false, width, shift, align, rng);
            for (int shift = 0; shift <= 4; ++shift, ++rows) check(true, width, shift, align, rng);
        }
    for (int width : {4080, 4096, 4098, 8160, 8192, 8190, 12288})
        for (int align = 0; align < 16; align += 5) {
            check(false, width, 0, align, rng); check(false, width, 2, align, rng);
            check(true, width, 0, align, rng); check(true, width, 4, align, rng);
            rows += 4;
        }
    gRaw10Shift.store(0); gRaw12Shift.store(0);
    for (int width = 0; width <= 100; ++width) { guarded(false, width); guarded(true, width); rows += 2; }
    guarded(false, 4080); guarded(true, 4080); rows += 2;
#if defined(__aarch64__)
    const char* path = "NEON";
#else
    const char* path = "scalar";
#endif
    if (failures) { std::printf("check_raw_unpack: %d failures (%s path)\n", failures, path); return 1; }
    std::printf("check_raw_unpack OK: %ld rows, the %s path equals the scalar unpack\n", rows, path);
    return 0;
}
