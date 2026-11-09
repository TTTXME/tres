package com.particlesdevs.photoncamera.ui.camera.views.settingsbar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * P502 (owner, 2026-11-05: «исправь иконку смены разрешения Sabre, на ней должно быть написано 1X/1.5X/2X»): a tile icon that is
 * a short bold text in the icon's place (24dp box, the tint as its colour), the text shrunk to the box's width.
 */
public final class TextIconDrawable extends Drawable {
    private final String text;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size;
    private ColorStateList tint = ColorStateList.valueOf(0xFFFFFFFF);

    public TextIconDrawable(Context c, String text) {
        this.text = text;
        size = Math.round(24 * c.getResources().getDisplayMetrics().density);
        paint.setTypeface(com.particlesdevs.photoncamera.ui.style.Fonts.of(c, com.particlesdevs.photoncamera.ui.style.Fonts.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        paint.setColor(tint.getColorForState(getState(), tint.getDefaultColor()));
        float h = b.height() * .62f;
        paint.setTextSize(h);
        float w = paint.measureText(text);
        if (w > b.width() * 1.08f) paint.setTextSize(h * b.width() * 1.08f / w);
        Paint.FontMetrics m = paint.getFontMetrics();
        canvas.drawText(text, b.exactCenterX(), b.exactCenterY() - (m.ascent + m.descent) / 2f, paint);
    }

    @Override
    public void setTintList(@Nullable ColorStateList t) {
        if (t != null) tint = t;
        invalidateSelf();
    }

    @Override
    public boolean isStateful() {
        return tint.isStateful();
    }

    @Override
    protected boolean onStateChange(@NonNull int[] state) {
        invalidateSelf();
        return tint.isStateful();
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }

    @Override public void setColorFilter(@Nullable ColorFilter f) { paint.setColorFilter(f); }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    @Override public int getIntrinsicWidth() { return size; }

    @Override public int getIntrinsicHeight() { return size; }
}
