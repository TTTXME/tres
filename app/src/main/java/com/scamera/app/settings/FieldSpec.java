package com.scamera.app.settings;

import com.scamera.app.settings.annotations.SensorConfig;
import com.scamera.app.settings.annotations.Tunable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One {@link Tunable} or {@link SensorConfig} field, read once: the annotation's values in one shape for the settings rows
 * and for the injectors. The fields of a class are scanned once per process (they never change at run time); before this
 * every GL node construction and every capture walked all declared fields of its class again.
 */
final class FieldSpec {
    /** The annotations' «no default given» value is NaN (the field's initial value is the default). */

    final Field field;
    final String name;
    /** The field name in lower case, the tail of its preference key. */
    final String keyTail;
    final Class<?> type;
    final String title, description, category;
    final float min, max, step, declared;
    final String[] entries, entryValues;
    final int[] pngEdges;

    private FieldSpec(Field f, String title, String description, String category, float min, float max, float step,
                      float declared, String[] entries, String[] entryValues, int[] pngEdges) {
        field = f;
        name = f.getName();
        keyTail = name.toLowerCase(Locale.ROOT);
        type = f.getType();
        this.title = title;
        this.description = description;
        this.category = category;
        this.min = min;
        this.max = max;
        this.step = step;
        this.declared = declared;
        this.entries = entries;
        this.entryValues = entryValues;
        this.pngEdges = pngEdges;
    }

    private static final Map<Class<?>, List<FieldSpec>> TUNABLE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, List<FieldSpec>> SENSOR = new ConcurrentHashMap<>();

    /** The {@code @Tunable} fields declared by {@code c} itself, in declaration order, made accessible. */
    static List<FieldSpec> tunables(Class<?> c) {
        return TUNABLE.computeIfAbsent(c, k -> scan(k, false));
    }

    /** The {@code @SensorConfig} fields declared by {@code c} itself, made accessible. */
    static List<FieldSpec> sensorConfigs(Class<?> c) {
        return SENSOR.computeIfAbsent(c, k -> scan(k, true));
    }

    private static List<FieldSpec> scan(Class<?> c, boolean sensor) {
        List<FieldSpec> out = new ArrayList<>();
        for (Field f : c.getDeclaredFields()) {
            FieldSpec s = sensor ? ofSensor(f) : ofTunable(f);
            if (s == null) continue;
            f.setAccessible(true);
            out.add(s);
        }
        return out.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(out);
    }

    private static FieldSpec ofTunable(Field f) {
        Tunable t = f.getAnnotation(Tunable.class);
        return t == null ? null : new FieldSpec(f, t.title(), t.description(), t.category(), t.min(), t.max(), t.step(),
                t.defaultValue(), t.entries(), t.entryValues(), t.pngEdges());
    }

    private static FieldSpec ofSensor(Field f) {
        SensorConfig s = f.getAnnotation(SensorConfig.class);
        return s == null ? null : new FieldSpec(f, s.title(), s.description(), "", s.min(), s.max(), s.step(),
                s.defaultValue(), s.entries(), s.entryValues(), new int[0]);
    }

    /** The default a row and the injector use: the declared one, else the minimum. */
    float fallback() {
        return Float.isNaN(declared) ? min : declared;
    }

    boolean hasDeclaredDefault() {
        return !Float.isNaN(declared);
    }

    /** A list row: both labels and values given. */
    boolean isList() {
        return entries.length > 0 && entryValues.length > 0;
    }

    /** Step 0: a text field without a slider. */
    boolean isText() {
        return step == 0f;
    }

    /** 0..1 in whole steps: a switch (stored as 0/1). */
    boolean isSwitch() {
        return min == 0f && max == 1f && step == 1f;
    }

    boolean isFloating() {
        return PreferenceNumber.floating(type);
    }

    boolean isWholeNumber() {
        return type == int.class || type == Integer.class || type == long.class || type == Long.class
                || type == short.class || type == Short.class || type == byte.class || type == Byte.class;
    }

    boolean isFlag() {
        return type == boolean.class || type == Boolean.class;
    }

    /** The value within the annotation's range. */
    double clamp(double v) {
        return Math.max(min, Math.min(max, v));
    }
}
