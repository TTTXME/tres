package com.scamera.app.gallery.glide;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.ResourceDecoder;
import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapResource;
import com.bumptech.glide.request.target.Target;
import com.scamera.app.gallery.dng.DngPreview;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Locale;

/**
 * P620: decodes a {@link GalleryThumb} from the cheapest source that is large enough for the request, never at full resolution:
 * <ul>
 *   <li>JPEG: the EXIF (IFD1) thumbnail when it has the picture's aspect and {@link ThumbPlan#ENOUGH} of the size; else a
 *   power-of-two sampled decode (libjpeg-turbo's DCT scaling up to 1/8) from the descriptor; the EXIF orientation applied.</li>
 *   <li>DNG: the file mapped in place (no temporary copy of 25-400 MB as DngGlideDecoder makes from a stream): an embedded
 *   JPEG preview that is large enough, else {@link DngPreview}'s render, sparse for a grid cell (a few CFA rows a block).</li>
 *   <li>Other camera RAWs: LibRaw on the file's path when it can be read (a copy only when not), its embedded thumbnail first.</li>
 *   <li>HEIF: the platform's thumbnail (ThumbnailUtils, Android 10+: the HEIF thumbnail item when the file has one), else a
 *   sampled ImageDecoder decode; AVIF: AvifGlideDecoder at the size; PNG / WebP / GIF / BMP: a sampled ImageDecoder decode.</li>
 *   <li>Video: MediaStore's thumbnail (MediaProvider keeps it), else a frame scaled by MediaMetadataRetriever.</li>
 * </ul>
 * The result is scaled down to the size the request needs (a grid cell: the short side covers it) and goes into Glide's disk
 * cache (GalleryThumb's options). A failure throws: the request's error path then gives the file's uri to Glide's own decoders.
 */
public final class ThumbDecoder implements ResourceDecoder<GalleryThumb, Bitmap> {
    private static final int HEAD = 64 * 1024;
    /**
     * P637: only a picture embedded in the file - a JPEG's, HEIC's or AVIF's EXIF (IFD1) thumbnail of any size with the picture's
     * aspect, a DNG's JPEG preview - never a decode of the picture itself; a file without one fails the request. The viewer's
     * first picture of a page whose thumbnail is not cached: a few ms, where a sampled decode would run beside the page's own.
     */
    public static final com.bumptech.glide.load.Option<Boolean> EMBEDDED_ONLY =
            com.bumptech.glide.load.Option.memory("com.scamera.app.gallery.glide.ThumbDecoder.EmbeddedOnly", false);
    private final Context context;
    private final BitmapPool pool;

    public ThumbDecoder(Context context, BitmapPool pool) {
        this.context = context.getApplicationContext();
        this.pool = pool;
    }

    @Override
    public boolean handles(@NonNull GalleryThumb source, @NonNull Options options) {
        return true;
    }

    /** A decoded picture and where it came from. */
    private static final class Out {
        Bitmap bitmap;
        String source;
        int srcW, srcH;

        Out(Bitmap bitmap, String source, int srcW, int srcH) {
            this.bitmap = bitmap;
            this.source = source;
            this.srcW = srcW;
            this.srcH = srcH;
        }
    }

    @Nullable
    @Override
    public Resource<Bitmap> decode(@NonNull GalleryThumb m, int width, int height, @NonNull Options options) throws IOException {
        final long t0 = System.nanoTime();
        final int reqW = width > 0 && width != Target.SIZE_ORIGINAL ? width : 2048;
        final int reqH = height > 0 && height != Target.SIZE_ORIGINAL ? height : 2048;
        ThumbPlan.Kind kind = ThumbPlan.Kind.OTHER;
        final boolean embeddedOnly = Boolean.TRUE.equals(options.get(EMBEDDED_ONLY));
        try (Src src = Src.open(context, m)) {
            byte[] head = src.head(HEAD);
            kind = ThumbPlan.kindOf(m.name, head, head.length);
            Out out;
            if (embeddedOnly) out = embedded(src, m, kind, reqW, reqH);
            else switch (kind) {
                case JPEG: out = jpeg(src, m, reqW, reqH); break;
                case DNG: out = dng(src, m, reqW, reqH); break;
                case RAW: out = raw(src, m, reqW, reqH); break;
                case HEIF: out = heif(src, m, reqW, reqH); break;
                case AVIF: out = avif(src, m, reqW, reqH); break;
                case VIDEO: out = video(src, m, reqW, reqH); break;
                default: out = sampled(src, m, reqW, reqH, null); break;
            }
            if (out == null || out.bitmap == null) throw new IOException("no picture");
            Bitmap b = fit(out.bitmap, reqW, reqH, m.cover);
            ThumbTrace.decoded(kind.name().toLowerCase(Locale.ROOT), out.source, (System.nanoTime() - t0) / 1_000_000,
                    out.srcW, out.srcH, b.getWidth(), b.getHeight());
            return BitmapResource.obtain(b, pool);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            // P637: a file without an embedded picture is no failure (the page's own decode shows it)
            if (!embeddedOnly) ThumbTrace.failed(kind.name().toLowerCase(Locale.ROOT), m.name, e);
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    // ---------------------------------------------------------------- formats

    /** P637: {@link #EMBEDDED_ONLY}: the file's embedded picture, else an IOException (nothing is decoded from the picture). */
    private Out embedded(Src src, GalleryThumb m, ThumbPlan.Kind kind, int reqW, int reqH) throws IOException {
        Out out = null;
        switch (kind) {
            case JPEG: {
                ThumbPlan.Jpeg j = ThumbPlan.parseJpeg(src.map());
                if (j != null && j.hasThumb() && ThumbPlan.sameAspect(j.thumbWidth, j.thumbHeight, j.width, j.height)) {
                    byte[] data = new byte[j.thumbLength];
                    ByteBuffer d = src.map().duplicate();
                    d.position(j.thumbOffset);
                    d.get(data);
                    Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (b != null) out = new Out(DngGlideDecoder.orient(b, j.orientation), "exif " + j.thumbWidth + "x" + j.thumbHeight, j.width, j.height);
                }
                break;
            }
            case HEIF:
            case AVIF:
                out = exifThumb(src, m, reqW, reqH, true);
                break;
            case DNG: {
                DngPreview.Result r = DngPreview.read(src.map(), reqW, reqH, true, true);
                if (r != null && r.isJpeg()) {
                    byte[] data = new byte[r.jpegLength];
                    ByteBuffer d = src.map().duplicate();
                    d.position(r.jpegOffset);
                    d.get(data);
                    Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (b != null) out = new Out(DngGlideDecoder.orient(b, r.orientation), "dng-preview " + r.width + "x" + r.height, r.width, r.height);
                }
                break;
            }
            default:
                break;
        }
        if (out == null) throw new IOException("no embedded preview");
        return out;
    }

    /**
     * P637: the EXIF (IFD1) JPEG thumbnail of a HEIC / AVIF (the camera writes one, 512 px, P620; androidx ExifInterface finds the
     * container's EXIF item), turned by the EXIF orientation; null without one, or - unless {@code anySize} - when it is too
     * small for the request or the picture's size is unknown. Its own descriptor: ExifInterface moves the offset.
     */
    @Nullable
    private Out exifThumb(Src src, GalleryThumb m, int reqW, int reqH, boolean anySize) {
        ParcelFileDescriptor own = null;
        try {
            androidx.exifinterface.media.ExifInterface exif;
            File direct = src.readablePath();
            if (direct != null) exif = new androidx.exifinterface.media.ExifInterface(direct);
            else {
                own = context.getContentResolver().openFileDescriptor(m.uri, "r");
                if (own == null) return null;
                exif = new androidx.exifinterface.media.ExifInterface(own.getFileDescriptor());
            }
            if (!exif.hasThumbnail()) return null;
            byte[] data = exif.getThumbnailBytes();
            if (data == null || data.length == 0) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            final int tw = o.outWidth, th = o.outHeight;
            final int w = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_WIDTH, 0),
                    h = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_IMAGE_LENGTH, 0);
            final int orientation = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1);
            final boolean turned = orientation >= 5 && orientation <= 8;
            // the thumbnail is stored unturned; the size the container reports is the stored or the displayed one
            final boolean stored = ThumbPlan.sameAspect(tw, th, w, h);
            if (w > 0 && h > 0 && !stored && !ThumbPlan.sameAspect(tw, th, h, w)) return null;
            final int pw = stored ? w : h, ph = stored ? h : w;
            if (!anySize && !ThumbPlan.enough(tw, th, pw, ph, turned ? reqH : reqW, turned ? reqW : reqH, m.cover)) return null;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (b == null) return null;
            return new Out(DngGlideDecoder.orient(b, orientation), "exif " + tw + "x" + th, w, h);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        } finally {
            if (own != null) try {
                own.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Nullable
    private Out jpeg(Src src, GalleryThumb m, int reqW, int reqH) throws IOException {
        ThumbPlan.Jpeg j = ThumbPlan.parseJpeg(src.map());
        if (j == null) return sampled(src, m, reqW, reqH, null);
        // the request in the stored orientation (the EXIF thumbnail and the frame are stored unturned)
        final boolean turned = j.orientation >= 5 && j.orientation <= 8;
        final int rw = turned ? reqH : reqW, rh = turned ? reqW : reqH;
        if (j.hasThumb() && ThumbPlan.enough(j.thumbWidth, j.thumbHeight, j.width, j.height, rw, rh, m.cover)) {
            byte[] data = new byte[j.thumbLength];
            ByteBuffer d = src.map().duplicate();
            d.position(j.thumbOffset);
            d.get(data);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = ThumbPlan.sampleSize(j.thumbWidth, j.thumbHeight, rw, rh, m.cover);
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b != null) return new Out(DngGlideDecoder.orient(b, j.orientation), "exif " + j.thumbWidth + "x" + j.thumbHeight, j.width, j.height);
        }
        final int sample = ThumbPlan.sampleSize(j.width, j.height, rw, rh, m.cover);
        if (!j.progressive && (long) j.width * j.height >= STRIPS_MIN_PIXELS) {
            Out strips = strips(src, j, sample);
            if (strips != null) return strips;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b = BitmapFactory.decodeFileDescriptor(src.fd(), null, o);
        if (b == null) return sampled(src, m, reqW, reqH, null);
        return new Out(DngGlideDecoder.orient(b, j.orientation), "decoded 1/" + o.inSampleSize + (j.progressive ? " progressive" : ""), j.width, j.height);
    }

    /** P620: JPEGs from 24 MP on are split at their restart markers when they can be (the camera's 50 / 200 MP ones). */
    static final long STRIPS_MIN_PIXELS = 24_000_000L;
    /** The strip decodes of every thumbnail (Glide's decode threads wait on them): most cores, not all. */
    private static final java.util.concurrent.ExecutorService STRIP_POOL = java.util.concurrent.Executors.newFixedThreadPool(
            Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 2)), r -> {
                Thread t = new Thread(r, "GalleryStrips");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

    /**
     * P620 (owner, 2026-11-11: «200мп в галерее тоже ускорь»): the strips of a restart-marker JPEG ({@link JpegStrips}) decoded
     * in parallel at a sample of at most 1/8 (libjpeg's DCT scaling: whole 8-row MCUs, so the strips meet exactly) and stacked;
     * {@link #fit} scales the rest. Null when the file has no such strips or a strip fails (the one-core decode then).
     */
    @Nullable
    private static Out strips(Src src, ThumbPlan.Jpeg j, int sample) {
        try {
            final ByteBuffer map = src.map();
            final JpegStrips js = JpegStrips.parse(map, 4);
            if (js == null || js.width != j.width || js.height != j.height) return null;
            final int s = Math.max(1, Math.min(8, sample));
            if (js.stripRows % s != 0) return null;
            java.util.List<java.util.concurrent.Future<Bitmap>> parts = new java.util.ArrayList<>(js.strips());
            for (int i = 0; i < js.strips(); i++) {
                final int k = i;
                parts.add(STRIP_POOL.submit(() -> {
                    byte[] data = js.strip(map, k);
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = s;
                    o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    return BitmapFactory.decodeByteArray(data, 0, data.length, o);
                }));
            }
            Bitmap[] got = new Bitmap[parts.size()];
            boolean ok = true;
            for (int i = 0; i < got.length; i++) {
                try {
                    got[i] = parts.get(i).get();
                } catch (Exception e) {
                    got[i] = null;
                }
                ok &= got[i] != null;
            }
            Bitmap out = null;
            if (ok) {
                int w = got[0].getWidth(), h = 0;
                for (Bitmap g : got) h += g.getHeight();
                out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, false,
                        got[0].getColorSpace() != null ? got[0].getColorSpace() : android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB));
                android.graphics.Canvas c = new android.graphics.Canvas(out);
                int y = 0;
                for (Bitmap g : got) {
                    c.drawBitmap(g, 0, y, null);
                    y += g.getHeight();
                }
            }
            for (Bitmap g : got) if (g != null) g.recycle();
            if (out == null) return null;
            return new Out(DngGlideDecoder.orient(out, j.orientation), "strips " + js.strips() + " x 1/" + s, j.width, j.height);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private Out dng(Src src, GalleryThumb m, int reqW, int reqH) throws IOException {
        DngPreview.Result r;
        try {
            r = DngPreview.read(src.map(), reqW, reqH, m.cover);
        } catch (IOException | RuntimeException e) {
            // a DNG the gallery's reader does not know: LibRaw, as Glide's decoders did after DngGlideDecoder
            if (!com.scamera.app.gallery.raw.LibRawBridge.available()) throw e;
            return raw(src, m, reqW, reqH);
        }
        Bitmap b;
        String how;
        if (r.isJpeg()) {
            byte[] data = new byte[r.jpegLength];
            ByteBuffer d = src.map().duplicate();
            d.position(r.jpegOffset);
            d.get(data);
            final boolean turned = r.orientation >= 5 && r.orientation <= 8;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = ThumbPlan.sampleSize(r.width, r.height, turned ? reqH : reqW, turned ? reqW : reqH, m.cover);
            b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            how = "dng-preview " + r.width + "x" + r.height;
        } else {
            b = Bitmap.createBitmap(r.argb, r.width, r.height, Bitmap.Config.ARGB_8888);
            how = "dng-raw" + (m.cover ? " sparse" : "");
        }
        if (b == null) throw new IOException("DNG preview not decodable");
        return new Out(DngGlideDecoder.orient(b, r.orientation), how, r.width, r.height);
    }

    private Out raw(Src src, GalleryThumb m, int reqW, int reqH) throws IOException {
        if (!com.scamera.app.gallery.raw.LibRawBridge.available()) throw new IOException("RAW: no LibRaw in this build");
        String[] how = new String[1];
        File direct = src.readablePath();
        if (direct != null) {
            Bitmap b = RawGlideDecoder.fromFile(direct, reqW, reqH, how);
            return new Out(b, "raw " + how[0], 0, 0);
        }
        File tmp = File.createTempFile("raw", ".tmp", context.getCacheDir());
        try {
            src.copyTo(tmp);
            Bitmap b = RawGlideDecoder.fromFile(tmp, reqW, reqH, how);
            return new Out(b, "raw-copy " + how[0], 0, 0);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    @Nullable
    private Out heif(Src src, GalleryThumb m, int reqW, int reqH) throws IOException {
        // P637: the camera's HEIC has an EXIF thumbnail (P620) and no HEIF thumbnail item: the platform's thumbnail was a decode
        // of the whole HEVC picture
        Out exif = exifThumb(src, m, reqW, reqH, false);
        if (exif != null) return exif;
        File direct = src.readablePath();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && direct != null) {
            // the platform's own thumbnail: the HEIF thumbnail item when there is one, else its sampled decode
            Size box = box(reqW, reqH, m.cover);
            try {
                Bitmap b = ThumbnailUtils.createImageThumbnail(direct, box, null);
                int[] t = ThumbPlan.targetSize(Math.max(b.getWidth(), 1), Math.max(b.getHeight(), 1), reqW, reqH, m.cover);
                if (b.getWidth() >= ThumbPlan.ENOUGH * t[0] && b.getHeight() >= ThumbPlan.ENOUGH * t[1])
                    return new Out(b, "platform-thumbnail", 0, 0);
                b.recycle();
            } catch (IOException | RuntimeException e) {
                // the sampled decode below
            }
        }
        return sampled(src, m, reqW, reqH, null);
    }

    @Nullable
    private Out avif(Src src, GalleryThumb m, int reqW, int reqH) throws IOException {
        Out exif = exifThumb(src, m, reqW, reqH, false); // P637: as the HEIC
        if (exif != null) return exif;
        if (!AvifGlideDecoder.decodable()) return sampled(src, m, reqW, reqH, null);
        Size box = box(reqW, reqH, m.cover);
        Bitmap b = AvifGlideDecoder.decodeBytes(src.bytes(), box.getWidth(), box.getHeight());
        return new Out(b, "avif-decoder", 0, 0);
    }

    @Nullable
    private Out video(Src src, GalleryThumb m, int reqW, int reqH) throws IOException {
        Size box = box(reqW, reqH, m.cover);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ContentResolver.SCHEME_CONTENT.equals(m.uri.getScheme())) {
            try {
                Bitmap b = context.getContentResolver().loadThumbnail(m.uri, box, null);
                if (b != null) return new Out(b, "mediastore", 0, 0);
            } catch (IOException | RuntimeException e) {
                // a frame below
            }
        }
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(src.fd());
            Bitmap b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
                    ? r.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, box.getWidth(), box.getHeight())
                    : r.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b == null) throw new IOException("video: no frame");
            return new Out(b, "frame", 0, 0);
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * A power-of-two sampled decode: ImageDecoder (Android 9+, the EXIF orientation applied by it) from the file or the uri, else
     * BitmapFactory from the descriptor.
     */
    @Nullable
    private Out sampled(Src src, GalleryThumb m, int reqW, int reqH, @Nullable String label) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            File direct = src.readablePath();
            ImageDecoder.Source s = direct != null ? ImageDecoder.createSource(direct)
                    : ImageDecoder.createSource(context.getContentResolver(), m.uri);
            final int[] info = new int[3];
            Bitmap b = ImageDecoder.decodeBitmap(s, (decoder, i, source) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int w = i.getSize().getWidth(), h = i.getSize().getHeight();
                int sample = ThumbPlan.sampleSize(w, h, reqW, reqH, m.cover);
                if (sample > 1) decoder.setTargetSampleSize(sample);
                info[0] = w;
                info[1] = h;
                info[2] = sample;
            });
            return new Out(b, (label != null ? label : "decoded") + " 1/" + info[2], info[0], info[1]);
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFileDescriptor(src.fd(), null, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("not decodable");
        final int w = o.outWidth, h = o.outHeight;
        o = new BitmapFactory.Options();
        o.inSampleSize = ThumbPlan.sampleSize(w, h, reqW, reqH, m.cover);
        Bitmap b = BitmapFactory.decodeFileDescriptor(src.fd(), null, o);
        return b != null ? new Out(b, (label != null ? label : "decoded") + " 1/" + o.inSampleSize, w, h) : null;
    }

    /** The box a platform thumbnail fits inside: twice the cell for a cover (a short side of 2:1 still covers it). */
    private static Size box(int reqW, int reqH, boolean cover) {
        return cover ? new Size(2 * Math.max(reqW, reqH), 2 * Math.max(reqW, reqH)) : new Size(reqW, reqH);
    }

    /** {@code b} scaled down to the request's size when it is notably larger (the cache keeps the small one). */
    private static Bitmap fit(Bitmap b, int reqW, int reqH, boolean cover) {
        int[] t = ThumbPlan.targetSize(b.getWidth(), b.getHeight(), reqW, reqH, cover);
        if (!ThumbPlan.worthScaling(b.getWidth(), b.getHeight(), t)) return b;
        Bitmap s = Bitmap.createScaledBitmap(b, t[0], t[1], true);
        if (s != b) b.recycle();
        return s;
    }

    // ---------------------------------------------------------------- the file

    /** The opened file: a descriptor (the uri through the ContentResolver, else the path), mapped on demand. */
    static final class Src implements Closeable {
        private final ParcelFileDescriptor pfd;
        private final FileInputStream in;
        private final FileChannel channel;
        @Nullable private final String path;
        private ByteBuffer map;

        private Src(ParcelFileDescriptor pfd, @Nullable String path) {
            this.pfd = pfd;
            this.in = new FileInputStream(pfd.getFileDescriptor());
            this.channel = in.getChannel();
            this.path = path;
        }

        static Src open(Context context, GalleryThumb m) throws IOException {
            ParcelFileDescriptor pfd = null;
            if (ContentResolver.SCHEME_CONTENT.equals(m.uri.getScheme())) {
                try {
                    pfd = context.getContentResolver().openFileDescriptor(m.uri, "r");
                } catch (IOException | RuntimeException e) {
                    if (m.path == null) throw e instanceof IOException ? (IOException) e : new IOException(e);
                }
            }
            if (pfd == null && m.path != null) pfd = ParcelFileDescriptor.open(new File(m.path), ParcelFileDescriptor.MODE_READ_ONLY);
            if (pfd == null && "file".equals(m.uri.getScheme()) && m.uri.getPath() != null)
                pfd = ParcelFileDescriptor.open(new File(m.uri.getPath()), ParcelFileDescriptor.MODE_READ_ONLY);
            if (pfd == null) throw new IOException("cannot open " + m.uri);
            return new Src(pfd, m.path);
        }

        java.io.FileDescriptor fd() {
            return pfd.getFileDescriptor();
        }

        /** Up to {@code max} first bytes (a positional read: the descriptor's offset stays at 0 for BitmapFactory). */
        byte[] head(int max) throws IOException {
            long size = channel.size();
            ByteBuffer b = ByteBuffer.allocate((int) Math.min(max, Math.max(0, size)));
            while (b.hasRemaining() && channel.read(b, b.position()) > 0) { /* read on */ }
            return java.util.Arrays.copyOf(b.array(), b.position());
        }

        /** The whole file mapped read-only (pages are read only where the decoder looks). */
        ByteBuffer map() throws IOException {
            if (map == null) {
                long size = channel.size();
                if (size > Integer.MAX_VALUE) throw new IOException("file too large to map: " + size);
                map = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
            }
            return map;
        }

        /** The whole file (an AVIF: a few MB). */
        byte[] bytes() throws IOException {
            ByteBuffer m = map().duplicate();
            m.position(0);
            byte[] out = new byte[m.remaining()];
            m.get(out);
            return out;
        }

        /** The file's own path when this process can read it (LibRaw, ThumbnailUtils), else null. */
        @Nullable
        File readablePath() {
            if (path == null) return null;
            File f = new File(path);
            return f.canRead() ? f : null;
        }

        void copyTo(File out) throws IOException {
            try (FileOutputStream o = new FileOutputStream(out); FileChannel oc = o.getChannel()) {
                long size = channel.size(), at = 0;
                while (at < size) {
                    long n = channel.transferTo(at, size - at, oc);
                    if (n <= 0) break;
                    at += n;
                }
            }
        }

        /** Closes the descriptor only (its owner; the stream over it does not own it, and a mapping outlives it). */
        @Override
        public void close() throws IOException {
            pfd.close();
        }
    }
}
