#!/usr/bin/env bash
# Builds the shader translators ScamGL links (glslang, SPIRV-Cross) as static libraries for one platform.
#   build_shader_deps.sh <macos|ios|iossim> <out dir>
# Pinned sources (fetched, not vendored): glslang 15.1.0, SPIRV-Cross vulkan-sdk-1.4.304.0. No optimizer (ENABLE_OPT OFF).
set -euo pipefail
platform="$1"
out="$(mkdir -p "$2" && cd "$2" && pwd)"
src="$out/src"
GLSLANG_TAG=15.1.0
SPIRV_CROSS_TAG=vulkan-sdk-1.4.304.0
mkdir -p "$src"
[ -d "$src/glslang" ] || git clone -q --depth 1 --branch "$GLSLANG_TAG" https://github.com/KhronosGroup/glslang.git "$src/glslang"
[ -d "$src/SPIRV-Cross" ] || git clone -q --depth 1 --branch "$SPIRV_CROSS_TAG" https://github.com/KhronosGroup/SPIRV-Cross.git "$src/SPIRV-Cross"

case "$platform" in
  macos)  cmake_platform=(-DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_DEPLOYMENT_TARGET=13.0) ;;
  ios)    cmake_platform=(-DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT=iphoneos -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_DEPLOYMENT_TARGET=17.0) ;;
  iossim) cmake_platform=(-DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT=iphonesimulator -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_DEPLOYMENT_TARGET=17.0) ;;
  *) echo "unknown platform $platform" >&2; exit 2 ;;
esac
common=(-DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON "${cmake_platform[@]}")

cmake -S "$src/glslang" -B "$out/build-glslang-$platform" "${common[@]}" -DENABLE_OPT=OFF -DGLSLANG_TESTS=OFF \
  -DENABLE_GLSLANG_BINARIES=OFF -DENABLE_HLSL=OFF -DENABLE_SPVREMAPPER=OFF -DGLSLANG_ENABLE_INSTALL=ON \
  -DCMAKE_INSTALL_PREFIX="$out/$platform" > "$out/glslang-$platform.cmake.log"
cmake --build "$out/build-glslang-$platform" --config Release -j "$(sysctl -n hw.ncpu 2>/dev/null || nproc)" > "$out/glslang-$platform.build.log"
cmake --install "$out/build-glslang-$platform" --config Release > /dev/null

cmake -S "$src/SPIRV-Cross" -B "$out/build-spirv-cross-$platform" "${common[@]}" -DSPIRV_CROSS_CLI=OFF -DSPIRV_CROSS_ENABLE_TESTS=OFF \
  -DSPIRV_CROSS_ENABLE_HLSL=OFF -DSPIRV_CROSS_ENABLE_CPP=OFF -DSPIRV_CROSS_ENABLE_REFLECT=OFF -DSPIRV_CROSS_ENABLE_C_API=OFF \
  -DSPIRV_CROSS_ENABLE_UTIL=OFF -DSPIRV_CROSS_SHARED=OFF -DCMAKE_INSTALL_PREFIX="$out/$platform" > "$out/spirv-cross-$platform.cmake.log"
cmake --build "$out/build-spirv-cross-$platform" --config Release -j "$(sysctl -n hw.ncpu 2>/dev/null || nproc)" > "$out/spirv-cross-$platform.build.log"
cmake --install "$out/build-spirv-cross-$platform" --config Release > /dev/null
ls "$out/$platform/lib"
