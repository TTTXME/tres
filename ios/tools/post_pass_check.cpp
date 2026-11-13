// The render path of the post port on real Android shaders: «Why» V2 passes through Engine/post/GLCore.hpp on a synthetic
// scene, every pass read back as RGBA float (SCRG files, tools/replay_bursts.py compare). The same source on Mesa and on
// ScamGL; CI compares the two.
//   post_pass_check <shaders root> <out dir>
#include "../Engine/post/GLCore.hpp"

#include <EGL/egl.h>

#include <cmath>
#include <cstdio>
#include <fstream>

using namespace scam_post;

static void save(GLTexture& t, const std::string& path) {
    const GLFormat rgbaF(DataType::FLOAT_32, 4);
    std::vector<uint8_t> px = t.textureBuffer(rgbaF);
    std::ofstream out(path, std::ios::binary);
    const uint32_t header[4] = {0x47524353u, uint32_t(t.mSize.x), uint32_t(t.mSize.y), 4u};
    out.write(reinterpret_cast<const char*>(header), sizeof header);
    out.write(reinterpret_cast<const char*>(px.data()), std::streamsize(px.size()));
    std::printf("%s %dx%d\n", path.c_str(), t.mSize.x, t.mSize.y);
}

int main(int argc, char** argv) {
    if (argc != 3) { std::fprintf(stderr, "usage: %s <shaders root> <out dir>\n", argv[0]); return 2; }
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(display, nullptr, nullptr);
    eglBindAPI(EGL_OPENGL_ES_API);
    const EGLint configAttrs[] = {EGL_RENDERABLE_TYPE, 0x40, EGL_NONE};
    EGLConfig config;
    EGLint n = 0;
    eglChooseConfig(display, configAttrs, &config, 1, &n);
    const EGLint ctxAttrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, ctxAttrs);
    const EGLint size[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    EGLSurface surface = eglCreatePbufferSurface(display, config, size);
    eglMakeCurrent(display, surface, surface, context);
    try {
        const std::string out = argv[2];
        const int w = 640, h = 480;
        // a scene: a horizontal ramp to 1.6 (highlights above 1), coloured, with a vertical sine texture
        std::vector<float> scene(size_t(w) * h * 4);
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x) {
                const float v = 1.6f * float(x) / float(w - 1) * (0.85f + 0.15f * std::sin(float(y) * 0.21f));
                float* p = &scene[(size_t(y) * w + x) * 4];
                p[0] = v * 1.05f; p[1] = v; p[2] = v * 0.8f; p[3] = 1;
            }
        GLProg prog(argv[1]);
        auto input = makeTexture({w, h}, GLFormat(DataType::FLOAT_16, 4), scene.data(), GL_NEAREST);
        const float one[4] = {1, 1, 1, 1};
        auto gain = makeTexture({1, 1}, GLFormat(DataType::FLOAT_16, 4), one, GL_LINEAR);
        const std::vector<float> identity = {1, 0, 0, 0, 1, 0, 0, 0, 1};

        auto clean = makeTexture({w, h}, GLFormat(DataType::FLOAT_16, 4), nullptr, GL_LINEAR);
        prog.useAssetProgram("why/v2_clean");
        prog.setTexture("InputBuffer", input);
        prog.setTexture("GainMap", gain);
        prog.setVar("sensorToIntermediate", false, identity);
        prog.setVar("intermediateToSRGB", false, identity);
        prog.setVar("neutralPointU", 1.f, 1.f, 1.f);
        prog.setVar("inScaleU", 1.f);
        prog.drawBlocks(clean);
        save(*clean, out + "/v2_clean.f32");

        auto luma = makeTexture({w, h}, GLFormat(DataType::FLOAT_16, 1), nullptr, GL_LINEAR);
        prog.setDefine("REF_LUMA", 1);
        prog.useAssetProgram("why/sharp_in");
        prog.setTexture("InputBuffer", input);
        prog.setTexture("GainMap", gain);
        prog.setTexture("CleanBuffer", clean);
        prog.setVar("sensorToIntermediate", false, identity);
        prog.setVar("intermediateToSRGB", false, identity);
        prog.setVar("mulU", 1.f);
        prog.drawBlocks(luma);
        save(*luma, out + "/sharp_in.f32");

        auto g0 = makeTexture({w, h}, GLFormat(DataType::FLOAT_16, 4), nullptr, GL_LINEAR);
        prog.setDefine("MODE", 0);
        prog.useAssetProgram("why/v2_ef_prep");
        prog.setTexture("u_tex_clean_rgb", clean);
        prog.setVar("u_width", w);
        prog.setVar("u_height", h);
        prog.setVar("u_exp_multipliers", 0.4f, 0.7f, 1.f, 2.5f);
        prog.setVar("u_layer_weights", 0.7f, 1.f, 2.f, 1.f);
        prog.setVar("u_weight_center", 1.f);
        prog.setVar("u_auto_exposure", 0.8f);
        prog.setVar("u_blend_smoothness", 0.45000002f);
        prog.setVar("u_gamma_curve_type", 0);
        prog.setVar("u_gamma_inv", 1.f / 2.2f);
        prog.setVar("u_aces_toe", 0.05f);
        prog.drawBlocks(g0);
        save(*g0, out + "/v2_ef_prep.f32");

        auto g1 = makeTexture({w / 2, h / 2}, GLFormat(DataType::FLOAT_16, 4), nullptr, GL_LINEAR);
        prog.useAssetProgram("why/v2_pyr_down");
        prog.setTexture("u_tex_src", g0);
        prog.setVar("u_texel_size", 1.f / float(w), 1.f / float(h));
        prog.setVar("u_dst_w", w / 2);
        prog.setVar("u_dst_h", h / 2);
        prog.drawBlocks(g1);
        save(*g1, out + "/v2_pyr_down.f32");

        // the tonemap with the fused luma taken as level 0's mid layer (a stand-in for the collapse)
        std::vector<float> curve(1024);
        for (int i = 0; i < 1024; ++i) curve[size_t(i)] = std::pow(float(i) / 1023.f, 0.8f);
        auto curveTex = makeTexture({1024, 1}, GLFormat(DataType::FLOAT_32, 1), curve.data(), GL_LINEAR);
        auto toned = makeTexture({w, h}, GLFormat(DataType::FLOAT_16, 4), nullptr, GL_LINEAR);
        prog.useAssetProgram("why/v2_tonemap");
        prog.setTexture("u_tex_clean_rgb", clean);
        prog.setTexture("u_tex_accum_ef", g0);
        prog.setTexture("u_tex_hires_luma", luma);
        prog.setTexture("u_tex_custom_curve", curveTex);
        prog.setVar("u_width", w);
        prog.setVar("u_height", h);
        prog.setVar("u_auto_exposure", 0.8f);
        prog.setVar("u_clip_ceiling", 1.f);
        prog.setVar("u_tonemap_operator", 3);
        prog.setVar("u_agx_look", 4);
        prog.setVar("u_agx_slope", 2.75f);
        prog.setVar("u_agx_sp", 1.35f);
        prog.setVar("u_agx_tp", 2.f);
        prog.setVar("u_agx_min_ev", -8.5f);
        prog.setVar("u_agx_max_ev", 3.5f);
        prog.setVar("u_agx_sat_boost", 1.f);
        prog.setVar("u_agx_ev", 0.f);
        prog.setVar("u_agx_hue_lock", 1);
        prog.setVar("u_agx_mat_inset", false, identity);
        prog.setVar("u_agx_mat_outset", false, identity);
        prog.setVar("u_aces_pre_gain", 1.f);
        prog.setVar("u_aces_a_coeff", 2.61f);
        prog.setVar("u_aces_d_coeff", 0.35f);
        prog.setVar("u_macro_contrast", 1.19f);
        prog.setVar("u_contrast_boost", 0.3f);
        prog.setVar("u_skin_protect_toggle", 1.f);
        prog.setVar("u_vibrance_sky_str", 0.25f);
        prog.setVar("u_vibrance_green_str", 0.05f);
        prog.setVar("u_vibrance_hl_str", 0.25f);
        prog.setVar("u_skin_hue_center", 41.25f * 0.017453292f);
        prog.setVar("u_skin_luma_min", 0.16f);
        prog.setVar("u_gamma_curve_type", 0);
        prog.setVar("u_gamma", 2.2f);
        prog.setVar("u_gamma_inv", 1.f / 2.2f);
        prog.setVar("u_hdr_shoulder_strength", 0.6f);
        prog.setVar("u_hdr_shoulder_knee", 0.6f);
        prog.setVar("u_hdr_shoulder_headroom", 0.35f);
        prog.drawBlocks(toned);
        save(*toned, out + "/v2_tonemap.f32");
        checkGl("end");
        prog.close();
        return 0;
    } catch (const std::exception& e) {
        std::fprintf(stderr, "POST PASS CHECK FAILED: %s\n", e.what());
        return 1;
    }
}
