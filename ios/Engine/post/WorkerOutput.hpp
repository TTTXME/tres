// ScamNeuralClient.processScamBurst's read of the merge (the RGB, its trailers and the numbers of the worker report) into the
// post pipeline's MergeInfo, as the host reference (HostPostRender.readWorkerOutput) and the phone do it.
#pragma once

#include "Pipeline.hpp"

#include <regex>

namespace scam_post {

namespace Report {
/// ScamNeuralClient.reportNumber: the first "-?[0-9]+(\.[0-9]+)?" after key on the first line starting with `line`
inline float number(const std::string& rep, const std::string& line, const std::string& key, float fallback) {
    const size_t at = rep.find(line);
    if (at == std::string::npos) return fallback;
    const size_t end = rep.find('\n', at);
    const std::string l = end == std::string::npos ? rep.substr(at) : rep.substr(at, end - at);
    const size_t k = l.find(key);
    if (k == std::string::npos) return fallback;
    static const std::regex num("-?[0-9]+(\\.[0-9]+)?");
    std::smatch m;
    const std::string rest = l.substr(k + key.size());
    if (!std::regex_search(rest, m, num)) return fallback;
    return std::strtof(m.str().c_str(), nullptr);
}
/// ScamNeuralClient.reportFloat: the number right after key (exponents allowed), -1 when absent
inline float floatAfter(const std::string& rep, const std::string& line, const std::string& key) {
    const size_t at = rep.find(line);
    if (at == std::string::npos) return -1.f;
    const size_t end = rep.find('\n', at);
    const std::string l = end == std::string::npos ? rep.substr(at) : rep.substr(at, end - at);
    const size_t k = l.find(key);
    if (k == std::string::npos) return -1.f;
    static const std::regex num("^-?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?");
    std::smatch m;
    const std::string rest = l.substr(k + key.size());
    if (!std::regex_search(rest, m, num)) return -1.f;
    return std::strtof(m.str().c_str(), nullptr);
}
inline std::optional<std::array<float, 4>> dcgNoise(const std::string& rep) {
    const std::string line = "HYBRID NOISE CHECK DCG:";
    const float s = floatAfter(rep, line, "slope="), hcg = floatAfter(rep, line, "hcg="), lcg = floatAfter(rep, line, "lcg="),
                knee = floatAfter(rep, line, "knee=");
    if (!(s > 0.f) || hcg < 0.f || lcg < 0.f || knee < 0.f || knee > 1.f || !std::isfinite(s) || !std::isfinite(hcg) || !std::isfinite(lcg))
        return std::nullopt;
    return std::array<float, 4>{s, hcg, lcg, knee};
}
} // namespace Report

/// The report's numbers (the part of the client's read after the trailers).
inline void mergeInfoFromReport(MergeInfo& m, const std::string& rep, bool rgbHalf) {
    float effMedian = NAN;
    std::istringstream lines(rep);
    std::string line;
    const std::string EFFECTIVE_MAP_LINE = "HYBRID EFFECTIVE MAP:";
    while (std::getline(lines, line))
        if (line.find(EFFECTIVE_MAP_LINE) != std::string::npos) effMedian = Report::number(line, EFFECTIVE_MAP_LINE, "median frames=", NAN);
    m.lastEffectiveMedian = effMedian;
    m.lastBentoApplied = rep.find("HYBRID BENTO: applied") != std::string::npos;
    m.lastReport = rep;
    m.lastBentoFactor = Report::number(rep, "HYBRID BENTO: applied", "factor=", 1.f);
    m.lastMergedDngHeadroom = std::max(1.f, Report::number(rep, "HYBRID DNG:", "headroom=", 1.f));
    m.lastBentoUsClipped = Report::number(rep, "HYBRID BENTO: applied", "usClippedRatio=", 0.f);
    const float noiseK = Report::number(rep, "HYBRID NOISE CHECK:", "-> model x", 1.f);
    m.lastNoiseFactor = noiseK >= 1.f / 16 && noiseK <= 4.f ? noiseK : 1.f;
    const float darkRead = Report::number(rep, "HYBRID NOISE CHECK DARK:", "read term x", 1.f);
    m.lastNoiseDarkRead = darkRead >= 1.f && darkRead <= 16.f ? darkRead : 1.f;
    m.lastDcgNoise = Report::dcgNoise(rep);
    m.lastRgbHalf = rgbHalf;
}

/// The worker's output file split as the client does it, for a transport with these requests: the RGB bytes (returned),
/// the effective map, the clip flags and the highlight mask (HALF of the sensor grid, 16 B header "SCHLMSK1" + w + h).
inline std::vector<uint8_t> readWorkerOutput(MergeInfo& m, const std::vector<uint8_t>& out, const std::string& report, int outW, int outH,
                                             int sensorW, int sensorH, bool rgbHalf, bool effectiveMap, bool clipFlags, bool highlightMask) {
    const size_t px = size_t(outW) * size_t(outH);
    const size_t rgbBytes = px * (rgbHalf ? 6 : 12);
    const size_t effBytes = effectiveMap ? px : 0, clipBytes = clipFlags ? px : 0;
    const size_t hlBytes = highlightMask ? 16 + size_t(sensorW / 2) * size_t(sensorH / 2) * 2 : 0;
    size_t hl = 0;
    if (highlightMask && out.size() == rgbBytes + effBytes + clipBytes + hlBytes && std::memcmp(out.data() + out.size() - hlBytes, "SCHLMSK1", 8) == 0) hl = hlBytes;
    if (out.size() != rgbBytes + effBytes + clipBytes + hl) throw std::runtime_error("worker output of " + std::to_string(out.size()) + " B does not match the request");
    m.lastHlMask.reset();
    if (hl) {
        MergeInfo::HlMask mask;
        mask.width = sensorW / 2;
        mask.height = sensorH / 2;
        mask.halves.resize((hl - 16) / 2);
        std::memcpy(mask.halves.data(), out.data() + out.size() - hl + 16, hl - 16);
        m.lastHlMask = std::move(mask);
    }
    m.lastEffectiveFrames.reset();
    m.lastClipFlags.reset();
    mergeInfoFromReport(m, report, rgbHalf);
    if (clipBytes) m.lastClipFlags = std::vector<uint8_t>(out.begin() + long(rgbBytes + effBytes), out.begin() + long(rgbBytes + effBytes + clipBytes));
    if (effBytes) m.lastEffectiveFrames = std::vector<uint8_t>(out.begin() + long(rgbBytes), out.begin() + long(rgbBytes + effBytes));
    return std::vector<uint8_t>(out.begin(), out.begin() + long(rgbBytes));
}

} // namespace scam_post
