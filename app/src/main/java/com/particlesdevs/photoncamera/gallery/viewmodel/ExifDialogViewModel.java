package com.particlesdevs.photoncamera.gallery.viewmodel;


import android.annotation.SuppressLint;
import android.app.Application;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Rational;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;
import androidx.lifecycle.AndroidViewModel;

import com.particlesdevs.photoncamera.gallery.glide.GalleryKeys;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.particlesdevs.photoncamera.api.ParseExif;
import com.particlesdevs.photoncamera.gallery.files.ImageFile;
import com.particlesdevs.photoncamera.gallery.files.MediaFile;
import com.particlesdevs.photoncamera.gallery.model.ExifDialogModel;
import com.particlesdevs.photoncamera.gallery.views.Histogram;
import com.particlesdevs.photoncamera.util.Utilities;

import org.apache.commons.io.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The View Model class which updates the {@link ExifDialogModel}
 */
public class ExifDialogViewModel extends AndroidViewModel {
    private static final String TAG = ExifDialogViewModel.class.getSimpleName();
    private final ExifDialogModel exifDialogModel;
    private final Handler histoHandler = new Handler(Looper.getMainLooper());
    private Runnable histoRunnable;

    public ExifDialogViewModel(Application application) {
        super(application);
        this.exifDialogModel = new ExifDialogModel();
    }

    public ExifDialogModel getExifDataModel() {
        return exifDialogModel;
    }

    /**
     * Updates the ExifDialogModel using exif attributes stored in the Image File
     *
     * @param imageFile the image imageFile whose exif data is to be read
     */
    public void updateModel(ContentResolver contentResolver, MediaFile imageFile) {
        Runnable apply = read(contentResolver, imageFile);
        if (apply != null) apply.run();
    }

    /**
     * P551: the viewer's swipe read the file (ExifInterface, LibRaw for camera RAWs, the header / bounds decode) on the main thread:
     * read on the EXIF thread, the model set on the main thread, then {@code done} (the newest request only).
     */
    private final java.util.concurrent.ExecutorService exifExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ExifRead");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final java.util.concurrent.atomic.AtomicInteger exifGeneration = new java.util.concurrent.atomic.AtomicInteger();

    public void updateModelAsync(ContentResolver contentResolver, MediaFile imageFile, Runnable done) {
        final int generation = exifGeneration.incrementAndGet();
        exifExecutor.execute(() -> {
            if (generation != exifGeneration.get()) return; // a newer page asked meanwhile
            Runnable apply;
            try {
                apply = read(contentResolver, imageFile);
            } catch (RuntimeException e) {
                com.particlesdevs.photoncamera.util.Log.d("ExifDialogViewModel", "EXIF of " + imageFile.getDisplayName() + ": " + e);
                return;
            }
            if (apply == null) return;
            histoHandler.post(() -> {
                if (generation != exifGeneration.get()) return;
                apply.run();
                done.run();
            });
        });
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        exifExecutor.shutdownNow();
    }

    private Runnable read(ContentResolver contentResolver, MediaFile imageFile) {
        ExifInterface exifInterface;
        InputStream inputStream;
        try {
            inputStream = contentResolver.openInputStream(imageFile.getFileUri());
            exifInterface = new ExifInterface(inputStream);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        String attr_make = exifInterface.getAttribute(ExifInterface.TAG_MAKE);
        String attr_model = exifInterface.getAttribute(ExifInterface.TAG_MODEL);
        String attr_exp = exifInterface.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
        String attr_width = exifInterface.getAttribute(ExifInterface.TAG_IMAGE_WIDTH);
        String attr_length = exifInterface.getAttribute(ExifInterface.TAG_IMAGE_LENGTH);
        String attr_iso = exifInterface.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY);
        String attr_fnum = exifInterface.getAttribute(ExifInterface.TAG_F_NUMBER);
        String attr_focal = exifInterface.getAttribute(ExifInterface.TAG_FOCAL_LENGTH);
        String attr_date = exifInterface.getAttribute(ExifInterface.TAG_DATETIME);
        // P352: a camera RAW ExifInterface does not read (CR3, RAF on old versions, 3FR, IIQ...): the same facts from LibRaw
        String rawName = imageFile.getDisplayName();
        if ((attr_iso == null || attr_exp == null || attr_make == null || attr_width == null)
                && com.particlesdevs.photoncamera.gallery.raw.RawFormats.isRawName(rawName)
                && !com.particlesdevs.photoncamera.gallery.raw.RawFormats.isDngName(rawName)
                && com.particlesdevs.photoncamera.gallery.raw.LibRawBridge.available()) {
            try (com.particlesdevs.photoncamera.gallery.raw.LibRawBridge.RawFile raw =
                         com.particlesdevs.photoncamera.gallery.raw.LibRawBridge.open(getApplication(), imageFile.getFileUri())) {
                if (attr_make == null && !raw.make.isEmpty()) attr_make = raw.make;
                if (attr_model == null && !raw.model.isEmpty()) attr_model = raw.model;
                if (attr_iso == null && raw.iso() > 0) attr_iso = String.valueOf(Math.round(raw.iso()));
                if (attr_exp == null && raw.shutter() > 0) attr_exp = String.valueOf(raw.shutter());
                if (attr_fnum == null && raw.aperture() > 0) attr_fnum = String.valueOf(raw.aperture());
                if (attr_focal == null && raw.focal() > 0) attr_focal = Math.round(raw.focal() * 1000) + "/1000";
                if (attr_width == null && raw.width() > 0) {
                    boolean turned = com.particlesdevs.photoncamera.gallery.editor.CropMath.swapsSides(raw.orientation());
                    attr_width = String.valueOf(turned ? raw.height() : raw.width());
                    attr_length = String.valueOf(turned ? raw.width() : raw.height());
                }
            } catch (IOException | RuntimeException e) {
                com.particlesdevs.photoncamera.util.Log.d("ExifDialogViewModel", "LibRaw facts of " + rawName + ": " + e);
            }
        }
        // P196: the pixels the file really stores (owner: «фактическое разрешение»): its own headers first (JPEG SOF, WebP
        // bitstream, HEIC / AVIF primary item 'ispe'), then the decoder's bounds; the EXIF size tags only when neither
        // reads (DNG: ExifInterface reads the raw image's own IFD). As shown: swapped for a 90 / 270 degree orientation.
        int[] size = actualSize(contentResolver, imageFile);
        if (size != null) {
            int[] shown = com.particlesdevs.photoncamera.gallery.ui.PhotoInfo.oriented(size[0], size[1], exifInterface.getRotationDegrees());
            attr_width = String.valueOf(shown[0]);
            attr_length = String.valueOf(shown[1]);
        }
//        String attr_35mmfocal = exifInterface.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM);
//        Log.d("attr_35mmfocal", "fetched attr_35mmfocal = " + attr_35mmfocal);

        String exposure = (Utilities.formatExposureTime(Double.parseDouble(attr_exp == null ? "NaN" : attr_exp)));
        String resolution_mp = (String.format(Locale.US, "%.1f",
                Double.parseDouble((attr_width == null ? "NaN" : attr_width))
                        * Double.parseDouble((attr_length == null ? "NaN" : attr_length)) / 1E6) + " MP");
        String disp_exp = exposure + "s";
        String disp_fnum = "\u0192/" + attr_fnum;
        String disp_focal = Rational.parseRational(attr_focal == null ? "NaN" : attr_focal).doubleValue() + "mm";
        String disp_iso = "ISO" + attr_iso;

        final String f_length = attr_length, f_width = attr_width, f_make = attr_make, f_model = attr_model, f_date = attr_date,
                f_iso = attr_iso, f_exp = attr_exp, f_fnum = attr_fnum, f_focal = attr_focal;
        final String attr_35 = exifInterface.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM);
        final String description = exifInterface.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION);
        final String dateText = getDateText(attr_date);
        try {
            inputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        // P551: the model is set on the main thread (the file was read on the viewer's EXIF thread, updateModelAsync)
        final Runnable apply = () -> {
            exifDialogModel.setTitle(imageFile.getAbsolutePath());
            exifDialogModel.setRes(f_length + "x" + f_width);
            exifDialogModel.setDevice(f_make + " " + f_model);
            exifDialogModel.setDate(dateText);
            exifDialogModel.setExposure(disp_exp);
            exifDialogModel.setIso(disp_iso);
            exifDialogModel.setFnum(disp_fnum);
            exifDialogModel.setFocal(disp_focal);
            exifDialogModel.setFile_size((FileUtils.byteCountToDisplaySize((int) imageFile.getSize())));
            exifDialogModel.setRes_mp(resolution_mp);
            exifDialogModel.setMiniText(
                    imageFile.getDisplayName() + "\n" +
                            disp_exp + " | " +
                            disp_iso + " | " +
                            disp_fnum + " | " +
                            disp_focal + " | " +
                            resolution_mp);
            // P59b: the details sheet's values
            exifDialogModel.setIsoValue(f_iso == null ? null : "ISO " + f_iso);
            exifDialogModel.setShutterValue(f_exp == null ? null : exposure);
            exifDialogModel.setFnumValue(f_fnum == null ? null : "f/" + com.particlesdevs.photoncamera.gallery.ui.GalleryUi.decimal(parseDouble(f_fnum), 2));
            double focalMm = f_focal == null ? Double.NaN : Rational.parseRational(f_focal).doubleValue();
            exifDialogModel.setFocalValue(Double.isNaN(focalMm) ? null : com.particlesdevs.photoncamera.gallery.ui.GalleryUi.decimal(focalMm, 1)
                    + com.particlesdevs.photoncamera.util.Lang.t(" мм", " mm"));
            exifDialogModel.setFocal35Value(attr_35 == null || "0".equals(attr_35) ? null : attr_35 + com.particlesdevs.photoncamera.util.Lang.t(" мм", " mm"));
            // P196: the phone as the file's EXIF names it («vivo X200 Ultra»), nothing when it has no Make / Model
            exifDialogModel.setMakeModel(com.particlesdevs.photoncamera.gallery.ui.PhotoInfo.device(f_make, f_model));
            exifDialogModel.setDescription(description);
            int width = (int) parseDouble(f_width), height = (int) parseDouble(f_length);
            exifDialogModel.setWidth(Math.max(0, width));
            exifDialogModel.setHeight(Math.max(0, height));
            exifDialogModel.setResolutionValue(com.particlesdevs.photoncamera.gallery.ui.PhotoInfo.resolution(width, height));
            exifDialogModel.setSizeBytes(imageFile.getSize());
            exifDialogModel.notifyChange(); //important
        };
        return apply;
    }


    private static double parseDouble(String v) {
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
    private static int[] actualSize(ContentResolver contentResolver, MediaFile imageFile) {
        try (InputStream in = contentResolver.openInputStream(imageFile.getFileUri())) {
            int[] size = com.particlesdevs.photoncamera.gallery.helper.ImageHeaderSize.read(in);
            if (size != null) return size;
        } catch (IOException | RuntimeException e) {
            com.particlesdevs.photoncamera.util.Log.d(TAG, "no header size: " + e);
        }
        String name = imageFile.getDisplayName();
        if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".dng")) return null;
        return decodedSize(contentResolver, imageFile);
    }

    /** {width, height} of the image from its header (BitmapFactory bounds), or null. */
    private static int[] decodedSize(ContentResolver contentResolver, MediaFile imageFile) {
        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream in = contentResolver.openInputStream(imageFile.getFileUri())) {
            if (in == null) return null;
            android.graphics.BitmapFactory.decodeStream(in, null, options);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return options.outWidth > 0 && options.outHeight > 0 ? new int[]{options.outWidth, options.outHeight} : null;
    }

    /**
     * Updates the {@link Histogram.HistogramModel} view which is associated with ExifDialogModel
     * check for more detail {@link com.particlesdevs.photoncamera.gallery.binding.CustomBinding#updateHistogram(Histogram, Histogram.HistogramModel)}
     */
    /** P551: one histogram (its thread and GL context) for the viewer, not a new one per page. */
    private Histogram histogramWorker;
    private int histogramGeneration;

    public void updateHistogramView(ImageFile imageFile) {
        if (histogramWorker == null) histogramWorker = new Histogram(getApplication().getBaseContext(), null);
        final Histogram histogram = histogramWorker;
        final int generation = ++histogramGeneration;
        if (histoRunnable != null) {
            histoHandler.removeCallbacks(histoRunnable);
        }
        histoHandler.post(histoRunnable = () ->
                Glide.with(getApplication())
                        .asBitmap()
                        .load(imageFile.getFileUri())
                        .apply(new RequestOptions()
                                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                                .signature(GalleryKeys.of(imageFile, "hist"))
                                .override(800) //800*800
                                .fitCenter().useUnlimitedSourceGeneratorsPool(true))
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                        // P551: on the histogram's thread (a copy: Glide may recycle its bitmap), the newest page only
                        final Bitmap copy = resource.copy(resource.getConfig() != null ? resource.getConfig() : Bitmap.Config.ARGB_8888, false);
                        if (copy == null) return;
                        histogram.analyzeAsync(copy, model -> {
                            copy.recycle();
                            if (generation == histogramGeneration) exifDialogModel.setHistogramModel(model);
                        });
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {

                    }
                }));
    }

    private String getDateText(String savedDate) {
        @SuppressLint("SimpleDateFormat")
        SimpleDateFormat displayedDateFormat = new SimpleDateFormat("EEEE, dd MMM, yyyy \u2022 HH:mm:ss");
        Date photoDate;
        try {
            photoDate = ParseExif.sFormatter.parse(savedDate); //parsing with the same formatter with which it was saved
        } catch (Exception ignored) {
            return "";
        }
        return displayedDateFormat.format(photoDate == null ? new Date() : photoDate);
    }

}
