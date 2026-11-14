// P665: the gallery developer's run of the camera's post pipeline on a DNG of ours (Android DevelopRun.java): the same
// PostPipeline the shot took, with the shot's Parameters (sized to the DNG's RGB), the shot's settings with the developer's changes
// over them, the shot's scam_dev lines and the merge's numbers and maps (the pipeline's MergeInfo). Under processLock(), so a shot's
// processing and a develop never overlap (they share the GL context's thread rules and the pipeline's statics).
#pragma once

#include "DevelopRecord.hpp"
#include "PostPipelineRun.hpp"
#include "SabreCrop.hpp"
#include "ShotTransport.hpp"
#include "WorkerOutput.hpp"

#include <chrono>
#include <map>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

namespace scam_post {

/// ScamLab.LOCK: one shot or one develop at a time (Bridge/ScamShot.mm, Bridge/ScamDevelop.mm)
inline std::timed_mutex& processLock() {
    static std::timed_mutex m;
    return m;
}

namespace Develop {

/// how long a develop waits for a shot being processed (s)
constexpr int LOCK_WAIT_S = 120;

/// a change of the developer: the key's new value (Boolean or String, as the camera stores it), none: the key unset (its default)
using Changes = std::map<std::string, std::optional<Pref>>;

inline long long javaRound(double v) { return (long long)std::floor(v + 0.5); }

/// DevelopRun.parameters: the shot's Parameters for an RGB of w x h (the DNG's grid, or a reduced copy of it for the preview)
inline Parameters parameters(const DevelopRecord& r, int w, int h) {
    Parameters p = Parameters::fromJson(r.meta["params"]);
    const std::optional<Point> full = p.rawSize;
    const Json& dng = r.meta["dng"];
    const int dw = dng["w"].isNull() ? 0 : int(DevelopRecord::numberOf(dng["w"], 0.f));
    const int dh = dng["h"].isNull() ? 0 : int(DevelopRecord::numberOf(dng["h"], 0.f));
    const std::optional<Point> base = dw > 0 && dh > 0 ? std::optional<Point>(Point{dw, dh}) : full;
    if (p.hybridFinalSize && base && base->x > 0 && base->y > 0) {
        const double fx = double(p.hybridFinalSize->x) / base->x, fy = double(p.hybridFinalSize->y) / base->y;
        const int fw = int(std::max<long long>(2, javaRound(w * fx))) & ~1, fh = int(std::max<long long>(2, javaRound(h * fy))) & ~1;
        // the final size only ever shrinks the grid (HybridFinalResize); at or above it, none
        p.hybridFinalSize = fx < 1 && fy < 1 && fw < w && fh < h ? std::optional<Point>(Point{fw, fh}) : std::nullopt;
    }
    if (full && full->x > 0 && full->y > 0 && (full->x != w || full->y != h)) {
        const double sx = double(w) / full->x, sy = double(h) / full->y;
        if (p.sensorPix)
            p.sensorPix = Rect{int(javaRound(p.sensorPix->left * sx)), int(javaRound(p.sensorPix->top * sy)),
                               int(javaRound(p.sensorPix->right * sx)), int(javaRound(p.sensorPix->bottom * sy))};
        p.outputScale = float(p.outputScale * sx);
    }
    p.rawSize = Point{w, h};
    return p;
}

/// DevelopRun.values: the shot's settings (never the diagnostics, the kept bursts, the lab's switch or the merge stamp), its
/// scam_dev lines, then the changes
inline Settings settings(const DevelopRecord& r, const Changes& changes) {
    Settings s = Settings::fromJson(r.meta);
    s.prefs["pref_scam_hybrid_diagnostics"] = Pref::boolean(false);
    s.prefs["pref_keep_raw_burst"] = Pref::boolean(false);
    s.prefs["pref_scam_lab_keep"] = Pref::boolean(false);
    s.prefs["pref_debug_merge_stamp"] = Pref::boolean(false);
    for (const auto& [key, value] : changes) {
        if (value) s.prefs[key] = *value;
        else s.prefs.erase(key);
    }
    return s;
}

/// The developer's changes from their JSON ({key: true / false / "text" / null}); keys and values as EditState.devKey keeps them.
inline Changes changesFromJson(const std::string& json) {
    Changes out;
    if (json.empty()) return out;
    const Json o = Json::parse(json);
    for (const auto& [key, v] : o.o) {
        if (v.kind == Json::Kind::Bool) out[key] = Pref::boolean(v.b);
        else if (v.kind == Json::Kind::String) out[key] = Pref::string(v.s);
        else if (v.kind == Json::Kind::Number) out[key] = Pref::string(v.s.empty() ? JsonText::number(v.n) : v.s);
        else if (v.isNull()) out[key] = std::nullopt;
    }
    return out;
}

/// DevelopRun.resampled: a one-byte-a-pixel map of mw x mh on a w x h grid (the covered source pixels OR-ed, or averaged)
inline std::vector<uint8_t> resampled(const std::vector<uint8_t>& data, int mw, int mh, int w, int h, bool orBits) {
    std::vector<uint8_t> out(size_t(w) * h);
    for (int y = 0; y < h; y++) {
        const int y0 = int((long long)y * mh / h), y1 = std::max(y0 + 1, int((long long)(y + 1) * mh / h));
        for (int x = 0; x < w; x++) {
            const int x0 = int((long long)x * mw / w), x1 = std::max(x0 + 1, int((long long)(x + 1) * mw / w));
            int acc = 0, n = 0;
            for (int sy = y0; sy < std::min(mh, y1); sy++)
                for (int sx = x0; sx < std::min(mw, x1); sx++) {
                    const int v = data[size_t(sy) * mw + sx];
                    if (orBits) acc |= v;
                    else acc += v;
                    n++;
                }
            out[size_t(y) * w + x] = uint8_t(orBits ? acc : n == 0 ? 0 : (acc + n / 2) / n);
        }
    }
    return out;
}

/// DevelopRun.map: the merge's per-pixel map for a w x h grid, none without one
inline std::optional<std::vector<uint8_t>> map(const std::vector<uint8_t>& data, int mw, int mh, int w, int h, bool orBits) {
    if (data.empty() || mw <= 0 || mh <= 0) return std::nullopt;
    if (mw == w && mh == h) return data;
    return resampled(data, mw, mh, w, h, orBits);
}

/// DevelopRun.hlMask: the highlight mask (halves, half grid) for a w x h RGB: its own size when the grid is the shot's, else nearest
inline std::optional<MergeInfo::HlMask> hlMask(const DevelopRecord& r, int w, int shotW) {
    if (r.hlMask.empty() || r.hlW <= 0 || r.hlH <= 0) return std::nullopt;
    const auto halfAt = [&](size_t i) { return uint16_t(r.hlMask[2 * i] | r.hlMask[2 * i + 1] << 8); };
    MergeInfo::HlMask m;
    if (w == shotW) {
        m.width = r.hlW;
        m.height = r.hlH;
        m.halves.resize(size_t(r.hlW) * r.hlH);
        for (size_t i = 0; i < m.halves.size(); i++) m.halves[i] = halfAt(i);
        return m;
    }
    const double k = double(w) / std::max(1, shotW);
    m.width = std::max(1, int(javaRound(r.hlW * k)));
    m.height = std::max(1, int(javaRound(r.hlH * k)));
    m.halves.resize(size_t(m.width) * m.height);
    for (int y = 0; y < m.height; y++) {
        const int sy = std::min(r.hlH - 1, int((y + .5) * r.hlH / m.height));
        for (int x = 0; x < m.width; x++) {
            const int sx = std::min(r.hlW - 1, int((x + .5) * r.hlW / m.width));
            m.halves[size_t(y) * m.width + x] = halfAt(size_t(sy) * r.hlW + sx);
        }
    }
    return m;
}

/// DevelopRun.crops: the crops ScamProcessor makes after the pipeline - the digital zoom's centre crop, then the ISZ frame's 4:3
inline void crops(const DevelopRecord& r, PostOutput& photo) {
    const Json& c = r.meta["crop"];
    if (c.kind != Json::Kind::Object) return;
    const float zoom = DevelopRecord::numberOf(c["zoom"], 1.f);
    if (const std::optional<CropRect> z = zoomCropRect(photo.width, photo.height, zoom)) cropRgba(photo.rgba, photo.width, photo.height, *z);
    if (DevelopRecord::boolOf(c["isz4x3"], false) && photo.width >= 8 && photo.height >= 8) {
        // IszAspectCrop.box: the centred 4:3 of even units
        const int unit = std::min(std::max(photo.width, photo.height) / 4, std::min(photo.width, photo.height) / 3) & ~1;
        if (unit >= 2) {
            const int w = photo.width >= photo.height ? 4 * unit : 3 * unit, h = photo.width >= photo.height ? 3 * unit : 4 * unit;
            const int x = (photo.width - w) / 2, y = (photo.height - h) / 2;
            if (w != photo.width || h != photo.height) cropRgba(photo.rgba, photo.width, photo.height, CropRect{x, y, x + w, y + h});
        }
    }
}

/// DevelopRun.develop: rgb (the DNG's RGB of w x h: float32 RGB, or IEEE halves when half) developed with the record's settings
/// and the changes - the photo (upright sRGB RGBA8), the record's crops applied. makeContext makes the pipeline's GL context current
/// on this thread. The caller holds processLock().
inline PostOutput develop(const DevelopRecord& r, std::vector<uint8_t> rgb, int w, int h, bool half, const Changes& changes,
                          const std::string& shaders, const std::function<void()>& makeContext,
                          const std::function<void(const std::string&)>& log) {
    Settings s = settings(r, changes);
    // the editor takes the SDR picture: the Ultra HDR snapshot and gain map (ScamProcessor's, after the pipeline) are not made
    s.prefs["pref_ultrahdr_key"] = Pref::boolean(false);
    Settings::Scope scope(s);
    const auto t0 = std::chrono::steady_clock::now();
    makeContext();
    GLProg prog(shaders);
    PostPipeline p;
    p.glProg = &prog;
    const DevelopRecord::Worker wk = r.worker();
    // the report's numbers as ScamNeuralClient reads them, the record's worker numbers over them
    mergeInfoFromReport(p.merge, wk.report, half);
    p.merge.lastBentoApplied = wk.bentoApplied;
    p.merge.lastBentoFactor = wk.bentoFactor;
    p.merge.lastBentoUsClipped = wk.bentoUsClipped;
    p.merge.lastEffectiveMedian = wk.effectiveMedian;
    p.merge.lastNoiseFactor = wk.noiseFactor;
    p.merge.lastNormals = wk.normals;
    p.merge.lastRgbHalf = half;
    const Parameters shot = Parameters::fromJson(r.meta["params"]);
    const int shotW = shot.rawSize ? shot.rawSize->x : w;
    p.merge.lastClipFlags = map(r.clipFlags, r.clipW, r.clipH, w, h, true);
    p.merge.lastEffectiveFrames = map(r.frames, r.framesW, r.framesH, w, h, false);
    p.merge.lastHlMask = hlMask(r, w, shotW);
    p.mParameters = parameters(r, w, h);
    p.mParameters.scamHdrRawScale = 1.f;
    p.scamRgb = std::move(rgb);
    PostOutput out = runPostPipeline(p);
    out.gainMap.reset();
    log("developed " + std::to_string(w) + "x" + std::to_string(h) + (half ? " (half RGB)" : "") + " -> " + std::to_string(out.width) + "x"
        + std::to_string(out.height) + " in "
        + std::to_string(int(std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count())) + " ms, "
        + std::to_string(changes.size()) + " setting(s) changed");
    if (out.width <= 0 || out.rgba.empty()) throw std::runtime_error("develop: no picture");
    crops(r, out);
    return out;
}

} // namespace Develop
} // namespace scam_post
