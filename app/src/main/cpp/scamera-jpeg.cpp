// JNI of JpegliEncoder: an RGBA_8888 Bitmap to a 4:4:4 JPEG written into a java.io.OutputStream (scamera-jpeg-core.h).
#include <android/bitmap.h>
#include <jni.h>

#include <algorithm>
#include <cstring>
#include <exception>
#include <string>
#include <thread>
#include <vector>

#include "scamera-jpeg-core.h"
#include "scamera-jpeg-parallel.h"

namespace {
struct PixelLock {
    JNIEnv* env;
    jobject bitmap;
    void* pixels = nullptr;
    PixelLock(JNIEnv* e, jobject b) : env(e), bitmap(b) {
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) pixels = nullptr;
    }
    ~PixelLock() { if (pixels) AndroidBitmap_unlockPixels(env, bitmap); }
};
}  // namespace

// P473 (speed): the final readback's mapped pixel-pack buffer into the new bitmap on `threads` threads - the same bytes as
// Bitmap.copyPixelsFromBuffer of an RGBA_8888 bitmap, but its first-touch page faults (~50 ms for 12 MP on one thread on the OPPO
// PHY110, 6.5 ms on four) spread over the threads. False when the bitmap or the buffer does not fit: the caller copies as before.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_particlesdevs_photoncamera_processing_JpegliEncoder_copyPixels(JNIEnv* env, jclass, jobject src, jobject bitmap, jint threads) {
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return JNI_FALSE;
    const auto* from = static_cast<const uint8_t*>(env->GetDirectBufferAddress(src));
    const jlong capacity = env->GetDirectBufferCapacity(src);
    const size_t row = size_t(info.width) * 4, bytes = row * info.height;
    if (from == nullptr || capacity < 0 || size_t(capacity) < bytes || info.stride < row) return JNI_FALSE;
    PixelLock lock(env, bitmap);
    if (lock.pixels == nullptr) return JNI_FALSE;
    auto* to = static_cast<uint8_t*>(lock.pixels);
    const int n = std::clamp(int(threads), 1, 8);
    const uint32_t rows = info.height, per = (rows + uint32_t(n) - 1) / uint32_t(n);
    auto part = [&](uint32_t y0) {
        const uint32_t y1 = std::min(rows, y0 + per);
        if (info.stride == row) { if (y1 > y0) std::memcpy(to + size_t(y0) * row, from + size_t(y0) * row, size_t(y1 - y0) * row); return; }
        for (uint32_t y = y0; y < y1; y++) std::memcpy(to + size_t(y) * info.stride, from + size_t(y) * row, row);
    };
    std::vector<std::thread> pool;
    try {
        for (int k = 1; k < n; k++) pool.emplace_back(part, uint32_t(k) * per);
    } catch (const std::exception&) {
        // fewer threads: this one copies the rest below
    }
    part(0);
    for (uint32_t k = uint32_t(pool.size()) + 1; k < uint32_t(n); k++) part(k * per);
    for (auto& t : pool) t.join();
    return JNI_TRUE;
}

// P473: the new bitmap's pages faulted in on `threads` threads (one byte written per 4 KB page; copyPixels overwrites them all) while
// the GPU still draws the final tiles, so the copy after the readback no longer takes the first-touch faults on its path.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_particlesdevs_photoncamera_processing_JpegliEncoder_touchPixels(JNIEnv* env, jclass, jobject bitmap, jint threads) {
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    PixelLock lock(env, bitmap);
    if (lock.pixels == nullptr) return JNI_FALSE;
    auto* p = static_cast<volatile uint8_t*>(lock.pixels);
    const size_t bytes = size_t(info.stride) * info.height, pages = (bytes + 4095) / 4096;
    const int n = std::clamp(int(threads), 1, 8);
    auto part = [&](int k) { for (size_t i = pages * size_t(k) / size_t(n); i < pages * size_t(k + 1) / size_t(n); i++) p[i * 4096] = 0; };
    std::vector<std::thread> pool;
    try {
        for (int k = 1; k < n; k++) pool.emplace_back(part, k);
    } catch (const std::exception&) {
        // fewer threads: this one touches the rest below
    }
    part(0);
    for (int k = int(pool.size()) + 1; k < n; k++) part(k);
    for (auto& t : pool) t.join();
    return JNI_TRUE;
}

// P443: jpegli's adaptive quantisation for the following encodes (scamera-jpeg-core.h; off by default).
extern "C" JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_processing_JpegliEncoder_setAdaptiveQuantization(JNIEnv*, jclass, jboolean on) {
    scamera_jpeg::adaptiveQuantisation().store(on == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_particlesdevs_photoncamera_processing_JpegliEncoder_setFastHuffmanTables(JNIEnv*, jclass, jboolean on) {
    scamera_jpeg::parallel::fastHuffmanTables().store(on == JNI_TRUE);
}

// P483: the encode of tightly or loosely packed RGBA rows (stride bytes apart) into `out` - the body the Bitmap and the direct
// buffer entries share, so the same pixels give the same bytes on either. Empty on success, else the reason.
static std::string encodePixels(JNIEnv* env, const uint8_t* pixels, uint32_t width, uint32_t height, size_t stride, jint quality,
                                jobject out, jint threads, jintArray modeOut, bool aq, bool fastTables, jbyteArray chunk, jmethodID write) {
    std::string error;
    const scamera_jpeg::Writer writer = [&](const uint8_t* data, size_t size) {
        for (size_t at = 0; at < size;) {
            const jsize n = jsize(std::min<size_t>(size - at, size_t(1) << 20));
            env->SetByteArrayRegion(chunk, 0, n, reinterpret_cast<const jbyte*>(data + at));
            env->CallVoidMethod(out, write, chunk, 0, n);
            if (env->ExceptionCheck()) return false;
            at += size_t(n);
        }
        return true;
    };
    bool written = false;
    bool started = false;
    const scamera_jpeg::Writer counting = [&](const uint8_t* data, size_t size) {
        started = true;
        return writer(data, size);
    };
    jint mode = 1;
    if (threads > 1 && uint64_t(width) * height >= 4000000ull) {
        try {
            scamera_jpeg::parallel::encodeRgba(pixels, int(width), int(height), stride, quality, int(threads), 0, counting, 0, fastTables, aq);
            written = true;
            mode = threads;
        } catch (const std::exception& e) {
            if (started) error = e.what();   // part of a file is out: the caller starts again with Android's encoder
        }
    }
    if (!written && error.empty()) {
        try {
            scamera_jpeg::encodeRgba(pixels, int(width), int(height), stride, quality, writer, aq);
        } catch (const std::exception& e) {
            error = e.what();
        }
    }
    if (modeOut != nullptr && env->GetArrayLength(modeOut) > 0) env->SetIntArrayRegion(modeOut, 0, 1, &mode);
    return error;
}

/** The OutputStream's write([BII)V and a 1 MB chunk for it; false (with `error`) when either is missing. */
static bool streamOf(JNIEnv* env, jobject out, jmethodID& write, jbyteArray& chunk, std::string& error) {
    jclass streamClass = env->GetObjectClass(out);
    write = env->GetMethodID(streamClass, "write", "([BII)V");
    env->DeleteLocalRef(streamClass);
    if (write == nullptr) { env->ExceptionClear(); error = "OutputStream.write missing"; return false; }
    chunk = env->NewByteArray(1 << 20);
    if (chunk == nullptr) { env->ExceptionClear(); error = "no memory for the output chunk"; return false; }
    return true;
}

static jstring finishEncode(JNIEnv* env, jbyteArray chunk, std::string error) {
    env->DeleteLocalRef(chunk);
    if (env->ExceptionCheck()) {
        // The Java stream threw (disk full, closed): report it as the failure, the caller decides.
        env->ExceptionClear();
        if (error.empty()) error = "JPEG output stream failed";
    }
    return error.empty() ? nullptr : env->NewStringUTF(error.c_str());
}

// Returns null on success, else the reason (nothing usable was written when it fails before the first chunk).
// P147: threads > 1 and at least 4 MP: strips on that many threads (scamera-jpeg-parallel.h); when the strips cannot be
// joined (nothing written yet) the one-thread progressive encode runs. *modeOut: "strips N" / "one thread".
extern "C" JNIEXPORT jstring JNICALL
Java_com_particlesdevs_photoncamera_processing_JpegliEncoder_encode(JNIEnv* env, jclass, jobject bitmap, jint quality,
                                                                    jobject out, jint threads, jintArray modeOut) {
    const bool aq = scamera_jpeg::adaptiveQuantisation().load();
    const bool fastTables = scamera_jpeg::parallel::fastHuffmanTables().load();
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS)
        return env->NewStringUTF("bitmap info unavailable");
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return env->NewStringUTF("bitmap is not RGBA_8888");
    jmethodID write = nullptr;
    jbyteArray chunk = nullptr;
    std::string error;
    if (!streamOf(env, out, write, chunk, error)) return env->NewStringUTF(error.c_str());
    {
        PixelLock lock(env, bitmap);
        if (lock.pixels == nullptr) {
            error = "bitmap pixels unavailable";
        } else {
            error = encodePixels(env, static_cast<const uint8_t*>(lock.pixels), info.width, info.height, info.stride, quality, out,
                                 threads, modeOut, aq, fastTables, chunk, write);
        }
    }
    return finishEncode(env, chunk, error);
}

// P483 (speed): the same encode straight from a direct buffer of RGBA rows `stride` bytes apart (the final readback's mapped
// pixel-pack buffer, or a sub-rectangle of it: a slice at its first pixel), with no bitmap and no copy into one: the bytes of
// encode() on a bitmap holding these pixels (encodePixels).
extern "C" JNIEXPORT jstring JNICALL
Java_com_particlesdevs_photoncamera_processing_JpegliEncoder_encodeBuffer(JNIEnv* env, jclass, jobject src, jint width, jint height,
                                                                          jint stride, jint quality, jobject out, jint threads,
                                                                          jintArray modeOut) {
    const bool aq = scamera_jpeg::adaptiveQuantisation().load();
    const bool fastTables = scamera_jpeg::parallel::fastHuffmanTables().load();
    const auto* pixels = static_cast<const uint8_t*>(env->GetDirectBufferAddress(src));
    const jlong capacity = env->GetDirectBufferCapacity(src);
    if (pixels == nullptr || width <= 0 || height <= 0 || capacity < 0 || int64_t(stride) < int64_t(width) * 4
        || uint64_t(capacity) < uint64_t(height - 1) * uint64_t(stride) + uint64_t(width) * 4u)
        return env->NewStringUTF("buffer does not hold the image");
    jmethodID write = nullptr;
    jbyteArray chunk = nullptr;
    std::string error;
    if (!streamOf(env, out, write, chunk, error)) return env->NewStringUTF(error.c_str());
    error = encodePixels(env, pixels, uint32_t(width), uint32_t(height), size_t(stride), quality, out, threads, modeOut, aq,
                         fastTables, chunk, write);
    return finishEncode(env, chunk, error);
}
