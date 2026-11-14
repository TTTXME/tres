// P367 check: the editor zoom's full-resolution windows (Editor/EditExport.hpp renderWindow) against the copy (renderCopy) at
// the same output size. Windows on the copy's 1024 px tile grid must give the copy's pixels exactly; windows cut elsewhere are
// reported (the regions' own passes - the guided filters, the noise reduction - see their region, as Android's EditZoomLoader).
// Build (Mesa): g++ -std=c++17 -O2 -ffp-contract=off -Iios/ScamGL/include -Iios ios/tools/zoom_window_check.cpp -lEGL -lGLESv2 -lz
// Run: EGL_PLATFORM=surfaceless ./a.out app/src/main/assets/shaders
#include "Editor/EditExport.hpp"

#include <EGL/egl.h>

#include <algorithm>
#include <cstdio>

using namespace scam_edit;

static void makeContext() {
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(display, nullptr, nullptr);
    eglBindAPI(EGL_OPENGL_ES_API);
    const EGLint configAttrs[] = {EGL_RENDERABLE_TYPE, 0x40, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
    EGLConfig config;
    EGLint n = 0;
    eglChooseConfig(display, configAttrs, &config, 1, &n);
    const EGLint ctxAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, ctxAttrs);
    const EGLint size[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    EGLSurface surface = eglCreatePbufferSurface(display, config, size);
    if (!eglMakeCurrent(display, surface, surface, context)) throw std::runtime_error("no GL context");
}

/// the four windows split at (cx, cy) against the copy: {differing values, the largest difference}
static std::pair<size_t, int> compare(EditRenderer& r, const PreviewSource& src, Reader& reader, const EditState& st, const ExportResult& copy,
                                      int cx, int cy) {
    const int win[4][4] = {{0, 0, cx, cy}, {cx, 0, copy.W - cx, cy}, {0, cy, cx, copy.H - cy}, {cx, cy, copy.W - cx, copy.H - cy}};
    size_t diff = 0;
    int maxd = 0;
    for (const auto& wv : win) {
        if (wv[2] <= 0 || wv[3] <= 0) continue;
        const ExportResult part = renderWindow(r, src, reader, st, 1, copy.W, copy.H, wv[0], wv[1], wv[2], wv[3]);
        for (int y = 0; y < part.H; y++)
            for (int x = 0; x < part.W * 4; x++) {
                const int a = part.rgba[size_t(y) * part.W * 4 + x], b = copy.rgba[(size_t(wv[1] + y) * copy.W + wv[0]) * 4 + x];
                if (a != b) {
                    diff++;
                    maxd = std::max(maxd, std::abs(a - b));
                }
            }
    }
    return {diff, maxd};
}

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "zoom_window_check <shaders root>\n");
        return 2;
    }
    makeContext();
    const int w = 1500, h = 1100;
    std::vector<uint8_t> px(size_t(w) * h * 4);
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
            uint8_t* p = &px[(size_t(y) * w + x) * 4];
            p[0] = uint8_t(x * 255 / w);
            p[1] = uint8_t(y * 255 / h);
            p[2] = uint8_t(((x ^ y) & 0x3f) * 4 + ((x * 7 + y * 13) & 3));
            p[3] = 255;
        }
    PreviewSource src;
    src.width = w;
    src.height = h;
    src.rgba = px;
    src.colour = std::make_shared<EditMath::DisplayColour>(false, false);
    const char* cases[] = {
        "{\"v\":1}",
        "{\"v\":1,\"p\":{\"exposure\":0.4,\"contrast\":25,\"clarity\":30,\"texture\":20,\"lumaNr\":40,\"sharpen\":60,\"grain\":50,\"vignette\":-40}}",
        "{\"v\":1,\"p\":{\"vignette\":30,\"grain\":80,\"sharpen\":40},\"g\":{\"q\":1,\"a\":12,\"c\":[0.1,0.12,0.85,0.9]}}",
    };
    int failures = 0;
    for (const char* json : cases) {
        EditState st;
        if (!EditState::fromJson(json, st)) return 2;
        EditRenderer r;
        r.init(argv[1]);
        PictureReader reader(px.data(), w, h, false);
        const ExportResult copy = renderCopy(r, src, reader, st, 1, 64000000LL, nullptr);
        const auto grid = compare(r, src, reader, st, copy, std::min(copy.W, TILE), std::min(copy.H, TILE));
        const auto off = compare(r, src, reader, st, copy, copy.W * 37 / 100, copy.H * 61 / 100);
        r.release();
        std::printf("%dx%d on the tile grid: %zu differing values (max %d); cut elsewhere: %zu (max %d)\n", copy.W, copy.H, grid.first,
                    grid.second, off.first, off.second);
        if (grid.first) failures++;
    }
    return failures ? 1 : 0;
}
