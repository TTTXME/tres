package com.scamera.app.gallery.ui;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.scamera.app.gallery.files.MediaFile;
import com.scamera.app.gallery.model.GalleryView;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P622: what the list view's rows show beyond the scan - the pixel size the file stores (ImageHeaderSize, the same as the details
 * sheet; a RAW's DefaultCropSize from its EXIF), the merge route of a SCAMERA photo (its description, GalleryFormat.routeOf) and
 * whether a JPEG is Ultra HDR. Read once per file version on one low-priority thread, only for rows that are bound (the visible
 * ones), newest request first; kept for the process (a small LRU); the row is told on the main thread when its facts arrive.
 */
public final class ItemMeta {
    /** The facts of one file; {@link #size} null when the headers did not say. */
    public static final class Info {
        @Nullable public final int[] size;
        @Nullable public final String route;
        public final boolean ultraHdr;

        public Info(@Nullable int[] size, @Nullable String route, boolean ultraHdr) {
            this.size = size;
            this.route = route;
            this.ultraHdr = ultraHdr;
        }
    }

    public interface Ready {
        void ready(Info info);
    }

    private ItemMeta() {}

    private static final int MAX = 2048;
    private static final Map<String, Info> CACHE = Collections.synchronizedMap(new LinkedHashMap<String, Info>(256, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Info> e) {
            return size() > MAX;
        }
    });
    /** The rows waiting for a file being read (every holder that bound it meanwhile hears the result). */
    private static final Map<String, java.util.List<Ready>> PENDING = new java.util.HashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService READ = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GalleryRowMeta");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    static String key(MediaFile f) {
        return GalleryView.key(f) + "|" + f.getLastModified() + "|" + f.getSize();
    }

    /** Test hook: facts known without reading. */
    static void put(MediaFile f, Info info) {
        CACHE.put(key(f), info);
    }

    /** The facts of {@code f}: at once when known, else read in the background and {@code ready} called on the main thread. */
    @Nullable
    public static Info get(Context c, MediaFile f, Ready ready) {
        if (f == null) return null;
        final String k = key(f);
        Info known = CACHE.get(k);
        if (known != null || !LibraryAdapter.loadPictures || f.getFileUri() == null) return known; // (layout tests read nothing)
        synchronized (PENDING) {
            java.util.List<Ready> waiting = PENDING.get(k);
            if (waiting != null) {
                waiting.add(ready);
                return null;
            }
            waiting = new java.util.ArrayList<>();
            waiting.add(ready);
            PENDING.put(k, waiting);
        }
        final Context app = c.getApplicationContext();
        READ.execute(() -> {
            Info info;
            try {
                info = read(app, f);
            } catch (RuntimeException | LinkageError e) {
                info = new Info(null, null, false);
            }
            CACHE.put(k, info);
            final java.util.List<Ready> waiting;
            synchronized (PENDING) {
                waiting = PENDING.remove(k);
            }
            final Info done = info;
            if (waiting != null) MAIN.post(() -> {
                for (Ready r : waiting) r.ready(done);
            });
        });
        return null;
    }

    private static Info read(Context app, MediaFile f) {
        String name = f.getDisplayName();
        GalleryFormat format = GalleryFormat.of(name, false);
        if (format == GalleryFormat.VIDEO) return new Info(null, null, false);
        Uri uri = f.getFileUri();
        int[] size = null;
        String route = null;
        if (format != GalleryFormat.RAW) {
            try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                size = com.scamera.app.gallery.helper.ImageHeaderSize.read(in);
            } catch (Exception ignored) {
                // no size: the row shows the date and the bytes
            }
        }
        // a file descriptor (seekable): the EXIF reader skips to the tags instead of buffering a 50 MB DNG from a stream
        try (android.os.ParcelFileDescriptor pfd = app.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd != null) {
                androidx.exifinterface.media.ExifInterface exif = new androidx.exifinterface.media.ExifInterface(pfd.getFileDescriptor());
                route = GalleryFormat.routeOf(exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_DESCRIPTION));
                if (size == null) size = rawSize(exif);
            }
        } catch (Exception ignored) {
            // no EXIF: no route
        }
        boolean uhdr = (format == GalleryFormat.JPEG) && com.scamera.app.gallery.helper.UltraHdrGalleryUtil.isUltraHdrJpeg(app, uri);
        return new Info(size, route, uhdr);
    }

    /** A RAW's picture size: DefaultCropSize, else the main image's width and length (EXIF). */
    @Nullable
    static int[] rawSize(androidx.exifinterface.media.ExifInterface exif) {
        String crop = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DEFAULT_CROP_SIZE);
        int[] s = parsePair(crop);
        if (s != null) return s;
        int w = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_WIDTH, 0);
        int h = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_LENGTH, 0);
        return w > 256 && h > 256 ? new int[]{w, h} : null;
    }

    /** «4096,3072» / «4096 3072» (rationals «4096/1,3072/1» too) to {w, h}; null when it is not two positive numbers. */
    @Nullable
    static int[] parsePair(@Nullable String s) {
        if (s == null) return null;
        String[] p = s.trim().split("[,\\s]+");
        if (p.length != 2) return null;
        try {
            int w = (int) Math.round(number(p[0])), h = (int) Math.round(number(p[1]));
            return w > 0 && h > 0 ? new int[]{w, h} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double number(String v) {
        int slash = v.indexOf('/');
        if (slash < 0) return Double.parseDouble(v);
        double d = Double.parseDouble(v.substring(slash + 1));
        return d == 0 ? 0 : Double.parseDouble(v.substring(0, slash)) / d;
    }
}
