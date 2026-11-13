package com.scamera.app.ui.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.appcompat.content.res.AppCompatResources;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.scamera.app.R;
import com.scamera.app.circularbarlib.ui.AccentPalette;
import com.scamera.app.settings.PreferenceKeys;

/**
 * One look for every alert dialog of the app (owner, 2026-10-28: «исправь стиль всех таких меню которые выбиваются из нашего
 * стиля приложения»; P574: the look of the camera's own sheets): the rows' card #2C2C2E (no outline, 30dp corners), a white
 * medium title, body text in white at 70 %, sentence-case buttons - «Отмена» a grey capsule, the confirm an accent capsule -
 * and the accent tick in lists (R.style.AlertDialogTheme).
 * <p>
 * The dialogs are androidx / Material ones drawn from the app's own layouts and theme: OEM ROMs (ColorOS, OriginOS, HyperOS)
 * restyle the framework's android.app.AlertDialog, a grey Holo card with ALL-CAPS buttons on those phones, but not these.
 * The same theme is the base theme's alertDialogTheme, so the dialogs the preference library builds (backup, restore, reset)
 * look the same.
 */
public final class DialogStyle {
    private DialogStyle() {}

    /** The app's alert dialog builder: the Material builder on {@code c} with the user's accent and the app's dialog theme. */
    public static MaterialAlertDialogBuilder builder(Context c) {
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(accented(c), R.style.AlertDialogTheme) {
            @Override public androidx.appcompat.app.AlertDialog create() {
                androidx.appcompat.app.AlertDialog d = super.create();
                // P584: the dialog's texts in the concept's Roboto Flex, sizes in dp (its decor followed from the first layout)
                if (d.getWindow() != null) ConceptFonts.follow(d.getWindow().getDecorView());
                return d;
            }
        };
        // the card with its outline (the builder's own surface has no stroke); the insets stay the theme's
        b.setBackground(AppCompatResources.getDrawable(b.getContext(), R.drawable.dialog_surface));
        return b;
    }

    /**
     * {@code c} with the overlay of the user's accent (as BaseActivity applies it), so a dialog over an activity that does not
     * apply it (LensDiscoveryActivity) or over a non-activity context has the same accent pill and radio.
     */
    static Context accented(Context c) {
        int style = PreferenceKeys.accentStyle(AccentPalette.selected(c));
        return style == 0 ? c : new ContextThemeWrapper(c, style);
    }

    /** A text field inside a dialog: P574 a step lighter than the card (#3A3A3C), 12dp corners, no outline; white, the hint at 55 %. */
    public static <T extends EditText> T field(T field) {
        Context c = field.getContext();
        field.setBackground(AppCompatResources.getDrawable(c, R.drawable.dialog_field));
        field.setTextColor(SettingsStyle.ROW_TITLE);
        field.setHintTextColor(SettingsStyle.SECTION);
        field.setTextSize(16);
        field.setMinHeight(SettingsStyle.dp(c, 48));
        field.setPadding(SettingsStyle.dp(c, 16), SettingsStyle.dp(c, 12), SettingsStyle.dp(c, 16), SettingsStyle.dp(c, 12));
        return field;
    }

    /** A check box inside a dialog: white text, the accent when on and a faint box when off (P574). */
    public static <T extends CompoundButton> T check(T box) {
        box.setTextColor(SettingsStyle.ROW_TITLE);
        box.setTextSize(15);
        box.setButtonTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{SettingsStyle.accent(box.getContext()), SettingsStyle.CHEVRON}));
        return box;
    }

    /**
     * The custom view of a dialog: {@code views} in a column aligned with the title (24dp at the sides), 12dp apart.
     */
    public static LinearLayout body(Context c, View... views) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(SettingsStyle.dp(c, 24), SettingsStyle.dp(c, 12), SettingsStyle.dp(c, 24), SettingsStyle.dp(c, 4));
        for (View v : views) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (box.getChildCount() > 0) lp.topMargin = SettingsStyle.dp(c, 12);
            box.addView(v, lp);
        }
        return box;
    }

    /**
     * P574 (owner, 2026-11-09, the concept's alert): a confirmation of something that cannot be undone (the page reset, the full
     * reset) - a centred card with 30dp corners, the title and the text centred, two capsule buttons side by side: «Отмена» and
     * {@code yes} in red. Still an AlertDialog (BUTTON_POSITIVE is {@code yes}, BUTTON_NEGATIVE the cancel). The card grows in
     * on a spring (an Animator: the system's animation scale applies).
     */
    public static androidx.appcompat.app.AlertDialog confirm(Context c, CharSequence title, CharSequence message, CharSequence yes,
                                                            Runnable onYes) {
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(accented(c), R.style.AlertDialogTheme);
        android.graphics.drawable.GradientDrawable card = new android.graphics.drawable.GradientDrawable();
        card.setColor(0xF2262628);
        card.setCornerRadius(SettingsStyle.dp(c, 30));
        card.setStroke(Math.max(1, SettingsStyle.dp(c, .5f)), 0x24FFFFFF);
        b.setBackground(card);
        b.setTitle(title).setMessage(message)
                .setPositiveButton(yes, (d, w) -> onYes.run())
                .setNegativeButton(com.scamera.app.util.Lang.t(c, "Отмена", "Cancel"), null);
        androidx.appcompat.app.AlertDialog d = b.create();
        d.setOnShowListener(x -> styleConfirm(d));
        d.show();
        track(d);
        return d;
    }

    /** P574: the confirmations and pull-down menus open now (weak: a closed one goes by itself). */
    private static final java.util.Set<Object> SHOWN = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** Keep {@code window} (a Dialog or a PopupWindow) for {@link #dismissShown}; returns it. */
    public static <T> T track(T window) {
        if (window != null) SHOWN.add(window);
        return window;
    }

    /** Close the tracked confirmations and menus still open (their page goes: a rotation would leak their windows). */
    public static void dismissShown() {
        for (Object w : new java.util.ArrayList<>(SHOWN)) {
            try {
                if (w instanceof android.app.Dialog && ((android.app.Dialog) w).isShowing()) ((android.app.Dialog) w).dismiss();
                else if (w instanceof android.widget.PopupWindow && ((android.widget.PopupWindow) w).isShowing())
                    ((android.widget.PopupWindow) w).dismiss();
            } catch (RuntimeException ignored) { /* its window is gone already */ }
        }
        SHOWN.clear();
    }

    private static void styleConfirm(androidx.appcompat.app.AlertDialog d) {
        Context c = d.getContext();
        if (d.getWindow() != null) {
            // 290dp of card in its 16dp insets, never wider than the screen less 32dp (a narrow window, split screen)
            d.getWindow().setLayout(Math.min(SettingsStyle.dp(c, 290 + 32), c.getResources().getDisplayMetrics().widthPixels - SettingsStyle.dp(c, 32)),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            d.getWindow().setDimAmount(.45f);
        }
        android.widget.TextView title = d.findViewById(androidx.appcompat.R.id.alertTitle);
        if (title != null) {
            title.setGravity(android.view.Gravity.CENTER);
            title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(17);
            title.setTypeface(com.scamera.app.ui.style.Fonts.of(title.getContext(), 700));
            title.setMaxLines(3);
            if (title.getLayoutParams() != null) title.getLayoutParams().width = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
            // P584 (the concept's alert): 20dp over the title, 16dp at the sides, its lines 22dp
            title.setIncludeFontPadding(false);
            SettingsStyle.lineHeight(title, 22);
            View template = d.findViewById(androidx.appcompat.R.id.title_template);
            if (template != null) template.setPadding(SettingsStyle.dp(c, 16), SettingsStyle.dp(c, 20), SettingsStyle.dp(c, 16), 0);
        }
        for (int id : new int[]{androidx.appcompat.R.id.textSpacerNoTitle, androidx.appcompat.R.id.textSpacerNoButtons}) {
            View spacer = d.findViewById(id);
            if (spacer != null) spacer.setVisibility(View.GONE);
        }
        android.widget.TextView message = d.findViewById(android.R.id.message);
        if (message != null) {
            message.setGravity(android.view.Gravity.CENTER);
            message.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            message.setTextColor(0xB3FFFFFF);
            message.setTextSize(14);
            // P584: 6dp under the title, its lines 19dp
            message.setIncludeFontPadding(false);
            SettingsStyle.lineHeight(message, 19);
            message.setPadding(SettingsStyle.dp(c, 16), SettingsStyle.dp(c, 6), SettingsStyle.dp(c, 16), 0);
        }
        android.widget.Button no = d.getButton(android.content.DialogInterface.BUTTON_NEGATIVE), yes = d.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        for (android.widget.Button button : new android.widget.Button[]{no, yes}) {
            if (button == null) continue;
            button.setAllCaps(false);
            button.setTextSize(17);
            button.setTypeface(com.scamera.app.ui.style.Fonts.of(button.getContext(), 600));
            button.setTextColor(button == yes ? SettingsStyle.RED : 0xFFFFFFFF);
            button.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x1FFFFFFF),
                    SettingsStyle.shape(c, SettingsStyle.CAPSULE, 0, 23), null));
            button.setBackgroundTintList(null);
            button.setStateListAnimator(null);
            button.setMinHeight(SettingsStyle.dp(c, 46));
            button.setMinimumHeight(SettingsStyle.dp(c, 46));
            if (button instanceof com.google.android.material.button.MaterialButton) {
                ((com.google.android.material.button.MaterialButton) button).setBackgroundTintList(ColorStateList.valueOf(SettingsStyle.CAPSULE));
                ((com.google.android.material.button.MaterialButton) button).setCornerRadius(SettingsStyle.dp(c, 23));
                ((com.google.android.material.button.MaterialButton) button).setRippleColor(ColorStateList.valueOf(0x1FFFFFFF));
            }
            if (button.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) button.getLayoutParams();
                lp.width = 0;
                lp.weight = 1f;
                lp.height = SettingsStyle.dp(c, 46);
                lp.leftMargin = lp.rightMargin = SettingsStyle.dp(c, 4);
                button.setLayoutParams(lp);
            }
        }
        // the bar: the two capsules side by side, never stacked (the neutral button and the spacer are not used)
        if (yes != null && yes.getParent() instanceof LinearLayout) {
            LinearLayout bar = (LinearLayout) yes.getParent();
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setPadding(SettingsStyle.dp(c, 12), SettingsStyle.dp(c, 18), SettingsStyle.dp(c, 12), SettingsStyle.dp(c, 16));   // P584
            for (int i = 0; i < bar.getChildCount(); i++) {
                View v = bar.getChildAt(i);
                if (v != yes && v != no) v.setVisibility(View.GONE);
            }
            if (bar.getParent() instanceof android.widget.ScrollView) ((android.widget.ScrollView) bar.getParent()).setFillViewport(true);
        }
        View card = d.getWindow() == null ? null : d.getWindow().getDecorView();
        if (card != null) ConceptFonts.apply(card);   // P584: the concept's font
        if (card != null && SettingsMotion.enabled(card)) {
            card.setScaleX(.86f);
            card.setScaleY(.86f);
            card.setAlpha(0f);
            card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(350)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
        }
    }
}
