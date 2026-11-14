//
// Created by eszdman on 02.06.2025.
//
// SCAMERA DNG writer (library dngCreator): collects a shot's raw metadata from processing/DngCreator.java and lays out a
// single-IFD CFA DNG 1.3 through tiny_dng_writer - 16 bits per sample, uncompressed or lossless JPEG (LJ92), with the Exif
// IFD, the lens-shading GainMap (opcode list 2) and the Quad / Tetra colour blocks (P318).
//
// The bytes of a file depend on the order in which the DNGImage setters run (every value lands in the data area in that
// order): DngFile::build keeps the order the writer always had - tools/check_dng_metadata.cpp and check_dng_mosaic_cfa.cpp
// read the result tag by tag.
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <memory>
#include <string>
#include <vector>
#define TINY_DNG_WRITER_IMPLEMENTATION
#include "deps/tiny_dng_writer.h"

#define DNG_INFO(...) __android_log_print(ANDROID_LOG_DEBUG, "ScamDng", __VA_ARGS__)
#define DNG_FAIL(...) __android_log_print(ANDROID_LOG_ERROR, "ScamDng", __VA_ARGS__)

namespace scamdng {

namespace tdw = tinydngwriter;

/** The 3x3 matrices a file may carry, in the order their tags are written. */
enum Matrix { kColour1, kColour2, kForward1, kForward2, kCalibration1, kCalibration2, kMatrixCount };

/** Colour (0 R, 1 G, 2 B) of the 2x2 sites (y * 2 + x) for Camera2's CFA arrangement 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR. */
constexpr unsigned char kSiteColour[4][4] = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
/**
 * The 2x2 site of each lens-shading channel (Camera2 order R, G on the even row, G on the odd row, B) per arrangement:
 * the GainMap of channel k is anchored on that site.
 */
constexpr unsigned char kShadingSite[4][4] = {{0, 1, 2, 3}, {1, 0, 3, 2}, {2, 0, 3, 1}, {3, 1, 2, 0}};

/** A matrix worth writing (P280): every entry finite and not all of them zero (zero = never set by the camera). */
bool writable(const std::array<double, 9> &m) {
    double magnitude = 0;
    for (double v : m) {
        if (!std::isfinite(v)) return false;
        magnitude += std::fabs(v);
    }
    return magnitude > 1e-9;
}

class DngFile {
public:
    DngFile() : image_(new tdw::DNGImage()) {
        image_->SetSubfileType(false, false, false);
        image_->SetBigEndian(false);
        image_->SetSamplesPerPixel(1);
        image_->SetPhotometric(tdw::PHOTOMETRIC_CFA);
        image_->SetPlanarConfig(tdw::PLANARCONFIG_CONTIG);
        image_->SetDNGVersion(1, 3, 0, 0);
        // P618: ExifVersion 2.31 (the version with OffsetTime*) in the Exif IFD, where it belongs
        image_->SetTag(1, tdw::EXIFTAG_EXIFVERSION, tdw::TIFF_UNDEFINED, 4, "0231");
    }

    // --- what goes straight to the image, in the caller's order ---
    void setUniqueCameraModel(const std::string &s) { image_->SetUniqueCameraModel(s); }
    void setDescription(const std::string &s) { image_->SetImageDescription(s); }
    void setSoftware(const std::string &s) { image_->SetSoftware(s); }
    void setMake(const std::string &s) { image_->SetMake(s); }
    void setModel(const std::string &s) { image_->SetModel(s); }
    void setDateTime(const std::string &s) { image_->SetDateTime(s); }
    void setIso(unsigned short v) { image_->SetIso(v); }
    void setExposureTime(double seconds) { image_->SetExposureTime(seconds); }
    void setAperture(double fNumber) { image_->SetAperture(fNumber); }
    void setFocalLength(double mm) { image_->SetFocalLength(mm); }
    void setNoiseProfile(const double *pairs, int count) { image_->SetNoiseProfile(static_cast<unsigned int>(count / 2), pairs); }

    /** P618: one more tag of IFD 0 (ifd 0) or the Exif IFD (ifd 1), values in host order; false (logged) when refused. */
    bool setTag(int ifd, unsigned short tag, unsigned short type, unsigned int count, const void *values) {
        if (image_->SetTag(ifd, tag, type, count, values)) return true;
        DNG_FAIL("tag %u of IFD %d (type %u x %u) refused: %s", tag, ifd, type, count, image_->Error().c_str());
        return false;
    }

    // --- what the layout uses when the file is built ---
    void setOrientation(int v) { orientation_ = v; }
    void setWhiteLevel(double v) { white_ = v; }
    void setBlackLevel(const unsigned short *v) { std::copy(v, v + 4, black_.begin()); }
    void setCompression(bool lossless) { lossless_ = lossless; }
    void setBaselineExposure(double ev) { baselineEv_ = ev; }
    void setCalibrationIlluminant1(unsigned short v) { illuminant_[0] = v; }
    void setCalibrationIlluminant2(unsigned short v) { illuminant_[1] = v; }
    void setColorMatrix1(const double *m) { keep(kColour1, m); }
    void setColorMatrix2(const double *m) { keep(kColour2, m); }
    void setForwardMatrix1(const double *m) { keep(kForward1, m); }
    void setForwardMatrix2(const double *m) { keep(kForward2, m); }
    void setCameraCalibration1(const double *m) { keep(kCalibration1, m); }
    void setCameraCalibration2(const double *m) { keep(kCalibration2, m); }
    void setAsShotNeutral(const double *rgb) {
        std::copy(rgb, rgb + 3, neutral_.begin());
        neutralSet_ = true;
    }

    /** Camera2's CFA arrangement; an unknown value is written as RGGB but places no GainMap. */
    void setCFAPattern(int arrangement) {
        arrangement_ = arrangement;
        const int row = arrangement >= 0 && arrangement < 4 ? arrangement : 0;
        std::copy(kSiteColour[row], kSiteColour[row] + 4, siteColour_.begin());
    }

    /** P318: colour block of the stored mosaic (2 Quad, 4 Tetra, else Bayer); before setGainMap, which places its maps by it. */
    void setMosaicBlock(int block) {
        block_ = block == 2 || block == 4 ? block : 1;
        if (gainMapPlaced_ && block_ > 1) DNG_FAIL("block %d after the GainMap: the maps stay on the Bayer grid", block_);
    }
    int mosaicBlock() const { return block_; }

    /** P618: DefaultCropOrigin / Size in the stored image's pixels; a non-positive size (or a negative origin) clears it. */
    void setDefaultCrop(int x, int y, int w, int h) {
        if (x >= 0 && y >= 0 && w > 0 && h > 0) crop_ = {unsigned(x), unsigned(y), unsigned(w), unsigned(h)};
        else crop_ = {0, 0, 0, 0};
    }

    /**
     * The lens-shading map, Camera2 layout (w x h points, 4 interleaved channels), over the stored area (xmin, ymin) ..
     * (xmax, ymax): one GainMap per channel on its 2x2 site, or per channel and site of a b x b colour block (pitch 2b).
     */
    void setGainMap(const float *interleaved, int xmin, int ymin, int xmax, int ymax, int w, int h) {
        const size_t points = static_cast<size_t>(w) * static_cast<size_t>(h);
        const bool known = arrangement_ >= 0 && arrangement_ < 4;
        std::vector<float> plane(points);
        std::vector<tdw::GainMap> maps;
        for (int channel = 0; channel < 4; ++channel) {
            for (size_t p = 0; p < points; ++p) plane[p] = interleaved[4 * p + channel];
            tdw::GainMap map(plane.data(), w, h, xmin, ymin, xmax, ymax);
            if (known) {
                const unsigned site = kShadingSite[arrangement_][channel];
                map.top = site >> 1;
                map.left = site & 1;
            }
            // P205: Bottom / Right are exclusive bounds of the area
            map.bottom = static_cast<unsigned>(ymax - ymin);
            map.right = static_cast<unsigned>(xmax - xmin);
            for (int sy = 0; sy < block_; ++sy)
                for (int sx = 0; sx < block_; ++sx) {
                    tdw::GainMap onSite = map;
                    if (block_ > 1) {
                        onSite.top = map.top * block_ + sy;
                        onSite.left = map.left * block_ + sx;
                        onSite.row_pitch = onSite.col_pitch = 2 * block_;
                    }
                    maps.push_back(onSite);
                }
        }
        image_->SetGainMap(maps);
        gainMapPlaced_ = true;
    }

    ~DngFile() { free(encoded_); }

    /**
     * The whole file for a w x h 16-bit mosaic (malloc'ed; the caller frees it), nullptr on failure. P681: the reference
     * layout of the host checks (tools/check_dng_stream.cpp); the app streams the same bytes through layoutDng.
     */
    void *createDng(void *mosaic, int w, int h, size_t &size) {
        tdw::DNGWriter writer(false);
        if (!prepare(mosaic, w, h, false)) return nullptr;
        writer.AddImage(image_.get());
        std::string error;
        size_t stripOffset = 0;
        void *file = writer.WriteToMemory(&size, &error, &stripOffset);
        if (!error.empty()) DNG_FAIL("layout failed: %s", error.c_str());
        return file;
    }

    /**
     * P681: createDng's file without building it in memory - {@link #head} (the TIFF header and the metadata), the strip (the
     * caller's mosaic itself, or the lossless JPEG encoded here; alive until this writer is deleted), {@link #tail} (the IFD).
     * The same bytes in the same order; the mosaic must stay unchanged until they are written. False on failure (logged).
     */
    bool layoutDng(void *mosaic, int w, int h) {
        tdw::DNGWriter writer(false);
        if (!prepare(mosaic, w, h, true)) return false;
        writer.AddImage(image_.get());
        std::string error;
        if (!writer.Layout(&head_, &strip_, &stripBytes_, &tail_, &error)) {
            DNG_FAIL("layout failed: %s", error.c_str());
            return false;
        }
        return true;
    }

    const std::string &head() const { return head_; }
    const std::string &tail() const { return tail_; }
    const unsigned char *strip() const { return strip_; }
    size_t stripBytes() const { return stripBytes_; }
    bool lossless() const { return encoded_ != nullptr; }

private:
    /** Every tag of the file and its strip: copied into the data area, or (P681 `reference`) referenced in place. */
    bool prepare(void *mosaic, int w, int h, bool reference) {
        const unsigned short bitsPerSample = 16;
        const unsigned int width = static_cast<unsigned>(w), height = static_cast<unsigned>(h);
        image_->SetBitsPerSample(1, &bitsPerSample);
        image_->SetOrientation(static_cast<unsigned short>(orientation_));
        image_->SetImageWidth(width);
        image_->SetImageLength(height);
        image_->SetRowsPerStrip(height);
        const unsigned int active[4] = {0, 0, height, width};
        image_->SetActiveArea(active);
        image_->SetResolutionUnit(tdw::RESUNIT_INCH);
        image_->SetXResolution(72.f);
        image_->SetYResolution(72.f);

        // P318: the 2x2 order repeated over the colour blocks (2x2 Bayer, 4x4 Quad, 8x8 Tetra)
        const int period = 2 * block_;
        unsigned char cfa[64];
        for (int y = 0; y < period; ++y)
            for (int x = 0; x < period; ++x) cfa[y * period + x] = siteColour_[siteOf(x, y, block_)];
        image_->SetCFARepeatPatternDim(static_cast<unsigned short>(period), static_cast<unsigned short>(period));
        image_->SetCFAPattern(static_cast<unsigned int>(period * period), cfa);
        writeBlack(period);
        image_->SetWhiteLevelRational(1, &white_);

        writeColour();

        if (crop_[2] > 0 && crop_[3] > 0) {
            if (crop_[0] + crop_[2] <= width && crop_[1] + crop_[3] <= height) {
                setTag(0, tdw::TIFFTAG_DEFAULT_CROP_ORIGIN, tdw::TIFF_LONG, 2, &crop_[0]);
                setTag(0, tdw::TIFFTAG_DEFAULT_CROP_SIZE, tdw::TIFF_LONG, 2, &crop_[2]);
            } else {
                DNG_FAIL("default crop %u,%u %ux%u outside %ux%u: not written", crop_[0], crop_[1], crop_[2], crop_[3], width, height);
            }
        }
        if (!image_->WriteExifIFD()) DNG_FAIL("Exif IFD not written: %s", image_->Error().c_str());

        if (!(lossless_ && writeLosslessJpeg(mosaic, w, h, reference))) {
            image_->SetCompression(tdw::COMPRESSION_NONE);
            const auto *samples = static_cast<const unsigned char *>(mosaic);
            const size_t bytes = static_cast<size_t>(width) * height * 2;
            if (!(reference ? image_->SetImageDataRef(samples, bytes) : image_->SetImageData(samples, bytes))) {
                DNG_FAIL("strip of %zu bytes not set: %s", bytes, image_->Error().c_str());
                return false;
            }
        }
        return true;
    }

    /** The 2x2 site (y * 2 + x) whose colour the pixel (x, y) of a block-b mosaic has. */
    static int siteOf(int x, int y, int b) { return ((y / b) & 1) * 2 + ((x / b) & 1); }

    void keep(Matrix which, const double *m) {
        std::copy(m, m + 9, matrix_[which].begin());
        matrixSet_[which] = true;
    }

    bool usable(Matrix which) const { return matrixSet_[which] && writable(matrix_[which]); }

    /** BlackLevel by 2x2 site: one 2x2 cell when the four agree, else the whole colour-block period. */
    void writeBlack(int period) {
        const bool flat = black_[1] == black_[0] && black_[2] == black_[0] && black_[3] == black_[0];
        const int dim = flat ? 2 : period, b = dim / 2;
        std::vector<unsigned short> levels(static_cast<size_t>(dim) * dim);
        for (int y = 0; y < dim; ++y)
            for (int x = 0; x < dim; ++x) levels[static_cast<size_t>(y) * dim + x] = black_[siteOf(x, y, b)];
        image_->SetBlackLevelRepeatDim(static_cast<unsigned short>(dim), static_cast<unsigned short>(dim));
        image_->SetBlackLevel(static_cast<unsigned int>(levels.size()), levels.data());
    }

    /**
     * P280 (owner, 2026-10-19: «Наши равы не открываются в лайтруме»): only colour data a raw converter accepts. The second
     * profile only with a usable first one and a different second illuminant; forward and calibration matrices only with
     * their colour matrix; AsShotNeutral only with three positive channels.
     */
    void writeColour() {
        const bool first = usable(kColour1);
        const bool second = first && usable(kColour2) && illuminant_[1] != 0 && illuminant_[1] != illuminant_[0];
        if (first) image_->SetColorMatrix1(3, matrix_[kColour1].data());
        if (second) image_->SetColorMatrix2(3, matrix_[kColour2].data());
        if (first && usable(kForward1)) image_->SetForwardMatrix1(3, matrix_[kForward1].data());
        if (second && usable(kForward2)) image_->SetForwardMatrix2(3, matrix_[kForward2].data());
        if (first && usable(kCalibration1)) image_->SetCameraCalibration1(3, matrix_[kCalibration1].data());
        if (second && usable(kCalibration2)) image_->SetCameraCalibration2(3, matrix_[kCalibration2].data());
        if (neutralSet_ && neutral_[0] > 0 && neutral_[1] > 0 && neutral_[2] > 0) image_->SetAsShotNeutral(3, neutral_.data());
        if (baselineEv_ != 0.0) image_->SetBaselineExposure(baselineEv_);
        if (first) image_->SetCalibrationIlluminant1(illuminant_[0]);
        if (second) image_->SetCalibrationIlluminant2(illuminant_[1]);
    }

    /**
     * P24: DNG compression 7 - the mosaic's row pairs as one 2W x H/2 component (LibRaw decodes it bit-exact). Encoded
     * before the tag is set, so a failure leaves the caller to store the samples uncompressed.
     */
    bool writeLosslessJpeg(void *mosaic, int w, int h, bool reference) {
        if (w % 2 != 0 || h % 2 != 0) return false;
        uint8_t *encoded = nullptr;
        int encodedSize = 0;
        const int rc = tdw::detail::lj92_encode(static_cast<uint16_t *>(mosaic), w * 2, h / 2, 16, w * 2 * (h / 2), 0,
                                                nullptr, 0, &encoded, &encodedSize);
        bool stored = false;
        if (rc == tdw::detail::LJ92_ERROR_NONE && encoded != nullptr && encodedSize > 0) {
            image_->SetCompression(tdw::COMPRESSION_NEW_JPEG);
            // P681: a referenced strip is the encoder's buffer itself, kept until this writer goes (no copy in the data area)
            stored = reference ? image_->SetImageDataRef(encoded, static_cast<size_t>(encodedSize))
                               : image_->SetImageData(encoded, static_cast<size_t>(encodedSize));
            DNG_INFO("LJ92 %d x %d: %d bytes", w, h, encodedSize);
        } else {
            DNG_FAIL("LJ92 failed (%d): stored uncompressed", rc);
        }
        if (reference && stored) {
            free(encoded_);
            encoded_ = encoded;
        } else {
            free(encoded);
        }
        return stored;
    }

    std::unique_ptr<tdw::DNGImage> image_;
    int orientation_ = 0;
    double white_ = 1023.0;
    std::array<unsigned short, 4> black_{};
    bool lossless_ = false;
    double baselineEv_ = 0.0;
    unsigned short illuminant_[2] = {0, 0};
    std::array<std::array<double, 9>, kMatrixCount> matrix_{};
    bool matrixSet_[kMatrixCount] = {};
    std::array<double, 3> neutral_{};
    bool neutralSet_ = false;
    int arrangement_ = 0;
    std::array<unsigned char, 4> siteColour_ = {1, 0, 2, 1};
    int block_ = 1;
    bool gainMapPlaced_ = false;
    std::array<unsigned int, 4> crop_{};
    // P681: layoutDng's parts; encoded_ the lossless JPEG strip they reference (malloc'ed by lj92_encode)
    std::string head_, tail_;
    const unsigned char *strip_ = nullptr;
    size_t stripBytes_ = 0;
    uint8_t *encoded_ = nullptr;
};

}  // namespace scamdng

using DngCreator = scamdng::DngFile;

// --- JNI (processing/DngCreator.java, static natives on a handle) ---------------------------------------------------------

namespace {

// Field ids shared with DngCreator.java (NUMBER_*, VECTOR_*, TEXT_*).
enum NumberField { kOrientation, kWhite, kBaseline, kIlluminant1, kIlluminant2, kIso, kExposure, kAperture, kFocal, kCfa,
                   kBlock, kLossless };
enum VectorField { kVColour1, kVColour2, kVForward1, kVForward2, kVCalibration1, kVCalibration2, kVNeutral, kVNoise };
enum TextField { kUniqueModel, kDescription, kSoftware, kMake, kModel, kDateTime };
enum TagKind { kAscii, kShorts, kLongs, kRationals };

DngCreator *file(jlong handle) { return reinterpret_cast<DngCreator *>(handle); }

unsigned short asShort(double v) { return static_cast<unsigned short>(static_cast<jshort>(v)); }

template <class T, class Get>
std::vector<T> copyOut(JNIEnv *env, jsize n, Get get) {
    std::vector<T> out(static_cast<size_t>(std::max<jsize>(n, 0)));
    if (n > 0) get(env, n, out.data());
    return out;
}

/** The unsigned tag values of `values` (each within 0 .. max, a rational's denominators above 0); false when one is not. */
bool tagWords(const std::vector<jlong> &values, jlong max, bool rational, std::vector<unsigned int> &out) {
    for (size_t i = 0; i < values.size(); ++i) {
        const jlong v = values[i];
        if (v < 0 || v > max || (rational && (i & 1) && v == 0)) return false;
        out.push_back(static_cast<unsigned int>(v));
    }
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_scamera_app_processing_DngCreator_nOpen(JNIEnv *, jclass) {
    return reinterpret_cast<jlong>(new DngCreator());
}

JNIEXPORT void JNICALL Java_com_scamera_app_processing_DngCreator_nClose(JNIEnv *, jclass, jlong handle) {
    delete file(handle);
}

JNIEXPORT void JNICALL Java_com_scamera_app_processing_DngCreator_nNumber(JNIEnv *, jclass, jlong handle, jint field,
                                                                          jdouble v) {
    DngCreator *f = file(handle);
    if (f == nullptr) return;
    switch (field) {
        case kOrientation: f->setOrientation(static_cast<int>(v)); break;
        case kWhite: f->setWhiteLevel(v); break;
        case kBaseline: f->setBaselineExposure(v); break;
        case kIlluminant1: f->setCalibrationIlluminant1(asShort(v)); break;
        case kIlluminant2: f->setCalibrationIlluminant2(asShort(v)); break;
        case kIso: f->setIso(asShort(v)); break;
        case kExposure: f->setExposureTime(v); break;
        case kAperture: f->setAperture(v); break;
        case kFocal: f->setFocalLength(v); break;
        case kCfa: f->setCFAPattern(static_cast<int>(v)); break;
        case kBlock: f->setMosaicBlock(static_cast<int>(v)); break;
        case kLossless: f->setCompression(v != 0.0); break;
        default: DNG_FAIL("unknown number field %d", field);
    }
}

JNIEXPORT void JNICALL Java_com_scamera_app_processing_DngCreator_nVector(JNIEnv *env, jclass, jlong handle, jint field,
                                                                          jdoubleArray values) {
    DngCreator *f = file(handle);
    if (f == nullptr || values == nullptr) return;
    const std::vector<double> v = copyOut<double>(env, env->GetArrayLength(values), [values](JNIEnv *e, jsize n, double *to) {
        e->GetDoubleArrayRegion(values, 0, n, to);
    });
    const size_t need = field == kVNeutral ? 3 : field == kVNoise ? 0 : 9;
    if (v.size() < need || v.empty()) return;
    switch (field) {
        case kVColour1: f->setColorMatrix1(v.data()); break;
        case kVColour2: f->setColorMatrix2(v.data()); break;
        case kVForward1: f->setForwardMatrix1(v.data()); break;
        case kVForward2: f->setForwardMatrix2(v.data()); break;
        case kVCalibration1: f->setCameraCalibration1(v.data()); break;
        case kVCalibration2: f->setCameraCalibration2(v.data()); break;
        case kVNeutral: f->setAsShotNeutral(v.data()); break;
        case kVNoise: f->setNoiseProfile(v.data(), static_cast<int>(v.size())); break;
        default: DNG_FAIL("unknown vector field %d", field);
    }
}

JNIEXPORT void JNICALL Java_com_scamera_app_processing_DngCreator_nText(JNIEnv *env, jclass, jlong handle, jint field,
                                                                        jstring value) {
    DngCreator *f = file(handle);
    if (f == nullptr || value == nullptr) return;
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return;
    const std::string s(chars);
    env->ReleaseStringUTFChars(value, chars);
    switch (field) {
        case kUniqueModel: f->setUniqueCameraModel(s); break;
        case kDescription: f->setDescription(s); break;
        case kSoftware: f->setSoftware(s); break;
        case kMake: f->setMake(s); break;
        case kModel: f->setModel(s); break;
        case kDateTime: f->setDateTime(s); break;
        default: DNG_FAIL("unknown text field %d", field);
    }
}

JNIEXPORT void JNICALL Java_com_scamera_app_processing_DngCreator_nBlack(JNIEnv *env, jclass, jlong handle,
                                                                         jshortArray levels) {
    DngCreator *f = file(handle);
    if (f == nullptr || levels == nullptr || env->GetArrayLength(levels) < 4) return;
    jshort raw[4];
    env->GetShortArrayRegion(levels, 0, 4, raw);
    const unsigned short black[4] = {static_cast<unsigned short>(raw[0]), static_cast<unsigned short>(raw[1]),
                                     static_cast<unsigned short>(raw[2]), static_cast<unsigned short>(raw[3])};
    f->setBlackLevel(black);
}

JNIEXPORT void JNICALL Java_com_scamera_app_processing_DngCreator_nGainMap(JNIEnv *env, jclass, jlong handle,
                                                                           jfloatArray map, jint xmin, jint ymin,
                                                                           jint xmax, jint ymax, jint w, jint h) {
    DngCreator *f = file(handle);
    if (f == nullptr || map == nullptr) return;
    const jsize n = env->GetArrayLength(map);
    if (w <= 0 || h <= 0 || static_cast<long long>(n) < 4LL * w * h) {
        DNG_FAIL("GainMap of %d values for %d x %d points: not written", n, w, h);
        return;
    }
    const std::vector<float> v = copyOut<float>(env, n, [map](JNIEnv *e, jsize count, float *to) {
        e->GetFloatArrayRegion(map, 0, count, to);
    });
    f->setGainMap(v.data(), xmin, ymin, xmax, ymax, w, h);
}

/** P618: a generic tag of IFD 0 or the Exif IFD (ShotExif.Tag): ASCII text, or SHORT / LONG / RATIONAL words. */
JNIEXPORT void JNICALL Java_com_scamera_app_processing_DngCreator_nTag(JNIEnv *env, jclass, jlong handle, jint ifd, jint tag,
                                                                       jint kind, jstring text, jlongArray words) {
    DngCreator *f = file(handle);
    if (f == nullptr) return;
    const unsigned short id = static_cast<unsigned short>(tag);
    if (kind == kAscii) {
        if (text == nullptr) return;
        const char *chars = env->GetStringUTFChars(text, nullptr);
        if (chars == nullptr) return;
        const std::string s(chars);
        env->ReleaseStringUTFChars(text, chars);
        if (!s.empty()) f->setTag(ifd, id, tinydngwriter::TIFF_ASCII, static_cast<unsigned int>(s.size() + 1), s.c_str());
        return;
    }
    if (words == nullptr) return;
    const std::vector<jlong> v = copyOut<jlong>(env, env->GetArrayLength(words), [words](JNIEnv *e, jsize n, jlong *to) {
        e->GetLongArrayRegion(words, 0, n, to);
    });
    if (kind == kRationals && (v.size() < 2 || v.size() % 2 != 0)) {
        DNG_FAIL("tag %d: %zu rational words, not written", tag, v.size());
        return;
    }
    std::vector<unsigned int> out;
    if (!tagWords(v, kind == kShorts ? 65535 : 0xFFFFFFFFLL, kind == kRationals, out)) {
        DNG_FAIL("tag %d: a value out of range, not written", tag);
        return;
    }
    if (out.empty()) return;
    if (kind == kShorts) {
        const std::vector<unsigned short> shorts(out.begin(), out.end());
        f->setTag(ifd, id, tinydngwriter::TIFF_SHORT, static_cast<unsigned int>(shorts.size()), shorts.data());
    } else if (kind == kLongs) {
        f->setTag(ifd, id, tinydngwriter::TIFF_LONG, static_cast<unsigned int>(out.size()), out.data());
    } else if (kind == kRationals) {
        f->setTag(ifd, id, tinydngwriter::TIFF_RATIONAL, static_cast<unsigned int>(out.size() / 2), out.data());
    }
}

JNIEXPORT void JNICALL Java_com_scamera_app_processing_DngCreator_nCrop(JNIEnv *, jclass, jlong handle, jint x, jint y,
                                                                        jint w, jint h) {
    if (DngCreator *f = file(handle)) f->setDefaultCrop(x, y, w, h);
}

/**
 * P681: lays out the file around `mosaic` (a direct buffer of w x h 16-bit samples, left unchanged until the strip is written)
 * without building it: nHead / nStrip / nTail give its three parts. False on failure (logged).
 */
JNIEXPORT jboolean JNICALL Java_com_scamera_app_processing_DngCreator_nLayout(JNIEnv *env, jclass, jlong handle, jint w,
                                                                             jint h, jobject mosaic) {
    DngCreator *f = file(handle);
    void *samples = mosaic != nullptr ? env->GetDirectBufferAddress(mosaic) : nullptr;
    const jlong capacity = mosaic != nullptr ? env->GetDirectBufferCapacity(mosaic) : 0;
    if (f == nullptr || samples == nullptr || w <= 0 || h <= 0 || capacity < 2LL * w * h) {
        DNG_FAIL("no writer, no direct mosaic buffer or one smaller than %d x %d samples", w, h);
        return JNI_FALSE;
    }
    return f->layoutDng(samples, w, h) ? JNI_TRUE : JNI_FALSE;
}

namespace {
jbyteArray bytesOf(JNIEnv *env, const std::string &s) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
    if (out != nullptr && !s.empty())
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return out;
}
}  // namespace

/** P681: the TIFF header and the metadata in front of the strip (nLayout's). */
JNIEXPORT jbyteArray JNICALL Java_com_scamera_app_processing_DngCreator_nHead(JNIEnv *env, jclass, jlong handle) {
    DngCreator *f = file(handle);
    return f != nullptr ? bytesOf(env, f->head()) : nullptr;
}

/** P681: the strip's pad byte and the IFD after it (nLayout's). */
JNIEXPORT jbyteArray JNICALL Java_com_scamera_app_processing_DngCreator_nTail(JNIEnv *env, jclass, jlong handle) {
    DngCreator *f = file(handle);
    return f != nullptr ? bytesOf(env, f->tail()) : nullptr;
}

/** P681: the strip in place (the mosaic, or the writer's lossless JPEG: valid until nClose). */
JNIEXPORT jobject JNICALL Java_com_scamera_app_processing_DngCreator_nStrip(JNIEnv *env, jclass, jlong handle) {
    DngCreator *f = file(handle);
    if (f == nullptr || f->strip() == nullptr || f->stripBytes() == 0) return nullptr;
    return env->NewDirectByteBuffer(const_cast<unsigned char *>(f->strip()), static_cast<jlong>(f->stripBytes()));
}

/** P681: whether the strip is the lossless JPEG (nLayout's), for the write's log line. */
JNIEXPORT jboolean JNICALL Java_com_scamera_app_processing_DngCreator_nLossless(JNIEnv *, jclass, jlong handle) {
    DngCreator *f = file(handle);
    return f != nullptr && f->lossless() ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
