package com.particlesdevs.photoncamera.gallery.adapters;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.FutureTarget;
import com.particlesdevs.photoncamera.gallery.files.MediaFile;
import com.particlesdevs.photoncamera.gallery.glide.GalleryThumb;
import com.particlesdevs.photoncamera.gallery.glide.ThumbPlan;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.TimeUnit;

/**
 * P620: the viewer's first picture of a JPEG page - the grid's cached thumbnail (memory or disk cache, never a decode) shown by
 * SubsamplingScaleImageView as its preview until the tiles of the base layer are decoded (a 50 MP JPEG: a few hundred ms). The
 * view draws a preview in the stored orientation of the main image with the main image's stored size, so the thumbnail (turned
 * by the EXIF orientation) is turned back and the page's orientation is set from the same header ({@link Result#rotation}).
 */
final class ViewerPreview {
    private ViewerPreview() {}

    /** How long the cache lookup may hold the page's tiles back. */
    static final long TIMEOUT_MS = 150;

    static final class Result {
        Bitmap bitmap;
        int width, height, rotation;
    }

    /** Off the main thread: the preview of {@code file}'s page, or null (no cached thumbnail, a mirrored orientation, no header). */
    @Nullable
    static Result load(@NonNull Context app, @NonNull MediaFile file) {
        ThumbPlan.Jpeg j = header(app, file);
        if (j == null) return null;
        int rotation = ThumbPlan.rotationOf(j.orientation);
        if (rotation < 0) return null; // the view turns, it does not mirror
        FutureTarget<Bitmap> f = GalleryThumb.cached(Glide.with(app), app, file).submit();
        try {
            Bitmap thumb = f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (thumb == null) return null;
            Bitmap own = unturned(thumb, rotation);
            if (!sameAspect(own.getWidth(), own.getHeight(), j.width, j.height)) {
                own.recycle();
                return null;
            }
            Result r = new Result();
            r.bitmap = own;
            r.width = j.width;
            r.height = j.height;
            r.rotation = rotation;
            return r;
        } catch (Exception e) {
            return null; // not cached, or not in time: the tiles alone
        } finally {
            Glide.with(app).clear(f);
        }
    }

    /** Whether a preview of {@code w x h} can stand for a {@code mw x mh} picture (the view stretches it to the picture). */
    static boolean sameAspect(int w, int h, int mw, int mh) {
        if (w <= 0 || h <= 0 || mw <= 0 || mh <= 0) return false;
        double a = (double) w / h, m = (double) mw / mh;
        return Math.abs(a - m) <= 0.03 * m;
    }

    /** A copy of {@code b} (Glide's: it is not kept) turned back by {@code rotation} degrees to the stored orientation. */
    private static Bitmap unturned(Bitmap b, int rotation) {
        if (rotation == 0) return b.copy(Bitmap.Config.ARGB_8888, false);
        Matrix m = new Matrix();
        m.setRotate(-rotation);
        Bitmap soft = b.getConfig() == Bitmap.Config.HARDWARE ? b.copy(Bitmap.Config.ARGB_8888, false) : b;
        Bitmap out = Bitmap.createBitmap(soft, 0, 0, soft.getWidth(), soft.getHeight(), m, true);
        if (soft != b && soft != out) soft.recycle();
        return out;
    }

    /** The JPEG header of the file (frame size and orientation). */
    @Nullable
    private static ThumbPlan.Jpeg header(Context app, MediaFile file) {
        ParcelFileDescriptor pfd = null;
        try {
            if (file.getFileUri() != null && ContentResolver.SCHEME_CONTENT.equals(file.getFileUri().getScheme()))
                pfd = app.getContentResolver().openFileDescriptor(file.getFileUri(), "r");
            else if (file.getAbsolutePath() != null)
                pfd = ParcelFileDescriptor.open(new File(file.getAbsolutePath()), ParcelFileDescriptor.MODE_READ_ONLY);
            if (pfd == null) return null;
            FileChannel ch = new FileInputStream(pfd.getFileDescriptor()).getChannel();
            // the frame follows the APP segments: EXIF (64 KB at most), ICC, Ultra HDR's XMP and MPF
            ByteBuffer head = ByteBuffer.allocate((int) Math.min(ch.size(), 512 * 1024));
            while (head.hasRemaining() && ch.read(head, head.position()) > 0) { /* read on */ }
            head.flip();
            return ThumbPlan.parseJpeg(head);
        } catch (Exception e) {
            return null;
        } finally {
            if (pfd != null) try {
                pfd.close();
            } catch (Exception ignored) {
            }
        }
    }
}
