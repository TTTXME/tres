// FilmGrain.java one to one: the film's sharpness and grain on the finished picture at its final size (before the rotation) -
// a stock of the reference model: the particle model's density noise (film/noise, film/blur at 11.8 um x the format's scale)
// through the print and the scan (FilmSim.grainLut, film/grain_sf); our black-and-white model: its MTF blur and film/grain.
#pragma once

#include "FilmHalation.hpp"

namespace scam_post {

class FilmGrain : public Node {
public:
    static constexpr int GRAIN_LUT = 17;

    explicit FilmGrain(std::shared_ptr<const FilmSettings> set) : Node("", "FilmGrain"), set_(std::move(set)) {}
    void Compile() override {}

    /// FilmGrain.from: null without a simulation or a format, or with neither grain nor softness
    static std::unique_ptr<FilmGrain> from(const std::shared_ptr<const FilmSettings>& s) {
        if (!s || s->format->longMm <= 0 || (s->grain <= 0.f && (s->softness <= 0.f || !s->stock->spectral.empty()))) return nullptr;
        return std::make_unique<FilmGrain>(s);
    }
    /// FilmGrain.addsGrain
    static bool addsGrain(const FilmSettings* s) { return s && s->format->longMm > 0 && s->grain > 0.f; }
    /// the noise's seed: Math.abs(stock id.hashCode()) % 9973
    static float seed(const FilmStock& st) { return float(jfmt::absInt(jfmt::hashCode(st.id)) % 9973); }

    void Run() override {
        GLProg& prog = *glProg;
        const FilmSettings& set = *set_;
        const Texture in = previousNode->WorkingTexture;
        WorkingTexture = in;
        const float um = set.pixelUm(FilmHalation::filmLongPx(std::max(in->mSize.x, in->mSize.y), basePipeline->mParameters));
        if (um <= 0.f) return;
        Texture cur = in;
        if (!set.stock->spectral.empty()) {
            runReference(in, um);
            return;
        }
        const float blur = FilmSim::classicBlurPx(set, um);
        if (blur >= FilmSim::CLASSIC_BLUR_MIN_PX) {
            Texture h = makeTexture(in->mSize, in->mFormat);
            prog.useAssetProgram("film/blur", false);
            prog.setTexture("InputBuffer", cur);
            prog.setVar("dir", 1.f, 0.f);
            prog.setVar("sigma", blur);
            prog.drawBlocks(h);
            Texture v = makeTexture(in->mSize, in->mFormat);
            prog.useAssetProgram("film/blur", false);
            prog.setTexture("InputBuffer", h);
            prog.setVar("dir", 0.f, 1.f);
            prog.setVar("sigma", blur);
            prog.drawBlocks(v);
            prog.closed = true;
            h->close();
            cur = v;
        }
        float s = 0.f;
        if (set.grain > 0.f) {
            const FilmStock& st = *set.stock;
            s = FilmSim::classicGrainSigma(set, um);
            const float cell = FilmSim::classicGrainCell(set, um);
            Texture out = makeTexture(in->mSize, in->mFormat);
            prog.useAssetProgram("film/grain", false);
            prog.setTexture("InputBuffer", cur);
            prog.setVar("sigma", s, s, s * 1.25f);
            prog.setVar("cell", cell, cell, cell * 1.4f);
            prog.setVar("sharedPart", st.mono() ? 1.f : .55f);
            prog.setVar("slide", st.reversal() ? 1 : 0);
            prog.setVar("mono", st.mono() ? 1 : 0);
            prog.setVar("seed", seed(st));
            prog.drawBlocks(out);
            prog.closed = true;
            if (cur != in) cur->close();
            cur = out;
        }
        WorkingTexture = cur;
        logI("SCAM_PIPELINE", "film grain " + set.stock->id + " " + set.format->id + " pixel " + jfmt::fixed(um, 2) + " um blur " + jfmt::fixed(blur, 2)
             + " px grain sigma " + jfmt::fixed(s, 4) + " cell " + jfmt::fixed(FilmSim::classicGrainCell(set, um), 2) + " px");
    }

private:
    std::shared_ptr<const FilmSettings> set_;

    void runReference(const Texture& in, float um) {
        GLProg& prog = *glProg;
        const FilmSettings& set = *set_;
        if (set.grain <= 0.f) return;
        const FilmLutP lut = FilmSim::grainLut(set, GRAIN_LUT);
        if (!lut) return;
        const GLuint tex = lut->upload();
        const GLFormat f16(DataType::FLOAT_16, 4);
        Texture noise = makeTexture(in->mSize, f16);
        Texture h = makeTexture(in->mSize, noise->mFormat), v = makeTexture(in->mSize, noise->mFormat);
        auto cleanup = [&] {
            glDeleteTextures(1, &tex);
            noise->close();
            h->close();
            v->close();
        };
        try {
            prog.useAssetProgram("film/noise", false);
            prog.setVar("seed", seed(*set.stock));
            prog.drawBlocks(noise);
            const float blur = FilmSim::grainBlurUm(*set.format) / um;
            prog.useAssetProgram("film/blur", false);
            prog.setTexture("InputBuffer", noise);
            prog.setVar("dir", 1.f, 0.f);
            prog.setVar("sigma", blur);
            prog.drawBlocks(h);
            prog.useAssetProgram("film/blur", false);
            prog.setTexture("InputBuffer", h);
            prog.setVar("dir", 0.f, 1.f);
            prog.setVar("sigma", blur);
            prog.drawBlocks(v);
            Texture out = makeTexture(in->mSize, in->mFormat);
            prog.useAssetProgram("film/grain_sf", false);
            prog.setTexture("InputBuffer", in);
            prog.setTexture("Noise", v);
            prog.setTexture3D("GrainLut", tex);
            const float scale = set.grainApplied() / um;
            prog.setVar("scale", scale);
            prog.setVar("lutSize", float(GRAIN_LUT));
            prog.drawBlocks(out);
            prog.closed = true;
            WorkingTexture = out;
            logI("SCAM_PIPELINE", "film grain (reference) " + set.stock->id + " " + set.format->id + " pixel " + jfmt::fixed(um, 2) + " um blur "
                 + jfmt::fixed(blur, 2) + " px scale " + jfmt::fixed(scale, 4));
        } catch (...) {
            cleanup();
            throw;
        }
        cleanup();
    }
};

} // namespace scam_post
