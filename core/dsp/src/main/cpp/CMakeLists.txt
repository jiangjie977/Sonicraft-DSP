cmake_minimum_required(VERSION 3.22.1)
project(sonicraft_dsp CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# 实时音频代码禁用快速数学与 FP 收缩，保证逐样本可复现（golden 文件比对依赖）
if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    add_compile_options(-fno-fast-math -ffp-contract=off)
    # 实时 DSP 内核（FFT/卷积）即使在 Debug 变体也必须优化：gradle assembleDebug
    # 走 CMake Debug（-O0），数值内核无优化慢一个数量级（真机实测混响块 9ms）。
    # add_compile_options 位于 flags_<config> 之后，此处 -O2 覆盖 Debug 的 -O0。
    add_compile_options(-O2)
elseif(MSVC)
    add_compile_options(/fp:precise)
endif()

add_library(sonicraft-dsp SHARED
        sonicraft/dsp/dsp_graph.cpp
        sonicraft/engine/native_audio_engine.cpp
        sonicraft/jni/bridge.cpp)

# 源码以 cpp 根目录为基准 include（如 "sonicraft/dsp/dsp_graph.hpp"）
target_include_directories(sonicraft-dsp PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)
# AAudio 输出流（实时低延迟播放）
find_library(aaudio-lib aaudio)
target_link_libraries(sonicraft-dsp ${log-lib} ${aaudio-lib})

# 16KB 页对齐（Android 15 要求；NDK 27 默认 4KB）
target_link_options(sonicraft-dsp PRIVATE "-Wl,-z,max-page-size=16384")
# 自定义脚本效果器后端 = 自研 SCVM（header-only，见 sonicraft/dsp/cvm.hpp），
# 零外部依赖、无需额外 CMake 源文件。
