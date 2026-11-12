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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P629 (owner, 2026-11-11: «Группу шумоподавление и склейка перемести под выбор склейки Быстро/Качество/Статика как мы сделали с
 * настройками тонмапинга»): which rows and groups of «Шумоподавление и склейка» each processing mode greys
 * (SettingsAvailability.routeReason), the rows every mode reads active with every mode - and the route of each row as the worker's
 * merge routes read it (scam-fast-merge.h against the SCAM merge of scam-hybrid.h). P644: «Статика» is gone (its route
 * scam-static-*.h, its rows and group, its note) - the checks of «Быстрый» and «Качество» are as before, the kernel's footprints
 * (read by both routes) now act in every mode.
 */
public class MergeRouteSettingsTest {
    private static final String H = "pref_scam_hybrid_";
    /** The processing modes as stored; null = nothing stored («Быстрый», the default), "x" = an unknown value (read as «Быстрый»). */
    private static final String[] FAST = {null, "fast", "x"};

    private static SettingsAvailability with(String mode) {
        final Map<String, Object> values = new HashMap<>();
        if (mode != null) values.put(H + "processing", mode);
        return new SettingsAvailability(values);
    }

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    /** The SCAM merge's rows: the HybridTuning member each one's tuning line sets (PreferenceKeys TUNING_KEYS / TUNING_EXTRA). */
    private static final String[][] QUALITY_MEMBERS = {
            {"cdm", "cdm"}, {"filter_variance", "filterVariance"}, {"dilate", "dilateScale"}, {"dilate_floor", "dilateFloor"},
            {"motion_boost", "boostEnable"}, {"boost_value", "boost"}, {"boost_threshold", "varianceThreshold"},
            {"motion_threshold", "motionThreshold"}, {"chroma_diff", "chromaDiff"}, {"chroma_diff_clamp", "chromaDiffClamp"},
            {"dezipper", "dezipper"}, {"hot_sigma", "hotSigma"}, {"hot_base_sigma", "hotBaseSigma"}, {"hot_max_key", "hotMaxKey"},
            {"cell_clip", "cellClip"}, {"sabre61", "sabre61"}, {"s61_max_key", "s61MaxKey"}, {"s61_min_motion", "s61MinMotion"},
            {"kernel", "kernelScale"}, {"snr_scale", "snrScale"}, {"tensor_noise", "rawNoise"}, {"widen_below", "widenBelow"},
            {"weight_cap", "weightCap"}, {"fwe", "fwe"}, {"mochi", "mochi"}, {"local_align", "localAlign"}, {"la_engine", "laGpu"},
            // «Спорт»'s values of the rejection set the same members (MotionMode.DEFAULTS)
            {"mm_motion_boost", "boostEnable"}, {"mm_boost_value", "boost"}, {"mm_motion_threshold", "motionThreshold"}};
    /** Rows every mode reads: the denoise after the merge, the burst's frames and «Спорт»'s capture, Quad / Tetra. */
    private static final String[] EVERY_MODE = {H + "denoise", H + "why_dn_factor", H + "why_dn_luma_revert", H + "why_dn_motion_revert",
            H + "why_dn_grain", H + "resolve_speckle_enable", H + "resolve_speckle_threshold", H + "resolve_speckle_strength",
            H + "why_ms_denoise", H + "why_ms_model_scale", H + "why_ms_shadow", H + "why_ms_luma", H + "why_ms_luma_l0", H + "why_ms_chroma",
            H + "why_ms_chroma_l2", H + "zsl_frames", H + "fullres50_frames", H + "fullres50_zsl", H + "fullres200_frames", "pref_zsl_buffer_count_key",
            H + "mm_shutter", H + "mm_flicker", H + "mm_zsl_frames", H + "mm_shasta", H + "mm_bento",
            H + "mosaic_remosaic", H + "mosaic_path", H + "mosaic_frames", H + "mosaic_window", H + "mosaic_tetra", H + "mosaic_chroma_fill",
            "scam_hybrid_nr_merge_category", "scam_hybrid_resolve_optics_category", "scam_hybrid_ms_general_category",
            "scam_hybrid_ms_luma_category", "scam_hybrid_ms_chroma_category", "scam_hybrid_merge_frames_category",
            "scam_hybrid_merge_kernel_category", "scam_hybrid_merge_quad_category", "scam_hybrid_mosaic_native_category",
            // outside the group: the processing mode, the tone, the HDR frames
            H + "processing", H + "why_ae_target", H + "bento", H + "shasta"};

    @Test public void eachModeGreysTheOtherRoutesRows() {
        final SettingsAvailability quality = with("quality");
        // the SCAM merge's rows: active with «Качество», greyed with «Быстрый» (P627: it hands no shot to it, so the note promises no
        // Tetra or moving-scene exception)
        for (String[] row : QUALITY_MEMBERS) {
            final String key = H + row[0];
            assertNull(key, quality.routeReason(key));   // (its own conditions aside: HybridSettingsTest)
            for (String fast : FAST) {
                final String why = with(fast).reason(key);
                assertNotNull(key + " with " + fast, why);
                assertTrue(why, says(why, "«Качество»", "“Quality”") && !why.contains("Tetra"));
            }
            for (String fast : FAST) {
                final String why = with(fast).reason(key);
                assertTrue(why, !why.contains("движется") && !why.contains("moving"));
            }
        }
        assertNull(quality.routeReason(H + "cre_source"));
        assertNotNull(with(null).reason(H + "cre_source"));
        // the kernel's footprints: the fast route reads them too - active in both modes
        for (String own : new String[]{"kernel_texture", "kernel_bokeh", "kernel_transition"}) {
            for (String fast : FAST) assertNull(own, with(fast).reason(H + own));
            assertNull(own, quality.reason(H + own));
        }
        // the rows and groups every mode reads
        for (String key : EVERY_MODE)
            for (String mode : new String[]{null, "fast", "quality"}) {
                assertNull(key + " with " + mode, with(mode).routeReason(key));
                assertNull(key + " with " + mode, with(mode).groupReason(key));
                // «Спорт»'s rows act with the mode on only (its own rule)
                if (!key.startsWith(H + "mm_")) assertNull(key + " with " + mode, with(mode).reason(key));
            }
        final Map<String, Object> sport = new HashMap<>();
        sport.put("pref_motion_mode", true);
        for (String mode : new String[]{"fast", "quality"}) {
            sport.put(H + "processing", mode);
            assertNull(mode, new SettingsAvailability(sport).reason(H + "mm_shutter"));
            assertNull(mode, new SettingsAvailability(sport).reason(H + "mm_zsl_frames"));
            assertEquals(mode, mode.equals("quality"), new SettingsAvailability(sport).reason(H + "mm_boost_value") == null);
        }
    }

    @Test public void wholeGroupsGreyAsOne() {
        for (String group : new String[]{"scam_hybrid_merge_align_category", "scam_hybrid_merge_reject_category",
                "scam_hybrid_merge_final_category"}) {
            assertNull(group, with("quality").groupReason(group));
            assertNotNull(group, with(null).groupReason(group));
            // the note under the group's title is its rows' own: not repeated on each (SettingsActivity.applyAvailability)
            assertEquals(with(null).groupReason(group), with(null).reason(H + "cdm"));
        }
        // the tone version still greys its own groups first
        final Map<String, Object> v2 = new HashMap<>();
        v2.put("pref_scam_hybrid_tone_version", "v2");
        assertNotNull(new SettingsAvailability(v2).groupReason("portrait_face_category"));
        assertNull(with(null).groupReason(null));
    }

    @Test public void englishNotesNameTheModes() {
        // Lang.t is RU in this JVM (no Android locale): the notes keep their RU / EN pair in the source
        final String src;
        try {
            src = read("src/main/java/com/particlesdevs/photoncamera/settings/SettingsAvailability.java");
        } catch (IOException e) { throw new AssertionError(e); }
        assertTrue(src.contains("“Quality” processing mode only"));
        // P644: «Статика»'s note and the «Быстрый» + «Качество» one (every row of both routes acts in both modes now) are gone
        for (String gone : new String[]{"“Static” processing mode only", "“Fast” and “Quality” processing modes only"})
            assertFalse(gone, src.contains(gone));
    }

    @Test public void theRoutesFollowTheWorkersMergeRoutes() throws IOException {
        final String fast = read("src/main/cpp/scam-fast-merge.h") + read("src/main/cpp/scam-fast-gpu.h");
        final String scam = read("src/main/cpp/scam-hybrid.h");
        // P644: the static merge route is gone - its headers, its tuning and its branch
        for (String gone : new String[]{"scam-static-merge.h", "scam-static-gpu.h", "scam-static-cpu.h", "scam-static-shaders.h", "scam-static-spirv.h"})
            assertFalse(gone, Files.exists(Paths.get("src/main/cpp/" + gone)));
        assertFalse(scam.contains("staticMerge") || scam.contains("hybridStaticMerge") || scam.contains("scam-static"));
        // the SCAM merge reads each member; neither route of its own does
        for (String[] row : QUALITY_MEMBERS) {
            final Pattern member = Pattern.compile("\\.\\s*" + row[1] + "\\b");
            assertTrue(row[1], member.matcher(scam).find());
            assertFalse(row[1] + " read by the fast route", member.matcher(fast).find());
        }
        // the fast route branches off at the top of the SCAM merge, before it reads any of them
        final int reconstruct = scam.indexOf("inline std::vector<float> hybridReconstruct(const HybridInput& input");
        final int fastBranch = scam.indexOf("if(tune.fastMerge>0&&!preset&&!native&&!dcgCheckFirst)", reconstruct);
        assertTrue(reconstruct > 0 && fastBranch > reconstruct);
        for (String[] row : QUALITY_MEMBERS)
            assertFalse(row[1], Pattern.compile("\\btune\\." + row[1] + "\\b").matcher(scam.substring(reconstruct, fastBranch)).find());
        // the kernel's footprints: the fast route takes them (hybridKernelFootprint)
        assertTrue(fast.contains("hybridKernelFootprint(tune)"));
        final int fp = scam.indexOf("inline HybridKernelFootprint hybridKernelFootprint(");
        final String body = scam.substring(fp, scam.indexOf("\n}", fp));
        for (String m : new String[]{"kernelTexture", "kernelBokeh", "kernelTransition"}) assertTrue(m, body.contains("t." + m));
        // P627: no hand-offs - «Быстрый» merges Tetra (4x4) itself and does not fall back to the SCAM merge: a route that cannot
        // run says so (HYBRID ROUTE) instead of taking another mode's merge
        assertTrue(fast.contains("fastMergeTetra 0 ignored"));
        assertTrue(scam.contains("int fastMergeTetra=1;"));
        assertTrue(scam.contains("return hybridFastRoute(input,tune,report"));
        // P644: nothing reads «Статика»'s rows any more
        final String keys = read("src/main/java/com/particlesdevs/photoncamera/settings/PreferenceKeys.java");
        assertFalse(keys.contains("\"static_iters\"") || keys.contains("\"static_sr\"") || keys.contains("staticProcessing"));
    }

    @Test public void theGroupHoldsEveryRowOnce() throws IOException {
        final String xml = read("src/main/res/xml/preferences.xml");
        // the group right under the processing mode, the former two pages gone
        final int mode = xml.indexOf("android:key=\"pref_scam_hybrid_processing\"");
        final int group = xml.indexOf("<PreferenceScreen android:key=\"scam_hybrid_dnmerge_screen\"");
        assertTrue(mode > 0 && group > mode);
        assertFalse(xml.substring(mode + 1, group).contains("android:key="));
        assertFalse(xml.contains("android:key=\"scam_hybrid_photo_screen\""));
        assertFalse(xml.contains("android:key=\"scam_hybrid_merge_screen\""));
        final int end = closing(xml, group);
        final String page = xml.substring(group, end);
        // every key of the routes is a row of the group, each key once in the whole settings
        final Set<String> named = new HashSet<>(SettingsAvailability.QUALITY_ONLY);
        for (String own : new String[]{"kernel_texture", "kernel_bokeh", "kernel_transition"}) named.add(H + own);
        named.add(H + "cre_source");
        for (String key : named) {
            assertTrue(key, page.contains("android:key=\"" + key + "\""));
            assertEquals(key, 1, count(xml, "android:key=\"" + key + "\""));
        }
        final Matcher keys = Pattern.compile("android:key=\"([^\"]+)\"").matcher(page);
        final List<String> rows = new ArrayList<>();
        while (keys.find()) rows.add(keys.group(1));
        assertTrue(rows.toString(), rows.size() > 70);
        for (String key : rows) assertEquals(key, 1, count(xml, "android:key=\"" + key + "\""));
        // P644: «Статика»'s group and rows are gone
        assertFalse(xml.contains("scam_hybrid_static_category"));
        assertFalse(xml.contains("pref_scam_hybrid_static_"));
        // the everyday «Шумоподавление Resolve» slider stays with the image's sliders (owner, 2026-10-27; P406)
        final int image = xml.indexOf("android:key=\"root_colour\"");
        final int dn = xml.indexOf("android:key=\"pref_scam_hybrid_why_dn_factor\"");
        assertTrue(dn > image && dn < xml.indexOf("android:key=\"root_lab\""));
        assertFalse(page.contains("pref_scam_hybrid_why_dn_factor"));
    }

    /** The note in either language (Lang.t follows the JVM's locale here). */
    private static boolean says(String why, String ru, String en) {
        return why.contains(ru) || why.contains(en);
    }

    private static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) n++;
        return n;
    }

    /** The end of the PreferenceScreen element opening at {@code start}. */
    private static int closing(String xml, int start) {
        final Matcher m = Pattern.compile("<PreferenceScreen\\b[^>]*?(/?)>|</PreferenceScreen>").matcher(xml);
        int depth = 0;
        m.region(start, xml.length());
        while (m.find()) {
            if (m.group().startsWith("</")) {
                if (--depth == 0) return m.end();
            } else if (!"/".equals(m.group(1))) depth++;
        }
        throw new AssertionError("no end of the group");
    }
}
