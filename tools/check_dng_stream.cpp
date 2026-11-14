// P681: the DNG the app streams (DngFile::layoutDng: the header and metadata, the strip in place - the mosaic itself or the
// lossless JPEG - then the IFD) is byte for byte the file the in-memory writer built before (DngFile::createDng, WriteToMemory):
// 16-bit uncompressed and lossless JPEG (LJ92), with and without the lens-shading GainMap opcodes, the Exif IFD and the
// DefaultCrop, Bayer / Quad / Tetra blocks, odd strip lengths; the parts add up to the file and the strip is the caller's mosaic
// (no copy) when uncompressed. tiny_dng_writer's own WriteToMemory / WriteToFile give the same bytes for a referenced strip.
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>
#include "../app/src/main/cpp/dngCreator.cpp"

namespace {
int failures = 0, cases = 0;

void expect(bool ok, const std::string &what) {
    if (!ok) {
        ++failures;
        std::printf("FAIL: %s\n", what.c_str());
    }
}

struct Shape {
    int w, h, block;
    bool lossless, gainMap, exif, crop, unequalBlack;
};

std::vector<unsigned short> mosaic(int w, int h, unsigned seed) {
    std::vector<unsigned short> raw(size_t(w) * h);
    unsigned s = seed * 2654435761u + 12345u;
    for (size_t i = 0; i < raw.size(); i++) {
        s = s * 1664525u + 1013904223u;
        // a smooth ramp with noise: LJ92 has something to predict, every value in 0 .. 65535 can occur
        raw[i] = static_cast<unsigned short>((((i % size_t(w)) * 37u + (i / size_t(w)) * 11u) & 0x3FFF) + ((s >> 20) & 0xFFF)
                                             + ((i % 4093 == 0) ? 0xC000 : 0));
    }
    return raw;
}

/** The setters of DngCreator.setParameters in its order (a full shot when every option is on). */
void configure(DngCreator &c, const Shape &s) {
    c.setMosaicBlock(s.block);
    c.setDescription("SCAMERA merged RAW (check)");
    c.setSoftware("SCAMERA 0.98 (check)");
    c.setDateTime("2026:11:14 12:34:56");
    if (s.exif) {
        const unsigned int exposure[2] = {1, 852}, fnumber[2] = {169, 100}, focal[2] = {912, 100};
        const unsigned short iso = 400;
        c.setTag(1, 33434, tinydngwriter::TIFF_RATIONAL, 1, exposure);
        c.setTag(1, 33437, tinydngwriter::TIFF_RATIONAL, 1, fnumber);
        c.setTag(1, 34855, tinydngwriter::TIFF_SHORT, 1, &iso);
        c.setTag(1, 37386, tinydngwriter::TIFF_RATIONAL, 1, focal);
        c.setTag(1, 36867, tinydngwriter::TIFF_ASCII, 20, "2026:11:14 12:34:56");
        c.setTag(1, 37521, tinydngwriter::TIFF_ASCII, 4, "042");
        c.setTag(1, 42035, tinydngwriter::TIFF_ASCII, 5, "vivo"); // odd: a pad byte follows
    }
    if (s.crop) c.setDefaultCrop(2, 4, s.w - 4, s.h - 8);
    const double noise[6] = {1e-4, 2e-6, 1.1e-4, 2.5e-6, 0.9e-4, 1.5e-6};
    c.setNoiseProfile(noise, 6);
    c.setMake("vivo");
    c.setModel("vivo X200 Ultra");
    c.setUniqueCameraModel("vivo V2454A camera 5");
    const unsigned short black[4] = {64, static_cast<unsigned short>(s.unequalBlack ? 65 : 64),
                                     static_cast<unsigned short>(s.unequalBlack ? 66 : 64), 64};
    c.setBlackLevel(black);
    c.setWhiteLevel(65535);
    c.setBaselineExposure(0.75);
    const double cm[9] = {0.9, -0.3, -0.1, -0.4, 1.2, 0.2, -0.1, 0.2, 0.6};
    const double fm[9] = {0.6, 0.2, 0.15, 0.25, 0.7, 0.05, 0.02, 0.1, 0.7};
    const double neutral[3] = {0.5, 1.0, 0.6};
    c.setCalibrationIlluminant1(21);
    c.setCalibrationIlluminant2(17);
    c.setColorMatrix1(cm);
    c.setColorMatrix2(cm);
    c.setForwardMatrix1(fm);
    c.setAsShotNeutral(neutral);
    c.setCFAPattern(3);
    c.setOrientation(6);
    c.setCompression(s.lossless);
    if (s.gainMap) {
        const int mapW = 5, mapH = 4;
        std::vector<float> gain(size_t(4) * mapW * mapH);
        for (size_t i = 0; i < gain.size(); i++) gain[i] = 1.0f + 0.01f * float(i % 37);
        c.setGainMap(gain.data(), 0, 0, s.w, s.h, mapW, mapH);
    }
}

std::string name(const Shape &s) {
    char b[160];
    std::snprintf(b, sizeof b, "%dx%d block %d %s%s%s%s%s", s.w, s.h, s.block, s.lossless ? "LJ92" : "16-bit",
                  s.gainMap ? " +GainMap" : "", s.exif ? " +Exif" : "", s.crop ? " +crop" : "", s.unequalBlack ? " +black4" : "");
    return b;
}

void check(const Shape &s) {
    ++cases;
    const std::vector<unsigned short> raw = mosaic(s.w, s.h, unsigned(cases));
    // the reference: the in-memory file of the writer before P681
    DngCreator before;
    configure(before, s);
    std::vector<unsigned short> copy = raw;
    size_t size = 0;
    auto *file = static_cast<unsigned char *>(before.createDng(copy.data(), s.w, s.h, size));
    if (file == nullptr) {
        expect(false, name(s) + ": no reference file");
        return;
    }
    // the stream: three parts, the strip in place
    DngCreator streamed;
    configure(streamed, s);
    std::vector<unsigned short> samples = raw;
    const bool laid = streamed.layoutDng(samples.data(), s.w, s.h);
    expect(laid, name(s) + ": layout");
    if (laid) {
        std::string out = streamed.head();
        out.append(reinterpret_cast<const char *>(streamed.strip()), streamed.stripBytes());
        out += streamed.tail();
        const bool same = out.size() == size && std::memcmp(out.data(), file, size) == 0;
        expect(same, name(s) + ": streamed bytes == in-memory bytes (" + std::to_string(out.size()) + " vs " +
                             std::to_string(size) + ")");
        expect(streamed.lossless() == s.lossless, name(s) + ": compression as asked");
        if (!s.lossless) {
            expect(streamed.strip() == reinterpret_cast<const unsigned char *>(samples.data()),
                   name(s) + ": the uncompressed strip is the caller's mosaic (no copy)");
            expect(streamed.stripBytes() == size_t(s.w) * s.h * 2, name(s) + ": strip bytes");
        }
        expect(std::memcmp(samples.data(), raw.data(), raw.size() * 2) == 0, name(s) + ": mosaic untouched");
        std::printf("%-58s %9zu B: head %zu, strip %zu, tail %zu %s\n", name(s).c_str(), size, streamed.head().size(),
                    streamed.stripBytes(), streamed.tail().size(), same ? "same" : "DIFFERENT");
    }
    free(file);
}

/** tiny_dng_writer alone: a referenced strip gives WriteToMemory's and WriteToFile's bytes of a copied one (and Layout's). */
void writerAlone(int w, int h, bool bigEndian, const std::string &dir) {
    ++cases;
    std::vector<unsigned short> px(size_t(w) * h);
    for (size_t i = 0; i < px.size(); i++) px[i] = static_cast<unsigned short>((i * 2654435761u >> 9) & 0xFFFF);
    auto make = [&](bool ref) {
        auto *img = new tinydngwriter::DNGImage();
        img->SetBigEndian(bigEndian);
        img->SetSubfileType(false, false, false);
        img->SetSamplesPerPixel(1);
        img->SetPhotometric(tinydngwriter::PHOTOMETRIC_CFA);
        unsigned short bps = 16;
        img->SetBitsPerSample(1, &bps);
        img->SetImageWidth(w);
        img->SetImageLength(h);
        img->SetRowsPerStrip(h);
        img->SetUniqueCameraModel("SCAMERA check!");
        const auto *bytes = reinterpret_cast<const unsigned char *>(px.data());
        if (ref) img->SetImageDataRef(bytes, px.size() * 2);
        else img->SetImageData(bytes, px.size() * 2);
        return img;
    };
    tinydngwriter::DNGImage *a = make(false), *b = make(true);
    tinydngwriter::DNGWriter wa(bigEndian), wb(bigEndian);
    wa.AddImage(a);
    wb.AddImage(b);
    size_t sa = 0, sb = 0;
    std::string err;
    void *ma = wa.WriteToMemory(&sa, &err), *mb = wb.WriteToMemory(&sb, &err);
    const std::string label = std::to_string(w) + "x" + std::to_string(h) + (bigEndian ? " big" : " little");
    expect(ma && mb && sa == sb && std::memcmp(ma, mb, sa) == 0, label + ": WriteToMemory, referenced strip == copied strip");
    const std::string path = dir + "/dng-stream-ref.dng";
    if (ma && wb.WriteToFile(path.c_str(), &err)) {
        std::ifstream in(path, std::ios::binary);
        std::vector<char> f((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
        expect(f.size() == sa && std::memcmp(f.data(), ma, sa) == 0, label + ": WriteToFile of the referenced strip");
        std::remove(path.c_str());
    } else {
        expect(false, label + ": WriteToFile");
    }
    std::string head, tail;
    const unsigned char *strip = nullptr;
    size_t stripBytes = 0;
    const bool laid = wb.Layout(&head, &strip, &stripBytes, &tail, &err);
    if (bigEndian) {
        expect(!laid, label + ": Layout refuses a strip to byte-swap");
    } else {
        std::string out = laid ? head : std::string();
        if (laid) {
            out.append(reinterpret_cast<const char *>(strip), stripBytes);
            out += tail;
        }
        expect(laid && out.size() == sa && std::memcmp(out.data(), ma, sa) == 0, label + ": Layout == WriteToMemory");
    }
    // nothing may be added after a referenced strip
    a->SetSoftware("late");
    b->SetSoftware("a value after the strip");
    expect(!b->StripRefIntact() && !wb.Layout(&head, &strip, &stripBytes, &tail, &err), label + ": data after the strip refused");
    free(ma);
    free(mb);
    delete a;
    delete b;
}
}  // namespace

int main(int argc, char **argv) {
    const std::string dir = argc > 1 ? argv[1] : "/tmp";
    for (bool lossless : {false, true})
        for (bool extras : {false, true}) {
            check({64, 48, 1, lossless, extras, extras, extras, false});
            check({130, 66, 1, lossless, !extras, extras, false, true});
            check({96, 64, 2, lossless, extras, extras, extras, true});   // Quad
            check({128, 64, 4, lossless, extras, !extras, extras, false}); // Tetra
        }
    check({4080, 3072, 1, false, true, true, true, false}); // 12.5 MP
    check({4080, 3072, 1, true, true, true, false, false});
    check({62, 42, 1, true, false, false, false, false});   // an LJ92 strip of odd length is likely across these
    check({66, 46, 1, true, true, false, false, false});
    check({70, 50, 1, true, false, true, false, false});
    writerAlone(64, 48, false, dir);
    writerAlone(97, 41, false, dir);
    writerAlone(130, 66, true, dir);
    std::printf(failures ? "DNG stream: FAILED (%d of %d cases)\n" : "DNG stream: OK (%d failures, %d cases)\n", failures, cases);
    return failures ? 1 : 0;
}
