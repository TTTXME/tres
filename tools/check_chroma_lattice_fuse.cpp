// P662 (speed, the fast route's Quad at 50 MP): the chroma median and the Quad lattice in the same GPU bands
// (mosaicChromaLatticeBands: each band's median result stays on the GPU for its lattice strip) give, bit for bit, the floats of the
// two passes as the route ran them before - the median in its bands (mosaicChromaMedianBands, read back), then quadLattice over
// the frame in its own strips (uploaded again) - for both lattice units, bands and strips of other heights than each other, a
// last band shorter than the others; the lattice's strip one call (the 12 MP path: quadLattice from the median's GPU buffer after
// process()) as well. And the refusal below kFuseMinRows rows (nothing touched).
//
// Build (CI):
//   g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_chroma_lattice_fuse.cpp -ldl -lEGL -lGLESv2
// scam-hybrid.h is not self-contained: scam-capture.h first.
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cstdio>
#include <cstring>
#include <random>

using namespace scam;

static size_t differing(const std::vector<float>& a, const std::vector<float>& b) {
    size_t n = 0;
    for (size_t i = 0; i < a.size(); ++i) n += std::memcmp(&a[i], &b[i], 4) != 0;
    return n;
}

int main() {
    std::mt19937 rng(662);
    std::normal_distribution<float> n01(0.f, 1.f);
    int bad = 0;
    const int block = 2, w = 256, h = 2 * 1000 + 300; // three fused bands, the last shorter
    std::vector<float> img(size_t(w) * h * 3);
    const float lat[4][4] = {{0.02f, -0.01f, 0.015f, -0.02f}, {-0.01f, 0.01f, -0.015f, 0.012f}, {0.01f, -0.02f, 0.01f, 0.0f}, {-0.02f, 0.015f, -0.01f, 0.012f}};
    for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
        const float base = (x > 60 && y > 700 ? 0.6f : 0.2f) + 0.05f * std::sin(x * 0.03f) * std::cos(y * 0.011f);
        const float v = base * (1.f + lat[y & 3][x & 3]), c = ((x / block + y / block) & 1) ? 0.03f : -0.03f;
        float* p = &img[(size_t(y) * w + x) * 3];
        p[0] = v * (0.9f + c) + 0.004f * n01(rng); p[1] = v + 0.004f * n01(rng); p[2] = v * (0.8f - c) + 0.004f * n01(rng);
        if (y > h / 2 && ((x * 7 + y * 3) % 97) < 9) p[1] += 0.2f * std::sin(x * 1.3f);
    }
    const int d = std::max(1, block / 2), reach = std::max(2 * d + 6 * block + 1, 3 * block + 1) + 2, halo = ((reach + 2 * block - 1) / (2 * block)) * (2 * block);
    const int LH = ChromaMedianGpu::kQlHalo;
    for (const bool rel : {true, false}) {
        for (const int fusedRows : {1000, 800}) {
            // MemAvailable that makes the fused bands `fusedRows` rows (and the old median's bands larger: other seams)
            const uint64_t avail = uint64_t(4) * uint64_t(w) * (48 + 36) * uint64_t(fusedRows + 2 * halo + 2 * LH);
            // before: the median's bands, read back; then the lattice over the frame in strips of 1024 / 333 rows
            for (const int qlStrip : {1024, 333}) {
                std::vector<float> two = img, fused = img;
                std::string t1, t2;
                chromaKeepNext(true);
                if (!mosaicChromaMedianBands(two, w, h, block, nullptr, avail, &t1)) { std::printf("median bands refused: %s\n", t1.c_str()); return 1; }
                {auto g = chromaTake(); g->qlStrip = qlStrip; g->quadLattice(two, w, h, 2.5f, rel); chromaKeep(std::move(g));}
                HybridLatticeFuse fuse; fuse.relative = rel;
                chromaKeepNext(true);
                if (!mosaicChromaLatticeBands(fused, w, h, block, avail, fuse, &t2) || !fuse.done) { std::printf("fused refused: %s\n", t2.c_str()); return 1; }
                const size_t off = differing(two, fused);
                std::printf("%s, fused %s | median %s, lattice strips %d: values off %zu\n", rel ? "relative" : "luma units", t2.c_str(), t1.c_str(), qlStrip, off);
                if (off || t2.find("3 bands") == std::string::npos) ++bad;
            }
        }
        { // the one-call median (process) and the lattice from its GPU buffer against the lattice uploaded
            const int h1 = 600;
            std::vector<float> a(img.begin(), img.begin() + size_t(w) * h1 * 3), b = a;
            std::string ta, tb;
            {auto g = chromaTake(); g->process(a, w, h1, block, nullptr); ta = g->quadLattice(a, w, h1, 2.5f, rel, false); chromaKeep(std::move(g));}
            {auto g = chromaTake(); g->process(b, w, h1, block, nullptr); tb = g->quadLattice(b, w, h1, 2.5f, rel, true); chromaKeep(std::move(g));}
            const size_t off = differing(a, b);
            std::printf("%s one call: lattice uploaded (%s) / from the median's buffer (%s): values off %zu\n", rel ? "relative" : "luma units", ta.c_str(), tb.c_str(), off);
            if (off || tb.find("from the median's GPU buffer") == std::string::npos) ++bad;
        }
    }
    { // below kFuseMinRows: refused, nothing touched
        std::vector<float> f = img;
        HybridLatticeFuse fuse;
        std::string t;
        const uint64_t avail = uint64_t(4) * uint64_t(w) * (48 + 36) * uint64_t(400 + 2 * halo + 2 * LH);
        chromaKeepNext(true);
        const bool took = mosaicChromaLatticeBands(f, w, h, block, avail, fuse, &t);
        std::printf("small bands: %s (%s)\n", took ? "taken" : "refused", t.c_str());
        if (took || fuse.done || differing(f, img)) ++bad;
    }
    std::printf(bad ? "check_chroma_lattice_fuse: FAILED\n" : "check_chroma_lattice_fuse: OK\n");
    return bad ? 1 : 0;
}
