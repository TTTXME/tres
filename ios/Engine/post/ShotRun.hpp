// One shot as the Android app processes it, from the NCH burst and the first frame's DNG to the photo: the transport decisions
// (ScamHybridBurst: header flags, job markers, the merge tuning), the SHDR merge, the worker output read as ScamNeuralClient
// reads it, the shot's Parameters and PostPipeline.Run. Shared by the app (Bridge/ScamShot.mm, ScamGL on the iPhone's GPU) and
// the host check (tools/shot_run_check.cpp, Mesa).
#pragma once

#include "../ScamMergeCore.hpp"
#include "DngShot.hpp"
#include "HybridOutput.hpp"
#include "HybridTuning.hpp"
#include "PostPipelineRun.hpp"
#include "WorkerOutput.hpp"

#include <fstream>
#include <sstream>

namespace scam_post {

struct ShotOutput {
    PostOutput photo;
    Parameters params;
    double mergeMs = 0, postMs = 0;
};

/// settings: the app's (Settings::current()); nchPath: the burst written by the app, its header flags set here; jobDir: an empty
/// directory for the worker's job; shaders: the app's shader assets.
/// makeContext: makes the post pipeline's GL context current, after the merge (its GPU engines end their display: eglTerminate).
/// gpuMaxSide: GLLimits.maxSide of the GPU (the smallest of the texture, renderbuffer and viewport limits; 16384 on the
/// iPhone's Metal and on Mesa llvmpipe).
inline ShotOutput runShot(const std::string& nchPath, const std::vector<uint8_t>& dngBytes, const std::string& jobDir, const std::string& shaders,
                          const std::function<void(const std::string&)>& log, const std::function<void()>& makeContext,
                          int gpuMaxSide = 16384) {
    Settings& s = Settings::current();
    const DngShot dng = readDng(dngBytes.data(), dngBytes.size());
    // the burst's header as ScamHybridBurst.header writes it: clip flags (4), strict Sabre 2x (8), no effective map above 100 MP of
    // the merged RGB (16); word 13 the output grid (1 sensor, 2 a Sabre scale), words 15 / 16 the 1.5x size (sabreWords)
    int32_t w = 0, h = 0;
    HybridOutput::Plan plan;
    {
        std::fstream f(nchPath, std::ios::in | std::ios::out | std::ios::binary);
        if (!f) throw std::runtime_error("no burst " + nchPath);
        f.seekg(8);
        f.read(reinterpret_cast<char*>(&w), 4);
        f.read(reinterpret_cast<char*>(&h), 4);
        int32_t block = 1;
        f.seekg(56);
        f.read(reinterpret_cast<char*>(&block), 4);
        plan = HybridOutput::plan(s, w, h, gpuMaxSide, std::max(1, int(block)));
        const bool clipFlags = s.hybridSwitch("clip_flags", true) && s.hybridValue("highlight_recovery", 100.f) > 0.f;
        const uint32_t flags = (clipFlags ? 4u : 0u) | (plan.strictTwoX ? 8u : 0u) | (plan.effectiveMap() ? 0u : 16u);
        const std::array<int, 2> sabre = plan.words();
        const uint32_t grid = uint32_t(plan.grid()), sabreW = uint32_t(sabre[0]), sabreH = uint32_t(sabre[1]);
        f.seekp(44);
        f.write(reinterpret_cast<const char*>(&flags), 4);
        f.seekp(52);
        f.write(reinterpret_cast<const char*>(&grid), 4);
        f.seekp(60);
        f.write(reinterpret_cast<const char*>(&sabreW), 4);
        f.write(reinterpret_cast<const char*>(&sabreH), 4);
        if (!f) throw std::runtime_error("cannot write the burst header " + nchPath);
    }
    if (plan.twoX)
        log("hybrid output: Sabre " + plan.outputMode + " grid " + std::to_string(plan.outWidth) + "x" + std::to_string(plan.outHeight)
            + ", final " + std::to_string(plan.finalWidth) + "x" + std::to_string(plan.finalHeight));
    // the colour first: the burst's tuning carries the neutral (tuningOverride)
    Parameters colour;
    ShotColour::reCalcColor(colour, dng.colour, [](const std::string&) {});
    const float sceneEv = HybridTuning::sceneEv100(dng.exposure, std::max(1, dng.iso), dng.aperture);
    const std::string tuning = HybridTuning::text(s) + HybridTuning::burstOverride(sceneEv, *colour.whitePoint, plan.strictTwoX);
    const bool rgbHalf = plan.rgbHalf;
    const bool highlightMask = s.scamDevSwitch("why_hl_dezipper", true);
    { std::ofstream(jobDir + "/hybrid_tuning.txt") << tuning; }
    if (rgbHalf) std::ofstream(jobDir + "/rgb-half");
    if (highlightMask) std::ofstream(jobDir + "/hl-mask");

    std::string report;
    auto worker = [&](const std::string& line) { report += line + "\n"; log("merge: " + line); };
    const scam_ios::MergeResult merged = scam_ios::mergeBurst(jobDir, nchPath, worker);
    ShotOutput out;
    out.mergeMs = merged.ms;
    const std::vector<uint8_t> bytes = scam_ios::workerOutput(merged);
    const bool effectiveMap = !merged.effMap.empty(), clipFlags = !merged.clipFlags.empty();

    const auto t0 = std::chrono::steady_clock::now();
    makeContext();
    GLProg prog(shaders);
    PostPipeline p;
    p.glProg = &prog;
    if (merged.width != plan.outWidth || merged.height != plan.outHeight)
        throw std::runtime_error("the merge returned " + std::to_string(merged.width) + "x" + std::to_string(merged.height) + ", the burst asked for "
                                 + std::to_string(plan.outWidth) + "x" + std::to_string(plan.outHeight));
    p.mParameters = shotParameters(dng, merged.width, merged.height, log);
    // ScamProcessor: the pipeline runs at the merged size (outputScale: its scale against the sensor grid); «Уменьшить до» below
    // it is the final size (HybridFinalResize)
    p.mParameters.outputScale = float(plan.outWidth) / float(w);
    p.mParameters.hybridFinalSize = plan.hybridFinalSize();
    p.mParameters.portraitEnabled = s.getBoolean("pref_portrait_enabled", false);
    p.mParameters.portraitSkinWarmth = p.mParameters.portraitEnabled && s.getBoolean("pref_portrait_skin_warmth", true);
    int normals = 0;
    {
        std::ifstream f(nchPath, std::ios::binary);
        f.seekg(20);
        f.read(reinterpret_cast<char*>(&normals), 4);
    }
    p.merge.lastNormals = std::max(1, normals);
    p.scamRgb = readWorkerOutput(p.merge, bytes, report, merged.width, merged.height, w, h, merged.rgbHalf, effectiveMap, clipFlags,
                                 highlightMask);
    out.photo = runPostPipeline(p);
    out.params = p.mParameters;
    out.postMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    return out;
}

} // namespace scam_post
