package com.particlesdevs.photoncamera.gallery.adapters;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;

import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * P620 (owner, 2026-11-11: «200мп в галерее тоже ускорь»): the JPEG page's tile decoder. SubsamplingScaleImageView's own one opens
 * a content URI as an InputStream, and BitmapRegionDecoder copies a stream that is not an asset into native memory: the whole
 * file per page (25 MB at 50 MP, 60-100 MB at 200 MP; five pages in the pager). This one decodes from the file descriptor (read
 * where a tile needs it, nothing copied) and keeps up to {@link #maxDecoders} decoders over their own descriptors, so the tiles
 * of a base layer or of a 1:1 zoom decode in parallel (one BitmapRegionDecoder decodes one region at a time; a region near the
 * bottom of a 200 MP JPEG entropy-decodes every row above it).
 */
public class FdRegionDecoder implements ImageRegionDecoder {
    /** Decoders of one page at most (each a descriptor and libjpeg's state, a few MB while decoding). */
    static int maxDecoders() {
        return Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 2));
    }

    private Context context;
    private Uri uri;
    private final List<Entry> free = new ArrayList<>();
    private int count;
    private boolean recycled;

    private static final class Entry {
        final ParcelFileDescriptor pfd;
        final BitmapRegionDecoder decoder;

        Entry(ParcelFileDescriptor pfd, BitmapRegionDecoder decoder) {
            this.pfd = pfd;
            this.decoder = decoder;
        }

        void close() {
            decoder.recycle();
            try {
                pfd.close();
            } catch (IOException ignored) {
            }
        }
    }

    @NonNull
    @Override
    public Point init(Context context, @NonNull Uri uri) throws Exception {
        this.context = context.getApplicationContext();
        this.uri = uri;
        Entry e = open();
        synchronized (this) {
            count = 1;
            free.add(e);
        }
        return new Point(e.decoder.getWidth(), e.decoder.getHeight());
    }

    @SuppressWarnings("deprecation")
    private Entry open() throws IOException {
        ParcelFileDescriptor pfd;
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) pfd = context.getContentResolver().openFileDescriptor(uri, "r");
        else if (uri.getPath() != null) pfd = ParcelFileDescriptor.open(new File(uri.getPath()), ParcelFileDescriptor.MODE_READ_ONLY);
        else throw new IOException("no file for " + uri);
        if (pfd == null) throw new IOException("cannot open " + uri);
        try {
            BitmapRegionDecoder d = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? BitmapRegionDecoder.newInstance(pfd)
                    : BitmapRegionDecoder.newInstance(pfd.getFileDescriptor(), false);
            if (d == null) throw new IOException("no region decoder for " + uri);
            return new Entry(pfd, d);
        } catch (IOException | RuntimeException e) {
            pfd.close();
            throw e;
        }
    }

    /** A free decoder, a new one while fewer than {@link #maxDecoders} exist, else waits for one. */
    private Entry acquire() throws IOException, InterruptedException {
        synchronized (this) {
            while (true) {
                if (recycled) throw new IOException("recycled");
                if (!free.isEmpty()) return free.remove(free.size() - 1);
                if (count < maxDecoders()) {
                    count++;
                    break;
                }
                wait();
            }
        }
        try {
            return open();
        } catch (IOException | RuntimeException e) {
            synchronized (this) {
                count--;
                notifyAll();
            }
            throw e;
        }
    }

    private void release(Entry e) {
        synchronized (this) {
            if (!recycled) {
                free.add(e);
                notifyAll();
                return;
            }
            count--;
            notifyAll();
        }
        e.close();
    }

    @NonNull
    @Override
    public Bitmap decodeRegion(@NonNull Rect rect, int sampleSize) {
        Entry e;
        try {
            e = acquire();
        } catch (IOException | InterruptedException ex) {
            throw new IllegalStateException("Cannot decode region after decoder has been recycled", ex);
        }
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sampleSize;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap b = e.decoder.decodeRegion(rect, o);
            if (b == null) throw new RuntimeException("Region decoder returned null bitmap - image format may not be supported");
            return b;
        } finally {
            release(e);
        }
    }

    @Override
    public synchronized boolean isReady() {
        return !recycled && count > 0;
    }

    @Override
    public void recycle() {
        List<Entry> idle;
        synchronized (this) {
            recycled = true;
            idle = new ArrayList<>(free);
            count -= idle.size();
            free.clear();
            notifyAll();
        }
        for (Entry e : idle) e.close(); // a decoder busy now closes when its tile is done (release)
    }
}
