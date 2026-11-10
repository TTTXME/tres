package com.particlesdevs.photoncamera.ui.settings.custompreferences;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiTokens;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle;
import com.particlesdevs.photoncamera.ui.controls.IosMotion;
import com.particlesdevs.photoncamera.ui.controls.IosSlider;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * SIMPLE_UI §4 «Прозрачность шторки» (the concept's sheetA page): a preview - a scene with the quick sheet over its lower
 * 62 % (title and eight tile shapes) at the chosen transparency - then one card with the slider over 0 / 20 / 30 / 40 / 50 / 60 %
 * (40 % the default; P574, was a card of radio rows) and the note that text and icons stay opaque. Stored as the percentage (ShadeStyle.TRANSPARENCY_KEY).
 */
public class ShadeTransparencyPreference extends Preference {
    static final int[] VALUES = {0, 20, 30, 40, 50, 60};
    @Nullable private Preview preview;
    @Nullable private LinearLayout card;

    public ShadeTransparencyPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(true);
        setLayoutResource(R.layout.preference_lut_page);
        setSelectable(false);
    }

    public ShadeTransparencyPreference(Context context) {
        this(context, null);
    }

    @Override
    protected Object onGetDefaultValue(android.content.res.TypedArray a, int index) {
        return a.getString(index);
    }

    public int value() {
        try {
            return Math.max(0, Math.min(80, Math.round(Float.parseFloat(getPersistedString(String.valueOf(ShadeStyle.TRANSPARENCY_DEFAULT))))));
        } catch (RuntimeException e) {
            return ShadeStyle.TRANSPARENCY_DEFAULT;
        }
    }

    public void choose(int percent) {
        persistString(String.valueOf(percent));
        if (preview != null) preview.setAlpha01(1f - percent / 100f);
        fill();
        notifyDependencyChange(false);
    }

    /** «40 % · по умолчанию» and the like, for the row of «Приложение». */
    public static String label(Context c, int percent) {
        switch (percent) {
            case 0: return Lang.t(c, "0 % · непрозрачная", "0 % · opaque");
            case 40: return Lang.t(c, "40 % · по умолчанию", "40 % · default");
            default: return percent + " %";
        }
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setClickable(false);
        Context c = getContext();
        FrameLayout host = (FrameLayout) holder.itemView;
        host.removeAllViews();
        LinearLayout page = new LinearLayout(c);
        page.setOrientation(LinearLayout.VERTICAL);
        preview = new Preview(c);
        preview.setAlpha01(1f - value() / 100f);
        page.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiTokens.dp(c, 150)));
        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTokens.shape(c, SettingsStyle.ROW, 0, 24));
        card.setClipChildren(false);
        card.setTag("shade_transparency_options");
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = UiTokens.dp(c, 14);
        page.addView(card, cp);
        fill();
        // P592: the note under the card is the row's long press (help_shade_transparency)
        host.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * P574 (owner, 2026-11-09: «Ползунки … ближе к IOS»): the choice is the settings' slider over the six steps 0 / 20 / 30 / 40 /
     * 50 / 60 % (the same values as the radio rows were) - the value in its pill on the title line, lifted while the knob is
     * held, popped on release; a double tap puts 40 % back.
     */
    private void fill() {
        LinearLayout box = card;
        if (box == null) return;
        Context c = getContext();
        int accent = SettingsStyle.accent(c), now = value();
        IosSlider slider = box.findViewWithTag("shade_transparency_slider");
        if (slider != null) {
            slider.setValue(index(now));
            paint(box, now, accent);
            return;
        }
        box.removeAllViews();
        box.setPadding(UiTokens.dp(c, 16), UiTokens.dp(c, 12), UiTokens.dp(c, 16), UiTokens.dp(c, 8));
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = SettingsStyle.text(c, Lang.t(c, "Прозрачность", "Transparency"), 16, SettingsStyle.ROW_TITLE);
        t.setTypeface(com.particlesdevs.photoncamera.ui.style.Fonts.of(t.getContext(), 600));
        texts.addView(t);
        // P592: no hint under the title (the value is in the pill; what a step looks like is the preview above)
        head.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView pill = SettingsStyle.text(c, "", 15, accent);
        pill.setTag("shade_transparency_value");
        pill.setTypeface(com.particlesdevs.photoncamera.ui.style.Fonts.of(pill.getContext(), 700));
        pill.setGravity(Gravity.CENTER);
        pill.setMinWidth(UiTokens.dp(c, 44));
        pill.setPadding(UiTokens.dp(c, 12), 0, UiTokens.dp(c, 12), 0);
        pill.setBackgroundResource(R.drawable.settings_value_pill);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UiTokens.dp(c, 30));
        pp.setMarginStart(UiTokens.dp(c, 10));
        head.addView(pill, pp);
        box.addView(head);
        LinearLayout range = new LinearLayout(c);
        range.setGravity(Gravity.CENTER_VERTICAL);
        range.setClipChildren(false);
        range.setClipToPadding(false);
        TextView min = SettingsStyle.text(c, VALUES[0] + " %", 12, SettingsStyle.RANGE), max = SettingsStyle.text(c, VALUES[VALUES.length - 1] + " %", 12, SettingsStyle.RANGE);
        range.addView(min);
        IosSlider bar = new IosSlider(c);
        bar.setTag("shade_transparency_slider");
        bar.setContentDescription(Lang.t(c, "Прозрачность шторки", "Panel transparency"));
        bar.setAccent(accent);
        bar.setRange(0, VALUES.length - 1, 1);
        bar.setDefaultValue(index(ShadeStyle.TRANSPARENCY_DEFAULT));
        bar.setValueNow(index(now));
        bar.setListener(new IosSlider.Listener() {
            @Override public void onValue(IosSlider s, float v, boolean fromUser) {
                if (!fromUser) return;
                int percent = VALUES[Math.max(0, Math.min(VALUES.length - 1, Math.round(v)))];
                if (percent != value()) choose(percent);
            }

            @Override public void onTracking(IosSlider s, boolean tracking) {
                if (tracking) IosMotion.lift(pill, true, pill.getCurrentTextColor(), pill.getCurrentTextColor());
                else IosMotion.pop(pill);
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bp.setMarginStart(UiTokens.dp(c, 10));
        bp.setMarginEnd(UiTokens.dp(c, 10));
        range.addView(bar, bp);
        range.addView(max);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = UiTokens.dp(c, 4);
        box.addView(range, rp);
        paint(box, now, accent);
    }

    /** The step of {@code percent} on the slider (the nearest of VALUES). */
    static int index(int percent) {
        int best = 0;
        for (int i = 1; i < VALUES.length; i++) if (Math.abs(VALUES[i] - percent) < Math.abs(VALUES[best] - percent)) best = i;
        return best;
    }

    private void paint(LinearLayout box, int now, int accent) {
        Context c = getContext();
        TextView pill = box.findViewWithTag("shade_transparency_value");
        if (pill != null) {
            pill.setText(now + " %");
            SettingsStyle.paintPill(pill, now != ShadeStyle.TRANSPARENCY_DEFAULT, accent);
            pill.setContentDescription(label(c, now));
        }
    }

    /** The concept's .prev: the scene, the sheet over its lower 62 % with «Быстрые настройки» and eight tile shapes. */
    static final class Preview extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path clip = new Path();
        private final RectF rect = new RectF();
        @Nullable private Bitmap scene;
        private float alpha = .6f;

        Preview(Context c) {
            super(c);
            setTag("shade_transparency_preview");
        }

        void setAlpha01(float a) {
            alpha = a;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Context c = getContext();
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            if (scene == null || scene.getWidth() != w) scene = LutSamples.scene(w, h, 0, 1.6f);
            float r = UiTokens.dp(c, 18);
            rect.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(rect, r, r, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawBitmap(scene, 0, 0, paint);
            float top = h * .38f;
            paint.setColor(UiTokens.alpha(ShadeStyle.SHEET_FILL, alpha));
            canvas.drawRoundRect(new RectF(0, top, w, h + r), r, r, paint);
            float pad = UiTokens.dp(c, 10);
            paint.setColor(UiTokens.TEXT);
            paint.setTextSize(UiTokens.dp(c, 12));
            paint.setTypeface(com.particlesdevs.photoncamera.ui.style.Fonts.of(c, 600));
            float ty = top + pad - paint.ascent();
            canvas.drawText(Lang.t(c, "Быстрые настройки", "Quick settings"), pad, ty, paint);
            float gap = UiTokens.dp(c, 5), tw = (w - 2 * pad - 3 * gap) / 4f, th = UiTokens.dp(c, 28), y0 = ty + paint.descent() + UiTokens.dp(c, 4);
            float tr = UiTokens.dp(c, 9), lw = Math.max(1, UiTokens.dp(c, 1));
            for (int k = 0; k < 8; k++) {
                float x = pad + (k % 4) * (tw + gap), y = y0 + (k / 4) * (th + gap);
                RectF t = new RectF(x, y, x + tw, y + th);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(UiTokens.alpha(0xFF1A1F22, alpha));
                canvas.drawRoundRect(t, tr, tr, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(lw);
                paint.setColor(UiTokens.LINE);
                canvas.drawRoundRect(t, tr, tr, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            canvas.restore();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(lw);
            paint.setColor(UiTokens.LINE);
            rect.inset(lw / 2, lw / 2);
            canvas.drawRoundRect(rect, r, r, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }
}
