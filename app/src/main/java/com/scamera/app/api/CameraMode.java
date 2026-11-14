package com.scamera.app.api;

/**
 * The camera mode: the photo mode, the only one there is (shown as «Фото»; the bottom row's «Спорт» and «Oldstyle» are
 * variants of it, settings.OldstyleMode / MotionMode, not camera modes).
 * <p>
 * The preference pref_camera_mode_key keeps a number. Older builds stored the ordinal of a longer list (0 unlimited series,
 * 1 RAW video, 2 the photo mode, 3 an older photo mode, 4 night, 5 video; P702: all but the photo mode were retired, none
 * could be chosen on the screen): every stored number reads as {@link #PHOTO}, and the screen stores {@link #STORED} (2, the
 * number every build read as its photo mode, so an older build installed over this one opens the photo mode too).
 */
public enum CameraMode {
    PHOTO;

    /** The number the preference keeps for {@link #PHOTO} (the photo mode's slot in the older builds' list). */
    public static final int STORED = 2;

    /** The mode of a stored number: any number (a retired mode's, or one no build wrote) reads as {@link #PHOTO}. */
    public static CameraMode fromStored(int stored) {
        return PHOTO;
    }
}
