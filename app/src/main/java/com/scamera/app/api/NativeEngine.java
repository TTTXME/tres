package com.scamera.app.api;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Hidden framework members for the camera code, reached through JNI (camera2native: its own exemption from the hidden-API
 * checks), with plain reflection as the fallback. Every lookup is kept for the process (P214a, {@link ReflectionCache}).
 */
public class NativeEngine {
    private static final String TAG = "NativeEngine";
    private static boolean initialized;
    /** P214a: resolved methods and fields (see {@link ReflectionCache}). */
    private static final ReflectionCache LOOKUPS = new ReflectionCache();

    static {
        System.loadLibrary("camera2native");
    }

    /** Sets up the hidden-API exemption once (any lookup does it first). */
    public static synchronized void initialize() {
        if (initialized) return;
        Log.d(TAG, "hidden-API exemption set up");
        nativeInitialize();
        initialized = true;
    }

    /** A method of {@code clazz} by name and parameter types, accessible; null when there is none. */
    public static Method getCameraMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        initialize();
        if (clazz == null || methodName == null) {
            Log.e(TAG, "method lookup without a class or a name");
            return null;
        }
        // P214a: one lookup per (class, name, parameter types) for the process; a failed one is tried again next time.
        return LOOKUPS.method(clazz, methodName, parameterTypes, NativeEngine::lookUpMethod);
    }

    private static Method lookUpMethod(Class<?> clazz, String methodName, Class<?>[] parameterTypes) {
        try {
            final Method viaJni = nativeGetCameraMethod(clazz, methodName, parameterTypes);
            if (viaJni != null) return viaJni;
            Log.d(TAG, "no JNI method " + methodName + ": plain reflection");
            final Method reflected = clazz.getDeclaredMethod(methodName, parameterTypes);
            reflected.setAccessible(true);
            return reflected;
        } catch (Exception e) {
            Log.e(TAG, "method " + methodName + " not reachable", e);
            return null;
        }
    }

    /** A field of {@code clazz} by name, accessible; null when there is none. */
    public static Field getCameraField(Class<?> clazz, String fieldName) {
        initialize();
        if (clazz == null || fieldName == null) {
            Log.e(TAG, "field lookup without a class or a name");
            return null;
        }
        // P214a: one lookup per (class, name) for the process, as for methods.
        return LOOKUPS.field(clazz, fieldName, NativeEngine::lookUpField);
    }

    private static Field lookUpField(Class<?> clazz, String fieldName) {
        try {
            final Field viaJni = nativeGetCameraField(clazz, fieldName);
            if (viaJni != null) return viaJni;
            Log.d(TAG, "no JNI field " + fieldName + ": plain reflection");
            final Field reflected = clazz.getDeclaredField(fieldName);
            reflected.setAccessible(true);
            return reflected;
        } catch (Exception e) {
            Log.e(TAG, "field " + fieldName + " not reachable", e);
            return null;
        }
    }

    // camera2native's entry points (JNI names: Java_com_scamera_app_api_NativeEngine_*)
    private static native void nativeInitialize();

    private static native Method nativeGetCameraMethod(Class<?> clazz, String methodName, Class<?>[] parameterTypes);

    private static native Field nativeGetCameraField(Class<?> clazz, String fieldName);
}
