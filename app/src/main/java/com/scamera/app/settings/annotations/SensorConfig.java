package com.scamera.app.settings.annotations;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * A setting kept per physical camera id, under {@code pref_sensorconfig_<sensorId>_<fieldName>}; otherwise read like
 * {@link Tunable}. A {@code float[]} field is edited as one number that every element takes.
 */
@Target(FIELD)
@Retention(RUNTIME)
public @interface SensorConfig {
    String title();
    String description() default "";

    // the slider: range, step (a fraction means float, a whole number int, 0 a plain text field without a slider) and the
    // default (NaN: the field's initial value)
    float min() default 0f;
    float max() default 1f;
    float step() default 1f;
    float defaultValue() default Float.NaN;

    // both set make the row a list: its labels and stored values, paired
    String[] entries() default {};
    String[] entryValues() default {};
}
