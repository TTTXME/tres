// ScamLocalContrast.java («Текстура», hybrid switch why_texture) one to one: the Laplacian levels 1-3 (and the finest level) of the
// display luminance scaled where their local amplitude is above the band's noise floor (a low percentile of a coarse read-back),
// less on strong edges. On the Sabre 2x grid the bands stay in sensor pixels (log2 of the scale extra fine levels).
#pragma once

#include "../GLUtils.hpp"
#include "../Pipeline.hpp"

#include <algorithm>
#include <chrono>

namespace scam_post {

class ScamLocalContrast : public Node {
public:
    ScamLocalContrast() : Node("", "ScamLocalContrast") {}
    void Compile() override {}

    void Run() override {
        PostPipeline& pipeline = *basePipeline;
        GLProg& prog = *glProg;
        Settings& prefs = Settings::current();
        GLUtils glUtils(prog);
        WorkingTexture = previousNode->WorkingTexture;
        const float amount = 1.f;
        const auto started = std::chrono::steady_clock::now();
        const GLFormat mono(DataType::FLOAT_16, 1);
        Texture luma = makeTexture(WorkingTexture->mSize, mono, nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
        prog.useAssetProgram("scamlc/luma", false);
        prog.setTexture("InputBuffer", WorkingTexture);
        prog.drawBlocks(*luma);
        const int s = std::max(1, int(Settings::javaRound(pipeline.mParameters.outputScale)));
        const int e = 31 - __builtin_clz(unsigned(s)); // floor(log2(s)): 0 at 1x, 1 on the 2x grid
        GLUtils::Pyramid pyramid = glUtils.createPyramid(5 + e, 2, luma);
        Texture energy[4];
        float floors[4];
        for (int i = 0; i < 4; i++) {
            const Texture& lap = pyramid.laplace[size_t(i < 3 ? e + i + 1 : e)];
            energy[i] = makeTexture(lap->mSize, mono, nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
            prog.useAssetProgram("scamlc/energy", false);
            prog.setTexture("InputBuffer", lap);
            prog.drawBlocks(*energy[i]);
            floors[i] = std::max(noiseFloor(*energy[i]), 1.0e-5f);
        }
        const bool hybrid = pipeline.merge.hybridShot;
        const float g1 = prefs.routeInternalValue("texture_g1", 1.25f, hybrid);
        const float g2 = prefs.routeInternalValue("texture_g2", 1.3f, hybrid);
        const float g3 = prefs.routeInternalValue("texture_g3", 1.3f, hybrid);
        // PreferenceKeys.isScamNetSoc (Build.SOC_MODEL SM8750): never an iPhone, nor the host reference
        const bool scamNetSoc = false;
        const float g0 = prefs.routeInternalValue("texture_g0", scamNetSoc ? 1.35f : 1.5f, hybrid);
        // the output is taken before the samplers are bound (ScamLocalContrast.java)
        const Texture out = pipeline.getMain();
        prog.useAssetProgram("scamlc/apply", false);
        prog.setTexture("InputBuffer", WorkingTexture);
        prog.setTexture("Lap0", pyramid.laplace[size_t(e)]);
        prog.setTexture("Lap1", pyramid.laplace[size_t(e) + 1]);
        prog.setTexture("Lap2", pyramid.laplace[size_t(e) + 2]);
        prog.setTexture("Lap3", pyramid.laplace[size_t(e) + 3]);
        prog.setTexture("En0", energy[3]);
        prog.setTexture("En1", energy[0]);
        prog.setTexture("En2", energy[1]);
        prog.setTexture("En3", energy[2]);
        prog.setVar("gain", (g1 - 1.f) * amount, (g2 - 1.f) * amount, (g3 - 1.f) * amount, (g0 - 1.f) * amount);
        prog.setVar("floorE", floors[0], floors[1], floors[2], floors[3]);
        prog.setVar("core", prefs.routeInternalValue("texture_core0", 0.8f, hybrid), prefs.routeInternalValue("texture_core1", 1.8f, hybrid));
        prog.setVar("bmax", prefs.routeInternalValue("texture_bmax", 0.12f, hybrid));
        WorkingTexture = out;
        prog.drawBlocks(*WorkingTexture);
        prog.closed = true;
        for (auto& en : energy) en->close();
        pyramid.releasePyramid();
        const long ms = long(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count());
        logI("SCAM_PIPELINE", "localContrast amount=" + javaFloatString(amount) + " gains=" + javaFloatString(g1) + "," + javaFloatString(g2) + ","
             + javaFloatString(g3) + " fine=" + javaFloatString(g0) + " floors=" + javaFloatString(floors[0]) + "," + javaFloatString(floors[1])
             + "," + javaFloatString(floors[2]) + "," + javaFloatString(floors[3]) + " step=" + std::to_string(s) + " subLevels="
             + std::to_string(e) + " ms=" + std::to_string(ms));
    }

private:
    /// noiseFloor: the low percentile (n / 4 of the sorted positive values) of a level's 96 x 72 block means read back as RGBA float
    float noiseFloor(const GLTexture& en) {
        GLProg& prog = *glProg;
        GLTexture coarse({96, 72}, GLFormat(DataType::FLOAT_16, 4), nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
        GLint oldRead = 0;
        GLuint framebuffer = 0;
        float result = 0.f;
        prog.useAssetProgram("scamlc/coarse", false);
        prog.setTexture("InputBuffer", en);
        prog.drawBlocks(coarse);
        glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &oldRead);
        glGenFramebuffers(1, &framebuffer);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, coarse.mTextureID, 0);
        std::vector<float> data(size_t(96) * 72 * 4);
        glReadPixels(0, 0, 96, 72, GL_RGBA, GL_FLOAT, data.data());
        std::vector<float> values;
        values.reserve(size_t(96) * 72);
        for (size_t i = 0; i < size_t(96) * 72; i++) {
            const float v = data[i * 4];
            if (v > 0.f && !std::isnan(v)) values.push_back(v);
        }
        if (!values.empty()) {
            std::sort(values.begin(), values.end());
            result = values[values.size() / 4];
        }
        glBindFramebuffer(GL_READ_FRAMEBUFFER, GLuint(oldRead));
        if (framebuffer) glDeleteFramebuffers(1, &framebuffer);
        coarse.close();
        return result;
    }
};

} // namespace scam_post
