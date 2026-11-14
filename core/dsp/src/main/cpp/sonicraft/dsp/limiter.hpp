/*
 * SPDX-FileCopyrightText: 2026 jiangjie
 * SPDX-License-Identifier: GPL-3.0-only
 */
#pragma once

#include "sonicraft/dsp/dsp_node.hpp"

#include <cmath>
#include <vector>

namespace sonicraft::dsp {

/**
 * Look-ahead 峰值限幅器（末级防削波安全网）。
 *
 * 结构（教科书式）：
 *   信号 → 延迟线(look-ahead) ─────────────────────┐
 *   信号 → 峰值包络(瞬时攻击+指数衰减) → 增益计算 → 包络平滑(env) →×→ 输出
 *
 *  - 增益必须对峰值包络计算而非瞬时样本：瞬时 |x| 在过零点把 target 弹回 1，
 *    造成信号频率的增益锯齿调制（深度压限时可闻抖动/杂音）。
 *
 *  - look-ahead 2.5ms：包络提前于信号知道未来峰值，允许有限速率 attack
 *    （1ms 内从 1 压到任意目标），look-ahead 深度 > attack 所需帧数，保证不超 ceiling。
 *  - attack = 每样本固定下降量（线性，非瞬时）——瞬时 attack 会让 look-ahead 失去意义；
 *    release = 一阶指数平滑（时间常数可调）。
 *  - ceiling/release 变更无需斜坡：env 本身受 attack/release 速率约束，天然无突变。
 *  - bypass 时输出原样透传，但 ring 与 env 照常推进——解除 bypass 时不产生
 *    「2.5ms 旧信号回声」。
 *
 * 参数约定（node 内自洽，经 PushCommand 的 node_index 区分节点）：
 *      param 0 = ceiling（dBFS，负值，默认 -1.0）
 *      param 1 = release（ms，默认 60）
 *      param 2 = true-peak 检测开关（0=关/采样峰值，1=开；native 默认 0，
 *                Kotlin 引擎构链后显式下发 1——真机默认启用，harness 默认链
 *                不受影响以保住 7a2 等 bit-exact 回归参照）
 *
 * true-peak 检测（2026-10-27，RocoDSP build22 砖墙限制器立项补强）：
 *  采样峰值 ≤ ceiling 不代表 DAC 理想带限重构后的真峰 ≤ ceiling——陡沿/方波类
 *  信号的 sinc 重构存在 Gibbs 带内过冲（±0.88 方波过采样峰实测 ~1.10 → 超
 *  ceiling 0.89 一档），采样峰值检测对此有洞。开启后以 4x 过采样（3 个子样本
 *  相位 × 24 tap Blackman 窗 sinc 插值核 + 原样本恒等）估计重构真峰值，包络
 *  跟随取 max(采样峰, 真峰估计)。检测滞后 ~12 帧 << look-ahead 余量
 *  （2.5ms−1ms attack = 72 帧 @48k），「look-ahead 覆盖 attack」保证不变。
 */
class LimiterNode : public DspNode {
 public:
  static constexpr int kParamCeilingDb = 0;
  static constexpr int kParamReleaseMs = 1;
  static constexpr int kParamTruePeak = 2;
  static constexpr float kLookAheadMs = 2.5f;
  static constexpr float kAttackMs = 1.0f;
  static constexpr float kDefaultCeilingDb = -1.0f;
  static constexpr float kDefaultReleaseMs = 60.0f;

  std::string_view id() const override { return "limiter"; }

  void Prepare(int sample_rate, int /*max_frames*/) override {
    sample_rate_ = sample_rate;
    look_ahead_ = static_cast<int>(kLookAheadMs * 0.001f * static_cast<float>(sample_rate));
    if (look_ahead_ < 1) look_ahead_ = 1;
    ring_capacity_ = look_ahead_ + 128;  // 稳态占用恒为 look_ahead_，+128 为跨块余量
    ring_.assign(static_cast<std::size_t>(ring_capacity_) * channels_, 0.0f);
    write_idx_.assign(channels_, 0);
    peak_env_.assign(channels_, 0.0f);
    ceiling_db_ = kDefaultCeilingDb;
    release_ms_ = kDefaultReleaseMs;
    recomputeRates();
    env_.assign(channels_, 1.0f);
    tp_hist_.assign(static_cast<std::size_t>(channels_) * kTpTaps, 0.0f);
  }

  void Reset() override {
    for (auto& v : ring_) v = 0.0f;
    for (auto& v : env_) v = 1.0f;
    for (auto& v : peak_env_) v = 0.0f;
    for (auto& v : write_idx_) v = 0;
    for (auto& v : tp_hist_) v = 0.0f;
  }

  void Process(float* const* channels, int frames) override {
    const std::size_t cap = static_cast<std::size_t>(ring_capacity_);
    const std::size_t look = static_cast<std::size_t>(look_ahead_);
    for (int c = 0; c < channels_; ++c) {
      float* ch = channels[c];
      float* ring = ring_.data() + static_cast<std::size_t>(c) * ring_capacity_;
      float env = env_[c];
      float peak = peak_env_[c];
      std::size_t w = write_idx_[c];  // 写指针每声道独立（共享会跨声道/跨块错位）
      for (int n = 0; n < frames; ++n) {
        const float x = ch[n];
        // 峰值包络跟随：瞬时攻击（精确捕捉峰值）+ 指数衰减（平滑）。
        // 增益对包络计算而非瞬时样本——瞬时值会在过零点把 target 弹回 1，
        // 产生信号频率的增益锯齿调制（深度压限时可闻抖动/杂音）。
        const float ax = std::fabs(x);
        float detect = ax;
        if (tp_enabled_) {
          // true-peak：BS.1770-4 式 4x 过采样（4 相 12 tap 多相 FIR），取 4 相
          // 幅值最大者为重构真峰估计；包络跟随取 max(采样峰, 真峰估计)。
          float* h = tp_hist_.data() + static_cast<std::size_t>(c) * kTpTaps;
          for (int i = kTpTaps - 1; i > 0; --i) h[i] = h[i - 1];
          h[0] = x;
          for (int p = 0; p < 3; ++p) {
            const float* k = kTpCoef[p];
            float acc = 0.0f;
            for (int i = 0; i < kTpTaps; ++i) acc += h[i] * k[i];
            const float a = std::fabs(acc);
            if (a > detect) detect = a;
          }
        }
        if (detect > peak) {
          peak = detect;
        } else {
          peak += (detect - peak) * release_coef_;
        }
        // 增益计算：把包络压到 ceiling 所需的增益（<=1）
        float target = ceiling_lin_ / (peak + 1e-9f);
        if (target > 1.0f) target = 1.0f;
        if (target < env) {
          env -= attack_step_;          // 有限速率下压（look-ahead 已提前覆盖此时间）
          if (env < target) env = target;
        } else {
          env += (target - env) * release_coef_;  // 一阶指数回落
        }
        // 延迟线：写入当前样本，读出 look_ahead_ 帧前的样本（写指针逐样本推进）
        ring[w] = x;
        const std::size_t read_idx = (w + cap - look) % cap;
        const float delayed = ring[read_idx];
        w = (w + 1) % cap;
        ch[n] = bypass_ ? x : delayed * env;
      }
      env_[c] = env;
      peak_env_[c] = peak;
      write_idx_[c] = w;
    }
  }

  void SetBypass(bool bypass) override { bypass_ = bypass; }
  bool Bypassed() const override { return bypass_; }

  void SetParam(int param_id, float value) override {
    if (param_id == kParamCeilingDb) {
      ceiling_db_ = value;  // 单位 dBFS
      recomputeRates();
    } else if (param_id == kParamReleaseMs) {
      release_ms_ = value;  // 单位 ms
      recomputeRates();
    } else if (param_id == kParamTruePeak) {
      tp_enabled_ = value > 0.5f;
    }
  }

  /** 外部在 AddNode 前设置声道数（与 ParametricEqNode 同一约定） */
  void set_channel_count(int channels) {
    channels_ = channels;
  }

 private:
  // 4x 过采样真峰值插值核：3 个子样本相位（d=0.25/0.5/0.75）× 24 tap
  // Blackman 窗 sinc（截止 0.25 归一，堵 4x 镜像带），每相 DC 归一。
  // 原样本点直接取 |x|（恒等精确）；d=0.5 相的 sinc 网格本性近 δ，作用有限
  // 但无害，保留以维持网格均匀。检测点滞后 ~12 帧 << look-ahead 余量 72 帧
  // （2.5ms−1ms attack @48k），「look-ahead 覆盖 attack」的不超 ceiling 保证不变。
  static constexpr int kTpTaps = 24;
  static constexpr float kTpCoef[3][kTpTaps] = {
      {0.0000000000f, 0.0001446395f, -0.0006845229f, 0.0018882074f, -0.0041852741f,
       0.0081816935f, -0.0147019688f, 0.0249430312f, -0.0409857821f, 0.0675820023f,
       -0.1202563988f, 0.2983884783f, 0.8951654349f, -0.1683589584f, 0.0826002251f,
       -0.0472912870f, 0.0278775055f, -0.0161021563f, 0.0088362290f, -0.0044739137f,
       0.0020026442f, -0.0007215241f, 0.0001516951f, 0.0000000000f},
      {0.0000000000f, 0.0000000000f, -0.0000000000f, 0.0000000000f, -0.0000000000f,
       0.0000000000f, -0.0000000000f, 0.0000000000f, -0.0000000000f, 0.0000000000f,
       -0.0000000000f, 0.0000000000f, 1.0000000000f, 0.0000000000f, -0.0000000000f,
       0.0000000000f, -0.0000000000f, 0.0000000000f, -0.0000000000f, 0.0000000000f,
       -0.0000000000f, -0.0000000000f, -0.0000000000f, -0.0000000000f},
      {-0.0000000000f, -0.0001403435f, 0.0006611775f, -0.0018136995f, 0.0039922800f,
       -0.0077349641f, 0.0137344936f, -0.0229156862f, 0.0367218788f, -0.0580670438f,
       0.0949758205f, -0.1817952908f, 0.9089764540f, 0.2849274614f, -0.1078387957f,
       0.0567519946f, -0.0320819606f, 0.0180717021f, -0.0097527809f, 0.0048794533f,
       -0.0021647381f, 0.0007745222f, -0.0001619348f, -0.0000000000f},
  };

  void recomputeRates() {
    ceiling_lin_ = std::pow(10.0f, ceiling_db_ / 20.0f);
    attack_step_ = 1.0f / (kAttackMs * 0.001f * static_cast<float>(sample_rate_));
    release_coef_ = 1.0f - std::exp(-1.0f / (release_ms_ * 0.001f * static_cast<float>(sample_rate_)));
  }

  int sample_rate_ = 48000;
  int channels_ = 2;
  bool bypass_ = true;  // 默认透传；UI 持久化状态由引擎 Running 后补发
  int look_ahead_ = 120;
  int ring_capacity_ = 248;
  std::vector<std::size_t> write_idx_;  // 每声道独立写指针
  float ceiling_db_ = kDefaultCeilingDb;
  float release_ms_ = kDefaultReleaseMs;
  float ceiling_lin_ = 0.891f;   // 10^(-1/20)
  float attack_step_ = 0.0f;
  float release_coef_ = 0.0f;
  std::vector<float> ring_;      // [channel][ring_capacity_]
  std::vector<float> env_;       // 每声道增益包络
  std::vector<float> peak_env_;  // 每声道峰值包络（瞬时攻击 + 指数衰减）
  bool tp_enabled_ = false;      // true-peak 检测（param 2；Kotlin 构链后显式开）
  std::vector<float> tp_hist_;   // [channel][kTpTaps] true-peak FIR 历史
};

}  // namespace sonicraft::dsp
