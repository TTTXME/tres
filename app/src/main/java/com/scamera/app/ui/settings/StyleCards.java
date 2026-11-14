package com.scamera.app.ui.settings;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.scamera.app.settings.PhotoStyle;
import com.scamera.app.ui.camera.LookThumbs;

import java.util.function.IntConsumer;

/**
 * P718: the «Стиль» presets as a row of three cards - Насыщенный / Обычный / Нейтральный (PhotoStyle.order) - each the wizard's
 * card (SettingsWizard.Card: its fill, ring, tick and pick spring) with the preset's thumbnail on top (LookThumbs.preset: the
 * settings' drawn sample through the preset, as the LUT tray's cards) and its name under it. The wizard's «Вид снимков → Стиль»
 * and the settings' «Стиль снимка» page show the same row. Tags {@code <prefix><id>} (wizard_style_vivid, …).
 */
public final class StyleCards {
    private StyleCards() {}

    /** The row with {@code chosen} marked; a tap on another card marks it (animated) and gives its preset to {@code pick}. */
    public static LinearLayout row(Context c, String tagPrefix, int chosen, IntConsumer pick) {
        final LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final int[] order = PhotoStyle.order();
        final SettingsWizard.Card[] cards = new SettingsWizard.Card[order.length];
        final TextView[] names = new TextView[order.length];
        for (int i = 0; i < order.length; i++) {
            final int style = order[i];
            final SettingsWizard.Card card = new SettingsWizard.Card(c, tagPrefix + PhotoStyle.id(style), 0, PhotoStyle.name(c, style), null);
            names[i] = fit(card);
            card.view.setContentDescription(PhotoStyle.name(c, style) + ". " + PhotoStyle.about(c, style));
            LookThumbs.preset(card.image, style);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) lp.setMarginStart(SettingsStyle.dp(c, 8));
            row.addView(card.view, lp);
            cards[i] = card;
            card.select(style == chosen, false);
        }
        for (int i = 0; i < order.length; i++) {
            final int index = i;
            cards[i].view.setOnClickListener(v -> {
                if (cards[index].on) return;
                for (int k = 0; k < cards.length; k++) cards[k].select(k == index, true);
                pick.accept(order[index]);
            });
        }
        row.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitNames(names));
        return row;
    }

    /** The cards' names at one size, down from 15sp (to 10sp at least) until the longest fits its card on one line. */
    private static void fitNames(TextView[] names) {
        float room = Float.MAX_VALUE;
        for (TextView t : names) room = Math.min(room, t.getWidth() - t.getPaddingLeft() - t.getPaddingRight());
        if (room <= 0 || room == Float.MAX_VALUE) return;
        final float sp = names[0].getResources().getDisplayMetrics().scaledDensity;
        final android.text.TextPaint paint = new android.text.TextPaint(names[0].getPaint());
        float size = 15f * sp;
        while (size > 10f * sp && widest(names, paint, size) > room - 2f) size -= 0.5f * sp;
        for (TextView t : names) {
            if (Math.abs(t.getTextSize() - size) < 0.01f) continue;
            final float fitted = size;
            t.post(() -> t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fitted));
        }
    }

    private static float widest(TextView[] names, android.text.TextPaint paint, float size) {
        paint.setTextSize(size);
        float w = 0f;
        for (TextView t : names) w = Math.max(w, android.text.Layout.getDesiredWidth(t.getText(), paint));
        return w;
    }

    /** Narrower padding and a one-line name that shrinks to fit, so «Нейтральный» stays whole at 360dp. */
    private static TextView fit(SettingsWizard.Card card) {
        TextView name = null;
        final int pad = SettingsStyle.dp(card.view.getContext(), 8);
        card.view.setPadding(pad, SettingsStyle.dp(card.view.getContext(), 10), pad, SettingsStyle.dp(card.view.getContext(), 10));
        for (int i = 0; i < card.view.getChildCount(); i++) {
            final View v = card.view.getChildAt(i);
            if (!(v instanceof TextView)) continue;
            final TextView t = (TextView) v;
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            name = t;
        }
        return name;
    }
}
