package com.scamera.app.processing;

import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.util.FileManager;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ImagePath {
    /** P119: the name (no extension) of a new photo from «Название фото» (FileNamePattern; SCA_yyyyMMdd_HHmmss by default). */
    public static String newBaseName() {
        return newBaseName(System.currentTimeMillis());
    }

    /** P618: the name of a photo taken at {@code ms} (the same instant its EXIF and DNG record). */
    public static String newBaseName(long ms) {
        String pattern;
        try { pattern = PreferenceKeys.getFileNamePattern(); } catch (RuntimeException e) { pattern = FileNamePattern.DEFAULT; }
        return FileNamePattern.format(pattern, ms);
    }

    /** The photo {@code name} (no extension yet) in the camera folder. */
    public static Path imageFile(String name) {
        return Paths.get(FileManager.sDCIM_CAMERA.getAbsolutePath(), name);
    }

    /** The DNG of the photo {@code name} in the RAW folder. */
    public static Path dngFile(String name) {
        return Paths.get(FileManager.sRAW_DIR.getAbsolutePath(), name + ".dng");
    }

    /** A new photo name in the camera folder, without an extension. */
    public static Path newPhotoPath() {
        return newPhotoPath("");
    }

    /** A new photo name with {@code extension} ("" for none): a DNG in the RAW folder, anything else in the camera folder. */
    public static Path newPhotoPath(String extension) {
        final File folder = extension.equalsIgnoreCase("dng") ? FileManager.sRAW_DIR : FileManager.sDCIM_CAMERA;
        final String name = extension.isEmpty() ? newBaseName() : newBaseName() + '.' + extension;
        return Paths.get(folder.getAbsolutePath(), name);
    }

}
