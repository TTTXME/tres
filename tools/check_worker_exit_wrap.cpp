// P668: linked into a host build of the worker (g++ ... -Wl,--wrap=eglInitialize -Wl,--wrap=eglMakeCurrent) for
// tools/check_worker_exit.py. A vendor GPU driver registers its exit handlers when it loads (the first eglInitialize, on the worker's
// EGL warm-up thread); an EGL call after they ran crashed the Adreno 650 (Xiaomi 12X, build 31266: SIGSEGV in libGLESv2_adreno under
// __cxa_finalize from a static destructor's eglMakeCurrent). Mesa survives that call, so this stands in for the vendor driver: the
// first eglInitialize registers an exit handler, and any eglMakeCurrent after it ran aborts the worker.
#include <EGL/egl.h>
#include <atomic>
#include <cstdio>
#include <cstdlib>

extern "C" EGLBoolean __real_eglInitialize(EGLDisplay display, EGLint* major, EGLint* minor);
extern "C" EGLBoolean __real_eglMakeCurrent(EGLDisplay display, EGLSurface draw, EGLSurface read, EGLContext context);

namespace {
std::atomic<bool> driverLoaded{false}, driverGone{false};
void driverExit() { driverGone = true; }
}

extern "C" EGLBoolean __wrap_eglInitialize(EGLDisplay display, EGLint* major, EGLint* minor) {
    if (!driverLoaded.exchange(true)) std::atexit(driverExit); // the driver's own exit handler, registered as it loads
    return __real_eglInitialize(display, major, minor);
}

extern "C" EGLBoolean __wrap_eglMakeCurrent(EGLDisplay display, EGLSurface draw, EGLSurface read, EGLContext context) {
    if (driverGone) {
        std::fputs("EXIT CHECK: eglMakeCurrent after the GPU driver's exit handlers (a static destructor's GL teardown)\n", stderr);
        std::fflush(stderr);
        std::abort();
    }
    return __real_eglMakeCurrent(display, draw, read, context);
}
