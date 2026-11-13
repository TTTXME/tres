// ScamRgb.java one to one (the default GL model): the worker RGB in, white balance and lens shading, the per-channel highlight
// recovery (clip levels from the histogram plateau, local chroma, RawTherapee's «Inpaint opposed» with its chrominance on the
// GPU), the purple fringe (P399), the sun bloom share, the opposed landing and the clip band. Not ported (they throw): the
// SCAM HDR route (no hybrid), the fringe net (CaNetP «Нейросеть»), a lab fragment's frozen levels, the Sabre 2x grid above
// 64 MP. The half RGB goes up by the CPU repack (HalfRgbUpload's GPU pass gives the same halves: its own exactness check).
#pragma once

#include "../Pipeline.hpp"
#include "../why/WhyToneCore.hpp"

#include <atomic>
#include <thread>

namespace scam_post {

inline float halfToFloat(uint16_t h) {
    const int s = (h >> 15) & 1, e = (h >> 10) & 31, m = h & 1023;
    float v;
    if (e == 0) v = float(m) * (1.f / 16777216.f);
    else if (e == 31) v = m == 0 ? INFINITY : NAN;
    else { const uint32_t bits = uint32_t(((e - 15 + 127) << 23) | (m << 13)); std::memcpy(&v, &bits, 4); }
    return s ? -v : v;
}
inline const std::vector<float>& halfTable() {
    static const std::vector<float> t = [] { std::vector<float> v(65536); for (int h = 0; h < 65536; h++) v[size_t(h)] = halfToFloat(uint16_t(h)); return v; }();
    return t;
}
/// Math.round(float): floor(a + 0.5) without the old 0.49999997 rounding error (exact in double)
inline int javaRound(float a) { return std::isnan(a) ? 0 : int(std::floor(double(a) + 0.5)); }
inline unsigned hardwareThreads() { return std::max(1u, std::thread::hardware_concurrency()); }

namespace WhyCombineHelpers {
inline float hlSoft() { return std::max(0.f, std::min(0.9f, WhyTone::value("hl_soft", 0.7f))); }
inline bool landingDrawn(PostPipeline& p) { return hlSoft() > 0.f && p.film == nullptr; }
}

class ScamRgb : public Node {
public:
    ScamRgb() : Node("", "ScamRgb") {}
    void Compile() override {}

    struct ChannelClip {
        float lo[3] = {0, 0, 0}, hi[3] = {0, 0, 0}, max[3] = {0, 0, 0};
        bool nearClip = false, hiUnflagged = false;
        std::string log;
        bool enabled() const { return lo[1] > 0.f || hi[1] > 0.f; }
    };
    static constexpr float HIST_T0 = 0.80f, HIST_BIN = 0.002f;
    static constexpr int HIST_BINS = 150;
    static constexpr int PLATEAU_WINDOW = 5;
    static constexpr float OPPOSED_RADIUS = 0.033f;

    static float plateau(const int* h, const double* sum, long samples, float searchLo, float searchHi) {
        const int bg1 = javaRound((0.90f - HIST_T0) / HIST_BIN);
        long bg = 0;
        for (int b = 0; b < bg1; b++) bg += h[b];
        const double bgPerBin = double(bg) / double(std::max(1, bg1));
        const int w = PLATEAU_WINDOW;
        const int s0 = std::max(bg1, javaRound((searchLo - HIST_T0) / HIST_BIN));
        const int s1 = std::min(HIST_BINS, javaRound((searchHi - HIST_T0) / HIST_BIN)) - w;
        int best = -1;
        long bestSum = 0;
        for (int b = s0; b <= s1; b++) {
            long s = 0;
            for (int j = 0; j < w; j++) s += h[b + j];
            if (s > bestSum) { bestSum = s; best = b; }
        }
        const double expected = w * bgPerBin;
        const long need = std::max(16L, long(std::ceil(double(samples) * 5e-6)));
        if (best < 0 || bestSum < need || double(bestSum) < 6.0 * expected + 4.0 * std::sqrt(expected + 1.0)) return NAN;
        double m = 0;
        for (int j = 0; j < w; j++) m += sum[best + j];
        return float(m / double(bestSum));
    }

    static void histogram(int* h, double* sum, float t) {
        if (t < HIST_T0) return;
        const int b = int(std::floor((t - HIST_T0) / HIST_BIN));
        if (b >= 0 && b < HIST_BINS) { h[b]++; sum[b] += t; }
    }

    /// channelClip: every 17th pixel's channels into the histograms near 1 (and near k); parts on threads, the same result
    static ChannelClip channelClip(const uint8_t* rgb, size_t bytes, bool half, bool bento, float k, float usClipped, bool flags) {
        ChannelClip cc;
        const size_t n = (half ? bytes / 2 : bytes / 4) / 3;
        const float inv = bento && k > 1.f ? 1.f / k : 0.f;
        const long samples = long((n + 16) / 17);
        struct Part { int hLo[3][HIST_BINS] = {}, hHi[3][HIST_BINS] = {}; double sLo[3][HIST_BINS] = {}, sHi[3][HIST_BINS] = {}; float max[3] = {0, 0, 0}; };
        const int chunks = samples < 65536 ? 1 : int(4 * hardwareThreads());
        std::vector<Part> parts{size_t(chunks)};
        const auto& table = halfTable();
        auto walk = [&](int c) {
            Part& p = parts[size_t(c)];
            const long s1 = samples * (c + 1) / chunks;
            for (long s = samples * c / chunks; s < s1; s++) {
                const size_t i = size_t(s) * 17;
                for (int ch = 0; ch < 3; ch++) {
                    float v;
                    if (half) { uint16_t hv; std::memcpy(&hv, rgb + (i * 3 + size_t(ch)) * 2, 2); v = table[hv]; }
                    else std::memcpy(&v, rgb + (i * 3 + size_t(ch)) * 4, 4);
                    if (v > p.max[ch]) p.max[ch] = v;
                    histogram(p.hLo[ch], p.sLo[ch], v);
                    if (inv > 0.f) histogram(p.hHi[ch], p.sHi[ch], v * inv);
                }
            }
        };
        if (chunks == 1) walk(0);
        else {
            std::vector<std::thread> threads;
            std::atomic<int> next{0};
            for (unsigned t = 0; t < hardwareThreads(); t++)
                threads.emplace_back([&] { for (int c; (c = next++) < chunks;) walk(c); });
            for (auto& t : threads) t.join();
        }
        static thread_local std::vector<int> hLo, hHi;
        static thread_local std::vector<double> sLo, sHi;
        hLo.assign(3 * HIST_BINS, 0); hHi.assign(3 * HIST_BINS, 0); sLo.assign(3 * HIST_BINS, 0); sHi.assign(3 * HIST_BINS, 0);
        for (const Part& p : parts)
            for (int c = 0; c < 3; c++) {
                if (p.max[c] > cc.max[c]) cc.max[c] = p.max[c];
                for (int b = 0; b < HIST_BINS; b++) {
                    hLo[size_t(c * HIST_BINS + b)] += p.hLo[c][b]; sLo[size_t(c * HIST_BINS + b)] += p.sLo[c][b];
                    hHi[size_t(c * HIST_BINS + b)] += p.hHi[c][b]; sHi[size_t(c * HIST_BINS + b)] += p.sHi[c][b];
                }
            }
        return clipFrom(cc, hLo.data(), sLo.data(), hHi.data(), sHi.data(), samples, bento, k, usClipped, flags);
    }

    static ChannelClip clipFrom(ChannelClip cc, const int* hLo, const double* sLo, const int* hHi, const double* sHi, long samples,
                                bool bento, float k, float usClipped, bool flags) {
        const bool useLo = !bento || flags;
        const bool useHi = bento && k > 1.f;
        std::string logLo, logHi;
        float hiPlateauMax = 0.f, hiPlateauMinFrac = 3.4028235e38f;
        for (int c = 0; c < 3; c++) {
            float lo = 0.f, hi = 0.f;
            if (useLo) {
                const float s = plateau(hLo + c * HIST_BINS, sLo + c * HIST_BINS, samples, 0.92f, 1.03f);
                lo = std::isnan(s) ? 1.f : s;
                logLo += (c == 0 ? "lo=" : ",") + javaFloatString(lo) + (std::isnan(s) ? "(nominal)" : "(plateau)");
            }
            if (useHi) {
                const float s = plateau(hHi + c * HIST_BINS, sHi + c * HIST_BINS, samples, 0.90f, 1.08f);
                hi = !std::isnan(s) ? s * k : (usClipped > 0.f ? k : 0.f);
                if (!std::isnan(s)) { hiPlateauMax = std::max(hiPlateauMax, hi); hiPlateauMinFrac = std::min(hiPlateauMinFrac, s); }
                logHi += (c == 0 ? "hi=" : ",") + javaFloatString(hi) + (!std::isnan(s) ? "(plateau)" : hi > 0.f ? "(nominal)" : "(off)");
            }
            cc.lo[c] = lo;
            cc.hi[c] = hi;
        }
        const std::string log = !logLo.empty() && !logHi.empty() ? logLo + " " + logHi : logLo + logHi;
        if (cc.hi[1] <= 0.f) { cc.hi[0] = cc.hi[1] = cc.hi[2] = 0.f; }
        else for (int c = 0; c < 3; c++) if (cc.hi[c] <= 0.f) cc.hi[c] = hiPlateauMax > 0.f ? std::min(k, std::max(hiPlateauMax, cc.max[c])) : k;
        cc.hiUnflagged = cc.hi[1] > 0.f && usClipped <= 0.f && hiPlateauMinFrac < 0.975f;
        for (int c = 0; c < 3; c++)
            if ((cc.lo[1] > 0.f && cc.max[c] >= 0.85f * cc.lo[c]) || (cc.hi[1] > 0.f && cc.max[c] >= 0.85f * cc.hi[c])) cc.nearClip = true;
        cc.log = log + (cc.hiUnflagged ? " hiUnflagged" : "") + " max=" + javaFloatString(cc.max[0]) + "," + javaFloatString(cc.max[1]) + ","
                 + javaFloatString(cc.max[2]) + " samples=" + std::to_string(samples);
        return cc;
    }

    /// pixels «Inpaint opposed» reconstructs: a clipped-mean channel (bits 0-2) and no ultrashort content (bits 4, 5)
    static long opposedPixels(const std::vector<uint8_t>& flags) {
        long total = 0;
        for (uint8_t v : flags) if ((v & 7) != 0 && (v & 48) == 0) total++;
        return total;
    }

    static float opposedMeansUnused(); // (the read-back route is not the default one)

    static int wholeShorter(int /*lowFactor*/, Point size) { return std::min(size.x, size.y); }
    static int shareBlock(int shorter) { return std::max(1, javaRound(float(shorter) / 192.f)); }
    static bool signedInput(bool hybrid) { return hybrid; }
    static bool halfInput(bool hybrid, bool halfTransport, int w, int h, size_t bytes) {
        const size_t pixels = size_t(w) * size_t(h);
        return hybrid && halfTransport && w > 0 && h > 0 && bytes >= pixels * 6 && bytes < pixels * 12;
    }
    static bool noShortBloom(PostPipeline& p) {
        return !p.merge.lastBentoApplied && Settings::current().hybridSwitch("sun_bloom_no_short", true);
    }
    static Mat3 toRender(PostPipeline& p) { return multiply(WhyTone::colourMatrix(p), *p.mParameters.sensorToProPhoto); }

    void Run() override {
        PostPipeline& p = *basePipeline;
        Settings& prefs = Settings::current();
        GLProg& prog = *glProg;
        std::optional<std::vector<uint8_t>> flags = std::move(p.merge.lastClipFlags);
        p.merge.lastClipFlags.reset();
        const bool hybrid = p.merge.hybridShot || p.merge.lastBentoApplied;
        if (!hybrid) throw std::runtime_error("post port: the SCAM HDR route is not ported");
        const Point size = *p.mParameters.rawSize;
        const bool largeTwoX = p.mParameters.outputScale > 1.f && int64_t(size.x) * size.y > 64000000LL;
        if (largeTwoX) throw std::runtime_error("post port: the Sabre 2x grid above 64 MP is not ported");
        if (flags && flags->size() != size_t(size.x) * size_t(size.y)) {
            logI("SCAM_PIPELINE", "highlight recovery: clip flags ignored (hybrid=true bytes=" + std::to_string(flags->size()) + " expected="
                 + std::to_string(int64_t(size.x) * size.y) + ")");
            flags.reset();
        }
        const bool bento = p.merge.lastBentoApplied;
        const float bentoK = p.merge.lastBentoFactor, bentoUs = p.merge.lastBentoUsClipped;
        const bool withFlags = flags.has_value();
        const bool half = !p.scamRgb.empty() && halfInput(hybrid, p.merge.lastRgbHalf, size.x, size.y, p.scamRgb.size());
        if (!half && !p.scamRgb.empty() && p.scamRgb.size() != size_t(size.x) * size.y * 12)
            throw std::runtime_error("post port: worker RGB of " + std::to_string(p.scamRgb.size()) + " B for " + std::to_string(size.x) + "x" + std::to_string(size.y));
        const float strength = std::max(0.f, std::min(1.f, prefs.hybridValue("highlight_recovery", 100.f) / 100.f));
        // the statistics (Java: on a helper thread beside the upload; the same result)
        const ChannelClip stats = channelClip(p.scamRgb.data(), p.scamRgb.size(), half, bento, bentoK, bentoUs, withFlags);
        const long oppPixels = flags ? opposedPixels(*flags) : -1;
        // the input texture: RGBA16F of the halves (alpha 1.0), or RGBA32F of the floats
        Texture input;
        {
            const size_t px = size_t(size.x) * size_t(size.y);
            if (half) {
                std::vector<uint16_t> rgba(px * 4);
                const uint16_t* src = reinterpret_cast<const uint16_t*>(p.scamRgb.data());
                for (size_t i = 0; i < px; i++) {
                    rgba[i * 4] = src[i * 3]; rgba[i * 4 + 1] = src[i * 3 + 1]; rgba[i * 4 + 2] = src[i * 3 + 2]; rgba[i * 4 + 3] = 0x3C00;
                }
                input = makeTexture(size, GLFormat(DataType::FLOAT_16, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
                glBindTexture(GL_TEXTURE_2D, input->mTextureID);
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, size.x, size.y, GL_RGBA, GL_HALF_FLOAT, rgba.data());
            } else {
                input = makeTexture(size, GLFormat(DataType::FLOAT_32, 3), p.scamRgb.data(), GL_NEAREST, GL_CLAMP_TO_EDGE);
            }
            checkGl("scamrgb upload");
        }
        // the decimated copy for the later nodes (every 61st pixel, float32 RGB), then the big buffer goes
        {
            const size_t n = (half ? p.scamRgb.size() / 2 : p.scamRgb.size() / 4) / 3, m = (n + 60) / 61;
            p.scamRgbDecimated.assign(m * 3, 0.f);
            const auto& table = halfTable();
            for (size_t kx = 0; kx < m; kx++)
                for (int c = 0; c < 3; c++) {
                    const size_t i = kx * 61 * 3 + size_t(c);
                    float v;
                    if (half) { uint16_t hv; std::memcpy(&hv, p.scamRgb.data() + i * 2, 2); v = table[hv]; }
                    else std::memcpy(&v, p.scamRgb.data() + i * 4, 4);
                    p.scamRgbDecimated[kx * 3 + size_t(c)] = v;
                }
            std::vector<uint8_t>().swap(p.scamRgb);
        }
        ChannelClip cc = stats;
        {
            const float* top = cc.hi[1] > 0.f ? cc.hi : cc.lo;
            const auto& wp = *p.mParameters.whitePoint;
            float white = 0.f;
            if (top[1] > 0.f) for (int c = 0; c < 3; c++) white = std::max(white, top[c] / std::max(wp[size_t(c)], 1e-6f));
            p.merge.lastClipWhite = white;
            p.merge.lastRealClip = cc.enabled();
            logI("SCAM_PIPELINE", "highlight recovery per channel: strength=" + javaFloatString(strength) + " bento=" + (bento ? "true" : "false")
                 + " k=" + javaFloatString(bentoK) + " usClipped=" + javaFloatString(bentoUs) + " flags=" + (withFlags ? "true" : "false") + " "
                 + cc.log + " nearClip=" + (cc.nearClip ? "true" : "false") + " clipWhite=" + javaFloatString(white) + " opposedPixels="
                 + std::to_string(oppPixels));
        }
        const bool perChannel = strength > 0.f && cc.enabled();
        Texture flagsTex, chromaNear, chromaWide, chromaHuge, purpleBound, oppGpu;
        const auto& gm = p.mParameters.gainMap;
        const Point mapSize = *p.mParameters.mapSize;
        std::vector<float> gainCopy = gm ? *gm : std::vector<float>();
        gainCopy.resize(size_t(mapSize.x) * mapSize.y * 4, 1.f);
        p.GainMap = makeTexture(mapSize, GLFormat(DataType::FLOAT_16, 4), gainCopy.data(), GL_LINEAR, GL_CLAMP_TO_EDGE);
        if (perChannel && flags) {
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            flagsTex = makeTexture(size, GLFormat(DataType::SIMPLE_8, 1), flags->data(), GL_NEAREST, GL_CLAMP_TO_EDGE);
        }
        flags.reset();
        p.main1 = makeTexture(size, GLFormat(DataType::FLOAT_16, GLDrawParams::WorkDim), nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
        p.main3 = nullptr;
        const bool early = int64_t(size.x) * size.y > 100000000LL || prefs.scamDevSwitch("early_release", false);
        if (early) {
            const Point mainSize = size;
            p.lazyMain2 = [mainSize] { return makeTexture(mainSize, GLFormat(DataType::FLOAT_16, GLDrawParams::WorkDim), nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE); };
        } else {
            p.main2 = makeTexture(size, GLFormat(DataType::FLOAT_16, GLDrawParams::WorkDim), nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
        }
        const int ox = 0, oy = 0;
        const int s = std::max(1, javaRound(p.mParameters.outputScale));
        const int block = 8 * s;
        const float chromaLimit = prefs.hybridValue("highlight_chroma", 0.35f);
        const float usWhite = bento && bentoK > 1.f ? bentoK : 0.f;
        const auto& whitePoint = *p.mParameters.whitePoint;
        const std::vector<float> wp3(whitePoint.begin(), whitePoint.begin() + 3);
        auto vec3 = [](const float* v) { return std::vector<float>{v[0], v[1], v[2]}; };
        if (perChannel && cc.nearClip) {
            const GLFormat rgba(DataType::FLOAT_16, 4);
            const Point near{(size.x + block - 1) / block, (size.y + block - 1) / block};
            chromaNear = makeTexture(near, rgba, nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
            const Point wide{(near.x + 3) / 4, (near.y + 3) / 4};
            chromaWide = makeTexture(wide, rgba, nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
            chromaHuge = makeTexture({(wide.x + 3) / 4, (wide.y + 3) / 4}, rgba, nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
            const bool cs = (block + s - 1) / s == 8 && prefs.scamDevSwitch("hl_chanprep_cs", true);
            bool drawn = false;
            for (int attempt = cs ? 0 : 1; attempt < 2 && !drawn; attempt++) {
                if (attempt == 0) { prog.setLayout(8, 8, 1); while (glGetError() != GL_NO_ERROR) {} }
                prog.useAssetProgram(attempt == 0 ? "hlrecovery/chanprep_cs" : "hlrecovery/chanprep", attempt == 0);
                prog.setTexture("InputBuffer", input);
                prog.setTexture("GainMap", p.GainMap);
                if (flagsTex) prog.setTexture("ClipFlags", flagsTex);
                prog.setVar("whitePoint", wp3);
                prog.setVar("inverseSize", 1.f / float(size.x), 1.f / float(size.y));
                prog.setVar("cropOffset", ox, oy);
                prog.setVar("clipLoU", vec3(cc.lo));
                prog.setVar("clipHiU", vec3(cc.hi));
                prog.setVar("clipHiUnflaggedU", cc.hiUnflagged ? 1 : 0);
                prog.setVar("clipFlagsU", flagsTex ? 1 : 0);
                prog.setVar("blockU", block);
                prog.setVar("pxStepU", s);
                prog.setVar("usWhiteU", usWhite);
                if (attempt == 1) { prog.drawBlocks(chromaNear); drawn = true; continue; }
                try {
                    prog.setTextureCompute("outChroma", chromaNear, true);
                    glDispatchCompute(GLuint(near.x), GLuint(near.y), 1);
                    glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
                    drawn = glGetError() == GL_NO_ERROR;
                } catch (const std::exception& e) {
                    logI("SCAM_PIPELINE", std::string("highlight chanprep compute: ") + e.what() + ", the fragment pass");
                }
            }
            prog.useAssetProgram("hlrecovery/reduce", false);
            prog.setTexture("InputBuffer", chromaNear);
            prog.drawBlocks(chromaWide);
            prog.useAssetProgram("hlrecovery/reduce", false);
            prog.setTexture("InputBuffer", chromaWide);
            prog.drawBlocks(chromaHuge);
        }
        std::optional<std::array<float, 3>> oppChroma;
        const bool opposed = perChannel && cc.nearClip && (flagsTex ? oppPixels > 0 : cc.hi[1] <= 0.f) && prefs.hybridValue("highlight_opposed", 1.f) > 0.f;
        if (opposed) {
            if (!prefs.scamDevSwitch("opposed_gpu_mean", true) || prefs.hybridSwitch("highlight_opposed_sparse", false))
                throw std::runtime_error("post port: the opposed chrominance read-back route is not ported");
            oppGpu = opposedChromaGpu(prog, *input, *p.GainMap, flagsTex.get(), wp3, cc, size, ox, oy);
            if (!oppGpu) throw std::runtime_error("post port: the opposed chrominance on the GPU failed");
        }
        const float purple = perChannel ? std::max(0.f, std::min(1.f, prefs.hybridValue("highlight_purple", 1.f))) : 0.f;
        const std::string fringeMethod = prefs.hybridString("fringe_method", "classic") == "neural" ? "neural" : "classic";
        if (purple > 0.f && fringeMethod == "neural") throw std::runtime_error("post port: the fringe net (CaNetP) is not ported");
        const float p399 = purple;
        if (p399 > 0.f && prefs.scamDevSwitch("purple_bound", true)) {
            purpleBound = makeTexture({(input->mSize.x + 7) / 8, (input->mSize.y + 7) / 8}, GLFormat(DataType::FLOAT_32, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
            prog.useAssetProgram("scamhdr/purplebound", false);
            prog.setTexture("InputBuffer", input);
            prog.setVar("whitePoint", wp3);
            prog.drawBlocks(purpleBound);
            prog.closed = true;
        }
        if (purpleBound) prog.setDefine("PURPLE_BOUND", 1);
        if (oppGpu) prog.setDefine("OPP_GPU", 1);
        prog.useAssetProgram("scamhdr/scamrgb");
        prog.setTexture("InputBuffer", input);
        prog.setTexture("GainMap", p.GainMap);
        if (purpleBound) prog.setTexture("PurpleBound", purpleBound);
        if (oppGpu) prog.setTexture("OppChroma", oppGpu);
        prog.setVar("whitePoint", wp3);
        const bool isSigned = signedInput(hybrid);
        prog.setVar("signedU", isSigned ? 1 : 0);
        p.signedRgb = isSigned;
        if (perChannel) {
            prog.setVar("hlModeU", 1);
            prog.setVar("clipLoU", vec3(cc.lo));
            prog.setVar("clipHiU", vec3(cc.hi));
            prog.setVar("clipHiUnflaggedU", cc.hiUnflagged ? 1 : 0);
            prog.setVar("hlStrengthU", strength);
            prog.setVar("clipFlagsU", flagsTex ? 1 : 0);
            if (flagsTex) prog.setTexture("ClipFlags", flagsTex);
            prog.setVar("chromaU", chromaNear ? 1 : 0);
            if (chromaNear) {
                prog.setTexture("Chroma8", chromaNear);
                prog.setTexture("Chroma32", chromaWide);
                prog.setTexture("Chroma128", chromaHuge);
            }
            prog.setVar("blockU", block);
            prog.setVar("chromaLimitU", chromaLimit);
            const float defringe = prefs.hybridValue("highlight_defringe", 0.85f);
            prog.setVar("defringeU", defringe > 0.f ? defringe : -1.f);
            prog.setVar("hlWhiteU", std::max(0.f, std::min(1.f, prefs.hybridValue("highlight_glow", 1.f))));
            prog.setVar("purpleU", p399);
            prog.setVar("oppU", opposed ? 1 : 0);
            prog.setVar("oppLocalU", opposed && prefs.hybridSwitch("highlight_opposed_local", true) ? 1 : 0);
            prog.setVar("oppWarmU", opposed ? std::max(0.f, std::min(1.f, prefs.hybridValue("highlight_opposed_warm", 1.f))) : 0.f);
            const std::array<float, 3> oc = oppChroma ? *oppChroma : std::array<float, 3>{0, 0, 0};
            prog.setVar("oppChromaU", oc[0], oc[1], oc[2]);
            prog.setVar("usWhiteU", usWhite);
        } else {
            prog.setVar("hlModeU", 0);
            prog.setVar("clipLevel", 1e30f, 1e30f, 1e30f);
        }
        prog.setVar("cropOffset", ox, oy);
        prog.setVar("inverseSize", 1.f / float(size.x), 1.f / float(size.y));
        WorkingTexture = p.getMain();
        prog.drawBlocks(WorkingTexture);
        prog.closed = true;
        if (purpleBound) { purpleBound->close(); purpleBound = nullptr; }
        if (oppGpu) { oppGpu->close(); oppGpu = nullptr; }
        const int shorter = wholeShorter(WhyTone::lowFactor(p.mParameters), size);
        if (hybrid && flagsTex) {
            p.sunBloom = sunBloomMap(p, *flagsTex, size, shorter);
            p.sunBloomSize = p.sunBloom ? std::optional<Point>(size) : std::nullopt;
            p.sunBloomBlock = shareBlock(shorter);
        }
        if (opposed) {
            p.opposedShare = opposedShareMap(p, *input, flagsTex.get(), cc, size, shorter);
            p.opposedShareSize = p.opposedShare ? std::optional<Point>(size) : std::nullopt;
            p.opposedShareBlock = shareBlock(shorter);
        }
        const float band = perChannel && flagsTex ? std::max(0.f, std::min(1.f, prefs.hybridValue("highlight_band", 1.f))) : 0.f;
        const float glow = perChannel && flagsTex ? std::max(0.f, std::min(1.f, prefs.hybridValue("highlight_glow", 1.f))) : 0.f;
        const bool bandPass = band > 0.f || glow > 0.f;
        bool inputIsMain2 = false;
        if (early && bandPass && !p.main2 && p.lazyMain2 && input->mainFormat()) {
            input->setFilter(GL_LINEAR);
            p.main2 = input;
            p.lazyMain2 = nullptr;
            input = nullptr;
            inputIsMain2 = true;
        }
        Texture unbanded;
        if (bandPass) {
            Texture bandMask;
            if (!(int64_t(size.x) * size.y > 100000000LL) && flagsTex->mSize == WorkingTexture->mSize && prefs.scamDevSwitch("clipband_mask", true)) {
                bandMask = makeTexture(flagsTex->mSize, GLFormat(DataType::SIMPLE_8, 1), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
                prog.useAssetProgram("scamhdr/clipmask", false);
                prog.setTexture("ClipFlags", flagsTex);
                prog.setVar("radiusU", 3 * s);
                prog.setVar("zoneU", 2 * s);
                prog.setVar("clipHiUnflaggedU", cc.hiUnflagged ? 1 : 0);
                prog.setVar("glowRadiusU", glow > 0.f ? 6 * s : 0);
                prog.drawBlocks(bandMask);
                prog.closed = true;
            }
            Texture banded = p.getMain();
            if (bandMask) prog.setDefine("BAND_MASK", 1);
            prog.useAssetProgram("scamhdr/clipband", false);
            if (bandMask) prog.setTexture("BandMask", bandMask);
            prog.setTexture("InputBuffer", WorkingTexture);
            prog.setTexture("ClipFlags", flagsTex);
            prog.setVar("radiusU", 3 * s);
            prog.setVar("zoneU", 2 * s);
            prog.setVar("clipHiUnflaggedU", cc.hiUnflagged ? 1 : 0);
            prog.setVar("strengthU", band);
            prog.setVar("glowU", glow);
            prog.setVar("glowRadiusU", glow > 0.f ? 6 * s : 0);
            prog.drawBlocks(banded);
            prog.closed = true;
            if (bandMask) bandMask->close();
            unbanded = WorkingTexture;
            WorkingTexture = banded;
        }
        if (early) {
            glFinish();
            if (input) { input->close(); input = nullptr; }
            if (flagsTex) { flagsTex->close(); flagsTex = nullptr; }
            if (chromaNear) { chromaNear->close(); chromaNear = nullptr; }
            if (chromaWide) { chromaWide->close(); chromaWide = nullptr; }
            if (chromaHuge) { chromaHuge->close(); chromaHuge = nullptr; }
            if (unbanded) p.releaseMain(unbanded);
        }
        logI("SCAM_PIPELINE", std::string("scamrgb input ") + (isSigned ? "signed (clamped after the noise reduction or the WHY colour averages)" : "clamped per pixel (post_ab_clamp)"));
        if (perChannel)
            logI("SCAM_PIPELINE", "highlight recovery per channel: chroma=" + (chromaNear ? std::to_string(chromaNear->mSize.x) + "x"
                 + std::to_string(chromaNear->mSize.y) + " block " + std::to_string(block) : std::string("neutral")) + " limit=" + javaFloatString(chromaLimit)
                 + " band=" + javaFloatString(band) + " glow=" + javaFloatString(glow) + " clipGuard k=" + javaFloatString(usWhite)
                 + (early ? std::string(" early release") + (inputIsMain2 ? " (input as main2)" : "") : ""));
        for (Texture* t : {&input, &flagsTex, &chromaNear, &chromaWide, &chromaHuge}) if (*t) (*t)->close();
    }

private:
    Texture opposedChromaGpu(GLProg& prog, GLTexture& input, GLTexture& gainMap, GLTexture* flagsTex, const std::vector<float>& wp3,
                             const ChannelClip& cc, Point size, int ox, int oy) {
        const int gx = (size.x + 15) / 16, gy = (size.y + 15) / 16;
        while (glGetError() != GL_NO_ERROR) {}
        GLBuffer sums(6 * gx * gy, GLFormat(DataType::UNSIGNED_32));
        Texture mean = makeTexture({1, 1}, GLFormat(DataType::FLOAT_32, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
        prog.setLayout(16, 16, 1);
        prog.setDefine("SPARSE", 0);
        prog.useAssetProgram("hlrecovery/opposed", true);
        prog.setTexture("InputBuffer", input);
        prog.setTexture("GainMap", gainMap);
        if (flagsTex) prog.setTexture("ClipFlags", *flagsTex);
        prog.setVar("whitePoint", wp3);
        prog.setVar("inverseSize", 1.f / float(size.x), 1.f / float(size.y));
        prog.setVar("cropOffset", ox, oy);
        prog.setVar("sizeU", size.x, size.y);
        prog.setVar("clipLoU", std::vector<float>{cc.lo[0], cc.lo[1], cc.lo[2]});
        prog.setVar("clipHiU", std::vector<float>{cc.hi[0], cc.hi[1], cc.hi[2]});
        prog.setVar("clipHiUnflaggedU", cc.hiUnflagged ? 1 : 0);
        prog.setVar("clipFlagsU", flagsTex ? 1 : 0);
        prog.setBufferCompute("oppSums", sums);
        prog.computeManualAsync(gx, gy, 1);
        prog.setLayout(256, 1, 1);
        prog.useAssetProgram("hlrecovery/opposed_mean", true);
        prog.setBufferCompute("oppSums", sums);
        prog.setVar("groupsU", gx * gy);
        prog.setTextureCompute("outMean", mean, true);
        prog.computeManualAsync(1, 1, 1);
        prog.closed = true;
        const GLenum error = glGetError();
        if (error != GL_NO_ERROR) { mean->close(); return nullptr; }
        logI("SCAM_PIPELINE", "highlight opposed (RawTherapee inpaint opposed): chrominance on the GPU (no read-back)");
        return mean;
    }

    /// suncore MODE 0 / 2 block sums by the compute pass, then MODE 4 / 5 into target; false: the fragment pass
    bool blockSums(PostPipeline& p, int mode, GLTexture& source, GLTexture* flags, const ChannelClip* cc, Point coarse, int block, GLTexture& target) {
        GLProg& prog = *glProg;
        if (!Settings::current().scamDevSwitch("suncore_cs", true)) return false;
        try {
            while (glGetError() != GL_NO_ERROR) {}
            GLTexture sums(coarse, GLFormat(DataType::FLOAT_32, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
            prog.setDefine("MODE", mode);
            prog.setLayout(16, 16, 1);
            prog.useAssetProgram("scamhdr/suncore_cs", true);
            prog.setTexture("InputBuffer", source);
            prog.setVar("blockU", block);
            if (mode == 0) prog.setVar("noShortU", noShortBloom(p) ? 1 : 0);
            if (mode == 2) {
                if (flags) prog.setTexture("ClipFlags", *flags);
                prog.setVar("clipFlagsU", flags ? 1 : 0);
                prog.setVar("clipLoU", std::vector<float>{cc->lo[0], cc->lo[1], cc->lo[2]});
                prog.setVar("clipHiU", std::vector<float>{cc->hi[0], cc->hi[1], cc->hi[2]});
                prog.setVar("toRenderU", WhyTone::vec(toRender(p)));
            }
            prog.setTextureCompute("outCells", sums, true);
            glDispatchCompute(GLuint(coarse.x), GLuint(coarse.y), 1);
            glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            if (glGetError() != GL_NO_ERROR) return false;
            prog.setDefine("MODE", mode == 0 ? 4 : 5);
            prog.useAssetProgram("scamhdr/suncore", false);
            prog.setTexture("InputBuffer", sums);
            prog.drawBlocks(target);
            prog.closed = true;
            return true;
        } catch (const std::exception& e) {
            logI("SCAM_PIPELINE", "suncore compute MODE " + std::to_string(mode) + ": " + e.what() + ", the fragment pass");
            return false;
        }
    }

    Texture sunBloomMap(PostPipeline& p, GLTexture& flags, Point size, int shorter) {
        GLProg& prog = *glProg;
        const auto set = WhyTone::sunBloom();
        if (set[0] <= 0.f) return nullptr;
        const int block = shareBlock(shorter);
        const Point coarse{(size.x + block - 1) / block, (size.y + block - 1) / block};
        const GLFormat one(DataType::FLOAT_16, 1);
        GLTexture share(coarse, one, nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
        if (!blockSums(p, 0, flags, nullptr, nullptr, coarse, block, share)) {
            prog.setDefine("MODE", 0);
            prog.useAssetProgram("scamhdr/suncore", false);
            prog.setTexture("InputBuffer", flags);
            prog.setVar("blockU", block);
            prog.setVar("noShortU", noShortBloom(p) ? 1 : 0);
            prog.drawBlocks(share);
        }
        const float radius = set[1] * float(shorter) / float(block);
        GLTexture halfTex(coarse, one, nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
        prog.setDefine("MODE", 1);
        prog.useAssetProgram("scamhdr/suncore", false);
        prog.setTexture("InputBuffer", share);
        prog.setVar("dirU", 1, 0);
        prog.setVar("radiusU", radius);
        prog.drawBlocks(halfTex);
        Texture out = makeTexture(coarse, one, nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
        prog.setDefine("MODE", 1);
        prog.useAssetProgram("scamhdr/suncore", false);
        prog.setTexture("InputBuffer", halfTex);
        prog.setVar("dirU", 0, 1);
        prog.setVar("radiusU", radius);
        prog.drawBlocks(out);
        prog.closed = true;
        return out;
    }

    Texture opposedShareMap(PostPipeline& p, GLTexture& input, GLTexture* flags, const ChannelClip& cc, Point size, int shorter) {
        GLProg& prog = *glProg;
        if (!WhyCombineHelpers::landingDrawn(p)) return nullptr;
        const int block = shareBlock(shorter);
        const Point coarse{(size.x + block - 1) / block, (size.y + block - 1) / block};
        const GLFormat two(DataType::FLOAT_16, 2), cells(DataType::FLOAT_32, 4);
        GLTexture weights(coarse, cells, nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
        if (!blockSums(p, 2, input, flags, &cc, coarse, block, weights)) {
            prog.setDefine("MODE", 2);
            prog.useAssetProgram("scamhdr/suncore", false);
            prog.setTexture("InputBuffer", input);
            if (flags) prog.setTexture("ClipFlags", *flags);
            prog.setVar("clipFlagsU", flags ? 1 : 0);
            prog.setVar("clipLoU", std::vector<float>{cc.lo[0], cc.lo[1], cc.lo[2]});
            prog.setVar("clipHiU", std::vector<float>{cc.hi[0], cc.hi[1], cc.hi[2]});
            prog.setVar("toRenderU", WhyTone::vec(toRender(p)));
            prog.setVar("blockU", block);
            prog.drawBlocks(weights);
        }
        prog.closed = true;
        if (!Settings::current().hybridSwitch("highlight_landing_async", true))
            throw std::runtime_error("post port: the landing's read-back statistics (highlight_landing_async 0) are not ported");
        const float radius = OPPOSED_RADIUS * float(shorter) / float(block);
        GLTexture sums(coarse, cells, nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
        prog.setDefine("MODE", 3);
        prog.useAssetProgram("scamhdr/suncore", false);
        prog.setTexture("InputBuffer", weights);
        prog.setVar("dirU", 1, 0);
        prog.setVar("radiusU", radius);
        prog.setVar("finalU", 0);
        prog.setVar("glowFullU", 0.1f * float(block) * float(block));
        prog.drawBlocks(sums);
        GLTexture gates(coarse, two, nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
        prog.setDefine("MODE", 3);
        prog.useAssetProgram("scamhdr/suncore", false);
        prog.setTexture("InputBuffer", sums);
        prog.setVar("dirU", 0, 1);
        prog.setVar("radiusU", radius);
        prog.setVar("finalU", 1);
        prog.setVar("glowFullU", 0.1f * float(block) * float(block));
        prog.drawBlocks(gates);
        GLTexture halfTex(coarse, two, nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
        prog.setDefine("MODE", 1);
        prog.useAssetProgram("scamhdr/suncore", false);
        prog.setTexture("InputBuffer", gates);
        prog.setVar("dirU", 1, 0);
        prog.setVar("radiusU", radius);
        prog.drawBlocks(halfTex);
        Texture out = makeTexture(coarse, two, nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
        prog.setDefine("MODE", 1);
        prog.useAssetProgram("scamhdr/suncore", false);
        prog.setTexture("InputBuffer", halfTex);
        prog.setVar("dirU", 0, 1);
        prog.setVar("radiusU", radius);
        prog.drawBlocks(out);
        prog.closed = true;
        logI("SCAM_PIPELINE", "highlight opposed landing: GPU only " + std::to_string(coarse.x) + "x" + std::to_string(coarse.y) + "@"
             + std::to_string(block) + ", radius " + javaFloatString(radius));
        return out;
    }
};

} // namespace scam_post
