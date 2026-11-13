package com.scamera.app.api;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;

import com.scamera.app.capture.CaptureController;
import com.scamera.app.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * Writes into the camera framework's own metadata objects (a characteristic a HAL declares wrongly, a key a result lacks) and
 * lists every characteristics key, vendor ones included. The members are reached through {@link NativeEngine}, which keeps each
 * lookup for the process (P214a).
 */
public final class CameraReflectionApi {
    private static final String TAG = "CameraAPI";

    private CameraReflectionApi() {
    }

    /** Every key the characteristics hold (CameraMetadata.getKeys, hidden): vendor keys and, if asked, synthetic ones; null on failure. */
    @SuppressWarnings("unchecked")
    public static ArrayList<Object> getCameraCharacteristicsKeys(CameraCharacteristics characteristics, int[] filterTags,
                                                                 boolean includeSynthetic) {
        try {
            final Class<?> keyType = Class.forName("android.hardware.camera2.CameraCharacteristics$Key");
            final Method getKeys = NativeEngine.findMethod(CameraMetadata.class, "getKeys",
                    Class.class, Class.class, CameraMetadata.class, int[].class, boolean.class);
            if (getKeys == null) {
                Log.e(TAG, "Failed to find getKeys method");
                return null;
            }
            getKeys.setAccessible(true);
            final ArrayList<Object> keys = (ArrayList<Object>) getKeys.invoke(characteristics,
                    characteristics.getClass(), keyType, characteristics, filterTags, includeSynthetic);
            Log.i(TAG, "characteristics keys listed: " + (keys == null ? 0 : keys.size()));
            return keys;
        } catch (Exception e) {
            Log.e(TAG, "Error calling getCameraCharacteristicsKeys: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Calls {@code setter(key, value)} on the CameraMetadataNative held in {@code field} of {@code owner}; a failure is logged
     * with {@code what}.
     */
    private static void setNative(Class<?> ownerType, String field, Object owner, String setter, Class<?> keyType,
                                  Object key, Object value, String what) {
        try {
            final Field holder = NativeEngine.findField(ownerType, field);
            holder.setAccessible(true);
            final Object metadata = holder.get(owner);
            if (metadata == null) throw new IllegalStateException("no metadata in " + field);
            final Method set = NativeEngine.findMethod(metadata.getClass(), setter, keyType, Object.class);
            set.setAccessible(true);
            set.invoke(metadata, key, value);
        } catch (Exception e) {
            Log.d(TAG, "Failed to set " + what + " key:" + Log.getStackTraceString(e));
        }
    }

    /** A characteristic as the app will read it (the framework's base value: what the HAL declared is overridden). */
    public static <T> void set(CameraCharacteristics characteristics, CameraCharacteristics.Key<T> key, T value) {
        setNative(CameraCharacteristics.class, "mProperties", characteristics, "setBase", CameraCharacteristics.Key.class, key, value,
                "CameraCharacteristics");
    }

    /** A key of the last still's result (CaptureController.mCaptureResult). */
    public static <T> void set(CaptureResult.Key<T> key, T value) {
        set(key, value, CaptureController.mCaptureResult);
    }

    /** A key of {@code result}. */
    public static <T> void set(CaptureResult.Key<T> key, T value, CaptureResult result) {
        setNative(CaptureResult.class, "mResults", result, "set", CaptureResult.Key.class, key, value, "CaptureResult");
    }

    /** A key of a built request's logical camera settings. */
    public static <T> void set(CaptureRequest request, CaptureRequest.Key<T> key, T value) {
        setNative(CaptureRequest.class, "mLogicalCameraSettings", request, "set", CaptureRequest.Key.class, key, value, "CaptureRequest");
    }

    /**
     * P192: the result's metadata field and its setter, looked up once (NativeEngine added JNI global references per lookup).
     * P214a: NativeEngine now keeps every lookup itself (ReflectionCache) and its native side leaves no global reference;
     * this per-class check of the setter stays as it was.
     */
    private static volatile Field resultsField;
    private static volatile Method resultsSet;

    /**
     * P192: {@link #set(CaptureResult.Key, Object, CaptureResult)} with the field and the method looked up once. Per-result calls
     * of the other one (the OPPO native viewfinder's every result) filled the JNI global reference table in about two minutes and
     * the runtime aborted the process (OPPO Find X7 Ultra, 2026-10-17). False when the value could not be set.
     */
    public static <T> boolean setCached(CaptureResult.Key<T> key, T value, CaptureResult res) {
        try {
            Field field = resultsField;
            if (field == null) {
                field = NativeEngine.findField(CaptureResult.class, "mResults");
                field.setAccessible(true);
                resultsField = field;
            }
            final Object metadata = field.get(res);
            if (metadata == null) return false;
            Method set = resultsSet;
            if (set == null || set.getDeclaringClass() != metadata.getClass()) {
                set = NativeEngine.findMethod(metadata.getClass(), "set", CaptureResult.Key.class, Object.class);
                set.setAccessible(true);
                resultsSet = set;
            }
            set.invoke(metadata, key, value);
            return true;
        } catch (Exception e) {
            Log.d(TAG, "Failed to set CaptureResult key " + key.getName() + ": " + e);
            return false;
        }
    }
}
