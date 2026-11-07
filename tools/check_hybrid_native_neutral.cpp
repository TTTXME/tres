// P523: the Quad / Tetra base demosaic (P282) damps its aliasing colour toward the camera neutral, not toward R = G = B of the
// sensor (magenta once white-balanced: pink shoelaces and stripes on moving people, X8U Quad bursts). Checks the default and the
// tuning key, the neutral (wbR / wbB, else the base frame's grey world over its colour blocks for every CFA order and block size,
// clipped blocks left out) and that the fast native merge carries the damping toward it.
// scam-hybrid.h is not self-contained: the worker includes scam-capture.h first.
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <unistd.h>
using namespace scam;

static bool near(float a, float b, float tol) { return std::fabs(a - b) <= tol; }

int main() {
    const HybridTuning d;
    assert(d.baseDemosaicNeutral == 1.f);
    assert(d.wbR == 0.f && d.wbB == 0.f);

    const char* tmp = std::getenv("TMPDIR");
    std::string tmpl = std::string(tmp && *tmp ? tmp : "/tmp") + "/hybrid-neutral-XXXXXX";
    char* dir = tmpl.data();
    if (!mkdtemp(dir)) { std::perror("mkdtemp"); return 1; }
    {
        std::ofstream f(std::string(dir) + "/hybrid_tuning.txt");
        f << "baseDemosaicNeutral 0\nwbR 0.4787\nwbB 0.6535\n";
    }
    const HybridTuning t = loadHybridTuning(dir, [](const std::string&) {});
    assert(t.baseDemosaicNeutral == 0.f && t.wbR == 0.4787f && t.wbB == 0.6535f);
    std::remove((std::string(dir) + "/hybrid_tuning.txt").c_str());
    rmdir(dir);

    // the camera neutral when given
    HybridInput none;
    auto n = hybridMosaicNeutral(none, 2, t);
    assert(n[0] == 0.4787f && n[1] == 0.6535f);
    // no frame and no neutral: 1, 1 (the damping as before)
    n = hybridMosaicNeutral(none, 2, d);
    assert(n[0] == 1.f && n[1] == 1.f);

    // grey world of a mosaic whose colour blocks hold R 0.24, G 0.5, B 0.325 (above black 64, white 1023): neutral 0.48 / 0.65,
    // for every CFA order of the blocks and Quad / Tetra blocks; clipped green blocks in a corner do not count
    for (int b : {2, 4})
        for (int cfa = 0; cfa < 4; cfa++) {
            HybridInput in;
            in.w = 64 * b; in.h = 48 * b; in.cfa = cfa; in.white = 1023.f; in.black = {64.f, 64.f, 64.f, 64.f};
            std::vector<uint16_t> raw(size_t(in.w) * in.h);
            for (int y = 0; y < in.h; y++)
                for (int x = 0; x < in.w; x++) {
                    const int bx = x / b, by = y / b, ph = (((by + (cfa >> 1)) & 1) << 1) | ((bx + (cfa & 1)) & 1);
                    float v = ph == 0 ? 0.24f : ph == 3 ? 0.325f : 0.5f;
                    if (ph != 0 && ph != 3 && bx < 8 && by < 8) v = 1.f; // clipped greens: would pull G up
                    raw[size_t(y) * in.w + x] = uint16_t(std::lround(64.f + v * (1023.f - 64.f)));
                }
            HybridFrame f; f.raw = raw.data(); in.frames.push_back(f);
            n = hybridMosaicNeutral(in, b, d);
            if (!near(n[0], 0.48f, 0.003f) || !near(n[1], 0.65f, 0.003f)) {
                std::fprintf(stderr, "block %d cfa %d: neutral %f %f\n", b, cfa, n[0], n[1]);
                return 1;
            }
        }

    // the fast native merge: the damping goes toward natDemN (its share z), the old damping when z = 0
    const std::string fast = kHybMergeMosaicFast;
    assert(fast.find("uniform vec3 natDemN;") != std::string::npos);
    assert(fast.find("float nz=natDemN.z*(1.0-damp)*max(G,0.0);") != std::string::npos);
    assert(fast.find("vec3 o=vec3(G+dR*damp+nz*(natDemN.x-1.0),G,G+dB*damp+nz*(natDemN.y-1.0));") != std::string::npos);
    std::puts("check_hybrid_native_neutral: OK");
    return 0;
}
