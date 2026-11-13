package com.scamera.app.settings.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A setting kept per physical camera id, under {@code pref_sensorconfig_<sensorId>_<fieldName>}; otherwise read like
 * {@link Tunable}. A {@code float[]} field is edited as one number that every element takes.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SensorConfig {
    String title();

    String description() default "";

    float min() default 0f;

    float max() default 1f;

    /** Default; {@code -999999} means the field's initial value. */
    float defaultValue() default -999999f;

    /** Slider step: a fraction means float, a whole number int, 0 a plain text field without a slider. */
    float step() default 1f;

    /** List labels, paired with {@link #entryValues}; both set make the row a list. */
    String[] entries() default {};

    String[] entryValues() default {};
}
