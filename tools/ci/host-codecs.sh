#!/usr/bin/env bash
set -euo pipefail

python3 tools/check_ultrahdr.py --self-test
python3 -m venv /tmp/heic10-check
/tmp/heic10-check/bin/pip install pillow-heif==1.8.0 numpy==2.3.5
/tmp/heic10-check/bin/python tools/check_heic10.py --self-test
/tmp/heic10-check/bin/python tools/check_colour_output.py --shader --files --strict
python3 -m venv /tmp/avif-check
/tmp/avif-check/bin/pip install pillow==12.3.0 numpy==2.3.5
/tmp/avif-check/bin/python tools/check_avif.py
# The worker includes the EGL/GLES GPU helpers (SHDR merge / super-resolution).
g++ -pthread -std=c++17 -O2 -Wall -Wextra app/src/main/cpp/scam-neural-worker.cpp -ldl -lEGL -lGLESv2 -o /tmp/scam-neural-worker
/tmp/scam-neural-worker --transport-check
# P668: a kept-ready worker whose shot never came leaves without GL calls after the GPU driver's exit handlers (Xiaomi 12X:
# SIGSEGV in eglMakeCurrent under __cxa_finalize at every camera close); the wrapped build aborts on such a call
g++ -pthread -std=c++17 -O1 app/src/main/cpp/scam-neural-worker.cpp tools/check_worker_exit_wrap.cpp -Wl,--wrap=eglInitialize -Wl,--wrap=eglMakeCurrent -ldl -lEGL -lGLESv2 -o /tmp/scam-worker-exit
LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless python3 tools/check_worker_exit.py /tmp/scam-worker-exit
mkdir -p /tmp/zsl-quality-check
javac -d /tmp/zsl-quality-check app/src/main/java/com/scamera/app/capture/RawFrameQuality.java tools/java/RawFrameQualityCheck.java
java -ea -cp /tmp/zsl-quality-check RawFrameQualityCheck
/usr/bin/python3 tools/check_lanczos_downscale.py
cmake -S tools/jpeg-check -B /tmp/jpeg-check -DCMAKE_BUILD_TYPE=Release && cmake --build /tmp/jpeg-check -j 4
/tmp/jpeg-check/scamera-jpeg-check 2592 1944 95 4 - /tmp && /tmp/jpeg-check/scamera-jpeg-check 1000 3000 100 3 - /tmp
# P662: the final readback ring's row copy (JpegliEncoder.copyRows) gives the strip path's bytes
g++ -std=c++17 -O1 -pthread -fsanitize=address tools/check_row_copy.cpp -o /tmp/check-row-copy && /tmp/check-row-copy
g++ -std=c++17 -O1 -fsanitize=address tools/check_dng_memory.cpp -o /tmp/check-dng-memory
ASAN_OPTIONS=detect_leaks=0 /tmp/check-dng-memory /tmp
LIBGL_ALWAYS_SOFTWARE=1 /usr/bin/python3 tools/checks/preview_gl.py
# P662: the RAW viewfinder's copy pass is the identity where MainRenderer.directTone skips it
LIBGL_ALWAYS_SOFTWARE=1 /usr/bin/python3 tools/checks/vf_direct_tone_gl.py
