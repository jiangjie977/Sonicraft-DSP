@echo off
REM SCVM 性能基准（PC/MSVC）。真机跑法见 cvm_bench.cpp 头部注释。
setlocal

set VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat
if not exist "%VCVARS%" (
    echo [bench] vcvars64.bat 不存在，请按本机 VS 安装路径修改脚本头部
    exit /b 1
)
call "%VCVARS%" >nul

set ROOT=%~dp0..\..
pushd "%ROOT%"
if not exist build\script-bench mkdir build\script-bench
cl /nologo /std:c++17 /EHsc /O2 /utf-8 ^
   /I "core\dsp\src\main\cpp" ^
   "tools\script-bench\cvm_bench.cpp" ^
   /Fo:build\script-bench\ /Fe:build\script-bench\cvm_bench.exe
if errorlevel 1 (
    popd
    echo [bench] 编译失败
    exit /b 1
)
echo.
build\script-bench\cvm_bench.exe
popd
