package com.particlesdevs.photoncamera.ui.style;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatAutoCompleteTextView;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.appcompat.widget.AppCompatCheckedTextView;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.appcompat.widget.AppCompatRadioButton;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.appcompat.widget.AppCompatToggleButton;

import com.google.android.material.theme.MaterialComponentsViewInflater;

/**
 * P582: the base theme's {@code viewInflaterClass}. A TextView whose layout or style still names a system family
 * ({@code android:fontFamily="sans-serif-medium"} and the like, not a font resource) gets Roboto Flex at the matching weight
 * ({@link Fonts#forFamily}), so no inflated text falls back to the phone's own system font. Everything else is Material's
 * inflater as before; text without such a family takes the theme's {@code @font/roboto_flex}.
 */
public class ConceptViewInflater extends MaterialComponentsViewInflater {
    private static final int[] FONT_FAMILY = {android.R.attr.fontFamily};

    /** Replaces a system sans-serif family read from {@code attrs} (layout, style or theme) with Roboto Flex. */
    public static <T extends TextView> T conceptFont(T view, Context context, AttributeSet attrs) {
        if (view == null || attrs == null) return view;
        String family = null;
        TypedArray a = context.obtainStyledAttributes(attrs, FONT_FAMILY);
        try {
            TypedValue v = a.peekValue(0);
            // a font resource (@font/…) has a resource id; only a bare family name is the system's
            if (v != null && v.type == TypedValue.TYPE_STRING && v.resourceId == 0 && v.string != null) {
                family = v.string.toString();
            }
        } finally {
            a.recycle();
        }
        if (family == null || !family.startsWith("sans-serif")) return view;
        Typeface current = view.getTypeface();
        view.setTypeface(Fonts.forFamily(context, family, current == null ? Typeface.NORMAL : current.getStyle()));
        return view;
    }

    @NonNull @Override
    protected AppCompatTextView createTextView(Context context, AttributeSet attrs) {
        return conceptFont(super.createTextView(context, attrs), context, attrs);
    }

    @NonNull @Override
    protected AppCompatButton createButton(@NonNull Context context, @NonNull AttributeSet attrs) {
        return conceptFont(super.createButton(context, attrs), context, attrs);
    }

    @NonNull @Override
    protected AppCompatEditText createEditText(Context context, AttributeSet attrs) {
        return conceptFont(super.createEditText(context, attrs), context, attrs);
    }

    @NonNull @Override
    protected AppCompatCheckBox createCheckBox(Context context, AttributeSet attrs) {
        return conceptFont(super.createCheckBox(context, attrs), context, attrs);
    }

    @NonNull @Override
    protected AppCompatRadioButton createRadioButton(Context context, AttributeSet attrs) {
        return conceptFont(super.createRadioButton(context, attrs), context, attrs);
    }

    @NonNull @Override
    protected AppCompatCheckedTextView createCheckedTextView(Context context, AttributeSet attrs) {
        return conceptFont(super.createCheckedTextView(context, attrs), context, attrs);
    }

    @NonNull @Override
    protected AppCompatAutoCompleteTextView createAutoCompleteTextView(Context context, AttributeSet attrs) {
        return conceptFont(super.createAutoCompleteTextView(context, attrs), context, attrs);
    }

    @NonNull @Override
    protected AppCompatToggleButton createToggleButton(Context context, AttributeSet attrs) {
        return conceptFont(super.createToggleButton(context, attrs), context, attrs);
    }
}
