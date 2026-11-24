@echo off
rem 一次性跑三套 DSP harness（CMake + Ninja + MSVC）。CI 亦复用同一套配置。
rem 用法：cmd /c tools\build_all_harness.bat
rem
rem 注意：必须显式 -DCMAKE_BUILD_TYPE=Release —— 默认 Debug（/Od /RTC1）会让
rem eq-harness 的 E9 CPU 断言（31 段全开 <150ms）假失败（实测 380ms vs 190ms）。
setlocal
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] vcvars64.bat failed: check VS2022 BuildTools path
    exit /b 1
)
set "PATH=D:\Development\Android\Sdk\cmake\3.22.1\bin;%PATH%"
cd /d %~dp0..

echo === host-harness ===
cmake -S tools/host-harness -B tools/host-harness/build -G Ninja -DCMAKE_CXX_COMPILER=cl -DCMAKE_BUILD_TYPE=Release >nul || exit /b 1
cmake --build tools/host-harness/build || exit /b 1
tools\host-harness\build\host_harness.exe || exit /b 1

echo === eq-harness ===
cmake -S tools/eq-harness -B tools/eq-harness/build -G Ninja -DCMAKE_CXX_COMPILER=cl -DCMAKE_BUILD_TYPE=Release >nul || exit /b 1
cmake --build tools/eq-harness/build || exit /b 1
tools\eq-harness\build\eq_harness.exe || exit /b 1

echo === dsp-harness ===
cmake -S tools/dsp-harness -B tools/dsp-harness/build -G Ninja -DCMAKE_CXX_COMPILER=cl -DCMAKE_BUILD_TYPE=Release >nul || exit /b 1
cmake --build tools/dsp-harness/build || exit /b 1
tools\dsp-harness\build\dsp_harness.exe || exit /b 1

echo ALL_HARNESS_OK
