@echo off
rem Sonicraft 宿主机 DSP harness（MSVC + Ninja + CMake）
rem 用法：cmd /c tools\host-harness\build_host.bat
if exist "%~dp0build" rd /s /q "%~dp0build"
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] vcvars64.bat 失败：请确认 VS2022 BuildTools 安装路径
    exit /b 1
)
set "PATH=D:\Development\Android\Sdk\cmake\3.22.1\bin;%PATH%"
cd /d %~dp0\..\..
cmake -S tools/host-harness -B tools/host-harness/build -G Ninja -DCMAKE_CXX_COMPILER=cl >nul
echo CONFIGURE_EXIT=%errorlevel%
cmake --build tools/host-harness/build
echo BUILD_EXIT=%errorlevel%
tools\host-harness\build\host_harness.exe
echo RUN_EXIT=%errorlevel%
