package com.particlesdevs.photoncamera.gallery.glide;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.module.AppGlideModule;

import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * P59: the app's Glide components: DNG files through {@link DngGlideDecoder}, AVIF files through {@link AvifGlideDecoder}; P352:
 * other camera RAWs through {@link RawGlideDecoder} (LibRaw); P620: the gallery's thumbnails ({@link GalleryThumb}) through
 * {@link ThumbDecoder}, a disk cache sized for a library of thousands of thumbnails and a memory cache of three screens.
 */
@GlideModule
public final class ScameraGlideModule extends AppGlideModule {
    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
        // P352: tried after the DNG decoder (prepend: the last one first): every other camera RAW through LibRaw
        registry.prepend(Registry.BUCKET_BITMAP, InputStream.class, Bitmap.class, new RawGlideDecoder(context, glide.getBitmapPool()));
        registry.prepend(Registry.BUCKET_BITMAP, InputStream.class, Bitmap.class, new DngGlideDecoder(context, glide.getBitmapPool()));
        registry.prepend(Registry.BUCKET_BITMAP, InputStream.class, Bitmap.class, new AvifGlideDecoder(context, glide.getBitmapPool()));
        registry.prepend(Registry.BUCKET_BITMAP, ByteBuffer.class, Bitmap.class, new AvifGlideDecoder.ByteBufferDecoder(glide.getBitmapPool()));
        // P620: the gallery's thumbnails: the model goes to the decoder as it is (it opens the file itself)
        registry.prepend(GalleryThumb.class, GalleryThumb.class, new ThumbLoader.Factory());
        registry.prepend(Registry.BUCKET_BITMAP, GalleryThumb.class, Bitmap.class, new ThumbDecoder(context, glide.getBitmapPool()));
    }

    /** P620: the disk cache's directory: Glide's default one (the thumbnails already cached stay). */
    static final String DISK_CACHE_DIR = "image_manager_disk_cache";
    static final long MB = 1024L * 1024L;

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        long usable = 0;
        try {
            usable = context.getCacheDir().getUsableSpace();
        } catch (RuntimeException ignored) {
        }
        builder.setDiskCache(new com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory(context, DISK_CACHE_DIR, diskCacheBytes(usable)));
        // the grid's thumbnails (~0.5 MB each at a 4-column cell) for about three screens of the full-screen size Glide counts in
        builder.setMemorySizeCalculator(new com.bumptech.glide.load.engine.cache.MemorySizeCalculator.Builder(context).setMemoryCacheScreens(3));
    }

    /**
     * P620: the disk cache's size: Glide's 250 MB held about 8000 grid thumbnails (30 KB each), fewer than a library of 9000 photos
     * plus the viewer's pictures, so a scroll back decoded the 50 MP files again; now 1/20 of the free space, 250 MB to 768 MB.
     */
    static long diskCacheBytes(long usableBytes) {
        return Math.max(250 * MB, Math.min(768 * MB, usableBytes / 20));
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
