#!/usr/bin/env bash
# The Android post pipeline (the real Java nodes and shaders) on the host GPU (Mesa) under Robolectric: the reference renders for
# the iOS port. Needs ANDROID_HOME (platforms;android-36) and the Mesa EGL / GLES development files.
#   run.sh <test class filter> [KEY=VALUE ...]   e.g. run.sh HostGlSmokeTest
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
out="${SCAM_HOST_OUT:-$root/ios/build/android-host}"
mkdir -p "$out/gen" "$out/lib"
jar="$ANDROID_HOME/platforms/android-36/android.jar"
python3 "$here/gen_host_gl.py" "$jar" /usr/include/GLES3/gl32.h "$out/gen"
jni="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")/include"
gcc -shared -fPIC -O1 -Wall -Wno-unused-parameter -I"$jni" -I"$jni/linux" "$out/gen/host_gl.c" "$here/host_egl.c" \
    -o "$out/lib/libscamhostgl.so" -lGLESv2 -lEGL
# the app's own native buffers (Allocator) built for the host
g++ -shared -fPIC -O2 -std=c++17 -I"$root/tools/host-include" -I"$jni" -I"$jni/linux" "$root/app/src/main/cpp/allocator.cpp" "$here/host_alloc_stubs.cpp" \
    -o "$out/lib/liballocator.so"
# «Oldstyle»: the owner's tone-port module behind its JNI (library scameraTone, OldstyleNative.load: System.loadLibrary from
# java.library.path), built as the app's CMake builds it (-O3 -ffp-contract=off)
tp="$root/app/src/main/cpp/third_party/tone_port"
g++ -shared -fPIC -O3 -std=c++17 -fexceptions -ffp-contract=off -fvisibility=hidden -pthread -I"$root/app/src/main/cpp" -I"$tp/include" \
    -I"$jni" -I"$jni/linux" "$root/app/src/main/cpp/oldstyle-tone-jni.cpp" "$tp"/src/*.cpp -o "$out/lib/libscameraTone.so"
# the spectral film engine (libscameraFilm: the app's JNI and spektrafilm's Vulkan renderer, ReferenceFilm's route) only with
# SCAM_HOST_FILM=1 on a host with a Vulkan driver (Mesa lavapipe) and the Vulkan headers; without it FilmReferenceEngine is
# unavailable, as on a phone without the library (the old film route). film_host_bridge.cpp adds the C++ port's entry point.
rm -f "$out/lib/libscameraFilm.so"
if [ "${SCAM_HOST_FILM:-0}" = 1 ]; then
    film="$root/app/src/main/cpp/third_party/spektrafilm"
    glslc="${GLSLC:-$ANDROID_HOME/ndk/27.2.12479018/shader-tools/linux-x86_64/glslc}"
    mkdir -p "$out/film-gen"
    python3 "$root/tools/embed_film_reference.py" --compiler "$glslc" --shaders "$film/shaders" \
        --floats "$root/app/src/main/assets/film-reference" --output "$out/film-gen" > /dev/null
    g++ -shared -fPIC -O2 -std=c++17 -DSCAMERA_EMBEDDED_FILM=1 -I"$film" -I"$root/app/src/main/cpp" -I"$out/film-gen" -I"$jni" -I"$jni/linux" \
        "$root/app/src/main/cpp/film-reference-jni.cpp" "$root/app/src/main/cpp/film-reference-resources.cpp" "$out/film-gen/embedded_film_data.cpp" \
        "$film/SpektraVulkanRenderer.cpp" "$film/SpektraGeneratedProfileCurves.cpp" "$here/film_host_bridge.cpp" \
        -o "$out/lib/libscameraFilm.so" -lvulkan -ldl
fi
filter="$1"; shift
for kv in "$@"; do export "SCAM_HOST_$kv"; done
export SCAM_HOST_DIR="$here" SCAM_HOST_GEN="$out/gen" SCAM_HOST_LIB="$out/lib" SCAM_HOST_OUT="$out"
cd "$root"
bash ./gradlew -I "$here/init.gradle" :app:testDebugUnitTest --tests "*.$filter" --no-daemon -q
