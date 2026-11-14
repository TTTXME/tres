// FilmHalation.java one to one: the film's in-emulsion scatter (a stock of the reference model with «Мягкость плёнки»,
// film/scatter) and its halation (three bounces on the Gaussian pyramid's level, film/blur, film/halo_sum, film/halation) on the
// linear scene light before the tone, sized on the film's frame (filmLongPx: Parameters.filmFrameScale; no lab fragments on iOS).
#pragma once

#include "../GLUtils.hpp"
#include "../Pipeline.hpp"

namespace scam_post {

class FilmHalation : public Node {
public:
    explicit FilmHalation(std::shared_ptr<const FilmSettings> set) : Node("", "FilmHalation"), set_(std::move(set)) {}
    void Compile() override {}

    /// FilmHalation.from: null without a simulation, a format or halation (nor the reference model's scatter)
    static std::unique_ptr<FilmHalation> from(const std::shared_ptr<const FilmSettings>& s) {
        if (!s || s->format->longMm <= 0) return nullptr;
        const auto& h = s->stock->halation;
        const bool halo = s->halation > 0.f && h[0] + h[1] + h[2] > 0.f, scatter = !s->stock->spectral.empty() && s->softness > 0.f;
        if (!halo && !scatter) return nullptr;
        return std::make_unique<FilmHalation>(s);
    }

    /// FilmHalation.filmLongPx: the film frame's long side in pixels of an image imageLong pixels long
    static int filmLongPx(int imageLong, const Parameters& p) {
        const float frame = p.filmFrameScale > 0.f ? p.filmFrameScale : 1.f;
        const float lab = 1.f; // Parameters.labWholeSize: the Android lab's fragments only
        if (frame == 1.f && lab == 1.f) return imageLong;
        return std::max(1, int(Settings::javaRound(float(imageLong) * frame * lab)));
    }

    static constexpr float SCATTER_CORE[3] = {2.2f, 2.0f, 1.6f}, SCATTER_TAIL[3] = {9.3f, 9.7f, 9.1f}, SCATTER_W[3] = {.78f, .65f, .67f};

    void Run() override {
        PostPipeline& pipeline = *basePipeline;
        GLProg& prog = *glProg;
        const FilmSettings& set = *set_;
        const Texture in = previousNode->WorkingTexture;
        WorkingTexture = in;
        const float um = set.pixelUm(filmLongPx(std::max(in->mSize.x, in->mSize.y), pipeline.mParameters));
        if (um <= 0.f) return;
        const GLFormat rgba(DataType::FLOAT_16, 4);
        std::vector<Texture> temp;
        auto closeTemp = [&] { for (Texture& t : temp) t->close(); };
        try {
            const std::array<float, 3> a = set.halationAmounts();
            const bool halo = a[0] + a[1] + a[2] > 0.f;
            Texture e1 = in;
            if (!set.stock->spectral.empty() && set.softness > 0.f) {
                const float k = set.softnessApplied() / um;
                Texture h = makeTexture(in->mSize, rgba);
                Texture h2 = halo ? makeTexture(in->mSize, rgba) : pipeline.getMain();
                temp.push_back(h);
                if (halo) temp.push_back(h2);
                for (int pass = 0; pass < 2; pass++) {
                    const Texture& dst = pass == 0 ? h : h2;
                    prog.useAssetProgram("film/scatter", false);
                    prog.setTexture("InputBuffer", pass == 0 ? in : h);
                    prog.setVar("dir", pass == 0 ? 1.f : 0.f, pass == 0 ? 0.f : 1.f);
                    prog.setVar("core", SCATTER_CORE[0] * k, SCATTER_CORE[1] * k, SCATTER_CORE[2] * k);
                    prog.setVar("tail", SCATTER_TAIL[0] * k, SCATTER_TAIL[1] * k, SCATTER_TAIL[2] * k);
                    prog.setVar("tailWeight", SCATTER_W[0], SCATTER_W[1], SCATTER_W[2]);
                    prog.drawBlocks(dst);
                }
                prog.closed = true;
                e1 = h2;
                if (!halo) WorkingTexture = e1;
            }
            if (!halo) { closeTemp(); return; }
            const float sigma = set.halationUm() / um;
            GLUtils glUtils(prog);
            std::vector<Texture> v{e1};
            while (0.8f * float(1 << v.size()) <= sigma * .5f && std::max(v.back()->mSize.x, v.back()->mSize.y) > 8) {
                const Texture last = v.back();
                Texture next = glUtils.gaussianDownsample(last, {std::max(1, last->mSize.x / 2), std::max(1, last->mSize.y / 2)});
                temp.push_back(next);
                v.push_back(next);
            }
            const int lv = int(v.size()) - 1;
            const Texture base = v[size_t(lv)];
            const float own = lv == 0 ? 0.f : 0.8f * float(1 << lv), scale = 1.f / float(1 << lv);
            Texture bounce[3];
            for (int k = 0; k < 3; k++) {
                const float sk = sigma * float(std::sqrt(double(k + 1)));
                const float rest = float(std::sqrt(double(std::max(sk * sk - own * own, 0.25f)))) * scale;
                Texture hx = makeTexture(base->mSize, rgba, nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
                Texture hy = makeTexture(base->mSize, rgba, nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
                temp.push_back(hx);
                temp.push_back(hy);
                prog.useAssetProgram("film/blur", false);
                prog.setTexture("InputBuffer", base);
                prog.setVar("dir", 1.f, 0.f);
                prog.setVar("sigma", rest);
                prog.drawBlocks(hx);
                prog.useAssetProgram("film/blur", false);
                prog.setTexture("InputBuffer", hx);
                prog.setVar("dir", 0.f, 1.f);
                prog.setVar("sigma", rest);
                prog.drawBlocks(hy);
                bounce[k] = hy;
            }
            Texture haloTex = makeTexture(base->mSize, rgba, nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
            temp.push_back(haloTex);
            prog.useAssetProgram("film/halo_sum", false);
            prog.setTexture("B1", bounce[0]);
            prog.setTexture("B2", bounce[1]);
            prog.setTexture("B3", bounce[2]);
            prog.drawBlocks(haloTex);
            const Texture out = pipeline.getMain();
            prog.useAssetProgram("film/halation", false);
            prog.setTexture("InputBuffer", e1);
            prog.setTexture("Halo", haloTex);
            prog.setVar("amount", a[0], a[1], a[2]);
            WorkingTexture = out;
            prog.drawBlocks(WorkingTexture);
            prog.closed = true;
            logI("SCAM_PIPELINE", "film halation " + set.stock->id + " " + set.format->id + " pixel " + jfmt::fixed(um, 2) + " um sigma "
                 + jfmt::fixed(sigma, 1) + " px level " + std::to_string(lv) + " amount " + jfmt::fixed(a[0], 3) + "/" + jfmt::fixed(a[1], 3) + "/"
                 + jfmt::fixed(a[2], 3));
        } catch (...) {
            closeTemp();
            throw;
        }
        closeTemp();
    }

private:
    std::shared_ptr<const FilmSettings> set_;
};

} // namespace scam_post
