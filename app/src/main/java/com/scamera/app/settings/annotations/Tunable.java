package com.scamera.app.settings.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a processing parameter that the settings screen exposes and {@code TunableInjector} writes back before a run.
 * The field's type picks the row: a number gets a slider (whole-number {@link #step} means an int), a boolean a check box,
 * a {@link java.io.File} a PNG picker, a String with {@link #entries}/{@link #entryValues} a list.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Tunable {
    /** Row title. */
    String title();

    /** Row summary; empty for none. */
    String description() default "";

    /** Section the row is grouped under ("Sharpening", "Demosaic", "Denoise", "Color", "Tone", "General"). */
    String category() default "General";

    float min() default 0f;

    float max() default 1f;

    /** Default; {@code -999999} means the field's initial value. */
    float defaultValue() default -999999f;

    /** Slider step; a fraction makes the row a float, a whole number an int. */
    float step() default 0.01f;

    /** Square PNG LUT edge sizes a File row accepts (pixels), empty for any. */
    int[] allowedPngSizes() default {};

    /** Labels of a String list row, paired with {@link #entryValues}. */
    String[] entries() default {};

    /** Stored values of a String list row; its default is matched from {@link #defaultValue} numerically, else by index. */
    String[] entryValues() default {};
}
