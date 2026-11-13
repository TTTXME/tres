package com.scamera.app.processing.opengl.postpipeline;

import android.graphics.Point;
import android.graphics.Rect;
import android.util.Pair;
import android.util.SizeF;

import com.scamera.app.processing.render.ColorCorrectionTransform;
import com.scamera.app.processing.render.NoiseModeler;
import com.scamera.app.processing.render.Parameters;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * P277: the post pipeline's {@link Parameters} of a lab shot as JSON and back. The shot's metadata path
 * (fillFromFrame / resolveColour) needs the live CaptureResult, so the final values are kept: every instance field of a
 * plain type (numbers, text, arrays, Point, Rect, SizeF), the ProPhoto -&gt; sRGB matrix and the noise model. Buffers, the
 * hot pixels and the press-time objects (WB at the press, the DNG profile, the RAW crop area) are not: the post pipeline of
 * a hybrid shot does not read them.
 */
public final class ScamLabParams {
    private ScamLabParams() {}

    /** Fields never written: the worker RGB and the per-shot objects above. */
    static final Set<String> SKIPPED = new java.util.HashSet<>(java.util.Arrays.asList("scamRgb", "scamRgbOwned", "hotSites",
            "dngColour", "manualWbAtPress", "mosaicWbAtPress", "rawCrop", "sabreCrop", "dngNoiseProfile", "dngBaselineExposure"));

    /**
     * The JSON key of each field whose name changed since the format was made (P659): a lab shot keeps its keys whatever the
     * fields are called now, so older shots and newer builds read each other. A field not listed is stored under its own name.
     */
    static final Map<String, String> STORED_KEYS = storedKeys(
            "dynamicBlackUsed", "usedDynamic", "cfaBlack", "blackLevel", "asShotNeutral", "whitePoint",
            "shadingMapUsed", "hasGainMap", "shadingGrid", "mapSize", "activeArea", "sensorPix", "shadingMap", "gainMap",
            "proPhotoToSrgb", "proPhotoToSRGB", "cameraToProPhoto", "sensorToProPhoto", "hotSites", "hotPixels",
            "focalMm", "focalLength", "fNumber", "aperture", "rotationDeg", "cameraRotation", "noiseModel", "noiseModeler",
            "colorTransform", "CCT", "sensorSizeMm", "sensorSize", "toneCurves", "tonemapCurves",
            "refIlluminant1", "calibrationIlluminant1", "refIlluminant2", "calibrationIlluminant2",
            "cameraCalibration1", "calibrationTransform1", "cameraCalibration2", "calibrationTransform2",
            "forwardMatrix1", "ForwardTransform1", "forwardMatrix2", "ForwardTransform2",
            "colorMatrix1", "ColorMatrix1", "colorMatrix2", "ColorMatrix2", "mirrored", "mirror");

    private static Map<String, String> storedKeys(String... pairs) {
        final Map<String, String> out = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put(pairs[i], pairs[i + 1]);
        return java.util.Collections.unmodifiableMap(out);
    }

    /** The key {@code f} is stored under. */
    static String key(Field f) {
        final String stored = STORED_KEYS.get(f.getName());
        return stored != null ? stored : f.getName();
    }

    public static JSONObject toJson(Parameters p) throws JSONException {
        final JSONObject out = new JSONObject();
        for (Field f : fields()) {
            try {
                final Object v = f.get(p);
                if (v == null) continue;
                final Object j = encode(v);
                if (j != null) out.put(key(f), j);
            } catch (IllegalAccessException ignored) {
            }
        }
        return out;
    }

    /** A Parameters with the values of {@link #toJson}; fields it lacks keep their defaults. */
    public static Parameters fromJson(JSONObject in) throws JSONException {
        final Parameters p = new Parameters();
        for (Field f : fields()) {
            final String key = key(f);
            if (!in.has(key)) continue;
            try {
                final Object v = decode(f.getType(), in.get(key), f.get(p));
                if (v != null) f.set(p, v);
            } catch (IllegalAccessException | IllegalArgumentException ignored) {
            }
        }
        p.hotSites = new Point[0];
        return p;
    }

    private static java.util.List<Field> fields() {
        final java.util.List<Field> out = new java.util.ArrayList<>();
        for (Field f : Parameters.class.getDeclaredFields()) {
            final int m = f.getModifiers();
            if (Modifier.isStatic(m) || Modifier.isFinal(m) || Modifier.isTransient(m) || SKIPPED.contains(f.getName()) || f.isSynthetic())
                continue;
            f.setAccessible(true);
            out.add(f);
        }
        return out;
    }

    static Object encode(Object v) throws JSONException {
        if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof String) return v;
        if (v instanceof Byte || v instanceof Short) return ((Number) v).intValue();
        if (v instanceof Float) return finite(((Float) v).doubleValue());
        if (v instanceof Double) return finite((Double) v);
        if (v instanceof float[]) { JSONArray a = new JSONArray(); for (float x : (float[]) v) a.put(finite(x)); return a; }
        if (v instanceof double[]) { JSONArray a = new JSONArray(); for (double x : (double[]) v) a.put(finite(x)); return a; }
        if (v instanceof int[]) { JSONArray a = new JSONArray(); for (int x : (int[]) v) a.put(x); return a; }
        if (v instanceof float[][]) {
            JSONArray a = new JSONArray();
            for (float[] row : (float[][]) v) a.put(row == null ? JSONObject.NULL : encode(row));
            return a;
        }
        if (v instanceof Point) return new JSONObject().put("x", ((Point) v).x).put("y", ((Point) v).y);
        if (v instanceof Rect) {
            Rect r = (Rect) v;
            return new JSONObject().put("l", r.left).put("t", r.top).put("r", r.right).put("b", r.bottom);
        }
        if (v instanceof SizeF) return new JSONObject().put("w", finite(((SizeF) v).getWidth())).put("h", finite(((SizeF) v).getHeight()));
        if (v instanceof ColorCorrectionTransform) return new JSONObject().put("matrix", encode(((ColorCorrectionTransform) v).matrix));
        if (v instanceof NoiseModeler) {
            NoiseModeler n = (NoiseModeler) v;
            return new JSONObject().put("base", pairs(n.rawModel)).put("compute", pairs(n.stackModel))
                    .put("analog", n.analogIso).put("iso", n.reportedIso);
        }
        return null;
    }

    static Object decode(Class<?> type, Object j, Object current) throws JSONException {
        if (j == JSONObject.NULL) return null;
        if (type == boolean.class) return (Boolean) j;
        if (type == int.class) return ((Number) j).intValue();
        if (type == long.class) return ((Number) j).longValue();
        if (type == byte.class) return (byte) ((Number) j).intValue();
        if (type == short.class) return (short) ((Number) j).intValue();
        if (type == float.class) return (float) number(j);
        if (type == double.class) return number(j);
        if (type == String.class) return j.toString();
        if (type == float[].class) { JSONArray a = (JSONArray) j; float[] v = new float[a.length()]; for (int i = 0; i < v.length; i++) v[i] = (float) number(a.get(i)); return v; }
        if (type == double[].class) { JSONArray a = (JSONArray) j; double[] v = new double[a.length()]; for (int i = 0; i < v.length; i++) v[i] = number(a.get(i)); return v; }
        if (type == int[].class) { JSONArray a = (JSONArray) j; int[] v = new int[a.length()]; for (int i = 0; i < v.length; i++) v[i] = a.getInt(i); return v; }
        if (type == float[][].class) {
            JSONArray a = (JSONArray) j;
            float[][] v = new float[a.length()][];
            for (int i = 0; i < v.length; i++) v[i] = (float[]) decode(float[].class, a.get(i), null);
            return v;
        }
        if (type == Point.class) { JSONObject o = (JSONObject) j; return new Point(o.getInt("x"), o.getInt("y")); }
        if (type == Rect.class) { JSONObject o = (JSONObject) j; return new Rect(o.getInt("l"), o.getInt("t"), o.getInt("r"), o.getInt("b")); }
        if (type == SizeF.class) { JSONObject o = (JSONObject) j; return new SizeF((float) o.getDouble("w"), (float) o.getDouble("h")); }
        if (type == ColorCorrectionTransform.class) {
            ColorCorrectionTransform t = new ColorCorrectionTransform();
            t.matrix = (float[]) decode(float[].class, ((JSONObject) j).get("matrix"), null);
            return t;
        }
        if (type == NoiseModeler.class) {
            JSONObject o = (JSONObject) j;
            Pair<Double, Double>[] base = pairs(o.getJSONArray("base")), compute = pairs(o.getJSONArray("compute"));
            NoiseModeler n = new NoiseModeler(base, o.getInt("analog"), o.getInt("iso"), 0);
            n.rawModel = base;
            n.stackModel = compute;
            return n;
        }
        return null;
    }

    private static Object finite(double v) {
        return Double.isFinite(v) ? (Object) v : (Object) (Double.isNaN(v) ? "NaN" : v > 0 ? "Infinity" : "-Infinity");
    }

    private static double number(Object j) {
        if (j instanceof Number) return ((Number) j).doubleValue();
        return Double.parseDouble(j.toString());
    }

    private static JSONArray pairs(Pair<Double, Double>[] model) throws JSONException {
        JSONArray a = new JSONArray();
        if (model != null) for (Pair<Double, Double> p : model) a.put(new JSONArray().put(finite(p.first)).put(finite(p.second)));
        return a;
    }

    @SuppressWarnings("unchecked")
    private static Pair<Double, Double>[] pairs(JSONArray a) throws JSONException {
        Pair<Double, Double>[] out = new Pair[a.length()];
        for (int i = 0; i < out.length; i++) out[i] = new Pair<>(number(a.getJSONArray(i).get(0)), number(a.getJSONArray(i).get(1)));
        return out;
    }

    // ───── The default preferences of the shot ─────

    /** Every stored default preference with its type ({"s"|"b"|"i"|"f"|"l"|"set", value}). */
    public static JSONObject prefs(Map<String, ?> all) throws JSONException {
        final JSONObject out = new JSONObject();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            final Object v = e.getValue();
            final String t = v instanceof Boolean ? "b" : v instanceof Integer ? "i" : v instanceof Float ? "f" : v instanceof Long ? "l"
                    : v instanceof Set ? "set" : v instanceof String ? "s" : null;
            if (t == null) continue;
            Object value = v;
            if (v instanceof Set) { JSONArray a = new JSONArray(); for (Object x : (Set<?>) v) a.put(String.valueOf(x)); value = a; }
            else if (v instanceof Float) value = finite((Float) v);
            out.put(e.getKey(), new JSONArray().put(t).put(value));
        }
        return out;
    }

    /** {@link #prefs}'s values back, typed as SharedPreferences returns them. */
    public static Map<String, Object> prefs(JSONObject in) throws JSONException {
        final Map<String, Object> out = new HashMap<>();
        for (Iterator<String> it = in.keys(); it.hasNext(); ) {
            final String key = it.next();
            final JSONArray e = in.getJSONArray(key);
            final String t = e.getString(0);
            switch (t) {
                case "b": out.put(key, e.getBoolean(1)); break;
                case "i": out.put(key, e.getInt(1)); break;
                case "f": out.put(key, (float) number(e.get(1))); break;
                case "l": out.put(key, e.getLong(1)); break;
                case "set": {
                    JSONArray a = e.getJSONArray(1);
                    Set<String> s = new java.util.HashSet<>();
                    for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
                    out.put(key, s);
                    break;
                }
                default: out.put(key, e.getString(1));
            }
        }
        return out;
    }
}
