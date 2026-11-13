package com.scamera.app.ui.camera.views;

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

import com.scamera.app.R;


/**
 * Created by Vibhor on 11/01/2021
 */
public class FocusCircleView extends View {
    private static final int[] FAILED_STATE = {R.attr.focus_failed};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path brackets = new android.graphics.Path();
    /** The ring's colours by state (android:color of the style; white without one): the failed state's is used. */
    private final ColorStateList colors;
    private boolean locked, failed;

    public FocusCircleView(Context context, @Nullable AttributeSet set) {
        super(context, set);
        final TypedArray style = context.getTheme().obtainStyledAttributes(set, R.styleable.FocusCircleView, 0, 0);
        final ColorStateList given = style.getColorStateList(R.styleable.FocusCircleView_android_color);
        final float line = style.getDimension(R.styleable.FocusCircleView_android_thickness, 2.5f);
        style.recycle();
        colors = given != null ? given : ColorStateList.valueOf(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(line);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    /**
     * P583 (the concept's tap focus): four corner brackets of the 76dp box - 14dp arms, 6dp rounded corners, a 1.5dp line - in
     * the camera accent (the concept's yellow); red while the focus failed. The arms and corners keep the concept's proportions
     * at the view's size (the ring's split and pop scale it).
     */
    @Override
    protected void onDraw(Canvas canvas) {
        paint.setColor(failed && !locked ? colors.getColorForState(FAILED_STATE, colors.getDefaultColor())
                : com.scamera.app.circularbarlib.ui.AccentPalette.camera(getContext()));
        final float w = getWidth(), h = getHeight(), inset = paint.getStrokeWidth() / 2f, k = Math.min(w, h) / 76f;
        final float arm = 14f * k, round = 6f * k, left = inset, top = inset, right = w - inset, bottom = h - inset;
        brackets.rewind();
        corner(left, top, 1, 1, arm, round, true);
        corner(right, top, -1, 1, arm, round, false);
        corner(left, bottom, 1, -1, arm, round, true);
        corner(right, bottom, -1, -1, arm, round, false);
        canvas.drawPath(brackets, paint);
    }

    /**
     * One bracket round corner (x, y), its arms going {@code dx} / {@code dy} (+1 / -1) from it: drawn from the vertical arm's end
     * ({@code fromVertical}, the left corners) or from the horizontal arm's end (the right ones).
     */
    private void corner(float x, float y, int dx, int dy, float arm, float round, boolean fromVertical) {
        if (fromVertical) {
            brackets.moveTo(x, y + dy * arm);
            brackets.lineTo(x, y + dy * round);
            brackets.quadTo(x, y, x + dx * round, y);
            brackets.lineTo(x + dx * arm, y);
        } else {
            brackets.moveTo(x + dx * arm, y);
            brackets.lineTo(x + dx * round, y);
            brackets.quadTo(x, y, x, y + dy * round);
            brackets.lineTo(x, y + dy * arm);
        }
    }

    /** The preview's AF state: locked (or passively focused) draws the accent, a failed lock (or passive miss) red. */
    public void setAfState(int afState) {
        locked = afState == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED || afState == CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED;
        failed = afState == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                || afState == CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED;
        invalidate();
    }
}
