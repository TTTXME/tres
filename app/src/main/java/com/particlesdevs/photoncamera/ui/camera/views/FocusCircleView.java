package com.particlesdevs.photoncamera.ui.camera.views;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.camera2.CameraMetadata;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.R;


/**
 * Created by Vibhor on 11/01/2021
 */
public class FocusCircleView extends View {
    private static final int[] STATE_FOCUSED_LOCKED = {R.attr.focused_locked};
    private static final int[] STATE_UNFOCUSED_LOCKED = {R.attr.unfocused_locked};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ColorStateList colorStateList;
    private boolean focused_locked;
    private boolean unfocused_locked;

    public FocusCircleView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.getTheme().obtainStyledAttributes(
                attrs,
                R.styleable.FocusCircleView,
                0, 0
        );
        colorStateList = a.getColorStateList(R.styleable.FocusCircleView_android_color);
        float thickness = a.getDimension(R.styleable.FocusCircleView_android_thickness, 2.5f);
        if (colorStateList == null)
            colorStateList = ColorStateList.valueOf(Color.WHITE);
        a.recycle();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(thickness);
    }

    private final android.graphics.Path brackets = new android.graphics.Path();

    /**
     * P583 (the concept's tap focus): four corner brackets of the 76dp box - 14dp arms, 6dp rounded corners, a 1.5dp line - in
     * the camera accent (the concept's yellow); red while the focus failed. The arms and corners keep the concept's proportions
     * at the view's size (the ring's split and pop scale it).
     */
    @Override
    protected void onDraw(Canvas canvas) {
        paint.setColor(getPaintColor());
        paint.setStrokeCap(Paint.Cap.BUTT);
        float w = getWidth(), h = getHeight(), half = paint.getStrokeWidth() / 2f, k = Math.min(w, h) / 76f;
        float arm = 14f * k, r = 6f * k, l = half, t = half, rt = w - half, b = h - half;
        brackets.rewind();
        brackets.moveTo(l, t + arm);
        brackets.lineTo(l, t + r);
        brackets.quadTo(l, t, l + r, t);
        brackets.lineTo(l + arm, t);
        brackets.moveTo(rt - arm, t);
        brackets.lineTo(rt - r, t);
        brackets.quadTo(rt, t, rt, t + r);
        brackets.lineTo(rt, t + arm);
        brackets.moveTo(l, b - arm);
        brackets.lineTo(l, b - r);
        brackets.quadTo(l, b, l + r, b);
        brackets.lineTo(l + arm, b);
        brackets.moveTo(rt - arm, b);
        brackets.lineTo(rt - r, b);
        brackets.quadTo(rt, b, rt, b - r);
        brackets.lineTo(rt, b - arm);
        canvas.drawPath(brackets, paint);
    }

    private int getPaintColor() {
        if (unfocused_locked && !focused_locked)
            return colorStateList.getColorForState(STATE_UNFOCUSED_LOCKED, colorStateList.getDefaultColor());
        return com.particlesdevs.photoncamera.circularbarlib.ui.AccentPalette.camera(getContext());
    }

    public void setAfState(int afState) {
        focused_locked = afState == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED
                || afState == CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED;
        unfocused_locked = afState == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                || afState == CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED;
        invalidate();
    }
}
