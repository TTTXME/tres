// GLBasePipeline / PostPipeline / Node (processing/opengl) one to one: the nodes in order, the main1 / main2 ping-pong, a
// node's program drawn into its WorkingTexture after it, the last node's program drawn tile by tile into the RGBA8 output.
// The statics the Java nodes share (ScamHybridBurst.last*, ScamRgb.lastClip*) live in MergeInfo of the pipeline.
#pragma once

#include "GLCore.hpp"
#include "Parameters.hpp"
#include "Settings.hpp"
#include "color/ColourLook.hpp"

#include <array>
#include <functional>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace scam_post {

/// The log lines the Java nodes write (SCAM_PIPELINE ...): the iOS log and the host comparison read them.
inline std::function<void(const std::string&)>& postLog() {
    static std::function<void(const std::string&)> f = [](const std::string& s) { std::fprintf(stderr, "%s\n", s.c_str()); };
    return f;
}
inline void logI(const std::string& tag, const std::string& line) { postLog()(tag + ": " + line); }

/// What the worker's output and report gave the post pipeline (ScamNeuralClient into ScamHybridBurst's / ScamRgb's statics).
struct MergeInfo {
    bool hybridShot = true;                 // PreferenceKeys.isHybridShot: the SHDR merge made the RGB
    bool lastBentoApplied = false;
    float lastBentoFactor = 1.f, lastBentoUsClipped = 0.f;
    bool lastRgbHalf = false;
    int lastNormals = 1;
    float lastEffectiveMedian = NAN;
    float lastNoiseFactor = 1.f, lastNoiseDarkRead = 1.f;
    std::optional<std::array<float, 4>> lastDcgNoise;
    float lastMergedDngHeadroom = 1.f;
    std::string lastReport;
    std::optional<std::vector<uint8_t>> lastEffectiveFrames; // uint8 per output pixel (1/8 frame)
    struct HlMask { std::vector<uint16_t> halves; int width = 0, height = 0; };
    std::optional<HlMask> lastHlMask;       // ScamHybridBurst.lastHlMask (HALF of the sensor grid)
    std::optional<std::vector<uint8_t>> lastClipFlags; // ScamRgb.lastClipFlags
    float lastClipWhite = 0.f;              // ScamRgb.lastClipWhite
    bool lastRealClip = false;              // ScamRgb.lastRealClip
};

class PostPipeline;

class Node {
public:
    Texture WorkingTexture;
    std::string Name = "Node";
    std::string Rid;
    Node* previousNode = nullptr;
    PostPipeline* basePipeline = nullptr;
    GLProg* glProg = nullptr;
    bool LastNode = false;

    Node(std::string rid, std::string name) : Name(std::move(name)), Rid(std::move(rid)) {}
    virtual ~Node() = default;
    virtual void BeforeCompile() {}
    virtual void Compile() { glProg->useAssetProgram(Rid); }
    virtual void BeforeRun() {}
    virtual void Run() {}
    virtual void AfterRun() {}
    virtual Texture GetProgTex() { return WorkingTexture; }
};

/// Shared state of the «Why» tone (WhyTone.State): the colour matrix once per shot, and what WhyStats hands on.
struct WhyToneState;

class PostPipeline {
public:
    GLProg* glProg = nullptr;
    Parameters mParameters;
    MergeInfo merge;
    std::vector<std::unique_ptr<Node>> Nodes;
    Texture main1, main2, main3;
    std::function<Texture()> lazyMain1, lazyMain2;
    int texnum = 0;
    Point workSize;
    float noiseS = 0, noiseO = 0;
    float fusionNoiseS = NAN, fusionNoiseO = NAN;

    // PostPipeline's fields
    Texture FusionMap, GainMap;
    float linearDisplayGain = 1.f;
    float scamNoiseSigma = 0.f;
    bool signedRgb = false;
    std::shared_ptr<WhyToneState> why;
    Texture sunBloom;
    std::optional<Point> sunBloomSize;
    int sunBloomBlock = 0;
    Texture opposedShare;
    std::optional<Point> opposedShareSize;
    int opposedShareBlock = 0;
    std::optional<std::vector<float>> skyHdrRatio;
    std::optional<Point> skyHdrRatioSize;
    Point cropSize;
    std::optional<Point> finalSize;
    bool finalResized = false;
    float rawClipLevel = 1.f;
    bool captureDemosaic = false;
    // the worker RGB (Parameters.scamRgb): halves or floats as the worker wrote it; ScamRgb leaves its decimated float copy
    std::vector<uint8_t> scamRgb;
    bool scamRgbHalf = false;
    std::vector<float> scamRgbDecimated;
    void* film = nullptr;                   // FilmSim.Settings of the shot: no film on iOS yet (null)

    Texture getMain() {
        if (texnum == 1) {
            texnum = 2;
            if (!main2 && lazyMain2) { auto make = lazyMain2; lazyMain2 = nullptr; main2 = make(); }
            return main2;
        }
        texnum = 1;
        if (!main1 && lazyMain1) { auto make = lazyMain1; lazyMain1 = nullptr; main1 = make(); }
        return main1;
    }

    bool releaseMain(const Texture& t) {
        if (!t || (t != main1 && t != main2)) return false;
        const Point size = t->mSize;
        const GLFormat format = t->mFormat;
        auto make = [size, format] { return makeTexture(size, format, nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE); };
        t->close();
        if (t == main1) { main1 = nullptr; lazyMain1 = make; } else { main2 = nullptr; lazyMain2 = make; }
        return true;
    }

    void add(std::unique_ptr<Node> in) {
        if (!Nodes.empty()) in->previousNode = Nodes.back().get();
        in->basePipeline = this;
        in->glProg = glProg;
        Nodes.push_back(std::move(in));
    }

    /// PostPipeline.computeNoise (the DCG zones are not ported: an iPhone shot is no DCG shot)
    void computeNoise() {
        fusionNoiseS = NAN;
        fusionNoiseO = NAN;
        const auto& m = mParameters.noiseModeler;
        if (!m || m->computeModel.size() < 3) {
            logI("PostPipeline", "Missing noise model; using conservative fallback");
            noiseS = 1.0e-4f;
            noiseO = 1.0e-6f;
            fusionNoiseS = noiseS;
            fusionNoiseO = noiseO;
            return;
        }
        noiseS = float(m->computeModel[0].first) + float(m->computeModel[1].first) + float(m->computeModel[2].first);
        noiseO = float(m->computeModel[0].second) + float(m->computeModel[1].second) + float(m->computeModel[2].second);
        noiseS /= 3.f;
        noiseO /= 3.f;
        if (mParameters.dcgShot) throw std::runtime_error("post port: the DCG noise zones are not ported");
        noiseO = std::max(noiseO, 1.17549435e-38f);
        noiseS = std::max(noiseS, 1.17549435e-38f);
        if (std::isnan(fusionNoiseS)) fusionNoiseS = noiseS;
        if (std::isnan(fusionNoiseO)) fusionNoiseO = noiseO;
        fusionNoiseO = std::max(fusionNoiseO, 1.17549435e-38f);
        fusionNoiseS = std::max(fusionNoiseS, 1.17549435e-38f);
    }

    int getRotation() const { return mParameters.cameraRotation; }
    Point getRotatedCoords(Point in) const {
        switch (getRotation()) {
        case 90: case 270: return {in.y, in.x};
        default: return in;
        }
    }

    /// The per-node hook (ScamDiagnostics.gpu's place): the host check dumps every node's output here.
    std::function<void(const std::string&, const Texture&)> afterNode;

    /// GLBasePipeline.runAllToBitmap: every node, then the last node's program in tiles of TileSize rows into RGBA8 (rows as
    /// GL reads them: row 0 at the bottom of each tile, the Java bitmap's order).
    std::vector<uint8_t> runAllToRgba(Point outSize) {
        GLint bound = 0;
        glGetIntegerv(GL_FRAMEBUFFER_BINDING, &bound);
        for (size_t i = 0; i < Nodes.size(); ++i) {
            Node& node = *Nodes[i];
            node.BeforeCompile();
            node.Compile();
            node.BeforeRun();
            if (i == Nodes.size() - 1) glBindFramebuffer(GL_FRAMEBUFFER, GLuint(bound));
            try {
                node.Run();
            } catch (const std::exception& e) {
                throw std::runtime_error("post node " + node.Name + " (run): " + e.what());
            }
            if (i != Nodes.size() - 1) {
                if (!glProg->closed) {
                    glProg->drawBlocks(node.GetProgTex());
                    glProg->closed = true;
                }
                if (afterNode) afterNode(node.Name, node.WorkingTexture);
            }
            node.AfterRun();
        }
        if (texnum == 1) { if (main2) main2->close(); } else { if (main1) main1->close(); }
        // the final output (GLCoreBlockProcessing.drawBlocksToBitmap): yOffset per tile
        const int tile = GLDrawParams::TileSize();
        GLTexture target({outSize.x, std::min(tile, outSize.y)}, GLFormat(DataType::SIMPLE_8, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
        target.BufferLoad();
        std::vector<uint8_t> rgba(size_t(outSize.x) * size_t(outSize.y) * 4);
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        for (int y = 0; y < outSize.y; y += tile) {
            const int h = std::min(tile, outSize.y - y);
            glViewport(0, 0, outSize.x, h);
            glProg->setVar("yOffset", y);
            glProg->draw();
            glReadPixels(0, 0, outSize.x, h, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data() + size_t(y) * size_t(outSize.x) * 4);
        }
        checkGl("final output");
        glBindFramebuffer(GL_FRAMEBUFFER, GLuint(bound));
        if (texnum == 1) { if (main1) main1->close(); } else { if (main2) main2->close(); }
        if (main3) main3->close();
        lazyMain1 = lazyMain2 = nullptr;
        Nodes.clear();
        return rgba;
    }
};

} // namespace scam_post
