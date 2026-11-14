// WhyMsDenoise.java one to one: the post-CCM optics cleanup and the reference's Oklab Laplacian pyramid denoise (strips of
// rows above 16 MP; the fused variants ms_fuse / ms_fuse_lab are off by default and not ported).
#pragma once

#include "ScamHdrDenoise.hpp"

#include <map>

namespace scam_post {

class WhyMsDenoise : public Node {
public:
    WhyMsDenoise() : Node("", "WhyMsDenoise") {}
    void Compile() override {}

    static bool enabled() { return Settings::current().hybridSwitch("denoise", true) && Settings::current().hybridSwitch("why_ms_denoise", true); }
    static float modelFactor(float noiseS, float noiseO, float scale) {
        if (!(scale > 0.001f) || !(noiseS > 0.f || noiseO > 0.f)) return 1.f;
        const double sigma = std::sqrt(std::max(double(std::max(noiseS, 0.f)) * 0.18 + double(std::max(noiseO, 0.f)), 1e-10));
        const double nf = std::max(0.4, std::min(3.5, 20.0 / std::max(0.18 / sigma, 1.0)));
        return float(std::max(0.25, std::min(4.0, 1.0 + (nf - 1.0) * double(scale))));
    }
    static std::array<std::array<float, 2>, 4> thresholds(float model) {
        static const float LUMA_LEVELS[3] = {0.4f, 0.1f, 0.35f}, CHROMA_LEVELS[3] = {0.2f, 0.4f, 0.6f};
        const float luma = 0.012f * std::max(0.f, WhyTone::value("ms_luma", 0.2f)) * model;
        const float chroma = 0.02f * std::max(0.f, WhyTone::value("ms_chroma", 0.5f)) * model;
        std::array<std::array<float, 2>, 4> t{};
        for (int l = 0; l < 3; l++) {
            t[size_t(l)][0] = luma * std::max(0.f, WhyTone::value("ms_luma_l" + std::to_string(l), LUMA_LEVELS[l]));
            t[size_t(l)][1] = chroma * std::max(0.f, WhyTone::value("ms_chroma_l" + std::to_string(l), CHROMA_LEVELS[l]));
        }
        t[3][1] = t[2][1];
        return t;
    }
    static std::optional<Mat3> inverse3(const Mat3& m) {
        const double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
        const double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if (std::fabs(det) < 1e-12) return std::nullopt;
        return Mat3{float((e * i - f * h) / det), float((c * h - b * i) / det), float((b * f - c * e) / det),
                    float((f * g - d * i) / det), float((a * i - c * g) / det), float((c * d - a * f) / det),
                    float((d * h - e * g) / det), float((b * g - a * h) / det), float((a * e - b * d) / det)};
    }
    static int stripRows(int width, int height, int requested) {
        if (requested <= 0 && int64_t(width) * height <= 16000000LL) return height;
        if ((width & 7) != 0 || (height & 7) != 0) return height;
        const int64_t budget = int64_t(256) << 20;
        const int rows = requested == 1 ? 128 : requested > 0 ? requested : int(std::max<int64_t>(8, budget / (std::max<int64_t>(1, width) * 40) - 128));
        return std::min(height, std::max(8, rows / 8 * 8));
    }

    void Run() override {
        PostPipeline& pipeline = *basePipeline;
        Settings& prefs = Settings::current();
        GLProg& prog = *glProg;
        Texture input = previousNode->WorkingTexture;
        WorkingTexture = input;
        prog.closed = true;
        if (!prefs.hybridSwitch("denoise", true)) return;
        const Mat3 toRender = ScamRgb::toRender(pipeline);
        const auto fromRender = inverse3(toRender);
        if (!fromRender) throw std::runtime_error("Singular denoise colour matrix");
        const auto& wp = *pipeline.mParameters.whitePoint;
        const auto so = WhyResolveDenoise::shotNoise(pipeline);
        const float model = modelFactor(so[0], so[1], WhyTone::value("ms_model_scale", 1.5f));
        const auto th = thresholds(model);
        const float shadow = std::max(0.f, WhyTone::value("ms_shadow", 1.5f));
        const bool pyramid = enabled() && (th[0][0] > 0.f || th[1][0] > 0.f || th[2][0] > 0.f || th[0][1] > 0.f || th[1][1] > 0.f || th[2][1] > 0.f)
                             && input->mSize.x >= 32 && input->mSize.y >= 32;
        whole_ = input->mSize;
        const int requested = std::max(0, javaRound(prefs.hybridValue("resolve_strip_rows", 0.f)));
        const int rows = stripRows(whole_.x, whole_.y, requested);
        tiled_ = rows < whole_.y;
        const bool plain = pyramid && !tiled_;
        if (plain && (prefs.scamDevSwitch("ms_fuse", false) || prefs.scamDevSwitch("ms_fuse_lab", false)))
            throw std::runtime_error("post port: the fused pyramid passes (ms_fuse / ms_fuse_lab) are not ported");
        const bool reuse = prefs.scamDevSwitch("ms_reuse", true);
        Texture out = pipeline.getMain();
        Texture codes;
        const auto& eff = pipeline.merge.lastEffectiveFrames;
        const float scale = WhyResolveDenoise::effScale(pipeline.merge.lastEffectiveMedian);
        if (pyramid && eff && std::isfinite(scale) && eff->size() == size_t(whole_.x) * size_t(whole_.y))
            codes = DenoiseStats::effectiveFramesTexture(whole_, *eff);
        for (int y = 0; y < whole_.y; y += rows) {
            const int count = std::min(rows, whole_.y - y);
            origin_ = tiled_ ? std::max(0, y - 64) : 0;
            end_ = tiled_ ? std::min(whole_.y, y + count + 64) : whole_.y;
            grids_.clear();
            grids_[input.get()] = Grid{whole_, 0};
            std::vector<Texture> owned;
            Texture prepared = tex(owned, 0, 4);
            prog.useAssetProgram("resolvedn/prepare", false);
            prog.setTexture("InputBuffer", input);
            prog.setTexture("GainMap", pipeline.GainMap);
            prog.setVar("toRenderU", WhyTone::vec(toRender));
            prog.setVar("neutralU", wp[0], wp[1], wp[2]);
            prog.setVar("originDstU", origin_);
            prog.drawBlocks(prepared);
            Texture filtered = tex(owned, 0, 4);
            program("optics", filtered);
            bind("u_tex_rgb", prepared);
            const bool speckle = prefs.hybridSwitch("resolve_speckle_enable", false);
            prog.setVar("u_quad_speckle_strength", speckle ? std::max(0.f, std::min(1.f, prefs.hybridValue("resolve_speckle_strength", 1.f))) : 0.f);
            prog.setVar("u_quad_speckle_threshold", std::max(0.01f, std::min(0.5f, prefs.hybridValue("resolve_speckle_threshold", 0.15f))));
            prog.drawBlocks(filtered);
            release(owned, prepared);
            if (pyramid) {
                Texture weights;
                if (codes) {
                    weights = tex(owned, 0, 1);
                    prog.useAssetProgram("resolvedn/frames", false);
                    prog.setTexture("EffMap", codes);
                    prog.setVar("effScaleU", scale);
                    prog.setVar("framesU", WhyResolveDenoise::defaultFrames(pipeline.merge.lastEffectiveMedian, pipeline.merge.lastNormals));
                    prog.setVar("totalU", float(std::max(1, pipeline.merge.lastNormals)));
                    prog.setVar("originDstU", origin_);
                    prog.drawBlocks(weights);
                    if (reuse && !tiled_) { codes->close(); codes = nullptr; }
                }
                Texture lab[4];
                lab[0] = tex(owned, 0, 4);
                program("lab", lab[0]);
                bind("u_tex_rgb", filtered);
                prog.drawBlocks(lab[0]);
                release(owned, filtered);
                filtered = nullptr;
                for (int l = 1; l <= 3; l++) {
                    lab[l] = tex(owned, l, 4);
                    program("down", lab[l]);
                    bind("u_tex_src", lab[l - 1]);
                    const Point fullSource = grids_[lab[l - 1].get()].full;
                    const Point fullTarget = grids_[lab[l].get()].full;
                    prog.setVar("u_texel_size", 1.f / float(fullSource.x), 1.f / float(fullSource.y));
                    prog.setVar("u_dst_w", fullTarget.x);
                    prog.setVar("u_dst_h", fullTarget.y);
                    prog.drawBlocks(lab[l]);
                }
                Texture rebuilt = tex(owned, 3, 4);
                program("base", rebuilt);
                bind("u_tex_src", lab[3]);
                prog.setVar("u_chroma_thresh", th[3][1]);
                prog.setVar("u_shadow_boost", shadow);
                prog.drawBlocks(rebuilt);
                for (int l = 2; l >= 0; l--) {
                    Texture detail = tex(owned, l, 4);
                    program("detail", detail);
                    bind("u_tex_fine", lab[l]);
                    bind("u_tex_coarse", lab[l + 1]);
                    bind("u_tex_sabre_weights", weights ? weights : lab[l]);
                    prog.setVar("u_has_sabre_weights", weights ? 1 : 0);
                    prog.setVar("u_level", l);
                    prog.setVar("u_luma_thresh", th[size_t(l)][0]);
                    prog.setVar("u_chroma_thresh", th[size_t(l)][1]);
                    prog.setVar("u_shadow_boost", shadow);
                    prog.drawBlocks(detail);
                    Texture next;
                    if (reuse && l == 0) { next = lab[l]; lab[l] = nullptr; }
                    else next = tex(owned, l, 4);
                    if (reuse && l == 0) { release(owned, weights); weights = nullptr; }
                    program("rebuild", next);
                    bind("u_tex_coarse_recon", rebuilt);
                    bind("u_tex_laplace_filt", detail);
                    prog.drawBlocks(next);
                    release(owned, detail);
                    release(owned, rebuilt);
                    release(owned, lab[l + 1]);
                    rebuilt = next;
                }
                release(owned, lab[0]);
                release(owned, weights);
                filtered = tex(owned, 0, 4);
                program("rgb", filtered);
                bind("u_tex_oklab", rebuilt);
                prog.drawBlocks(filtered);
                release(owned, rebuilt);
            }
            prog.useAssetProgram("resolvedn/finish", false);
            prog.setTexture("InputBuffer", input);
            prog.setTexture("Filtered", filtered);
            prog.setTexture("GainMap", pipeline.GainMap);
            prog.setVar("fromRenderU", WhyTone::vec(*fromRender));
            prog.setVar("neutralU", wp[0], wp[1], wp[2]);
            prog.setVar("filteredOriginU", origin_);
            drawRegion(*out, y, count);
            for (auto& t : owned) t->close();
        }
        WorkingTexture = out;
        if (codes) codes->close();
        grids_.clear();
        prog.closed = true;
        logI("SCAM_PIPELINE", std::string("resolve post: pyramid=") + (pyramid ? "true" : "false") + " model=" + javaFloatString(model) + " shadow="
             + javaFloatString(shadow) + " rows=" + std::to_string(rows));
    }

private:
    struct Grid { Point full; int origin = 0; };
    std::map<GLTexture*, Grid> grids_;
    Point whole_;
    int origin_ = 0, end_ = 0;
    bool tiled_ = false;

    void program(const std::string& name, const Texture& target) {
        const Grid grid = grids_[target.get()];
        glProg->setDefine("TILED", tiled_ ? 1 : 0);
        glProg->useAssetProgram("resolvedn/" + name, false);
        glProg->setVar("u_width", grid.full.x);
        glProg->setVar("u_height", grid.full.y);
        glProg->setVar("originDstU", grid.origin);
    }
    void bind(const std::string& name, const Texture& texture) {
        glProg->setTexture(name, texture);
        if (tiled_) {
            const Grid grid = grids_[texture.get()];
            glProg->setVar("full_" + name, grid.full.x, grid.full.y);
            glProg->setVar("origin_" + name, grid.origin);
        }
    }
    Texture tex(std::vector<Texture>& owned, int level, int channels) {
        const Point full{std::max(1, whole_.x >> level), std::max(1, whole_.y >> level)};
        const int first = origin_ >> level;
        const Point size{full.x, std::max(1, (end_ >> level) - first)};
        Texture t = makeTexture(size, GLFormat(DataType::FLOAT_16, channels), nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
        owned.push_back(t);
        grids_[t.get()] = Grid{full, first};
        return t;
    }
    void release(std::vector<Texture>& owned, const Texture& t) {
        if (!t) return;
        auto it = std::find(owned.begin(), owned.end(), t);
        if (it == owned.end()) return;
        owned.erase(it);
        grids_.erase(t.get());
        t->close();
    }
    void drawRegion(GLTexture& target, int y, int height) {
        target.BufferLoad();
        for (int row = 0; row < height; row += 256) {
            glViewport(0, y + row, target.mSize.x, std::min(256, height - row));
            glProg->draw();
        }
        glFlush();
    }
};

} // namespace scam_post
