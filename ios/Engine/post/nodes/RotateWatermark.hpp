// RotateWatermark.java (the watermark is PhotoWatermark's, P628 / P659): the last node - its program stays bound and GLBasePipeline draws it tile by tile into
// the RGBA8 output (yOffset per tile), turned by the camera's rotation and mirrored for a front camera.
#pragma once

#include "../Pipeline.hpp"

namespace scam_post {

class RotateWatermark : public Node {
public:
    explicit RotateWatermark(int rotation) : Node("", "Rotate"), rotate_(rotation) {}
    /// the shader's quarter turns of a sensor rotation (0, 90, 180, 270 degrees: 0, 3, 2, 1); -1 for any other
    static int quarterTurns(int degrees) {
        if (degrees < 0 || degrees > 270 || degrees % 90 != 0) return -1;
        return (4 - degrees / 90) % 4; // the shader turns the other way
    }
    void Compile() override {}
    void Run() override {
        PostPipeline& p = *basePipeline;
        GLProg& prog = *glProg;
        prog.useAssetProgram("RotateWatermark/addwatermark_rotate");
        prog.setTexture("InputBuffer", previousNode->WorkingTexture);
        prog.setVar("rotate", quarterTurns(rotate_));
        prog.setVar("mirror", p.mParameters.mirror ? 1 : 0);
        const Point rawSz = p.finalSize ? previousNode->WorkingTexture->mSize : *p.mParameters.rawSize;
        prog.setVar("cropSize", p.cropSize);
        prog.setVar("rawSize", rawSz);
    }

private:
    int rotate_;
};

} // namespace scam_post
