package com.scamera.app.gallery.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.scamera.app.R;
import com.scamera.app.util.Lang;

/**
 * P59b: the library's floating selection bar (replaces the FABs): × to clear, the accent count pill with the plural
 * («3 снимка», replaces number_fab), «Сравнить» (only with exactly 2), «Поделиться», «Удалить» (warning colour).
 * P579 (owner, 2026-11-09: «Не забудь что у нас есть галерея и в ней надо стиль также обновить»): the tab bar's dark capsule with its
 * light edge, white line icons, «Удалить» in the red, the count the accent on its dark tone (the settings' value pill).
 */
public final class SelectionBar {
    public final LinearLayout view;
    public final View close, compare, share, delete, favourite;
    public final TextView count;

    public SelectionBar(Context c, Runnable onClose, Runnable onCompare, Runnable onShare, Runnable onDelete) {
        this(c, onClose, onCompare, onShare, onDelete, null, null);
    }

    /**
     * P498: with «Избранное» (the hearts of the selected photos on / off) and a tap on the count selecting every photo shown
     * (or clearing the selection when all are selected).
     */
    public SelectionBar(Context c, Runnable onClose, Runnable onCompare, Runnable onShare, Runnable onDelete,
                        @androidx.annotation.Nullable Runnable onFavourite, @androidx.annotation.Nullable Runnable onAll) {
        view = new LinearLayout(c);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setBackground(GalleryUi.bar(c, 26));
        view.setElevation(GalleryUi.dp(c, 10));
        int p = GalleryUi.dp(c, 6);
        view.setPadding(p, p, p, p);
        view.setContentDescription(Lang.t("Выбранные снимки", "Selected photos"));
        close = GalleryUi.squareButton(c, R.drawable.ui_ic_close, Lang.t("Снять выделение", "Clear the selection"), v -> onClose.run());
        close.setBackground(GalleryUi.pressable(GalleryUi.round(c, 0, 0, 999)));
        view.addView(close, new LinearLayout.LayoutParams(GalleryUi.dp(c, 40), GalleryUi.dp(c, 40)));
        count = GalleryUi.fitText(c, "", 14, GalleryUi.accent(c));
        count.setTypeface(com.scamera.app.ui.style.Fonts.of(count.getContext(), 600));
        count.setGravity(Gravity.CENTER);
        count.setPadding(GalleryUi.dp(c, 12), GalleryUi.dp(c, 8), GalleryUi.dp(c, 12), GalleryUi.dp(c, 8));
        int bg = com.scamera.app.ui.settings.SettingsStyle.pillFill(GalleryUi.accent(c));
        count.setBackground(GalleryUi.round(c, bg, bg, 999));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.leftMargin = cp.rightMargin = GalleryUi.dp(c, 4);
        view.addView(count, cp);
        if (onAll != null) {
            count.setClickable(true);
            count.setFocusable(true);
            count.setOnClickListener(v -> onAll.run());
            com.scamera.app.ui.Motion.press(count);
        }
        compare = GalleryUi.actionButton(c, R.drawable.ui_ic_compare, Lang.t("Сравнить", "Compare"), false, v -> onCompare.run());
        share = GalleryUi.actionButton(c, R.drawable.ui_ic_share, Lang.t("Поделиться", "Share"), false, v -> onShare.run());
        delete = GalleryUi.actionButton(c, R.drawable.ui_ic_trash, Lang.t("Удалить", "Delete"), true, v -> onDelete.run());
        favourite = onFavourite == null ? null : GalleryUi.actionButton(c, R.drawable.ui_ic_heart, Lang.t("Избранное", "Favourite"), false, v -> {
            com.scamera.app.ui.Motion.pop(((ViewGroup) v).getChildAt(0));
            onFavourite.run();
        });
        View[] actions = favourite == null ? new View[]{compare, share, delete} : new View[]{favourite, compare, share, delete};
        // (owner, 2026-11-05: the bottom bars «гораздо более компактными» - fixed buttons in a centred capsule, not the full width)
        int each = GalleryUi.dp(c, Math.max(52, Math.min(72, (c.getResources().getConfiguration().screenWidthDp - 190) / actions.length)));
        for (View b : actions) {
            com.scamera.app.ui.Motion.press(b);
            view.addView(b, new LinearLayout.LayoutParams(each, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /** The count and the enabled actions for {@code n} selected photos. */
    public void bind(int n) {
        count.setText(GalleryUi.shots(n));
        count.setContentDescription(Lang.t("Выбрано: ", "Selected: ") + GalleryUi.shots(n));
        GalleryUi.setEnabledLook(compare, n == 2);
        GalleryUi.setEnabledLook(share, n > 0);
        GalleryUi.setEnabledLook(delete, n > 0);
        if (favourite != null) GalleryUi.setEnabledLook(favourite, n > 0);
    }

    /** P498: the heart filled when every selected photo is a favourite (a tap then takes them out). */
    public void bindFavourite(boolean allFavourite) {
        if (favourite == null) return;
        android.widget.ImageView i = (android.widget.ImageView) ((ViewGroup) favourite).getChildAt(0);
        i.setImageResource(allFavourite ? R.drawable.ui_ic_heart_on : R.drawable.ui_ic_heart);
        favourite.setContentDescription(allFavourite ? Lang.t("Убрать из избранного", "Remove from favourites") : Lang.t("В избранное", "Add to favourites"));
    }
}
