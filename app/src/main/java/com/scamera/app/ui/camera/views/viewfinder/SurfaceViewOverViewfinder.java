package com.scamera.app.ui.camera.views.viewfinder;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.util.Log;

/**
 * The transparent layer over the viewfinder. Its view draws the composition grid; its surface carries what «Данные AF» shows:
 * 1 the information column (top left), 2 the column and a live RGB histogram (top right), 3 the debug table with the AF / AE
 * regions. Column and histogram turn with the phone (the shortest way round, 250 ms).
 */
public class SurfaceViewOverViewfinder extends SurfaceView {
    private static final String TAG = "ViewfinderOverlay";
    /** The golden section of the grid's mode 3, as the old successive-ratio search found it (to within 1e-5). */
    private static final float GOLDEN = (float) goldenSection();
    private static final int[][] HIST_ARGB = {{0xDD, 0xFF, 0x1A, 0x1A}, {0xDD, 0x1A, 0xD4, 0x2A}, {0xDD, 0x2A, 0x55, 0xFF}};

    private final SurfaceHolder holder;
    /** The screen's long : short side: a tall screen starts the debug table higher. */
    private final float screenAspect;
    private final float dp;
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint debugPaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    private final Paint regionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint columnPaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
    private final Paint oisStrike = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint histFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint histFrame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint histChannel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path curve = new Path();
    private final float oisWidth;

    /** The surface holds a drawing (the camera screen clears it only then). */
    public boolean drawn = false;
    @Nullable private RectF afRegion = new RectF();
    @Nullable private RectF aeRegion = new RectF();
    @Nullable private String debugText;
    /** P293: the column's top, below the LUT pill in the same corner (px from this view's top; <= 0: 12dp). */
    private float columnTopPx;

    // the information column's rows (null exposure: nothing to show)
    @Nullable private String exposure, iso, lens, focus, whiteBalance;
    private boolean tripod, oisPresent, oisWorking;

    private float angle;
    private int targetOrientation;
    @Nullable private ValueAnimator turn;

    @Nullable private int[][] histogram;
    private int histogramPeak = 1;
    private int histogramBins = 64;

    public SurfaceViewOverViewfinder(Context context, AttributeSet attrs) {
        super(context, attrs);
        setZOrderOnTop(true);
        holder = getHolder();
        holder.setFormat(PixelFormat.TRANSPARENT);
        final android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenAspect = (float) Math.max(metrics.heightPixels, metrics.widthPixels) / Math.min(metrics.heightPixels, metrics.widthPixels);
        dp = metrics.density;

        gridPaint.setColor(0x47FFFFFF); // P583: the concept's grid, rgba(255, 255, 255, .28), one dp wide
        gridPaint.setStrokeWidth(Math.max(1f, dp));

        debugPaint.setColor(Color.WHITE);
        debugPaint.setTextSize(25);
        debugPaint.setTextAlign(Paint.Align.LEFT);
        regionPaint.setStyle(Paint.Style.STROKE);
        regionPaint.setStrokeWidth(3);

        columnPaint.setColor(Color.WHITE);
        columnPaint.setTextSize(13f * dp);
        columnPaint.setTypeface(com.scamera.app.ui.style.Fonts.of(getContext(), com.scamera.app.ui.style.Fonts.SEMIBOLD));
        columnPaint.setTextAlign(Paint.Align.LEFT);
        columnPaint.setShadowLayer(4f, 1f, 1f, Color.BLACK);
        // the line through «OIS» while it is off: white, 1.5dp, the column's shadow
        oisStrike.set(columnPaint);
        oisStrike.setStyle(Paint.Style.STROKE);
        oisStrike.setStrokeWidth(1.5f * dp);
        oisWidth = columnPaint.measureText("OIS");

        histFill.setColor(Color.argb(100, 0, 0, 0));
        histFill.setStyle(Paint.Style.FILL);
        histFrame.setColor(Color.argb(90, 255, 255, 255));
        histFrame.setStyle(Paint.Style.STROKE);
        histFrame.setStrokeWidth(dp);
        histChannel.setAntiAlias(true);
        histChannel.setStyle(Paint.Style.FILL);
        histChannel.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
    }

    /** Successive Fibonacci ratios until two neighbours agree within 1e-5. */
    private static double goldenSection() {
        double a = 1, b = 1;
        while (Math.abs(b / a - (a + b) / b) >= 0.00001) {
            final double next = a + b;
            a = b;
            b = next;
        }
        return (a + b) / b;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (isInEditMode()) return; // no preferences in the layout editor
        final float w = getWidth(), h = getHeight();
        switch (PreferenceKeys.getGridValue()) {
            case 1: // thirds
                lines(canvas, w, h, 1f / 3f, 2f / 3f);
                break;
            case 2: // quarters
                lines(canvas, w, h, 0.25f, 0.5f, 0.75f);
                break;
            case 3: // the golden section
                lines(canvas, w, h, 1f / (1 + GOLDEN), GOLDEN / (1 + GOLDEN));
                break;
            case 4: // the diagonal and its two thirds
                canvas.drawLine(0, 0, w, h, gridPaint);
                canvas.drawLine(w / 3.f, h / 3.f, w, 0, gridPaint);
                canvas.drawLine(2.f * w / 3.f, 2.f * h / 3.f, 0, h, gridPaint);
                break;
            default:
                break;
        }
    }

    /** Vertical and horizontal grid lines at these shares of the width and the height. */
    private void lines(Canvas canvas, float w, float h, float... shares) {
        for (float share : shares) canvas.drawLine(w * share, 0, w * share, h, gridPaint);
        for (float share : shares) canvas.drawLine(0, h * share, w, h * share, gridPaint);
    }

    public void showAfRegion(@Nullable RectF rect) {
        afRegion = rect;
    }

    public void showAeRegion(@Nullable RectF rect) {
        aeRegion = rect;
    }

    public void showDebugTable(@Nullable String text) {
        debugText = text;
    }

    /** P293 (owner, 2026-10-20: «кнопка LUT перекрыта статистикой»): the column starts below this many px. */
    public void setHudTop(float px) {
        columnTopPx = px;
    }

    /** The information column's rows and marks, drawn with the next refresh. */
    public void showColumn(String shutter, String sensitivity, String optics, String af, String wb,
                           boolean onTripod, boolean hasOis, boolean oisOn) {
        exposure = shutter;
        iso = sensitivity;
        lens = optics;
        focus = af;
        whiteBalance = wb;
        tripod = onTripod;
        oisPresent = hasOis;
        oisWorking = oisOn;
    }

    /** Turns the column and the histogram to the phone's orientation, the shortest way round in 250 ms. */
    public void setOrientation(int orientation) {
        if (targetOrientation == orientation) return;
        targetOrientation = orientation;
        float delta = (orientation - angle) % 360f;
        if (delta > 180f) delta -= 360f;
        if (delta < -180f) delta += 360f;
        if (turn != null && turn.isRunning()) turn.cancel();
        final ValueAnimator next = ValueAnimator.ofFloat(angle, angle + delta);
        next.setDuration(250);
        next.setInterpolator(new DecelerateInterpolator());
        next.addUpdateListener(a -> {
            angle = (float) a.getAnimatedValue();
            refresh();
        });
        turn = next;
        next.start();
    }

    /**
     * P510 (owner's screenshot 2026-11-05, «Инфо» cut at the viewfinder's top): the layout follows the angle the canvas is turned
     * by now, not the target - a box laid out for landscape but drawn upright put its first line 6dp above its margin.
     */
    private boolean drawnLandscape() {
        final float a = ((angle % 360f) + 360f) % 360f;
        return Math.abs(a - 90f) < 45f || Math.abs(a - 270f) < 45f;
    }

    /** Draws the surface again with what «Данные AF» shows now. */
    public void refresh() {
        try {
            final Canvas canvas = holder.lockHardwareCanvas();
            if (canvas == null) {
                Log.e(TAG, "no canvas to draw the overlay on");
                return;
            }
            canvas.drawColor(0, PorterDuff.Mode.CLEAR);
            final int shown = PreferenceKeys.getAfDataValue();
            if (shown == 1 || shown == 2) drawColumn(canvas);
            if (shown == 2) drawHistogram(canvas);
            if (shown == 3 && PreferenceKeys.isAfDataOn()) drawDebug(canvas);
            holder.unlockCanvasAndPost(canvas);
            drawn = true;
        } catch (Exception e) {
            Log.w(TAG, "overlay not drawn: " + e);
        }
    }

    /** The information column: exposure, ISO, lens, focus, white balance, OIS (struck through when off), tripod. */
    private void drawColumn(Canvas canvas) {
        if (exposure == null) return;
        final float side = 14f * dp, top = columnTopPx > 0f ? columnTopPx : 12f * dp, step = 16f * dp;
        final float tall = (5 + (tripod ? 1 : 0) + (oisPresent ? 1 : 0)) * step, wide = 64f * dp;
        final boolean landscape = drawnLandscape();
        // the turn's centre keeps the 14dp side and 12dp top margins in every orientation
        final float cx = side + (landscape ? tall : wide) / 2f;
        final float cy = top + (landscape ? wide : tall) / 2f;
        final float x = cx - wide / 2f;
        float y = cy - tall / 2f + 10f * dp; // the first baseline
        canvas.save();
        if (angle != 0f) canvas.rotate(angle, cx, cy);
        for (String row : new String[]{exposure, iso, lens, focus}) {
            canvas.drawText(row, x, y, columnPaint);
            y += step;
        }
        if (whiteBalance != null) {
            canvas.drawText(whiteBalance, x, y, columnPaint);
            y += step;
        }
        if (oisPresent) {
            canvas.drawText("OIS", x, y, columnPaint);
            if (!oisWorking) {
                final float strikeY = y - columnPaint.getTextSize() * 0.32f;
                canvas.drawLine(x - 2f * dp, strikeY, x + oisWidth + 2f * dp, strikeY, oisStrike);
            }
            y += step;
        }
        if (tripod) canvas.drawText("[TRIPOD]", x, y, columnPaint);
        canvas.restore();
    }

    /** The histogram's bins per channel, their peak and their count, drawn with the next refresh. */
    public void showHistogram(int[][] channels, int peak, int binCount) {
        histogram = channels;
        histogramPeak = Math.max(1, peak);
        histogramBins = binCount;
    }

    /** The live histogram: a dark box with thirds, the R, G and B curves added over each other. */
    private void drawHistogram(Canvas canvas) {
        final int[][] bins = histogram;
        if (bins == null || bins.length < 3) return;
        final float side = 14f * dp, topMargin = 12f * dp, w = 72f * dp, h = 36f * dp;
        final boolean landscape = drawnLandscape();
        // the turn's centre keeps the 14dp side and 12dp top margins in every orientation
        final float cx = getWidth() - side - (landscape ? h : w) / 2f;
        final float cy = topMargin + (landscape ? w : h) / 2f;
        final float left = cx - w / 2f, top = cy - h / 2f, right = left + w, bottom = top + h;
        canvas.save();
        if (angle != 0f) canvas.rotate(angle, cx, cy);
        canvas.drawRect(left, top, right, bottom, histFill);
        canvas.drawRect(left, top, right, bottom, histFrame);
        canvas.drawLine(left + w / 3f, top, left + w / 3f, bottom, histFrame);
        canvas.drawLine(left + 2f * w / 3f, top, left + 2f * w / 3f, bottom, histFrame);
        final int count = histogramBins;
        final float stepX = w / (float) (count + 1);
        final float scale = h / (float) histogramPeak;
        for (int channel = 0; channel < 3; channel++) {
            final int[] argb = HIST_ARGB[channel];
            histChannel.setARGB(argb[0], argb[1], argb[2], argb[3]);
            curve.reset();
            curve.moveTo(left, bottom);
            for (int bin = 0; bin < count; bin++)
                curve.lineTo(left + bin * stepX, bottom - Math.min(bins[channel][bin] * scale, h));
            curve.lineTo(left + count * stepX, bottom);
            canvas.drawPath(curve, histChannel);
        }
        canvas.restore();
    }

    /** Empties the surface and forgets what was on it (the turn stops). */
    public void clear() {
        if (turn != null) turn.cancel();
        try {
            final Canvas canvas = holder.lockHardwareCanvas();
            if (canvas == null) Log.e(TAG, "no canvas to clear the overlay on");
            else {
                canvas.drawColor(0, PorterDuff.Mode.CLEAR);
                holder.unlockCanvasAndPost(canvas);
            }
        } catch (Exception e) {
            Log.w(TAG, "overlay not cleared: " + e);
        }
        afRegion = null;
        aeRegion = null;
        debugText = null;
        exposure = iso = lens = focus = whiteBalance = null;
        histogram = null;
        drawn = false;
    }

    /** «Данные AF» 3: the AF region green, the AE region yellow, the table's lines in their colours. */
    private void drawDebug(Canvas canvas) {
        region(canvas, afRegion, Color.GREEN);
        region(canvas, aeRegion, Color.YELLOW);
        final String text = debugText;
        if (text == null) return;
        int y = screenAspect > 16 / 9f ? 50 : 180; // whole pixels: each line's height is cut to them
        final float lineHeight = debugPaint.descent() - debugPaint.ascent();
        for (String line : text.split("\n")) {
            debugPaint.setColor(line.contains("AF_RECT") ? Color.GREEN : line.contains("AE_RECT") ? Color.YELLOW : Color.WHITE);
            canvas.drawText(line, 50, y, debugPaint);
            y += lineHeight;
        }
    }

    private void region(Canvas canvas, @Nullable RectF rect, int colour) {
        if (rect == null || rect.isEmpty()) return;
        regionPaint.setColor(colour);
        canvas.drawRect(rect, regionPaint);
    }
}
