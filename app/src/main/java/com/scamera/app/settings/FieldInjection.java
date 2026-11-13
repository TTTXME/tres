package com.scamera.app.settings;

import com.scamera.app.ui.settings.custompreferences.TunablePngPreference;

import java.io.File;
import java.util.Arrays;

/**
 * Writes one stored setting into its annotated field. The two annotations read slightly differently, kept as they were:
 * <ul>
 * <li>a {@code @Tunable} list row of a number stores the number itself; a 0..1 whole-step flag is stored as 0/1; a String
 * list value must be one of its entries (else the field's own value, else the first entry); a File names a PNG of
 * {@link TunablePngPreference}'s folder;</li>
 * <li>a {@code @SensorConfig} list row stores text, read like a text row.</li>
 * </ul>
 * Numbers that are not list values are clamped to the annotation's range.
 */
final class FieldInjection {
    private FieldInjection() {}

    private static final String GLOBAL = SettingsManager.SCOPE_GLOBAL;

    /** @return the value written, for the log line; null when the field type is not handled */
    static Object write(SettingsManager m, Object target, FieldSpec s, String key, boolean perSensor)
            throws IllegalAccessException {
        boolean list = s.isList();
        boolean asText = s.isText() || (perSensor && list);
        String text = s.isText() || list ? SettingsManagerExtensions.getString(m, GLOBAL, key, null) : null;
        boolean blank = text == null || text.trim().isEmpty();
        float def = s.fallback();
        Class<?> t = s.type;

        if (t == float.class || t == Float.class) {
            float v = asText ? (blank ? def : (float) PreferenceNumber.read(text, def))
                    : SettingsManagerExtensions.getFloat(m, GLOBAL, key, def);
            if (!list) v = (float) bounded(s, v);
            s.field.setFloat(target, v);
            return v;
        }
        if (t == int.class || t == Integer.class) {
            int v = asText ? (blank ? (int) def : (int) PreferenceNumber.read(text, def))
                    : SettingsManagerExtensions.getInteger(m, GLOBAL, key, (int) def);
            if (!list) v = (int) bounded(s, v);
            s.field.setInt(target, v);
            return v;
        }
        if (t == double.class || t == Double.class) {
            double v = asText ? (blank ? def : PreferenceNumber.read(text, def))
                    : (double) SettingsManagerExtensions.getFloat(m, GLOBAL, key, def);
            if (!list) v = bounded(s, v);
            s.field.setDouble(target, v);
            return v;
        }
        if (s.isFlag()) {
            boolean on = def != 0f, v;
            if (asText) v = blank ? on : Boolean.parseBoolean(text) || "1".equals(text);
            else if (!perSensor && s.isSwitch()) v = SettingsManagerExtensions.getInteger(m, GLOBAL, key, on ? 1 : 0) != 0;
            else v = SettingsManagerExtensions.getBoolean(m, GLOBAL, key, on);
            s.field.setBoolean(target, v);
            return v;
        }
        if (t == String.class && !perSensor && list) {
            String v = blank ? (String) s.field.get(target) : text;
            if (!Arrays.asList(s.entryValues).contains(v)) v = s.entryValues[0];
            s.field.set(target, v);
            return v;
        }
        if (t == String.class && asText) {
            String v = blank ? String.valueOf((int) def) : text;
            s.field.set(target, v);
            return v;
        }
        if (t == File.class && !perSensor) {
            String name = m.getString(GLOBAL, key, null);
            File png = name == null || name.isEmpty() ? null : new File(TunablePngPreference.getPngsDir(m.getContext()), name);
            File v = png != null && png.exists() ? png : null;
            s.field.set(target, v);
            return String.valueOf(v);
        }
        return null;
    }

    /** In range; a non-finite value becomes the default first. */
    private static double bounded(FieldSpec s, double v) {
        return s.clamp(PreferenceNumber.read(v, s.fallback()));
    }
}
