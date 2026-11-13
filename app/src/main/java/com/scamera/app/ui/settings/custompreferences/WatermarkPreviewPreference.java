package com.scamera.app.ui.settings.custompreferences;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.scamera.app.R;
import com.scamera.app.circularbarlib.ui.UiTokens;
import com.scamera.app.processing.watermark.WatermarkFonts;
import com.scamera.app.processing.watermark.WatermarkPainter;
import com.scamera.app.processing.watermark.WatermarkSpec;
import com.scamera.app.processing.watermark.WatermarkText;
import com.scamera.app.ui.controls.IosSegmented;
import com.scamera.app.ui.settings.SettingsMotion;
import com.scamera.app.ui.settings.SettingsStyle;
import com.scamera.app.util.Lang;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * P628: the watermark page's live preview - a sample photo (the settings' drawn landscape, LutSamples) with the watermark of the
 * stored settings drawn by the shot's own code (processing.watermark), in a rounded card like the other previews; a segmented
 * switch under it turns the sample landscape / portrait. Every change of a watermark setting redraws it with a cross-fade (the
 * settings' ease-out, 260 ms). The values are a sample shot's (the phone it runs on, 24mm f/1.8 1/120s ISO 100, now).
 */
public class WatermarkPreviewPreference extends Preference implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final Set<String> KEYS = new HashSet<>(Arrays.asList(WatermarkSpec.KEYS));
    /** The sample's orientation, kept while the app runs. */
    static boolean portrait;
    @Nullable private Preview preview;

    public WatermarkPreviewPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
        setLayoutResource(R.layout.preference_lut_page);
        setSelectable(false);
    }

    public WatermarkPreviewPreference(Context context) {
        this(context, null);
    }

    @Override
    public void onAttached() {
        super.onAttached();
        SharedPreferences p = getSharedPreferences();
        if (p != null) p.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onDetached() {
        SharedPreferences p = getSharedPreferences();
        if (p != null) p.unregisterOnSharedPreferenceChangeListener(this);
        super.onDetached();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, @Nullable String key) {
        if (key != null && KEYS.contains(key) && preview != null) preview.refresh(true);
    }

    /** The settings as stored ({@link WatermarkSpec#from}). */
    WatermarkSpec spec() {
        return WatermarkSpec.from(getSharedPreferences());
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false);
        Context c = getContext();
        View found = holder.findViewById(R.id.lut_page_host);
        if (!(found instanceof FrameLayout)) found = holder.itemView;
        if (!(found instanceof FrameLayout)) return;
        FrameLayout host = (FrameLayout) found;
        host.removeAllViews();
        LinearLayout page = new LinearLayout(c);
        page.setOrientation(LinearLayout.VERTICAL);
        preview = new Preview(c, this);
        page.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiTokens.dp(c, 236)));
        IosSegmented turn = new IosSegmented(c);
        turn.setTag("watermark_preview_orientation");
        turn.setItems(new CharSequence[]{Lang.t(c, "Горизонтально", "Landscape"), Lang.t(c, "Вертикально", "Portrait")});
        turn.setColours(SettingsStyle.accent(c), 0x3D767680, 0xFF636366);
        turn.setSelected(portrait ? 1 : 0, false);
        turn.setListener((s, i) -> {
            portrait = i == 1;
            if (preview != null) preview.refresh(true);
        });
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = UiTokens.dp(c, 10);
        page.addView(turn, tp);
        host.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * The sample photo {@code w} x {@code h} with the watermark of {@code spec} drawn in by the shot's code; with the switch off the
     * sample alone. {@code now}: the sample's clock.
     */
    @VisibleForTesting
    public static Bitmap render(Context c, WatermarkSpec spec, boolean on, int w, int h, long now) {
        return render(c, spec, on, w, h, now, sampleDevice());
    }

    /** {@link #render} for the phone named {@code device}. */
    @VisibleForTesting
    public static Bitmap render(Context c, WatermarkSpec spec, boolean on, int w, int h, long now, String device) {
        Bitmap photo = LutSamples.scene(w, h, 0, w > h ? 1.4f : 1.9f);
        if (!on) return photo;
        WatermarkText text = WatermarkText.of(spec, WatermarkText.Info.sample(device, now));
        WatermarkPainter.drawOn(photo, spec, text, new WatermarkFonts(c));
        return photo;
    }

    /** The phone the page runs on, as the photo's EXIF names it. */
    static String sampleDevice() {
        try {
            return com.scamera.app.gallery.ui.PhotoInfo.device(com.scamera.app.util.DeviceName.make(),
                    com.scamera.app.util.DeviceName.model());
        } catch (RuntimeException e) {
            return android.os.Build.MODEL;
        }
    }

    /** The card: the sample photo (4:3 or 3:4, centred) with its watermark, cross-faded on every change. */
    static final class Preview extends View {
        private final WatermarkPreviewPreference owner;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path clip = new Path();
        private final RectF rect = new RectF(), photoRect = new RectF();
        @Nullable private Bitmap shown, previous;
        @Nullable private RectF previousRect;
        private float fade = 1f;
        @Nullable private ValueAnimator anim;

        Preview(Context c, WatermarkPreviewPreference owner) {
            super(c);
            this.owner = owner;
            setTag("watermark_preview");
            setContentDescription(Lang.t(c, "Предпросмотр водяного знака", "Watermark preview"));
        }

        /** Renders the stored settings again; {@code animate}: cross-fades from what was shown. */
        void refresh(boolean animate) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) { invalidate(); return; }
            Bitmap old = shown;
            RectF oldRect = new RectF(photoRect);
            fit(w, h);
            shown = render(getContext(), owner.spec(), WatermarkPreviewPreference.on(owner), Math.round(photoRect.width()),
                    Math.round(photoRect.height()), System.currentTimeMillis());
            if (anim != null) anim.cancel();
            if (previous != null && previous != old) previous.recycle();
            previous = null;
            if (animate && old != null && SettingsMotion.enabled(this)) {
                previous = old;
                previousRect = oldRect;
                fade = 0f;
                anim = ValueAnimator.ofFloat(0f, 1f);
                anim.setDuration(260);
                anim.setInterpolator(SettingsMotion.EASE_OUT);
                anim.addUpdateListener(a -> { fade = (float) a.getAnimatedValue(); invalidate(); });
                anim.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a) {
                        fade = 1f;
                        if (previous != null) { previous.recycle(); previous = null; }
                        invalidate();
                    }
                });
                anim.start();
            } else {
                fade = 1f;
                if (old != null) old.recycle();
            }
            invalidate();
        }

        /** The photo's box in the card: landscape 4:3 filling the width, portrait 3:4 at the card's height. */
        private void fit(int w, int h) {
            float pad = UiTokens.dp(getContext(), 0);
            if (!portrait) {
                float ph = Math.min(h, w * .75f), pw = ph / .75f;
                photoRect.set((w - pw) / 2f, (h - ph) / 2f, (w + pw) / 2f, (h + ph) / 2f);
            } else {
                float ph = h - 2 * pad, pw = ph * .75f;
                photoRect.set((w - pw) / 2f, pad, (w + pw) / 2f, pad + ph);
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            refresh(false);
        }

        @Override
        protected void onDetachedFromWindow() {
            if (anim != null) anim.cancel();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            if (shown == null) refresh(false);
            float r = UiTokens.dp(getContext(), 18);
            float lw = Math.max(1, UiTokens.dp(getContext(), 1));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(SettingsStyle.ROW);
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, r, r, paint);
            if (previous != null && previousRect != null && fade < 1f) drawPhoto(canvas, previous, previousRect, 1f - fade, r);
            if (shown != null) drawPhoto(canvas, shown, photoRect, fade, r);
            paint.setAlpha(255);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(lw);
            paint.setColor(UiTokens.LINE);
            rect.inset(lw / 2, lw / 2);
            canvas.drawRoundRect(rect, r, r, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawPhoto(Canvas canvas, Bitmap b, RectF at, float alpha, float r) {
            canvas.save();
            clip.reset();
            clip.addRoundRect(at, r, r, Path.Direction.CW);
            canvas.clipPath(clip);
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha(Math.round(255 * Math.max(0, Math.min(1, alpha))));
            canvas.drawBitmap(b, null, at, paint);
            canvas.restore();
        }

        @Nullable Bitmap shown() { return shown; }
    }

    /** Whether the watermark switch is on (the preview shows the sample without a watermark when it is off). */
    static boolean on(Preference p) {
        SharedPreferences s = p.getSharedPreferences();
        return s == null || com.scamera.app.settings.PreferenceNumber.bool(
                com.scamera.app.settings.PreferenceValue.get(s, WatermarkSpec.KEY_ON),
                p.getContext().getResources().getBoolean(R.bool.pref_show_watermark_default));
    }
}
