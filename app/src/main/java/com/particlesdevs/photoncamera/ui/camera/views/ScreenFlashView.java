package com.particlesdevs.photoncamera.ui.camera.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * P507 (owner, 2026-11-05: the front torch lights the face with the screen, «Видоискатель должно быть видно»): a white frame over the
 * live preview's box with a rounded window in its middle where the preview stays visible. The box follows {@code target} (the
 * viewfinder's texture) in this view's coordinates; the bottom bar keeps its dark ground and its controls.
 */
public class ScreenFlashView extends View {
    /** The share of the preview's width / height the window keeps (the rest of the box is white). */
    static final float WINDOW = 0.64f;
    private final View target;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF box = new RectF(), hole = new RectF();
    private final int[] at = new int[2], own = new int[2];

    public ScreenFlashView(Context context, View target) {
        super(context);
        this.target = target;
        paint.setColor(Color.WHITE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        target.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> invalidate());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        target.getLocationInWindow(at);
        getLocationInWindow(own);
        final float x = at[0] - own[0], y = at[1] - own[1], w = target.getWidth(), h = target.getHeight();
        if (w <= 0 || h <= 0) return;
        box.set(x, y, x + w, y + h);
        final float mx = w * (1f - WINDOW) / 2f, my = h * (1f - WINDOW) / 2f, r = 24f * getResources().getDisplayMetrics().density;
        hole.set(box.left + mx, box.top + my, box.right - mx, box.bottom - my);
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRect(box, Path.Direction.CW);
        path.addRoundRect(hole, r, r, Path.Direction.CW);
        canvas.drawPath(path, paint);
    }
}
