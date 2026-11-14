// GLUtils (processing/opengl) one to one for what the ported nodes take: the Laplacian pyramid (createPyramid with its
// Gaussian downsample, utils/gaussdown + utils/pyramiddiff) and releasePyramid.
#pragma once

#include "GLCore.hpp"

#include <vector>

namespace scam_post {

class GLUtils {
public:
    explicit GLUtils(GLProg& prog) : glProg(prog) {}

    struct Pyramid {
        std::vector<Texture> gauss, laplace;
        std::vector<Point> sizes;
        int levels = 0;
        double step = 2;
        /// releasePyramid: every level, the input (gauss[0]) too
        void releasePyramid() {
            for (auto& t : gauss) if (t) t->close();
            for (auto& t : laplace) if (t) t->close();
        }
    };

    /// gaussianDownsample: the input's format, utils/gaussdown at the input / output ratio
    Texture gaussianDownsample(const Texture& in, Point outSize) {
        const double downscaleFactor = double(in->mSize.x) / outSize.x;
        Texture out = makeTexture(outSize, in->mFormat);
        glProg.useUtilProgram("gaussdown", false);
        glProg.setTexture("srcLevel", in);
        glProg.setVar("srcSize", in->mSize);
        glProg.setVar("srcPerOut", float(downscaleFactor));
        glProg.drawBlocks(*out, outSize);
        glProg.closed = true;
        return out;
    }

    /// createPyramid(levels, step, input)
    Pyramid createPyramid(int levels, double step, const Texture& input) {
        Pyramid pyramid;
        pyramid.levels = levels;
        pyramid.step = step;
        pyramid.gauss.resize(size_t(levels));
        pyramid.gauss[0] = input;
        pyramid.sizes.resize(size_t(levels));
        pyramid.sizes[0] = input->mSize;
        const bool autostep = step == 0;
        for (int i = 1; i < levels; i++) {
            const Point insize = pyramid.gauss[size_t(i) - 1]->mSize;
            if (autostep && (insize.x <= step + 2 || insize.y <= step + 2)) step = 2;
            int sizex = int(insize.x / step);
            int sizey = int(insize.y / step);
            sizex = std::max(1, sizex);
            sizey = std::max(1, sizey);
            pyramid.gauss[size_t(i)] = gaussianDownsample(pyramid.gauss[size_t(i) - 1], {sizex, sizey});
            pyramid.sizes[size_t(i)] = {int(pyramid.sizes[size_t(i) - 1].x / step), int(pyramid.sizes[size_t(i) - 1].y / step)};
        }
        glProg.useUtilProgram("pyramiddiff", false);
        pyramid.laplace.resize(size_t(levels) - 1);
        for (size_t i = 0; i < pyramid.laplace.size(); i++) {
            glProg.setTexture("fineLevel", pyramid.gauss[i]);
            glProg.setTexture("coarseLevel", pyramid.gauss[i + 1]);
            glProg.setVar("fineTexel", 1.0f / float(pyramid.sizes[i].x), 1.0f / float(pyramid.sizes[i].y));
            pyramid.laplace[i] = makeTexture(pyramid.sizes[i], pyramid.gauss[i + 1]->mFormat);
            glProg.drawBlocks(*pyramid.laplace[i]);
        }
        return pyramid;
    }

private:
    GLProg& glProg;
};

} // namespace scam_post
