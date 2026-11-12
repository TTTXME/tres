package com.particlesdevs.photoncamera.gallery.ui;

import android.content.Context;
import android.graphics.Outline;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.particlesdevs.photoncamera.gallery.glide.GalleryKeys;
import com.bumptech.glide.Glide;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.gallery.model.GalleryItem;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.List;

/**
 * SIMPLE_UI §5 (the concept's viewer): over a dark gradient at the top the back disc and two lines - when («Сегодня,
 * 11:17») and with what («24 мм · 12 Мп · JPEG»), an Ultra HDR photo's «HDR: вкл. / выкл.» pill at the right; at the bottom one
 * bar of five labelled buttons «Поделиться · Изменить · Сравнить · Удалить · Сведения» (owner, 2026-10-18: «вместо сведения
 * поставь кнопку удалить, а вместо ещё сведения» - no «Ещё» menu any more).
 * The filmstrip of P59b stays built for the swipe position but is not shown (the concept has none). Built here so the
 * fragment and the 360dp layout test share them.
 * P579 (owner, 2026-11-09: «Не забудь что у нас есть галерея и в ней надо стиль также обновить»): the camera's look over a photo -
 * round buttons (the back 40dp, the three beside the meta 36dp) and one bottom capsule as dark bars (rgba(20, 20, 22, .9) with a 0.5dp light edge; less glass than before),
 * white line icons with 60 % white labels, «Удалить» in the red, the HDR pill a dark capsule with the accent when on.
 */
public final class ViewerChrome {
    /** The concept's .vbar; P579: the camera's dark bar (rgba(20, 20, 22, .9)). */
    static final int BAR_FILL = GalleryUi.BAR;
    public final LinearLayout top, bottom;
    public final View back;
    public final TextView metaTitle, metaSub;
    public final TextView hdrPill;
    /** P492: the histogram switch (a round icon button beside the HDR pill) and its icon. */
    public final View histButton;
    private final ImageView histIcon;
    /** P498: «Избранное» (the heart) and «Ещё» (slideshow, set as, open with) at the right of the top row. */
    public final View fav, more;
    private final ImageView favIcon;
    private boolean favOn;
    public final RecyclerView strip;
    public final Filmstrip filmstrip;
    public final LinearLayout buttons;
    public final View share, edit, compare, delete, info;

    public interface Actions {
        void back();

        void share();

        void edit();

        void compare();

        void info();

        void delete();

        void hdr();

        /** P492: the histogram overlay on / off. */
        default void histogram() {}

        /** P498: the shown photo into / out of the favourites. */
        default void favourite() {}

        /** P498: the «Ещё» sheet. */
        default void more() {}

        void openPosition(int position);
    }

    public ViewerChrome(Context c, Actions a) {
        int accent = GalleryUi.accent(c);
        top = new LinearLayout(c);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(GalleryUi.dp(c, 12), GalleryUi.dp(c, 14), GalleryUi.dp(c, 12), GalleryUi.dp(c, 24));
        top.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0x00000000}));
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        back = disc(c, R.drawable.ui_ic_back, Lang.t("Назад к сетке", "Back to the grid"), v -> a.back());
        row.addView(back, new LinearLayout.LayoutParams(GalleryUi.dp(c, 40), GalleryUi.dp(c, 40)));
        LinearLayout meta = new LinearLayout(c);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        metaTitle = GalleryUi.fitText(c, "", 15, GalleryUi.TEXT);
        metaTitle.setTypeface(com.particlesdevs.photoncamera.ui.style.Fonts.of(metaTitle.getContext(), 600));
        metaTitle.setShadowLayer(GalleryUi.dp(c, 3), 0, 0, 0x66000000);
        metaSub = GalleryUi.fitText(c, "", 13, 0xB3FFFFFF);
        metaSub.setShadowLayer(GalleryUi.dp(c, 3), 0, 0, 0x66000000);
        meta.addView(metaTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = GalleryUi.dp(c, 3);
        meta.addView(metaSub, sp);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        mp.setMarginStart(GalleryUi.dp(c, 12));
        row.addView(meta, mp);
        // the Ultra HDR switch: a pill at the right of the top row (Ultra HDR photos only)
        hdrPill = GalleryUi.text(c, "", 12.5f, accent);
        hdrPill.setTypeface(com.particlesdevs.photoncamera.ui.style.Fonts.of(hdrPill.getContext(), 600));
        hdrPill.setGravity(Gravity.CENTER);
        hdrPill.setMinHeight(GalleryUi.dp(c, 36));
        hdrPill.setPadding(GalleryUi.dp(c, 12), 0, GalleryUi.dp(c, 12), 0);
        hdrPill.setBackground(GalleryUi.pressable(GalleryUi.bar(c, 18)));
        hdrPill.setClickable(true);
        hdrPill.setFocusable(true);
        hdrPill.setOnClickListener(v -> a.hdr());
        hdrPill.setTag("viewer_hdr");
        hdrPill.setVisibility(View.GONE);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.setMarginStart(GalleryUi.dp(c, 8));
        row.addView(hdrPill, hp);
        // P492 (owner, 2026-11-03: «добавь кнопку вывода гистограммы, при активации гистограмма должна отображаться постоянно пока ты не
        // деактивируешь»): a pill beside the HDR one; on, the histogram stays over every photo until it is switched off
        histButton = disc(c, R.drawable.gallery_ic_histogram, Lang.t("Гистограмма", "Histogram"), v -> a.histogram());
        histButton.setTag("viewer_histogram");
        histIcon = (ImageView) ((FrameLayout) histButton).getChildAt(0);
        setHistogram(false); // P570: off, tonal mono
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(GalleryUi.dp(c, 36), GalleryUi.dp(c, 36)); // the meta keeps its room
        gp.setMarginStart(GalleryUi.dp(c, 6));
        row.addView(histButton, gp);
        fav = disc(c, R.drawable.ui_ic_heart, Lang.t("В избранное", "Add to favourites"), v -> a.favourite());
        fav.setTag("viewer_favourite");
        favIcon = (ImageView) ((FrameLayout) fav).getChildAt(0);
        com.particlesdevs.photoncamera.ui.Icons.own(favIcon); // P570: the colour heart
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(GalleryUi.dp(c, 36), GalleryUi.dp(c, 36)); // the meta keeps its room
        fp.setMarginStart(GalleryUi.dp(c, 6));
        row.addView(fav, fp);
        more = disc(c, R.drawable.ui_ic_more, Lang.t("Ещё", "More"), v -> a.more());
        more.setTag("viewer_more");
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(GalleryUi.dp(c, 36), GalleryUi.dp(c, 36)); // the meta keeps its room
        op.setMarginStart(GalleryUi.dp(c, 6));
        row.addView(more, op);
        top.addView(row);

        bottom = new LinearLayout(c);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(GalleryUi.dp(c, 6), 0, GalleryUi.dp(c, 6), GalleryUi.dp(c, 16));
        strip = new RecyclerView(c);
        strip.setLayoutManager(new LinearLayoutManager(c, LinearLayoutManager.HORIZONTAL, false));
        filmstrip = new Filmstrip(a::openPosition);
        strip.setAdapter(filmstrip);
        strip.setVisibility(View.GONE);
        bottom.addView(strip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setBackground(GalleryUi.bar(c, 26));
        buttons.setPadding(GalleryUi.dp(c, 4), GalleryUi.dp(c, 4), GalleryUi.dp(c, 4), GalleryUi.dp(c, 4));
        share = barButton(c, R.drawable.ui_ic_share, Lang.t("Поделиться", "Share"), v -> a.share());
        edit = barButton(c, R.drawable.ui_ic_edit, Lang.t("Изменить", "Edit"), v -> a.edit());
        compare = barButton(c, R.drawable.ui_ic_compare, Lang.t("Сравнить", "Compare"), v -> a.compare());
        compare.setContentDescription(Lang.t("Сравнить с соседним", "Compare with the next photo"));
        delete = barButton(c, R.drawable.ui_ic_trash, Lang.t("Удалить", "Delete"), GalleryUi.WARN, v -> a.delete());
        delete.setTag("viewer_delete");
        info = barButton(c, R.drawable.ui_ic_info, Lang.t("Сведения", "Details"), v -> a.info());
        // (owner, 2026-11-05: the bottom bars «гораздо более компактными» in both orientations - a centred capsule of 72dp buttons)
        int widthDp = c.getResources().getConfiguration().screenWidthDp;
        int each = GalleryUi.dp(c, Math.max(56, Math.min(72, (widthDp - 24) / 5)));
        for (View b : new View[]{share, edit, compare, delete, info})
            buttons.addView(b, new LinearLayout.LayoutParams(each, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.CENTER_HORIZONTAL;
        bottom.addView(buttons, bp);
    }

    /** The concept's .back; P579: a 40dp round dark bar (its light edge) with a white line icon, over the photo. */
    private static View disc(Context c, int icon, String description, View.OnClickListener click) {
        FrameLayout b = new FrameLayout(c);
        android.graphics.drawable.GradientDrawable d = GalleryUi.bar(c, 20);
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        b.setBackground(GalleryUi.pressable(d));
        b.setContentDescription(description);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(click);
        com.particlesdevs.photoncamera.ui.Motion.press(b);
        ImageView i = GalleryUi.icon(c, icon, GalleryUi.TEXT, 22);
        com.particlesdevs.photoncamera.ui.Icons.ink(i, icon, GalleryUi.TEXT); // P579: white (tonal mono for a colour icon)
        b.addView(i, new FrameLayout.LayoutParams(GalleryUi.dp(c, 22), GalleryUi.dp(c, 22), Gravity.CENTER));
        return b;
    }

    /** A button of the concept's .vbar: the line icon over a 10.5sp label (P579: a white icon, the label at 60 % white). */
    private static View barButton(Context c, int icon, String label, View.OnClickListener click) {
        return barButton(c, icon, label, GalleryUi.TEXT, click);
    }

    /** {@link #barButton(Context, int, String, View.OnClickListener)} in {@code color} («Удалить»: the warning colour). */
    private static View barButton(Context c, int icon, String label, int color, View.OnClickListener click) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, GalleryUi.dp(c, 8), 0, GalleryUi.dp(c, 8));
        b.setBackground(GalleryUi.pressable(GalleryUi.round(c, android.graphics.Color.TRANSPARENT, 0, 22)));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(click);
        b.setContentDescription(label);
        com.particlesdevs.photoncamera.ui.Motion.press(b);
        ImageView i = GalleryUi.icon(c, icon, color, 22);
        com.particlesdevs.photoncamera.ui.Icons.ink(i, icon, color);
        b.addView(i);
        TextView t = GalleryUi.fitText(c, label, 10.5f, color == GalleryUi.TEXT ? 0xB3FFFFFF : color);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = GalleryUi.dp(c, 3);
        b.addView(t, lp);
        return b;
    }

    /** The two lines at the top; {@code route} and {@code format} stay for the callers (the format is in {@code sub}). */
    public void bindMeta(String title, String sub, @Nullable String route, GalleryFormat format, boolean ultraHdr) {
        metaTitle.setText(title);
        metaSub.setText(sub);
    }

    /** P498: the heart filled (pink) for a favourite; a change pops it. */
    public void setFavourite(boolean on, boolean animate) {
        boolean changed = on != favOn;
        favOn = on;
        favIcon.setImageResource(on ? R.drawable.ui_ic_heart_on : R.drawable.ui_ic_heart);
        com.particlesdevs.photoncamera.ui.Icons.own(favIcon); // P570: the pink heart, the filled red one for a favourite
        fav.setContentDescription(on ? Lang.t("Убрать из избранного", "Remove from favourites") : Lang.t("В избранное", "Add to favourites"));
        if (changed && animate) com.particlesdevs.photoncamera.ui.Motion.pop(favIcon);
    }

    /** The favourites' pink (the grid's heart badge too). */
    public static final int FAV = 0xFFFF6B81;

    /** P498: a video page: no «Изменить» (the editor takes photos). */
    public void setVideo(boolean video) {
        edit.setVisibility(video ? View.GONE : View.VISIBLE);
    }

    /** P492: the histogram switch's state (accent when the overlay is on). */
    public void setHistogram(boolean on) {
        com.particlesdevs.photoncamera.ui.Icons.state(histIcon, on, GalleryUi.accent(histIcon.getContext()), GalleryUi.TEXT); // P570
        // P579: on, the accent (tonal mono for a colour icon) - the camera's toggles are the accent when on
        if (on && com.particlesdevs.photoncamera.ui.Icons.colour(histIcon))
            histIcon.setColorFilter(com.particlesdevs.photoncamera.ui.Icons.mono(GalleryUi.accent(histIcon.getContext())));
        androidx.core.view.ViewCompat.setStateDescription(histButton, on ? Lang.t("включено", "on") : Lang.t("выключено", "off"));
    }

    /** «HDR: вкл. / выкл.» pill at the top right for an Ultra HDR photo; hidden otherwise. */
    public void bindHdr(boolean ultraHdr, boolean on) {
        hdrPill.setVisibility(ultraHdr ? View.VISIBLE : View.GONE);
        hdrPill.setText(on ? Lang.t("HDR: вкл.", "HDR: on") : Lang.t("HDR: выкл.", "HDR: off"));
        hdrPill.setTextColor(on ? GalleryUi.accent(hdrPill.getContext()) : 0xB3FFFFFF);
        hdrPill.setContentDescription("Ultra HDR");
        androidx.core.view.ViewCompat.setStateDescription(hdrPill, on ? Lang.t("включено", "on") : Lang.t("выключено", "off"));
    }

    /** Keeps the current thumbnail centred. */
    public void centre(int position) {
        filmstrip.setCurrent(position);
        LinearLayoutManager lm = (LinearLayoutManager) strip.getLayoutManager();
        if (lm == null) return;
        Context c = strip.getContext();
        int item = GalleryUi.dp(c, 50);
        lm.scrollToPositionWithOffset(position, Math.max(0, (strip.getWidth() - item) / 2));
    }

    /** The filmstrip: 44dp rounded thumbnails, 6dp apart, the current one with a 2dp accent border. */
    public static final class Filmstrip extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        public interface Open {
            void open(int position);
        }

        private final Open open;
        private List<GalleryItem> items = new ArrayList<>();
        private int current = -1;

        Filmstrip(Open open) {
            this.open = open;
        }

        public void setItems(List<GalleryItem> list) {
            items = list != null ? list : new ArrayList<>();
            notifyDataSetChanged();
        }

        void setCurrent(int position) {
            int old = current;
            current = position;
            if (old >= 0 && old < items.size()) notifyItemChanged(old);
            if (position >= 0 && position < items.size()) notifyItemChanged(position);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            FrameLayout f = new FrameLayout(c);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(GalleryUi.dp(c, 44), GalleryUi.dp(c, 44));
            lp.leftMargin = lp.rightMargin = GalleryUi.dp(c, 3);
            f.setLayoutParams(lp);
            ImageView i = new ImageView(c);
            i.setScaleType(ImageView.ScaleType.CENTER_CROP);
            i.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), GalleryUi.dp(view.getContext(), 10));
                }
            });
            i.setClipToOutline(true);
            f.addView(i, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            f.setClickable(true);
            f.setFocusable(true);
            return new RecyclerView.ViewHolder(f) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            FrameLayout f = (FrameLayout) holder.itemView;
            Context c = f.getContext();
            ImageView i = (ImageView) f.getChildAt(0);
            GalleryItem item = items.get(position);
            boolean on = position == current;
            f.setForeground(on ? GalleryUi.round(c, 0, GalleryUi.accent(c), 10) : null);
            if (on) ((android.graphics.drawable.GradientDrawable) f.getForeground()).setStroke(GalleryUi.dp(c, 2), GalleryUi.accent(c));
            f.setSelected(on);
            f.setContentDescription(GalleryUi.dayTime(item.getFile().getLastModified(), System.currentTimeMillis()));
            androidx.core.view.ViewCompat.setStateDescription(f, on ? Lang.t("текущий", "current") : null);
            f.setOnClickListener(v -> open.open(holder.getAbsoluteAdapterPosition()));
            if (item.getFile().getFileUri() != null && GalleryKeys.waitForFile(i, item.getFile(), () -> {
                if (holder.getAbsoluteAdapterPosition() == position) notifyItemChanged(position);
            })) Glide.with(i).clear(i); // P89: after the camera has finished writing the file
            else if (item.getFile().getFileUri() != null)
                // P620: the grid's thumbnail (cached by the grid, the view crops it): not a decode of each 50 MP photo at 132 px
                com.particlesdevs.photoncamera.gallery.glide.GalleryThumb.request(Glide.with(i),
                        com.particlesdevs.photoncamera.gallery.glide.GalleryThumb.of(item.getFile()),
                        com.particlesdevs.photoncamera.gallery.glide.GalleryThumb.gridPx(c)).into(i);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
