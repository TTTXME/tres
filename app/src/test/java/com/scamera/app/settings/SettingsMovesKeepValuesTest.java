package com.scamera.app.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * 2026-10-27 (owner: «я разрешаю тебе полностью поменять расположение настроек», «но функционал теряться не должен при
 * упрощении»): the settings may move anywhere, but every row keeps its stored key, its type, its default and range, its
 * entries, its dependency, its keyboard and whether it is a tap. The frozen rows (src/test/resources/settings_rows_frozen.tsv)
 * are the value rows of preferences.xml before the new layout; each must still exist with identical attributes - only «Стиль
 * снимка → Плёночный LUT» (photo_style_lut, a link that stored nothing; its page sits in the same group now) is gone. New rows
 * are allowed.
 * The file is regenerated only for an owner-approved change of a default or a range.
 */
public class SettingsMovesKeepValuesTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android", APP = "http://schemas.android.com/apk/res-auto";
    /**
     * The frozen columns after the key and the tag: {attribute, namespace} (inputType: the text rows' keyboard; selectable: the
     * notes that are no tap).
     */
    private static final String[][] COLUMNS = {{"defaultValue", ANDROID}, {"minValue", APP}, {"maxValue", APP}, {"stepPerUnit", APP},
            {"isFloat", APP}, {"entries", ANDROID}, {"entryValues", ANDROID}, {"dependency", ANDROID}, {"persistent", ANDROID},
            {"inputType", ANDROID}, {"selectable", ANDROID}};
    /**
     * The rows removed on purpose: the 2026-10-27 layout's empty LUT link (no stored value, no action left); P419 the «Цвет» shadow /
     * light vibrance and its skin protection, duplicates of the reference app's vibrance rows (SettingsMigration moves their values
     * into why_vibrance_shadows / why_vibrance_hl_str once; why_skin_protect is the one protection); P443 the SCAM 9.6 denoise's
     * tuning rows (its tables, multipliers, noise model and safeguards).
     */
    private static final List<String> REMOVED = java.util.Arrays.asList("pref_scam_hybrid_despeckle", "photo_style_lut", "pref_scam_hybrid_colour_vib_shadows",
            "pref_scam_hybrid_colour_vib_highlights", "pref_scam_hybrid_colour_skin_protect",
            // P421: the hybrid's NLM engine and its two strengths, the manual SNR and «Карта силы» (scam_dev keys now)
            "pref_scam_hybrid_dn_engine", "pref_scam_hybrid_post_luma", "pref_scam_hybrid_post_chroma", "pref_scam_hybrid_dn_snr",
            "pref_scam_hybrid_dn_strength_map",
            // P443: the SCAM 9.6 engine's tuning rows (the Resolve engine is the default): the numbers are scam_dev keys, the tables
            // their built-in defaults; a value stored before stays
            "pref_scam_hybrid_bento_denoise_max", "pref_scam_hybrid_dn_chroma_2x_keep", "pref_scam_hybrid_dn_chroma_floor", "pref_scam_hybrid_dn_chroma_mult",
            "pref_scam_hybrid_dn_chroma_t1_outlier", "pref_scam_hybrid_dn_chroma_t1_snr", "pref_scam_hybrid_dn_chroma_t1_strength", "pref_scam_hybrid_dn_chroma_t2_outlier",
            "pref_scam_hybrid_dn_chroma_t2_snr", "pref_scam_hybrid_dn_chroma_t2_strength", "pref_scam_hybrid_dn_chroma_t3_outlier", "pref_scam_hybrid_dn_chroma_t3_snr",
            "pref_scam_hybrid_dn_chroma_t3_strength", "pref_scam_hybrid_dn_chroma_t4_outlier", "pref_scam_hybrid_dn_chroma_t4_snr", "pref_scam_hybrid_dn_chroma_t4_strength",
            "pref_scam_hybrid_dn_coarse_stock", "pref_scam_hybrid_dn_dark_fade", "pref_scam_hybrid_dn_luma_gid14_mult", "pref_scam_hybrid_dn_luma_mult",
            "pref_scam_hybrid_dn_luma_t1_outlier", "pref_scam_hybrid_dn_luma_t1_revert", "pref_scam_hybrid_dn_luma_t1_snr", "pref_scam_hybrid_dn_luma_t1_strength",
            "pref_scam_hybrid_dn_luma_t2_outlier", "pref_scam_hybrid_dn_luma_t2_revert", "pref_scam_hybrid_dn_luma_t2_snr", "pref_scam_hybrid_dn_luma_t2_strength",
            "pref_scam_hybrid_dn_luma_t3_outlier", "pref_scam_hybrid_dn_luma_t3_revert", "pref_scam_hybrid_dn_luma_t3_snr", "pref_scam_hybrid_dn_luma_t3_strength",
            "pref_scam_hybrid_dn_luma_t4_outlier", "pref_scam_hybrid_dn_luma_t4_revert", "pref_scam_hybrid_dn_luma_t4_snr", "pref_scam_hybrid_dn_luma_t4_strength",
            "pref_scam_hybrid_dn_luma_t5_outlier", "pref_scam_hybrid_dn_luma_t5_revert", "pref_scam_hybrid_dn_luma_t5_snr", "pref_scam_hybrid_dn_luma_t5_strength",
            "pref_scam_hybrid_dn_model_read", "pref_scam_hybrid_dn_model_shot", "pref_scam_hybrid_dn_revert_max", "pref_scam_hybrid_dn_revert_mult",
            "pref_scam_hybrid_dn_sabre_luma_mult", "pref_scam_hybrid_dn_snr_scale", "pref_scam_hybrid_why_chroma_denoise",
            // P628 (owner, 2026-11-11: «убери по дефолту надпись "Shot on"»): the two caption lines are the one custom text
            // (pref_watermark_text, default «SCAMERA»); SettingsMigration.migrateWatermark carries a typed line over once
            "pref_watermark_line1", "pref_watermark_line2");

    private static File src(String path) {
        File f = new File(path);
        return f.exists() ? f : new File("app/" + path);
    }

    /** key -> the frozen line (tag and attributes, "-" for an absent attribute). */
    private static Map<String, String> frozen() throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream in = SettingsMovesKeepValuesTest.class.getClassLoader().getResourceAsStream("settings_rows_frozen.tsv")) {
            assertNotNull("settings_rows_frozen.tsv", in);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] cells = line.split("\t", -1);
                assertEquals(line, 2 + COLUMNS.length, cells.length);
                assertFalse("frozen twice: " + cells[0], out.containsKey(cells[0]));
                out.put(cells[0], line.substring(cells[0].length() + 1));
            }
        }
        return out;
    }

    /** key -> the same line built from today's preferences.xml (every keyed element but a page or a category). */
    private static Map<String, String> current() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Element root = factory.newDocumentBuilder().parse(src("src/main/res/xml/preferences.xml")).getDocumentElement();
        Map<String, String> out = new LinkedHashMap<>();
        List<Element> all = new ArrayList<>();
        collect(root, all);
        for (Element e : all) {
            if (e == root || !e.hasAttributeNS(ANDROID, "key")) continue;
            String tag = e.getTagName();
            if (tag.equals("PreferenceScreen") || tag.equals("PreferenceCategory")) continue;
            String key = e.getAttributeNS(ANDROID, "key");
            // The stored key is written out (the key string resources are gone), so the XML shows what is stored.
            assertFalse("key through a resource: " + key, key.startsWith("@string/"));
            StringBuilder line = new StringBuilder(tag);
            for (String[] c : COLUMNS) line.append('\t').append(e.hasAttributeNS(c[1], c[0]) ? e.getAttributeNS(c[1], c[0]) : "-");
            assertFalse("key used twice in preferences.xml: " + key, out.containsKey(key));
            out.put(key, line.toString());
        }
        return out;
    }

    private static void collect(Node node, List<Element> out) {
        if (node instanceof Element) out.add((Element) node);
        for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling()) collect(c, out);
    }

    @Test public void everyFrozenRowKeepsItsKeyTypeDefaultAndRange() throws Exception {
        Map<String, String> before = frozen(), now = current();
        assertTrue("frozen rows: " + before.size(), before.size() > 350);
        List<String> lost = new ArrayList<>(), changed = new ArrayList<>();
        for (Map.Entry<String, String> e : before.entrySet()) {
            if (REMOVED.contains(e.getKey())) continue;
            String today = now.get(e.getKey());
            if (today == null) lost.add(e.getKey());
            else if (!today.equals(e.getValue())) changed.add(e.getKey() + "\n  was " + e.getValue() + "\n  now " + today);
        }
        assertTrue("rows lost: " + lost, lost.isEmpty());
        assertTrue("rows changed: " + changed, changed.isEmpty());
    }

    @Test public void onlyTheEmptyLutLinkWasRemoved() throws Exception {
        Map<String, String> before = frozen(), now = current();
        for (String key : REMOVED) {
            assertTrue("frozen " + key, before.containsKey(key));
            assertFalse("still in preferences.xml: " + key, now.containsKey(key));
        }
    }

    /**
     * The stored text of every PreferenceKeys.Key, as the string resources gave it before the keys became literals: users'
     * settings and backups are keyed by it.
     */
    @Test public void keyEnumKeepsTheStoredKeys() {
        String[] expected = {
                "KEY_PREF_VERSION", "_pref_version",
                "KEY_SAVE_PER_LENS_SETTINGS", "pref_save_per_lens_settings",
                "KEY_SHOW_WATERMARK", "pref_show_watermark_key",
                "KEY_SHOW_ROUND_EDGE", "pref_show_roundedge_key",
                "KEY_SHOW_GRID", "pref_show_grid_key",
                "KEY_CAMERA_SOUNDS", "pref_camera_sounds_key",
                "KEY_TIMER_SOUND", "pref_timer_sound_key",
                "KEY_LIVE_VIEWFINDER_RAW", "pref_live_viewfinder_raw_key",
                "KEY_WIDE169", "pref_wide169_key",
                "KEY_ZSL_BUFFER_COUNT", "pref_zsl_buffer_count_key",
                "KEY_COLOR_METHOD", "pref_color_method_key",
                "KEY_FOCUS_PEAK", "pref_peak_method_key",
                "KEY_THEME", "pref_theme_key",
                "KEY_THEME_ACCENT", "pref_theme_accent_key",
                "KEY_HIDE_GALLERY_ICON", "pref_hide_gallery_icon_key",
                "KEY_AF_MODE", "pref_af_mode_key",
                "KEY_AE_MODE", "pref_ae_mode_key",
                "KEY_AE_METERING_STD", "pref_ae_metering_std_mode_key",
                "KEY_BRACKETING_MODE", "pref_bracketing_mode_key",
                "KEY_COUNTDOWN_TIMER", "pref_countdown_timer_key",
                "KEY_SHOW_AF_DATA", "pref_show_afdata_key",
                "KEY_SHOW_HORIZON", "pref_horizon",
                "KEY_SAVE_RAW", "pref_save_raw_key",
                "KEY_CFA", "pref_cfa_key",
                "KEY_REMOSAIC", "pref_remosaic_key",
                "KEY_EIS_PHOTO", "pref_eis_photo_key",
                "KEY_FPS_PREVIEW", "pref_fps_preview_key",
                "KEY_ULTRAHDR", "pref_ultrahdr_key",
                "CAMERA_ID", "camera_id",
                "TONEMAP", "tonemap_key",
                "GAMMA", "gamma_key",
                "CAMERA_MODE", "pref_camera_mode_key",
                "CAMERAS_PREFERENCE_FILE_NAME", "_cameras",
                "ALL_CAMERA_IDS_KEY", "all_camera_ids",
                "FRONT_IDS_KEY", "front_camera_ids",
                "BACK_IDS_KEY", "back_camera_ids",
                "ALL_CAMERA_LENS_KEY", "all_camera_lens",
                "CAMERA_COUNT_KEY", "all_camera_count",
                "DEVICES_PREFERENCE_FILE_NAME", "_devices",
                "PER_LENS_FILE_NAME", "_per_lens",
                "FOLDERS_LIST", "pref_folders_list"};
        assertEquals(expected.length / 2, PreferenceKeys.Key.values().length);
        for (int i = 0; i < expected.length; i += 2)
            assertEquals(expected[i], expected[i + 1], PreferenceKeys.Key.valueOf(expected[i]).mValue);
    }
}
