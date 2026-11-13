package com.scamera.app.api;

/**
 * The capture modes. The screen offers {@link #MOTION} only (shown as «Фото»); the others stay for the number older builds stored
 * (the preference holds the ordinal, so the order below is fixed) and read back as MOTION where they are retired.
 */
public enum CameraMode {
    /** retired: no processor any more */ UNLIMITED,
    /** retired: no processor any more */ RAWVIDEO,
    /** the photo mode on screen */ MOTION,
    PHOTO,
    /** retired (P25, owner's answer 4: it differed only by the tripod detection) */ NIGHT,
    VIDEO;

    /** The stored mode (an ordinal); a retired one or a number no mode has reads as MOTION. */
    public static CameraMode valueOf(int stored) {
        final CameraMode[] all = values();
        final CameraMode mode = stored >= 0 && stored < all.length ? all[stored] : MOTION;
        return mode == UNLIMITED || mode == RAWVIDEO || mode == NIGHT ? MOTION : mode;
    }

    /** Still photo modes (the ZSL routes); tripod detection runs in them. */
    public static boolean isStill(CameraMode mode) {
        return mode == MOTION || mode == PHOTO || mode == NIGHT;
    }

    /** The capture UI: Photo only (P25: the Night mode, which differed only by the tripod detection, is gone). */
    public static CameraMode[] userModes() {
        return new CameraMode[]{MOTION};
    }

    /** The user mode at {@code index}, clamped to the ones there are. */
    public static CameraMode userModeAt(int index) {
        final CameraMode[] modes = userModes();
        return modes[Math.max(0, Math.min(index, modes.length - 1))];
    }
}
