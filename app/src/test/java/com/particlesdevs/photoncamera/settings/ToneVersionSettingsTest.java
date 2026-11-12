package com.particlesdevs.photoncamera.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P614 (owner, 2026-11-11: «Сделай так чтобы в этом меню можно было настраивать V1 и V2, но когда выбирается например V1, то
 * недоступные для него настройки были не активными и наоборот»): which rows and groups of «Настройки изображения» each «Why тон»
 * version greys (SettingsAvailability.toneVersionReason), the merged rows active with both.
 */
public class ToneVersionSettingsTest {
    private static SettingsAvailability with(String version) {
        final Map<String, Object> values = new HashMap<>();
        if (version != null) values.put("pref_scam_hybrid_tone_version", version);
        return new SettingsAvailability(values);
    }

    private static String xml() throws IOException {
        return new String(Files.readAllBytes(Paths.get("src/main/res/xml/preferences.xml")), StandardCharsets.UTF_8);
    }

    @Test public void eachVersionGreysTheOthersRows() {
        final SettingsAvailability v1 = with(null), v1Set = with("v1"), v2 = with("v2");
        // V2's own rows: greyed with V1 (the default), active with V2
        for (String own : new String[]{"agx_look", "agx_hue_lock", "clarity", "skin_hue_center", "hr_amount"}) {
            final String key = "pref_scam_hybrid_why_v2_" + own;
            assertNotNull(key, v1.reason(key));
            assertNotNull(key, v1Set.reason(key));
            assertTrue(key, v1.reason(key).contains("V2"));
            assertNull(key, v2.reason(key));
        }
        // V1's own rows, groups and pages: active with V1, greyed with V2
        for (String key : new String[]{"pref_scam_hybrid_why_sharp_gain", "pref_scam_hybrid_why_metering", "pref_scam_hybrid_why_night_dim",
                "pref_scam_hybrid_why_agx_look", "pref_scam_hybrid_colour_hue_lock", "pref_scam_hybrid_why_curve_boost",
                "pref_scam_hybrid_why_fusion_layout", "pref_scam_hybrid_why_weight_dark2", "pref_scam_hybrid_why_weight_center",
                "pref_scam_hybrid_why_clarity", "pref_scam_hybrid_why_skin_hue_center", "pref_scam_hybrid_colour_mono",
                "pref_scam_hybrid_why_portrait_curve_lift", "pref_scam_hybrid_why_face_smooth", "pref_scam_hybrid_colour_mix_red_hue",
                "pref_scam_hybrid_colour_grade_shadows_str", "pref_scam_hybrid_why_hl_soft", "pref_scam_hybrid_why_flat_protect",
                "scam_hybrid_why_weights_category", "portrait_mask_screen", "colour_mixer_screen"}) {
            assertNull(key, v1.reason(key));
            assertNotNull(key, v2.reason(key));
            assertTrue(key, v2.reason(key).contains("V1"));
        }
        // the rows both read (WhyV2Stats.MERGED and the stages around the tone): active with either
        for (String key : new String[]{"pref_scam_hybrid_why_ae_target", "pref_scam_hybrid_why_macro_contrast", "pref_scam_hybrid_why_agx_sat",
                "pref_scam_hybrid_colour_warmth", "pref_scam_hybrid_why_dn_factor", "pref_scam_hybrid_why_input_ev",
                "pref_scam_hybrid_why_night_thresh", "pref_scam_hybrid_why_classic_weight_mid", "pref_scam_hybrid_why_blend_smoothness",
                "pref_scam_hybrid_why_curve_points", "pref_scam_hybrid_why_curve_lift", "pref_scam_hybrid_why_curve_on",
                "pref_scam_hybrid_why_aces_on", "pref_scam_hybrid_why_vibrance_hl_str", "pref_scam_hybrid_why_skin_hue_shift",
                "pref_scam_hybrid_why_hr_room", "pref_scam_hybrid_why_bracket_denoise", "pref_scam_hybrid_why_sharp_usm_amount",
                "pref_scam_hybrid_colour_tint", "pref_scam_hybrid_why_texture", "scam_hybrid_why_classic_weights_category"}) {
            assertNull(key, v1.reason(key));
            assertNull(key, v2.reason(key));
        }
        // a group greys as a whole only for the version
        assertNotNull(v2.groupReason("portrait_face_category"));
        assertNull(v1.groupReason("portrait_face_category"));
        assertNotNull(v1.groupReason("pref_scam_hybrid_why_v2_clarity"));
        assertNull(v2.groupReason("scam_hybrid_why_curve_category"));
        assertNull(v1.groupReason(null));
    }

    @Test public void agxLookRulesFollowTheVersion() {
        // V1: a look other than «Custom» sets the AgX values itself; V2's AgX reads its rows whatever V1's look says
        final Map<String, Object> values = new HashMap<>();
        values.put("pref_scam_hybrid_why_agx_look", "1");
        values.put("pref_scam_hybrid_why_curve_on", false);
        assertNotNull(new SettingsAvailability(values).reason("pref_scam_hybrid_why_agx_slope"));
        assertNotNull(new SettingsAvailability(values).reason("pref_scam_hybrid_why_agx_sat"));
        values.put("pref_scam_hybrid_tone_version", "v2");
        assertNull(new SettingsAvailability(values).reason("pref_scam_hybrid_why_agx_slope"));
        assertNull(new SettingsAvailability(values).reason("pref_scam_hybrid_why_agx_sat"));
        assertNull(new SettingsAvailability(values).reason("pref_scam_hybrid_why_v2_agx_look"));
        // another operator: the AgX rows of both versions give way (V2's operator is V1's switches)
        values.put("pref_scam_hybrid_why_curve_on", true);
        assertNotNull(new SettingsAvailability(values).reason("pref_scam_hybrid_why_agx_slope"));
        assertNotNull(new SettingsAvailability(values).reason("pref_scam_hybrid_why_v2_agx_look"));
        assertNull(new SettingsAvailability(values).reason("pref_scam_hybrid_why_v2_agx_hue_lock"));   // V2's lock: every operator
        // «Все шумодавы» off greys the short frame denoise for both (V2 follows the switch)
        values.put("pref_scam_hybrid_denoise", false);
        assertNotNull(new SettingsAvailability(values).reason("pref_scam_hybrid_why_bracket_denoise"));
    }

    @Test public void theVersionListsNameRealRowsOfThePage() throws IOException {
        final String xml = xml();
        for (String key : SettingsAvailability.V1_ONLY)
            assertTrue(key, xml.contains("android:key=\"" + key + "\""));
        // V2's own rows sit in V1's groups of «Настройки изображения», no page of their own
        assertFalse(xml.contains("scam_hybrid_why_v2_screen"));
        final Matcher m = Pattern.compile("android:key=\"(pref_scam_hybrid_why_v2_[a-z0-9_]+)\"").matcher(xml);
        int n = 0;
        while (m.find()) {
            n++;
            assertTrue(m.group(1), SettingsAvailability.v2Only(m.group(1)));
            assertFalse(m.group(1), SettingsAvailability.v1Only(m.group(1)));
        }
        assertEquals(5, n);
        // P629b: in «Настройки тона» (the group under «Why тон») or, the colour's skin row, in «Настройки изображения»
        final int page = xml.indexOf("android:key=\"root_colour\""), lab = xml.indexOf("android:key=\"root_lab\"");
        final int tone = xml.indexOf("<PreferenceScreen android:key=\"tone_settings_screen\""), toneEnd = xml.indexOf("android:key=\"root_lenses\"");
        assertTrue(tone > xml.indexOf("android:key=\"pref_scam_hybrid_tone_version\"") && toneEnd > tone);
        final Matcher own = Pattern.compile("android:key=\"pref_scam_hybrid_why_v2_").matcher(xml);
        int inTone = 0;
        while (own.find()) {
            final boolean t = own.start() > tone && own.start() < toneEnd;
            if (t) inTone++;
            assertTrue(t || own.start() > page && own.start() < lab);
        }
        assertEquals(4, inTone);   // the AgX look and lock, the local clarity, the highlight colour; the skin hue with the colour
    }

    /**
     * P629b (owner, 2026-11-11: «У нас под V1/V2 должна находиться группа настроек для тонмапинга»): one group «Настройки тона» right
     * under «Why тон» holds the tone - the three tone pages (WhyStats / WhyV2Stats AE, WhyTone's operators and curve, WhyFusion /
     * WhyCombine local tone), the shoulder (WhyTone / WhyV2Stats hr_*), the tone's highlight passes and the short frames' denoise
     * (WhyCombine hl_*, WhyV2Highlights hr_amount, WhyFusion bracket_denoise), V1's detail / colour combine - every row once; what is not
     * tone (colour, style, sharpening, portrait, frames) and the everyday sliders stay in «Настройки изображения».
     */
    @Test public void theToneGroupSitsUnderTheSwitch() throws IOException {
        final String xml = xml();
        final int version = xml.indexOf("android:key=\"pref_scam_hybrid_tone_version\"");
        final int group = xml.indexOf("<PreferenceScreen android:key=\"tone_settings_screen\"");
        assertTrue(version > 0 && group > version);
        assertFalse(xml.substring(version + 1, group).contains("android:key="));
        final int end = xml.indexOf("android:key=\"root_lenses\"");
        final String tone = xml.substring(group, end), image = xml.substring(xml.indexOf("android:key=\"root_colour\""),
                xml.indexOf("android:key=\"root_lab\""));
        final Matcher keys = Pattern.compile("android:key=\"([^\"]+)\"").matcher(tone);
        int rows = 0;
        while (keys.find()) {
            rows++;
            final String key = keys.group(1);
            assertEquals(key, 1, xml.split("android:key=\"" + key + "\"", -1).length - 1);
            assertFalse(key, image.contains("android:key=\"" + key + "\""));
        }
        assertTrue("rows " + rows, rows > 90);
        for (String key : new String[]{"scam_hybrid_why_ae_screen", "scam_hybrid_why_curves_screen", "scam_hybrid_why_tone_screen",
                "pref_scam_hybrid_why_metering", "pref_scam_hybrid_why_input_ev", "pref_scam_hybrid_why_agx_look", "pref_scam_hybrid_why_v2_agx_look",
                "pref_scam_hybrid_why_curve_points", "pref_scam_hybrid_why_aces_on", "pref_scam_hybrid_why_uchi_on", "pref_scam_hybrid_why_fusion_layout",
                "pref_scam_hybrid_why_v2_clarity", "pref_scam_hybrid_why_texture", "pref_scam_hybrid_why_hr_strength", "pref_scam_hybrid_why_hr_room",
                "pref_scam_hybrid_why_hl_soft", "pref_scam_hybrid_why_hl_white", "pref_scam_hybrid_why_v2_hr_amount",
                "pref_scam_hybrid_why_bracket_denoise", "pref_scam_hybrid_why_delta_chroma", "pref_scam_hybrid_why_flat_protect",
                "scam_hybrid_why_weights_category", "scam_hybrid_why_artifacts_category"})
            assertTrue(key, tone.contains("android:key=\"" + key + "\""));
        // every AgX / ACES / Uchimura / own-curve row is the tone's (the portrait's own curve is the portrait's)
        final Matcher operators = Pattern.compile("android:key=\"(pref_scam_hybrid_why_(agx|aces|uchi|curve|hr)_[a-z0-9_]+)\"").matcher(xml);
        while (operators.find())
            assertTrue(operators.group(1), tone.contains(operators.group()));
        // P629c (owner, 2026-11-11: «Ползунки перенеси в настройки тона»): the three everyday tone sliders lead the group
        final int ae = tone.indexOf("android:key=\"pref_scam_hybrid_why_ae_target\""), contrast = tone.indexOf("android:key=\"pref_scam_hybrid_why_macro_contrast\""),
                sat = tone.indexOf("android:key=\"pref_scam_hybrid_why_agx_sat\""), first = tone.indexOf("android:key=", tone.indexOf("tone_settings_screen") + 30);
        assertTrue(ae == first && contrast > ae && sat > contrast && sat < tone.indexOf("android:key=\"scam_hybrid_why_ae_screen\""));
        // kept in «Настройки изображения»: the other everyday sliders, the style, the colour, the portrait, the sharpening
        for (String key : new String[]{"pref_scam_hybrid_colour_warmth", "pref_scam_hybrid_why_sharp_gain", "pref_scam_hybrid_why_dn_factor", "photo_style_screen",
                "photo_processing_screen", "portrait_screen", "scam_hybrid_why_sharp_screen", "scam_hybrid_why_rl_screen", "scam_hybrid_hdr_screen",
                "scam_hybrid_artifacts_screen"})
            assertTrue(key, image.contains("android:key=\"" + key + "\""));
        // the V1-only groups of the tone that grey as a whole are in it
        for (String key : SettingsAvailability.V1_ONLY)
            if (key.startsWith("scam_hybrid_why_")) assertTrue(key, tone.contains("android:key=\"" + key + "\""));
    }
}
