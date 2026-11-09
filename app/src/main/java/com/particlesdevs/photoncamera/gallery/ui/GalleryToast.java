package com.particlesdevs.photoncamera.gallery.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.particlesdevs.photoncamera.ui.Motion;

/**
 * P579 (owner, 2026-11-09: «Не забудь что у нас есть галерея и в ней надо стиль также обновить»): the gallery's short messages as
 * the concept's toast - a dark capsule (rgba(20, 20, 22, .92), a 0.5dp light edge) at the top of the screen, under the status bar,
 * sliding down in and back up out (Animators: the system's animation scale applies). One at a time: a new one replaces the shown
 * one. Without an activity window (a service, a test without a window) it falls back to the system toast.
 */
public final class GalleryToast {
    private GalleryToast() {}

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** The shown capsule (its window may be gone: the next one checks). */
    @Nullable private static TextView shown;
    @Nullable private static Runnable hide;

    /** Shows {@code text} for about 2 s ({@code longer}: 3.5 s). */
    public static void show(@Nullable Context c, CharSequence text, boolean longer) {
        if (c == null) return;
        Activity a = activity(c);
        View content = a == null || a.isFinishing() ? null : a.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout) || content.getWindowToken() == null) {
            Toast.makeText(c.getApplicationContext(), text, longer ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            return;
        }
        FrameLayout root = (FrameLayout) content;
        dismissNow();
        TextView t = GalleryUi.text(a, text, 14, GalleryUi.TEXT);
        t.setTypeface(GalleryUi.semibold());
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(4);
        t.setLineSpacing(0, 1.15f);
        t.setMinHeight(GalleryUi.dp(a, 40));
        t.setPadding(GalleryUi.dp(a, 18), GalleryUi.dp(a, 10), GalleryUi.dp(a, 18), GalleryUi.dp(a, 10));
        android.graphics.drawable.GradientDrawable bg = GalleryUi.bar(a, 20);
        bg.setColor(0xEB141416);
        t.setBackground(bg);
        t.setElevation(GalleryUi.dp(a, 12));
        t.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        t.setTag("gallery_toast");
        int top = 0;
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(root);
        if (insets != null) top = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()).top;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = top + GalleryUi.dp(a, 12);
        int side = GalleryUi.dp(a, 24);
        lp.leftMargin = lp.rightMargin = side;
        t.setMaxWidth(Math.min(GalleryUi.dp(a, 360), Math.max(GalleryUi.dp(a, 160), root.getWidth() - 2 * side)));
        root.addView(t, lp);
        shown = t;
        if (Motion.enabled(t)) {
            t.setAlpha(0f);
            t.setTranslationY(-GalleryUi.dp(a, 16));
            t.setScaleX(.94f);
            t.setScaleY(.94f);
            t.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(Motion.ENTER).start();
        }
        hide = () -> out(t);
        MAIN.postDelayed(hide, longer ? 3500 : 1900);
    }

    public static void show(@Nullable Context c, CharSequence text) {
        show(c, text, false);
    }

    private static void out(TextView t) {
        if (shown == t) {
            shown = null;
            hide = null;
        }
        if (!(t.getParent() instanceof ViewGroup)) return;
        if (!Motion.enabled(t)) {
            ((ViewGroup) t.getParent()).removeView(t);
            return;
        }
        t.animate().alpha(0f).translationY(-GalleryUi.dp(t.getContext(), 12)).setDuration(220).setInterpolator(Motion.EASE_OUT)
                .withEndAction(() -> {
                    if (t.getParent() instanceof ViewGroup) ((ViewGroup) t.getParent()).removeView(t);
                }).start();
    }

    /** The shown one goes at once (a new one takes its place). */
    private static void dismissNow() {
        if (hide != null) MAIN.removeCallbacks(hide);
        TextView t = shown;
        shown = null;
        hide = null;
        if (t != null && t.getParent() instanceof ViewGroup) {
            t.animate().cancel();
            ((ViewGroup) t.getParent()).removeView(t);
        }
    }

    @Nullable
    static Activity activity(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }
}
