package com.scamera.app.ui.style;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.widget.TextView;

import com.scamera.app.circularbarlib.ui.UiScale;
import com.scamera.app.ui.SplashActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * P582 (owner, 2026-11-09: «Убедись что масштаб самого смартфона и то какой у него в системе шрифт не влияет на наше
 * приложение»): an activity of the app is 390dp wide (the concept's phone) on any panel at any display size, and the system
 * font size never changes its text.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class UiFixedScaleTest {

    /** The display sizes of one 1080 px panel (default, smaller, larger), a 1440 px one and a 720 px one. */
    private static final String[] SCREENS = {"w360dp-h780dp-xxhdpi", "w411dp-h891dp-420dpi", "w320dp-h693dp-480dpi",
            "w411dp-h914dp-560dpi", "w480dp-h1066dp-xxhdpi", "w412dp-h915dp-xhdpi", "w360dp-h780dp-xhdpi"};

    private static Activity splash() {
        return Robolectric.buildActivity(SplashActivity.class).setup().get();
    }

    private static float widthDp(Context c) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        return Math.min(m.widthPixels, m.heightPixels) / m.density;
    }

    private static float textPx(Context c, float sp) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t.getTextSize();
    }

    @Test public void theDensityOfTheConceptsWidth() {
        assertEquals("1080 px", 443, UiFixedScale.densityDpi(1080));
        assertEquals("1440 px", 590, UiFixedScale.densityDpi(1440));
        assertEquals("1260 px", 516, UiFixedScale.densityDpi(1260));
        assertEquals("never under 390dp", 390f, 1080 * 160f / UiFixedScale.densityDpi(1080), 1f);
        assertTrue(1080 * 160f / UiFixedScale.densityDpi(1080) >= 390f);
        assertEquals("unknown", 0, UiFixedScale.densityDpi(0));
    }

    @Test public void theOverrideTouchesOnlyTheScale() {
        Configuration sys = new Configuration();
        sys.densityDpi = 560;
        sys.fontScale = 1.3f;
        sys.screenWidthDp = 411;
        sys.screenHeightDp = 891;
        sys.smallestScreenWidthDp = 411;
        Configuration o = UiFixedScale.override(sys, 1440);
        assertNotNull(o);
        assertEquals(590, o.densityDpi);
        assertEquals(1f, o.fontScale, 0f);
        assertEquals(390, o.screenWidthDp);
        assertEquals(390, o.smallestScreenWidthDp);
        assertEquals((int) (891 * 560f / 590), o.screenHeightDp);
        assertTrue("nothing else is overridden", o.orientation == Configuration.ORIENTATION_UNDEFINED
                && o.uiMode == Configuration.UI_MODE_TYPE_UNDEFINED && o.getLocales().isEmpty());

        Configuration concept = new Configuration();
        concept.densityDpi = 590;
        concept.fontScale = 1f;
        concept.smallestScreenWidthDp = 390;
        assertNull("already the concept's", UiFixedScale.override(concept, 1440));

        Configuration tablet = new Configuration();
        tablet.densityDpi = 320;
        tablet.fontScale = 1.15f;
        tablet.smallestScreenWidthDp = 800;
        Configuration t = UiFixedScale.override(tablet, 1600);
        assertNotNull(t);
        assertEquals("a large screen keeps its density", Configuration.DENSITY_DPI_UNDEFINED, t.densityDpi);
        assertEquals("but not the font scale", 1f, t.fontScale, 0f);
    }

    @Test public void everyDisplaySizeGivesTheConceptsWidth() {
        for (String q : SCREENS) {
            RuntimeEnvironment.setQualifiers(q);
            Activity a = splash();
            Configuration c = a.getResources().getConfiguration();
            float w = widthDp(a);
            assertTrue(q + ": the window's width " + w + " (390 .. 391: an integer density)", w >= 390f && w < 391f);
            assertEquals(q + ": screenWidthDp", 390, c.screenWidthDp);
            assertEquals(q + ": smallestScreenWidthDp", 390, c.smallestScreenWidthDp);
            assertEquals(q + ": the camera chrome's k", 1f, UiScale.k(a), 0.002f);
            assertEquals(q + ": the app context too", w, widthDp(UiFixedScale.wrap(RuntimeEnvironment.getApplication())), 1e-3f);
            // the preview and the GL viewfinder keep the panel's real pixels
            assertEquals(q + ": pixels untouched", RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().widthPixels,
                    a.getResources().getDisplayMetrics().widthPixels);
        }
    }

    @Test @Config(qualifiers = "w360dp-h780dp-xxhdpi") public void landscapeUsesTheShortSide() {
        RuntimeEnvironment.setQualifiers("+land");
        Activity a = splash();
        DisplayMetrics m = a.getResources().getDisplayMetrics();
        assertEquals("the short side is the concept's", 390f, Math.min(m.widthPixels, m.heightPixels) / m.density, 1f);
    }

    @Test @Config(qualifiers = "w411dp-h914dp-560dpi") public void theSystemFontSizeDoesNotChangeOurText() {
        RuntimeEnvironment.setFontScale(1f);
        Activity normal = splash();
        float px14 = textPx(normal, 14), px20 = textPx(normal, 20);
        assertEquals("14sp at the concept's density", 14 * normal.getResources().getDisplayMetrics().density, px14, 0.01f);
        for (float scale : new float[]{0.85f, 1.3f, 2f}) {
            RuntimeEnvironment.setFontScale(scale);
            Activity a = splash();
            assertEquals("font scale " + scale, 1f, a.getResources().getConfiguration().fontScale, 0f);
            assertEquals("font scale " + scale + ": 14sp", px14, textPx(a, 14), 0.01f);
            assertEquals("font scale " + scale + ": 20sp", px20, textPx(a, 20), 0.01f);
            assertEquals("font scale " + scale + ": the app context", px14,
                    textPx(UiFixedScale.wrap(RuntimeEnvironment.getApplication()), 14), 0.01f);
        }
    }

    @Test @Config(qualifiers = "w800dp-h1280dp-xhdpi") public void aTabletKeepsItsDensityButNotTheFontScale() {
        RuntimeEnvironment.setFontScale(1.3f);
        Activity a = splash();
        assertEquals(320, a.getResources().getConfiguration().densityDpi);
        assertEquals(1f, a.getResources().getConfiguration().fontScale, 0f);
    }
}
