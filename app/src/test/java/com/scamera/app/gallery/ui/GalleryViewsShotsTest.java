package com.scamera.app.gallery.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.scamera.app.R;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.gallery.files.ImageFile;
import com.scamera.app.gallery.model.GalleryItem;
import com.scamera.app.gallery.model.GalleryLayout;
import com.scamera.app.gallery.model.GalleryView;
import com.scamera.app.settings.SettingsManager;
import com.scamera.app.util.Lang;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * P622: the gallery's three views rendered for review - the photos (grid, list, timeline at every zoom, the timeline scrolled with
 * its month capsule) and the albums (list, timeline), and the «Вид галереи» sheet, in Russian and English on a 360dp phone, into
 * build/reports/gallery-views (the gallery has one, dark, look). Thumbnails are painted gradients (no files are read).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GalleryViewsShotsTest {
    private MockedStatic<ScameraApp> camera;
    private ActivityController<AppCompatActivity> controller;
    private Context context;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(AppCompatActivity.class);
        AppCompatActivity activity = controller.get();
        activity.setTheme(R.style.Theme_Scamera_SettingsActivity);
        controller.setup();
        context = activity;
        SettingsManager manager = new SettingsManager(context);
        camera = mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(inv -> context.getString(inv.getArgument(0)));
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        camera.when(ScameraApp::getSettings).thenReturn(mock(com.scamera.app.api.Settings.class));
        context.getSharedPreferences("gallery_ui", Context.MODE_PRIVATE).edit().clear().commit();
        LibraryAdapter.loadPictures = false;
    }

    @After
    public void tearDown() {
        LibraryAdapter.loadPictures = true;
        camera.close();
        controller.destroy();
    }

    private int dp(float v) {
        return GalleryUi.dp(context, v);
    }

    // ---------------------------------------------------------------- the library

    private static long ago(int days, int h, int m) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -days);
        c.set(Calendar.HOUR_OF_DAY, h);
        c.set(Calendar.MINUTE, m);
        return c.getTimeInMillis();
    }

    private static long monthsAgo(int months, int day) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.add(Calendar.MONTH, -months);
        c.set(Calendar.DAY_OF_MONTH, Math.min(day, c.getActualMaximum(Calendar.DAY_OF_MONTH)));
        c.set(Calendar.HOUR_OF_DAY, 15);
        return c.getTimeInMillis();
    }

    private final List<GalleryItem> library = new ArrayList<>();

    private GalleryItem add(String name, long when, long bytes, int[] size, String route, boolean uhdr, long videoMs) {
        long id = library.size() + 1;
        GalleryItem g = new GalleryItem(new ImageFile(id, null, name, when, bytes, "/storage/emulated/0/DCIM/Camera/" + name));
        library.add(g);
        if (size != null || route != null || uhdr) ItemMeta.put(g.mediaFile(), new ItemMeta.Info(size, route, uhdr));
        if (videoMs > 0) {
            try {
                java.lang.reflect.Method m = com.scamera.app.gallery.files.GalleryVideo.class.getDeclaredMethod("remember", long.class, long.class);
                m.setAccessible(true);
                m.invoke(null, id, videoMs);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
        return g;
    }

    private List<GalleryItem> library() {
        library.clear();
        int[] mp50 = {8192, 6144}, mp12 = {4096, 3072}, mp200 = {16320, 12240};
        add("IMG_20261111_101700.jpg", ago(0, 10, 17), 14_200_000L, mp50, "SHDR", true, 0);
        add("IMG_20261111_101700.dng", ago(0, 10, 17), 50_400_000L, mp50, null, false, 0);
        add("VID_20261111_093012.mp4", ago(0, 9, 30), 128_400_000L, null, null, false, 42_000);
        add("IMG_20261111_090102.heic", ago(0, 9, 1), 3_900_000L, mp12, "SHDR", false, 0);
        add("IMG_20261110_184455_TELE_10x_long_name.jpg", ago(1, 18, 44), 6_100_000L, mp12, null, false, 0);
        add("IMG_20261110_120001.jpg", ago(1, 12, 0), 32_000_000L, mp200, "SHDR", false, 0);
        add("Screenshot_20261110-083000.png", ago(1, 8, 30), 900_000L, null, null, false, 0);
        for (int i = 0; i < 9; i++) add("IMG_202610" + (20 - i) + "_1" + i + "0000.jpg", monthsAgo(1, 20 - i), 4_000_000L + i * 100_000L, mp12, i % 3 == 0 ? "SHDR" : null, false, 0);
        for (int i = 0; i < 6; i++) add("IMG_202609" + (12 - i) + "_1" + i + "0000.heic", monthsAgo(2, 12 - i), 3_000_000L, mp12, null, false, 0);
        for (int i = 0; i < 7; i++) add("IMG_2025120" + (8 - i) + ".jpg", monthsAgo(11, 8 - i), 3_000_000L, mp12, null, false, 0);
        for (int i = 0; i < 4; i++) add("VID_2025060" + (4 - i) + ".mp4", monthsAgo(17, 4 - i), 40_000_000L, null, null, false, 61_000L * (i + 1));
        for (int i = 0; i < 5; i++) add("IMG_2024030" + (6 - i) + ".dng", monthsAgo(32, 6 - i), 24_000_000L, mp12, null, false, 0);
        return new ArrayList<>(library);
    }

    // ---------------------------------------------------------------- pages

    private int paintIndex;

    /** Every thumbnail of {@code v} painted with a soft two-colour gradient (a stand-in for the photos). */
    private void paint(View v) {
        int[][] palettes = {{0xFF7FB2D9, 0xFF2E4F6E}, {0xFFE7B07A, 0xFF7A3E2E}, {0xFF8FC1A2, 0xFF2F5E47}, {0xFFC9A6E0, 0xFF4B3266},
                {0xFFF0D58C, 0xFF8A6A2A}, {0xFF9DB7C9, 0xFF39454F}, {0xFFE09A9A, 0xFF6A2F3B}, {0xFFA0D0D8, 0xFF2D5C66}};
        if (v instanceof ImageView && ("thumb".equals(v.getTag())
                || v.getParent() instanceof LibraryAdapter.Square && ((ViewGroup) v.getParent()).indexOfChild(v) == 0)) { // (the grid's picture)
            int[] p = palettes[paintIndex++ % palettes.length];
            GradientDrawable g = new GradientDrawable(paintIndex % 2 == 0 ? GradientDrawable.Orientation.TL_BR : GradientDrawable.Orientation.TOP_BOTTOM, p);
            ((ImageView) v).setImageDrawable(g);
        }
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) paint(((ViewGroup) v).getChildAt(i));
    }

    /** Detached views keep a measured size across a text / visibility change: everything is measured again. */
    private static void forceAll(View v) {
        v.forceLayout();
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) forceAll(((ViewGroup) v).getChildAt(i));
    }

    private void layout(View v, int w, int h) {
        forceAll(v);
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), h > 0 ? View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                : View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, w, v.getMeasuredHeight());
    }

    private File save(View v, String name) throws Exception {
        paintIndex = 0;
        paint(v);
        Bitmap bmp = Bitmap.createBitmap(v.getWidth(), Math.max(1, v.getHeight()), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(GalleryUi.BG);
        v.draw(canvas);
        File dir = new File("build/reports/gallery-views");
        dir.mkdirs();
        File out = new File(dir, name + "_" + (Lang.ru() ? "ru" : "en") + ".png");
        try (FileOutputStream o = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
        return out;
    }

    /** The «Фото» page as the fragment builds it: the header, the date line, the page in {@code mode}, the tab bar. */
    private final class Page {
        final FrameLayout root = new FrameLayout(context);
        final RecyclerView rv = new RecyclerView(context);
        final GridLayoutManager lm = new GridLayoutManager(context, 3);
        final LibraryAdapter adapter;
        final GalleryViews views;
        final List<GalleryItem> items;

        Page(GalleryLayout.Mode mode, GalleryLayout.Level level, boolean selecting) {
            GalleryLayout.setMode(context, mode);
            GalleryLayout.setLevel(context, level);
            items = library();
            final String last = GalleryView.key(items.get(3).mediaFile());
            root.setBackgroundColor(GalleryUi.BG);
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            GalleryHeader header = new GalleryHeader(context, Lang.t("Фото", "Photos"), null,
                    GalleryUi.squareButton(context, R.drawable.ui_ic_search, "", v -> {}),
                    GalleryUi.squareButton(context, R.drawable.ui_ic_select_all, "", v -> {}),
                    GalleryUi.squareButton(context, R.drawable.ui_ic_more, "", v -> {}));
            column.addView(header.view);
            LinearLayout range = new LinearLayout(context);
            range.setGravity(Gravity.CENTER_VERTICAL);
            range.setPadding(dp(18), 0, dp(16), dp(6));
            TextView text = GalleryUi.fitText(context, Lang.t("6 октября — Сегодня", "October 6 — Today"), 15, GalleryUi.TEXT);
            text.setTypeface(com.scamera.app.ui.style.Fonts.of(context, 600));
            range.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            range.addView(GalleryUi.squareButton(context, R.drawable.ui_ic_jump, "", v -> {}), new LinearLayout.LayoutParams(dp(40), dp(40)));
            column.addView(range);
            FrameLayout body = new FrameLayout(context);
            column.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            adapter = new LibraryAdapter(new LibraryAdapter.Host() {
                @Override public boolean selecting() { return selecting; }

                @Override public boolean isSelected(GalleryItem item) { return item == items.get(1) || item == items.get(2); }

                @Override public void onPhotoClicked(int itemIndex, View view) {}

                @Override public boolean isFavourite(GalleryItem item) { return item == items.get(0) || item == items.get(5); }
            });
            rv.setLayoutManager(lm);
            rv.setAdapter(adapter);
            rv.setClipToPadding(false);
            body.addView(rv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            adapter.setItems(items);
            views = new GalleryViews(context, rv, lm, adapter, body, range, header, new GalleryViews.Host() {
                @Override public void onItemMenu(int itemIndex, View anchor) {}

                @Override public String lastOpened() { return last; }

                @Override public boolean selectingNow() { return selecting; }
            });
            views.applyStored();
            GalleryTabBar tabs = new GalleryTabBar(context, i -> {}, i -> {});
            if (!selecting) root.addView(tabs.view, GalleryTabBar.tabParams(context));
            else {
                SelectionBar bar = new SelectionBar(context, () -> {}, () -> {}, () -> {}, () -> {});
                bar.bind(2);
                FrameLayout.LayoutParams bp = GalleryTabBar.tabParams(context);
                bp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                bp.leftMargin = bp.rightMargin = dp(12);
                root.addView(bar.view, bp);
            }
        }

        File shot(String name) throws Exception {
            int w = dp(360), h = dp(800);
            layout(root, w, h);
            layout(root, w, h); // the second pass: the collapse margin and the capsule
            return save(root, name);
        }
    }

    private void photos(String suffix) throws Exception {
        Page grid = new Page(GalleryLayout.Mode.GRID, GalleryLayout.Level.ALL, false);
        assertTrue(grid.shot("photos_grid").exists());

        Page list = new Page(GalleryLayout.Mode.LIST, GalleryLayout.Level.ALL, false);
        assertTrue(list.adapter.style() instanceof ListRows);
        list.shot("photos_list");
        Page listSel = new Page(GalleryLayout.Mode.LIST, GalleryLayout.Level.ALL, true);
        listSel.shot("photos_list_selecting");

        Page all = new Page(GalleryLayout.Mode.TIMELINE, GalleryLayout.Level.ALL, false);
        all.shot("photos_timeline");
        // scrolled into October: the large title collapsed, the month capsule pinned
        all.lm.scrollToPositionWithOffset(12, 0);
        layout(all.root, dp(360), dp(800));
        all.rv.scrollBy(0, dp(40));
        java.lang.reflect.Field f = GalleryViews.class.getDeclaredField("sticky");
        f.setAccessible(true);
        TextView pill = (TextView) f.get(all.views);
        all.shot("photos_timeline_scrolled");
        assertEquals(View.VISIBLE, pill.getVisibility());
        assertEquals(all.adapter.dayLabelAt(all.lm.findFirstVisibleItemPosition()), pill.getText().toString()); // the month on screen
        assertTrue("the capsule is laid out", pill.getWidth() > 0);
        Page months = new Page(GalleryLayout.Mode.TIMELINE, GalleryLayout.Level.MONTHS, false);
        months.shot("photos_timeline_months");
        assertEquals(TimelineSections.months(months.items).size(), months.adapter.getItemCount());
        Page years = new Page(GalleryLayout.Mode.TIMELINE, GalleryLayout.Level.YEARS, false);
        years.shot("photos_timeline_years");
        assertEquals(TimelineSections.years(years.items).size(), years.adapter.getItemCount());
    }

    // ---------------------------------------------------------------- albums

    private AlbumsPage.Album album(String title, List<GalleryItem> files, int icon) {
        GalleryItem folder = new GalleryItem(files.get(0).mediaFile());
        folder.setShownName(title);
        folder.groupedItems().addAll(files);
        return new AlbumsPage.Album(title, folder, icon);
    }

    private File albums(GalleryLayout.Mode mode, String name) throws Exception {
        List<GalleryItem> all = library();
        List<GalleryItem> camera = all.subList(0, 20), videos = new ArrayList<>(), raws = new ArrayList<>();
        for (GalleryItem g : all) {
            GalleryFormat f = GalleryFormat.of(g.mediaFile().displayName(), false);
            if (f == GalleryFormat.VIDEO) videos.add(g);
            if (f == GalleryFormat.RAW) raws.add(g);
        }
        List<AlbumsPage.Album> frequent = new ArrayList<>();
        frequent.add(album(Lang.t("Все", "All"), all, R.drawable.ui_ic_photos));
        frequent.add(album(Lang.t("Камера", "Camera"), camera, R.drawable.ui_ic_camera));
        frequent.add(album(Lang.t("Видео", "Videos"), videos, R.drawable.ui_ic_video));
        frequent.add(album("RAW", raws, R.drawable.ui_ic_raw));
        List<AlbumsPage.Album> others = new ArrayList<>();
        // first visits (no badge) everywhere but «Telegram», last opened before its three newest files: a badge of 3
        context.getSharedPreferences("gallery_ui", Context.MODE_PRIVATE).edit().clear().commit();
        GalleryLayout.markSeen(context, "Telegram", all.subList(3, 10));
        others.add(album("Telegram", all.subList(0, 10), R.drawable.ui_ic_folder));
        others.add(album("Download", all.subList(10, 16), R.drawable.ui_ic_folder));
        others.add(album("WhatsApp Images", all.subList(16, 30), R.drawable.ui_ic_folder));
        others.add(album("Pictures/Edited", all.subList(30, 31), R.drawable.ui_ic_folder));
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(GalleryUi.BG);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        column.addView(new GalleryHeader(context, Lang.t("Альбомы", "Albums"), null,
                GalleryUi.squareButton(context, R.drawable.ui_ic_search, "", v -> {}), GalleryUi.squareButton(context, R.drawable.ui_ic_more, "", v -> {})).view);
        AlbumsPage page = new AlbumsPage(context, new AlbumsPage.Host() {
            @Override public void openAlbum(GalleryItem folder, String title) {}

            @Override public void openTrash() {}

            @Override public void openPhotoFolders() {}
        });
        page.setMode(mode);
        page.bind(frequent, others, new ArrayList<>(), 4);
        column.addView(page.view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        GalleryTabBar tabs = new GalleryTabBar(context, i -> {}, i -> {});
        tabs.select(GalleryTabBar.ALBUMS);
        root.addView(tabs.view, GalleryTabBar.tabParams(context));
        layout(root, dp(360), dp(800));
        if (mode == GalleryLayout.Mode.LIST) {
            // the badge on Telegram's cover, said by the row
            View row = findByDescriptionStart(root, "Telegram");
            assertNotNull(row);
            assertTrue(row.getContentDescription().toString(), row.getContentDescription().toString().endsWith(": 3"));
        }
        return save(root, name);
    }

    private static View findByDescriptionStart(View v, String start) {
        if (v.getContentDescription() != null && v.getContentDescription().toString().startsWith(start)) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            View f = findByDescriptionStart(((ViewGroup) v).getChildAt(i), start);
            if (f != null) return f;
        }
        return null;
    }

    private void sheet() throws Exception {
        GalleryLayout.setMode(context, GalleryLayout.Mode.TIMELINE);
        final GalleryLayout.Mode[] picked = {null};
        LinearLayout s = GallerySheets.viewOptions(context, () -> {}, m -> picked[0] = m);
        View seg = s.findViewWithTag("gallery_mode");
        assertNotNull(seg);
        // a tap on «Список» hands the mode over
        ((ViewGroup) seg).getChildAt(1).performClick();
        assertEquals(GalleryLayout.Mode.LIST, picked[0]);
        layout(s, dp(360), 0);
        save(s, "view_sheet");
    }

    private void all() throws Exception {
        photos("");
        albums(GalleryLayout.Mode.LIST, "albums_list");
        albums(GalleryLayout.Mode.TIMELINE, "albums_timeline");
        sheet();
    }

    @Test
    public void renderRussian() throws Exception {
        all();
    }

    @Test
    @Config(qualifiers = "en-w360dp-h800dp-xhdpi")
    public void renderEnglish() throws Exception {
        all();
    }
}
