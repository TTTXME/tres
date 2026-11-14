#!/usr/bin/env bash
set -euo pipefail

# P364: the GPU homography and field on their error paths (no broken promise, no slot off by one) and the memory they hold
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined -DSCAM_LA_GPU_FAULTS tools/check_la_gpu_errors.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-la-gpu-errors
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless ASAN_OPTIONS=detect_leaks=0 /tmp/check-la-gpu-errors
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_la_gpu_reuse.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-la-gpu-reuse
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless ASAN_OPTIONS=detect_leaks=0 /tmp/check-la-gpu-reuse
g++ -pthread -std=c++17 -O1 -g -D_GLIBCXX_ASSERTIONS -fsanitize=thread tools/check_la_gpu_reuse.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-la-gpu-reuse-tsan
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless TSAN_OPTIONS="suppressions=$PWD/tools/check_hybrid_gpu_align.tsan.supp" setarch "$(uname -m)" -R /tmp/check-la-gpu-reuse-tsan
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_us_measure_reuse.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-us-measure-reuse
ASAN_OPTIONS=detect_leaks=0 /tmp/check-us-measure-reuse
# P364: the plain merge with the GPU alignment under ThreadSanitizer (no race on the ultrashort registration's state), the
# GPU field's memory guard with the homography counted, and a GPU field fault mid-burst (the merge goes on: the frames the
# failed worker left by the global translation, the CPU field)
g++ -pthread -std=c++17 -O1 -g -D_GLIBCXX_ASSERTIONS -fsanitize=thread -DSCAM_LA_GPU_FAULTS tools/check_hybrid_gpu_align.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-hybrid-gpu-align
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless TSAN_OPTIONS="suppressions=$PWD/tools/check_hybrid_gpu_align.tsan.supp" setarch "$(uname -m)" -R /tmp/check-hybrid-gpu-align
# P337: the lateral CA beyond the old 2-cell cut (R +6 / B -3 px at the corner) measured and corrected
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_hybrid_raw_ca.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-hybrid-raw-ca
ASAN_OPTIONS=detect_leaks=0 /tmp/check-hybrid-raw-ca
# P219: the mosaic merges' frame pick (the split unchanged, the native merge takes every frame) and the skipped detector
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_mosaic_pick.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-mosaic-pick
ASAN_OPTIONS=detect_leaks=0 /tmp/check-mosaic-pick
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_dng_ceiling.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-dng-ceiling
ASAN_OPTIONS=detect_leaks=0 /tmp/check-dng-ceiling
# P268: the site response field of the mosaic merges (2x2-OCL Quad sensors)
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_gain_field.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-gain-field
ASAN_OPTIONS=detect_leaks=0 /tmp/check-gain-field
# P220: the merge worker's heartbeat (scam-progress.h) that the app's watchdog and processing ring read
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_worker_progress.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-worker-progress
ASAN_OPTIONS=detect_leaks=0 /tmp/check-worker-progress
# P371: the worker's RGB halves streamed into the output memfd during the merge (scam-stream-out.h, the plain sink's rows)
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined tools/check_stream_out.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-stream-out
ASAN_OPTIONS=detect_leaks=0 /tmp/check-stream-out
# P685: the fast route's memory estimate (against the merge's own count), its plan, and its row bands against the whole frame's merge
g++ -pthread -std=c++17 -O1 tools/check_fast_row_bands.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-fast-row-bands
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless /tmp/check-fast-row-bands
g++ -std=c++17 -O1 -fsanitize=address,undefined tools/check_scam_probe.cpp -ldl -lEGL -lGLESv2 -o /tmp/check-scam-probe
ASAN_OPTIONS=detect_leaks=0 /tmp/check-scam-probe /tmp/scam-probe-fixture
# P214a: NativeEngine lookups leave no JNI global reference (fake JNIEnv against the NDK's jni.h).
mkdir -p /tmp/native-engine-host && cp "$ANDROID_HOME/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/jni.h" /tmp/native-engine-host/
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined -I/tmp/native-engine-host tools/check_native_engine_refs.cpp -ldl -o /tmp/check-native-engine-refs
ASAN_OPTIONS=detect_leaks=0 /tmp/check-native-engine-refs
# P318: a Quad / Tetra mosaic DNG declares its CFA, black level and GainMap sites over the colour blocks; every CFA order
# keeps its colour tags (the 4-byte CFA array overflow)
g++ -std=c++17 -O1 -fsanitize=address,undefined -fno-sanitize-recover=bounds -I/tmp/native-engine-host -Itools/host-include -Iapp/src/main/cpp/deps tools/check_dng_mosaic_cfa.cpp -ldl -o /tmp/check-dng-mosaic-cfa
ASAN_OPTIONS=detect_leaks=0 /tmp/check-dng-mosaic-cfa
# P618: the DNG's metadata - the Exif IFD (time with milliseconds and zone, exposure, lens), LensInfo, the 4:3 DefaultCrop,
# every IFD sorted with each tag once, no PhotonCamera string, the image and the colour tags as without them
g++ -std=c++17 -O1 -fsanitize=address,undefined -fno-sanitize-recover=bounds -I/tmp/native-engine-host -Itools/host-include -Iapp/src/main/cpp/deps tools/check_dng_metadata.cpp -ldl -o /tmp/check-dng-metadata
ASAN_OPTIONS=detect_leaks=0 /tmp/check-dng-metadata
# P371: the merge client reads the worker's result in place (allocator.cpp arenaAdopt: views of the memfd, the RGB view's
# free punches its pages, unmapped after the last view; review B1: the trailers between the views survive the release)
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined -I/tmp/native-engine-host -Itools/host-include tools/check_allocator_adopt.cpp -o /tmp/check-allocator-adopt
ASAN_OPTIONS=detect_leaks=0 /tmp/check-allocator-adopt
# P662: the packed RAW10 / RAW12 unpack (allocator.cpp: NEON on aarch64, 16 samples a step) gives the scalar unpack's samples on
# random rows of every width, shift and alignment, never reading past a row; the NEON path itself where qemu runs an aarch64 build
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined -I/tmp/native-engine-host -Itools/host-include tools/check_raw_unpack.cpp -o /tmp/check-raw-unpack
ASAN_OPTIONS=detect_leaks=0 /tmp/check-raw-unpack
NDK_BIN="$ANDROID_HOME/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin"
if command -v qemu-aarch64-static >/dev/null && [ -x "$NDK_BIN/aarch64-linux-android35-clang++" ]; then
  "$NDK_BIN/aarch64-linux-android35-clang++" -static -std=c++17 -O2 tools/check_raw_unpack.cpp -o /tmp/check-raw-unpack-arm64
  qemu-aarch64-static /tmp/check-raw-unpack-arm64
fi
# P609: the OnePlus 16 main's 50 MP from its 200 MP row - allocator.cpp binBlocks2 averages each 2x2 box of a Tetra frame into the
# sensor's own Quad (colour order, levels, rounding, whole 8x8 periods, padded strides, packed RAW10 / RAW12, refusals)
g++ -pthread -std=c++17 -O1 -fsanitize=address,undefined -I/tmp/native-engine-host -Itools/host-include tools/check_tetra_bin.cpp -o /tmp/check-tetra-bin
ASAN_OPTIONS=detect_leaks=0 /tmp/check-tetra-bin
# P610: the photo's crop packed in its own pixel buffer (ScamPostDownscale.cropInPlace) gives the bytes of a copy of the region
g++ -std=c++17 -O1 -Wall -Wextra -fsanitize=address,undefined -fno-sanitize-recover=all tools/check_bitmap_crop.cpp -o /tmp/check-bitmap-crop
/tmp/check-bitmap-crop
g++ -std=c++17 -O1 -Wall -Wextra -fsanitize=address,undefined -fno-sanitize-recover=all tools/check_dcg_noise.cpp -o /tmp/check-dcg-noise
/tmp/check-dcg-noise
python3 tools/check_scam_reference_metadata.py
