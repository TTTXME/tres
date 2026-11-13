// P482 (ahb_transport): the merge worker's RGB halves in an AHardwareBuffer of the app instead of the output memfd. The app allocates
// it (BLOB, GPU_DATA_BUFFER | CPU_READ_OFTEN) and sends its handle over the job's unix socket (the worker's fast merge writes the
// halves into it on the GPU); the result is a CPU view of the locked buffer (the statistics, the decimated copy, the CPU repack read
// it as they read the memfd) that ScamRgb's input imports into the post's own GL context as a storage buffer (HalfRgbUpload) instead
// of a 72 MB glBufferSubData. Allocator.free of the view waits for the post's last GPU read (an EGL fence), unlocks and releases it.
#include <jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl31.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <cstring>
#include <mutex>
#include <vector>

#define AHB_LOG(...) __android_log_print(ANDROID_LOG_INFO, "SCAM_AHB", __VA_ARGS__)

namespace {
struct View { void* ptr; AHardwareBuffer* ahb; size_t bytes; EGLDisplay display; EGLSyncKHR sync; };
std::mutex viewLock;
std::vector<View> views;

PFNEGLCREATESYNCKHRPROC createSync() {
    static auto f = reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(eglGetProcAddress("eglCreateSyncKHR"));
    return f;
}
PFNEGLCLIENTWAITSYNCKHRPROC clientWait() {
    static auto f = reinterpret_cast<PFNEGLCLIENTWAITSYNCKHRPROC>(eglGetProcAddress("eglClientWaitSyncKHR"));
    return f;
}
PFNEGLDESTROYSYNCKHRPROC destroySync() {
    static auto f = reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(eglGetProcAddress("eglDestroySyncKHR"));
    return f;
}
long findLocked(const void* p) {
    for (size_t i = 0; i < views.size(); ++i) if (views[i].ptr == p) return long(i);
    return -1;
}
}

// Allocator.free (allocator.cpp): true when ptr was such a view (waited, unlocked, released); false: not one of them.
bool scamAhbFreeView(const void* ptr) {
    View v{};
    {
        std::lock_guard<std::mutex> lock(viewLock);
        const long i = findLocked(ptr);
        if (i < 0) return false;
        v = views[size_t(i)];
        views.erase(views.begin() + i);
    }
    if (v.sync != EGL_NO_SYNC_KHR && clientWait() && destroySync()) {
        // the post's GPU read of the buffer done before it goes (2 s at most: a hung driver must not hold the release forever)
        clientWait()(v.display, v.sync, 0, 2000000000ull);
        destroySync()(v.display, v.sync);
    }
    AHardwareBuffer_unlock(v.ahb, nullptr);
    AHardwareBuffer_release(v.ahb);
    return true;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_scamera_app_util_HardwareRgb_allocate(JNIEnv*, jclass, jlong bytes) {
    if (bytes <= 0 || bytes > jlong(INT32_MAX)) return 0;
    AHardwareBuffer_Desc d{};
    d.width = uint32_t(bytes); d.height = 1; d.layers = 1; d.format = AHARDWAREBUFFER_FORMAT_BLOB;
    d.usage = AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_GPU_DATA_BUFFER;
    AHardwareBuffer* h = nullptr;
    if (AHardwareBuffer_allocate(&d, &h) != 0 || !h) return 0;
    return jlong(reinterpret_cast<intptr_t>(h));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_scamera_app_util_HardwareRgb_send(JNIEnv*, jclass, jlong handle, jint socketFd) {
    auto* h = reinterpret_cast<AHardwareBuffer*>(intptr_t(handle));
    return h && socketFd >= 0 && AHardwareBuffer_sendHandleToUnixSocket(h, socketFd) == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_scamera_app_util_HardwareRgb_release(JNIEnv*, jclass, jlong handle) {
    auto* h = reinterpret_cast<AHardwareBuffer*>(intptr_t(handle));
    if (h) AHardwareBuffer_release(h);
}

// The buffer locked for CPU reads as a direct ByteBuffer of `bytes` (the view owns the handle from here: Allocator.free releases
// it); null: not locked (the caller still owns the handle).
extern "C" JNIEXPORT jobject JNICALL
Java_com_scamera_app_util_HardwareRgb_view(JNIEnv* env, jclass, jlong handle, jint bytes) {
    auto* h = reinterpret_cast<AHardwareBuffer*>(intptr_t(handle));
    if (!h || bytes <= 0) return nullptr;
    AHardwareBuffer_Desc d{};
    AHardwareBuffer_describe(h, &d);
    if (d.format != AHARDWAREBUFFER_FORMAT_BLOB || d.width < uint32_t(bytes)) return nullptr;
    void* p = nullptr;
    if (AHardwareBuffer_lock(h, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &p) != 0 || !p) return nullptr;
    jobject view = env->NewDirectByteBuffer(p, jlong(bytes));
    if (!view) { AHardwareBuffer_unlock(h, nullptr); return nullptr; }
    std::lock_guard<std::mutex> lock(viewLock);
    views.push_back(View{p, h, size_t(bytes), EGL_NO_DISPLAY, EGL_NO_SYNC_KHR});
    return view;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_scamera_app_util_HardwareRgb_isView(JNIEnv* env, jclass, jobject buffer) {
    void* p = buffer ? env->GetDirectBufferAddress(buffer) : nullptr;
    if (!p) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(viewLock);
    return findLocked(p) >= 0 ? JNI_TRUE : JNI_FALSE;
}

// The view's buffer as the storage of a new GL buffer object in the calling thread's current context (EXT_external_buffer with
// EGL_ANDROID_get_native_client_buffer); the GL name, 0 when refused (no context, no extension, a GL error).
extern "C" JNIEXPORT jint JNICALL
Java_com_scamera_app_util_HardwareRgb_importGl(JNIEnv* env, jclass, jobject buffer) {
    void* p = buffer ? env->GetDirectBufferAddress(buffer) : nullptr;
    if (!p || eglGetCurrentContext() == EGL_NO_CONTEXT) return 0;
    AHardwareBuffer* h = nullptr; size_t bytes = 0;
    {
        std::lock_guard<std::mutex> lock(viewLock);
        const long i = findLocked(p);
        if (i < 0) return 0;
        h = views[size_t(i)].ahb; bytes = views[size_t(i)].bytes;
    }
    const auto* ext = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
    const char* eglExt = eglQueryString(eglGetCurrentDisplay(), EGL_EXTENSIONS);
    if (!ext || !std::strstr(ext, "GL_EXT_external_buffer") || !eglExt || !std::strstr(eglExt, "EGL_ANDROID_get_native_client_buffer")) return 0;
    using ClientBuffer = EGLClientBuffer (*)(const AHardwareBuffer*);
    using Storage = void (*)(GLenum, GLintptr, GLsizeiptr, EGLClientBuffer, GLbitfield);
    static auto clientBuffer = reinterpret_cast<ClientBuffer>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    static auto storage = reinterpret_cast<Storage>(eglGetProcAddress("glBufferStorageExternalEXT"));
    if (!clientBuffer || !storage) return 0;
    const EGLClientBuffer cb = clientBuffer(h);
    if (!cb) return 0;
    while (glGetError() != GL_NO_ERROR) {}
    GLuint name = 0;
    glGenBuffers(1, &name);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, name);
    storage(GL_SHADER_STORAGE_BUFFER, 0, GLsizeiptr(bytes), cb, 0);
    const GLenum error = glGetError();
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    if (error != GL_NO_ERROR) {
        AHB_LOG("import refused: GL error 0x%x", error);
        glDeleteBuffers(1, &name);
        return 0;
    }
    return jint(name);
}

// An EGL fence after the calling thread's GL reads of the view: its free waits for it (off this thread). false: no fence (the
// caller then finishes the GL work itself before the view may go).
extern "C" JNIEXPORT jboolean JNICALL
Java_com_scamera_app_util_HardwareRgb_fence(JNIEnv* env, jclass, jobject buffer) {
    void* p = buffer ? env->GetDirectBufferAddress(buffer) : nullptr;
    const EGLDisplay display = eglGetCurrentDisplay();
    if (!p || display == EGL_NO_DISPLAY || !createSync()) return JNI_FALSE;
    const EGLSyncKHR sync = createSync()(display, EGL_SYNC_FENCE_KHR, nullptr);
    if (sync == EGL_NO_SYNC_KHR) return JNI_FALSE;
    glFlush(); // the fence reaches the GPU (a wait from another thread must not wait for this thread's next flush)
    std::lock_guard<std::mutex> lock(viewLock);
    const long i = findLocked(p);
    if (i < 0) { if (destroySync()) destroySync()(display, sync); return JNI_FALSE; }
    if (views[size_t(i)].sync != EGL_NO_SYNC_KHR && destroySync()) destroySync()(views[size_t(i)].display, views[size_t(i)].sync);
    views[size_t(i)].display = display;
    views[size_t(i)].sync = sync;
    return JNI_TRUE;
}
