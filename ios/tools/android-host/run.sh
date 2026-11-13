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
filter="$1"; shift
for kv in "$@"; do export "SCAM_HOST_$kv"; done
export SCAM_HOST_DIR="$here" SCAM_HOST_GEN="$out/gen" SCAM_HOST_LIB="$out/lib" SCAM_HOST_OUT="$out"
cd "$root"
bash ./gradlew -I "$here/init.gradle" :app:testDebugUnitTest --tests "*.$filter" --no-daemon -q
