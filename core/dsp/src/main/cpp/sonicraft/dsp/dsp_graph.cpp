/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#include "sonicraft/dsp/dsp_graph.hpp"

#include <chrono>

namespace sonicraft::dsp {

DspGraph::DspGraph(int sample_rate, int channels, int max_frames)
    : sample_rate_(sample_rate), channels_(channels), max_frames_(max_frames) {
  EnsurePlanarBuffers();
}

DspGraph::~DspGraph() = default;

void DspGraph::EnsurePlanarBuffers() {
  planar_.assign(static_cast<std::size_t>(channels_) * max_frames_, 0.0f);
  channel_ptrs_.resize(channels_);
  for (int c = 0; c < channels_; ++c) {
    channel_ptrs_[c] = planar_.data() + static_cast<std::size_t>(c) * max_frames_;
  }
}

int DspGraph::AddNode(std::unique_ptr<DspNode> node) {
  const int index = static_cast<int>(nodes_.size());
  node->Prepare(sample_rate_, max_frames_);
  nodes_.push_back(std::move(node));
  return index;
}

bool DspGraph::PushCommand(const ParamCommand& cmd) { return commands_.Push(cmd); }

void DspGraph::DrainCommands() {
  ParamCommand cmd;
  while (commands_.Pop(&cmd)) {
    if (cmd.node_index < 0 || static_cast<std::size_t>(cmd.node_index) >= nodes_.size()) continue;
    DspNode* node = nodes_[static_cast<std::size_t>(cmd.node_index)].get();
    if (cmd.is_bypass) {
      node->SetBypass(cmd.value != 0.0f);
    } else {
      node->SetParam(cmd.param_id, cmd.value);
    }
  }
}

void DspGraph::ProcessInterleaved(float* interleaved, int frames) {
  const auto start = std::chrono::steady_clock::now();

  DrainCommands();

  // 交织 → planar
  for (int c = 0; c < channels_; ++c) {
    float* dst = channel_ptrs_[c];
    for (int i = 0; i < frames; ++i) dst[i] = interleaved[static_cast<std::size_t>(i) * channels_ + c];
  }

  // 逐节点就地处理（全部共享同一 planar 缓冲）
  // 逐节点耗时统计（诊断用，定位"单帧 5.3ms 超 4ms 预算"的大头）。
  // 累加自上次 ResetNodeNanos 以来的 ns；仅在诊断线程读取，实时线程只做 ++。
  if (node_ns_.size() != nodes_.size()) node_ns_.assign(nodes_.size(), 0);
  for (std::size_t ni = 0; ni < nodes_.size(); ++ni) {
    const auto n0 = std::chrono::steady_clock::now();
    nodes_[ni]->Process(channel_ptrs_.data(), frames);
    const auto n1 = std::chrono::steady_clock::now();
    node_ns_[ni] += static_cast<std::uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(n1 - n0).count());
  }

  // planar → 交织
  for (int c = 0; c < channels_; ++c) {
    const float* src = channel_ptrs_[c];
    for (int i = 0; i < frames; ++i) interleaved[static_cast<std::size_t>(i) * channels_ + c] = src[i];
  }

  const auto end = std::chrono::steady_clock::now();
  probe_.Record(static_cast<std::uint64_t>(
      std::chrono::duration_cast<std::chrono::nanoseconds>(end - start).count()));
}

void DspGraph::Reset() {
  for (auto& node : nodes_) node->Reset();
  probe_.Reset();
}

}  // namespace sonicraft::dsp
