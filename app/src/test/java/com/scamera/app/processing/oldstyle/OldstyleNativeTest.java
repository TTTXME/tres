package com.scamera.app.processing.oldstyle;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * P630b «Oldstyle»: the app's side of the owner's tone-port module - the JNI symbols match OldstyleNative's natives, the module is
 * vendored with its regression (P630e: plus one documented hook) and built into scameraTone with -ffp-contract=off, V1's CCM is the colour chain's two
 * matrices in their order, and the planar frame's size. The module's bytes against its regression and the JNI path against direct
 * calls of the module: tools/check_tone_port.py (host).
 */
public class OldstyleNativeTest {
    static String read(String p) throws Exception {
        return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
    }

    @Test public void jniMatchesTheNatives() throws Exception {
        final String jni = read("src/main/cpp/oldstyle-tone-jni.cpp");
        assertTrue(jni.contains("Java_com_scamera_app_processing_oldstyle_OldstyleNative_render(JNIEnv* env, jclass, jint version,\n"
                + "        jobject planar, jint width, jint height, jfloatArray ccmArray, jfloat gainShort, jfloat gainLong, jboolean hdr,\n"
                + "        jboolean postview, jfloat saturation, jfloatArray postArray, jfloatArray shadingArray, jint shadingW, jint shadingH,\n"
                + "        jfloatArray infoArray, jobject out)"));
        // P630e: the supplement's plan and the viewfinder's LUT
        assertTrue(jni.contains("Java_com_scamera_app_processing_oldstyle_OldstyleNative_plan(JNIEnv* env, jclass, jint profile, jfloat shortCandidate,\n"
                + "        jfloat longCandidate, jfloat fractionLong, jfloat actualShortTet, jfloat maxGain, jfloatArray outArray)"));
        assertTrue(jni.contains("Java_com_scamera_app_processing_oldstyle_OldstyleNative_previewLut(JNIEnv* env, jclass, jint version, jbyteArray outArray)"));
        // P636: the frame's work lives in oldstyle-tone-core.h (the JNI includes it; a host check includes it alone)
        assertTrue(jni.contains("#include \"oldstyle-tone-core.h\""));
        final String core = read("src/main/cpp/oldstyle-tone-core.h");
        final String both = jni + core;
        assertTrue(jni.contains("Java_com_scamera_app_processing_oldstyle_OldstyleNative_deinterleave16(JNIEnv* env, jclass, jobject strip,\n"
                + "        jobject planar, jint width, jint height, jint y0, jint rows)"));
        assertTrue(jni.contains("Java_com_scamera_app_processing_oldstyle_OldstyleNative_setThreads(JNIEnv*, jclass, jint count)"));
        assertTrue(jni.contains("tone::set_threads(count);"));
        // one thread runs P630e's functions, more than one the banded ones
        assertTrue(core.contains("const bool fast = tone::threads() > 1;"));
        for (String call : new String[]{"d = stages_fast(rgb.data(), dst,", "if (post.dehaze) d = dehaze_rgb8(rgb.data(), w, h, post, shading, shadingW, shadingH);",
                "tone_supplement::dehaze_stats(ys.get(), us.get(), vs.get(), int(pw), int(ph), std::ptrdiff_t(pw / 2), p.max_dark, p.percentile)",
                "tone_supplement::smooth_dehaze_luma(tone_supplement::dehaze_luma_q4_unsmoothed(stats), stats.shadow)", "tone_supplement::tint_chroma_q4("})
            assertTrue(call, core.contains(call));
        // the dither the banded dehaze uses is the supplement's own table
        final String suppText = read("src/main/cpp/third_party/tone_supplement/tone_supplement.cpp");
        final String oursHead = "constexpr std::array<uint8_t, 128> kDehazeDither{", theirsHead = "constexpr std::array<uint8_t, 128> dither{";
        final String table = core.substring(core.indexOf(oursHead) + oursHead.length(), core.indexOf("};", core.indexOf(oursHead)));
        final String theirs = suppText.substring(suppText.indexOf(theirsHead) + theirsHead.length(), suppText.indexOf("};", suppText.indexOf(theirsHead)));
        assertEquals(theirs.replaceAll("\\s", ""), table.replaceAll("\\s", ""));
        assertEquals(128, table.replaceAll("\\s", "").split(",").length);
        // the module's threads: its own files and their diff beside the P630e hook's
        for (String f : new String[]{"src/parallel.h", "src/parallel.cpp"})
            assertTrue(f, Files.exists(Paths.get("src/main/cpp/third_party/tone_port/" + f)));
        final String threadsDiff = read("src/main/cpp/third_party/tone_port.p636.diff");
        assertTrue(threadsDiff.contains("+void set_threads(int count);") && threadsDiff.contains("+++ tone_port/src/parallel.cpp"));
        assertTrue(read("src/main/cpp/third_party/tone_port/include/tone_port.h").contains("void set_threads(int count);"));
        for (String call : new String[]{"#include \"third_party/tone_supplement/tone_supplement.cpp\"", "tone_supplement::plan27(",
                "tone_supplement::plan31(", "tone_supplement::fusion_gains(", "tone_supplement::awb_ccm(", "tone_supplement::dehaze_yuv444(",
                "tone_supplement::rgb8_to_yuv444(", "tone_supplement::yuv444_to_rgb8(", "tone_supplement::make_saturation(",
                "tone_supplement::output_saturation_params(", "tone_supplement::saturate_rgb8(", "tone_supplement::make_low_frequency_delta(",
                "tone_supplement::apply_low_frequency_delta(", "post.low_frequency ? (fast ? low_frequency_hook_fast : low_frequency_hook) : nullptr"})
            assertTrue(call, both.contains(call));
        // the supplement vendored as the document's block, unchanged; the module's one hook as its diff
        final String supp = read("src/main/cpp/third_party/tone_supplement/tone_supplement.cpp");
        final String doc = read("src/main/cpp/third_party/tone_supplement/hdr-ae-color-27-31.md");
        assertTrue(doc.contains(supp.trim()));
        assertTrue(read("src/main/cpp/third_party/tone_port/include/tone_port.h").contains("using BrightHook = void (*)(uint8_t* bright, size_t width, size_t height, void* user);"));
        assertTrue(read("src/main/cpp/third_party/tone_port.p630e.diff").contains("+    if (hook) hook(pair.bright.data(), src.width, src.height, user);"));
        assertTrue(jni.contains("Java_com_scamera_app_processing_oldstyle_OldstyleNative_deinterleave(JNIEnv* env, jclass, jobject strip,\n"
                + "        jobject planar, jint width, jint height, jint y0, jint rows)"));
        for (String call : new String[]{"tone::finish_raw27_rgb(", "tone::tone_rgb8_hdr_luts(tone::Version::v27", "tone::raw_hdr_gains_31(",
                "tone::render_raw31_hdr(", "tone::render_raw31_sdr(", "tone::retone(inverse, curve, q8)", "tone::raw_curve_31()", "tone::yuv_curve()"})
            assertTrue(call, both.contains(call));
        final String cmake = read("src/main/cpp/CMakeLists.txt");
        assertTrue(cmake.contains("add_subdirectory(${SRC_DIR}/third_party/tone_port"));
        assertTrue(cmake.contains("target_link_libraries(scameraTone PRIVATE tone_port log)"));
        assertTrue(cmake.contains("-ffp-contract=off"));
        assertTrue(read("src/main/cpp/third_party/tone_port/CMakeLists.txt").contains("target_compile_options(tone_port PRIVATE -ffp-contract=off)"));
        for (String f : new String[]{"include/tone_port.h", "src/curves.cpp", "src/processing.cpp", "src/rgb8_hdr.cpp", "src/linear_hdr31.cpp",
                "src/raw_tone.cpp", "src/raw_finish31.cpp", "src/lut_data.inc", "src/raw_mask.inc", "src/profile_tables.inc", "src/raw_dither.inc",
                "tests/check.cpp", "tests/regression.py", "tests/expected.json", "tests/expected_full_frame.json", "README.md"})
            assertTrue(f, Files.exists(Paths.get("src/main/cpp/third_party/tone_port/" + f)));
    }

    /** P636: the cores less one (the viewfinder's), 2 .. 8; scam_dev oldstyle_threads as given (1: the one-thread path). */
    @Test public void threadsOfTheTone() {
        assertEquals(7, OldstyleNative.threadsFor(8, 0f));
        assertEquals(8, OldstyleNative.threadsFor(12, 0f));
        assertEquals(3, OldstyleNative.threadsFor(4, 0f));
        assertEquals(2, OldstyleNative.threadsFor(2, 0f));
        assertEquals(2, OldstyleNative.threadsFor(1, 0f));
        assertEquals(1, OldstyleNative.threadsFor(8, 1f));
        assertEquals(4, OldstyleNative.threadsFor(8, 4f));
        assertEquals(16, OldstyleNative.threadsFor(8, 40f));
    }

    @Test public void ccmIsTheColourChainInOrder() {
        final float[] i2s = {1.5f, -0.3f, -0.2f, -0.1f, 1.3f, -0.2f, 0f, -0.4f, 1.4f};
        final float[] s2i = {0.9f, 0.1f, 0f, 0.05f, 0.9f, 0.05f, 0f, 0.1f, 0.9f};
        final float[] m = OldstyleNative.mul(i2s, s2i);
        // the shader's intermediateToSRGB * (sensorToIntermediate * c) for c = (1, 2, 3)
        final float[] c = {1f, 2f, 3f}, a = new float[3], b = new float[3];
        for (int r = 0; r < 3; r++) a[r] = s2i[r * 3] * c[0] + s2i[r * 3 + 1] * c[1] + s2i[r * 3 + 2] * c[2];
        for (int r = 0; r < 3; r++) b[r] = i2s[r * 3] * a[0] + i2s[r * 3 + 1] * a[1] + i2s[r * 3 + 2] * a[2];
        for (int r = 0; r < 3; r++) assertEquals(b[r], m[r * 3] * c[0] + m[r * 3 + 1] * c[1] + m[r * 3 + 2] * c[2], 1e-5f);
        assertArrayEquals(new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1}, OldstyleNative.mul(new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1},
                new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1}), 0f);
        assertEquals(4080 * 3072 * 6, OldstyleNative.planar(4080, 3072).capacity());
    }
}
