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
        ByteBuffer[] head = new ByteBuffer[1];
        ThumbPlan.Jpeg j = header(app, file, head);
        if (j == null) return null;
        int rotation = ThumbPlan.rotationOf(j.orientation);
        if (rotation < 0) return null; // the view turns, it does not mirror
        // P637: the file's own EXIF thumbnail first (the camera writes one, 512 px, P620): decoded here from the header just read,
        // 1-3 ms, no wait for a Glide thread - a photo taken a moment ago has no cached grid thumbnail, its page was black until
        // the base tiles
        Bitmap exif = embedded(j, head[0]);
        if (exif != null) {
            Result r = new Result();
            r.bitmap = exif;
            r.width = j.width;
            r.height = j.height;
            r.rotation = rotation;
            return r;
        }
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

    /**
     * P637: the EXIF (IFD1) thumbnail of the header {@code head} parsed as {@code j}, in the stored orientation (as the view's
     * preview must be); null without one, with another aspect than the picture's, or when it lies beyond the bytes read.
     */
    @Nullable
    static Bitmap embedded(@NonNull ThumbPlan.Jpeg j, @Nullable ByteBuffer head) {
        if (head == null || !j.hasThumb() || !ThumbPlan.sameAspect(j.thumbWidth, j.thumbHeight, j.width, j.height)) return null;
        if (j.thumbOffset < 0 || (long) j.thumbOffset + j.thumbLength > head.limit()) return null;
        try {
            byte[] data = new byte[j.thumbLength];
            ByteBuffer d = head.duplicate();
            d.position(j.thumbOffset);
            d.get(data);
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** The JPEG header of the file (frame size and orientation); {@code out[0]}: the bytes read. */
    @Nullable
    private static ThumbPlan.Jpeg header(Context app, MediaFile file, ByteBuffer[] out) {
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
            out[0] = head;
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
