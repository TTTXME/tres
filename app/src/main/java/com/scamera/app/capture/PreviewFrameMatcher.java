package com.scamera.app.capture;

import java.util.LinkedHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Owns at most {@link #DEFAULT_IMAGES} preview images while waiting for their exact CaptureResult. P653: a session whose HAL
 * returns the viewfinder's results a few frames after their images (the Xiaomi 17 Ultra with a full-size output of a vendor
 * stream use case configured) keeps more ({@link #imageLimit(int)}); the counters say how late the results ran. P707: a pair is
 * delivered after this matcher's lock is left (the delivery - the live RAW frame's meters and ring copy, 10-100 ms - held it, and
 * {@link #clear} on the main thread waited for it); the caller's thread runs it as before.
 */
public final class PreviewFrameMatcher<T, R> {
    /** Unmatched images kept by default (of the viewfinder reader's buffers). */
    public static final int DEFAULT_IMAGES = 2;
    private final LinkedHashMap<Long,T> images = new LinkedHashMap<>();
    private final LinkedHashMap<Long,R> results = new LinkedHashMap<>();
    private final BiConsumer<T,R> deliver;
    private final Consumer<T> release;
    private int imageLimit = DEFAULT_IMAGES;
    private long matched, dropped;
    private int maxLate;
    public PreviewFrameMatcher(BiConsumer<T,R> deliver, Consumer<T> release) {
        this.deliver=deliver; this.release=release;
    }
    /** P653: the unmatched images kept from now on (at least 1); the counters start again. */
    public synchronized void imageLimit(int limit) {
        imageLimit=Math.max(1,limit);
        matched=0; dropped=0; maxLate=0;
        trim();
    }
    public synchronized int imageLimit() { return imageLimit; }
    public void image(long timestamp, T image) {
        final R result;
        synchronized (this) {
            result=results.remove(timestamp);
            if(result==null) {
                T old=images.put(timestamp,image);
                if(old!=null) { dropped++; release.accept(old); }
                trim();
                return;
            }
            matched++;
        }
        deliver.accept(image,result);
    }
    public void result(long timestamp,R result) {
        final T image;
        synchronized (this) {
            if(!images.containsKey(timestamp)) {
                results.put(timestamp,result);
                while(results.size()>16) results.remove(results.keySet().iterator().next());
                return;
            }
            // how many images came after this one while it waited for its result: the result's lag in frames
            int later=0;
            boolean after=false;
            for(Long t:images.keySet()) { if(after) later++; else if(t==timestamp) after=true; }
            maxLate=Math.max(maxLate,later);
            matched++;
            image=images.remove(timestamp);
        }
        deliver.accept(image,result);
    }
    public synchronized void clear() {
        for(T image:images.values()) release.accept(image);
        images.clear(); results.clear();
    }
    /** "kept ≤N unmatched images, matched m, dropped d, results up to k frames late". */
    public synchronized String stats() {
        return "kept <=" + imageLimit + " unmatched images, matched " + matched + ", dropped " + dropped + ", results up to " + maxLate + " frames late";
    }
    private void trim() {
        while(images.size()>imageLimit) { dropped++; release.accept(images.remove(images.keySet().iterator().next())); }
    }
}
