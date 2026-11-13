package com.scamera.app.ui.settings.custompreferences;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import com.scamera.app.app.ScameraApp;
import com.scamera.app.processing.color.FilmLut;
import com.scamera.app.processing.color.FilmSim;
import com.scamera.app.settings.PreferenceKeys;
import com.scamera.app.settings.SettingsManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class FilmSimPreviewTest {
    private MockedStatic<ScameraApp> app;
    private SettingsManager settings;
    private FilmSimPreference preference;
    private LutCompareView compare;
    private final List<Runnable> work = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();
    private final List<Consumer<FilmLut>> ready = new ArrayList<>();

    @Before public void setup() {
        Context context = RuntimeEnvironment.getApplication();
        settings = new SettingsManager(context);
        settings.getDefaultPreferences().edit().clear().commit();
        app = mockStatic(ScameraApp.class);
        app.when(ScameraApp::getAppContext).thenReturn(context);
        app.when(ScameraApp::getSettingsManagerStatic).thenReturn(settings);
        app.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        app.when(() -> ScameraApp.getStringStatic(anyInt())).thenAnswer(i -> context.getString(i.<Integer>getArgument(0)));
        app.when(ScameraApp::getSettings).thenReturn(mock(com.scamera.app.api.Settings.class));
        PreferenceKeys.initialise(settings);
        settings.set(SettingsManager.SCOPE_GLOBAL, FilmSim.STOCK_KEY, "kodak_gold_200");
        settings.set(SettingsManager.SCOPE_GLOBAL, FilmLut.KEY, "");
        preference = new FilmSimPreference(context);
        compare = new LutCompareView(context);
        compare.layout(0, 0, 64, 48);
        ReflectionHelpers.setField(preference, "compare", compare);
        preference.previewWorker = work::add;
        preference.previewLoader = (c, key, current, callback) -> { keys.add(key); ready.add(callback); };
    }

    @After public void finish() { app.close(); }

    private void runWork() { while (!work.isEmpty()) work.remove(0).run(); }
    private Bitmap after() { return ReflectionHelpers.getField(compare, "after"); }
    private static FilmLut colour(float r, float g, float b) {
        FilmLut lut = FilmLut.identity(2);
        for (int i = 0; i < lut.rgb.length; i += 3) { lut.rgb[i] = r; lut.rgb[i+1] = g; lut.rgb[i+2] = b; }
        return lut;
    }

    @Test public void aDelayedReferenceUsesTheOriginalStockAndEffectsSnapshot() {
        preference.redrawPreview();
        assertEquals("Rendering is queued off the UI thread", 1, work.size());
        settings.set(SettingsManager.SCOPE_GLOBAL, FilmSim.STOCK_KEY, "kodak_portra_400");
        settings.set(SettingsManager.SCOPE_GLOBAL, FilmSim.GRAIN_KEY, "200");
        runWork();
        assertTrue(keys.get(0), keys.get(0).contains("kodak_gold_200"));
        assertNull(after());
        ready.get(0).accept(colour(1, 1, 1));
        assertNull("The LUT callback does not render on its calling thread", after());
        runWork(); shadowOf(Looper.getMainLooper()).idle();
        Bitmap shown = after();
        assertNotNull(shown);
        for (int y = 0; y < shown.getHeight(); y++) for (int x = 0; x < shown.getWidth(); x++)
            assertEquals("Newer grain settings must not leak into the older request", 0xffffffff, shown.getPixel(x, y));
    }

    @Test public void anOldGenerationWithTheSameColourKeyCannotReplaceTheNewPreview() {
        preference.redrawPreview(); runWork();
        settings.set(SettingsManager.SCOPE_GLOBAL, FilmSim.FORMAT_KEY, "off");
        preference.redrawPreview(); runWork();
        assertEquals("Format is outside the colour key but inside the preview generation", keys.get(0), keys.get(1));
        ready.get(1).accept(colour(1, 0, 0)); runWork(); shadowOf(Looper.getMainLooper()).idle();
        Bitmap latest = after();
        assertNotNull(latest);
        assertEquals(0xffff0000, latest.getPixel(20, 20));
        ready.get(0).accept(colour(0, 0, 1));
        assertTrue("A stale callback queues no bitmap render", work.isEmpty());
        shadowOf(Looper.getMainLooper()).idle();
        assertSame(latest, after());
    }
}
