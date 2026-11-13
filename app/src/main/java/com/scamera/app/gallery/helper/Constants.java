package com.scamera.app.gallery.helper;

/** Names and numbers the gallery's screens share (the extras' texts are part of saved states and intents). */
public final class Constants {
    private Constants() {}

    // Arguments of the viewer and the compare screen: the screen's mode (the compare one), the photo's position (the text keeps
    // its old spelling) and the two photos compared
    public static final String ARG_MODE = "mode", MODE_COMPARE = "compare";
    public static final String ARG_POSITION = "imagePositon";
    public static final String ARG_FIRST_IMAGE = "image1", ARG_SECOND_IMAGE = "image2";

    /** The request code of an edit in another app. */
    public static final int REQ_EXTERNAL_EDIT = 1001;
    /** How long a double tap's zoom animates (ms). */
    public static final int ZOOM_ANIMATION_MS = 200;
    /** The «review the photo just taken» intent other camera apps send. */
    public static final String REVIEW_INTENT_ACTION = "com.android.camera.action.REVIEW";
}
