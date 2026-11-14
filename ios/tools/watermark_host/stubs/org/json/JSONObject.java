package org.json;
/** Host stub (ios/tools/watermark_host): enough for WatermarkSpec to compile; its JSON is not used by the checks. */
public class JSONObject {
    private final java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
    public JSONObject() {}
    public JSONObject(String json) throws JSONException { throw new JSONException("not parsed on the host"); }
    public JSONObject put(String k, Object v) throws JSONException { map.put(k, v); return this; }
    public JSONObject put(String k, boolean v) throws JSONException { map.put(k, v); return this; }
    public JSONObject put(String k, double v) throws JSONException { map.put(k, v); return this; }
    public String optString(String k, String f) { Object v = map.get(k); return v == null ? f : v.toString(); }
    public boolean optBoolean(String k, boolean f) { Object v = map.get(k); return v instanceof Boolean ? (Boolean) v : f; }
    public double optDouble(String k, double f) { Object v = map.get(k); return v instanceof Number ? ((Number) v).doubleValue() : f; }
    @Override public String toString() { return map.toString(); }
}
