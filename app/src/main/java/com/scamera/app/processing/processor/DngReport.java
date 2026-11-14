package com.scamera.app.processing.processor;

import com.scamera.app.processing.ProcessingEvents;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * P681: when the camera screen and the media scanner hear of a shot's DNG. A DNG that is the shot (RAW only, the merged mosaic
 * DNG alone) is reported once written, as before ({@link ScamProcessor#reportDng}: the gallery card, the handoff, the scan).
 * A DNG beside a photo is not: the card took its thumbnail from the whole DNG (Glide copied it to its cache and rendered the RAW
 * beside the GPU post) only to be replaced by the photo's a moment later, and the scanner read it twice (at the write, again
 * after its preview was appended). It is scanned once, after the photo, its preview and its develop record; a failed DNG is a
 * notice at once; a shot that ends without its photo reports its saved DNG as the shot's file.
 */
final class DngReport {
    private final ProcessingEvents events;
    private final Consumer<Path> scanner;
    private final Path dng;
    private final int rawSaving;
    private final boolean mosaicDngOnly;
    /** A photo of the shot follows the DNG (not RAW only, not the mosaic DNG alone). */
    final boolean photoFollows;
    private boolean saved, reported, photoDone, scanned;

    DngReport(ProcessingEvents events, Consumer<Path> scanner, Path dng, int rawSaving, boolean mosaicDngOnly) {
        this.events = events;
        this.scanner = scanner;
        this.dng = dng;
        this.rawSaving = rawSaving;
        this.mosaicDngOnly = mosaicDngOnly;
        this.photoFollows = !(rawSaving == 2 || mosaicDngOnly);
    }

    /** The DNG's write ended ({@code ok}: the file is complete). */
    synchronized void written(boolean ok) {
        saved = ok;
        if (!photoFollows || !ok) {
            reported = true;
            ScamProcessor.reportDng(events, ok, rawSaving, mosaicDngOnly, dng);
        }
    }

    /** Whether the DNG was written whole. */
    synchronized boolean saved() {
        return saved;
    }

    /** The shot's photo went its way (saved or not): the DNG is no longer the shot's only file; {@link #scan} follows. */
    synchronized void photoSaved() {
        photoDone = true;
    }

    /** The DNG's one scan, after the photo and whatever is appended to the DNG (any thread). */
    void scan() {
        synchronized (this) {
            if (!photoFollows || !saved || reported || scanned) return;
            scanned = true;
        }
        scanner.accept(dng);
    }

    /** The shot ended without its photo: a saved DNG held back is reported as the shot's file. */
    void shotFailed() {
        synchronized (this) {
            if (!photoFollows || !saved || reported || scanned || photoDone) return;
            reported = true;
        }
        events.imageSaved(true, dng);
    }

    /**
     * P681: whether the DNG is written on its own thread beside the post pipeline: a photo follows, the switch is on (scam_dev
     * dng_async), at most 100 MP (a 200 MP shot runs its post at the memory's edge, P179) and more than 8 GB of RAM (the
     * merged mosaic and its lossless JPEG stay in memory through the post instead of going before it).
     */
    static boolean offPostPath(boolean switchOn, boolean photoFollows, long dngPixels, long totalMemBytes) {
        return switchOn && photoFollows && dngPixels > 0 && dngPixels <= 100_000_000L && totalMemBytes > (8L << 30);
    }
}
