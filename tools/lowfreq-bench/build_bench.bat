@echo off
rem 低频管家客观测量工具（MSVC + Ninja + CMake）
rem 用法：tools\lowfreq-bench\build_bench.bat [all^|freq^|thd^|dyn^|stereo]
if exist "%~dp0build" rd /s /q "%~dp0build"
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] vcvars64.bat failed
    exit /b 1
)
set "PATH=D:\Development\Android\Sdk\cmake\3.22.1\bin;%PATH%"
cd /d %~dp0\..\..
cmake -S tools/lowfreq-bench -B tools/lowfreq-bench/build -G Ninja -DCMAKE_CXX_COMPILER=cl >nul
echo CONFIGURE_EXIT=%errorlevel%
cmake --build tools/lowfreq-bench/build
echo BUILD_EXIT=%errorlevel%
if "%1"=="" (
    tools\lowfreq-bench\build\lowfreq_bench.exe all
) else (
    tools\lowfreq-bench\build\lowfreq_bench.exe %1
)
echo RUN_EXIT=%errorlevel%
