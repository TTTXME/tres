// One shot as the Android app processes it, from the NCH burst and the first frame's DNG to the photo: the transport decisions
// (ScamHybridBurst: header flags, job markers, the merge tuning), the SHDR merge, the worker output read as ScamNeuralClient
// reads it, the shot's Parameters and PostPipeline.Run. Shared by the app (Bridge/ScamShot.mm, ScamGL on the iPhone's GPU) and
// the host check (tools/shot_run_check.cpp, Mesa).
#pragma once

#include "../ScamMergeCore.hpp"
#include "DngShot.hpp"
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
inline ShotOutput runShot(const std::string& nchPath, const std::vector<uint8_t>& dngBytes, const std::string& jobDir, const std::string& shaders,
                          const std::function<void(const std::string&)>& log, const std::function<void()>& makeContext) {
    Settings& s = Settings::current();
    const DngShot dng = readDng(dngBytes.data(), dngBytes.size());
    // the burst's header as ScamHybridBurst.header writes it: clip flags (4), no effective map above 100 MP (16), sensor grid
    int32_t w = 0, h = 0;
    {
        std::fstream f(nchPath, std::ios::in | std::ios::out | std::ios::binary);
        if (!f) throw std::runtime_error("no burst " + nchPath);
        f.seekg(8);
        f.read(reinterpret_cast<char*>(&w), 4);
        f.read(reinterpret_cast<char*>(&h), 4);
        const bool clipFlags = s.hybridSwitch("clip_flags", true) && s.hybridValue("highlight_recovery", 100.f) > 0.f;
        const bool effectiveMap = !(int64_t(w) * h > 100000000LL);
        const uint32_t flags = (clipFlags ? 4u : 0u) | (effectiveMap ? 0u : 16u);
        f.seekp(44);
        f.write(reinterpret_cast<const char*>(&flags), 4);
    }
    // the colour first: the burst's tuning carries the neutral (tuningOverride)
    Parameters colour;
    ShotColour::reCalcColor(colour, dng.colour, [](const std::string&) {});
    const float sceneEv = HybridTuning::sceneEv100(dng.exposure, std::max(1, dng.iso), dng.aperture);
    const std::string tuning = HybridTuning::text(s) + HybridTuning::burstOverride(sceneEv, *colour.whitePoint, false);
    const bool rgbHalf = s.scamDevSwitch("hybrid_rgb_half", true);
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
    p.mParameters = shotParameters(dng, merged.width, merged.height, log);
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
