package com.scamera.app.gallery.model;

import android.widget.Checkable;

import com.scamera.app.gallery.files.ImageFile;
import com.scamera.app.gallery.files.MediaFile;
import com.scamera.app.gallery.raw.RawFormats;

import java.util.ArrayList;
import java.util.Locale;

/**
 * One entry of the gallery: a file, the name it is shown under, its format badge («RAW», none for JPEG, else the extension)
 * and whether it is selected. {@link #getFiles} holds the entries grouped under it.
 */
public class GalleryItem implements Checkable {
    private MediaFile file;
    private final String badge;
    private boolean checked;
    private String shownName;
    private final ArrayList<GalleryItem> grouped = new ArrayList<>(0);

    public GalleryItem(MediaFile file) {
        this.file = file;
        shownName = file.getDisplayName();
        badge = badgeOf(shownName);
    }

    /** A placeholder entry without a file. */
    public static GalleryItem createEmpty() {
        return new GalleryItem(new ImageFile(0, null, "", 0, 0, ""));
    }

    private static String badgeOf(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 || name.indexOf('/', dot) >= 0 || name.indexOf('\\', dot) >= 0 ? "" : name.substring(dot + 1);
        if (ext.equalsIgnoreCase("dng") || RawFormats.isRawName(name)) return "RAW"; // P352
        if (ext.equalsIgnoreCase("jpg") || ext.equalsIgnoreCase("jpeg")) return "";
        return ext.toUpperCase(Locale.ROOT);
    }

    public String getMediaTypeTag() {
        return badge;
    }

    public MediaFile getFile() {
        return file;
    }

    /** P637: the same photo under its MediaStore row once the scanner filed it (it was known by its path only). */
    public void setFile(MediaFile file) {
        this.file = file;
    }

    public ArrayList<GalleryItem> getFiles() {
        return grouped;
    }

    public String getDisplayName() {
        return shownName;
    }

    public void setDisplayName(String name) {
        shownName = name;
    }

    @Override public boolean isChecked() { return checked; }

    @Override public void setChecked(boolean on) { checked = on; }

    @Override public void toggle() { checked = !checked; }
}
