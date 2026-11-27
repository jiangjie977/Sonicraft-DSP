/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * 虚拟环绕节点的**电平标定**测量（离线，噪声激励）。
 *
 * 为什么单独一个工具：
 *   gain_audit 用稳态正弦测各节点，但环绕节点的输出是多路 HRTF 卷积的叠加，
 *   正弦下的相位关系与实际音乐差异很大，测出来的增益不可用。
 *   这里用**白噪声**（频谱平坦、相位随机）测 RMS 比，才接近真实节目的电平。
 *
 * 测什么：
 *   同一个噪声信号，分别经过
 *     A. 节点旁路（enabled=0，逐样本透传）
 *     B. 经典矩阵解码
 *     C. Ambisonic（HOA 12 扬声器）
 *     D. VBAP 12
 *   输出 RMS 与输入 RMS 的比值（dB），就是"开关前后用户听到的音量差"。
 *
 * 用法：
 *   cmake --build 后运行 vs_gain.exe，直接打印四种状态的增益。
 */
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <vector>

#include "sonicraft/dsp/virtual_surround.hpp"

namespace {

constexpr int kRate = 48000;
constexpr int kBlock = 192;
constexpr int kWarmupBlocks = 60;   // 等交叉淡化、房间/反射状态收敛
constexpr int kMeasureBlocks = 200;

uint32_t g_seed = 0x1234567u;

float Noise() {
  // xorshift32 → [-1, 1)
  g_seed ^= g_seed << 13;
  g_seed ^= g_seed >> 17;
  g_seed ^= g_seed << 5;
  const float v = static_cast<float>(g_seed & 0xFFFFFFu) / 8388608.0f - 1.0f;
  return v * 0.25f;   // 约 −12 dBFS RMS，留足余量避免限幅器介入
}

struct Result {
  double in_rms = 0.0;
  double out_rms = 0.0;
  double gain_db() const { return 20.0 * std::log10((out_rms + 1e-12) / (in_rms + 1e-12)); }
};

Result Measure(sonicraft::dsp::VirtualSurroundNode& node) {
  std::vector<float> l(kBlock), r(kBlock);
  Result res;
  double sum_in = 0.0, sum_out = 0.0;
  long n = 0;

  for (int b = 0; b < kWarmupBlocks + kMeasureBlocks; ++b) {
    for (int i = 0; i < kBlock; ++i) {
      const float x = Noise();
      l[i] = x;
      r[i] = x * 0.9f;   // 轻微左右差异，避免解码器进入完全相关分支
    }
    if (b >= kWarmupBlocks) {
      for (int i = 0; i < kBlock; ++i) {
        sum_in += static_cast<double>(l[i]) * l[i] + static_cast<double>(r[i]) * r[i];
      }
    }
    float* ch[2] = {l.data(), r.data()};
    node.Process(ch, kBlock);
    if (b >= kWarmupBlocks) {
      for (int i = 0; i < kBlock; ++i) {
        sum_out += static_cast<double>(ch[0][i]) * ch[0][i] +
                   static_cast<double>(ch[1][i]) * ch[1][i];
      }
      n += 2 * kBlock;
    }
  }
  res.in_rms = std::sqrt(sum_in / static_cast<double>(n));
  res.out_rms = std::sqrt(sum_out / static_cast<double>(n));
  return res;
}

void Run(const char* label, int enabled, int decoder, int hrtf_model) {
  sonicraft::dsp::VirtualSurroundNode node;
  node.Prepare(kRate, kBlock);
  // DspNode 默认 bypass=true，必须显式解除，否则 Process 直接透传（测出来恒为 0 dB）
  node.SetBypass(false);
  node.SetParam(sonicraft::dsp::vs::kParamEnabled, static_cast<float>(enabled));
  node.SetParam(sonicraft::dsp::vs::kParamAmbisonicMode, static_cast<float>(decoder));
  node.SetParam(sonicraft::dsp::vs::kParamHrtfModel, static_cast<float>(hrtf_model));
  node.SetParam(sonicraft::dsp::vs::kParamRoomSize, 0.0f);
  node.SetParam(sonicraft::dsp::vs::kParamTransientProtect, 0.0f);
  // 关掉所有会额外改变电平的处理，只看解码 + HRTF 链路
  node.SetParam(sonicraft::dsp::vs::kParamBassEnhancer, 0.0f);
  node.SetParam(sonicraft::dsp::vs::kParamStereoExpand, 0.5f);
  node.SetParam(sonicraft::dsp::vs::kParamHeightStrength, 0.5f);
  node.SetParam(sonicraft::dsp::vs::kParamDistance, 0.5f);

  const Result r = Measure(node);
  std::printf("%-34s in=%.4f  out=%.4f  gain=%+7.2f dB\n", label, r.in_rms, r.out_rms,
              r.gain_db());
}

}  // namespace

int main() {
  std::printf("=== 虚拟环绕节点电平标定（白噪声，48 kHz / 192 帧）===\n");
  std::printf("目标：开启与旁路的 gain 差应接近 0 dB\n\n");

  Run("A. 旁路（enabled=0）", 0, 0, 0);
  Run("B. 经典矩阵 + 参数化 HRTF", 1, 0, 0);
  Run("C. HOA 12 + 参数化 HRTF", 1, 1, 0);
  Run("D. HOA 12 + 实测 IR(KEMAR)", 1, 1, 1);
  Run("E. VBAP 12 + 实测 IR(KEMAR)", 1, 2, 1);
  std::printf("\n注：差值为正表示「开启比关闭更响」。\n");
  return 0;
}
