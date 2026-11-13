// The app's whole shot (Engine/post/ShotRun.hpp: transport, merge, Parameters from the DNG, post pipeline) on the host GPU, for a
// lab burst with its DNG (tools/make_test_dng.py) and its prefs; against the Android photo of that burst when given (the app's
// burst tuning adds the shot's own lines, so the merge is not the lab's: a PSNR, not identity).
//   shot_run_check <shaders> <meta.json> <burst.nch (a copy: its header flags are set)> <dng> [photo.rgba of the Android run]
// The burst's sidecar <burst.nch>.json (ShotTransport.hpp: the zoom, the captured frames' roles and exposures) is read as the app
// reads it: {"zoom": 2} makes a digital zoom shot (SabreCrop's window, the photo's crop after the pipeline). With
// SCAM_SHOT_NO_TAIL=1 the photo is the pipeline's, before that crop (the Android lab renders the pipeline alone). SCAM_SHOT_LAB_PARAMS=1
// takes the lab shot's values its DNG cannot carry (exposure as Camera2 reported it, analog ISO, levels, sensor geometry); with the
// meta's tuningBurst set to the app's burst lines the photo then matches the lab's bit for bit. SCAM_SHOT_SAVE=<file>: the photo
// as photo.rgba ("SCR8" header).
//   shot_run_check --select <cases.txt>
// The frame rules (HybridSelect.hpp) against the Android ScamHybridBurst constructor's frame tables of random bursts (dumped by a
// Robolectric run of the Java: roles, exposures, ISO, noise, orderMs and the base's EV100, bit for bit).
// «Oldstyle» needs the owner's tone-port module: -Iapp/src/main/cpp/third_party/tone_port/include (else an Oldstyle shot stops at
// its tone). Ultra HDR (pref_ultrahdr_key): the gain map's size and metadata are printed.
#include "../Engine/post/Json.hpp"
#include "../Engine/post/ShotRun.hpp"
#if __has_include("tone_port.h") && __has_include("oldstyle-tone-core.h")
#include "../Engine/post/oldstyle/ToneUnity.hpp"
#else
namespace scam_post::ToneNative {
int setThreads(int) { return 1; }
void render(int, const int16_t*, int, int, const float*, float, float, bool, bool, float, const float*, int, const float*, int, int, float*,
            uint8_t*) {
    throw std::runtime_error("the tone-port module is not in this build (-Iapp/src/main/cpp/third_party/tone_port/include)");
}
} // namespace scam_post::ToneNative
#endif

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

/// --select: each case's frames through HybridSelect::select with the case's settings, against the Java's table
static int selectCheck(const char* path) {
    std::ifstream in(path);
    if (!in) { std::fprintf(stderr, "no %s\n", path); return 2; }
    auto bitsF = [](const std::string& hex) { const uint32_t u = uint32_t(std::stoul(hex, nullptr, 16)); float f; std::memcpy(&f, &u, 4); return f; };
    std::string line;
    int cases = 0, bad = 0, entries = 0;
    struct Case { int k = 0; float bento = 2, factor = 8, photon = 1, readout = 1, sharp = 0.75f, aperture = 0; std::vector<HybridSelect::Frame> frames;
                  std::vector<std::string> want; };
    Case c;
    while (std::getline(in, line)) {
        std::istringstream ls(line);
        std::string tag;
        ls >> tag;
        if (tag == "case") {
            c = Case();
            std::string b, f, ph, ro, sh, ap;
            ls >> c.k >> b >> f >> ph >> ro >> sh >> ap;
            c.bento = bitsF(b); c.factor = bitsF(f); c.photon = bitsF(ph); c.readout = bitsF(ro); c.sharp = bitsF(sh); c.aperture = bitsF(ap);
        } else if (tag == "in") {
            HybridSelect::Frame fr;
            int role;
            std::string slope, offset, sharp;
            ls >> fr.number >> role >> fr.exposureNs >> fr.iso >> fr.timestampNs >> slope >> offset >> sharp;
            fr.index = fr.number;
            fr.role = role == 3 ? HybridSelect::CaptureRole::Long : role == 5 ? HybridSelect::CaptureRole::ExtraShort : HybridSelect::CaptureRole::Normal;
            fr.noiseSlope = bitsF(slope);
            fr.noiseOffset = bitsF(offset);
            fr.sharpness = bitsF(sharp);
            c.frames.push_back(fr);
        } else if (tag == "out" || tag == "ev" || tag == "throws") {
            c.want.push_back(line);
        } else if (tag == "end") {
            cases++;
            char dev[512];
            std::snprintf(dev, sizeof dev, "{\"prefs\":{},\"dev\":{\"hybrid_bento_frames\":%.9g,\"hybrid_bento_factor\":%.9g,"
                          "\"hybrid_noise_photon\":%.9g,\"hybrid_noise_readout\":%.9g,\"hybrid_sharp_select\":%.9g}}",
                          double(c.bento), double(c.factor), double(c.photon), double(c.readout), double(c.sharp));
            Settings settings = Settings::fromJson(Json::parse(dev));
            std::vector<std::string> got;
            std::map<int, float> sharpness;
            for (const auto& fr : c.frames) sharpness[fr.index] = fr.sharpness;
            try {
                const HybridSelect::Result r = HybridSelect::select(c.frames, 64, 64, settings, [&](int i) { return sharpness[i]; }, c.aperture);
                for (const auto& e : r.frames) {
                    char buf[160];
                    uint32_t u[4];
                    std::memcpy(&u[0], &e.exposure, 4); std::memcpy(&u[1], &e.slope, 4); std::memcpy(&u[2], &e.offset, 4); std::memcpy(&u[3], &e.orderMs, 4);
                    std::snprintf(buf, sizeof buf, "out %d %u %08x %u %08x %08x %08x", e.number, e.role, u[0], e.iso, u[1], u[2], u[3]);
                    got.push_back(buf);
                }
                uint32_t ev;
                std::memcpy(&ev, &r.sceneEv, 4);
                char buf[32];
                std::snprintf(buf, sizeof buf, "ev %08x", ev);
                got.push_back(buf);
            } catch (const std::exception& e) {
                got.push_back(std::string("throws ") + e.what());
            }
            entries += int(c.want.size());
            bool same = got.size() == c.want.size();
            for (size_t i = 0; same && i < got.size(); i++) {
                const bool thrown = got[i].rfind("throws", 0) == 0 && c.want[i].rfind("throws", 0) == 0;
                if (got[i] != c.want[i] && !thrown) same = false;
            }
            if (!same) {
                if (++bad <= 5) {
                    std::printf("case %d differs\n  java:\n", c.k);
                    for (const auto& w : c.want) std::printf("    %s\n", w.c_str());
                    std::printf("  port:\n");
                    for (const auto& g : got) std::printf("    %s\n", g.c_str());
                }
            }
        }
    }
    std::printf("frame rules: %d bursts, %d table lines, %d differ\n", cases, entries, bad);
    return bad == 0 && cases > 0 ? 0 : 1;
}

int main(int argc, char** argv) {
    if (argc == 3 && std::string(argv[1]) == "--select") return selectCheck(argv[2]);
    if (argc < 5) { std::fprintf(stderr, "usage: shot_run_check <shaders> <meta.json> <burst.nch> <dng> [photo.rgba]\n"); return 2; }
    try {
        const auto metaText = readAll(argv[2]);
        Settings settings = Settings::fromJson(Json::parse(std::string(metaText.begin(), metaText.end())));
        Settings::Scope scope(settings);
        const std::string job = std::string(argv[3]) + ".job";
        std::system(("mkdir -p '" + job + "'").c_str());
        size_t lines = 0;
        const char* noTail = std::getenv("SCAM_SHOT_NO_TAIL");
        // SCAM_SHOT_LAB_PARAMS=1: the lab shot's own values that its DNG does not carry (the vivo burst's exposure as Camera2 gave it,
        // its analog ISO, levels, sensor geometry; the crop's fields stay the app's), so the photo can match the Android lab's bit for bit
        const char* labValues = std::getenv("SCAM_SHOT_LAB_PARAMS");
        const Parameters lab = Parameters::fromJson(Json::parse(std::string(metaText.begin(), metaText.end()))["params"]);
        std::function<void(Parameters&)> labHook;
        if (labValues && std::string(labValues) == "1")
            labHook = [&](Parameters& p) {
                p.exposureTime = lab.exposureTime;
                p.analogIso = lab.analogIso;
                p.levelBits = lab.levelBits;
                p.highlightSuppressionStrength = lab.highlightSuppressionStrength;
                p.sensorPix = lab.sensorPix;
                p.physicalID = lab.physicalID;
                p.cameraID = lab.cameraID;
                p.finishSnr = lab.finishSnr;
                p.effectiveStackSamples = lab.effectiveStackSamples;
            };
        const ShotOutput out = runShot(argv[3], readAll(argv[4]), job, argv[1], [&](const std::string& l) {
            lines++;
            if (l.rfind("merge: ", 0) != 0) std::printf("%s\n", l.c_str());
        }, makeContext, 16384, {}, !(noTail && std::string(noTail) == "1"), labHook);
        std::printf("shot: merge %.0f ms, post %.0f ms, pipeline %dx%d, photo %dx%d, %zu log lines\n", out.mergeMs, out.postMs,
                    out.pipelineWidth, out.pipelineHeight, out.photo.width, out.photo.height, lines);
        if (const char* save = std::getenv("SCAM_SHOT_SAVE")) {
            // the photo as the Android harness writes photo.rgba: "SCR8", width, height, 4 then the RGBA8 rows
            std::ofstream o(save, std::ios::binary);
            const int32_t head[4] = {0x38524353, out.photo.width, out.photo.height, 4};
            o.write(reinterpret_cast<const char*>(head), 16);
            o.write(reinterpret_cast<const char*>(out.photo.rgba.data()), std::streamsize(out.photo.rgba.size()));
        }
        if (out.gainMap)
            std::printf("Ultra HDR gain map %dx%d min %s max %s capacity %s\n", out.gainMap->width, out.gainMap->height,
                        javaFloatString(out.gainMap->gainMapMin).c_str(), javaFloatString(out.gainMap->gainMapMax).c_str(),
                        javaFloatString(out.gainMap->hdrCapacityMax).c_str());
        if (argc > 5) {
            const auto ref = readAll(argv[5]);
            if (ref.size() != 16 + out.photo.rgba.size()) { std::printf("the Android photo has another size\n"); return 3; }
            double sq = 0;
            size_t values = 0, differ = 0;
            int worst = 0;
            for (size_t i = 0; i < out.photo.rgba.size(); i++) {
                if (i % 4 == 3) continue;
                const int d = int(out.photo.rgba[i]) - int(ref[16 + i]);
                sq += double(d) * d;
                values++;
                if (d) differ++;
                worst = std::max(worst, std::abs(d));
            }
            const double mse = sq / double(values);
            std::printf("against the Android lab photo: %zu of %zu values differ, max %d, PSNR %.2f dB\n", differ, values, worst,
                        mse > 0 ? 10 * std::log10(255.0 * 255.0 / mse) : 999.0);
        }
        return 0;
    } catch (const std::exception& e) {
        std::fprintf(stderr, "shot_run_check: %s\n", e.what());
        return 1;
    }
}
