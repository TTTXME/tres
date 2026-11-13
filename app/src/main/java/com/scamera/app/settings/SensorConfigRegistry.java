package com.scamera.app.settings;

/**
 * The classes whose fields carry {@code @SensorConfig} (the per-module settings): SensorConfigPreferenceGenerator,
 * SensorConfigInjector and the module copy read them from this list alone (SessionKeys files each one under a session kind).
 */
public final class SensorConfigRegistry {
    public static final Class<?>[] OWNERS = {
            com.scamera.app.processing.render.Parameters.class,
            com.scamera.app.capture.CaptureController.class};

    private SensorConfigRegistry() {
    }
}
