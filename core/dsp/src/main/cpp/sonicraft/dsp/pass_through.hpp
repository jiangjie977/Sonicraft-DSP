/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include "sonicraft/dsp/dsp_node.hpp"

namespace sonicraft::dsp {

/** 直通节点：M0 用于验证管线与自检，bypass 时逐样本置零。 */
class PassThroughNode : public DspNode {
 public:
  std::string_view id() const override { return "pass_through"; }
  void Prepare(int, int) override {}
  void Reset() override {}
  void Process(float* const* channels, int frames) override {
    if (bypass_) {
      for (int c = 0; c < channel_count_; ++c) {
        float* ch = channels[c];
        for (int i = 0; i < frames; ++i) ch[i] = 0.0f;
      }
    }
  }
  void SetBypass(bool bypass) override { bypass_ = bypass; }
  bool Bypassed() const override { return bypass_; }

  void set_channel_count(int channels) { channel_count_ = channels; }

 private:
  bool bypass_ = false;
  int channel_count_ = 2;
};

}  // namespace sonicraft::dsp
