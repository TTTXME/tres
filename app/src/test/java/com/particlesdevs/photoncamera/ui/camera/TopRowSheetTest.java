package com.particlesdevs.photoncamera.ui.camera;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import com.particlesdevs.photoncamera.databinding.LayoutMainTopbarBinding;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;
import com.particlesdevs.photoncamera.settings.SettingsManager;
import com.particlesdevs.photoncamera.settings.TopRow;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * P574 (the approved concept, screen «edit»): the «Верхняя строка» sheet - its «−» badges, «ДОБАВИТЬ», «Вернуть основные»,
 * «Отмена» / «Готово»; the open sheet closes with a swipe up (the card follows the finger: far or fast closes, short springs back,
 * down does nothing), also during its opening; and the top bar shows the stored row in its order.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w392dp-h871dp-xxhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
public class TopRowSheetTest {
    private Context context;
    private MockedStatic<ScameraApp> camera;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Scamera_SettingsActivity);
        SettingsManager manager = new SettingsManager(context);
        prefs = manager.getDefaultPreferences();
        prefs.edit().clear().commit();
        camera = mockStatic(ScameraApp.class);
        camera.when(() -> ScameraApp.getStringStatic(org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(i -> RuntimeEnvironment.getApplication().getString(i.<Integer>getArgument(0)));
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        camera.when(ScameraApp::getSettings).thenReturn(mock(com.particlesdevs.photoncamera.api.Settings.class));
        PreferenceKeys.initialise(manager);
    }

    @After
    public void tearDown() {
        TopBar.dcgAvailable(false);
        camera.close();
    }

    @Test
    public void theEditorAddsRemovesResetsAndHoldsSixAtMost() {
        TopRowSheet.Editor e = new TopRowSheet.Editor(TopRow.DEFAULT, id -> !TopRow.DCG.equals(id));
        assertFalse("not on this camera", e.library().contains(TopRow.DCG));
        assertTrue(e.remove(TopRow.TORCH));
        assertFalse(e.remove(TopRow.TORCH));
        assertFalse("the LUT tray opens only from the row", e.remove(TopRow.LUT));
        assertFalse("the format sheet opens only from the row", e.remove(TopRow.FORMAT));
        assertTrue(e.items.contains(TopRow.LUT) && e.items.contains(TopRow.FORMAT));
        assertTrue(e.add(TopRow.GRID));
        assertFalse("already there", e.add(TopRow.GRID));
        assertFalse("not on this camera", e.add(TopRow.DCG));
        assertTrue(e.add(TopRow.HORIZON));
        assertEquals(Arrays.asList("format", "timer", "lut", "dcg", "grid", "horizon"), e.items);
        assertFalse("six at most", e.add(TopRow.UHDR));
        e.reset();
        assertEquals(TopRow.DEFAULT, e.items);
        // a row stored without them gets them back in the editor too
        TopRowSheet.Editor bare = new TopRowSheet.Editor(Arrays.asList("torch"), id -> true);
        assertEquals(Arrays.asList("format", "torch", "lut"), bare.items);
    }

    @Test
    public void theSheetsBadgesAddAndResetEditOnlyUntilDone() {
        List<List<String>> done = new ArrayList<>();
        int[] cancelled = {0};
        TopRowSheet.Editor e = new TopRowSheet.Editor(TopRow.DEFAULT, id -> true);
        ViewGroup card = TopRowSheet.build(context, e, done::add, () -> cancelled[0]++);
        assertNotNull(card.findViewWithTag("top_row_item_torch"));
        assertNull("no «−» on the LUT", card.findViewWithTag("top_row_remove_lut"));
        assertNull("no «−» on the format", card.findViewWithTag("top_row_remove_format"));
        card.findViewWithTag("top_row_remove_torch").performClick();
        assertNull("its «−» took it out", card.findViewWithTag("top_row_item_torch"));
        card.findViewWithTag("top_row_add_grid").performClick();
        assertNotNull(card.findViewWithTag("top_row_item_grid"));
        View dimmed = card.findViewWithTag("top_row_add_format");
        assertFalse("an item in the row is dimmed in «ДОБАВИТЬ»", dimmed.isEnabled());
        assertTrue(done.isEmpty());
        card.findViewWithTag("top_row_done").performClick();
        assertEquals(Arrays.asList("format", "timer", "lut", "dcg", "grid"), done.get(0));
        render(card, "top-row-sheet.png");
        card.findViewWithTag("top_row_reset").performClick();
        assertNotNull(card.findViewWithTag("top_row_item_torch"));
        assertNull(card.findViewWithTag("top_row_item_grid"));
        card.findViewWithTag("top_row_cancel").performClick();
        card.findViewWithTag("top_row_grab").performClick();
        assertEquals(2, cancelled[0]);
        assertEquals("nothing more stored", 1, done.size());
    }

    // ───── the open sheet

    private Activity activity;
    private FrameLayout root;

    private TopRowSheet.Handle open(List<List<String>> done) {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        root = new FrameLayout(activity);
        activity.setContentView(root);
        ShadowLooper.idleMainLooper();
        TopRowSheet.Handle h = TopRowSheet.show(root, TopRow.DEFAULT, id -> true, done::add);
        assertNotNull(h);
        assertTrue(h.isOpen());
        return h;
    }

    private static void layout(TopRowSheet.Handle h) {
        View frame = (View) h.watcher.getParent();
        if (h.card.getHeight() == 0) {
            frame.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY));
            frame.layout(0, 0, 1080, 2400);
        }
    }

    /** A one-finger drag on the card's frame from y0 by dy in {@code ms}. */
    private static void drag(TopRowSheet.Handle h, float y0, float dy, long ms) {
        long t = SystemClock.uptimeMillis();
        h.watcher.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 540, y0, 0));
        for (int i = 1; i <= 8; i++)
            h.watcher.dispatchTouchEvent(MotionEvent.obtain(t, t + ms * i / 8, MotionEvent.ACTION_MOVE, 540, y0 + dy * i / 8, 0));
        h.watcher.dispatchTouchEvent(MotionEvent.obtain(t, t + ms, MotionEvent.ACTION_UP, 540, y0 + dy, 0));
    }

    private static void settle() {
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS);
    }

    @Test
    public void aSwipeUpClosesItAShortOneSpringsBackADownOneDoesNothing() {
        List<List<String>> done = new ArrayList<>();
        TopRowSheet.Handle h = open(done);
        settle();
        layout(h);
        int height = h.card.getHeight();
        assertTrue(height > 0);
        // down: nothing
        drag(h, 400, 300, 400);
        settle();
        assertTrue(h.isOpen());
        assertEquals(0f, h.offset(), 0f);
        // a short slow drag up: the card followed, then springs back
        drag(h, 400, -height / 5f, 1200);
        settle();
        assertTrue(h.isOpen());
        assertEquals(0f, h.offset(), 0.5f);
        // past half of the card: closed, nothing stored
        drag(h, height - 20, -(height * 0.7f), 1500);
        assertFalse(h.isOpen());
        settle();
        assertFalse("gone", h.isShowing());
        assertTrue(done.isEmpty());
    }

    @Test
    public void aFlingUpDuringTheOpeningClosesItAndClosingTwiceIsHarmless() {
        List<List<String>> done = new ArrayList<>();
        TopRowSheet.Handle h = open(done);
        layout(h);   // still opening: no settle
        drag(h, 300, -260, 60);
        assertFalse(h.isOpen());
        h.close();
        h.dismissNow();
        settle();
        assertFalse(h.isShowing());
        assertTrue(done.isEmpty());
    }

    @Test
    public void doneStoresAndBackOrPauseLeaveNoWindow() {
        List<List<String>> done = new ArrayList<>();
        TopRowSheet.Handle h = open(done);
        settle();
        h.card.findViewWithTag("top_row_remove_timer").performClick();
        h.card.findViewWithTag("top_row_done").performClick();
        settle();
        assertEquals(Arrays.asList("format", "torch", "lut", "dcg"), done.get(0));
        assertFalse(h.isShowing());
        // another one closed by Back (the focusable window's own dismissal) and one by the screen pausing
        TopRowSheet.Handle back = TopRowSheet.show(root, TopRow.DEFAULT, id -> true, done::add);
        back.window.dismiss();
        assertFalse(back.isOpen());
        back.close();   // a swipe that comes late: nothing
        TopRowSheet.Handle paused = TopRowSheet.show(root, TopRow.DEFAULT, id -> true, done::add);
        paused.dismissNow();
        settle();
        assertFalse(back.isShowing());
        assertFalse(paused.isShowing());
        assertEquals(1, done.size());
    }

    @Test
    public void theTopBarShowsTheStoredRowInItsOrder() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        LayoutMainTopbarBinding tb = LayoutMainTopbarBinding.inflate(LayoutInflater.from(new ContextThemeWrapper(a, R.style.Theme_Scamera_SettingsActivity)));
        a.setContentView(tb.getRoot(), new ViewGroup.LayoutParams(1176, 192));
        TopBar.dcgAvailable(false);
        TopBar.bind(tb);
        ViewGroup row = (ViewGroup) tb.getRoot();
        assertEquals("the default row", Arrays.asList(tb.formatButton, tb.torchButton, tb.timerButton, tb.lutButton), shown(row));
        assertEquals("DCG kept in the list, hidden without it", View.GONE, tb.dcgBadge.getVisibility());
        TopRow.save(prefs, Arrays.asList("grid", "lut", "format", "dcg"));
        TopBar.dcgAvailable(true);
        TopBar.bind(tb);
        settle();
        assertEquals(Arrays.asList(tb.gridButton, tb.lutButton, tb.formatButton, tb.dcgBadge), shown(row));
        assertEquals(View.GONE, tb.torchButton.getVisibility());
        TopRow.save(prefs, new ArrayList<>());
        TopBar.bind(tb);
        settle();
        assertEquals("everything else taken out: the format and the LUT stay", Arrays.asList(tb.formatButton, tb.lutButton), shown(row));
    }

    private static void render(View v, String name) {
        v.measure(View.MeasureSpec.makeMeasureSpec(1056, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
        android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(v.getMeasuredWidth(), v.getMeasuredHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        image.eraseColor(0xFF45525B);
        v.draw(new android.graphics.Canvas(image));
        java.io.File dir = new java.io.File("build/reports/viewfinder");
        dir.mkdirs();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, name))) {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static List<View> shown(ViewGroup row) {
        List<View> out = new ArrayList<>();
        for (int i = 0; i < row.getChildCount(); i++) if (row.getChildAt(i).getVisibility() == View.VISIBLE) out.add(row.getChildAt(i));
        return out;
    }
}
