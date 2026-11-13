package com.scamera.app.settings.annotations;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marks a processing parameter that the settings screen exposes and {@code TunableInjector} writes back before a run.
 * The field's type picks the row: a number gets a slider (whole-number {@link #step} means an int), a boolean a check box,
 * a {@link java.io.File} a PNG picker, a String with {@link #entries}/{@link #entryValues} a list.
 */
@Target(FIELD)
@Retention(RUNTIME)
public @interface Tunable {
    // the row: its title, the section it is grouped under ("Sharpening", "Demosaic", "Denoise", "Color", "Tone", "General")
    // and its summary (empty: none)
    String title();
    String category() default "General";
    float min() default 0f;
    String description() default "";

    // a slider: its range (min above), its step (a fraction makes the row a float, a whole number an int) and the default
    // (NaN: the field's initial value)
    float max() default 1f;
    float step() default 0.01f;
    float defaultValue() default Float.NaN;

    /** A File row: the square PNG LUT edge sizes it accepts (pixels), empty for any. */
    int[] pngEdges() default {};

    // a String list row: its labels and stored values, paired; the default is matched from defaultValue numerically, else by
    // index
    String[] entries() default {};
    String[] entryValues() default {};
}
