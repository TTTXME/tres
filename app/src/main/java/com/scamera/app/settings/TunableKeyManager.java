package com.scamera.app.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CaptureRequest;

import androidx.preference.PreferenceManager;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.scamera.app.api.VendorTagUtils;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.util.Log;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The user's camera keys ({@link VendorTagUtils.TunableKey}) of each module, a JSON list under
 * {@code pref_sensorconfig_<scope>_tunablekeys}; applied to the module's requests and session parameters.
 */
public final class TunableKeyManager {
    private static final String TAG = "CameraKeys";
    private static final Gson GSON = new Gson();
    private static final Type LIST = new TypeToken<List<VendorTagUtils.TunableKey>>() {}.getType();
    /** The last signature of each stored JSON text: sessions ask for it several times per open, the text rarely changes. */
    private static final Map<String, String> SIGNATURES = new ConcurrentHashMap<>();

    private TunableKeyManager() {}

    private static String keyOf(String scope) {
        return "pref_sensorconfig_" + scope + "_tunablekeys";
    }

    private static String storedJson(Context context, String scope) {
        return PreferenceManager.getDefaultSharedPreferences(context).getString(keyOf(scope), null);
    }

    private static List<VendorTagUtils.TunableKey> parse(String json, String scope) {
        if (json == null || json.isEmpty()) return new ArrayList<>();
        try {
            List<VendorTagUtils.TunableKey> keys = GSON.fromJson(json, LIST);
            return keys == null ? new ArrayList<>() : keys;
        } catch (RuntimeException broken) {
            Log.w(TAG, "unreadable key list of " + scope, broken);
            return new ArrayList<>();
        }
    }

    /** A fresh copy of the scope's keys (the caller may edit it); empty when none or unreadable. */
    public static List<VendorTagUtils.TunableKey> loadKeys(Context context, String scope) {
        if (context == null || scope == null) return new ArrayList<>();
        return parse(storedJson(context, scope), scope);
    }

    public static void saveKeys(Context context, String scope, List<VendorTagUtils.TunableKey> keys) {
        if (context == null || scope == null || keys == null) return;
        String json = GSON.toJson(keys);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        // Applying the keys saves their tested/supported flags on every request; unchanged flags need no write.
        if (!json.equals(prefs.getString(keyOf(scope), null))) prefs.edit().putString(keyOf(scope), json).apply();
    }

    private static Context appContext() {
        SettingsManager m = ScameraApp.getSettingsManagerStatic();
        return m == null ? null : m.getContext();
    }

    /**
     * The request keys a session of this physical sensor applies, as "name=value;" pairs ("" without any): the sensor mode of
     * the stream depends on them (OPPO engineer-mode select, vendor remosaic switches), so they key its measured colour block.
     */
    public static String signature(String physicalId) {
        Context context = appContext();
        if (context == null || physicalId == null || physicalId.isEmpty()) return "";
        String scope = ModuleSensorSettings.runtimeScope(physicalId);
        String json = storedJson(context, scope);
        if (json == null || json.isEmpty()) return "";
        if (SIGNATURES.size() > 64) SIGNATURES.clear(); // a few modules' lists, rewritten as their flags change
        return SIGNATURES.computeIfAbsent(json, j -> {
            StringBuilder pairs = new StringBuilder();
            for (VendorTagUtils.TunableKey k : parse(j, scope)) {
                boolean sent = k != null && k.enabled
                        && ("CaptureRequest".equals(k.type) || VendorTagUtils.TunableKey.TYPE_SESSION.equals(k.type));
                if (sent) pairs.append(k.name).append('=').append(k.value).append(';');
            }
            return pairs.toString();
        });
    }

    /**
     * Puts the module's request keys on {@code builder} and saves what the builder told about each (tested, supported), which
     * the settings rows show green or red.
     */
    public static void applyTunableKeys(CaptureRequest.Builder builder, String physicalId) {
        apply(builder, physicalId, false);
    }

    /** P122: the module's session-only keys (TunableKey.TYPE_SESSION) on the builder of the session parameters. */
    public static void applySessionKeys(CaptureRequest.Builder builder, String physicalId) {
        apply(builder, physicalId, true);
    }

    private static void apply(CaptureRequest.Builder builder, String physicalId, boolean session) {
        Context context = appContext();
        if (context == null || builder == null || physicalId == null || physicalId.isEmpty()) return;
        String scope = ModuleSensorSettings.runtimeScope(physicalId);
        List<VendorTagUtils.TunableKey> keys = loadKeys(context, scope);
        if (keys.isEmpty()) return;
        if (session) VendorTagUtils.applyTunableKeys(builder, keys, physicalId, true);
        else VendorTagUtils.applyTunableKeys(builder, keys, physicalId);
        saveKeys(context, scope, keys);
    }
}
