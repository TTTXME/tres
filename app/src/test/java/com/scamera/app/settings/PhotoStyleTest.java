package com.scamera.app.settings;

import android.app.Application;
import android.content.SharedPreferences;

import com.scamera.app.processing.color.FilmLut;
import com.scamera.app.processing.color.FilmSim;
import com.scamera.app.processing.opengl.postpipeline.WhyV2Ae;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * SIMPLE_UI §4 «Стиль снимка», P718: the tone presets Насыщенный / Обычный / Нейтральный (owner, 2026-11-14), stored on their own,
 * «Why тон» V2 with every preset; black and white and film are rows of their own, untouched by the style.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class PhotoStyleTest {
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("photo_style_test", 0);
        prefs.edit().clear().commit();
    }

    /** The cards' order (owner: «Порядок плиток сделай Насыщенный/Обычный/Нейтральный»), Normal the default, the shade cycles it. */
    @Test
    public void threePresetsInTheOwnersOrder() {
        assertArrayEquals(new int[]{PhotoStyle.VIVID, PhotoStyle.NORMAL, PhotoStyle.NEUTRAL}, PhotoStyle.order());
        assertEquals(PhotoStyle.NORMAL, PhotoStyle.current(prefs));
        assertEquals(PhotoStyle.NEUTRAL, PhotoStyle.next(PhotoStyle.NORMAL));
        assertEquals(PhotoStyle.VIVID, PhotoStyle.next(PhotoStyle.NEUTRAL));
        assertEquals(PhotoStyle.NORMAL, PhotoStyle.next(PhotoStyle.VIVID));
        for (int s : PhotoStyle.order()) assertEquals(s, PhotoStyle.of(PhotoStyle.id(s)));
        assertEquals("an unknown value is Normal", PhotoStyle.NORMAL, PhotoStyle.of("film"));
    }

    /** Every preset puts «Why тон» on V2 and touches nothing else. */
    @Test
    public void choosingAPresetTakesTheToneToV2() {
        for (int s : PhotoStyle.order()) {
            prefs.edit().putString(PhotoStyle.TONE_VERSION, "v1").commit();
            PhotoStyle.apply(prefs, s);
            assertEquals(s, PhotoStyle.current(prefs));
            assertEquals("v2", prefs.getString(PhotoStyle.TONE_VERSION, ""));
            assertEquals(2, prefs.getAll().size());
        }
    }

    /**
     * Migration: a user of the old Ч/б or Плёнка style (derived from the filter, the LUT and the stock) reads Обычный, and their
     * black-and-white filter, film LUT and film stock stay as they are - whatever preset is chosen afterwards.
     */
    @Test
    public void blackAndWhiteAndFilmStayUntouched() {
        final String portra = FilmSim.STOCKS[0].id;
        prefs.edit().putString("pref_scam_hybrid_colour_mono", "red").putString(FilmLut.KEY, "scam:cine500")
                .putString(FilmSim.STOCK_KEY, portra).putString("ui_style_last_mono", "yellow").commit();
        assertEquals(PhotoStyle.NORMAL, PhotoStyle.current(prefs));
        for (int s : new int[]{PhotoStyle.VIVID, PhotoStyle.NEUTRAL, PhotoStyle.NORMAL}) {
            PhotoStyle.apply(prefs, s);
            assertEquals("red", prefs.getString("pref_scam_hybrid_colour_mono", ""));
            assertEquals("scam:cine500", prefs.getString(FilmLut.KEY, ""));
            assertEquals(portra, prefs.getString(FilmSim.STOCK_KEY, ""));
        }
    }

    /** The presets are factors on the user's own rows: Normal leaves them, Vivid lifts the HDR terms by 30 %, Neutral calms them. */
    @Test
    public void presetsScaleTheUsersToneRows() {
        final WhyV2Ae.Params user = new WhyV2Ae.Params();
        user.shadowStr = 1.5f;
        user.hlBoost = 1.2f;
        user.macroContrast = 1.2f;
        user.vibStrength = 0.1f;
        final WhyV2Ae.Params n = copy(user), v = copy(user), q = copy(user);
        WhyV2Ae.applyStyle(n, PhotoStyle.NORMAL);
        WhyV2Ae.applyStyle(v, PhotoStyle.VIVID);
        WhyV2Ae.applyStyle(q, PhotoStyle.NEUTRAL);
        assertEquals(1.5f, n.shadowStr, 0f);
        assertEquals(1.2f, n.macroContrast, 0f);
        assertEquals(1.5f * 1.7f, v.shadowStr, 1e-6f);
        assertEquals(1.2f * 1.6f, v.hlBoost, 1e-6f);
        assertEquals(user.highlightStr * 1.6f, v.highlightStr, 1e-6f);
        assertTrue("more contrast", v.macroContrast > 1.2f);
        assertTrue("richer muted colours", v.vibStrength > 0.1f && v.vibStrength - 0.1f > v.vibSaturated - user.vibSaturated);
        assertEquals(1.5f * 0.85f, q.shadowStr, 1e-6f);
        assertEquals("Neutral keeps the highlight compression (less of it clips)", 1.2f, q.hlBoost, 0f);
        assertEquals(1f + 0.2f * 0.3f, q.macroContrast, 1e-6f);
        assertTrue(q.vibStrength < 0.1f && q.vibSky < user.vibSky && q.contrastBoost < user.contrastBoost);
    }

    private static WhyV2Ae.Params copy(WhyV2Ae.Params p) {
        final WhyV2Ae.Params c = new WhyV2Ae.Params();
        c.shadowStr = p.shadowStr;
        c.hlBoost = p.hlBoost;
        c.macroContrast = p.macroContrast;
        c.vibStrength = p.vibStrength;
        return c;
    }
}
