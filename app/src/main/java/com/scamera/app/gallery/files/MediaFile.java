package com.scamera.app.gallery.files;

import android.net.Uri;

/**
 * Created by Vibhor Srivastava on October 14, 2021
 */
/** One item of the gallery: its MediaStore row and content uri, its name, when it was last written, its size and its path. */
public abstract class MediaFile {
    public abstract long rowId();
    public abstract Uri contentUri();
    public abstract String displayName();
    /** Last modification, ms since 1970. */
    public abstract long modifiedMs();
    public abstract long sizeBytes();
    public abstract String filePath();
}
