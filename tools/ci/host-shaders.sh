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
# P683: the APK's default program recipes (the clean install's warm-up) are what the code gives now; every one compiles (glslc)
python3 tools/make_default_recipes.py --check
# P662: the merge's programs as a Mali phone gets them (the «Качество» context's variants, the fast route's, the F6 alignment's),
# dumped and compiled by glslc as strict GLSL ES 3.10 and with the Mali-G925 loop-index leak emulated (31253's blend0 fails it);
# a driver's refusal remembered per driver and source, logged once, not compiled again (SCAM_GLSL_FAULTS)
g++ -pthread -std=c++17 -O1 -DSCAM_GLSL_FAULTS tools/check_glsl_programs.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-glsl-programs
rm -rf /tmp/scam-glsl-dump /tmp/scam-glsl-refusals && mkdir -p /tmp/scam-glsl-refusals
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless /tmp/check-glsl-programs /tmp/scam-glsl-dump
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless /tmp/check-glsl-programs --refusals /tmp/scam-glsl-refusals
python3 tools/check_glsl_loop_shadow.py --dump /tmp/scam-glsl-dump
python3 -m venv /tmp/tetra-gpu-check
/tmp/tetra-gpu-check/bin/pip install moderngl==5.12.0 numpy==2.3.5
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_post_pbo.py
# P668: every post fragment program drawn in bands of 16 / 48 / 112 rows equals one draw bit for bit (the paced post's band sizing);
# P683: and in the 8-row tiles a picture under 8 MP had until P683 (now 256 rows as every picture)
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_post_bands.py
# P671: the clip band in the flagged tiles only equals the band over every pixel, bit for bit (and no tile without a flag)
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_band_tiles.py
# P671: the photo's combine with its one-channel windows read as textureGather blocks equals the single reads, bit for bit
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_combine_gather.py
# P671: the denoise's optics with its per-strip Oklab cache equals the inline conversions, bit for bit
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_ms_oklab_cache.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_noise_quantile.py
python3 tools/check_gpu_readback.py
python3 tools/check_film_tiling.py
python3 tools/check_film_profile_dir.py
python3 tools/check_film_profiles.py
python3 tools/check_film_queue_policy.py
# P683: the film data stored packed (tools/film_pack.py: delta + shuffle + deflate) decodes bit for bit - assets/film's SFD2
# arrays against the SFD1 arrays' hashes, libscameraFilm's Hanatos spectra (and its plain resources) against the repo's files
python3 tools/film_pack.py check app/src/main/assets/film tools/film_arrays.sha256
rm -rf /tmp/scamera-film-embed
python3 tools/embed_film_reference.py --compiler "$ANDROID_HOME/ndk/27.2.12479018/shader-tools/linux-x86_64/glslc" \
    --shaders app/src/main/cpp/third_party/spektrafilm/shaders --floats app/src/main/assets/film-reference --output /tmp/scamera-film-embed
g++ -std=c++17 -O1 -Iapp/src/main/cpp -I/tmp/scamera-film-embed tools/check_film_embed.cpp app/src/main/cpp/film-reference-resources.cpp \
    /tmp/scamera-film-embed/embedded_film_data.cpp -lz -o /tmp/scamera-film-embed/check
/tmp/scamera-film-embed/check app/src/main/assets/film-reference /tmp/scamera-film-embed/shaders
g++ -std=c++17 -O2 tools/check_bayer_tile.cpp -o /tmp/scamera-bayer-tile-check
/tmp/scamera-bayer-tile-check app/src/main/cpp/scam-hybrid.h /tmp/scamera-bayer-tile-shaders
python3 tools/check_bayer_tile.py /tmp/scamera-bayer-tile-shaders
python3 tools/check_bayer_quads.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_film_unpack.py
# P619: the gallery editor's LUT / film look equals the camera's LUT stage; its film grain tiles without seams
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_editor_look.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_resolve_denoise.py
# P648: «Подстройка под свет (SNR)» - the Resolve's night flat smoothing: off and one-frame pixels bit for bit, texture / colour / edges kept
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_post_snr.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_scam_hdr_gpu.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_why_highlights.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_why_pyramid.py
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_why_pyramid_edge.py
# P391: the pyramid's edge-aware bands and continuous depth; the noise-bound deep floor and band cap
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_why_pyramid_bands.py
# P673: the owner's joint bilateral upsampling in the V1 / V2 pyramids' collapse (off by default; the off path token for token)
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_why_jbu_owner.py
# P677: V1's edge-aware bands on V2's pyramid (off by default; the off path token for token)
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_why_v2_bands.py
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
# P614: «Why тон» V2 - the reference tone's chain (why/v2_*.glsl, WhyV2Ae through javac) against a numpy model of it
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_why_v2_tone.py
# P630: «Oldstyle» - the adjustments (GLSL == Java, the viewfinder's splice), the frame export, the mode's shaders
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_oldstyle_tone.py
# P630g: «Oldstyle» - the viewfinder's live tone on a simulated viewfinder (shipped OldstyleLive + shader + the module): steady,
# the photo's mean, no more burnt out than the photo
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_oldstyle_live.py
# P630b: the owner's tone-port module (vendored): its regression both profiles --full-frame, the specification's numbers, the JNI path
python3 tools/check_tone_port.py
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
# P623: the fast merge's measured light above white on the real-headroom shoulder: the sun's glow without a grey ring
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_sun_glow.py
# P623 round 2: HL_LOCAL's mean bilinear over the whyLow texels (no 2 px teeth along a light's rim)
LIBGL_ALWAYS_SOFTWARE=1 /tmp/tetra-gpu-check/bin/python tools/check_hl_local_smooth.py
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
