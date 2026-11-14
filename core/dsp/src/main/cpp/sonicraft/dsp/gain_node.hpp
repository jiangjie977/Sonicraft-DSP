/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include "sonicraft/dsp/dsp_node.hpp"

#include <cmath>

namespace sonicraft::dsp {

/**
 * 线性增益节点（M5 链序扩展）：输入驱动衰减（链首）与输出响度补偿（尾部 Limiter
 * 之前）共用同一实现，实例化两次。增益 0~2（0=静音，1=原样，2=+6dB），
 * 30ms one-pole 斜坡防突变。bypass 透传但增益状态照常推进。
 */
class GainNode : public DspNode {
 public:
  static constexpr int kParamGain = 0;

  std::string_view id() const override { return id_; }

  void Prepare(int sample_rate, int max_frames) override {
    sample_rate_ = sample_rate;
    block_ = max_frames;
    target_gain_ = 1.0f;
    current_gain_ = 1.0f;
  }

  void Reset() override {}

  void Process(float* const* channels, int frames) override {
    const float alpha =
        1.0f - std::exp(-static_cast<float>(frames) / (0.030f * sample_rate_));
    if (std::fabs(target_gain_ - current_gain_) > 0.001f) {
      current_gain_ += (target_gain_ - current_gain_) * alpha;
    } else {
      current_gain_ = target_gain_;
    }
    const float g = bypass_ ? 1.0f : current_gain_;
    if (g == 1.0f) return;
    for (int c = 0; c < channels_; ++c) {
      float* ch = channels[c];
      for (int i = 0; i < frames; ++i) ch[i] *= g;
    }
  }

  void SetBypass(bool bypass) override { bypass_ = bypass; }
  bool Bypassed() const override { return bypass_; }

  void SetParam(int param_id, float value) override {
    if (param_id == kParamGain) {
      target_gain_ = value < 0.0f ? 0.0f : (value > 2.0f ? 2.0f : value);
    }
  }

  void set_channel_count(int channels) { channels_ = channels; }
  void set_id(const char* id) { id_ = id; }

 private:
  const char* id_ = "gain";
  int sample_rate_ = 48000;
  int channels_ = 2;
  int block_ = 192;
  bool bypass_ = true;
  float target_gain_ = 1.0f;
  float current_gain_ = 1.0f;
};

}  // namespace sonicraft::dsp
