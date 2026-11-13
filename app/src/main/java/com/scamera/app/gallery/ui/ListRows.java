package com.scamera.app.gallery.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.scamera.app.R;
import com.scamera.app.gallery.files.GalleryVideo;
import com.scamera.app.gallery.files.MediaFile;
import com.scamera.app.gallery.model.GalleryItem;
import com.scamera.app.gallery.model.GalleryView;
import com.scamera.app.ui.Motion;
import com.scamera.app.util.Lang;

import java.util.ArrayList;
import java.util.List;

/**
 * P622 (owner, 2026-11-11, the list of another gallery «в нашем стиле»): the «Список» view of the photos - the grid's day captions,
 * under each a card of rows in the settings' grouped look (#2C2C2E, 20dp outer corners, 0.5dp hairlines from the text): a 4:3
 * rounded thumbnail (a video's length over its corner, the selection check), the file name on up to two lines, the date / size /
 * bytes line, the tags (format, big megapixels, SHDR, Ultra HDR) and a «⋮» with the long press's actions. The photo opened last is
 * tinted in the accent. Rows give under the finger on the shared spring; a tap opens the viewer out of the row's thumbnail.
 */
public final class ListRows implements RowStyle {
    /** The «⋮» of a row (the fragment shows the actions). */
    public interface Menu {
        void onItemMenu(int itemIndex, View anchor);

        /** The key (GalleryView.key) of the photo opened last, "" for none. */
        String lastOpened();
    }

    static final int ROW = 11;
    /** {@link LibraryAdapter.Row#count} of a photo row: its place in the day's card. */
    static final int FIRST = 1, LAST = 2;
    /** The thumbnail (dp): 4:3, the photos' own shape. */
    static final int THUMB_W = 104, THUMB_H = 78;

    private final Menu menu;

    public ListRows(Menu menu) {
        this.menu = menu;
    }

    /** The thumbnail size in px for the list's rows (the longer side: a 4:3 or 3:4 picture covers the 4:3 box). */
    public static int thumbPx(Context c) {
        return RowViews.step(GalleryUi.dp(c, THUMB_W));
    }

    @Override
    public List<LibraryAdapter.Row> rows(List<GalleryItem> items, long now) {
        List<LibraryAdapter.Row> day = LibraryAdapter.rowsOf(items, now);
        List<LibraryAdapter.Row> out = new ArrayList<>(day.size());
        for (int p = 0; p < day.size(); ++p) {
            LibraryAdapter.Row r = day.get(p);
            if (r.type != LibraryAdapter.PHOTO) {
                out.add(r);
                continue;
            }
            boolean first = p == 0 || day.get(p - 1).type != LibraryAdapter.PHOTO;
            boolean last = p == day.size() - 1 || day.get(p + 1).type != LibraryAdapter.PHOTO;
            out.add(new LibraryAdapter.Row(LibraryAdapter.PHOTO, r.item, null, (first ? FIRST : 0) | (last ? LAST : 0)));
        }
        return out;
    }

    @Override
    public int viewType(LibraryAdapter.Row row) {
        return row.type == LibraryAdapter.PHOTO ? ROW : row.type;
    }

    @Override
    public int span(LibraryAdapter.Row row, int columns) {
        return columns;
    }

    @Override
    public RecyclerView.ViewHolder create(ViewGroup parent, int viewType, LibraryAdapter adapter) {
        return new Holder(parent.getContext(), adapter);
    }

    @Override
    public void bind(RecyclerView.ViewHolder holder, LibraryAdapter.Row row, LibraryAdapter adapter) {
        if (holder instanceof Holder) ((Holder) holder).bind(row, adapter);
    }

    @Override
    public void refreshSelection(RecyclerView.ViewHolder holder, LibraryAdapter adapter) {
        if (holder instanceof Holder) ((Holder) holder).paint(adapter, true);
    }

    final class Holder extends RecyclerView.ViewHolder {
        final FrameLayout box;
        final ImageView image;
        final TextView length, name, line;
        final RowViews.FlowRow tags;
        final FrameLayout check;
        final ImageView heart;
        final View hairline, more;
        GalleryItem item;
        int index, place;

        Holder(Context c, LibraryAdapter adapter) {
            super(new FrameLayout(c));
            FrameLayout root = (FrameLayout) itemView;
            root.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout content = new LinearLayout(c);
            content.setOrientation(LinearLayout.HORIZONTAL);
            content.setGravity(Gravity.CENTER_VERTICAL);
            content.setMinimumHeight(GalleryUi.dp(c, 96));
            content.setPaddingRelative(GalleryUi.dp(c, 10), GalleryUi.dp(c, 9), GalleryUi.dp(c, 2), GalleryUi.dp(c, 9));
            root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            box = new FrameLayout(c);
            box.setBackground(GalleryUi.card(c, 0xFF3A3A3C, 12));
            RowViews.round(box, 12);
            image = new ImageView(c);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setTag("thumb");
            box.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            length = RowViews.duration(c);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.END);
            lp.rightMargin = lp.bottomMargin = GalleryUi.dp(c, 5);
            box.addView(length, lp);
            heart = GalleryUi.icon(c, R.drawable.ui_ic_heart_on, 0xFFFF6B81, 14);
            heart.setElevation(GalleryUi.dp(c, 2));
            FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(GalleryUi.dp(c, 14), GalleryUi.dp(c, 14), Gravity.BOTTOM | Gravity.START);
            hp.leftMargin = hp.bottomMargin = GalleryUi.dp(c, 6);
            box.addView(heart, hp);
            check = RowViews.check(c);
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(GalleryUi.dp(c, 22), GalleryUi.dp(c, 22), Gravity.TOP | Gravity.START);
            cp.leftMargin = cp.topMargin = GalleryUi.dp(c, 5);
            box.addView(check, cp);
            box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            content.addView(box, new LinearLayout.LayoutParams(GalleryUi.dp(c, THUMB_W), GalleryUi.dp(c, THUMB_H)));

            LinearLayout text = new LinearLayout(c);
            text.setOrientation(LinearLayout.VERTICAL);
            name = GalleryUi.text(c, "", 15, GalleryUi.TEXT);
            name.setTypeface(com.scamera.app.ui.style.Fonts.of(c, 600));
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setLineSpacing(GalleryUi.dp(c, 1), 1f);
            text.addView(name);
            line = GalleryUi.text(c, "", 13, GalleryUi.MUTED);
            line.setMaxLines(2);
            line.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.topMargin = GalleryUi.dp(c, 4);
            text.addView(line, sp);
            tags = new RowViews.FlowRow(c);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.topMargin = GalleryUi.dp(c, 6);
            text.addView(tags, tp);
            LinearLayout.LayoutParams xp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            xp.setMarginStart(GalleryUi.dp(c, 12));
            content.addView(text, xp);

            FrameLayout dots = new FrameLayout(c);
            ImageView icon = GalleryUi.icon(c, R.drawable.ui_ic_more, GalleryUi.MUTED, 20);
            dots.addView(icon, new FrameLayout.LayoutParams(GalleryUi.dp(c, 20), GalleryUi.dp(c, 20), Gravity.CENTER));
            dots.setBackground(GalleryUi.pressable(GalleryUi.round(c, 0, 0, 999)));
            dots.setClickable(true);
            dots.setFocusable(true);
            dots.setOnClickListener(v -> {
                if (item != null) menu.onItemMenu(index, v);
            });
            more = dots;
            content.addView(dots, new LinearLayout.LayoutParams(GalleryUi.dp(c, 44), GalleryUi.dp(c, 44)));

            hairline = new View(c);
            hairline.setBackgroundColor(GalleryUi.LINE);
            FrameLayout.LayoutParams lineP = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, GalleryUi.dp(c, .5f)),
                    Gravity.BOTTOM);
            lineP.setMarginStart(GalleryUi.dp(c, 10 + THUMB_W + 12));
            root.addView(hairline, lineP);

            root.setClickable(true);
            root.setFocusable(true);
            Motion.press(root, .975f);
            root.setOnClickListener(v -> {
                if (item != null) adapter.host().onPhotoClicked(index, box); // the viewer opens out of the thumbnail
            });
        }

        void bind(LibraryAdapter.Row row, LibraryAdapter adapter) {
            final GalleryItem it = adapter.items().get(row.item);
            item = it;
            index = row.item;
            place = row.count;
            Context c = itemView.getContext();
            MediaFile f = it.getFile();
            boolean video = GalleryVideo.isVideo(f);
            String dur = video ? RowText.duration(GalleryVideo.durationMs(f)) : null;
            length.setText(dur != null ? dur : "");
            length.setVisibility(dur != null ? View.VISIBLE : View.GONE);
            heart.setVisibility(adapter.host().isFavourite(it) ? View.VISIBLE : View.GONE);
            name.setText(f.getDisplayName());
            more.setContentDescription(Lang.t("Действия: ", "Actions: ") + f.getDisplayName());
            ItemMeta.Info info = ItemMeta.get(c, f, done -> {
                if (item == it) facts(adapter, done);
            });
            facts(adapter, info);
            hairline.setVisibility((place & LAST) != 0 ? View.GONE : View.VISIBLE);
            RowViews.thumb(image, f, adapter.thumbPx(), () -> {
                if (item == it) RowViews.thumb(image, f, adapter.thumbPx(), null);
            });
            paint(adapter, false);
        }

        /** The secondary line, the tags and the description from what is known of the file now. */
        void facts(LibraryAdapter adapter, ItemMeta.Info info) {
            if (item == null) return;
            Context c = itemView.getContext();
            MediaFile f = item.getFile();
            boolean video = GalleryVideo.isVideo(f);
            int[] size = info != null ? info.size : null;
            String second = RowText.secondary(f.getLastModified(), adapter.now, size, f.getSize(), video);
            line.setText(second);
            GalleryFormat format = GalleryFormat.of(item, adapter.rawBases());
            List<RowText.Tag> list = RowText.tags(format, f.getDisplayName(), size, info != null ? info.route : null, info != null && info.ultraHdr);
            // the row's tag views are kept and given the new labels (no views made per bind while scrolling)
            for (int i = 0; i < list.size(); i++) {
                RowText.Tag t = list.get(i);
                if (i < tags.getChildCount()) {
                    TextView v = (TextView) tags.getChildAt(i);
                    RowViews.paintTag(v, t.label, t.kind);
                    v.setVisibility(View.VISIBLE);
                } else tags.addView(RowViews.tag(c, t.label, t.kind));
            }
            for (int i = list.size(); i < tags.getChildCount(); i++) tags.getChildAt(i).setVisibility(View.GONE);
            tags.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
            String dur = video ? RowText.duration(GalleryVideo.durationMs(f)) : null;
            itemView.setContentDescription(RowText.describe(f.getDisplayName(), second, list, dur, adapter.host().isFavourite(item), isLast()));
        }

        boolean isLast() {
            String last = menu.lastOpened();
            return item != null && !last.isEmpty() && last.equals(GalleryView.key(item.getFile()));
        }

        /** The card piece, the selection and the last-opened tint. */
        void paint(LibraryAdapter adapter, boolean animate) {
            if (item == null) return;
            Context c = itemView.getContext();
            boolean selecting = adapter.host().selecting(), on = selecting && adapter.host().isSelected(item), last = isLast();
            int accent = GalleryUi.accent(c);
            int fill = on ? RowViews.tint(GalleryUi.CARD, accent, .24f) : last ? RowViews.tint(GalleryUi.CARD, accent, .12f) : GalleryUi.CARD;
            itemView.setBackground(GalleryUi.pressable(RowViews.piece(c, fill, (place & FIRST) != 0, (place & LAST) != 0, 20)));
            name.setTextColor(last ? accent : GalleryUi.TEXT);
            RowViews.paintCheck(check, selecting, on, animate);
            more.setVisibility(selecting ? View.INVISIBLE : View.VISIBLE);
            if (selecting) androidx.core.view.ViewCompat.setStateDescription(itemView, on ? Lang.t("выбрано", "selected") : Lang.t("не выбрано", "not selected"));
            else androidx.core.view.ViewCompat.setStateDescription(itemView, null);
        }
    }

    @NonNull
    @Override
    public String toString() {
        return "ListRows";
    }
}
