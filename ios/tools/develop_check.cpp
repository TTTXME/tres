// P665 host check of the gallery developer's C++ (Engine/post/DevelopRecord.hpp, DevelopRun.hpp, Editor/EditDevelop.hpp) on the
// cases of the Android tests (DevelopRecordTest, DevelopRunTest, EditDevelopTest): the record through a file and back (zlib as the
// platform's deflate), the Parameters field for field, a reduced grid and a Sabre 2x shot's final size, the settings with the
// changes, the maps' resampling, the crops, the demosaics and the halves. No GL: the pipeline's run itself is the post checks'.
//   g++ -std=c++17 -O1 -ffp-contract=off -Iios/ScamGL/include -Iapp/src/main/cpp -Iapp/src/main/cpp/third_party/tone_port/include
//       ios/tools/develop_check.cpp -lz -pthread -o develop_check && ./develop_check
#include "../Engine/post/DevelopRun.hpp"
#include "../Editor/EditDevelop.hpp"

#include <zlib.h>

#include <cstdio>
#include <iostream>

using namespace scam_post;

static int failures = 0;
#define CHECK(c) do { if (!(c)) { std::fprintf(stderr, "FAIL %s:%d %s\n", __FILE__, __LINE__, #c); failures++; } } while (0)
#define NEAR(a, b, e) CHECK(std::fabs(double(a) - double(b)) <= (e))

static DevelopRecord::Codec zlibCodec() {
    DevelopRecord::Codec c;
    c.deflateRaw = [](const uint8_t* p, size_t n) {
        z_stream z{};
        deflateInit2(&z, 1, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY);
        std::vector<uint8_t> out(deflateBound(&z, uLong(n)) + 16);
        z.next_in = const_cast<Bytef*>(p);
        z.avail_in = uInt(n);
        z.next_out = out.data();
        z.avail_out = uInt(out.size());
        deflate(&z, Z_FINISH);
        out.resize(z.total_out);
        deflateEnd(&z);
        return out;
    };
    c.inflateRaw = [](const uint8_t* p, size_t n, uint8_t* out, size_t outLen) {
        z_stream z{};
        inflateInit2(&z, -15);
        z.next_in = const_cast<Bytef*>(p);
        z.avail_in = uInt(n);
        z.next_out = out;
        z.avail_out = uInt(outLen);
        const int rc = inflate(&z, Z_FINISH);
        const bool ok = (rc == Z_STREAM_END || rc == Z_OK || rc == Z_BUF_ERROR) && z.total_out == outLen;
        inflateEnd(&z);
        return ok;
    };
    return c;
}

static Parameters shot() {
    Parameters p;
    p.rawSize = Point{4096, 3072};
    p.sensorPix = Rect{0, 0, 4096, 3072};
    p.hybridFinalSize = Point{2048, 1536};
    p.whitePoint = std::vector<float>{.5f, 1.f, .7f};
    p.sensorToProPhoto = std::vector<float>{1, 2, 3, 4, 5, 6, 7, 8, 9};
    p.CCT = ColorCorrection{{9, 8, 7, 6, 5, 4, 3, 2, 1}};
    p.gainMap = std::vector<float>{1, 1.5f, 1.5f, 2, 1, 1, 1, 1};
    p.mapSize = Point{2, 1};
    p.iso = 640;
    p.cameraRotation = 90;
    p.filmFrameScale = .8f;
    p.portraitFaces = std::vector<float>{.1f, .2f, .3f, .4f};
    p.sceneDuv = NAN;
    NoiseModel n;
    n.baseModel = {{1e-4, 1e-6}, {2e-4, 2e-6}, {3e-4, 3e-6}};
    n.computeModel = {{1e-5, 1e-7}, {2e-5, 2e-7}, {3e-5, 3e-7}};
    n.AnalogueISO = 100;
    n.SensivityISO = 640;
    p.noiseModeler = n;
    p.tonemapCurves = {std::vector<float>{0, 0, 1, 1}, std::nullopt, std::vector<float>{0, .1f}};
    return p;
}

static Settings prefs() {
    Settings s;
    s.prefs["pref_scam_hybrid_why_sharp_gain"] = Pref::string("1.25");
    s.prefs["pref_scam_hybrid_tone_version"] = Pref::string("v2");
    s.prefs["pref_scam_hybrid_denoise"] = Pref::boolean(true);
    s.prefs["pref_film_sim_stock"] = Pref::string("kodak_portra_400");
    s.prefs["pref_scam_hybrid_diagnostics"] = Pref::boolean(true);
    s.prefs["pref_keep_raw_burst"] = Pref::boolean(true);
    s.prefs["pref_some_int"] = Pref::integer(3);
    s.prefs["pref_some_float"] = Pref::real(0.1f);
    Pref set;
    set.type = Pref::Type::Set;
    set.set = {"a", "b \"q\""};
    s.prefs["pref_some_set"] = set;
    s.dev["hybrid_sun_bloom_no_short"] = 0.f;
    return s;
}

static DevelopRecord::Worker worker() {
    DevelopRecord::Worker w;
    w.bentoApplied = true;
    w.bentoFactor = 6.5f;
    w.bentoUsClipped = .02f;
    w.effectiveMedian = 11.f;
    w.noiseFactor = .8f;
    w.normals = 12;
    w.report = "HYBRID FAST MERGE HIGHLIGHT: accumulated validity 0.9\nHYBRID NOISE CHECK: x -> model x0.75";
    return w;
}

/// the record as the camera appends it to a DNG and the editor reads it back from the file's end
static DevelopRecord throughTheFile(const DevelopRecord& r) {
    const DevelopRecord::Codec c = zlibCodec();
    std::vector<uint8_t> file = {'I', 'I', 42, 0, 8, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    const std::vector<uint8_t> tail = r.appendix(c);
    file.insert(file.end(), tail.begin(), tail.end());
    const std::optional<DevelopRecord> back = DevelopRecord::read(file.data(), file.size(), c);
    CHECK(back.has_value());
    // a flipped byte in a section fails the CRC
    std::vector<uint8_t> bad = file;
    bad[18 + 30] ^= 0x40;
    CHECK(!DevelopRecord::read(bad.data(), bad.size(), c).has_value());
    return back ? *back : DevelopRecord();
}

static DevelopRecord record(const Parameters& p, float headroom, float zoom, bool isz) {
    Settings s = prefs();
    return throughTheFile(DevelopRecord::make(p.toJson(), s, worker(), headroom, 4096, 3072, zoom, isz, 665));
}

int main() {
    // the record: the shot's Parameters field for field, its settings, dev lines, the merge's numbers, headroom
    {
        const Parameters p = shot();
        DevelopRecord r = record(p, 4.f, 1.f, false);
        CHECK(Develop::parameters(r, 4096, 3072).toJson() == p.toJson());
        NEAR(r.headroom(), 4.f, 0);
        CHECK(r.worker().toJson() == worker().toJson());
        CHECK(r.meta["build"].integer() == 665);
        Settings v = Develop::settings(r, {});
        CHECK(!v.getBoolean("pref_scam_hybrid_diagnostics", true));
        CHECK(!v.getBoolean("pref_keep_raw_burst", true));
        CHECK(!v.getBoolean("pref_scam_lab_keep", true));
        CHECK(!v.getBoolean("pref_debug_merge_stamp", true));
        CHECK(v.getString("pref_scam_hybrid_why_sharp_gain", "") == "1.25");
        CHECK(v.getBoolean("pref_scam_hybrid_denoise", false));
        CHECK(v.get("pref_some_int") && v.get("pref_some_int")->type == Pref::Type::Int && v.getInteger("pref_some_int", 0) == 3);
        CHECK(v.get("pref_some_float") && v.get("pref_some_float")->type == Pref::Type::Float && v.getFloat("pref_some_float", 0) == 0.1f);
        CHECK(v.get("pref_some_set") && v.get("pref_some_set")->set.size() == 2 && v.get("pref_some_set")->set[1] == "b \"q\"");
        CHECK(v.scamDevValue("hybrid_sun_bloom_no_short") && *v.scamDevValue("hybrid_sun_bloom_no_short") == 0.f);
        const Develop::Changes changes = Develop::changesFromJson("{\"pref_scam_hybrid_why_sharp_gain\":\"0.5\",\"pref_film_sim_stock\":null,"
                                                                  "\"pref_scam_hybrid_denoise\":false}");
        Settings c = Develop::settings(r, changes);
        CHECK(c.getString("pref_scam_hybrid_why_sharp_gain", "") == "0.5");
        CHECK(!c.contains("pref_film_sim_stock"));
        CHECK(!c.getBoolean("pref_scam_hybrid_denoise", true));
    }
    // a reduced grid scales only its size
    {
        DevelopRecord r = record(shot(), 1.f, 1.f, false);
        Parameters q = Develop::parameters(r, 1024, 768);
        CHECK(q.rawSize && *q.rawSize == (Point{1024, 768}));
        CHECK(q.sensorPix && q.sensorPix->right == 1024 && q.sensorPix->bottom == 768);
        CHECK(q.hybridFinalSize && *q.hybridFinalSize == (Point{512, 384}));
        NEAR(q.outputScale, .25f, 1e-6);
        Parameters a = shot();
        q.rawSize = a.rawSize;
        q.sensorPix = a.sensorPix;
        q.hybridFinalSize = a.hybridFinalSize;
        q.outputScale = a.outputScale;
        CHECK(q.toJson() == a.toJson());
    }
    // a Sabre 2x shot's final size is against the DNG's grid
    {
        Parameters p = shot();
        p.rawSize = Point{8192, 6144};
        p.sensorPix = Rect{0, 0, 8192, 6144};
        p.hybridFinalSize = Point{4096, 3072};
        p.outputScale = 2.f;
        const Parameters q = Develop::parameters(record(p, 1.f, 1.f, false), 4096, 3072);
        CHECK(!q.hybridFinalSize);
        NEAR(q.outputScale, 1.f, 1e-6);
    }
    // the maps: the shot's at its size; on a reduced grid the flags OR-ed, the frame map averaged
    {
        std::vector<uint8_t> flags(16);
        flags[0] = 1;
        flags[5] = 4;
        flags[15] = 2;
        CHECK(Develop::map(flags, 4, 4, 4, 4, true) == flags);
        CHECK(Develop::resampled(flags, 4, 4, 2, 2, true) == (std::vector<uint8_t>{5, 0, 0, 2}));
        const std::vector<uint8_t> frames = {10, 20, 30, 40, 10, 20, 30, 40, 50, 50, 0, 0, 50, 50, 0, 0};
        CHECK(Develop::resampled(frames, 4, 4, 2, 2, false) == (std::vector<uint8_t>{15, 35, 50, 0}));
        CHECK(!Develop::map({}, 4, 4, 4, 4, true));
        // the maps and the highlight mask through the file
        DevelopRecord r = DevelopRecord::make(shot().toJson(), prefs(), worker(), 1.f, 4, 4, 1.f, false, 1);
        r.clipFlags = flags;
        r.clipW = r.clipH = 4;
        r.frames = frames;
        r.framesW = r.framesH = 4;
        r.hlMask = {0x00, 0x3C, 0x00, 0x40, 0x00, 0x00, 0xFF, 0x7B};
        r.hlW = r.hlH = 2;
        const DevelopRecord b = throughTheFile(r);
        CHECK(b.clipFlags == flags && b.frames == frames && b.hlMask == r.hlMask && b.hlW == 2);
        const std::optional<MergeInfo::HlMask> m = Develop::hlMask(b, 4096, 4096);
        CHECK(m && m->halves == (std::vector<uint16_t>{0x3C00, 0x4000, 0x0000, 0x7BFF}));
        const std::optional<MergeInfo::HlMask> half = Develop::hlMask(b, 2048, 4096);
        CHECK(half && half->width == 1 && half->height == 1);
    }
    // the crops after the pipeline
    {
        DevelopRecord r = record(shot(), 1.f, 2.f, true);
        NEAR(DevelopRecord::numberOf(r.meta["crop"]["zoom"], 0), 2.0, 0);
        CHECK(DevelopRecord::boolOf(r.meta["crop"]["isz4x3"], false));
        CHECK(DevelopRecord::numberOf(record(shot(), 1.f, NAN, false).meta["crop"]["zoom"], 0) == 1.f);
        PostOutput photo;
        photo.width = 400;
        photo.height = 300;
        photo.rgba.assign(size_t(400) * 300 * 4, 7);
        Develop::crops(r, photo);
        CHECK(photo.width == 200 && photo.height == 150 && photo.rgba.size() == size_t(200) * 150 * 4);
    }
    // not a record: no footer, a later version
    {
        const DevelopRecord::Codec c = zlibCodec();
        const std::vector<uint8_t> plain(64, 1);
        CHECK(!DevelopRecord::read(plain.data(), plain.size(), c));
        DevelopRecord r = DevelopRecord::make("{}", prefs(), worker(), 1.f, 4, 4, 1.f, false, 1);
        r.metaText = "{\"v\":2}";
        const std::vector<uint8_t> t = r.appendix(c);
        CHECK(!DevelopRecord::read(t.data(), t.size(), c));
    }
    // the developer's demosaics (EditDevelopTest)
    {
        using namespace scam_edit;
        CHECK(develop::colour(DngRaw::RGGB, 0, 0) == 0 && develop::colour(DngRaw::RGGB, 1, 1) == 2);
        CHECK(develop::colour(DngRaw::GRBG, 1, 0) == 0 && develop::colour(DngRaw::GRBG, 0, 1) == 2);
        CHECK(develop::colour(DngRaw::GBRG, 1, 0) == 2 && develop::colour(DngRaw::GBRG, 0, 1) == 0);
        CHECK(develop::colour(DngRaw::BGGR, 0, 0) == 2 && develop::colour(DngRaw::BGGR, 1, 1) == 0 && develop::colour(DngRaw::BGGR, 1, 0) == 1);
        auto mosaic = [](int w, int h, int phase, const std::function<std::array<float, 3>(int, int)>& rgb) {
            std::vector<float> m(size_t(w) * h);
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) m[size_t(y) * w + x] = rgb(x, y)[size_t(develop::colour(phase, x, y))];
            return m;
        };
        for (int phase = 0; phase < 4; phase++) {
            const std::vector<float> m = mosaic(8, 6, phase, [](int x, int) { return std::array<float, 3>{.2f, .3f + (x & 1) * .02f, .1f}; });
            const std::vector<float> rgb = develop::superpixel(m, 8, 6, phase, 4.f);
            CHECK(rgb.size() == 36);
            for (size_t i = 0; i < rgb.size(); i += 3) {
                NEAR(rgb[i], .8f, 1e-6);
                NEAR(rgb[i + 1], (.3f + .32f) / 2 * 4.f, 1e-6);
                NEAR(rgb[i + 2], .4f, 1e-6);
            }
            const int w = 24, h = 18;
            auto field = [](int x, int y) {
                return std::array<float, 3>{.1f + .01f * x + .005f * y, .2f + .004f * x + .007f * y, .3f - .003f * x + .002f * y};
            };
            const std::vector<uint8_t> out = develop::mhc(mosaic(w, h, phase, field), w, h, phase, 2.f, false);
            const float* f = reinterpret_cast<const float*>(out.data());
            for (int y = 2; y < h - 2; y++)
                for (int x = 2; x < w - 2; x++)
                    for (int c = 0; c < 3; c++) NEAR(f[(size_t(y) * w + x) * 3 + c], field(x, y)[size_t(c)] * 2.f, 1e-5);
            const std::vector<uint8_t> flat = develop::mhc(mosaic(w, h, phase, [](int, int) { return std::array<float, 3>{.25f, .5f, .125f}; }),
                                                           w, h, phase, 1.f, false);
            const float* g = reinterpret_cast<const float*>(flat.data());
            for (int i = 0; i < w * h; i++) {
                NEAR(g[3 * i], .25f, 1e-6);
                NEAR(g[3 * i + 1], .5f, 1e-6);
                NEAR(g[3 * i + 2], .125f, 1e-6);
            }
        }
        CHECK(develop::toHalf(1.f) == 0x3C00 && develop::toHalf(0.f) == 0 && develop::toHalf(1.f / 3.f) == 0x3555);
        CHECK(develop::toHalf(65504.f) == 0x7BFF && develop::toHalf(1e6f) == 0x7C00 && develop::toHalf(5.96e-8f) == 0x0001);
        CHECK(develop::toHalf(10.f) == 0x4900);
        const std::vector<uint8_t> half = develop::mhc(mosaic(8, 8, DngRaw::RGGB, [](int, int) { return std::array<float, 3>{.25f, .5f, 3.f}; }),
                                                       8, 8, DngRaw::RGGB, 2.f, true);
        const uint16_t* hv = reinterpret_cast<const uint16_t*>(half.data());
        CHECK(hv[0] == develop::toHalf(.5f) && hv[1] == develop::toHalf(1.f) && hv[2] == develop::toHalf(6.f));
        CHECK(develop::binFor(4096, 3072) == 1 && develop::binFor(8160, 6144) == 2 && develop::binFor(16320, 12288) == 4
              && develop::binFor(1000, 800) == 1);
    }
    std::printf(failures ? "develop_check: %d failure(s)\n" : "develop_check: all passed\n", failures);
    return failures ? 1 : 0;
}
