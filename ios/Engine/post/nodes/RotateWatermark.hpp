// RotateWatermark.java (no watermark): the last node - its program stays bound and GLBasePipeline draws it tile by tile into
// the RGBA8 output (yOffset per tile), turned by the camera's rotation and mirrored for a front camera.
#pragma once

#include "../Pipeline.hpp"

namespace scam_post {

class RotateWatermark : public Node {
public:
    explicit RotateWatermark(int rotation) : Node("", "Rotate"), rotate_(rotation) {}
    void Compile() override {}
    void Run() override {
        PostPipeline& p = *basePipeline;
        GLProg& prog = *glProg;
        prog.setDefine("WATERMARK", false);
        prog.useAssetProgram("RotateWatermark/addwatermark_rotate");
        prog.setTexture("InputBuffer", previousNode->WorkingTexture);
        int rot = -1;
        switch (rotate_) {
        case 0: rot = 0; break;
        case 90: rot = 3; break;
        case 180: rot = 2; break;
        case 270: rot = 1; break;
        }
        prog.setVar("rotate", rot);
        prog.setVar("mirror", p.mParameters.mirror ? 1 : 0);
        const Point rawSz = p.finalSize ? previousNode->WorkingTexture->mSize : *p.mParameters.rawSize;
        prog.setVar("cropSize", p.cropSize);
        prog.setVar("rawSize", rawSz);
    }

private:
    int rotate_;
};

} // namespace scam_post
