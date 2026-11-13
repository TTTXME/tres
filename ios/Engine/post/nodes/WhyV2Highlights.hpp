// «Why тон» V2 (P614): WhyV2Highlights.java one to one - bracket_denoise when short frames were merged (the ceiling above
// 1.05) and its strength is above 0.001; hr_extract at half the grid, box_blur_h4 / v4 (radius 24), hr_apply at the grid when
// «Восстановление цвета светов» is above 0.001. Every target RGBA16F, LINEAR, clamp to edge. Replaces WhyV2State.clean; the
// image passes through.
#pragma once

#include "WhyV2Stats.hpp"

namespace scam_post {

class WhyV2Highlights : public Node {
public:
    WhyV2Highlights() : Node("", "WhyV2Highlights") {}
    void Compile() override {}

    static constexpr float HR_THRESHOLD = 0.85f;
    static constexpr int HR_RADIUS = 24;

    static bool bracketOn(float clip, float strength) { return clip > 1.05f && strength > 0.001f; }
    static bool hrOn(float amount) { return amount > 0.001f; }
    static Point half(Point size) { return {std::max(1, size.x / 2), std::max(1, size.y / 2)}; }

    void Run() override {
        PostPipeline& pipeline = *basePipeline;
        GLProg& prog = *glProg;
        WorkingTexture = previousNode->WorkingTexture;
        prog.closed = true;
        auto v2 = pipeline.whyV2;
        if (!v2 || !v2->ae || !v2->clean) throw std::runtime_error("WHY V2: no statistics (WhyV2Stats did not run)");
        const WhyV2Ae::Params& p = v2->params;
        const bool bracket = bracketOn(v2->ae->clip, p.bracketDenoise), hr = hrOn(p.hrAmount);
        if (!bracket && !hr) return;
        const Point size = v2->clean->mSize;
        if (bracket) {
            Texture out = rgba(size);
            prog.useAssetProgram("why/v2_bracket_dn", false);
            prog.setTexture("u_tex_rgb", v2->clean);
            prog.setVar("u_width", size.x);
            prog.setVar("u_height", size.y);
            prog.setVar("u_clip_ceiling", v2->ae->clip);
            prog.setVar("u_strength", p.bracketDenoise);
            prog.drawBlocks(out);
            prog.closed = true;
            v2->clean->close();
            v2->clean = out;
        }
        if (hr) {
            const Point h = half(size);
            Texture extracted, blurred, diffused;
            auto cleanup = [&] {
                for (Texture* t : {&extracted, &blurred, &diffused}) if (*t) (*t)->close();
            };
            try {
                extracted = rgba(h);
                prog.useAssetProgram("why/v2_hr_extract", false);
                prog.setTexture("u_tex_rgb", v2->clean);
                prog.setVar("u_width", h.x);
                prog.setVar("u_height", h.y);
                prog.setVar("u_threshold", HR_THRESHOLD);
                prog.drawBlocks(extracted);
                blurred = rgba(h);
                diffused = rgba(h);
                for (int dir = 0; dir < 2; dir++) {
                    prog.setDefine("DIR", dir);
                    prog.useAssetProgram("why/v2_box", false);
                    prog.setTexture("u_input_tex", dir == 0 ? extracted : blurred);
                    prog.setVar("u_width", h.x);
                    prog.setVar("u_height", h.y);
                    prog.setVar("u_radius", HR_RADIUS);
                    prog.drawBlocks(dir == 0 ? blurred : diffused);
                }
                Texture out = rgba(size);
                prog.useAssetProgram("why/v2_hr_apply", false);
                prog.setTexture("u_tex_rgb", v2->clean);
                prog.setTexture("u_tex_diffused_hr", diffused);
                prog.setVar("u_width", size.x);
                prog.setVar("u_height", size.y);
                prog.setVar("u_threshold", HR_THRESHOLD);
                prog.setVar("u_hr_amount", p.hrAmount);
                prog.drawBlocks(out);
                prog.closed = true;
                v2->clean->close();
                v2->clean = out;
            } catch (...) {
                cleanup();
                throw;
            }
            cleanup();
        }
        logI("SCAM_PIPELINE", "WHY V2 highlights bracket=" + javaFloatString(bracket ? p.bracketDenoise : 0.f) + " hr="
             + javaFloatString(hr ? p.hrAmount : 0.f));
    }

private:
    static Texture rgba(Point size) { return makeTexture(size, GLFormat(DataType::FLOAT_16, 4), nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE); }
};

} // namespace scam_post
