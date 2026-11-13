package com.scamera.app.gallery.viewmodel;

import android.app.Application;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;
import androidx.lifecycle.AndroidViewModel;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.scamera.app.gallery.editor.CropMath;
import com.scamera.app.gallery.glide.GalleryKeys;
import com.scamera.app.gallery.glide.GalleryThumb;
import com.scamera.app.gallery.files.ImageFile;
import com.scamera.app.gallery.files.MediaFile;
import com.scamera.app.gallery.helper.ImageHeaderSize;
import com.scamera.app.gallery.model.ExifDialogModel;
import com.scamera.app.gallery.raw.LibRawBridge;
import com.scamera.app.gallery.raw.RawFormats;
import com.scamera.app.gallery.ui.GalleryUi;
import com.scamera.app.gallery.ui.PhotoInfo;
import com.scamera.app.gallery.views.Histogram;
import com.scamera.app.util.Lang;
import com.scamera.app.util.Log;
import com.scamera.app.util.Utilities;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fills the viewer's {@link ExifDialogModel} for the photo on screen: its EXIF facts (LibRaw's for a camera RAW
 * ExifInterface cannot read), the pixel size it really stores, and its histogram.
 */
public class ExifDialogViewModel extends AndroidViewModel {
    private static final String TAG = "ExifDialogViewModel";
    private final ExifDialogModel shown = new ExifDialogModel();
    private final Handler main = new Handler(Looper.getMainLooper());
    /**
     * P551: the viewer's swipe read the file (ExifInterface, LibRaw, the header / bounds decode) on the main thread: it is
     * read on this thread, the model set on the main thread, the newest request only.
     */
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ExifRead");
        t.setPriority(Thread.NORM_PRIORITY); // P637: the page's title waits for it (NORM_PRIORITY - 1 is nice 10)
        return t;
    });
    private final AtomicInteger readRequest = new AtomicInteger();
    /** P551: one histogram (its thread and GL context) for the viewer, not a new one per page. */
    private Histogram histogram;
    private int histogramRequest;
    private Runnable pendingHistogram;

    public ExifDialogViewModel(Application application) {
        super(application);
    }

    public ExifDialogModel getExifDataModel() {
        return shown;
    }

    /** Reads the file's facts off the main thread, sets them and runs {@code done} there (unless a newer page asked). */
    public void updateModelAsync(ContentResolver resolver, MediaFile file, Runnable done) {
        final int request = readRequest.incrementAndGet();
        reader.execute(() -> {
            if (request != readRequest.get()) return; // a newer page asked meanwhile
            Facts facts;
            try {
                facts = read(resolver, file);
            } catch (RuntimeException e) {
                Log.d(TAG, "EXIF of " + file.displayName() + ": " + e);
                return;
            }
            if (facts == null) return;
            main.post(() -> {
                if (request != readRequest.get()) return;
                facts.into(shown);
                shown.notifyChange();
                done.run();
            });
        });
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        reader.shutdownNow();
        // P617: the histogram's thread and GL context went with the process, one more for every viewer opened
        if (pendingHistogram != null) main.removeCallbacks(pendingHistogram);
        if (histogram != null) histogram.release();
        histogram = null;
    }

    /** The facts of one file, read and turned into the shown texts on the reader thread (a malformed value fails there). */
    private static final class Facts {
        String make, model, exposure, iso, aperture, focal, focal35, description;
        String width, height;
        private final ExifDialogModel texts = new ExifDialogModel();

        Facts done() {
            ExifDialogModel m = texts;
            m.setIsoValue(iso == null ? null : "ISO " + iso);
            m.setShutterValue(exposure == null ? null : Utilities.formatExposureTime(Double.parseDouble(exposure)));
            m.setFnumValue(aperture == null ? null : "f/" + GalleryUi.decimal(number(aperture), 2));
            double mm = focal == null ? Double.NaN : Rational.parseRational(focal).doubleValue();
            m.setFocalValue(Double.isNaN(mm) ? null : GalleryUi.decimal(mm, 1) + Lang.t(" мм", " mm"));
            m.setFocal35Value(focal35 == null || "0".equals(focal35) ? null : focal35 + Lang.t(" мм", " mm"));
            // P196: the phone as the file's EXIF names it («vivo X200 Ultra»), nothing when it has no Make / Model
            m.setMakeModel(PhotoInfo.device(make, model));
            m.setDescription(description);
            int w = (int) number(width), h = (int) number(height);
            m.setWidth(Math.max(0, w));
            m.setHeight(Math.max(0, h));
            m.setResolutionValue(PhotoInfo.resolution(w, h));
            return this;
        }

        /** On the main thread: the texts into the viewer's model (its histogram stays). */
        void into(ExifDialogModel m) {
            ExifDialogModel t = texts;
            m.setIsoValue(t.getIsoValue());
            m.setShutterValue(t.getShutterValue());
            m.setFnumValue(t.getFnumValue());
            m.setFocalValue(t.getFocalValue());
            m.setFocal35Value(t.getFocal35Value());
            m.setMakeModel(t.getMakeModel());
            m.setDescription(t.getDescription());
            m.setWidth(t.getWidth());
            m.setHeight(t.getHeight());
            m.setResolutionValue(t.getResolutionValue());
        }
    }

    private Facts read(ContentResolver resolver, MediaFile file) {
        ExifInterface exif;
        try (InputStream in = resolver.openInputStream(file.contentUri())) {
            if (in == null) return null;
            exif = new ExifInterface(in); // reads the metadata at once
        } catch (IOException e) {
            Log.d(TAG, "no EXIF in " + file.displayName() + ": " + e);
            return null;
        }
        Facts f = new Facts();
        f.make = exif.getAttribute(ExifInterface.TAG_MAKE);
        f.model = exif.getAttribute(ExifInterface.TAG_MODEL);
        f.exposure = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
        f.width = exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH);
        f.height = exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH);
        f.iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY);
        f.aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER);
        f.focal = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH);
        f.focal35 = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM);
        f.description = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION);
        String name = file.displayName();
        boolean missing = f.iso == null || f.exposure == null || f.make == null || f.width == null;
        if (missing && RawFormats.isRawName(name) && !RawFormats.isDngName(name) && LibRawBridge.available()) fromLibRaw(file, f);
        // P196: the pixels the file really stores (owner: «фактическое разрешение»): its own headers first (JPEG SOF, WebP
        // bitstream, HEIC / AVIF primary item 'ispe'), then the decoder's bounds; the EXIF size tags only when neither
        // reads (DNG: ExifInterface reads the raw image's own IFD). As shown: swapped for a 90 / 270 degree orientation.
        int[] stored = storedSize(resolver, file);
        if (stored != null) {
            int[] upright = PhotoInfo.oriented(stored[0], stored[1], exif.getRotationDegrees());
            f.width = String.valueOf(upright[0]);
            f.height = String.valueOf(upright[1]);
        }
        return f.done();
    }

    /** P352: a camera RAW ExifInterface does not read (CR3, RAF on old versions, 3FR, IIQ...): the missing facts from LibRaw. */
    private void fromLibRaw(MediaFile file, Facts f) {
        try (LibRawBridge.RawFile raw = LibRawBridge.open(getApplication(), file.contentUri())) {
            if (f.make == null && !raw.make.isEmpty()) f.make = raw.make;
            if (f.model == null && !raw.model.isEmpty()) f.model = raw.model;
            if (f.iso == null && raw.iso() > 0) f.iso = String.valueOf(Math.round(raw.iso()));
            if (f.exposure == null && raw.shutter() > 0) f.exposure = String.valueOf(raw.shutter());
            if (f.aperture == null && raw.aperture() > 0) f.aperture = String.valueOf(raw.aperture());
            if (f.focal == null && raw.focal() > 0) f.focal = Math.round(raw.focal() * 1000) + "/1000";
            if (f.width == null && raw.width() > 0) {
                boolean turned = CropMath.swapsSides(raw.orientation());
                f.width = String.valueOf(turned ? raw.height() : raw.width());
                f.height = String.valueOf(turned ? raw.width() : raw.height());
            }
        } catch (IOException | RuntimeException e) {
            Log.d(TAG, "LibRaw facts of " + file.displayName() + ": " + e);
        }
    }

    private static double number(String v) {
        if (v == null) return Double.NaN;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * P196: {width, height} the file stores, before any rotation: its headers (ImageHeaderSize), else the decoder's bounds
     * (not for a DNG: the platform raw decoder would read the whole file), or null.
     */
    private static int[] storedSize(ContentResolver resolver, MediaFile file) {
        try (InputStream in = resolver.openInputStream(file.contentUri())) {
            int[] size = ImageHeaderSize.read(in);
            if (size != null) return size;
        } catch (IOException | RuntimeException e) {
            Log.d(TAG, "no header size: " + e);
        }
        String name = file.displayName();
        if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".dng")) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(file.contentUri())) {
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return bounds.outWidth > 0 && bounds.outHeight > 0 ? new int[]{bounds.outWidth, bounds.outHeight} : null;
    }

    /** The histogram of the photo (an 800 px rendition), into the model when it is still the newest page's. */
    public void updateHistogramView(ImageFile file) {
        if (histogram == null) histogram = new Histogram(getApplication().getBaseContext(), null);
        final Histogram counter = histogram;
        final int request = ++histogramRequest;
        if (pendingHistogram != null) main.removeCallbacks(pendingHistogram);
        // P620: a DNG / RAW through the thumbnail decoder (read in place, a sparse render: a 200 MP DNG was copied whole,
        // 400 MB, to read 800 px of it)
        Object source = RawFormats.isRawName(file.displayName()) ? GalleryThumb.of(file) : file.contentUri();
        RequestOptions small = new RequestOptions().diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .signature(GalleryKeys.of(file, "hist")).override(800).fitCenter().useUnlimitedSourceGeneratorsPool(true);
        pendingHistogram = () -> Glide.with(getApplication()).asBitmap().load(source).apply(small)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap bitmap, @Nullable Transition<? super Bitmap> transition) {
                        // P551: counted on the histogram's thread from a copy (Glide may recycle its bitmap)
                        Bitmap copy = bitmap.copy(bitmap.getConfig() != null ? bitmap.getConfig() : Bitmap.Config.ARGB_8888, false);
                        if (copy == null) return;
                        counter.analyzeAsync(copy, model -> {
                            copy.recycle();
                            if (request == histogramRequest) shown.setHistogramModel(model);
                        });
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {
                        // the copy is the histogram's own
                    }
                });
        main.post(pendingHistogram);
    }
}
