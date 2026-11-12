package com.particlesdevs.photoncamera.gallery.ui;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.gallery.files.GalleryVideo;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * P622: the texts of the list view's rows - a photo's secondary line («Сегодня, 11:17 · 50 Мп · 12,4 МБ»), its tags («DNG»,
 * «RAW + JPEG», «HEIC», «50 Мп», «SHDR», «Ultra HDR», a video's container) and an album's counts («128 фото · 3 видео · 12 RAW»).
 * Only what the file or its scan already says: the size and the route come from {@link ItemMeta} when it has read them.
 */
public final class RowText {
    private RowText() {}

    /** A tag's look: the format (accent tint), a fact about the picture (grey), the merge / HDR (warm). */
    public static final int FORMAT = 0, PLAIN = 1, HDR = 2;

    /** Pixel count from which the megapixels get a tag of their own (a 48 / 50 / 200 MP shot stands out in the list). */
    static final double BIG_MP = 40e6;

    public static final class Tag {
        public final String label;
        public final int kind;

        Tag(String label, int kind) {
            this.label = label;
            this.kind = kind;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * «Сегодня, 11:17 · 8192×6144 · 12,4 МБ» for a photo whose size is known (the megapixels are a tag of their own, or part of
     * the line below {@link #BIG_MP}: «12,6 Мп»), «Сегодня, 11:17 · 12,4 МБ» while it is not; a video: the date and the size.
     */
    public static String secondary(long when, long now, @Nullable int[] size, long bytes, boolean video) {
        StringBuilder s = new StringBuilder(GalleryUi.dayTime(when, now));
        if (!video && size != null && size.length == 2 && size[0] > 0 && size[1] > 0) {
            String mp = PhotoInfo.megapixels(size[0], size[1]);
            if (mp != null && (double) size[0] * size[1] < BIG_MP) s.append(" · ").append(mp);
            else s.append(" · ").append(size[0]).append('×').append(size[1]);
        }
        if (bytes > 0) s.append(" · ").append(GalleryUi.size(bytes));
        return s.toString();
    }

    /** The tags of a file named {@code name} of {@code format}: the format, big megapixels, the route, Ultra HDR. */
    public static List<Tag> tags(GalleryFormat format, @Nullable String name, @Nullable int[] size, @Nullable String route, boolean ultraHdr) {
        List<Tag> out = new ArrayList<>();
        String ext = GalleryFormat.extension(name).toUpperCase(Locale.ROOT);
        switch (format) {
            case VIDEO:
                if (!ext.isEmpty()) out.add(new Tag(ext, FORMAT));
                break;
            case RAW:
                out.add(new Tag(ext.isEmpty() ? "RAW" : ext, FORMAT)); // «DNG», «ARW», «CR3»
                break;
            case OTHER:
                if (!ext.isEmpty()) out.add(new Tag(ext, FORMAT));
                break;
            default:
                out.add(new Tag(format.label, FORMAT));
                break;
        }
        if (format != GalleryFormat.VIDEO && size != null && size.length == 2 && (double) size[0] * size[1] >= BIG_MP) {
            String mp = PhotoInfo.megapixels(size[0], size[1]);
            if (mp != null) out.add(new Tag(mp, PLAIN));
        }
        if (route != null && !route.isEmpty()) out.add(new Tag(route, HDR));
        if (ultraHdr) out.add(new Tag("Ultra HDR", HDR));
        return out;
    }

    /** «128 фото · 3 видео · 12 RAW» (zero parts left out); «Пусто» for none. */
    public static String counts(int photos, int videos, int raws) {
        List<String> parts = new ArrayList<>();
        if (photos > 0) parts.add(GalleryUi.dayCount(photos));
        if (videos > 0) parts.add(Lang.t(videos + " видео", videos + (videos == 1 ? " video" : " videos")));
        if (raws > 0) parts.add(raws + " RAW");
        if (parts.isEmpty()) return Lang.t("Пусто", "Empty");
        return String.join(" · ", parts);
    }

    /** {photos (videos left out), videos, RAW files among the photos} of an album's files. */
    public static int[] tally(List<GalleryItem> files) {
        int photos = 0, videos = 0, raws = 0;
        if (files != null) for (GalleryItem i : files) {
            if (i == null || i.getFile() == null) continue;
            String n = i.getFile().getDisplayName();
            if (GalleryVideo.isVideo(i.getFile())) ++videos;
            else {
                ++photos;
                if (GalleryFormat.of(n, false) == GalleryFormat.RAW) ++raws;
            }
        }
        return new int[]{photos, videos, raws};
    }

    /** An album's counts line from its files. */
    public static String counts(List<GalleryItem> files) {
        int[] t = tally(files);
        return counts(t[0], t[1], t[2]);
    }

    /** A video's length over its thumbnail («0:42»), null when unknown. */
    @Nullable
    public static String duration(long ms) {
        return ms > 0 ? GalleryVideo.clock(ms) : null;
    }

    /** What a screen reader says for a row: the name, the secondary line, the tags, the length, favourite, the last opened. */
    public static String describe(String name, String secondary, List<Tag> tags, @Nullable String duration, boolean favourite, boolean last) {
        StringBuilder s = new StringBuilder(name).append(", ").append(secondary);
        if (duration != null) s.append(", ").append(Lang.t("видео ", "video ")).append(duration);
        for (Tag t : tags) s.append(", ").append(t.label);
        if (favourite) s.append(", ").append(Lang.t("избранное", "favourite"));
        if (last) s.append(", ").append(Lang.t("открыто последним", "opened last"));
        return s.toString();
    }
}
