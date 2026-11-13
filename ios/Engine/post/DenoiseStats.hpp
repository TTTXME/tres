// DenoiseStats.java one to one for the photo route: the finish SNR (level 0's block statistics by the compute pass, the YUV
// level and fragment statistics as its fallback) and the effective-frame map texture. DCG zones are not ported (no DCG on iOS).
#pragma once

#include "NoiseMath.hpp"
#include "Pipeline.hpp"
#include "nodes/ScamRgb.hpp"

namespace scam_post {
namespace DenoiseStats {

inline float clampf(float v, float lo, float hi) { return std::isnan(v) ? lo : std::max(lo, std::min(hi, v)); }

inline float displayGain(float p50, float p90, bool scamSoc) {
    const float mid = 0.050f, high = 0.180f, gainMax = 128.f;
    p50 = std::max(p50, 1.0e-5f);
    p90 = std::max(p90, 1.0e-5f);
    const float gain50 = mid / p50, gain90 = high / p90;
    float sceneGain = float(std::sqrt(double(std::max(1.f, gain50) * std::max(1.f, gain90))));
    const float brightMid = 0.25f;
    if (scamSoc) {
        const float limit = 0.40f;
        const float highGain = std::max(1.f, limit / p90);
        sceneGain = std::min(std::max(1.f, gain50), highGain);
        if (brightMid > 0.f && p50 >= mid) sceneGain = std::max(sceneGain, std::min(highGain, std::max(1.f, std::min(2.2f, brightMid / p50))));
    } else if (brightMid > 0.f) {
        sceneGain = std::max(sceneGain, std::max(1.f, std::min(2.2f, brightMid / p50)));
    }
    return std::max(1.f, std::min(gainMax, sceneGain));
}

/// the RGBA32UI texels of t (GL_RGBA_INTEGER / GL_UNSIGNED_INT through a read framebuffer of its own)
inline std::vector<int32_t> readInts(GLTexture& t) {
    const int w = t.mSize.x, h = t.mSize.y;
    GLint oldRead = 0;
    GLuint framebuffer = 0;
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &oldRead);
    glGenFramebuffers(1, &framebuffer);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
    glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t.mTextureID, 0);
    std::vector<int32_t> out(size_t(w) * h * 4);
    glReadPixels(0, 0, w, h, GL_RGBA_INTEGER, GL_UNSIGNED_INT, out.data());
    glBindFramebuffer(GL_READ_FRAMEBUFFER, GLuint(oldRead));
    glDeleteFramebuffers(1, &framebuffer);
    return out;
}

inline std::vector<float> statsOf(const std::vector<int32_t>& texels, int atlasWidth, int x0, int y0, int w, int h) {
    const auto& table = halfTable();
    std::vector<float> out(size_t(w) * h * 8);
    for (int y = 0; y < h; y++) {
        const size_t src = (size_t(y0 + y) * size_t(atlasWidth) + size_t(x0)) * 4, dst = size_t(y) * size_t(w) * 8;
        for (int k = 0; k < w * 4; k++) {
            const uint32_t v = uint32_t(texels[src + size_t(k)]);
            out[dst + size_t(2 * k)] = table[v & 0xffff];
            out[dst + size_t(2 * k + 1)] = table[v >> 16];
        }
    }
    return out;
}

inline std::array<int, 2> statsAtlas(const std::vector<int>& w, const std::vector<int>& h, std::vector<int>& x, std::vector<int>& y) {
    int width = 0, shelfY = 0, shelfH = 0, at = 0;
    for (size_t i = 0; i < w.size(); i++) {
        if (i > 0 && at + w[i] > width) { shelfY += shelfH; shelfH = 0; at = 0; }
        x[i] = at;
        y[i] = shelfY;
        at += w[i];
        shelfH = std::max(shelfH, h[i]);
        width = std::max(width, at);
    }
    return {width, shelfY + shelfH};
}

inline void statsProgram(GLProg& prog, float sY, float rY) {
    prog.useAssetProgram("scamdn/stats", false);
    prog.setVar("atU", 0, 0);
    prog.setVar("modelU", sY, rY);
}

inline void drawRegion(GLProg& prog, GLTexture& target, int x, int y, int w, int h) {
    target.BufferLoad();
    const int tile = GLDrawParams::TileSize();
    for (int passed = 0; passed < h; passed += tile) {
        glViewport(x, y + passed, w, std::min(tile, h - passed));
        prog.draw();
    }
    glFlush();
}

inline std::vector<std::optional<std::vector<float>>> levelNoiseBatch(GLProg& prog, const std::vector<GLTexture*>& levels, int block, float sY,
                                                                      float rY, std::vector<float>* rawOut) {
    const size_t n = levels.size();
    std::vector<int> bw(n), bh(n), ax(n), ay(n);
    for (size_t i = 0; i < n; i++) {
        bw[i] = std::max(1, levels[i]->mSize.x / block);
        bh[i] = std::max(1, levels[i]->mSize.y / block);
    }
    const auto atlasSize = statsAtlas(bw, bh, ax, ay);
    std::vector<int32_t> texels;
    {
        GLTexture atlas({atlasSize[0], atlasSize[1]}, GLFormat(DataType::UNSIGNED_32, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
        for (size_t i = 0; i < n; i++) {
            statsProgram(prog, sY, rY);
            prog.setTexture("InputBuffer", *levels[i]);
            prog.setVar("blockU", block);
            prog.setVar("atU", ax[i], ay[i]);
            drawRegion(prog, atlas, ax[i], ay[i], bw[i], bh[i]);
        }
        texels = readInts(atlas);
    }
    std::vector<std::optional<std::vector<float>>> out(n);
    for (size_t i = 0; i < n; i++) {
        auto raw = statsOf(texels, atlasSize[0], ax[i], ay[i], bw[i], bh[i]);
        out[i] = NoiseMath::reduce(raw, bw[i] * bh[i], 0.8f);
        if (i == 0 && rawOut) *rawOut = std::move(raw);
    }
    return out;
}

/// finishNoiseCs: level 0's statistics in one compute pass from the RGB (block 16); null on a GL failure
inline std::optional<std::vector<float>> finishNoiseCs(GLProg& prog, GLTexture& rgb, float sY, float rY, std::vector<float>& rawOut, bool& failed) {
    const int bw = std::max(1, rgb.mSize.x / 16), bh = std::max(1, rgb.mSize.y / 16);
    for (int i = 0; i < 8 && glGetError() != GL_NO_ERROR; i++) {}
    try {
        GLTexture atlas({bw, bh}, GLFormat(DataType::UNSIGNED_32, 4), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
        prog.setLayout(16, 16, 1);
        prog.useAssetProgram("scamdn/stats_cs", true);
        prog.setTexture("InputBuffer", rgb);
        prog.setVar("modelU", sY, rY);
        prog.setTextureCompute("outStats", atlas, true);
        prog.computeManual(bw, bh, 1);
        const auto texels = readInts(atlas);
        if (glGetError() != GL_NO_ERROR) { failed = true; return std::nullopt; }
        rawOut = statsOf(texels, bw, 0, 0, bw, bh);
        failed = false;
        return NoiseMath::reduce(rawOut, bw * bh, 0.8f);
    } catch (const std::exception& e) {
        logI("SCAM_PIPELINE", std::string("finish noise compute pass: ") + e.what() + ", the two passes");
        failed = true;
        return std::nullopt;
    }
}

/// finishSnr (finishSnrBegin + finishSnrEnd, the read-back at once): the shot's SNR into Parameters.finishSnr
inline float finishSnr(GLProg& prog, PostPipeline& pipeline, GLTexture& clean, std::string* log) {
    Parameters& par = pipeline.mParameters;
    Settings& prefs = Settings::current();
    const Point full = clean.mSize;
    const Point shotSize = par.labWholeSize ? *par.labWholeSize : full;
    const int s = int64_t(shotSize.x) * shotSize.y > 100000000LL ? 2 : std::max(1, std::min(2, javaRound(par.outputScale)));
    const Point size0{(full.x + s - 1) / s, (full.y + s - 1) / s};
    const float rhoS = clampf(prefs.hybridValue("dn_model_shot", 1.2f), 0.25f, 4.f);
    const float rhoR = clampf(prefs.hybridValue("dn_model_read", 2.f), 0.25f, 16.f);
    const float snrFixed = clampf(prefs.hybridValue("dn_snr", 0.f), 0.f, 200.f);
    const float snrScale = clampf(prefs.hybridValue("dn_snr_scale", 1.f), 0.25f, 8.f);
    if (par.dcgShot) throw std::runtime_error("post port: the DCG noise zones are not ported");
    const float wY[3] = {0.2126f, 0.7152f, 0.0721996f};
    float S[3] = {2e-4f, 2e-4f, 2e-4f}, O[3] = {2e-6f, 2e-6f, 2e-6f};
    if (par.noiseModeler && par.noiseModeler->baseModel.size() >= 3) {
        for (int c = 0; c < 3; c++) {
            S[c] = float(std::max(1e-9, par.noiseModeler->baseModel[size_t(c)].first));
            O[c] = float(std::max(1e-12, par.noiseModeler->baseModel[size_t(c)].second));
        }
        const float k = pipeline.merge.lastNoiseFactor;
        if (k != 1.f) for (int c = 0; c < 3; c++) { S[c] *= k; O[c] *= k; }
    }
    float sY = 0.f, rY = 0.f, sG = 0.f, rG = 0.f;
    for (int c = 0; c < 3; c++) {
        const float wp = par.whitePoint && par.whitePoint->size() > size_t(c) && (*par.whitePoint)[size_t(c)] > 1e-6f ? (*par.whitePoint)[size_t(c)] : 1.f;
        const float g = 1.f / wp;
        sY += wY[c] * wY[c] * g * S[c];
        rY += wY[c] * wY[c] * g * g * O[c];
        if (c == 1) { sG = g * S[c]; rG = g * g * O[c]; }
    }
    const bool cs = s == 1 && prefs.scamDevSwitch("snr_cs", true);
    std::vector<float> raw0;
    std::optional<std::vector<float>> g0;
    bool failed = !cs;
    if (cs) g0 = finishNoiseCs(prog, clean, sY, rY, raw0, failed);
    if (failed) {
        raw0.clear();
        Texture base;
        GLTexture* basePtr = &clean;
        if (s == 2) {
            base = makeTexture(size0, GLFormat(DataType::FLOAT_16, 4), nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
            prog.useAssetProgram("scamdn/down2x", false);
            prog.setTexture("InputBuffer", clean);
            prog.drawBlocks(base);
            basePtr = base.get();
        }
        GLTexture x0(size0, GLFormat(DataType::FLOAT_16, 4), nullptr, GL_LINEAR, GL_CLAMP_TO_EDGE);
        prog.useAssetProgram("scamdn/yuv", false);
        prog.setTexture("InputBuffer", *basePtr);
        prog.drawBlocks(x0);
        if (base) base->close();
        g0 = levelNoiseBatch(prog, {&x0}, 16, sY, rY, &raw0)[0];
    }
    const float gy00 = g0 ? (*g0)[0] : NoiseMath::whiteGain(0, false) / 4.f;
    float p50 = 0.f, p90 = 0.f;
    if (!raw0.empty()) {
        const int n = int(raw0.size() / 8);
        std::vector<float> ys;
        ys.reserve(size_t(n));
        for (int i = 0; i < n; i++) if (raw0[size_t(8 * i)] >= 0.f && std::isfinite(raw0[size_t(8 * i)])) ys.push_back(raw0[size_t(8 * i)]);
        const int m = int(ys.size());
        if (m > 0) {
            p50 = NoiseMath::selectFloat(ys.data(), m, std::min(m - 1, m / 2));
            p90 = NoiseMath::selectFloat(ys.data(), m, std::min(m - 1, int(float(m) * 0.9f)));
        }
    }
    const float rawScale = par.scamHdrMode ? std::max(1e-6f, par.scamHdrRawScale) : 1.f;
    float mu = 0.18f, gain = 1.f;
    if (p50 > 0.f) {
        gain = displayGain(p50 / rawScale, p90 / rawScale, false);
        mu = 0.18f * rawScale / gain;
    }
    const float snrMeasured = NoiseMath::snr(mu, gy00, sG, rG, rhoS, rhoR);
    const float snr = snrFixed > 0.f ? snrFixed : snrMeasured * snrScale;
    par.finishSnr = snr;
    // P648c: the luma noise the statistics measured on this input (its model x the measured ratio), for the Resolve's flat test
    par.finishNoiseS = gy00 * sY;
    par.finishNoiseO = gy00 * rY;
    if (log) {
        auto r2 = [](float v) { return float(javaRound(v * 100.f)) / 100.f; };
        auto r3 = [](float v) { return float(javaRound(v * 1000.f)) / 1000.f; };
        *log += " finishSnr=" + javaFloatString(r2(snr)) + (snrFixed > 0.f ? "(fixed)" : "") + " mu=" + javaFloatString(mu) + " gain="
                + javaFloatString(r2(gain)) + " g00=" + javaFloatString(r3(gy00));
    }
    return snr;
}

/// effectiveFramesTexture: the uint8 codes as R8UI (GL_RED_INTEGER), unpack alignment 1 left set
inline Texture effectiveFramesTexture(Point size, const std::vector<uint8_t>& eff) {
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    Texture t = makeTexture(size, GLFormat(DataType::UNSIGNED_8, 1), nullptr, GL_NEAREST, GL_CLAMP_TO_EDGE);
    const bool real = Settings::current().scamDevSwitch("effmap_real", true);
    GLenum uploadError = GL_NO_ERROR;
    if (real) {
        for (int i = 0; i < 8 && glGetError() != GL_NO_ERROR; i++) {}
        glBindTexture(GL_TEXTURE_2D, t->mTextureID);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, size.x, size.y, GL_RED_INTEGER, GL_UNSIGNED_BYTE, eff.data());
        uploadError = glGetError();
    }
    const bool loaded = real && uploadError == GL_NO_ERROR;
    if (!loaded) {
        t->BufferLoad();
        const GLuint zero[4] = {0, 0, 0, 0};
        glClearBufferuiv(GL_COLOR, 0, zero);
    }
    logI("SCAM_PIPELINE", loaded ? "EFFMAP real codes uploaded (GL_RED_INTEGER), one-sided strength" : "EFFMAP: map cleared");
    return t;
}

} // namespace DenoiseStats
} // namespace scam_post
