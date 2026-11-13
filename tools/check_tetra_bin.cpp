// P609 (the OnePlus 16 main's 50 MP from its 200 MP row): allocator.cpp Allocator.binBlocks2 averages each 2x2 box of a Tetra frame
// (4x4 sites of one colour) into the sensor's own Quad of half its sides. This check builds the real allocator.cpp on the host
// against the NDK's jni.h with a fake JNIEnv (direct buffers only), as check_allocator_adopt.cpp, and checks: a synthetic Tetra
// frame of four flat colours gives the same four colours as 2x2 Quad cells in the same CFA order (the phase of every 4x4 period);
// a flat black / white frame keeps its levels; the mean rounds (sum + 2) >> 2; sizes that are not whole 8x8 periods keep the whole
// ones only (out = side / 8 * 4) and the stride's padding is never read as a sample; packed RAW10 / RAW12 rows bin as their unpacked
// RAW16 twin; too small a source or destination, an unknown format and a short stride are refused.
//
// Build (CI copies jni.h from the NDK into /tmp/native-engine-host first):
//   g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined -I/tmp/native-engine-host -Itools/host-include tools/check_tetra_bin.cpp
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "../app/src/main/cpp/allocator.cpp"

bool scamAhbFreeView(const void*) { return false; }

namespace {
struct Direct { void* address; jlong capacity; };
std::vector<Direct*> made;
jobject direct(void* address, jlong capacity) {
    made.push_back(new Direct{address, capacity});
    return reinterpret_cast<jobject>(made.back());
}
jobject newDirect(JNIEnv*, void* address, jlong capacity) { return direct(address, capacity); }
void* address(JNIEnv*, jobject buffer) { return buffer ? reinterpret_cast<Direct*>(buffer)->address : nullptr; }
jlong capacity(JNIEnv*, jobject buffer) { return buffer ? reinterpret_cast<Direct*>(buffer)->capacity : -1; }

int failures = 0;
void expect(bool ok, const std::string& what) {
    if (!ok) { std::printf("FAIL: %s\n", what.c_str()); failures++; }
}

JNIEnv* env = nullptr;

bool bin(std::vector<uint16_t>& out, std::vector<uint8_t>& in, int offset, int format, int w, int h, int stride) {
    return Java_com_scamera_app_util_Allocator_binBlocks2(env, nullptr, direct(out.data(), jlong(out.size() * 2)),
            direct(in.data(), jlong(in.size())), offset, format, w, h, stride) == JNI_TRUE;
}

// a RAW16 frame (rows {stride} bytes apart from {offset}) of value(x, y); the padding filled with 0xEEEE
std::vector<uint8_t> raw16(int w, int h, int stride, int offset, uint16_t (*value)(int, int)) {
    std::vector<uint8_t> b(size_t(offset) + size_t(stride) * size_t(h), 0xEE);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            const uint16_t v = value(x, y);
            std::memcpy(b.data() + offset + size_t(y) * stride + 2 * size_t(x), &v, 2);
        }
    return b;
}

// the colour of a Tetra site: 4x4 cells in the 2x2 order R G / G B (indices 0 1 / 2 3), 8x8 period
int tetraColour(int x, int y) { return ((y >> 2) & 1) * 2 + ((x >> 2) & 1); }
int quadColour(int x, int y) { return ((y >> 1) & 1) * 2 + ((x >> 1) & 1); }
const uint16_t kLevel[4] = {300, 520, 610, 180};
uint16_t flatColours(int x, int y) { return kLevel[tetraColour(x, y)]; }
uint16_t ramp(int x, int y) { return uint16_t((x * 7 + y * 13) % 1024); }
uint16_t black64(int, int) { return 64; }
uint16_t white1023(int, int) { return 1023; }

// MIPI RAW10 / RAW12 packing of a uint16 row of 10 / 12-bit samples (the layout allocator.cpp decodes)
std::vector<uint8_t> pack(int format, int w, int h, int stride, uint16_t (*value)(int, int)) {
    std::vector<uint8_t> b(size_t(stride) * size_t(h), 0);
    for (int y = 0; y < h; ++y) {
        uint8_t* r = b.data() + size_t(y) * stride;
        if (format == 0x25) {
            for (int x = 0; x + 3 < w; x += 4) {
                uint8_t low = 0;
                for (int k = 0; k < 4; ++k) {
                    const uint16_t v = value(x + k, y) & 1023;
                    r[x / 4 * 5 + k] = uint8_t(v >> 2);
                    low |= uint8_t((v & 3) << (2 * k));
                }
                r[x / 4 * 5 + 4] = low;
            }
        } else {
            for (int x = 0; x + 1 < w; x += 2) {
                const uint16_t a = value(x, y) & 4095, c = value(x + 1, y) & 4095;
                r[x / 2 * 3] = uint8_t(a >> 4);
                r[x / 2 * 3 + 1] = uint8_t(c >> 4);
                r[x / 2 * 3 + 2] = uint8_t((a & 15) | ((c & 15) << 4));
            }
        }
    }
    return b;
}
}  // namespace

int main() {
    JNINativeInterface functions{};
    functions.NewDirectByteBuffer = newDirect;
    functions.GetDirectBufferAddress = address;
    functions.GetDirectBufferCapacity = capacity;
    JNIEnv e{};
    e.functions = &functions;
    env = &e;

    {   // four flat colours: each output 2x2 cell is one colour, in the Tetra's order (the CFA phase kept)
        const int w = 64, h = 48, stride = w * 2;
        auto in = raw16(w, h, stride, 0, flatColours);
        std::vector<uint16_t> out(size_t(w / 2) * (h / 2), 0);
        expect(bin(out, in, 0, 0x20, w, h, stride), "flat Tetra binned");
        bool ok = true;
        for (int y = 0; y < h / 2; ++y)
            for (int x = 0; x < w / 2; ++x) ok &= out[size_t(y) * (w / 2) + x] == kLevel[quadColour(x, y)];
        expect(ok, "Tetra 4x4 cells -> Quad 2x2 cells of the same colour order");
    }
    {   // the mean of each 2x2 box, rounded (sum + 2) >> 2
        const int w = 32, h = 16, stride = w * 2;
        auto in = raw16(w, h, stride, 0, ramp);
        std::vector<uint16_t> out(size_t(w / 2) * (h / 2), 0);
        expect(bin(out, in, 0, 0x20, w, h, stride), "ramp binned");
        bool ok = true;
        for (int y = 0; y < h / 2; ++y)
            for (int x = 0; x < w / 2; ++x) {
                const uint32_t s = ramp(2 * x, 2 * y) + ramp(2 * x + 1, 2 * y) + ramp(2 * x, 2 * y + 1) + ramp(2 * x + 1, 2 * y + 1);
                ok &= out[size_t(y) * (w / 2) + x] == uint16_t((s + 2) >> 2);
            }
        expect(ok, "each site is the rounded mean of its 2x2 box");
    }
    {   // levels: a flat black and a flat white frame keep 64 / 1023 (the mean, not the sum)
        const int w = 16, h = 16, stride = w * 2;
        auto b = raw16(w, h, stride, 0, black64), wt = raw16(w, h, stride, 0, white1023);
        std::vector<uint16_t> ob(64, 0), ow(64, 0);
        expect(bin(ob, b, 0, 0x20, w, h, stride) && bin(ow, wt, 0, 0x20, w, h, stride), "levels binned");
        bool ok = true;
        for (int i = 0; i < 64; ++i) ok &= ob[size_t(i)] == 64 && ow[size_t(i)] == 1023;
        expect(ok, "black 64 and white 1023 stay");
    }
    {   // a size that is not whole periods (37 x 21) and a padded stride with an offset: whole 8x8 periods only, padding unread
        const int w = 37, h = 21, stride = 96, offset = 6;
        auto in = raw16(w, h, stride, offset, flatColours);
        const int ow = w / 8 * 4, oh = h / 8 * 4;  // 16 x 8
        std::vector<uint16_t> out(size_t(ow) * oh + 4, 0xABCD);
        expect(bin(out, in, offset, 0x20, w, h, stride), "odd size binned");
        bool ok = true;
        for (int y = 0; y < oh; ++y)
            for (int x = 0; x < ow; ++x) ok &= out[size_t(y) * ow + x] == kLevel[quadColour(x, y)];
        expect(ok, "odd size: whole periods in the Quad order, no padding read");
        expect(out[size_t(ow) * oh] == 0xABCD, "nothing written past the binned frame");
    }
    {   // packed RAW10 and RAW12 rows bin as their RAW16 twin
        const int w = 64, h = 32;
        auto twin = raw16(w, h, w * 2, 0, ramp);
        std::vector<uint16_t> ref(size_t(w / 2) * (h / 2), 0);
        expect(bin(ref, twin, 0, 0x20, w, h, w * 2), "RAW16 twin binned");
        for (int format : {0x25, 0x26}) {
            const int rowBytes = format == 0x25 ? w * 10 / 8 : w * 12 / 8, stride = rowBytes + 16;
            auto in = pack(format, w, h, stride, ramp);
            std::vector<uint16_t> out(ref.size(), 0);
            expect(bin(out, in, 0, format, w, h, stride), "packed binned " + std::to_string(format));
            expect(out == ref, "packed " + std::to_string(format) + " = its RAW16 twin");
        }
    }
    {   // refusals: a short source, a short destination, an unknown format, a stride below a row, a frame below one period
        const int w = 32, h = 16, stride = w * 2;
        auto in = raw16(w, h, stride, 0, ramp);
        std::vector<uint16_t> out(size_t(w / 2) * (h / 2), 0), small(10, 0);
        std::vector<uint8_t> shortIn(in.begin(), in.end() - 2);
        expect(!bin(out, shortIn, 0, 0x20, w, h, stride), "short source refused");
        expect(!bin(small, in, 0, 0x20, w, h, stride), "short destination refused");
        expect(!bin(out, in, 0, 0x7E04, w, h, stride), "RAW14 / unknown format refused");
        expect(!bin(out, in, 0, 0x20, w, h, stride - 2), "a stride below one row refused");
        expect(!bin(out, in, 0, 0x20, 4, 4, 8), "below one period refused");
    }
    {   // a large frame across the threads (16320 x 64 rows of the 200 MP width): the same as row by row
        const int w = 16320, h = 64, stride = w * 2;
        auto in = raw16(w, h, stride, 0, ramp);
        std::vector<uint16_t> out(size_t(w / 2) * (h / 2), 0);
        expect(bin(out, in, 0, 0x20, w, h, stride), "wide frame binned");
        bool ok = true;
        for (int y = 0; y < h / 2; ++y)
            for (int x = 0; x < w / 2; x += 97) {
                const uint32_t s = ramp(2 * x, 2 * y) + ramp(2 * x + 1, 2 * y) + ramp(2 * x, 2 * y + 1) + ramp(2 * x + 1, 2 * y + 1);
                ok &= out[size_t(y) * (w / 2) + x] == uint16_t((s + 2) >> 2);
            }
        expect(ok, "wide frame: every thread's rows");
    }
    for (Direct* d : made) delete d;
    if (failures) { std::printf("check_tetra_bin: %d failure(s)\n", failures); return 1; }
    std::printf("check_tetra_bin: OK\n");
    return 0;
}
