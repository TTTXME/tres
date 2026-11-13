package com.scamera.app.settings;

/**
 * Single source of truth for all classes that carry {@code @SensorConfig} annotations.
 * Add or remove entries here; {@link SensorConfigPreferenceGenerator} and
 * {@link SensorConfigInjector} both derive their class lists from this array.
 */
public final class SensorConfigRegistry {

    private SensorConfigRegistry() {}

    public static final Class<?>[] SENSOR_CONFIG_CLASSES = {
        com.scamera.app.processing.render.Parameters.class,
        com.scamera.app.capture.CaptureController.class,
    };
}
