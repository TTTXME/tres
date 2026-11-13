// OldstyleTone.java and OldstyleStyleNode.java one to one: «Oldstyle» («ScamMaster Тон»), the mode's tone in place of «Why тон»
// through the owner's tone-port module (oldstyle/ToneNative.hpp), then its style.
//   1. the plan from the clean linear RGB's strided samples (why/v2_clean, why/v2_stride; OldstyleAe.plan), the version by memory;
//   2. the module's frame (oldstyle/linear, RGBA16I) read back in strips of STRIP rows into one planar int16 frame;
//   3. one call of the module on the whole frame (CPU);
//   4. the RGB8 picture into the pipeline's main texture (oldstyle/rgb8).
// OldstyleStyleNode: oldstyle/adjust with the style's uniforms after the tone.
#pragma once

#include "../oldstyle/OldstyleAe.hpp"
#include "../oldstyle/ToneNative.hpp"
#include "../why/WhyToneCore.hpp"
#include "WhyV2Stats.hpp"

#include <chrono>
#include <thread>

#if defined(__APPLE__)
#include <TargetConditionals.h>
#if TARGET_OS_IPHONE
#include <os/proc.h>
#endif
#endif

namespace scam_post {

class OldstyleTone : public Node {
public:
    OldstyleTone(bool v2, OldstyleAe::Params params, bool postview) : Node("", "OldstyleTone"), v2_(v2), params_(params), postview_(postview) {}
    void Compile() override {}

    static constexpr int BYTES_V1_SDR = 18, BYTES_V1_HDR = 34, BYTES_V2_SDR = 30, BYTES_V2_HDR = 24;
    static constexpr float MEMORY_SHARE = 0.6f;
    static constexpr int STRIP = 256;

    static bool read16(GLint implementationFormat, GLint implementationType, bool allowed) {
        return allowed && implementationFormat == GL_RGBA_INTEGER && implementationType == GL_SHORT;
    }
    static int64_t bytes(bool v2, bool hdr, int64_t pixels) {
        return pixels * (v2 ? (hdr ? BYTES_V2_HDR : BYTES_V2_SDR) : (hdr ? BYTES_V1_HDR : BYTES_V1_SDR));
    }
    static bool route(bool chosen, bool hdr, int64_t pixels, int64_t available, float share) {
        if (!chosen) return false;
        if (available <= 0) return pixels <= 100000000LL;
        return bytes(true, hdr, pixels) <= int64_t(double(available) * double(share));
    }
    /// ActivityManager.MemoryInfo.availMem's place: the memory the app may still take (os_proc_available_memory on the iPhone),
    /// 0 elsewhere (not known: the 100 MP rule; the Android host reference's ActivityManager reports none either)
    static int64_t availableMemory() {
#if defined(__APPLE__) && TARGET_OS_IPHONE
        return int64_t(os_proc_available_memory());
#else
        return 0;
#endif
    }
    /// OldstyleNative.threadsFor
    static int threadsFor(int cores, float dev) {
        const long asked = Settings::javaRound(dev);
        if (asked >= 1) return int(std::min<long>(16, asked));
        return std::max(2, std::min(8, cores - 1));
    }
    /// the dehaze's U / V gain map from the lens-shading gain map (null without one)
    static std::optional<std::vector<float>> shadingGrid(const std::vector<float>& gainMap, int mapW, int mapH) {
        if (mapW < 2 || mapH < 2 || gainMap.size() < size_t(mapW) * size_t(mapH) * 4) return std::nullopt;
        std::vector<float> lsc(size_t(mapW) * size_t(mapH));
        float min = std::numeric_limits<float>::max();
        for (size_t i = 0; i < lsc.size(); i++) {
            const float r = gainMap[i * 4], g = (gainMap[i * 4 + 1] + gainMap[i * 4 + 2]) * 0.5f, b = gainMap[i * 4 + 3];
            lsc[i] = (r + g + b) / 3.f;
            if (lsc[i] > 0.f) min = jmath::min(min, lsc[i]);
        }
        if (!(min > 0.f) || min == std::numeric_limits<float>::max()) return std::nullopt;
        std::vector<float> out(lsc.size() * 2);
        for (size_t i = 0; i < lsc.size(); i++) out[i * 2] = out[i * 2 + 1] = jmath::max(1.f, lsc[i] / min);
        return out;
    }
    /// OldstyleNative.ccmStrength: identity + strength x (ccm - identity)
    static std::array<float, 9> ccmStrength(const std::array<float, 9>& ccm, float strength) {
        if (strength == 1.f || !std::isfinite(strength)) return ccm;
        std::array<float, 9> out{};
        for (size_t i = 0; i < 9; i++) {
            const float id = i % 4 == 0 ? 1.f : 0.f;
            out[i] = id + strength * (ccm[i] - id);
        }
        return out;
    }

    void Run() override {
        PostPipeline& pipeline = *basePipeline;
        GLProg& prog = *glProg;
        const Texture input = previousNode->WorkingTexture;
        const Point size = input->mSize;
        const auto started = std::chrono::steady_clock::now();
        auto ms = [](std::chrono::steady_clock::time_point a, std::chrono::steady_clock::time_point b) {
            return std::to_string(long(std::chrono::duration<double, std::milli>(b - a).count()));
        };
        const unsigned cores = std::max(1u, std::thread::hardware_concurrency());
        const int threads = ToneNative::setThreads(threadsFor(int(cores), Settings::current().scamDevNumber("oldstyle_threads", 0.f)));
        const int64_t pixels = int64_t(size.x) * size.y, available = availableMemory();
        // what the Why tones consume of the merge's side data: not drawn by this tone, given back here
        if (pipeline.sunBloom) { pipeline.sunBloom->close(); pipeline.sunBloom = nullptr; }
        if (pipeline.opposedShare) { pipeline.opposedShare->close(); pipeline.opposedShare = nullptr; }
        pipeline.skyHdrRatio.reset();
        pipeline.skyHdrRatioSize.reset();
        Texture gainMap = pipeline.GainMap, fallback, clean, samples, frame, rgb;
        auto cleanup = [&] {
            for (Texture* t : {&fallback, &clean, &samples, &frame, &rgb}) if (*t) { (*t)->close(); *t = nullptr; }
        };
        try {
            if (!gainMap) {
                const float one[4] = {1.f, 1.f, 1.f, 1.f};
                fallback = makeTexture({1, 1}, GLFormat(DataType::FLOAT_16, 4), one, GL_LINEAR, GL_CLAMP_TO_EDGE);
                gainMap = fallback;
            }
            // 1. the plan from the clean linear RGB's samples
            clean = makeTexture(size, GLFormat(DataType::FLOAT_16, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
            prog.useAssetProgram("why/v2_clean", false);
            prog.setTexture("InputBuffer", input);
            WhyTone::setColour(prog, pipeline, *gainMap, 1.f);
            prog.drawBlocks(clean);
            const int stride = WhyV2Ae::stride(size.x, size.y);
            const int sw = WhyV2Ae::samples(size.x, stride), sh = WhyV2Ae::samples(size.y, stride);
            samples = makeTexture({sw, sh}, GLFormat(DataType::FLOAT_16, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
            prog.useAssetProgram("why/v2_stride", false);
            prog.setTexture("u_tex_rgb", clean);
            prog.setVar("u_stride", stride);
            prog.drawBlocks(samples);
            prog.closed = true;
            clean->close();
            clean = nullptr;
            samples->BufferLoad();
            const auto samplesStart = std::chrono::steady_clock::now();
            const std::vector<uint8_t> px = samples->textureBuffer(GLFormat(DataType::FLOAT_16, 4));
            std::vector<float> f(px.size() / 4);
            std::memcpy(f.data(), px.data(), f.size() * 4);
            std::vector<float> rgbSamples(size_t(sw) * size_t(sh) * 3);
            for (size_t i = 0; i < size_t(sw) * size_t(sh); i++)
                for (size_t c = 0; c < 3; c++) {
                    const float v = f[i * 4 + c];
                    rgbSamples[i * 3 + c] = std::isfinite(v) ? v : 0.f;
                }
            samples->close();
            samples = nullptr;
            const auto planStart = std::chrono::steady_clock::now();
            const bool shorts = WhyV2Stats::shortFramesMerged(pipeline.merge.lastBentoApplied, pipeline.merge.lastReport);
            const double frameTet = pipeline.mParameters.exposureTime * 1e9 * pipeline.mParameters.iso;
            // the version by memory first (the plan of V2 and V1 differ in their rules), with the HDR of V2's plan
            params_.v31 = v2_;
            OldstyleAe::Plan plan = OldstyleAe::plan(rgbSamples, sw, sh, params_, shorts ? WhyV2Stats::MAX_CEILING : 1.f);
            const bool useV2 = route(v2_, plan.hdr, pixels, available, params_.memoryShare);
            if (v2_ && !useV2) {
                logI("SCAM_PIPELINE", "Oldstyle V2 chosen, V1 used: " + std::to_string(bytes(true, plan.hdr, pixels) >> 20) + " MB of buffers for "
                     + std::to_string(size.x) + "x" + std::to_string(size.y) + ", " + std::to_string(available >> 20) + " MB available");
                params_.v31 = false;
                plan = OldstyleAe::plan(rgbSamples, sw, sh, params_, shorts ? WhyV2Stats::MAX_CEILING : 1.f);
            }
            if (!useV2 && bytes(false, plan.hdr, pixels) > int64_t(double(available) * double(params_.memoryShare)) && available > 0)
                logI("SCAM_PIPELINE", "Oldstyle: " + std::to_string(bytes(false, plan.hdr, pixels) >> 20) + " MB of V1 buffers for "
                     + std::to_string(size.x) + "x" + std::to_string(size.y) + " above the budget of " + std::to_string(available >> 20)
                     + " MB available, run anyway");
            pipeline.linearDisplayGain = plan.gainLong;
            // 2. the module's frame, read back in strips into the planar int16 frame
            const std::vector<float>& s2iv = *pipeline.mParameters.sensorToProPhoto;
            std::array<float, 9> s2i{};
            for (size_t i = 0; i < 9 && i < s2iv.size(); i++) s2i[i] = s2iv[i];
            const Mat3 i2s = WhyTone::colourMatrix(pipeline);
            frame = makeTexture(size, GLFormat(DataType::SIGNED_16, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
            prog.setDefine("PRE_CCM", useV2 ? 0 : 1);
            prog.useAssetProgram("oldstyle/linear", false);
            prog.setTexture("InputBuffer", input);
            WhyTone::setColour(prog, pipeline, *gainMap, 1.f);
            prog.setVar("uScale", plan.inputScale * float(ToneNative::WHITE));
            prog.drawBlocks(frame);
            prog.closed = true;
            const auto readStart = std::chrono::steady_clock::now();
            const size_t plane = size_t(size.x) * size_t(size.y);
            std::vector<int16_t> planar(plane * 3);
            std::vector<int32_t> strip(size_t(size.x) * size_t(std::min(STRIP, size.y)) * 4);
            frame->BufferLoad();
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            // 16-bit samples where the driver reads this framebuffer so; a refused read goes back to GL_INT from that strip on
            GLint pair[2] = {0, 0};
            glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_FORMAT, &pair[0]);
            glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_TYPE, &pair[1]);
            bool short16 = read16(pair[0], pair[1], Settings::current().scamDevSwitch("oldstyle_read16", true));
            const bool asked16 = short16;
            while (glGetError() != GL_NO_ERROR) {}
            for (int y = 0; y < size.y; y += STRIP) {
                const int rows = std::min(STRIP, size.y - y);
                if (short16) {
                    auto* s16 = reinterpret_cast<int16_t*>(strip.data());
                    glReadPixels(0, y, size.x, rows, GL_RGBA_INTEGER, GL_SHORT, s16);
                    if (glGetError() == GL_NO_ERROR) {
                        for (int r = 0; r < rows; r++) {
                            const int16_t* in = s16 + size_t(r) * size_t(size.x) * 4;
                            const size_t o = size_t(y + r) * size_t(size.x);
                            for (int x = 0; x < size.x; x++) {
                                planar[o + size_t(x)] = in[x * 4];
                                planar[plane + o + size_t(x)] = in[x * 4 + 1];
                                planar[2 * plane + o + size_t(x)] = in[x * 4 + 2];
                            }
                        }
                        continue;
                    }
                    short16 = false;
                }
                glReadPixels(0, y, size.x, rows, GL_RGBA_INTEGER, GL_INT, strip.data());
                for (int r = 0; r < rows; r++) {
                    const int32_t* in = strip.data() + size_t(r) * size_t(size.x) * 4;
                    const size_t o = size_t(y + r) * size_t(size.x);
                    for (int x = 0; x < size.x; x++) {
                        planar[o + size_t(x)] = static_cast<int16_t>(in[x * 4]);
                        planar[plane + o + size_t(x)] = static_cast<int16_t>(in[x * 4 + 1]);
                        planar[2 * plane + o + size_t(x)] = static_cast<int16_t>(in[x * 4 + 2]);
                    }
                }
            }
            glPixelStorei(GL_PACK_ALIGNMENT, 4);
            frame->close();
            frame = nullptr;
            std::vector<int32_t>().swap(strip);
            // 3. the module
            const auto toneStart = std::chrono::steady_clock::now();
            std::vector<uint8_t> out(plane * 3);
            std::optional<std::vector<float>> shading;
            if (params_.dehaze && params_.dehazeShading && pipeline.mParameters.mapSize && pipeline.mParameters.gainMap)
                shading = shadingGrid(*pipeline.mParameters.gainMap, pipeline.mParameters.mapSize->x, pipeline.mParameters.mapSize->y);
            float info[8] = {};
            const std::vector<float> post = params_.post();
            const std::array<float, 9> ccm = ccmStrength(ColourLook::mul(i2s, s2i), params_.ccmStrength);
            ToneNative::render(useV2 ? ToneNative::V2 : ToneNative::V1, planar.data(), size.x, size.y, useV2 ? nullptr : ccm.data(),
                               plan.gainShort, plan.gainLong, plan.hdr, postview_, params_.saturation, post.data(), int(post.size()),
                               shading ? shading->data() : nullptr, shading ? pipeline.mParameters.mapSize->x : 0,
                               shading ? pipeline.mParameters.mapSize->y : 0, info, out.data());
            const auto toneEnd = std::chrono::steady_clock::now();
            std::vector<int16_t>().swap(planar);
            // 4. the picture into the main texture
            const auto uploadStart = std::chrono::steady_clock::now();
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            rgb = makeTexture(size, GLFormat(DataType::SIMPLE_8, 3), out.data(), GL_NEAREST, GL_CLAMP_TO_EDGE);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
            std::vector<uint8_t>().swap(out);
            WorkingTexture = pipeline.getMain();
            if (WorkingTexture == input) WorkingTexture = pipeline.getMain();
            prog.useAssetProgram("oldstyle/rgb8", false);
            prog.setTexture("InputBuffer", rgb);
            prog.drawBlocks(WorkingTexture);
            prog.closed = true;
            char dehaze[160];
            if (params_.dehaze)
                std::snprintf(dehaze, sizeof dehaze, " dehaze shadow=%.0f white=%.0f cut=%.0f grid=%s", double(info[1]), double(info[2]),
                              double(info[3]), shading ? "lens shading" : "unit");
            else
                std::snprintf(dehaze, sizeof dehaze, " dehaze off");
            const auto now = std::chrono::steady_clock::now();
            logI("SCAM_PIPELINE", std::string("Oldstyle ") + (useV2 ? "V2 (tone-port 3.1)" : "V1 (tone-port 2.7)") + " " + plan.describe()
                 + (useV2 && postview_ ? " postview" : "") + " grid=" + std::to_string(size.x) + "x" + std::to_string(size.y) + " samples="
                 + std::to_string(sw) + "x" + std::to_string(sh) + " shorts=" + (shorts ? "true" : "false") + " frameTET="
                 + std::to_string(long(std::floor(frameTet + 0.5))) + " samples ms=" + ms(started, planStart) + " (read "
                 + ms(samplesStart, planStart) + ") plan ms=" + ms(planStart, readStart) + " readback ms=" + ms(readStart, toneStart)
                 + (short16 ? " (16-bit)" : asked16 ? " (16-bit refused: 32-bit)" : " (32-bit)") + " module ms="
                 + std::to_string(Settings::javaRound(info[4])) + " (gain " + std::to_string(Settings::javaRound(info[7])) + ") stages ms="
                 + std::to_string(Settings::javaRound(info[5])) + " (" + ms(toneStart, toneEnd) + ", " + std::to_string(threads)
                 + " threads) upload ms=" + ms(uploadStart, now) + dehaze
                 + (!useV2 && plan.hdr ? (info[0] >= 0 ? " lowfreq x" + std::to_string(Settings::javaRound(info[0])) + "/32" : " lowfreq off") : "")
                 + " ms=" + ms(started, now));
        } catch (...) {
            cleanup();
            throw;
        }
        cleanup();
    }

private:
    bool v2_;
    OldstyleAe::Params params_;
    bool postview_;
};

/// OldstyleStyleNode.java: the style on the finished photo, after the tone (oldstyle/adjust)
class OldstyleStyleNode : public Node {
public:
    explicit OldstyleStyleNode(OldstyleStyle style) : Node("", "OldstyleStyle"), style_(style) {}
    void Compile() override {}

    void Run() override {
        GLProg& prog = *glProg;
        const Texture in = previousNode->WorkingTexture;
        const Point size = in->mSize;
        const auto u = style_.uniformsAfterTone();
        prog.useAssetProgram("oldstyle/adjust", false);
        prog.setTexture("InputBuffer", in);
        for (size_t i = 0; i < 4; i++) prog.setVar("uOs" + std::to_string(i), u[i * 4], u[i * 4 + 1], u[i * 4 + 2], u[i * 4 + 3]);
        // the main textures alternate: the tone wrote one, this pass writes the other
        WorkingTexture = basePipeline->getMain();
        if (WorkingTexture == in) WorkingTexture = basePipeline->getMain();
        prog.drawBlocks(WorkingTexture);
        prog.closed = true;
        logI("SCAM_PIPELINE", "Oldstyle style " + style_.toString() + " grid=" + std::to_string(size.x) + "x" + std::to_string(size.y));
    }

private:
    OldstyleStyle style_;
};

} // namespace scam_post
