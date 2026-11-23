// SCVM 每样本耗时基准（与运行时同一套 cvm.hpp，便于在 PC / 真机复现）。
//
// PC：  tools\script-bench\build_bench.bat
// 真机：用 NDK clang 交叉编译后 push 到 /data/local/tmp 运行，例如
//   clang++ --target=aarch64-linux-android29 -std=c++17 -O2 -fPIE -pie \
//     -static-libstdc++ -I core/dsp/src/main/cpp cvm_bench.cpp -o cvm_bench
//   adb push cvm_bench /data/local/tmp/ && adb shell chmod 755 /data/local/tmp/cvm_bench
//   adb shell /data/local/tmp/cvm_bench
//
// 指标：ns/sample（逐样本解释器开销）、CPU 占用（处理 1 秒音频 / 1 秒）、
//       每 192 帧块耗时（对照 4ms 预算）。

#include "sonicraft/dsp/cvm.hpp"

#include <chrono>
#include <cstdio>
#include <string>

using namespace sonicraft::dsp::cvm;

static void Bench(const char* name, const std::string& src, int iters) {
  Program p;
  std::string err;
  if (!Compile(src, p, err)) {
    std::printf("%-24s COMPILE ERR: %s\n", name, err.c_str());
    return;
  }
  Vm vm;
  vm.Reset(&p);
  vm.gmem()[kSlotSrate] = 48000.0f;
  vm.gmem()[kSlotSlider1] = 0.5f;
  vm.RunInit();

  volatile float acc = 0.0f;
  const auto t0 = std::chrono::high_resolution_clock::now();
  for (int i = 0; i < iters; ++i) {
    vm.gmem()[kSlotSpl0] = 0.5f;
    vm.gmem()[kSlotSpl1] = -0.5f;
    vm.RunSample();
    acc = acc + vm.gmem()[kSlotSpl0];
  }
  const auto t1 = std::chrono::high_resolution_clock::now();

  const double ns = std::chrono::duration<double, std::nano>(t1 - t0).count() / iters;
  const double cpu_pct = ns * 48000.0 / 1e9 * 100.0;
  const double block_us = ns * 192.0 / 1000.0;
  std::printf("%-24s %8.2f ns/sample  CPU %5.2f%%   块(192帧) %6.1f us / 4000 us\n",
              name, ns, cpu_pct, block_us);
  (void)acc;
}

int main() {
  const int N = 48000 * 5;
  std::printf("SCVM benchmark (48kHz, per-sample)\n");
  Bench("gain", "void init(){}\nvoid sample(){ spl0 = spl0 * 0.8; spl1 = spl1 * 0.8; }", N);
  Bench("soft-clip+slider",
        "// slider1: 0.0, 8.0\nvoid init(){}\n"
        "void sample(){ float g=1.0+slider1; float x=spl0*g; spl0=x/(1.0+abs(x));"
        " float y=spl1*g; spl1=y/(1.0+abs(y)); }",
        N);
  Bench("biquad lowpass",
        "float b0=0.02; float b1=0.04; float b2=0.02; float a1=-1.5; float a2=0.6;\n"
        "float x1=0.0; float x2=0.0; float y1=0.0; float y2=0.0;\n"
        "void init(){}\n"
        "void sample(){ float x=spl0; float y=b0*x+b1*x1+b2*x2-a1*y1-a2*y2;"
        " x2=x1; x1=x; y2=y1; y1=y; spl0=y; spl1=y; }",
        N);
  Bench("delay 4800 + mix",
        "float buf[4800]; int idx=0;\n"
        "void init(){}\n"
        "void sample(){ float d=buf[idx]; buf[idx]=spl0; idx=(idx+1)%4800;"
        " spl0=spl0*0.7+d*0.3; spl1=spl0; }",
        N);
  Bench("recursive fib(8)",
        "float fib(float n){ if(n<2.0){ return n; } return fib(n-1.0)+fib(n-2.0); }\n"
        "void init(){}\n"
        "void sample(){ spl0=fib(8.0)*0.001; spl1=spl0; }",
        N / 20);
  return 0;
}
