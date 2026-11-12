package com.particlesdevs.photoncamera.processing;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.particlesdevs.photoncamera.processing.heif.TenBitBitmaps;

public final class IszAspectCrop {
    private IszAspectCrop() { }

    /** The 4:3 crop's unit (its long side is 4 units, its short side 3), even. */
    private static int unit(int width, int height) {
        return Math.min(Math.max(width, height) / 4, Math.min(width, height) / 3) & ~1;
    }

    /** P369: the 4:3 crop's long side over the image's ({@link #bounds}); 1 for an image too small for the crop. */
    public static float longRatio(int width, int height) {
        final int unit = unit(width, height);
        return unit < 2 ? 1f : 4f * unit / Math.max(width, height);
    }

    public static Rect bounds(int width, int height) {
        final int[] b = box(width, height);
        return new Rect(b[0], b[1], b[0] + b[2], b[1] + b[3]);
    }

    /** {@link #bounds} as {x, y, width, height} (P618: the DNG's DefaultCrop, without android.graphics). */
    public static int[] box(int width, int height) {
        int unit = unit(width, height);
        if (unit < 2) throw new IllegalArgumentException("Image too small for even 4:3 crop");
        int w = width >= height ? 4 * unit : 3 * unit;
        int h = width >= height ? 3 * unit : 4 * unit;
        int x = (width - w) / 2, y = (height - h) / 2;
        return new int[]{x, y, w, h};
    }

    /**
     * P610: {@link #crop} for a caller that drops {@code image} after it: the crop in the same bitmap where it can be
     * (ScamPostDownscale.cropInPlace: the same pixels, no second bitmap), else the copy. The result's size tells it apart.
     */
    public static Bitmap cropOwned(Bitmap image, Rect bounds) {
        if (com.particlesdevs.photoncamera.processing.ml.ScamPostDownscale.cropInPlace(image, bounds.left, bounds.top, bounds.width(), bounds.height()))
            return image;
        return crop(image, bounds);
    }

    public static Bitmap crop(Bitmap image, Rect bounds) {
        if (bounds.left == 0 && bounds.top == 0 && bounds.width() == image.getWidth()
                && bounds.height() == image.getHeight()) return image;
        return TenBitBitmaps.crop(image, bounds.left, bounds.top, bounds.width(), bounds.height());
    }
}
