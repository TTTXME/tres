package com.particlesdevs.photoncamera.gallery.glide;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Priority;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.particlesdevs.photoncamera.gallery.files.MediaFile;

/**
 * P620 (owner, 2026-11-11: «В галерее ускорь загрузку превью фотографий, равов и всех других форматов. Особенно фото снятых в
 * 50мп»): the Glide model of a gallery thumbnail, decoded by {@link ThumbDecoder} from the cheapest source that is large enough
 * (an embedded EXIF thumbnail, a DNG's preview, a sparse raw render, a power-of-two sampled decode, MediaStore's video thumbnail),
 * never at full resolution.
 *
 * <p>A thumbnail is not cropped: its short side covers the cell ({@link #cover}) and the cell's ImageView crops it (CENTER_CROP).
 * The grid, the viewer's film strip, the picker and the viewer's first picture of a page therefore share one decoded bitmap per
 * file and size, in the memory cache and in the disk cache (RESOURCE: a 50 MP photo is decoded once, not again on every visit).
 * The model carries the file's GalleryKeys key (length + modification time), so a file rewritten after its first decode gets a
 * new thumbnail.
 */
public final class GalleryThumb {
    @NonNull public final Uri uri;
    /** The file's DATA path when it has one (a direct read of a DNG / RAW without a temporary copy), else null. */
    @Nullable public final String path;
    @NonNull public final String name;
    /** True: the short side covers the request (a grid cell); false: the picture fits inside it (the viewer). */
    public final boolean cover;
    private final String id;

    private GalleryThumb(@NonNull Uri uri, @Nullable String path, @Nullable String name, @NonNull String key, boolean cover) {
        this.uri = uri;
        this.path = path;
        this.name = name != null ? name : "";
        this.cover = cover;
        this.id = "scam-thumb|" + (cover ? "c|" : "f|") + uri + "|" + key;
    }

    /** The thumbnail of a gallery file. */
    @NonNull
    public static GalleryThumb of(@NonNull MediaFile file) {
        return new GalleryThumb(file.getFileUri(), absolute(file.getAbsolutePath()), file.getDisplayName(), GalleryKeys.of(file).toString(), true);
    }

    /** The thumbnail of a bare uri (an album cover, the picker): no file key, the name from the uri. */
    @NonNull
    public static GalleryThumb of(@NonNull Uri uri) {
        return new GalleryThumb(uri, "file".equals(uri.getScheme()) ? uri.getPath() : null, uri.getLastPathSegment(), "", true);
    }

    /** The viewer's picture of a DNG / RAW page: fits inside the request, read straight from the file. */
    @NonNull
    public static GalleryThumb fit(@NonNull MediaFile file) {
        return new GalleryThumb(file.getFileUri(), absolute(file.getAbsolutePath()), file.getDisplayName(), GalleryKeys.of(file).toString(), false);
    }

    @Nullable
    private static String absolute(@Nullable String path) {
        return path != null && path.startsWith("/") ? path : null;
    }

    // ---------------------------------------------------------------- the grid's size

    /** The grid's thumbnail size (px, the cell width); the viewer's first picture asks for the same to hit the grid's cache. */
    private static volatile int gridPx;
    private static final String PREFS = "gallery_thumbs", KEY_PX = "grid_px";
    static final int DEFAULT_PX = 360;

    /** The grid's cell size changed (columns, rotation): kept for the viewer opened from the camera in a later process too. */
    public static void setGridPx(@Nullable Context context, int px) {
        if (px <= 0 || px == gridPx) return;
        gridPx = px;
        if (context != null)
            context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_PX, px).apply();
    }

    public static int gridPx(@Nullable Context context) {
        int px = gridPx;
        if (px <= 0 && context != null) {
            px = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PX, 0);
            if (px > 0) gridPx = px;
        }
        return px > 0 ? px : DEFAULT_PX;
    }

    // ---------------------------------------------------------------- requests

    /**
     * The thumbnail request of {@code model} at {@code px}: the same options everywhere (the memory / disk cache key), no
     * transformation (the view crops), the result kept in the disk cache. When the thumbnail decoder fails, the file's uri
     * through Glide's own decoders, as before P620.
     */
    @NonNull
    public static RequestBuilder<Bitmap> request(@NonNull RequestManager glide, @NonNull GalleryThumb model, int px) {
        return glide.asBitmap().load(model).apply(options(px))
                .error(glide.asBitmap().load(model.uri).apply(new RequestOptions()
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .signature(new com.bumptech.glide.signature.ObjectKey(model.id))
                        .override(px, px)
                        .downsample(DownsampleStrategy.CENTER_OUTSIDE)
                        .dontTransform()));
    }

    /** The options of every thumbnail request (no priority: a preload and a visible cell share the cache entry). */
    @NonNull
    static RequestOptions options(int px) {
        return new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .override(px, px)
                .dontTransform();
    }

    /** The grid's thumbnail of {@code file} only when it is already cached (the viewer's first picture). */
    @NonNull
    public static RequestBuilder<Bitmap> cached(@NonNull RequestManager glide, @NonNull Context context, @NonNull MediaFile file) {
        return glide.asBitmap().load(of(file)).apply(options(gridPx(context)).onlyRetrieveFromCache(true).priority(Priority.IMMEDIATE));
    }

    /** P637: the long side of the viewer's embedded first picture at most (the camera's EXIF thumbnail is 512 px, its DNG preview 1024). */
    static final int EMBEDDED_PX = 1024;

    /**
     * P637: the viewer's first picture of a page whose grid thumbnail is not cached: only what the file embeds
     * ({@link ThumbDecoder#EMBEDDED_ONLY}: a few ms), not kept on disk; the request fails for a file without one.
     */
    @NonNull
    public static RequestBuilder<Bitmap> embedded(@NonNull RequestManager glide, @NonNull MediaFile file) {
        return glide.asBitmap().load(fit(file)).apply(new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .override(EMBEDDED_PX, EMBEDDED_PX)
                .dontTransform()
                .set(ThumbDecoder.EMBEDDED_ONLY, true)
                .priority(Priority.IMMEDIATE));
    }

    /**
     * P637: the viewer's first picture of a HEIC / WebP / AVIF / DNG / RAW page: the grid's cached thumbnail, else - {@code raw}:
     * the grid's thumbnail decoded now (a DNG's preview or sparse render, a RAW's own thumbnail: tens of ms beside a render of a
     * second or more, and the grid has it afterwards); otherwise the file's embedded picture, nothing for a file without one.
     */
    @NonNull
    public static RequestBuilder<Bitmap> first(@NonNull RequestManager glide, @NonNull Context context, @NonNull MediaFile file, boolean raw) {
        return cached(glide, context, file).error(raw ? request(glide, of(file), gridPx(context)).priority(Priority.IMMEDIATE) : embedded(glide, file));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GalleryThumb && ((GalleryThumb) o).id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** Glide's disk cache key of the model (ObjectKey writes toString). */
    @NonNull
    @Override
    public String toString() {
        return id;
    }
}
