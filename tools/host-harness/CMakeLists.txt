cmake_minimum_required(VERSION 3.22.1)
project(sonicraft_host_harness CXX)

# 宿主机 DSP 验证 harness：不依赖 Android/NDK，直接编译 core:dsp 的纯算法源码。
# 用法（任选其一）：
#   Windows: cmake -S . -B build -G Ninja && cmake --build build
#   运行:    ./build/host_harness
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 与 Android 侧保持一致：禁用快速数学与 FP 收缩，保证 golden 比对可复现
if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    add_compile_options(-fno-fast-math -ffp-contract=off)
elseif(MSVC)
    add_compile_options(/fp:precise /utf-8)
endif()

set(DSP_SOURCES
        ${CMAKE_CURRENT_SOURCE_DIR}/../../core/dsp/src/main/cpp/sonicraft/dsp/dsp_graph.cpp)

add_executable(host_harness main.cpp ${DSP_SOURCES})

target_include_directories(host_harness PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/shim
        ${CMAKE_CURRENT_SOURCE_DIR}/../../core/dsp/src/main/cpp)
