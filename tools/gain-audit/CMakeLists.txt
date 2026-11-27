cmake_minimum_required(VERSION 3.22.1)

project(sonicraft_gain_audit CXX)

# 默认 Release：harness 里有 CPU 性能断言（如 E9 31 段全开 <150ms），
# Debug（无 /O2）会慢 2.5 倍造成假失败。CI 与本机行为必须一致。
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    add_compile_options(-fno-fast-math -ffp-contract=off)
elseif(MSVC)
    add_compile_options(/fp:precise /utf-8)
endif()

set(DSP_SOURCES
        ${CMAKE_CURRENT_SOURCE_DIR}/../../core/dsp/src/main/cpp/sonicraft/dsp/dsp_graph.cpp)

add_executable(gain_audit main.cpp ${DSP_SOURCES})

target_include_directories(gain_audit PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../host-harness/shim
        ${CMAKE_CURRENT_SOURCE_DIR}/../../core/dsp/src/main/cpp)

# 虚拟环绕节点的电平标定（噪声激励；正弦不具代表性，故单独一个目标）
add_executable(vs_gain vs_gain.cpp ${DSP_SOURCES})

target_include_directories(vs_gain PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../host-harness/shim
        ${CMAKE_CURRENT_SOURCE_DIR}/../../core/dsp/src/main/cpp)

add_executable(limiter_check limiter_check.cpp ${DSP_SOURCES})
target_include_directories(limiter_check PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../host-harness/shim ${CMAKE_CURRENT_SOURCE_DIR}/../../core/dsp/src/main/cpp)


