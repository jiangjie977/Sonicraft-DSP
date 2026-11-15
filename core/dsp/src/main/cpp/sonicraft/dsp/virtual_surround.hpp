/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include <array>
#include <cmath>
#include <vector>

#include "sonicraft/dsp/dsp_node.hpp"
#include "sonicraft/dsp/vs_decoder.hpp"
#include "sonicraft/dsp/vs_hrtf.hpp"
#include "sonicraft/dsp/vs_params.hpp"
#include "sonicraft/dsp/vs_post.hpp"
#include "sonicraft/dsp/vs_selftest.hpp"

namespace sonicraft::dsp {

/**
 * 虚拟环绕渲染节点（立体声 → 7 声道虚拟布局 → 双耳）。
 *
 * 处理链（级内顺序不可调换）：
 *   ① 解码   立体声 → 7 声道（M/S + 相干度 + 非对称 steering + 80Hz LFE）
 *   ② 编解码 可选 Lt/Rt 往返（强度 0 = 直通）—— 待实现
 *   ③ 后处理 逐声道 trim/静音、中置三态、人声清晰度、低音谐波、后中置 —— 待实现
 *   ④ 渲染   HRTF（每声道一组参数化模型）→ 双耳；房间 RIR —— 待实现
 *
 * 实时性：Prepare 分配全部缓冲；Process 内不分配、不加锁、不打日志。
 * 参数经 DspGraph 的 SPSC 队列投递，节点内一阶平滑，开关走 20 ms 交叉淡化。
 */
class VirtualSurroundNode final : public DspNode {
 public:
  VirtualSurroundNode() {
    for (int i = 0; i < vs::kParamCount; ++i) {
      param_[i] = vs::kDefault[i];
      target_[i] = vs::kDefault[i];
    }
  }

  std::string_view id() const override { return "virtual_surround"; }

  void Prepare(int sample_rate, int max_frames) override {
    sample_rate_ = sample_rate > 0 ? sample_rate : 48000;
    max_frames_ = max_frames > 0 ? max_frames : 512;
    layout_.assign(static_cast<std::size_t>(vs::kChannelCount) * max_frames_, 0.0f);

    const float sr = static_cast<float>(sample_rate_);
    decoder_.Prepare(sr);
    for (int c = 0; c < vs::kChannelCount; ++c) {
      hrtf_ch_[static_cast<std::size_t>(c)].Prepare(sr);
      hrtf_ch_[static_cast<std::size_t>(c)].Configure(vs::kAzimuthDeg[c], 0.0f);
    }
    hrtf_model_.Prepare(sr);
    post_.Prepare(sr);
    for (int c = 0; c < vs::kChannelCount; ++c) {
      ch_ptr_[static_cast<std::size_t>(c)] = layout_.data() +
          static_cast<std::size_t>(c) * static_cast<std::size_t>(max_frames_);
    }
    Reset();
  }

  void Reset() override {
    render_gain_ = 0.0f;  // 从静音淡入，避免开关瞬间爆音
    decoder_.Reset();
    for (auto& h : hrtf_ch_) h.Reset();
    hrtf_model_.Reset();
    post_.Reset();
  }

  void SetBypass(bool bypass) override { bypass_ = bypass; }
  bool Bypassed() const override { return bypass_; }

  void SetParam(int param_id, float value) override {
    if (param_id < 0 || param_id >= vs::kParamCount) return;
    target_[param_id] = value;
  }

  /**
   * 声像定位自检 + 自动校正（**非实时**，由 UI/JNI 在非音频线程触发）。
   *
   * 用带限噪声在 ±90° 范围测 7 个方位，检查：
   *   ① 左右不接反（θ>0 必须"右响 + 右耳早"）
   *   ② 左右镜像对称
   *   ③ ITD/ILD 与理论值的偏差（判据见 LocalizationSelfTest）
   *
   * apply_correction=true 时把校正系数写回渲染器（ITD 缩放、ILD 修调），再测一轮确认收敛。
   */
  vs::LocalizationSelfTest::Report RunLocalizationSelfTest(bool apply_correction = true) {
    last_report_ = vs::LocalizationSelfTest::Run(hrtf_model_, static_cast<float>(sample_rate_),
                                                 apply_correction);
    if (apply_correction) {
      // 校正同样作用到 7 个声道渲染器，否则自检与实际渲染口径不一致
      for (auto& h : hrtf_ch_) h.SetCalibration(last_report_.itd_scale, last_report_.ild_trim);
    }
    return last_report_;
  }

  const vs::LocalizationSelfTest::Report& last_self_test() const { return last_report_; }

  void Process(float* const* channels, int frames) override {
    if (frames <= 0 || channels == nullptr) return;
    if (frames > max_frames_) frames = max_frames_;

    // 参数一阶平滑（约 20 ms 时间常数 @48k/512）
    constexpr float kParamSmooth = 0.25f;
    for (int i = 0; i < vs::kParamCount; ++i) {
      param_[i] += kParamSmooth * (target_[i] - param_[i]);
    }

    const bool want = param_[vs::kParamEnabled] > 0.5f && !bypass_;
    if (!want && render_gain_ <= 0.0f) return;  // 完全旁路：逐样本透传，零开销

    const float step = 1.0f / std::max(1.0f, static_cast<float>(sample_rate_) * 0.02f);
    const float goal = want ? 1.0f : 0.0f;

    decoder_.SetMode(param_[vs::kParamMode] > 0.5f);
    decoder_.SetGains(param_[vs::kParamCenterGain], param_[vs::kParamSurroundGain], 0.0f);

    DecodeFrames(channels[0], channels[1], frames);

    // 7 声道后处理：中置三态 / 人声清晰度 / 低音谐波 / 后中置
    vs::PostStage::Params pp;
    pp.center_mode = param_[vs::kParamCenterMode];
    pp.dialog_db = param_[vs::kParamDialog];
    pp.dialog_enabled = param_[vs::kParamDialogEnabled];
    pp.bass_enhancer = param_[vs::kParamBassEnhancer];
    pp.bass_enhancer_mode = param_[vs::kParamBassEnhancerMode];
    pp.rear_center_enabled = param_[vs::kParamRearCenterEnabled];
    pp.rear_delay_ms = param_[vs::kParamRearCenterDelayMs];
    post_.Process(ch_ptr_.data(), frames, pp);

    RenderFrames(channels[0], channels[1], frames, goal, step);
  }

 private:
  void DecodeFrames(const float* l, const float* r, int frames) {
    for (int i = 0; i < frames; ++i) {
      float out[vs::kChannelCount];
      decoder_.ProcessSample(l[i], r[i], out);
      for (int c = 0; c < vs::kChannelCount; ++c) {
        layout_[static_cast<std::size_t>(c) * max_frames_ + i] = out[c];
      }
    }
  }

  /** 逐声道 HRTF 累加到双耳，再与干声按 render_gain_ 交叉淡化 */
  void RenderFrames(float* l, float* r, int frames, float goal, float step) {
    // 逐声道 trim（dB → 线性）与静音；LFE 不做 HRTF，直接按增益混入双耳
    float gain[vs::kChannelCount];
    for (int c = 0; c < vs::kChannelCount; ++c) {
      const int trim_id = vs::kParamTrimL + c;
      const int mute_id = vs::kParamMuteL + c;
      gain[c] = param_[mute_id] > 0.5f ? 0.0f : std::pow(10.0f, param_[trim_id] / 20.0f);
    }

    for (int i = 0; i < frames; ++i) {
      float wet_l = 0.0f;
      float wet_r = 0.0f;
      for (int c = 0; c < vs::kChannelCount; ++c) {
        const float x = layout_[static_cast<std::size_t>(c) * max_frames_ + i] * gain[c];
        if (c == vs::kLFE) {
          wet_l += x;
          wet_r += x;
          continue;
        }
        float hl = 0.0f, hr = 0.0f;
        hrtf_ch_[static_cast<std::size_t>(c)].ProcessSample(x, &hl, &hr);
        wet_l += hl;
        wet_r += hr;
      }

      // 交叉淡化：dry*(1−g) + wet*g（g 逐样本推进，开关无爆音）
      render_gain_ += (goal > render_gain_) ? step : -step;
      if (render_gain_ < 0.0f) render_gain_ = 0.0f;
      if (render_gain_ > 1.0f) render_gain_ = 1.0f;
      const float g = render_gain_;
      l[i] = l[i] * (1.0f - g) + wet_l * g;
      r[i] = r[i] * (1.0f - g) + wet_r * g;
    }
  }

  bool bypass_ = true;   // 默认旁路，接入图后由 UI 打开
  float render_gain_ = 0.0f;
  int sample_rate_ = 48000;
  int max_frames_ = 512;

  std::array<float, vs::kParamCount> param_{};
  std::array<float, vs::kParamCount> target_{};
  std::vector<float> layout_;  // 7 声道 × max_frames（planar 展开存储）

  vs::StereoSpatialDecoder decoder_;
  vs::PostStage post_;
  std::array<float*, vs::kChannelCount> ch_ptr_{};
  std::array<vs::ParametricHrtf, vs::kChannelCount> hrtf_ch_{};
  vs::ParametricHrtf hrtf_model_;
  vs::LocalizationSelfTest::Report last_report_{};
};

}  // namespace sonicraft::dsp
