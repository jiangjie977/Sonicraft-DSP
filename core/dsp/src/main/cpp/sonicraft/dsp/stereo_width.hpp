/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include "sonicraft/dsp/dsp_node.hpp"

#include <cmath>

namespace sonicraft::dsp {

/**
 * 立体声宽度（M/S 矩阵，M4d）。
 *
 *  - 公式（等价于 mid/side 缩放，无中间量）：
 *      L' = a·L + b·R,  R' = b·L + a·R，其中 a = (1+w)/2, b = (1-w)/2
 *    w=1 → a=1,b=0 恒等；w=0 → a=b=0.5 单声道化；w=2 → side 加倍展宽。
 *    推导自 mid=(L+R)/2, side=(L-R)/2, L'=mid+w·side, R'=mid-w·side。
 *  - 参数约定：param 0 = width（0.0~2.0，1.0 = 原样）；
 *    bypass 走 DspGraph 的 is_bypass 通道（A/B 标尺）。
 *  - width 变更经 PushCommand 投递，实时线程内 30ms 一阶斜坡，杜绝突变爆音。
 *    （参考万向 SurroundEngine 的 M/S 部分；其 depth 伪环绕与 cross 交叉馈送
 *    含经验魔法系数，不可证伪，v1 不做。）
 */
class StereoWidthNode : public DspNode {
 public:
  static constexpr int kParamWidth = 0;

  std::string_view id() const override { return "stereo_width"; }

  void Prepare(int sample_rate, int /*max_frames*/) override {
    sample_rate_ = sample_rate;
    target_width_ = 1.0f;
    current_width_ = 1.0f;
  }

  void Reset() override {}

  void Process(float* const* channels, int frames) override {
    if (bypass_) return;  // A/B 标尺：bypass = 原样透传（不处理）
    // width 平滑：30ms 时间常数，逐块逼近目标值
    const float alpha =
        1.0f - std::exp(-static_cast<float>(frames) / (0.030f * sample_rate_));
    float& cur = current_width_;
    const float tgt = target_width_;
    if (std::fabs(tgt - cur) > 0.005f) {
      cur += (tgt - cur) * alpha;
    } else {
      cur = tgt;
    }
    const float w = cur;
    const float a = (1.0f + w) * 0.5f;
    const float b = (1.0f - w) * 0.5f;
    float* l = channels[0];
    float* r = channels[1];
    for (int i = 0; i < frames; ++i) {
      const float xl = l[i], xr = r[i];
      l[i] = a * xl + b * xr;
      r[i] = b * xl + a * xr;
    }
  }

  void SetBypass(bool bypass) override { bypass_ = bypass; }
  bool Bypassed() const override { return bypass_; }

  void SetParam(int param_id, float value) override {
    if (param_id == kParamWidth) {
      target_width_ = value < 0.0f ? 0.0f : (value > 2.0f ? 2.0f : value);
    }
  }

  /** 外部在 AddNode 前设置声道数（与 ParametricEqNode 同一约定） */
  void set_channel_count(int channels) {
    channels_ = channels;
  }

 private:
  int sample_rate_ = 48000;
  int channels_ = 2;
  bool bypass_ = true;  // 默认透传：先听原声副本
  float target_width_ = 1.0f;
  float current_width_ = 1.0f;
};

}  // namespace sonicraft::dsp
