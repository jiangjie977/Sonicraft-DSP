/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include <cstddef>
#include <memory>
#include <string_view>
#include <vector>

#include "sonicraft/dsp/dsp_node.hpp"
#include "sonicraft/dsp/param_queue.hpp"
#include "sonicraft/dsp/perf_probe.hpp"
// 编译期校验：虚拟环绕节点头文件随图一起构建（接入音频图见 native_audio_engine.cpp）
#include "sonicraft/dsp/virtual_surround.hpp"

namespace sonicraft::dsp {

/**
 * DSP 处理图：按添加顺序串联若干节点，共享同一组 planar 缓冲（节点全部就地处理）。
 *
 * 线程模型：
 *  - UI/配置线程：add_node()、push_command()
 *  - 实时线程：Process()（内部先消费参数命令，再逐节点处理）
 */
class DspGraph {
 public:
  DspGraph(int sample_rate, int channels, int max_frames);
  ~DspGraph();

  DspGraph(const DspGraph&) = delete;
  DspGraph& operator=(const DspGraph&) = delete;

  /** 非实时线程调用；返回节点索引（后续命令按索引寻址） */
  int AddNode(std::unique_ptr<DspNode> node);

  /** 任意线程调用（SPSC 生产者唯一，即配置线程）；队列满时丢弃 */
  bool PushCommand(const ParamCommand& cmd);

  /** 实时线程：处理交织双声道缓冲，in-place */
  void ProcessInterleaved(float* interleaved, int frames);

  const PerfProbe& probe() const { return probe_; }

  /** 非实时线程按索引取节点裸指针（JNI 重活直调用，如 IR 文件加载；勿在实时线程调） */
  DspNode* node_at(int index) {
    return index >= 0 && index < static_cast<int>(nodes_.size()) ? nodes_[index].get() : nullptr;
  }

  /** 逐节点累计耗时（ns），索引与 node_at 一致；诊断线程读，实时线程累加 */
  std::uint64_t node_nanos(int index) const {
    return (index >= 0 && index < static_cast<int>(node_ns_.size())) ? node_ns_[index] : 0;
  }
  void ResetNodeNanos() { node_ns_.assign(node_ns_.size(), 0); }

  int sample_rate() const { return sample_rate_; }
  int channels() const { return channels_; }
  int max_frames() const { return max_frames_; }
  std::size_t node_count() const { return nodes_.size(); }

  void Reset();

 private:
  void DrainCommands();
  void EnsurePlanarBuffers();

  int sample_rate_;
  int channels_;
  int max_frames_;
  std::vector<std::unique_ptr<DspNode>> nodes_;
  /** 逐节点累计耗时（ns），诊断用；实时线程累加，诊断线程读取 */
  std::vector<std::uint64_t> node_ns_;
  ParamQueue commands_;
  PerfProbe probe_;

  std::vector<float> planar_;             // channels * max_frames
  std::vector<float*> channel_ptrs_;      // channels
};

}  // namespace sonicraft::dsp
