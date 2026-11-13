// WhyLumaSharpen.java one to one: the WHY sharpening of the luminance (guided-filter local contrast, USM / bilateral, three
// Richardson-Lucy stages, the R1 / lattice source split), issued early by WhyStats in the G_CLEAN domain. Not ported (throws):
// the image's early release above 100 MP.
#pragma once

#include "WhyStats.hpp"
#include "../why/WhyV2Ae.hpp"

namespace scam_post {

class WhyLumaSharpen : public Node, public EarlySharpen {
public:
    WhyLumaSharpen() : Node("", "WhyLumaSharpen") {}
    void Compile() override {}

    static constexpr float SCALE2_BIL = 0.75f, SCALE2_RL_GAUSS = 0.85f, QUAD_RL3_BOOST = 2.f;
    static constexpr int MAX_HALF = 8;
    static constexpr float LOW_LIGHT_MIN = 0.3f, LOW_LIGHT_START = 4.f, LOW_LIGHT_FULL = 24.f;
    static constexpr int64_t HUGE_GF_PIXELS = 100000000LL;

    static float lowLightFactor(float snr) {
        if (!(snr > 0.f) || std::isinf(snr)) return 1.f;
        if (snr <= LOW_LIGHT_START) return LOW_LIGHT_MIN;
        if (snr >= LOW_LIGHT_FULL) return 1.f;
        const double t = std::log(double(snr / LOW_LIGHT_START)) / std::log(double(LOW_LIGHT_FULL / LOW_LIGHT_START));
        return float(double(LOW_LIGHT_MIN) + double(1 - LOW_LIGHT_MIN) * t);
    }
    /// an amount of our sharpening under a film: x (1 - the simulation's strength), none at 100 %; without a film the amount itself
    static float filmShare(float amount, const FilmSettings* film) { return !film ? amount : amount * (1.f - film->strength); }
    static bool cleanDomain(float configured, const FilmSettings* film) { return (film && film->strength > 0.f) || javaRound(configured) >= 1; }
    static bool neutralFilmGuide(const FilmSettings* film) { return film && film->strength >= 1.f; }
    static bool quadSwitch() { return Settings::current().scamDevSwitch("sharpen_quad", true); }
    static bool quadOnLattice(int block, bool enabled) { return block == 2 && enabled; }
    static bool r1Fits(int w, int h) { return int64_t(w) * h <= HUGE_GF_PIXELS; }
    static bool smoothedSource(int block, bool enabled) { return (block == 2 || block == 4) && enabled; }
    static bool smoothedSource(int block) {
        return smoothedSource(block, block > 1 && Settings::current().scamDevSwitch("sharpen_r1", true)) && !quadOnLattice(block, block == 2 && quadSwitch());
    }
    static bool smoothedSource(int block, int w, int h) { return r1Fits(w, h) && smoothedSource(block); }
    static bool latticeSource(int block, bool enabled) { return block <= 1 && enabled; }
    static bool latticeSource(int block, int w, int h) {
        const bool quad = quadOnLattice(block, block == 2 && quadSwitch());
        return r1Fits(w, h) && latticeSource(quad ? 1 : block, (block <= 1 || quad) && Settings::current().scamDevSwitch("sharpen_lattice", true));
    }
    static float scaleComp(float s, float k) { return 1.f + (k - 1.f) * std::min(std::max(s - 1.f, 0.f), 1.f); }
    static int gfDown(int w, int h, int down) { return int64_t(w) * h > HUGE_GF_PIXELS ? std::max(down, 2) : down; }

    struct Psf { int half = 0; std::vector<float> quadrant = std::vector<float>(81); };
    static std::vector<double> gauss1d(double sigma) {
        const int r = int(std::ceil(sigma * 3.5));
        std::vector<double> w(size_t(2 * r + 1));
        double sum = 0;
        for (int i = -r; i <= r; i++) { w[size_t(i + r)] = std::exp(-double(i * i) / (2 * sigma * sigma)); sum += w[size_t(i + r)]; }
        for (double& v : w) v /= sum;
        return w;
    }
    static double j1OverX(double x) {
        const double ax = std::fabs(x);
        if (ax < 1e-4) return 0.5;
        if (ax <= 3.75) {
            const double y = (ax / 3.75) * (ax / 3.75);
            return 0.5 + y * (-0.56249982 + y * (0.21093573 + y * (-0.03954289 + y * (0.00443319 + y * -0.00031761))));
        }
        const double z = 3.75 / ax, y = z * z;
        const double p = 0.79788458 + y * (-7.7e-7 + y * (-0.0055274 + y * (9.512e-5 + y * -0.00137237)));
        const double q = 0.046875 + y * (-0.00020033 + y * (0.00844919 + y * -0.00088126));
        return (p / std::sqrt(ax)) * std::cos(ax - 2.3561945 + z * q) / ax;
    }
    static double airyAt(double d, double rad) {
        if (rad <= 1e-4) return 0;
        const double v = 2 * j1OverX(d / rad * 3.831706);
        return v * v;
    }
    using Grid2 = std::vector<std::vector<double>>;
    static Grid2 airy(double rad) {
        const int half = std::min(int(std::ceil(rad * 1.85)), 16);
        Grid2 k(size_t(2 * half + 1), std::vector<double>(size_t(2 * half + 1)));
        const double offs[4] = {-0.375, -0.125, 0.125, 0.375};
        double sum = 0;
        for (int dy = -half; dy <= half; dy++)
            for (int dx = -half; dx <= half; dx++) {
                const double d = std::hypot(double(dx), double(dy));
                double v;
                if (d > 1.5) v = airyAt(d, rad);
                else {
                    v = 0;
                    for (double oy : offs) for (double ox : offs) v += airyAt(std::hypot(dx + ox, dy + oy), rad);
                    v /= 16;
                }
                if (v <= 1e-5) v = 0;
                k[size_t(dy + half)][size_t(dx + half)] = v;
                sum += v;
            }
        for (auto& row : k) for (double& v : row) v /= sum;
        return k;
    }
    static Grid2 disk(double rad) {
        const int half = std::min(int(std::ceil(rad + 0.5)), 16);
        Grid2 k(size_t(2 * half + 1), std::vector<double>(size_t(2 * half + 1)));
        double sum = 0;
        for (int dy = -half; dy <= half; dy++)
            for (int dx = -half; dx <= half; dx++) {
                const double d = std::hypot(double(dx), double(dy));
                double v;
                if (d + 0.7071 <= rad) v = 1;
                else if (d - 0.7071 >= rad) v = 0;
                else {
                    int in = 0;
                    for (int j = 0; j < 8; j++)
                        for (int i = 0; i < 8; i++) {
                            const double ox = (i + 0.5) / 8 - 0.5, oy = (j + 0.5) / 8 - 0.5;
                            if ((dx + ox) * (dx + ox) + (dy + oy) * (dy + oy) <= rad * rad) in++;
                        }
                    v = in / 64.0;
                }
                k[size_t(dy + half)][size_t(dx + half)] = v;
                sum += v;
            }
        for (auto& row : k) for (double& v : row) v /= sum;
        return k;
    }
    static Psf psf(int kernel, float rad) {
        Grid2 k;
        if (kernel == 2) k = airy(rad);
        else if (kernel == 1) k = disk(rad);
        else {
            const auto g = gauss1d(rad);
            k.assign(g.size(), std::vector<double>(g.size()));
            for (size_t y = 0; y < g.size(); y++) for (size_t x = 0; x < g.size(); x++) k[y][x] = g[y] * g[x];
        }
        const int full = int(k.size()) / 2;
        const int half = std::min(full, MAX_HALF);
        double sum = 0;
        for (int y = -half; y <= half; y++) for (int x = -half; x <= half; x++) sum += k[size_t(full + y)][size_t(full + x)];
        Psf out;
        out.half = half;
        for (int y = 0; y <= half; y++)
            for (int x = 0; x <= half; x++) out.quadrant[size_t(y * (half + 1) + x)] = float(k[size_t(full + y)][size_t(full + x)] / sum);
        return out;
    }

    bool runEarly(PostPipeline& pipeline, const Texture& input) override {
        if (!cleanDomain(WhyTone::value("sharp_domain", 1.f), pipeline.film.get()) || !pipeline.why || !input) return false;
        issue(pipeline, input, true);
        earlyInput_ = input;
        return true;
    }

    void Run() override {
        PostPipeline& pipeline = *basePipeline;
        Texture input = previousNode->WorkingTexture;
        WorkingTexture = input;
        glProg->closed = true;
        auto st = pipeline.why;
        if (earlyInput_) {
            const bool same = earlyInput_ == input;
            earlyInput_ = nullptr;
            if (same) return;
            logI("SCAM_PIPELINE", "WHY luma sharpen: input changed after the early passes, sharpening again");
            if (st && st->lumaS) { st->lumaS->close(); st->lumaS = nullptr; }
            if (st && st->detailRef) { st->detailRef->close(); st->detailRef = nullptr; }
            if (st) st->inputReleased = false;
        }
        if (!st || !st->ae) throw std::runtime_error("WHY sharpen: no statistics (WhyStats did not run)");
        issue(pipeline, input, false);
    }

private:
    Texture earlyInput_;

    static Texture plane(Point size, DataType type, int channels) { return makeTexture(size, GLFormat(type, channels), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE); }
    static Texture spare(std::vector<Texture>& pool, Point size, std::initializer_list<const Texture*> busy) {
        for (const Texture& t : pool) {
            bool used = false;
            for (const Texture* b : busy) used |= *b && t == *b;
            if (!used) return t;
        }
        Texture t = plane(size, DataType::FLOAT_16, 1);
        pool.push_back(t);
        return t;
    }
    static float clamp01(float v) { return std::max(0.f, std::min(1.f, v)); }

    void issue(PostPipeline& pipeline, const Texture& input, bool early) {
        GLProg& prog = *glProg;
        auto st = pipeline.why;
        const Point size = input->mSize;
        const FilmSettings* film = pipeline.film.get();
        const bool gClean = cleanDomain(WhyTone::value("sharp_domain", 1.f), film);
        const float mul = gClean ? 1.f : st->ae->ae;
        const float scaleSetting = WhyTone::value("sharp_scale", 0.f);
        const float s = scaleSetting > 0.f ? std::min(scaleSetting, 4.f) : float(std::max(1, javaRound(pipeline.mParameters.outputScale)));
        const int down = std::max(1, int(std::floor(s + 1e-4f)));
        const int nb = std::max(1, javaRound(s));
        const float lowLight = lowLightFactor(pipeline.mParameters.finishSnr);
        const float usmRadius = WhyTone::value("sharp_usm_radius", 0.1f);
        const float usmAmount = filmShare(WhyTone::value("sharp_usm_amount", 0.5f) * lowLight, film);
        const float usmThresh = WhyTone::value("sharp_usm_thresh", 0.f) / 255.f;
        const float bilRadius = WhyTone::value("sharp_bilateral_radius", 0.f);
        const float bilAmount = filmShare(WhyTone::value("sharp_bilateral_amount", 1.f) * lowLight, film);
        const float bilColor = std::max(WhyTone::value("sharp_bilateral_color", 10.f) / 100.f, 0.001f);
        const float halo = clamp01(WhyTone::value("sharp_halo_control", 100.f) / 100.f);
        const float protectShadows = clamp01(WhyTone::value("sharp_protect_shadows", 0.f) / 100.f);
        const float protectHighlights = clamp01(WhyTone::value("sharp_protect_highlights", 100.f) / 100.f);
        const int gfRadius = javaRound(WhyTone::value("sharp_gf_radius", 8.f));
        const float gfEps = std::max(WhyTone::value("sharp_gf_eps_e3", 10.f) * 0.001f, 1e-6f);
        const float gfLc = filmShare(WhyTone::value("sharp_gf_lc", 0.f) * lowLight, film);
        const float grain = filmShare(WhyTone::value("sharp_film_grain", 0.f) / 255.f, film);
        const float sobelAmount = filmShare(WhyTone::value("sharp_sobel_amount", 0.f) * lowLight, film), sobelThresh = WhyTone::value("sharp_sobel_thresh", 0.f) / 255.f;
        const float thin = filmShare(WhyTone::value("sharp_edge_thinning", 0.f), film);
        const float rlHalo = clamp01(WhyTone::value("sharp_rl_halo_control", 100.f) / 100.f);
        const float rlMargin = WhyTone::value("sharp_rl_halo_margin", 0.15f);
        const float rlMacro = WhyTone::value("sharp_rl_halo_macro", 0.4f);
        const float rlEdgeGuard = WhyTone::rlEdgeGuard();
        float rl[3][5] = {
            {WhyTone::value("sharp_rl1_kernel", 2.f), WhyTone::value("sharp_rl1_rad", 1.f), filmShare(WhyTone::value("sharp_rl1_amount", 1.f) * lowLight, film),
             WhyTone::value("sharp_rl1_iters", 3.f), WhyTone::value("sharp_rl1_damping", 0.f)},
            {WhyTone::value("sharp_rl2_kernel", 1.f), WhyTone::value("sharp_rl2_rad", 0.f), filmShare(WhyTone::value("sharp_rl2_amount", 0.f) * lowLight, film),
             WhyTone::value("sharp_rl2_iters", 0.f), WhyTone::value("sharp_rl2_damping", 0.f)},
            {WhyTone::value("sharp_rl3_kernel", 0.f), WhyTone::value("sharp_rl3_rad", 0.5f), filmShare(WhyTone::value("sharp_rl3_amount", 1.5f) * lowLight, film),
             WhyTone::value("sharp_rl3_iters", 3.f), WhyTone::value("sharp_rl3_damping", 0.f)}};
        Texture gainMap = pipeline.GainMap, fallback;
        if (!gainMap) {
            const float one[4] = {1.f, 1.f, 1.f, 1.f};
            fallback = makeTexture({1, 1}, GLFormat(DataType::FLOAT_16, 4), one, GL_LINEAR, GL_CLAMP_TO_EDGE);
            gainMap = fallback;
        }
        Texture ya = plane(size, DataType::FLOAT_16, 1);
        // P614 «Why тон» V2: the guide is the reference's luma_extract of V2's clean RGB (unclamped), as its tone sharpens it
        const Texture v2Clean = pipeline.whyV2 ? pipeline.whyV2->clean : nullptr;
        if (v2Clean) prog.setDefine("REF_LUMA", 1);
        prog.useAssetProgram("why/sharp_in", false);
        prog.setTexture("InputBuffer", input);
        if (v2Clean) prog.setTexture("CleanBuffer", v2Clean);
        WhyTone::setColour(prog, pipeline, *gainMap, st->inScale);
        prog.setVar("mulU", mul);
        prog.drawBlocks(ya);
        if (int64_t(size.x) * size.y > 100000000LL || Settings::current().scamDevSwitch("early_release", false))
            throw std::runtime_error("post port: the image's early release (above 100 MP) is not ported");
        if (fallback) fallback->close();
        if (neutralFilmGuide(film)) {
            st->lumaS = ya;
            st->sharpMul = mul;
            prog.closed = true;
            logI("SCAM_PIPELINE", "WHY luma sharpen film neutral guide grid=" + std::to_string(size.x) + "x" + std::to_string(size.y)
                 + " domain=G_CLEAN mul=" + javaFloatString(mul) + (early ? " (issued before the AE)" : ""));
            return;
        }
        const int block = pipeline.mParameters.mosaicBlock;
        const bool r1 = smoothedSource(block, size.x, size.y);
        const bool lattice = !r1 && latticeSource(block, size.x, size.y);
        const bool quadBoost = lattice && quadOnLattice(block, block == 2 && quadSwitch());
        if (quadBoost) rl[2][2] *= QUAD_RL3_BOOST;
        Texture src = ya, residual;
        if (r1 || lattice) {
            src = plane(size, DataType::FLOAT_16, 1);
            if (!r1) prog.setDefine("GATHER", 1);
            prog.useAssetProgram("why/sharp_r1", false);
            prog.setTexture("InputBuffer", ya);
            if (!r1) prog.setVar("latCapU", WhyTone::latticeCap());
            prog.setVar("modeU", r1 ? 0 : 3);
            prog.drawBlocks(src);
            residual = plane(size, DataType::FLOAT_16, 1);
            prog.useAssetProgram("why/sharp_r1", false);
            prog.setTexture("InputBuffer", ya);
            prog.setTexture("Smoothed", src);
            prog.setVar("modeU", 1);
            prog.drawBlocks(residual);
            ya->close();
        }
        const bool gf = gfRadius >= 1 && std::fabs(gfLc) > 0.001f;
        Texture ab;
        int gfBox = 0;
        const int gfDownV = gfDown(size.x, size.y, down);
        if (gf) {
            gfBox = std::max(1, javaRound(float(gfRadius) * s / float(gfDownV)));
            const Point reducedSize = WhyTone::reduced(size, gfDownV);
            Texture h = plane(reducedSize, DataType::FLOAT_32, 2);
            prog.setDefine("GATHER", 1);
            prog.useAssetProgram("why/sharp_box", false);
            prog.setTexture("InputBuffer", src);
            prog.setVar("modeU", 0);
            prog.setVar("radiusU", gfBox);
            prog.setVar("downU", gfDownV);
            prog.drawBlocks(h);
            for (int mode = 1; mode <= 3; mode++) {
                Texture next = plane(reducedSize, DataType::FLOAT_16, 2);
                prog.useAssetProgram("why/sharp_box", false);
                prog.setTexture("InputBuffer", h);
                prog.setVar("modeU", mode);
                prog.setVar("radiusU", gfBox);
                prog.setVar("epsU", gfEps);
                prog.drawBlocks(next);
                h->close();
                h = next;
            }
            ab = h;
        }
        const bool usm = usmRadius > 0.f && std::fabs(usmAmount) > 0.001f;
        const bool bil = bilRadius > 0.f && std::fabs(bilAmount) > 0.001f;
        std::vector<Texture> pool;
        Texture obs = spare(pool, size, {});
        prog.setDefine("GATHER", 1);
        prog.useAssetProgram("why/sharp_compose", false);
        prog.setTexture("InputBuffer", src);
        prog.setTexture("GfAB", ab ? ab : src);
        prog.setVar("downU", gfDownV);
        prog.setVar("gfLcU", gf ? gfLc : 0.f);
        prog.setVar("usmSigmaU", usm ? usmRadius * s : 0.f);
        prog.setVar("usmAmountU", usmAmount);
        prog.setVar("usmThreshU", usmThresh);
        prog.setVar("bilAmountU", bil ? bilAmount * scaleComp(s, SCALE2_BIL) : 0.f);
        prog.setVar("bilRadiusU", int(std::ceil(std::ceil(double(bilRadius)) * double(s))));
        prog.setVar("bilSigmaSU", std::max(0.5f, bilRadius * 0.5f) * s);
        prog.setVar("bilSigmaLU", bilColor);
        prog.setVar("nbU", nb);
        prog.setVar("haloU", halo);
        prog.setVar("protectShadowsU", protectShadows);
        prog.setVar("protectHighlightsU", protectHighlights);
        prog.setVar("grainU", grain);
        prog.setVar("sobelU", sobelAmount, sobelThresh);
        prog.setVar("thinU", thin);
        prog.drawBlocks(obs);
        src->close();
        if (ab) ab->close();
        std::string stages;
        Texture ratio;
        for (int i = 0; i < 3; i++) {
            const int kernel = javaRound(rl[i][0]);
            const float rad = rl[i][1];
            float amount = rl[i][2];
            const int iters = std::min(javaRound(rl[i][3]), 20);
            if (!(rad > 0.f && std::fabs(amount) > 0.001f && iters >= 1)) continue;
            const Psf k = psf(kernel, rad * s);
            if (kernel == 0) amount *= scaleComp(s, SCALE2_RL_GAUSS);
            if (!ratio) ratio = plane(size, DataType::FLOAT_16, 1);
            const int gatherHalf = k.half <= 4 ? k.half : 0;
            std::vector<float> quadrant(k.quadrant.begin(), k.quadrant.end());
            Texture est = obs;
            for (int it = 0; it < iters; it++) {
                if (gatherHalf > 0) prog.setDefine("PSF_HALF", gatherHalf);
                prog.useAssetProgram("why/sharp_rl", false);
                prog.setTexture("Observed", obs);
                prog.setTexture("Estimate", est);
                prog.setVar("modeU", 0);
                prog.setVar("psfHalfU", k.half);
                prog.setVarFloats("psfU", quadrant);
                prog.drawBlocks(ratio);
                Texture next = spare(pool, size, {&obs, &est});
                if (gatherHalf > 0) prog.setDefine("PSF_HALF", gatherHalf);
                prog.useAssetProgram("why/sharp_rl", false);
                prog.setTexture("Observed", obs);
                prog.setTexture("Estimate", est);
                prog.setTexture("Ratio", ratio);
                prog.setVar("modeU", it == iters - 1 ? 2 : 1);
                prog.setVar("psfHalfU", k.half);
                prog.setVarFloats("psfU", quadrant);
                prog.setVar("amountU", amount);
                prog.setVar("dampU", std::max(0.f, rl[i][4]));
                prog.setVar("haloU", rlHalo);
                prog.setVar("marginU", rlMargin);
                prog.setVar("macroU", rlMacro);
                prog.setVar("edgeGuardU", rlEdgeGuard);
                prog.setVar("protectShadowsU", protectShadows);
                prog.setVar("protectHighlightsU", protectHighlights);
                prog.setVar("nbU", nb);
                prog.drawBlocks(next);
                est = next;
            }
            obs = est;
            stages += " rl" + std::to_string(i + 1) + "=" + (kernel == 2 ? "airy" : kernel == 1 ? "disk" : "gauss") + javaFloatString(rad * s) + "x"
                      + std::to_string(iters) + "@" + javaFloatString(amount);
        }
        if (r1 || lattice) {
            if (ratio) { ratio->close(); ratio = nullptr; }
            Texture out = spare(pool, size, {&obs});
            prog.useAssetProgram("why/sharp_r1", false);
            prog.setTexture("Sharpened", obs);
            prog.setTexture("Residual", residual);
            prog.setVar("modeU", 2);
            prog.drawBlocks(out);
            residual->close();
            obs = out;
            stages += r1 ? " r1=B3(block " + std::to_string(block) + ")" : std::string(" lattice=off-sharpened") + (quadBoost ? " quad(rl3 x2.0)" : "");
        } else if (block > 1) stages += std::string(r1Fits(size.x, size.y) ? " r1=off(block " : " r1=off(>100MP, block ") + std::to_string(block) + ")";
        prog.closed = true;
        if (ratio) ratio->close();
        for (Texture& t : pool) if (t != obs) t->close();
        st->lumaS = obs;
        st->sharpMul = mul;
        logI("SCAM_PIPELINE", "WHY luma sharpen grid=" + std::to_string(size.x) + "x" + std::to_string(size.y) + " scale=" + javaFloatString(s) + " domain="
             + (gClean ? "G_CLEAN" : "ae") + " mul=" + javaFloatString(mul) + " usm=" + (usm ? javaFloatString(usmAmount) + "@" + javaFloatString(usmRadius * s) : "off")
             + stages + (early ? " (issued before the AE)" : ""));
    }
};

} // namespace scam_post
