@echo off
rem M12 全效果器行业验收 harness（MSVC 直编 native 头文件）
rem 用法：cmd /c tools\dsp-harness\build_dsp.bat
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] vcvars64.bat failed
    exit /b 1
)
cd /d %~dp0\..\..\tools\dsp-harness
cl /nologo /std:c++17 /O2 /EHsc /utf-8 /fp:precise /I %~dp0\..\..\core\dsp\src\main\cpp /I ..\eq-harness\stub main.cpp /Fe:dsp_harness.exe
echo BUILD_EXIT=%errorlevel%
dsp_harness.exe
echo RUN_EXIT=%errorlevel%
