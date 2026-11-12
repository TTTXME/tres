package com.particlesdevs.photoncamera.settings;

import android.app.Application;
import android.content.Context;
import android.view.ContextThemeWrapper;
import androidx.preference.*;
import com.particlesdevs.photoncamera.R;
import com.particlesdevs.photoncamera.app.ScameraApp;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * P624 «Статика»: the third processing mode - its value in «Режим обработки», the worker's tuning lines (the static merge on,
 * no «Быстрый» lines), the iterations and super-resolution settings in «Склейка», the scam_dev backend switch. P627: every mode
 * stays the mode chosen - the help text no longer promises «Качество» for a moving scene, «Быстрый» is exactly the fast route.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class, qualifiers="w400dp-h880dp-mdpi")
public class StaticRouteTest {
    private Context context;
    private SettingsManager manager;
    private MockedStatic<ScameraApp> camera;
    @Before public void setUp(){
        context=new ContextThemeWrapper(RuntimeEnvironment.getApplication(),R.style.Theme_Scamera_SettingsActivity);
        manager=new SettingsManager(context);manager.getDefaultPreferences().edit().clear().commit();
        camera=mockStatic(ScameraApp.class);
        camera.when(ScameraApp::getAppContext).thenReturn(context);
        camera.when(ScameraApp::getResourcesStatic).thenReturn(context.getResources());
        camera.when(()->ScameraApp.getStringStatic(anyInt())).thenAnswer(inv->context.getString(inv.getArgument(0)));
        PreferenceKeys.initialise(manager);
        camera.when(ScameraApp::getSettingsManagerStatic).thenReturn(manager);
        ScameraApp app=mock(ScameraApp.class,RETURNS_DEEP_STUBS);when(app.getSettingsManager()).thenReturn(manager);
        camera.when(()->ScameraApp.getInstance(any(Context.class))).thenReturn(app);
    }
    @After public void tearDown(){PreferenceKeys.endShotProfile();if(camera!=null)camera.close();}
    private void processing(String v){manager.set("default_scope","pref_scam_hybrid_processing",v);}

    @Test public void theThreeModes(){
        assertEquals("fast",PreferenceKeys.processingMode());
        assertTrue(PreferenceKeys.fastProcessing());assertFalse(PreferenceKeys.staticProcessing());
        processing("quality");
        assertFalse(PreferenceKeys.fastProcessing());assertFalse(PreferenceKeys.staticProcessing());
        processing("static");
        assertEquals("static",PreferenceKeys.processingMode());
        assertTrue(PreferenceKeys.staticProcessing());assertFalse(PreferenceKeys.fastProcessing());
        processing("turbo"); // an unknown stored value reads as the default
        assertEquals("fast",PreferenceKeys.processingMode());
    }

    @Test public void staticLinesReachTheWorkerWithoutTheFastOnes(){
        processing("static");
        String t=PreferenceKeys.hybridTuningText();
        assertTrue(t,t.contains("staticMerge 1\nstaticIters 8\nstaticVulkan -1\n"));
        assertFalse(t,t.contains("fastMerge 1"));       // P627: no other mode's lines (the route takes every burst itself)
        processing("quality");
        assertFalse(PreferenceKeys.hybridTuningText().contains("staticMerge"));
        processing("fast");
        t=PreferenceKeys.hybridTuningText();
        assertFalse(t,t.contains("staticMerge"));assertTrue(t,t.contains("fastMerge 1\n"));
    }

    @Test public void iterationsAreClamped(){
        processing("static");
        manager.set("default_scope","pref_scam_hybrid_static_iters","20");
        assertTrue(PreferenceKeys.staticTuning().contains("staticIters 16\n"));
        manager.set("default_scope","pref_scam_hybrid_static_iters","2");
        assertTrue(PreferenceKeys.staticTuning().contains("staticIters 4\n"));
        manager.set("default_scope","pref_scam_hybrid_static_iters","12");
        assertTrue(PreferenceKeys.staticTuning().contains("staticIters 12\n"));
    }

    @Test public void superResolutionIsTheStaticModesOwnOutput(){
        manager.set("default_scope","pref_scam_hybrid_output","1.5x");
        manager.set("default_scope","pref_scam_hybrid_static_sr","2x");
        assertEquals("1.5x",PreferenceKeys.hybridOutputMode()); // «Быстрый»: the merge's output setting
        processing("static");
        assertEquals("2x",PreferenceKeys.hybridOutputMode());   // «Статика»: its super-resolution setting
        manager.set("default_scope","pref_scam_hybrid_static_sr","sensor");
        assertEquals("sensor",PreferenceKeys.hybridOutputMode());
        processing("quality");
        assertEquals("1.5x",PreferenceKeys.hybridOutputMode());
    }

    @Test public void theSettingsRows(){
        SettingsMigration.prepare(context,manager.getDefaultPreferences());
        PreferenceManager pm=new PreferenceManager(context);
        PreferenceScreen screen=pm.inflateFromResource(context,R.xml.preferences,null);pm.setPreferences(screen);
        ListPreference mode=screen.findPreference("pref_scam_hybrid_processing");
        assertNotNull(mode);
        assertEquals(Arrays.asList("fast","quality","static"),Arrays.asList(Arrays.stream(mode.getEntryValues()).map(CharSequence::toString).toArray()));
        assertEquals(3,mode.getEntries().length);
        PreferenceGroup merge=screen.findPreference("scam_hybrid_dnmerge_screen");
        assertNotNull(merge);
        PreferenceGroup cat=screen.findPreference("scam_hybrid_static_category");
        assertNotNull(cat);
        assertNotNull(cat.findPreference("pref_scam_hybrid_static_sr"));
        assertNotNull(cat.findPreference("pref_scam_hybrid_static_iters"));
        assertNotNull(merge.findPreference("pref_scam_hybrid_static_sr")); // in «Шумоподавление и склейка» (P629)
        assertTrue(mode.getSummary().toString().contains("Static")||mode.getSummary().toString().contains("Статика"));
    }

    @Test public void backendDevSwitch(){
        DevKeys.Key k=DevKeys.find("static_vulkan");
        assertNotNull(k);assertEquals("-1",k.def);
    }

    /** P627: the help text of the mode switch promises no hand-over; «Быстрый» is the fast route exactly, in every mode. */
    @Test public void everyModeStaysItsOwn(){
        String ru=context.getString(R.string.prefs_scam_hybrid_processing_summary);
        assertFalse(ru,ru.contains("как в «Качестве»")||ru.contains("as in Quality"));
        processing("static");
        assertFalse(com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.fastRoute());
        assertFalse(PreferenceKeys.hybridTuningText().contains("fastMerge 0"));
        processing("quality");
        assertFalse(com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.fastRoute());
        processing("fast");
        assertTrue(com.particlesdevs.photoncamera.processing.opengl.postpipeline.ScamHybridBurst.fastRoute());
    }
}
