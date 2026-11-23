@echo off
rem 双端音频取证分析工具（MSVC + Ninja + CMake，复用 dsp 的 FftPlan）
rem 用法：cmd /c tools\audio-forensics\build_forensics.bat
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] vcvars64.bat 失败：请确认 VS2022 BuildTools 安装路径
    exit /b 1
)
set "PATH=D:\Development\Android\Sdk\cmake\3.22.1\bin;%PATH%"
cd /d %~dp0\..\..
cmake -S tools/audio-forensics -B tools/audio-forensics/build -G Ninja -DCMAKE_CXX_COMPILER=cl >nul
echo CONFIGURE_EXIT=%errorlevel%
cmake --build tools/audio-forensics/build
echo BUILD_EXIT=%errorlevel%
