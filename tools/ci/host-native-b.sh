#!/usr/bin/env bash
set -euo pipefail

g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_mosaic_chroma_bands.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-mosaic-chroma-bands
ASAN_OPTIONS=detect_leaks=0 /tmp/check-mosaic-chroma-bands
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined -DSCAM_CHROMA_RING_CHECK tools/check_chroma_band.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-chroma-band
ASAN_OPTIONS=detect_leaks=0 /tmp/check-chroma-band quick
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined -DSCAM_CHROMA_RING_CHECK tools/check_chroma_overlap.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-chroma-overlap
ASAN_OPTIONS=detect_leaks=0 /tmp/check-chroma-overlap quick
# P564: the GPU chroma median in bands of rows (a frame above one GPU call) against the CPU stream
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_chroma_gpu_bands.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-chroma-gpu-bands
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless ASAN_OPTIONS=detect_leaks=0 /tmp/check-chroma-gpu-bands
# P569: the fast route's Quad programs without run-time indices are made and build
g++ -pthread -std=c++17 -O1 tools/check_fast_index_free.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-fast-index-free
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless /tmp/check-fast-index-free
# P568: the Quad native merge with shared-memory tiles gives the original's floats bit for bit
g++ -pthread -std=c++17 -O1 tools/check_nat_tile.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-nat-tile
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless /tmp/check-nat-tile
# P571: the fast route's Quad lattice pass on the GPU against its CPU reference, in one strip and in several; P632: the grid
# energy of a synthetic Quad scene's dark branches across a bright sky falls (relative units)
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_quad_lattice_gpu.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-quad-lattice-gpu
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless ASAN_OPTIONS=detect_leaks=0 /tmp/check-quad-lattice-gpu
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_hybrid_still.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-hybrid-still
ASAN_OPTIONS=detect_leaks=0 /tmp/check-hybrid-still
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_hybrid_stages.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-hybrid-stages
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless ASAN_OPTIONS=detect_leaks=0 /tmp/check-hybrid-stages
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_hybrid_series.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-hybrid-series
ASAN_OPTIONS=detect_leaks=0 /tmp/check-hybrid-series
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_hybrid_us_rows.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-hybrid-us-rows
ASAN_OPTIONS=detect_leaks=0 /tmp/check-hybrid-us-rows
# P366: the lamp-flicker corrections of the ultrashort frames held in daylight and for an incoherent tile field (one decision per burst)
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_hybrid_us_daylight.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-hybrid-us-daylight
ASAN_OPTIONS=detect_leaks=0 /tmp/check-hybrid-us-daylight
# P364: a merge that throws while the ultrashort check runs beside it waits for the check before its copies are freed
# (ASan: the use after free; TSan, setarch -R for its fixed shadow layout: the missing join, whatever the timing)
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_hybrid_us_pending.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-hybrid-us-pending
ASAN_OPTIONS=detect_leaks=0 /tmp/check-hybrid-us-pending
g++ -pthread -std=c++17 -O1 -g -D_GLIBCXX_ASSERTIONS -fsanitize=thread tools/check_hybrid_us_pending.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-hybrid-us-pending-tsan
setarch "$(uname -m)" -R /tmp/check-hybrid-us-pending-tsan
# P587: the texture / bokeh kernel footprints (factors of 1 bit for bit; texture / bokeh act on their regions)
g++ -pthread -std=c++17 -O1 tools/check_hybrid_kernel_footprint.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-hybrid-kernel-footprint
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless /tmp/check-hybrid-kernel-footprint
# P600: «Все шумодавы» in the worker (denoise 0: the registry's stages off, the merge itself and the lattice passes bit for bit)
g++ -pthread -std=c++17 -O1 tools/check_denoise_switch.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-denoise-switch
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless /tmp/check-denoise-switch
