// WhySky.java one to one: the sky's cells of whyLow on the GPU, the decision on the CPU (WhySkyMask), the mask texture.
#pragma once

#include "WhySkyMask.hpp"
#include "WhyToneCore.hpp"
#include "WhyNoiseClamp.hpp"

namespace scam_post {

struct WhySkyMaskResult : WhySkyMask::Mask {};

namespace WhySky {

inline GLFormat cellsFormat() { return GLFormat(DataType::FLOAT_32, 4); }
inline int maskBox(int w, int h) { return std::max(1, (std::max(w, h) + 2047) / 2048); }

inline std::vector<float> readCells(GLTexture& t) {
    std::vector<float> v(size_t(t.mSize.x) * t.mSize.y * 4);
    glReadPixels(0, 0, t.mSize.x, t.mSize.y, GL_RGBA, GL_FLOAT, v.data());
    return v;
}

inline bool topCanSeed(GLProg& prog, GLTexture& src, Point grid, int cell, int top, float ev, float ceiling) {
    const bool rows = top == WhySkyMask::TOP_ROW0 || top == WhySkyMask::TOP_ROWN;
    const Point edge = rows ? Point{grid.x, 1} : Point{1, grid.y};
    const int x = top == WhySkyMask::TOP_COLN ? grid.x - 1 : 0;
    const int y = top == WhySkyMask::TOP_ROWN ? grid.y - 1 : 0;
    try {
        GLTexture stats(edge, cellsFormat(), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
        prog.setDefine("MODE", 0);
        prog.useAssetProgram("why/skycells", false);
        prog.setTexture("InputBuffer", src);
        prog.setVar("cellU", cell);
        prog.setVar("cellOriginU", x, y);
        prog.setVar("stepU", WhySkyMask::step(cell));
        prog.setVar("clipU", WhySkyMask::CLIP_LEVEL * ceiling);
        prog.drawBlocks(stats);
        prog.closed = true;
        const auto mean = readCells(stats);
        if (glGetError() != GL_NO_ERROR) return true;
        return WhySkyMask::topCanSeed(mean, ev);
    } catch (const std::exception& e) {
        for (int i = 0; i < 8 && glGetError() != GL_NO_ERROR; i++) {}
        logI("SCAM_PIPELINE", std::string("WHY sky seed check failed; full cells: ") + e.what());
        return true;
    }
}

inline void build(GLProg& prog, PostPipeline& pipeline, WhyToneState& st, GLTexture& src) {
    st.sky = nullptr;
    st.skyMask = nullptr;
    if (!(WhyTone::sky() > 0.f)) return;
    if (pipeline.film) { logI("SCAM_PIPELINE", "WHY sky: none (a film simulation)"); return; }
    const Parameters& p = pipeline.mParameters;
    const WhyAe::Result& r = *st.ae;
    const float ev = WhySkyMaskEv::evOffset(p.iso, p.exposureTime, p.aperture, st.inScale);
    const Point low = src.mSize;
    if (!std::isfinite(ev) || ev + float(std::log(double(std::max(r.gmax, 1e-6f))) / std::log(2.0)) < WhySkyMask::EV_LO) {
        logI("SCAM_PIPELINE", std::string("WHY sky: none (") + (std::isfinite(ev) ? "the brightest EV " + WhySkyMask::fmt("%.1f", double(ev) + std::log(double(std::max(r.gmax, 1e-6f))) / std::log(2.0))
             + " under daylight" : "exposure unknown") + ")");
        return;
    }
    const float ceiling = std::max(r.clip, r.hr);
    const int cell = WhySkyMask::cellPx(low.x, low.y);
    const Point grid{(low.x + cell - 1) / cell, (low.y + cell - 1) / cell};
    if (Settings::current().hybridValue("why_sky_seed_gate", 1.f) > 0.f
        && !topCanSeed(prog, src, grid, cell, WhySkyMask::topEdge(p.cameraRotation, p.mirror), ev, ceiling)) {
        logI("SCAM_PIPELINE", "WHY sky: none (no seed on the photo's top edge)");
        return;
    }
    std::vector<float> read[2];
    for (int mode = 0; mode < 2; mode++) {
        GLTexture stats(grid, cellsFormat(), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
        prog.setDefine("MODE", mode);
        prog.useAssetProgram("why/skycells", false);
        prog.setTexture("InputBuffer", src);
        prog.setVar("cellU", cell);
        prog.setVar("cellOriginU", 0, 0);
        prog.setVar("stepU", WhySkyMask::step(cell));
        prog.setVar("clipU", WhySkyMask::CLIP_LEVEL * ceiling);
        prog.drawBlocks(stats);
        prog.closed = true;
        read[mode] = readCells(stats);
    }
    const WhySkyMask::Cells c = WhySkyMask::Cells::fromGpu(low.x, low.y, read[0], read[1]);
    const WhySkyMask::Scene scene{ev, WhySkyMask::topEdge(p.cameraRotation, p.mirror), ceiling};
    const WhySkyMask::Decision d = WhySkyMask::decide(c, scene);
    if (!d.mask) {
        logI("SCAM_PIPELINE", "WHY sky: " + d.log);
        return;
    }
    GLTexture cells({c.nx, c.ny}, GLFormat(DataType::FLOAT_16, 4), d.mask->cells.data(), GL_LINEAR, GL_CLAMP_TO_EDGE);
    const int box = maskBox(low.x, low.y);
    Texture mask = makeTexture(WhyTone::reduced(low, box), GLFormat(DataType::FLOAT_16, 2), nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
    prog.useAssetProgram("why/skymask", false);
    prog.setTexture("InputBuffer", src);
    prog.setTexture("SkyCells", cells);
    prog.setVar("cellScaleU", 1.f / float(c.cell * c.nx), 1.f / float(c.cell * c.ny));
    prog.setVar("cellOriginU", 0.f, 0.f);
    prog.setVar("boxU", box);
    prog.drawBlocks(mask);
    prog.closed = true;
    st.sky = mask;
    auto result = std::make_shared<WhySkyMaskResult>();
    static_cast<WhySkyMask::Mask&>(*result) = *d.mask;
    st.skyMask = result;
    logI("SCAM_PIPELINE", "WHY sky: " + d.log + " cells=" + std::to_string(c.nx) + "x" + std::to_string(c.ny) + "@" + std::to_string(c.cell));
}

} // namespace WhySky
} // namespace scam_post
