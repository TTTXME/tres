package com.scamera.app.gallery.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.scamera.app.util.Lang;

/**
 * P637 (owner, 2026-11-12: the viewer opened from the camera right after a shot was black for ~6 s): what the viewer shows
 * over its pages while the photo it was opened for is not on a page yet - a picture the gallery already holds, fitted as the
 * photo will be, so the open's first frame has pixels:
 * <ul>
 *   <li>{@link State#PENDING}: the shot is still processed - the viewfinder's frame at its shutter under «Обработка снимка…»,
 *   until the camera saves the file (or no shot is in work any more);</li>
 *   <li>{@link State#PAGE}: the photo's page is being decoded - the camera's thumbnail, or a copy of the page shown so far when
 *   the list under it is replaced (the camera's last photo first, then the media lists), until the page has its picture.</li>
 * </ul>
 * The cover takes no touches (the pages under it swipe and zoom); it is a view over the pager and under the bars.
 */
public final class ViewerCover {
    public enum State { NONE, PENDING, PAGE }

    public final FrameLayout view;
    final ImageView picture;
    final LinearLayout pill;
    final TextView label;
    private State state = State.NONE;
    /** A bitmap this cover made (a copy of a page): recycled when the cover is dropped; a handed one is the camera's. */
    @Nullable private Bitmap own;

    public ViewerCover(Context c) {
        view = new FrameLayout(c);
        view.setBackgroundColor(0xFF000000);
        view.setVisibility(View.GONE);
        view.setClickable(false);
        view.setFocusable(false);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        view.setTag("viewer_cover");
        picture = new ImageView(c);
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.addView(picture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pill = new LinearLayout(c);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(GalleryUi.bar(c, 999));
        pill.setPadding(GalleryUi.dp(c, 12), GalleryUi.dp(c, 8), GalleryUi.dp(c, 14), GalleryUi.dp(c, 8));
        ProgressBar spin = new ProgressBar(c, null, android.R.attr.progressBarStyleSmall);
        spin.setIndeterminate(true);
        spin.setIndeterminateTintList(ColorStateList.valueOf(GalleryUi.TEXT));
        pill.addView(spin, new LinearLayout.LayoutParams(GalleryUi.dp(c, 16), GalleryUi.dp(c, 16)));
        label = GalleryUi.text(c, processingText(), 13, GalleryUi.TEXT);
        label.setTypeface(com.scamera.app.ui.style.Fonts.of(c, 600));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = GalleryUi.dp(c, 8);
        pill.addView(label, lp);
        pill.setTag("viewer_processing");
        pill.setVisibility(View.GONE);
        view.addView(pill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    static String processingText() {
        return Lang.t("Обработка снимка…", "Processing the photo…");
    }

    public State state() {
        return state;
    }

    /**
     * Covers the pages now, in this layout pass: {@code picture} fitted (null: black), {@code pending}: under the processing
     * pill, the shot is not saved yet; {@code own}: the bitmap is this cover's to recycle.
     */
    public void show(@Nullable Bitmap bitmap, boolean pending, boolean own) {
        release();
        view.animate().cancel();
        if (bitmap != null && !bitmap.isRecycled()) picture.setImageBitmap(bitmap);
        else picture.setImageDrawable(null);
        this.own = own ? bitmap : null;
        pill.setVisibility(pending ? View.VISIBLE : View.GONE);
        view.setAlpha(1f);
        view.setVisibility(View.VISIBLE);
        state = pending ? State.PENDING : State.PAGE;
    }

    /** The pending shot is saved: its page loads under the cover; the pill stays until the page has its picture. */
    public void waitForPage() {
        if (state == State.PENDING) state = State.PAGE;
    }

    /** Uncovers the pages ({@code fade}: over 120 ms; the page under it is drawn). */
    public void drop(boolean fade) {
        if (state == State.NONE) return;
        state = State.NONE;
        view.animate().cancel();
        if (!fade) {
            hide();
            return;
        }
        view.animate().alpha(0f).setDuration(120).withEndAction(this::hide).start();
    }

    private void hide() {
        if (state != State.NONE) return; // shown again meanwhile
        view.setVisibility(View.GONE);
        view.setAlpha(1f);
        pill.setVisibility(View.GONE);
        picture.setImageDrawable(null);
        release();
    }

    private void release() {
        Bitmap b = own;
        own = null;
        if (b != null && !b.isRecycled()) {
            picture.setImageDrawable(null);
            b.recycle();
        }
    }

    /** The view is going: nothing kept. */
    public void destroy() {
        state = State.NONE;
        view.animate().cancel();
        picture.setImageDrawable(null);
        release();
    }
}
