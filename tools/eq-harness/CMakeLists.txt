cmake_minimum_required(VERSION 3.22.1)
project(sonicraft_eq_harness CXX)

# 默认 Release：harness 里有 CPU 性能断言（如 E9 31 段全开 <150ms），
# Debug（无 /O2）会慢 2.5 倍造成假失败。CI 与本机行为必须一致。
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

# PEQ 行业标准验收（E1~E12）。原本只有 MSVC 直编的 build_eq.bat，
# 这里补 CMake 以便 CI（windows-latest）复用同一套构建。
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    add_compile_options(-fno-fast-math -ffp-contract=off)
elseif(MSVC)
    add_compile_options(/fp:precise /utf-8)
endif()

add_executable(eq_harness main.cpp)

target_include_directories(eq_harness PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/stub
        ${CMAKE_CURRENT_SOURCE_DIR}/../../core/dsp/src/main/cpp)
