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
        shownName = file.displayName();
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

    /** The badge of the grid cell: «RAW», a format's extension, "" for a JPEG. */
    public String typeTag() {
        return badge;
    }

    /** The photo (an empty placeholder's has no row). P637: replaced by its MediaStore row once the scanner filed it. */
    public MediaFile mediaFile() {
        return file;
    }

    public void replaceFile(MediaFile file) {
        this.file = file;
    }

    /** The items grouped under this one (a burst's other frames); empty for a single photo. */
    public ArrayList<GalleryItem> groupedItems() {
        return grouped;
    }

    /** The name the grid shows (the file's, until a rename). */
    public String shownName() {
        return shownName;
    }

    public void setShownName(String name) {
        shownName = name;
    }

    @Override public boolean isChecked() { return checked; }

    @Override public void setChecked(boolean on) { checked = on; }

    @Override public void toggle() { checked = !checked; }
}
