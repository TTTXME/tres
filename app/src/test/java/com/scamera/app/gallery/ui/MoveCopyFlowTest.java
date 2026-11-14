package com.scamera.app.gallery.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.scamera.app.R;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.gallery.files.GalleryMoveCopy;
import com.scamera.app.gallery.files.GalleryMoveCopy.Mode;
import com.scamera.app.gallery.files.ImageFile;
import com.scamera.app.gallery.files.MediaFile;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** P697: the folder sheet, the toasts' texts and the selection bar with «В папку» (Russian, a 411dp phone). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class, qualifiers = "ru-w411dp-h900dp-xhdpi")
public class MoveCopyFlowTest {
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
    }

    @After
    public void tearDown() {
        camera.close();
        controller.destroy();
    }

    private static ImageFile file(long id, String path) {
        return new ImageFile(id, Uri.parse("content://media/external/images/media/" + id), path.substring(path.lastIndexOf('/') + 1), id, 1, path);
    }

    private static GalleryMoveCopy.Result result(int done, int skipped, int failed) {
        GalleryMoveCopy.Result r = new GalleryMoveCopy.Result();
        r.done = done;
        r.skipped = skipped;
        r.failed = failed;
        return r;
    }

    @Test
    public void toastsSayWhatWentWhere() {
        GalleryMoveCopy.Target trip = GalleryMoveCopy.newFolder("Поездка", "/storage/emulated/0/");
        assertEquals("Перемещено в «Поездка»: 3 снимка", MoveCopyFlow.resultText(Mode.MOVE, trip, result(3, 0, 0)));
        assertEquals("Скопировано в «Поездка»: 1 снимок, не удалось: 2", MoveCopyFlow.resultText(Mode.COPY, trip, result(1, 0, 2)));
        assertEquals("Перемещено в «Поездка»: 2 снимка, уже там: 1", MoveCopyFlow.resultText(Mode.MOVE, trip, result(2, 1, 0)));
        assertEquals("Уже в папке «Поездка»", MoveCopyFlow.resultText(Mode.MOVE, trip, result(0, 2, 0)));
        assertEquals("Не удалось переместить", MoveCopyFlow.resultText(Mode.MOVE, trip, result(0, 0, 1)));
        assertEquals("Не удалось скопировать", MoveCopyFlow.resultText(Mode.COPY, trip, result(0, 1, 1)));
        assertEquals("Копирование: 5 снимков…", MoveCopyFlow.progressText(Mode.COPY, 0, 5));
        assertEquals("Перемещение: 2 из 7…", MoveCopyFlow.progressText(Mode.MOVE, 2, 7));
        assertEquals("DCIM/Поездка", MoveCopyFlow.place(trip));
        assertEquals("Карта 1234-ABCD: DCIM/SD", MoveCopyFlow.place(GalleryMoveCopy.targetOf("SD", "/storage/1234-abcd/DCIM/SD/a.jpg")));
    }

    private static void collect(View v, Object tag, List<View> out) {
        if (tag.equals(v.getTag())) out.add(v);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); ++i) collect(((ViewGroup) v).getChildAt(i), tag, out);
    }

    private static String texts(View v) {
        StringBuilder b = new StringBuilder();
        if (v instanceof TextView) b.append(((TextView) v).getText()).append('|');
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); ++i) b.append(texts(((ViewGroup) v).getChildAt(i)));
        return b.toString();
    }

    @Test
    public void theSheetListsNewFolderThenTheFoldersAndDimsTheOneTheyAreIn() {
        ImageFile jpg = file(1, "/storage/emulated/0/DCIM/Camera/IMG_1.jpg"), dng = file(2, "/storage/emulated/0/DCIM/Camera/IMG_1.dng");
        List<List<MediaFile>> groups = GalleryMoveCopy.groups(Collections.singletonList(jpg), Arrays.asList(jpg, dng));
        List<GalleryMoveCopy.Target> targets = Arrays.asList(GalleryMoveCopy.targetOf("Camera", jpg.filePath()),
                GalleryMoveCopy.targetOf("Trip", "/storage/emulated/0/Pictures/Trip/x.jpg"));
        List<GalleryMoveCopy.Target> picked = new ArrayList<>();
        boolean[] asked = {false};
        View sheet = MoveCopyFlow.sheet(context, Mode.MOVE, 1, groups, targets, picked::add, () -> asked[0] = true);
        String all = texts(sheet);
        assertTrue(all, all.startsWith("Переместить в папку|1 снимок · с ними пары RAW + фото того же имени: 1|"));
        List<View> create = new ArrayList<>(), rows = new ArrayList<>();
        collect(sheet, "move_new_folder", create);
        collect(sheet, "move_target", rows);
        assertEquals(1, create.size());
        assertEquals(2, rows.size());
        assertFalse("the files are in Camera already", rows.get(0).isEnabled());
        assertTrue(texts(rows.get(0)).contains("Уже здесь"));
        assertTrue(rows.get(1).isEnabled());
        assertTrue(texts(rows.get(1)).contains("Pictures/Trip"));
        rows.get(1).performClick();
        assertEquals(Collections.singletonList(targets.get(1)), picked);
        create.get(0).performClick();
        assertTrue(asked[0]);
        // a copy may go into the same folder (a numbered duplicate)
        View copy = MoveCopyFlow.sheet(context, Mode.COPY, 1, groups, targets, picked::add, () -> {});
        rows.clear();
        collect(copy, "move_target", rows);
        assertTrue(rows.get(0).isEnabled());
    }

    @Test
    public void theNewFolderDialogMakesTheNameSafe() {
        List<GalleryMoveCopy.Target> created = new ArrayList<>();
        android.app.Dialog d = MoveCopyFlow.askName(context, created::add);
        View root = d.findViewById(android.R.id.content);
        List<View> found = new ArrayList<>();
        collect(root, "move_folder_name", found);
        ((TextView) found.get(0)).setText("  Отпуск: море ");
        found.clear();
        collect(root, "confirm_yes", found);
        found.get(0).performClick();
        assertEquals(1, created.size());
        assertEquals("DCIM/Отпуск_ море/", created.get(0).relativePath);
        assertFalse(d.isShowing());
    }

    @Test
    public void theFullSelectionBarFitsWithTheFolderAction() {
        View[] tapped = {null};
        SelectionBar bar = new SelectionBar(context, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, v -> tapped[0] = v);
        assertNotNull(bar.folder);
        bar.bind(11);
        int w = GalleryUi.dp(context, 411);
        bar.view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        assertTrue(bar.view.getMeasuredWidth() + " > " + w, bar.view.getMeasuredWidth() <= w);
        bar.view.layout(0, 0, bar.view.getMeasuredWidth(), bar.view.getMeasuredHeight());
        ViewGroup g = bar.view;
        for (int i = 0; i < g.getChildCount(); ++i) assertTrue(g.getChildAt(i).getRight() <= g.getWidth() + 1);
        assertTrue(bar.folder.isEnabled());
        bar.folder.performClick();
        assertTrue(tapped[0] == bar.folder);
        bar.bind(0);
        assertFalse(bar.folder.isEnabled());
        // without the folder action the bar is as before
        assertTrue(new SelectionBar(context, () -> {}, () -> {}, () -> {}, () -> {}).folder == null);
    }
}
