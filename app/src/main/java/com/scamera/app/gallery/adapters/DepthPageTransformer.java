package com.scamera.app.gallery.adapters;

import android.view.View;

import androidx.viewpager.widget.ViewPager;

/**
 * The viewer's page change: the page leaving to the left slides as usual; the page coming from the right stays in place,
 * fades in and grows from 3/4 size, so the next photo appears from behind the current one. Pages further away are hidden.
 */
public class DepthPageTransformer implements ViewPager.PageTransformer {
    private static final float SMALLEST = 0.75f;

    @Override
    public void transformPage(View page, float position) {
        if (position < -1f || position > 1f) {
            page.setAlpha(0f); // off screen
            return;
        }
        boolean behind = position > 0f; // the next page, under the current one
        float scale = behind ? SMALLEST + (1f - SMALLEST) * (1f - Math.abs(position)) : 1f;
        page.setAlpha(behind ? 1f - position : 1f);
        page.setTranslationX(behind ? -position * page.getWidth() : 0f);
        page.setScaleX(scale);
        page.setScaleY(scale);
    }
}
