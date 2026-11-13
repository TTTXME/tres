// The photo editor's C++ port (ios/Editor) against Android's own (HostEditRender under ios/tools/android-host): the same
// sources and EditState cases, the same frames - the preview of a 640 x 480 view, the crop tab's frame, the histogram's render,
// the copy's tiles - compared byte for byte with the reference dumps.
//   edit_check <shaders root> <input dir> <reference dir> [--save <dir>] [--outmax N]
// P633: a picture's state with "wm":{"rm":true} renders the picture with a stand-in watermark drawn over WM_RECTS and its record
// (Editor/WatermarkPatch.hpp: the clean preview, the export's picture restored) - the frames must be the reference's of the
// clean picture (Android renders it: no record there, so «Убрать» changes nothing).
//   edit_check --wm make <dir>    the iPhone's records (cpp*.rec, cpp.jpg / .webp / .heic, the containers' bases, rects, spec)
//   edit_check --wm check <dir>   after HostEditRender.watermarkRecord: Android's records read here, the iPhone's read there
// Build (Mesa): g++ -std=c++17 -O2 -ffp-contract=off -Iios/ScamGL/include ios/tools/edit_check.cpp -lEGL -lGLESv2 -lz -pthread
// Run with EGL_PLATFORM=surfaceless.
#include "../Editor/EditExport.hpp"
#include "../Editor/WatermarkPatch.hpp"

#include <zlib.h>

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

// ───── P633: the watermark's record ─────

/// zlib's raw deflate / inflate (the iPhone: Foundation's NSData compression), as Codec wants them.
static wm::Codec hostCodec() {
    wm::Codec c;
    c.deflateRaw = [](const uint8_t* p, size_t n) {
        z_stream z{};
        if (deflateInit2(&z, 6, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY) != Z_OK) throw std::runtime_error("deflateInit2");
        std::vector<uint8_t> out(deflateBound(&z, uLong(n)) + 16);
        z.next_in = const_cast<Bytef*>(p);
        z.avail_in = uInt(n);
        z.next_out = out.data();
        z.avail_out = uInt(out.size());
        const int r = deflate(&z, Z_FINISH);
        out.resize(z.total_out);
        deflateEnd(&z);
        if (r != Z_STREAM_END) throw std::runtime_error("deflate");
        return out;
    };
    c.inflateRaw = [](const uint8_t* p, size_t n, uint8_t* out, size_t outLen) {
        z_stream z{};
        if (inflateInit2(&z, -15) != Z_OK) return false;
        z.next_in = const_cast<Bytef*>(p);
        z.avail_in = uInt(n);
        z.next_out = out;
        z.avail_out = uInt(outLen);
        const int r = inflate(&z, Z_FINISH);
        const bool ok = z.total_out == outLen && (r == Z_STREAM_END || r == Z_OK || r == Z_BUF_ERROR);
        inflateEnd(&z);
        return ok;
    };
    return c;
}

/// The rectangles the stand-in watermark and its record take (WatermarkArea's kind: 16 px blocks, corners and a band).
static std::vector<wm::Rect> wmRects(int w, int h) {
    auto snap = [](int v, int lim) { return std::max(0, std::min(lim, v / 16 * 16)); };
    std::vector<wm::Rect> r;
    r.push_back(wm::Rect{0, snap(h - 112, h), snap(w * 2 / 5, w), h});
    r.push_back(wm::Rect{snap(w - 192, w), snap(h - 64, h), w, h});
    r.push_back(wm::Rect{snap(w / 3, w), 0, snap(w / 3 + 96, w), 48});
    return r;
}

static void writeAll(const std::string& path, const std::vector<uint8_t>& b) {
    std::ofstream o(path, std::ios::binary);
    o.write(reinterpret_cast<const char*>(b.data()), std::streamsize(b.size()));
}

/// The words of `rect` of RGBA8 rows (R in the low byte); `ten`: as the 10-bit words of the same values ((v << 2) | (v >> 6), A 3).
static std::vector<uint32_t> wordsIn(const std::vector<uint8_t>& px, int w, const wm::Rect& r, bool ten) {
    std::vector<uint32_t> out;
    for (int y = r.top; y < r.bottom; y++)
        for (int x = r.left; x < r.right; x++) {
            const uint8_t* p = px.data() + (size_t(y) * w + x) * 4;
            if (ten) {
                auto t = [](uint32_t v) { return (v << 2) | (v >> 6); };
                out.push_back(t(p[0]) | t(p[1]) << 10 | t(p[2]) << 20 | 3u << 30);
            } else {
                out.push_back(uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | 255u << 24);
            }
        }
    return out;
}

/// The synthetic wide picture (4160 x 1040: one rectangle over the piece limit, cut in two), the same in HostEditRender.
static std::vector<uint8_t> bigPicture(int& w, int& h) {
    w = 4160;
    h = 1040;
    std::vector<uint8_t> px(size_t(w) * h * 4);
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
            uint8_t* p = px.data() + (size_t(y) * w + x) * 4;
            p[0] = uint8_t((x * 7 + y * 3) & 255);
            p[1] = uint8_t((x ^ y) & 255);
            p[2] = uint8_t(((x * y) >> 4) & 255);
            p[3] = 255;
        }
    return px;
}

static const char* WM_SPEC = "{\"layout\":\"badge\",\"position\":\"br\",\"showText\":true,\"text\":\"SCAMERA «Тест» \\/ 1\",\"device\":true,"
                             "\"lens\":false,\"aperture\":true,\"shutter\":false,\"iso\":true,\"time\":true,\"dateFormat\":\"text\",\"logo\":true,"
                             "\"font\":\"serif\",\"colour\":\"auto\",\"size\":7.5,\"opacity\":0.8500000238418579,\"margin\":3}";

static std::vector<uint8_t> containerBase(const std::string& kind) {
    std::vector<uint8_t> b;
    if (kind == "jpg") {
        b = {0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0};
        for (int i = 0; i < 40; i++) b.push_back(uint8_t(i * 37));
        b.insert(b.end(), {0xFF, 0xD9});
    } else if (kind == "webp") {
        b = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', 'L', 21, 0, 0, 0};
        for (int i = 0; i < 22; i++) b.push_back(uint8_t(i * 11));
        const uint32_t riff = uint32_t(b.size() - 8);
        for (int k = 0; k < 4; k++) b[4 + k] = uint8_t(riff >> (8 * k));
    } else {
        b = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0, 'm', 'i', 'f', '1', 'h', 'e', 'i', 'c',
             0, 0, 0, 40, 'm', 'd', 'a', 't'};
        for (int i = 0; i < 32; i++) b.push_back(uint8_t(i * 5 + 1));
    }
    return b;
}

static int wmFailures = 0, wmChecks = 0;

static void wmExpect(bool ok, const std::string& what) {
    wmChecks++;
    std::printf("%-58s %s\n", what.c_str(), ok ? "same" : "DIFF");
    if (!ok) wmFailures++;
}

/// A record's pieces against the words the rectangles of `px` hold (`ten`: their 10-bit form).
static bool piecesMatch(const wm::Patch& p, const std::vector<uint8_t>& px, int w, bool ten, const wm::Codec& codec) {
    for (size_t i = 0; i < p.pieces.size(); i++)
        if (p.wordsOf(i, codec) != wordsIn(px, w, p.pieces[i].rect, ten)) return false;
    return true;
}

static int wmMain(const std::string& mode, const std::string& dir) {
    const wm::Codec codec = hostCodec();
    const Rgba pic = readRgba(dir + "/picture.rgba");
    int bw = 0, bh = 0;
    const std::vector<uint8_t> big = bigPicture(bw, bh);
    const std::vector<wm::Rect> rects = wmRects(pic.w, pic.h);
    if (mode == "make") {
        std::ofstream rf(dir + "/rects.txt");
        for (const wm::Rect& r : rects) rf << r.left << ' ' << r.top << ' ' << r.right << ' ' << r.bottom << '\n';
        std::ofstream(dir + "/spec.txt", std::ios::binary) << WM_SPEC;
        const wm::Patch p = wm::capture(pic.px.data(), pic.w, pic.h, size_t(pic.w) * 4, rects, WM_SPEC, wm::SPACE_SRGB, true, codec);
        const wm::Patch pb = wm::capture(big.data(), bw, bh, size_t(bw) * 4, {wm::Rect{0, 0, bw, bh}}, WM_SPEC, wm::SPACE_P3, true, codec);
        std::printf("iPhone records: %s, %s\n", p.describe().c_str(), pb.describe().c_str());
        writeAll(dir + "/cpp.rec", p.toBytes());
        writeAll(dir + "/cpp_big.rec", pb.toBytes());
        for (const char* kind : {"jpg", "webp", "heic"}) {
            std::vector<uint8_t> base = containerBase(kind);
            writeAll(dir + "/base." + kind, base);
            if (!wm::store::append(base, p.toBytes())) throw std::runtime_error(std::string("not appended to ") + kind);
            writeAll(dir + "/cpp." + kind, base);
        }
        return 0;
    }
    // Android's records here: every piece's words, the bytes written again, the containers, the restore
    struct Named { const char* name; const std::vector<uint8_t>* px; int w; bool ten; };
    const Named recs[] = {{"java.rec", &pic.px, pic.w, false}, {"java10.rec", &pic.px, pic.w, true}, {"java_big.rec", &big, bw, false}};
    for (const Named& n : recs) {
        const std::vector<uint8_t> bytes = readAll(dir + "/" + n.name);
        const wm::Patch p = wm::Patch::fromBytes(bytes.data(), bytes.size());
        wmExpect(p.verify(codec) && piecesMatch(p, *n.px, n.w, n.ten, codec), std::string("Android ") + n.name + " read: " + p.describe());
        wmExpect(p.toBytes() == bytes, std::string("Android ") + n.name + " written again byte for byte");
        wmExpect(p.spec == WM_SPEC, std::string("Android ") + n.name + " spec");
    }
    // zlib's deflate at Android's level makes the iPhone's record byte for byte Android's here (the phone's own deflate differs)
    wmExpect(readAll(dir + "/cpp.rec") == readAll(dir + "/java.rec") && readAll(dir + "/cpp_big.rec") == readAll(dir + "/java_big.rec"),
             "the records made here are Android's byte for byte");
    const std::vector<uint8_t> rec = readAll(dir + "/java.rec");
    for (const char* kind : {"jpg", "webp", "heic"}) {
        std::vector<uint8_t> base = readAll(dir + "/base." + kind);
        const std::vector<uint8_t> java = readAll(dir + "/java." + kind);
        wm::store::append(base, rec);
        wmExpect(base == java, std::string("WatermarkStore.append ") + kind + " byte for byte");
        const auto back = wm::store::read(java.data(), java.size());
        wmExpect(back && *back == rec, std::string("WatermarkStore.read Android's ") + kind);
    }
    // the iPhone's records as Android read them (HostEditRender: <name>.words, each piece's rect and words)
    for (const char* name : {"cpp.jpg", "cpp.webp", "cpp.heic", "cpp_big.rec"}) {
        const bool isBig = std::string(name) == "cpp_big.rec";
        const std::vector<uint8_t> dump = readAll(dir + "/android_" + name + ".words");
        const std::vector<uint8_t>& px = isBig ? big : pic.px;
        const int w = isBig ? bw : pic.w;
        bool ok = dump.size() >= 4;
        size_t at = 0;
        auto i32 = [&]() {
            if (dump.size() - at < 4) throw std::runtime_error("short dump");
            int32_t v;
            std::memcpy(&v, dump.data() + at, 4);
            at += 4;
            return v;
        };
        int pieces = ok ? i32() : 0;
        for (int i = 0; ok && i < pieces; i++) {
            wm::Rect r;
            r.left = i32();
            r.top = i32();
            r.right = i32();
            r.bottom = i32();
            const std::vector<uint32_t> want = wordsIn(px, w, r, false);
            if (dump.size() - at < want.size() * 4 || std::memcmp(dump.data() + at, want.data(), want.size() * 4) != 0) ok = false;
            at += want.size() * 4;
        }
        wmExpect(ok && pieces > 0, std::string("iPhone ") + name + " read by Android (" + std::to_string(pieces) + " pieces)");
    }
    // the restore: the stand-in watermark over the rectangles, Android's record back - the picture again
    {
        std::vector<uint8_t> dirty = pic.px;
        for (const wm::Rect& r : rects)
            for (int y = r.top; y < r.bottom; y++)
                for (int x = r.left; x < r.right; x++)
                    for (int c = 0; c < 3; c++) dirty[(size_t(y) * pic.w + x) * 4 + c] ^= 0xFF;
        const wm::Patch p = wm::Patch::fromBytes(rec.data(), rec.size());
        p.restore(dirty.data(), size_t(pic.w) * 4, codec);
        bool same = true;
        for (size_t i = 0; i < dirty.size(); i++) if (i % 4 != 3 && dirty[i] != pic.px[i]) same = false;
        wmExpect(same, "Android's record restores the picture");
    }
    std::printf("edit_check --wm: %d checks, %d differ\n", wmChecks, wmFailures);
    return wmFailures ? 1 : 0;
}

int main(int argc, char** argv) {
    if (argc >= 4 && std::string(argv[1]) == "--wm") {
        try {
            return wmMain(argv[2], argv[3]);
        } catch (const std::exception& e) {
            std::fprintf(stderr, "edit_check --wm: %s\n", e.what());
            return 1;
        }
    }
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
                // P633: «Убрать» on a picture: a stand-in watermark drawn over the rectangles, its record restores them
                const bool removal = rgba && st.wmRemove;
                PreviewSource shown = src;
                std::vector<uint8_t> restored;
                if (removal) {
                    const wm::Codec codec = hostCodec();
                    const auto rects = wmRects(pic.w, pic.h);
                    const wm::Patch rec = wm::capture(pic.px.data(), pic.w, pic.h, size_t(pic.w) * 4, rects, WM_SPEC, wm::SPACE_SRGB, true, codec);
                    std::vector<uint8_t> dirty = pic.px;
                    for (const wm::Rect& q : rects)
                        for (int y = q.top; y < q.bottom; y++)
                            for (int x = q.left; x < q.right; x++)
                                for (int k = 0; k < 3; k++) dirty[(size_t(y) * pic.w + x) * 4 + k] = uint8_t(255 - dirty[(size_t(y) * pic.w + x) * 4 + k] / 2);
                    const std::vector<uint8_t> bytes = rec.toBytes();
                    const wm::Patch back = wm::Patch::fromBytes(bytes.data(), bytes.size());
                    if (!wm::recordFits(back, pic.w, pic.h, 1) || !back.verify(codec)) throw std::runtime_error("the record does not fit");
                    shown.rgba = dirty;
                    shown.rgba = wm::cleanPreview(shown, back, codec);
                    restored = dirty;
                    wm::restorePicture(restored, pic.w, pic.h, back, codec);
                }
                preview(r, shown, st, refDir, tag);
                const long long outMaxPx = c % 2 == 1 ? outMaxSmall : OUT_MAX;
                std::unique_ptr<Reader> reader;
                if (rgba) reader.reset(new PictureReader(removal ? restored.data() : pic.px.data(), pic.w, pic.h, false));
                else reader.reset(new RawReader(full, *rawColour));
                r.invalidate();
                const ExportResult res = renderCopy(r, removal ? shown : src, *reader, st, src.orientation, outMaxPx, nullptr);
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
