package com.particlesdevs.photoncamera.processing.opengl.postpipeline;

import android.app.Application;

import com.particlesdevs.photoncamera.processing.color.ColourLook;
import com.particlesdevs.photoncamera.processing.color.ToneCurve;
import com.particlesdevs.photoncamera.settings.PreferenceKeys;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mockStatic;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class WhyAgxSkinProtectionTest {
    private final Map<String, Float> values = new HashMap<>();
    private MockedStatic<PreferenceKeys> preferences;
    private boolean why = true;

    @Before public void setUp() {
        preferences = mockStatic(PreferenceKeys.class, call -> {
            String name = call.getMethod().getName();
            if (name.equals("hybridValue")) return values.getOrDefault(call.getArgument(0), call.getArgument(1));
            if (name.equals("hybridString")) return call.getArgument(1);
            if (name.equals("scamDevSwitch"))
                return "why_vibrance".equals(call.getArgument(0)) ? why : call.getArgument(1);
            return Mockito.RETURNS_DEFAULTS.answer(call);
        });
    }

    @After public void tearDown() {
        preferences.close();
    }

    @Test public void skinOnlyActivatesTheAgxPositiveGainBranch() {
        ColourLook look = WhyTone.colourLook();
        assertEquals(100f, look.skinProtect, 0f);
        assertFalse(look.protectsVibrance);
        assertTrue(look.shaderNeutral());
        for (float sat : new float[]{0f, .5f, 1f, 1.0005f})
            assertFalse(WhyTone.colourLookOn(look, ToneCurve.AGX, sat));
        assertTrue(WhyTone.colourLookOn(look, ToneCurve.AGX, 1.5f));
        assertFalse(WhyTone.colourLookOn(look, ToneCurve.ACES, 1.5f));
        assertFalse(WhyTone.colourLookOn(look, ToneCurve.UCHIMURA, 1.5f));
        values.put("why_skin_protect", 0f);
        assertFalse(WhyTone.colourLookOn(WhyTone.colourLook(), ToneCurve.AGX, 1.5f));
    }

    @Test public void theCommonVibranceKeepsItsOwnProtection() {
        values.put("why_skin_protect", .6f);
        ColourLook look = WhyTone.colourLook();
        assertFalse(look.protectsVibrance);
        assertEquals(.6f, look.skinUniform()[0], 1e-7f);
        assertEquals(.6f, WhyTone.vibranceSkinUniform()[0], 0f);
        assertFalse(WhyTone.colourLookOn(look, ToneCurve.AGX, 1f));
        values.put("colour_skin_hue", 10f);
        assertTrue(WhyTone.colourLookOn(WhyTone.colourLook(), ToneCurve.AGX, .5f));
        why = false;
        values.clear();
        assertTrue(WhyTone.colourLook().protectsVibrance);
        assertFalse(WhyTone.colourLookOn(WhyTone.colourLook(), ToneCurve.AGX, 1.5f));
    }

    @Test public void configuredSaturationMatchesTheActualAeWithPresetsAndMatrixCompensation() {
        float[] pixels = new float[16 * 16 * 3];
        Arrays.fill(pixels, .18f);
        WhyAe.Settings settings = new WhyAe.Settings();
        for (boolean ef : new boolean[]{false, true})
            for (int look = 0; look <= 4; look++)
                for (float sat : new float[]{.5f, 1f, 1.5f})
                    for (float ccm : new float[]{.6f, 1f, 1.1f}) {
                        settings.efEnabled = ef;
                        settings.look = look;
                        settings.sat = sat;
                        settings.ccmSat = ccm;
                        WhyAe.Result ae = WhyAe.smartHdr(FloatBuffer.wrap(pixels), 3, 16, 16, settings, 100, 6400);
                        assertEquals(ae.sat, WhyTone.agxSaturation(settings), 0f);
                    }
    }

    @Test public void theViewfinderCoversTheConfiguredAndEasedSaturation() throws Exception {
        LiveWhyTone live = new LiveWhyTone();
        WhyAe.Settings settings = new WhyAe.Settings();
        settings.sat = 1.5f;
        set(live, "settings", settings);
        set(live, "look", WhyTone.colourLook());
        set(live, "toneOp", ToneCurve.AGX);
        Method refresh = LiveWhyTone.class.getDeclaredMethod("refreshColourOn");
        refresh.setAccessible(true);
        refresh.invoke(live);
        assertTrue((Boolean) get(live, "colourOn"));
        settings.sat = 1f;
        ToneEase shown = (ToneEase) get(live, "shown");
        shown.sat = 1.2f;
        refresh.invoke(live);
        assertTrue((Boolean) get(live, "colourOn"));
        shown.sat = 1f;
        WhyAe.Result pending = new WhyAe.Result();
        pending.sat = 1.2f;
        set(live, "ae", pending);
        refresh.invoke(live);
        assertTrue((Boolean) get(live, "colourOn"));
        pending.sat = 1f;
        refresh.invoke(live);
        assertFalse((Boolean) get(live, "colourOn"));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
