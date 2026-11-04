package com.particlesdevs.photoncamera.gallery.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.gallery.files.GalleryTrash;
import com.particlesdevs.photoncamera.util.Lang;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P502 (owner, 2026-11-05: the reference video's «Недавно удаленные»): the trashed photos and videos with the days left on each
 * («29 дн.»), a tap selects, the bottom bar puts the selection back or deletes it for good (another app's files through the
 * system's dialog), «Очистить» deletes everything. The note says how long the system keeps them.
 */
public final class TrashPage {
    public interface Host {
        Activity activity();

        void closeTrash();

        /** Files came back (or went for good): the library reads them again. */
        void trashChanged();
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> new Thread(r, "GalleryTrash"));
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public final FrameLayout view;
    private final Host host;
    private final ThumbGrid grid;
    private final TextView empty, note;
    private final View bar, restore, delete, clearAll;
    private final TextView count;
    private List<GalleryTrash.Item> items = new ArrayList<>();

    public TrashPage(Context c, Host host) {
        this.host = host;
        view = new FrameLayout(c);
        view.setBackgroundColor(GalleryUi.BG);
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        view.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View back = GalleryUi.squareButton(c, R.drawable.ui_ic_back, Lang.t("Назад", "Back"), v -> host.closeTrash());
        clearAll = GalleryUi.squareButton(c, R.drawable.ui_ic_cleanup, Lang.t("Очистить всё", "Empty the bin"), v -> confirmClearAll());
        column.addView(GalleryUi.header(c, Lang.t("Недавно удаленные", "Recently deleted"), back, clearAll));
        note = GalleryUi.text(c, Lang.t("Удаленное хранится здесь 30 дней, потом удаляется навсегда.",
                "Deleted items stay here for 30 days, then they are removed for good."), 13, GalleryUi.MUTED);
        note.setPadding(GalleryUi.dp(c, 18), 0, GalleryUi.dp(c, 18), GalleryUi.dp(c, 10));
        column.addView(note);
        grid = new ThumbGrid(new ThumbGrid.Listener() {
            @Override
            public void onClick(int index) {
                if (index < 0) return;
                grid.toggle(index);
                bind();
            }

            @Override
            public void onLongClick(int index) {
                onClick(index);
            }
        });
        RecyclerView rv = ThumbGrid.view(c, grid, 3);
        rv.setPadding(GalleryUi.dp(c, 8), 0, GalleryUi.dp(c, 8), GalleryUi.dp(c, 120));
        column.addView(rv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        empty = GalleryUi.text(c, Lang.t("Здесь пусто", "Nothing here"), 15, GalleryUi.MUTED);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, GalleryUi.dp(c, 160), 0, 0);
        view.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackground(GalleryTabBar.glass(c, 26));
        b.setElevation(GalleryUi.dp(c, 10));
        int pad = GalleryUi.dp(c, 8);
        b.setPadding(GalleryUi.dp(c, 16), pad, pad, pad);
        count = GalleryUi.text(c, "", 14, GalleryUi.TEXT);
        count.setClickable(true);
        count.setContentDescription(Lang.t("Выбрать всё", "Select all"));
        count.setOnClickListener(v -> selectAll());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(GalleryUi.dp(c, 130), ViewGroup.LayoutParams.WRAP_CONTENT);
        b.addView(count, cp);
        restore = GalleryUi.actionButton(c, R.drawable.ui_ic_restore, Lang.t("Вернуть", "Restore"), false, v -> restoreSelected());
        b.addView(restore, new LinearLayout.LayoutParams(GalleryUi.dp(c, 80), ViewGroup.LayoutParams.WRAP_CONTENT));
        delete = GalleryUi.actionButton(c, R.drawable.ui_ic_trash, Lang.t("Удалить", "Delete"), true, v -> deleteSelected());
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(GalleryUi.dp(c, 80), ViewGroup.LayoutParams.WRAP_CONTENT);
        dp.leftMargin = GalleryUi.dp(c, 6);
        b.addView(delete, dp);
        bar = b;
        view.addView(bar, GalleryTabBar.tabParams(c));
    }

    /** Reads the trash again (off the main thread). */
    public void reload() {
        final Context app = view.getContext().getApplicationContext();
        IO.execute(() -> {
            List<GalleryTrash.Item> list = GalleryTrash.load(app);
            MAIN.post(() -> show(list));
        });
    }

    private void show(List<GalleryTrash.Item> list) {
        items = list;
        long now = System.currentTimeMillis();
        List<ThumbGrid.Cell> cells = new ArrayList<>();
        for (GalleryTrash.Item i : list) cells.add(new ThumbGrid.Cell(i.uri, days(i.daysLeft(now)), i.video));
        grid.set(cells);
        empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        GalleryUi.setEnabledLook(clearAll, !list.isEmpty());
        bind();
    }

    /** «29 дн.» / "29 d". */
    static String days(int n) {
        return Lang.t(n + " дн.", n + " d");
    }

    private void bind() {
        int n = grid.selected().size();
        count.setText(n == 0 ? Lang.t("Выберите снимки", "Select items") : Lang.t("Выбрано: ", "Selected: ") + GalleryUi.shots(n));
        GalleryUi.setEnabledLook(restore, n > 0);
        GalleryUi.setEnabledLook(delete, n > 0);
        bar.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void selectAll() {
        if (grid.selected().size() == items.size()) grid.clearSelection();
        else for (int i = 0; i < items.size(); ++i) if (!grid.selected().contains(i)) grid.toggle(i);
        bind();
    }

    private List<GalleryTrash.Item> chosen() {
        List<GalleryTrash.Item> out = new ArrayList<>();
        for (Integer i : grid.selected()) if (i >= 0 && i < items.size()) out.add(items.get(i));
        return out;
    }

    private void restoreSelected() {
        List<GalleryTrash.Item> s = chosen();
        if (s.isEmpty()) return;
        boolean asked = GalleryTrash.restore(host.activity(), s);
        if (!asked) done();
    }

    private void deleteSelected() {
        List<GalleryTrash.Item> s = chosen();
        if (s.isEmpty()) return;
        GallerySheets.showDelete(view.getContext(), s.size(), size(s), () -> {
            boolean asked = GalleryTrash.deleteForGood(host.activity(), s);
            if (!asked) done();
        });
    }

    private void confirmClearAll() {
        if (items.isEmpty()) return;
        final List<GalleryTrash.Item> all = new ArrayList<>(items);
        GallerySheets.showDelete(view.getContext(), all.size(), size(all), () -> {
            boolean asked = GalleryTrash.deleteForGood(host.activity(), all);
            if (!asked) done();
        });
    }

    private static long size(List<GalleryTrash.Item> s) {
        long b = 0;
        for (GalleryTrash.Item i : s) b += Math.max(0, i.size);
        return b;
    }

    /** An action finished (here, or after the system's dialog): the trash and the library read again. */
    public void done() {
        grid.clearSelection();
        reload();
        host.trashChanged();
    }
}
