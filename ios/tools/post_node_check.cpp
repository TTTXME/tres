// The C++ post port against the Android reference (ios/tools/android-host, HostPostRender with SCAM_HOST_DUMP): the lab shot's
// settings and parameters, the worker output the reference merged, then the ported nodes in the Android order; each node's
// output is compared with the reference's dump of that node. With --inject a node's output is replaced by the reference dump
// before the next node runs (every node then starts from the reference's input).
//   post_node_check <shaders root> <shot dir> <reference dir> <nodes> [--inject] [--save <dir>]
//   <nodes>: comma separated, e.g. ScamRgb
#include "../Engine/post/WorkerOutput.hpp"
#include "../Engine/post/nodes/ScamRgb.hpp"
#include "../Engine/post/nodes/ScamHdrDenoise.hpp"
#include "../Engine/post/nodes/WhyHlDezipper.hpp"
#include "../Engine/post/nodes/WhyMsDenoise.hpp"
#include "../Engine/post/nodes/WhyLumaSharpen.hpp"
#include "../Engine/post/PostPipelineRun.hpp"

#include <EGL/egl.h>

#include <chrono>
#include <fstream>

using namespace scam_post;

static std::vector<uint8_t> readAll(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) throw std::runtime_error("cannot read " + path);
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
}

struct F32 { int w = 0, h = 0, c = 0; std::vector<float> v; };
static F32 readF32(const std::string& path) {
    const auto b = readAll(path);
    F32 r;
    uint32_t hdr[4];
    std::memcpy(hdr, b.data(), 16);
    r.w = int(hdr[1]); r.h = int(hdr[2]); r.c = int(hdr[3]);
    r.v.resize((b.size() - 16) / 4);
    std::memcpy(r.v.data(), b.data() + 16, r.v.size() * 4);
    return r;
}
static void writeF32(const std::string& path, int w, int h, int c, const float* v) {
    std::ofstream out(path, std::ios::binary);
    const uint32_t header[4] = {0x47524353u, uint32_t(w), uint32_t(h), uint32_t(c)};
    out.write(reinterpret_cast<const char*>(header), 16);
    out.write(reinterpret_cast<const char*>(v), std::streamsize(size_t(w) * h * c * 4));
}

/// the texture as RGBA float (the reference's ShadowScamDiagnostics read: GL_RGBA / GL_FLOAT of its framebuffer)
static std::vector<float> readTexture(GLTexture& t) {
    t.BufferLoad();
    std::vector<float> px(size_t(t.mSize.x) * t.mSize.y * 4);
    glPixelStorei(GL_PACK_ALIGNMENT, 4);
    glReadPixels(0, 0, t.mSize.x, t.mSize.y, GL_RGBA, GL_FLOAT, px.data());
    return px;
}

static std::string compare(const std::vector<float>& a, const F32& ref, int channels) {
    double maxAbs = 0, sumSq = 0, peak = 0;
    size_t diff = 0, n = 0, nanMismatch = 0;
    for (size_t i = 0; i < a.size() && i < ref.v.size(); i++) {
        if (int(i % 4) >= channels) continue;
        const float x = a[i], y = ref.v[i];
        n++;
        if (std::isnan(x) || std::isnan(y)) { if (std::isnan(x) != std::isnan(y)) nanMismatch++; continue; }
        if (!std::isfinite(x) || !std::isfinite(y)) { if (x != y) nanMismatch++; continue; }
        const double d = std::fabs(double(x) - double(y));
        if (x != y) diff++;
        maxAbs = std::max(maxAbs, d);
        sumSq += d * d;
        peak = std::max(peak, std::fabs(double(y)));
    }
    const double rmse = std::sqrt(sumSq / double(std::max<size_t>(1, n)));
    const double psnr = rmse > 0 ? 20 * std::log10(std::max(peak, 1e-6) / rmse) : 999;
    char buf[256];
    std::snprintf(buf, sizeof buf, "%zu of %zu values differ, max |d| %.3g, rmse %.3g, PSNR %.1f dB%s", diff, n, maxAbs, rmse, psnr,
                  nanMismatch ? (" NaN/inf mismatches " + std::to_string(nanMismatch)).c_str() : "");
    return buf;
}

int main(int argc, char** argv) {
    if (argc < 5) {
        std::fprintf(stderr, "usage: %s <shaders root> <shot dir> <reference dir> <nodes> [--inject] [--save dir]\n", argv[0]);
        return 2;
    }
    const std::string shaders = argv[1], shot = argv[2], refDir = argv[3];
    std::vector<std::string> nodeNames;
    { std::istringstream s(argv[4]); std::string n; while (std::getline(s, n, ',')) nodeNames.push_back(n); }
    bool inject = false;
    std::string save;
    for (int i = 5; i < argc; i++) {
        if (std::string(argv[i]) == "--inject") inject = true;
        else if (std::string(argv[i]) == "--save" && i + 1 < argc) save = argv[++i];
    }
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
    if (!eglMakeCurrent(display, surface, surface, context)) {
        std::fprintf(stderr, "no current GL context (EGL error 0x%x)\n", eglGetError());
        return 1;
    }
    try {
        const auto metaText = readAll(shot + "/meta.json");
        const Json meta = Json::parse(std::string(metaText.begin(), metaText.end()));
        Settings settings = Settings::fromJson(meta);
        Settings::Scope scope(settings);
        // the transport the worker got (ScamLabBurst): its header's flags and grid
        std::vector<uint8_t> head(128);
        { std::ifstream b(shot + "/burst.nch", std::ios::binary); b.read(reinterpret_cast<char*>(head.data()), 128); }
        int32_t hw, hh, flags, grid;
        std::memcpy(&hw, head.data() + 8, 4); std::memcpy(&hh, head.data() + 12, 4);
        std::memcpy(&flags, head.data() + 44, 4); std::memcpy(&grid, head.data() + 52, 4);
        grid = std::max(1, grid);
        const int outW = hw * grid, outH = hh * grid;
        const bool rgbHalf = settings.scamDevSwitch("hybrid_rgb_half", true) || int64_t(outW) * outH > 2147483647LL / 12;
        const bool effectiveMap = (flags & 16) == 0, clipFlags = (flags & 4) != 0;
        const bool highlightMask = settings.scamDevSwitch("why_hl_dezipper", true);

        GLProg prog(shaders);
        PostPipeline p;
        p.glProg = &prog;
        p.mParameters = Parameters::fromJson(meta["params"]);
        p.mParameters.portraitEnabled = settings.getBoolean("pref_portrait_enabled", false);
        p.mParameters.portraitSkinWarmth = p.mParameters.portraitEnabled && settings.getBoolean("pref_portrait_skin_warmth", true);
        p.merge.lastNormals = meta["normals"].isNull() ? 1 : meta["normals"].integer();
        const auto workerOut = readAll(refDir + "/worker.out");
        const auto reportBytes = readAll(refDir + "/report.txt");
        p.scamRgb = readWorkerOutput(p.merge, workerOut, std::string(reportBytes.begin(), reportBytes.end()), outW, outH, hw, hh, rgbHalf,
                                     effectiveMap, clipFlags, highlightMask);
        p.workSize = *p.mParameters.rawSize;
        GLDrawParams::TileSize() = int64_t(p.mParameters.rawSize->x) * p.mParameters.rawSize->y < 8000000 ? 8 : 256;
        p.computeNoise();
        p.cropSize = *p.mParameters.rawSize;

        // the reference's dumps in node order
        std::vector<std::string> dumps;
        for (int i = 0; i < 64; i++) {
            char prefix[8];
            std::snprintf(prefix, sizeof prefix, "%02d-", i);
            for (const char* name : {"ScamRgb", "ScamHdrDenoise", "WhyHlDezipper", "WhyMsDenoise", "WhyStats", "WhyFusion", "WhyLumaSharpen",
                                     "WhyFused", "WhyLumaS", "WhyCombine"}) {
                const std::string path = refDir + "/dump/" + prefix + name + ".f32";
                if (std::ifstream(path).good()) dumps.push_back(path);
            }
        }
        auto dumpOf = [&](const std::string& node) -> std::string {
            for (const auto& d : dumps) if (d.find("-" + node + ".f32") != std::string::npos) return d;
            return "";
        };
        for (const auto& name : nodeNames) {
            if (name == "ScamRgb") p.add(std::make_unique<ScamRgb>());
            else if (name == "ScamHdrDenoise") p.add(std::make_unique<ScamHdrDenoise>());
            else if (name == "WhyHlDezipper") {
                if (!p.merge.lastHlMask) throw std::runtime_error("no highlight mask from the worker");
                p.add(std::make_unique<WhyHlDezipper>(*p.merge.lastHlMask));
            } else if (name == "WhyMsDenoise") p.add(std::make_unique<WhyMsDenoise>());
            else if (name == "WhyStats") p.add(std::make_unique<WhyStats>());
            else if (name == "WhyLumaSharpen") p.add(std::make_unique<WhyLumaSharpen>());
            else if (name == "WhyFusion") p.add(std::make_unique<WhyFusion>());
            else if (name == "WhyCombine") p.add(std::make_unique<WhyCombine>());
            else if (name == "full") {}
            else throw std::runtime_error("node " + name + " is not ported yet");
        }
        // WhyStats issues WhyLumaSharpen's passes early (PostPipeline.BuildDefaultPipeline: whyStats.earlySharpen)
        for (auto& a : p.Nodes)
            if (auto* stats = dynamic_cast<WhyStats*>(a.get()))
                for (auto& b : p.Nodes)
                    if (auto* sharpen = dynamic_cast<WhyLumaSharpen*>(b.get())) stats->earlySharpen = sharpen;
        int failures = 0;
        if (nodeNames.size() == 1 && nodeNames[0] == "full") {
            // the whole pipeline (BuildDefaultPipeline + the final output) against the reference's photo.rgba
            const auto t0 = std::chrono::steady_clock::now();
            const PostOutput photo = runPostPipeline(p);
            const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
            const auto ref = readAll(refDir + "/photo.rgba");
            uint32_t hdr[4];
            std::memcpy(hdr, ref.data(), 16);
            size_t diff = 0, maxd = 0, n = photo.rgba.size();
            if (int(hdr[1]) != photo.width || int(hdr[2]) != photo.height) {
                std::printf("photo %dx%d, the reference %ux%u\n", photo.width, photo.height, hdr[1], hdr[2]);
                return 3;
            }
            double sum = 0;
            for (size_t i = 0; i < n; i++) {
                if (i % 4 == 3) continue;
                const int d = std::abs(int(photo.rgba[i]) - int(ref[16 + i]));
                if (d) diff++;
                maxd = std::max(maxd, size_t(d));
                sum += d;
            }
            std::printf("photo %dx%d in %.0f ms: %zu of %zu channel values differ, max %zu, mean %.4f\n", photo.width, photo.height, ms, diff,
                        n / 4 * 3, maxd, sum / double(n / 4 * 3));
            if (!save.empty()) {
                std::ofstream f(save + "/photo.rgba", std::ios::binary);
                const uint32_t h2[4] = {0x38524353u, uint32_t(photo.width), uint32_t(photo.height), 4u};
                f.write(reinterpret_cast<const char*>(h2), 16);
                f.write(reinterpret_cast<const char*>(photo.rgba.data()), std::streamsize(photo.rgba.size()));
            }
            return diff == 0 ? 0 : 3;
        }
        // the «Why» state textures the reference dumps under their stage names (WhyCombine's ScamDiagnostics.gpu calls)
        auto checkState = [&](const char* stage, const Texture& t, int channels) {
            const std::string ref = dumpOf(stage);
            if (ref.empty() || !t) return;
            const F32 r = readF32(ref);
            std::vector<float> ours = readTexture(*t);
            const std::string line = compare(ours, r, channels);
            std::printf("%s: %s\n", stage, line.c_str());
            if (line.rfind("0 of", 0) != 0) failures++;
        };
        for (size_t i = 0; i < p.Nodes.size(); i++) {
            Node& node = *p.Nodes[i];
            const auto t0 = std::chrono::steady_clock::now();
            node.BeforeCompile();
            node.Compile();
            node.BeforeRun();
            node.Run();
            if (!prog.closed) { prog.drawBlocks(node.GetProgTex()); prog.closed = true; }
            glFinish();
            const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
            const std::string ref = dumpOf(node.Name);
            if (ref.empty()) { std::printf("%s: %.0f ms, no reference dump\n", node.Name.c_str(), ms); continue; }
            const F32 r = readF32(ref);
            std::vector<float> ours = readTexture(*node.WorkingTexture);
            if (r.w != node.WorkingTexture->mSize.x || r.h != node.WorkingTexture->mSize.y) {
                std::printf("%s: size %dx%d, the reference %dx%d\n", node.Name.c_str(), node.WorkingTexture->mSize.x, node.WorkingTexture->mSize.y, r.w, r.h);
                failures++;
                continue;
            }
            const std::string line = compare(ours, r, node.WorkingTexture->mFormat.mChannels);
            std::printf("%s: %.0f ms, %s\n", node.Name.c_str(), ms, line.c_str());
            if (line.rfind("0 of", 0) != 0) failures++;
            if (!save.empty()) writeF32(save + "/" + node.Name + ".f32", r.w, r.h, 4, ours.data());
            if (inject) {
                // the reference's values into this node's output (RGBA float upload converts as the GL would store them)
                glBindTexture(GL_TEXTURE_2D, node.WorkingTexture->mTextureID);
                glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, r.w, r.h, GL_RGBA, GL_FLOAT, r.v.data());
            }
        }
        if (p.why && p.why->lumaS) checkState("WhyLumaS", p.why->lumaS, 1);
        if (p.why && p.why->fused) checkState("WhyFused", p.why->fused, 1);
        checkGl("end");
        prog.close();
        return failures == 0 ? 0 : 3;
    } catch (const std::exception& e) {
        std::fprintf(stderr, "POST NODE CHECK FAILED: %s\n", e.what());
        return 1;
    }
}
