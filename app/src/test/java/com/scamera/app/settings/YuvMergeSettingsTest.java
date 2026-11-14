package com.scamera.app.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.scamera.app.capture.YuvMergeRoute;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * P646 «Склейка YUV»: the lens switch's default (on only for the vivo X200 Pro's front camera "1"), its stored value per module,
 * and the greying of «Шумоподавление и склейка» both ways (owner, 2026-11-12: «При включенной склейке YUV на определенном модуле
 * ... все настройки которые с ним не работают должны быть серыми»): the whole group walked from preferences.xml for the current
 * module's route on and off; a module switch changes the route and with it the greying.
 */
public class YuvMergeSettingsTest {
    private static final String H = "pref_scam_hybrid_";
    /** The rows / groups of the group the YUV route reads (the post pipeline's denoise, the ZSL buffer of its ring capture). */
    private static final Set<String> SHARED = new HashSet<>(Arrays.asList(
            "scam_hybrid_nr_merge_category", H + "denoise", H + "post_snr", H + "why_dn_luma_revert", H + "why_dn_motion_revert", H + "why_dn_grain",
            "scam_hybrid_resolve_optics_category", H + "resolve_speckle_enable", H + "resolve_speckle_threshold", H + "resolve_speckle_strength",
            "scam_hybrid_ms_general_category", H + "why_ms_denoise", H + "why_ms_model_scale", H + "why_ms_shadow",
            "scam_hybrid_ms_luma_category", H + "why_ms_luma", H + "why_ms_luma_l0", H + "why_ms_luma_l1", H + "why_ms_luma_l2",
            "scam_hybrid_ms_chroma_category", H + "why_ms_chroma", H + "why_ms_chroma_l0", H + "why_ms_chroma_l1", H + "why_ms_chroma_l2",
            "scam_hybrid_merge_frames_category", "pref_zsl_buffer_count_key",
            // P656: «Кадры ZSL» is the YUV merge's frame count since P650 (YuvMergePlan.Tuning: «YUV: кадров в серии» 0)
            H + "zsl_frames"));

    private static boolean says(String why, String ru, String en) {
        return why != null && (why.contains(ru) || why.contains(en));
    }

    private static boolean notOurs(String why) {
        return says(why, "Не действует при «Склейке YUV»", "No effect with “YUV merge”");
    }

    private static boolean onlyOurs(String why) {
        return says(why, "Только при «Склейке YUV»", "Only with “YUV merge” on");
    }

    /** Every key of «Шумоподавление и склейка» with whether it is a group (category / screen). */
    private static Map<String, Boolean> groupKeys() throws Exception {
        final org.w3c.dom.Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File("src/main/res/xml/preferences.xml"));
        final org.w3c.dom.NodeList screens = doc.getElementsByTagName("PreferenceScreen");
        Element group = null;
        for (int i = 0; i < screens.getLength(); i++) {
            final Element e = (Element) screens.item(i);
            if ("scam_hybrid_dnmerge_screen".equals(e.getAttribute("android:key"))) group = e;
        }
        assertNotNull("the group «Шумоподавление и склейка»", group);
        final Map<String, Boolean> keys = new java.util.LinkedHashMap<>();
        collect(group, keys);
        return keys;
    }

    private static void collect(Element e, Map<String, Boolean> keys) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) continue;
            final Element c = (Element) n;
            String key = c.getAttribute("android:key");
            if (key.equals("@string/pref_zsl_buffer_count_key")) key = "pref_zsl_buffer_count_key";
            final boolean isGroup = c.getTagName().equals("PreferenceCategory") || c.getTagName().equals("PreferenceScreen");
            if (!key.isEmpty()) keys.put(key, isGroup);
            if (isGroup) collect(c, keys);
        }
    }

    private static String why(SettingsAvailability a, String key, boolean group) {
        return group ? a.groupReason(key) : a.reason(key);
    }

    @Test public void theX200ProFrontCameraHasItOnByDefault() {
        assertTrue(YuvMergeRoute.x200Pro("vivo", "V2405A", "PD2405"));
        assertTrue(YuvMergeRoute.x200Pro("vivo", "V2405DA", "PD2405F"));
        assertFalse(YuvMergeRoute.x200Pro("vivo", "V2454A", "PD2454"));   // the X200 Ultra
        assertFalse(YuvMergeRoute.x200Pro("OPPO", "V2405A", "PD2405"));
        assertTrue(YuvMergeRoute.defaultOn(true, "1"));
        assertFalse(YuvMergeRoute.defaultOn(true, "0"));
        assertFalse(YuvMergeRoute.defaultOn(true, "5"));
        assertFalse(YuvMergeRoute.defaultOn(false, "1"));
        assertEquals("pref_sensorconfig_front0_yuvmerge", YuvMergeRoute.key("front0"));
    }

    @Test public void theStoredSwitchOfEachModuleWins() {
        final Map<String, Object> values = new HashMap<>();
        // nothing stored: the X200 Pro's front module on, its main off, any other phone off
        assertTrue(YuvMergeRoute.on(values, "front0", "1", true));
        assertFalse(YuvMergeRoute.on(values, "back0", "0", true));
        assertFalse(YuvMergeRoute.on(values, "front0", "1", false));
        values.put(YuvMergeRoute.key("front0"), false);
        values.put(YuvMergeRoute.key("back0"), "true");
        assertFalse(YuvMergeRoute.on(values, "front0", "1", true));
        assertTrue(YuvMergeRoute.on(values, "back0", "0", true));
        assertTrue(YuvMergeRoute.on(values, "back0", "0", false));   // on any phone once switched on
        assertFalse(YuvMergeRoute.on(values, null, "0", true));
    }

    @Test public void theGroupGreysWhatTheRouteDoesNotRead() throws Exception {
        final Map<String, Boolean> keys = groupKeys();
        assertTrue(keys.containsKey(H + "yuv_frames") && keys.containsKey("scam_hybrid_yuv_category"));
        final SettingsAvailability on = new SettingsAvailability(new HashMap<>()).yuvMerge(true);
        final SettingsAvailability off = new SettingsAvailability(new HashMap<>()).yuvMerge(false);
        final List<String> unclassified = new ArrayList<>();
        int own = 0, shared = 0, raw = 0;
        for (Map.Entry<String, Boolean> e : keys.entrySet()) {
            final String key = e.getKey();
            final boolean group = e.getValue();
            final String withYuv = why(on, key, group), withoutYuv = why(off, key, group);
            if (SettingsAvailability.yuvOwn(key)) {
                own++;
                assertNull(key + " with YUV on: " + withYuv, withYuv);
                assertTrue(key + " with YUV off: " + withoutYuv, onlyOurs(withoutYuv));
            } else if (SHARED.contains(key)) {
                shared++;
                assertFalse(key + " with YUV on: " + withYuv, notOurs(withYuv) || onlyOurs(withYuv));
                assertFalse(key + " with YUV off: " + withoutYuv, notOurs(withoutYuv) || onlyOurs(withoutYuv));
            } else if (SettingsAvailability.rawMergeOnly(key)) {
                raw++;
                assertTrue(key + " with YUV on: " + withYuv, notOurs(withYuv));
                assertFalse(key + " with YUV off: " + withoutYuv, notOurs(withoutYuv) || onlyOurs(withoutYuv));
            } else {
                unclassified.add(key);
            }
        }
        // a new row of the group must say whether the YUV route reads it (SHARED here, or SettingsAvailability.RAW_MERGE_ONLY)
        assertTrue("rows of «Шумоподавление и склейка» without a YUV role: " + unclassified, unclassified.isEmpty());
        // P692: «YUV: 10 бит» left the group for the lens page («Склейка YUV 10 бит», one switch per lens)
        assertEquals(15, own);
        assertTrue(shared >= 20 && raw >= 40);
    }

    /** P656: «Кадры ZSL» sets the YUV series since P650 (YuvMergePlan.Tuning, «YUV: кадров в серии» 0): never greyed for it. */
    @Test public void theGeneralFrameCountActsOnTheYuvMerge() {
        final SettingsAvailability on = new SettingsAvailability(new HashMap<>()).yuvMerge(true);
        assertNull(on.reason(H + "zsl_frames"));
        assertFalse(SettingsAvailability.rawMergeOnly(H + "zsl_frames"));
        // the RAW-only counts stay greyed
        assertTrue(notOurs(on.reason(H + "fullres50_frames")));
    }

    @Test public void theYuvRowsFollowTheirOwnSwitches() {
        final Map<String, Object> values = new HashMap<>();
        final SettingsAvailability a = new SettingsAvailability(values).yuvMerge(true);
        assertNull(a.reason(H + "yuv_es_ev"));
        values.put(H + "yuv_bracket", false);
        assertTrue(says(a.reason(H + "yuv_es_ev"), "брекетинг", "brackets"));
        assertTrue(says(a.reason(H + "yuv_deghost"), "брекетинг", "brackets"));
        assertNull(a.reason(H + "yuv_bracket"));
        values.put(H + "yuv_bracket", true);
        values.put(H + "yuv_capture", "zsl");
        assertTrue(says(a.reason(H + "yuv_bracket"), "после спуска", "after the shutter"));
        assertTrue(says(a.reason(H + "yuv_long_ev"), "после спуска", "after the shutter"));
        assertNull(a.reason(H + "yuv_10bit"));
        assertNull(a.reason(H + "yuv_frames"));
    }

    @Test public void aModuleSwitchChangesTheGreying() {
        // two modules of the X200 Pro: the front (camera 1, YUV by default) and the main (camera 0, RAW)
        final Map<String, Object> values = new HashMap<>();
        for (String[] module : new String[][]{{"front0", "1"}, {"back0", "0"}}) {
            final boolean route = YuvMergeRoute.on(values, module[0], module[1], true);
            final SettingsAvailability a = new SettingsAvailability(values).yuvMerge(route);
            assertEquals(module[0], route, notOurs(a.reason(H + "cdm")));
            assertEquals(module[0], route, notOurs(a.groupReason("scam_hybrid_merge_kernel_category")));
            assertEquals(module[0], !route, onlyOurs(a.reason(H + "yuv_frames")));
            assertEquals(module[0], !route, onlyOurs(a.groupReason("scam_hybrid_yuv_category")));
            assertFalse(module[0], notOurs(a.reason(H + "why_ms_denoise")));
        }
        assertTrue(YuvMergeRoute.on(values, "front0", "1", true));
        assertFalse(YuvMergeRoute.on(values, "back0", "0", true));
    }
}
