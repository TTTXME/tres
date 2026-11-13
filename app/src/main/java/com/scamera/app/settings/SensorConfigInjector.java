package com.scamera.app.settings;

import com.scamera.app.app.ScameraApp;
import com.scamera.app.util.Log;

import java.util.List;

/**
 * Fills an object's {@code @SensorConfig} fields (CaptureController, Parameters) with the values stored for one physical
 * camera, so tuning follows the module in use.
 */
public final class SensorConfigInjector {
    private static final String TAG = "SensorConfigInjector";

    private SensorConfigInjector() {}

    /** @param sensorId physical camera id ("0", "1", ...) or module slot */
    public static void applyToSensor(String sensorId, Object target) {
        if (target == null || sensorId == null || sensorId.isEmpty()) {
            Log.w(TAG, "nothing to fill (target " + target + ", sensor " + sensorId + ")");
            return;
        }
        SettingsManager m = ScameraApp.getSettingsManagerStatic();
        if (m == null) {
            Log.w(TAG, "no settings yet");
            return;
        }
        List<FieldSpec> fields = FieldSpec.sensorConfigs(target.getClass());
        String prefix = ModuleSensorSettings.prefix(ModuleSensorSettings.runtimeScope(sensorId));
        StringBuilder done = new StringBuilder(target.getClass().getSimpleName()).append(" [").append(sensorId).append("]:");
        for (FieldSpec s : fields) {
            try {
                Object v = FieldInjection.write(m, target, s, prefix + s.keyTail, true);
                if (v == null) Log.w(TAG, "unsupported field " + s.name + " (" + s.type + ")");
                else done.append(' ').append(s.name).append('=').append(v);
            } catch (Exception e) {
                Log.e(TAG, "could not set " + s.name, e);
            }
        }
        Log.d(TAG, done.toString());
    }
}
