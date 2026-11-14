// The native half of scam.host.ShadowEGL14: EGL 1.4 on the host (Mesa), handles as jlong.
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <EGL/egl.h>

#define P(x) ((void*)(intptr_t)(x))
#define J(x) ((jlong)(intptr_t)(x))
#define FN(name) JNICALL Java_scam_host_ShadowEGL14_##name

static jint* ints(JNIEnv* env, jintArray a) { return a ? (*env)->GetIntArrayElements(env, a, NULL) : NULL; }
static void done(JNIEnv* env, jintArray a, jint* e) { if (a) (*env)->ReleaseIntArrayElements(env, a, e, 0); }

JNIEXPORT jint FN(nGetError)(JNIEnv* env, jclass c) { return eglGetError(); }
JNIEXPORT jlong FN(nGetDisplay)(JNIEnv* env, jclass c, jint id) { return J(eglGetDisplay((EGLNativeDisplayType)(intptr_t)id)); }
JNIEXPORT jboolean FN(nInitialize)(JNIEnv* env, jclass c, jlong d, jintArray major, jint mo, jintArray minor, jint no) {
    EGLint ma = 0, mi = 0;
    const EGLBoolean ok = eglInitialize(P(d), &ma, &mi);
    if (major) (*env)->SetIntArrayRegion(env, major, mo, 1, &ma);
    if (minor) (*env)->SetIntArrayRegion(env, minor, no, 1, &mi);
    return ok;
}
JNIEXPORT jboolean FN(nTerminate)(JNIEnv* env, jclass c, jlong d) { return eglTerminate(P(d)); }
JNIEXPORT jstring FN(nQueryString)(JNIEnv* env, jclass c, jlong d, jint name) {
    const char* s = eglQueryString(P(d), name);
    return s ? (*env)->NewStringUTF(env, s) : NULL;
}
JNIEXPORT jboolean FN(nBindAPI)(JNIEnv* env, jclass c, jint api) { return eglBindAPI((EGLenum)api); }
JNIEXPORT jint FN(nQueryAPI)(JNIEnv* env, jclass c) { return (jint)eglQueryAPI(); }
JNIEXPORT jboolean FN(nChooseConfig)(JNIEnv* env, jclass c, jlong d, jintArray attrs, jint ao, jlongArray configs, jint size, jintArray num, jint no) {
    jint* a = ints(env, attrs);
    EGLConfig* list = size > 0 ? calloc((size_t)size, sizeof(EGLConfig)) : NULL;
    EGLint n = 0;
    const EGLBoolean ok = eglChooseConfig(P(d), a ? a + ao : NULL, configs ? list : NULL, size, &n);
    done(env, attrs, a);
    if (configs && ok) for (EGLint i = 0; i < n && i < size; ++i) { jlong v = J(list[i]); (*env)->SetLongArrayRegion(env, configs, i, 1, &v); }
    free(list);
    if (num) (*env)->SetIntArrayRegion(env, num, no, 1, &n);
    return ok;
}
JNIEXPORT jboolean FN(nGetConfigAttrib)(JNIEnv* env, jclass c, jlong d, jlong cfg, jint attr, jintArray value, jint off) {
    EGLint v = 0;
    const EGLBoolean ok = eglGetConfigAttrib(P(d), P(cfg), attr, &v);
    if (value) (*env)->SetIntArrayRegion(env, value, off, 1, &v);
    return ok;
}
JNIEXPORT jlong FN(nCreateContext)(JNIEnv* env, jclass c, jlong d, jlong cfg, jlong share, jintArray attrs, jint off) {
    jint* a = ints(env, attrs);
    eglBindAPI(EGL_OPENGL_ES_API);
    const EGLContext r = eglCreateContext(P(d), P(cfg), P(share), a ? a + off : NULL);
    done(env, attrs, a);
    return J(r);
}
JNIEXPORT jlong FN(nCreatePbufferSurface)(JNIEnv* env, jclass c, jlong d, jlong cfg, jintArray attrs, jint off) {
    jint* a = ints(env, attrs);
    const EGLSurface r = eglCreatePbufferSurface(P(d), P(cfg), a ? a + off : NULL);
    done(env, attrs, a);
    return J(r);
}
JNIEXPORT jboolean FN(nDestroyContext)(JNIEnv* env, jclass c, jlong d, jlong ctx) { return eglDestroyContext(P(d), P(ctx)); }
JNIEXPORT jboolean FN(nDestroySurface)(JNIEnv* env, jclass c, jlong d, jlong s) { return eglDestroySurface(P(d), P(s)); }
JNIEXPORT jboolean FN(nMakeCurrent)(JNIEnv* env, jclass c, jlong d, jlong draw, jlong read, jlong ctx) {
    return eglMakeCurrent(P(d), P(draw), P(read), P(ctx));
}
JNIEXPORT jlong FN(nGetCurrentContext)(JNIEnv* env, jclass c) { return J(eglGetCurrentContext()); }
JNIEXPORT jlong FN(nGetCurrentDisplay)(JNIEnv* env, jclass c) { return J(eglGetCurrentDisplay()); }
JNIEXPORT jlong FN(nGetCurrentSurface)(JNIEnv* env, jclass c, jint which) { return J(eglGetCurrentSurface(which)); }
JNIEXPORT jboolean FN(nQueryContext)(JNIEnv* env, jclass c, jlong d, jlong ctx, jint attr, jintArray value, jint off) {
    EGLint v = 0;
    const EGLBoolean ok = eglQueryContext(P(d), P(ctx), attr, &v);
    if (value) (*env)->SetIntArrayRegion(env, value, off, 1, &v);
    return ok;
}
JNIEXPORT jboolean FN(nQuerySurface)(JNIEnv* env, jclass c, jlong d, jlong s, jint attr, jintArray value, jint off) {
    EGLint v = 0;
    const EGLBoolean ok = eglQuerySurface(P(d), P(s), attr, &v);
    if (value) (*env)->SetIntArrayRegion(env, value, off, 1, &v);
    return ok;
}
JNIEXPORT jboolean FN(nReleaseThread)(JNIEnv* env, jclass c) { return eglReleaseThread(); }
JNIEXPORT jboolean FN(nSwapBuffers)(JNIEnv* env, jclass c, jlong d, jlong s) { return eglSwapBuffers(P(d), P(s)); }
