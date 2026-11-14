// One shot as the Android app processes it, from the NCH burst and the first frame's DNG to the photo: the transport decisions
// (ScamHybridBurst: the frame table of the captured N / bracketed / ultrashort frames, header flags, job markers, the merge tuning),
// the crop (SabreCrop's window before the merge, «16:9» frames, the photo's crop after the pipeline), the SHDR merge, the worker
// output read as ScamNeuralClient reads it, the shot's Parameters and PostPipeline.develop. Shared by the app (Bridge/ScamShot.mm, ScamGL
// on the iPhone's GPU) and the host check (tools/shot_run_check.cpp, Mesa).
#pragma once

#include "../ScamMergeCore.hpp"
#include "DevelopRecord.hpp"
#include "DngShot.hpp"
#include "HybridOutput.hpp"
#include "HybridTuning.hpp"
#include "PostPipelineRun.hpp"
#include "ShotTransport.hpp"
#include "WorkerOutput.hpp"
#include "portrait/PortraitSkin.hpp"

#include <fstream>
#include <regex>
#include <sstream>
#if defined(__APPLE__)
#include <TargetConditionals.h>
#if TARGET_OS_IPHONE
#include <os/proc.h> // os_proc_available_memory: iOS only
#define SCAM_SHOT_PROC_MEMORY 1
#endif
#endif

namespace scam_post {

struct ShotOutput {
    PostOutput photo;
    /// Ultra HDR on (pref_ultrahdr_key): the gain map of the photo (GainMapComputer.Result: RGBA8 rows as the photo's, R = G = B;
    /// IsoGainMap.luma for one byte a pixel), GainMapMin / GainMapMax (log2) and HDRCapacityMax; none when off or when the pass failed
    std::optional<GainMapComputer::Result> gainMap;
    Parameters params;
    double mergeMs = 0, postMs = 0;
    /// the pipeline's photo size before the crop after it (ScamProcessor's tail), for the host check against the Android lab
    int pipelineWidth = 0, pipelineHeight = 0;
    /// P665: the gallery developer's record for the shot's DNG (DevelopCapture.take), none when it could not be made
    std::optional<DevelopRecord> develop;
};

/// P665 DevelopCapture.take for the iPhone's DNG. Android appends the record to its merged DNG (the merge's RGB as a mosaic over
/// its headroom); the iPhone saves the camera's DNG of the series' first frame, so the record describes that frame's RGB: the
/// shot's Parameters on the DNG's whole grid (its own lens shading map, the photo's final size and faces on that grid), the shot's
/// settings and dev lines, the merge's numbers of one frame (one N frame, no Bento, no effective-frame or clip map: the merge's
/// maps are not this frame's), the base highlight mask only when the base frame is the DNG's frame on its grid, headroom 1, the
/// digital zoom's crop after the pipeline.
inline std::optional<DevelopRecord> developRecord(const Parameters& par, const DngShot& dng, const std::optional<std::vector<float>>& fullGainMap,
                                                  const std::vector<float>& fullFaces, int frameW, int frameH, bool window, bool dngFrameBase,
                                                  const MergeInfo& merge, float zoom, long long build,
                                                  const std::function<void(const std::string&)>& log) {
    try {
        const auto t0 = std::chrono::steady_clock::now();
        if (dng.width <= 0 || dng.height <= 0) throw std::runtime_error("the DNG has no size");
        Parameters p = par;
        p.rawSize = Point{dng.width, dng.height};
        p.outputScale = 1.f;
        p.gainMap = fullGainMap;
        if (p.portraitEnabled) p.portraitFaces = fullFaces;
        if (window || !par.hybridFinalSize || frameW <= 0) p.hybridFinalSize.reset();
        else {
            const double k = double(par.hybridFinalSize->x) / (double(frameW) * std::max(1.f, par.outputScale));
            p.hybridFinalSize = Point{int(std::floor(dng.width * k + 0.5)) & ~1, int(std::floor(dng.height * k + 0.5)) & ~1};
        }
        DevelopRecord::Worker w;
        DevelopRecord r = DevelopRecord::make(p.toJson(), Settings::current(), w, 1.f, dng.width, dng.height, zoom, false, build,
                                              "\"frame\":\"reference\"");
        if (dngFrameBase && !window && merge.lastHlMask && merge.lastHlMask->width * 2 == frameW && merge.lastHlMask->height * 2 == frameH
            && frameW == dng.width && frameH == dng.height && (long long)frameW * frameH <= DevelopRecord::MAP_MAX_PIXELS) {
            const MergeInfo::HlMask& m = *merge.lastHlMask;
            r.hlMask.resize(m.halves.size() * 2);
            for (size_t i = 0; i < m.halves.size(); i++) {
                r.hlMask[2 * i] = uint8_t(m.halves[i] & 0xFF);
                r.hlMask[2 * i + 1] = uint8_t(m.halves[i] >> 8);
            }
            r.hlW = m.width;
            r.hlH = m.height;
        }
        log("develop record taken in " + std::to_string(int(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count()))
            + " ms (the DNG's frame " + std::to_string(dng.width) + "x" + std::to_string(dng.height) + ", highlight mask "
            + (r.hlMask.empty() ? "false" : "true") + ")");
        return r;
    } catch (const std::exception& e) {
        log(std::string("develop record not taken: ") + e.what());
        return std::nullopt;
    }
}

/// What the shot does after its pipeline (ScamProcessor): the window's remaining crop and its photo size, or the digital zoom's
/// centre crop brought back to the photo size (P226 / P576)
struct ShotTail {
    float zoom = 1.f;                         // the press's residual crop
    std::optional<SabreCrop::Plan> window;    // the frames' window
    std::optional<Point> hybridFinal;         // ScamHybridBurst.lastFinalSize
    bool cropUpscale = true;                  // «Увеличивать кадр с кропом»
    std::string downsampler = "lanczos";
};

/// ScamProcessor's steps between the pipeline and the encoding for a crop shot; the gain map follows the photo.
inline void applyTail(PostOutput& photo, std::optional<GainMapComputer::Result>& gm, const ShotTail& t,
                      const std::function<void(const std::string&)>& log) {
    if (!(t.zoom > 1.005f) || photo.width <= 0) return;
    const std::optional<Point> cropTarget = t.window ? SabreCrop::target(photo.width, photo.height, *t.window)
                                            : t.cropUpscale ? std::optional<Point>(photoSize(photo.width, photo.height, t.hybridFinal))
                                                            : std::nullopt;
    auto mapCrop = [&](const CropRect& r, int baseW, int baseH) {
        if (!gm) return;
        if (gm->width == baseW && gm->height == baseH) cropRgba(gm->rgba, gm->width, gm->height, r);
        else gm.reset(); // a map of another size than the photo: none of this port's (it is pixel-aligned), SDR as Android's failure path
    };
    if (t.window) {
        const int baseW = photo.width, baseH = photo.height;
        const std::optional<CropRect> rest = SabreCrop::cropRect(baseW, baseH, *t.window);
        if (rest) {
            cropRgba(photo.rgba, photo.width, photo.height, *rest);
            mapCrop(*rest, baseW, baseH);
        }
        log("Sabre crop (P576): merged window " + std::to_string(baseW) + "x" + std::to_string(baseH)
            + (rest ? " cropped to " + std::to_string(rest->width()) + "x" + std::to_string(rest->height()) + " at " + std::to_string(rest->left)
                          + "," + std::to_string(rest->top)
                    : std::string(" kept whole"))
            + (cropTarget ? ", photo " + std::to_string(cropTarget->x) + "x" + std::to_string(cropTarget->y) : std::string(" (no upscale)")));
    } else if (const std::optional<CropRect> r = zoomCropRect(photo.width, photo.height, t.zoom)) {
        const int baseW = photo.width, baseH = photo.height;
        cropRgba(photo.rgba, photo.width, photo.height, *r);
        mapCrop(*r, baseW, baseH);
        log("digital zoom crop x" + javaFloatString(t.zoom) + ": " + std::to_string(photo.width) + "x" + std::to_string(photo.height));
    }
    if (cropTarget && (cropTarget->x != photo.width || cropTarget->y != photo.height)) {
        const int fromW = photo.width, fromH = photo.height;
        // P576: a window merged above the photo's size goes down with the output's downsampler (as an uncropped 2x shot)
        if (t.window && cropTarget->x <= photo.width && cropTarget->y <= photo.height)
            resizeTo(photo.rgba, photo.width, photo.height, cropTarget->x, cropTarget->y, t.downsampler);
        else
            scaleTo(photo.rgba, photo.width, photo.height, cropTarget->x, cropTarget->y);
        if (gm) resizeMap(gm->rgba, gm->width, gm->height, photo.width, photo.height);
        log("crop " + javaFloatString(t.zoom) + "x brought to the photo size " + std::to_string(photo.width) + "x" + std::to_string(photo.height)
            + " (P226, from " + std::to_string(fromW) + "x" + std::to_string(fromH) + ")");
    }
}

/// «Портрет»: the faces of the shot as Parameters.fillFromFrame takes them from PortraitFaces.fromResult - whether a face
/// was found, and its boxes (normalised left, top, right, bottom in the unrotated RAW buffer, the merged RGB's sensor orientation;
/// empty when a face was found but its place in the RAW is unknown). The iOS app finds them with Vision (BurstProcessor).
/// ScamHybridBurst.memoryAvailable: the memory this process may still take (bytes), -1 when unknown (the host)
inline long long availableMemory() {
#if defined(SCAM_SHOT_PROC_MEMORY)
    return (long long)os_proc_available_memory();
#else
    return -1;
#endif
}

struct ShotFaces {
    bool detected = false;
    std::vector<float> boxes;
    bool front = false; // the front camera (Parameters.mirror unless «disable mirror»)
};

/// settings: the app's (Settings::current()); nchPath: the burst written by the app, its header flags set here (its sidecar
/// nchPath + ".json", ShotFacts, when present: the frames' capture roles and exposures, the zoom, the 16:9 cut); jobDir: an empty
/// directory for the worker's job (a cut or re-ordered transport is written there); shaders: the app's shader assets.
/// makeContext: makes the post pipeline's GL context current, after the merge (its GPU engines end their display: eglTerminate).
/// gpuMaxSide: GLLimits.maxSide of the GPU (the smallest of the texture, renderbuffer and viewport limits; 16384 on the
/// iPhone's Metal and on Mesa llvmpipe). faces: the shot's faces (ShotFaces), read only with «Портрет» on. tail: false leaves the
/// photo as the pipeline made it (the host check against the Android lab, which renders the pipeline alone). labParameters: the
/// host check's hook over the Parameters before the pipeline (the lab shot's own values the DNG does not carry).
inline ShotOutput runShot(const std::string& nchPath, const std::vector<uint8_t>& dngBytes, const std::string& jobDir, const std::string& shaders,
                          const std::function<void(const std::string&)>& log, const std::function<void()>& makeContext,
                          int gpuMaxSide = 16384, const ShotFaces& faces = {}, bool tail = true,
                          const std::function<void(Parameters&)>& labParameters = {}, long long build = 0) {
    Settings& s = Settings::current();
    const DngShot dng = readDng(dngBytes.data(), dngBytes.size());
    const ShotFacts facts = readShotFacts(nchPath + ".json");
    // the frames as captured (after the «16:9» cut of the capture)
    int frameW = 0, frameH = 0, block = 1, captured = 0;
    {
        NchFile nch(nchPath);
        frameW = nch.width();
        frameH = nch.height();
        block = int(nch.h[14]);
        captured = nch.frames();
    }
    // P576 (SabreCrop.cut, before the merge and its frame rules): the zoom's window of the whole frames
    const float zoom = facts.zoom > 1.f && std::isfinite(facts.zoom) ? facts.zoom : 1.f;
    const bool cropUpscale = s.getBoolean("pref_crop_upscale", true);
    const bool cropShot = zoom > 1.005f; // PreferenceKeys.cropShot: the digital zoom's residual (no lens-crop modules on the iPhone)
    const bool keepCrop = !cropUpscale && cropShot;
    std::optional<SabreCrop::Plan> window;
    if (cropShot) {
        if (!s.scamDevSwitch("sabre_crop", true)) log("Sabre crop (P576): zoom x" + javaFloatString(zoom) + ", the whole frame merged and cropped as before (scam_dev sabre_crop 0)");
        else {
            const std::string mode = s.hybridOutputMode();
            const Point target = SabreCrop::uncroppedPhoto(frameW, frameH, mode, HybridOutput::sabre2xFits(frameW, frameH, gpuMaxSide),
                                                           HybridOutput::sabreSize(frameW, frameH, mode), HybridOutput::hybridFinalSize(s, frameW, frameH));
            SabreCrop::Limits limits;
            limits.gpuMaxSide = gpuMaxSide;
            limits.fastRoute = HybridTuning::fastProcessing(s);
            if ((long long)frameW * frameH > HybridSelect::CLASSIC_INPUT_PIXELS) limits.available = availableMemory();
            const SabreCrop::Decision d = SabreCrop::plan(frameW, frameH, zoom, block, cropUpscale, target, limits);
            if (d.plan) {
                window = d.plan;
                log("Sabre crop (P576): " + window->str() + (window->sabre() ? ", Sabre super-resolution on the window" : ", sensor grid")
                    + "; block " + std::to_string(block) + ", " + (limits.fastRoute ? "fast" : "SCAM") + " route");
            } else
                log("Sabre crop (P576): zoom x" + javaFloatString(zoom) + " on " + std::to_string(frameW) + "x" + std::to_string(frameH) + ": " + d.why
                    + " - the whole frame merged and cropped as before");
        }
    }
    const int w = window ? window->area.width : frameW, h = window ? window->area.height : frameH;
    if (keepCrop) log("hybrid output: a crop shot without upscale (P226): sensor grid, no output resize");
    HybridOutput::Plan plan = HybridOutput::plan(s, w, h, gpuMaxSide, std::max(1, block), keepCrop, window ? &*window : nullptr);
    // the ScamHybridBurst frame rules over the captured frames (their roles, exposures, times and noise from the sidecar)
    std::optional<HybridSelect::Result> picked;
    if (!facts.frames.empty()) {
        if (int(facts.frames.size()) != captured)
            throw std::runtime_error("the burst's sidecar describes " + std::to_string(facts.frames.size()) + " frames, the burst holds " + std::to_string(captured));
        NchFile nch(nchPath);
        const long long available = availableMemory();
        picked = HybridSelect::select(facts.frames, w, h, s, [&](int i) { return float(HybridSelect::score(nch.plane(i), frameW, frameH)); },
                                      dng.aperture, plan.twoX ? 2 : 1, available);
        for (const std::string& line : picked->log) log(line);
    }
    const std::string mergePath = writeTransport(nchPath, picked ? &picked->frames : nullptr, window ? std::optional<CropArea>(window->area) : std::nullopt,
                                                 jobDir + "/transport.nch");
    if (mergePath != nchPath) log("burst transport: " + std::to_string(w) + "x" + std::to_string(h) + " written for the merge");
    // the burst's header as ScamHybridBurst.header writes it: clip flags (4), strict Sabre 2x (8), no effective map above 100 MP of
    // the merged RGB (16); word 13 the output grid (1 sensor, 2 a Sabre scale), words 15 / 16 the 1.5x size (sabreWords)
    {
        std::fstream f(mergePath, std::ios::in | std::ios::out | std::ios::binary);
        if (!f) throw std::runtime_error("no burst " + mergePath);
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
        if (!f) throw std::runtime_error("cannot write the burst header " + mergePath);
    }
    if (plan.twoX)
        log("hybrid output: Sabre " + plan.outputMode + " grid " + std::to_string(plan.outWidth) + "x" + std::to_string(plan.outHeight)
            + ", final " + std::to_string(plan.finalWidth) + "x" + std::to_string(plan.finalHeight));
    // the colour first: the burst's tuning carries the neutral (tuningOverride)
    Parameters colour;
    ShotColour::reCalcColor(colour, dng.colour, [](const std::string&) {});
    // P366: the base frame's EV100 (the merge's base; without the sidecar the DNG's frame)
    const float sceneEv = picked ? picked->sceneEv : HybridTuning::sceneEv100(dng.exposure, std::max(1, dng.iso), dng.aperture);
    const std::string tuning = HybridTuning::text(s) + HybridTuning::burstOverride(sceneEv, *colour.whitePoint, plan.strictTwoX);
    const bool rgbHalf = plan.rgbHalf;
    const bool highlightMask = s.scamDevSwitch("why_hl_dezipper", true);
    { std::ofstream(jobDir + "/hybrid_tuning.txt") << tuning; }
    if (rgbHalf) std::ofstream(jobDir + "/rgb-half");
    if (highlightMask) std::ofstream(jobDir + "/hl-mask");

    std::string report;
    auto worker = [&](const std::string& line) { report += line + "\n"; log("merge: " + line); };
    const scam_ios::MergeResult merged = scam_ios::mergeBurst(jobDir, mergePath, worker);
    if (mergePath != nchPath) std::remove(mergePath.c_str());
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
    Parameters& par = p.mParameters;
    const std::optional<std::vector<float>> fullGainMap = par.gainMap;
    // P118 «16:9»: the frames are the capture's centred 16:9 rows (BayerBurst), the DNG's map covers the whole frame: its rows of
    // the crop only (Parameters.fillFromFrame; the full frame's rows as the capture recorded them, else from the DNG's size
    // as Android takes them from the active array)
    const int fullRows = facts.fullHeight > 0 ? facts.fullHeight
                         : dng.width > 0 && dng.height > 0 ? int(HybridOutput::roundD(double(frameW) * dng.height / dng.width)) : 0;
    if (par.hasGainMap && par.mapSize && par.mapSize->y > 1 && par.gainMap && s.getBoolean("pref_wide169_key", false) && frameW > frameH
        && fullRows > 0) {
        const int full = fullRows;
        if (Wide169::rows(frameW, full) == frameH) {
            par.gainMap = Wide169::cropGainMap(*par.gainMap, par.mapSize->x, par.mapSize->y, full, Wide169::firstRow(frameW, full), frameH);
            log("16:9 (P118): " + std::to_string(frameW) + "x" + std::to_string(frameH) + " of " + std::to_string(frameW) + "x" + std::to_string(full)
                + ", lens shading map of its rows");
        } else
            log("16:9 frame " + std::to_string(frameW) + "x" + std::to_string(frameH) + " is not the crop of " + std::to_string(frameW) + "x"
                + std::to_string(full) + ": lens shading map kept");
    }
    // P576 Parameters.applySabreCrop: the lens shading map of the window (the face boxes below)
    if (window && par.gainMap && par.mapSize && par.mapSize->x >= 2 && par.mapSize->y >= 2)
        par.gainMap = RawCrop::cropGainMap(*par.gainMap, par.mapSize->x, par.mapSize->y, frameW, frameH, window->area);
    // ScamProcessor: the pipeline runs at the merged size (outputScale: its scale against the sensor grid); «Уменьшить до» below
    // it is the final size (HybridFinalResize)
    par.outputScale = float(plan.outWidth) / float(w);
    par.hybridFinalSize = plan.hybridFinalSize();
    // P369: the film's frame (its grain and halation) is the photo's share of the field: the window's remaining crop, else the zoom's
    par.filmFrameScale = filmFrameScale(window ? window->remaining() : zoom);
    par.portraitEnabled = s.getBoolean("pref_portrait_enabled", false);
    par.portraitSkinWarmth = par.portraitEnabled && s.getBoolean("pref_portrait_skin_warmth", true);
    // Parameters.fillFromFrame: the faces only with «Портрет» on (kept to the frame and to PortraitFaces.MAX_FACES as
    // PortraitFaces.append keeps them; P576: in the window)
    // Parameters.fillFromCamera: a front camera mirrors, pref_tunable_parameters_disablemirror turns it off
    par.mirror = faces.front && !s.getBoolean("pref_tunable_parameters_disablemirror", false);
    par.portraitFaceDetected = false;
    par.portraitFaces = std::vector<float>();
    if (par.portraitEnabled) {
        par.portraitFaceDetected = faces.detected;
        par.portraitFaces = PortraitFaces::crop(faces.boxes, 0.f, 0.f, 1.f, 1.f);
        if (window && !par.portraitFaces->empty())
            par.portraitFaces = PortraitFaces::crop(*par.portraitFaces, window->area.x / float(frameW), window->area.y / float(frameH),
                                                    window->area.width / float(frameW), window->area.height / float(frameH));
        if (faces.detected) {
            std::string boxes;
            for (float v : *par.portraitFaces) boxes += (boxes.empty() ? "" : ", ") + javaFloatString(v);
            log("portrait: face found, boxes [" + boxes + "]");
        }
    }
    // P212h: the N frames of the burst (the base and the frames merged as N: ROLE_NORMAL in the frame table)
    int normals = 0;
    if (picked) {
        for (const HybridSelect::Entry& e : picked->frames) if (e.role == uint32_t(HybridSelect::ROLE_NORMAL)) normals++;
    } else {
        NchFile nch(nchPath);
        for (int i = 0; i < nch.frames(); i++) if (nch.word(i, 0) == uint32_t(HybridSelect::ROLE_NORMAL)) normals++;
    }
    p.merge.lastNormals = std::max(1, normals);
    // P214e ScamHybridBurst.mergedBlock: the colour block of the merged image (the worker's mosaic merge head, else the block sent)
    {
        static const std::regex binned("HYBRID MOSAIC BINNED: block [24] without remosaic");
        static const std::regex head("HYBRID MOSAIC NATIVE: block ([24])[ ,]|HYBRID MOSAIC: block ([24]), \\d+ plain-Bayer sub-frames");
        int merged = block == 2 || block == 4 ? block : 1;
        if (std::regex_search(report, binned)) merged = 1;
        else {
            int found = 0;
            for (auto it = std::sregex_iterator(report.begin(), report.end(), head); it != std::sregex_iterator(); ++it)
                found = std::stoi((*it)[1].matched ? (*it)[1].str() : (*it)[2].str());
            if (found > 0) merged = found;
        }
        par.mosaicBlock = merged;
    }
    if (labParameters) labParameters(par);
    p.scamRgb = readWorkerOutput(p.merge, bytes, report, merged.width, merged.height, w, h, merged.rgbHalf, effectiveMap, clipFlags,
                                 highlightMask);
    // P665: the record right before the pipeline (DevelopCapture.take's moment), from copies
    out.develop = developRecord(par, dng, fullGainMap, PortraitFaces::crop(faces.boxes, 0.f, 0.f, 1.f, 1.f), frameW, frameH,
                                window.has_value(), picked && picked->base == 0, p.merge, zoom, build, log);
    out.photo = runPostPipeline(p);
    out.gainMap = std::move(out.photo.gainMap);
    out.photo.gainMap.reset();
    out.pipelineWidth = out.photo.width;
    out.pipelineHeight = out.photo.height;
    if (tail) {
        ShotTail t;
        t.zoom = zoom;
        t.window = window;
        t.hybridFinal = Point{plan.finalWidth, plan.finalHeight};
        t.cropUpscale = cropUpscale;
        t.downsampler = s.hybridDownsampler();
        applyTail(out.photo, out.gainMap, t, log);
    }
    out.params = p.mParameters;
    out.postMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    return out;
}

} // namespace scam_post
