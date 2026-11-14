/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include "sonicraft/dsp/dsp_node.hpp"

#include <cmath>

namespace sonicraft::dsp {

/**
 * 声道平衡节点（M5 链序扩展，链首源校正区）：校正源素材的左右声道失衡。
 *
 * 参数 balance ∈ [-1, +1]：-1 = 声像全左，0 = 居中，+1 = 全右。
 * 采用等功率 pan law（-3dB）：gL = cos((b+1)·π/4), gR = sin((b+1)·π/4)——
 * 极端位置时对侧声道静音、总功率守恒（线性 pan 中心会 +3dB）。
 * 30ms one-pole 斜坡防突变。bypass = 透传，状态照常推进。
 */
class BalanceNode : public DspNode {
 public:
  static constexpr int kParamBalance = 0;

  std::string_view id() const override { return "balance"; }

  void Prepare(int sample_rate, int max_frames) override {
    sample_rate_ = sample_rate;
    block_ = max_frames;
    target_ = 0.0f;
    current_ = 0.0f;
  }

  void Reset() override {}

  void Process(float* const* channels, int frames) override {
    const float alpha =
        1.0f - std::exp(-static_cast<float>(frames) / (0.030f * sample_rate_));
    if (std::fabs(target_ - current_) > 0.001f) {
      current_ += (target_ - current_) * alpha;
    } else {
      current_ = target_;
    }
    if (channels_ < 2 || (bypass_ || current_ == 0.0f)) return;  // 居中恒等快路径
    // 等功率 pan：b=-1 → (1,0)，b=0 → (√2/2, √2/2)，b=+1 → (0,1)（相对居中归一）
    const float theta = (current_ + 1.0f) * 0.7853981633974483f;  // (b+1)·π/4
    const float gl = std::cos(theta) * 1.4142135623730951f;
    const float gr = std::sin(theta) * 1.4142135623730951f;
    float* l = channels[0];
    float* r = channels[1];
    for (int i = 0; i < frames; ++i) {
      l[i] *= gl;
      r[i] *= gr;
    }
  }

  void SetBypass(bool bypass) override { bypass_ = bypass; }
  bool Bypassed() const override { return bypass_; }

  void SetParam(int param_id, float value) override {
    if (param_id == kParamBalance) {
      target_ = value < -1.0f ? -1.0f : (value > 1.0f ? 1.0f : value);
    }
  }

  /** 外部在 AddNode 前设置声道数（与 ParametricEqNode 同一约定） */
  void set_channel_count(int channels) { channels_ = channels; }

 private:
  int sample_rate_ = 48000;
  int channels_ = 2;
  int block_ = 192;
  bool bypass_ = true;
  float target_ = 0.0f;
  float current_ = 0.0f;
};

}  // namespace sonicraft::dsp
