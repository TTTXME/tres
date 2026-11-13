package com.scamera.app.gallery.ui;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.scamera.app.R;
import com.scamera.app.gallery.files.MediaFile;
import com.scamera.app.gallery.glide.GalleryKeys;
import com.scamera.app.gallery.glide.GalleryThumb;

/**
 * P622: the pieces the list and timeline views share - a rounded clip, a thumbnail loaded through the gallery's thumbnail pipeline
 * (GalleryThumb, the size the view asks for), a tag capsule, a video's length over a thumbnail, the selection check and a row of
 * tags that wraps (large text never pushes a tag off the row).
 */
public final class RowViews {
    private RowViews() {}

    /** The view's picture and children clipped to {@code radiusDp} corners. */
    public static void round(View v, float radiusDp) {
        final float r = GalleryUi.dp(v.getContext(), radiusDp);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    /** Rounds {@code px} up to 32 px steps (a slightly different size keeps the cached thumbnails), 120..1440. */
    public static int step(int px) {
        return Math.max(120, Math.min(1440, (px + 31) / 32 * 32));
    }

    /**
     * The thumbnail of {@code file} at {@code px} into {@code image} (P620's decoder: the cheapest large-enough source, uncropped -
     * the view crops); a file the camera is still writing loads when it is done (P89). Nothing in layout tests.
     */
    public static void thumb(ImageView image, MediaFile file, int px, @Nullable Runnable stillBound) {
        if (!LibraryAdapter.loadPictures || file == null || file.getFileUri() == null) return;
        if (GalleryKeys.waitForFile(image, file, () -> {
            if (stillBound != null) stillBound.run();
        })) {
            Glide.with(image).clear(image);
            return;
        }
        GalleryThumb.request(Glide.with(image), GalleryThumb.of(file), px).priority(com.bumptech.glide.Priority.HIGH).into(image);
    }

    /** A tag capsule (10.5sp bold): the format in the accent's tint, a fact in grey, the merge / HDR in a warm tint. */
    public static TextView tag(Context c, String label, int kind) {
        TextView t = GalleryUi.text(c, label, 10.5f, GalleryUi.TEXT);
        t.setTypeface(com.scamera.app.ui.style.Fonts.of(c, 700));
        t.setLetterSpacing(.03f);
        t.setSingleLine(true);
        t.setPadding(GalleryUi.dp(c, 7), GalleryUi.dp(c, 3), GalleryUi.dp(c, 7), GalleryUi.dp(c, 3));
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        paintTag(t, label, kind);
        return t;
    }

    /** A tag capsule given another label and kind (a recycled row keeps its tag views). */
    public static void paintTag(TextView t, String label, int kind) {
        Context c = t.getContext();
        int accent = GalleryUi.accent(c);
        int fg, bg;
        if (kind == RowText.FORMAT) {
            fg = accent;
            bg = (accent & 0x00FFFFFF) | 0x2E000000;
        } else if (kind == RowText.HDR) {
            fg = 0xFFFFD60A;
            bg = 0x2EFFD60A;
        } else {
            fg = 0xD9FFFFFF;
            bg = 0x1FFFFFFF;
        }
        if (!label.contentEquals(t.getText())) t.setText(label);
        t.setTextColor(fg);
        Object was = t.getTag();
        if (!(was instanceof Integer) || (Integer) was != kind) {
            t.setBackground(GalleryUi.round(c, bg, 0, 7));
            t.setTag(kind);
        }
    }

    /** A video's length on the dark backing (11sp bold), bottom right of a thumbnail. */
    public static TextView duration(Context c) {
        TextView t = GalleryUi.text(c, "", 11, GalleryUi.TEXT);
        t.setTypeface(com.scamera.app.ui.style.Fonts.of(c, 700));
        t.setPadding(GalleryUi.dp(c, 6), GalleryUi.dp(c, 2), GalleryUi.dp(c, 6), GalleryUi.dp(c, 2));
        t.setBackground(GalleryUi.round(c, GalleryUi.SCRIM, 0, 7));
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return t;
    }

    /** The selection check (22dp): a light ring while selecting, the accent disc with the tick when chosen. */
    public static FrameLayout check(Context c) {
        FrameLayout f = new FrameLayout(c);
        ImageView mark = GalleryUi.icon(c, R.drawable.ui_ic_check, GalleryUi.INK, 13);
        f.addView(mark, new FrameLayout.LayoutParams(GalleryUi.dp(c, 13), GalleryUi.dp(c, 13), Gravity.CENTER));
        f.setVisibility(View.GONE);
        f.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return f;
    }

    /** Shows {@code check} for the selection state; pops it when it was just chosen. */
    public static void paintCheck(FrameLayout check, boolean selecting, boolean on, boolean animate) {
        Context c = check.getContext();
        int accent = GalleryUi.accent(c);
        boolean was = check.getVisibility() == View.VISIBLE && check.getChildAt(0).getVisibility() == View.VISIBLE;
        check.setVisibility(selecting ? View.VISIBLE : View.GONE);
        check.setBackground(GalleryUi.round(c, on ? accent : 0x66000000, on ? accent : 0xFFFFFFFF, 999));
        check.getChildAt(0).setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        if (animate && on && !was) com.scamera.app.ui.Motion.pop(check);
    }

    /** A card piece of a group: {@code first} / {@code last} round its top / bottom corners ({@code radiusDp}). */
    public static GradientDrawable piece(Context c, int fill, boolean first, boolean last, float radiusDp) {
        float r = GalleryUi.dp(c, radiusDp);
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        float t = first ? r : 0, b = last ? r : 0;
        g.setCornerRadii(new float[]{t, t, t, t, b, b, b, b});
        return g;
    }

    /** {@code accent} at {@code alpha} (0..1) over {@code base} (opaque). */
    public static int tint(int base, int accent, float alpha) {
        int r = Math.round(((base >> 16) & 0xFF) * (1 - alpha) + ((accent >> 16) & 0xFF) * alpha);
        int g = Math.round(((base >> 8) & 0xFF) * (1 - alpha) + ((accent >> 8) & 0xFF) * alpha);
        int b = Math.round((base & 0xFF) * (1 - alpha) + (accent & 0xFF) * alpha);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** A row of views that wraps to the next line when the next one does not fit (the tags under a list row's name). */
    public static final class FlowRow extends ViewGroup {
        private final int gap;

        public FlowRow(Context c) {
            super(c);
            gap = GalleryUi.dp(c, 5);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int max = MeasureSpec.getSize(widthSpec);
            boolean bounded = MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED;
            int x = 0, y = 0, line = 0, widest = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                if (ch.getVisibility() == GONE) continue;
                ch.measure(MeasureSpec.makeMeasureSpec(bounded ? max : 0, bounded ? MeasureSpec.AT_MOST : MeasureSpec.UNSPECIFIED),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int w = ch.getMeasuredWidth(), h = ch.getMeasuredHeight();
                if (x > 0 && bounded && x + w > max) {
                    y += line + gap;
                    x = 0;
                    line = 0;
                }
                x += w + gap;
                line = Math.max(line, h);
                widest = Math.max(widest, x - gap);
            }
            int height = getChildCount() == 0 ? 0 : y + line;
            setMeasuredDimension(bounded && MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY ? max : Math.min(widest, bounded ? max : widest),
                    height);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int max = r - l, x = 0, y = 0, line = 0;
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                if (ch.getVisibility() == GONE) continue;
                int w = ch.getMeasuredWidth(), h = ch.getMeasuredHeight();
                if (x > 0 && x + w > max) {
                    y += line + gap;
                    x = 0;
                    line = 0;
                }
                int left = rtl ? max - x - w : x;
                ch.layout(left, y, left + w, y + h);
                x += w + gap;
                line = Math.max(line, h);
            }
        }
    }
}
