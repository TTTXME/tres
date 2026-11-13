package com.scamera.app.capture;

import java.util.LinkedHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Owns at most {@link #DEFAULT_IMAGES} preview images while waiting for their exact CaptureResult. P653: a session whose HAL
 * returns the viewfinder's results a few frames after their images (the Xiaomi 17 Ultra with a full-size output of a vendor
 * stream use case configured) keeps more ({@link #imageLimit(int)}); the counters say how late the results ran.
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
    public synchronized void image(long timestamp, T image) {
        R result=results.remove(timestamp);
        if(result!=null) { matched++; deliver.accept(image,result); return; }
        T old=images.put(timestamp,image);
        if(old!=null) { dropped++; release.accept(old); }
        trim();
    }
    public synchronized void result(long timestamp,R result) {
        if(images.containsKey(timestamp)) {
            // how many images came after this one while it waited for its result: the result's lag in frames
            int later=0;
            boolean after=false;
            for(Long t:images.keySet()) { if(after) later++; else if(t==timestamp) after=true; }
            maxLate=Math.max(maxLate,later);
            matched++;
            deliver.accept(images.remove(timestamp),result);
            return;
        }
        results.put(timestamp,result);
        while(results.size()>16) results.remove(results.keySet().iterator().next());
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
