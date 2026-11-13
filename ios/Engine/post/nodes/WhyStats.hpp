// WhyStats.java one to one (the default GL model): whyLow (the box mean of the linear Rec.709 scene at half the grid), the
// Smart-HDR statistics chained on the GPU with WhyLumaSharpen's early passes queued behind them, then WhyAe.finish. The image
// passes through. Not ported (they throw): the faces' skin measure (Portrait with a detected face), a lab fragment's frozen stats.
#pragma once

#include "../why/WhyAeGpu.hpp"
#include "../why/WhyNoiseClamp.hpp"
#include "../why/WhyToneCore.hpp"

namespace scam_post {

/// a node whose sharpening passes WhyStats may issue early (WhyLumaSharpen.runEarly)
struct EarlySharpen {
    virtual ~EarlySharpen() = default;
    virtual bool runEarly(PostPipeline& pipeline, const Texture& input) = 0;
};

class WhyStats : public Node {
public:
    WhyStats() : Node("", "WhyStats") {}
    void Compile() override {}
    EarlySharpen* earlySharpen = nullptr;

    static WhyAe::AeSettings aeSettings(PostPipeline& pipeline, int width, int height, float inScale, std::optional<std::string>& clampLine) {
        WhyAe::AeSettings settings = WhyTone::settings(pipeline);
        settings.portraitFace = pipeline.mParameters.portraitEnabled && pipeline.mParameters.portraitFaceDetected;
        if (settings.portraitFace) throw std::runtime_error("post port: the faces' skin measure (Portrait) is not ported");
        settings.noiseS = pipeline.noiseS;
        settings.noiseO = pipeline.noiseO;
        settings.motionGain = std::max(1.f, pipeline.mParameters.postRawBoost);
        settings.evWhite = WhySkyMaskEv::evOffset(pipeline.mParameters.iso, pipeline.mParameters.exposureTime, pipeline.mParameters.aperture, inScale);
        settings.dcg = pipeline.mParameters.dcgShot;
        if (settings.dcg) throw std::runtime_error("post port: DCG is not ported");
        settings.dcgIsoScale = 1.f;
        clampLine = WhyNoiseClamp::apply(settings, pipeline.mParameters.fullResRequested > 0, pipeline.merge.lastEffectiveFrames, width, height,
                                         pipeline.merge.lastEffectiveMedian, pipeline.merge.lastNormals);
        settings.ccmSat = WhyTone::ccmSatComp(pipeline.mParameters);
        return settings;
    }

    void Run() override {
        PostPipeline& pipeline = *basePipeline;
        GLProg& prog = *glProg;
        WorkingTexture = previousNode->WorkingTexture;
        prog.closed = true;
        auto st = std::make_shared<WhyToneState>();
        pipeline.why = st;
        st->factor = WhyTone::lowFactor(pipeline.mParameters);
        st->inScale = float(std::pow(2.0, double(WhyTone::value("input_ev", 0.f))));
        const Point lowSize = WhyTone::reduced(WorkingTexture->mSize, st->factor);
        Texture gainMap = pipeline.GainMap, fallback;
        if (!gainMap) {
            const float one[4] = {1.f, 1.f, 1.f, 1.f};
            fallback = makeTexture({1, 1}, GLFormat(DataType::FLOAT_16, 4), one, GL_LINEAR, GL_CLAMP_TO_EDGE);
            gainMap = fallback;
        }
        st->low = makeTexture(lowSize, GLFormat(DataType::FLOAT_16, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
        prog.useAssetProgram("why/low", false);
        prog.setTexture("InputBuffer", WorkingTexture);
        WhyTone::setColour(prog, pipeline, *gainMap, st->inScale);
        prog.setVar("factorU", st->factor);
        prog.drawBlocks(st->low);
        prog.closed = true;
        st->low->BufferLoad();
        const int iso = pipeline.mParameters.iso, maxIso = 10000;
        std::optional<std::string> clampLine;
        WhyAe::AeSettings settings = aeSettings(pipeline, WorkingTexture->mSize.x, WorkingTexture->mSize.y, st->inScale, clampLine);
        if (clampLine) logI("SCAM_PIPELINE", *clampLine);
        const double noiseS0 = pipeline.noiseS, noiseO0 = pipeline.noiseO;
        st->noiseS = std::isfinite(pipeline.fusionNoiseS) && pipeline.fusionNoiseS > 0.f ? pipeline.fusionNoiseS : float(noiseS0);
        st->noiseO = std::isfinite(pipeline.fusionNoiseO) && pipeline.fusionNoiseO > 0.f ? pipeline.fusionNoiseO : float(noiseO0);
        {
            const float neff = WhyNoiseClamp::fusionFrames(pipeline.merge.lastEffectiveFrames, WorkingTexture->mSize.x, WorkingTexture->mSize.y,
                                                           pipeline.merge.lastEffectiveMedian, pipeline.merge.lastNormals);
            const float k = pipeline.merge.lastNoiseFactor;
            st->noiseMerged = float(double(k) * WhyNoiseClamp::modelScale(neff));
            char buf[256];
            std::snprintf(buf, sizeof buf, "WHY fusion noise: S=%.4g O=%.4g Neff=%.2f model x%.3g -> x%.4g", double(st->noiseS), double(st->noiseO),
                          double(neff), double(k), double(st->noiseMerged));
            logI("SCAM_PIPELINE", buf);
        }
        bool early = false;
        std::optional<WhyAe::Stats> gpu;
        if (Settings::current().scamDevSwitch("ae_gpu_chain", true)) {
            gpu = WhyAeGpu::chained(prog, *st->low, settings.metering);
            prog.closed = true;
            // the early sharpening queued behind the statistics (the Java issues it between the passes and their read-back:
            // the same passes on the same inputs)
            early = earlySharpen && earlySharpen->runEarly(pipeline, WorkingTexture);
            prog.closed = true;
        } else {
            throw std::runtime_error("post port: the statistics with the read-back between the passes (ae_gpu_chain 0) are not ported");
        }
        WhyAe::Stats stats = gpu ? *gpu : WhyAeGpu::cpuStats(*st->low, settings.metering);
        const WhyAe::Result r = WhyAe::finish(stats, settings, iso, maxIso);
        st->ae = r;
        pipeline.linearDisplayGain = r.ae;
        logI("SCAM_PIPELINE", "WHY SMART_HDR_STAT " + r.describe() + " iso=" + std::to_string(iso) + " maxIso=" + std::to_string(maxIso) + " inScale="
             + javaFloatString(st->inScale) + " low=" + std::to_string(lowSize.x) + "x" + std::to_string(lowSize.y) + " factor=" + std::to_string(st->factor)
             + (gpu ? " stats=gpu" : " readback=float") + (early ? " sharpen=early" : ""));
        if (fallback) fallback->close();
    }
};

} // namespace scam_post
