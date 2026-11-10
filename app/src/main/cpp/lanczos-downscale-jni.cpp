#include <jni.h>
#include <android/bitmap.h>
#include "lanczos-downscale.h"
#include "bitmap-crop.h"

class BitmapPixels {
    JNIEnv* env;
    jobject bitmap;
public:
    void* data=nullptr;
    BitmapPixels(JNIEnv* e,jobject b):env(e),bitmap(b) {
        if (AndroidBitmap_lockPixels(e,b,&data)!=ANDROID_BITMAP_RESULT_SUCCESS)
            throw std::runtime_error("Cannot lock bitmap pixels");
    }
    ~BitmapPixels() { AndroidBitmap_unlockPixels(env,bitmap); }
};
static jboolean resampleBitmaps(JNIEnv* env,jobject source,jobject destination,jint lobes,bool up);
extern "C" JNIEXPORT jboolean JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_ScamPostDownscale_nativeResize(
        JNIEnv* env,jclass,jobject source,jobject destination,jint lobes) {
    return resampleBitmaps(env,source,destination,lobes,false);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_ScamPostDownscale_nativeResample(
        JNIEnv* env,jclass,jobject source,jobject destination,jint lobes) {
    return resampleBitmaps(env,source,destination,lobes,true);
}
static jboolean resampleBitmaps(JNIEnv* env,jobject source,jobject destination,jint lobes,bool up) {
    AndroidBitmapInfo src{},dst{};
    if (AndroidBitmap_getInfo(env,source,&src)!=ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env,destination,&dst)!=ANDROID_BITMAP_RESULT_SUCCESS ||
        src.format!=ANDROID_BITMAP_FORMAT_RGBA_8888 || dst.format!=ANDROID_BITMAP_FORMAT_RGBA_8888)
        return JNI_FALSE;
    try {
        BitmapPixels input(env,source),output(env,destination);
        scamera_lanczos::resample(static_cast<const uint8_t*>(input.data),src.width,src.height,src.stride,
                static_cast<uint8_t*>(output.data),dst.width,dst.height,dst.stride,lobes,up);
        return JNI_TRUE;
    } catch (const std::exception&) {
        return JNI_FALSE;
    }
}
// P610 (200 MP: the ISZ 4:3 crop copied the 800 MB photo into a second bitmap): the crop packed in the bitmap's own pixels. The
// bitmap comes reconfigured to the crop (w x h, rows of w * bpp bytes); stride, width, height and bytes describe its pixels as they
// still lie in the buffer (ScamPostDownscale.cropInPlace).
extern "C" JNIEXPORT jboolean JNICALL
Java_com_particlesdevs_photoncamera_processing_ml_ScamPostDownscale_nativeCropInPlace(
        JNIEnv* env,jclass,jobject bitmap,jint stride,jint width,jint height,jlong bytes,jint x,jint y,jint w,jint h,jint bpp) {
    AndroidBitmapInfo info{};
    if (bpp<=0 || stride<=0 || bytes<=0 || AndroidBitmap_getInfo(env,bitmap,&info)!=ANDROID_BITMAP_RESULT_SUCCESS
            || int64_t(info.width)!=w || int64_t(info.height)!=h || int64_t(info.stride)!=int64_t(w)*bpp)
        return JNI_FALSE;
    void* data=nullptr;
    if (AndroidBitmap_lockPixels(env,bitmap,&data)!=ANDROID_BITMAP_RESULT_SUCCESS || !data) return JNI_FALSE;
    const bool ok=scamera_bitmap::cropRowsInPlace(static_cast<uint8_t*>(data),size_t(bytes),size_t(stride),width,height,x,y,w,h,bpp);
    AndroidBitmap_unlockPixels(env,bitmap);
    return ok?JNI_TRUE:JNI_FALSE;
}
