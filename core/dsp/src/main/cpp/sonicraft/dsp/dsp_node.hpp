/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include <cstddef>
#include <string_view>

namespace sonicraft::dsp {

/**
 * 实时音频处理节点接口。
 *
 * 实时性契约（违反会造成爆音）：
 *  - prepare()/reset() 仅在非实时线程调用，允许分配内存与预计算系数；
 *  - process() 在实时线程调用：禁止内存分配、禁止加锁、禁止系统调用/日志；
 *  - 参数更新通过 DspGraph::push_command() 投递，实时线程内消费，天然线程安全；
 *  - 所有节点为就地处理（in-place），可安全共享同一组 planar 缓冲。
 */
class DspNode {
 public:
  virtual ~DspNode() = default;

  virtual std::string_view id() const = 0;

  virtual void Prepare(int sample_rate, int max_frames) = 0;

  virtual void Reset() = 0;

  /** 就地处理；channels 为构造时确定的固定声道数，frames <= max_frames */
  virtual void Process(float* const* channels, int frames) = 0;

  virtual void SetBypass(bool bypass) = 0;
  virtual bool Bypassed() const = 0;

  /**
   * 实时参数更新。命令由 DspGraph::PushCommand 经 SPSC 队列投递，在实时线程
   * DrainCommands 中消费（不是调用方线程），天然线程安全。节点内部对已更新的目标值
   * 做平滑斜坡（逐样本/逐块逼近），禁止在 process() 内产生参数突变（爆音）。
   * 默认 no-op：既有无参数节点（PassThroughNode）无需改动即可编译。
   */
  virtual void SetParam(int param_id, float value) {}
};

}  // namespace sonicraft::dsp
