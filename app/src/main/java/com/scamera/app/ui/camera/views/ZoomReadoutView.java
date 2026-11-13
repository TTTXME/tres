package com.scamera.app.ui.camera.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.scamera.app.circularbarlib.ui.UiScale;
import com.scamera.app.ui.camera.views.settingsbar.ShadeStyle;
import com.scamera.app.util.Lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SIMPLE_UI §1, the concept's zoom readout (.zhud): while the zoom changes, a 22dp pill in the message band - the value in
 * the accent («2,5×», 12sp bold) and a 150dp mini log scale from the lowest to the highest zoom (third-stop ticks, taller
 * marks at the modules, the accent needle at the zoom). It replaces the 232 x 48dp zoom dial card over the viewfinder; it
 * fades out {@link #HIDE_MS} after the last change and takes the band's place from any message meanwhile.
 */
public class ZoomReadoutView extends View {
    public static final long HIDE_MS = 1300;
    // owner, 2026-11-05 («эту шкалу тоже переделай под новый стиль», «между тем что сейчас и А»): the lens strip's glass, the value
    // on a tonal accent chip, the module marks bright, the ticks fading towards the ends, the needle ringed in the glass colour
    static final float HEIGHT_DP = 26f, SCALE_DP = 150f, PAD_DP = 3f, GAP_DP = 10f, VALUE_DP = 40f, END_DP = 12f;
    static final int FILL = BottomChrome.STRIP_FILL, FAINT = 0xFF8B9096;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float min = 1f, max = 10f, zoom = 1f;
    private final List<Float> modules = new ArrayList<>();
    private final Runnable hide = () -> animate().alpha(0f).setDuration(200).withEndAction(() -> setVisibility(GONE)).start();

    public ZoomReadoutView(Context context) {
        this(context, null);
    }

    public ZoomReadoutView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        value.setTextSize(UiScale.spPx(context, 12));
        value.setTypeface(com.scamera.app.ui.style.Fonts.of(getContext(), com.scamera.app.ui.style.Fonts.BOLD));
        value.setTextAlign(Paint.Align.CENTER);
        value.setFontFeatureSettings("tnum");
        setVisibility(GONE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The zoom range and the modules' marks. */
    public void configure(float min, float max, List<Float> moduleZooms) {
        if (max > min && min > 0f) {
            this.min = min;
            this.max = max;
        }
        modules.clear();
        modules.addAll(moduleZooms);
        invalidate();
    }

    /** Shows the readout at {@code zoom} and (re)starts its fade-out timer. */
    public void show(float zoom) {
        this.zoom = zoom;
        removeCallbacks(hide);
        animate().cancel();
        setAlpha(1f);
        setVisibility(VISIBLE);
        invalidate();
        postDelayed(hide, HIDE_MS);
    }

    public float getZoom() {
        return zoom;
    }

    /** «2,5×» (the decimal comma in Russian). */
    static String label(float zoom) {
        float rounded = Math.round(zoom * 10f) / 10f;
        String s = Math.abs(rounded - Math.round(rounded)) < 1e-3 ? String.valueOf(Math.round(rounded))
                : String.format(Locale.US, "%.1f", rounded);
        return (Lang.ru() ? s.replace('.', ',') : s) + "×";
    }

    /** A tick's alpha by its distance from the scale's end in {@link #END_DP} units: faint at the end, full from one unit in. */
    static int edgeAlpha(float units) {
        return Math.round(255 * (.25f + .75f * Math.max(0f, Math.min(1f, units))));
    }

    /** Position of {@code z} on the scale, 0..1 (log). */
    float pos(float z) {
        double lo = Math.log(min), hi = Math.log(max);
        return (float) Math.max(0, Math.min(1, (Math.log(Math.max(min, Math.min(max, z))) - lo) / (hi - lo)));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Context c = getContext();
        int w = UiScale.dp(c, PAD_DP * 2 + VALUE_DP + GAP_DP + SCALE_DP);
        setMeasuredDimension(w, UiScale.dp(c, HEIGHT_DP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Context c = getContext();
        float h = getHeight(), w = getWidth(), d = UiScale.dpF(c, 1);
        int accent = ShadeStyle.accent(c);
        // the glass capsule
        rect.set(d / 2, d / 2, w - d / 2, h - d / 2);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(FILL);
        canvas.drawRoundRect(rect, h / 2, h / 2, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, d / 2));
        paint.setColor(BottomChrome.STRIP_LINE);
        canvas.drawRoundRect(rect, h / 2, h / 2, paint);
        // the value on its tonal chip
        float pad = UiScale.dpF(c, PAD_DP), chipW = UiScale.dpF(c, VALUE_DP);
        rect.set(pad, pad, pad + chipW, h - pad);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((accent & 0x00FFFFFF) | 0x2E000000);
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint);
        value.setColor(accent);
        value.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = value.getFontMetrics();
        canvas.drawText(label(zoom), rect.centerX(), h / 2 - (fm.ascent + fm.descent) / 2, value);
        // the scale: third-stop ticks fading towards its ends, module marks, the needle
        float x0 = pad + chipW + UiScale.dpF(c, GAP_DP), sw = UiScale.dpF(c, SCALE_DP), cy = h / 2;
        for (double z = min; z <= max * 1.0001; z *= Math.pow(2, 1 / 3.0)) {
            float t = pos((float) z);
            paint.setColor(FAINT);
            paint.setAlpha(edgeAlpha(Math.min(t, 1 - t) * sw / UiScale.dpF(c, END_DP)));
            float x = x0 + t * sw;
            canvas.drawRect(x - d / 2, cy - 2.5f * d, x + d / 2, cy + 2.5f * d, paint);
        }
        paint.setColor(ShadeStyle.TEXT);
        for (float z : modules) {
            float x = x0 + pos(z) * sw;
            rect.set(x - .6f * d, cy - 5 * d, x + .6f * d, cy + 5 * d);
            canvas.drawRoundRect(rect, d, d, paint);
        }
        float nx = x0 + pos(zoom) * sw;
        paint.setColor(FILL | 0xFF000000);
        rect.set(nx - 3.5f * d, cy - 9 * d, nx + 3.5f * d, cy + 9 * d);
        canvas.drawRoundRect(rect, 3.5f * d, 3.5f * d, paint);
        paint.setColor(accent);
        rect.set(nx - 2f * d, cy - 7.5f * d, nx + 2f * d, cy + 7.5f * d);
        canvas.drawRoundRect(rect, 2f * d, 2f * d, paint);
    }
}
