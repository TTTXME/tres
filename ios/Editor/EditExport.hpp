// EditSource's colours and readers, EditExporter's plan and tile loop, EditorView's preview / histogram / thumbnail frames
// (gallery/editor) one to one: the same renderer and uniforms for the preview (a downscaled source) and the copy (full-resolution
// tiles of TILE output px, each with the overlap of NR / the Gaussians / the demosaic, the local-tone bases from the preview's
// 512 px proxy). Decoding (ImageIO on the iPhone) and saving (JPEG q95 + EXIF) are the caller's.
#pragma once

#include "DngRaw.hpp"
#include "EditRenderer.hpp"

#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace scam_edit {

// ---------------------------------------------------------------- colours (EditSource.RawColour)

/// A RAW's colour: as-shot WB from the camera neutral, Температура / Оттенок as CCT (mired) / Duv offsets, the DNG matrices.
struct RawColour : EditMath::SourceColour {
    DngColor dng;
    float base = 0;
    double t0 = 0, duv0 = 0;

    RawColour(const DngColor& c, float baseStops) : dng(c), base(baseStops) {
        if (dng.hasMatrices()) {
            const auto cd = DngColor::cctDuv(dng.xyForNeutral(dng.asShotNeutral));
            t0 = cd[0];
            duv0 = cd[1];
        }
    }
    bool linear() const override { return true; }
    bool scene() const override { return true; }
    float baseStops() const override { return base; }

    /// {CCT K, Duv} the sliders ask for; empty without matrices
    std::vector<double> white(const EditState& s) const {
        if (!dng.hasMatrices()) return {};
        double mired = 1e6 / t0 - double(s.get(Param::TEMPERATURE)) * 1.5;
        mired = std::max(1e6 / 50000, std::min(1e6 / 1800, mired));
        return {1e6 / mired, duv0 + double(s.get(Param::TINT)) * 0.0005};
    }

    void colour(const EditState& s, float* wbMul, float* toWork) const override {
        const auto w = white(s);
        std::vector<double> n;
        M3 m;
        if (!w.empty()) {
            n = dng.neutralForXy(DngColor::xyFromCctDuv(w[0], w[1]));
            m = dng.cameraToSrgb(n);
        } else {
            const auto g = EditMath::wbGains(s.get(Param::TEMPERATURE) / 100.f, s.get(Param::TINT) / 100.f);
            const auto& a = dng.asShotNeutral;
            n = {a[0] / g[0], a[1] / g[1], a[2] / g[2]};
            m = {1 / n[0], 0, 0, 0, 1 / n[1], 0, 0, 0, 1 / n[2]};
        }
        for (int i = 0; i < 3; i++) wbMul[i] = float(1 / std::max(n[size_t(i)], 1e-9));
        for (int r = 0; r < 3; r++)
            for (int k = 0; k < 3; k++) toWork[r * 3 + k] = float(m[size_t(r * 3 + k)] * n[size_t(k)]);
    }
};

// ---------------------------------------------------------------- the copy's plan (EditExporter.Plan, EditZoom.region)

constexpr int TILE = 1024;
constexpr long long OUT_MAX = 64000000LL;
constexpr long long RAW_MAX = 64000000LL;
constexpr int PREVIEW_SIDE = 2048;

/// EditExporter.outMax: the export's output pixel limit for this much free memory (bytes)
inline long long outMax(long long budget) { return std::max(4000000LL, std::min(OUT_MAX, budget / 10)); }

inline std::array<int, 2> outputSize(double cropW, double cropH, long long outMaxPx) {
    const double k = std::min(1.0, std::sqrt(double(outMaxPx) / std::max(1.0, cropW * cropH)));
    return {std::max(1, int(jroundD(cropW * k))), std::max(1, int(jroundD(cropH * k)))};
}

struct Plan {
    int W = 1, H = 1;
    double ow = 1, oh = 1;
    CropMath::Affine outToBase, baseToImg, imgToSrcUv, outToSrcPx;

    Plan(const EditState& state, int sw, int sh, int orientation, long long outMaxPx) {
        const bool swap = CropMath::swapsSides(orientation);
        ow = swap ? sh : sw;
        oh = swap ? sw : sh;
        const auto crop = CropMath::cropPixels(state, ow, oh);
        const auto size = outputSize(crop[0], crop[1], outMaxPx);
        W = size[0];
        H = size[1];
        outToBase = CropMath::Affine::scale(1.0 / W, 1.0 / H).then(CropMath::cropToBase(state));
        baseToImg = CropMath::baseToOriented(state, ow, oh);
        imgToSrcUv = CropMath::orientationToDisplay(orientation).inverse();
        outToSrcPx = outToBase.then(baseToImg).then(imgToSrcUv).then(CropMath::Affine::scale(sw, sh));
    }
    /// EditZoom: the displayed crop as an output of w x h px (the view's zoom instead of the copy's size)
    Plan(const EditState& state, int sw, int sh, int orientation, int w, int h) {
        const bool swap = CropMath::swapsSides(orientation);
        ow = swap ? sh : sw;
        oh = swap ? sw : sh;
        W = std::max(1, w);
        H = std::max(1, h);
        outToBase = CropMath::Affine::scale(1.0 / W, 1.0 / H).then(CropMath::cropToBase(state));
        baseToImg = CropMath::baseToOriented(state, ow, oh);
        imgToSrcUv = CropMath::orientationToDisplay(orientation).inverse();
        outToSrcPx = outToBase.then(baseToImg).then(imgToSrcUv).then(CropMath::Affine::scale(sw, sh));
    }
    int longSide() const { return std::max(W, H); }
    float latticeAspect() const { return float(W) / float(H); }
};

inline std::array<double, 4> box(const CropMath::Affine& m, const std::array<double, 4>& r) {
    double x0 = 1.7976931348623157e308, y0 = 1.7976931348623157e308, x1 = -1.7976931348623157e308, y1 = -1.7976931348623157e308;
    const double pts[4][2] = {{r[0], r[1]}, {r[2], r[1]}, {r[0], r[3]}, {r[2], r[3]}};
    for (const auto& p : pts) {
        const auto q = m.apply(p[0], p[1]);
        x0 = std::min(x0, q[0]);
        y0 = std::min(y0, q[1]);
        x1 = std::max(x1, q[0]);
        y1 = std::max(y1, q[1]);
    }
    return {x0, y0, x1, y1};
}

/// EditZoom.region: {x, y, w, h} of the source an output tile needs (its corners mapped back, the overlap, inside the source)
inline std::array<int, 4> region(const CropMath::Affine& outToSrcPx, int ox, int oy, int tw, int th, int overlap, int sw, int sh, bool even) {
    const auto b = box(outToSrcPx, {double(ox), double(oy), double(ox + tw), double(oy + th)});
    int x0 = std::max(0, jint(std::floor(b[0])) - overlap), y0 = std::max(0, jint(std::floor(b[1])) - overlap);
    int x1 = std::min(sw, jint(std::ceil(b[2])) + overlap), y1 = std::min(sh, jint(std::ceil(b[3])) + overlap);
    if (even) {
        x0 &= ~1;
        y0 &= ~1;
    }
    x1 = std::max(x1, std::min(sw, x0 + 2));
    y1 = std::max(y1, std::min(sh, y0 + 2));
    return {x0, y0, x1 - x0, y1 - y0};
}

// ---------------------------------------------------------------- sources

/// What the export's tiles read (EditSource.Reader): regions of the original in the reader's pixels, stored orientation.
struct Reader {
    virtual ~Reader() = default;
    virtual int width() const = 0;
    virtual int height() const = 0;
    /// a texture of the region; the caller deletes it unless ownsTextures()
    virtual GLuint upload(EditRenderer& r, int x, int y, int w, int h) = 0;
    virtual bool ownsTextures() const { return false; }
    virtual bool evenAligned() const { return false; }
    virtual const EditMath::SourceColour& colour() const = 0;
};

/// A decoded picture (EditSource.BitmapReader / RegionReader at sample 1): RGBA8 rows top-down, sRGB or Display P3.
struct PictureReader : Reader {
    const uint8_t* rgba;
    int w, h;
    EditMath::DisplayColour col;
    PictureReader(const uint8_t* rgba_, int w_, int h_, bool p3) : rgba(rgba_), w(w_), h(h_), col(p3, false) {}
    int width() const override { return w; }
    int height() const override { return h; }
    GLuint upload(EditRenderer&, int x, int y, int rw, int rh) override {
        std::vector<uint8_t> part(size_t(rw) * size_t(rh) * 4);
        for (int j = 0; j < rh; j++) std::memcpy(part.data() + size_t(j) * size_t(rw) * 4, rgba + (size_t(y + j) * size_t(w) + size_t(x)) * 4, size_t(rw) * 4);
        return EditGl::uploadRgba8(part.data(), rw, rh);
    }
    const EditMath::SourceColour& colour() const override { return col; }
};

/// A RAW decoded for the export (EditSource.RawReader): the renderer demosaics each region.
struct RawReader : Reader {
    const DngRaw::Image& img;
    const RawColour& col;
    RawReader(const DngRaw::Image& image, const RawColour& c) : img(image), col(c) {}
    int width() const override { return img.width; }
    int height() const override { return img.height; }
    bool ownsTextures() const override { return true; }
    bool evenAligned() const override { return img.info.kind == DngRaw::KIND_BAYER; }
    GLuint upload(EditRenderer& r, int x, int y, int w, int h) override { return r.rawSource(samples(img), x, y, w, h); }
    const EditMath::SourceColour& colour() const override { return col; }
    static RawSamples samples(const DngRaw::Image& i) {
        RawSamples s;
        s.data = i.data.data();
        s.width = i.width;
        s.height = i.height;
        s.rgb = i.info.kind == DngRaw::KIND_RGB;
        s.phase = i.phase;
        s.scale = i.scale;
        return s;
    }
};

/// The preview's source (EditSource's preview / previewRaw): a texture of the downscaled picture and its colour.
struct PreviewSource {
    int width = 1, height = 1;     // the preview's texture (as stored)
    int orientation = 1;
    int fullW = 0, fullH = 0;      // the original as the route stores it
    std::shared_ptr<EditMath::SourceColour> colour;
    std::vector<uint8_t> rgba;     // a picture's preview
    bool p3 = false;
    std::shared_ptr<DngRaw::Image> raw; // a RAW's preview decode

    GLuint upload(EditRenderer& r, bool& owned) const {
        if (raw) {
            owned = false;
            return r.rawSource(RawReader::samples(*raw), 0, 0, raw->width, raw->height);
        }
        owned = true;
        return EditGl::uploadRgba8(rgba.data(), width, height);
    }
    /// EditorView.oriented: the oriented preview size (what the crop math works in)
    std::array<double, 2> oriented() const {
        return CropMath::swapsSides(orientation) ? std::array<double, 2>{double(height), double(width)} : std::array<double, 2>{double(width), double(height)};
    }
};

/// The RAW route of EditSource.openRaw (DngRaw): the preview binned to about PREVIEW_SIDE x 1.25.
inline PreviewSource openDngPreview(const uint8_t* data, size_t size) {
    const DngRaw::Info info = DngRaw::probe(data, size);
    int bin = 1;
    while (true) {
        const auto sz = info.size(bin);
        if (std::max(sz[0], sz[1]) <= PREVIEW_SIDE * 1.25 || bin >= 16) break;
        bin *= 2;
    }
    auto img = std::make_shared<DngRaw::Image>(DngRaw::decode(data, size, bin));
    PreviewSource s;
    s.raw = img;
    s.width = img->width;
    s.height = img->height;
    s.fullW = info.size(1)[0];
    s.fullH = info.size(1)[1];
    s.orientation = img->info.orientation;
    s.colour = std::make_shared<RawColour>(img->info.colour, float(img->info.baselineExposure) + img->autoLiftStops);
    return s;
}

/// EditSource.RawReader: the RAW decoded again for the export (2x2 binned over RAW_MAX samples or the memory budget / 6)
inline DngRaw::Image decodeDngForExport(const uint8_t* data, size_t size, long long budget, bool* binned) {
    const DngRaw::Info info = DngRaw::probe(data, size);
    const auto s1 = info.size(1);
    const bool bin2 = (long long)s1[0] * s1[1] > std::min(RAW_MAX, budget / 6);
    if (binned) *binned = bin2;
    return DngRaw::decode(data, size, bin2 ? 2 : 1);
}

// ---------------------------------------------------------------- the copy (EditExporter.export's GL part)

/// The edit at full resolution: RGBA8 rows top-down, W x H (plan of the reader's picture). progress 0..1.
struct ExportResult {
    int W = 0, H = 0;
    std::vector<uint8_t> rgba;
    bool shrunk = false;
};

inline ExportResult renderCopy(EditRenderer& r, const PreviewSource& preview, Reader& reader, const EditState& state, int orientation,
                               long long outMaxPx, const std::function<void(float)>& progress) {
    // the proxy from the preview's decode: the same bases as the preview
    bool owned = false;
    const GLuint previewTex = preview.upload(r, owned);
    r.setProxySource(previewTex, preview.width, preview.height);
    if (owned) EditGl::deleteTexture(previewTex);
    r.updateProxy(EditMath::Uniforms::of(state, *preview.colour));
    if (progress) progress(0.02f);
    const int sw = reader.width(), sh = reader.height();
    const Plan plan(state, sw, sh, orientation, outMaxPx);
    const auto crop = CropMath::cropPixels(state, plan.ow, plan.oh);
    const double k = std::min(1.0, std::sqrt(double(outMaxPx) / std::max(1.0, crop[0] * crop[1])));
    ExportResult res;
    res.W = plan.W;
    res.H = plan.H;
    res.shrunk = k < 0.999;
    res.rgba.assign(size_t(plan.W) * size_t(plan.H) * 4, 0);
    // P619: the «LUT» tab's look on every tile, from the state's tables (EditorViewModel: EditLook.blocking); the overlap also
    // covers the reference grain's blur
    const int overlap = EditLook::overlap(state, float(std::max(sw, sh))) + 2;
    r.setLook(EditLook::on(state) ? EditLook::applied(state, EditLook::blocking(state)) : std::nullopt);
    EditRenderer::Target tile;
    std::vector<uint8_t> pixels(size_t(TILE) * TILE * 4);
    const int tilesX = (plan.W + TILE - 1) / TILE, tilesY = (plan.H + TILE - 1) / TILE;
    int done = 0;
    for (int ty = 0; ty < tilesY; ty++) {
        for (int tx = 0; tx < tilesX; tx++) {
            const int ox = tx * TILE, oy = ty * TILE, tw = std::min(TILE, plan.W - ox), th = std::min(TILE, plan.H - oy);
            const auto reg = region(plan.outToSrcPx, ox, oy, tw, th, overlap, sw, sh, reader.evenAligned());
            const GLuint src = reader.upload(r, reg[0], reg[1], reg[2], reg[3]);
            const EditMath::Uniforms u = EditMath::Uniforms::of(state, reader.colour());
            const CropMath::Affine texToSrcUv = CropMath::Affine::scale(reg[2], reg[3]).then(CropMath::Affine::translate(reg[0], reg[1]))
                .then(CropMath::Affine::scale(1.0 / sw, 1.0 / sh));
            r.renderSource(src, reg[2], reg[3], float(std::max(sw, sh)), texToSrcUv, u, false);
            if (!reader.ownsTextures()) EditGl::deleteTexture(src);
            tile.ensure(tw, th, GL_RGBA8, false, 1);
            EditRenderer::Finish f;
            f.fbo = tile.fbo;
            f.w = tw;
            f.h = th;
            f.originX = float(ox);
            f.originY = float(oy);
            f.swap = false; // RGBA bytes (Android: BGRA read as ARGB ints, the same pixels)
            f.mode = 0;
            f.outToBase = plan.outToBase;
            f.baseToImg = plan.baseToImg;
            f.imgToTex = plan.imgToSrcUv.then(texToSrcUv.inverse());
            f.outLongSide = float(plan.longSide());
            r.latticeAspect = plan.latticeAspect();
            r.finish(f, state, u);
            glPixelStorei(GL_PACK_ALIGNMENT, 4);
            glReadPixels(0, 0, tw, th, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
            for (int j = 0; j < th; j++)
                std::memcpy(res.rgba.data() + (size_t(oy + j) * size_t(plan.W) + size_t(ox)) * 4, pixels.data() + size_t(j) * size_t(tw) * 4, size_t(tw) * 4);
            done++;
            if (progress) progress(0.05f + 0.8f * float(done) / float(tilesX * tilesY));
        }
    }
    tile.release();
    r.setLook(std::nullopt);
    return res;
}

/// P367 EditZoomLoader: the window (wx, wy, ww x wh, output px) of the displayed crop rendered as a W x H output from the
/// reader's picture - the export's chain (the preview's proxy, the source region with its overlap, the finish at the window's
/// origin), so the zoom shows the pixels «Сохранить копию» makes at that size. RGBA8 rows top-down, ww x wh.
inline ExportResult renderWindow(EditRenderer& r, const PreviewSource& preview, Reader& reader, const EditState& state, int orientation,
                                 int W, int H, int wx, int wy, int ww, int wh) {
    bool owned = false;
    const GLuint previewTex = preview.upload(r, owned);
    r.setProxySource(previewTex, preview.width, preview.height);
    if (owned) EditGl::deleteTexture(previewTex);
    r.updateProxy(EditMath::Uniforms::of(state, *preview.colour));
    const int sw = reader.width(), sh = reader.height();
    const Plan plan(state, sw, sh, orientation, W, H);
    const int x0 = std::max(0, std::min(plan.W, wx)), y0 = std::max(0, std::min(plan.H, wy));
    const int x1 = std::max(x0, std::min(plan.W, wx + ww)), y1 = std::max(y0, std::min(plan.H, wy + wh));
    ExportResult res;
    res.W = x1 - x0;
    res.H = y1 - y0;
    if (res.W <= 0 || res.H <= 0) return res;
    res.rgba.assign(size_t(res.W) * size_t(res.H) * 4, 0);
    const int overlap = EditLook::overlap(state, float(std::max(sw, sh))) + 2;
    r.setLook(EditLook::on(state) ? EditLook::applied(state, EditLook::blocking(state)) : std::nullopt);
    EditRenderer::Target tile;
    std::vector<uint8_t> pixels(size_t(TILE) * TILE * 4);
    for (int oy = y0; oy < y1; oy += TILE) {
        for (int ox = x0; ox < x1; ox += TILE) {
            const int tw = std::min(TILE, x1 - ox), th = std::min(TILE, y1 - oy);
            const auto reg = region(plan.outToSrcPx, ox, oy, tw, th, overlap, sw, sh, reader.evenAligned());
            const GLuint src = reader.upload(r, reg[0], reg[1], reg[2], reg[3]);
            const EditMath::Uniforms u = EditMath::Uniforms::of(state, reader.colour());
            const CropMath::Affine texToSrcUv = CropMath::Affine::scale(reg[2], reg[3]).then(CropMath::Affine::translate(reg[0], reg[1]))
                .then(CropMath::Affine::scale(1.0 / sw, 1.0 / sh));
            r.renderSource(src, reg[2], reg[3], float(std::max(sw, sh)), texToSrcUv, u, false);
            if (!reader.ownsTextures()) EditGl::deleteTexture(src);
            tile.ensure(tw, th, GL_RGBA8, false, 1);
            EditRenderer::Finish f;
            f.fbo = tile.fbo;
            f.w = tw;
            f.h = th;
            f.originX = float(ox);
            f.originY = float(oy);
            f.swap = false;
            f.mode = 0;
            f.outToBase = plan.outToBase;
            f.baseToImg = plan.baseToImg;
            f.imgToTex = plan.imgToSrcUv.then(texToSrcUv.inverse());
            f.outLongSide = float(plan.longSide());
            r.latticeAspect = plan.latticeAspect();
            r.finish(f, state, u);
            glPixelStorei(GL_PACK_ALIGNMENT, 4);
            glReadPixels(0, 0, tw, th, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
            for (int j = 0; j < th; j++)
                std::memcpy(res.rgba.data() + (size_t(oy - y0 + j) * size_t(res.W) + size_t(ox - x0)) * 4, pixels.data() + size_t(j) * size_t(tw) * 4,
                            size_t(tw) * 4);
        }
    }
    tile.release();
    r.setLook(std::nullopt);
    return res;
}

// ---------------------------------------------------------------- the preview (EditorView's GL thread)

/// EditorView.canvas in pixels of a w x h view (no margin: the caller's view places the picture)
struct Rect { float l = 0, t = 0, r = 0, b = 0; float width() const { return r - l; } float height() const { return b - t; } };

inline Rect canvas(float w, float h, float margin, const EditState& s, double ow, double oh, bool crop) {
    const auto b = CropMath::baseSize(ow, oh, s.quarterTurns);
    const double aw = crop ? b[0] : b[0] * double(s.cropR - s.cropL), ah = crop ? b[1] : b[1] * double(s.cropB - s.cropT);
    const float aw2 = w - 2 * margin, ah2 = h - 2 * margin;
    const double k = std::min(aw2 / std::max(1e-6, aw), ah2 / std::max(1e-6, ah));
    const float fw = float(aw * k), fh = float(ah * k);
    const float l = (w - fw) / 2.f, t = (h - fh) / 2.f;
    return {l, t, l + fw, t + fh};
}

/// EditorView.finishFor: the frame of the canvas rect c (output px), crop tab or not
inline EditRenderer::Finish finishFor(EditRenderer& r, const EditState& st, const Rect& c, bool crop, const std::array<double, 2>& o, int orientation,
                                      const float* bg) {
    EditRenderer::Finish f;
    f.mode = crop ? 1 : 0;
    const CropMath::Affine toCanvas = CropMath::Affine::translate(-c.l, -c.t).then(CropMath::Affine::scale(1.0 / c.width(), 1.0 / c.height()));
    f.outToBase = crop ? toCanvas : toCanvas.then(CropMath::cropToBase(st));
    f.baseToImg = CropMath::baseToOriented(st, o[0], o[1]);
    f.imgToTex = CropMath::orientationToDisplay(orientation).inverse();
    for (int i = 0; i < 3; i++) f.bg[i] = bg[i];
    f.outLongSide = crop ? std::max(c.width() * (st.cropR - st.cropL), c.height() * (st.cropB - st.cropT)) : std::max(c.width(), c.height());
    const auto px = CropMath::cropPixels(st, o[0], o[1]);
    r.latticeAspect = float(px[0] / std::max(1e-6, px[1]));
    return f;
}

/// A picture read back from a target (RGBA8 rows top-down: row 0 of the read is output row 0, flipY off)
inline std::vector<uint8_t> readTarget(int w, int h) {
    std::vector<uint8_t> px(size_t(w) * size_t(h) * 4);
    glPixelStorei(GL_PACK_ALIGNMENT, 4);
    glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
    return px;
}

/// EditHistogramView.of: counts[3][256] (R, G, B) of RGBA8 pixels
inline std::array<std::array<int, 256>, 3> histogramOf(const std::vector<uint8_t>& rgba, int n) {
    std::array<std::array<int, 256>, 3> h{};
    for (int i = 0; i < n; i++) {
        h[0][rgba[size_t(i) * 4]]++;
        h[1][rgba[size_t(i) * 4 + 1]]++;
        h[2][rgba[size_t(i) * 4 + 2]]++;
    }
    return h;
}

} // namespace scam_edit
