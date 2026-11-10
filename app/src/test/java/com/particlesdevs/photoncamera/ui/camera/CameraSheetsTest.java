package com.particlesdevs.photoncamera.ui.camera;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.processing.PhotoFormat;
import com.particlesdevs.photoncamera.settings.FormatChoice;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * SIMPLE_UI §2: «Формат файла» is two choices over the same 9 values - (X, Нет) -> X, (X, + RAW) -> RAW + X, (any, Только RAW)
 * -> RAW, «Только RAW» keeps the photo choice and disables the photo control, the summary says what is saved; «Размер фото»
 * dims a size the module lacks.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CameraSheetsTest {
    @Test
    public void theTwoChoicesMapOntoTheNineValues() {
        assertEquals(FormatChoice.HEIC, CameraSheets.choice(PhotoFormat.HEIC, CameraSheets.RAW_NONE));
        assertEquals(FormatChoice.RAW_HEIC, CameraSheets.choice(PhotoFormat.HEIC, CameraSheets.RAW_PAIRED));
        assertEquals(FormatChoice.RAW, CameraSheets.choice(PhotoFormat.AVIF, CameraSheets.RAW_ONLY));
        assertEquals(FormatChoice.RAW_JPEG, CameraSheets.choice(PhotoFormat.JPEG, CameraSheets.RAW_PAIRED));
        assertEquals("RAW (DNG) + HEIC", CameraSheets.summary(PhotoFormat.HEIC, CameraSheets.RAW_PAIRED));
        assertEquals("RAW (DNG)", CameraSheets.summary(PhotoFormat.HEIC, CameraSheets.RAW_ONLY));
        assertEquals("WebP", CameraSheets.summary(PhotoFormat.WEBP, CameraSheets.RAW_NONE));
        // «Только RAW» keeps the photo codec: «+ RAW» restores it
        CameraSheets.FormatState state = new CameraSheets.FormatState(FormatChoice.RAW, PhotoFormat.HEIC);
        assertEquals(PhotoFormat.HEIC, state.photo);
        assertEquals(CameraSheets.RAW_ONLY, state.raw);
    }

    @Test
    public void theFormatSheetStoresEveryTapAndStaysOpen() throws Exception {
        AppCompatActivity activity = Robolectric.buildActivity(AppCompatActivity.class).setup().get();
        activity.setTheme(R.style.Theme_Scamera_SettingsActivity);
        List<FormatChoice> stored = new ArrayList<>();
        List<FormatChoice> offered = Arrays.asList(FormatChoice.values());
        BottomSheetDialog d = CameraSheets.format(activity, offered, FormatChoice.JPEG, PhotoFormat.JPEG, stored::add);
        View content = d.findViewById(android.R.id.content).findViewWithTag("format_sheet");
        assertNotNull(content);
        ViewGroup photo = content.findViewWithTag("format_photo"), raw = content.findViewWithTag("format_raw");
        assertEquals(4, photo.getChildCount());
        assertEquals(3, raw.getChildCount());
        // P592 (the owner's crossed-out «Формат файла»): the two controls under their captions and nothing else - no hint lines,
        // no «Сохранится» card, no note
        assertEquals(4, ((ViewGroup) content).getChildCount());
        assertNull(content.findViewWithTag("format_summary"));
        assertNull(content.findViewWithTag("format_photo_line"));
        photo.getChildAt(1).performClick();   // HEIC
        raw.getChildAt(1).performClick();     // + RAW
        assertEquals(Arrays.asList(FormatChoice.HEIC, FormatChoice.RAW_HEIC), stored);
        // TalkBack still hears what is saved and what each choice means
        assertTrue(String.valueOf(content.getContentDescription()), String.valueOf(content.getContentDescription()).endsWith("RAW (DNG) + HEIC"));
        assertEquals("HEIC, " + CameraSheets.photoLine(activity, PhotoFormat.HEIC), String.valueOf(photo.getChildAt(1).getContentDescription()));
        // the hints are the controls' long press: the settings' help sheet
        assertTrue(raw.getChildAt(0).performLongClick());
        android.app.Dialog help = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(help.getWindow().getDecorView().findViewWithTag("setting_help"));
        assertTrue(String.valueOf(((TextView) help.getWindow().getDecorView().findViewWithTag("help_text")).getText()).contains("Lightroom"));
        help.dismiss();
        raw.getChildAt(2).performClick();     // Только RAW
        assertFalse("the photo control is disabled", photo.getChildAt(0).isEnabled());
        assertEquals("Не сохраняется: выбрано «Только RAW»", String.valueOf(androidx.core.view.ViewCompat.getStateDescription(photo)));
        assertEquals(FormatChoice.RAW, stored.get(stored.size() - 1));
        assertTrue("selecting does not close it", d.isShowing());
        render(content.getRootView(), "format-sheet.png");
    }

    @Test
    public void theSizeSheetListsOnlyWhatTheModuleHas() throws Exception {
        AppCompatActivity activity = Robolectric.buildActivity(AppCompatActivity.class).setup().get();
        activity.setTheme(R.style.Theme_Scamera_SettingsActivity);
        List<Integer> picked = new ArrayList<>();
        List<CharSequence> refused = new ArrayList<>();
        BottomSheetDialog d = CameraSheets.size(activity, PhotoSize.NORMAL, true, false, picked::add, refused::add);
        View sheet = d.findViewById(android.R.id.content);
        // P319 (user report: a front camera listed 50 and 200 MP): a size the lens lacks is not listed at all
        assertNull(sheet.findViewWithTag("size_" + PhotoSize.MAX));
        assertTrue(picked.isEmpty());
        assertTrue(refused.isEmpty());
        sheet.findViewWithTag("size_" + PhotoSize.FULL).performClick();
        assertEquals(Arrays.asList(PhotoSize.FULL), picked);
        render(sheet.getRootView(), "size-sheet.png");
    }

    /**
     * P574 (the approved concept): the top row's badge opens one sheet - «Формат | Размер» tabs over the format's choices or the
     * size rows; a size row ticks the chosen size (no radio circle), a pick stays in the sheet and the tick follows the camera.
     */
    @Test
    public void theFormatAndSizeSheetHasTwoTabsAndTicksTheSize() throws Exception {
        AppCompatActivity activity = Robolectric.buildActivity(AppCompatActivity.class).setup().get();
        activity.setTheme(R.style.Theme_Scamera_SettingsActivity);
        List<FormatChoice> stored = new ArrayList<>();
        int[] size = {PhotoSize.NORMAL};
        List<Integer> picked = new ArrayList<>();
        BottomSheetDialog d = CameraSheets.formatAndSize(activity, Arrays.asList(FormatChoice.values()), FormatChoice.JPEG, PhotoFormat.JPEG,
                stored::add, new CameraSheets.SizeSource() {
                    @Override public int current() { return size[0]; }
                    @Override public boolean full() { return true; }
                    @Override public boolean max() { return true; }
                    @Override public void pick(int s) { picked.add(s); size[0] = s; }
                }, CameraSheets.TAB_FORMAT);
        View root = d.findViewById(android.R.id.content);
        ViewGroup tabs = root.findViewWithTag("format_size_tabs");
        assertEquals(2, tabs.getChildCount());
        View format = root.findViewWithTag("format_sheet"), sizes = root.findViewWithTag("size_page");
        assertEquals(View.VISIBLE, format.getVisibility());
        assertEquals(View.GONE, sizes.getVisibility());
        tabs.getChildAt(1).performClick();
        assertEquals(View.GONE, format.getVisibility());
        assertEquals(View.VISIBLE, sizes.getVisibility());
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1f, sizes.getAlpha(), 0f);
        // a tap on the shown tab: no new page (no fade from nothing again)
        tabs.getChildAt(1).performClick();
        assertEquals("a new page would start from alpha 0", 1f, sizes.getAlpha(), 0f);
        ViewGroup normal = root.findViewWithTag("size_" + PhotoSize.NORMAL), full = root.findViewWithTag("size_" + PhotoSize.FULL);
        // P592: an available size shows its name alone (what it gives is its long press); TalkBack still hears both
        ViewGroup fullTexts = (ViewGroup) full.getChildAt(0);
        assertEquals(1, fullTexts.getChildCount());
        assertTrue(full.getContentDescription().toString(), full.getContentDescription().toString().startsWith(
                ((android.widget.TextView) fullTexts.getChildAt(0)).getText() + ", "));
        assertTrue("the chosen size is ticked", normal.isSelected());
        assertEquals(View.VISIBLE, normal.getChildAt(normal.getChildCount() - 1).getVisibility());
        assertEquals("no tick on the others", View.INVISIBLE, full.getChildAt(full.getChildCount() - 1).getVisibility());
        full.performClick();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(Arrays.asList(PhotoSize.FULL), picked);
        assertTrue("a pick keeps the sheet", d.isShowing());
        assertTrue("the tick follows", ((View) root.findViewWithTag("size_" + PhotoSize.FULL)).isSelected());
        render(root.getRootView(), "format-size-sheet.png");
    }

    private static void render(View root, String name) throws Exception {
        int w = root.getWidth() > 0 ? root.getWidth() : 1080, h = root.getHeight() > 0 ? root.getHeight() : 2400;
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        Bitmap image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        image.eraseColor(0xFF45525B);
        root.draw(new Canvas(image));
        File dir = new File("build/reports/viewfinder");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
