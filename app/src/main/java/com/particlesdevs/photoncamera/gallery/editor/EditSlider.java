package com.particlesdevs.photoncamera.gallery.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.gallery.ui.GalleryUi;
import com.particlesdevs.photoncamera.ui.controls.IosMotion;
import com.particlesdevs.photoncamera.ui.controls.IosSlider;
import com.particlesdevs.photoncamera.ui.settings.SettingsStyle;
import com.particlesdevs.photoncamera.util.Lang;

/**
 * P351: one slider row of the editor in the gallery's look - the label at the left, the value at the right (accent when it is
 * not the default), the track under them (a bipolar slider fills from its middle). A double tap puts the default back.
 * {@link Listener#changed} while dragging, {@link Listener#committed} when let go (one undo step).
 * P579 (owner, 2026-11-09: «Не забудь что у нас есть галерея и в ней надо стиль также обновить»): the settings' slider row - the
 * label (15sp) and the value in its pill (the accent on its dark tone at the default, the theme's opposite when changed; it lifts
 * while the knob is held and pops when let go: IosMotion), the shared slider under them (IosSlider: the relative drag, the glass knob,
 * the rubber band, a tap jumps, a double tap goes to the default, all on springs). A colour scale keeps its gradient track; a bipolar
 * slider shows no fill from the left end (its middle is the default: a small mark there), so the pill tells a change.
 */
public final class EditSlider extends LinearLayout {
    public interface Listener {
        void changed(float value);

        void committed();
    }

    public interface Format {
        String of(float value);
    }

    private final TextView title, pill;
    private final Track slider;
    private final int accent;
    private String label;
    private float def;
    private float step;
    private boolean bipolar;
    @Nullable private Listener listener;
    @Nullable private Format format;
    /** The value when the finger went down: the release commits only a change. */
    private float downValue;

    public EditSlider(Context c) {
        super(c);
        accent = GalleryUi.accent(c);
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        setPadding(GalleryUi.dp(c, 18), GalleryUi.dp(c, 8), GalleryUi.dp(c, 18), GalleryUi.dp(c, 2));
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(GalleryUi.dp(c, 28));
        title = GalleryUi.text(c, "", 15, GalleryUi.TEXT);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(title, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        pill = GalleryUi.text(c, "", 14, accent);
        pill.setTypeface(com.particlesdevs.photoncamera.ui.style.Fonts.of(pill.getContext(), 700));
        pill.setFontFeatureSettings("tnum");
        pill.setGravity(Gravity.CENTER);
        pill.setMaxLines(1);
        pill.setMinWidth(GalleryUi.dp(c, 44));
        pill.setPadding(GalleryUi.dp(c, 11), 0, GalleryUi.dp(c, 11), 0);
        pill.setBackgroundResource(R.drawable.settings_value_pill);
        pill.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams pp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, GalleryUi.dp(c, 26));
        pp.setMarginStart(GalleryUi.dp(c, 10));
        head.addView(pill, pp);
        addView(head, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        slider = new Track(c);
        slider.setAccent(accent);
        slider.setListener(new IosSlider.Listener() {
            @Override
            public void onValue(IosSlider s, float v, boolean fromUser) {
                paintPill();
                if (!fromUser || listener == null) return;
                listener.changed(v);
                // a step by the keyboard or the accessibility actions: no finger to let go, one undo step each
                if (!s.isTracking()) listener.committed();
            }

            @Override
            public void onTracking(IosSlider s, boolean tracking) {
                IosMotion.lift(pill, tracking, pill.getCurrentTextColor(), pill.getCurrentTextColor());
                if (tracking) {
                    downValue = s.getValue();
                    return;
                }
                IosMotion.pop(pill);
                if (listener != null && s.getValue() != downValue) listener.committed();
            }
        });
        LayoutParams sp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, GalleryUi.dp(c, 36));
        sp.topMargin = GalleryUi.dp(c, 2);
        addView(slider, sp);
    }

    public EditSlider setup(String label, float min, float max, float def, float step, boolean bipolar) {
        this.label = label;
        this.def = def;
        this.step = step;
        this.bipolar = bipolar;
        title.setText(label);
        slider.setRange(min, max, step);
        slider.setDefaultValue(def);
        slider.setValueNow(def);
        slider.setContentDescription(label);
        slider.bipolar(bipolar, (def - min) / Math.max(1e-6f, max - min));
        paintPill();
        return this;
    }

    public EditSlider listener(@Nullable Listener l) {
        listener = l;
        return this;
    }

    public EditSlider format(@Nullable Format f) {
        format = f;
        paintPill();
        return this;
    }

    /** A colour track (a hue or temperature scale) instead of the plain one. */
    public EditSlider gradient(@Nullable int[] colours) {
        slider.gradient(colours);
        return this;
    }

    public float value() {
        return slider.getValue();
    }

    /** Sets the value (snapped); {@code notify}: tells the listener. The knob glides there when the row is on screen. */
    public void set(float v, boolean notify) {
        float before = slider.getValue();
        slider.setValue(v);
        if (slider.getValue() == before) return;
        paintPill();
        if (notify && listener != null) listener.changed(slider.getValue());
    }

    /** The value pill: its text, the accent on its dark tone at the default, the theme's opposite when changed (the settings' pill). */
    private void paintPill() {
        float value = slider.getValue();
        pill.setText(valueText());
        SettingsStyle.paintPill(pill, Math.abs(value - def) > 1e-6f, accent);
    }

    String valueText() {
        float value = slider.getValue();
        if (format != null) return format.of(value);
        if (step >= 1f) {
            int i = Math.round(value);
            return bipolar && i > 0 ? "+" + i : String.valueOf(i);
        }
        String t = GalleryUi.decimal(value, step < 0.1f ? 2 : 1);
        return bipolar && value > 0 ? "+" + t : t;
    }

    /** «Сброс двойным касанием» for the help text. */
    static String hint() {
        return Lang.t("Двойное касание — сброс", "Double tap resets");
    }

    /**
     * The shared slider with the editor's two tracks: a colour scale (its gradient under the knob, no fill) and a bipolar one (the
     * plain track with a mark at its default in the middle, no fill from the left end). Both are drawn under the shared knob.
     */
    static final class Track extends IosSlider {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        @Nullable private int[] colours;
        @Nullable private LinearGradient shader;
        private int shaderWidth;
        private boolean centred;
        private float centreK;
        private int accent;

        Track(Context c) {
            super(c);
        }

        @Override
        public void setAccent(int colour) {
            accent = colour;
            apply();
        }

        void bipolar(boolean on, float k) {
            centred = on;
            centreK = k;
            apply();
        }

        void gradient(@Nullable int[] c) {
            colours = c;
            shader = null;
            apply();
        }

        private boolean own() {
            return colours != null || centred;
        }

        /** The shared drawing takes the plain track and the fill; this one hides them for its own tracks. */
        private void apply() {
            super.setAccent(own() ? accent & 0x00FFFFFF : accent);
            setTrackColour(own() ? 0 : 0x5C787880);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (own()) {
                float d = getResources().getDisplayMetrics().density;
                float cy = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;
                float x0 = getPaddingLeft(), x1 = getWidth() - getPaddingRight(), r = 3 * d;
                rect.set(x0, cy - r, x1, cy + r);
                paint.setStyle(Paint.Style.FILL);
                if (colours != null) {
                    int w = Math.round(x1 - x0);
                    if (shader == null || shaderWidth != w) {
                        shader = new LinearGradient(x0, 0, x1, 0, colours, null, Shader.TileMode.CLAMP);
                        shaderWidth = w;
                    }
                    paint.setShader(shader);
                    paint.setColor(0xFFFFFFFF);
                } else {
                    paint.setShader(null);
                    paint.setColor(0x5C787880);
                }
                c.drawRoundRect(rect, r, r, paint);
                paint.setShader(null);
                if (centred) {
                    // the default's mark: the knob's own travel (inset by its radius) at the default's place
                    float knobR = 14 * d;
                    float x = x0 + knobR + centreK * Math.max(1f, x1 - x0 - 2 * knobR);
                    paint.setColor(colours != null ? 0xCCFFFFFF : 0x99FFFFFF);
                    rect.set(x - d, cy - 6 * d, x + d, cy + 6 * d);
                    c.drawRoundRect(rect, d, d, paint);
                }
            }
            super.onDraw(c);
        }
    }
}
