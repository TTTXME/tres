package com.scamera.app.processing.opengl.postpipeline;

import android.graphics.Rect;

import com.scamera.app.processing.JpegliEncoder;
import com.scamera.app.processing.opengl.GLCoreBlockProcessing;
import com.scamera.app.processing.watermark.PhotoWatermark;
import com.scamera.app.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * P483 (speed): encodes the final readback's mapped RGBA rows into a JPEG straight away, for a caller that only saves the
 * photo ({@link PostPipeline#streamJpeg}). Declining ({@code false}) hands the same pixels to the bitmap path, unchanged.
 */
final class StreamedJpegSink implements GLCoreBlockProcessing.RgbaSink {
    private static final String TAG = "PostPipeline";
    private final GLCoreBlockProcessing processing;
    private final int quality;
    private final PostPipeline.JpegPlan plan;
    private final boolean draws;
    /** The encoded file once {@link #accept} took the pixels; null otherwise (P681: in chunks, no single array of it). */
    com.scamera.app.processing.ChunkedBytes jpeg;

    StreamedJpegSink(GLCoreBlockProcessing processing, int quality, PostPipeline.JpegPlan plan) {
        this.processing = processing;
        this.quality = quality;
        this.plan = plan;
        this.draws = plan != null && plan.draws();
    }

    @Override
    public boolean accept(ByteBuffer rgba, int width, int height) {
        // P642: a plan that draws (watermark, ES / L stamp) needs a mapping it may write
        if (draws && !processing.sinkWritable) {
            Log.i(TAG, "streamed JPEG: the readback could not be mapped for the watermark, the bitmap path");
            return false;
        }
        final com.scamera.app.processing.ChunkedBytes file = new com.scamera.app.processing.ChunkedBytes();
        final int stride = 4 * width;
        Runnable undo = null;
        try {
            final Rect keep = plan != null ? plan.crop(width, height) : null;
            ByteBuffer pixels = rgba;
            int w = width, h = height;
            if (keep != null && !covers(keep, width, height)) {
                if (keep.isEmpty() || keep.left < 0 || keep.top < 0 || keep.right > width || keep.bottom > height)
                    throw new IOException("crop " + keep + " outside " + width + "x" + height);
                pixels = window(rgba, (keep.top * width + keep.left) * 4);
                w = keep.width();
                h = keep.height();
            }
            if (draws) undo = plan.draw(pixels, w, h, stride);
            final OutputStream sink = plan != null ? plan.wrap(file, w, h, pixels, stride) : file;
            JpegliEncoder.compress(pixels, w, h, stride, quality, sink);
            if (sink != file) sink.close();
            jpeg = file;
            return true;
        } catch (IOException | RuntimeException e) {
            final String why = e instanceof PhotoWatermark.NotStreamed ? "not taken (" + e.getMessage() + ")" : "failed (" + e + ")";
            // P642: the bitmap path reads these same pixels next, so they go back as they were before the plan drew on them;
            // pixels left drawn on would be drawn twice: then the shot fails instead
            final boolean restored = undoDrawing(undo);
            Log.w(TAG, "streamed JPEG " + why + ", the bitmap path");
            if (!restored) throw new IllegalStateException("streamed JPEG: pixels left drawn on", e);
            return false;
        }
    }

    /**
     * P713: the frame streamed - its rows go to the encoder (JpegliEncoder.Stream) as the readback copies them, the rows the plan
     * draws into (from {@link PostPipeline.JpegPlan#firstDrawnRow}) only after it drew them at the end; the end then does what
     * {@link #accept} does (the drawing, the stream wrapper, the encode) and gives the same file. Null when the crop does not fit.
     */
    @Override
    public GLCoreBlockProcessing.RowStream stream(ByteBuffer frame, int width, int height) {
        final Rect keep = plan != null ? plan.crop(width, height) : null;
        final int stride = 4 * width;
        final ByteBuffer pixels;
        final int w, h, top;
        if (keep != null && !covers(keep, width, height)) {
            if (keep.isEmpty() || keep.left < 0 || keep.top < 0 || keep.right > width || keep.bottom > height) return null;
            pixels = window(frame, (keep.top * width + keep.left) * 4);
            w = keep.width();
            h = keep.height();
            top = keep.top;
        } else {
            pixels = frame;
            w = width;
            h = height;
            top = 0;
        }
        final int held = draws ? Math.max(0, Math.min(h, plan.firstDrawnRow(w, h))) : h;
        final JpegliEncoder.Stream encoder = JpegliEncoder.Stream.begin(pixels, w, h, stride, quality);
        return new GLCoreBlockProcessing.RowStream() {
            @Override
            public void ready(int rows) {
                if (encoder != null) encoder.rows(Math.min(held, Math.max(0, Math.min(h, rows - top))));
            }

            @Override
            public boolean finish() {
                final com.scamera.app.processing.ChunkedBytes file = new com.scamera.app.processing.ChunkedBytes();
                Runnable undo = null;
                try {
                    if (draws) undo = plan.draw(pixels, w, h, stride);
                    final OutputStream sink = plan != null ? plan.wrap(file, w, h, pixels, stride) : file;
                    if (encoder != null) encoder.finish(sink);
                    else JpegliEncoder.compress(pixels, w, h, stride, quality, sink);
                    if (sink != file) sink.close();
                    jpeg = file;
                    return true;
                } catch (IOException | RuntimeException e) {
                    if (encoder != null) encoder.close();
                    final String why = e instanceof PhotoWatermark.NotStreamed ? "not taken (" + e.getMessage() + ")" : "failed (" + e + ")";
                    final boolean restored = undoDrawing(undo);
                    Log.w(TAG, "streamed JPEG (rows) " + why + ", the bitmap path");
                    if (!restored) throw new IllegalStateException("streamed JPEG: pixels left drawn on", e);
                    return false;
                }
            }

            @Override
            public void abort() {
                if (encoder != null) encoder.close();
            }
        };
    }

    private static boolean covers(Rect r, int width, int height) {
        return r.left == 0 && r.top == 0 && r.width() == width && r.height() == height;
    }

    /** The rows from byte {@code offset} on, as a buffer of their own (its position 0 there; the source untouched). */
    private static ByteBuffer window(ByteBuffer rgba, int offset) {
        final ByteBuffer d = rgba.duplicate();
        d.clear();
        d.position(offset);
        return d.slice();
    }

    private static boolean undoDrawing(Runnable undo) {
        if (undo == null) return true;
        try {
            undo.run();
            return true;
        } catch (RuntimeException failed) {
            Log.e(TAG, "streamed JPEG: the drawing could not be taken back (" + failed + ")");
            return false;
        }
    }
}
