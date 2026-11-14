package com.scamera.app.gallery.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.exifinterface.media.ExifInterface;

import com.scamera.app.gallery.dng.DngFacts;
import com.scamera.app.gallery.files.GalleryVideo;
import com.scamera.app.gallery.files.MediaFile;
import com.scamera.app.gallery.raw.RawFormats;
import com.scamera.app.util.Log;

import java.io.FileInputStream;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * P706: reads {@link PhotoProperties.Facts} of a file off the main thread - the EXIF through a seekable descriptor (the header
 * only; the details sheet read the whole EXIF from a stream on the main thread before), a DNG's IFDs from the mapped file
 * ({@link DngFacts}) - and hands the sections to the main thread.
 */
public final class PhotoPropertiesReader {
    private PhotoPropertiesReader() {}

    private static final String TAG = "PhotoProperties";
    private static final ExecutorService READER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GalleryProperties");
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });

    /**
     * The sections of {@code file} into {@code done} on the main thread. {@code width} x {@code height}: the size the viewer
     * already knows (as shown), {@code format}: its label, {@code ultraHdr}: whether the page has a gain map.
     */
    public static void load(Context context, MediaFile file, int width, int height, @Nullable String format, boolean ultraHdr,
                            Consumer<List<PhotoProperties.Section>> done) {
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        READER.execute(() -> {
            List<PhotoProperties.Section> sections;
            try {
                sections = PhotoProperties.sections(read(app, file, width, height, format, ultraHdr));
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "facts of " + file.displayName() + ": " + e);
                PhotoProperties.Facts f = new PhotoProperties.Facts();
                f.name = file.displayName();
                f.path = file.filePath();
                sections = PhotoProperties.sections(f);
            }
            final List<PhotoProperties.Section> out = sections;
            main.post(() -> done.accept(out));
        });
    }

    @WorkerThread
    static PhotoProperties.Facts read(Context app, MediaFile file, int width, int height, @Nullable String format, boolean ultraHdr) {
        final PhotoProperties.Facts f = new PhotoProperties.Facts();
        f.name = file.displayName();
        f.path = file.filePath();
        f.bytes = file.sizeBytes();
        f.modifiedMs = file.modifiedMs();
        f.width = width;
        f.height = height;
        f.format = format;
        f.ultraHdr = ultraHdr;
        try {
            f.mime = app.getContentResolver().getType(file.contentUri());
        } catch (RuntimeException ignored) {
        }
        if (f.mime == null) f.mime = com.scamera.app.gallery.editor.ExternalEdit.mediaTypeOf(f.name);
        if (GalleryVideo.isVideo(file)) return f;
        try (ParcelFileDescriptor pfd = app.getContentResolver().openFileDescriptor(file.contentUri(), "r")) {
            if (pfd == null) return f;
            exif(f, new ExifInterface(pfd.getFileDescriptor()));
            if (RawFormats.isDngName(f.name)) {
                try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor()); FileChannel ch = in.getChannel()) {
                    final long size = ch.size();
                    if (size > 0 && size <= Integer.MAX_VALUE) f.dng = DngFacts.of(ch.map(FileChannel.MapMode.READ_ONLY, 0, size));
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "no EXIF / DNG facts in " + f.name + ": " + e);
        }
        return f;
    }

    private static void exif(PhotoProperties.Facts f, ExifInterface e) {
        f.taken = e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
        f.takenOffset = e.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL);
        f.make = e.getAttribute(ExifInterface.TAG_MAKE);
        f.model = e.getAttribute(ExifInterface.TAG_MODEL);
        f.lensMake = e.getAttribute(ExifInterface.TAG_LENS_MAKE);
        f.lensModel = e.getAttribute(ExifInterface.TAG_LENS_MODEL);
        f.software = e.getAttribute(ExifInterface.TAG_SOFTWARE);
        f.description = e.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION);
        f.focalMm = e.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, Double.NaN);
        f.focal35 = e.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0);
        f.fNumber = e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, Double.NaN);
        f.exposureS = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, Double.NaN);
        f.iso = e.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0);
        f.bias = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, Double.NaN);
        f.flash = e.getAttributeInt(ExifInterface.TAG_FLASH, -1);
        f.whiteBalance = e.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, -1);
        f.metering = e.getAttributeInt(ExifInterface.TAG_METERING_MODE, -1);
        f.exposureProgram = e.getAttributeInt(ExifInterface.TAG_EXPOSURE_PROGRAM, -1);
        f.colourSpace = e.getAttributeInt(ExifInterface.TAG_COLOR_SPACE, -1);
        f.zoom = e.getAttributeDouble(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, Double.NaN);
        f.rotation = e.getRotationDegrees();
        f.mirrored = e.isFlipped();
        final double[] at = e.getLatLong();
        if (at != null) {
            f.latitude = at[0];
            f.longitude = at[1];
            final double alt = e.getAltitude(Double.NaN);
            if (e.getAttribute(ExifInterface.TAG_GPS_ALTITUDE) != null) f.altitude = alt;
        }
    }
}
