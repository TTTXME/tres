// The photo editor's C++ port (ios/Editor) against Android's own (HostEditRender under ios/tools/android-host): the same
// sources and EditState cases, the same frames - the preview of a 640 x 480 view, the crop tab's frame, the histogram's render,
// the copy's tiles - compared byte for byte with the reference dumps.
//   edit_check <shaders root> <input dir> <reference dir> [--save <dir>] [--outmax N]
// Build (Mesa): g++ -std=c++17 -O2 -ffp-contract=off -Iios/ScamGL/include ios/tools/edit_check.cpp -lEGL -lGLESv2 -pthread
// Run with EGL_PLATFORM=surfaceless.
#include "../Editor/EditExport.hpp"

#include <EGL/egl.h>

#include <algorithm>
#include <cstdio>
#include <dirent.h>
#include <fstream>
#include <iterator>
#include <sstream>

using namespace scam_edit;

static std::vector<uint8_t> readAll(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) throw std::runtime_error("cannot read " + path);
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
}

struct Rgba { int w = 0, h = 0; std::vector<uint8_t> px; };

static Rgba readRgba(const std::string& path) {
    const auto b = readAll(path);
    Rgba r;
    uint32_t hdr[4];
    std::memcpy(hdr, b.data(), 16);
    r.w = int(hdr[1]);
    r.h = int(hdr[2]);
    r.px.assign(b.begin() + 16, b.end());
    return r;
}

static void writeRgba(const std::string& path, int w, int h, const std::vector<uint8_t>& px) {
    std::ofstream o(path, std::ios::binary);
    const uint32_t hdr[4] = {0x38524353u, uint32_t(w), uint32_t(h), 4};
    o.write(reinterpret_cast<const char*>(hdr), 16);
    o.write(reinterpret_cast<const char*>(px.data()), std::streamsize(px.size()));
}

static int failures = 0, compared = 0;
static std::string saveDir;

static void compare(const std::string& refDir, const std::string& name, int w, int h, const std::vector<uint8_t>& px) {
    if (!saveDir.empty()) writeRgba(saveDir + "/" + name, w, h, px);
    Rgba ref;
    try {
        ref = readRgba(refDir + "/" + name);
    } catch (const std::exception& e) {
        std::printf("%-22s no reference\n", name.c_str());
        failures++;
        return;
    }
    compared++;
    if (ref.w != w || ref.h != h) {
        std::printf("%-22s SIZE %dx%d vs reference %dx%d\n", name.c_str(), w, h, ref.w, ref.h);
        failures++;
        return;
    }
    size_t diff = 0, n = 0;
    int maxd = 0;
    for (size_t i = 0; i < px.size(); i++) {
        if (i % 4 == 3) continue;
        n++;
        const int d = std::abs(int(px[i]) - int(ref.px[i]));
        if (d) diff++;
        maxd = std::max(maxd, d);
    }
    std::printf("%-22s %5dx%-5d %s  differing %zu of %zu values, max %d\n", name.c_str(), w, h, diff ? "DIFF" : "same", diff, n, maxd);
    if (diff) failures++;
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

static const float BG[3] = {0x12 / 255.f, 0x12 / 255.f, 0x12 / 255.f};

static void preview(EditRenderer& r, const PreviewSource& s, const EditState& st, const std::string& refDir, const std::string& tag) {
    r.invalidate();
    bool owned = false;
    const GLuint srcTex = s.upload(r, owned);
    r.setProxySource(srcTex, s.width, s.height);
    const EditMath::Uniforms u = EditMath::Uniforms::of(st, *s.colour);
    const auto o = s.oriented();
    r.updateProxy(u);
    // P619: the «LUT» tab's look (EditorView: the tables EditLook loaded for the state)
    r.setLook(EditLook::on(st) ? EditLook::applied(st, EditLook::blocking(st)) : std::nullopt);
    r.renderSource(srcTex, s.width, s.height, float(std::max(s.width, s.height)), CropMath::Affine(), u, true);
    EditRenderer::Target view;
    for (int mode = 0; mode < 2; mode++) {
        const int vw = 640, vh = 480;
        view.ensure(vw, vh, GL_RGBA8, false, 1);
        const Rect c = canvas(float(vw), float(vh), 0.f, st, o[0], o[1], mode == 1);
        EditRenderer::Finish f = finishFor(r, st, c, mode == 1, o, s.orientation, BG);
        f.fbo = view.fbo;
        f.w = vw;
        f.h = vh;
        r.finish(f, st, u);
        compare(refDir, tag + (mode == 0 ? "_view.rgba" : "_crop.rgba"), vw, vh, readTarget(vw, vh));
    }
    const auto px = CropMath::cropPixels(st, o[0], o[1]);
    const double k = 160.0 / std::max(px[0], px[1]);
    const int w = std::max(8, int(jroundD(px[0] * k))), h = std::max(8, int(jroundD(px[1] * k)));
    r.hist.ensure(w, h, GL_RGBA8, false, 1);
    EditRenderer::Finish f = finishFor(r, st, Rect{0, 0, float(w), float(h)}, false, o, s.orientation, BG);
    f.fbo = r.hist.fbo;
    f.w = w;
    f.h = h;
    r.finish(f, st, u);
    compare(refDir, tag + "_hist.rgba", w, h, readTarget(w, h));
    view.release();
    if (owned) EditGl::deleteTexture(srcTex);
}

int main(int argc, char** argv) {
    if (argc < 4) {
        std::fprintf(stderr, "edit_check <shaders root> <input dir> <reference dir> [--save <dir>] [--outmax N]\n");
        return 2;
    }
    const std::string shaders = argv[1], in = argv[2], refDir = argv[3];
    long long outMaxSmall = 1000000;
    for (int i = 4; i + 1 < argc; i++) {
        if (std::string(argv[i]) == "--save") saveDir = argv[++i];
        else if (std::string(argv[i]) == "--outmax") outMaxSmall = std::atoll(argv[++i]);
    }
    try {
        makeContext();
        EditRenderer r;
        r.init(shaders);
        std::vector<std::string> cases;
        {
            std::ifstream f(in + "/cases.txt");
            for (std::string line; std::getline(f, line);) cases.push_back(line);
        }
        std::vector<std::string> files;
        if (DIR* d = opendir(in.c_str())) {
            while (dirent* e = readdir(d)) files.push_back(e->d_name);
            closedir(d);
        }
        std::sort(files.begin(), files.end());
        for (const std::string& name : files) {
            const bool rgba = name.size() > 5 && name.substr(name.size() - 5) == ".rgba";
            const bool dng = name.size() > 4 && name.substr(name.size() - 4) == ".dng";
            if (!rgba && !dng) continue;
            const std::string base = name.substr(0, name.rfind('.'));
            PreviewSource src;
            Rgba pic;
            std::vector<uint8_t> file;
            DngRaw::Image full;
            std::shared_ptr<RawColour> rawColour;
            if (rgba) {
                pic = readRgba(in + "/" + name);
                src.width = pic.w;
                src.height = pic.h;
                src.rgba = pic.px;
                src.colour = std::make_shared<EditMath::DisplayColour>(false, false);
            } else {
                file = readAll(in + "/" + name);
                src = openDngPreview(file.data(), file.size());
                full = DngRaw::decode(file.data(), file.size(), 1);
                rawColour = std::static_pointer_cast<RawColour>(src.colour);
            }
            for (size_t c = 0; c < cases.size(); c++) {
                if (cases[c].find_first_not_of(" \t\r") == std::string::npos) continue;
                EditState st;
                if (!EditState::fromJson(cases[c], st)) throw std::runtime_error("case " + std::to_string(c) + " is not a state");
                const std::string tag = base + "_" + std::to_string(c);
                preview(r, src, st, refDir, tag);
                const long long outMaxPx = c % 2 == 1 ? outMaxSmall : OUT_MAX;
                std::unique_ptr<Reader> reader;
                if (rgba) reader.reset(new PictureReader(pic.px.data(), pic.w, pic.h, false));
                else reader.reset(new RawReader(full, *rawColour));
                r.invalidate();
                const ExportResult res = renderCopy(r, src, *reader, st, src.orientation, outMaxPx, nullptr);
                compare(refDir, tag + "_export.rgba", res.W, res.H, res.rgba);
            }
        }
        r.release();
    } catch (const std::exception& e) {
        std::fprintf(stderr, "edit_check: %s\n", e.what());
        return 1;
    }
    std::printf("edit_check: %d frames compared, %d differ or missing\n", compared, failures);
    return failures ? 1 : 0;
}
