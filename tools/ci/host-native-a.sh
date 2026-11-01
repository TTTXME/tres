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
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_native_cull_gpu.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-native-cull-gpu
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless ASAN_OPTIONS=detect_leaks=0 /tmp/check-native-cull-gpu
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_us_cache_gpu.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-us-cache-gpu
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless ASAN_OPTIONS=detect_leaks=0 /tmp/check-us-cache-gpu
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_resolve_grain_gpu.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-resolve-grain-gpu
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless ASAN_OPTIONS=detect_leaks=0 /tmp/check-resolve-grain-gpu
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_hybrid_fullres_2x.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-hybrid-fullres-2x
ASAN_OPTIONS=detect_leaks=0 /tmp/check-hybrid-fullres-2x
