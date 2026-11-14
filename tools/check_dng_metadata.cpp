// P618: the metadata of our DNGs (dngCreator.cpp through tiny_dng_writer): the shot's time with its milliseconds and zone, the
// exposure and the lens in an Exif IFD (tag 34665) a raw converter and Android's ExifInterface read, LensInfo and the 4:3
// DefaultCrop in IFD 0, every IFD sorted with each tag once (a description set twice keeps the last), ExifVersion out of IFD 0,
// no PhotonCamera string - and the image data and every colour / level tag byte for byte as without these tags.
// Usage: check-dng-metadata [out.dng] (the DNG of the full case is written there, e.g. for exiftool).
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>
#include <vector>
#include "../app/src/main/cpp/dngCreator.cpp"

namespace {

struct Tag { unsigned type = 0, count = 0, offset = 0; bool inline_ = true; std::vector<unsigned char> data; };

unsigned le(const unsigned char *p, int n) {
    unsigned v = 0;
    for (int i = 0; i < n; i++) v |= unsigned(p[i]) << (8 * i);
    return v;
}

int failures = 0, checks = 0;

void expect(bool ok, const std::string &what) {
    ++checks;
    if (!ok) {
        std::printf("FAIL %s\n", what.c_str());
        ++failures;
    }
}

/** The tags of the IFD at `at`; `sorted` says whether they ascend strictly (sorted, each tag once). */
std::map<unsigned, Tag> ifd(const unsigned char *b, size_t size, unsigned at, bool *sorted) {
    static const int typeSize[] = {0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4};
    std::map<unsigned, Tag> tags;
    *sorted = true;
    if (at + 2 > size) { expect(false, "IFD past the end"); return tags; }
    const unsigned count = le(b + at, 2);
    unsigned last = 0;
    for (unsigned i = 0; i < count; i++) {
        const unsigned char *e = b + at + 2 + 12 * i;
        if (e + 12 > b + size) { expect(false, "IFD entry past the end"); return tags; }
        const unsigned tag = le(e, 2);
        if (i > 0 && tag <= last) *sorted = false;
        last = tag;
        Tag t;
        t.type = le(e + 2, 2);
        t.count = le(e + 4, 4);
        const size_t len = size_t(t.count) * (t.type < 14 ? typeSize[t.type] : 1);
        t.inline_ = len <= 4;
        t.offset = t.inline_ ? 0 : le(e + 8, 4);
        const unsigned char *v = t.inline_ ? e + 8 : b + t.offset;
        if (v + len > b + size) { expect(false, "tag " + std::to_string(tag) + " past the end"); continue; }
        t.data.assign(v, v + len);
        tags[tag] = t;
    }
    return tags;
}

std::string ascii(const Tag &t) { return t.data.empty() ? "" : std::string(reinterpret_cast<const char *>(t.data.data())); }

unsigned rationalNum(const Tag &t, int i) { return le(t.data.data() + 8 * i, 4); }
unsigned rationalDen(const Tag &t, int i) { return le(t.data.data() + 8 * i + 4, 4); }

const int W = 64, H = 50;

/** A DNG as DngCreator.setParameters builds it (the P618 tags with `full`); the caller frees it. */
unsigned char *build(bool full, const std::vector<unsigned short> &raw, size_t &size) {
    DngCreator c;
    c.setMosaicBlock(1);
    c.setDescription("a first description, replaced below (its bytes stay in the data, unreferenced)");
    c.setSoftware("SCAMERA 0.98 (31200) 0123abcd");
    c.setDateTime("2026:11:11 14:03:07");
    if (full) {
        c.setDescription("SCAMERA\n Frames=8\n Camera=5\n RAW=merged burst\n Route=SHDR");
        const unsigned int exposure[2] = {1, 852}, fnumber[2] = {169, 100}, focal[2] = {912, 100}, apex[2] = {1514, 1000};
        const unsigned int spec[8] = {912, 100, 912, 100, 169, 100, 169, 100};
        const unsigned short iso = 65535, sensitivity = 3, f35 = 35;
        const unsigned int isoSpeed = 102400;
        c.setTag(1, 33434, tinydngwriter::TIFF_RATIONAL, 1, exposure);
        c.setTag(1, 33437, tinydngwriter::TIFF_RATIONAL, 1, fnumber);
        c.setTag(1, 34855, tinydngwriter::TIFF_SHORT, 1, &iso);
        c.setTag(1, 34864, tinydngwriter::TIFF_SHORT, 1, &sensitivity);
        c.setTag(1, 34867, tinydngwriter::TIFF_LONG, 1, &isoSpeed);
        c.setTag(1, 36867, tinydngwriter::TIFF_ASCII, 20, "2026:11:11 14:03:07");
        c.setTag(1, 36868, tinydngwriter::TIFF_ASCII, 20, "2026:11:11 14:03:07");
        for (unsigned short t : {36880, 36881, 36882}) c.setTag(1, t, tinydngwriter::TIFF_ASCII, 7, "+03:00");
        for (unsigned short t : {37520, 37521, 37522}) c.setTag(1, t, tinydngwriter::TIFF_ASCII, 4, "042");
        c.setTag(1, 37378, tinydngwriter::TIFF_RATIONAL, 1, apex);
        c.setTag(1, 37386, tinydngwriter::TIFF_RATIONAL, 1, focal);
        c.setTag(1, 41989, tinydngwriter::TIFF_SHORT, 1, &f35);
        c.setTag(1, 42034, tinydngwriter::TIFF_RATIONAL, 4, spec);
        c.setTag(1, 42035, tinydngwriter::TIFF_ASCII, 5, "vivo");
        const std::string lens = "vivo X200 Ultra back camera 9.12mm f/1.69";
        c.setTag(1, 42036, tinydngwriter::TIFF_ASCII, unsigned(lens.size() + 1), lens.c_str());
        c.setTag(0, 50736, tinydngwriter::TIFF_RATIONAL, 4, spec);
        c.setDefaultCrop(0, 1, W, 48);
    }
    c.setMake("vivo");
    c.setModel("vivo X200 Ultra");
    c.setUniqueCameraModel("V2454A-vivo-vivo");
    if (!full) {
        // the writer before P618: the exposure and lens in IFD 0 (ISO 102400 cast to a short: 36864)
        c.setIso(36864);
        c.setExposureTime(1.0 / 852);
        c.setFocalLength(9.12);
        c.setAperture(1.69);
    }
    const unsigned short black[4] = {64, 64, 64, 64};
    c.setBlackLevel(black);
    c.setWhiteLevel(1023);
    const double cm[9] = {0.9, -0.3, -0.1, -0.4, 1.2, 0.2, -0.1, 0.2, 0.6};
    const double fm[9] = {0.6, 0.2, 0.15, 0.25, 0.7, 0.05, 0.02, 0.1, 0.7};
    const double neutral[3] = {0.5, 1.0, 0.6};
    c.setCalibrationIlluminant1(21);
    c.setCalibrationIlluminant2(21);
    c.setColorMatrix1(cm);
    c.setForwardMatrix1(fm);
    c.setAsShotNeutral(neutral);
    c.setBaselineExposure(1.25);
    const double noise[6] = {1e-4, 2e-6, 1e-4, 2e-6, 1e-4, 2e-6};
    c.setNoiseProfile(noise, 6);
    c.setCFAPattern(3);
    c.setOrientation(6);
    const float gain[4] = {1.1f, 1.2f, 1.3f, 1.4f};
    c.setGainMap(gain, 0, 0, W, H, 1, 1);
    std::vector<unsigned short> copy = raw;
    return static_cast<unsigned char *>(c.createDng(copy.data(), W, H, size));
}

void checkAligned(const std::map<unsigned, Tag> &tags, const char *where) {
    for (const auto &kv : tags)
        if (!kv.second.inline_) expect(kv.second.offset % 2 == 0, std::string(where) + " tag " + std::to_string(kv.first) + " value word-aligned");
}

}  // namespace

int main(int argc, char **argv) {
    std::vector<unsigned short> raw(size_t(W) * H);
    for (size_t i = 0; i < raw.size(); i++) raw[i] = static_cast<unsigned short>(64 + (i * 37) % 900);

    size_t size = 0, plainSize = 0;
    unsigned char *dng = build(true, raw, size);
    unsigned char *plain = build(false, raw, plainSize);
    if (!dng || !plain) { std::printf("FAIL no DNG\n"); return 1; }
    if (argc > 1) {
        FILE *f = std::fopen(argv[1], "wb");
        if (f) { std::fwrite(dng, 1, size, f); std::fclose(f); }
    }
    expect(dng[0] == 'I' && dng[1] == 'I' && le(dng + 2, 2) == 42, "little-endian TIFF header");

    bool sorted = false;
    std::map<unsigned, Tag> t0 = ifd(dng, size, le(dng + 4, 4), &sorted);
    expect(sorted, "IFD 0 sorted, each tag once");
    checkAligned(t0, "IFD 0");
    expect(ascii(t0[270]) == "SCAMERA\n Frames=8\n Camera=5\n RAW=merged burst\n Route=SHDR", "ImageDescription: the last one set");
    expect(ascii(t0[305]) == "SCAMERA 0.98 (31200) 0123abcd", "Software");
    expect(ascii(t0[271]) == "vivo" && ascii(t0[272]) == "vivo X200 Ultra", "Make / Model");
    expect(ascii(t0[50708]) == "V2454A-vivo-vivo", "UniqueCameraModel (P696: the DCP key)");
    expect(ascii(t0[306]) == "2026:11:11 14:03:07", "DateTime");
    expect(t0.count(36864) == 0, "no ExifVersion in IFD 0");
    expect(t0.count(296) && le(t0[296].data.data(), 2) == 2, "ResolutionUnit 2 (inch, as before)");
    for (unsigned tag : {33434u, 33437u, 34855u, 37386u})
        expect(t0.count(tag) == 0, "tag " + std::to_string(tag) + " in the Exif IFD only (exiftool -validate)");
    expect(t0.count(50736) && t0[50736].count == 4 && rationalNum(t0[50736], 0) == 912 && rationalDen(t0[50736], 2) == 100,
           "LensInfo");
    expect(t0.count(50719) && le(t0[50719].data.data(), 4) == 0 && le(t0[50719].data.data() + 4, 4) == 1, "DefaultCropOrigin");
    expect(t0.count(50720) && le(t0[50720].data.data(), 4) == unsigned(W) && le(t0[50720].data.data() + 4, 4) == 48,
           "DefaultCropSize");

    // the Exif IFD
    expect(t0.count(34665) && t0[34665].type == 4 && t0[34665].count == 1, "ExifIFD pointer");
    const unsigned exifAt = t0.count(34665) ? le(t0[34665].data.data(), 4) : 0;
    expect(exifAt % 2 == 0 && exifAt > 8 && exifAt < size, "Exif IFD word-aligned inside the file");
    std::map<unsigned, Tag> ex = ifd(dng, size, exifAt, &sorted);
    expect(sorted, "Exif IFD sorted, each tag once");
    checkAligned(ex, "Exif IFD");
    const unsigned exifCount = le(dng + exifAt, 2);
    expect(le(dng + exifAt + 2 + 12 * exifCount, 4) == 0, "Exif IFD has no next IFD");
    expect(ex.count(36864) && ex[36864].type == 7 && std::string(ex[36864].data.begin(), ex[36864].data.end()) == "0231",
           "ExifVersion 0231 in the Exif IFD");
    expect(ascii(ex[36867]) == "2026:11:11 14:03:07" && ascii(ex[36868]) == "2026:11:11 14:03:07", "DateTimeOriginal / Digitized");
    expect(ascii(ex[36880]) == "+03:00" && ascii(ex[36881]) == "+03:00" && ascii(ex[36882]) == "+03:00", "OffsetTime*");
    expect(ascii(ex[37520]) == "042" && ascii(ex[37521]) == "042" && ascii(ex[37522]) == "042" && ex[37521].inline_,
           "SubSecTime* (4 bytes, in the entry)");
    expect(ex.count(33434) && rationalNum(ex[33434], 0) == 1 && rationalDen(ex[33434], 0) == 852, "ExposureTime 1/852");
    expect(ex.count(33437) && rationalNum(ex[33437], 0) == 169 && rationalDen(ex[33437], 0) == 100, "FNumber 1.69");
    expect(ex.count(34855) && le(ex[34855].data.data(), 2) == 65535 && ex[34855].type == 3, "PhotographicSensitivity 65535");
    expect(ex.count(34864) && le(ex[34864].data.data(), 2) == 3, "SensitivityType 3");
    expect(ex.count(34867) && le(ex[34867].data.data(), 4) == 102400 && ex[34867].type == 4, "ISOSpeed 102400");
    expect(ex.count(37378) && rationalNum(ex[37378], 0) == 1514, "ApertureValue (APEX)");
    expect(ex.count(37386) && rationalNum(ex[37386], 0) == 912, "FocalLength");
    expect(ex.count(41989) && le(ex[41989].data.data(), 2) == 35, "FocalLengthIn35mmFilm");
    expect(ex.count(42034) && ex[42034].count == 4 && rationalNum(ex[42034], 3) == 169, "LensSpecification");
    expect(ascii(ex[42035]) == "vivo" && ascii(ex[42036]) == "vivo X200 Ultra back camera 9.12mm f/1.69", "LensMake / LensModel");

    // nothing of PhotonCamera in the file
    const std::string bytes(reinterpret_cast<const char *>(dng), size);
    for (const char *old : {"PhotonCamera", "Photon Camera", "particlesdevs", "photoncamera"})
        expect(bytes.find(old) == std::string::npos, std::string("no \"") + old + "\"");

    // the image and every colour / level / noise / shading tag as in the file without the P618 tags
    std::map<unsigned, Tag> p0 = ifd(plain, plainSize, le(plain + 4, 4), &sorted);
    expect(sorted, "plain IFD 0 sorted");
    for (unsigned tag : {254u, 256u, 257u, 258u, 259u, 262u, 274u, 277u, 278u, 279u, 284u, 33421u, 33422u, 50706u, 50713u, 50714u,
                         50717u, 50721u, 50728u, 50730u, 50778u, 50829u, 50964u, 51009u, 51041u}) {
        expect(t0.count(tag) && p0.count(tag) && t0[tag].type == p0[tag].type && t0[tag].count == p0[tag].count
                       && t0[tag].data == p0[tag].data,
               "tag " + std::to_string(tag) + " unchanged");
    }
    const unsigned strip = t0.count(273) ? le(t0[273].data.data(), 4) : 0, plainStrip = p0.count(273) ? le(p0[273].data.data(), 4) : 0;
    expect(strip > 0 && strip % 2 == 0 && strip + raw.size() * 2 <= size
                   && std::memcmp(dng + strip, raw.data(), raw.size() * 2) == 0
                   && std::memcmp(plain + plainStrip, raw.data(), raw.size() * 2) == 0,
           "the image data as given");

    // the bare writer: ExifVersion alone in its Exif IFD; a crop outside the image is not written
    {
        DngCreator c;
        c.setDefaultCrop(10, 10, W, H);
        std::vector<unsigned short> copy = raw;
        size_t n = 0;
        unsigned char *d = static_cast<unsigned char *>(c.createDng(copy.data(), W, H, n));
        expect(d != nullptr, "bare DNG");
        if (d) {
            std::map<unsigned, Tag> b0 = ifd(d, n, le(d + 4, 4), &sorted);
            expect(b0.count(50719) == 0 && b0.count(50720) == 0, "a DefaultCrop outside the image is not written");
            std::map<unsigned, Tag> be = ifd(d, n, b0.count(34665) ? le(b0[34665].data.data(), 4) : 0, &sorted);
            expect(be.size() == 1 && be.count(36864), "bare Exif IFD: ExifVersion only");
            std::free(d);
        }
    }
    // a tag refused: a 3-byte value of IFD 0, an unknown type, an Exif tag after the IFD was laid out
    {
        DngCreator c;
        expect(!c.setTag(0, 700, tinydngwriter::TIFF_ASCII, 3, "ab"), "3-byte IFD 0 value refused");
        expect(!c.setTag(1, 700, 99, 1, "a"), "unknown type refused");
        std::vector<unsigned short> copy = raw;
        size_t n = 0;
        unsigned char *d = static_cast<unsigned char *>(c.createDng(copy.data(), W, H, n));
        expect(!c.setTag(1, 42036, tinydngwriter::TIFF_ASCII, 2, "x"), "Exif tag after the layout refused");
        std::free(d);
    }

    std::free(dng);
    std::free(plain);
    if (failures) std::printf("DNG metadata: %d failures of %d checks\n", failures, checks);
    else std::printf("DNG metadata: ok (%d checks)\n", checks);
    return failures ? 1 : 0;
}
