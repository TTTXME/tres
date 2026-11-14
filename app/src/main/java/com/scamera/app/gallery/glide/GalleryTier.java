package com.scamera.app.gallery.glide;

import com.scamera.app.util.RamClass;

/**
 * P706 (owner, 2026-11-14: «даже на старых смартфонах … работало быстро» and «на 8 Gen 3 и новее … оптимально пользуйся их
 * мощностью»): how much the gallery keeps and prepares ahead, by the phone's memory - never what it shows (every picture is
 * decoded at the same size and in ARGB_8888 on every tier).
 * <ul>
 *   <li>{@link #LOW} (a low-RAM device or at most 4 GB): two screens of decoded pictures in memory, one viewer page kept on
 *   each side (a page of HEIC / DNG is one bitmap of up to 4096 px, ~50 MB), two rows of thumbnails decoded ahead.</li>
 *   <li>{@link #MID} (at most 8 GB, or unknown): as before P706 - three screens, two pages, four rows.</li>
 *   <li>{@link #HIGH} (more than 8 GB): five screens (a swipe back to a photo seen is a memory hit), two pages, six rows.</li>
 * </ul>
 * Glide caps its memory cache by the app's memory class anyway: the screens are an upper bound.
 */
public final class GalleryTier {
    public static final int LOW = 0, MID = 1, HIGH = 2;
    static final long LOW_BYTES = 4L << 30;

    private GalleryTier() {}

    /** The tier of a phone of {@code totalBytes} (0: unknown) memory. */
    public static int of(long totalBytes, boolean lowRamDevice) {
        if (lowRamDevice || (totalBytes > 0 && totalBytes <= LOW_BYTES)) return LOW;
        if (totalBytes <= 0 || totalBytes <= RamClass.LOW_TOTAL_BYTES) return MID;
        return HIGH;
    }

    /** This phone's tier. */
    public static int get() {
        final RamClass r = RamClass.get();
        return of(r.totalBytes, r.lowRamDevice);
    }

    /** Glide's memory cache in screens of pictures. */
    public static float memoryCacheScreens(int tier) {
        return tier == LOW ? 2 : tier == HIGH ? 5 : 3;
    }

    /** The viewer's pages kept beside the shown one, on each side (compare: one, whatever the tier). */
    public static int viewerOffscreenPages(int tier, boolean compare) {
        return compare || tier == LOW ? 1 : 2;
    }

    /** The grid's rows of thumbnails decoded ahead of the scroll. */
    public static int preloadRows(int tier) {
        return tier == LOW ? 2 : tier == HIGH ? 6 : 4;
    }
}
