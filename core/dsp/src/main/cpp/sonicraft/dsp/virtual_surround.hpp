/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include <array>
#include <cmath>
#include <vector>

#include "sonicraft/dsp/dsp_node.hpp"
#include "sonicraft/dsp/vs_params.hpp"

namespace sonicraft::dsp {

/**
 * 虚拟环绕渲染节点（立体声 → 7 声道虚拟布局 → 双耳）。
 *
 * 处理链（4 级，级内顺序不可调换）：
 *   1. 解码   StereoSpatialDecoder：M/S 分解 + 相干度分析 + 非对称 steering
 *             → 7 声道（FL/FR/C/SL/SR/CS/LFE，低频 120Hz 以下并入 LFE）
 *   2. 编解码 往返可选（encode_strength=0 时完全直通，避免相位损失）
 *   3. 后处理 逐声道 trim/静音、中置三态、人声清晰度、低音谐波、后中置延迟
 *   4. 渲染   HRTF（参数化模型 / 用户导入数据集）+ 可选早期反射 → 双耳
 *             + 房间 RIR 卷积（小/客厅/音乐厅/大教堂）
 *
 * 实时性：Prepare 分配全部缓冲；Process 内不分配、不加锁、不打日志。
 * 参数经 DspGraph 的 SPSC 队列投递，节点内做一阶平滑，避免突变爆音。
 *
 * **当前状态**：参数契约与骨架已就位，四级 DSP 体在下一步逐级填充
 * （见各 stage 函数的 TODO）。未接入音频图前不会影响现有声音。
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
    Reset();
  }

  void Reset() override {
    for (int c = 0; c < vs::kChannelCount; ++c) {
      for (int i = 0; i < kSmoothStateCount; ++i) unused_smooth_[c][i] = 0.0f;
    }
    render_gain_ = 0.0f;  // 从静音开始淡入，避免开关瞬间爆音
    decoder_.Reset();
    hrtf_.Reset();
    room_.Reset();
  }

  void SetBypass(bool bypass) override { bypass_ = bypass; }
  bool Bypassed() const override { return bypass_; }

  void SetParam(int param_id, float value) override {
    if (param_id < 0 || param_id >= vs::kParamCount) return;
    target_[param_id] = value;
  }

  void Process(float* const* channels, int frames) override {
    if (frames <= 0 || channels == nullptr) return;
    if (frames > max_frames_) frames = max_frames_;

    // 参数平滑：每块逼近目标值（系数按 48k / 512 帧标定，约 20ms 时间常数）
    const float coef = 0.25f;
    for (int i = 0; i < vs::kParamCount; ++i) {
      param_[i] += coef * (target_[i] - param_[i]);
    }

    const bool want = param_[vs::kParamEnabled] > 0.5f && !bypass_;
    const float step = 1.0f / std::max(1.0f, static_cast<float>(sample_rate_) * 0.02f);
    const float goal = want ? 1.0f : 0.0f;

    if (!want && render_gain_ <= 0.0f) {
      return;  // 完全旁路：直接透传，零开销
    }

    // 交叉淡化增益（逐帧推进）
    for (int i = 0; i < frames; ++i) {
      render_gain_ += (goal > render_gain_) ? step : -step;
      if (render_gain_ < 0.0f) render_gain_ = 0.0f;
      if (render_gain_ > 1.0f) render_gain_ = 1.0f;
    }

    DecodeStage(channels[0], channels[1], frames);
    EncodeStage(frames);
    PostStage(frames);
    RenderStage(channels[0], channels[1], frames);
  }

 private:
  static constexpr int kSmoothStateCount = 4;

  // ---- 各级实现（下一步逐级填充，接口先固定，避免牵连调用方）----
  void DecodeStage(const float* l, const float* r, int frames) {
    (void)l; (void)r; (void)frames;  // TODO 解码：M/S + 相干度 + 非对称 steering
  }
  void EncodeStage(int frames) { (void)frames; }  // TODO 编解码往返（强度为 0 时直通）
  void PostStage(int frames) { (void)frames; }    // TODO trim/中置三态/人声/低音/后中置
  void RenderStage(float* l, float* r, int frames) {
    // 未实现前保持透传（render_gain_ 已保证开关无爆音）
    (void)l; (void)r; (void)frames;  // TODO HRTF + 早期反射 + 房间 RIR
  }

  struct DecoderState { void Reset() {} } decoder_;
  struct HrtfState { void Reset() {} } hrtf_;
  struct RoomState { void Reset() {} } room_;

  bool bypass_ = true;      // 默认旁路，接入图后才由 UI 打开
  float render_gain_ = 0.0f;
  int sample_rate_ = 48000;
  int max_frames_ = 512;

  std::array<float, vs::kParamCount> param_{};
  std::array<float, vs::kParamCount> target_{};
  std::vector<float> layout_;  // 7 声道 × max_frames（planar 展开存储）
  float unused_smooth_[vs::kChannelCount][kSmoothStateCount]{};
};

}  // namespace sonicraft::dsp
