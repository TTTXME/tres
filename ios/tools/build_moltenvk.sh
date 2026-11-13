#!/usr/bin/env bash
# Builds MoltenVK (Vulkan on Metal) from its pinned upstream release for the spectral film engine (spektrafilm's Vulkan renderer,
# Engine/post/nodes/SpektraFilmEngine.hpp), and the host glslangValidator that compiles the engine's shaders to SPIR-V.
#   build_moltenvk.sh <ios|macos> <out dir>
#     ios:   <out>/moltenvk-ios/{lib/libMoltenVK.a,include}      (iPhone, arm64)
#            <out>/moltenvk-iossim/{lib/libMoltenVK.a,include}   (the simulator)
#     macos: <out>/moltenvk-macos/{lib/libMoltenVK.a,include}    (the macOS check, ios-post-check.yml)
#     both:  <out>/moltenvk-host/bin/glslangValidator            (runs on the build Mac)
# Sources are fetched, not vendored: KhronosGroup/MoltenVK v1.4.1 (its fetchDependencies fetches and builds the pinned
# SPIRV-Cross, SPIRV-Tools headers, Vulkan-Headers and the rest), glslang 15.1.0 (the tag build_shader_deps.sh uses). MoltenVK is
# linked directly (no Vulkan loader): its static library exports the vk* entry points. Needs Xcode and CMake.
set -euo pipefail
platform="$1"
out="$(mkdir -p "$2" && cd "$2" && pwd)"
src="$out/src"
MOLTENVK_TAG=v1.4.1
GLSLANG_TAG=15.1.0
jobs="$(sysctl -n hw.ncpu 2>/dev/null || nproc)"
mkdir -p "$src"
case "$platform" in
  ios)   deps=(--ios --iossim); targets=(ios iossim) ;;
  macos) deps=(--macos);        targets=(macos) ;;
  *) echo "unknown platform $platform" >&2; exit 2 ;;
esac

run() { # run <log> <command...>: quiet, the log's tail on failure
  local log="$1"; shift
  "$@" > "$log" 2>&1 || { echo "failed: $* (see $log)" >&2; tail -60 "$log" >&2; exit 1; }
}

# glslangValidator for the build host (the engine's shaders, as the Android build compiles them with the NDK's glslc)
[ -d "$src/glslang" ] || git clone -q --depth 1 --branch "$GLSLANG_TAG" https://github.com/KhronosGroup/glslang.git "$src/glslang"
if [ ! -x "$out/moltenvk-host/bin/glslangValidator" ]; then
  run "$out/glslang-host.cmake.log" cmake -S "$src/glslang" -B "$out/build-glslang-host" -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
    -DENABLE_OPT=OFF -DGLSLANG_TESTS=OFF -DENABLE_GLSLANG_BINARIES=ON -DENABLE_HLSL=OFF -DENABLE_SPVREMAPPER=OFF -DGLSLANG_ENABLE_INSTALL=ON \
    -DCMAKE_INSTALL_PREFIX="$out/moltenvk-host"
  run "$out/glslang-host.build.log" cmake --build "$out/build-glslang-host" --config Release --target glslang-standalone -j "$jobs"
  mkdir -p "$out/moltenvk-host/bin"
  cp "$(find "$out/build-glslang-host" -type f -name glslang -perm -u+x | head -1)" "$out/moltenvk-host/bin/glslangValidator"
fi
"$out/moltenvk-host/bin/glslangValidator" --version | head -1

# MoltenVK: its dependencies for the platforms, then its own package (Package/Release/MoltenVK: the static XCFramework, headers)
mvk="$src/MoltenVK"
[ -d "$mvk" ] || git clone -q --depth 1 --branch "$MOLTENVK_TAG" https://github.com/KhronosGroup/MoltenVK.git "$mvk"
(cd "$mvk" && run "$out/moltenvk-deps-$platform.log" ./fetchDependencies "${deps[@]}")
for target in "${targets[@]}"; do
  (cd "$mvk" && run "$out/moltenvk-$target.log" make "$target")
done
xcf="$mvk/Package/Release/MoltenVK/static/MoltenVK.xcframework"
for slice in "$xcf"/*/; do
  name="$(basename "$slice")"
  case "$name" in
    ios-*-simulator) dest=iossim ;;
    ios-*) dest=ios ;;
    macos-*) dest=macos ;;
    *) continue ;;
  esac
  mkdir -p "$out/moltenvk-$dest/lib"
  rm -rf "$out/moltenvk-$dest/include"
  cp "$slice/libMoltenVK.a" "$out/moltenvk-$dest/lib/"
  cp -R "$mvk/Package/Release/MoltenVK/include" "$out/moltenvk-$dest/include"
done
for target in "${targets[@]}"; do
  test -f "$out/moltenvk-$target/lib/libMoltenVK.a" && test -f "$out/moltenvk-$target/include/vulkan/vulkan.h"
  echo "moltenvk-$target: $(lipo -info "$out/moltenvk-$target/lib/libMoltenVK.a")"
done
# the sources and intermediates are not cached
rm -rf "$src" "$out"/build-glslang-host
