package com.scamera.app.gallery.helper;

/** Names and numbers the gallery's screens share (the extras' texts are part of saved states and intents). */
public final class Constants {
    private Constants() {}

    // Arguments of the viewer and the compare screen
    public static final String MODE_KEY = "mode";
    public static final String COMPARE = "compare";
    /** The extra's text keeps its old spelling. */
    public static final String IMAGE_POSITION_KEY = "imagePositon";
    public static final String IMAGE1_KEY = "image1";
    public static final String IMAGE2_KEY = "image2";

    /** The request code of an edit in another app. */
    public static final int REQUEST_EDIT_IMAGE = 1001;
    /** How long a double tap's zoom animates (ms). */
    public static final int DOUBLE_TAP_ZOOM_DURATION_MS = 200;
    /** The «review the photo just taken» intent other camera apps send. */
    public static final String ACTION_REVIEW = "com.android.camera.action.REVIEW";
}
