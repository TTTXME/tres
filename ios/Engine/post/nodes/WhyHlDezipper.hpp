// WhyHlDezipper.java one to one: the reference's highlight chroma dezipper on the worker's base highlight mask (HALF grid).
#pragma once

#include "ScamRgb.hpp"

namespace scam_post {

class WhyHlDezipper : public Node {
public:
    explicit WhyHlDezipper(MergeInfo::HlMask mask) : Node("", "WhyHlDezipper"), mask_(std::move(mask)) {}
    void Compile() override {}
    void Run() override {
        PostPipeline& pipeline = *basePipeline;
        Texture input = previousNode->WorkingTexture;
        WorkingTexture = input;
        glProg->closed = true;
        const Point size = input->mSize;
        const float s = std::max(1.f, std::min(2.f, pipeline.mParameters.outputScale));
        if (std::fabs(float(mask_.width) * 2 * s - float(size.x)) > 4 * s || std::fabs(float(mask_.height) * 2 * s - float(size.y)) > 4 * s
            || mask_.halves.size() < size_t(mask_.width) * size_t(mask_.height)) {
            logI("SCAM_PIPELINE", "why-hl-dezipper: skipped (mask " + std::to_string(mask_.width) + "x" + std::to_string(mask_.height) + " for "
                 + std::to_string(size.x) + "x" + std::to_string(size.y) + ")");
            return;
        }
        GLTexture m({mask_.width, mask_.height}, GLFormat(DataType::FLOAT_16, 1), nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glBindTexture(GL_TEXTURE_2D, m.mTextureID);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, mask_.width, mask_.height, GL_RED, GL_HALF_FLOAT, mask_.halves.data());
        const GLenum error = glGetError();
        glBindTexture(GL_TEXTURE_2D, 0);
        if (error != GL_NO_ERROR) {
            logI("SCAM_PIPELINE", "why-hl-dezipper: skipped (mask upload GL error " + std::to_string(error) + ")");
            return;
        }
        Texture out = pipeline.getMain();
        if (out == input) out = pipeline.getMain();
        GLProg& prog = *glProg;
        prog.useAssetProgram("scamdn/hl_dezipper", false);
        prog.setTexture("u_merged_rgb", input);
        prog.setTexture("u_highlight_mask", m);
        prog.setVar("u_tex_size", float(size.x), float(size.y), 1.f / float(size.x), 1.f / float(size.y));
        prog.setVar("u_sabre_scale", s);
        prog.setVar("u_inv_zoom", 1.f);
        prog.drawBlocks(out);
        prog.closed = true;
        WorkingTexture = out;
        logI("SCAM_PIPELINE", "why-hl-dezipper " + std::to_string(size.x) + "x" + std::to_string(size.y) + " mask " + std::to_string(mask_.width) + "x"
             + std::to_string(mask_.height) + " grid " + javaFloatString(s));
    }

private:
    MergeInfo::HlMask mask_;
};

} // namespace scam_post
