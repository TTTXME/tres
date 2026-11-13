// PortraitGpu.java one to one: portrait/sample.glsl takes a 192 px (longer side) sample of a texture (RGBA32F, the 4 x 4 box of
// each sample point), read back and measured on the CPU (PortraitSkin.measure). measure: whyLow (WhyStats); measureScene: the
// camera's linear image through the shot's colour (CAMERA_SOURCE 1, the film's scene: ReferenceFilm).
#pragma once

#include "../why/WhyToneCore.hpp"
#include "PortraitSkin.hpp"

namespace scam_post {
namespace PortraitGpu {

inline std::optional<PortraitSkinResult> measure(GLProg& prog, const Texture& source, const std::vector<float>& faces, PostPipeline* pipeline) {
    if (faces.size() < 4) return std::nullopt;
    std::optional<PortraitSkinResult> skin;
    try {
        const int side = std::max(source->mSize.x, source->mSize.y);
        const Point size{std::max(1, source->mSize.x * 192 / side), std::max(1, source->mSize.y * 192 / side)};
        // Java: setTexture("GainMap", null) throws (caught below: no measure)
        if (pipeline && !pipeline->GainMap) throw std::runtime_error("no gain map");
        Texture sample = makeTexture(size, GLFormat(DataType::FLOAT_32, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
        try {
            if (pipeline) prog.setDefine("CAMERA_SOURCE", 1);
            prog.useAssetProgram("portrait/sample", false);
            prog.setTexture("InputBuffer", source);
            if (pipeline) WhyTone::setColour(prog, *pipeline, *pipeline->GainMap, 1.f);
            prog.setVar("sampleSizeU", size.x, size.y);
            prog.drawBlocks(sample);
            std::vector<float> rgb(size_t(size.x) * size_t(size.y) * 4);
            sample->textureBuffer(GLFormat(DataType::FLOAT_32, 4), rgb.data());
            if (glGetError() == GL_NO_ERROR) {
                skin = PortraitSkin::measure(rgb.data(), rgb.size(), 4, size.x, size.y, faces, 1.f);
                logI("SCAM_PIPELINE", "Portrait skin " + std::to_string(size.x) + "x" + std::to_string(size.y) + ": " + skin->describe());
            }
        } catch (...) {
            sample->close();
            throw;
        }
        sample->close();
    } catch (const std::exception& e) {
        logI("SCAM_PIPELINE", std::string("Portrait skin sample skipped: ") + e.what());
        skin.reset();
    }
    source->BufferLoad();
    prog.closed = true;
    return skin;
}

/// PortraitGpu.measure: whyLow's sample (already the linear Rec.709 scene)
inline std::optional<PortraitSkinResult> measure(GLProg& prog, const Texture& source, const std::vector<float>& faces) {
    return measure(prog, source, faces, nullptr);
}

/// PortraitGpu.measureScene: the camera's image through the shot's colour (sample.glsl CAMERA_SOURCE 1)
inline std::optional<PortraitSkinResult> measureScene(GLProg& prog, PostPipeline& pipeline, const Texture& source, const std::vector<float>& faces) {
    return measure(prog, source, faces, &pipeline);
}

} // namespace PortraitGpu
} // namespace scam_post
