// LensFilter.java one to one: the lens filter on the linear scene RGB before the tone (DiffusionGauss's plan: a box pyramid, each
// Gaussian a separable blur on its level added to the level's sum, the levels summed from the coarsest up - lensgauss/down, blur,
// up), and P380's "FilmDiffusion": the film's own camera diffusion, the same passes with its share on the film format's frame.
// No filter (or strength 0): the node only passes its input on. The lab's frame (LabFrame) is not on iOS: the frame is the image.
#pragma once

#include "FilmHalation.hpp"
#include "../color/LensFilters.hpp"

namespace scam_post {

class LensFilter : public Node {
public:
    /// the lens filter of a shot
    LensFilter(std::string id, float strength, LensFilters::Tune tune) : Node("", "LensFilter"), id_(std::move(id)), strength_(strength), tune_(tune) {}
    /// P380: the film snapshot's diffusion
    explicit LensFilter(std::shared_ptr<const FilmSettings> film)
        : Node("", "FilmDiffusion"), id_(film->diffusion), strength_(1.f), tune_(LensFilters::Tune::defaults(film->diffusion)), film_(std::move(film)) {}
    void Compile() override {}

    /// LensFilter.film: null without a film, a diffusion or a share above 0
    static std::unique_ptr<LensFilter> film(const std::shared_ptr<const FilmSettings>& film) {
        if (!film || film->diffusion.empty() || !(diffusionP(*film) > 0)) return nullptr;
        return std::make_unique<LensFilter>(film);
    }
    /// LensFilter.of: null for none
    static std::unique_ptr<LensFilter> of(const LensFilters::Choice& choice) {
        if (!choice.tune) return nullptr;
        return std::make_unique<LensFilter>(choice.id, choice.strength, *choice.tune);
    }
    /// FilmSim.Settings.diffusionP: DiffusionModel.scatterAtGrade of its family at its grade x «Сила симуляции»
    static double diffusionP(const FilmSettings& f) {
        if (f.diffusion.empty()) return 0.0;
        return DiffusionModel::scatterAtGrade(f.diffusion, f.diffusionGrade) * double(f.strength);
    }

    void Run() override {
        PostPipeline& pipeline = *basePipeline;
        GLProg& prog = *glProg;
        const Texture in = previousNode->WorkingTexture;
        WorkingTexture = in;
        if (LensFilters::index(id_) <= 0 || strength_ <= 0.f) return;
        std::optional<DiffusionGauss::Plan> plan;
        if (film_) {
            const int filmLong = FilmHalation::filmLongPx(std::max(in->mSize.x, in->mSize.y), pipeline.mParameters);
            plan = DiffusionGauss::plan(id_, diffusionP(*film_), tune_, in->mSize.x, in->mSize.y, filmLong, film_->diffusionFrameUm());
        } else {
            plan = DiffusionGauss::plan(id_, strength_, tune_, in->mSize.x, in->mSize.y, std::max(in->mSize.x, in->mSize.y));
        }
        if (!plan) return;
        const GLFormat rgba(DataType::FLOAT_16, 4);
        std::vector<Texture> temp;
        auto release = [&](const Texture& t) {
            for (auto it = temp.begin(); it != temp.end(); ++it)
                if (*it == t) { (*it)->close(); temp.erase(it); return; }
        };
        int passes = 0;
        try {
            std::vector<Texture> level(size_t(plan->top) + 1);
            level[0] = in;
            for (int l = 1; l <= plan->top; l++) {
                level[size_t(l)] = makeTexture({DiffusionGauss::levelSize(in->mSize.x, l), DiffusionGauss::levelSize(in->mSize.y, l)}, rgba,
                                               nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
                temp.push_back(level[size_t(l)]);
                prog.useAssetProgram("lensgauss/down", false);
                prog.setTexture("InputBuffer", level[size_t(l) - 1]);
                prog.drawBlocks(level[size_t(l)]);
                passes++;
            }
            Texture coarse;
            for (int l = plan->top; l >= 0; l--) {
                const Point size = level[size_t(l)]->mSize;
                Texture sum;
                for (const DiffusionGauss::Blur& b : plan->blurs) {
                    if (b.level != l) continue;
                    Texture h = makeTexture(size, rgba, nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
                    Texture next = makeTexture(size, rgba, nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
                    temp.push_back(h);
                    temp.push_back(next);
                    prog.useAssetProgram("lensgauss/blur", false);
                    prog.setTexture("InputBuffer", level[size_t(l)]);
                    prog.setTexture("Acc", level[size_t(l)]);
                    prog.setVar("dir", 1.f, 0.f);
                    prog.setVar("sigma", b.sigma);
                    prog.setVar("weight", 1.f, 1.f, 1.f);
                    prog.setVar("accU", 0);
                    prog.setVar("hasAccU", 0);
                    prog.drawBlocks(h);
                    prog.useAssetProgram("lensgauss/blur", false);
                    prog.setTexture("InputBuffer", h);
                    prog.setTexture("Acc", sum ? sum : level[size_t(l)]);
                    prog.setVar("dir", 0.f, 1.f);
                    prog.setVar("sigma", b.sigma);
                    prog.setVar("weight", b.weight[0], b.weight[1], b.weight[2]);
                    prog.setVar("accU", 1);
                    prog.setVar("hasAccU", sum ? 1 : 0);
                    prog.drawBlocks(next);
                    passes += 2;
                    release(h);
                    if (sum) release(sum);
                    sum = next;
                }
                Texture target;
                if (l == 0) target = makeTexture(in->mSize, rgba, nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
                else {
                    target = makeTexture(size, rgba, nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
                    temp.push_back(target);
                }
                prog.useAssetProgram("lensgauss/up", false);
                prog.setTexture("Fine", sum ? sum : level[size_t(l)]);
                prog.setTexture("Coarse", coarse ? coarse : level[size_t(l)]);
                prog.setTexture("Image", level[size_t(l)]);
                prog.setVar("hasFineU", sum ? 1 : 0);
                prog.setVar("hasCoarseU", coarse ? 1 : 0);
                prog.setVar("finalU", l == 0 ? 1 : 0);
                prog.setVar("pU", float(plan->p));
                prog.drawBlocks(target);
                passes++;
                if (sum) release(sum);
                if (coarse) release(coarse);
                if (l > 0) release(level[size_t(l)]);
                coarse = target;
            }
            prog.closed = true;
            WorkingTexture = coarse;
            logI("SCAM_PIPELINE", (film_ ? "film diffusion " + film_->format->id + " grade " + jfmt::fixed(film_->diffusionGrade, 2) + " " : std::string("lens filter "))
                 + id_ + " p " + jfmt::fixed(plan->p, 3) + " gaussians " + std::to_string(plan->blurs.size()) + " levels "
                 + std::to_string(plan->top + 1) + " passes " + std::to_string(passes));
        } catch (...) {
            for (Texture& t : temp) t->close();
            throw;
        }
        for (Texture& t : temp) t->close();
    }

private:
    std::string id_;
    float strength_;
    LensFilters::Tune tune_;
    std::shared_ptr<const FilmSettings> film_;
};

} // namespace scam_post
