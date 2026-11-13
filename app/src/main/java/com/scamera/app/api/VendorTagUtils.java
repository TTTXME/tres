package com.scamera.app.api;

import android.annotation.SuppressLint;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;

import com.scamera.app.util.Log;

import java.lang.reflect.Array;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vendor request keys: the module's own keys («Добавить vendor tag», stored as {@link TunableKey}s) and the keys every request
 * of a session carries (builderSessionApply).
 */
public final class VendorTagUtils {
    private static final String TAG = "VendorTagUtils";
    private static volatile boolean clientNameSkipLogged;

    private VendorTagUtils() {
    }

    /** The builder knows the key (an unknown vendor tag throws on get). */
    private static boolean knows(CaptureRequest.Builder builder, CaptureRequest.Key<?> key) {
        try {
            builder.get(key);
        } catch (IllegalArgumentException unknown) {
            Log.w(TAG, "vendor tag " + key.getName() + " is not supported");
            return false;
        }
        Log.d(TAG, "vendor tag " + key.getName() + " is supported");
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void put(CaptureRequest.Builder builder, CaptureRequest.Key key, Object value) {
        builder.set(key, value);
    }

    @SuppressLint("NewApi")
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void putPhysical(CaptureRequest.Builder builder, CaptureRequest.Key key, Object value, String physicalId) {
        try {
            builder.setPhysicalCameraKey(key, value, physicalId);
        } catch (Exception e) {
            Log.w(TAG, "Error setting physical camera key: " + key.getName() + " for camera: " + physicalId, e);
        }
    }

    /**
     * A user-defined vendor tag of a module (Sensor Configurations settings): a request, session or result key with its value
     * type and value as text. The field names are the stored format (the module's key list).
     */
    public static class TunableKey {
        /** P122: a key set in the session parameters only, never in a request (vivo X300 Ultra's forceSensorMode). */
        public static final String TYPE_SESSION = "Session";
        /** The value types by their stored names, in the order the settings offer them. */
        private static final Map<String, Class<?>> TYPES = new LinkedHashMap<>();

        static {
            TYPES.put("Integer", Integer.class);
            TYPES.put("Long", Long.class);
            TYPES.put("Float", Float.class);
            TYPES.put("Double", Double.class);
            TYPES.put("Byte", Byte.class);
            TYPES.put("Short", Short.class);
            TYPES.put("int[]", int[].class);
            TYPES.put("byte[]", byte[].class);
            TYPES.put("long[]", long[].class);
            TYPES.put("float[]", float[].class);
            TYPES.put("String", String.class);
        }

        /** "CaptureRequest", {@link #TYPE_SESSION} or "CaptureResult" */
        public String type = "CaptureRequest";
        /** Vendor tag string, e.g. "com.qti.sensorbps.mode_index" */
        public String name = "";
        /** "Integer", "Long", "Float", "Byte", "int[]", ... */
        public String valueType = "Integer";
        /** Value as a string (comma separated for arrays) */
        public String value = "0";
        /** The camera knew the key the last time it was sent. */
        public boolean supported = false;
        /** The key was sent at least once (only a capture can tell whether it is known). */
        public boolean tested = false;
        /** P122: off = kept in the list but not sent (module settings switch); older lists have no field: on. */
        public boolean enabled = true;

        public TunableKey() {
        }

        public TunableKey(String type, String name, Class<?> valueClass, Object value) {
            this.type = type;
            this.name = name;
            valueType = valueTypeForClass(valueClass);
            this.value = valueToString(valueClass, value);
        }

        public Object parseValue() {
            return parseValue(valueType, value);
        }

        public CaptureRequest.Key<?> toCaptureRequestKey() {
            return new CaptureRequest.Key<>(name, classForValueType(valueType));
        }

        public CaptureResult.Key<?> toCaptureResultKey() {
            return new CaptureResult.Key<>(name, classForValueType(valueType));
        }

        /** The stored name of a value class ("Integer" for null or one not offered). */
        public static String valueTypeForClass(Class<?> valueClass) {
            for (Map.Entry<String, Class<?>> type : TYPES.entrySet()) if (type.getValue() == valueClass) return type.getKey();
            return "Integer";
        }

        /** The class of a stored value type (Integer for null or an unknown name). */
        public static Class<?> classForValueType(String valueType) {
            final Class<?> known = valueType == null ? null : TYPES.get(valueType);
            return known != null ? known : Integer.class;
        }

        /** A value as stored: arrays comma separated, null as "0". */
        public static String valueToString(Class<?> valueClass, Object value) {
            if (value == null) return "0";
            if (valueClass == null || !valueClass.isArray()) return String.valueOf(value);
            final StringBuilder text = new StringBuilder();
            for (int i = 0, n = Array.getLength(value); i < n; i++) {
                if (i > 0) text.append(',');
                text.append(Array.get(value, i));
            }
            return text.toString();
        }

        /** The stored text as a value of its type (an array from its comma-separated items); throws on text of another type. */
        public static Object parseValue(String valueType, String value) {
            final String text = value == null ? "" : value;
            final String type = valueType == null ? "Integer" : valueType;
            if (type.endsWith("[]")) {
                final String[] items = text.split(",");
                switch (type) {
                    case "int[]": {
                        final int[] out = new int[items.length];
                        for (int i = 0; i < items.length; i++) out[i] = Integer.parseInt(items[i].trim());
                        return out;
                    }
                    case "byte[]": {
                        final byte[] out = new byte[items.length];
                        for (int i = 0; i < items.length; i++) out[i] = Byte.parseByte(items[i].trim());
                        return out;
                    }
                    case "long[]": {
                        final long[] out = new long[items.length];
                        for (int i = 0; i < items.length; i++) out[i] = Long.parseLong(items[i].trim());
                        return out;
                    }
                    case "float[]": {
                        final float[] out = new float[items.length];
                        for (int i = 0; i < items.length; i++) out[i] = Float.parseFloat(items[i].trim());
                        return out;
                    }
                    default:
                        break; // not an array type we offer: read as an Integer below, as before
                }
            }
            if (type.equals("String")) return text;
            final String number = text.trim();
            switch (type) {
                case "Long": return Long.parseLong(number);
                case "Float": return Float.parseFloat(number);
                case "Double": return Double.parseDouble(number);
                case "Byte": return Byte.parseByte(number);
                case "Short": return Short.parseShort(number);
                default: return Integer.parseInt(number);
            }
        }
    }

    /**
     * The module's request keys on a request builder: each enabled key is tried (the builder tells whether the camera knows it)
     * and set when known; its {@code tested} / {@code supported} flags are updated in place for the settings' green / red marks.
     */
    public static void applyTunableKeys(CaptureRequest.Builder builder, List<TunableKey> keys, String physicalId) {
        applyTunableKeys(builder, keys, physicalId, false);
    }

    /**
     * {@code session}: the builder of the session parameters, which takes the {@link TunableKey#TYPE_SESSION} keys only (the
     * request keys reach it through builderSessionApply); otherwise the request keys, the session keys skipped.
     */
    public static void applyTunableKeys(CaptureRequest.Builder builder, List<TunableKey> keys, String physicalId, boolean session) {
        if (builder == null || keys == null) return;
        final boolean physical = !session && physicalId != null && !physicalId.isEmpty();
        for (TunableKey tunable : keys) {
            if (tunable == null || !tunable.enabled || TunableKey.TYPE_SESSION.equals(tunable.type) != session) continue;
            tunable.tested = true;
            if (!"CaptureRequest".equals(tunable.type) && !TunableKey.TYPE_SESSION.equals(tunable.type)) {
                Log.w(TAG, "Tunable key " + tunable.name + " is a " + tunable.type + " key, cannot be applied to the request builder");
                tunable.supported = false;
                continue;
            }
            try {
                final CaptureRequest.Key<?> key = tunable.toCaptureRequestKey();
                tunable.supported = knows(builder, key);
                if (!tunable.supported) continue;
                final Object parsed = tunable.parseValue();
                put(builder, key, parsed);
                // the session parameters take the key as it is (as MotionCam sets its session keys), no physical copy
                if (physical) putPhysical(builder, key, parsed, physicalId);
                Log.d(TAG, "Applied tunable key " + tunable.name + " = " + tunable.value + " (" + tunable.valueType + ")");
            } catch (Exception e) {
                tunable.supported = false;
                Log.w(TAG, "Error applying tunable key " + tunable.name, e);
            }
        }
    }

    /**
     * The keys every request of a session (and the session parameters) carry: the maximum-resolution pixel mode where asked,
     * the module's keys, then the routes' own keys in this order.
     */
    @SuppressLint({"NewApi", "LocalSuppress"})
    public static void builderSessionApply(CaptureRequest.Builder builder, boolean burst, boolean useMaximumResolutionKey, String physicalId) {
        // Owner: no impersonation of the stock camera on any phone. com.xiaomi.sessionparams.clientName = com.android.camera is
        // never sent: the 17 Ultra's HAL still knew the real package (isThirdParty = 1), and on the 13 Ultra (2026-10-10 log)
        // the sessions that carried it stopped delivering frames.
        if (!clientNameSkipLogged) {
            clientNameSkipLogged = true;
            Log.i(TAG, "com.xiaomi.sessionparams.clientName not sent (no stock-camera impersonation)");
        }
        if (useMaximumResolutionKey) builder.set(CaptureRequest.SENSOR_PIXEL_MODE, CaptureRequest.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
        com.scamera.app.settings.TunableKeyManager.applyTunableKeys(builder, physicalId);
        // P103: the HAL keys of «Движение» / Ideal RAW, on the session and every request
        final android.hardware.camera2.CameraCharacteristics lens = com.scamera.app.capture.CaptureController.mCameraCharacteristics;
        com.scamera.app.capture.HiddenFeatures.apply(builder, lens);
        com.scamera.app.capture.VivoQcomModes.apply(builder, lens, physicalId);
        com.scamera.app.capture.VivoPd2454Isz.mirrorRequest(builder, physicalId);
        // P274: the X300 Ultra's forceSensorMode is a session key the camera service drops when a request lacks it
        com.scamera.app.capture.VivoQcomIsz.mirrorRequest(builder);
        // P276: the module's picked SensorModeTable row on the session parameters and every request
        com.scamera.app.capture.SensorModePick.apply(builder);
        com.scamera.app.capture.VivoPd2454NativeRaw.apply(builder, physicalId);
        // P598: the nubia remosaic probe's setting of the maximum-resolution FULLRES session (none outside it); P626: a still
        // (burst) takes the way's snapshot keys too
        com.scamera.app.capture.NubiaRemosaicProbe.apply(builder, burst);
        // P635: a full-resolution recipe's tags of every request (and of the stills) - none outside its session
        com.scamera.app.capture.FullResRecipeSession.applyRequest(builder, burst);
    }
}
