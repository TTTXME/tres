package android.content;
/** Host stub (ios/tools/watermark_host): the reads PreferenceValue makes. */
public interface SharedPreferences {
    boolean contains(String key);
    String getString(String key, String fallback);
    boolean getBoolean(String key, boolean fallback);
    int getInt(String key, int fallback);
    float getFloat(String key, float fallback);
    long getLong(String key, long fallback);
    java.util.Set<String> getStringSet(String key, java.util.Set<String> fallback);
}
