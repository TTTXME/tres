#!/usr/bin/env bash
set -euo pipefail

g++ -std=c++17 -O1 -fsanitize=address,undefined tools/check_scam_preprocess.cpp -lEGL -lGLESv2 -o /tmp/check-scam-preprocess
ASAN_OPTIONS=detect_leaks=0 /tmp/check-scam-preprocess
g++ -std=c++17 -O2 -fsanitize=address,undefined tools/check_scam_capture.cpp -lEGL -lGLESv2 -o /tmp/check-scam-capture
ASAN_OPTIONS=detect_leaks=0 /tmp/check-scam-capture
g++ -std=c++17 -O1 -fsanitize=address,undefined tools/check_scam_motion_capture.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-scam-motion-capture
ASAN_OPTIONS=detect_leaks=0 /tmp/check-scam-motion-capture
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_hybrid_measured_gain.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-hybrid-measured-gain
ASAN_OPTIONS=detect_leaks=0 /tmp/check-hybrid-measured-gain
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_hybrid_dark_noise.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-hybrid-dark-noise
ASAN_OPTIONS=detect_leaks=0 /tmp/check-hybrid-dark-noise
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_hybrid_prefetch.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-hybrid-prefetch
ASAN_OPTIONS=detect_leaks=0 /tmp/check-hybrid-prefetch
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_hybrid_native_widen.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-hybrid-native-widen
ASAN_OPTIONS=detect_leaks=0 /tmp/check-hybrid-native-widen
# P523: the Quad / Tetra base demosaic damps its aliasing colour toward the camera neutral (not magenta)
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_hybrid_native_neutral.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-hybrid-native-neutral
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless ASAN_OPTIONS=detect_leaks=0 /tmp/check-hybrid-native-neutral
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_native_cull_gpu.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-native-cull-gpu
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless ASAN_OPTIONS=detect_leaks=0 /tmp/check-native-cull-gpu
# P594: the Tetra native merge's luma guide takes the 8 px beads off edges of a burst drifting along one axis
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_tetra_luma_guide.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-tetra-luma-guide
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless ASAN_OPTIONS=detect_leaks=0 /tmp/check-tetra-luma-guide
# P603: the Tetra motion smoothing where few frames merged: GPU = CPU twin, high coverage bit for bit, edges kept
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_tetra_motion_smooth.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-tetra-motion-smooth
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless ASAN_OPTIONS=detect_leaks=0 /tmp/check-tetra-motion-smooth
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_us_cache_gpu.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-us-cache-gpu
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless ASAN_OPTIONS=detect_leaks=0 /tmp/check-us-cache-gpu

g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_bayer_tile_gpu.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-bayer-tile-gpu
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless ASAN_OPTIONS=detect_leaks=0 /tmp/check-bayer-tile-gpu

g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_donor_sums_gpu.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-donor-sums-gpu
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless ASAN_OPTIONS=detect_leaks=0 /tmp/check-donor-sums-gpu
# P613: a merge strip the GPU dropped fails the merge instead of repeating the strip two above (the clean merge bit for bit)
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_hybrid_strip_guard.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-hybrid-strip-guard
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless ASAN_OPTIONS=detect_leaks=0 /tmp/check-hybrid-strip-guard
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_resolve_grain_gpu.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-resolve-grain-gpu
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless ASAN_OPTIONS=detect_leaks=0 /tmp/check-resolve-grain-gpu
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_hybrid_fullres_2x.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-hybrid-fullres-2x
ASAN_OPTIONS=detect_leaks=0 /tmp/check-hybrid-fullres-2x

g++ -std=c++17 -O2 -fsanitize=address,undefined tools/check_bayer_quads_policy.cpp -o /tmp/check-bayer-quads-policy
ASAN_OPTIONS=detect_leaks=0 /tmp/check-bayer-quads-policy
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_bayer_quads_tuning.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-bayer-quads-tuning
ASAN_OPTIONS=detect_leaks=0 /tmp/check-bayer-quads-tuning
python3 tools/check_bayer_quads_memory.py

# P646 «Склейка YUV»: the port == the owner's reference (tools/yuv-merge-ref) on random 8-bit bursts, both profiles; 16-bit samples;
# our alignment, the burst's own noise LUT, the sharpest base, guided chroma, the linear bracket (no ghost, highlights back), HLG P010
g++ -pthread -std=c++17 -O2 -ffp-contract=off -fsanitize=address,undefined -Iapp/src/main/cpp -Itools/yuv-merge-ref tools/check_yuv_merge.cpp -o /tmp/check-yuv-merge
ASAN_OPTIONS=detect_leaks=0 /tmp/check-yuv-merge
