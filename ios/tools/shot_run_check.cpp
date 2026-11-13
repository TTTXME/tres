// The app's whole shot (Engine/post/ShotRun.hpp: transport, merge, Parameters from the DNG, post pipeline) on the host GPU, for a
// lab burst with its DNG (tools/make_test_dng.py) and its prefs; against the Android photo of that burst when given (the app's
// burst tuning adds the shot's own lines, so the merge is not the lab's: a PSNR, not identity).
//   shot_run_check <shaders> <meta.json> <burst.nch (a copy: its header flags are set)> <dng> [photo.rgba of the Android run]
#include "../Engine/post/Json.hpp"
#include "../Engine/post/ShotRun.hpp"

#include <EGL/egl.h>
#include <iterator>

using namespace scam_post;

static std::vector<uint8_t> readAll(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) throw std::runtime_error("cannot read " + path);
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(f)), {});
}

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

int main(int argc, char** argv) {
    if (argc < 5) { std::fprintf(stderr, "usage: shot_run_check <shaders> <meta.json> <burst.nch> <dng> [photo.rgba]\n"); return 2; }
    try {
        const auto metaText = readAll(argv[2]);
        Settings settings = Settings::fromJson(Json::parse(std::string(metaText.begin(), metaText.end())));
        Settings::Scope scope(settings);
        const std::string job = std::string(argv[3]) + ".job";
        std::system(("mkdir -p '" + job + "'").c_str());
        size_t lines = 0;
        const ShotOutput out = runShot(argv[3], readAll(argv[4]), job, argv[1], [&](const std::string& l) {
            lines++;
            if (l.rfind("merge: ", 0) != 0) std::printf("%s\n", l.c_str());
        }, makeContext);
        std::printf("shot: merge %.0f ms, post %.0f ms, photo %dx%d, %zu log lines\n", out.mergeMs, out.postMs, out.photo.width,
                    out.photo.height, lines);
        if (argc > 5) {
            const auto ref = readAll(argv[5]);
            if (ref.size() != 16 + out.photo.rgba.size()) { std::printf("the Android photo has another size\n"); return 3; }
            double sq = 0;
            size_t values = 0;
            for (size_t i = 0; i < out.photo.rgba.size(); i++) {
                if (i % 4 == 3) continue;
                const double d = double(out.photo.rgba[i]) - ref[16 + i];
                sq += d * d;
                values++;
            }
            const double mse = sq / double(values);
            std::printf("against the Android lab photo: PSNR %.2f dB\n", mse > 0 ? 10 * std::log10(255.0 * 255.0 / mse) : 999.0);
        }
        return 0;
    } catch (const std::exception& e) {
        std::fprintf(stderr, "shot_run_check: %s\n", e.what());
        return 1;
    }
}
