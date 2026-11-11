#!/usr/bin/env bash
set -euo pipefail

node tools/scam-zsl-live/check_ae_solver_context.js
python3 tools/check_scam_legacy_isolation.py
python3 tools/check_scam_ae_context.py
python3 tools/check_scam_capture_sequence.py
# P317: no mediump (fp16 on Mali) shader operation with a constant outside the fp16 range (NDK glslc)
python3 tools/check_shader_fp16.py
# P612: no GLSL loop index over a parameter of its function (Mali-G925 leaks it: «int * float», the blend refused)
python3 tools/check_glsl_loop_shadow.py
python3 -m venv /tmp/tetra-gpu-check
/tmp/tetra-gpu-check/bin/pip install moderngl==5.12.0 numpy==2.3.5
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_post_pbo.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_noise_quantile.py
python3 tools/check_gpu_readback.py
python3 tools/check_film_tiling.py
python3 tools/check_film_profile_dir.py
python3 tools/check_film_profiles.py
python3 tools/check_film_queue_policy.py
g++ -std=c++17 -O2 tools/check_bayer_tile.cpp -o /tmp/scamera-bayer-tile-check
/tmp/scamera-bayer-tile-check app/src/main/cpp/scam-hybrid.h /tmp/scamera-bayer-tile-shaders
python3 tools/check_bayer_tile.py /tmp/scamera-bayer-tile-shaders
python3 tools/check_bayer_quads.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_film_unpack.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_resolve_denoise.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_scam_hdr_gpu.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_why_highlights.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_why_pyramid.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_why_pyramid_edge.py
# P391: the pyramid's edge-aware bands and continuous depth; the noise-bound deep floor and band cap
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_why_pyramid_bands.py
# P596: the photo's local contrast on the fused luma (why/localcontrast.glsl)
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_why_local_contrast.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_why_noise.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_why_sky.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_vibrance_hl.py
# P419: the reference app's vibrance in one gain (every strength 0 bit for bit no vibrance; each term on its own colours)
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_why_vibrance.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_agx_colour.py
# P395: the saturated orange-yellows towards yellow (skin, oranges, pale colours, greens, blues bit for bit)
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_yellow_hue.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_portrait.py
# P398: pale light at the top of the tone towards white (blue near-whites and saturated colours bit for bit)
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_hl_neutral.py
# P399: the lens's purple fringe against a bright light neutral (violet lights, skies, reds and surfaces away from lights bit for bit)
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_purple_fringe.py
# P400: a daylit window's mid-tones under its sky darker in a room (the room, the sky, a backlit face, a sunlit sill bit for bit)
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_why_window.py
# P402: the reference app's settings ported (the tone's inset / shoulder strength / base, the pyramid denoise, the dezipper)
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_why_v207.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_ms_denoise.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_motion_sharp.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_sports_denoise.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_hybrid_dezipper.py
# P404: the denoise's motion boost, vibrance / skin / matrix gains, curve modifiers, gamma standard, Sobel / thinning / RL damping
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_why_p404.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_luma_grain.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_why_sharpen.py
# P595: the RL halo clamp within the range at a dark / bright edge (no white dots); the photo's dither triangular (no sky bands)
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_edge_dots.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_why_headroom.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_highlight_recovery.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_highlight_neutral.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_fast_clip_flags.py
# P596: the fast route's Quad de-grid from the merged noise (kFastOutput deGrid, u_flat_noise)
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_fast_quad_detail.py
# P607: the fast route's hole fill only between two sides of recovered light (kFastOutput fill, u_fill_light): a lamp keeps its falloff
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_fast_light_fill.py
# P607: why/combine HL_LOCAL does not darken a light's falloff (HL_LOCAL_RIM), window detail as before
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_hl_local_rim.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_coloured_light_border.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_ssbo_bindings.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_press_handoff.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_highlight_opposed.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_highlight_one_channel_off.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_highlight_ceiling.py
# P393: a clipped channel of sunlit foliage without ultrashort content from the local light (scamhdr/scamrgb oppLocalU)
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_opposed_foliage.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_hybrid_mochi.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_hybrid_outlier_cap.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_signed_rgb.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_effmap_onesided.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_dark_fade.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_chroma_dark_mix.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_colour_output.py --gl --strict
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_colour_look.py
# P370: the same with the signed hue on (signedHueU 1, the shipped default); the other GL checks render both themselves
SIGNED_HUE=1 LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_colour_output.py --gl --strict
SIGNED_HUE=1 LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_colour_look.py
# P577: every colour row at its range ends as the photo ships it (WHY_VIBRANCE 1), the monochrome kept grey with Face AWB
SIGNED_HUE=0 LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_colour_params.py
SIGNED_HUE=1 LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_colour_params.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_ccm_gamut.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_ccm_chain.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_tone_ops.py
# P393: a saturated colour's light tones keep its mid-tone chroma (why/combine LIGHT_CHROMA 1; 0 is the shader before, token for token)
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_light_chroma.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_film_lut.py
# P369: the film fed scene light (why/combine FILM_SIM 1); FILM_SIM 0 is the shader without it, token for token
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_film_sim.py --strict
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_highlight_landing.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_viewfinder_look.py
# P365: the RAW viewfinder's fused WHY pass against P314's passes; RAW_SOURCE 0 is the photo's combine token for token
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_vf_fused.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_why_ae_gpu.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_half_rgba.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_gainmap_gpu.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_ultrahdr.py --scene-luma
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_rotate.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_linear_resize.py
