package com.particlesdevs.photoncamera.gallery.adapters;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager.widget.PagerAdapter;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.CustomViewTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.davemorrissey.labs.subscaleview.ImageSource;
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;
import com.particlesdevs.photoncamera.gallery.compare.SSIVListener;
import com.particlesdevs.photoncamera.gallery.glide.GalleryKeys;
import com.particlesdevs.photoncamera.gallery.helper.UltraHdrGalleryUtil;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;
import com.particlesdevs.photoncamera.gallery.views.CustomSSIV;
import com.particlesdevs.photoncamera.processing.PhotoFormat;

import org.apache.commons.io.FileUtils;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;



public class ImageAdapter extends PagerAdapter {
    private static final int BASE_ID = View.generateViewId();
    /** Gallery decode problems in the app's log file (log-DATE.txt). */
    private static final String GALLERY_TAG = "GALLERY_DECODE";
    /**
     * P620 (owner, 2026-11-11: «200мп в галерее тоже ускорь»): CENTER_INSIDE, but a picture of twice the request or more (50 /
     * 200 MP for 4096) is sampled by the decoder to about the request (MEMORY rounding: 1/2, 1/4) instead of decoded at the
     * nearest smaller sample and scaled (a 50 MP HEIC was decoded whole, a 200 MP one at 8160 x 6144, then scaled to 4096).
     */
    static final DownsampleStrategy SAMPLED_INSIDE = new DownsampleStrategy() {
        @Override
        public float getScaleFactor(int sourceWidth, int sourceHeight, int requestedWidth, int requestedHeight) {
            return DownsampleStrategy.CENTER_INSIDE.getScaleFactor(sourceWidth, sourceHeight, requestedWidth, requestedHeight);
        }

        @Override
        public SampleSizeRounding getSampleSizeRounding(int sourceWidth, int sourceHeight, int requestedWidth, int requestedHeight) {
            return getScaleFactor(sourceWidth, sourceHeight, requestedWidth, requestedHeight) <= 0.505f ? SampleSizeRounding.MEMORY
                    : DownsampleStrategy.CENTER_INSIDE.getSampleSizeRounding(sourceWidth, sourceHeight, requestedWidth, requestedHeight);
        }
    };

    /** P620: the long side of an Ultra HDR page's bitmap at most (one bitmap, no tiles: 8192 keeps a 50 MP photo whole). */
    static final int HDR_MAX_SIDE = 8192;
    private static final ExecutorService HDR_EXECUTOR = Executors.newSingleThreadExecutor();
    /** P620: the JPEG pages' first pictures (the grid's cached thumbnail and the header): two pages at a time. */
    private static final ExecutorService PREVIEW_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "GalleryPreview");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final List<GalleryItem> galleryItemList;
    private final boolean[] hdrRequested;
    private final boolean[] hdrActive;
    private final boolean[] hdrAvailable;
    private final Bitmap[] hdrBitmaps;
    private final Target<Bitmap>[] hdrTargets;
    /** P46: colour of each page's file, 1 + UltraHdrGalleryUtil.COLOUR_*; 0 = not probed yet. */
    private final byte[] colour;
    /** P81: pages whose decode gave up (the file was still being written): decoded again when shown. */
    private final boolean[] gaveUp;
    private ImageViewClickListener imageViewClickListener;
    private SSIVListener ssivListener;
    private SubsamplingScaleImageView.OnImageEventListener imageEventListener;
    private HdrStateListener hdrStateListener;
    /** Set once the host view is gone: late HDR callbacks must not touch the pages any more. */
    private boolean released;


    public ImageAdapter(List<GalleryItem> galleryItemList) {
        this.galleryItemList = galleryItemList;
        int size = galleryItemList.size();
        this.hdrRequested = new boolean[size];
        this.hdrActive = new boolean[size];
        this.hdrAvailable = new boolean[size];
        this.hdrBitmaps = new Bitmap[size];
        this.hdrTargets = new Target[size];
        this.colour = new byte[size];
        this.gaveUp = new boolean[size];
    }

    public void setSsivListener(SSIVListener ssivListener) {
        this.ssivListener = ssivListener;
    }

    public void setImageEventListener(SubsamplingScaleImageView.OnImageEventListener imageEventListener) {
        this.imageEventListener = imageEventListener;
    }

    public void setHdrStateListener(HdrStateListener hdrStateListener) {
        this.hdrStateListener = hdrStateListener;
    }

    @Override
    public int getCount() {
        return galleryItemList.size();
    }

    @Override
    public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
        return view == object;
    }


    @NonNull
    @Override
    public Object instantiateItem(@NonNull ViewGroup container, int position) {
        GalleryItem galleryItem = galleryItemList.get(position);
        String fileName = galleryItem.getFile().getDisplayName();

        CustomSSIV scaleImageView = new CustomSSIV(container.getContext());
        scaleImageView.setId(getSsivId(position));
        // P620: tiles from the file descriptor (no copy of the file into native memory), a few decoders in parallel
        scaleImageView.setRegionDecoderClass(FdRegionDecoder.class);
        if (imageViewClickListener != null) {
            scaleImageView.setOnClickListener(v -> imageViewClickListener.onImageViewClicked(v));
        }
        if (ssivListener != null) {
            scaleImageView.setOnStateChangedListener(ssivListener);
            scaleImageView.setTouchCallBack(ssivListener);
        }

        // P117: in the pager first: the Glide decode of a HEIC / WebP / AVIF / DNG page starts only for a page with a parent, so a
        // page loaded before it was added stayed black for good (nothing logged, nothing retried)
        container.addView(scaleImageView);
        if (inBounds(position)) gaveUp[position] = false;
        loadPage(scaleImageView, galleryItem, fileName);
        return scaleImageView;
    }

    /** The page's picture; P89: after the camera has finished writing the file. */
    private void loadPage(CustomSSIV scaleImageView, GalleryItem galleryItem, String fileName) {
        if (GalleryKeys.waitForFile(scaleImageView, galleryItem.getFile(), () -> {
            if (!released && scaleImageView.getParent() != null) loadPage(scaleImageView, galleryItem, fileName);
        })) return;
        if (GalleryDecodeRetry.shouldUseTiledDecode(fileName)) {
            // JPEG: BitmapRegionDecoder tiles smoothly and safely on Android; falls back to Glide if tiling fails.
            scaleImageView.setOnImageEventListener(new BitmapFallback(imageEventListener, scaleImageView, galleryItem));
            tilesWithPreview(scaleImageView, galleryItem);
        } else {
            // HEIC, WebP, AVIF, DNG: platform BitmapRegionDecoder causes native SIGSEGV crashes on HEIC
            // (libheif concurrency, 10-bit Main10) and dropped tiles / black screens on WebP.
            // These formats load safely via Glide into a cached bitmap with automatic retry.
            scaleImageView.setOnImageEventListener(imageEventListener);
            loadGlideBitmap(scaleImageView, galleryItem, 0, imageEventListener);
        }
    }

    /**
     * P620: the tiled page with the grid's cached thumbnail as its preview (shown at once; the base layer of a 50 MP JPEG takes a
     * few hundred ms), else the tiles alone as before. The cache lookup and the header run off the main thread (at most
     * ViewerPreview.TIMEOUT_MS).
     */
    private void tilesWithPreview(CustomSSIV page, GalleryItem item) {
        final android.net.Uri uri = item.getFile().getFileUri();
        final Context app = page.getContext().getApplicationContext();
        PREVIEW_EXECUTOR.execute(() -> {
            final ViewerPreview.Result p = released ? null : ViewerPreview.load(app, item.getFile());
            page.post(() -> {
                if (released || page.getParent() == null || page.hasImage()) { // gone, or an Ultra HDR / fallback picture already
                    if (p != null) p.bitmap.recycle();
                    return;
                }
                if (p != null) {
                    page.setOrientation(p.rotation); // the preview's and the tiles' turn, from the same header
                    page.setImage(ImageSource.uri(uri).dimensions(p.width, p.height), ImageSource.bitmap(p.bitmap));
                } else page.setImage(ImageSource.uri(uri));
            });
        });
    }

    /** P620: a decoded bitmap (Glide's, already turned) on a page whose orientation a JPEG preview may have fixed. */
    private static void showBitmap(SubsamplingScaleImageView page, Bitmap bitmap) {
        if (page.getOrientation() != SubsamplingScaleImageView.ORIENTATION_USE_EXIF)
            page.setOrientation(SubsamplingScaleImageView.ORIENTATION_USE_EXIF);
        page.setImage(ImageSource.cachedBitmap(bitmap));
    }

    /**
     * Decodes an image into a bounded bitmap via Glide and sets it as the page's cached bitmap.
     * Retries automatically if the file is still being written or flushed right after capture.
     */
    private void loadGlideBitmap(SubsamplingScaleImageView page, GalleryItem item, int retryCount,
                                 SubsamplingScaleImageView.OnImageEventListener listener) {
        if (released || page.getParent() == null) {
            com.particlesdevs.photoncamera.util.Log.d(GALLERY_TAG, "no decode of " + item.getFile().getDisplayName()
                    + (released ? ": the viewer is gone" : ": the page is not in the pager"));
            return;
        }
        final String name = item.getFile().getDisplayName();
        final Context app = page.getContext().getApplicationContext();
        // P620: a DNG / camera RAW read in place by the thumbnail decoder (DngGlideDecoder copied the whole file from a stream
        // first); every page shows the grid's cached thumbnail until its picture is decoded
        final boolean raw = com.particlesdevs.photoncamera.gallery.raw.RawFormats.isRawName(name);
        Glide.with(app)
                .asBitmap()
                .load(raw ? com.particlesdevs.photoncamera.gallery.glide.GalleryThumb.fit(item.getFile()) : item.getFile().getFileUri())
                .thumbnail(com.particlesdevs.photoncamera.gallery.glide.GalleryThumb.cached(Glide.with(app), app, item.getFile()))
                // P621: another app's DNG whose descriptor cannot be mapped (a pipe): its stream through Glide's decoders, as before
                .error(raw ? Glide.with(app).asBitmap().load(item.getFile().getFileUri())
                        .apply(RequestOptions.signatureOf(GalleryKeys.of(item.getFile())))
                        .override(GalleryDecodeRetry.FALLBACK_MAX_SIDE, GalleryDecodeRetry.FALLBACK_MAX_SIDE)
                        .downsample(SAMPLED_INSIDE).disallowHardwareConfig() : null)
                .apply(RequestOptions.signatureOf(GalleryKeys.of(item.getFile())))
                // Zoomable but bounded: at most 4096 px on the long side (~50 MB), never upscaled. P620: a 50 / 200 MP picture
                // sampled to about 4096 by the decoder itself (CENTER_INSIDE decoded 50 MP whole, 200 MB, before scaling it)
                .override(GalleryDecodeRetry.FALLBACK_MAX_SIDE, GalleryDecodeRetry.FALLBACK_MAX_SIDE)
                .downsample(SAMPLED_INSIDE)
                // P117: a software bitmap: the compare screenshot draws the panes into a software canvas, which throws on a
                // hardware bitmap
                .disallowHardwareConfig()
                .into(new CustomViewTarget<SubsamplingScaleImageView, Bitmap>(page) {
                    @Override
                    public void onResourceReady(@NonNull Bitmap bitmap, @Nullable Transition<? super Bitmap> transition) {
                        if (!released && page.getParent() != null) {
                            showBitmap(page, bitmap);
                        }
                    }

                    @Override
                    protected void onResourceCleared(@Nullable Drawable placeholder) {
                    }

                    @Override
                    public void onLoadFailed(@Nullable Drawable errorDrawable) {
                        if (released || page.getParent() == null) return;
                        if (GalleryDecodeRetry.mayRetry(retryCount)) {
                            long delay = GalleryDecodeRetry.delayMs(retryCount);
                            com.particlesdevs.photoncamera.util.Log.w(GALLERY_TAG,
                                    "Glide could not decode " + name + " - retrying in " + delay + " ms (retry " + (retryCount + 1) + ")");
                            page.postDelayed(() -> loadGlideBitmap(page, item, retryCount + 1, listener), delay);
                        } else {
                            com.particlesdevs.photoncamera.util.Log.w(GALLERY_TAG,
                                    "Glide failed to decode " + name + " after " + retryCount + " retries - giving up");
                            markGaveUp(item);
                            if (listener != null) {
                                listener.onImageLoadError(new IOException("Failed to decode " + name));
                            }
                        }
                    }
                });
    }

    private void markGaveUp(GalleryItem item) {
        final int position = galleryItemList.indexOf(item);
        if (inBounds(position)) gaveUp[position] = true;
    }

    /**
     * P81: decodes the page again when an earlier decode gave up (a WebP / AVIF opened while the camera still wrote it);
     * the shown page of a later swipe or of the return to the viewer.
     */
    public void reloadIfGaveUp(@Nullable SubsamplingScaleImageView page, int position) {
        if (page == null || released || !inBounds(position) || !gaveUp[position] || page.isImageLoaded()) return;
        gaveUp[position] = false;
        final GalleryItem item = galleryItemList.get(position);
        com.particlesdevs.photoncamera.util.Log.d(GALLERY_TAG, "decoding " + item.getFile().getDisplayName() + " again");
        loadGlideBitmap(page, item, 0, imageEventListener); // a JPEG page here has already failed its tiled decode
    }

    /**
     * Passes every image event of a tiled JPEG page on to the host's listener; when the tiled decode fails,
     * the page falls back to one bitmap decoded by Glide.
     */
    private final class BitmapFallback implements SubsamplingScaleImageView.OnImageEventListener {
        private final SubsamplingScaleImageView.OnImageEventListener host;
        private final SubsamplingScaleImageView page;
        private final GalleryItem item;
        private boolean fellBack;

        BitmapFallback(SubsamplingScaleImageView.OnImageEventListener host, SubsamplingScaleImageView page, GalleryItem item) {
            this.host = host;
            this.page = page;
            this.item = item;
        }

        @Override public void onReady() { if (host != null) host.onReady(); }
        @Override public void onImageLoaded() { if (host != null) host.onImageLoaded(); }
        @Override public void onPreviewReleased() { if (host != null) host.onPreviewReleased(); }

        @Override
        public void onPreviewLoadError(Exception e) {
            if (host != null) host.onPreviewLoadError(e);
        }

        @Override
        public void onTileLoadError(Exception e) {
            if (host != null) host.onTileLoadError(e);
            // A zoomed-in tile that fails later is retried by the view on the next pan; a page with no image yet stays black.
            if (GalleryDecodeRetry.fallBackOnTileError(page.isImageLoaded(), fellBack)) fallBack(e);
        }

        @Override
        public void onImageLoadError(Exception e) {
            fallBack(e);
        }

        private void fallBack(Exception e) {
            if (fellBack || released) {
                if (fellBack) markGaveUp(item);
                if (host != null) host.onImageLoadError(e);
                return;
            }
            fellBack = true;
            final String name = item.getFile().getDisplayName();
            com.particlesdevs.photoncamera.util.Log.w(GALLERY_TAG, "tiled decode failed for " + name + ": " + e
                    + " - decoding one bitmap through Glide");
            loadGlideBitmap(page, item, 0, host);
        }
    }

    @Override
    public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        if (object instanceof SubsamplingScaleImageView) {
            // Tile decodes still in flight would otherwise call the host fragment's listeners on a dead page,
            // and the page's tile bitmaps would stay alive until the next GC.
            detachPage((SubsamplingScaleImageView) object);
        }
        releaseHdrResources(container.getContext(), position);
        container.removeView((View) object);
    }

    /**
     * Detaches a page from every host listener and frees its decoder and tile
     * bitmaps right away. Asynchronous tile loads that finish later see a
     * recycled view and do not report onReady / scale changes any more.
     */
    public static void detachPage(SubsamplingScaleImageView page) {
        page.setOnImageEventListener(null);
        page.setOnStateChangedListener(null);
        page.setOnClickListener(null);
        if (page instanceof CustomSSIV) {
            ((CustomSSIV) page).setTouchCallBack(null);
        }
        Context ctx = page.getContext();
        if (ctx != null) {
            try {
                Glide.with(ctx.getApplicationContext()).clear(page);
            } catch (Throwable ignored) {
            }
        }
        page.recycle();
    }

    /**
     * Releases every page's Ultra HDR bitmap (full resolution: ~200 MB at 50 MP)
     * when the host view is destroyed; pending header scans and decodes are
     * ignored from then on.
     */
    public void releaseAllHdr(@Nullable Context context) {
        released = true;
        for (int position = 0; position < hdrTargets.length; position++) {
            releaseHdrResources(context, position);
        }
    }

    public void setImageViewClickListener(ImageViewClickListener imageViewClickListener) {
        this.imageViewClickListener = imageViewClickListener;
    }

    public int getSsivId(int position) {
        return BASE_ID + position;
    }

    /**
     * Whether the page at {@code position} is currently displaying an
     * Ultra HDR bitmap (gainmap attached) rather than the SDR tiled source.
     */
    public boolean isHdrActive(int position) {
        return inBounds(position) && hdrActive[position];
    }

    /**
     * Whether the page is known to contain an Ultra HDR gainmap. This remains true
     * after the user disables HDR viewing, so the gallery can show an HDR-off icon
     * and allow Ultra HDR rendering to be enabled again.
     */
    public boolean isHdrAvailable(int position) {
        return inBounds(position) && hdrAvailable[position];
    }

    /**
     * Loads the Ultra HDR rendition for the page at {@code position} if the
     * file contains a gain map and the device can display it. Cheap header
     * scan first, then a full-resolution Glide decode which preserves the
     * gainmap. No-op for SDR files, DNGs, non-HDR devices or pages already
     * in HDR mode.
     */
    public void loadHdrForPosition(CustomSSIV scaleImageView, int position) {
        if (scaleImageView == null || !inBounds(position)) {
            return;
        }
        if (hdrRequested[position] || hdrActive[position]) {
            return;
        }
        Context context = scaleImageView.getContext();
        if (!UltraHdrGalleryUtil.isDeviceHdrCapable(context)) {
            return;
        }
        GalleryItem galleryItem = galleryItemList.get(position);
        // Ultra HDR is a JPEG container (the camera writes it only into JPEG files): DNG, HEIC, WebP and AVIF pages skip the scan.
        String ext = FileUtils.getExtension(galleryItem.getFile().getDisplayName());
        if (!ext.equalsIgnoreCase("jpg") && !ext.equalsIgnoreCase("jpeg")) {
            return;
        }
        hdrRequested[position] = true;
        if (hdrAvailable[position]) {
            decodeHdrBitmap(scaleImageView, position);
            return;
        }
        HDR_EXECUTOR.execute(() -> {
            boolean candidate = UltraHdrGalleryUtil.isUltraHdrJpeg(context,
                    galleryItem.getFile().getFileUri());
            scaleImageView.post(() -> {
                if (released || !hdrRequested[position] || !inBounds(position)) {
                    return;
                }
                hdrRequested[position] = false;
                if (!candidate) {
                    hdrAvailable[position] = false;
                    if (hdrStateListener != null) {
                        hdrStateListener.onHdrAvailabilityChanged(position, false);
                    }
                    return;
                }
                hdrAvailable[position] = true;
                if (hdrStateListener != null) {
                    hdrStateListener.onHdrAvailabilityChanged(position, true);
                }
                decodeHdrBitmap(scaleImageView, position);
            });
        });
    }

    /**
     * Releases the Ultra HDR bitmap of a page, reverting it to the SDR
     * tiled source. Keeps at most one full-resolution HDR bitmap alive.
     */
    public void releaseHdrForPosition(CustomSSIV scaleImageView, int position) {
        if (scaleImageView == null || !inBounds(position)) {
            return;
        }
        boolean wasActive = hdrActive[position];
        releaseHdrResources(scaleImageView.getContext(), position);
        if (wasActive) {
            scaleImageView.setImage(ImageSource.uri(galleryItemList.get(position).getFile().getFileUri()));
            if (hdrStateListener != null) {
                hdrStateListener.onHdrStateChanged(position, false);
            }
        }
    }

    private void releaseHdrResources(Context context, int position) {
        if (!inBounds(position)) {
            return;
        }
        hdrRequested[position] = false;
        hdrActive[position] = false;
        hdrBitmaps[position] = null;
        Target<Bitmap> target = hdrTargets[position];
        hdrTargets[position] = null;
        if (target != null && context != null) {
            // The application RequestManager clears targets of any manager, and unlike an activity one it does not
            // throw while the activity is being destroyed (onDestroyView during finish()).
            Glide.with(context.getApplicationContext()).clear(target);
        }
    }

    private void decodeHdrBitmap(CustomSSIV scaleImageView, int position) {
        GalleryItem galleryItem = galleryItemList.get(position);
        CustomTarget<Bitmap> target = new CustomTarget<Bitmap>(HDR_MAX_SIDE, HDR_MAX_SIDE) {
            @Override
            public void onResourceReady(@NonNull Bitmap bitmap, @Nullable Transition<? super Bitmap> transition) {
                if (released || !hdrRequested[position] || !inBounds(position)) {
                    return;
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                        || !bitmap.hasGainmap()) {
                    hdrRequested[position] = false;
                    hdrAvailable[position] = false;
                    if (hdrStateListener != null) {
                        hdrStateListener.onHdrAvailabilityChanged(position, false);
                    }
                    return;
                }
                hdrActive[position] = true;
                hdrBitmaps[position] = bitmap;
                showBitmap(scaleImageView, bitmap);
                if (hdrStateListener != null) {
                    hdrStateListener.onHdrStateChanged(position, true);
                }
            }

            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) {
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                hdrRequested[position] = false;
                hdrAvailable[position] = false;
                if (hdrStateListener != null) {
                    hdrStateListener.onHdrAvailabilityChanged(position, false);
                }
            }
        };
        hdrTargets[position] = target;
        hdrRequested[position] = true;
        // P620: at most HDR_MAX_SIDE (a power-of-two sample: a 200 MP Ultra HDR JPEG at 1/2, 8160 x 6144 with its gain map, not
        // 800 MB at full size; 50 MP stays whole); not in the disk cache (DATA copied the whole JPEG there, 25-100 MB a page,
        // pushing out hundreds of grid thumbnails)
        Glide.with(scaleImageView.getContext())
                .asBitmap()
                .load(galleryItem.getFile().getFileUri())
                .apply(new RequestOptions()
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .skipMemoryCache(true)
                        .override(HDR_MAX_SIDE, HDR_MAX_SIDE)
                        .downsample(DownsampleStrategy.AT_MOST))
                .into(target);
    }

    /** P46: the colour of the page's file (UltraHdrGalleryUtil.COLOUR_*); sRGB until {@link #probeColour} knows it. */
    public int colourOf(int position) {
        return inBounds(position) && colour[position] > 0 ? colour[position] - 1 : UltraHdrGalleryUtil.COLOUR_SRGB;
    }

    /**
     * P46: reads the colour space of the page's file once (header only, on the HDR executor); {@code wider} runs on the
     * main thread when it turns out wider than sRGB (Display P3, HLG), so the host can switch the window's colour mode.
     */
    public void probeColour(int position, @Nullable Context context, @Nullable Runnable wider) {
        if (context == null || !inBounds(position) || colour[position] != 0) {
            return;
        }
        GalleryItem galleryItem = galleryItemList.get(position);
        if (FileUtils.getExtension(galleryItem.getFile().getDisplayName()).equalsIgnoreCase("dng")
                || com.particlesdevs.photoncamera.gallery.files.GalleryVideo.isVideo(galleryItem.getFile())) { // P498: a video's poster frame
            colour[position] = 1 + UltraHdrGalleryUtil.COLOUR_SRGB;
            return;
        }
        final Context app = context.getApplicationContext();
        final android.net.Uri uri = galleryItem.getFile().getFileUri();
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        HDR_EXECUTOR.execute(() -> {
            final int kind = UltraHdrGalleryUtil.colourOf(app, uri);
            main.post(() -> {
                if (released || !inBounds(position) || colour[position] != 0) {
                    return;
                }
                colour[position] = (byte) (1 + kind);
                if (kind != UltraHdrGalleryUtil.COLOUR_SRGB && wider != null) {
                    wider.run();
                }
            });
        });
    }

    private boolean inBounds(int position) {
        return position >= 0 && position < galleryItemList.size();
    }

    public interface ImageViewClickListener {
        void onImageViewClicked(View v);
    }

    /**
     * Notifies the host fragment when a page's HDR state changes so it can
     * toggle the window color mode.
     */
    public interface HdrStateListener {
        void onHdrStateChanged(int position, boolean isHdr);
        void onHdrAvailabilityChanged(int position, boolean isUltraHdr);
    }
}
