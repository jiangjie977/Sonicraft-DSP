cmake_minimum_required(VERSION 3.22.1)
project(sonicraft_lowfreq_bench CXX)

# 低频管家效果器的客观测量工具（2026-11-11）。
# 与 host-harness 共用同一套 shim / DSP 头文件，但产出的是**测量数据**而非断言。
#
#   cmake -S . -B build -G Ninja
#   cmake --build build
#   ./build/lowfreq_bench > out.csv

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 与 Android 侧一致：禁用快速数学与 FP 收缩，保证测量可复现
if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    add_compile_options(-fno-fast-math -ffp-contract=off)
elseif(MSVC)
    add_compile_options(/fp:precise /utf-8)
endif()

set(DSP_SOURCES
        ${CMAKE_CURRENT_SOURCE_DIR}/../../core/dsp/src/main/cpp/sonicraft/dsp/dsp_graph.cpp)

add_executable(lowfreq_bench main.cpp ${DSP_SOURCES})

target_include_directories(lowfreq_bench PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../host-harness/shim
        ${CMAKE_CURRENT_SOURCE_DIR}/../../core/dsp/src/main/cpp)
