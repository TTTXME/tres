package com.scamera.app.gallery.views;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.scamera.app.processing.opengl.GLImage;
import com.scamera.app.processing.opengl.scripts.GLHistogram;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * The RGB histogram of a photo (the viewer's overlay, the «Сведения» sheet): three filled curves added over each other
 * inside a frame with thirds. The counts are drawn on a square-root scale; the computation runs on the GPU on the
 * histogram's own thread.
 */
public class Histogram extends View {
    private static final int BINS = 256;
    /** Red, green and blue of the curves. */
    private static final int[] CHANNEL_COLOURS = {0xFFFF0700, 0xFF00C90D, 0xFF1924B1};

    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curve = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();
    private HistogramLoadingListener loadingListener;
    private HistogramModel model;
    /** Made on the histogram's thread, used and closed there only. */
    private GLHistogram gpu;
    /**
     * P617: the thread and its GL context, made at the first computation and ended by {@link #release}: a histogram that
     * only draws a model computed elsewhere (the viewer's overlay, each sheet) no longer leaves a thread and an EGL context
     * behind for the rest of the process.
     */
    @Nullable private ExecutorService thread;
    private boolean released;

    public Histogram(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        frame.setStyle(Paint.Style.STROKE);
        frame.setARGB(100, 255, 255, 255);
        curve.setStyle(Paint.Style.FILL);
        curve.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
    }

    private synchronized ExecutorService thread() {
        if (released) throw new RejectedExecutionException("histogram released");
        if (thread == null) {
            thread = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "HistogramThread");
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
            thread.execute(() -> gpu = new GLHistogram(BINS));
        }
        return thread;
    }

    /** Whether this histogram has its thread (P617: only once it computed something). */
    synchronized boolean hasWorker() {
        return thread != null;
    }

    /** P617: the thread and the GL context go (on the thread, after a computation still queued); later computations are refused. */
    public synchronized void release() {
        if (released) return;
        released = true;
        ExecutorService t = thread;
        thread = null;
        if (t == null) return;
        t.execute(() -> {
            GLHistogram g = gpu;
            gpu = null;
            if (g != null) g.close();
        });
        t.shutdown();
    }

    /**
     * P551: computed on the histogram's thread and handed to {@code done} on the main thread. {@code bitmap} must stay valid
     * until then: pass a copy. Nothing happens once released.
     */
    public void analyzeAsync(Bitmap bitmap, Consumer<HistogramModel> done) {
        Handler main = new Handler(Looper.getMainLooper());
        ExecutorService t;
        try {
            t = thread();
        } catch (RejectedExecutionException gone) {
            return; // released (the viewer is gone): nothing to show it on
        }
        t.execute(() -> {
            HistogramModel m = toModel(count(bitmap));
            main.post(() -> done.accept(m));
        });
    }

    /** On the histogram's thread: the counts per channel and bin. */
    private int[][] count(Bitmap bitmap) {
        GLImage image = new GLImage(bitmap);
        try {
            gpu.Ac = false;
            return gpu.Compute(image);
        } finally {
            image.close();
        }
    }

    /**
     * Square roots of the counts; the height is the tallest inner bin, raised half way to the end bins when those are
     * taller (clipped shadows or highlights would flatten the rest).
     */
    private static HistogramModel toModel(int[][] counts) {
        for (int c = 0; c < CHANNEL_COLOURS.length; c++)
            for (int i = 0; i < BINS; i++) counts[c][i] = (int) Math.sqrt(counts[c][i]);
        int inner = 0;
        for (int i = 1; i < BINS - 1; i++) inner = Math.max(inner, tallest(counts, i));
        int ends = Math.max(tallest(counts, 0), tallest(counts, BINS - 1));
        return new HistogramModel(BINS, counts, inner < ends ? (inner + ends) / 2 : inner);
    }

    private static int tallest(int[][] counts, int bin) {
        return Math.max(counts[0][bin], Math.max(counts[1][bin], counts[2][bin]));
    }

    public void setHistogramLoadingListener(HistogramLoadingListener listener) {
        loadingListener = listener;
    }

    public void setHistogramModel(HistogramModel m) {
        model = m;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.drawRect(0, 0, w, h, frame);
        for (int third = 1; third <= 2; third++) canvas.drawLine(third * w / 3f, 0, third * w / 3f, h, frame);
        if (loadingListener != null) loadingListener.isLoading(true);
        if (model == null) return;
        int bins = model.getSize();
        float step = w / (bins + 1f), perCount = (float) h / model.getMaxY();
        int[][] counts = model.getColorsMap();
        for (int c = 0; c < CHANNEL_COLOURS.length; c++) {
            curve.setColor(CHANNEL_COLOURS[c]);
            outline.reset();
            outline.moveTo(0, h);
            for (int i = 0; i < bins; i++) outline.lineTo(i * step, h - counts[c][i] * perCount);
            outline.lineTo(bins * step, h);
            canvas.drawPath(outline, curve);
        }
        if (loadingListener != null) loadingListener.isLoading(false);
    }

    public interface HistogramLoadingListener {
        void isLoading(boolean loading);
    }

    /** What the histogram draws: bin count, the counts per channel (square roots) and the height they are scaled to. */
    public static class HistogramModel {
        private final int bins;
        private final int top;
        private final int[][] counts;

        public HistogramModel(int bins, int[][] counts, int top) {
            this.bins = bins;
            this.counts = counts;
            this.top = top;
        }

        public int getSize() { return bins; }

        public int getMaxY() { return top; }

        public int[][] getColorsMap() { return counts; }
    }
}
