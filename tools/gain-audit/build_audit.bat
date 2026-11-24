@echo off
rem 链路增益审计（MSVC + Ninja + CMake）
rem 用法：cmd /c tools\gain-audit\build_audit.bat
if exist "%~dp0build" rd /s /q "%~dp0build"
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] vcvars64.bat failed: check VS2022 BuildTools path
    exit /b 1
)
set "PATH=D:\Development\Android\Sdk\cmake\3.22.1\bin;%PATH%"
cd /d %~dp0\..\..
cmake -S tools/gain-audit -B tools/gain-audit/build -G Ninja -DCMAKE_CXX_COMPILER=cl >nul
echo CONFIGURE_EXIT=%errorlevel%
cmake --build tools/gain-audit/build
echo BUILD_EXIT=%errorlevel%
tools\gain-audit\build\gain_audit.exe
echo RUN_EXIT=%errorlevel%
