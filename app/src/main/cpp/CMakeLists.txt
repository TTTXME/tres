cmake_minimum_required(VERSION 3.6)

project(dngCreator)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_link_options("-Wl,-z,max-page-size=16384")

file(READ ${CMAKE_CURRENT_SOURCE_DIR}/../../../version.properties ver)
string(REGEX MATCH "VERSION_BUILD=([0-9]*)" _ ${ver})
set(VERSION_BUILD ${CMAKE_MATCH_1})
string(REGEX MATCH "VERSION_NAME=([0-9,.]*)" _ ${ver})
set(VERSION_NAME ${CMAKE_MATCH_1})

if(NOT EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/deps/archive.h)
    file(DOWNLOAD
            https://raw.githubusercontent.com/libarchive/libarchive/master/libarchive/archive.h
            ${CMAKE_CURRENT_SOURCE_DIR}/deps/archive.h)
endif()

if(NOT EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/deps/archive_entry.h)
    file(DOWNLOAD
            https://raw.githubusercontent.com/libarchive/libarchive/master/libarchive/archive_entry.h
            ${CMAKE_CURRENT_SOURCE_DIR}/deps/archive_entry.h)
endif()

include_directories(${CMAKE_CURRENT_SOURCE_DIR}/deps)

set(SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR})

set(FILM_DIR ${SRC_DIR}/third_party/spektrafilm)
find_package(Python3 REQUIRED COMPONENTS Interpreter)
set(FILM_GENERATED ${CMAKE_CURRENT_BINARY_DIR}/embedded-film)
if(CMAKE_HOST_WIN32)
    set(FILM_SHADER_HOST windows-x86_64)
    set(FILM_SHADER_EXE .exe)
elseif(CMAKE_HOST_APPLE)
    set(FILM_SHADER_HOST darwin-x86_64)
else()
    set(FILM_SHADER_HOST linux-x86_64)
endif()
set(FILM_SHADER_COMPILER ${CMAKE_ANDROID_NDK}/shader-tools/${FILM_SHADER_HOST}/glslc${FILM_SHADER_EXE})
file(GLOB FILM_SHADER_SOURCES CONFIGURE_DEPENDS ${FILM_DIR}/shaders/*.comp)
set(FILM_GENERATOR ${SRC_DIR}/../../../../tools/embed_film_reference.py)
set(FILM_FLOATS ${SRC_DIR}/../assets/film-reference)
add_custom_command(
    OUTPUT ${FILM_GENERATED}/embedded_film_data.cpp ${FILM_GENERATED}/embedded_film_index.h
           ${FILM_GENERATED}/embedded_film_data.bin ${FILM_GENERATED}/embedded_film_manifest.json
    COMMAND ${Python3_EXECUTABLE} ${FILM_GENERATOR} --compiler ${FILM_SHADER_COMPILER}
            --shaders ${FILM_DIR}/shaders --floats ${FILM_FLOATS} --output ${FILM_GENERATED}
    DEPENDS ${FILM_GENERATOR} ${FILM_SHADER_SOURCES}
            ${FILM_FLOATS}/SpektraHanatos2025Spectra.f32 ${FILM_FLOATS}/SpektraOutputGamutCompression.f32
    VERBATIM)
add_library(scameraFilm SHARED film-reference-jni.cpp film-reference-resources.cpp
    ${FILM_GENERATED}/embedded_film_data.cpp
    ${FILM_DIR}/SpektraVulkanRenderer.cpp ${FILM_DIR}/SpektraGeneratedProfileCurves.cpp)
target_compile_features(scameraFilm PRIVATE cxx_std_17)
target_include_directories(scameraFilm PRIVATE ${FILM_DIR} ${SRC_DIR} ${FILM_GENERATED})
target_compile_definitions(scameraFilm PRIVATE SCAMERA_EMBEDDED_FILM=1)
target_compile_options(scameraFilm PRIVATE -O2 -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_options(scameraFilm PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections
    -Wl,--version-script=${SRC_DIR}/film-reference-exports.map)
set_property(TARGET scameraFilm APPEND PROPERTY LINK_DEPENDS ${SRC_DIR}/film-reference-exports.map)
target_link_libraries(scameraFilm PRIVATE vulkan dl log)

add_library(${CMAKE_PROJECT_NAME} SHARED
    ${SRC_DIR}/dngCreator.cpp
)
add_library(allocator SHARED
    ${SRC_DIR}/allocator.cpp
    ${SRC_DIR}/worker-spawn.cpp
    ${SRC_DIR}/scam-ahb.cpp # P482: the merge RGB in the app's hardware buffer (HardwareRgb)
)
add_library(
        camera2native SHARED ${SRC_DIR}/native-engine.cpp
)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        VERSION_BUILD="${VERSION_BUILD}"
        VERSION_NAME="${VERSION_NAME}"
)

target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE
        log
        dl
)

target_link_libraries(allocator PRIVATE
        log
        dl
        android
        EGL
        GLESv3
)

target_link_libraries(
        camera2native
        log
)

# P129: scamAe (SCAM HDR's stock AE observer) and the scamProbe launch check went with SCAM HDR; scam-probe.cpp stays
# (the worker includes it). P175: scamNeuralProbe (the neural remosaic's model probe, loaded by nothing) went with the
# neural remosaic.

# P24: every saved JPEG through jpegli, 4:4:4 (third_party/README.md); Highway gives it NEON / SVE.
set(HWY_DIR ${SRC_DIR}/third_party/highway)
add_library(scameraHwy STATIC
    ${HWY_DIR}/hwy/abort.cc ${HWY_DIR}/hwy/aligned_allocator.cc ${HWY_DIR}/hwy/nanobenchmark.cc ${HWY_DIR}/hwy/per_target.cc
    ${HWY_DIR}/hwy/perf_counters.cc ${HWY_DIR}/hwy/print.cc ${HWY_DIR}/hwy/profiler.cc ${HWY_DIR}/hwy/targets.cc ${HWY_DIR}/hwy/timer.cc)
set_property(TARGET scameraHwy PROPERTY CXX_STANDARD 17)
target_include_directories(scameraHwy PUBLIC ${HWY_DIR})
target_compile_definitions(scameraHwy PUBLIC HWY_STATIC_DEFINE)
target_compile_options(scameraHwy PRIVATE -O3 -fmerge-all-constants -fvisibility=hidden -fvisibility-inlines-hidden)
file(GLOB JPEGLI_SRC ${SRC_DIR}/third_party/jpegli/lib/jpegli/*.cc)
add_library(scameraJpegli STATIC ${JPEGLI_SRC})
set_property(TARGET scameraJpegli PROPERTY CXX_STANDARD 17)
target_include_directories(scameraJpegli PUBLIC ${SRC_DIR}/third_party/jpegli ${SRC_DIR}/third_party/jpegli/include)
target_compile_options(scameraJpegli PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_libraries(scameraJpegli PUBLIC scameraHwy)
add_library(scameraJpeg SHARED scamera-jpeg.cpp)
set_property(TARGET scameraJpeg PROPERTY CXX_STANDARD 17)
target_compile_options(scameraJpeg PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_libraries(scameraJpeg PRIVATE scameraJpegli jnigraphics log)

# AVIF photo output: libavif + libaom (pinned releases, scamera-avif-deps.cmake), 8/10/12-bit, 4:4:4 / 4:2:0, lossless.
include(${SRC_DIR}/scamera-avif-deps.cmake)
add_library(scameraAvif SHARED scamera-avif.cpp)
set_property(TARGET scameraAvif PROPERTY CXX_STANDARD 17)
target_compile_options(scameraAvif PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden)
# The codec libraries are linked in privately: their symbols stay hidden, unused code is dropped.
target_link_options(scameraAvif PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections)
target_link_libraries(scameraAvif PRIVATE scameraLibavif jnigraphics log)

# P630b (owner, 2026-11-11: «Используй как основу для Oldstyle»): the owner's tone-port module (third_party/tone_port, its own
# CMake: static tone_port, C++17, -ffp-contract=off; P630e's hook and P636's threads as the diffs beside it) behind the
# «Oldstyle» mode's JNI (library scameraTone; the frame's work in oldstyle-tone-core.h).
add_subdirectory(${SRC_DIR}/third_party/tone_port ${CMAKE_CURRENT_BINARY_DIR}/tone_port)
add_library(scameraTone SHARED oldstyle-tone-jni.cpp)
target_compile_options(scameraTone PRIVATE -O3 -fexceptions -ffp-contract=off -fvisibility=hidden)
target_link_libraries(scameraTone PRIVATE tone_port log)

# P646 «Склейка YUV»: the burst merge of a lens that gives YUV (scam-yuv-merge.h; processing/yuv/YuvMergeNative).
add_library(scameraYuv SHARED scam-yuv-jni.cpp)
set_property(TARGET scameraYuv PROPERTY CXX_STANDARD 17)
target_compile_options(scameraYuv PRIVATE -O3 -fexceptions -ffp-contract=off -fvisibility=hidden)
target_link_libraries(scameraYuv PRIVATE log)

# Final, memory-bounded resampling of successful Vivo RGB output.
add_library(lanczosDownscale SHARED lanczos-downscale-jni.cpp)
target_compile_options(lanczosDownscale PRIVATE -O3)
target_link_libraries(lanczosDownscale PRIVATE jnigraphics)

# P352 (owner, 2026-10-26: «галерея должна работать с любыми Рав форматами»): camera RAW (CR2/CR3, NEF, ARW, RAF, ORF, RW2,
# PEF, SRW, 3FR, IIQ, ... and DNGs the Java reader refuses) through LibRaw 0.21.3 (third_party/libraw, LGPL-2.1 / CDDL,
# unchanged sources; third_party/README.md). The thread-safe build (no LIBRAW_NOTHREADS: Glide decodes on several threads),
# zlib for deflate DNG, no JPEG / Jasper / OpenMP / RawSpeed / DNG SDK. Library scameraRaw = scamera-raw.cpp + LibRaw.
set(LIBRAW_DIR ${SRC_DIR}/third_party/libraw)
file(GLOB_RECURSE LIBRAW_SRC ${LIBRAW_DIR}/src/*.cpp)
list(FILTER LIBRAW_SRC EXCLUDE REGEX ".*_ph\.cpp$")
add_library(scameraLibRaw STATIC ${LIBRAW_SRC})
target_include_directories(scameraLibRaw PUBLIC ${LIBRAW_DIR})
target_compile_definitions(scameraLibRaw PUBLIC USE_ZLIB=1 LIBRAW_NODLL)
target_compile_options(scameraLibRaw PRIVATE -O2 -w -fexceptions -fvisibility=hidden -fvisibility-inlines-hidden)
add_library(scameraRaw SHARED scamera-raw.cpp)
target_compile_options(scameraRaw PRIVATE -O2 -fexceptions -fvisibility=hidden)
target_link_options(scameraRaw PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections)
target_link_libraries(scameraRaw PRIVATE scameraLibRaw z log)
