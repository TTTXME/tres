// Library camera2native (api/NativeEngine.java): opens the camera framework's non-SDK members (vendor keys, the custom
// capture session) to the app. Two parts:
//  - nativeInitialize lifts the hidden-API policy for this process (ART's policy word when its symbol is visible, and
//    VMRuntime.setHiddenApiExemptions, from a freshly attached thread when the app's own call is refused);
//  - nativeGetCameraMethod / nativeGetCameraField resolve one declared member on a freshly attached thread, which has no
//    managed caller for ART to judge.
// P214a: a lookup leaves no global reference behind (tools/check_native_engine_refs.cpp builds this file on the host with a
// counting JNIEnv and its own log stub, SCAM_HOST_TEST).
#include <jni.h>
#ifndef SCAM_HOST_TEST
#include <android/log.h>
#endif
#include <dlfcn.h>
#include <thread>
#include <utility>

#define ENGINE_LOG(level, ...) __android_log_print(level, "NativeEngine", __VA_ARGS__)

namespace {

JavaVM *g_vm = nullptr;
void *g_art = nullptr;

/** A thread attached to the VM for its lifetime (env() null when the VM is unknown or refuses). */
class AttachedThread {
public:
    AttachedThread() {
        if (g_vm == nullptr) {
            ENGINE_LOG(ANDROID_LOG_ERROR, "no JavaVM to attach a thread to");
            return;
        }
        const jint rc = g_vm->AttachCurrentThread(&env_, nullptr);
        ENGINE_LOG(ANDROID_LOG_DEBUG, "lookup thread attached (%d)", rc);
    }
    ~AttachedThread() {
        if (g_vm != nullptr) g_vm->DetachCurrentThread();
    }
    AttachedThread(const AttachedThread &) = delete;
    AttachedThread &operator=(const AttachedThread &) = delete;
    JNIEnv *env() const { return env_; }

private:
    JNIEnv *env_ = nullptr;
};

/** Clears a pending exception (logged with `what`); true when there was one. */
bool swallow(JNIEnv *env, const char *what) {
    if (!env->ExceptionCheck()) return false;
    ENGINE_LOG(ANDROID_LOG_DEBUG, "%s", what);
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}

void openArt() {
    if (g_art != nullptr) return;
    g_art = dlopen("libart.so", RTLD_NOW);
    if (g_art == nullptr) ENGINE_LOG(ANDROID_LOG_ERROR, "libart.so not opened: %s", dlerror());
    else ENGINE_LOG(ANDROID_LOG_INFO, "libart.so opened");
}

/** Zeroes ART's hidden-API policy word when one of its symbols is visible (older platforms); false when none is. */
bool clearArtPolicy() {
    if (g_art == nullptr) {
        ENGINE_LOG(ANDROID_LOG_DEBUG, "no libart handle: the policy word stays");
        return false;
    }
    static const char *const kPolicySymbols[] = {
            "_ZN3art9hiddenapi6detail19g_hiddenapi_policyE",
            "_ZN3art9hiddenapi6detail24kStrongHiddenApiSentinelE",
    };
    for (const char *symbol : kPolicySymbols) {
        if (void *word = dlsym(g_art, symbol)) {
            *static_cast<int *>(word) = 0;
            ENGINE_LOG(ANDROID_LOG_INFO, "hidden-API policy cleared (%s)", symbol);
            return true;
        }
    }
    ENGINE_LOG(ANDROID_LOG_DEBUG, "no policy symbol in libart: the exemption call decides");
    return false;
}

/** VMRuntime.getRuntime().setHiddenApiExemptions(new String[]{"L"}): every class. False when refused or unavailable. */
bool exemptEverything(JNIEnv *env) {
    jclass runtimeClass = env->FindClass("dalvik/system/VMRuntime");
    if (runtimeClass == nullptr) {
        swallow(env, "VMRuntime not found");
        return false;
    }
    jmethodID current = env->GetStaticMethodID(runtimeClass, "getRuntime", "()Ldalvik/system/VMRuntime;");
    jobject runtime = current != nullptr ? env->CallStaticObjectMethod(runtimeClass, current) : nullptr;
    jmethodID exempt = runtime != nullptr
            ? env->GetMethodID(runtimeClass, "setHiddenApiExemptions", "([Ljava/lang/String;)V") : nullptr;
    if (exempt == nullptr) {
        env->ExceptionClear();
        ENGINE_LOG(ANDROID_LOG_DEBUG, "VMRuntime exemptions not reachable");
        return false;
    }
    jobjectArray prefixes = env->NewObjectArray(1, env->FindClass("java/lang/String"), env->NewStringUTF("L"));
    env->CallVoidMethod(runtime, exempt, prefixes);
    if (swallow(env, "setHiddenApiExemptions refused")) return false;
    ENGINE_LOG(ANDROID_LOG_INFO, "hidden-API exemptions set");
    return true;
}

/** What a lookup thread resolves: a declared method (with its parameter types) or a declared field of `owner`. */
struct Member {
    jobject owner;
    jstring name;
    jobjectArray parameters;  // methods only, may be null
    bool method;
};

/**
 * On the calling (attached) thread: owner.getDeclaredMethod / getDeclaredField, a global reference to the member (null when
 * there is none; the lookup's exception is cleared - normal for a key a vendor does not have).
 */
jobject resolveHere(JNIEnv *env, const Member &m) {
    jclass classOfClass = env->GetObjectClass(m.owner);
    jobject found;
    if (m.method) {
        jmethodID get = env->GetMethodID(classOfClass, "getDeclaredMethod",
                                         "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
        found = env->CallObjectMethod(m.owner, get, m.name, m.parameters);
    } else {
        jmethodID get = env->GetMethodID(classOfClass, "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;");
        found = env->CallObjectMethod(m.owner, get, m.name);
    }
    swallow(env, m.method ? "no such method (may be normal for vendor keys)" : "no such field (may be normal for vendor keys)");
    if (found == nullptr) return nullptr;
    ENGINE_LOG(ANDROID_LOG_INFO, "%s resolved", m.method ? "method" : "field");
    return env->NewGlobalRef(found);
}

/**
 * Resolves `m` on a fresh attached thread. The inputs travel there as global references (a local one belongs to its
 * thread); the result comes back as a global one and is returned as a local reference of the caller, so the global table
 * ends as it started (P214a: the old code kept about nine entries per lookup, and ART aborts at 51200 - OPPO Find X7 Ultra,
 * 2026-10-17).
 */
jobject resolveOnFreshThread(JNIEnv *env, Member m) {
    m.owner = env->NewGlobalRef(m.owner);
    m.name = static_cast<jstring>(env->NewGlobalRef(m.name));
    if (m.parameters != nullptr) m.parameters = static_cast<jobjectArray>(env->NewGlobalRef(m.parameters));

    jobject global = nullptr;
    std::thread worker([&global, &m]() {
        AttachedThread attached;
        if (attached.env() != nullptr) global = resolveHere(attached.env(), m);
        else ENGINE_LOG(ANDROID_LOG_ERROR, "lookup thread not attached");
    });
    worker.join();

    env->DeleteGlobalRef(m.owner);
    env->DeleteGlobalRef(m.name);
    if (m.parameters != nullptr) env->DeleteGlobalRef(m.parameters);
    swallow(env, "exception after the lookup");
    if (global == nullptr) return nullptr;
    jobject local = env->NewLocalRef(global);
    env->DeleteGlobalRef(global);
    return local;
}

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        ENGINE_LOG(ANDROID_LOG_ERROR, "JNI_OnLoad: no JNIEnv");
        return -1;
    }
    ENGINE_LOG(ANDROID_LOG_INFO, "camera2native loaded");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL Java_com_scamera_app_api_NativeEngine_nativeInitialize(JNIEnv *env, jclass) {
    ENGINE_LOG(ANDROID_LOG_INFO, "hidden-API access: starting");
    openArt();
    const bool policy = clearArtPolicy();
    bool exempt = exemptEverything(env);
    // Android 11+ refuses the exemption to an app caller; a freshly attached native thread has no managed frame, and ART
    // treats a caller it cannot determine as trusted - so the call is repeated from one.
    if (!exempt && g_vm != nullptr) {
        std::thread([&exempt]() {
            AttachedThread attached;
            if (attached.env() != nullptr) exempt = exemptEverything(attached.env());
        }).join();
        ENGINE_LOG(ANDROID_LOG_INFO, "exemptions from an attached native thread: %d", exempt);
    }
    if (policy || exempt) ENGINE_LOG(ANDROID_LOG_INFO, "hidden-API access open (policy %d, exemptions %d)", policy, exempt);
    else ENGINE_LOG(ANDROID_LOG_WARN, "hidden-API access not opened");
}

extern "C" JNIEXPORT jobject JNICALL Java_com_scamera_app_api_NativeEngine_nativeGetCameraMethod(JNIEnv *env, jclass,
        jclass owner, jstring name, jobjectArray parameterTypes) {
    if (owner == nullptr || name == nullptr) {
        ENGINE_LOG(ANDROID_LOG_ERROR, "method lookup without a class or a name");
        return nullptr;
    }
    return resolveOnFreshThread(env, Member{owner, name, parameterTypes, true});
}

extern "C" JNIEXPORT jobject JNICALL Java_com_scamera_app_api_NativeEngine_nativeGetCameraField(JNIEnv *env, jclass,
        jclass owner, jstring name) {
    if (owner == nullptr || name == nullptr) {
        ENGINE_LOG(ANDROID_LOG_ERROR, "field lookup without a class or a name");
        return nullptr;
    }
    return resolveOnFreshThread(env, Member{owner, name, nullptr, false});
}
