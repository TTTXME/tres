package com.particlesdevs.photoncamera.ui.camera.views.settingsbar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.settings.ShadeCatalog;

/**
 * One tile of the quick sheet, as the concept draws it (SIMPLE_UI §2, .tile): a compact rectangle, wider than tall (50dp of
 * card, 5dp gaps - half of it this view's padding; x the screen's scale), 13dp corners, the card colour at the sheet's
 * alpha with a LINE stroke, holding the icon of the current value (18dp, muted), the short name (11sp, weight 600, one
 * line) and the value (10sp, muted, one line). A value that differs from the default tints the card with the accent (14 %,
 * a 35 % accent stroke) and turns the icon and the value accent; an unavailable setting is dimmed to 45 %. The text shrinks
 * (down to 8sp) instead of ending in an ellipsis. The last tile «Добавить» has a dashed outline and no fill.
 */
public class ShadeTileView extends FrameLayout {
    private static final float CARD_PADDING_DP = 2f;
    /** The card's least height (the concept's .tile min-height) before the scale. */
    // P583 (the concept's 4 x 2 shade: tiles 84px high, 8px apart): the card's least height
    static final float CARD_HEIGHT_DP = 84f;    /** The sheet's alpha the cards share (the concept's 40 % transparency: the fill at 60 %); SettingsBarLayout sets it. */
    static float sheetAlpha = .6f;
    final LinearLayout card;
    final ImageView icon;
    final TextView value;
    final TextView name;
    /** «×» in the edit mode: unpins the tile. */
    final TextView remove;
    @Nullable String key;
    boolean active, dimmed, addTile;
    /** P570: the icon shown is one of the colour icons (ui/Icons). */
    private boolean colourIcon;
    /** The inline slider card under the grid belongs to this tile: an accent outline. */
    boolean open;
    /** Edit mode: «×» shown, the tile wobbles. */
    boolean editing;
    private int accent;
    @Nullable private android.animation.ObjectAnimator wobble;

    public ShadeTileView(Context context) {
        super(context);
        // P583: half of the concept's 8px gap inside each tile
        int half = Math.round(com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dpF(context, 4f));
        setPadding(half, half, half, half);
        setClipToPadding(false);
        setClipChildren(false);
        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        int p = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(context, CARD_PADDING_DP);
        card.setPadding(p, p, p, p);
        card.setDuplicateParentStateEnabled(true);
        com.particlesdevs.photoncamera.ui.Motion.press(card); // P495: gives under the finger, springs back
        addView(card, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        // P583: the concept's 22px icon, 2px over the name
        int iconSize = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(context, 22);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(iconSize, iconSize);
        ip.bottomMargin = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(context, 2);
        card.addView(icon, ip);

        // the concept's order: the name (11sp, 600) over the value (10sp, muted)
        name = text(context, 11, ShadeStyle.TEXT);
        com.particlesdevs.photoncamera.ui.style.Fonts.apply(name, com.particlesdevs.photoncamera.ui.style.Fonts.SEMIBOLD);
        card.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        value = text(context, 10, ShadeStyle.MUTED);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.topMargin = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(context, 2);
        com.particlesdevs.photoncamera.ui.style.Fonts.apply(value, com.particlesdevs.photoncamera.ui.style.Fonts.REGULAR);
        card.addView(value, vp);

        // the concept's .x: a 20px #48484A disc (right -5, top -5) with its bold cross (60 %) in white
        remove = new TextView(context);
        remove.setGravity(Gravity.CENTER);
        remove.setIncludeFontPadding(false);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(com.particlesdevs.photoncamera.ui.camera.ConceptStyle.GREY);
        int crossSize = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(context, 12);
        remove.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{dot,
                com.particlesdevs.photoncamera.ui.camera.ConceptStyle.icon(context, R.drawable.cv_cross, ShadeStyle.TEXT, crossSize)}) {{
            setLayerSize(1, crossSize, crossSize);
            setLayerGravity(1, Gravity.CENTER);
        }});
        remove.setContentDescription(com.particlesdevs.photoncamera.util.Lang.t(context, "Убрать плитку", "Remove the tile"));
        remove.setVisibility(GONE);
        remove.setTag("shade_tile_remove");
        int size = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(context, 20);
        LayoutParams rp = new LayoutParams(size, size, Gravity.TOP | Gravity.END);
        // Over the card's corner, up to the tile's edge (the grid clips anything further out).
        rp.topMargin = -com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(context, 1);
        rp.setMarginEnd(-com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(context, 1));
        addView(remove, rp);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(true);
        setClickable(true);
    }

    private static TextView text(Context context, float sp, int color) {
        TextView t = new TextView(context);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setMaxLines(1);
        t.setEllipsize(null);
        t.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_BALANCED);
        t.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return t;
    }

    /** Shows a setting: icon of its current value, short value, name, highlight and dimming. */
    void bind(ShadeCatalog catalog, ShadeCatalog.Entry entry, @Nullable String reason) {
        final boolean wasActive = active && entry.key.equals(key);
        final boolean sameKey = entry.key.equals(key);
        key = entry.key;
        addTile = false;
        accent = ShadeStyle.accent(getContext());
        active = catalog.lit(entry);   // P583: lit while on, as the concept
        // P495: a switched tile's icon «breathes» (the same tile bound again with the other state)
        if (sameKey && wasActive != active) com.particlesdevs.photoncamera.ui.Motion.pop(icon);
        dimmed = reason != null;
        // SIMPLE_UI: the icon as it is (the concept strikes nothing through; the value says «Выкл.»)
        icon.setImageDrawable(catalog.icon(entry));
        colourIcon = catalog.colourValueIcon(entry);
        value.setText(catalog.valueText(entry, true));
        name.setText(entry.shortTitle);
        name.setVisibility(VISIBLE);
        applyColors();
        String description = getContext().getString(R.string.shade_toast_value, entry.shortTitle, catalog.valueText(entry, false));
        if (catalog.changed(entry)) description += ", " + getContext().getString(R.string.shade_changed);
        if (dimmed) description += ". " + reason;
        setContentDescription(description);
        requestLayout();
    }

    /** The dashed «Добавить» tile. */
    void bindAdd() {
        key = null;
        addTile = true;
        active = false;
        dimmed = false;
        accent = ShadeStyle.accent(getContext());
        icon.setImageResource(R.drawable.ic_shade_plus);
        colourIcon = true;
        name.setText(R.string.shade_add);
        name.setVisibility(VISIBLE);
        value.setText("");
        value.setVisibility(GONE);
        remove.setVisibility(GONE);
        GradientDrawable dashed = new GradientDrawable();
        // P583 (the concept's add tile): a 1px dashed rgba(255, 255, 255, .22) outline, the plus and «Добавить» at 70 %
        dashed.setCornerRadius(radius());
        dashed.setColor(0);
        dashed.setStroke(Math.max(1, Math.round(getResources().getDisplayMetrics().density)), 0x38FFFFFF, ShadeStyle.dp(getContext(), 5), ShadeStyle.dp(getContext(), 4));
        card.setBackground(dashed);
        com.particlesdevs.photoncamera.ui.camera.ConceptStyle.icon(icon, R.drawable.cv_plus, 0xB3FFFFFF);
        icon.setActivated(false);
        name.setTextColor(0xB3FFFFFF);
        com.particlesdevs.photoncamera.ui.style.Fonts.apply(name, com.particlesdevs.photoncamera.ui.style.Fonts.REGULAR);
        card.setAlpha(1f);
        setContentDescription(getContext().getString(R.string.shade_add_description));
        requestLayout();
    }

    void setOpen(boolean open) {
        if (this.open == open) return;
        this.open = open;
        if (!addTile) applyColors();
    }

    /**
     * P495 (concept G, Material You tiles): a tonal tile without a line, 20dp corners; a changed setting fills the whole tile with
     * the accent and its ink ({@link ShadeStyle#onAccent}); the open slider tile keeps the tonal fill with an accent outline. The
     * value line only for a changed setting (the name alone otherwise, the value is in the description and the toast).
     */
    private void applyColors() {
        // P515 (owner, 2026-11-05: «сделай чтобы было видно какое значение в данный момент у плитки как это было в старой шторке»):
        // the value under the name again (P495 had the name only)
        value.setVisibility(value.getText().length() > 0 ? VISIBLE : GONE);
        // P574 (the approved concept's tiles): a changed setting is a deep accent tile with the accent's own ink (the camera's
        // yellow on a dark yellow), not a full accent fill
        // P583 (the concept): on, the accent's dark tone (#4A3D00 for the yellow) with the accent's ink; off, #2C2C2E and white;
        // the open slider tile an inset 1.5px accent edge
        final int ink = active ? accent : ShadeStyle.TEXT;
        final int fill = active ? com.particlesdevs.photoncamera.ui.camera.ConceptStyle.dark(accent) : com.particlesdevs.photoncamera.ui.camera.ConceptStyle.ROW;
        final GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(radius());
        if (open) shape.setStroke(Math.max(1, Math.round(1.5f * getResources().getDisplayMetrics().density)), accent);
        final GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(radius());
        card.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x29FFFFFF), shape, mask));
        // P570: a colour icon in its own colours on the tonal tile; on the accent, tonal mono in its ink and in its pressed pose
        if (!colourIcon) {
            icon.clearColorFilter();
            icon.setImageTintList(ColorStateList.valueOf(ink));
        } else if (active) com.particlesdevs.photoncamera.ui.Icons.mono(icon, ink);
        else com.particlesdevs.photoncamera.ui.Icons.own(icon);
        icon.setActivated(active);
        value.setTextColor((ink & 0x00FFFFFF) | 0xB8000000);
        name.setTextColor(ink);
        com.particlesdevs.photoncamera.ui.style.Fonts.apply(name, com.particlesdevs.photoncamera.ui.style.Fonts.SEMIBOLD);
        card.setAlpha(dimmed ? ShadeStyle.DIMMED : 1f);
    }

    /** The concept's tile corner: min(22, max(6, width / 3.2)) px of its width (22px on the 4-column page). */
    private float radius() {
        float d = getResources().getDisplayMetrics().density, k = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.k(getContext());
        float w = card.getWidth() > 0 ? card.getWidth() / (d * k) : 80f;
        return Math.min(22f, Math.max(6f, w / 3.2f)) * d * k;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w != ow) post(() -> {
            if (addTile) {
                if (card.getBackground() instanceof GradientDrawable) ((GradientDrawable) card.getBackground()).setCornerRadius(radius());
            } else if (key != null) applyColors();
        });
    }

    /** Edit mode: «×» on the tile and a gentle wobble (none when animations are off). */
    void setEditing(boolean editing, int index) {
        this.editing = editing;
        remove.setVisibility(editing && !addTile ? VISIBLE : GONE);
        if (editing && !addTile) startWobble(index);
        else stopWobble();
    }

    private void startWobble(int index) {
        if (wobble != null || !android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        wobble = android.animation.ObjectAnimator.ofFloat(card, ROTATION, -.8f, .8f);
        wobble.setDuration(320);
        wobble.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        wobble.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        wobble.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        // Neighbours out of step, as in the concept.
        if (index % 2 == 1) wobble.setCurrentPlayTime(160);
        wobble.start();
    }

    void stopWobble() {
        if (wobble != null) {
            wobble.cancel();
            wobble = null;
        }
        card.setRotation(0f);
    }

    /** The tile under the finger: a bit larger, the wobble stops. */
    void lift(boolean lifted) {
        if (lifted) stopWobble();
        animate().scaleX(lifted ? 1.08f : 1f).scaleY(lifted ? 1.08f : 1f).setDuration(150).start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopWobble();
        super.onDetachedFromWindow();
    }

    /** Landscape: the icon turns with the phone, the labels stay (owner's answer 11). */
    void setIconRotation(float degrees) {
        icon.setRotation(degrees);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int inner = width - getPaddingLeft() - getPaddingRight() - 2 * com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(getContext(), CARD_PADDING_DP);
        float k = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.k(getContext());
        // P517: narrow tiles (up to 10 columns, P515) shrink the text further before an ellipsis
        // P583: the concept's level-2 tile - the name 12.5px semibold, the value 11.5px at 72 %
        ShadeStyle.fit(name, inner, 12.5f * k, 9 * k, 6.5f, 1);
        ShadeStyle.fit(value, inner, 11.5f * k, 9 * k, 6.5f, 1);
        // Content height at this width, at least the concept's 50dp card (a compact rectangle, wider than tall).
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int least = com.particlesdevs.photoncamera.circularbarlib.ui.UiScale.dp(getContext(), CARD_HEIGHT_DP) + getPaddingTop() + getPaddingBottom();
        int height = Math.max(least, card.getMeasuredHeight() + getPaddingTop() + getPaddingBottom());
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY)
            height = Math.max(height, MeasureSpec.getSize(heightMeasureSpec));
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
