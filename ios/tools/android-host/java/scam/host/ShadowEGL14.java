package scam.host;

import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLObjectHandle;
import android.opengl.EGLSurface;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** android.opengl.EGL14 on the host's EGL (Mesa, EGL_PLATFORM=surfaceless): the handles are the native pointers. */
@Implements(android.opengl.EGL14.class)
public class ShadowEGL14 {
    static { System.loadLibrary("scamhostgl"); }

    private static <T extends EGLObjectHandle> T wrap(Class<T> type, long handle) {
        try {
            java.lang.reflect.Constructor<T> c;
            try { c = type.getDeclaredConstructor(long.class); c.setAccessible(true); return c.newInstance(handle); }
            catch (NoSuchMethodException e) { c = type.getDeclaredConstructor(int.class); c.setAccessible(true); return c.newInstance((int) handle); }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("host EGL: " + type, e);
        }
    }

    private static long h(EGLObjectHandle o) { return o == null ? 0 : o.getNativeHandle(); }

    @Implementation protected static void _nativeClassInit() {
        try {
            for (String[] f : new String[][]{{"EGL_NO_CONTEXT", "C"}, {"EGL_NO_DISPLAY", "D"}, {"EGL_NO_SURFACE", "S"}}) {
                java.lang.reflect.Field field = android.opengl.EGL14.class.getDeclaredField(f[0]);
                field.setAccessible(true);
                field.set(null, f[1].equals("C") ? wrap(EGLContext.class, 0) : f[1].equals("D") ? wrap(EGLDisplay.class, 0) : wrap(EGLSurface.class, 0));
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("host EGL: EGL14 constants", e);
        }
    }

    @Implementation protected static int eglGetError() { return nGetError(); }
    @Implementation protected static EGLDisplay eglGetDisplay(int id) { return wrap(EGLDisplay.class, nGetDisplay(id)); }
    @Implementation protected static boolean eglInitialize(EGLDisplay d, int[] major, int mo, int[] minor, int no) {
        return nInitialize(h(d), major, mo, minor, no);
    }
    @Implementation protected static boolean eglTerminate(EGLDisplay d) { return nTerminate(h(d)); }
    @Implementation protected static String eglQueryString(EGLDisplay d, int name) { return nQueryString(h(d), name); }
    @Implementation protected static boolean eglBindAPI(int api) { return nBindAPI(api); }
    @Implementation protected static int eglQueryAPI() { return nQueryAPI(); }
    @Implementation protected static boolean eglChooseConfig(EGLDisplay d, int[] attrs, int ao, EGLConfig[] configs, int co, int size, int[] num, int no) {
        final long[] handles = new long[Math.max(0, size)];
        final boolean ok = nChooseConfig(h(d), attrs, ao, configs == null ? null : handles, size, num, no);
        if (ok && configs != null) for (int i = 0; i < num[no] && i < size; i++) configs[co + i] = wrap(EGLConfig.class, handles[i]);
        return ok;
    }
    @Implementation protected static boolean eglGetConfigAttrib(EGLDisplay d, EGLConfig c, int attr, int[] value, int off) {
        return nGetConfigAttrib(h(d), h(c), attr, value, off);
    }
    @Implementation protected static EGLContext eglCreateContext(EGLDisplay d, EGLConfig c, EGLContext share, int[] attrs, int off) {
        return wrap(EGLContext.class, nCreateContext(h(d), h(c), h(share), attrs, off));
    }
    @Implementation protected static EGLSurface eglCreatePbufferSurface(EGLDisplay d, EGLConfig c, int[] attrs, int off) {
        return wrap(EGLSurface.class, nCreatePbufferSurface(h(d), h(c), attrs, off));
    }
    @Implementation protected static boolean eglDestroyContext(EGLDisplay d, EGLContext c) { return nDestroyContext(h(d), h(c)); }
    @Implementation protected static boolean eglDestroySurface(EGLDisplay d, EGLSurface s) { return nDestroySurface(h(d), h(s)); }
    @Implementation protected static boolean eglMakeCurrent(EGLDisplay d, EGLSurface draw, EGLSurface read, EGLContext c) {
        return nMakeCurrent(h(d), h(draw), h(read), h(c));
    }
    @Implementation protected static EGLContext eglGetCurrentContext() { return wrap(EGLContext.class, nGetCurrentContext()); }
    @Implementation protected static EGLDisplay eglGetCurrentDisplay() { return wrap(EGLDisplay.class, nGetCurrentDisplay()); }
    @Implementation protected static EGLSurface eglGetCurrentSurface(int which) { return wrap(EGLSurface.class, nGetCurrentSurface(which)); }
    @Implementation protected static boolean eglQueryContext(EGLDisplay d, EGLContext c, int attr, int[] value, int off) {
        return nQueryContext(h(d), h(c), attr, value, off);
    }
    @Implementation protected static boolean eglQuerySurface(EGLDisplay d, EGLSurface s, int attr, int[] value, int off) {
        return nQuerySurface(h(d), h(s), attr, value, off);
    }
    @Implementation protected static boolean eglReleaseThread() { return nReleaseThread(); }
    @Implementation protected static boolean eglSwapBuffers(EGLDisplay d, EGLSurface s) { return nSwapBuffers(h(d), h(s)); }
    @Implementation protected static boolean eglSwapInterval(EGLDisplay d, int interval) { return true; }
    @Implementation protected static boolean eglWaitClient() { return true; }
    @Implementation protected static boolean eglWaitGL() { return true; }

    private static native int nGetError();
    private static native long nGetDisplay(int id);
    private static native boolean nInitialize(long d, int[] major, int mo, int[] minor, int no);
    private static native boolean nTerminate(long d);
    private static native String nQueryString(long d, int name);
    private static native boolean nBindAPI(int api);
    private static native int nQueryAPI();
    private static native boolean nChooseConfig(long d, int[] attrs, int ao, long[] configs, int size, int[] num, int no);
    private static native boolean nGetConfigAttrib(long d, long c, int attr, int[] value, int off);
    private static native long nCreateContext(long d, long c, long share, int[] attrs, int off);
    private static native long nCreatePbufferSurface(long d, long c, int[] attrs, int off);
    private static native boolean nDestroyContext(long d, long c);
    private static native boolean nDestroySurface(long d, long s);
    private static native boolean nMakeCurrent(long d, long draw, long read, long c);
    private static native long nGetCurrentContext();
    private static native long nGetCurrentDisplay();
    private static native long nGetCurrentSurface(int which);
    private static native boolean nQueryContext(long d, long c, int attr, int[] value, int off);
    private static native boolean nQuerySurface(long d, long s, int attr, int[] value, int off);
    private static native boolean nReleaseThread();
    private static native boolean nSwapBuffers(long d, long s);
}
