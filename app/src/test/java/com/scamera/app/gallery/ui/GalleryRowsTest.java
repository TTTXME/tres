package com.scamera.app.gallery.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import android.app.Application;
import android.content.Context;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

/**
 * P622: the list view's row texts (secondary line, tags, album counts, durations), its day cards, the timeline's month / year
 * sections, the views' spans and thumbnail sizes, and the rows at a large font on a 360dp phone, in English and Russian.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "en-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GalleryRowsTest {
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

    /** Today at hh:mm (local time). */
    static long today(int h, int m) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, h);
        c.set(Calendar.MINUTE, m);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static long at(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month, day, 12, 0, 0);
        return c.getTimeInMillis();
    }

    static GalleryItem item(long id, String name, long when, long bytes) {
        return new GalleryItem(new ImageFile(id, null, name, when, bytes, "/storage/emulated/0/DCIM/Camera/" + name));
    }

    // ---------------------------------------------------------------- texts

    @Test
    public void secondaryLine() {
        long now = System.currentTimeMillis(), when = today(11, 17);
        assertEquals("Today, 11:17 · 12.6 MP · 1.2 MB", RowText.secondary(when, now, new int[]{4096, 3072}, 1_258_291L, false));
        // 40 MP and up: the pixel size here, the megapixels a tag of their own
        assertEquals("Today, 11:17 · 8192×6144 · 12.4 MB", RowText.secondary(when, now, new int[]{8192, 6144}, 13_002_342L, false));
        assertEquals("Today, 11:17 · 900 KB", RowText.secondary(when, now, null, 921_600L, false));
        assertEquals("Today, 11:17 · 120 MB", RowText.secondary(when, now, new int[]{1920, 1080}, 125_829_120L, true));
    }

    @Test
    @Config(qualifiers = "ru-w360dp-h800dp-xhdpi")
    public void secondaryLineInRussian() {
        long now = System.currentTimeMillis(), when = today(11, 17);
        assertEquals("Сегодня, 11:17 · 12,6 Мп · 1,2 МБ", RowText.secondary(when, now, new int[]{4096, 3072}, 1_258_291L, false));
        assertEquals("128 фото · 3 видео · 12 RAW", RowText.counts(128, 3, 12));
        assertEquals("Пусто", RowText.counts(0, 0, 0));
        assertEquals("[HEIC, 50 Мп, SHDR]", RowText.tags(GalleryFormat.HEIC, "a.heic", new int[]{8192, 6144}, "SHDR", false).toString());
    }

    @Test
    public void tags() {
        assertEquals("[DNG]", RowText.tags(GalleryFormat.RAW, "IMG_1.dng", null, null, false).toString());
        assertEquals("[ARW]", RowText.tags(GalleryFormat.RAW, "DSC_1.ARW", null, null, false).toString());
        assertEquals("[RAW + JPEG, SHDR]", RowText.tags(GalleryFormat.RAW_JPEG, "IMG_1.jpg", new int[]{4096, 3072}, "SHDR", false).toString());
        assertEquals("[HEIC, 50 MP, SHDR]", RowText.tags(GalleryFormat.HEIC, "IMG_2.heic", new int[]{8192, 6144}, "SHDR", false).toString());
        assertEquals("[JPEG, 200 MP, Ultra HDR]", RowText.tags(GalleryFormat.JPEG, "IMG_3.jpg", new int[]{16320, 12240}, null, true).toString());
        assertEquals("[MP4]", RowText.tags(GalleryFormat.VIDEO, "VID_1.mp4", new int[]{7680, 4320}, null, false).toString());
        List<RowText.Tag> t = RowText.tags(GalleryFormat.HEIC, "IMG_2.heic", new int[]{8192, 6144}, "SCAM HDR", true);
        assertEquals(RowText.FORMAT, t.get(0).kind);
        assertEquals(RowText.PLAIN, t.get(1).kind);
        assertEquals(RowText.HDR, t.get(2).kind);
        assertEquals("SCAM HDR", t.get(2).label);
        assertEquals(RowText.HDR, t.get(3).kind);
    }

    @Test
    public void countsAndDurations() {
        assertEquals("128 photos · 3 videos · 12 RAW", RowText.counts(128, 3, 12));
        assertEquals("1 photo", RowText.counts(1, 0, 0));
        assertEquals("1 video", RowText.counts(0, 1, 0));
        assertEquals("Empty", RowText.counts(0, 0, 0));
        List<GalleryItem> files = Arrays.asList(item(1, "a.jpg", 1, 1), item(2, "a.dng", 1, 1), item(3, "b.mp4", 1, 1), item(4, "c.heic", 1, 1));
        assertEquals("3 photos · 1 video · 1 RAW", RowText.counts(files));
        assertNull(RowText.duration(0));
        assertEquals("0:42", RowText.duration(42_000));
        assertEquals("1:02:07", RowText.duration(3_727_000));
        String d = RowText.describe("IMG_1.jpg", "Today, 11:17", RowText.tags(GalleryFormat.JPEG, "IMG_1.jpg", null, null, false), null, true, true);
        assertEquals("IMG_1.jpg, Today, 11:17, JPEG, favourite, opened last", d);
    }

    // ---------------------------------------------------------------- sections

    @Test
    public void listRowsFormOneCardPerDay() {
        long now = System.currentTimeMillis();
        List<GalleryItem> items = Arrays.asList(item(1, "a.jpg", now, 1), item(2, "b.jpg", now - 1000, 1), item(3, "c.jpg", now - 2000, 1),
                item(4, "d.jpg", now - 3L * 24 * 3600 * 1000, 1));
        List<LibraryAdapter.Row> rows = new ListRows(menu()).rows(items, now);
        assertEquals(6, rows.size());
        assertEquals(LibraryAdapter.HEADER, rows.get(0).type);
        assertEquals(ListRows.FIRST, rows.get(1).count);
        assertEquals(0, rows.get(2).count);
        assertEquals(ListRows.LAST, rows.get(3).count);
        assertEquals(LibraryAdapter.HEADER, rows.get(4).type);
        assertEquals(ListRows.FIRST | ListRows.LAST, rows.get(5).count);
        assertEquals(3, rows.get(5).item);
    }

    private static List<GalleryItem> library() {
        // newest first: two in November 2026, one in October 2026, two in December 2025, one in March 2024
        return Arrays.asList(item(1, "a.jpg", at(2026, 10, 9), 1), item(2, "b.dng", at(2026, 10, 2), 1), item(3, "c.heic", at(2026, 9, 30), 1),
                item(4, "d.mp4", at(2025, 11, 31), 1), item(5, "e.jpg", at(2025, 11, 1), 1), item(6, "f.jpg", at(2024, 2, 3), 1));
    }

    @Test
    public void timelineSections() {
        List<GalleryItem> items = library();
        List<LibraryAdapter.Row> all = TimelineSections.all(items);
        // 4 month headers + 6 photos
        assertEquals(10, all.size());
        assertEquals(LibraryAdapter.HEADER, all.get(0).type);
        assertEquals("November 2026", all.get(0).label);
        assertEquals(2, all.get(0).count);
        assertEquals("October 2026", all.get(3).label);
        assertEquals("December 2025", all.get(5).label);
        assertEquals(2, all.get(5).count);
        assertEquals("March 2024", all.get(8).label);
        assertEquals(LibraryAdapter.PHOTO, all.get(9).type);
        assertEquals(5, all.get(9).item);

        List<LibraryAdapter.Row> months = TimelineSections.months(items);
        assertEquals(4, months.size());
        assertEquals(RowStyle.GROUP, months.get(0).type);
        assertEquals(0, months.get(0).item);
        assertEquals(2, months.get(0).count);
        assertEquals("October 2026", months.get(1).label);
        assertEquals(3, months.get(2).item);

        List<LibraryAdapter.Row> years = TimelineSections.years(items);
        assertEquals(3, years.size());
        assertEquals("2026", years.get(0).label);
        assertEquals(3, years.get(0).count);
        assertEquals("2025", years.get(1).label);
        assertEquals("2024", years.get(2).label);
        // a year card's covers: the first photo of each of its months
        assertEquals("[0, 2]", Arrays.toString(TimelineSections.monthCovers(items, 0, 3, 3)));
        // the row showing an item: its photo, else the card holding it
        assertEquals(2, TimelineSections.positionOf(all, 1));
        assertEquals(1, TimelineSections.positionOf(months, 2));
        assertEquals(1, TimelineSections.positionOf(years, 4));
        assertEquals(-1, TimelineSections.positionOf(years, 40));
    }

    @Test
    public void timelineOldestFirstKeepsTheOrder() {
        List<GalleryItem> items = new ArrayList<>(library());
        java.util.Collections.reverse(items);
        List<LibraryAdapter.Row> months = TimelineSections.months(items);
        assertEquals("March 2024", months.get(0).label);
        assertEquals("November 2026", months.get(3).label);
    }

    @Test
    @Config(qualifiers = "ru-w360dp-h800dp-xhdpi")
    public void monthTitlesInRussian() {
        assertEquals("Ноябрь 2026", TimelineSections.monthTitle(at(2026, 10, 9)));
        assertEquals("Май", TimelineSections.monthName(4));
    }

    @Test
    public void timelineTokensAreAFifthOfTheWayToOurs() {
        assertEquals(2f, TimelineRows.GAP, 1e-4);
        assertEquals(2.8f, TimelineRows.TILE_RADIUS, 1e-4);
        assertEquals(2.4f, TimelineRows.EDGE, 1e-4);
        assertEquals(12.8f, TimelineRows.CARD_RADIUS, 1e-4);
        assertEquals(18.4f, TimelineRows.TITLE_SP, 1e-4);
    }

    // ---------------------------------------------------------------- the views on the page

    private ListRows.Menu menu() {
        return new ListRows.Menu() {
            @Override public void onItemMenu(int itemIndex, View anchor) {}

            @Override public String lastOpened() { return "id:2"; }
        };
    }

    private LibraryAdapter adapter(List<GalleryItem> items) {
        LibraryAdapter a = new LibraryAdapter(new LibraryAdapter.Host() {
            @Override public boolean selecting() { return false; }

            @Override public boolean isSelected(GalleryItem item) { return false; }

            @Override public void onPhotoClicked(int itemIndex, View view) {}
        });
        a.setItems(items);
        return a;
    }

    private GalleryViews views(GalleryLayout.Mode mode, LibraryAdapter a, RecyclerView rv, GridLayoutManager lm) {
        GalleryLayout.setMode(context, mode);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        View range = new View(context);
        column.addView(range, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, GalleryUi.dp(context, 46)));
        FrameLayout body = new FrameLayout(context);
        column.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        if (rv.getParent() != null) ((ViewGroup) rv.getParent()).removeView(rv);
        body.addView(rv);
        GalleryViews v = new GalleryViews(context, rv, lm, a, body, range, null, new GalleryViews.Host() {
            @Override public void onItemMenu(int itemIndex, View anchor) {}

            @Override public String lastOpened() { return ""; }

            @Override public boolean selectingNow() { return false; }
        });
        v.applyStored();
        return v;
    }

    @Test
    public void eachViewSetsItsSpansAndThumbnailSize() {
        LibraryAdapter a = adapter(library());
        RecyclerView rv = new RecyclerView(context);
        GridLayoutManager lm = new GridLayoutManager(context, 3);
        rv.setLayoutManager(lm);
        rv.setAdapter(a);
        int width = context.getResources().getDisplayMetrics().widthPixels;

        GalleryViews grid = views(GalleryLayout.Mode.GRID, a, rv, lm);
        assertNull(a.style());
        assertEquals(GalleryView.columns(context), lm.getSpanCount());
        assertEquals(RowViews.step(width / lm.getSpanCount()), a.thumbPx());

        GalleryViews list = views(GalleryLayout.Mode.LIST, a, rv, lm);
        assertTrue(a.style() instanceof ListRows);
        assertEquals(1, lm.getSpanCount());
        assertEquals(ListRows.thumbPx(context), a.thumbPx());
        assertEquals(ListRows.ROW, a.getItemViewType(1));
        assertEquals(LibraryAdapter.HEADER, a.getItemViewType(0));

        GalleryViews timeline = views(GalleryLayout.Mode.TIMELINE, a, rv, lm);
        assertTrue(a.style() instanceof TimelineRows);
        assertEquals(GalleryLayout.TL_DEFAULT_COLUMNS, lm.getSpanCount());
        assertEquals(10, a.getItemCount());
        assertEquals(lm.getSpanCount(), lm.getSpanSizeLookup().getSpanSize(0)); // a month title spans the row
        assertEquals(1, lm.getSpanSizeLookup().getSpanSize(1));
        // a pinch: one more column, then (at the most) the months
        assertTrue(timeline.pinch(false));
        assertEquals(GalleryLayout.TL_DEFAULT_COLUMNS + 1, lm.getSpanCount());
        assertEquals(RowViews.step(width / lm.getSpanCount()), a.thumbPx());
        timeline.zoom(GalleryLayout.Level.MONTHS, 4);
        assertEquals(GalleryLayout.Level.MONTHS, GalleryLayout.level(context));
        assertEquals(4, a.getItemCount());
        assertEquals(1, lm.getSpanCount());
        assertEquals(2, timeline.positionOfItem(4)); // December 2025's card
        // the grid's pinch stays the fragment's; the list has one density
        assertFalse(grid.pinch(true));
        assertTrue(list.pinch(true));
    }

    /** Lays {@code v} out at {@code widthDp} and checks that no visible child sticks out of its parent. */
    private void assertInside(View v) {
        if (!(v instanceof ViewGroup) || v.getVisibility() != View.VISIBLE) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View ch = g.getChildAt(i);
            if (ch.getVisibility() != View.VISIBLE) continue;
            assertTrue(ch.getClass().getSimpleName() + " right " + ch.getRight() + " > " + g.getWidth(), ch.getRight() <= g.getWidth() + 1);
            assertTrue(ch.getClass().getSimpleName() + " bottom " + ch.getBottom() + " > " + g.getHeight(), ch.getBottom() <= g.getHeight() + 1);
            if (ch instanceof TextView) {
                Layout l = ((TextView) ch).getLayout();
                if (l != null) assertTrue(((TextView) ch).getText() + ": " + l.getLineCount() + " lines", l.getLineCount() <= 2);
            }
            assertInside(ch);
        }
    }

    @Test
    @Config(qualifiers = "en-w360dp-h800dp-xhdpi", fontScale = 2f)
    public void listRowsHoldLargeTextInTwoLines() {
        long now = System.currentTimeMillis();
        List<GalleryItem> items = Arrays.asList(
                item(1, "IMG_20261111_101700_SHDR_very_long_file_name_from_another_camera_app_0123456789.jpg", now, 13_002_342L),
                item(2, "IMG_20261111_101500.heic", now - 1000, 4_002_342L),
                item(3, "IMG_20261111_101400.dng", now - 2000, 24_002_342L));
        ItemMeta.put(items.get(0).getFile(), new ItemMeta.Info(new int[]{8192, 6144}, "SHDR", true));
        LibraryAdapter a = adapter(items);
        a.setStyle(new ListRows(menu()));
        RecyclerView rv = new RecyclerView(context);
        rv.setLayoutManager(new GridLayoutManager(context, 1));
        rv.setAdapter(a);
        int w = GalleryUi.dp(context, 360);
        rv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(GalleryUi.dp(context, 800), View.MeasureSpec.EXACTLY));
        rv.layout(0, 0, w, GalleryUi.dp(context, 800));
        RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(1);
        assertNotNull(h);
        assertTrue(h instanceof ListRows.Holder);
        ListRows.Holder row = (ListRows.Holder) h;
        assertEquals(2, row.name.getLayout().getLineCount());
        assertTrue(row.name.getLayout().getEllipsisCount(1) > 0);
        assertEquals("[JPEG, 50 MP, SHDR, Ultra HDR]", tagsOf(row));
        String d = h.itemView.getContentDescription().toString();
        assertTrue(d, d.startsWith("IMG_20261111_101700_SHDR"));
        assertTrue(d, d.contains("Ultra HDR"));
        assertInside(h.itemView);
        // the photo opened last: its name in the accent
        ListRows.Holder last = (ListRows.Holder) rv.findViewHolderForAdapterPosition(2);
        assertEquals(GalleryUi.accent(context), last.name.getCurrentTextColor());
        assertTrue(last.itemView.getContentDescription().toString().endsWith("opened last"));
        assertEquals(GalleryUi.TEXT, row.name.getCurrentTextColor());
        // the last row of the day's card has no hairline under it
        ListRows.Holder third = (ListRows.Holder) rv.findViewHolderForAdapterPosition(3);
        assertEquals(View.GONE, third.hairline.getVisibility());
        assertEquals(View.VISIBLE, row.hairline.getVisibility());
        assertEquals("Actions: IMG_20261111_101400.dng", third.more.getContentDescription().toString());
        // a recycled row keeps its tag views: bound to the DNG it shows one, the others hidden
        a.onBindViewHolder(row, 3);
        assertEquals("[DNG]", tagsOf(row));
        assertEquals(4, row.tags.getChildCount());
    }

    private static String tagsOf(ListRows.Holder h) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < h.tags.getChildCount(); i++)
            if (h.tags.getChildAt(i).getVisibility() == View.VISIBLE) out.add(((TextView) h.tags.getChildAt(i)).getText().toString());
        return out.toString();
    }

    @Test
    public void tagsWrapInsteadOfOverflowing() {
        RowViews.FlowRow row = new RowViews.FlowRow(context);
        for (String t : new String[]{"RAW + JPEG", "50 MP", "SHDR", "Ultra HDR", "HEIC"}) row.addView(RowViews.tag(context, t, RowText.FORMAT));
        int w = GalleryUi.dp(context, 120);
        row.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, w, row.getMeasuredHeight());
        int one = row.getChildAt(0).getMeasuredHeight();
        assertTrue("wrapped: " + row.getMeasuredHeight() + " vs " + one, row.getMeasuredHeight() > one * 2);
        for (int i = 0; i < row.getChildCount(); i++) assertTrue(row.getChildAt(i).getRight() <= w);
    }
}
