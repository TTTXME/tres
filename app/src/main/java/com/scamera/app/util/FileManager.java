package com.scamera.app.util;

import android.os.Environment;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** The camera's folders on the shared storage and the newest-first list of the photos in them. */
public class FileManager {
    private static final String TAG = "FileManager";
    /** Photos in every format the camera writes (JPEG, HEIC / HEIF, WebP, AVIF) and DNG: PhotoFormat.isGalleryFile. */
    private static final FilenameFilter PHOTOS = (dir, name) ->
            com.scamera.app.processing.PhotoFormat.isGalleryFile(name) && new File(dir, name).length() > 0;
    private static final Comparator<File> NEWEST_FIRST = (a, b) -> Long.compare(b.lastModified(), a.lastModified());
    private static final File STORAGE = Environment.getExternalStorageDirectory();
    /** DCIM/SCAMERA: the RAW (DNG) files in Raw/, the logs in Log/ (util/Log). Photos go to DCIM/Camera. */
    public static File sSCAMERA_DIR = new File(STORAGE + "//DCIM//SCAMERA//");
    public static File sRAW_DIR = new File(STORAGE + "//DCIM//SCAMERA//Raw//");
    public static File sDCIM_CAMERA = new File(STORAGE + "//DCIM//Camera//");
    /** The list given last (newest first); a later call puts only the files newer than its first one in front of it. */
    public static List<File> tempImageFiles;

    public static void CreateFolders() {
        for (File folder : new File[]{sDCIM_CAMERA, sRAW_DIR}) Log.d(TAG, "folder " + folder + " made: " + folder.mkdirs());
    }

    @NonNull
    public static List<File> getAllImageFiles() {
        final List<File> found = new ArrayList<>();
        for (File folder : new File[]{sDCIM_CAMERA, sRAW_DIR}) {
            final File[] files = folder.listFiles(PHOTOS);
            if (files != null) Collections.addAll(found, files);
        }
        final List<File> known = tempImageFiles;
        if (known != null && known.size() > 2) {
            if (known.size() >= found.size()) return known;
            // photos were added: the ones newer than the newest known, newest first, before the known list
            final long newestKnown = known.get(0).lastModified();
            final List<File> added = new ArrayList<>();
            for (File f : found) if (f.lastModified() > newestKnown) added.add(f);
            added.sort(NEWEST_FIRST);
            added.addAll(known);
            tempImageFiles = added;
            return added;
        }
        if (found.isEmpty()) {
            Log.e(TAG, "no photos in " + sDCIM_CAMERA + " or " + sRAW_DIR);
            return found;
        }
        found.sort(NEWEST_FIRST);
        tempImageFiles = found;
        return found;
    }
}
