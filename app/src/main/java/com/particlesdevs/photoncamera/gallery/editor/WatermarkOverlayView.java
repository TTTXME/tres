package com.particlesdevs.photoncamera.gallery.editor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.processing.watermark.WatermarkFonts;
import com.particlesdevs.photoncamera.processing.watermark.WatermarkPainter;
import com.particlesdevs.photoncamera.processing.watermark.WatermarkSpec;
import com.particlesdevs.photoncamera.processing.watermark.WatermarkText;

/**
 * P633: the live preview of the «Водяной знак» tab over the editor's picture - the camera's watermark (WatermarkPainter) laid out
 * for the copy's own size ({@link #bind}: the size «Сохранить копию» writes), then drawn scaled into where the picture is on the
 * screen ({@link #setPicture}: the fit or the zoomed crop), so the preview is the copy's watermark at the screen's scale. «Авто»
 * reads a small picture of the edited crop ({@link #setInkPicture}); the copy reads its own pixels, as the camera does.
 */
final class WatermarkOverlayView extends View {
    @Nullable private WatermarkSpec spec;
    @Nullable private WatermarkPainter.Plan plan;
    @Nullable private RectF picture;
    @Nullable private Bitmap ink;
    @Nullable private String key;
    private final WatermarkFonts fonts;

    WatermarkOverlayView(Context c) {
        super(c);
        fonts = new WatermarkFonts(c.getApplicationContext());
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The watermark {@code s} (null: none) with the photo's facts {@code info} on a copy of {@code outW x outH}. */
    void bind(@Nullable WatermarkSpec s, WatermarkText.Info info, int outW, int outH) {
        String k = s == null ? null : s.toJson() + "|" + outW + "x" + outH + "|" + System.identityHashCode(info);
        if (k == null ? key == null : k.equals(key)) return;
        key = k;
        spec = s;
        plan = s == null || outW <= 0 || outH <= 0 ? null : WatermarkPainter.plan(s, WatermarkText.of(s, info), outW, outH, fonts);
        if (plan != null && plan.empty()) plan = null;
        chooseInk();
        invalidate();
    }

    /** Where the displayed crop is in this view (px). */
    void setPicture(RectF r) {
        if (picture != null && picture.equals(r)) return;
        picture = new RectF(r);
        invalidate();
    }

    /** «Авто»'s picture: the edited crop, small (ARGB rows). */
    void setInkPicture(int[] argb, int w, int h) {
        if (w <= 0 || h <= 0 || argb.length < w * h) return;
        Bitmap b = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
        if (ink != null) ink.recycle();
        ink = b;
        chooseInk();
        invalidate();
    }

    private void chooseInk() {
        if (plan != null && spec != null) WatermarkPainter.chooseInkScaled(plan, spec, ink);
    }

    @Nullable
    WatermarkPainter.Plan plan() {
        return plan;
    }

    boolean showing() {
        return plan != null && getVisibility() == VISIBLE;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        WatermarkPainter.Plan p = plan;
        RectF r = picture;
        if (p == null || spec == null || r == null || r.isEmpty()) return;
        canvas.save();
        canvas.clipRect(r);
        canvas.translate(r.left, r.top);
        canvas.scale(r.width() / p.width, r.height() / p.height);
        WatermarkPainter.draw(canvas, p, spec, fonts);
        canvas.restore();
    }
}
