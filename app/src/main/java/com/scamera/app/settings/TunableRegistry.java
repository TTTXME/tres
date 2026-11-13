package com.scamera.app.settings;

/**
 * The classes whose fields carry {@code @Tunable}: TunableSettingsManager, the settings screens and the module copy read their
 * rows from this list alone, so a new class with tunables is added here.
 */
public final class TunableRegistry {
    public static final Class<?>[] OWNERS = {com.scamera.app.processing.render.Parameters.class};

    private TunableRegistry() {
    }
}
