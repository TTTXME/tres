cmake_minimum_required(VERSION 3.22)
project(tone_port LANGUAGES CXX)

add_library(tone_port STATIC src/curves.cpp src/processing.cpp src/rgb8_hdr.cpp
                             src/linear_hdr31.cpp src/raw_tone.cpp src/raw_finish31.cpp
                             src/parallel.cpp)
find_package(Threads REQUIRED)
target_link_libraries(tone_port PUBLIC Threads::Threads)
target_compile_features(tone_port PUBLIC cxx_std_17)
target_include_directories(tone_port PUBLIC include)
target_compile_options(tone_port PRIVATE -ffp-contract=off)
set_target_properties(tone_port PROPERTIES POSITION_INDEPENDENT_CODE ON)

option(TONE_BUILD_TESTS "Build the regression test runner" OFF)
if(TONE_BUILD_TESTS)
    add_executable(tone_check tests/check.cpp)
    target_link_libraries(tone_check PRIVATE tone_port)
endif()
