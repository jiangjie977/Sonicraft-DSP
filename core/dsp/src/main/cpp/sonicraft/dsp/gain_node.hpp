/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include "sonicraft/dsp/dsp_node.hpp"

#include <cmath>

namespace sonicraft::dsp {

/**
 * 线性增益节点（链序扩展）：输入驱动衰减（链首）与输出响度补偿（尾部 Limiter
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
    // 基准增益始终生效（bypass 只跳过旋钮量，不跳过基准）
    const float g = (bypass_ ? 1.0f : current_gain_) * makeup_;
    if (g == 1.0f) return;
    for (int c = 0; c < channels_; ++c) {
      float* ch = channels[c];
      for (int i = 0; i < frames; ++i) ch[i] *= g;
    }
  }

  void SetBypass(bool bypass) override { bypass_ = bypass; }
  bool Bypassed() const override { return bypass_; }

  /**
   * 设置本实例的增益上限（线性倍数）。
   *
   * 默认 2.0（+6 dB）—— 输入增益必须保持这个值：前端过载是不可逆的，
   * 采进来的波形一旦削平，后面任何 DSP 都救不回来。
   * 输出增益可以放宽（它之后只剩限幅器，过载会被兜住），由装配处指定。
   */
  void set_max_gain(float max_gain) { max_gain_ = max_gain > 0.0f ? max_gain : 2.0f; }

  /**
   * 固定基准增益（不随旋钮变化，bypass 时也生效）。
   *
   * 用途：输出级的内建响度基准。本机实测「输出增益 +12 dB 才是正常音量」，
   * 于是把 +12 dB（×3.9811）烘焙到输出级，旋钮回到 0~2×（相对基准 ±）。
   * 输入级不设基准（保持 1.0）。
   */
  void set_makeup_gain(float makeup) { makeup_ = makeup > 0.0f ? makeup : 1.0f; }

  void SetParam(int param_id, float value) override {
    if (param_id == kParamGain) {
      target_gain_ = value < 0.0f ? 0.0f : (value > max_gain_ ? max_gain_ : value);
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
  float max_gain_ = 2.0f;   // 旋钮上限（默认 +6 dB）；输入实例必须保持
  float makeup_ = 1.0f;     // 固定基准增益；仅输出实例设 +12 dB
};

}  // namespace sonicraft::dsp
