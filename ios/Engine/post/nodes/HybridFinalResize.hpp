// HybridFinalResize.java one to one: the final photo size (P503 «Уменьшить до», below the Sabre grid's size) made on the GPU as the
// last processing step before the rotation, with the downsampler of pref_scam_hybrid_downsampler (hybrid/final_resize: Lanczos-3,
// bicubic, area, bilinear). The idle ping-pong textures are freed before the allocation.
#pragma once

#include "../Pipeline.hpp"

#include <chrono>

namespace scam_post {

class HybridFinalResize : public Node {
public:
    HybridFinalResize(Point target, std::string kernelName) : Node("", "FinalResize"), target_(target), kernelName_(std::move(kernelName)) {
        if (kernelName_.empty()) kernelName_ = "lanczos";
    }

    static int kernelIndex(const std::string& name) {
        if (name == "bicubic") return 1;
        if (name == "area") return 2;
        if (name == "bilinear") return 3;
        return 0;
    }

    void Compile() override {}

    /// HybridFinalResize.resized: the linear scene snapshot of Ultra HDR at the final size (LINEAR_IN: the input is linear light)
    static Texture resized(GLProg& prog, const Texture& in, Point target, const std::string& kernelName) {
        if (target.x >= in->mSize.x || target.y >= in->mSize.y || target.x * 4 < in->mSize.x || target.y * 4 < in->mSize.y)
            throw std::runtime_error("resize " + std::to_string(in->mSize.x) + "x" + std::to_string(in->mSize.y) + " -> "
                                     + std::to_string(target.x) + "x" + std::to_string(target.y) + " out of range");
        prog.setDefine("LINEAR_IN", 1);
        prog.useAssetProgram("hybrid/final_resize", false);
        prog.setTexture("InputBuffer", in);
        prog.setVar("kernel", kernelIndex(kernelName));
        prog.setVar("inSize", in->mSize.x, in->mSize.y);
        prog.setVar("outSize", target.x, target.y);
        Texture out = makeTexture(target, in->mFormat, nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
        prog.drawBlocks(*out);
        prog.closed = true;
        return out;
    }

    void Run() override {
        PostPipeline& p = *basePipeline;
        GLProg& prog = *glProg;
        const Texture in = previousNode->WorkingTexture;
        if (target_.x >= in->mSize.x || target_.y >= in->mSize.y || target_.x * 4 < in->mSize.x || target_.y * 4 < in->mSize.y)
            throw std::runtime_error("final resize " + std::to_string(in->mSize.x) + "x" + std::to_string(in->mSize.y) + " -> "
                                     + std::to_string(target_.x) + "x" + std::to_string(target_.y)
                                     + " out of range (the output buffer is already at the final size)");
        const auto started = std::chrono::steady_clock::now();
        p.lazyMain2 = nullptr;
        p.lazyMain1 = nullptr;
        Texture* mains[3] = {&p.main1, &p.main2, &p.main3};
        for (Texture* m : mains) {
            if (*m && *m != in) {
                (*m)->close();
                *m = nullptr;
            }
        }
        prog.useAssetProgram("hybrid/final_resize", false);
        prog.setTexture("InputBuffer", in);
        prog.setVar("kernel", kernelIndex(kernelName_));
        prog.setVar("inSize", in->mSize.x, in->mSize.y);
        prog.setVar("outSize", target_.x, target_.y);
        WorkingTexture = makeTexture(target_, in->mFormat, nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
        prog.drawBlocks(*WorkingTexture);
        p.finalResized = true;
        const long ms = long(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count());
        logI("SCAM_HDR", "hybrid final size " + std::to_string(target_.x) + "x" + std::to_string(target_.y) + " (" + kernelName_ + ", GPU) from "
             + std::to_string(in->mSize.x) + "x" + std::to_string(in->mSize.y) + " ms=" + std::to_string(ms));
    }

private:
    Point target_;
    std::string kernelName_;
};

} // namespace scam_post
