package com.scamera.app.ui.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroupAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.scamera.app.R;
import com.scamera.app.ui.settings.custompreferences.ModuleSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SIMPLE_UI §4, the concept's settings cards (.card / .row): the rows between two group titles share one card - the card
 * colour, a 1dp line, 20dp corners - divided by 1dp lines (.row + .row). The rows themselves are transparent; this draws
 * the card behind each run of rows, also when the run starts or ends off screen (its square edge then). A row that draws
 * its own card (the tone curve, the crop picture, the «Конфиг» tiles, the info notes) breaks the run.
 * <p>
 * P384 (owner, 2026-10-28: «Сделай это меню красивым с плавными анимациями»): the main page's module cards open and close in the
 * list (rows fade in and out, the rows below glide). A row's preference comes from the view (SettingsStyle.bind tags it), so a
 * row fading out - no adapter position any more, and put at the end of the list's children - still belongs to its card; the
 * runs are built in the order the rows are drawn on screen, the card follows the rows' translation, a line between two rows
 * takes the fainter one's fade, and the rows of an open module card get a lighter fill (CARD_NESTED) inside the card's outline.
 * <p>
 * P574 (owner, 2026-11-09: the settings in the look of the camera's sheets): one #2C2C2E card per group with 24dp corners and no
 * outline; the rows divided by 0.5dp hairlines (rgba(84, 84, 88, .65)) that start past the icon tile (60dp in) when the row below
 * has one, 16dp in otherwise; on the right-to-left layout from the other side.
 * <p>
 * P574 (owner, 2026-11-09, the approved «Камеры и модули»): an open module card's rows sit in an inner #232325 block with 18dp
 * corners, 10dp inside the card's sides and above its next row (the item offset under the block's last row); no line between a
 * card's header and its block; the module headers' lines start past the badge (68dp), the block's lines end at the block.
 */
public final class SettingsCardDecoration extends RecyclerView.ItemDecoration {
    /** Rows that draw their own card or are no row at all: they break a run. */
    public interface Standalone {
        boolean standalone(@Nullable Preference p);
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(),
            nested = new Paint();
    private final float radius, density, hairline;
    private final Standalone standalone;
    private final RectF rect = new RectF();
    private final android.graphics.Rect tileRect = new android.graphics.Rect();
    private final Path path = new Path();
    private final List<Item> items = new ArrayList<>();

    /** A child of the list as drawn: its view, its preference, its top and bottom on screen. */
    private static final class Item {
        View view;
        Preference p;
        int position;
        float top, bottom;
    }

    public SettingsCardDecoration(Context c, Standalone standalone) {
        float d = c.getResources().getDisplayMetrics().density;
        radius = 24 * d;
        density = d;
        fill.setColor(SettingsStyle.ROW);
        // the outline is gone (P574): the stroke paint only keeps its width for the geometry below
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(0f);
        line.setColor(SettingsStyle.HAIRLINE);
        hairline = Math.max(1f, .5f * d);
        nested.setColor(NESTED);
        this.standalone = standalone;
    }

    /** P574: the inner block of an open module card, its corners and its inset from the card. */
    static final int NESTED = 0xFF232325;
    private static final float NESTED_RADIUS = 18f, NESTED_INSET = 10f;
    private final Path block = new Path();

    private static boolean nested(@Nullable Preference p) {
        return p != null && p.getParent() instanceof ModuleSection;
    }

    /** P574: the room under an open module card's block (its last row's offset), covered by the card. */
    @Override
    public void getItemOffsets(@NonNull android.graphics.Rect out, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        out.set(0, 0, 0, 0);
        int position = parent.getChildAdapterPosition(view);
        Preference p = preference(parent, view, position);
        if (!nested(p) || position < 0 || !(parent.getAdapter() instanceof PreferenceGroupAdapter)) return;
        PreferenceGroupAdapter adapter = (PreferenceGroupAdapter) parent.getAdapter();
        Preference next = position + 1 < adapter.getItemCount() ? adapter.getItem(position + 1) : null;
        if (next == null || next.getParent() != p.getParent()) out.bottom = Math.round(NESTED_INSET * density);
    }

    private boolean row(@Nullable Preference p) {
        return p != null && (p instanceof ModuleSection || (!(p instanceof PreferenceCategory) && !standalone.standalone(p)));
    }

    /** The row at an adapter position (the neighbours outside the drawn children decide a square or a rounded edge). */
    private boolean row(RecyclerView parent, int position) {
        if (position < 0 || !(parent.getAdapter() instanceof PreferenceGroupAdapter)) return false;
        PreferenceGroupAdapter adapter = (PreferenceGroupAdapter) parent.getAdapter();
        if (position >= adapter.getItemCount()) return false;
        return row(adapter.getItem(position));
    }

    @Nullable
    private static Preference preference(RecyclerView parent, View child, int position) {
        Object tag = child.getTag(R.id.settings_bound_preference);
        if (tag instanceof Preference) return (Preference) tag;
        if (position >= 0 && parent.getAdapter() instanceof PreferenceGroupAdapter && position < parent.getAdapter().getItemCount())
            return ((PreferenceGroupAdapter) parent.getAdapter()).getItem(position);
        return null;
    }

    /** How far a row has faded in or out (its alpha against the alpha it rests at). */
    private static float fade(Item item) {
        if (SettingsMotion.dimming(item.view)) return 1f;   // P614: a row turning on or off is not a row coming or going
        float rest = SettingsStyle.restingAlpha(item.p);
        return rest <= 0f ? 1f : Math.max(0f, Math.min(1f, item.view.getAlpha() / rest));
    }

    /** P574: where a row's hairline starts, from the row's start edge - past its icon tile (the text's start), else 16dp in. */
    private float inset(View row) {
        Object bound = row.getTag(R.id.settings_bound_preference);
        if (bound instanceof ModuleSection) return 68 * density;   // P574: past the zoom badge
        View tile = row.findViewById(R.id.settings_icon_tile);
        if (row instanceof android.view.ViewGroup && tile != null && tile.getVisibility() == View.VISIBLE && tile.getWidth() > 0) {
            tileRect.set(0, 0, tile.getWidth(), tile.getHeight());
            ((android.view.ViewGroup) row).offsetDescendantRectToMyCoords(tile, tileRect);
            boolean rtl = row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            return (rtl ? row.getWidth() - tileRect.left : tileRect.right) + 12 * density;
        }
        return 16 * density;
    }

    @Override
    public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        items.clear();
        for (int i = 0; i < parent.getChildCount(); i++) {
            Item item = new Item();
            item.view = parent.getChildAt(i);
            item.position = parent.getChildAdapterPosition(item.view);
            item.p = preference(parent, item.view, item.position);
            item.top = item.view.getTop() + item.view.getTranslationY();
            item.bottom = item.view.getBottom() + item.view.getTranslationY();
            // P574: the room under a module card's block belongs to the card
            if (parent.getLayoutManager() != null) item.bottom += parent.getLayoutManager().getBottomDecorationHeight(item.view);
            // a title not yet faded in (a module's sensor rows swapping) does not split the card it sits in
            if (!row(item.p) && item.view.getAlpha() <= 0f) continue;
            items.add(item);
        }
        // the order on screen (a row fading out is the list's last child, drawn at its old place)
        Collections.sort(items, (a, b) -> Float.compare(a.top, b.top));
        int n = items.size();
        int i = 0;
        while (i < n) {
            if (!row(items.get(i).p)) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < n && row(items.get(j + 1).p)) j++;
            Item first = items.get(i), last = items.get(j);
            boolean roundTop = i > 0 || !row(parent, first.position - 1);
            boolean roundBottom = j < n - 1 || !row(parent, last.position + 1);
            if (first.position < 0) roundTop = true;
            if (last.position < 0) roundBottom = true;
            float top = first.top, bottom = last.bottom;
            float left = first.view.getLeft(), right = first.view.getRight();
            float half = stroke.getStrokeWidth() / 2f;
            rect.set(left + half, top + half, right - half, bottom - half);
            float rt = roundTop ? radius : 0f, rb = roundBottom ? radius : 0f;
            path.reset();
            path.addRoundRect(rect, new float[]{rt, rt, rt, rt, rb, rb, rb, rb}, Path.Direction.CW);
            canvas.drawPath(path, fill);
            // an open module card's rows: the inner block (P574), each row's part faded with it, inside the block's rounded outline
            float inner = NESTED_INSET * density;
            for (int k = i; k <= j; k++) {
                if (!nested(items.get(k).p)) continue;
                int end = k;
                while (end + 1 <= j && nested(items.get(end + 1).p) && items.get(end + 1).p.getParent() == items.get(k).p.getParent()) end++;
                float blockBottom = items.get(end).view.getBottom() + items.get(end).view.getTranslationY();
                block.reset();
                block.addRoundRect(new RectF(left + inner, items.get(k).top, right - inner, blockBottom),
                        NESTED_RADIUS * density, NESTED_RADIUS * density, Path.Direction.CW);
                canvas.save();
                canvas.clipPath(block);
                for (int m = k; m <= end; m++) {
                    Item item = items.get(m);
                    nested.setAlpha(Math.round(255 * fade(item)));
                    float partEnd = m == end ? blockBottom : items.get(m + 1).top;
                    canvas.drawRect(left, item.top, right, Math.max(item.top, partEnd), nested);
                }
                canvas.restore();
                k = end;
            }
            // a divider above each shown row but the first: a row not yet faded in (still under the rows gliding past it) is
            // skipped, so the rows on screen keep their lines
            Item above = null;
            for (int k = i; k <= j; k++) {
                Item item = items.get(k);
                if (fade(item) <= 0f) continue;
                // P574: no line between a module card's header and its block
                if (above != null && !(nested(item.p) && !nested(above.p))) {
                    line.setAlpha(Math.round(((SettingsStyle.HAIRLINE >>> 24) & 255) * Math.min(fade(item), fade(above))));
                    float y = item.top;
                    float inset = inset(item.view);
                    // inside the block a line ends at the block's edge
                    float end = nested(item.p) ? inner : 0f;
                    boolean rtl = parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                    canvas.drawRect(rtl ? left + end : left + inset, y, rtl ? right - inset : right - end, y + hairline, line);
                }
                above = item;
            }
            i = j + 1;
        }
    }
}
