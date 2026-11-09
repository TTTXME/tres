package com.particlesdevs.photoncamera.ui.camera;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.Space;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.circularbarlib.ui.UiScale;
import com.particlesdevs.photoncamera.settings.TopRow;
import com.particlesdevs.photoncamera.ui.Motion;
import com.particlesdevs.photoncamera.ui.camera.views.settingsbar.ShadeStyle;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * P574 (the approved concept, screen «edit»: «свайп сверху вниз» or the top grabber): the «Верхняя строка» sheet - a card from the
 * top with «Отмена» / «Готово», the row as it will be (each item with a «−» badge that takes it out, but the format and the LUT: TopRow.REQUIRED), «ДОБАВИТЬ» with every item
 * the open camera can show (the ones already in the row dimmed) and «Вернуть основные». Nothing is stored before «Готово»;
 * «Отмена», a tap beside the card, a swipe up on it (the opposite of the swipe that opened it), the grabber and Back leave the row
 * as it was. The list is TopRow's (ui_top_row).
 */
public final class TopRowSheet {
    private TopRowSheet() {}

    /** The concept's sheet and row colours, the «−» badge's grey. */
    static final int SHEET = 0xFF1C1C1E, ROW = 0xFF2C2C2E, BADGE = 0xFF8E8E93;

    /** The editing state: the row while the sheet is open. */
    static final class Editor {
        final List<String> items;
        final Predicate<String> available;

        Editor(List<String> now, Predicate<String> available) {
            this.items = new ArrayList<>(TopRow.clean(now));
            this.available = available;
        }

        /** Takes {@code id} out of the row; true when it was there (never a {@link TopRow#REQUIRED} one: LUT, format). */
        boolean remove(String id) {
            return !TopRow.required(id) && items.remove(id);
        }

        /** Puts {@code id} at the row's end; false when it is there already, the row is full or the camera cannot show it. */
        boolean add(String id) {
            if (items.contains(id) || items.size() >= TopRow.MAX || !TopRow.ALL.contains(id) || !available.test(id)) return false;
            items.add(id);
            return true;
        }

        /** «Вернуть основные»: the default row. */
        void reset() {
            items.clear();
            items.addAll(TopRow.DEFAULT);
        }

        /** The items «ДОБАВИТЬ» offers: every one this camera can show, in TopRow's order. */
        List<String> library() {
            List<String> out = new ArrayList<>();
            for (String id : TopRow.ALL) if (available.test(id)) out.add(id);
            return out;
        }
    }

    /** The sheet's view: the card (built again on every change); {@code done} hears the new row, {@code cancel} closes it. */
    static LinearLayout build(Context c, Editor editor, Consumer<List<String>> done, Runnable cancel) {
        final LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setTag("top_row_sheet");
        // P583 (the concept's edit sheet): padding 14 14 8, 36dp corners, #1C1C1E
        final int pad = UiScale.dp(c, 14);
        card.setPadding(pad, pad, pad, UiScale.dp(c, 8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SHEET);
        bg.setCornerRadius(UiScale.dpF(c, 36));
        card.setBackground(bg);
        card.setElevation(UiScale.dpF(c, 18));
        card.setClickable(true);
        card.setContentDescription(Lang.t(c, "Верхняя строка", "Top row"));
        fill(card, editor, done, cancel);
        return card;
    }

    /** The concept's gap between the sheet's parts (flex gap: 14px). */
    static final float GAP = 14f;

    private static void fill(LinearLayout card, Editor editor, Consumer<List<String>> done, Runnable cancel) {
        final Context c = card.getContext();
        final int accent = ShadeStyle.accent(c);
        card.removeAllViews();
        final Runnable again = () -> fill(card, editor, done, cancel);
        // the head: «Отмена» · «Верхняя строка» · «Готово» - P583 (owner: «не отцентрованы названия»): the title centred on the
        // card whatever the two buttons' widths (a frame: the buttons at its ends, the title in its middle between them)
        FrameLayout head = new FrameLayout(c);
        head.setTag("top_row_head");
        TextView no = pill(c, Lang.t(c, "Отмена", "Cancel"), ROW, ShadeStyle.TEXT, false);
        no.setTag("top_row_cancel");
        no.setOnClickListener(v -> cancel.run());
        head.addView(no, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UiScale.dp(c, 34), Gravity.START | Gravity.CENTER_VERTICAL));
        TextView yes = pill(c, Lang.t(c, "Готово", "Done"), accent, ShadeStyle.onAccent(accent), true);
        yes.setTag("top_row_done");
        yes.setOnClickListener(v -> done.accept(new ArrayList<>(editor.items)));
        head.addView(yes, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UiScale.dp(c, 34), Gravity.END | Gravity.CENTER_VERTICAL));
        final TextView title = text(c, Lang.t(c, "Верхняя строка", "Top row"), 15, ShadeStyle.TEXT, com.particlesdevs.photoncamera.ui.style.Fonts.SEMIBOLD);
        title.setTag("top_row_title");
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        ViewCompat.setAccessibilityHeading(title, true);
        head.addView(title, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        // a long title never runs under the buttons: it keeps the room beside the wider one on both sides (still centred)
        head.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            final int side = Math.max(no.getWidth(), yes.getWidth()) + UiScale.dp(c, 6);
            final int room = Math.max(0, (r - l) - 2 * side);
            if (title.getMaxWidth() != room && room > 0) {
                title.setMaxWidth(room);
                v.post(title::requestLayout);
            }
        });
        card.addView(head, matchWrap());
        // the row as it will be, each item with its «−» badge: 38dp slots 12dp apart, centred in a 50dp black capsule
        LinearLayout strip = new LinearLayout(c);
        strip.setTag("top_row_preview");
        strip.setGravity(Gravity.CENTER);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(0xFF000000);
        sb.setCornerRadius(UiScale.dpF(c, 25));
        sb.setStroke(Math.max(1, Math.round(c.getResources().getDisplayMetrics().density)), 0x2EFFFFFF);
        strip.setBackground(sb);
        strip.setMinimumHeight(UiScale.dp(c, 50));
        if (editor.items.isEmpty()) {
            TextView empty = text(c, Lang.t(c, "Пусто — добавьте ниже", "Empty: add below"), 13, ShadeStyle.MUTED, com.particlesdevs.photoncamera.ui.style.Fonts.REGULAR);
            strip.addView(empty);
        }
        for (String id : editor.items) {
            FrameLayout slot = new FrameLayout(c);
            slot.setClipChildren(false);
            slot.setClipToPadding(false);
            slot.setTag("top_row_item_" + id);
            View glyph = glyph(c, id, 20, ShadeStyle.TEXT);
            // the glyph centred on the slot whatever its own width (the format's «JPEG» runs past the 38dp, as in the concept)
            FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(UiScale.dp(c, glyph instanceof ImageView ? 38 : 64),
                    UiScale.dp(c, 38), Gravity.CENTER);
            slot.addView(glyph, gp);
            slot.setContentDescription(name(c, id));
            // kept in the row, hidden on this camera: the glyph dimmed (not the slot: its layer would cut the badge off)
            if (!TopBar.available(id)) glyph.setAlpha(.45f);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(UiScale.dp(c, 38), UiScale.dp(c, 38));
            if (strip.getChildCount() > 0) sl.setMarginStart(UiScale.dp(c, 12));
            strip.addView(slot, sl);
            // the format and the LUT stay (their sheet and tray open only from the row): no «−»
            if (TopRow.required(id)) continue;
            ImageView minus = new ImageView(c);
            minus.setTag("top_row_remove_" + id);
            ConceptStyle.icon(minus, R.drawable.cv_minus, SHEET);
            GradientDrawable mb = new GradientDrawable();
            mb.setShape(GradientDrawable.OVAL);
            mb.setColor(BADGE);
            minus.setBackground(mb);
            // the concept: an 11px glyph in the 20px disc
            final int mp = Math.round(UiScale.dpF(c, 4.5f));
            minus.setPadding(mp, mp, mp, mp);
            minus.setScaleType(ImageView.ScaleType.FIT_CENTER);
            minus.setContentDescription(Lang.t(c, "Убрать: ", "Remove: ") + name(c, id));
            minus.setOnClickListener(v -> {
                if (editor.remove(id)) {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                    again.run();
                }
            });
            // right: -6px, top: -6px of the 38dp slot, the same on every item
            FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(UiScale.dp(c, 20), UiScale.dp(c, 20), Gravity.TOP | Gravity.START);
            ml.leftMargin = UiScale.dp(c, 38) - UiScale.dp(c, 20) + UiScale.dp(c, 6);
            ml.topMargin = -UiScale.dp(c, 6);
            slot.addView(minus, ml);
        }
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiScale.dp(c, 50));
        stp.topMargin = UiScale.dp(c, GAP);
        card.addView(strip, stp);
        // «ДОБАВИТЬ»: every item this camera can show, the ones in the row dimmed (12px semibold, 1px letter spacing, white 60 %)
        TextView add = text(c, Lang.t(c, "Добавить", "Add"), 12, 0x99FFFFFF, com.particlesdevs.photoncamera.ui.style.Fonts.SEMIBOLD);
        add.setAllCaps(true);
        add.setLetterSpacing(1f / 12f);
        add.setPaddingRelative(UiScale.dp(c, 6), 0, 0, 0);
        LinearLayout.LayoutParams ap = matchWrap();
        ap.topMargin = UiScale.dp(c, GAP);
        card.addView(add, ap);
        GridLayout grid = new GridLayout(c);
        grid.setTag("top_row_library");
        grid.setColumnCount(5);
        final boolean full = editor.items.size() >= TopRow.MAX;
        final List<String> library = editor.library();
        for (int i = 0; i < library.size(); i++) {
            final String id = library.get(i);
            final boolean in = editor.items.contains(id);
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setTag("top_row_add_" + id);
            FrameLayout disc = new FrameLayout(c);
            GradientDrawable db = new GradientDrawable();
            db.setShape(GradientDrawable.OVAL);
            db.setColor(ROW);
            disc.setBackground(db);
            View g = glyph(c, id, 21, ShadeStyle.TEXT, true);
            disc.addView(g, new FrameLayout.LayoutParams(UiScale.dp(c, 48), UiScale.dp(c, 48), Gravity.CENTER));
            disc.setClipChildren(false);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(UiScale.dp(c, 48), UiScale.dp(c, 48));
            dp.gravity = Gravity.CENTER_HORIZONTAL;
            cell.addView(disc, dp);
            // the caption centred under its disc (the cell's full width, centred text)
            TextView label = text(c, label(c, id), 10, ShadeStyle.TEXT, com.particlesdevs.photoncamera.ui.style.Fonts.SEMIBOLD);
            label.setGravity(Gravity.CENTER);
            label.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = UiScale.dp(c, 5);
            cell.addView(label, lp);
            cell.setAlpha(in || full ? .35f : 1f);
            cell.setEnabled(!in && !full);
            cell.setContentDescription(Lang.t(c, "Добавить: ", "Add: ") + name(c, id));
            if (in) ViewCompat.setStateDescription(cell, Lang.t(c, "уже в строке", "already in the row"));
            else if (full) ViewCompat.setStateDescription(cell, Lang.t(c, "строка заполнена", "the row is full"));
            cell.setOnClickListener(v -> {
                if (editor.add(id)) {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                    again.run();
                    pop(card.findViewWithTag("top_row_item_" + id));
                }
            });
            Motion.press(cell);
            // five equal columns 4dp apart, rows 12dp apart (the concept's grid gap 12px 4px)
            GridLayout.LayoutParams gp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f));
            gp.width = 0;
            final int col = i % 5;
            gp.setMarginStart(col == 0 ? 0 : UiScale.dp(c, 2));
            gp.setMarginEnd(col == 4 ? 0 : UiScale.dp(c, 2));
            gp.topMargin = i >= 5 ? UiScale.dp(c, 12) : 0;
            grid.addView(cell, gp);
        }
        // a short last row keeps the five columns' widths (empty cells hold them)
        for (int i = library.size(); i < 5; i++) {
            Space space = new Space(c);
            GridLayout.LayoutParams gp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
            gp.width = 0;
            grid.addView(space, gp);
        }
        LinearLayout.LayoutParams gl = matchWrap();
        gl.topMargin = UiScale.dp(c, GAP);
        card.addView(grid, gl);
        // «Вернуть основные» (34dp, 14px semibold, the accent)
        TextView reset = text(c, Lang.t(c, "Вернуть основные", "Back to the defaults"), 14, accent, com.particlesdevs.photoncamera.ui.style.Fonts.SEMIBOLD);
        reset.setTag("top_row_reset");
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(UiScale.dp(c, 16), 0, UiScale.dp(c, 16), 0);
        reset.setBackground(ShadeStyle.ripple(c, 17));
        reset.setOnClickListener(v -> {
            editor.reset();
            again.run();
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UiScale.dp(c, 34));
        rp.gravity = Gravity.CENTER_HORIZONTAL;
        rp.topMargin = UiScale.dp(c, GAP);
        card.addView(reset, rp);
        // the grabber (44 x 12, a 36 x 5 bar at 30 %): a tap (or a swipe up on the card) closes it as «Отмена»
        FrameLayout grab = new FrameLayout(c);
        grab.setTag("top_row_grab");
        grab.setContentDescription(Lang.t(c, "Закрыть", "Close"));
        View bar = new View(c);
        GradientDrawable gb = new GradientDrawable();
        gb.setColor(ConceptStyle.SHEET_GRIP);
        gb.setCornerRadius(UiScale.dpF(c, 2.5f));
        bar.setBackground(gb);
        grab.addView(bar, new FrameLayout.LayoutParams(UiScale.dp(c, 36), UiScale.dp(c, 5), Gravity.CENTER));
        grab.setOnClickListener(v -> cancel.run());
        TouchTargets.ensure(grab, UiScale.dp(c, 32));
        LinearLayout.LayoutParams gbp = new LinearLayout.LayoutParams(UiScale.dp(c, 44), UiScale.dp(c, 12));
        gbp.gravity = Gravity.CENTER_HORIZONTAL;
        gbp.topMargin = UiScale.dp(c, GAP);
        card.addView(grab, gbp);
    }

    /**
     * The open sheet (P574, owner 2026-11-09: «верхняя и нижняя шторка в открытом состоянии закрываются противоположными
     * жестами»): a full-screen focusable window over the camera screen, so while it is up (and while it leaves) no touch reaches
     * the viewfinder or the quick-settings shade under it. It closes with a swipe up (the card follows the finger; a fling up or
     * more than half its height closes it, less springs back), «Отмена», «Готово», a tap beside the card, the grabber and Back.
     */
    public static final class Handle {
        final PopupWindow window;
        final View scrim;
        final FrameLayout watcher;
        LinearLayout card;
        boolean closing;

        Handle(PopupWindow window, View scrim, FrameLayout watcher) {
            this.window = window;
            this.scrim = scrim;
            this.watcher = watcher;
        }

        /** Up and not on its way out. */
        public boolean isOpen() {
            return window.isShowing() && !closing;
        }

        /** Up or still leaving (its window takes the touches until it is gone). */
        public boolean isShowing() {
            return window.isShowing();
        }

        /** Closes it as «Отмена» (the card slides up from where it is); a second call while it leaves does nothing. */
        public void close() {
            if (closing) return;
            closing = true;
            final Context c = watcher.getContext();
            if (!ValueAnimator.areAnimatorsEnabled() || card.getHeight() == 0 || !watcher.isAttachedToWindow()) {
                safeDismiss(window);
                return;
            }
            scrim.animate().cancel();
            scrim.animate().alpha(0f).setDuration(200).start();
            card.animate().cancel();
            card.animate().translationY(-card.getHeight() - UiScale.dpF(c, 16)).setDuration(240).setInterpolator(Motion.EASE_OUT)
                    .withEndAction(() -> safeDismiss(window)).start();
        }

        /** Gone at once, no motion (the camera screen pauses or goes). */
        public void dismissNow() {
            closing = true;
            card.animate().cancel();
            scrim.animate().cancel();
            safeDismiss(window);
        }

        /** The card's offset while a finger drags it (tests). */
        float offset() {
            return card.getTranslationY();
        }
    }

    /** A fling up faster than this (dp/s) closes the sheet whatever its offset. */
    static final float CLOSE_FLING_DP = 700f;

    /**
     * Shows the sheet over {@code root} (the camera screen): the card slides down from the top on the spring, the screen behind it
     * dims; {@code done} hears the new row after «Готово». Null when the root is not on screen.
     */
    @Nullable
    public static Handle show(View root, List<String> current, Predicate<String> available, Consumer<List<String>> done) {
        if (root.getWindowToken() == null) return null;
        final Context c = root.getContext();
        final FrameLayout frame = new FrameLayout(c);
        final View scrim = new View(c);
        scrim.setBackgroundColor(0x73000000);
        scrim.setContentDescription(Lang.t(c, "Закрыть", "Close"));
        frame.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final PopupWindow pw = new PopupWindow(frame, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true);
        pw.setBackgroundDrawable(new ColorDrawable(0));
        pw.setAnimationStyle(0);
        pw.setClippingEnabled(false);
        final Handle[] h = new Handle[1];
        final FrameLayout watcher = new DragFrame(c, () -> h[0]);
        h[0] = new Handle(pw, scrim, watcher);
        final Handle handle = h[0];
        handle.card = build(c, new Editor(current, available), items -> {
            if (handle.closing) return;
            done.accept(items);
            handle.close();
        }, handle::close);
        scrim.setOnClickListener(v -> handle.close());
        watcher.addView(handle.card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        final int m = UiScale.dp(c, 8);
        wl.setMargins(m, m, m, 0);
        frame.addView(watcher, wl);
        // Back (the focusable window's own) and any other dismissal: closed for good
        pw.setOnDismissListener(() -> handle.closing = true);
        pw.showAtLocation(root, Gravity.NO_GRAVITY, 0, 0);
        if (ValueAnimator.areAnimatorsEnabled()) {
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(220).start();
            handle.card.setAlpha(0f);
            handle.card.post(() -> {
                if (handle.closing) return;
                handle.card.setTranslationY(-handle.card.getHeight() - UiScale.dpF(c, 16));
                handle.card.setAlpha(1f);
                handle.card.animate().translationY(0f).setDuration(Motion.ENTER.durationMs).setInterpolator(Motion.ENTER).start();
            });
        }
        return handle;
    }

    /**
     * The card's frame: it watches every touch on the card (its buttons keep their taps) and takes over a vertical drag up - the
     * card follows the finger (not below its place), the scrim fades with it; let go, a fling up or more than half the card's
     * height closes the sheet, less springs it back. A drag down does nothing.
     */
    static final class DragFrame extends FrameLayout {
        private final java.util.function.Supplier<Handle> handle;
        private final int slop;
        private float downX, downY;
        private boolean dragging;
        @Nullable private android.view.VelocityTracker velocity;

        DragFrame(Context c, java.util.function.Supplier<Handle> handle) {
            super(c);
            this.handle = handle;
            slop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            track(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    dragging = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (startsDrag(e)) {
                        dragging = true;
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            final Handle h = handle.get();
            if (h == null || h.closing) return true;
            if (e.getActionMasked() != MotionEvent.ACTION_DOWN) track(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    track(e);
                    downX = e.getX();
                    downY = e.getY();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && startsDrag(e)) dragging = true;
                    if (dragging) follow(h, e.getY() - downY);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) release(h, e.getY() - downY, false);
                    endTrack();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) release(h, e.getY() - downY, true);
                    endTrack();
                    dragging = false;
                    return true;
                default:
                    return true;
            }
        }

        private boolean startsDrag(MotionEvent e) {
            final float dy = e.getY() - downY, dx = e.getX() - downX;
            return dy < -slop && Math.abs(dy) > Math.abs(dx);
        }

        private void follow(Handle h, float dy) {
            final float y = Math.min(0f, dy);
            h.card.animate().cancel();
            h.card.setTranslationY(y);
            final float k = h.card.getHeight() > 0 ? Math.min(1f, -y / h.card.getHeight()) : 0f;
            h.scrim.animate().cancel();
            h.scrim.setAlpha(1f - k);
        }

        private void release(Handle h, float dy, boolean cancelled) {
            float vy = 0f;
            if (velocity != null) {
                velocity.computeCurrentVelocity(1000);
                vy = velocity.getYVelocity();
            }
            final float density = getResources().getDisplayMetrics().density;
            final boolean far = h.card.getHeight() > 0 && -Math.min(0f, dy) > h.card.getHeight() / 2f;
            if (!cancelled && (far || vy < -CLOSE_FLING_DP * density && dy < 0)) {
                h.close();
                return;
            }
            // not far enough: back on the spring
            h.card.animate().translationY(0f).setDuration(Motion.ENTER.durationMs).setInterpolator(Motion.ENTER).start();
            h.scrim.animate().alpha(1f).setDuration(200).start();
        }

        private void track(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) endTrack();
            if (velocity == null) velocity = android.view.VelocityTracker.obtain();
            velocity.addMovement(e);
        }

        private void endTrack() {
            if (velocity != null) {
                velocity.recycle();
                velocity = null;
            }
        }
    }

    private static void safeDismiss(PopupWindow pw) {
        try {
            if (pw.isShowing()) pw.dismiss();
        } catch (IllegalArgumentException ignored) {
            // the window went with its activity
        }
    }

    /** The item that just came in grows into its place. */
    private static void pop(@Nullable View v) {
        if (v == null || !ValueAnimator.areAnimatorsEnabled()) return;
        v.setScaleX(.5f);
        v.setScaleY(.5f);
        v.setAlpha(0f);
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(Motion.ENTER.durationMs).setInterpolator(Motion.ENTER).start();
    }

    /** An item's glyph as the top row draws it: its icon, or its text (the format over the size, DCG). */
    static View glyph(Context c, String id, int iconDp, int ink) {
        return glyph(c, id, iconDp, ink, false);
    }

    /**
     * {@link #glyph}; {@code small}: the library's disc (the concept: 11px over 8px there, 12px over 9px in the row). P583: the
     * concept's line icons (cv_*) and its text in Roboto Flex.
     */
    static View glyph(Context c, String id, int iconDp, int ink, boolean small) {
        final int icon = icon(id);
        if (icon != 0) {
            ImageView v = new ImageView(c);
            ConceptStyle.icon(v, icon, ink);
            v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            final int box = UiScale.dp(c, small ? 48 : 38), p = Math.max(0, (box - UiScale.dp(c, iconDp)) / 2);
            v.setPadding(p, p, p, p);
            v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            return v;
        }
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        String main, sub = null;
        if (TopRow.FORMAT.equals(id)) {
            main = formatText();
            sub = sizeText();
        } else main = TopRow.DCG.equals(id) ? "DCG" : "UHDR";
        TextView t = text(c, main, (small ? 11 : 12) - (main.length() > 5 ? 2 : 0), ink, com.particlesdevs.photoncamera.ui.style.Fonts.BOLD);
        t.setIncludeFontPadding(false);
        t.setMaxLines(1);
        t.setGravity(Gravity.CENTER);
        box.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (sub != null) {
            TextView s = text(c, sub, small ? 8 : 9, ink, com.particlesdevs.photoncamera.ui.style.Fonts.SEMIBOLD);
            s.setAlpha(.85f);
            s.setIncludeFontPadding(false);
            s.setMaxLines(1);
            s.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.topMargin = UiScale.dp(c, 3);
            box.addView(s, sp);
        }
        return box;
    }

    private static String formatText() {
        try {
            return com.particlesdevs.photoncamera.settings.FormatChoice.current().longLabel().replace(" + ", "+");
        } catch (RuntimeException e) {
            return "JPEG";
        }
    }

    private static String sizeText() {
        try {
            return PhotoSize.badge();
        } catch (RuntimeException e) {
            return "";
        }
    }

    @DrawableRes
    static int icon(String id) {
        switch (id) {
            case TopRow.TORCH: return R.drawable.cv_bolt;
            case TopRow.TIMER: return R.drawable.cv_timer;
            case TopRow.LUT: return R.drawable.cv_lut;
            case TopRow.GRID: return R.drawable.cv_grid;
            case TopRow.HORIZON: return R.drawable.cv_level;
            case TopRow.UHDR: return R.drawable.cv_sparkle;
            default: return 0;
        }
    }

    /** The item's caption under its disc in «ДОБАВИТЬ». */
    static String label(Context c, String id) {
        switch (id) {
            case TopRow.FORMAT: return Lang.t(c, "Формат", "Format");
            case TopRow.TORCH: return Lang.t(c, "Вспышка", "Flash"); // P590: off / auto / on / torch
            case TopRow.TIMER: return Lang.t(c, "Таймер", "Timer");
            case TopRow.LUT: return "LUT";
            case TopRow.DCG: return "DCG";
            case TopRow.GRID: return Lang.t(c, "Сетка", "Grid");
            case TopRow.HORIZON: return Lang.t(c, "Горизонт", "Level");
            default: return "UHDR";
        }
    }

    /** The item's full name (read out). */
    static String name(Context c, String id) {
        switch (id) {
            case TopRow.FORMAT: return Lang.t(c, "Формат и размер", "Format and size");
            case TopRow.LUT: return Lang.t(c, "LUT и плёнка", "LUT and film");
            case TopRow.DCG: return Lang.t("DCG 14 бит", "DCG 14-bit");
            case TopRow.UHDR: return "Ultra HDR";
            default: return label(c, id);
        }
    }

    /** The head's buttons: 34dp capsules, 15px - «Отмена» regular on #2C2C2E (14px sides), «Готово» heavy on the accent (16px sides). */
    private static TextView pill(Context c, CharSequence s, int fill, int ink, boolean bold) {
        TextView t = text(c, s, 15, ink, bold ? com.particlesdevs.photoncamera.ui.style.Fonts.BOLD : com.particlesdevs.photoncamera.ui.style.Fonts.REGULAR);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(1);
        t.setPadding(UiScale.dp(c, bold ? 16 : 14), 0, UiScale.dp(c, bold ? 16 : 14), 0);
        t.setBackground(ShadeStyle.pressable(c, fill, 0, 17));
        Motion.press(t);
        return t;
    }

    /** The concept's text: {@code px} as dp times the chrome's scale, Roboto Flex at {@code weight}. */
    private static TextView text(Context c, CharSequence s, float px, int colour, int weight) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(colour);
        t.setIncludeFontPadding(false);
        ConceptStyle.text(t, px, weight);
        return t;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
