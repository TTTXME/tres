package com.scamera.app.gallery.files;

import android.net.Uri;

import androidx.annotation.NonNull;

/**
 * A photo of the library: its MediaStore row (id, content uri), name, modification time (ms), size (bytes) and path. Two
 * entries are the same photo when id, name and modification time agree (a rewrite in place changes the time).
 */
public final class ImageFile extends MediaFile {
    private final long rowId;
    private final Uri uri;
    private final String name;
    private final long modifiedMs;
    private final long bytes;
    private final String path;

    public ImageFile(long rowId, Uri uri, String name, long modifiedMs, long bytes, String path) {
        this.rowId = rowId;
        this.uri = uri;
        this.name = name;
        this.modifiedMs = modifiedMs;
        this.bytes = bytes;
        this.path = path;
    }

    @Override public long getId() { return rowId; }

    @Override public Uri getFileUri() { return uri; }

    @Override public String getDisplayName() { return name; }

    @Override public long getLastModified() { return modifiedMs; }

    @Override public long getSize() { return bytes; }

    @Override public String getAbsolutePath() { return path; }

    @Override
    public boolean equals(Object other) {
        if (other == this) return true;
        if (!(other instanceof ImageFile)) return false;
        ImageFile f = (ImageFile) other;
        return rowId == f.rowId && modifiedMs == f.modifiedMs && name.equals(f.name);
    }

    @Override
    public int hashCode() {
        int h = Long.hashCode(rowId);
        h = 31 * h + name.hashCode();
        return 31 * h + Long.hashCode(modifiedMs);
    }

    @NonNull
    @Override
    public String toString() {
        return "photo #" + rowId + " " + name + " (" + bytes + " B, modified " + modifiedMs + ", " + uri + ", " + path + ")";
    }
}
